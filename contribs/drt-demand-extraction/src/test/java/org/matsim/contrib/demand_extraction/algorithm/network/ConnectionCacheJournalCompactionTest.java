package org.matsim.contrib.demand_extraction.algorithm.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.contrib.demand_extraction.algorithm.bamas.checkpoint.CheckpointManager;
import org.matsim.contrib.demand_extraction.algorithm.bamas.ride.RideLayer;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;

/**
 * Journal COMPACTION (2026-09-01) — the fix for the 83.5 GB {@code cache.journal} that filled C:
 * and killed the 100% extraction at degree 14 on 2026-08-31.
 *
 * <p>Every barrier snapshots the whole live cache, so an append-only journal grows as
 * {@code barriers x cacheSize}. Compaction rewrites the file as one snapshot instead of appending
 * one more. These tests pin the four properties that make that safe:
 *
 * <ol>
 *   <li><b>Replay equality</b> — a compacted journal reconstructs the same cache map as the
 *       append-only one it replaces, at both the record level and through a real
 *       {@link MatsimNetworkCache}.</li>
 *   <li><b>Barrier accounting</b> — the COMPACTION record preserves the logical barrier count, so
 *       {@link CheckpointManager#requireJournalCoversCompletedDegrees} still accepts a resume whose
 *       manifest records N completed degrees.</li>
 *   <li><b>Threshold 0 never compacts</b> — the pre-2026-09-01 append-only behaviour is reachable.</li>
 *   <li><b>Torn-compaction safety</b> — a crash mid-compaction leaves a valid journal (the old one
 *       or the new one, never neither), and the stale temp is cleaned up on the next open.</li>
 * </ol>
 */
class ConnectionCacheJournalCompactionTest {

    private static final int TIME_BIN_SIZE = 900;
    private static final double DEPARTURE_TIME = 8 * 3600.0;

    // =====================================================================
    // 1. Replay equality
    // =====================================================================

    /**
     * Record-level equality: journal three barriers append-only, then journal the SAME three
     * barriers with the third compacted, and assert both files replay to identical maps.
     *
     * <p>Each barrier is a full snapshot (that is what {@code snapshotToJournal} writes), so the
     * third barrier's payload is the state both files must end at.
     */
    @Test
    void compactedJournalReplaysToTheSameMapAsTheAppendOnlyOne(@TempDir Path tmp) throws IOException {
        List<List<ConnectionCacheJournal.Segment>> segBarriers = List.of(
                List.of(seg("a", "b", 0, 100.0, 1000.0, 1.0),
                        seg("a", "c", 1, Double.POSITIVE_INFINITY, 0.0, -1.0)),
                List.of(seg("a", "b", 0, 100.0, 1000.0, 1.0),
                        seg("a", "c", 1, Double.POSITIVE_INFINITY, 0.0, -1.0),
                        seg("b", "d", 2, 60.5, 900.25, 1.5)),
                List.of(seg("a", "b", 0, 100.0, 1000.0, 1.0),
                        seg("a", "c", 1, Double.POSITIVE_INFINITY, 0.0, -1.0),
                        seg("b", "d", 2, 60.5, 900.25, 1.5),
                        seg("d", "e", 3, 12.0, 45.0, 0.125)));
        List<List<ConnectionCacheJournal.Sssp>> ssspBarriers = List.of(
                List.of(sssp("a", 0)),
                List.of(sssp("a", 0), sssp("b", 2)),
                List.of(sssp("a", 0), sssp("b", 2), sssp("d", 3)));

        Path appendOnly = tmp.resolve("append.journal");
        try (var w = ConnectionCacheJournal.Writer.openForAppend(appendOnly)) {
            for (int i = 0; i < 3; i++) {
                w.appendBarrier(segBarriers.get(i), ssspBarriers.get(i));
            }
        }

        Path compacted = tmp.resolve("compacted.journal");
        try (var w = ConnectionCacheJournal.Writer.openForAppend(compacted)) {
            w.appendBarrier(segBarriers.get(0), ssspBarriers.get(0));
            w.appendBarrier(segBarriers.get(1), ssspBarriers.get(1));
            w.compactWithBarrier(segBarriers.get(2), ssspBarriers.get(2));
        }

        assertEquals(replaySegments(appendOnly), replaySegments(compacted),
                "compacted journal must replay to the same segment map");
        assertEquals(replaySssp(appendOnly), replaySssp(compacted),
                "compacted journal must replay to the same sssp key set");

        // The point of the exercise: the compacted file is strictly smaller.
        assertTrue(Files.size(compacted) < Files.size(appendOnly),
                "compaction must shrink the journal (was " + Files.size(compacted)
                        + " vs " + Files.size(appendOnly) + ")");

        // And it can still be appended to afterwards.
        try (var w = ConnectionCacheJournal.Writer.openForAppend(compacted)) {
            w.appendBarrier(List.of(seg("e", "f", 4, 7.0, 8.0, 9.0)), List.of());
        }
        assertEquals(4, ConnectionCacheJournal.read(compacted).committedBarrierCount(),
                "an appended barrier must add to the folded count, not restart it");
        assertTrue(replaySegments(compacted).containsKey("e|f|4"));
    }

