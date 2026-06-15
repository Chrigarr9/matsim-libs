package org.matsim.contrib.demand_extraction.algorithm.network;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;

/**
 * Round-trip test: MatsimNetworkCache live-cache snapshot → ConnectionCacheJournal write →
 * read → bulkLoadFromJournal → verify bit-identical values.
 *
 * <p>The journal is derived from the cache's current contents at each barrier (snapshot), not
 * from a per-insert capture queue — so every entry live in the cache when the barrier is taken
 * is journaled. An empty cache snapshots to an empty barrier.
 */
public class ConnectionCacheJournalCacheRoundTripTest {

    /** 5x5 grid, 200m spacing, 15 m/s freespeed, 900s time bin. */
    private static final int TIME_BIN_SIZE = 900;
    private static final double DEPARTURE_TIME = 8 * 3600.0; // bin = 32
    private static final int EXPECTED_BIN = (int)(DEPARTURE_TIME / TIME_BIN_SIZE);

    // -------------------------------------------------------------------------
    // Test 1: full round-trip across two snapshot barriers
    // -------------------------------------------------------------------------
    @Test
    @SuppressWarnings("unchecked")
    void roundTrip_twoBarriers_bitIdenticalValues(@TempDir Path tmp) throws IOException {
        Network network = buildGridNetwork(5, 5, 200.0, 15.0);
        List<Id<Link>> linkIds = new ArrayList<>(network.getLinks().keySet());
        linkIds.sort(Comparator.comparing(Id::toString));

        var tt = new FreeSpeedTravelTime();
        var td = new OnlyTimeDependentTravelDisutility(tt);
        MatsimNetworkCache original = MatsimNetworkCacheTestFixture.createWithRouting(network, tt, td, TIME_BIN_SIZE);

        // --- Barrier 1: route a handful of OD pairs via getSegment ---
        assertTrue(linkIds.size() >= 6);
        Id<Link> origin1 = linkIds.get(0);
        Id<Link> dest1   = linkIds.get(linkIds.size() / 2);
        Id<Link> dest2   = linkIds.get(linkIds.size() - 1);

        TravelSegment seg1 = original.getSegment(origin1, dest1, DEPARTURE_TIME);
        TravelSegment seg2 = original.getSegment(origin1, dest2, DEPARTURE_TIME);
        assertTrue(seg1.isReachable(), "seg1 should be reachable");
        assertTrue(seg2.isReachable(), "seg2 should be reachable");

        // Also run batchPrecompute to populate ssspCompleted
        Id<Link> origin2 = linkIds.get(linkIds.size() / 4);
        Id<Link>[] batchTargets1 = new Id[] { dest1, dest2, linkIds.get(1) };
        original.batchPrecompute(origin2, DEPARTURE_TIME, batchTargets1, 600.0);
        assertTrue(MatsimNetworkCacheTestFixture.isSsspCompleted(original, origin2, EXPECTED_BIN),
                "ssspCompleted should contain origin2 after batchPrecompute");

        Path journalFile = tmp.resolve("cache.journal");
        try (ConnectionCacheJournal.Writer writer = ConnectionCacheJournal.Writer.openForAppend(journalFile)) {
            original.snapshotToJournal(writer); // barrier 1
        }

        // --- Barrier 2: route more entries ---
        // Reopen journal for append (second barrier in same session)
        Id<Link> origin3  = linkIds.get(3 * linkIds.size() / 4);
        Id<Link>[] batchTargets2 = new Id[] { dest1, linkIds.get(2) };
        original.batchPrecompute(origin3, DEPARTURE_TIME, batchTargets2, 600.0);
        assertTrue(MatsimNetworkCacheTestFixture.isSsspCompleted(original, origin3, EXPECTED_BIN),
                "ssspCompleted should contain origin3 after second batchPrecompute");

        try (ConnectionCacheJournal.Writer writer = ConnectionCacheJournal.Writer.openForAppend(journalFile)) {
            original.snapshotToJournal(writer); // barrier 2 (full live snapshot — superset of barrier 1)
        }

        // --- Read and bulk-load into a fresh cache ---
        ConnectionCacheJournal.Contents contents = ConnectionCacheJournal.read(journalFile);
        assertFalse(contents.segments().isEmpty(), "journal must contain at least some segments");
        assertFalse(contents.ssspKeys().isEmpty(), "journal must contain at least some sssp keys");

        MatsimNetworkCache reloaded = MatsimNetworkCacheTestFixture.createWithRouting(network, tt, td, TIME_BIN_SIZE);
        reloaded.bulkLoadFromJournal(contents);

        // --- Assert every segment is present with bit-identical doubles ---
        for (ConnectionCacheJournal.Segment journaledSeg : contents.segments()) {
            Id<Link> from = Id.createLinkId(journaledSeg.fromLink());
            Id<Link> to   = Id.createLinkId(journaledSeg.toLink());
            TravelSegment reloadedSeg = MatsimNetworkCacheTestFixture.peek(reloaded, from, to, journaledSeg.bin());
            assertNotNull(reloadedSeg,
                    "Reloaded cache must contain segment " + journaledSeg.fromLink() + " -> " + journaledSeg.toLink());
            assertEquals(Double.doubleToRawLongBits(journaledSeg.tt()),
                    Double.doubleToRawLongBits(reloadedSeg.getTravelTime()),
                    "tt must be bit-identical for " + journaledSeg);
            assertEquals(Double.doubleToRawLongBits(journaledSeg.dist()),
                    Double.doubleToRawLongBits(reloadedSeg.getDistance()),
                    "dist must be bit-identical for " + journaledSeg);
            assertEquals(Double.doubleToRawLongBits(journaledSeg.utility()),
                    Double.doubleToRawLongBits(reloadedSeg.getNetworkUtility()),
                    "utility must be bit-identical for " + journaledSeg);
        }

        // --- Assert every ssspCompleted key is present in the reloaded cache ---
        for (ConnectionCacheJournal.Sssp ssspEntry : contents.ssspKeys()) {
            Id<Link> from = Id.createLinkId(ssspEntry.fromLink());
            assertTrue(MatsimNetworkCacheTestFixture.isSsspCompleted(reloaded, from, ssspEntry.bin()),
                    "ssspCompleted must contain " + ssspEntry.fromLink() + " bin=" + ssspEntry.bin());
        }

        // --- Spot-check: the two explicitly routed segments are present ---
        TravelSegment r1 = MatsimNetworkCacheTestFixture.peek(reloaded, origin1, dest1, EXPECTED_BIN);
        assertNotNull(r1, "origin1->dest1 must be in reloaded cache");
        assertEquals(Double.doubleToRawLongBits(seg1.getTravelTime()),
                Double.doubleToRawLongBits(r1.getTravelTime()),
                "seg1 tt must be bit-identical after reload");
        assertEquals(Double.doubleToRawLongBits(seg1.getDistance()),
                Double.doubleToRawLongBits(r1.getDistance()),
                "seg1 dist must be bit-identical after reload");
        assertEquals(Double.doubleToRawLongBits(seg1.getNetworkUtility()),
                Double.doubleToRawLongBits(r1.getNetworkUtility()),
                "seg1 utility must be bit-identical after reload");

        TravelSegment r2 = MatsimNetworkCacheTestFixture.peek(reloaded, origin1, dest2, EXPECTED_BIN);
        assertNotNull(r2, "origin1->dest2 must be in reloaded cache");
        assertEquals(Double.doubleToRawLongBits(seg2.getTravelTime()),
                Double.doubleToRawLongBits(r2.getTravelTime()),
                "seg2 tt must be bit-identical after reload");
    }

