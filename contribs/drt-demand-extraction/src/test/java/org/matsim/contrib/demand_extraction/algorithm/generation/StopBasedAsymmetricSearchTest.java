package org.matsim.contrib.demand_extraction.algorithm.generation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideKind;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideVariant;
import org.matsim.contrib.demand_extraction.algorithm.domain.StopLocation;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCacheTestFixture;
import org.matsim.contrib.demand_extraction.algorithm.stops.StopFinder;
import org.matsim.contrib.demand_extraction.algorithm.stops.WalkingDistanceCalculator;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A5+A6: Unit tests for the asymmetric two-phase stop search in
 * {@link StopBasedRideGenerator}.
 *
 * <h2>What A6 changes</h2>
 * {@code convertToStopBased} dispatches on {@code enableBudgetAwareConstraints}:
 * <ul>
 *   <li>flag=on → {@code convertToStopBasedBudgetAware}: Phase A gets cap
 *       {@code accessCaps[i] = min(2·mid, hardCap)}; after measuring the actual
 *       access walk, Phase B gets
 *       {@code egressCaps[i] = max(0, min(2·mid − accessWalk[i], hardCap))}.
 *   <li>flag=off → {@code convertToStopBasedLegacy}: both phases receive the
 *       same pre-computed {@code maxWalkDistances} array.
 * </ul>
 *
 * <h2>Test design</h2>
 * Stub {@link WalkingDistanceCalculator} returns {@value WALK_M} m for every
 * walk. Stub {@link WalkBudgetProvider} returns {@code mid = }{@value MID_M} m.
 * With {@code hardCap = }{@value HARD_CAP_M} m:
 * <pre>
 *   Phase A cap = min(2·200, 600)             = 400 m
 *   Phase B cap = max(0, min(400 − 50, 600)) = 350 m  (because accessWalk=50)
 * </pre>
 * So 400 ≠ 350 → flag=on produces asymmetric caps.
 * Legacy path passes the same array to both phases → caps are equal.
 *
 * <h2>Pre-A6 failure</h2>
 * Before A6, {@code convertToStopBased} routes to the legacy path even when
 * flag=on, so Phase A and Phase B caps are equal. The first test therefore
 * FAILS before A6 and PASSES after.
 */
class StopBasedAsymmetricSearchTest {

    /** Fixed mid returned by the WalkBudgetProvider stub (metres). */
    static final double MID_M = 200.0;
    /** Fixed walk returned by the WalkingDistanceCalculator stub (metres). */
    static final double WALK_M = 50.0;
    /** Hard cap set in ExMasConfigGroup (metres). */
    static final double HARD_CAP_M = 600.0;

    // -------------------------------------------------------------------------
    // Test 1 — must FAIL before A6, PASS after A6
    // -------------------------------------------------------------------------

    /**
     * With {@code enableBudgetAwareConstraints=true} (post-A6 budget-aware path)
     * Phase A and Phase B must receive DIFFERENT cap arrays because Phase B
     * subtracts the measured access walk from the total walk envelope.
     *
     * <p>Expected values (derived from stub constants):
     * <pre>
     *   Phase A cap = min(2·MID_M, HARD_CAP_M)              = min(400, 600) = 400 m
     *   Phase B cap = max(0, min(2·MID_M − WALK_M, HARD_CAP_M)) = max(0, min(350, 600)) = 350 m
     * </pre>
     * So Phase A = [400.0, 400.0], Phase B = [350.0, 350.0].
     */
    @Test
    void budgetAwarePath_phaseACapsAndPhaseBCapsDiffer() {
        CapturingStopFinder capturingFinder = new CapturingStopFinder();
        StopBasedRideGenerator gen = buildGenerator(/* enableBudgetAware= */ true, capturingFinder);

        gen.generateStopBasedRides(List.of(buildD2DRide()), /* startIndex= */ 0);

        assertTrue(capturingFinder.calls.size() >= 2,
                "Expected at least 2 findStop calls (Phase A + Phase B), got: "
                        + capturingFinder.calls.size());

        double[] phaseACaps = capturingFinder.calls.get(0).caps;
        double[] phaseBCaps = capturingFinder.calls.get(1).caps;

        System.out.printf(
                "%n[budgetAware] Phase A: %s  Phase B: %s%n",
                Arrays.toString(phaseACaps), Arrays.toString(phaseBCaps));

        // Phase A cap = min(2·MID_M, HARD_CAP_M) = min(400, 600) = 400 m
        double expectedPhaseACap = Math.min(2 * MID_M, HARD_CAP_M);
        // Phase B cap = max(0, min(2·MID_M - WALK_M, HARD_CAP_M)) = max(0, min(350, 600)) = 350 m
        double expectedPhaseBCap = Math.max(0, Math.min(2 * MID_M - WALK_M, HARD_CAP_M));

        assertArrayEquals(
                new double[]{expectedPhaseACap, expectedPhaseACap}, phaseACaps, 1e-9,
                "Budget-aware path: Phase A cap should be min(2·mid, hardCap)="
                        + expectedPhaseACap + " m for each passenger");
        assertArrayEquals(
                new double[]{expectedPhaseBCap, expectedPhaseBCap}, phaseBCaps, 1e-9,
                "Budget-aware path: Phase B cap should be max(0, min(2·mid−accessWalk, hardCap))="
                        + expectedPhaseBCap + " m for each passenger");
    }

