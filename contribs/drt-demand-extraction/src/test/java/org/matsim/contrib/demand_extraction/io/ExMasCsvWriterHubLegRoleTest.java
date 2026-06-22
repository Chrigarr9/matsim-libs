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
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideKind;
import org.matsim.contrib.demand_extraction.algorithm.domain.StopLocation;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

/**
 * TDD: Verifies that {@link ExMasCsvWriter#writeRides(String, List)} and
 * {@link ExMasCsvWriter#writeHyperPooledRides(String, List)} emit a per-pax
 * {@code hubLegRoles} column immediately after {@code hubIds} and before
 * {@code peak_pax} in both writer blocks.
 *
 * <p>Column ordering required: {@code ...,requestTags,hubIds,hubLegRoles,peak_pax,...}
 *
 * <p>Per-pax values use the {@code [a | b | c]} format. {@code NONE} is the
 * default for requests that have no {@link DrtRequest.HubLegRole} set.
 */
class ExMasCsvWriterHubLegRoleTest {

    @TempDir
    Path tempDir;

    // -------------------------------------------------------------------------
    // Block A — regular rides writer (writeRides / writeRideRow)
    // -------------------------------------------------------------------------

    @Test
    void writeRides_headerContainsHubLegRolesAfterHubIds() throws Exception {
        DrtRequest none = req(0, "p_none", DrtRequest.HubLegRole.NONE);
        DrtRequest contLeg = req(1, "p_cont", DrtRequest.HubLegRole.CONTINUATION_LEG);

        Ride pair = ride(0, new DrtRequest[]{none, contLeg}, 1_500.0);

        Path csv = tempDir.resolve("exmas_rides.csv");
        ExMasCsvWriter.writeRides(csv.toString(), List.of(pair));

        assertTrue(Files.exists(csv));
        List<String> lines = Files.readAllLines(csv);
        assertEquals(2, lines.size(), "expected header + 1 data row");

        String header = lines.get(0);
        assertTrue(header.contains(",hubIds,hubLegRoles,"),
                "Header must contain ,hubIds,hubLegRoles, — was: " + header);
        assertTrue(header.contains(",hubLegRoles,peak_pax"),
                "hubLegRoles must immediately precede peak_pax — was: " + header);
    }

    @Test
    void writeRides_rowContainsCorrectHubLegRolesValues() throws Exception {
        DrtRequest none = req(0, "p_none", DrtRequest.HubLegRole.NONE);
        DrtRequest contLeg = req(1, "p_cont", DrtRequest.HubLegRole.CONTINUATION_LEG);

        Ride pair = ride(0, new DrtRequest[]{none, contLeg}, 1_500.0);

        Path csv = tempDir.resolve("exmas_rides.csv");
        ExMasCsvWriter.writeRides(csv.toString(), List.of(pair));

        List<String> lines = Files.readAllLines(csv);
        String header = lines.get(0);
        String[] headerCols = header.split(",", -1);
        List<String> headerList = Arrays.asList(headerCols);
        int rolesIdx = headerList.indexOf("hubLegRoles");
        assertTrue(rolesIdx >= 0, "hubLegRoles column must be present in header");

        String[] cols = lines.get(1).split(",", -1);
        assertEquals(headerCols.length, cols.length,
                "Row column count must match header — line: " + lines.get(1));
        assertEquals("[NONE | CONTINUATION_LEG]", cols[rolesIdx],
                "hubLegRoles values must reflect per-pax roles — line: " + lines.get(1));
    }

    // -------------------------------------------------------------------------
    // Block B — hyper-pooled rides writer (writeHyperPooledRides)
    // -------------------------------------------------------------------------

    @Test
    void writeHyperPooledRides_headerContainsHubLegRolesAfterHubIds() throws Exception {
        DrtRequest none = req(0, "p_none", DrtRequest.HubLegRole.NONE);
        DrtRequest accessLeg = req(1, "p_access", DrtRequest.HubLegRole.ACCESS_LEG);

        HyperPooledRide hyperRide = hyperRide(0, new DrtRequest[]{none, accessLeg});

        Path csv = tempDir.resolve("hyperpool_rides.csv");
        ExMasCsvWriter.writeHyperPooledRides(csv.toString(), List.of(hyperRide));

        assertTrue(Files.exists(csv));
        List<String> lines = Files.readAllLines(csv);
        assertEquals(2, lines.size(), "expected header + 1 data row");

        String header = lines.get(0);
        assertTrue(header.contains(",hubIds,hubLegRoles,"),
                "Header must contain ,hubIds,hubLegRoles, — was: " + header);
        assertTrue(header.endsWith(",hubLegRoles,peak_pax"),
                "hubLegRoles must immediately precede peak_pax (last column) — was: " + header);
    }

    @Test
    void writeHyperPooledRides_rowContainsCorrectHubLegRolesValues() throws Exception {
        DrtRequest none = req(0, "p_none", DrtRequest.HubLegRole.NONE);
        DrtRequest accessLeg = req(1, "p_access", DrtRequest.HubLegRole.ACCESS_LEG);

        HyperPooledRide hyperRide = hyperRide(0, new DrtRequest[]{none, accessLeg});

        Path csv = tempDir.resolve("hyperpool_rides.csv");
        ExMasCsvWriter.writeHyperPooledRides(csv.toString(), List.of(hyperRide));

        List<String> lines = Files.readAllLines(csv);
        String header = lines.get(0);
        String[] headerCols = header.split(",", -1);
        List<String> headerList = Arrays.asList(headerCols);
        int rolesIdx = headerList.indexOf("hubLegRoles");
        assertTrue(rolesIdx >= 0, "hubLegRoles column must be present in header");

        String[] cols = lines.get(1).split(",", -1);
        assertEquals(headerCols.length, cols.length,
                "Row column count must match header — line: " + lines.get(1));
        assertEquals("[NONE | ACCESS_LEG]", cols[rolesIdx],
                "hubLegRoles values must reflect per-pax roles — line: " + lines.get(1));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static DrtRequest req(int index, String personIdStr, DrtRequest.HubLegRole hubLegRole) {
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
                .requestTag("rural_intra")
                .hubId(null)
                .hubLegRole(hubLegRole)
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
                .connectionTravelTimes(new double[]{0.0})
                .connectionDistances(new double[]{rideDistance})
                .connectionNetworkUtilities(new double[]{0.0})
                .startTime(0)
                .build();
    }

    private static HyperPooledRide hyperRide(int index, DrtRequest[] requests) {
        int degree = requests.length;
        double[] zeros = new double[degree];
        StopLocation pickup = new StopLocation(Id.createLinkId("stop-pickup"), new Coord(0.0, 0.0), 0.0);
        StopLocation dropoff = new StopLocation(Id.createLinkId("stop-dropoff"), new Coord(1_000.0, 0.0), 0.0);
        int[] boarding = new int[degree];   // all board at stop 0
        int[] alighting = new int[degree];
        Arrays.fill(alighting, 1);          // all alight at stop 1
        return HyperPooledRide.builder()
                .index(index)
                .stopSequence(new StopLocation[]{pickup, dropoff})
                .requests(requests)
                .boardingStopIndices(boarding)
                .alightingStopIndices(alighting)
                .accessWalkDistances(zeros)
                .egressWalkDistances(zeros)
                .inVehicleTimes(zeros)
                .remainingBudgets(zeros)
                .totalRideTime(0.0)
                .totalRideDistance(0.0)
                .startTime(0.0)
                .endTime(0.0)
                .build();
    }
}
