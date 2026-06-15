package org.matsim.contrib.demand_extraction.algorithm.generation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

/**
 * The candidate pre-filter must be a lossless superset of the exact temporal
 * overlap check that {@code PairGenerator} applies afterwards. A flat
 * {@code horizon} on {@code |treq_i - treq_j|} is NOT a superset for long trips:
 * a partner whose request time differs by more than the horizon can still share
 * the tail of a long ride because the windows {@code [earliestDeparture,
 * latestArrival]} overlap. {@code findCandidatesInWindow} fixes that by searching
 * each request's own window (padded by the global max flexibility), while never
 * returning fewer candidates than the flat horizon would.
 */
class TimeFilterWindowTest {

    /** treq=10000, directTT=6000 (long trip, 100 min), flex=100 s each side. */
    private static DrtRequest longTrip() {
        return req(0, "A_long", 10_000.0, 6_000.0, 100.0, 100.0);
    }

    /**
     * treq=15000 — 5000 s after the long trip's request time (well beyond a
     * 3600 s horizon) — but its window [14900, 16100] overlaps the long trip's
     * window [9900, 16100], so the two CAN pool.
     */
    private static DrtRequest farButOverlapping() {
        return req(1, "B_far", 15_000.0, 1_000.0, 100.0, 100.0);
    }

    @Test
    void windowFilterIncludesFarPartnerWhoseWindowsOverlap() {
        DrtRequest[] requests = { longTrip(), farButOverlapping() };
        TimeFilter filter = new TimeFilter(requests);
        int longIdx = indexOf(filter, "A_long");
        int farIdx = indexOf(filter, "B_far");

        int[] windowCandidates = filter.findCandidatesInWindow(longIdx, 3600.0);

        assertTrue(contains(windowCandidates, farIdx),
            "window filter must keep a far-treq partner whose temporal windows overlap");
    }

    @Test
    void flatHorizonWronglyDropsThatPartner() {
        DrtRequest[] requests = { longTrip(), farButOverlapping() };
        TimeFilter filter = new TimeFilter(requests);
        int longIdx = indexOf(filter, "A_long");
        int farIdx = indexOf(filter, "B_far");

        int[] horizonCandidates = filter.findCandidatesInHorizon(longIdx, 3600.0);

        assertFalse(contains(horizonCandidates, farIdx),
            "the flat 3600 s horizon is what drops the feasible long-trip pair (the bug)");
    }

    @Test
    void windowFilterIsSupersetOfFlatHorizonForShortTrips() {
        // Two short trips within the horizon: the window filter must not drop a
        // partner the flat horizon kept (no regression on short-trip scenarios).
        DrtRequest[] requests = {
            req(0, "S0", 10_000.0, 600.0, 100.0, 100.0),
            req(1, "S1", 10_500.0, 600.0, 100.0, 100.0),
        };
        TimeFilter filter = new TimeFilter(requests);
        int i = indexOf(filter, "S0");

        int[] horizon = filter.findCandidatesInHorizon(i, 3600.0);
        int[] window = filter.findCandidatesInWindow(i, 3600.0);

        for (int c : horizon) {
            assertTrue(contains(window, c),
                "window filter dropped candidate " + c + " that the flat horizon kept");
        }
    }

    private static int indexOf(TimeFilter filter, String paxId) {
        for (int i = 0; i < filter.size(); i++) {
            if (filter.getRequest(i).getPaxId().equals(paxId)) return i;
        }
        throw new IllegalStateException("no request " + paxId);
    }

    private static boolean contains(int[] arr, int v) {
        return Arrays.stream(arr).anyMatch(x -> x == v);
    }

    private static DrtRequest req(int index, String pid, double treq, double directTT,
            double originFlex, double destFlex) {
        return DrtRequest.builder()
            .index(index)
            .personId(Id.createPersonId(pid))
            .groupId(pid + "_g0")
            .tripIndex(0)
            .isCommute(false)
            .isEducation(false)
            .budget(0.0)
            .bestModeScore(0.0)
            .bestMode("walk")
            .originLinkId(Id.createLinkId("l_o"))
            .destinationLinkId(Id.createLinkId("l_d"))
            .originX(0.0).originY(0.0)
            .destinationX(1000.0).destinationY(0.0)
            .originLinkCoordFromX(0.0).originLinkCoordFromY(0.0)
            .originLinkCoordToX(0.0).originLinkCoordToY(0.0)
            .destinationLinkCoordFromX(1000.0).destinationLinkCoordFromY(0.0)
            .destinationLinkCoordToX(1000.0).destinationLinkCoordToY(0.0)
            .requestTime(treq)
            .earliestDeparture(treq - originFlex)
            .latestArrival(treq + directTT + destFlex)
            .directTravelTime(directTT)
            .directDistance(1000.0)
            .maxDetourFactor(1.5)
            .originActivityType("home")
            .destinationActivityType("work")
            .carTravelTime(directTT)
            .ptTravelTime(directTT * 1.5)
            .ptAccessibility(1.5)
            .build();
    }
}