    // -------------------------------------------------------------------------
    // Test 2 — legacy path symmetry regression guard
    // -------------------------------------------------------------------------

    /**
     * With {@code enableBudgetAwareConstraints=false} (legacy path) both phases
     * must receive the same cap array (same reference passed to both calls).
     */
    @Test
    void legacyPath_phaseACapsAndPhaseBCapsAreEqual() {
        CapturingStopFinder capturingFinder = new CapturingStopFinder();
        StopBasedRideGenerator gen = buildGenerator(/* enableBudgetAware= */ false, capturingFinder);

        gen.generateStopBasedRides(List.of(buildD2DRide()), /* startIndex= */ 0);

        assertTrue(capturingFinder.calls.size() >= 2,
                "Expected at least 2 findStop calls, got: " + capturingFinder.calls.size());

        double[] phaseACaps = capturingFinder.calls.get(0).caps;
        double[] phaseBCaps = capturingFinder.calls.get(1).caps;

        System.out.printf(
                "%n[legacy]      Phase A: %s  Phase B: %s%n",
                Arrays.toString(phaseACaps), Arrays.toString(phaseBCaps));

        assertArrayEquals(phaseACaps, phaseBCaps, 1e-9,
                "Legacy path must pass IDENTICAL caps to both findStop calls. "
                + "Phase A: " + Arrays.toString(phaseACaps)
                + "  Phase B: " + Arrays.toString(phaseBCaps));
    }

    // =========================================================================
    // Infrastructure
    // =========================================================================

    /**
     * Build a {@link StopBasedRideGenerator} for unit tests.
     *
     * <p>All dependencies are stubs — no MATSim scenario is required.
     *
     * <ul>
     *   <li>{@link MatsimNetworkCache} — Dijkstra on a 3-node, 2-link chain.
     *   <li>{@link StopFinder} — capturing stub; returns "stop" link for
     *       Phase A and "dropoff" link for Phase B.
     *   <li>{@link WalkingDistanceCalculator} — always returns {@link #WALK_M}.
     *   <li>{@link BudgetValidator} — override of
     *       {@code calculateDrtScoreWithWalks} always returns
     *       {@code request.bestModeScore + 1.0} (budget always positive).
     *   <li>{@link WalkBudgetProvider} — lambda returning {@link #MID_M}
     *       (only used when flag=on).
     * </ul>
     */
    private StopBasedRideGenerator buildGenerator(boolean enableBudgetAware,
            CapturingStopFinder capturingFinder) {

        // ---- Minimal 3-node, 4-link directed chain network ----
        Network network = buildMinimalNetwork();

        var tt = new FreeSpeedTravelTime();
        var td = new OnlyTimeDependentTravelDisutility(tt);
        // 3600 s bins — routing is live via Dijkstra for cache misses
        MatsimNetworkCache networkCache = MatsimNetworkCacheTestFixture.createWithRouting(network, tt, td, 3600);

        // ---- Stub: WalkingDistanceCalculator always returns WALK_M ----
        WalkingDistanceCalculator walkCalc = new WalkingDistanceCalculator() {
            @Override
            public double calculateWalkDistance(Coord coord, Link link) {
                return WALK_M;
            }
        };

        // ---- Stub: BudgetValidator always grants positive budget ----
        ExMasConfigGroup exMasConfig = buildExMasConfig(enableBudgetAware);
        BudgetValidator stubValidator = new BudgetValidator(null, exMasConfig, 1.34) {
            @Override
            public double calculateDrtScoreWithWalks(DrtRequest request, double delay,
                    double actualTravelTime, double actualDistance,
                    double accessWalkDist, double egressWalkDist) {
                // Always return bestModeScore + 1.0 so remaining budget = 1.0 > 0
                return request.bestModeScore + 1.0;
            }
        };

        // ---- Stub: WalkBudgetProvider (only used when flag=on) ----
        WalkBudgetProvider walkBudgetProvider = enableBudgetAware
                ? (budget, req, actualTT, dist, delay) -> MID_M
                : null;

        return new StopBasedRideGenerator(
                networkCache,
                capturingFinder,
                walkCalc,
                stubValidator,
                exMasConfig,
                /* algorithmProcessCount= */ 1, // sequential
                walkBudgetProvider);
    }

