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
 * EXP-7: hyperpool_rides.csv must carry per-pax {@code requestIndices} and
 * {@code personIds} (mirroring getRequests() order) so each per-pax
 * role/hub/delay/budget position can be attributed to a commuter — appended at
 * the END of the header per the module's additive-schema convention.
 */
class ExMasCsvWriterHyperPoolLinkageColumnsTest {

    @TempDir
    Path tempDir;

    @Test
    void writeHyperPooledRides_appendsRequestIndicesAndPersonIds() throws Exception {
        DrtRequest paxA = req(17, "p_A");
        DrtRequest paxB = req(4, "p_B");
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
                .passengerDelays(zeros)
                .totalRideTime(0.0)
                .totalRideDistance(0.0)
                .startTime(0.0)
                .endTime(0.0)
                .build();

        Path csv = tempDir.resolve("hyperpool_rides.csv");
        ExMasCsvWriter.writeHyperPooledRides(csv.toString(), List.of(ride));

        List<String> lines = Files.readAllLines(csv);
        String header = lines.get(0);
        assertTrue(header.endsWith(",peak_pax,requestIndices,personIds"),
                "linkage columns must be appended at the END — was: " + header);

        String[] headerCols = header.split(",", -1);
        String[] cols = lines.get(1).split(",", -1);
        assertEquals(headerCols.length, cols.length);

        int reqIdx = Arrays.asList(headerCols).indexOf("requestIndices");
        int pidIdx = Arrays.asList(headerCols).indexOf("personIds");
        // getRequests() order — same order as every other per-pax column.
        assertEquals("[17 | 4]", cols[reqIdx]);
        assertEquals("[p_A | p_B]", cols[pidIdx]);
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