    /**
     * End-to-end through a real {@link MatsimNetworkCache}: route into a cache, journal it with
     * interleaved barriers where the last one compacts, then bulk-load a fresh cache from the
     * compacted journal and assert it holds bit-identical values for every routed key.
     */
    @Test
    @SuppressWarnings("unchecked")
    void compactedJournalRestoresTheSameLiveCache(@TempDir Path tmp) throws IOException {
        Network network = buildGridNetwork(5, 5, 200.0, 15.0);
        List<Id<Link>> linkIds = new ArrayList<>(network.getLinks().keySet());
        linkIds.sort(Comparator.comparing(Id::toString));

        var tt = new FreeSpeedTravelTime();
        var td = new OnlyTimeDependentTravelDisutility(tt);
        MatsimNetworkCache original =
                MatsimNetworkCacheTestFixture.createWithRouting(network, tt, td, TIME_BIN_SIZE);

        Id<Link> origin = linkIds.get(0);
        Id<Link> dest1 = linkIds.get(linkIds.size() / 2);
        Id<Link> dest2 = linkIds.get(linkIds.size() - 1);
        assertTrue(original.getSegment(origin, dest1, DEPARTURE_TIME).isReachable());
        assertTrue(original.getSegment(origin, dest2, DEPARTURE_TIME).isReachable());
        original.batchPrecompute(linkIds.get(linkIds.size() / 4), DEPARTURE_TIME,
                new Id[] {dest1, dest2}, 600.0);

        Path journal = tmp.resolve("cache.journal");
        try (var w = ConnectionCacheJournal.Writer.openForAppend(journal)) {
            // Barrier 1: plain append (threshold not reached).
            original.snapshotToJournal(w, Long.MAX_VALUE);
            // More routing, then barrier 2 with a threshold of 1 byte -> always compacts.
            original.batchPrecompute(linkIds.get(3 * linkIds.size() / 4), DEPARTURE_TIME,
                    new Id[] {dest1, linkIds.get(2)}, 600.0);
            original.snapshotToJournal(w, 1L);
            assertEquals(2, w.committedBarriers(),
                    "a compaction barrier counts exactly like the append it replaced");
        }

        MatsimNetworkCache restored =
                MatsimNetworkCacheTestFixture.createWithRouting(network, tt, td, TIME_BIN_SIZE);
        assertEquals(2, restored.bulkLoadStreamingFromJournal(journal));

        // Every segment the original holds must be present with bit-identical values.
        ConnectionCacheJournal.Writer probe = null;
        Path probeFile = tmp.resolve("probe.journal");
        try {
            probe = ConnectionCacheJournal.Writer.openForAppend(probeFile);
            restored.snapshotToJournal(probe, 0L);
        } finally {
            if (probe != null) probe.close();
        }
        assertEquals(replaySegments(journal), replaySegments(probeFile),
                "cache restored from a compacted journal must re-snapshot to the same segments");
        assertEquals(replaySssp(journal), replaySssp(probeFile),
                "cache restored from a compacted journal must re-snapshot to the same sssp keys");
    }

