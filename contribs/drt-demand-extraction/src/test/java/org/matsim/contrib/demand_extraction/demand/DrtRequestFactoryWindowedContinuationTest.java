package org.matsim.contrib.demand_extraction.demand;

import java.util.List;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup.FleetSide;
import org.matsim.contrib.demand_extraction.demand.DrtRequest.HubLegRole;
import org.matsim.contrib.demand_extraction.demand.DrtRequestFactory.ExpansionDropStats;
import org.matsim.contrib.demand_extraction.demand.DrtRequestFactory.HubDetour;
import org.matsim.contrib.demand_extraction.demand.DrtRequestFactory.LegRouter;
import org.matsim.core.network.NetworkUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-W1 + D-W6 (Task W4, 2026-08-25 plan
 * {@code docs/superpowers/plans/2026-08-25-booking-horizons-urban-whitelist.md},
 * design {@code docs/superpowers/specs/2026-08-25-window-based-hub-sync-design.md}):
 * windowed CONTINUATION leg emission inside {@link DrtRequestFactory#expandConnecting}.
 *
 * <p>Harness copied from {@link DrtRequestFactoryWindowedAccessTest} (grid network, fake
 * routers, {@link TestRequestBuilder} fixtures) and {@link ContinuationLegWideWindowTest}
 * (legacy CONTINUATION expectations, {@code FleetSide.URBAN} so the fixture's rural origin
 * / urban destination makes THIS fleet serve the hub->D leg): rural leg (O->hub) = 600 s /
 * 6000 m, urban leg (hub->D) = 900 s / 9000 m, {@code BUFFER = 300}.
 */
class DrtRequestFactoryWindowedContinuationTest {

    private static final double BUFFER = 300.0;
    private static final double RURAL_LEG_T = 600.0;
    private static final double URBAN_LEG_T = 900.0;
    private static final Predicate<Coord> METRO = coord -> coord.getX() >= 500.0;

    // -------------------------------------------------------------------------
    // Windowed mode: exactly one CONTINUATION request per (trip, hub).
    // -------------------------------------------------------------------------

    @Test
    void windowedMode_emitsExactlyOneContinuationRequestPerHub() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = threeHubs();
        DrtRequest c = TestRequestBuilder.connectingFixture(null);

        List<DrtRequest> out = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.URBAN, METRO, network, fakeRouter(hubs, network), BUFFER,
                /* maxHubWaitSeconds */ 0.0, /* hubSyncTwoSided */ false,
                /* hubSyncMaxAdvanceSeconds */ 900.0, /* hubTopK */ 0,
                /* hubSyncWindowed */ true, null);

        assertEquals(hubs.size(), out.size(),
                "windowed mode: exactly ONE CONTINUATION request per (trip, hub)");
        for (DrtRequest v : out) {
            assertEquals(HubLegRole.CONTINUATION_LEG, v.hubLegRole);
            assertEquals(0.0, v.transferWaitSeconds, 1e-9);
        }
    }

    // -------------------------------------------------------------------------
    // requestTime == earliestDeparture == earliest PHYSICAL hub arrival, using
    // the nominal-departure-routed firstLeg (departure-independent router here).
    // -------------------------------------------------------------------------

    @Test
    void windowedMode_requestTimeAndEarliestDeparture_equalEarliestPhysicalHubArrival() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = oneHub();
        DrtRequest c = TestRequestBuilder.connectingFixture(null); // requestTime = 0.0
        double advance = 900.0;

        DrtRequest v = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.URBAN, METRO, network, fakeRouter(hubs, network), BUFFER,
                0.0, false, advance, 0, true, null).get(0);

        // Router is departure-independent (rural leg always 600 s), so the shifted
        // routing call returns the same 600 s as pass 1: earliestPhysicalHubArrival
        // = (requestTime - advance) + firstLeg = (0 - 900) + 600 = -300.
        double expected = (c.requestTime - advance) + RURAL_LEG_T;
        assertEquals(expected, v.requestTime, 1e-9,
                "requestTime = earliest physical hub arrival");
        assertEquals(expected, v.earliestDeparture, 1e-9,
                "earliestDeparture = earliest physical hub arrival");
    }

    // -------------------------------------------------------------------------
    // The extra routing call at the shifted departure is actually consumed:
    // a router that returns a DIFFERENT leg time for the shifted departure than
    // for the nominal one must have its shifted value land in the request.
    // -------------------------------------------------------------------------

    @Test
    void windowedMode_shiftedDepartureRoutingResult_isUsed_notNominal() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = oneHub();
        DrtRequest c = TestRequestBuilder.connectingFixture(null); // requestTime = 0.0
        double advance = 900.0;
        double shiftedDeparture = c.requestTime - advance; // -900
        double shiftedRuralT = 500.0; // != RURAL_LEG_T (600), != any fallback value

        LegRouter router = departureDependentRuralRouter(hubs, network,
                RURAL_LEG_T, shiftedDeparture, shiftedRuralT, URBAN_LEG_T);

        DrtRequest v = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.URBAN, METRO, network, router, BUFFER,
                0.0, false, advance, 0, true, null).get(0);

        double expectedFromShiftedRouting = shiftedDeparture + shiftedRuralT; // -900+500=-400
        double nominalFallbackWouldBe = c.requestTime + RURAL_LEG_T;          // 0+600=600
        assertNotEquals(nominalFallbackWouldBe, expectedFromShiftedRouting,
                "sanity: the two candidate values must differ for this test to be meaningful");
        assertEquals(expectedFromShiftedRouting, v.requestTime, 1e-9,
                "the shifted-departure routing result must land in the request, not the "
                + "nominal-departure fallback");
        assertEquals(expectedFromShiftedRouting, v.earliestDeparture, 1e-9);
    }

    @Test
    void windowedMode_slowerRouteAtShiftedDeparture_doesNotPushEarliestArrivalLater() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = oneHub();
        DrtRequest c = TestRequestBuilder.connectingFixture(null); // requestTime = 0.0
        double advance = 900.0;
        double shiftedDeparture = c.requestTime - advance;   // -900
        // Leaving 900 s early costs 2000 s more travel time, so the shifted route
        // arrives at -900 + 2600 = 1700, LATER than simply leaving on time (0 + 600
        // = 600). The passenger can always choose the nominal departure, so the
        // earliest achievable arrival is 600. Taking the shifted value here would
        // be unphysical and would make DrtRequest.build() throw once the extra
        // travel time exceeds the slack, aborting the extraction mid-run.
        double shiftedRuralT = 2600.0;

        LegRouter router = departureDependentRuralRouter(hubs, network,
                RURAL_LEG_T, shiftedDeparture, shiftedRuralT, URBAN_LEG_T);

        DrtRequest v = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.URBAN, METRO, network, router, BUFFER,
                0.0, false, advance, 0, true, null).get(0);

        assertEquals(c.requestTime + RURAL_LEG_T, v.requestTime, 1e-9,
                "a slower route at the shifted departure must not push the earliest "
                + "physical arrival later than the nominal one");
        assertEquals(v.requestTime, v.earliestDeparture, 1e-9);
    }

    // -------------------------------------------------------------------------
    // Routing failure at the shifted departure falls back to the nominal-
    // departure arrival (pass 1's firstLeg) instead of dropping the hub.
    // -------------------------------------------------------------------------

    @Test
    void windowedMode_shiftedDepartureRoutingFails_fallsBackToNominalArrival() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = oneHub();
        DrtRequest c = TestRequestBuilder.connectingFixture(null); // requestTime = 0.0
        double advance = 900.0;
        double shiftedDeparture = c.requestTime - advance; // -900

        LegRouter router = failingAtDepartureRouter(hubs, network,
                shiftedDeparture, RURAL_LEG_T, URBAN_LEG_T);

        List<DrtRequest> out = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.URBAN, METRO, network, router, BUFFER,
                0.0, false, advance, 0, true, null);

        assertEquals(1, out.size(), "a shifted-departure routing failure must NOT drop the hub");
        double expectedFallback = c.requestTime + RURAL_LEG_T; // pass 1's nominal-departure arrival
        assertEquals(expectedFallback, out.get(0).requestTime, 1e-9,
                "falls back to the nominal-departure arrival (pass 1's firstLeg)");
        assertEquals(expectedFallback, out.get(0).earliestDeparture, 1e-9);
    }

    // -------------------------------------------------------------------------
    // latestArrival = original.latestArrival, explicitly NOT capped at
    // anchor + maxHubWaitSeconds + secondLeg (D-W6).
    // -------------------------------------------------------------------------

    @Test
    void windowedMode_latestArrival_isOriginalDeadline_notCappedByMaxHubWait() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = oneHub();
        DrtRequest c = TestRequestBuilder.connectingFixture(null); // latestArrival = 3600
        double advance = 900.0;
        // maxHubWaitSeconds set small on purpose: if a maxHubWait cap were
        // reintroduced (anchor(-300) + maxHubWaitSeconds(100) + urbanLeg(900) = 700),
        // it would bind hard against the uncapped 3600 and this assertion would fail.
        double maxHubWaitSeconds = 100.0;

        DrtRequest v = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.URBAN, METRO, network, fakeRouter(hubs, network), BUFFER,
                maxHubWaitSeconds, false, advance, 0, true, null).get(0);

        assertEquals(c.latestArrival, v.latestArrival, 1e-9,
                "latestArrival = original.latestArrival, person-anchored, "
                + "uncapped by maxHubWaitSeconds (D-W6: the wait cap lives only in the MIP)");
    }

    // -------------------------------------------------------------------------
    // Temporal-infeasible hub still dropped with the existing stat + detour row.
    // -------------------------------------------------------------------------

    @Test
    void windowedMode_nominalChainMissesDeadline_isDroppedAsTemporalInfeasible() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = oneHub();
        DrtRequest c = TestRequestBuilder.connectingFixture(null); // latestArrival = 3600
        // Nominal chain: requestTime(0) + ruralLeg(600) + urbanLeg(3200) = 3800 > 3600.
        LegRouter router = legRouterTo(hubs, network, RURAL_LEG_T, 3200.0);
        ExpansionDropStats stats = new ExpansionDropStats();

        List<DrtRequest> out = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.URBAN, METRO, network, router, BUFFER,
                0.0, false, 900.0, 0, true, stats);

        assertTrue(out.isEmpty(), "a hub whose nominal chain misses the deadline must be dropped");
        assertEquals(1, stats.temporalInfeasible);
        assertEquals(0, stats.kept);
        List<HubDetour> dropped = stats.detours.stream()
                .filter(d -> !d.kept() && "temporal_infeasible".equals(d.reason())).toList();
        assertEquals(1, dropped.size(), "a temporal_infeasible detour row must be recorded");
    }

    // -------------------------------------------------------------------------
    // kept detour rows: one per emitted request, reason "kept".
    // -------------------------------------------------------------------------

    @Test
    void windowedMode_producesKeptDetourRow_perHub() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = threeHubs();
        DrtRequest c = TestRequestBuilder.connectingFixture(null);
        ExpansionDropStats stats = new ExpansionDropStats();

        List<DrtRequest> out = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.URBAN, METRO, network, fakeRouter(hubs, network), BUFFER,
                0.0, false, 900.0, 0, true, stats);

        assertEquals(hubs.size(), out.size());
        assertEquals(hubs.size(), stats.kept);
        assertEquals(0, stats.temporalInfeasible);
        List<HubDetour> kept = stats.detours.stream().filter(HubDetour::kept).toList();
        assertEquals(hubs.size(), kept.size());
    }

    // -------------------------------------------------------------------------
    // Legacy mode (hubSyncWindowed = false): unchanged, same counts/values as
    // ContinuationLegWideWindowTest expects.
    // -------------------------------------------------------------------------

    @Test
    void legacyMode_maxHubWaitZero_matchesContinuationLegWideWindowTestExpectations() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = threeHubs();
        DrtRequest c = TestRequestBuilder.connectingFixture(null);

        // The pre-Task-W3/W4 overload (no hubSyncWindowed) — what
        // ContinuationLegWideWindowTest#maxHubWaitZero_reproducesLegacyFixedShiftWindow calls.
        List<DrtRequest> legacyOverload = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.URBAN, METRO, network, fakeRouter(hubs, network), BUFFER,
                /* maxHubWaitSeconds */ 0.0);

        // The widest overload with hubSyncWindowed explicitly false must be
        // byte-identical.
        List<DrtRequest> windowedOff = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.URBAN, METRO, network, fakeRouter(hubs, network), BUFFER,
                /* maxHubWaitSeconds */ 0.0, /* hubSyncTwoSided */ false,
                /* hubSyncMaxAdvanceSeconds */ 0.0, /* hubTopK */ 0,
                /* hubSyncWindowed */ false, null);

        assertFalse(windowedOff.isEmpty());
        assertEquals(legacyOverload.size(), windowedOff.size());
        for (int i = 0; i < windowedOff.size(); i++) {
            DrtRequest legacy = legacyOverload.get(i);
            DrtRequest windowed = windowedOff.get(i);
            assertEquals(legacy.requestTime, windowed.requestTime, 1e-9);
            assertEquals(legacy.earliestDeparture, windowed.earliestDeparture, 1e-9);
            assertEquals(legacy.latestArrival, windowed.latestArrival, 1e-9);
            assertEquals(legacy.transferWaitSeconds, windowed.transferWaitSeconds, 1e-9);
            assertEquals(legacy.hubLegRole, windowed.hubLegRole);

            // Byte-parity with ContinuationLegWideWindowTest's own assertions.
            assertEquals(c.requestTime + RURAL_LEG_T + BUFFER, windowed.requestTime, 1e-9);
            assertEquals(c.earliestDeparture + RURAL_LEG_T + BUFFER, windowed.earliestDeparture, 1e-9);
            assertEquals(c.latestArrival, windowed.latestArrival, 1e-9);
            assertEquals(BUFFER, windowed.transferWaitSeconds, 1e-9);
        }
    }

    @Test
    void legacyMode_maxHubWaitPositive_matchesContinuationLegWideWindowTestExpectations() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = threeHubs();
        DrtRequest c = TestRequestBuilder.connectingFixture(null);
        double maxHubWait = 300.0;
        double hubArrival = c.requestTime + RURAL_LEG_T; // 600

        List<DrtRequest> legacyOverload = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.URBAN, METRO, network, fakeRouter(hubs, network), BUFFER,
                maxHubWait);

        List<DrtRequest> windowedOff = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.URBAN, METRO, network, fakeRouter(hubs, network), BUFFER,
                maxHubWait, /* hubSyncTwoSided */ false, /* hubSyncMaxAdvanceSeconds */ 0.0,
                /* hubTopK */ 0, /* hubSyncWindowed */ false, null);

        double expectedLatest = Math.min(c.latestArrival, hubArrival + maxHubWait + URBAN_LEG_T);
        assertFalse(windowedOff.isEmpty());
        assertEquals(legacyOverload.size(), windowedOff.size());
        for (int i = 0; i < windowedOff.size(); i++) {
            DrtRequest legacy = legacyOverload.get(i);
            DrtRequest windowed = windowedOff.get(i);
            assertEquals(legacy.requestTime, windowed.requestTime, 1e-9);
            assertEquals(legacy.latestArrival, windowed.latestArrival, 1e-9);
            assertEquals(legacy.transferWaitSeconds, windowed.transferWaitSeconds, 1e-9);

            assertEquals(hubArrival, windowed.requestTime, 1e-9,
                    "maxHubWait>0: requestTime = hubArrival (nominal earliest hub departure)");
            assertEquals(hubArrival, windowed.earliestDeparture, 1e-9);
            assertEquals(expectedLatest, windowed.latestArrival, 1e-9,
                    "maxHubWait>0: latestArrival = min(original, hubArrival + maxHubWait + urbanLeg)");
            assertEquals(0.0, windowed.transferWaitSeconds, 1e-9);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers — copied verbatim from DrtRequestFactoryWindowedAccessTest /
    // ContinuationLegWideWindowTest.
    // -------------------------------------------------------------------------

    private static LegRouter fakeRouter(List<HubSetLoader.Hub> hubs, Network network) {
        return legRouterTo(hubs, network, RURAL_LEG_T, URBAN_LEG_T);
    }

    private static LegRouter legRouterTo(List<HubSetLoader.Hub> hubs, Network network,
            double ruralT, double urbanT) {
        java.util.Set<Id<Link>> hubLinks = new java.util.HashSet<>();
        for (HubSetLoader.Hub h : hubs) {
            hubLinks.add(NetworkUtils.getNearestLink(network, h.coord()).getId());
        }
        return (from, to, dep) -> hubLinks.contains(to)
                ? new double[] {ruralT, 6000.0}      // rural leg O->hub
                : new double[] {urbanT, 9000.0};     // urban leg hub->D
    }

    /** Rural leg (O->hub) returns {@code shiftedRuralT} at departure {@code shiftedDep}
     *  and {@code nominalRuralT} at any other departure; urban leg (hub->D) constant. */
    private static LegRouter departureDependentRuralRouter(List<HubSetLoader.Hub> hubs,
            Network network, double nominalRuralT, double shiftedDep, double shiftedRuralT,
            double urbanT) {
        java.util.Set<Id<Link>> hubLinks = new java.util.HashSet<>();
        for (HubSetLoader.Hub h : hubs) {
            hubLinks.add(NetworkUtils.getNearestLink(network, h.coord()).getId());
        }
        return (from, to, dep) -> {
            if (hubLinks.contains(to)) {
                double ruralT = (dep == shiftedDep) ? shiftedRuralT : nominalRuralT;
                return new double[] {ruralT, 6000.0};
            }
            return new double[] {urbanT, 9000.0};
        };
    }

    /** Rural leg (O->hub) returns {@code null} (routing failure) at departure
     *  {@code failDep} and {@code ruralT} otherwise; urban leg (hub->D) constant. */
    private static LegRouter failingAtDepartureRouter(List<HubSetLoader.Hub> hubs,
            Network network, double failDep, double ruralT, double urbanT) {
        java.util.Set<Id<Link>> hubLinks = new java.util.HashSet<>();
        for (HubSetLoader.Hub h : hubs) {
            hubLinks.add(NetworkUtils.getNearestLink(network, h.coord()).getId());
        }
        return (from, to, dep) -> {
            if (hubLinks.contains(to)) {
                if (dep == failDep) return null;
                return new double[] {ruralT, 6000.0};
            }
            return new double[] {urbanT, 9000.0};
        };
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
}
