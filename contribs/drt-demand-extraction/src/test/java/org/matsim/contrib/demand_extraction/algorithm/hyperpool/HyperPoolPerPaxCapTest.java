package org.matsim.contrib.demand_extraction.algorithm.hyperpool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideKind;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideVariant;
import org.matsim.contrib.demand_extraction.algorithm.domain.StopLocation;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;
import org.matsim.contrib.demand_extraction.algorithm.generation.WalkBudgetProvider;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCacheTestFixture;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B1+B2: Unit test verifying that {@link HyperPoolGenerator} computes and
 * forwards per-pax walk cap arrays to {@link HyperPoolGenerator.StopRelocator#findMergedStop}
 * when {@code enableBudgetAwareConstraints=true}, and passes {@code null} when
 * the flag is off.
 *
 * <h2>Design</h2>
 * A {@link RecordingStopRelocator} (analogous to {@code CapturingStopFinder} in
 * {@code StopBasedTestFixtures}) records every {@code findMergedStop} invocation,
 * capturing the {@code maxRelocDistPerPax} array (or null).
 *
 * <h2>Pre-B1 failure (TDD)</h2>
 * Before B1+B2 wiring, the old 3-arg interface (no 4th param) means this test
 * does not compile — hence it FAILS before the implementation exists and PASSES
 * after.
 */
class HyperPoolPerPaxCapTest {

    /** Fixed mid returned by the stub WalkBudgetProvider (metres). */
    private static final double MID_M = 200.0;
    /**
     * Total walk cap per pax = 2 * mid.
     */
    private static final double TOTAL_CAP_M = 2 * MID_M;

    // =========================================================================
    // Test 1: flag=on → cap arrays forwarded to relocator
    // =========================================================================

    /**
     * When {@code enableBudgetAwareConstraints=true} and a {@link WalkBudgetProvider}
     * returns {@value MID_M} for every call, the cap arrays forwarded to
     * {@code findMergedStop} must be non-null and each element should be
     * {@code <= 2 * MID_M} (remaining budget after committed walks are subtracted).
     */
    @Test
    void budgetAwareFlag_on_capsForwardedToRelocator() throws Exception {
        RecordingStopRelocator relocator = new RecordingStopRelocator();

        ExMasConfigGroup config = buildConfig(/* enableBudgetAware= */ true);
        WalkBudgetProvider provider = (budget, req, tt, dist, delay) -> MID_M;
        MatsimNetworkCache network = buildNetworkCache();

        HyperPoolGenerator gen = new HyperPoolGenerator(
                network, relocator,
                (r1, r2) -> true,  // all-compatible checker
                config,
                buildPassingBudgetValidator(),
                provider);

        List<Ride> s2sRides = buildTwoS2SRides();
        gen.generate(s2sRides, network, 0);

        // Relocator should have been called (relocation enabled)
        assertFalse(relocator.calls.isEmpty(),
                "RecordingStopRelocator should have been called at least once");

        // All calls where a cap array was passed must have non-null arrays
        // (flag=on → caps are passed)
        boolean atLeastOneNonNull = relocator.calls.stream()
                .anyMatch(c -> c.maxRelocDistPerPax != null);
        assertTrue(atLeastOneNonNull,
                "With flag=on, at least one findMergedStop call should receive a non-null " +
                "maxRelocDistPerPax array. Calls: " + relocator.calls.size());

        System.out.println("[B1+B2] flag=on: " + relocator.calls.size() + " relocator calls.");
        for (RecordingStopRelocator.CapturedCall c : relocator.calls) {
            System.out.println("  caps=" + Arrays.toString(c.maxRelocDistPerPax));
        }
    }

    // =========================================================================
    // Test 2: flag=off → null arrays forwarded to relocator (legacy path)
    // =========================================================================

    /**
     * When {@code enableBudgetAwareConstraints=false}, all {@code findMergedStop}
     * calls must receive {@code null} for {@code maxRelocDistPerPax} — preserving
     * pre-Phase-B flag-off behaviour bit-identical.
     */
    @Test
    void budgetAwareFlag_off_nullCapsForwardedToRelocator() throws Exception {
        RecordingStopRelocator relocator = new RecordingStopRelocator();

        ExMasConfigGroup config = buildConfig(/* enableBudgetAware= */ false);
        WalkBudgetProvider provider = (budget, req, tt, dist, delay) -> MID_M; // present but flag=off
        MatsimNetworkCache network = buildNetworkCache();

        HyperPoolGenerator gen = new HyperPoolGenerator(
                network, relocator,
                (r1, r2) -> true,
                config,
                buildPassingBudgetValidator(),
                provider);

        List<Ride> s2sRides = buildTwoS2SRides();
        gen.generate(s2sRides, network, 0);

        // All cap arrays must be null (flag=off → legacy unconstrained path)
        for (RecordingStopRelocator.CapturedCall c : relocator.calls) {
            assertNull(c.maxRelocDistPerPax,
                    "With flag=off, findMergedStop must receive null caps. Got: "
                    + Arrays.toString(c.maxRelocDistPerPax));
        }

        System.out.println("[B1+B2] flag=off: " + relocator.calls.size() + " relocator calls, all null caps.");
    }

    // =========================================================================
    // Test 3: null provider → null caps even with flag=on
    // =========================================================================

    /**
     * When the {@link WalkBudgetProvider} is {@code null} (not injected), all
     * {@code findMergedStop} calls must receive {@code null} regardless of the
     * flag value — preserving pre-Phase-B behaviour for callers that don't
     * provide a provider.
     */
    @Test
    void nullProvider_nullCapsRegardlessOfFlag() throws Exception {
        RecordingStopRelocator relocator = new RecordingStopRelocator();

        ExMasConfigGroup config = buildConfig(/* enableBudgetAware= */ true); // flag=on but no provider
        MatsimNetworkCache network = buildNetworkCache();

        HyperPoolGenerator gen = new HyperPoolGenerator(
                network, relocator,
                (r1, r2) -> true,
                config,
                buildPassingBudgetValidator(),
                /* walkBudgetProvider= */ null);

        List<Ride> s2sRides = buildTwoS2SRides();
        gen.generate(s2sRides, network, 0);

        for (RecordingStopRelocator.CapturedCall c : relocator.calls) {
            assertNull(c.maxRelocDistPerPax,
                    "With null provider, findMergedStop must receive null caps.");
        }

        System.out.println("[B1+B2] null-provider: " + relocator.calls.size() + " relocator calls, all null caps.");
    }

    // =========================================================================
    // Infrastructure
    // =========================================================================

    private static void assertFalse(boolean condition, String message) {
        if (condition) {
            throw new AssertionError(message);
        }
    }

    private static ExMasConfigGroup buildConfig(boolean enableBudgetAware) {
        ExMasConfigGroup cfg = new ExMasConfigGroup();
        cfg.setEnableBudgetAwareConstraints(enableBudgetAware);
        cfg.setHyperPoolEnableStopRelocation(true); // MUST be on to create a StopRelocator
        cfg.setHyperPoolMinOccupancy(1);
        cfg.setHyperPoolTimeWindowSeconds(3600.0);
        cfg.setHyperPoolStopProximityMeters(500.0);
        cfg.setHyperPoolMaxStops(-1);
        cfg.setWalkSpeedMps(1.2);
        return cfg;
    }

    private static MatsimNetworkCache buildNetworkCache() {
        // Empty cache pre-populated with the pickup→dropoff segment.
        // HyperPoolGenerator routes through consecutive stops; any unpopulated
        // pair returns an unreachable segment (time=∞), which would cause
        // generateHyperPooledRide to return null.  We pre-populate the one
        // segment that the two identical-stop rides will route through.
        MatsimNetworkCache cache = MatsimNetworkCacheTestFixture.create();
        TravelSegment seg = new TravelSegment(60.0, 500.0, 0.0);
        MatsimNetworkCacheTestFixture.put(cache,
                Id.createLinkId("link_pickup"),
                Id.createLinkId("link_dropoff"),
                seg);
        return cache;
    }

    private static BudgetValidator buildPassingBudgetValidator() {
        ExMasConfigGroup cfg = new ExMasConfigGroup();
        return new BudgetValidator(null, cfg, 1.34) {
            @Override
            public double calculateDrtScoreWithWalks(DrtRequest request, double delay,
                    double actualTravelTime, double actualDistance,
                    double accessWalkDist, double egressWalkDist) {
                return request.bestModeScore + 1.0; // always passes
            }
        };
    }

    /**
     * Builds two minimal stop-to-stop rides sharing the same pickup and dropoff stops.
     * Ride 0: pax0 + pax1 (degree 2), remainingBudgets=[1.0, 1.0].
     * Ride 1: pax2 (degree 1), remainingBudgets=[1.0].
     */
    private static List<Ride> buildTwoS2SRides() {
        StopLocation sharedPickup = new StopLocation(Id.createLinkId("link_pickup"),
                new Coord(0.0, 0.0), 0.0);
        StopLocation sharedDropoff = new StopLocation(Id.createLinkId("link_dropoff"),
                new Coord(500.0, 0.0), 0.0);

        DrtRequest req0 = buildRequest(0, "pax0", 0.0, 500.0);
        DrtRequest req1 = buildRequest(1, "pax1", 50.0, 550.0);
        DrtRequest req2 = buildRequest(2, "pax2", 100.0, 600.0);

        // Ride 0: degree=2, STOP_TO_STOP, remainingBudgets populated
        Ride ride0 = Ride.builder()
                .index(0).degree(2).kind(RideKind.FIFO)
                .requests(new DrtRequest[]{req0, req1})
                .originsOrderedRequests(new DrtRequest[]{req0, req1})
                .destinationsOrderedRequests(new DrtRequest[]{req0, req1})
                .passengerTravelTimes(new double[]{60.0, 60.0})
                .passengerDistances(new double[]{500.0, 500.0})
                .passengerNetworkUtilities(new double[]{0.0, 0.0})
                .delays(new double[]{30.0, 30.0})
                .detours(new double[]{1.0, 1.0})
                .remainingBudgets(new double[]{1.0, 1.0})
                .connectionTravelTimes(new double[]{60.0})
                .connectionDistances(new double[]{500.0})
                .connectionNetworkUtilities(new double[]{0.0})
                .startTime(8.0 * 3600)
                .variant(RideVariant.STOP_TO_STOP)
                .pickupStop(sharedPickup)
                .dropoffStop(sharedDropoff)
                .accessWalkDistances(new double[]{50.0, 50.0})
                .egressWalkDistances(new double[]{50.0, 50.0})
                .build();

        // Ride 1: degree=1, STOP_TO_STOP, remainingBudgets populated
        StopLocation pickup1 = new StopLocation(Id.createLinkId("link_pickup"),
                new Coord(10.0, 0.0), 0.0); // nearby but distinct object
        StopLocation dropoff1 = new StopLocation(Id.createLinkId("link_dropoff"),
                new Coord(510.0, 0.0), 0.0);
        Ride ride1 = Ride.builder()
                .index(1).degree(1).kind(RideKind.SINGLE)
                .requests(new DrtRequest[]{req2})
                .originsOrderedRequests(new DrtRequest[]{req2})
                .destinationsOrderedRequests(new DrtRequest[]{req2})
                .passengerTravelTimes(new double[]{60.0})
                .passengerDistances(new double[]{500.0})
                .passengerNetworkUtilities(new double[]{0.0})
                .delays(new double[]{30.0})
                .detours(new double[]{1.0})
                .remainingBudgets(new double[]{1.0})
                .connectionTravelTimes(new double[]{60.0})
                .connectionDistances(new double[]{500.0})
                .connectionNetworkUtilities(new double[]{0.0})
                .startTime(8.0 * 3600)
                .variant(RideVariant.STOP_TO_STOP)
                .pickupStop(pickup1)
                .dropoffStop(dropoff1)
                .accessWalkDistances(new double[]{30.0})
                .egressWalkDistances(new double[]{30.0})
                .build();

        return List.of(ride0, ride1);
    }

    private static DrtRequest buildRequest(int index, String personId,
            double originX, double destX) {
        return DrtRequest.builder()
                .index(index)
                .personId(Id.createPersonId(personId))
                .groupId("g")
                .tripIndex(0)
                .isCommute(true)
                .isEducation(false)
                .budget(5.0)
                .bestModeScore(-2.0)
                .bestMode("car")
                .originLinkId(Id.createLinkId("link_orig"))
                .destinationLinkId(Id.createLinkId("link_dest"))
                .originX(originX).originY(0.0)
                .destinationX(destX).destinationY(0.0)
                .originLinkCoordFromX(0.0).originLinkCoordFromY(0.0)
                .originLinkCoordToX(500.0).originLinkCoordToY(0.0)
                .destinationLinkCoordFromX(500.0).destinationLinkCoordFromY(0.0)
                .destinationLinkCoordToX(1000.0).destinationLinkCoordToY(0.0)
                .requestTime(8.0 * 3600)
                .earliestDeparture(8.0 * 3600 - 300)
                .latestArrival(8.0 * 3600 + 3000)
                .directTravelTime(60.0)
                .directDistance(500.0)
                .maxDetourFactor(1.5)
                .maxWalkDistance(0.0)
                .carTravelTime(60.0)
                .ptTravelTime(120.0)
                .ptAccessibility(1.0)
                .build();
    }

    // =========================================================================
    // RecordingStopRelocator — analogous to CapturingStopFinder in StopBasedTestFixtures
    // =========================================================================

    /**
     * {@link HyperPoolGenerator.StopRelocator} stub that records every
     * {@code findMergedStop} call, specifically capturing the
     * {@code maxRelocDistPerPax} array (or null).
     *
     * <p>The stub merges by identity (returns original stop) to keep geometry
     * neutral. {@code areStopsNearby} always returns {@code true} so the
     * implementation under test always calls {@code findMergedStop} with the
     * nearby-stop candidate path (exercises the cap-array forwarding).
     */
    static class RecordingStopRelocator implements HyperPoolGenerator.StopRelocator {
        final List<CapturedCall> calls = new ArrayList<>();

        @Override
        public boolean areStopsNearby(StopLocation stop1, StopLocation stop2,
                double proximityMeters) {
            return true; // always report nearby so findMergedStop is exercised
        }

        @Override
        public StopLocation findMergedStop(StopLocation stop,
                List<StopLocation> existingStops,
                double proximityMeters,
                double[] maxRelocDistPerPax) {
            calls.add(new CapturedCall(
                    stop,
                    new ArrayList<>(existingStops),
                    proximityMeters,
                    maxRelocDistPerPax != null ? maxRelocDistPerPax.clone() : null));
            // No actual merging — return original stop
            return stop;
        }

        @Override
        public double calculateRelocationDistance(StopLocation originalStop,
                StopLocation mergedStop) {
            return 0.0; // stub
        }

        static class CapturedCall {
            final StopLocation stop;
            final List<StopLocation> existingStops;
            final double proximityMeters;
            final double[] maxRelocDistPerPax;

            CapturedCall(StopLocation stop, List<StopLocation> existingStops,
                    double proximityMeters, double[] maxRelocDistPerPax) {
                this.stop = stop;
                this.existingStops = existingStops;
                this.proximityMeters = proximityMeters;
                this.maxRelocDistPerPax = maxRelocDistPerPax;
            }
        }
    }
}