    // =====================================================================
    // 2. Barrier accounting across compaction
    // =====================================================================

    /**
     * A manifest recording base + two extension degrees expects three journal barriers. After the
     * third barrier compacts, the journal physically holds ONE barrier byte — but must still report
     * three, or {@link CheckpointManager#requireJournalCoversCompletedDegrees} would refuse every
     * resume from that point on.
     */
    @Test
    void compactedJournalStillCoversAllCompletedDegrees(@TempDir Path dir) throws IOException {
        CheckpointManager mgr = new CheckpointManager(dir, "fp-compaction", "fp-compaction-base");
        mgr.init();
        Path journal = dir.resolve("cache.journal");

        try (var w = ConnectionCacheJournal.Writer.openForAppend(journal)) {
            // Base barrier, then the manifest records base done (production order).
            w.appendBarrier(List.of(seg("a", "b", 0, 1.0, 2.0, 3.0)), List.of(sssp("a", 0)));
            RideLayer pairs = new RideLayer(2);
            pairs.addRow(new int[] {1, 2}, 0x1L, 0x2L, 100, 50, (byte) 0, new int[] {0, 1});
            mgr.writeBase(pairs);

            // Degree 3.
            w.appendBarrier(List.of(seg("a", "b", 0, 1.0, 2.0, 3.0),
                    seg("b", "c", 1, 4.0, 5.0, 6.0)), List.of(sssp("a", 0)));
            RideLayer d3 = new RideLayer(3);
            d3.addRow(new int[] {1, 2, 3}, 0x123L, 0x321L, 200, 90, (byte) 0);
            mgr.writeDegree(3, d3, 7);

            // Degree 4 — this barrier COMPACTS.
            w.compactWithBarrier(List.of(seg("a", "b", 0, 1.0, 2.0, 3.0),
                    seg("b", "c", 1, 4.0, 5.0, 6.0),
                    seg("c", "d", 2, 7.0, 8.0, 9.0)), List.of(sssp("a", 0), sssp("c", 2)));
            RideLayer d4 = new RideLayer(4);
            d4.addRow(new int[] {1, 2, 3, 4}, 0x1234L, 0x4321L, 300, 120, (byte) 1);
            mgr.writeDegree(4, d4, 11);
        }

        assertEquals(3, mgr.expectedJournalBarriers());

        // Physically one barrier byte, logically three.
        assertEquals(1, countBarrierBytes(journal), "compaction folds the physical barriers away");
        ConnectionCacheJournal.Contents c = ConnectionCacheJournal.read(journal);
        assertEquals(3, c.committedBarrierCount(), "the COMPACTION record must restore the count");

        // The gate accepts the resume, via read() and via the streaming loader alike.
        mgr.requireJournalCoversCompletedDegrees(c.committedBarrierCount());
        int streamed = ConnectionCacheJournal.streamCommitted(journal, s -> { }, s -> { });
        assertEquals(3, streamed, "streamCommitted must count folded barriers too");
        mgr.requireJournalCoversCompletedDegrees(streamed);

        // Reopening seeds the writer's own counter from the same accounting.
        try (var w = ConnectionCacheJournal.Writer.openForAppend(journal)) {
            assertEquals(3, w.committedBarriers());
        }
    }

    /** A COMPACTION record anywhere but the first position is corruption, not a shorter journal. */
    @Test
    void compactionRecordAwayFromTheHeaderIsRejected(@TempDir Path tmp) throws IOException {
        Path journal = tmp.resolve("cache.journal");
        try (var w = ConnectionCacheJournal.Writer.openForAppend(journal)) {
            w.appendBarrier(List.of(seg("a", "b", 0, 1.0, 2.0, 3.0)), List.of());
        }
        // Append a bare COMPACTION(99) after the committed barrier.
        try (RandomAccessFile raf = new RandomAccessFile(journal.toFile(), "rw")) {
            raf.seek(raf.length());
            raf.writeByte(5); // TAG_COMPACTION
            raf.writeInt(99);
            raf.writeByte(4); // TAG_BARRIER — would otherwise bank the 99
        }
        assertThrows(IOException.class, () -> ConnectionCacheJournal.read(journal));
    }

