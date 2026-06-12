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

    /** Read a specific cache slot without triggering routing. */
    public static TravelSegment peek(MatsimNetworkCache cache, Id<Link> origin, Id<Link> dest, int timeBin) {
        return cache.peekForTesting(origin, dest, timeBin);
    }

    /** Check whether a given (origin, timeBin) pair is present in ssspCompleted. */
    public static boolean isSsspCompleted(MatsimNetworkCache cache, Id<Link> origin, int timeBin) {
        return cache.isSsspCompletedForTesting(origin, timeBin);
    }

    /** Build a MatsimNetworkCache with real routing that mirrors production exactly:
     *  the given disutility is wrapped in DeterministicTravelDisutility; SpeedyALT for
     *  cache-miss point-to-point + LeastCostPathTree for batch SSSP, both from the same
     *  wrapped instance. Deterministic across instances, threads, and JVMs. */
    public static MatsimNetworkCache createWithRouting(Network network, TravelTime tt, TravelDisutility td, int timeBinSize) {
        return new MatsimNetworkCache(network, tt, td, timeBinSize);
    }
}
