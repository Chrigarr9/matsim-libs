package org.matsim.contrib.demand_extraction.demand;

import java.util.List;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 11c (Paper-2 hub-sync v2, two-sided): when {@code hubSyncTwoSided} is ON,
 * the ACCESS (rural→hub) branch of {@code expandConnecting} must emit MULTIPLE
 * access variants per (commuter, hub) at earlier-departure offsets
 * {@code 0, step, 2·step, …} where {@code step = maxHubWaitSeconds}, while
 * {@code offset ≤ hubSyncMaxAdvanceSeconds}. Each variant departs at
 * {@code requestTime − offset} (both {@code requestTime} and
 * {@code earliestDeparture} shifted), re-routed at the new departure time, so the
 * hub arrival clusters earlier and the Python side can bin the diversity.
 *
 * <p>Backward-compat (HARD): with {@code hubSyncTwoSided = false} (default) the
 * ACCESS branch emits exactly ONE access leg per (commuter, hub) at the original
 * {@code requestTime} — byte-identical to today. Offset 0 reproduces that leg.
 *
 * <p>Mirrors the {@code expandConnecting} harness in
 * {@link ContinuationLegWideWindowTest}: a sparse in-memory grid network, the
 * {@link TestRequestBuilder#connectingFixture} (requestTime=0,
 * earliestDeparture=0, latestArrival=3600, directTT=600), and a fake router
 * returning rural leg = 600 s / 6000 m (TO a hub link) and urban leg =
 * 900 s / 9000 m (FROM a hub link).
 */
public class AccessLegSyncVariantsTest {

    private static final double BUFFER = 300.0;
    private static final double RURAL_LEG_T = 600.0;
    private static final double URBAN_LEG_T = 900.0;

    // -------------------------------------------------------------------------
    // ON: multiple access variants per hub at earlier-departure offsets.
    // -------------------------------------------------------------------------

    @Test
    void twoSidedOn_emitsAccessVariantsAtEarlierOffsets() {
        Network network = buildGridNetwork();
        // Single hub so the variant ladder is unambiguous.
        List<HubSetLoader.Hub> hubs = oneHub();
        DrtRequest c = TestRequestBuilder.connectingFixture(null);

        double maxHubWait = 120.0;       // step
        double maxAdvance = 300.0;       // bound → offsets {0,120,240}

        List<DrtRequest> out = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.RURAL, coord -> coord.getX() >= 500.0,
                network, fakeRouter(hubs, network), BUFFER, maxHubWait,
                /* hubSyncTwoSided */ true, /* hubSyncMaxAdvanceSeconds */ maxAdvance,
                null);

        assertEquals(3, out.size(),
                "offsets {0,120,240} → 3 access variants for 1 hub");

