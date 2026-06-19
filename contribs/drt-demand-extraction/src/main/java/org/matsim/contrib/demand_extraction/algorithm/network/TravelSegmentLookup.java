package org.matsim.contrib.demand_extraction.algorithm.network;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;

/**
 * Minimal interface for looking up travel segments between network links.
 *
 * <p>Introduced so {@link org.matsim.contrib.demand_extraction.algorithm.engine.RidePostProcessor}
 * can be unit-tested without a full MATSim network/routing stack.
 * {@link MatsimNetworkCache} implements this interface; tests can provide
 * lightweight stubs.
 */
@FunctionalInterface
public interface TravelSegmentLookup {

	/**
	 * Returns the travel segment (travel time, distance, utility) between two
	 * links at the given departure time.  Must never return {@code null}; use
	 * {@link TravelSegment#unreachable()} when no path exists.
	 *
	 * @param originLinkId  the departure link
	 * @param destLinkId    the arrival link
	 * @param departureTime departure time in seconds since midnight
	 * @return travel segment, never null
	 */
	TravelSegment getSegment(Id<Link> originLinkId, Id<Link> destLinkId, double departureTime);

	/**
	 * Optional batch precompute: runs one SSSP tree from {@code fromLinkId} and
	 * populates the internal cache for all {@code toLinkIds}.  Subsequent
	 * {@link #getSegment} calls for those destinations are served from the cache.
	 *
	 * <p>The default implementation is a no-op — test stubs and simple
	 * implementations fall through to the per-pair {@link #getSegment} path,
	 * which remains correct.  {@link MatsimNetworkCache} overrides this with
	 * the full SSSP implementation (3-4x speedup on large scenarios).
	 *
	 * <p>The tree is bounded by {@code maxTravelTimeSeconds} and caches only nodes settled within that
	 * bound (eviction-invariance — see {@link MatsimNetworkCache#batchPrecompute}). It does NOT
	 * guarantee that every time-feasible target is cached: the SSSP is ordered by generalized
	 * disutility, not time, so a time-feasible target on a higher-disutility path can be left absent.
	 * Callers therefore treat a later cache miss as "route it point-to-point", never as "infeasible".
	 *
	 * @param fromLinkId          origin link for the SSSP tree
	 * @param departureTime       departure time in seconds since midnight
	 * @param toLinkIds           candidate destination links to precompute
	 * @param maxTravelTimeSeconds early-termination bound for the Dijkstra tree
	 */
	@SuppressWarnings("unused")
	default void batchPrecompute(Id<Link> fromLinkId, double departureTime,
	                             Id<Link>[] toLinkIds, double maxTravelTimeSeconds) {
		// no-op: stubs rely entirely on getSegment
	}

	/**
	 * Optional promotion: move the {@code (origin, dest, bin)} segment into a never-evicted
	 * retained tier so it survives watermark eviction. Used by the predecessor-window pass to pin
	 * every evaluated handoff segment (the export domain).
	 *
	 * <p>The default implementation is a no-op — test stubs and simple implementations have no tiers.
	 * {@link MatsimNetworkCache} overrides this with the real promotion.
	 *
	 * @param originLinkId  the departure link
	 * @param destLinkId    the arrival link
	 * @param departureTime departure time in seconds since midnight
	 */
	@SuppressWarnings("unused")
	default void promoteSegment(Id<Link> originLinkId, Id<Link> destLinkId, double departureTime) {
		// no-op: stubs have no retained tier
	}

	/**
	 * Retain a KNOWN segment value into the never-evicted retained tier, keyed exactly as
	 * {@link #getSegment} keys it. Unlike {@link #promoteSegment} (a spec→retained <em>move</em> that
	 * no-ops if the entry was already evicted), this carries the value the caller already routed, so it
	 * survives a watermark eviction racing the call. The predecessor pass uses this — instead of
	 * {@code promoteSegment} — precisely because it now evicts the speculative tier <em>during</em> the
	 * parallel routing (via {@link #checkWatermark()}), so a move-based promote would silently lose
	 * handoffs from the connection-cache export.
	 *
	 * <p>The default implementation is a no-op — test stubs and simple implementations have no tiers.
	 * {@link MatsimNetworkCache} overrides this with the real retention.
	 */
	@SuppressWarnings("unused")
	default void retainSegment(Id<Link> originLinkId, Id<Link> destLinkId, double departureTime,
			double travelTime, double distance, double networkUtility) {
		// no-op: stubs have no retained tier
	}

	/**
	 * Drop the entire speculative tier (both generations) in one single-threaded call. The
	 * predecessor pass calls this once at its start — before any parallel routing — to reclaim the
	 * dead enumeration segments that are never re-read here and would otherwise stay frozen-resident
	 * through post-processing (the cause of the predecessor-pass OOM).
	 *
	 * <p>Unlike per-unit watermark eviction, this runs at a barrier (no routing in flight), so it can
	 * never race a {@code getSegment} and force a divergent point-to-point recompute — the same
	 * eviction discipline the degree-barrier extension uses. The retained tier (enumeration survivor
	 * legs) is untouched. The default implementation is a no-op; {@link MatsimNetworkCache} overrides it.
	 */
	default void dropSpeculativeTier() {
		// no-op: stubs have no speculative tier
	}

	/**
	 * Freeze the retained-tier overlay into a compact snapshot. Called once from a single-threaded
	 * barrier (after the predecessor pass joins) so the retained handoffs are stored compactly rather
	 * than as boxed overlay entries. The default implementation is a no-op;
	 * {@link MatsimNetworkCache} overrides it.
	 */
	default void compactRetained() {
		// no-op: stubs have no retained tier
	}

	/**
	 * Sample heap usage and, if it is above the configured eviction watermark, rotate the older
	 * speculative generation out. Cheap (a heap sample + a synchronized check) and a no-op below the
	 * watermark; called per work unit inside the parallel passes so a large scenario evicts the dead
	 * speculative fills instead of OOMing. Output-invariant — an evicted segment re-routes to a
	 * bit-identical value, and retained handoffs are never touched. The default is a no-op; stubs
	 * have no tiers. {@link MatsimNetworkCache} overrides it.
	 */
	default void checkWatermark() {
		// no-op: stubs have no speculative tier to evict
	}

	/**
	 * Snapshot of the cumulative routing counters, for measuring the routing done by a single phase
	 * (snapshot before, snapshot after, subtract). Layout:
	 * {@code [getSegmentCalls, speedyAltMisses, treesComputed, treesSkipped, segmentsPopulated]}.
	 * The default returns {@code null} — stubs have no counters and callers must null-check.
	 */
	default long[] routingCountersSnapshot() {
		return null;
	}
}
