package org.matsim.contrib.demand_extraction.algorithm.network;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
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
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.core.population.PopulationUtils;
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
public class MatsimNetworkCache implements TravelSegmentLookup {
	
	private static final Logger log = LogManager.getLogger(MatsimNetworkCache.class);

	private final Network network;
	private final ThreadLocal<LeastCostPathCalculator> threadLocalRouter;
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

	// ── Journaling (Plan A3 checkpoint/resume) ────────────────────────────────
	// When disabled (default) the volatile read is the only overhead — routing paths
	// are byte-identical to pre-journaling code.

	/** When true, newly-inserted cache/sssp keys are captured in the pending queues. */
	private volatile boolean journalingEnabled = false;

	/** Keys of cache entries inserted since the last drainPendingToJournal (or since enableJournaling). */
	private final ConcurrentLinkedQueue<CacheKey> pendingSegmentKeys = new ConcurrentLinkedQueue<>();

	/** Keys of ssspCompleted entries inserted since the last drainPendingToJournal. */
	private final ConcurrentLinkedQueue<SsspKey> pendingSsspKeys = new ConcurrentLinkedQueue<>();

	// ─────────────────────────────────────────────────────────────────────────

	// SSSP tree for batch precomputation (one per thread, NOT thread-safe)
	private final ThreadLocal<LeastCostPathTree> threadLocalTree;

	// Batch precompute statistics
	private final AtomicInteger batchTreesComputed = new AtomicInteger(0);
	private final AtomicInteger batchTreesSkipped = new AtomicInteger(0);
	private final AtomicLong batchSegmentsPopulated = new AtomicLong(0);

	// getSegment call statistics
	// cacheGetAttempts counts every call; totalRoutingAttempts (below) counts only cache misses
	// (calls that fell through to computeSegment). The difference is cache hits.
	private final AtomicLong cacheGetAttempts = new AtomicLong(0);

	// Track routing failures for summary logging (thread-safe)
	private final AtomicInteger routingFailures = new AtomicInteger(0);
	private final AtomicInteger totalRoutingAttempts = new AtomicInteger(0);
	
