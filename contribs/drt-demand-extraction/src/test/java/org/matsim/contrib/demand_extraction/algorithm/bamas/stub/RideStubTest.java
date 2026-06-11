package org.matsim.contrib.demand_extraction.algorithm.bamas.stub;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideKind;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

/**
 * Round-trip trap-catcher for {@link RideStub}.
 *
 * <h3>The critical test</h3>
 * Uses non-contiguous global request indices {17, 4099, 65537} — values whose
 * low 4 bits collide ({@code 17 & 0xF == 1}, {@code 65537 & 0xF == 1}).  A
 * global-index-packing bug (packing global values directly into OrderingCodec)
 * CANNOT survive this test: the reconstructed orderings would be wrong.  Only
 * the correct local-position encoding passes.
 *
 * <h3>Fixture note</h3>
 * {@code rideDistance} and {@code rideTravelTime} are computed from connection
 * arrays by {@link Ride}'s constructor.  We set a single nonzero connection
 * element to avoid floating-point summation error and hit exactly 1234.5 m /
 * 678.9 s.  Expected stub integers: distDm=12345, ttDs=6789.
 */
class RideStubTest {

    // ── helpers (reuse pattern from MaterializedRideStoreTest) ────────────────

    private static DrtRequest request(int globalIndex) {
        return DrtRequest.builder()
                .index(globalIndex)
                .personId(Id.createPersonId("person-" + globalIndex))
                .groupId("group-" + globalIndex)
                .tripIndex(0)
                .originLinkId(Id.createLinkId("origin-" + globalIndex))
                .destinationLinkId(Id.createLinkId("dest-" + globalIndex))
                .requestTime(0.0)
                .earliestDeparture(0.0)
                .latestArrival(10.0)
                .directTravelTime(1.0)
                .directDistance(1.0)
                .maxDetourFactor(1.0)
                .budget(0.0)
                .bestModeScore(0.0)
                .bestMode("car")
                .originActivityType("home")
                .destinationActivityType("work")
                .carTravelTime(1.0)
                .ptTravelTime(1.0)
                .ptAccessibility(1.0)
                .build();
    }

    /**
     * Build a degree-3 Ride with:
     * <ul>
     *   <li>request set (in requests[] order): {17, 4099, 65537}</li>
     *   <li>pickup order: {4099, 17, 65537}  (global indices)</li>
     *   <li>dropoff order: {17, 65537, 4099} (global indices)</li>
     *   <li>rideDistance = 1234.5 m  → distDm = 12345</li>
     *   <li>rideTravelTime = 678.9 s → ttDs   = 6789</li>
     *   <li>kind = FIFO</li>
     * </ul>
     *
     * <p>rideDistance / rideTravelTime are derived by the Ride constructor from
     * the connection arrays (sum of connectionDistances / connectionTravelTimes).
     * We place the full value in the first element and zero the rest to avoid
     * any floating-point summation error.  Degree-3 requires 2*3-1 = 5 connection
     * segments.
     */
    private static Ride buildTestRide() {
        DrtRequest req17    = request(17);
        DrtRequest req4099  = request(4099);
        DrtRequest req65537 = request(65537);

        // requests[] — the canonical "index order" array (order doesn't matter for
        // the round-trip test; we use ascending global order for clarity).
        DrtRequest[] requests = { req17, req4099, req65537 };

        // Pickup order: 4099 first, then 17, then 65537
        DrtRequest[] origins = { req4099, req17, req65537 };

        // Dropoff order: 17 first, then 65537, then 4099
        DrtRequest[] dests = { req17, req65537, req4099 };

        // Connection arrays: degree-3 → 5 segments (2*3-1).
        // Place the full metric in index 0 and zero the rest to get exact sums.
        double[] connDist = { 1234.5, 0.0, 0.0, 0.0, 0.0 };
        double[] connTT   = {  678.9, 0.0, 0.0, 0.0, 0.0 };
        double[] connUtil = {    0.0, 0.0, 0.0, 0.0, 0.0 };

        return Ride.builder()
                .index(0)
                .degree(3)
                .kind(RideKind.FIFO)
                .requests(requests)
                .originsOrderedRequests(origins)
                .destinationsOrderedRequests(dests)
                .passengerTravelTimes(new double[3])
                .passengerDistances(new double[3])
                .passengerNetworkUtilities(new double[3])
                .delays(new double[3])
                .detours(new double[3])
                .connectionTravelTimes(connTT)
                .connectionDistances(connDist)
                .connectionNetworkUtilities(connUtil)
                .startTime(0.0)
                .build();
    }

    // ── main round-trip test ──────────────────────────────────────────────────

