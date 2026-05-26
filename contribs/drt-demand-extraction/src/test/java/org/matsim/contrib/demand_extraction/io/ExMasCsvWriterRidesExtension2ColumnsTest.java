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

/**
 * Verifies that {@link ExMasCsvWriter#writeRides(String, List)} emits the
 * Extension-2 schema additions ({@code requestTags} and {@code hubIds}) as the
 * last two per-pax columns of {@code exmas_rides.csv}.
 *
 * <p>Per-pax fields in {@code exmas_rides.csv} use Java's
 * {@code List.toString()}-like {@code [a | b | c]} format. The two new
 * columns mirror that formatting and the iteration order of the existing
 * {@code personIds} field. Null {@code hubId} values render as the empty
 * string within the {@code [...|...]} list.
 *
 * <p>Schema discipline: the two columns are APPENDED — never inserted in the
 * middle — so any downstream tooling that reads by column name still works.
 */
class ExMasCsvWriterRidesExtension2ColumnsTest {

    @TempDir
    Path tempDir;

    @Test
    void writeRides_emitsPerPaxRequestTagsAndHubIdsAsLastTwoColumns() throws Exception {
        // Two-pax synthetic ride: first pax is `connecting` with hub_03,
        // second pax is `rural_intra` with null hubId.
        DrtRequest connecting = req(0, "p_connect", "connecting", "hub_03");
        DrtRequest ruralIntra = req(1, "p_rural", "rural_intra", null);

        Ride pair = ride(0, new DrtRequest[] { connecting, ruralIntra }, 1_500.0);

        Path csv = tempDir.resolve("exmas_rides.csv");

        ExMasCsvWriter.writeRides(csv.toString(), List.of(pair));

        assertTrue(Files.exists(csv));

        List<String> lines = Files.readAllLines(csv);
        // Header + 1 data row.
        assertEquals(2, lines.size());

        String header = lines.get(0);
        // requestTags,hubIds remain APPENDED — never inserted in the middle.
        // Task 7.2 added peak_pax as a per-ride column AFTER hubIds. The
        // per-pax pair (requestTags, hubIds) still ends at headerCols[len-2]/[len-3].
        assertTrue(header.contains(",requestTags,hubIds,"),
                "Header must contain ,requestTags,hubIds, in order — was: " + header);

        // Pre-existing leading columns survive untouched (sanity check).
        assertTrue(header.startsWith("rideIndex,degree,kind,variant,"),
                "Existing leading header schema unchanged — was: " + header);

        String[] headerCols = header.split(",", -1);
        // requestTags / hubIds sit immediately before peak_pax (Task 7.2).
        int tagsIdx = headerCols.length - 3;
        int hubsIdx = headerCols.length - 2;
        assertEquals("requestTags", headerCols[tagsIdx]);
        assertEquals("hubIds", headerCols[hubsIdx]);

        // -1 limit so trailing empty fields still become real columns.
        String[] cols = lines.get(1).split(",", -1);
        assertEquals(headerCols.length, cols.length,
                "Row column count mismatch — line: " + lines.get(1));

        // Per-pax columns follow the same iteration order as personIds and use
        // the [a | b | c] List.toString()-like format. Null hubId for the
        // second pax renders as empty string within the [...|...] list.
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
