package org.matsim.contrib.demand_extraction.demand;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup.FleetSide;
import org.matsim.contrib.demand_extraction.demand.DrtRequest.HubLegRole;
import org.matsim.contrib.demand_extraction.demand.DrtRequestFactory.LegRouter;
import org.matsim.core.network.NetworkUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task W5 (partial), 2026-08-25 plan
 * {@code docs/superpowers/plans/2026-08-25-booking-horizons-urban-whitelist.md} section
 * "Task W5", design {@code docs/superpowers/specs/2026-08-25-window-based-hub-sync-design.md}
 * D-W4: request-level invariants for windowed hub sync
 * ({@link DrtRequestFactory#expandConnecting}, Tasks W2/W3/W4).
 *
 * <p>Covers 3 of the plan's 4 asserts, at unit scale (no enumeration, no MATSim
 * {@code Controler}):
 * <ul>
 *   <li><b>Assert 1</b> (D-W4 solo fallback) — {@code solofallback_*} tests below.</li>
 *   <li><b>Assert 2</b> (negative delay producible) — {@code negativeDelay_*} tests below.</li>
 *   <li><b>Assert 4</b> (count sanity, {@code <= 2*hubTopK}) — {@code countSanity_*} tests below.</li>
 * </ul>
 * <b>Assert 3</b> (SHA-256 byte-parity CSV comparison against master on the merged fixture)
 * is explicitly OUT of scope here — it needs a real extraction and belongs to the phase
 * gate ({@code WindowedHubSyncE2ETest}, scheduled separately). The
 * {@code legacyControl_*} test at the bottom is this class's unit-scale STAND-IN for
 * that assert: it shows {@code hubSyncWindowed=false} + {@code hubTopK=0} reproduces the
 * pre-windowed overload's request count and every per-hub temporal field exactly, which
 * is the request-level analogue of "byte-identical" without needing a real corpus.
 *
 * <p>Harness copied from {@link DrtRequestFactoryWindowedAccessTest} and
 * {@link DrtRequestFactoryWindowedContinuationTest}: the same grid network, the same
 * {@code legRouterTo} / uniform fake-router helpers, and {@link TestRequestBuilder}
 * fixtures. The distinct-per-hub-cost network + {@code perHubRouter} helper used for the
 * top-K-specific assert-1 test is copied from {@link DrtRequestFactoryHubTopKTest}.
 */
class DrtRequestFactoryWindowedInvariantsTest {

    private static final double BUFFER = 300.0;
    private static final double RURAL_LEG_T = 600.0;
    private static final double URBAN_LEG_T = 900.0;
    private static final double ADVANCE = 900.0;
    private static final Predicate<Coord> METRO = coord -> coord.getX() >= 500.0;

    // Floating-point slop only (chained +/- over values in the 1e2-1e3 s range,
    // double error is ~1e-13 relative). The D-W4 pairing invariants proved below
    // are EXACT by construction (the CONTINUATION branch's
    // earliestPhysicalHubArrival = min(shifted, nominal) guarantees the
    // continuation window opens no later than the nominal ACCESS arrival) — this
    // is not modelling a physical grace period, so it stays tiny and is not tied
    // to maxHubWaitSeconds or hubSyncMaxAdvanceSeconds.
    private static final double TOL = 1e-6;

    // =========================================================================
    // Assert 1 — D-W4 solo fallback: the nominal-timed degree-1 ACCESS and
    // CONTINUATION requests for the same (trip, hub) are transfer-compatible by
    // construction.
    // =========================================================================

    /**
     * Uniform-cost fixture (all hubs equally attractive), hubTopK=0 (unlimited) so
     * EVERY hub is a "top-K" hub. For each hub id emitted on both sides: the nominal
     * ACCESS arrival at the hub must be no later than the CONTINUATION request's
     * departure window opening ({@code earliestDeparture}), and no later than its
     * departure deadline ({@code latestArrival - directTravelTime}); the implied
     * hub wait (window-open minus access-arrival, floored at 0 the way a real
     * transfer's wait cannot be negative) must be <= maxHubWaitSeconds.
     */
    @Test
    void solofallback_nominalAccessArrivalIsTransferCompatibleWithContinuationWindow_allHubs() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = threeHubs();
        DrtRequest c = TestRequestBuilder.connectingFixture(null);
        LegRouter router = fakeRouter(hubs, network);
        double maxHubWaitSeconds = 300.0;

        // Derive both requests exactly as applyVirtualExpansion does for the merged
        // both-sides run: call expandConnecting once per FleetSide on the SAME trip,
        // hub set, and router.
        List<DrtRequest> access = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.RURAL, METRO, network, router, BUFFER,
                maxHubWaitSeconds, /* hubSyncTwoSided */ false, ADVANCE,
                /* hubTopK */ 0, /* hubSyncWindowed */ true, null);
        List<DrtRequest> continuation = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.URBAN, METRO, network, router, BUFFER,
                maxHubWaitSeconds, false, ADVANCE, 0, true, null);

        assertEquals(hubs.size(), access.size(), "sanity: all hubs feasible on the ACCESS side");
        assertEquals(hubs.size(), continuation.size(), "sanity: all hubs feasible on the CONTINUATION side");

        Map<String, DrtRequest> accessByHub = access.stream()
                .collect(Collectors.toMap(r -> r.hubId, r -> r));
        Map<String, DrtRequest> contByHub = continuation.stream()
                .collect(Collectors.toMap(r -> r.hubId, r -> r));
        assertEquals(accessByHub.keySet(), contByHub.keySet(),
                "same hub id set survives on both sides (identical pass-1 routing/ranking)");

        for (String hubId : accessByHub.keySet()) {
            assertPairingHolds(accessByHub.get(hubId), contByHub.get(hubId), maxHubWaitSeconds, hubId);
        }
    }

    /**
     * Same invariant with an actual hubTopK cut (K=2 of 3, distinct per-hub costs so
     * ranking is unambiguous) — proves the pairing holds for "the top-K hubs", not
     * just in the unlimited case, and that both sides independently rank to the SAME
     * survivor set (D-W5 ranking is physical, not role-dependent — established by
     * {@link DrtRequestFactoryHubTopKTest}, re-checked here as a precondition for
     * this test being meaningful).
     */
    @Test
    void solofallback_holdsForEachOfTheTopKHubs_notJustTheUnrankedSet() {
        Network network = distinctCostHubNetwork();
        List<HubSetLoader.Hub> hubs = distinctCostHubs();
        // hub_near: 600+600=1200, hub_mid: 500+500=1000, hub_far: 900+900=1800.
        // Top-2 by ascending sum: hub_mid(1000), hub_near(1200) -> hub_far cut.
        Map<String, Double> roundTripSums = Map.of(
                "hub_near", 1200.0, "hub_mid", 1000.0, "hub_far", 1800.0);
        LegRouter router = perHubRouter(hubs, network, roundTripSums);
        DrtRequest c = TestRequestBuilder.connectingFixture(null);
        double maxHubWaitSeconds = 300.0;
        int hubTopK = 2;

        List<DrtRequest> access = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.RURAL, METRO, network, router, BUFFER,
                maxHubWaitSeconds, false, ADVANCE, hubTopK, true, null);
        List<DrtRequest> continuation = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.URBAN, METRO, network, router, BUFFER,
                maxHubWaitSeconds, false, ADVANCE, hubTopK, true, null);

        Set<String> expectedSurvivors = Set.of("hub_mid", "hub_near");
        assertEquals(expectedSurvivors, access.stream().map(r -> r.hubId).collect(Collectors.toSet()));
        assertEquals(expectedSurvivors, continuation.stream().map(r -> r.hubId).collect(Collectors.toSet()),
                "both FleetSide calls rank the SAME physical legs -> identical top-K survivor set");

        Map<String, DrtRequest> accessByHub = access.stream()
                .collect(Collectors.toMap(r -> r.hubId, r -> r));
        Map<String, DrtRequest> contByHub = continuation.stream()
                .collect(Collectors.toMap(r -> r.hubId, r -> r));
        for (String hubId : expectedSurvivors) {
            assertPairingHolds(accessByHub.get(hubId), contByHub.get(hubId), maxHubWaitSeconds, hubId);
        }
    }

    private static void assertPairingHolds(DrtRequest accessReq, DrtRequest contReq,
            double maxHubWaitSeconds, String hubId) {
        assertEquals(HubLegRole.ACCESS_LEG, accessReq.hubLegRole);
        assertEquals(HubLegRole.CONTINUATION_LEG, contReq.hubLegRole);

        double accessNominalArrival = accessReq.requestTime + accessReq.directTravelTime;
        double contWindowStart = contReq.earliestDeparture; // == contReq.requestTime (D-W1/Task W4)
        double contWindowEnd = contReq.latestArrival - contReq.directTravelTime;

        assertEquals(contReq.requestTime, contReq.earliestDeparture, TOL,
                "hub " + hubId + ": CONTINUATION requestTime == earliestDeparture by construction (Task W4)");

        // (a) The continuation's departure window has already OPENED by the time the
        // access leg nominally arrives -- guaranteed by construction: earliestPhysicalHubArrival
        // = min(shiftedArrival, nominalArrival) <= nominalArrival == accessNominalArrival.
        assertTrue(contWindowStart <= accessNominalArrival + TOL,
                "hub " + hubId + ": continuation window must open no later than the nominal access "
                + "arrival (" + contWindowStart + " <= " + accessNominalArrival + ")");

        // (b) The access leg arrives in time for the continuation to still depart
        // before its own deadline -- guaranteed by the CONTINUATION branch's temporal
        // feasibility guard (nominalHubArrival + secondLeg <= original.latestArrival).
        assertTrue(accessNominalArrival <= contWindowEnd + TOL,
                "hub " + hubId + ": nominal access arrival must fit before the continuation's "
                + "departure deadline (" + accessNominalArrival + " <= " + contWindowEnd + ")");

        // (c) Implied hub wait for the solo, nominal-timed fallback: the earliest the
        // continuation could actually depart is max(contWindowStart, accessNominalArrival)
        // -- a real vehicle cannot leave before the passenger has physically arrived.
        // Since (a) established contWindowStart <= accessNominalArrival, that max is
        // always accessNominalArrival itself, so the implied wait is exactly 0 here --
        // strictly stronger than "<= maxHubWaitSeconds" (which only needs
        // maxHubWaitSeconds >= 0 to hold trivially).
        double impliedWait = Math.max(contWindowStart, accessNominalArrival) - accessNominalArrival;
        assertEquals(0.0, impliedWait, TOL,
                "hub " + hubId + ": D-W4 solo fallback wait is exactly 0 by construction");
        assertTrue(impliedWait <= maxHubWaitSeconds + TOL,
                "hub " + hubId + ": implied wait must not exceed maxHubWaitSeconds");
    }

    // =========================================================================
    // Assert 2 — negative delay is producible: a windowed ACCESS request's
    // earliestDeparture is genuinely earlier than requestTime, and that earliness
    // survives into the built request (checked at the request level; a full
    // enumeration run producing an actual shared ride with a realized negative
    // delay is E2E and out of scope per the task).
    // =========================================================================

    @Test
    void negativeDelay_earliestDepartureIsGenuinelyEarlierThanRequestTime() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = oneHub();
        DrtRequest c = TestRequestBuilder.connectingFixture(null); // requestTime = 0.0

        DrtRequest v = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.RURAL, METRO, network, fakeRouter(hubs, network), BUFFER,
                /* maxHubWaitSeconds */ 0.0, false, ADVANCE, 0, /* hubSyncWindowed */ true, null)
                .get(0);

        assertTrue(v.earliestDeparture < v.requestTime - TOL,
                "windowed ACCESS: earliestDeparture must be strictly earlier than requestTime "
                + "for the advance to be exercisable at all");
        assertEquals(c.requestTime - ADVANCE, v.earliestDeparture, TOL);

        // A schedule that picks this request up at earliestDeparture has
        // delay = pickupTime - requestTime = -ADVANCE, i.e. a genuine negative
        // delay (pickup 900 s BEFORE nominal). getMaxNegativeDelay() is exactly
        // the quantity OrderingEnumerator/PairGenerator/TimeFilter use to bound how
        // early a request may be picked up (requestTime - earliestDeparture); it
        // must be strictly positive for a negative-delay schedule to be admissible.
        assertEquals(ADVANCE, v.getMaxNegativeDelay(), TOL,
                "getMaxNegativeDelay() = requestTime - earliestDeparture must equal the "
                + "advance window, i.e. a -900 s delay pickup is within bounds");
        assertTrue(v.getMaxNegativeDelay() > 0.0,
                "a strictly positive getMaxNegativeDelay() is what makes negative-delay "
                + "pickup schedules admissible downstream");
    }

    // =========================================================================
    // Assert 4 — count sanity: windowed mode emits at most 2*hubTopK connecting
    // requests per trip (ACCESS + CONTINUATION across both FleetSides), and the
    // exact expected count for this fixture.
    // =========================================================================

    @Test
    void countSanity_atMostTwiceHubTopK_andExactCountForFixture() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = threeHubs();
        DrtRequest c = TestRequestBuilder.connectingFixture(null);
        LegRouter router = fakeRouter(hubs, network);
        int hubTopK = 2;

        List<DrtRequest> access = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.RURAL, METRO, network, router, BUFFER,
                0.0, false, ADVANCE, hubTopK, true, null);
        List<DrtRequest> continuation = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.URBAN, METRO, network, router, BUFFER,
                0.0, false, ADVANCE, hubTopK, true, null);

        int total = access.size() + continuation.size();
        assertTrue(total <= 2 * hubTopK,
                "windowed mode must emit at most 2*hubTopK connecting requests per trip; got "
                + total + " for hubTopK=" + hubTopK);
        // Exact count for THIS fixture (3 uniform-cost hubs, all temporally feasible):
        // both sides keep exactly hubTopK hubs each -> the bound is met with equality.
        // A regression that silently doubled emission (e.g. re-introducing a variant
        // loop inside the windowed branch) would fail this exact-count assert even if
        // it never exceeded the loose upper bound.
        assertEquals(2, access.size());
        assertEquals(2, continuation.size());
        assertEquals(2 * hubTopK, total);
    }

    // =========================================================================
    // Legacy-mode control (unit-scale stand-in for the skipped Assert 3 byte-
    // parity check): hubSyncWindowed=false + hubTopK=0 must reproduce the exact
    // request count and every per-hub temporal field of the pre-Task-W3/W4
    // overload, on both FleetSides.
    // =========================================================================

    @Test
    void legacyControl_hubSyncWindowedFalse_hubTopKZero_matchesPreWindowedOverload_bothSides() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = threeHubs();
        DrtRequest c = TestRequestBuilder.connectingFixture(null);
        LegRouter router = fakeRouter(hubs, network);
        double maxHubWaitSeconds = 120.0;
        double advance = 300.0;

        for (FleetSide side : List.of(FleetSide.RURAL, FleetSide.URBAN)) {
            // The pre-Task-W3/W4 overload: no hubSyncWindowed parameter at all.
            List<DrtRequest> legacy = DrtRequestFactory.expandConnecting(
                    c, hubs, side, METRO, network, router, BUFFER,
                    maxHubWaitSeconds, /* hubSyncTwoSided */ false, advance,
                    /* hubTopK */ 0, null);

            List<DrtRequest> windowedOff = DrtRequestFactory.expandConnecting(
                    c, hubs, side, METRO, network, router, BUFFER,
                    maxHubWaitSeconds, false, advance, 0, /* hubSyncWindowed */ false, null);

            assertEquals(hubs.size(), legacy.size());
            assertEquals(legacy.size(), windowedOff.size(),
                    "hubSyncWindowed=false must not change the request count for side " + side);
            for (int i = 0; i < windowedOff.size(); i++) {
                DrtRequest l = legacy.get(i);
                DrtRequest w = windowedOff.get(i);
                assertEquals(l.hubId, w.hubId);
                assertEquals(l.hubLegRole, w.hubLegRole);
                assertEquals(l.requestTime, w.requestTime, TOL);
                assertEquals(l.earliestDeparture, w.earliestDeparture, TOL);
                assertEquals(l.latestArrival, w.latestArrival, TOL);
                assertEquals(l.directTravelTime, w.directTravelTime, TOL);
                assertEquals(l.directDistance, w.directDistance, TOL);
                assertEquals(l.transferWaitSeconds, w.transferWaitSeconds, TOL);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers — copied verbatim from DrtRequestFactoryWindowedAccessTest /
    // DrtRequestFactoryWindowedContinuationTest (uniform grid network) and
    // DrtRequestFactoryHubTopKTest (distinct-per-hub-cost network + perHubRouter).
    // -------------------------------------------------------------------------

    private static LegRouter fakeRouter(List<HubSetLoader.Hub> hubs, Network network) {
        Set<Id<Link>> hubLinks = hubs.stream()
                .map(h -> NetworkUtils.getNearestLink(network, h.coord()).getId())
                .collect(Collectors.toSet());
        return (from, to, dep) -> hubLinks.contains(to)
                ? new double[] {RURAL_LEG_T, 6000.0}
                : new double[] {URBAN_LEG_T, 9000.0};
    }

    private static Network buildGridNetwork() {
        Network net = NetworkUtils.createNetwork();
        NetworkFactory nf = net.getFactory();
        Node nw = nf.createNode(Id.createNodeId("nw"), new Coord(-1_000.0, 11_000.0));
        Node ne = nf.createNode(Id.createNodeId("ne"), new Coord(11_000.0, 11_000.0));
        Node sw = nf.createNode(Id.createNodeId("sw"), new Coord(-1_000.0, -1_000.0));
        Node se = nf.createNode(Id.createNodeId("se"), new Coord(11_000.0, -1_000.0));
        net.addNode(nw); net.addNode(ne); net.addNode(sw); net.addNode(se);
        net.addLink(nf.createLink(Id.createLinkId("n"), nw, ne));
        net.addLink(nf.createLink(Id.createLinkId("e"), ne, se));
        net.addLink(nf.createLink(Id.createLinkId("s"), se, sw));
        net.addLink(nf.createLink(Id.createLinkId("w"), sw, nw));
        assertNotNull(NetworkUtils.getNearestLink(net, new Coord(0.0, 0.0)));
        return net;
    }

    private static List<HubSetLoader.Hub> oneHub() {
        return List.of(new HubSetLoader.Hub("hub_a", new Coord(5_000.0, 8_000.0)));
    }

    private static List<HubSetLoader.Hub> threeHubs() {
        return List.of(
                new HubSetLoader.Hub("hub_a", new Coord(5_000.0, 8_000.0)),
                new HubSetLoader.Hub("hub_b", new Coord(7_500.0, 5_000.0)),
                new HubSetLoader.Hub("hub_c", new Coord(9_000.0, 2_000.0))
        );
    }

    private static List<HubSetLoader.Hub> distinctCostHubs() {
        return List.of(
                new HubSetLoader.Hub("hub_near", new Coord(1_000.0, 5_000.0)),
                new HubSetLoader.Hub("hub_mid", new Coord(3_000.0, 5_000.0)),
                new HubSetLoader.Hub("hub_far", new Coord(5_000.0, 5_000.0)));
    }

    /** One short link per hub, 2000 m apart, so {@code getNearestLink} never confuses them. */
    private static Network distinctCostHubNetwork() {
        Network net = NetworkUtils.createNetwork();
        NetworkFactory nf = net.getFactory();
        double[] hubX = {1_000.0, 3_000.0, 5_000.0};
        for (int i = 0; i < hubX.length; i++) {
            Node a = nf.createNode(Id.createNodeId("hub" + i + "_a"), new Coord(hubX[i] - 5.0, 5_000.0));
            Node b = nf.createNode(Id.createNodeId("hub" + i + "_b"), new Coord(hubX[i] + 5.0, 5_000.0));
            net.addNode(a);
            net.addNode(b);
            net.addLink(nf.createLink(Id.createLinkId("hubLink" + i), a, b));
        }
        for (double x : hubX) {
            assertNotNull(NetworkUtils.getNearestLink(net, new Coord(x, 5_000.0)));
        }
        return net;
    }

    /**
     * Routes TO a hub link with half the round-trip sum (rural/first leg), FROM a hub
     * link with the other half (urban/second leg); departure-independent (returns the
     * same leg time regardless of {@code dep}), matching the ACCESS/CONTINUATION
     * branches' "reuse pass 1's routing" contract for a uniform, deterministic fixture.
     */
    private static LegRouter perHubRouter(List<HubSetLoader.Hub> hubs, Network network,
            Map<String, Double> roundTripSumByHubId) {
        Map<Id<Link>, Double> sumByLink = new HashMap<>();
        for (HubSetLoader.Hub h : hubs) {
            Id<Link> linkId = NetworkUtils.getNearestLink(network, h.coord()).getId();
            sumByLink.put(linkId, roundTripSumByHubId.get(h.id()));
        }
        return (from, to, dep) -> {
            if (sumByLink.containsKey(to)) {
                double half = sumByLink.get(to) / 2.0;
                return new double[] {half, half * 10.0};
            }
            if (sumByLink.containsKey(from)) {
                double half = sumByLink.get(from) / 2.0;
                return new double[] {half, half * 10.0};
            }
            throw new IllegalStateException(
                    "router stub got unexpected leg from=" + from + " to=" + to);
        };
    }
}
