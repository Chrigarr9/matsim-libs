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
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * D-W1 + D-W6 (Task W3, 2026-08-25 plan
 * {@code docs/superpowers/plans/2026-08-25-booking-horizons-urban-whitelist.md},
 * design {@code docs/superpowers/specs/2026-08-25-window-based-hub-sync-design.md}):
 * windowed ACCESS leg emission inside {@link DrtRequestFactory#expandConnecting}.
 *
 * <p>Harness copied from {@link DrtRequestFactoryHubTopKTest} / {@link AccessLegSyncVariantsTest}:
 * drives the static {@code expandConnecting} directly with a synthetic hub list and a stub
 * {@link LegRouter}, no {@code DrtRequestFactory} instance and no MATSim {@code Controler}.
 * The network/hub/router helpers below are copied verbatim from
 * {@link AccessLegSyncVariantsTest} (grid network, rural leg 600 s TO a hub link, urban
 * leg 900 s FROM a hub link, {@code BUFFER = 300}), so the legacy-mode assertions can be
 * compared directly against that class's expectations.
 */
class DrtRequestFactoryWindowedAccessTest {

    private static final double BUFFER = 300.0;
    private static final double RURAL_LEG_T = 600.0;
    private static final double URBAN_LEG_T = 900.0;
    private static final Predicate<Coord> METRO = coord -> coord.getX() >= 500.0;

    // -------------------------------------------------------------------------
    // Windowed mode: exactly one ACCESS request per (trip, hub).
    // -------------------------------------------------------------------------

    @Test
    void windowedMode_emitsExactlyOneAccessRequestPerHub() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = threeHubs();
        DrtRequest c = TestRequestBuilder.connectingFixture(null);

        List<DrtRequest> out = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.RURAL, METRO, network, fakeRouter(hubs, network), BUFFER,
                /* maxHubWaitSeconds */ 0.0, /* hubSyncTwoSided */ false,
                /* hubSyncMaxAdvanceSeconds */ 900.0, /* hubTopK */ 0,
                /* hubSyncWindowed */ true, null);

        assertEquals(hubs.size(), out.size(),
                "windowed mode: exactly ONE ACCESS request per (trip, hub)");
        for (DrtRequest v : out) {
            assertEquals(HubLegRole.ACCESS_LEG, v.hubLegRole);
            assertEquals(0.0, v.transferWaitSeconds, 1e-9);
        }
    }

    // -------------------------------------------------------------------------
    // requestTime stays nominal (the KPI anchor, never shifted).
    // -------------------------------------------------------------------------

    @Test
    void windowedMode_requestTimeStaysNominal() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = oneHub();
        DrtRequest c = TestRequestBuilder.connectingFixture(null); // requestTime = 0.0

        DrtRequest v = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.RURAL, METRO, network, fakeRouter(hubs, network), BUFFER,
                0.0, false, 900.0, 0, true, null).get(0);

        assertEquals(c.requestTime, v.requestTime, 1e-9,
                "requestTime = original.requestTime, NOT shifted by the advance window");
    }

    // -------------------------------------------------------------------------
    // earliestDeparture = requestTime - hubSyncMaxAdvanceSeconds, combined
    // ADDITIVELY (min) with the existing budget-flex earliestDeparture.
    // -------------------------------------------------------------------------

    @Test
    void windowedMode_earliestDeparture_advanceWidensPastNominalWhenFlexIsNarrower() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = oneHub();
        // Budget flex only opened earliestDeparture to -200 (narrower than the
        // 900 s advance window) -> the advance-widened bound (-900) must win.
        DrtRequest c = TestRequestBuilder.connectingFixture(null)
                .toBuilder().earliestDeparture(-200.0).build();

        DrtRequest v = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.RURAL, METRO, network, fakeRouter(hubs, network), BUFFER,
                0.0, false, /* hubSyncMaxAdvanceSeconds */ 900.0, 0, true, null).get(0);

        assertEquals(c.requestTime - 900.0, v.earliestDeparture, 1e-9,
                "requestTime(0) - hubSyncMaxAdvanceSeconds(900) = -900, earlier than the -200 flex bound");
    }

    @Test
    void windowedMode_earliestDeparture_takesMinWhenBudgetFlexIsAlreadyEarlier() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = oneHub();
        // Budget flex already opened earliestDeparture to -1500, EARLIER than
        // requestTime(0) - hubSyncMaxAdvanceSeconds(900) = -900 -> the pre-existing
        // (more restrictive/earlier) flex bound must survive: min(-1500, -900) = -1500.
        DrtRequest c = TestRequestBuilder.connectingFixture(null)
                .toBuilder().earliestDeparture(-1500.0).build();

        DrtRequest v = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.RURAL, METRO, network, fakeRouter(hubs, network), BUFFER,
                0.0, false, /* hubSyncMaxAdvanceSeconds */ 900.0, 0, true, null).get(0);

        assertEquals(-1500.0, v.earliestDeparture, 1e-9,
                "min(original.earliestDeparture=-1500, requestTime-advance=-900) = -1500");
    }

    // -------------------------------------------------------------------------
    // latestArrival = original.latestArrival - secondLegDirect - transferBuffer,
    // explicitly NOT capped by maxHubWaitSeconds (D-W6).
    // -------------------------------------------------------------------------

    @Test
    void windowedMode_latestArrival_isFullSlackBackout_notCappedByMaxHubWait() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = oneHub();
        DrtRequest c = TestRequestBuilder.connectingFixture(null);
        // legLatestArrival = latestArrival(3600) - secondLeg/urban(900) - buffer(300) = 2400.
        // maxHubWaitSeconds is set small (100) on purpose: if a maxHubWait cap were
        // reintroduced (e.g. nominalHubArrival(600) + maxHubWaitSeconds(100) = 700), it
        // would bind hard against the uncapped 2400 and this assertion would fail.
        double maxHubWaitSeconds = 100.0;

        DrtRequest v = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.RURAL, METRO, network, fakeRouter(hubs, network), BUFFER,
                maxHubWaitSeconds, /* hubSyncTwoSided */ false,
                /* hubSyncMaxAdvanceSeconds */ 900.0, 0, /* hubSyncWindowed */ true, null).get(0);

        assertEquals(3600.0 - 900.0 - 300.0, v.latestArrival, 1e-9,
                "latestArrival = original.latestArrival - secondLegDirect - transferBuffer, "
                + "uncapped by maxHubWaitSeconds (D-W6: the wait cap lives only in the MIP)");
    }

    @Test
    void windowedMode_directTravelTimeAndDistance_reusePass1NominalRouting() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = oneHub();
        DrtRequest c = TestRequestBuilder.connectingFixture(null);

        DrtRequest v = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.RURAL, METRO, network, fakeRouter(hubs, network), BUFFER,
                0.0, false, 900.0, 0, true, null).get(0);

        assertEquals(RURAL_LEG_T, v.directTravelTime, 1e-9,
                "reuses pass 1's nominal-departure firstLeg[0], no re-routing");
        assertEquals(6000.0, v.directDistance, 1e-9,
                "reuses pass 1's nominal-departure firstLeg[1], no re-routing");
    }

    @Test
    void windowedMode_producesKeptDetourRow_noTopkOrTemporalDrops() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = threeHubs();
        DrtRequest c = TestRequestBuilder.connectingFixture(null);

        ExpansionDropStats stats = new ExpansionDropStats();
        List<DrtRequest> out = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.RURAL, METRO, network, fakeRouter(hubs, network), BUFFER,
                0.0, false, 900.0, 0, true, stats);

        assertEquals(hubs.size(), out.size());
        assertEquals(hubs.size(), stats.kept);
        assertEquals(0, stats.droppedByTopK);
        assertEquals(0, stats.temporalInfeasible);
        List<HubDetour> kept = stats.detours.stream().filter(HubDetour::kept).toList();
        assertEquals(hubs.size(), kept.size());
    }

    // -------------------------------------------------------------------------
    // Legacy mode (hubSyncWindowed = false): variant-grid behaviour completely
    // unchanged, same count as AccessLegSyncVariantsTest expects.
    // -------------------------------------------------------------------------

    @Test
    void legacyMode_hubSyncWindowedFalse_stillEmitsVariantGrid_sameCountAsAccessLegSyncVariantsTest() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = oneHub();
        DrtRequest c = TestRequestBuilder.connectingFixture(null);

        double maxHubWait = 120.0;   // step
        double maxAdvance = 300.0;   // bound -> offsets {0,120,240}

        // The pre-Task-W3 overload (no hubSyncWindowed parameter at all) — exactly
        // what AccessLegSyncVariantsTest#twoSidedOn_emitsAccessVariantsAtEarlierOffsets calls.
        List<DrtRequest> legacyOverload = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.RURAL, METRO, network, fakeRouter(hubs, network), BUFFER,
                maxHubWait, /* hubSyncTwoSided */ true, /* hubSyncMaxAdvanceSeconds */ maxAdvance,
                null);

        // The new widest overload with hubSyncWindowed explicitly false must be
        // byte-identical to the pre-Task-W3 overload.
        List<DrtRequest> windowedOff = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.RURAL, METRO, network, fakeRouter(hubs, network), BUFFER,
                maxHubWait, /* hubSyncTwoSided */ true, /* hubSyncMaxAdvanceSeconds */ maxAdvance,
                /* hubTopK */ 0, /* hubSyncWindowed */ false, null);

        assertEquals(3, windowedOff.size(),
                "offsets {0,120,240} -> 3 access variants for 1 hub (AccessLegSyncVariantsTest expectation)");
        assertEquals(legacyOverload.size(), windowedOff.size());

        double[] expectedRequestTimes = {0.0, -120.0, -240.0};
        for (int k = 0; k < windowedOff.size(); k++) {
            DrtRequest legacy = legacyOverload.get(k);
            DrtRequest windowed = windowedOff.get(k);
            assertEquals(expectedRequestTimes[k], windowed.requestTime, 1e-9);
            assertEquals(legacy.requestTime, windowed.requestTime, 1e-9);
            assertEquals(legacy.earliestDeparture, windowed.earliestDeparture, 1e-9);
            assertEquals(legacy.latestArrival, windowed.latestArrival, 1e-9);
            assertEquals(legacy.directTravelTime, windowed.directTravelTime, 1e-9);
            assertEquals(legacy.directDistance, windowed.directDistance, 1e-9);
            assertEquals(legacy.hubLegRole, windowed.hubLegRole);
            assertEquals(legacy.transferWaitSeconds, windowed.transferWaitSeconds, 1e-9);
        }
    }

    @Test
    void legacyMode_defaultSingleAccessOverload_unaffectedByHubSyncWindowedParam() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = threeHubs();
        DrtRequest c = TestRequestBuilder.connectingFixture(null);

        // hubSyncTwoSided = false, hubSyncWindowed = false: exactly one access leg
        // per hub at the original requestTime (regression guard, mirrors
        // AccessLegSyncVariantsTest#twoSidedOff_emitsSingleAccessLegPerHubAtOriginalRequestTime).
        List<DrtRequest> out = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.RURAL, METRO, network, fakeRouter(hubs, network), BUFFER,
                /* maxHubWaitSeconds */ 120.0, /* hubSyncTwoSided */ false,
                /* hubSyncMaxAdvanceSeconds */ 300.0, /* hubTopK */ 0,
                /* hubSyncWindowed */ false, null);

        assertEquals(hubs.size(), out.size());
        for (DrtRequest v : out) {
            assertEquals(HubLegRole.ACCESS_LEG, v.hubLegRole);
            assertEquals(c.requestTime, v.requestTime, 1e-9);
            assertEquals(c.earliestDeparture, v.earliestDeparture, 1e-9);
        }
    }

    // Window-aware temporal feasibility. legLatestArrival = latestArrival(3600)
    // - urbanLeg(900) - BUFFER(300) = 2400. The fixture departs at requestTime 0.

    @Test
    void windowedMode_hubInfeasibleAtNominalButReachableWithAdvance_isKept() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = oneHub();
        DrtRequest c = TestRequestBuilder.connectingFixture(null);

        // rural leg 2700 s: 0 + 2700 > 2400 (infeasible at the nominal departure),
        // but -900 + 2700 = 1800 <= 2400 with the advance. Rescuing exactly this
        // case is what D-W1 windowing is for, so the check must NOT be nominal-only.
        List<DrtRequest> out = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.RURAL, METRO, network,
                legRouterTo(hubs, network, 2700.0, URBAN_LEG_T), BUFFER,
                /* maxHubWaitSeconds */ 0.0, /* hubSyncTwoSided */ false,
                /* hubSyncMaxAdvanceSeconds */ 900.0, /* hubTopK */ 0,
                /* hubSyncWindowed */ true, null);

        assertEquals(1, out.size(),
                "a hub reachable only with advance must survive the windowed feasibility check");
    }

    @Test
    void windowedMode_hubUnreachableEvenAtEarliestDeparture_isDroppedAsTemporalInfeasible() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = oneHub();
        DrtRequest c = TestRequestBuilder.connectingFixture(null);
        DrtRequestFactory.ExpansionDropStats stats = new DrtRequestFactory.ExpansionDropStats();

        // rural leg 3600 s: even the earliest departure in the window misses the
        // deadline (-900 + 3600 = 2700 > 2400). Emitting it would produce a request
        // whose latestArrival precedes its earliest possible arrival.
        List<DrtRequest> out = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.RURAL, METRO, network,
                legRouterTo(hubs, network, 3600.0, URBAN_LEG_T), BUFFER,
                /* maxHubWaitSeconds */ 0.0, /* hubSyncTwoSided */ false,
                /* hubSyncMaxAdvanceSeconds */ 900.0, /* hubTopK */ 0,
                /* hubSyncWindowed */ true, stats);

        assertEquals(0, out.size(), "an unreachable hub must not be emitted");
        assertEquals(1, stats.temporalInfeasible);
        assertEquals(0, stats.kept);
    }

    // -------------------------------------------------------------------------
    // Helpers — copied verbatim from AccessLegSyncVariantsTest.
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
