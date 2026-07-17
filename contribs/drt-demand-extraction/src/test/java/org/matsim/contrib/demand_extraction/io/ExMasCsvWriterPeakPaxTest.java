package org.matsim.contrib.demand_extraction.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
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
 * Verifies that {@link ExMasCsvWriter#writeRides(String, List)} and
 * {@link ExMasCsvWriter#writeHyperPooledRides(String, List)} emit the
 * Extension-2 {@code peak_pax} column — the max simultaneous in-vehicle
 * occupancy over each ride's stop sequence.
 *
 * <p>{@code peak_pax} is appended at the END of the header (after
 * Task 1.3/1.4's {@code requestTags,hubIds}) — never inserted in the middle —
 * so existing consumers that read by column name or trailing position keep
 * working.
 *
 * <p>Two structural cases are covered:
 *
 * <ul>
 *   <li>Regular {@link Ride}: the implicit stop sequence is always
 *       {@code [all pickups in origin-order, then all dropoffs in
 *       destination-order]}, so the sweep yields {@code peak_pax == degree}
 *       by construction. Verified on a degree-2 ride (peak=2).</li>
 *   <li>{@link HyperPooledRide}: the stop sequence can interleave pickups
 *       and dropoffs, so peak can be strictly less than degree. Verified
 *       on the rev-3 §7.4b reference fixture: 3 pax with the boarding
 *       sequence pickup-A, pickup-B, dropoff-B, dropoff-A, pickup-C,
 *       dropoff-C — A and B are both in-vehicle between stops 1 and 2
 *       (peak=2), C enters alone after A and B have alighted (peak=1).
 *       At no point are all three in the vehicle. Expected
 *       {@code peak_pax == 2}.</li>
 * </ul>
 */
class ExMasCsvWriterPeakPaxTest {

    @TempDir
    Path tempDir;

    @Test
    void writeRides_appendsPeakPaxAtEndOfHeader_equalsDegreeForDoorToDoor() throws Exception {
        // Door-to-door / FIFO Ride: stop sequence is [pickup-A, pickup-B,
        // dropoff-A, dropoff-B] (all pickups, then all dropoffs). Both pax
        // are in-vehicle between stops 1 and 2 -> peak = 2 = degree.
        DrtRequest paxA = req(0, "p_A", "rural_intra", null);
        DrtRequest paxB = req(1, "p_B", "rural_intra", null);

        Ride pair = ride(0, new DrtRequest[] { paxA, paxB }, 1_500.0);

        Path csv = tempDir.resolve("exmas_rides.csv");
        ExMasCsvWriter.writeRides(csv.toString(), List.of(pair));

        assertTrue(Files.exists(csv));
        List<String> lines = Files.readAllLines(csv);
        assertEquals(2, lines.size(), "expected header + 1 data row");

        String header = lines.get(0);
        // peak_pax is appended after requestTags,hubIds,hubLegRoles — never
        // inserted in the middle. (reposTimeMeanOutgoing follows it as the
        // final column, so match by adjacency/name rather than asserting
        // peak_pax is dead last.)
        assertTrue(header.contains(",requestTags,hubIds,hubLegRoles,peak_pax"),
                "Header must contain requestTags,hubIds,hubLegRoles,peak_pax in order — was: " + header);

        String[] headerCols = header.split(",", -1);
        int peakIdx = java.util.Arrays.asList(headerCols).indexOf("peak_pax");
        assertEquals("peak_pax", headerCols[peakIdx]);

        String[] cols = lines.get(1).split(",", -1);
        assertEquals(headerCols.length, cols.length,
                "Row column count must match header — line: " + lines.get(1));

        // Regular Ride: pickups always precede dropoffs in the sweep, so peak == degree.
        assertEquals("2", cols[peakIdx],
                "peak_pax for degree-2 Ride must equal degree (pickups precede dropoffs) — line: "
                        + lines.get(1));
    }

    @Test
    void writeHyperPooledRides_appendsPeakPaxAtEndOfHeader_capturesInterleavedPickupsAndDropoffs()
            throws Exception {
        // Rev-3 §7.4b reference fixture: 3 pax, stop sequence interleaves
        // pickups and dropoffs so peak in-vehicle < degree.
        //
        // Vehicle stop sequence (indices 0..5):
        //   0: pickup-A    -> in-vehicle = {A}        -> 1
        //   1: pickup-B    -> in-vehicle = {A, B}     -> 2  <-- peak
        //   2: dropoff-B   -> in-vehicle = {A}        -> 1
        //   3: dropoff-A   -> in-vehicle = {}         -> 0
        //   4: pickup-C    -> in-vehicle = {C}        -> 1
        //   5: dropoff-C   -> in-vehicle = {}         -> 0
        //
        // Expected peak_pax = 2 (NEVER all 3 in the vehicle simultaneously).
        DrtRequest paxA = req(0, "p_A", "rural_intra", null);
        DrtRequest paxB = req(1, "p_B", "rural_intra", null);
        DrtRequest paxC = req(2, "p_C", "rural_intra", null);

        StopLocation s0 = stop("s0", 0.0, 0.0);
        StopLocation s1 = stop("s1", 100.0, 0.0);
        StopLocation s2 = stop("s2", 200.0, 0.0);
        StopLocation s3 = stop("s3", 300.0, 0.0);
        StopLocation s4 = stop("s4", 400.0, 0.0);
        StopLocation s5 = stop("s5", 500.0, 0.0);

        int degree = 3;
        double[] zeros = new double[degree];

        // A boards@0/alights@3, B boards@1/alights@2, C boards@4/alights@5.
        int[] boarding = new int[] { 0, 1, 4 };
        int[] alighting = new int[] { 3, 2, 5 };

        HyperPooledRide hyperRide = HyperPooledRide.builder()
                .index(0)
                .stopSequence(new StopLocation[] { s0, s1, s2, s3, s4, s5 })
                .requests(new DrtRequest[] { paxA, paxB, paxC })
                .boardingStopIndices(boarding)
                .alightingStopIndices(alighting)
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
        ExMasCsvWriter.writeHyperPooledRides(csv.toString(), List.of(hyperRide));

        assertTrue(Files.exists(csv));
        List<String> lines = Files.readAllLines(csv);
        assertEquals(2, lines.size(), "expected header + 1 data row");

        String header = lines.get(0);
        assertTrue(header.endsWith(",requestTags,hubIds,hubLegRoles,peak_pax"),
                "Header must end with requestTags,hubIds,hubLegRoles,peak_pax — was: " + header);

        String[] headerCols = header.split(",", -1);
        int peakIdx = headerCols.length - 1;
        assertEquals("peak_pax", headerCols[peakIdx]);

        String[] cols = lines.get(1).split(",", -1);
        assertEquals(headerCols.length, cols.length,
                "Row column count must match header — line: " + lines.get(1));

        // Interleaved fixture: peak = 2, never 3.
        assertEquals("2", cols[peakIdx],
                "peak_pax for interleaved 3-pax HyperPooledRide must be 2 — line: "
                        + lines.get(1));
    }

    private static DrtRequest req(int index, String personIdStr, String requestTag, String hubId) {
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
                .originX(0)
                .originY(0)
                .destinationX(0)
                .destinationY(0)
                .requestTime(0)
                .requestTag(requestTag)
                .hubId(hubId)
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

    private static StopLocation stop(String linkId, double x, double y) {
        return new StopLocation(Id.createLinkId(linkId), new Coord(x, y), 0.0);
    }
}