    // -------------------------------------------------------------------------
    // Test 2: snapshot of an empty cache produces one empty barrier (no segments,
    //         no sssp keys) — the snapshot is derived from live cache contents.
    // -------------------------------------------------------------------------
    @Test
    void emptyCache_snapshotProducesEmptyContents(@TempDir Path tmp) throws IOException {
        Network network = buildGridNetwork(5, 5, 200.0, 15.0);

        var tt = new FreeSpeedTravelTime();
        var td = new OnlyTimeDependentTravelDisutility(tt);
        MatsimNetworkCache emptyCache = MatsimNetworkCacheTestFixture.createWithRouting(network, tt, td, TIME_BIN_SIZE);
        // No routing performed → the cache is empty.

        Path journalFile = tmp.resolve("empty.journal");
        try (ConnectionCacheJournal.Writer writer = ConnectionCacheJournal.Writer.openForAppend(journalFile)) {
            emptyCache.snapshotToJournal(writer);
        }

        ConnectionCacheJournal.Contents contents = ConnectionCacheJournal.read(journalFile);
        assertTrue(contents.segments().isEmpty(),
                "empty cache: no segments should be snapshotted");
        assertTrue(contents.ssspKeys().isEmpty(),
                "empty cache: no sssp keys should be snapshotted");
        assertEquals(1, contents.committedBarrierCount(),
                "snapshot still writes exactly one (empty) barrier");
    }