    // =====================================================================
    // 3. Threshold 0 never compacts
    // =====================================================================

    @Test
    @SuppressWarnings("unchecked")
    void thresholdZeroNeverCompacts(@TempDir Path tmp) throws IOException {
        Network network = buildGridNetwork(4, 4, 200.0, 15.0);
        List<Id<Link>> linkIds = new ArrayList<>(network.getLinks().keySet());
        linkIds.sort(Comparator.comparing(Id::toString));
        var tt = new FreeSpeedTravelTime();
        var td = new OnlyTimeDependentTravelDisutility(tt);
        MatsimNetworkCache cache =
                MatsimNetworkCacheTestFixture.createWithRouting(network, tt, td, TIME_BIN_SIZE);
        cache.getSegment(linkIds.get(0), linkIds.get(linkIds.size() - 1), DEPARTURE_TIME);

        Path journal = tmp.resolve("cache.journal");
        long afterFirst;
        try (var w = ConnectionCacheJournal.Writer.openForAppend(journal)) {
            cache.snapshotToJournal(w, 0L);
            afterFirst = w.sizeBytes();
            // Three more barriers, each far above any plausible size — still no compaction.
            cache.snapshotToJournal(w, 0L);
            cache.snapshotToJournal(w, 0L);
            cache.snapshotToJournal(w, 0L);
            assertEquals(4, w.committedBarriers());
        }

        assertEquals(4, countBarrierBytes(journal),
                "threshold 0 must leave every barrier physically present");
        assertEquals(0, countCompactionBytes(journal), "threshold 0 must write no COMPACTION record");
        assertTrue(Files.size(journal) > afterFirst,
                "append-only growth is the documented threshold-0 behaviour");
        assertEquals(4, ConnectionCacheJournal.read(journal).committedBarrierCount());

        // The 1-arg overload is the same thing (used by the older round-trip tests).
        Path legacy = tmp.resolve("legacy.journal");
        try (var w = ConnectionCacheJournal.Writer.openForAppend(legacy)) {
            cache.snapshotToJournal(w);
            cache.snapshotToJournal(w);
        }
        assertEquals(0, countCompactionBytes(legacy));
        assertEquals(2, countBarrierBytes(legacy));
    }

    // =====================================================================
    // 4. Torn-compaction safety
    // =====================================================================

    /**
     * A crash BEFORE the rename leaves the old journal authoritative and a stale temp on disk. The
     * old journal must still read, and the next open must clear the temp — never adopt it.
     */
    @Test
    void tornCompactionBeforeRenameLeavesTheOldJournalValid(@TempDir Path tmp) throws IOException {
        Path journal = tmp.resolve("cache.journal");
        try (var w = ConnectionCacheJournal.Writer.openForAppend(journal)) {
            w.appendBarrier(List.of(seg("a", "b", 0, 1.0, 2.0, 3.0)), List.of(sssp("a", 0)));
            w.appendBarrier(List.of(seg("a", "b", 0, 1.0, 2.0, 3.0),
                    seg("b", "c", 1, 4.0, 5.0, 6.0)), List.of(sssp("a", 0)));
        }
        byte[] before = Files.readAllBytes(journal);

        // Simulate the crash: a half-written temp exists, the rename never happened.
        Path tmpFile = ConnectionCacheJournal.compactTmpPath(journal);
        Files.write(tmpFile, new byte[] {0x43, 0x4A, 0x52, 0x4C, 0, 0, 0, 2, 5, 0, 0});

        // The old journal is untouched and fully readable.
        assertArrayEquals(before, Files.readAllBytes(journal));
        ConnectionCacheJournal.Contents c = ConnectionCacheJournal.read(journal);
        assertEquals(2, c.committedBarrierCount());
        // read() returns every committed record in write order (1 + 2 across the two snapshots);
        // the two distinct keys are what a replay folds them into.
        assertEquals(3, c.segments().size());
        assertEquals(2, replaySegments(journal).size());

        // Reopening clears the stale temp and keeps the journal's own accounting.
        try (var w = ConnectionCacheJournal.Writer.openForAppend(journal)) {
            assertEquals(2, w.committedBarriers());
        }
        assertFalse(Files.exists(tmpFile), "a stale .compact.tmp must be deleted on reopen");
        assertEquals(2, ConnectionCacheJournal.read(journal).committedBarrierCount());
    }

