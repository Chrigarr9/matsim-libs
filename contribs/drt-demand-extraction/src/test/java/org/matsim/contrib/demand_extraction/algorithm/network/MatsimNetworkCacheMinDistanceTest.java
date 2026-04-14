package org.matsim.contrib.demand_extraction.algorithm.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;

/**
 * Unit tests for {@link MatsimNetworkCache#minDistanceAcrossBins(Id, Id)}.
 *
 * <p>The helper is used by the LB B&amp;B cut to obtain an admissible lower
 * bound on the network distance between two links, independent of departure
 * time. The previous implementation queried {@code getSegment(from, to, 0.0)},
 * which is only one time-bin sample and can overestimate the true minimum
 * under time-dependent routing — an overestimate makes the cut predicate
 * unsound (prunes orderings that would have produced a valid ride).
 */
class MatsimNetworkCacheMinDistanceTest {

    @Test
    void minDistanceAcrossBinsReturnsSmallestCachedDistance() {
        MatsimNetworkCache cache = MatsimNetworkCache.forTesting();
        Id<Link> from = Id.createLinkId("A");
        Id<Link> to   = Id.createLinkId("B");

        // Same (from, to) pair cached at three distinct time bins with
        // different distances — simulates time-dependent path selection.
        cache.putForTesting(from, to, 0, new TravelSegment(100.0, 1200.0, 0.0));
        cache.putForTesting(from, to, 3, new TravelSegment(110.0, 1150.0, 0.0));
        cache.putForTesting(from, to, 5, new TravelSegment(120.0,  980.0, 0.0));

        // Unrelated pair cached with an even smaller distance — must not leak
        // into the result.
        cache.putForTesting(
                Id.createLinkId("X"), Id.createLinkId("Y"),
                0, new TravelSegment(50.0, 10.0, 0.0));

        double min = cache.minDistanceAcrossBins(from, to);
        assertEquals(980.0, min, 1e-9);
    }

    @Test
    void minDistanceAcrossBinsReturnsInfinityWhenUncached() {
        MatsimNetworkCache cache = MatsimNetworkCache.forTesting();
        double min = cache.minDistanceAcrossBins(
                Id.createLinkId("A"), Id.createLinkId("B"));
        assertTrue(Double.isInfinite(min) && min > 0,
                "Expected +Infinity for an uncached pair, got " + min);
    }

    @Test
    void minDistanceAcrossBinsIgnoresReverseDirection() {
        MatsimNetworkCache cache = MatsimNetworkCache.forTesting();
        Id<Link> a = Id.createLinkId("A");
        Id<Link> b = Id.createLinkId("B");

        // Only the B -> A direction is cached — the helper must NOT return
        // that distance when asked for A -> B.
        cache.putForTesting(b, a, 0, new TravelSegment(50.0, 500.0, 0.0));

        double min = cache.minDistanceAcrossBins(a, b);
        assertTrue(Double.isInfinite(min) && min > 0,
                "Expected +Infinity when only the reverse direction is cached, got " + min);
    }
}
