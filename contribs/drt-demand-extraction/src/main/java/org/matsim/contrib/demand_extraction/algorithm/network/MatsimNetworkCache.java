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
import org.matsim.contrib.demand_extraction.algorithm.util.PackedKeyCodec;
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

	// Three-tier connection cache (design 2026-06-12 §3): a compact retained tier over a
	// two-generation watermark-evicted speculative tier. Keys are packed primitive longs
	// (PackedKeyCodec): segment keys carry (originLinkIdx, destLinkIdx, timeBin); the SSSP
	// completion marks carry (originLinkIdx, timeBin). Link indices are MATSim Id.index()
	// values — stable within this JVM only, so anything persisted (journal, CSV) maps back
	// to link-id strings via linkIdByIndex(). The previous ssspCompleted map now lives
	// inside the speculative tier (co-evicted with its segments).
	private final TieredSegmentCache cache = new TieredSegmentCache();

	// Lazily-built int index -> Id<Link> back-mapping, used only at export / journal-drain
	// time to turn packed long keys back into link-id strings. Sized by the global Id
	// registry; populated from this cache's network links. Volatile + double-checked so the
	// single-threaded export/drain barriers share one build without locking the hot path.
	private volatile Id<Link>[] linkByIndex = null;

	// ── Journaling (Plan A3 checkpoint/resume) ────────────────────────────────
	// When disabled (default) the volatile read is the only overhead — routing paths
	// are byte-identical to pre-journaling code.

	/** When true, newly-inserted cache/sssp keys are captured in the pending queues. */
	private volatile boolean journalingEnabled = false;

	/** Packed segment keys inserted since the last drainPendingToJournal (or since enableJournaling). */
	private final ConcurrentLinkedQueue<Long> pendingSegmentKeys = new ConcurrentLinkedQueue<>();

	/** Packed SSSP keys inserted since the last drainPendingToJournal. */
	private final ConcurrentLinkedQueue<Long> pendingSsspKeys = new ConcurrentLinkedQueue<>();

	// ─────────────────────────────────────────────────────────────────────────

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

		long key = PackedKeyCodec.segmentKey(originLinkId.index(), destLinkId.index(), timeBin);

		// computeIfAbsent fills a cache miss exactly once under normal use. The tiered cache
		// allows a rare duplicate fill under a get/rotate race — harmless: routing is
		// deterministic so both fills carry bit-identical values, and the speculative put is
		// first-write-wins. Journal the key only when this call actually inserted it.
		TravelSegment cached = cache.get(key);
		if (cached != null) return cached;
		TravelSegment seg = computeSegment(originLinkId, destLinkId, canonicalDepartureTime);
		if (cache.putSpeculative(key, seg) && journalingEnabled) {
			pendingSegmentKeys.add(key);
		}
		// Re-read so all callers observe the first-write winner (in case of a concurrent fill).
		TravelSegment winner = cache.get(key);
		return winner != null ? winner : seg;
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
		long ssspKey = PackedKeyCodec.ssspKey(fromLinkId.index(), timeBin);
		if (cache.isSsspDone(ssspKey)) {
			batchTreesSkipped.incrementAndGet();
			return;
		}

		LeastCostPathTree tree = threadLocalTree.get();
		LeastCostPathTree.StopCriterion stopCriterion =
			new LeastCostPathTree.TravelTimeStopCriterion(maxTravelTimeSeconds);
		tree.calculate(fromLink, canonicalDepartureTime, dummyPerson, dummyVehicle, stopCriterion);
		// markSssp returns true only for the thread that actually inserted the mark: two threads
		// can pass the isSsspDone gate above for the same (fromLink, timeBin) and both compute the
		// tree; only the inserting one journals it, so the journal never accumulates duplicate
		// SSSP records for a raced key.
		if (cache.markSssp(ssspKey) && journalingEnabled) {
			pendingSsspKeys.add(ssspKey);
		}
		batchTreesComputed.incrementAndGet();

		for (Id<Link> toLinkId : toLinkIds) {
			long key = PackedKeyCodec.segmentKey(fromLinkId.index(), toLinkId.index(), timeBin);
			if (cache.get(key) != null) continue;

			if (fromLinkId.equals(toLinkId)) {
				TravelSegment seg = computeSegment(fromLinkId, toLinkId, canonicalDepartureTime);
				if (cache.putSpeculative(key, seg) && journalingEnabled) {
					pendingSegmentKeys.add(key);
				}
				batchSegmentsPopulated.incrementAndGet();
				continue;
			}

			Link toLink = network.getLinks().get(toLinkId);
			if (toLink == null) {
				if (cache.putSpeculative(key, TravelSegment.unreachable()) && journalingEnabled) {
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
				if (cache.putSpeculative(key, computeSegment(fromLinkId, toLinkId, canonicalDepartureTime))
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
				if (cache.putSpeculative(key, new TravelSegment(tt, dist, utility)) && journalingEnabled) {
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

			Id<Link>[] byIndex = linkByIndex();

			// Collect all keys, then sort by (origin string, dest string, bin) to match the
			// pre-tiered deterministic ordering exactly. forEachKey walks both tiers deduped.
			List<Long> keys = new ArrayList<>();
			cache.forEachKey(keys::add);
			keys.sort((a, b) -> {
				String oa = linkStr(byIndex, PackedKeyCodec.origin(a));
				String ob = linkStr(byIndex, PackedKeyCodec.origin(b));
				int cmp = oa.compareTo(ob);
				if (cmp != 0) return cmp;
				String da = linkStr(byIndex, PackedKeyCodec.dest(a));
				String db = linkStr(byIndex, PackedKeyCodec.dest(b));
				cmp = da.compareTo(db);
				if (cmp != 0) return cmp;
				return Integer.compare(PackedKeyCodec.bin(a), PackedKeyCodec.bin(b));
			});

			int exported = 0;
			for (long key : keys) {
				TravelSegment seg = cache.get(key);
				if (seg == null || !seg.isReachable()) {
					continue;
				}
				writer.write(String.format(java.util.Locale.US, "%s,%s,%d,%.2f,%.2f\n",
						linkStr(byIndex, PackedKeyCodec.origin(key)),
						linkStr(byIndex, PackedKeyCodec.dest(key)),
						PackedKeyCodec.bin(key),
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
				long key = PackedKeyCodec.segmentKey(origin.index(), destination.index(), timeBin);

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
						long key = PackedKeyCodec.segmentKey(origin.index(), dest.index(), timeBin);
						TravelSegment seg = new TravelSegment(tt, dist, util);
						cache.putSpeculative(key, seg);
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
		return (int) cache.size();
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
		long retainedSize = cache.retainedSize();
		long speculativeSize = cache.speculativeSize();

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
		log.info("  Cache total size:  {} entries  (retained {} / speculative {})",
				String.format("%,d", cacheSize), String.format("%,d", retainedSize),
				String.format("%,d", speculativeSize));
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

		Id<Link>[] byIndex = linkByIndex();

		Long segKey;
		while ((segKey = pendingSegmentKeys.poll()) != null) {
			long k = segKey;
			TravelSegment seg = cache.get(k);
			if (seg == null) continue; // defensive: entry was removed (e.g. clearCache in tests)
			segments.add(new ConnectionCacheJournal.Segment(
					linkStr(byIndex, PackedKeyCodec.origin(k)),
					linkStr(byIndex, PackedKeyCodec.dest(k)),
					PackedKeyCodec.bin(k),
					seg.getTravelTime(),
					seg.getDistance(),
					seg.getNetworkUtility()));
		}

		Long ssspKey;
		while ((ssspKey = pendingSsspKeys.poll()) != null) {
			long k = ssspKey;
			ssspKeys.add(new ConnectionCacheJournal.Sssp(
					linkStr(byIndex, PackedKeyCodec.ssspOrigin(k)),
					PackedKeyCodec.ssspBin(k)));
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
			cache.putSpeculative(PackedKeyCodec.segmentKey(from.index(), to.index(), seg.bin()),
					new TravelSegment(seg.tt(), seg.dist(), seg.utility()));
		}
		for (ConnectionCacheJournal.Sssp sssp : contents.ssspKeys()) {
			Id<Link> from = Id.createLinkId(sssp.fromLink());
			cache.markSssp(PackedKeyCodec.ssspKey(from.index(), sssp.bin()));
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
		try {
				// Use link-based routing (new non-deprecated method)
				// This properly handles turn restrictions and considers full link-to-link
				// travel
				// Use dummy person/vehicle for generic routing (required by TravelDisutility)
				// Always thread-local: per-thread SpeedyALT instances are constructed
				// identically from the same factory/network/disutility/travelTime, so
				// they give identical paths for identical OD queries. The previous single
				// synchronized shared router was a global serialization point — JFR
				// (2026-05-27) showed 15/16 workers blocked there, dominating the
				// predecessors phase. ConcurrentHashMap.computeIfAbsent already serializes
				// same-OD callers via bucket locks, preserving per-OD cache determinism.
				Path path = threadLocalRouter.get().calcLeastCostPath(
						originLink,
						destLink,
						departureTime,
						dummyPerson,
						dummyVehicle);
			
			if (path == null || path.links.isEmpty()) {
				// No path found - track failure
				routingFailures.incrementAndGet();
				return createInfinitySegment();
			}

			// MATSim routers (SpeedyALT, SpeedyDijkstra, LeastCostPathTree) all
			// return path.travelTime measured from fromLink.toNode to toLink.fromNode.
			// It does NOT include traversal of either fromLink or toLink. We add
			// toLink's traversal so each cache value represents "drive from event at
			// fromLink (vehicle at fromLink.toNode) to event at toLink (vehicle at
			// toLink.toNode)" — matching VrpPaths' "vehicle enters and exits links
			// at toNode" convention used throughout DVRP/DRT. The first link of any
			// chain (originsOrdered[0].originLinkId) is never the toLink of any
			// segment, so its traversal is correctly omitted (the vehicle is assumed
			// to already be at its toNode at startTime).
			double toLinkTT = travelTime.getLinkTravelTime(destLink,
					departureTime + path.travelTime, dummyPerson, dummyVehicle);
			double toLinkDisutility = travelDisutility.getLinkTravelDisutility(destLink,
					departureTime + path.travelTime, dummyPerson, dummyVehicle);
			double tt = path.travelTime + toLinkTT;
			double dist = path.links.stream().mapToDouble(Link::getLength).sum() + destLink.getLength();
			double utility = -(path.travelCost + toLinkDisutility);

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
		cache.putSpeculative(PackedKeyCodec.segmentKey(origin.index(), dest.index(), 0), seg);
	}

	/**
	 * Read a specific cache slot without triggering routing.
	 * Intended for diagnostic tests only.
	 */
	TravelSegment peekForTesting(Id<Link> origin, Id<Link> dest, int timeBin) {
		return cache.get(PackedKeyCodec.segmentKey(origin.index(), dest.index(), timeBin));
	}

	/**
	 * Check whether the given (origin, timeBin) pair is present in ssspCompleted.
	 * Intended for use in unit tests only.
	 */
	boolean isSsspCompletedForTesting(Id<Link> origin, int bin) {
		return cache.isSsspDone(PackedKeyCodec.ssspKey(origin.index(), bin));
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

	// ── Packed-key string back-mapping (export / journal drain only) ───────────

	/**
	 * Lazily build and cache the int-index → {@code Id<Link>} array used to turn packed long
	 * keys back into link-id strings at export / journal-drain time. Sized by the global Id
	 * registry and populated from this cache's network links. Double-checked: the array is
	 * built once at the first single-threaded export/drain barrier; concurrent routing never
	 * calls this.
	 */
	@SuppressWarnings("unchecked")
	private Id<Link>[] linkByIndex() {
		Id<Link>[] local = linkByIndex;
		if (local != null) return local;
		synchronized (this) {
			if (linkByIndex != null) return linkByIndex;
			Id<Link>[] arr = (Id<Link>[]) new Id[Id.getNumberOfIds(Link.class)];
			if (network != null) {
				for (Id<Link> id : network.getLinks().keySet()) {
					int idx = id.index();
					if (idx >= 0 && idx < arr.length) {
						arr[idx] = id;
					}
				}
			}
			linkByIndex = arr;
			return arr;
		}
	}

	/**
	 * Resolve a packed link index to its string form. Falls back to the raw index as a string
	 * if the index is outside the back-map (only possible for an id created after the array was
	 * built, which export/drain do not produce — every drained/exported key came from a network
	 * link present at build time).
	 */
	private static String linkStr(Id<Link>[] byIndex, int idx) {
		if (idx >= 0 && idx < byIndex.length && byIndex[idx] != null) {
			return byIndex[idx].toString();
		}
		return Integer.toString(idx);
	}
}
