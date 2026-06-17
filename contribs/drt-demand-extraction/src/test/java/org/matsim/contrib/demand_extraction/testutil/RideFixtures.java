package org.matsim.contrib.demand_extraction.testutil;

import java.util.List;

import org.matsim.api.core.v01.Id;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideKind;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

/**
 * Minimal, self-contained {@link Ride}/{@link DrtRequest} builders shared by the
 * streaming-export unit tests (CSV writer, post-processor, data manager). Mirrors the
 * inline helpers in {@code MaterializedRideStoreTest}; centralised here so the streaming
 * tests don't each re-derive the builder boilerplate (DRY).
 */
public final class RideFixtures {

    private RideFixtures() {}

    /** A fully-populated {@link DrtRequest} with deterministic per-index identities. */
    public static DrtRequest request(int index) {
        return DrtRequest.builder()
                .index(index)
                .personId(Id.createPersonId("person-" + index))
                .groupId("group-" + index)
                .tripIndex(0)
                .originLinkId(Id.createLinkId("origin-" + index))
                .destinationLinkId(Id.createLinkId("dest-" + index))
                .requestTime(0.0)
                .earliestDeparture(0.0)
                .latestArrival(10.0)
                .directTravelTime(1.0)
                .directDistance(1.0)
                .maxDetourFactor(1.0)
                .budget(0.0)
                .bestModeScore(0.0)
                .bestMode("car")
                .originActivityType("home")
                .destinationActivityType("work")
                .carTravelTime(1.0)
                .ptTravelTime(1.0)
                .ptAccessibility(1.0)
                .build();
    }

    /** Build a degree-N {@link Ride} whose request indices equal {@code requestIndices}. */
    public static Ride ride(int rideIndex, int... requestIndices) {
        DrtRequest[] requests = new DrtRequest[requestIndices.length];
        for (int i = 0; i < requestIndices.length; i++) {
            requests[i] = request(requestIndices[i]);
        }
        return Ride.builder()
                .index(rideIndex)
                .degree(requestIndices.length)
                .kind(RideKind.MIXED)
                .requests(requests)
                .originsOrderedRequests(requests)
                .destinationsOrderedRequests(requests)
                .passengerTravelTimes(new double[requestIndices.length])
                .passengerDistances(new double[requestIndices.length])
                .passengerNetworkUtilities(new double[requestIndices.length])
                .delays(new double[requestIndices.length])
                .detours(new double[requestIndices.length])
                .connectionTravelTimes(new double[requestIndices.length * 2 - 1])
                .connectionDistances(new double[requestIndices.length * 2 - 1])
                .connectionNetworkUtilities(new double[requestIndices.length * 2 - 1])
                .startTime(0.0)
                .build();
    }

    /** A degree-1 ride (index 0) + a degree-2 ride (index 1), already in index order. */
    public static List<Ride> singleAndPair() {
        return List.of(
                ride(0, 1),     // degree-1: request 1
                ride(1, 2, 3)   // degree-2: requests 2,3
        );
    }
}
