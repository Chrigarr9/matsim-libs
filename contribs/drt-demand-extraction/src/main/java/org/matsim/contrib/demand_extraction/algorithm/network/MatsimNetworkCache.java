package org.matsim.contrib.demand_extraction.algorithm.network;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;
import org.matsim.contrib.demand_extraction.algorithm.util.StringUtils;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility;
import org.matsim.core.router.costcalculators.TravelDisutilityFactory;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.LeastCostPathCalculator.Path;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.google.inject.name.Names;


/**
 * MATSim-native network implementation with time-binned lazy caching.
 * 
 * DESIGN NOTE: This implementation uses a separate cache layer on top of the router.
 * An alternative design would be to implement a caching LeastCostPathCalculator decorator
 * that wraps the base router (similar to caching patterns used elsewhere in MATSim).
 * The decorator approach would:
 * - Check cache before calling router
 * - Store results after router call
 * - Support mode-specific caching (limited to car mode for DRT)
 * - Be more modular and reusable
 * 
 * Current implementation is acceptable but could be refactored to use the decorator pattern
 * if similar caching is needed elsewhere in the codebase.
 * 
 * Uses MATSim's routing infrastructure (LeastCostPathCalculator) to compute
 * link-to-link travel times and distances. Results are cached with time binning
 * to balance accuracy vs memory/performance.
 * 
 * Time binning groups departure times into bins (e.g., 15-minute intervals),
 * allowing cache reuse for queries in the same bin while maintaining
 * time-dependent routing accuracy.
 */
@Singleton
public class MatsimNetworkCache {
	
	private static final Logger log = LogManager.getLogger(MatsimNetworkCache.class);
	
	private final Network network;
	private final Provider<LeastCostPathCalculator> routerProvider;
	private final ThreadLocal<LeastCostPathCalculator> threadLocalRouter;
	private final boolean useSharedDeterministicRouter;
	private final boolean quantizeDeterministicSegments;
	private final LeastCostPathCalculator sharedRouter;
	private final Object routerLock = new Object();
	private final TravelTime travelTime;
	private final TravelDisutility travelDisutility;
	private final int timeBinSize;
	
	// Dummy person and vehicle for generic routing (required by router)
	private final Person dummyPerson;
	private final Vehicle dummyVehicle;

	// Cache: (originLinkId, destLinkId, timeBin) -> TravelSegment
	private final ConcurrentHashMap<CacheKey, TravelSegment> cache = new ConcurrentHashMap<>();
	
	// Track routing failures for summary logging (thread-safe)
	private final AtomicInteger routingFailures = new AtomicInteger(0);
	private final AtomicInteger totalRoutingAttempts = new AtomicInteger(0);
	