    /** Build a minimal 3-node, 4-link directed chain network. */
    private static Network buildMinimalNetwork() {
        Network net = NetworkUtils.createNetwork();
        NetworkFactory f = net.getFactory();

        Node n0 = f.createNode(Id.createNodeId("n0"), new Coord(0.0, 0.0));
        Node n1 = f.createNode(Id.createNodeId("n1"), new Coord(1000.0, 0.0));
        Node n2 = f.createNode(Id.createNodeId("n2"), new Coord(2000.0, 0.0));
        net.addNode(n0);
        net.addNode(n1);
        net.addNode(n2);

        // Pickup stop link (used by CapturingStopFinder for Phase A)
        addLink(net, f, "stop", n0, n1);
        // Dropoff stop link (used by CapturingStopFinder for Phase B)
        addLink(net, f, "dropoff", n1, n2);
        // Origin link for passengers (same physical segment, different id)
        addLink(net, f, "origin", n0, n1);
        // Destination link for passengers
        addLink(net, f, "dest", n1, n2);

        return net;
    }

    private static void addLink(Network net, NetworkFactory f,
            String id, Node from, Node to) {
        Link lnk = f.createLink(Id.createLinkId(id), from, to);
        lnk.setLength(1000.0);
        lnk.setFreespeed(13.89); // 50 km/h
        lnk.setCapacity(1000.0);
        lnk.setNumberOfLanes(1.0);
        net.addLink(lnk);
    }

    /** Build a minimal {@link ExMasConfigGroup} for unit tests. */
    private static ExMasConfigGroup buildExMasConfig(boolean enableBudgetAware) {
        ExMasConfigGroup cfg = new ExMasConfigGroup();
        cfg.setEnableStopBased(true);
        cfg.setMaxWalkDistanceMeters(HARD_CAP_M);
        cfg.setStopSearchRadiusMeters(HARD_CAP_M);
        cfg.setEnableBudgetAwareConstraints(enableBudgetAware);
        cfg.setDrtMode("drt");
        cfg.setMinDrtAccessEgressDistance(0.0);
        return cfg;
    }

