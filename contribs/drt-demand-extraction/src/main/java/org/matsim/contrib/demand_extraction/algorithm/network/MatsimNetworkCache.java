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
import java.util.concurrent.atomic.AtomicLong;

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
import org.matsim.core.router.speedy.LeastCostPathTree;
import org.matsim.core.router.speedy.SpeedyALTFactory;
import org.matsim.core.router.speedy.SpeedyGraph;
import org.matsim.core.router.speedy.SpeedyGraphBuilder;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.LeastCostPathCalculator.Path;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.utils.misc.OptionalTime;
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

	// Tracks (originLinkId, timeBin) pairs for which SSSP was actually completed.
	// batchPrecompute skips only when the SAME origin+timeBin was already SSSP'd — not
	// when an individual getSegment call happened to populate one destination of that origin.
	// Using ConcurrentHashMap as a set (value = Boolean.TRUE).
	private final ConcurrentHashMap<SsspKey, Boolean> ssspCompleted = new ConcurrentHashMap<>();

	// SSSP tree for batch precomputation (one per thread, NOT thread-safe)
	private final ThreadLocal<LeastCostPathTree> threadLocalTree;

	// Batch precompute statistics
	private final AtomicInteger batchTreesComputed = new AtomicInteger(0);
	private final AtomicInteger batchTreesSkipped = new AtomicInteger(0);
	private final AtomicLong batchSegmentsPopulated = new AtomicLong(0);

	// getSegment call statistics
	// cacheGetAttempts counts every call; totalRoutingAttempts (below) counts only cache misses
	// (calls that fell through to computeSegment / SpeedyALT). The difference is cache hits.
	private final AtomicLong cacheGetAttempts = new AtomicLong(0);

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

		// Build SpeedyGraph eagerly — must happen before Id caches are reset at end of simulation
		SpeedyGraph speedyGraph = SpeedyGraphBuilder.build(network);
		this.threadLocalTree = ThreadLocal.withInitial(() ->
			new LeastCostPathTree(speedyGraph, this.travelTime, this.travelDisutility));
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
		cacheGetAttempts.incrementAndGet();

		// Calculate time bin
		int timeBin = (int) (departureTime / timeBinSize);
		// Canonical departure time for this bin: midpoint
		double canonicalDepartureTime = (timeBin + 0.5) * timeBinSize;

		CacheKey key = new CacheKey(originLinkId, destLinkId, timeBin);

		// Use computeIfAbsent for atomic cache operations
		// This ensures only ONE thread computes the segment for a given key,
		// preventing race conditions in the SpeedyALT router
		return cache.computeIfAbsent(key, k ->
				computeSegment(originLinkId, destLinkId, canonicalDepartureTime));
	}

	/**
	 * Check if connection exists between links.
	 */
	public boolean hasConnection(Id<Link> originLinkId, Id<Link> destLinkId, double departureTime) {
		return getSegment(originLinkId, destLinkId, departureTime).isReachable();
	}

	/**
	 * Batch-precompute travel segments from a single origin link to multiple target links
	 * using a single-source shortest path tree (SSSP). One Dijkstra pass from the origin
	 * populates the cache for all targets, replacing N individual point-to-point routing calls.
	 *
	 * @param fromLinkId source link
	 * @param departureTime departure time (seconds since midnight)
	 * @param toLinkIds target links to populate
	 * @param maxTravelTimeSeconds early termination bound — Dijkstra stops exploring nodes
	 *        beyond this travel time from the source
	 */
	@SuppressWarnings("unchecked")
	public void batchPrecompute(Id<Link> fromLinkId, double departureTime, Id<Link>[] toLinkIds,
	                            double maxTravelTimeSeconds) {
		if (toLinkIds.length == 0) return;

		Link fromLink = network.getLinks().get(fromLinkId);
		if (fromLink == null) return;

		int timeBin = (int)(departureTime / timeBinSize);
		double canonicalDepartureTime = (timeBin + 0.5) * timeBinSize;

		// Skip only when a full SSSP was already completed for this (fromLink, timeBin).
		// Do NOT use the main segment cache as a proxy: individual getSegment calls can
		// populate (fromLink, someDestination, timeBin) even though no SSSP was run,
		// causing false-positive skips that leave other destinations un-precomputed and
		// routed by the individual LeastCostPathCalculator instead of the SSSP tree.
		// Different implementations (SpeedyALT vs LeastCostPathTree) give slightly
		// different travel times for some segments, flipping borderline pair feasibility.
		SsspKey ssspKey = new SsspKey(fromLinkId, timeBin);
		if (ssspCompleted.containsKey(ssspKey)) {
			batchTreesSkipped.incrementAndGet();
			return;
		}

		LeastCostPathTree tree = threadLocalTree.get();
		LeastCostPathTree.StopCriterion stopCriterion =
			new LeastCostPathTree.TravelTimeStopCriterion(maxTravelTimeSeconds);
		tree.calculate(fromLink, canonicalDepartureTime, dummyPerson, dummyVehicle, stopCriterion);
		ssspCompleted.put(ssspKey, Boolean.TRUE);
		batchTreesComputed.incrementAndGet();

		for (Id<Link> toLinkId : toLinkIds) {
			CacheKey key = new CacheKey(fromLinkId, toLinkId, timeBin);
			if (cache.containsKey(key)) continue;

			if (fromLinkId.equals(toLinkId)) {
				cache.computeIfAbsent(key, k -> computeSegment(fromLinkId, toLinkId, canonicalDepartureTime));
				batchSegmentsPopulated.incrementAndGet();
				continue;
			}

			Link toLink = network.getLinks().get(toLinkId);
			if (toLink == null) {
				cache.put(key, TravelSegment.unreachable());
				batchSegmentsPopulated.incrementAndGet();
				continue;
			}

			int toNodeIdx = toLink.getFromNode().getId().index();
			OptionalTime time = tree.getTime(toNodeIdx);

			if (time.isDefined()) {
				double tt = time.seconds() - canonicalDepartureTime;
				double dist = tree.getDistance(toNodeIdx);
				double utility = -tree.getCost(toNodeIdx);
				if (quantizeDeterministicSegments) {
					tt = quantizeSecondsToTenth(tt);
					dist = quantizeMetersToCentimeter(dist);
					utility = quantizeUtilityTo1e4(utility);
				}
				cache.put(key, new TravelSegment(tt, dist, utility));
				batchSegmentsPopulated.incrementAndGet();
			}
			// else: SSSP stop-criterion didn't reach this node within the bound — leave the
			// key absent so a later point-to-point getSegment computes the true path on demand.
			// Caching unreachable here would be a false negative (path exists, just beyond bound)
			// and silently breaks downstream extenders that need the segment at higher degree.
		}
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
	 * Export connection cache to CSV file for ExMasCommuter (Python).
	 * Format: origin,destination,time_bin,travel_time,distance
	 *
	 * @param filepath output file path
	 * @param connectionKeys if non-null, export only these "origin_destination_timeBin" keys;
	 *                       if null, export all cached connections
	 */
	public void exportConnectionCache(String filepath, java.util.Set<String> connectionKeys) throws IOException {
		if (connectionKeys == null) {
			exportAllEntries(filepath);
		} else {
			exportFilteredEntries(filepath, connectionKeys);
		}
	}

	private void exportAllEntries(String filepath) throws IOException {
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(filepath))) {
			writer.write("origin,destination,time_bin,travel_time,distance\n");

			// Deterministic output: sort keys lexicographically
			List<CacheKey> sortedKeys = cache.keySet().stream()
					.sorted((a, b) -> {
						int cmp = a.origin.toString().compareTo(b.origin.toString());
						if (cmp != 0) return cmp;
						cmp = a.destination.toString().compareTo(b.destination.toString());
						if (cmp != 0) return cmp;
						return Integer.compare(a.timeBin, b.timeBin);
					})
					.toList();

			int exported = 0;
			for (CacheKey key : sortedKeys) {
				TravelSegment seg = cache.get(key);
				if (seg == null || !seg.isReachable()) {
					continue;
				}
				writer.write(String.format(java.util.Locale.US, "%s,%s,%d,%.2f,%.2f\n",
						key.origin, key.destination, key.timeBin,
						seg.getTravelTime(), seg.getDistance()));
				exported++;
			}
			log.info("Exported connection cache (all): {} reachable entries (from {} total)",
					exported, cache.size());
		}
	}

	private void exportFilteredEntries(String filepath, java.util.Set<String> connectionKeys) throws IOException {
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(filepath))) {
			writer.write("origin,destination,time_bin,travel_time,distance\n");

			// Deterministic output: sort keys
			List<String> sortedKeys = connectionKeys.stream().sorted().toList();
			int exported = 0;
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
					// Fallback: route on demand (should already be cached from predecessor calc)
					double canonicalDepartureTime = (timeBin + 0.5) * timeBinSize;
					seg = getSegment(origin, destination, canonicalDepartureTime);
				}
				if (seg == null || !seg.isReachable()) {
					continue;
				}

				writer.write(String.format(java.util.Locale.US, "%s,%s,%d,%.2f,%.2f\n",
						originStr, destStr, timeBin, seg.getTravelTime(), seg.getDistance()));
				exported++;
			}
			log.info("Exported connection cache (filtered): {} entries (from {} requested)",
					exported, connectionKeys.size());
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
	 * Get the underlying network.
	 * Used by stop-based pooling to look up links by ID.
	 */
	public Network getNetwork() {
		return network;
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
		long gets = cacheGetAttempts.get();
		long speedyAlt = totalRoutingAttempts.get();
		long failures = routingFailures.get();
		long hits = gets - speedyAlt;
		int trees = batchTreesComputed.get();
		int treesSkipped = batchTreesSkipped.get();
		long ssspSegs = batchSegmentsPopulated.get();
		long cacheSize = cache.size();

		if (gets == 0 && trees == 0) {
			log.info("Network cache: No routing activity");
			return;
		}

		log.info("Network cache statistics:");
		log.info("  getSegment calls:  {}", String.format("%,d", gets));
		if (gets > 0) {
			log.info("    cache hits:      {}  ({}%)",
					String.format("%,d", hits), String.format("%.1f", 100.0 * hits / gets));
			log.info("    SpeedyALT:       {}  ({}%)  [{} failures]",
					String.format("%,d", speedyAlt), String.format("%.1f", 100.0 * speedyAlt / gets),
					String.format("%,d", failures));
		}
		log.info("  batchPrecompute:   {} trees  ({} skipped)  -> {} segments cached",
				String.format("%,d", trees), String.format("%,d", treesSkipped),
				String.format("%,d", ssspSegs));
		log.info("  Cache total size:  {} entries", String.format("%,d", cacheSize));
		if (gets > 0 && ssspSegs > 0) {
			log.info("  SSSP segment reuse estimate: {} SSSP entries / {} hits  " +
					"(upper bound; same entry may be hit many times)",
					String.format("%,d", ssspSegs), String.format("%,d", hits));
		}

		if (speedyAlt > 0 && (100.0 * failures / speedyAlt) > 10.0) {
			log.warn("High SpeedyALT failure rate ({}%). Check network connectivity.",
					String.format("%.1f", 100.0 * failures / speedyAlt));
		}
	}
	
	/**
	 * Reset routing statistics counters.
	 * Useful when reusing the cache across multiple iterations.
	 */
	public void resetStatistics() {
		cacheGetAttempts.set(0);
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
	
	// ── Test support ─────────────────────────────────────────────────────────

	/**
	 * Creates a lightweight {@code MatsimNetworkCache} for unit tests.
	 *
	 * <p>Bypasses the Guice-injected constructor. All routing fields are null;
	 * callers must pre-populate every required segment via {@link #putForTesting}
	 * so that {@link #getSegment} never falls through to {@code computeSegment}.
	 * {@code timeBinSize} is set to {@code Integer.MAX_VALUE} so that any
	 * departure time maps to time bin 0, matching the keys written by
	 * {@code putForTesting}.
	 *
	 * <p>Intended for use in JUnit tests only — not for production code.
	 */
	static MatsimNetworkCache forTesting() {
		return new MatsimNetworkCache();
	}

	/**
	 * Pre-populate a cache entry for unit tests (time bin = 0).
	 *
	 * <p>Must only be called on instances created via {@link #forTesting()}.
	 * Avoids touching the router, so no MATSim infrastructure is required.
	 *
	 * <p>Intended for use in JUnit tests only — not for production code.
	 */
	void putForTesting(Id<Link> origin, Id<Link> dest, TravelSegment seg) {
		cache.put(new CacheKey(origin, dest, 0), seg);
	}

	private MatsimNetworkCache() {
		this.network = null;
		this.routerProvider = null;
		this.threadLocalRouter = null;
		this.threadLocalTree = null;
		this.useSharedDeterministicRouter = false;
		this.quantizeDeterministicSegments = false;
		this.sharedRouter = null;
		this.travelTime = null;
		this.travelDisutility = null;
		this.timeBinSize = Integer.MAX_VALUE; // any departure time → bin 0
		this.dummyPerson = null;
		this.dummyVehicle = null;
	}

	/**
	 * Test constructor with real routing capability.
	 * Bypasses Guice injection, uses provided components directly.
	 * Defaults to Dijkstra for cache-miss point-to-point routing.
	 */
	MatsimNetworkCache(Network network, TravelTime travelTime, TravelDisutility travelDisutility, int timeBinSize) {
		this(network, travelTime, travelDisutility, timeBinSize, /* useSpeedyAlt= */ false);
	}

	/**
	 * Test constructor with selectable cache-miss router.
	 * When {@code useSpeedyAlt=true}, mirrors the production routing combination
	 * (SpeedyALT for point-to-point cache miss + LeastCostPathTree for batch SSSP).
	 */
	MatsimNetworkCache(Network network, TravelTime travelTime, TravelDisutility travelDisutility,
			int timeBinSize, boolean useSpeedyAlt) {
		this.network = network;
		this.travelTime = travelTime;
		this.travelDisutility = travelDisutility;
		this.timeBinSize = timeBinSize;
		this.useSharedDeterministicRouter = false;
		this.quantizeDeterministicSegments = false;
		this.sharedRouter = null;
		this.routerProvider = null;
		this.routerLock.getClass(); // suppress unused warning

		this.dummyPerson = PopulationUtils.getFactory().createPerson(Id.createPersonId("test_dummy"));
		VehicleType dummyType = VehicleUtils.createVehicleType(Id.create("car", VehicleType.class));
		this.dummyVehicle = VehicleUtils.createVehicle(Id.createVehicleId("test_dummy_vehicle"), dummyType);

		SpeedyGraph speedyGraph = SpeedyGraphBuilder.build(network);
		if (useSpeedyAlt) {
			SpeedyALTFactory altFactory = new SpeedyALTFactory();
			this.threadLocalRouter = ThreadLocal.withInitial(() ->
				altFactory.createPathCalculator(network, travelDisutility, travelTime));
		} else {
			this.threadLocalRouter = ThreadLocal.withInitial(() ->
				new org.matsim.core.router.DijkstraFactory().createPathCalculator(network, travelDisutility, travelTime));
		}
		this.threadLocalTree = ThreadLocal.withInitial(() ->
			new LeastCostPathTree(speedyGraph, travelTime, travelDisutility));
	}

	// ─────────────────────────────────────────────────────────────────────────

	/** Key for the ssspCompleted set: origin link + time bin. */
	private static class SsspKey {
		private final Id<Link> origin;
		private final int timeBin;

		SsspKey(Id<Link> origin, int timeBin) {
			this.origin = origin;
			this.timeBin = timeBin;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) return true;
			if (!(obj instanceof SsspKey)) return false;
			SsspKey other = (SsspKey) obj;
			return timeBin == other.timeBin && origin.equals(other.origin);
		}

		@Override
		public int hashCode() {
			return 31 * origin.hashCode() + timeBin;
		}
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
