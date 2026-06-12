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
}
