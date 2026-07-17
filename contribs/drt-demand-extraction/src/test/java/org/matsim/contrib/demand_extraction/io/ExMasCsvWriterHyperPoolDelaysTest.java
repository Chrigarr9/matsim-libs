package org.matsim.contrib.demand_extraction.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.demand_extraction.algorithm.domain.HyperPooledRide;
import org.matsim.contrib.demand_extraction.algorithm.domain.StopLocation;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

/**
 * HYP-4: the exported {@code passengerDelays} column must carry the
 * HYPERPOOLED per-pax delays stored on the ride — not a concatenation of the
 * Stage-1 source-ride delays.
 */
class ExMasCsvWriterHyperPoolDelaysTest {

    @TempDir
    Path tempDir;

    @Test
    void writeHyperPooledRides_emitsRidePassengerDelays() throws Exception {
        DrtRequest paxA = req(0, "p_A");
        DrtRequest paxB = req(1, "p_B");
        StopLocation pickup = new StopLocation(Id.createLinkId("s0"), new Coord(0, 0), 0.0);
        StopLocation dropoff = new StopLocation(Id.createLinkId("s1"), new Coord(500, 0), 0.0);
        double[] zeros = new double[2];

        HyperPooledRide ride = HyperPooledRide.builder()
                .index(0)
                .stopSequence(new StopLocation[]{pickup, dropoff})
                .requests(new DrtRequest[]{paxA, paxB})
                .boardingStopIndices(new int[]{0, 0})
                .alightingStopIndices(new int[]{1, 1})
                .accessWalkDistances(zeros)
                .egressWalkDistances(zeros)
                .inVehicleTimes(zeros)
                .remainingBudgets(zeros)
                .passengerDelays(new double[]{12.34, 56.78})
                .totalRideTime(0.0)
                .totalRideDistance(0.0)
                .startTime(0.0)
                .endTime(0.0)
                .build();

        Path csv = tempDir.resolve("hyperpool_rides.csv");
        ExMasCsvWriter.writeHyperPooledRides(csv.toString(), List.of(ride));

        List<String> lines = Files.readAllLines(csv);
        String[] headerCols = lines.get(0).split(",", -1);
        int delaysIdx = Arrays.asList(headerCols).indexOf("passengerDelays");
        assertTrue(delaysIdx >= 0);
        String[] cols = lines.get(1).split(",", -1);
        assertEquals("[12.34 | 56.78]", cols[delaysIdx],
                "passengerDelays must come from HyperPooledRide.getPassengerDelays()");
    }

    private static DrtRequest req(int index, String personIdStr) {
        return new DrtRequest.Builder()
                .index(index)
                .personId(Id.createPersonId(personIdStr))
                .groupId(personIdStr + "_g0")
                .tripIndex(0)
                .originLinkId(Id.createLinkId("from-" + index))
                .destinationLinkId(Id.createLinkId("to-" + index))
                .directDistance(1_000.0)
                .directTravelTime(0)
                .earliestDeparture(0)
                .latestArrival(0)
                .originX(0).originY(0)
                .destinationX(0).destinationY(0)
                .requestTime(0)
                .requestTag("rural_intra")
                .build();
    }
}