	@Inject
	public MatsimNetworkCache(
			Network network,
			ExMasConfigGroup config,
			Injector injector) {
		// Inject DRT-specific components (router, travel time, travel disutility)
		// These may differ from car mode for:
		// - Different toll/pricing structures (e.g., DRT exempt from congestion
		// charges)
		// - Access to dedicated lanes (e.g., bus lanes, HOV lanes)
		// - Special routing permissions on restricted roads
		// Falls back to car mode if DRT-specific components not bound

		String drtMode = config.getDrtMode();
		String drtRouterName = "direct" + StringUtils.capitalize(drtMode) + "Router";

		// Get DRT-specific router provider (uses filtered network)
		this.routerProvider = injector.getProvider(Key.get(LeastCostPathCalculator.class, Names.named(drtRouterName)));
		this.threadLocalRouter = ThreadLocal.withInitial(routerProvider::get);
		// In deterministic mode, avoid subtle per-instance differences from multiple router instances.
		// Some router implementations (e.g. SpeedyALT) can vary slightly between instances.
		// We serialize access through a single shared instance to make cached values invariant
		// to thread scheduling.
		this.useSharedDeterministicRouter = config.isUseDeterministicNetworkRouting();
		// Additionally, quantize segment metrics to avoid tiny run-to-run floating drift
		// (e.g., on multithreaded travel-time aggregation) that can flip 2-decimal CSV rounding.
		this.quantizeDeterministicSegments = config.isUseDeterministicNetworkRouting();
		this.sharedRouter = useSharedDeterministicRouter ? routerProvider.get() : null;

		// Try to get DRT-specific TravelTime, fall back to car
		TravelTime drtTravelTime;
		try {
			drtTravelTime = injector.getInstance(Key.get(TravelTime.class, Names.named(drtMode)));
		} catch (Exception e) {
			// DRT-specific TravelTime not bound, use car
			drtTravelTime = injector.getInstance(Key.get(TravelTime.class, Names.named(TransportMode.car)));
		}

		// Use mode-specific TravelDisutility which includes:
		// - Travel time costs
		// - Distance costs
		// - Monetary distance rates (tolls, road pricing)
		//
		// NOTE: DemandExtractionModule sets config.routing().routingRandomness = 0
		// which ensures deterministic routing while preserving toll/cost calculations.
		// If useDeterministicNetworkRouting is true, we use OnlyTimeDependentTravelDisutility
		// which ignores distance/monetary costs entirely (useful for debugging or specific scenarios).
		TravelDisutility disutility;
		if (config.isUseDeterministicNetworkRouting()) {
			log.info("Using time-only network routing (ignores tolls and distance costs)");
			disutility = new OnlyTimeDependentTravelDisutility(drtTravelTime);
		} else {
			// Use mode-specific TravelDisutility (captures tolls, deterministic via routingRandomness=0)
			TravelDisutilityFactory drtDisutilityFactory;
			try {
				drtDisutilityFactory = injector.getInstance(Key.get(TravelDisutilityFactory.class, Names.named(drtMode)));
			} catch (Exception e) {
				// DRT-specific TravelDisutility not bound, use car
				drtDisutilityFactory = injector.getInstance(Key.get(TravelDisutilityFactory.class, Names.named(TransportMode.car)));
			}
			disutility = drtDisutilityFactory.createTravelDisutility(drtTravelTime);
			log.info("Using full network routing with tolls (deterministic via routingRandomness=0, type: {})",
					disutility.getClass().getSimpleName());
		}

		this.network = network;
		this.travelTime = drtTravelTime;
		this.travelDisutility = disutility;
		this.timeBinSize = config.getNetworkTimeBinSize();

		// Create dummy person and vehicle for generic routing
		// These are required by the router/travel time/disutility calculations
		this.dummyPerson = PopulationUtils.getFactory().createPerson(Id.createPersonId("exmas_dummy"));
		VehicleType dummyType = VehicleUtils.createVehicleType(Id.create("car", VehicleType.class));
		this.dummyVehicle = VehicleUtils.createVehicle(Id.createVehicleId("exmas_dummy_vehicle"), dummyType);
	}
	
	/**
	 * Get travel segment between links at specified departure time.
	 * Results are cached per time bin for efficiency.
	 *
	 * IMPORTANT: The cache key includes the time bin, not the exact departure time.
	 * To keep results deterministic (especially under parallel execution), we compute
	 * the cached segment for a bin using a canonical departure time: the midpoint of
	 * the bin. Otherwise, the "first" caller within a bin would determine the cached
	 * value, which can vary with thread scheduling.
	 * 
	 * @param originLinkId origin link
	 * @param destLinkId destination link
	 * @param departureTime departure time (seconds since midnight)
	 * @return travel segment with metrics, or infinity segment if unreachable
	 */
	public TravelSegment getSegment(Id<Link> originLinkId, Id<Link> destLinkId, double departureTime) {
		// Calculate time bin
		int timeBin = (int) (departureTime / timeBinSize);
		// Canonical departure time for this bin: midpoint
		double canonicalDepartureTime = (timeBin + 0.5) * timeBinSize;
		
		CacheKey key = new CacheKey(originLinkId, destLinkId, timeBin);
		
		// Use computeIfAbsent for atomic cache operations
		// This ensures only ONE thread computes the segment for a given key,
		// preventing race conditions in the SpeedyALT router
		return cache.computeIfAbsent(key, k -> computeSegment(originLinkId, destLinkId, canonicalDepartureTime));
	}
	
	/**
	 * Check if connection exists between links.
	 */
	public boolean hasConnection(Id<Link> originLinkId, Id<Link> destLinkId, double departureTime) {
		return getSegment(originLinkId, destLinkId, departureTime).isReachable();
	}

