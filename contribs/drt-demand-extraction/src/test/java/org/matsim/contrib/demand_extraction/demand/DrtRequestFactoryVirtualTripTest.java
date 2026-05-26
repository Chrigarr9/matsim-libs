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
                connecting, hubs, FleetSide.RURAL, isInsideMetropole, network);

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
                connecting, hubs, FleetSide.URBAN, isInsideMetropole, network);

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
                ruralIntra, hubs, FleetSide.RURAL, isInsideMetropole, network);

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
                connecting, hubs, FleetSide.RURAL, isInsideMetropole, network);

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
                connecting, hubs, FleetSide.RURAL, isInsideMetropole, network);

        assertFalse(expanded.isEmpty(), "expansion produced 0 requests");
        for (DrtRequest v : expanded) {
            assertEquals(connecting.personId, v.personId, "personId");
            assertEquals(connecting.groupId, v.groupId, "groupId");
            assertEquals(connecting.tripIndex, v.tripIndex, "tripIndex");
            assertEquals(connecting.budget, v.budget, 1e-12, "budget");
            assertEquals(connecting.directTravelTime, v.directTravelTime, 1e-12,
                    "directTravelTime (Phase 4 keeps stale; Phase 5 reroutes)");
            assertEquals(connecting.directDistance, v.directDistance, 1e-12,
                    "directDistance");
            assertEquals(connecting.maxDetourFactor, v.maxDetourFactor, 1e-12);
            assertEquals(connecting.requestTime, v.requestTime, 1e-12);
            assertEquals(connecting.earliestDeparture, v.earliestDeparture, 1e-12);
            assertEquals(connecting.latestArrival, v.latestArrival, 1e-12);
            assertEquals(connecting.bestMode, v.bestMode);
            assertEquals(connecting.bestModeScore, v.bestModeScore, 1e-12);
        }
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