    /**
     * A crash AFTER the rename leaves the compacted journal authoritative: complete, fsync'd, and
     * carrying the full logical barrier count. Nothing is lost by the missing manifest update — the
     * gate only refuses a journal with FEWER barriers than the manifest.
     */
    @Test
    void tornCompactionAfterRenameLeavesTheNewJournalValid(@TempDir Path tmp) throws IOException {
        Path journal = tmp.resolve("cache.journal");
        var writer = ConnectionCacheJournal.Writer.openForAppend(journal);
        writer.appendBarrier(List.of(seg("a", "b", 0, 1.0, 2.0, 3.0)), List.of());
        writer.appendBarrier(List.of(seg("a", "b", 0, 1.0, 2.0, 3.0),
                seg("b", "c", 1, 4.0, 5.0, 6.0)), List.of());
        writer.compactWithBarrier(List.of(seg("a", "b", 0, 1.0, 2.0, 3.0),
                seg("b", "c", 1, 4.0, 5.0, 6.0),
                seg("c", "d", 2, 7.0, 8.0, 9.0)), List.of(sssp("c", 2)));
        // Simulate the process dying right here: no close, no manifest update.
        writer.close();

        assertFalse(Files.exists(ConnectionCacheJournal.compactTmpPath(journal)),
                "a successful compaction leaves no temp behind");
        ConnectionCacheJournal.Contents c = ConnectionCacheJournal.read(journal);
        assertEquals(3, c.committedBarrierCount());
        assertEquals(3, c.segments().size());
        assertEquals(1, c.ssspKeys().size());

        // A manifest that only got as far as 2 degrees still resumes (more barriers is fine).
        CheckpointManager mgr = new CheckpointManager(tmp, "fp", "fp-base");
        mgr.init();
        RideLayer pairs = new RideLayer(2);
        pairs.addRow(new int[] {1, 2}, 0x1L, 0x2L, 100, 50, (byte) 0, new int[] {0, 1});
        mgr.writeBase(pairs);
        RideLayer d3 = new RideLayer(3);
        d3.addRow(new int[] {1, 2, 3}, 0x123L, 0x321L, 200, 90, (byte) 0);
        mgr.writeDegree(3, d3, 7);
        assertEquals(2, mgr.expectedJournalBarriers());
        mgr.requireJournalCoversCompletedDegrees(c.committedBarrierCount());
    }

