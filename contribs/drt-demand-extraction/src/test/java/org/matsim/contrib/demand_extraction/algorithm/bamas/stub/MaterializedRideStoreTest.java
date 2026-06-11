package org.matsim.contrib.demand_extraction.algorithm.bamas.stub;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideKind;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

class MaterializedRideStoreTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private static DrtRequest request(int index) {
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

    /** Build a degree-N Ride whose request indices equal {@code requestIndices}. */
    private static Ride ride(int rideIndex, int... requestIndices) {
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

    // ── fixture ───────────────────────────────────────────────────────────────

    private static List<Ride> makeTestList() {
        return List.of(
                ride(0, 1),       // degree-1: request 1
                ride(1, 2, 3),    // degree-2: requests 2,3
                ride(2, 4, 5, 6)  // degree-3: requests 4,5,6
        );
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    void sizeReflectsBackingList() {
        List<Ride> list = makeTestList();
        MaterializedRideStore store = new MaterializedRideStore(list);
        assertEquals(list.size(), store.size());
    }

    @Test
    void materializeReturnsSameInstance() {
        List<Ride> list = makeTestList();
        MaterializedRideStore store = new MaterializedRideStore(list);
        for (int i = 0; i < list.size(); i++) {
            assertSame(list.get(i), store.materialize(i),
                    "materialize(" + i + ") must return the same Ride instance");
        }
    }

    @Test
    void requestIndicesMatchRideGetRequestIndices() {
        List<Ride> list = makeTestList();
        MaterializedRideStore store = new MaterializedRideStore(list);
        for (int i = 0; i < list.size(); i++) {
            assertArrayEquals(list.get(i).getRequestIndices(), store.requestIndices(i),
                    "requestIndices(" + i + ") mismatch");
        }
    }

    @Test
    void forEachMaterializedVisitsAllRidesInOrder() {
        List<Ride> list = makeTestList();
        MaterializedRideStore store = new MaterializedRideStore(list);

        List<Ride> visited = new ArrayList<>();
        store.forEachMaterialized(visited::add);

        assertEquals(list.size(), visited.size(), "visitor must be called once per ride");
        for (int i = 0; i < list.size(); i++) {
            assertSame(list.get(i), visited.get(i),
                    "forEachMaterialized visit[" + i + "] must be the same instance in list order");
        }
    }
}