    // -------------------------------------------------------------------------
    // Test 3: unreachable segment round-trips correctly if routing produces one.
    //         Uses a 3-node chain where the target link is null in the network
    //         (simulated by querying a link id not in the network in a manual
    //         test-fixture cache — unreachable() has Infinity tt/dist).
    // -------------------------------------------------------------------------
    @Test
    void unreachableSegment_roundTripsCorrectly(@TempDir Path tmp) throws IOException {
        // Use the forTesting() cache (no router) to inject an unreachable segment directly,
        // then exercise the journal path by enabling journaling and forcing a capture via
        // bulkLoadFromJournal (round-trip only; capture is via putForTesting which is the
        // load/test path — we test unreachable round-trip at the journal record level).
        //
        // The specification says: capture at importCache/putForTesting is NOT required.
        // So we test the journal record round-trip by constructing the segment manually
        // and writing directly to a journal, then reading it back via bulkLoadFromJournal.

        var segUnreachable = new ConnectionCacheJournal.Segment(
                "linkUNKNOWN_FROM", "linkUNKNOWN_TO", 5,
                Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY);

        Path journalFile = tmp.resolve("unreachable.journal");
        try (ConnectionCacheJournal.Writer writer = ConnectionCacheJournal.Writer.openForAppend(journalFile)) {
            writer.appendBarrier(List.of(segUnreachable), List.of());
        }

        ConnectionCacheJournal.Contents contents = ConnectionCacheJournal.read(journalFile);
        assertEquals(1, contents.segments().size());

        var tt = new FreeSpeedTravelTime();
        var td = new OnlyTimeDependentTravelDisutility(tt);
        Network freshNet = buildGridNetwork(3, 3, 200.0, 15.0);
        MatsimNetworkCache reloaded = MatsimNetworkCacheTestFixture.createWithRouting(freshNet, tt, td, TIME_BIN_SIZE);
        reloaded.bulkLoadFromJournal(contents);

        Id<Link> from = Id.createLinkId("linkUNKNOWN_FROM");
        Id<Link> to   = Id.createLinkId("linkUNKNOWN_TO");
        TravelSegment reloadedSeg = MatsimNetworkCacheTestFixture.peek(reloaded, from, to, 5);
        assertNotNull(reloadedSeg, "unreachable segment must be present after bulkLoad");
        assertFalse(reloadedSeg.isReachable(), "reloaded segment must be unreachable");
        assertEquals(Double.doubleToRawLongBits(Double.POSITIVE_INFINITY),
                Double.doubleToRawLongBits(reloadedSeg.getTravelTime()),
                "Infinity tt must survive round-trip bit-identically");
        assertEquals(Double.doubleToRawLongBits(Double.NEGATIVE_INFINITY),
                Double.doubleToRawLongBits(reloadedSeg.getNetworkUtility()),
                "NEGATIVE_INFINITY utility must survive round-trip bit-identically");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static Network buildGridNetwork(int rows, int cols, double spacing, double freespeed) {
        Network net = NetworkUtils.createNetwork();
        NetworkFactory factory = net.getFactory();
        Node[][] nodes = new Node[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                String nodeId = r + "_" + c;
                Node node = factory.createNode(Id.createNodeId(nodeId), new Coord(c * spacing, r * spacing));
                net.addNode(node);
                nodes[r][c] = node;
            }
        }
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (c + 1 < cols) addBidirectionalLink(net, factory, nodes[r][c], nodes[r][c + 1], spacing, freespeed);
                if (r + 1 < rows) addBidirectionalLink(net, factory, nodes[r][c], nodes[r + 1][c], spacing, freespeed);
            }
        }
        return net;
    }

    private static void addBidirectionalLink(Network net, NetworkFactory factory,
            Node from, Node to, double length, double freespeed) {
        String fwd = from.getId() + "-" + to.getId();
        String rev = to.getId() + "-" + from.getId();
        Link linkFwd = factory.createLink(Id.createLinkId(fwd), from, to);
        linkFwd.setLength(length);
        linkFwd.setFreespeed(freespeed);
        linkFwd.setCapacity(1000);
        linkFwd.setNumberOfLanes(1);
        linkFwd.setAllowedModes(Set.of(TransportMode.car));
        net.addLink(linkFwd);
        Link linkRev = factory.createLink(Id.createLinkId(rev), to, from);
        linkRev.setLength(length);
        linkRev.setFreespeed(freespeed);
        linkRev.setCapacity(1000);
        linkRev.setNumberOfLanes(1);
        linkRev.setAllowedModes(Set.of(TransportMode.car));
        net.addLink(linkRev);
    }
}
