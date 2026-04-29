package org.matsim.contrib.demand_extraction.algorithm.bamas.graph;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideKind;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

class DegreeGraphTest {

	@Test
	void findExtensionsRequiresSupportFromEverySubset() {
		DegreeGraph graph = DegreeGraph.buildFromRides(List.of(
				ride(1, 2, 3),
				ride(1, 3, 4),
				ride(2, 3, 4)), 3);

		assertArrayEquals(new int[0], graph.findExtensions(new int[] { 1, 2, 3 }));
	}

	@Test
	void containsSetUsesExactFeasibleDegreeCatalog() {
		DegreeGraph graph = DegreeGraph.buildFromRides(List.of(
				ride(1, 2, 3),
				ride(1, 3, 4),
				ride(2, 3, 4)), 3);

		assertTrue(graph.containsSet(new int[] { 1, 2, 3 }));
		assertFalse(graph.containsSet(new int[] { 1, 2, 4 }));
	}

	private static Ride ride(int... requestIndices) {
		DrtRequest[] requests = new DrtRequest[requestIndices.length];
		for (int i = 0; i < requestIndices.length; i++) {
			requests[i] = request(requestIndices[i]);
		}

		return Ride.builder()
				.index(0)
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
}