        double[] expectedRequestTimes = {0.0, -120.0, -240.0};
        for (int k = 0; k < out.size(); k++) {
            DrtRequest v = out.get(k);
            assertEquals(HubLegRole.ACCESS_LEG, v.hubLegRole, "variant role = ACCESS_LEG");
            assertEquals(0.0, v.transferWaitSeconds, 1e-9, "ACCESS transferWait = 0");
            assertEquals(expectedRequestTimes[k], v.requestTime, 1e-9,
                    "variant " + k + " requestTime = original − offset");
            assertEquals(expectedRequestTimes[k], v.earliestDeparture, 1e-9,
                    "variant " + k + " earliestDeparture = original − offset");
        }
    }

    /** Offset 0 (first variant) reproduces today's single access leg exactly. */
    @Test
    void twoSidedOn_offsetZeroReproducesTodayLeg() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = oneHub();
        DrtRequest c = TestRequestBuilder.connectingFixture(null);

        DrtRequest today = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.RURAL, coord -> coord.getX() >= 500.0,
                network, fakeRouter(hubs, network), BUFFER, /* maxHubWait */ 120.0).get(0);

        DrtRequest v0 = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.RURAL, coord -> coord.getX() >= 500.0,
                network, fakeRouter(hubs, network), BUFFER, /* maxHubWait */ 120.0,
                /* hubSyncTwoSided */ true, /* hubSyncMaxAdvanceSeconds */ 300.0,
                null).get(0);

        assertEquals(today.requestTime, v0.requestTime, 1e-9);
        assertEquals(today.earliestDeparture, v0.earliestDeparture, 1e-9);
        assertEquals(today.latestArrival, v0.latestArrival, 1e-9);
        assertEquals(today.directTravelTime, v0.directTravelTime, 1e-9);
        assertEquals(today.directDistance, v0.directDistance, 1e-9);
        assertEquals(today.hubLegRole, v0.hubLegRole);
        assertEquals(today.transferWaitSeconds, v0.transferWaitSeconds, 1e-9);
    }

    // -------------------------------------------------------------------------
    // Default OFF: byte-identical to today (exactly one access leg per hub).
    // -------------------------------------------------------------------------

    @Test
    void twoSidedOff_emitsSingleAccessLegPerHubAtOriginalRequestTime() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = threeHubs();
        DrtRequest c = TestRequestBuilder.connectingFixture(null);

        List<DrtRequest> out = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.RURAL, coord -> coord.getX() >= 500.0,
                network, fakeRouter(hubs, network), BUFFER, /* maxHubWait */ 120.0,
                /* hubSyncTwoSided */ false, /* hubSyncMaxAdvanceSeconds */ 300.0,
                null);

        assertEquals(hubs.size(), out.size(),
                "OFF: exactly one access leg per hub (regression guard)");
        for (DrtRequest v : out) {
            assertEquals(HubLegRole.ACCESS_LEG, v.hubLegRole);
            assertEquals(c.requestTime, v.requestTime, 1e-9,
                    "OFF: access departs at original requestTime");
            assertEquals(c.earliestDeparture, v.earliestDeparture, 1e-9);
        }
    }

    /** The default convenience overload (no twosided params) is unchanged. */
    @Test
    void defaultOverload_emitsSingleAccessLegPerHub() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = threeHubs();
        DrtRequest c = TestRequestBuilder.connectingFixture(null);

        List<DrtRequest> out = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.RURAL, coord -> coord.getX() >= 500.0,
                network, fakeRouter(hubs, network), BUFFER, /* maxHubWait */ 0.0);

        assertEquals(hubs.size(), out.size());
        for (DrtRequest v : out) {
            assertEquals(c.requestTime, v.requestTime, 1e-9);
        }
    }

    // -------------------------------------------------------------------------
    // Step-guard: twosided ON but maxHubWait <= 0 → throws.
    // -------------------------------------------------------------------------

    @Test
    void twoSidedOn_zeroStep_throws() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = oneHub();
        DrtRequest c = TestRequestBuilder.connectingFixture(null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                DrtRequestFactory.expandConnecting(
                        c, hubs, FleetSide.RURAL, coord -> coord.getX() >= 500.0,
                        network, fakeRouter(hubs, network), BUFFER, /* maxHubWait */ 0.0,
                        /* hubSyncTwoSided */ true, /* hubSyncMaxAdvanceSeconds */ 300.0,
                        null));
        assertTrue(ex.getMessage().toLowerCase().contains("maxhubwait"),
                "message must name the offending knob: " + ex.getMessage());
    }

    // -------------------------------------------------------------------------
    // step > maxAdvance → only offset 0 → one variant.
    // -------------------------------------------------------------------------

    @Test
    void twoSidedOn_stepBiggerThanAdvance_emitsOnlyOffsetZero() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = oneHub();
        DrtRequest c = TestRequestBuilder.connectingFixture(null);

        List<DrtRequest> out = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.RURAL, coord -> coord.getX() >= 500.0,
                network, fakeRouter(hubs, network), BUFFER, /* maxHubWait */ 1000.0,
                /* hubSyncTwoSided */ true, /* hubSyncMaxAdvanceSeconds */ 300.0,
                null);

        assertEquals(1, out.size(), "step > maxAdvance → only offset 0");
        assertEquals(c.requestTime, out.get(0).requestTime, 1e-9);
    }

    // -------------------------------------------------------------------------
    // Temporal-infeasible variants are skipped, not crashing.
    // -------------------------------------------------------------------------

    @Test
    void twoSidedOn_infeasibleVariantsSkipped() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = oneHub();
        DrtRequest c = TestRequestBuilder.connectingFixture(null);

        // rural leg (TO hub) = 600 s; urban leg (FROM hub) = 2800 s.
        // legLatestArrival = 3600 − 2800 − 300 = 500. Variant fits iff
        // newRequestTime + 600 <= 500  ⇒  newRequestTime <= -100.
        // offsets {0,120,240}: requestTimes {0,-120,-240}.
        //   offset 0   → 0   > -100 → infeasible (skipped)
        //   offset 120 → -120 <= -100 → feasible
        //   offset 240 → -240 <= -100 → feasible
        LegRouter router = legRouterTo(hubs, network, RURAL_LEG_T, 2800.0);
        DrtRequestFactory.ExpansionDropStats stats = new DrtRequestFactory.ExpansionDropStats();

        List<DrtRequest> out = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.RURAL, coord -> coord.getX() >= 500.0,
                network, router, BUFFER, /* maxHubWait */ 120.0,
                /* hubSyncTwoSided */ true, /* hubSyncMaxAdvanceSeconds */ 300.0,
                stats);

        assertEquals(2, out.size(), "offset 0 infeasible, offsets 120 & 240 feasible");
        assertEquals(1, stats.temporalInfeasible, "exactly one variant temporal-infeasible");
        for (DrtRequest v : out) {
            assertTrue(v.requestTime <= -100.0 + 1e-9, "kept variants depart early enough");
        }
    }

    // -------------------------------------------------------------------------
    // Helpers — router, network, hubs (mirrors ContinuationLegWideWindowTest).
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
