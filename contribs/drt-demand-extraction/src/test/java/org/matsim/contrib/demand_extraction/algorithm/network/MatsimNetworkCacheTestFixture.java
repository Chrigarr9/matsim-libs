package org.matsim.contrib.demand_extraction.algorithm.network;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;

/**
 * Test-only bridge that exposes the package-private
 * {@link MatsimNetworkCache#forTesting()} and
 * {@link MatsimNetworkCache#putForTesting(Id, Id, TravelSegment)} hooks
 * to tests outside the {@code network} package. Must never be referenced
 * from production code.
 */
public final class MatsimNetworkCacheTestFixture {

    private MatsimNetworkCacheTestFixture() {}

    /** Build a MatsimNetworkCache with no router — segments are pre-populated by the caller. */
    public static MatsimNetworkCache create() {
        return MatsimNetworkCache.forTesting();
    }

    /** Pre-populate a single origin→destination segment in the cache at time-bin 0. */
    public static void put(MatsimNetworkCache cache, Id<Link> origin, Id<Link> dest, TravelSegment seg) {
        cache.putForTesting(origin, dest, seg);
    }

    /** Build a MatsimNetworkCache with real routing capability for integration tests.
     *  Uses Dijkstra for cache-miss point-to-point routing. */
    public static MatsimNetworkCache createWithRouting(Network network, TravelTime tt, TravelDisutility td, int timeBinSize) {
        return new MatsimNetworkCache(network, tt, td, timeBinSize);
    }

    /** Build a MatsimNetworkCache that mirrors the production routing path:
     *  SpeedyALT (A* with landmarks) for cache-miss point-to-point + LeastCostPathTree for batch SSSP.
     *  Use this when a test needs to exercise the same routing combination eqasim runs in production. */
    public static MatsimNetworkCache createWithSpeedyAltRouting(Network network, TravelTime tt, TravelDisutility td, int timeBinSize) {
        return new MatsimNetworkCache(network, tt, td, timeBinSize, /* useSpeedyAlt= */ true);
    }
}