    /**
     * Build a degree-2 DOOR_TO_DOOR ride for unit tests.
     *
     * <p>Passenger origins are placed at (50, 0) and (100, 0) so that they
     * are clearly NOT on the "stop" link (which runs from (0,0) to (1000,0)).
     * The stub WalkingDistanceCalculator ignores geometry and always returns
     * {@link #WALK_M}, so the exact coordinates do not affect walk measurement.
     *
     * <p>Remaining budgets are pre-set to [0.5, 0.5] (positive) so no budget
     * validation can reject the ride before we reach the stop search.
     */
    private static Ride buildD2DRide() {
        DrtRequest req0 = DrtRequest.builder()
                .index(0)
                .personId(Id.createPersonId("pax0"))
                .groupId("g0")
                .tripIndex(0)
                .isCommute(true)
                .isEducation(false)
                .budget(1.0)
                .bestModeScore(-1.0)
                .bestMode("car")
                .originLinkId(Id.createLinkId("origin"))
                .destinationLinkId(Id.createLinkId("dest"))
                .originX(50.0).originY(0.0)
                .destinationX(1050.0).destinationY(0.0)
                .originLinkCoordFromX(0.0).originLinkCoordFromY(0.0)
                .originLinkCoordToX(1000.0).originLinkCoordToY(0.0)
                .destinationLinkCoordFromX(1000.0).destinationLinkCoordFromY(0.0)
                .destinationLinkCoordToX(2000.0).destinationLinkCoordToY(0.0)
                .requestTime(8.0 * 3600)
                .earliestDeparture(8.0 * 3600 - 300)
                .latestArrival(8.0 * 3600 + 3000)
                .directTravelTime(600.0)
                .directDistance(6000.0)
                .maxDetourFactor(1.5)
                .maxWalkDistance(0.0)
                .carTravelTime(600.0)
                .ptTravelTime(900.0)
                .ptAccessibility(1.0)
                .build();

        DrtRequest req1 = DrtRequest.builder()
                .index(1)
                .personId(Id.createPersonId("pax1"))
                .groupId("g1")
                .tripIndex(0)
                .isCommute(true)
                .isEducation(false)
                .budget(1.0)
                .bestModeScore(-1.0)
                .bestMode("car")
                .originLinkId(Id.createLinkId("origin"))
                .destinationLinkId(Id.createLinkId("dest"))
                .originX(100.0).originY(0.0)
                .destinationX(1100.0).destinationY(0.0)
                .originLinkCoordFromX(0.0).originLinkCoordFromY(0.0)
                .originLinkCoordToX(1000.0).originLinkCoordToY(0.0)
                .destinationLinkCoordFromX(1000.0).destinationLinkCoordFromY(0.0)
                .destinationLinkCoordToX(2000.0).destinationLinkCoordToY(0.0)
                .requestTime(8.0 * 3600)
                .earliestDeparture(8.0 * 3600 - 300)
                .latestArrival(8.0 * 3600 + 3000)
                .directTravelTime(600.0)
                .directDistance(6000.0)
                .maxDetourFactor(1.5)
                .maxWalkDistance(0.0)
                .carTravelTime(600.0)
                .ptTravelTime(900.0)
                .ptAccessibility(1.0)
                .build();

        DrtRequest[] reqs = {req0, req1};

        return Ride.builder()
                .index(0)
                .degree(2)
                .kind(RideKind.FIFO)
                .requests(reqs)
                .originsOrderedRequests(reqs)
                .destinationsOrderedRequests(reqs)
                .passengerTravelTimes(new double[]{720.0, 720.0})
                .passengerDistances(new double[]{7200.0, 7200.0})
                .passengerNetworkUtilities(new double[]{0.0, 0.0})
                .delays(new double[]{120.0, 120.0})
                .detours(new double[]{1.2, 1.2})
                .remainingBudgets(new double[]{0.5, 0.5})
                .connectionTravelTimes(new double[]{600.0})
                .connectionDistances(new double[]{6000.0})
                .connectionNetworkUtilities(new double[]{0.0})
                .startTime(8.0 * 3600)
                .variant(RideVariant.DOOR_TO_DOOR)
                .build();
    }

    // =========================================================================
    // CapturingStopFinder: records every findStop call
    // =========================================================================

    /**
     * {@link StopFinder} stub that records the {@code maxWalkDistances} array
     * passed to every {@link #findStop} call.
     *
     * <ul>
     *   <li>Call 1 (Phase A): returns a stop at link "stop", coord (500, 0).
     *   <li>Call 2 (Phase B): returns a stop at link "dropoff", coord (1500, 0).
     * </ul>
     *
     * The two link IDs match the links added in {@link #buildMinimalNetwork()}.
     */
    static class CapturingStopFinder implements StopFinder {
        final List<CapturedCall> calls = new ArrayList<>();
        private int callCount = 0;

        @Override
        public Optional<StopLocation> findStop(
                List<Coord> passengerLocations,
                double[] maxWalkDistances,
                double departureTime) {
            // Defensive copy so later mutations to the array don't corrupt history
            calls.add(new CapturedCall(
                    new ArrayList<>(passengerLocations),
                    Arrays.copyOf(maxWalkDistances, maxWalkDistances.length),
                    departureTime));
            callCount++;

            if (callCount == 1) {
                // Phase A: pickup stop at midpoint of "stop" link
                return Optional.of(new StopLocation(
                        Id.createLinkId("stop"),
                        new Coord(500.0, 0.0),
                        0.0));
            } else {
                // Phase B: dropoff stop at midpoint of "dropoff" link
                return Optional.of(new StopLocation(
                        Id.createLinkId("dropoff"),
                        new Coord(1500.0, 0.0),
                        0.0));
            }
        }

        @Override
        public String getName() { return "Capturing"; }

        static class CapturedCall {
            final List<Coord> locations;
            final double[] caps;
            final double time;

            CapturedCall(List<Coord> locations, double[] caps, double time) {
                this.locations = locations;
                this.caps = caps;
                this.time = time;
            }
        }
    }
}
