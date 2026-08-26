package org.matsim.contrib.demand_extraction.demand;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-W5 / Task W2 (2026-08-25 plan, {@code docs/superpowers/specs/2026-08-25-window-based-hub-sync-design.md}):
 * hub top-K selection inside {@link DrtRequestFactory#expandConnecting}.
 *
 * <p>Harness copied from {@link AccessLegSyncVariantsTest} / {@link ContinuationLegWideWindowTest}:
 * drives the static {@code expandConnecting} directly with a synthetic hub list and a
 * stub {@link LegRouter}, no {@code DrtRequestFactory} instance and no MATSim
 * {@code Controler}. Unlike those tests (which use one uniform leg-time pair for every
 * hub), this fixture needs DISTINCT round-trip leg times per hub so the top-K ranking
 * is unambiguous, so each hub gets its own tiny network link (well separated in space)
 * and the router looks up leg times by which hub link is being routed to/from.
 *
 * <p>The five hub leg-time sums are chosen so that rank order and hub-list order
 * DIFFER — hub_1/hub_3/hub_4 are the three smallest sums but are not contiguous in the
 * hub list — so a test that only checked "which 3 hubs survive" could pass even if the
 * implementation emitted them in rank order instead of hub-list order. Asserting the
 * exact emission order against hub-list order (not rank order) is what proves
 * "ranking decides survival only, never emission order" (the spec's correctness
 * requirement 1).
 */
class DrtRequestFactoryHubTopKTest {

    private static final double BUFFER = 300.0;
    private static final Predicate<Coord> METRO = coord -> coord.getX() >= 500.0;

    // hub_0..hub_4 round-trip (rural+urban) leg-time sums: 1000, 200, 1200, 150, 400.
    // Ascending rank: hub_3(150), hub_1(200), hub_4(400), hub_0(1000), hub_2(1200).
    // Top-3 survivors = {hub_1, hub_3, hub_4}; in ORIGINAL hub-list order that is
    // [hub_1, hub_3, hub_4] (hub_0 and hub_2 dropped, list order preserved otherwise).
    private static final Map<String, Double> HUB_SUMS = Map.of(
            "hub_0", 1000.0,
            "hub_1", 200.0,
            "hub_2", 1200.0,
            "hub_3", 150.0,
            "hub_4", 400.0);

    // -------------------------------------------------------------------------
    // hubTopK = 3: exactly the 3 smallest-sum hubs survive, in hub-list order.
    // -------------------------------------------------------------------------

    @Test
    void hubTopKThree_keepsThreeSmallestSumHubs_inHubListOrder_bothRolesPresent() {
        Network network = fiveHubNetwork();
        List<HubSetLoader.Hub> hubs = fiveHubs();
        LegRouter router = perHubRouter(hubs, network, HUB_SUMS);
        DrtRequest c = TestRequestBuilder.connectingFixture(null);

        ExpansionDropStats accessStats = new ExpansionDropStats();
        List<DrtRequest> access = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.RURAL, METRO, network, router, BUFFER,
                /* maxHubWaitSeconds */ 0.0, /* hubSyncTwoSided */ false,
                /* hubSyncMaxAdvanceSeconds */ 0.0, /* hubTopK */ 3, accessStats);

        assertEquals(List.of("hub_1", "hub_3", "hub_4"),
                access.stream().map(r -> r.hubId).toList(),
                "survivors emitted in ORIGINAL hub-list order, not rank order");
        for (DrtRequest r : access) {
            assertEquals(HubLegRole.ACCESS_LEG, r.hubLegRole);
        }
        assertEquals(2, accessStats.droppedByTopK, "hub_0 and hub_2 cut by top-K");
        assertEquals(0, accessStats.unroutableRuralLeg);
        assertEquals(0, accessStats.unroutableUrbanLeg);
        assertEquals(0, accessStats.temporalInfeasible);

        ExpansionDropStats contStats = new ExpansionDropStats();
        List<DrtRequest> continuation = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.URBAN, METRO, network, router, BUFFER,
                /* maxHubWaitSeconds */ 0.0, /* hubSyncTwoSided */ false,
                /* hubSyncMaxAdvanceSeconds */ 0.0, /* hubTopK */ 3, contStats);

        assertEquals(List.of("hub_1", "hub_3", "hub_4"),
                continuation.stream().map(r -> r.hubId).toList(),
                "same 3 survivors regardless of fleetSide/role — ranking is physical, not role-based");
        for (DrtRequest r : continuation) {
            assertEquals(HubLegRole.CONTINUATION_LEG, r.hubLegRole);
        }
        assertEquals(2, contStats.droppedByTopK);
    }

    @Test
    void droppedByTopKHubs_produceTopkDroppedDetourRows() {
        Network network = fiveHubNetwork();
        List<HubSetLoader.Hub> hubs = fiveHubs();
        LegRouter router = perHubRouter(hubs, network, HUB_SUMS);
        DrtRequest c = TestRequestBuilder.connectingFixture(null);

        ExpansionDropStats stats = new ExpansionDropStats();
        DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.RURAL, METRO, network, router, BUFFER,
                0.0, false, 0.0, /* hubTopK */ 3, stats);

        List<HubDetour> topkDropped = stats.detours.stream()
                .filter(d -> "topk_dropped".equals(d.reason())).toList();
        assertEquals(2, topkDropped.size(), "one topk_dropped row per cut hub");
        assertEquals(java.util.Set.of("hub_0", "hub_2"),
                topkDropped.stream().map(HubDetour::hubId).collect(java.util.stream.Collectors.toSet()));
        for (HubDetour d : topkDropped) {
            assertTrue(!d.kept(), "topk_dropped rows are not kept");
        }

        List<HubDetour> kept = stats.detours.stream().filter(HubDetour::kept).toList();
        assertEquals(3, kept.size(), "the 3 survivors each have a kept row");
        assertEquals(java.util.Set.of("hub_1", "hub_3", "hub_4"),
                kept.stream().map(HubDetour::hubId).collect(java.util.stream.Collectors.toSet()));
    }

    // -------------------------------------------------------------------------
    // hubTopK = 0: unlimited — all 5 hubs emitted, byte-identical to the
    // pre-D-W5 overload that has no hubTopK parameter at all.
    // -------------------------------------------------------------------------

    @Test
    void hubTopKZero_emitsAllFiveHubs_inHubListOrder_byteIdenticalToLegacyOverload() {
        Network network = fiveHubNetwork();
        List<HubSetLoader.Hub> hubs = fiveHubs();
        LegRouter router = perHubRouter(hubs, network, HUB_SUMS);
        DrtRequest c = TestRequestBuilder.connectingFixture(null);

        ExpansionDropStats statsWithTopK = new ExpansionDropStats();
        List<DrtRequest> withExplicitZero = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.RURAL, METRO, network, router, BUFFER,
                0.0, false, 0.0, /* hubTopK */ 0, statsWithTopK);

        ExpansionDropStats statsLegacy = new ExpansionDropStats();
        // Pre-D-W5 overload: no hubTopK parameter, forwards internally with hubTopK=0.
        List<DrtRequest> legacy = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.RURAL, METRO, network, router, BUFFER,
                0.0, false, 0.0, statsLegacy);

        assertEquals(List.of("hub_0", "hub_1", "hub_2", "hub_3", "hub_4"),
                withExplicitZero.stream().map(r -> r.hubId).toList(),
                "hubTopK=0: all 5 hubs, original hub-list order");
        assertEquals(
                legacy.stream().map(r -> r.hubId).toList(),
                withExplicitZero.stream().map(r -> r.hubId).toList(),
                "hubId sequence identical to the legacy (no hubTopK arg) overload");
        assertEquals(
                legacy.stream().map(r -> r.requestTime).toList(),
                withExplicitZero.stream().map(r -> r.requestTime).toList());
        assertEquals(
                legacy.stream().map(r -> r.directTravelTime).toList(),
                withExplicitZero.stream().map(r -> r.directTravelTime).toList());
        assertEquals(0, statsWithTopK.droppedByTopK, "no cut when hubTopK<=0");
        assertEquals(0, statsLegacy.droppedByTopK);
        assertEquals(5, statsWithTopK.kept);
        assertEquals(statsLegacy.kept, statsWithTopK.kept);
    }

    // -------------------------------------------------------------------------
    // A hub that fails to route is excluded from ranking entirely and counted
    // under the existing unroutable counter, never folded into droppedByTopK.
    // -------------------------------------------------------------------------

    @Test
    void unroutableHub_excludedFromRanking_countedSeparatelyFromTopKDrop() {
        Network network = fiveHubNetwork();
        List<HubSetLoader.Hub> hubs = fiveHubs();
        // hub_2 always fails to route (both directions); the other 4 keep their
        // HUB_SUMS times. With hubTopK=3 over the 4 ROUTABLE hubs, ranks ascending
        // are hub_3(150), hub_1(200), hub_4(400), hub_0(1000) -> top-3 survivors
        // {hub_1, hub_3, hub_4}; hub_0 is the one dropped by the top-K cut.
        Map<String, Double> sumsWithOneFailure = new HashMap<>(HUB_SUMS);
        sumsWithOneFailure.put("hub_2", null);
        LegRouter router = perHubRouter(hubs, network, sumsWithOneFailure);
        DrtRequest c = TestRequestBuilder.connectingFixture(null);

        ExpansionDropStats stats = new ExpansionDropStats();
        List<DrtRequest> access = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.RURAL, METRO, network, router, BUFFER,
                0.0, false, 0.0, /* hubTopK */ 3, stats);

        assertEquals(List.of("hub_1", "hub_3", "hub_4"),
                access.stream().map(r -> r.hubId).toList());
        assertEquals(1, stats.unroutableRuralLeg,
                "hub_2's routing failure is the pre-existing unroutable counter");
        assertEquals(0, stats.unroutableUrbanLeg);
        assertEquals(1, stats.droppedByTopK,
                "hub_0 cut by top-K, DISJOINT from hub_2's routing failure");
        assertEquals(3, stats.kept);

        long unroutableRows = stats.detours.stream()
                .filter(d -> "unroutable_rural_leg".equals(d.reason())).count();
        long topkRows = stats.detours.stream()
                .filter(d -> "topk_dropped".equals(d.reason())).count();
        assertEquals(1, unroutableRows);
        assertEquals(1, topkRows);
    }

    // -------------------------------------------------------------------------
    // Helpers — five well-separated hub links (so getNearestLink resolves each
    // hub unambiguously) + a router that looks up leg times per hub link.
    // -------------------------------------------------------------------------

    private static List<HubSetLoader.Hub> fiveHubs() {
        return List.of(
                new HubSetLoader.Hub("hub_0", new Coord(1_000.0, 5_000.0)),
                new HubSetLoader.Hub("hub_1", new Coord(3_000.0, 5_000.0)),
                new HubSetLoader.Hub("hub_2", new Coord(5_000.0, 5_000.0)),
                new HubSetLoader.Hub("hub_3", new Coord(7_000.0, 5_000.0)),
                new HubSetLoader.Hub("hub_4", new Coord(9_000.0, 5_000.0)));
    }

    /** One short link per hub, 2000 m apart, so {@code getNearestLink} never confuses them. */
    private static Network fiveHubNetwork() {
        Network net = NetworkUtils.createNetwork();
        NetworkFactory nf = net.getFactory();
        double[] hubX = {1_000.0, 3_000.0, 5_000.0, 7_000.0, 9_000.0};
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
     * Routes TO a hub link with that hub's rural (first) leg time, FROM a hub link
     * with that hub's urban (second) leg time (round-trip sum = the map value); a
     * {@code null} map value makes that hub's link always fail to route (both
     * directions), simulating an unroutable hub.
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
                Double sum = sumByLink.get(to);
                if (sum == null) return null; // simulated routing failure
                double ruralT = sum / 2.0;
                return new double[] {ruralT, ruralT * 10.0};
            }
            if (sumByLink.containsKey(from)) {
                Double sum = sumByLink.get(from);
                if (sum == null) return null; // simulated routing failure
                double urbanT = sum / 2.0;
                return new double[] {urbanT, urbanT * 10.0};
            }
            throw new IllegalStateException(
                    "router stub got unexpected leg from=" + from + " to=" + to);
        };
    }
}
