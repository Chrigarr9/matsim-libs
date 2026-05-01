package org.matsim.contrib.demand_extraction.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideKind;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

class ExMasCsvWriterTest {

	@TempDir
	Path tempDir;

	@Test
	void writeRideBatches_writesCompletedAndPartialRidesIntoSingleCsvInIndexOrder() throws Exception {
		DrtRequest request0 = req(0, 1_000.0);
		DrtRequest request1 = req(1, 1_100.0);
		DrtRequest request2 = req(2, 1_200.0);

		Ride completedPair = ride(1, new DrtRequest[] { request0, request1 }, 1_500.0);
		Ride completedSingle = ride(0, new DrtRequest[] { request0 }, 1_000.0);
		Ride partialTriple = ride(2, new DrtRequest[] { request0, request1, request2 }, 1_900.0);

		Path csv = tempDir.resolve("r1-partial-rides.csv");

		ExMasCsvWriter.writeRideBatches(
				csv.toString(),
				List.of(completedPair, completedSingle),
				List.of(partialTriple));

		assertTrue(Files.exists(csv));

		List<String> lines = Files.readAllLines(csv);
		assertEquals(4, lines.size());
		assertTrue(lines.get(0).startsWith("rideIndex,degree,kind,variant,"));
		assertEquals(List.of("0", "1", "2"), lines.subList(1, 4).stream()
				.map(line -> line.substring(0, line.indexOf(',')))
				.toList());
	}

	private static DrtRequest req(int index, double directDistance) {
		return new DrtRequest.Builder()
				.index(index)
				.personId(Id.createPersonId("p" + index))
				.groupId("g" + index)
				.tripIndex(0)
				.originLinkId(Id.createLinkId("from-" + index))
				.destinationLinkId(Id.createLinkId("to-" + index))
				.directDistance(directDistance)
				.directTravelTime(0)
				.earliestDeparture(0)
				.latestArrival(0)
				.originX(0)
				.originY(0)
				.destinationX(0)
				.destinationY(0)
				.requestTime(0)
				.build();
	}

	private static Ride ride(int index, DrtRequest[] requests, double rideDistance) {
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
				.connectionDistances(new double[] { rideDistance })
				.connectionNetworkUtilities(new double[] { 0.0 })
				.startTime(0)
				.build();
	}
}