    /**
     * Core trap-catcher: global request indices {17, 4099, 65537} have colliding
     * low 4 bits (17 and 65537 both end in {@code 0x1}).  Only the correct
     * local-position encoding survives this round-trip.
     */
    @Test
    void roundTripWithNonContiguousGlobalIndices() {
        Ride ride = buildTestRide();

        // Sanity-check the fixture before testing the stub.
        assertArrayEquals(new int[]{ 4099, 17, 65537 }, ride.getOriginsIndex(),
                "fixture: pickup order must be {4099, 17, 65537}");
        assertArrayEquals(new int[]{ 17, 65537, 4099 }, ride.getDestinationsIndex(),
                "fixture: dropoff order must be {17, 65537, 4099}");
        assertEquals(1234.5, ride.getRideDistance(), 1e-9,
                "fixture: rideDistance must be 1234.5");
        assertEquals(678.9, ride.getRideTravelTime(), 1e-9,
                "fixture: rideTravelTime must be 678.9");

        // Extract stub.
        RideStub stub = RideStub.fromRide(ride);

        // (a) sortedSet, distDm, ttDs, kind
        assertArrayEquals(new int[]{ 17, 4099, 65537 }, stub.sortedSet,
                "sortedSet must be ascending: {17, 4099, 65537}");
        assertEquals(12345, stub.distDm,
                "distDm must be toDeci(1234.5) == 12345");
        assertEquals(6789, stub.ttDs,
                "ttDs must be toDeci(678.9) == 6789");
        assertEquals(RideKind.FIFO, RideStub.flagsToKind(stub.flags),
                "kind round-trip via flags must reproduce FIFO");

        // (b) THE TRAP-CATCHER: ordering round-trip must reproduce exact global arrays.
        // A global-packing bug (packing 4099 directly → 4099 & 0xF = 3, wrong local)
        // would map back to sortedSet[3] which is out-of-bounds → AIOOBE, or if it
        // happened to be in-bounds would produce the wrong global index.
        assertArrayEquals(ride.getOriginsIndex(), stub.originsGlobal(),
                "originsGlobal() must reproduce getOriginsIndex() exactly");
        assertArrayEquals(ride.getDestinationsIndex(), stub.destsGlobal(),
                "destsGlobal() must reproduce getDestinationsIndex() exactly");
    }

    // ── per-kind flag encoding ─────────────────────────────────────────────────

    @Test
    void allRideKindsRoundTripThroughFlags() {
        for (RideKind kind : RideKind.values()) {
            byte flags = RideStub.kindToFlags(kind);
            assertEquals(kind, RideStub.flagsToKind(flags),
                    "kind " + kind + " must survive kindToFlags/flagsToKind round-trip");
        }
    }

    // ── negative guard: inconsistent ordering index throws ────────────────────

    @Test
    void fromRideThrowsWhenOrderingIndexAbsentFromRequestSet() {
        // Build a ride where originsOrderedRequests contains a request whose
        // index is NOT in the requests[] array — an inconsistent Ride state.
        // We achieve this by building two separate request objects with different
        // indices and using one in requests[] but a different one in origins[].
        DrtRequest req0 = request(0);
        DrtRequest req1 = request(1);
        DrtRequest req2 = request(2);
        // Malformed: requests = {0, 1}, origins = {0, 99} where 99 is not in set.
        DrtRequest req99 = request(99);

        DrtRequest[] requests = { req0, req1 };
        DrtRequest[] badOrigins = { req0, req99 }; // 99 not in {0,1}
        DrtRequest[] dests = { req0, req1 };

        Ride badRide = Ride.builder()
                .index(0)
                .degree(2)
                .kind(RideKind.FIFO)
                .requests(requests)
                .originsOrderedRequests(badOrigins)
                .destinationsOrderedRequests(dests)
                .passengerTravelTimes(new double[2])
                .passengerDistances(new double[2])
                .passengerNetworkUtilities(new double[2])
                .delays(new double[2])
                .detours(new double[2])
                .connectionTravelTimes(new double[3])  // 2*2-1 = 3
                .connectionDistances(new double[3])
                .connectionNetworkUtilities(new double[3])
                .startTime(0.0)
                .build();

        assertThrows(IllegalStateException.class, () -> RideStub.fromRide(badRide),
                "fromRide must throw IllegalStateException when an ordering index is absent from the request set");
    }

    // ── degree accessor ───────────────────────────────────────────────────────

    @Test
    void degreeEqualsSortedSetLength() {
        Ride ride = buildTestRide();
        RideStub stub = RideStub.fromRide(ride);
        assertEquals(3, stub.degree(), "degree() must equal sortedSet.length for a degree-3 ride");
    }
}
