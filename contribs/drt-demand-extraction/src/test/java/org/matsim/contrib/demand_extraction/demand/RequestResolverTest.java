package org.matsim.contrib.demand_extraction.demand;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;

/**
 * Unit tests for {@link RequestResolver}.
 *
 * <p>Tests cover the two index-vs-position lookup contracts and error handling
 * for unknown indices.
 */
class RequestResolverTest {

    /**
     * Builds a minimal DrtRequest with only {@code index} and {@code personId} set.
     * All numeric fields default to 0.0; the builder validates
     * {@code earliestDeparture (0) <= latestArrival (0) - directTravelTime (0)}
     * which holds at equality.
     *
     * <p>Modelled on the pattern in
     * {@code PostExtensionPrunerTest#req(int, double)}.
     */
    private static DrtRequest req(int index) {
        return new DrtRequest.Builder()
                .index(index)
                .personId(Id.createPersonId("p" + index))
                .directDistance(0)
                .directTravelTime(0)
                .earliestDeparture(0)
                .latestArrival(0)
                .build();
    }

    @Test
    void byIndexIsLastWriteWinsOnCollisions() {
        DrtRequest a = req(7);
        DrtRequest b = req(7);
        assertNotSame(a, b); // sanity: two distinct object instances
        RequestResolver resolver = new RequestResolver(List.of(a, b));
        // b is appended after a, so it overwrites in the map
        assertSame(b, resolver.byIndex(7));
    }

    @Test
    void byPositionReturnsTheExactGenerationCopy() {
        DrtRequest a = req(7);
        DrtRequest b = req(7);
        RequestResolver resolver = new RequestResolver(List.of(a, b));
        assertSame(a, resolver.byPosition(0));
        assertSame(b, resolver.byPosition(1));
    }

    @Test
    void unknownIndexThrows() {
        RequestResolver resolver = new RequestResolver(List.of(req(1)));
        assertThrows(IllegalArgumentException.class, () -> resolver.byIndex(99));
    }

    @Test
    void indexMapSharesTheSameLastWriteWinsMapAndReturnsNullOnMissing() {
        DrtRequest a = req(7);
        DrtRequest b = req(7);
        RequestResolver resolver = new RequestResolver(List.of(a, b, req(3)));
        // same winner as byIndex (last write wins), exposed for legacy Map consumers
        assertSame(b, resolver.indexMap().get(7));
        assertSame(resolver.byIndex(3), resolver.indexMap().get(3));
        // legacy .get() contract: null on missing (NOT the throwing byIndex)
        assertNull(resolver.indexMap().get(99));
    }
}
