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
import org.matsim.contrib.demand_extraction.demand.DrtRequestFactory.LegRouter;
import org.matsim.core.network.NetworkUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 10b (Paper-2 hub-sync v1): the CONTINUATION (hub→urban) virtual leg must
 * get a WIDE hub-departure/wait window when {@code maxHubWaitSeconds > 0}, so the
 * urban shareability graph can enumerate pooled continuation bundles at different
 * departure slots within {@code [hubArrival, hubArrival + maxHubWait]}.
 *
 * <p>Backward-compat contract: with {@code maxHubWaitSeconds = 0} (the default)
 * the continuation leg's window is byte-identical to the legacy fixed-buffer
 * split (departure pinned at {@code requestTime + ruralLeg + buffer},
 * {@code transferWaitSeconds = buffer}).
 *
 * <p>Mirrors the {@code expandConnecting} harness in
 * {@link DrtRequestFactoryVirtualTripTest}: a sparse in-memory grid network, the
 * {@link TestRequestBuilder#connectingFixture} (requestTime=0,
 * earliestDeparture=0, latestArrival=3600, directTT=600), and a fake router
 * returning rural leg = 600 s / 6000 m (TO a hub link) and urban leg =
 * 900 s / 9000 m (FROM a hub link). So {@code hubArrival = 0 + 600 = 600}.
 */
public class ContinuationLegWideWindowTest {

    private static final double BUFFER = 300.0;
    private static final double RURAL_LEG_T = 600.0;
    private static final double URBAN_LEG_T = 900.0;

    // -------------------------------------------------------------------------
    // Backward-compat: maxHubWait = 0 reproduces the legacy fixed-buffer window.
    // -------------------------------------------------------------------------

    @Test
    void maxHubWaitZero_reproducesLegacyFixedShiftWindow() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = threeHubs();
        DrtRequest c = TestRequestBuilder.connectingFixture(null);