	/**
	 * Pre-populate cache with specific O-D pairs only.
	 * Useful for filtering cache to only relevant connections.
	 * 
	 * @param connections list of origin-destination link ID pairs
	 * @param departureTime reference departure time for routing
	 */
	public void preloadConnections(List<Pair<Id<Link>, Id<Link>>> connections, double departureTime) {
		for (Pair<Id<Link>, Id<Link>> conn : connections) {
			getSegment(conn.getFirst(), conn.getSecond(), departureTime);
		}
	}
	
	/**
	 * Export cache to CSV file.
	 * Format: originLinkId, destLinkId, timeBin, travelTime, distance, utility
	 */
	public void exportCache(String filepath) throws IOException {
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(filepath))) {
			writer.write("originLinkId,destLinkId,timeBin,travelTime,distance,utility\n");
			
			for (Map.Entry<CacheKey, TravelSegment> entry : cache.entrySet()) {
				CacheKey key = entry.getKey();
				TravelSegment seg = entry.getValue();
				
				if (!seg.isReachable()) {
					continue; // Skip unreachable segments
				}
				
				writer.write(String.format("%s,%s,%d,%.2f,%.2f,%.4f\n",
						key.origin, key.destination, key.timeBin,
						seg.getTravelTime(), seg.getDistance(), seg.getNetworkUtility()));
			}
		}
	}

	/**
	 * Export filtered connection cache to CSV file for ExMasCommuter (Python).
	 * Format: origin,destination,time_bin,travel_time,distance
	 * 
	 * @param filepath output file path
	 * @param connectionKeys set of "origin_destination_timeBin" string keys to export
	 */
	public void exportFilteredConnectionCache(String filepath, java.util.Set<String> connectionKeys) throws IOException {
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(filepath))) {
			writer.write("origin,destination,time_bin,travel_time,distance\n");

			// Deterministic output: sort keys and write in that order.
			// Avoids nondeterminism from HashSet/ConcurrentHashMap iteration.
			List<String> sortedKeys = connectionKeys.stream().sorted().toList();
			for (String lookupKey : sortedKeys) {
				int lastUnderscore = lookupKey.lastIndexOf('_');
				int secondLastUnderscore = lookupKey.lastIndexOf('_', lastUnderscore - 1);
				if (lastUnderscore < 0 || secondLastUnderscore < 0) {
					continue;
				}

				String originStr = lookupKey.substring(0, secondLastUnderscore);
				String destStr = lookupKey.substring(secondLastUnderscore + 1, lastUnderscore);
				int timeBin;
				try {
					timeBin = Integer.parseInt(lookupKey.substring(lastUnderscore + 1));
				} catch (NumberFormatException e) {
					continue;
				}

				Id<Link> origin = Id.createLinkId(originStr);
				Id<Link> destination = Id.createLinkId(destStr);
				CacheKey key = new CacheKey(origin, destination, timeBin);

				TravelSegment seg = cache.get(key);
				if (seg == null) {
					// Fallback: ensure the segment exists (should already be routed during predecessor calc).
					double canonicalDepartureTime = (timeBin + 0.5) * timeBinSize;
					seg = getSegment(origin, destination, canonicalDepartureTime);
				}
				if (seg == null || !seg.isReachable()) {
					continue;
				}

				writer.write(String.format(java.util.Locale.US, "%s,%s,%d,%.2f,%.2f\n",
						originStr, destStr, timeBin, seg.getTravelTime(), seg.getDistance()));
			}
		}
	}
	
	/**
	 * Import cache from CSV file.
	 * Only imports entries for links that exist in current network.
	 */
	public void importCache(String filepath) throws IOException {
		try (BufferedReader reader = new BufferedReader(new FileReader(filepath))) {
			reader.readLine(); // Skip header
			
			String line;
			while ((line = reader.readLine()) != null) {
				String[] parts = line.split(",");
				if (parts.length != 6) continue;
				
				try {
					Id<Link> origin = Id.createLinkId(parts[0]);
					Id<Link> dest = Id.createLinkId(parts[1]);
					int timeBin = Integer.parseInt(parts[2]);
					double tt = Double.parseDouble(parts[3]);
					double dist = Double.parseDouble(parts[4]);
					double util = Double.parseDouble(parts[5]);
					
					// Only import if links exist in network
					if (network.getLinks().containsKey(origin) && network.getLinks().containsKey(dest)) {
						CacheKey key = new CacheKey(origin, dest, timeBin);
						TravelSegment seg = new TravelSegment(tt, dist, util);
						cache.put(key, seg);
					}
				} catch (Exception e) {
					// Skip invalid entries
				}
			}
		}
	}
	
	/**
	 * Clear the cache. Useful for memory management or when network conditions change.
	 */
	public void clearCache() {
		cache.clear();
	}
	
	/**
	 * Get current cache size (number of cached segments).
	 */
	public int getCacheSize() {
		return cache.size();
	}
	
	/**
	 * Get routing statistics.
	 * @return array [totalAttempts, failures, successRate]
	 */
	public int[] getRoutingStatistics() {
		int total = totalRoutingAttempts.get();
		int failures = routingFailures.get();
		return new int[]{total, failures};
	}
	
	/**
	 * Log routing statistics summary.
	 * Call this after demand extraction to get an overview of routing success/failure rates.
	 */
	public void logRoutingStatistics() {
		int total = totalRoutingAttempts.get();
		int failures = routingFailures.get();
		
		if (total == 0) {
			log.info("Network cache: No routing attempts made");
			return;
		}
		
		double failureRate = (100.0 * failures) / total;
		double successRate = 100.0 - failureRate;
		
		log.info(String.format("Network cache statistics:"));
		log.info(String.format("  Total routing attempts: %,d", total));
		log.info(String.format("  Successful routes: %,d (%.1f%%)", total - failures, successRate));
		log.info(String.format("  Failed routes: %,d (%.1f%%)", failures, failureRate));
		log.info(String.format("  Cache size: %,d entries", cache.size()));
		
		if (failureRate > 10.0) {
			log.warn(String.format("High routing failure rate (%.1f%%). This may indicate network connectivity issues.", failureRate));
			log.warn("Consider running NetworkUtils.cleanNetwork() or checking network mode assignments.");
		}
	}
	
	/**
	 * Reset routing statistics counters.
	 * Useful when reusing the cache across multiple iterations.
	 */
	public void resetStatistics() {
		totalRoutingAttempts.set(0);
		routingFailures.set(0);
	}
	
	/**
	 * Simple pair class for O-D connections.
	 */
	public static class Pair<A, B> {
		private final A first;
		private final B second;
		
		public Pair(A first, B second) {
			this.first = first;
			this.second = second;
		}
		
		public A getFirst() { return first; }
		public B getSecond() { return second; }
	}
	
	// Use a thread-local router to allow parallel routing on cache misses.
	// SpeedyALT is not thread-safe across threads, but separate instances are safe.
	private TravelSegment computeSegment(Id<Link> originLinkId, Id<Link> destLinkId, double departureTime) {
		totalRoutingAttempts.incrementAndGet();
		
		Link originLink = network.getLinks().get(originLinkId);
		Link destLink = network.getLinks().get(destLinkId);
		
		if (originLink == null || destLink == null) {
			// Links don't exist in network
			routingFailures.incrementAndGet();
			return createInfinitySegment();
		}
		
		if (originLinkId.equals(destLinkId)) {
			// Same link - only need to traverse the link itself
			// Use actual travel time (respects simulation state), not freespeed
			double linkTravelTime = travelTime.getLinkTravelTime(originLink, departureTime, dummyPerson, dummyVehicle);
			double linkDistance = originLink.getLength();
			// Disutility for traversing the link
			double linkDisutility = travelDisutility.getLinkTravelDisutility(originLink, departureTime, dummyPerson,
					dummyVehicle);
			double utility = -linkDisutility;
			if (quantizeDeterministicSegments) {
				linkTravelTime = quantizeSecondsToTenth(linkTravelTime);
				linkDistance = quantizeMetersToCentimeter(linkDistance);
				utility = quantizeUtilityTo1e4(utility);
			}
			return new TravelSegment(linkTravelTime, linkDistance, utility);
		}
		try {
				// Use link-based routing (new non-deprecated method)
				// This properly handles turn restrictions and considers full link-to-link
				// travel
				// Use dummy person/vehicle for generic routing (required by TravelDisutility)
				Path path;
				if (useSharedDeterministicRouter) {
					synchronized (routerLock) {
						path = sharedRouter.calcLeastCostPath(
								originLink,
								destLink,
								departureTime,
								dummyPerson,
								dummyVehicle);
					}
				} else {
					path = threadLocalRouter.get().calcLeastCostPath(
				originLink,
				destLink,
				departureTime,
				dummyPerson,
				dummyVehicle);
				}
			
			if (path == null || path.links.isEmpty()) {
				// No path found - track failure
				routingFailures.incrementAndGet();
				return createInfinitySegment();
			}
			
			// path.travelTime already includes origin and destination links
			// path.links already includes all traversed links
			// Router implementations handle link-to-link travel correctly
			double tt = path.travelTime;
			double dist = path.links.stream().mapToDouble(Link::getLength).sum();

			// Network utility: negative of generalized cost (disutility)
			// This allows sorting by "best" routes (higher utility = better)
			double disutility = path.travelCost;
			double utility = -disutility;

			if (quantizeDeterministicSegments) {
				tt = quantizeSecondsToTenth(tt);
				dist = quantizeMetersToCentimeter(dist);
				utility = quantizeUtilityTo1e4(utility);
			}
			
			return new TravelSegment(tt, dist, utility);
			
		} catch (OutOfMemoryError e) {
			// SpeedyALT bug: infinite loop in path construction for some link pairs
			// Treat as routing failure and return infinity segment
			log.warn("OutOfMemoryError during routing from link {} to link {} - treating as unreachable",
					originLinkId, destLinkId);
			routingFailures.incrementAndGet();
			return createInfinitySegment();
		} catch (Exception e) {
			// Any other routing exception - treat as failure
			log.warn("Routing exception from link {} to link {}: {} - treating as unreachable",
					originLinkId, destLinkId, e.getMessage());
			routingFailures.incrementAndGet();
			return createInfinitySegment();
		}
	}
	
	private TravelSegment createInfinitySegment() {
		return TravelSegment.unreachable();
	}

	private static double quantizeSecondsToTenth(double seconds) {
		return quantizeTowardZero(seconds, 10.0);
	}

	private static double quantizeMetersToCentimeter(double meters) {
		return quantizeTowardZero(meters, 100.0);
	}

	private static double quantizeUtilityTo1e4(double utility) {
		return quantizeTowardZero(utility, 10000.0);
	}

	/**
	 * Quantizes a value by truncating toward zero on a fixed grid.
	 * <p>
	 * We intentionally avoid {@code Math.round(...)} here because tiny run-to-run
	 * floating drift can flip values across a rounding threshold (e.g. ...3.3499999
	 * vs ...3.3500001 when quantizing to 0.1), which then propagates into CSV output
	 * and breaks strict byte-identical determinism.
	 */
	private static double quantizeTowardZero(double value, double scale) {
		if (!Double.isFinite(value)) {
			return value;
		}

		double scaled = value * scale;
		// small epsilon to counter binary representation errors around integer boundaries
		double eps = 1e-9;

		double truncated = scaled >= 0.0 ? Math.floor(scaled + eps) : Math.ceil(scaled - eps);
		return truncated / scale;
	}
	
	/**
	 * Cache key for link-to-link travel at specific time bin.
	 */
	private static class CacheKey {
		private final Id<Link> origin;
		private final Id<Link> destination;
		private final int timeBin;
		
		CacheKey(Id<Link> origin, Id<Link> destination, int timeBin) {
			this.origin = origin;
			this.destination = destination;
			this.timeBin = timeBin;
		}
		
		@Override
		public boolean equals(Object obj) {
			if (this == obj) return true;
			if (!(obj instanceof CacheKey)) return false;
			CacheKey other = (CacheKey) obj;
			return timeBin == other.timeBin &&
					origin.equals(other.origin) &&
					destination.equals(other.destination);
		}
		
		@Override
		public int hashCode() {
			int result = origin.hashCode();
			result = 31 * result + destination.hashCode();
			result = 31 * result + timeBin;
			return result;
		}
	}
}
