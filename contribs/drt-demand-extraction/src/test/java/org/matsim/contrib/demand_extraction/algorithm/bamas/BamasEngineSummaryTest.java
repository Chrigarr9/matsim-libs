package org.matsim.contrib.demand_extraction.algorithm.bamas;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideKind;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

class BamasEngineSummaryTest {

	@Test
	void summarizeRideCountsUsesSurvivingRidesPerDegree() throws Exception {
		List<Ride> rides = List.of(
				ride(1, request(1, 1000.0)),
				ride(2, request(2, 1000.0)),
				ride(3, request(1, 1000.0), request(2, 1000.0)),
				ride(4, request(1, 1000.0), request(2, 1000.0), request(3, 1000.0))
		);

		Method summarizeRideCounts = BamasEngine.class.getDeclaredMethod("summarizeRideCounts", List.class);
		summarizeRideCounts.setAccessible(true);

		int[] counts = (int[]) summarizeRideCounts.invoke(null, rides);

		assertArrayEquals(new int[] { 2, 1, 1 }, counts);
	}

	private static DrtRequest request(int index, double directDistance) {
		return new DrtRequest.Builder()
				.index(index)
				.personId(Id.createPersonId("p" + index))
				.directDistance(directDistance)
				.directTravelTime(0.0)
				.earliestDeparture(0.0)
				.latestArrival(0.0)
				.build();
	}

	private static Ride ride(int index, DrtRequest... requests) {
		int degree = requests.length;
		double[] zeros = new double[degree];
		RideKind kind = degree == 1 ? RideKind.SINGLE : RideKind.FIFO;
		return Ride.builder()
				.index(index)
				.degree(degree)
				.kind(kind)
				.requests(requests)
				.originsOrderedRequests(requests)
				.destinationsOrderedRequests(requests)
				.passengerTravelTimes(zeros)
				.passengerDistances(zeros)
				.passengerNetworkUtilities(zeros)
				.delays(zeros)
				.detours(zeros)
				.connectionTravelTimes(new double[] { 0.0 })
				.connectionDistances(new double[] { 0.0 })
				.connectionNetworkUtilities(new double[] { 0.0 })
				.startTime(0.0)
				.build();
	}
}