    /** A v1 journal predates COMPACTION and must be refused loudly, not silently mis-read. */
    @Test
    void version1JournalIsRefused(@TempDir Path tmp) throws IOException {
        Path journal = tmp.resolve("v1.journal");
        Files.write(journal, new byte[] {0x43, 0x4A, 0x52, 0x4C, 0, 0, 0, 1});
        IOException e = assertThrows(IOException.class, () -> ConnectionCacheJournal.read(journal));
        assertTrue(e.getMessage().contains("version 1"), e.getMessage());
        assertThrows(IOException.class, () -> ConnectionCacheJournal.Writer.openForAppend(journal));
        assertNotEquals(1, ConnectionCacheJournal.VERSION);
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private static ConnectionCacheJournal.Segment seg(String from, String to, int bin,
            double tt, double dist, double u) {
        return new ConnectionCacheJournal.Segment(from, to, bin, tt, dist, u);
    }

    private static ConnectionCacheJournal.Sssp sssp(String from, int bin) {
        return new ConnectionCacheJournal.Sssp(from, bin);
    }

    /** Replay a journal the way the cache does: idempotent keyed puts, last write wins. */
    private static Map<String, ConnectionCacheJournal.Segment> replaySegments(Path journal)
            throws IOException {
        Map<String, ConnectionCacheJournal.Segment> map = new HashMap<>();
        for (ConnectionCacheJournal.Segment s : ConnectionCacheJournal.read(journal).segments()) {
            map.put(s.fromLink() + "|" + s.toLink() + "|" + s.bin(), s);
        }
        return map;
    }

    private static Map<String, ConnectionCacheJournal.Sssp> replaySssp(Path journal)
            throws IOException {
        Map<String, ConnectionCacheJournal.Sssp> map = new LinkedHashMap<>();
        for (ConnectionCacheJournal.Sssp s : ConnectionCacheJournal.read(journal).ssspKeys()) {
            map.put(s.fromLink() + "|" + s.bin(), s);
        }
        return map;
    }

    /**
     * Count physical BARRIER bytes by walking the record stream (a byte scan would false-positive
     * inside doubles). This is what compaction folds away and the COMPACTION record restores.
     */
    private static int countBarrierBytes(Path journal) throws IOException {
        return countTag(journal, (byte) 4);
    }

    private static int countCompactionBytes(Path journal) throws IOException {
        return countTag(journal, (byte) 5);
    }

    private static int countTag(Path journal, byte wanted) throws IOException {
        byte[] all = Files.readAllBytes(journal);
        java.io.DataInputStream in = new java.io.DataInputStream(
                new java.io.ByteArrayInputStream(all, 8, all.length - 8));
        int count = 0;
        while (in.available() > 0) {
            byte tag = in.readByte();
            if (tag == wanted) count++;
            switch (tag) {
                case 1 -> { // DICT
                    in.readInt();
                    in.skipBytes(in.readUnsignedShort());
                }
                case 2 -> in.skipBytes(36); // SEGMENT
                case 3 -> in.skipBytes(8);  // SSSP
                case 4 -> { }               // BARRIER
                case 5 -> in.readInt();     // COMPACTION
                default -> throw new IOException("unexpected tag " + tag);
            }
        }
        return count;
    }

    private static void assertArrayEquals(byte[] expected, byte[] actual) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual);
    }

    /** Same grid fixture as {@code ConnectionCacheJournalCacheRoundTripTest}. */
    private static Network buildGridNetwork(int cols, int rows, double spacing, double freespeed) {
        Network network = NetworkUtils.createNetwork();
        NetworkFactory f = network.getFactory();
        Node[][] nodes = new Node[cols][rows];
        for (int x = 0; x < cols; x++) {
            for (int y = 0; y < rows; y++) {
                Node n = f.createNode(Id.createNodeId("n_" + x + "_" + y),
                        new Coord(x * spacing, y * spacing));
                nodes[x][y] = n;
                network.addNode(n);
            }
        }
        for (int x = 0; x < cols; x++) {
            for (int y = 0; y < rows; y++) {
                if (x + 1 < cols) {
                    addLink(network, f, nodes[x][y], nodes[x + 1][y], spacing, freespeed);
                    addLink(network, f, nodes[x + 1][y], nodes[x][y], spacing, freespeed);
                }
                if (y + 1 < rows) {
                    addLink(network, f, nodes[x][y], nodes[x][y + 1], spacing, freespeed);
                    addLink(network, f, nodes[x][y + 1], nodes[x][y], spacing, freespeed);
                }
            }
        }
        return network;
    }

    private static void addLink(Network network, NetworkFactory f, Node from, Node to,
            double length, double freespeed) {
        Link l = f.createLink(Id.createLinkId(from.getId() + "->" + to.getId()), from, to);
        l.setLength(length);
        l.setFreespeed(freespeed);
        l.setCapacity(1800);
        l.setNumberOfLanes(1);
        l.setAllowedModes(java.util.Set.of(TransportMode.car));
        network.addLink(l);
    }
}
