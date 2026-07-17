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
import org.matsim.contrib.demand_extraction.algorithm.domain.StopLocation;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

/**
 * Verifies that {@link ExMasCsvWriter#writeHyperPooledRides(String, List)}
 * emits the Extension-2 schema additions ({@code requestTags} and
 * {@code hubIds}) as the last two per-pax columns of {@code hyperpool_rides.csv}.
 *
 * <p>Mirrors {@link ExMasCsvWriterRidesExtension2ColumnsTest} for the
 * HyperPool-rides writer path (kept separate per the 2026-05-23 INV2 fix).
 *
 * <p>Per-pax fields in {@code hyperpool_rides.csv} use Java's
 * {@code List.toString()}-like {@code [a | b | c]} format. The two new
 * columns mirror that formatting and the iteration order of
 * {@link HyperPooledRide#getRequests()}. Null {@code hubId} values render as
 * the empty string within the {@code [...|...]} list.
 *
 * <p>Schema discipline: the two columns are APPENDED — never inserted in the
 * middle — so any downstream tooling that reads by column name still works.
 */
class ExMasCsvWriterHyperPoolExtension2ColumnsTest {

    @TempDir
    Path tempDir;

    @Test
    void writeHyperPooledRides_emitsPerPaxRequestTagsAndHubIdsAsLastTwoColumns() throws Exception {
        // Two-pax synthetic hyper-pooled ride: first pax is `connecting` with
        // hub_03, second pax is `rural_intra` with null hubId.
        DrtRequest connecting = req(0, "p_connect", "connecting", "hub_03");
        DrtRequest ruralIntra = req(1, "p_rural", "rural_intra", null);

        StopLocation pickupStop = new StopLocation(
                Id.createLinkId("stop-pickup"), new Coord(0.0, 0.0), 0.0);
        StopLocation dropoffStop = new StopLocation(
                Id.createLinkId("stop-dropoff"), new Coord(1_000.0, 0.0), 0.0);

        int degree = 2;
        double[] zeros = new double[degree];
        // boarding < alighting required by builder
        int[] boarding = new int[] { 0, 0 };
        int[] alighting = new int[] { 1, 1 };

        HyperPooledRide hyperRide = HyperPooledRide.builder()
                .index(42)
                .stopSequence(new StopLocation[] { pickupStop, dropoffStop })
                .requests(new DrtRequest[] { connecting, ruralIntra })
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
        // Header + 1 data row.
        assertEquals(2, lines.size());

        String header = lines.get(0);
        // requestTags,hubIds,hubLegRoles remain APPENDED — never inserted in the
        // middle. Task 7.2 added peak_pax AFTER hubIds; Task 3 inserted
        // hubLegRoles between hubIds and peak_pax. The per-pax triple
        // (requestTags, hubIds, hubLegRoles) sits immediately before peak_pax.
        assertTrue(header.contains(",requestTags,hubIds,hubLegRoles,"),
                "Header must contain ,requestTags,hubIds,hubLegRoles, in order — was: " + header);

        // Pre-existing leading columns survive untouched (sanity check).
        assertTrue(header.startsWith("rideIndex,degree,"),
                "Existing leading header schema unchanged — was: " + header);

        String[] headerCols = header.split(",", -1);
        // requestTags / hubIds / hubLegRoles sit immediately before peak_pax.
        List<String> headerList = java.util.Arrays.asList(headerCols);
        int tagsIdx = headerList.indexOf("requestTags");
        int hubsIdx = headerList.indexOf("hubIds");
        assertEquals("requestTags", headerCols[tagsIdx]);
        assertEquals("hubIds", headerCols[hubsIdx]);

        // -1 limit so trailing empty fields still become real columns.
        String[] cols = lines.get(1).split(",", -1);
        assertEquals(headerCols.length, cols.length,
                "Row column count mismatch — line: " + lines.get(1));

        // Per-pax columns follow the same iteration order as the requests
        // array and use the [a | b | c] List.toString()-like format. Null
        // hubId for the second pax renders as empty string within the
        // [...|...] list.
        assertEquals("[connecting | rural_intra]", cols[tagsIdx],
                "requestTags mismatch — line: " + lines.get(1));
        assertEquals("[hub_03 | ]", cols[hubsIdx],
                "hubIds mismatch (null must serialise as empty inside the list) — line: " + lines.get(1));
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
}