	@Inject
	public MatsimNetworkCache(
			Network network,
			ExMasConfigGroup config,
			Injector injector) {
		// Inject DRT-specific TravelTime / TravelDisutilityFactory; fall back to car.
		// Mode-specific disutility stays the primary cost function so future toll /
		// road-pricing scenarios route correctly. Determinism comes from the wrapper,
		// not from discarding the mode costs.
		String drtMode = config.getDrtMode();

		TravelTime drtTravelTime = injectNamedOrFallback(injector, TravelTime.class, drtMode, TransportMode.car);
		TravelDisutilityFactory drtDisutilityFactory = injectNamedOrFallback(
				injector, TravelDisutilityFactory.class, drtMode, TransportMode.car);
		TravelDisutility baseDisutility = drtDisutilityFactory.createTravelDisutility(drtTravelTime);

		// Deterministic by construction: unique optimum (eps*length tie-breaker) +
		// admissible heuristic => LeastCostPathTree == SpeedyALT == any instance ==
		// any thread count == any JVM. Both engines below are built from this SAME
		// wrapped instance.
		TravelDisutility disutility = DeterministicTravelDisutility.wrap(baseDisutility, drtTravelTime, network);
		log.info("Network cache routing: SpeedyALT + LeastCostPathTree on {} (wrapping {})",
				disutility.getClass().getSimpleName(), baseDisutility.getClass().getSimpleName());

		this.network = network;
		this.travelTime = drtTravelTime;
		this.travelDisutility = disutility;
		this.timeBinSize = config.getNetworkTimeBinSize();

		SpeedyALTFactory altFactory = new SpeedyALTFactory();
		this.threadLocalRouter = ThreadLocal.withInitial(() ->
				altFactory.createPathCalculator(network, this.travelDisutility, this.travelTime));

		// Create dummy person and vehicle for generic routing
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

		// Use computeIfAbsent for atomic cache operations. This ensures only ONE thread
		// computes the segment for a given key, preventing race conditions in routers.
		return cache.computeIfAbsent(key, k -> {
			TravelSegment seg = computeSegment(originLinkId, destLinkId, canonicalDepartureTime);
			if (journalingEnabled) pendingSegmentKeys.add(k);
			return seg;
		});
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
		// putIfAbsent (not put): two threads can pass the containsKey gate above for the same
		// (fromLink, timeBin) and both compute the tree; only the one that actually inserts the
		// key journals it, so the journal never accumulates duplicate records for a raced key.
		if (ssspCompleted.putIfAbsent(ssspKey, Boolean.TRUE) == null && journalingEnabled) {
			pendingSsspKeys.add(ssspKey);
		}
		batchTreesComputed.incrementAndGet();

		for (Id<Link> toLinkId : toLinkIds) {
			CacheKey key = new CacheKey(fromLinkId, toLinkId, timeBin);
			if (cache.containsKey(key)) continue;

			if (fromLinkId.equals(toLinkId)) {
				cache.computeIfAbsent(key, k -> {
					TravelSegment seg = computeSegment(fromLinkId, toLinkId, canonicalDepartureTime);
					if (journalingEnabled) pendingSegmentKeys.add(k);
					return seg;
				});
				batchSegmentsPopulated.incrementAndGet();
				continue;
			}

			Link toLink = network.getLinks().get(toLinkId);
			if (toLink == null) {
				if (cache.putIfAbsent(key, TravelSegment.unreachable()) == null && journalingEnabled) {
					pendingSegmentKeys.add(key);
				}
				batchSegmentsPopulated.incrementAndGet();
				continue;
			}

			// LeastCostPathTree is node-based. When the target link starts at the same
			// node where the source link ends, tree state collapses to zero inter-link
			// cost and cannot encode whether the actual transition onto toLink is legal
			// (e.g. forbidden immediate turn or only a loop-back path exists). Route
			// these adjacent link-to-link cases point-to-point instead.
			if (fromLink.getToNode().getId().equals(toLink.getFromNode().getId())) {
				if (cache.putIfAbsent(key, computeSegment(fromLinkId, toLinkId, canonicalDepartureTime)) == null
						&& journalingEnabled) {
					pendingSegmentKeys.add(key);
				}
				batchSegmentsPopulated.incrementAndGet();
				continue;
			}

			int toNodeIdx = toLink.getFromNode().getId().index();
			OptionalTime time = tree.getTime(toNodeIdx);

			if (time.isDefined()) {
				// Mirror the convention in computeSegment: add toLink's traversal so each
				// cache value represents "drive from fromLink.toNode to toLink.toNode".
				double interLinkTT = time.seconds() - canonicalDepartureTime;
				double toLinkTT = travelTime.getLinkTravelTime(toLink,
						time.seconds(), dummyPerson, dummyVehicle);
				double toLinkDisutility = travelDisutility.getLinkTravelDisutility(toLink,
						time.seconds(), dummyPerson, dummyVehicle);
				double tt = interLinkTT + toLinkTT;
				double dist = tree.getDistance(toNodeIdx) + toLink.getLength();
				double utility = -(tree.getCost(toNodeIdx) + toLinkDisutility);
				if (cache.putIfAbsent(key, new TravelSegment(tt, dist, utility)) == null && journalingEnabled) {
					pendingSegmentKeys.add(key);
				}
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
			log.info("    cache misses:    {}  ({}%)  [{} failures]",
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
			log.warn("High cache-miss routing failure rate ({}%). Check network connectivity.",
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

	// ── Journaling API (Plan A3 checkpoint/resume) ────────────────────────────

	/**
	 * Enable journaling — from this point on, every newly-inserted cache/sssp entry
	 * is captured in the pending queues for draining at checkpoint barriers.
	 *
	 * <p>Must be called before routing begins if journaling is desired.
	 */
	public void enableJournaling() {
		this.journalingEnabled = true;
	}

	/**
	 * Stop capturing newly-inserted entries and discard anything still pending. Called by the
	 * engine once the LAST checkpoint barrier has been drained and generation is complete, so the
	 * subsequent lazy export pass (which routes the never-cached "backstop" class — reproduced
	 * bit-identically point-to-point on resume, no journal needed) does not grow the pending queue
	 * unboundedly. Only safe to call when no further barrier will be written: any still-pending
	 * entries are intentionally dropped because they will not be journaled.
	 *
	 * <p>Thread-safety: the engine calls this from the single coordinating thread after all
	 * parallel generation has joined, so it is NOT concurrent with the routing threads that
	 * enqueue into the pending queues. {@code journalingEnabled} is volatile and the queues are
	 * concurrent, so a late stray enqueue would be memory-safe, but the contract is single-threaded
	 * quiescence at this call: clearing here races with no live producer.
	 */
	public void disableJournaling() {
		this.journalingEnabled = false;
		pendingSegmentKeys.clear();
		pendingSsspKeys.clear();
	}

	/**
	 * Drain all pending (newly-inserted since the last drain or since enableJournaling)
	 * cache and sssp entries into the journal, then write a single BARRIER marker and fsync.
	 *
	 * <p>Must be called from a single thread at a checkpoint barrier — no routing should
	 * be concurrently inserting into the cache when this method runs.
	 *
	 * @param writer open journal writer (caller owns the lifecycle)
	 * @throws IOException if writing fails
	 */
	public void drainPendingToJournal(ConnectionCacheJournal.Writer writer) throws IOException {
		List<ConnectionCacheJournal.Segment> segments = new ArrayList<>();
		List<ConnectionCacheJournal.Sssp> ssspKeys = new ArrayList<>();

		CacheKey segKey;
		while ((segKey = pendingSegmentKeys.poll()) != null) {
			TravelSegment seg = cache.get(segKey);
			if (seg == null) continue; // defensive: entry was removed (e.g. clearCache in tests)
			segments.add(new ConnectionCacheJournal.Segment(
					segKey.origin.toString(),
					segKey.destination.toString(),
					segKey.timeBin,
					seg.getTravelTime(),
					seg.getDistance(),
					seg.getNetworkUtility()));
		}

		SsspKey ssspKey;
		while ((ssspKey = pendingSsspKeys.poll()) != null) {
			ssspKeys.add(new ConnectionCacheJournal.Sssp(
					ssspKey.origin.toString(),
					ssspKey.timeBin));
		}

		writer.appendBarrier(segments, ssspKeys);
	}

	/**
	 * Bulk-load committed journal contents into this cache, reconstructing both
	 * the connection-cache map and the ssspCompleted map.
	 *
	 * <p>Intended for use at resume time, before any routing begins, to restore
	 * the cache to the exact state it was in when the checkpoint was taken.
	 *
	 * @param contents result of {@link ConnectionCacheJournal#read(java.nio.file.Path)}
	 */
	public void bulkLoadFromJournal(ConnectionCacheJournal.Contents contents) {
		for (ConnectionCacheJournal.Segment seg : contents.segments()) {
			Id<Link> from = Id.createLinkId(seg.fromLink());
			Id<Link> to   = Id.createLinkId(seg.toLink());
			cache.put(new CacheKey(from, to, seg.bin()),
					new TravelSegment(seg.tt(), seg.dist(), seg.utility()));
		}
		for (ConnectionCacheJournal.Sssp sssp : contents.ssspKeys()) {
			Id<Link> from = Id.createLinkId(sssp.fromLink());
			ssspCompleted.put(new SsspKey(from, sssp.bin()), Boolean.TRUE);
		}
	}

	// ─────────────────────────────────────────────────────────────────────────

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
	
	/**
	 * Attempt to resolve a Guice binding for {@code type} named {@code mode}; on
	 * {@link com.google.inject.ConfigurationException} (no binding registered for that name),
	 * fall back to the binding named {@code fallbackMode}. Any other exception — in particular
	 * {@link com.google.inject.ProvisionException} (binding exists but its provider threw) —
	 * is intentionally not caught so misconfiguration surfaces immediately.
	 */
	private static <T> T injectNamedOrFallback(Injector injector, Class<T> type, String mode, String fallbackMode) {
		try {
			return injector.getInstance(Key.get(type, Names.named(mode)));
		} catch (com.google.inject.ConfigurationException e) {
			log.debug("No {} bound for mode '{}', falling back to '{}'", type.getSimpleName(), mode, fallbackMode);
			return injector.getInstance(Key.get(type, Names.named(fallbackMode)));
		}
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
			return new TravelSegment(linkTravelTime, linkDistance, utility);
		}

		// Adjacent-link OD: originLink.toNode == destLink.fromNode.
		// Node-based LeastCostPathTree cannot encode turn legality here (the tree starts
		// at originLink.toNode, which is already destLink.fromNode — cost = 0 with no
		// inter-link traversal, masking forbidden immediate turns). Keep the link-based
		// SpeedyALT path router for these cases. batchPrecompute also defers adjacent
		// ODs to computeSegment for exactly this reason.
		if (originLink.getToNode().getId().equals(destLink.getFromNode().getId())) {
			try {
				Path path = threadLocalRouter.get().calcLeastCostPath(
						originLink,
						destLink,
						departureTime,
						dummyPerson,
						dummyVehicle);

				if (path == null || path.links.isEmpty()) {
					routingFailures.incrementAndGet();
					return createInfinitySegment();
				}

				double toLinkTT = travelTime.getLinkTravelTime(destLink,
						departureTime + path.travelTime, dummyPerson, dummyVehicle);
				double toLinkDisutility = travelDisutility.getLinkTravelDisutility(destLink,
						departureTime + path.travelTime, dummyPerson, dummyVehicle);
				double tt = path.travelTime + toLinkTT;
				double dist = path.links.stream().mapToDouble(Link::getLength).sum() + destLink.getLength();
				double utility = -(path.travelCost + toLinkDisutility);
				return new TravelSegment(tt, dist, utility);

			} catch (OutOfMemoryError e) {
				log.warn("OutOfMemoryError during routing from link {} to link {} - treating as unreachable",
						originLinkId, destLinkId);
				routingFailures.incrementAndGet();
				return createInfinitySegment();
			} catch (Exception e) {
				log.warn("Routing exception from link {} to link {}: {} - treating as unreachable",
						originLinkId, destLinkId, e.getMessage());
				routingFailures.incrementAndGet();
				return createInfinitySegment();
			}
		}

		// Non-adjacent OD: fill via the SAME mechanism as batchPrecompute so the cached
		// value is a function of (origin, dest, bin) only — never of fill history.
		// Under eviction/watermark schemes a segment may be evicted and re-filled: if the
		// re-fill used a different engine than the original fill, downstream pair/extension
		// feasibility decisions could flip. Routing via the same LeastCostPathTree guarantees
		// bit-identical results regardless of fill order or thread scheduling.
		try {
			LeastCostPathTree tree = threadLocalTree.get();
			int targetNode = destLink.getFromNode().getId().index();
			tree.calculate(originLink, departureTime, dummyPerson, dummyVehicle,
					(node, arrTime, cost, distance, depTime) -> node == targetNode);

			OptionalTime time = tree.getTime(targetNode);
			if (time.isUndefined()) {
				routingFailures.incrementAndGet();
				return createInfinitySegment();
			}

			// Mirror batchPrecompute's value formula exactly (see lines 300–307):
			// add destLink traversal so the segment represents "fromLink.toNode → destLink.toNode".
			double interLinkTT = time.seconds() - departureTime;
			double toLinkTT = travelTime.getLinkTravelTime(destLink, time.seconds(), dummyPerson, dummyVehicle);
			double toLinkDisutility = travelDisutility.getLinkTravelDisutility(destLink, time.seconds(), dummyPerson, dummyVehicle);
			double tt = interLinkTT + toLinkTT;
			double dist = tree.getDistance(targetNode) + destLink.getLength();
			double utility = -(tree.getCost(targetNode) + toLinkDisutility);
			return new TravelSegment(tt, dist, utility);

		} catch (Exception e) {
			log.warn("Tree routing exception from link {} to link {}: {} - treating as unreachable",
					originLinkId, destLinkId, e.getMessage());
			routingFailures.incrementAndGet();
			return createInfinitySegment();
		}
	}
	
	private TravelSegment createInfinitySegment() {
		return TravelSegment.unreachable();
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

	/**
	 * Read a specific cache slot without triggering routing.
	 * Intended for diagnostic tests only.
	 */
	TravelSegment peekForTesting(Id<Link> origin, Id<Link> dest, int timeBin) {
		return cache.get(new CacheKey(origin, dest, timeBin));
	}

	/**
	 * Check whether the given (origin, timeBin) pair is present in ssspCompleted.
	 * Intended for use in unit tests only.
	 */
	boolean isSsspCompletedForTesting(Id<Link> origin, int bin) {
		return ssspCompleted.containsKey(new SsspKey(origin, bin));
	}

	private MatsimNetworkCache() {
		this.network = null;
		this.threadLocalRouter = null;
		this.threadLocalTree = null;
		this.travelTime = null;
		this.travelDisutility = null;
		this.timeBinSize = Integer.MAX_VALUE; // any departure time → bin 0
		this.dummyPerson = null;
		this.dummyVehicle = null;
	}

	/**
	 * Test constructor mirroring the production routing path exactly: the given
	 * disutility is wrapped in {@link DeterministicTravelDisutility}; cache-miss
	 * point-to-point routing uses thread-local SpeedyALT and batch SSSP uses
	 * {@link LeastCostPathTree}, both built from the SAME wrapped instance.
	 *
	 * <p>Keeps raw additive segment metrics: the cache deliberately avoids per-segment
	 * quantization because it is not additive and can make a split route appear shorter
	 * than the equivalent direct route at feasibility boundaries.
	 */
	MatsimNetworkCache(Network network, TravelTime travelTime, TravelDisutility travelDisutility,
			int timeBinSize) {
		this.network = network;
		this.travelTime = travelTime;
		TravelDisutility wrapped = DeterministicTravelDisutility.wrap(travelDisutility, travelTime, network);
		this.travelDisutility = wrapped;
		this.timeBinSize = timeBinSize;

		this.dummyPerson = PopulationUtils.getFactory().createPerson(Id.createPersonId("test_dummy"));
		VehicleType dummyType = VehicleUtils.createVehicleType(Id.create("car", VehicleType.class));
		this.dummyVehicle = VehicleUtils.createVehicle(Id.createVehicleId("test_dummy_vehicle"), dummyType);

		SpeedyGraph speedyGraph = SpeedyGraphBuilder.build(network);
		SpeedyALTFactory altFactory = new SpeedyALTFactory();
		this.threadLocalRouter = ThreadLocal.withInitial(() ->
			altFactory.createPathCalculator(network, wrapped, travelTime));
		this.threadLocalTree = ThreadLocal.withInitial(() ->
			new LeastCostPathTree(speedyGraph, travelTime, wrapped));
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
