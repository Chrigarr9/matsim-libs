package org.matsim.contrib.demand_extraction.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

/**
 * Verifies that {@link ExMasCsvWriter#writeRequests(String, List)} emits the
 * Extension-2 schema additions as the last columns of {@code drt_requests.csv}:
 * {@code requestTag}, {@code hubId}, {@code hubLegRole}, {@code transferWaitSeconds},
 * {@code marginalUtilityOfMoney}. Null values render as empty string / "NONE" / 0.0
 * respectively.
 *
 * <p>Schema discipline: all five columns are APPENDED — never inserted in the
 * middle — so any downstream tooling that reads by column name still works,
 * and tooling that walks trailing columns also picks them up.
 */
class ExMasCsvWriterExtension2ColumnsTest {

    @TempDir
    Path tempDir;

    @Test
    void writeRequests_emitsRequestTagAndHubIdAsLastTwoColumns() throws Exception {
        DrtRequest ruralIntra = req(0, "p_rural", "rural_intra", null);
        DrtRequest urbanIntra = req(1, "p_urban", "urban_intra", null);
        DrtRequest connecting = req(2, "p_connect", "connecting", "hub_03");
        DrtRequest external   = req(3, "p_external", "external", null);

        Path csv = tempDir.resolve("drt_requests.csv");

        ExMasCsvWriter.writeRequests(csv.toString(),
                List.of(ruralIntra, urbanIntra, connecting, external));

        assertTrue(Files.exists(csv));

        List<String> lines = Files.readAllLines(csv);
        // Header + 4 data rows.
        assertEquals(5, lines.size());

        String header = lines.get(0);
        // Trailing columns must be exactly the five Extension-2 additions (appended).
        assertTrue(header.endsWith(",requestTag,hubId,hubLegRole,transferWaitSeconds,marginalUtilityOfMoney"),
                "Header must end with requestTag,hubId,hubLegRole,transferWaitSeconds,marginalUtilityOfMoney — was: " + header);

        // Pre-existing leading columns survive untouched (sanity check).
        assertTrue(header.startsWith("index,personId,groupId,tripIndex,"),
                "Existing leading header schema unchanged — was: " + header);

        String[] headerCols = header.split(",", -1);
        int tagIdx  = headerCols.length - 5;
        int hubIdx  = headerCols.length - 4;
        int roleIdx = headerCols.length - 3;
        assertEquals("requestTag",              headerCols[tagIdx]);
        assertEquals("hubId",                   headerCols[hubIdx]);
        assertEquals("hubLegRole",              headerCols[roleIdx]);
        assertEquals("transferWaitSeconds",     headerCols[headerCols.length - 2]);
        assertEquals("marginalUtilityOfMoney",  headerCols[headerCols.length - 1]);

        // Per-row tag/hubId values match insertion order; null hubId renders empty;
        // hubLegRole defaults to "NONE" for plain (non-virtual) requests.
        assertRow(lines.get(1), headerCols.length, tagIdx, hubIdx, roleIdx, "rural_intra", "", "NONE");
        assertRow(lines.get(2), headerCols.length, tagIdx, hubIdx, roleIdx, "urban_intra", "", "NONE");
        assertRow(lines.get(3), headerCols.length, tagIdx, hubIdx, roleIdx, "connecting", "hub_03", "NONE");
        assertRow(lines.get(4), headerCols.length, tagIdx, hubIdx, roleIdx, "external", "", "NONE");
    }

    private static void assertRow(String line, int expectedCols, int tagIdx, int hubIdx, int roleIdx,
                                   String expectedTag, String expectedHubId, String expectedRole) {
        // -1 limit so trailing empty field still becomes a real column.
        String[] cols = line.split(",", -1);
        assertEquals(expectedCols, cols.length,
                "Row column count mismatch — line: " + line);
        assertEquals(expectedTag, cols[tagIdx],
                "requestTag mismatch — line: " + line);
        assertEquals(expectedHubId, cols[hubIdx],
                "hubId mismatch (null must serialise as empty string) — line: " + line);
        assertEquals(expectedRole, cols[roleIdx],
                "hubLegRole mismatch (null must serialise as NONE) — line: " + line);
    }

    /**
     * Builds a minimal {@link DrtRequest} with the supplied Extension-2 fields.
     * Goes through {@link DrtRequest.Builder} directly so we don't need a
     * Scenario/scoring config — same approach as
     * {@code demand.TestRequestBuilder} in the sibling test package (which is
     * package-private, hence the duplication here).
     */
    private static DrtRequest req(int index, String personIdStr, String requestTag, String hubId) {
        return DrtRequest.builder()
                .index(index)
                .personId(Id.createPersonId(personIdStr))
                .groupId(personIdStr + "_g0")
                .tripIndex(0)
                .isCommute(false)
                .isEducation(false)
                .budget(0.0)
                .bestModeScore(0.0)
                .bestMode("walk")
                .requestTag(requestTag)
                .hubId(hubId)
                .originLinkId(Id.createLinkId("l_o"))
                .destinationLinkId(Id.createLinkId("l_d"))
                .originX(0.0).originY(0.0)
                .destinationX(1000.0).destinationY(0.0)
                .originLinkCoordFromX(0.0).originLinkCoordFromY(0.0)
                .originLinkCoordToX(0.0).originLinkCoordToY(0.0)
                .destinationLinkCoordFromX(1000.0).destinationLinkCoordFromY(0.0)
                .destinationLinkCoordToX(1000.0).destinationLinkCoordToY(0.0)
                .requestTime(0.0)
                .earliestDeparture(0.0)
                .latestArrival(600.0)
                .directTravelTime(600.0)
                .directDistance(1000.0)
                .maxDetourFactor(1.5)
                .originActivityType("home")
                .destinationActivityType("work")
                .carTravelTime(600.0)
                .ptTravelTime(900.0)
                .ptAccessibility(1.5)
                .build();
    }
}