        List<DrtRequest> out = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.URBAN, coord -> coord.getX() >= 500.0,
                network, fakeRouter(hubs, network), BUFFER, /* maxHubWait */ 0.0);

        assertFalse(out.isEmpty(), "continuation copies must be feasible for the wide fixture window");
        for (DrtRequest v : out) {
            // Legacy: departure pinned at original + ruralLeg + buffer.
            assertEquals(c.requestTime + RURAL_LEG_T + BUFFER, v.requestTime, 1e-9,
                    "maxHubWait=0: requestTime = original + ruralLeg + buffer (legacy)");
            assertEquals(c.earliestDeparture + RURAL_LEG_T + BUFFER, v.earliestDeparture, 1e-9,
                    "maxHubWait=0: earliestDeparture = original + ruralLeg + buffer (legacy)");
            assertEquals(c.latestArrival, v.latestArrival, 1e-9,
                    "maxHubWait=0: full-trip deadline kept (legacy)");
            assertEquals(BUFFER, v.transferWaitSeconds, 1e-9,
                    "maxHubWait=0: buffer charged as transfer wait (legacy)");
            assertEquals(DrtRequest.HubLegRole.CONTINUATION_LEG, v.hubLegRole);
            assertEquals(URBAN_LEG_T, v.directTravelTime, 1e-9, "urban leg's own direct tt");
        }
    }

    // -------------------------------------------------------------------------
    // Wide window: maxHubWait > 0 widens the continuation departure window.
    // -------------------------------------------------------------------------

    @Test
    void maxHubWaitPositive_widensContinuationDepartureWindow() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = threeHubs();
        DrtRequest c = TestRequestBuilder.connectingFixture(null);
        double maxHubWait = 300.0;
        double hubArrival = c.requestTime + RURAL_LEG_T; // 600

        List<DrtRequest> out = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.URBAN, coord -> coord.getX() >= 500.0,
                network, fakeRouter(hubs, network), BUFFER, maxHubWait);

        assertFalse(out.isEmpty(), "continuation copies must be feasible");
        double expectedLatest = Math.min(c.latestArrival, hubArrival + maxHubWait + URBAN_LEG_T);
        for (DrtRequest v : out) {
            assertEquals(hubArrival, v.requestTime, 1e-9,
                    "maxHubWait>0: requestTime = hubArrival (nominal earliest hub departure)");
            assertEquals(hubArrival, v.earliestDeparture, 1e-9,
                    "maxHubWait>0: earliestDeparture = hubArrival");
            assertEquals(expectedLatest, v.latestArrival, 1e-9,
                    "maxHubWait>0: latestArrival = min(original, hubArrival + maxHubWait + urbanLeg)");
            assertEquals(0.0, v.transferWaitSeconds, 1e-9,
                    "maxHubWait>0: transferWait = 0 (served wait realized by bundling)");
            assertEquals(DrtRequest.HubLegRole.CONTINUATION_LEG, v.hubLegRole);
            assertEquals(URBAN_LEG_T, v.directTravelTime, 1e-9, "urban leg's own direct tt");
        }

        // The departure window is genuinely widened vs the fixed case: the
        // continuation leg now nominally departs maxHubWait + buffer earlier than
        // the legacy pin (hubArrival vs hubArrival + buffer), and its deadline
        // sits at hubArrival + maxHubWait + urbanLeg = 1800 (< original 3600),
        // giving exactly a maxHubWait-wide departure window.
        DrtRequest v0 = out.get(0);
        assertEquals(maxHubWait, v0.latestArrival - v0.earliestDeparture - URBAN_LEG_T, 1e-9,
                "departure window width = latestArrival - earliestDeparture - urbanLeg = maxHubWait");
    }

    // -------------------------------------------------------------------------
    // Temporal drop: cannot make the deadline even departing immediately.
    // -------------------------------------------------------------------------

    @Test
    void continuationLeg_droppedWhenCannotMakeDeadlineEvenDepartingNow() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = threeHubs();
        DrtRequest c = TestRequestBuilder.connectingFixture(null);
        // hubArrival = 600. Make urbanLeg so long that 600 + urbanLeg > 3600.
        // rural leg (TO hub) = 600 s; urban leg (FROM hub) = 3200 s →
        // 600 + 3200 = 3800 > 3600 = latestArrival → dropped.
        LegRouter router = legRouterTo(hubs, network, RURAL_LEG_T, 3200.0);

        DrtRequestFactory.ExpansionDropStats stats = new DrtRequestFactory.ExpansionDropStats();
        List<DrtRequest> out = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.URBAN, coord -> coord.getX() >= 500.0,
                network, router, BUFFER, /* maxHubWait */ 300.0, stats);

        assertTrue(out.isEmpty(),
                "continuation leg must be dropped when hubArrival + urbanLeg > latestArrival");
        assertEquals(hubs.size(), stats.temporalInfeasible,
                "every hub charged to the temporal-infeasible bucket");
        assertEquals(0, stats.kept);
    }

    // -------------------------------------------------------------------------
    // SYNC-10: non-adjacent anchor collisions are deduplicated.
    // -------------------------------------------------------------------------

    /**
     * Departure-dependent routing can make two NON-ADJACENT access offsets land
     * on the SAME hub arrival, with a DIFFERENT anchor in between. Here the
     * rural (O->hub) leg travel time varies with departure so that offsets
     * k=0 and k=2 both anchor at hubArrival = t0+600, while k=1 anchors at
     * t0+200:
     * <pre>
     *   k=0: dep = 0    , ruralT = 600  -> anchor = 0    + 600  = 600
     *   k=1: dep = -300 , ruralT = 500  -> anchor = -300 + 500  = 200
     *   k=2: dep = -600 , ruralT = 1200 -> anchor = -600 + 1200 = 600  (== k=0)
     * </pre>
     * The old {@code prevAnchor} guard only caught ADJACENT duplicates, so it
     * let the k=2 collision through as a third, duplicate continuation variant.
     * The seen-anchor set dedupes it: exactly two distinct anchors
     * (requestTimes 200 and 600), i.e. 2 variants, not 3.
     */
    @Test
    void nonAdjacentAnchorCollisionIsDeduplicated() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = oneHub();
        DrtRequest c = TestRequestBuilder.connectingFixture(null);
        double maxHubWait = 300.0;   // step  -> offsets {0, 300, 600}
        double maxAdvance = 600.0;   // bound -> numContVariants = floor(600/300)+1 = 3

        List<DrtRequest> out = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.URBAN, coord -> coord.getX() >= 500.0,
                network, departureDependentRuralRouter(hubs, network), BUFFER, maxHubWait,
                /* hubSyncTwoSided */ true, /* hubSyncMaxAdvanceSeconds */ maxAdvance,
                null);

        assertEquals(2, out.size(),
                "non-adjacent collision (k=0 & k=2 both anchor at 600) must dedupe to 2 variants, not 3");

        java.util.Set<Double> anchors = new java.util.HashSet<>();
        for (DrtRequest v : out) {
            assertEquals(DrtRequest.HubLegRole.CONTINUATION_LEG, v.hubLegRole);
            assertEquals(URBAN_LEG_T, v.directTravelTime, 1e-9, "urban leg's own direct tt");
            assertEquals(v.requestTime, v.earliestDeparture, 1e-9,
                    "continuation earliestDeparture pinned to its own anchor");
            anchors.add(v.requestTime);
        }
        assertEquals(java.util.Set.of(200.0, 600.0), anchors,
                "distinct anchors are t0+200 and t0+600 (the k=2 duplicate of t0+600 dropped)");
    }

    // -------------------------------------------------------------------------
    // Helpers — router, network, hubs (mirrors DrtRequestFactoryVirtualTripTest).
    // -------------------------------------------------------------------------

    /**
     * Rural leg (O->hub) travel time DEPENDS on departure so offsets k=0 and
     * k=2 collide on the same hub anchor (600) while k=1 differs (200):
     * dep 0 -> 600, dep -300 -> 500, dep -600 -> 1200. Urban leg (hub->D) is
     * the constant continuation leg (900 s / 9000 m).
     */
    private static LegRouter departureDependentRuralRouter(List<HubSetLoader.Hub> hubs, Network network) {
        java.util.Set<Id<Link>> hubLinks = new java.util.HashSet<>();
        for (HubSetLoader.Hub h : hubs) {
            hubLinks.add(NetworkUtils.getNearestLink(network, h.coord()).getId());
        }
        return (from, to, dep) -> {
            if (hubLinks.contains(to)) {
                double ruralT;
                if (dep == -300.0) {
                    ruralT = 500.0;
                } else if (dep == -600.0) {
                    ruralT = 1200.0;
                } else {
                    ruralT = RURAL_LEG_T; // dep == 0 (and any other departure)
                }
                return new double[] {ruralT, 6000.0}; // rural leg O->hub
            }
            return new double[] {URBAN_LEG_T, 9000.0}; // urban leg hub->D
        };
    }

    /** Rural leg (TO a hub link) = 600 s / 6000 m; urban leg (FROM a hub link)
     *  = 900 s / 9000 m. */
    private static LegRouter fakeRouter(List<HubSetLoader.Hub> hubs, Network network) {
        return legRouterTo(hubs, network, RURAL_LEG_T, URBAN_LEG_T);
    }

    /** Returns {ruralT, 6000} for ODs ending at a hub link, {urbanT, 9000}
     *  for ODs starting at a hub link. */
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
