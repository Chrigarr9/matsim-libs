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
import org.matsim.contrib.demand_extraction.demand.DrtRequestFactory.LegRouter;
import org.matsim.core.network.NetworkUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests Paper-2 Extension 2 virtual-trip expansion in
 * {@link DrtRequestFactory#expandConnecting}: a single {@code connecting}
 * request fans out to |H| {@link DrtRequest} instances, one per hub, with the
 * cross-boundary endpoint replaced by the hub coordinate. {@code rural_intra}
 * and {@code urban_intra} pass through unchanged.
 *
 * <p>The expansion helper is package-private and stateless so tests can wire
 * it up without a Controler: an in-memory link-grid {@link Network}, a stub
 * "inside metropole" {@link Predicate}, and the hand-built fixture requests
 * from {@link TestRequestBuilder}.
 *
 * <p>Phase 4 Task 4.2 of the Paper-2 Extension 2 plan.
 */
public class DrtRequestFactoryVirtualTripTest {

    // -------------------------------------------------------------------------
    // RURAL fleet side: urban endpoint of each connecting request is replaced
    // by the hub coord; rural endpoint stays put.
    // -------------------------------------------------------------------------

    @Test
    void ruralFleet_connectingRequest_emitsOneCopyPerHub_withUrbanEndpointAtHub() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = threeHubs();
        // Rural -> urban: origin (0,0) is OUTSIDE the metropole; destination
        // (10_000, 0) is INSIDE. The predicate returns "true" iff x >= 5_000.
        Predicate<Coord> isInsideMetropole = c -> c.getX() >= 500.0;
        DrtRequest connecting = TestRequestBuilder.connectingFixture(null);

        List<DrtRequest> expanded = DrtRequestFactory.expandConnecting(
                connecting, hubs, FleetSide.RURAL, isInsideMetropole, network,
                fakeRouter(hubs, network), 300.0);

        assertEquals(3, expanded.size(),
                "|H|=3 hubs → 3 virtual rural copies of one connecting request");

        for (int i = 0; i < expanded.size(); i++) {
            DrtRequest v = expanded.get(i);
            HubSetLoader.Hub h = hubs.get(i);

            assertEquals(h.id(), v.hubId,
                    "virtual copy #" + i + " must carry its hub's id");
            assertEquals("connecting", v.requestTag,
                    "requestTag must stay 'connecting' on every virtual copy");

            // Urban endpoint (destination) replaced by hub coord.
            assertEquals(h.coord().getX(), v.destinationX, 1e-9,
                    "RURAL fleet: destination X must be replaced by hub coord");
            assertEquals(h.coord().getY(), v.destinationY, 1e-9,
                    "RURAL fleet: destination Y must be replaced by hub coord");

            // Rural endpoint (origin) untouched.
            assertEquals(connecting.originX, v.originX, 1e-9,
                    "RURAL fleet: origin X must be unchanged");
            assertEquals(connecting.originY, v.originY, 1e-9,
                    "RURAL fleet: origin Y must be unchanged");
        }
    }

    // -------------------------------------------------------------------------
    // URBAN fleet side mirror: rural endpoint replaced by hub coord.
    // -------------------------------------------------------------------------

    @Test
    void urbanFleet_connectingRequest_replacesRuralEndpointWithHub() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = threeHubs();
        Predicate<Coord> isInsideMetropole = c -> c.getX() >= 500.0;
        DrtRequest connecting = TestRequestBuilder.connectingFixture(null);

        List<DrtRequest> expanded = DrtRequestFactory.expandConnecting(
                connecting, hubs, FleetSide.URBAN, isInsideMetropole, network,
                fakeRouter(hubs, network), 300.0);

        assertEquals(3, expanded.size());

        for (DrtRequest v : expanded) {
            // For URBAN fleet, the rural endpoint (origin in this fixture) is
            // replaced; the urban endpoint (destination) stays put.
            assertEquals(connecting.destinationX, v.destinationX, 1e-9,
                    "URBAN fleet: destination X must be unchanged");
            assertEquals(connecting.destinationY, v.destinationY, 1e-9,
                    "URBAN fleet: destination Y must be unchanged");
        }

        // Each virtual copy's origin matches its hub.
        for (int i = 0; i < expanded.size(); i++) {
            DrtRequest v = expanded.get(i);
            HubSetLoader.Hub h = hubs.get(i);
            assertEquals(h.coord().getX(), v.originX, 1e-9);
            assertEquals(h.coord().getY(), v.originY, 1e-9);
            assertEquals(h.id(), v.hubId);
        }
    }

    // -------------------------------------------------------------------------
    // Non-connecting tags pass through 1-to-1 with hubId = null.
    // -------------------------------------------------------------------------

    @Test
    void ruralIntraRequest_returnsSelf_withNullHubId() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = threeHubs();
        Predicate<Coord> isInsideMetropole = c -> false;
        DrtRequest ruralIntra = TestRequestBuilder.ruralIntraFixture();

        List<DrtRequest> expanded = DrtRequestFactory.expandConnecting(
                ruralIntra, hubs, FleetSide.RURAL, isInsideMetropole, network,
                fakeRouter(hubs, network), 300.0);

        assertEquals(1, expanded.size(),
                "non-connecting requests are not fanned out");
        DrtRequest only = expanded.get(0);
        assertEquals("rural_intra", only.requestTag);
        assertNull(only.hubId, "rural_intra must have null hubId");
    }

    // -------------------------------------------------------------------------
    // hubId + linkId rewriting: each virtual copy must point its rewritten
    // endpoint at the nearest network link to the hub.
    // -------------------------------------------------------------------------

    @Test
    void ruralFleet_virtualCopiesHaveLinkIdsSnappedToHub() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = threeHubs();
        Predicate<Coord> isInsideMetropole = c -> c.getX() >= 500.0;
        DrtRequest connecting = TestRequestBuilder.connectingFixture(null);

        List<DrtRequest> expanded = DrtRequestFactory.expandConnecting(
                connecting, hubs, FleetSide.RURAL, isInsideMetropole, network,
                fakeRouter(hubs, network), 300.0);

        for (int i = 0; i < expanded.size(); i++) {
            DrtRequest v = expanded.get(i);
            HubSetLoader.Hub h = hubs.get(i);

            Id<Link> expected = NetworkUtils.getNearestLink(network, h.coord()).getId();
            assertEquals(expected, v.destinationLinkId,
                    "RURAL fleet: rewritten destinationLinkId must be the nearest "
                            + "link to hub coord (copy " + i + ")");

            // Origin link unchanged.
            assertEquals(connecting.originLinkId, v.originLinkId,
                    "origin link must be untouched on RURAL fleet (copy " + i + ")");
        }
    }

    // -------------------------------------------------------------------------
    // Field preservation: every non-endpoint field must round-trip identically.
    // Spec: "all other fields identical."
    // -------------------------------------------------------------------------

    @Test
    void virtualCopies_preserveAllNonEndpointFields() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = threeHubs();
        Predicate<Coord> isInsideMetropole = c -> c.getX() >= 500.0;
        DrtRequest connecting = TestRequestBuilder.connectingFixture(null);

        List<DrtRequest> expanded = DrtRequestFactory.expandConnecting(
                connecting, hubs, FleetSide.RURAL, isInsideMetropole, network,
                fakeRouter(hubs, network), 300.0);

        // NOTE: Task 8 deliberately REWRITES the temporal/direct fields on each
        // virtual copy (per-leg routed directTravelTime/directDistance, split
        // requestTime/earliestDeparture/latestArrival). Those are exercised by
        // the routed-leg / shift / deadline tests below. Here we only assert the
        // identity/budget fields that still round-trip unchanged.
        assertFalse(expanded.isEmpty(), "expansion produced 0 requests");
        for (DrtRequest v : expanded) {
            assertEquals(connecting.personId, v.personId, "personId");
            assertEquals(connecting.groupId, v.groupId, "groupId");
            assertEquals(connecting.tripIndex, v.tripIndex, "tripIndex");
            assertEquals(connecting.budget, v.budget, 1e-12, "budget");
            assertEquals(connecting.maxDetourFactor, v.maxDetourFactor, 1e-12);
            assertEquals(connecting.bestMode, v.bestMode);
            assertEquals(connecting.bestModeScore, v.bestModeScore, 1e-12);
        }
    }

    // -------------------------------------------------------------------------
    // Task 8: per-leg routed direct attrs, temporal split, leg roles.
    // -------------------------------------------------------------------------

    /** tR = 600 s / 6000 m for ANY od ending at a hub link; tU = 900 s / 9000 m
     *  for ANY od starting at a hub link. Recognisable values let assertions
     *  distinguish the two legs. */
    private static LegRouter fakeRouter(List<HubSetLoader.Hub> hubs, Network network) {
        java.util.Set<Id<Link>> hubLinks = new java.util.HashSet<>();
        for (HubSetLoader.Hub h : hubs) {
            hubLinks.add(NetworkUtils.getNearestLink(network, h.coord()).getId());
        }
        return (from, to, dep) -> hubLinks.contains(to)
                ? new double[] {600.0, 6000.0}     // rural leg O->hub
                : new double[] {900.0, 9000.0};    // urban leg hub->D
    }

    @Test
    void ruralCopy_getsOwnLegDirectAttrs_andSplitWindow() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = threeHubs();
        DrtRequest c = TestRequestBuilder.connectingFixture(null);
        double buffer = 300.0;

        List<DrtRequest> out = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.RURAL, coord -> coord.getX() >= 500.0,
                network, fakeRouter(hubs, network), buffer);

        assertFalse(out.isEmpty(), "rural copies must be feasible for the wide fixture window");
        for (DrtRequest v : out) {
            assertEquals(600.0, v.directTravelTime, 1e-9, "rural leg's OWN direct tt");
            assertEquals(6000.0, v.directDistance, 1e-9, "rural leg's OWN direct dist");
            assertEquals(c.requestTime, v.requestTime, 1e-9, "rural departure unshifted");
            assertEquals(c.latestArrival - 900.0 - buffer, v.latestArrival, 1e-9,
                    "rural latestArrival backs out urban leg + buffer");
            assertEquals(DrtRequest.HubLegRole.ACCESS_LEG, v.hubLegRole);
            assertEquals(0.0, v.transferWaitSeconds, 1e-9);
        }
    }

    @Test
    void urbanCopy_isShiftedByRuralLegPlusBuffer_andIsContinuation() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = threeHubs();
        DrtRequest c = TestRequestBuilder.connectingFixture(null);
        double buffer = 300.0;

        List<DrtRequest> out = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.URBAN, coord -> coord.getX() >= 500.0,
                network, fakeRouter(hubs, network), buffer);

        assertFalse(out.isEmpty(), "urban copies must be feasible for the wide fixture window");
        for (DrtRequest v : out) {
            assertEquals(900.0, v.directTravelTime, 1e-9, "urban leg's OWN direct tt");
            assertEquals(c.requestTime + 600.0 + buffer, v.requestTime, 1e-9,
                    "urban departure = original + rural leg + buffer");
            assertEquals(c.earliestDeparture + 600.0 + buffer, v.earliestDeparture, 1e-9);
            assertEquals(c.latestArrival, v.latestArrival, 1e-9, "full-trip deadline kept");
            assertEquals(DrtRequest.HubLegRole.CONTINUATION_LEG, v.hubLegRole);
            assertEquals(buffer, v.transferWaitSeconds, 1e-9);
        }
    }

    /**
     * Router whose returned distance encodes the NON-hub terminus link id, so a
     * leg routed to the WRONG terminus is detectable: {@code l_d} (urban
     * destination) → 9000 m, {@code l_o} (rural origin) → 6000 m. Travel time is
     * a fixed 1 s so the temporal split window is always satisfied (the distance,
     * not the time, is under test here).
     */
    private static LegRouter terminusDistanceRouter(List<HubSetLoader.Hub> hubs,
            Network network) {
        java.util.Set<Id<Link>> hubLinks = new java.util.HashSet<>();
        for (HubSetLoader.Hub h : hubs) {
            hubLinks.add(NetworkUtils.getNearestLink(network, h.coord()).getId());
        }
        return (from, to, dep) -> {
            Id<Link> terminus = hubLinks.contains(from) ? to : from;
            double dist = "l_d".equals(terminus.toString()) ? 9000.0   // urban destination
                        : "l_o".equals(terminus.toString()) ? 6000.0   // rural origin
                        : 1.0;
            return new double[] {1.0, dist};
        };
    }

    /**
     * Regression for the Paper-2 Ext-2 directDistance corruption: the URBAN
     * continuation leg must be routed {@code hub -> urban destination}, not
     * {@code hub -> rural origin}. The rural/urban terminus assignment in
     * {@code expandConnecting} must branch on {@code fleetSide} because
     * {@code replaceOrigin} carries opposite coordinate-semantics per fleet.
     * With the bug the leg routes to {@code l_o} (6000 m, the rural origin).
     */
    @Test
    void urbanContinuationLeg_isRoutedToUrbanTerminus_notRuralOrigin() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = threeHubs();
        // connectingFixture: origin (0,0)=rural=l_o, destination (1000,0)=urban=l_d.
        DrtRequest c = TestRequestBuilder.connectingFixture(null);

        List<DrtRequest> out = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.URBAN, coord -> coord.getX() >= 500.0,
                network, terminusDistanceRouter(hubs, network), 300.0);

        assertFalse(out.isEmpty(), "urban copies must be feasible for the wide fixture window");
        for (DrtRequest v : out) {
            assertEquals(9000.0, v.directDistance, 1e-9,
                    "URBAN continuation leg must be routed hub->urban destination "
                            + "(l_d=9000 m), not hub->rural origin (l_o=6000 m)");
        }
    }

    @Test
    void temporallyInfeasibleHub_isDropped() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = threeHubs();
        DrtRequest c = TestRequestBuilder.connectingFixture(null);
        LegRouter slow = (f, t, dep) -> new double[] {1e7, 1e7};

        List<DrtRequest> out = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.RURAL, coord -> coord.getX() >= 500.0,
                network, slow, 300.0);
        assertTrue(out.isEmpty(), "hubs that don't fit the time envelope are dropped");
    }

    @Test
    void dropStats_countUnroutableSeparatelyFromTemporal() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = threeHubs();
        DrtRequest c = TestRequestBuilder.connectingFixture(null);
        Predicate<Coord> metro = coord -> coord.getX() >= 500.0;

        // (a) dead router: the rural leg never routes -> all hubs charged to the
        // rural-leg unroutable bucket, none temporal, none kept.
        DrtRequestFactory.ExpansionDropStats dead = new DrtRequestFactory.ExpansionDropStats();
        DrtRequestFactory.expandConnecting(c, hubs, FleetSide.RURAL, metro, network,
                (f, t, dep) -> null, 300.0, dead);
        assertEquals(hubs.size(), dead.unroutableRuralLeg);
        assertEquals(0, dead.unroutableUrbanLeg);
        assertEquals(0, dead.temporalInfeasible);
        assertEquals(0, dead.kept);

        // (b) rural leg routes but the urban leg is unreachable -> urban-leg bucket.
        java.util.Set<Id<Link>> hubLinks = new java.util.HashSet<>();
        for (HubSetLoader.Hub h : hubs) {
            hubLinks.add(NetworkUtils.getNearestLink(network, h.coord()).getId());
        }
        DrtRequestFactory.ExpansionDropStats urbanDead = new DrtRequestFactory.ExpansionDropStats();
        DrtRequestFactory.expandConnecting(c, hubs, FleetSide.RURAL, metro, network,
                (f, t, dep) -> hubLinks.contains(t) ? new double[] {1.0, 1.0} : null,
                300.0, urbanDead);
        assertEquals(0, urbanDead.unroutableRuralLeg);
        assertEquals(hubs.size(), urbanDead.unroutableUrbanLeg);
        assertEquals(0, urbanDead.temporalInfeasible);

        // (c) both legs route but are so long the window collapses -> temporal bucket.
        DrtRequestFactory.ExpansionDropStats slow = new DrtRequestFactory.ExpansionDropStats();
        DrtRequestFactory.expandConnecting(c, hubs, FleetSide.RURAL, metro, network,
                (f, t, dep) -> new double[] {1e7, 1e7}, 300.0, slow);
        assertEquals(0, slow.unroutableRuralLeg);
        assertEquals(0, slow.unroutableUrbanLeg);
        assertEquals(hubs.size(), slow.temporalInfeasible);
        assertEquals(0, slow.kept);

        // (d) feasible router: all kept, no drops.
        DrtRequestFactory.ExpansionDropStats ok = new DrtRequestFactory.ExpansionDropStats();
        DrtRequestFactory.expandConnecting(c, hubs, FleetSide.RURAL, metro, network,
                fakeRouter(hubs, network), 300.0, ok);
        assertEquals(hubs.size(), ok.kept);
        assertEquals(0, ok.unroutableRuralLeg + ok.unroutableUrbanLeg + ok.temporalInfeasible);

        // Detour diagnostics: one row per hub attempt, regardless of outcome.
        assertEquals(hubs.size(), ok.detours.size());
        assertTrue(ok.detours.stream().allMatch(d -> d.kept() && "kept".equals(d.reason())));
        assertEquals(hubs.size(), dead.detours.size());
        assertTrue(dead.detours.stream()
                .allMatch(d -> "unroutable_rural_leg".equals(d.reason())));
        assertEquals(hubs.size(), slow.detours.size());
        assertTrue(slow.detours.stream()
                .allMatch(d -> "temporal_infeasible".equals(d.reason())));
    }

    private static LegRouter fixedRouter() {
        return (from, to, dep) -> new double[] {600.0, 5000.0};
    }

    // ---- EXT-1: reverse direction (urban origin -> rural destination) ----

    @Test
    void reverseTrip_ruralFleet_emitsContinuationLeg_shiftedLate_withOwnDirectionMetrics() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = threeHubs();
        Predicate<Coord> isInsideMetropole = c -> c.getX() >= 500.0;
        DrtRequest rev = TestRequestBuilder.connectingReverseFixture(null);

        List<DrtRequest> expanded = DrtRequestFactory.expandConnecting(
                rev, hubs, FleetSide.RURAL, isInsideMetropole, network,
                fixedRouter(), 300.0);

        assertEquals(3, expanded.size());
        for (DrtRequest v : expanded) {
            // Rural fleet serves the SECOND leg (hub -> rural destination).
            assertEquals(DrtRequest.HubLegRole.CONTINUATION_LEG, v.hubLegRole,
                    "reverse trip: rural-side copy is the continuation leg");
            // Origin replaced by hub, rural destination kept.
            assertEquals(rev.destinationX, v.destinationX, 1e-9);
            assertEquals(rev.destinationY, v.destinationY, 1e-9);
            // Anchored AFTER the urban access leg: requestTime + first(600) + buffer(300).
            assertEquals(rev.requestTime + 600.0 + 300.0, v.requestTime, 1e-9);
            // Direct metrics from the copy's own OD (hub -> rural dest), 600 s / 5000 m.
            assertEquals(600.0, v.directTravelTime, 1e-9);
            assertEquals(5000.0, v.directDistance, 1e-9);
            // EXT-3 clamp: the leg can never depart the hub before the pax arrives.
            assertTrue(v.earliestDeparture >= v.requestTime - 1e-9,
                    "legacy continuation must not depart before hub arrival + buffer");
        }
    }

    @Test
    void reverseTrip_urbanFleet_emitsAccessLeg_atOriginalDeparture_withDeadlineBackout() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = threeHubs();
        Predicate<Coord> isInsideMetropole = c -> c.getX() >= 500.0;
        DrtRequest rev = TestRequestBuilder.connectingReverseFixture(null);

        List<DrtRequest> expanded = DrtRequestFactory.expandConnecting(
                rev, hubs, FleetSide.URBAN, isInsideMetropole, network,
                fixedRouter(), 300.0);

        assertEquals(3, expanded.size());
        for (DrtRequest v : expanded) {
            // Urban fleet serves the FIRST leg (urban origin -> hub).
            assertEquals(DrtRequest.HubLegRole.ACCESS_LEG, v.hubLegRole,
                    "reverse trip: urban-side copy is the access leg");
            // Urban origin kept, destination replaced by hub.
            assertEquals(rev.originX, v.originX, 1e-9);
            assertEquals(rev.originY, v.originY, 1e-9);
            // Departs at the ORIGINAL desired time.
            assertEquals(rev.requestTime, v.requestTime, 1e-9);
            // Deadline backout: latestArrival - secondLeg(600) - buffer(300).
            assertEquals(rev.latestArrival - 600.0 - 300.0, v.latestArrival, 1e-9);
            assertEquals(600.0, v.directTravelTime, 1e-9);
        }
    }

    // ---- EXT-3: clamp also for FORWARD trips with origin flexibility ----

    @Test
    void legacyContinuation_neverDepartsBeforeHubArrivalPlusBuffer() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = threeHubs();
        Predicate<Coord> isInsideMetropole = c -> c.getX() >= 500.0;
        // Forward fixture with 600 s origin flexibility (earliestDeparture < requestTime).
        DrtRequest flex = TestRequestBuilder.connectingFixture(null).toBuilder()
                .requestTime(1000.0)
                .earliestDeparture(400.0)
                .latestArrival(4600.0)
                .build();

        List<DrtRequest> expanded = DrtRequestFactory.expandConnecting(
                flex, hubs, FleetSide.URBAN, isInsideMetropole, network,
                fixedRouter(), 300.0);

        for (DrtRequest v : expanded) {
            assertEquals(DrtRequest.HubLegRole.CONTINUATION_LEG, v.hubLegRole);
            // Old (buggy) value would be 400 + 900 = 1300 < requestTime 1900.
            assertEquals(v.requestTime, v.earliestDeparture, 1e-9,
                    "continuation earliestDeparture must clamp to hub arrival + buffer");
            assertEquals(0.0, v.getMaxNegativeDelay(), 1e-9);
        }
    }

    @Test
    void unroutableHub_isDropped() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = threeHubs();
        DrtRequest c = TestRequestBuilder.connectingFixture(null);
        LegRouter dead = (f, t, dep) -> null;

        List<DrtRequest> out = DrtRequestFactory.expandConnecting(
                c, hubs, FleetSide.RURAL, coord -> coord.getX() >= 500.0,
                network, dead, 300.0);
        assertTrue(out.isEmpty());
    }

    // -------------------------------------------------------------------------
    // Helpers — a tiny 2-node grid network so NetworkUtils.getNearestLink works
    // for any hub coordinate inside our test bounding box, and three stub hubs.
    // -------------------------------------------------------------------------

    /**
     * Builds a sparse 4-link grid covering (-1_000, -1_000) → (11_000, 11_000):
     * just enough nodes/links that {@link NetworkUtils#getNearestLink} resolves
     * to a deterministic link for each of our three hub coordinates.
     */
    private static Network buildGridNetwork() {
        Network net = NetworkUtils.createNetwork();
        NetworkFactory nf = net.getFactory();

        // Place nodes at the four corners of a wide box plus one near each hub
        // coord so the nearest-link lookup is well-defined.
        Node nw = nf.createNode(Id.createNodeId("nw"), new Coord(-1_000.0, 11_000.0));
        Node ne = nf.createNode(Id.createNodeId("ne"), new Coord(11_000.0, 11_000.0));
        Node sw = nf.createNode(Id.createNodeId("sw"), new Coord(-1_000.0, -1_000.0));
        Node se = nf.createNode(Id.createNodeId("se"), new Coord(11_000.0, -1_000.0));
        net.addNode(nw);
        net.addNode(ne);
        net.addNode(sw);
        net.addNode(se);

        // Four perimeter links — enough for nearest-link to project anywhere
        // in the box onto a unique link.
        net.addLink(nf.createLink(Id.createLinkId("n"), nw, ne));
        net.addLink(nf.createLink(Id.createLinkId("e"), ne, se));
        net.addLink(nf.createLink(Id.createLinkId("s"), se, sw));
        net.addLink(nf.createLink(Id.createLinkId("w"), sw, nw));

        assertNotNull(NetworkUtils.getNearestLink(net, new Coord(0.0, 0.0)));
        return net;
    }

    private static List<HubSetLoader.Hub> threeHubs() {
        return List.of(
                new HubSetLoader.Hub("hub_a", new Coord(5_000.0, 8_000.0)),
                new HubSetLoader.Hub("hub_b", new Coord(7_500.0, 5_000.0)),
                new HubSetLoader.Hub("hub_c", new Coord(9_000.0, 2_000.0))
        );
    }

    // ---- EXT-2: hub-sync v2 continuation co-shift ----

    @Test
    void hubSyncTwoSided_continuationVariantsCoShiftWithAccessVariants() {
        Network network = buildGridNetwork();
        List<HubSetLoader.Hub> hubs = threeHubs().subList(0, 1); // one hub is enough
        Predicate<Coord> isInsideMetropole = c -> c.getX() >= 500.0;
        // Forward fixture, generous window so all variants fit.
        DrtRequest fwd = TestRequestBuilder.connectingFixture(null).toBuilder()
                .requestTime(7200.0).earliestDeparture(7200.0)
                .latestArrival(14400.0)
                .build();
        double maxHubWait = 600.0, maxAdvance = 1200.0, buffer = 300.0;

        List<DrtRequest> access = DrtRequestFactory.expandConnecting(
                fwd, hubs, FleetSide.RURAL, isInsideMetropole, network,
                fixedRouter(), buffer, maxHubWait, true, maxAdvance, null);
        List<DrtRequest> continuation = DrtRequestFactory.expandConnecting(
                fwd, hubs, FleetSide.URBAN, isInsideMetropole, network,
                fixedRouter(), buffer, maxHubWait, true, maxAdvance, null);

        assertEquals(3, access.size(), "k = 0, 600, 1200 -> 3 access variants");
        assertEquals(3, continuation.size(), "continuation must co-shift: one variant per offset");

        for (DrtRequest a : access) {
            double hubArrival = a.requestTime + a.directTravelTime; // fixed router: +600
            boolean hasNestedContinuation = continuation.stream().anyMatch(u ->
                    Math.abs(u.requestTime - hubArrival) < 1e-9
                    && u.earliestDeparture >= hubArrival - 1e-9
                    && u.earliestDeparture <= hubArrival + maxHubWait + 1e-9);
            assertTrue(hasNestedContinuation,
                    "every access variant needs a same-offset continuation variant "
                    + "(EXT-2: offsets >= 1 step were previously unusable)");
        }
    }

    @Test
    void sanity_metropoleStubMatchesFixtureEndpoints() {
        // The connecting fixture has origin=(0,0) (outside the x>=500 stub
        // metropole) and destination=(1000,0) (inside). The ruralFleet tests
        // rely on this orientation: rural origin -> urban destination, so the
        // urban (destination) end is the one that gets rewritten to a hub.
        Predicate<Coord> p = c -> c.getX() >= 500.0;
        assertFalse(p.test(new Coord(0.0, 0.0)),
                "origin (0,0) must be outside the stub metropole");
        assertTrue(p.test(new Coord(1_000.0, 0.0)),
                "destination (1000,0) must be inside the stub metropole");
    }
}
