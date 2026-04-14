package org.matsim.contrib.demand_extraction.algorithm.network;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;

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

    /** Pre-populate a single origin→destination segment at an explicit time-bin. */
    public static void putAtBin(MatsimNetworkCache cache, Id<Link> origin, Id<Link> dest,
                                int timeBin, TravelSegment seg) {
        cache.putForTesting(origin, dest, timeBin, seg);
    }
}
