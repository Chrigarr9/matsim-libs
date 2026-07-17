package org.matsim.contrib.demand_extraction.io;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.demand_extraction.algorithm.domain.HyperPooledRide;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideKind;
import org.matsim.contrib.demand_extraction.algorithm.domain.StopLocation;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

/**
 * EXP-5: utility (utils-of-money) columns — remainingBudgets, shapleyValues,
 * maxCosts — must be emitted at %.4f. %.2f (0.01 utils ≈ 1 EUR-equivalent at
 * eqasim margUtilOfMoney) quantizes serve/reject decisions at the MIP boundary
 * 100x coarser than the request-side budget/baseModeScore columns. Timing and
 * distance columns stay at %.2f.
 */
class ExMasCsvWriterUtilityPrecisionTest {

    @TempDir
    Path tempDir;

    @Test
    void writeRides_emitsUtilityColumnsAtFourDecimals() throws Exception {
        DrtRequest pax = req(0, "p_A");
        Ride r = Ride.builder()
                .index(0).degree(1).kind(RideKind.SINGLE)
                .requests(new DrtRequest[]{pax})
                .originsOrderedRequests(new DrtRequest[]{pax})
                .destinationsOrderedRequests(new DrtRequest[]{pax})
                .passengerTravelTimes(new double[]{1.0})
                .passengerDistances(new double[]{1.0})
                .passengerNetworkUtilities(new double[]{0.0})
                .delays(new double[]{1.0})
                .detours(new double[]{1.0})
                .remainingBudgets(new double[]{0.123456})
                .maxCosts(new double[]{1.234567})
                .shapleyValues(new double[]{2.345678})
                .connectionTravelTimes(new double[]{0.0})
                .connectionDistances(new double[]{100.0})
                .connectionNetworkUtilities(new double[]{0.0})
                .startTime(0)
                .build();

        Path csv = tempDir.resolve("exmas_rides.csv");
        ExMasCsvWriter.writeRides(csv.toString(), List.of(r));

        List<String> lines = Files.readAllLines(csv);
        List<String> header = Arrays.asList(lines.get(0).split(",", -1));
        String[] cols = lines.get(1).split(",", -1);

        assertEquals("[0.1235]", cols[header.indexOf("remainingBudgets")]);
        assertEquals("[1.2346]", cols[header.indexOf("maxCosts")]);
        assertEquals("[2.3457]", cols[header.indexOf("shapleyValues")]);
        // Timing columns stay at two decimals.
        assertEquals("[1.00]", cols[header.indexOf("delays")]);
    }

    @Test
    void writeHyperPooledRides_emitsRemainingBudgetsAtFourDecimals() throws Exception {
        DrtRequest pax = req(0, "p_A");
        StopLocation pickup = new StopLocation(Id.createLinkId("s0"), new Coord(0, 0), 0.0);
        StopLocation dropoff = new StopLocation(Id.createLinkId("s1"), new Coord(500, 0), 0.0);
        HyperPooledRide ride = HyperPooledRide.builder()
                .index(0)
                .stopSequence(new StopLocation[]{pickup, dropoff})
                .requests(new DrtRequest[]{pax})
                .boardingStopIndices(new int[]{0})
                .alightingStopIndices(new int[]{1})
                .accessWalkDistances(new double[]{0.0})
                .egressWalkDistances(new double[]{0.0})
                .inVehicleTimes(new double[]{0.0})
                .remainingBudgets(new double[]{0.123456})
                .passengerDelays(new double[]{0.0})
                .totalRideTime(0.0).totalRideDistance(0.0)
                .startTime(0.0).endTime(0.0)
                .build();

        Path csv = tempDir.resolve("hyperpool_rides.csv");
        ExMasCsvWriter.writeHyperPooledRides(csv.toString(), List.of(ride));

        List<String> lines = Files.readAllLines(csv);
        List<String> header = Arrays.asList(lines.get(0).split(",", -1));
        String[] cols = lines.get(1).split(",", -1);
        assertEquals("[0.1235]", cols[header.indexOf("remainingBudgets")]);
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
