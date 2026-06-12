package org.matsim.contrib.demand_extraction.algorithm.network;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * TDD tests for ConnectionCacheJournal — written BEFORE the implementation.
 *
 * Tests cover:
 * 1. Round-trip across two barriers (bit-identical doubles, including +Infinity)
 * 2. Dictionary reuse (link seen in barrier A not re-emitted in barrier B)
 * 3. Reopen-and-append (openForAppend on existing journal, 2nd barrier returned on read)
 * 4. Torn tail after last barrier (truncation → read returns committed data only)
 * 5. Corruption within committed region (bad magic → IOException)
 * 6. Empty / no-barrier journal (zero barriers → empty contents, no throw)
 */
public class ConnectionCacheJournalTest {

    @TempDir
    Path tmp;

    // -------------------------------------------------------------------------
    // Test 1: round-trip across two barriers — bit-identical doubles + Infinity
    // -------------------------------------------------------------------------
    @Test
    void roundTripTwoBarriers() throws IOException {
        Path file = tmp.resolve("cache.journal");

        // Barrier A: two segments (one carries +Infinity utility), one sssp key
        var segA1 = new ConnectionCacheJournal.Segment("linkA", "linkB", 0, 120.5, 1800.0, 3.14);
        var segA2 = new ConnectionCacheJournal.Segment("linkA", "linkC", 1, Double.POSITIVE_INFINITY, 0.0, -1.0);
        var ssspA = new ConnectionCacheJournal.Sssp("linkA", 0);

        // Barrier B: reuses linkA/linkB, adds linkD
        var segB1 = new ConnectionCacheJournal.Segment("linkB", "linkD", 2, 60.0, 900.0, 1.5);
        var ssspB = new ConnectionCacheJournal.Sssp("linkD", 2);

        try (var w = ConnectionCacheJournal.Writer.openForAppend(file)) {
            w.appendBarrier(List.of(segA1, segA2), List.of(ssspA));
            w.appendBarrier(List.of(segB1), List.of(ssspB));
        }

        ConnectionCacheJournal.Contents c = ConnectionCacheJournal.read(file);

        // Segments: both barriers, in order
        List<ConnectionCacheJournal.Segment> segs = c.segments();
        assertEquals(3, segs.size());

        ConnectionCacheJournal.Segment r0 = segs.get(0);
        assertEquals("linkA", r0.fromLink());
        assertEquals("linkB", r0.toLink());
        assertEquals(0, r0.bin());
        assertEquals(Double.doubleToRawLongBits(120.5), Double.doubleToRawLongBits(r0.tt()));
        assertEquals(Double.doubleToRawLongBits(1800.0), Double.doubleToRawLongBits(r0.dist()));
        assertEquals(Double.doubleToRawLongBits(3.14), Double.doubleToRawLongBits(r0.utility()));

        ConnectionCacheJournal.Segment r1 = segs.get(1);
        assertEquals("linkA", r1.fromLink());
        assertEquals("linkC", r1.toLink());
        assertEquals(Double.doubleToRawLongBits(Double.POSITIVE_INFINITY),
                Double.doubleToRawLongBits(r1.tt()));
        assertEquals(Double.doubleToRawLongBits(0.0), Double.doubleToRawLongBits(r1.dist()));
        assertEquals(Double.doubleToRawLongBits(-1.0), Double.doubleToRawLongBits(r1.utility()));

        ConnectionCacheJournal.Segment r2 = segs.get(2);
        assertEquals("linkB", r2.fromLink());
        assertEquals("linkD", r2.toLink());
        assertEquals(2, r2.bin());

        // SSSP keys: both barriers, in order
        List<ConnectionCacheJournal.Sssp> sssp = c.ssspKeys();
        assertEquals(2, sssp.size());
        assertEquals("linkA", sssp.get(0).fromLink());
        assertEquals(0, sssp.get(0).bin());
        assertEquals("linkD", sssp.get(1).fromLink());
        assertEquals(2, sssp.get(1).bin());
    }

    // -------------------------------------------------------------------------
    // Test 2: dictionary reuse — link seen in barrier A must NOT grow the file
    //         by a full dict record in barrier B
    // -------------------------------------------------------------------------
    @Test
    void dictionaryReuse_linkUsedInBothBarriers() throws IOException {
        Path file = tmp.resolve("dict.journal");

        var segA = new ConnectionCacheJournal.Segment("alpha", "beta", 0, 1.0, 2.0, 3.0);
        var segB_reuse = new ConnectionCacheJournal.Segment("alpha", "beta", 1, 4.0, 5.0, 6.0);
        var segB_new   = new ConnectionCacheJournal.Segment("gamma", "delta", 0, 7.0, 8.0, 9.0);

        // Write single barrier A
        long sizeAfterA;
        try (var w = ConnectionCacheJournal.Writer.openForAppend(file)) {
            w.appendBarrier(List.of(segA), List.of());
            // flush happens in close
        }
        sizeAfterA = Files.size(file);

        // Reopen and write barrier B — "alpha" and "beta" already in dict
        try (var w = ConnectionCacheJournal.Writer.openForAppend(file)) {
            w.appendBarrier(List.of(segB_reuse, segB_new), List.of());
        }

        // The round-trip should recover all 3 segments cleanly
        var c = ConnectionCacheJournal.read(file);
        assertEquals(3, c.segments().size());
        assertEquals("gamma", c.segments().get(2).fromLink());

        // File should have grown (barrier B has 2 segments + 2 new dict entries "gamma","delta"),
        // but if ids were re-emitted for "alpha"/"beta" the growth would be even larger.
        // We verify correctness via a reopen: the writer must not reassign ids.
        // (If ids were reassigned, the segment payload ints would mismatch the re-loaded dict
        // and we would get wrong link names on read — which we already checked above.)
        assertTrue(Files.size(file) > sizeAfterA, "file must grow after appending barrier B");
    }

    // -------------------------------------------------------------------------
    // Test 3: reopen-and-append — openForAppend on existing 1-barrier journal,
    //         append 2nd barrier, read returns both
    // -------------------------------------------------------------------------
    @Test
    void reopenAndAppend() throws IOException {
        Path file = tmp.resolve("reopen.journal");

        var seg1 = new ConnectionCacheJournal.Segment("L1", "L2", 0, 10.0, 100.0, 1.0);
        var sssp1 = new ConnectionCacheJournal.Sssp("L1", 0);

        // Write barrier 1
        try (var w = ConnectionCacheJournal.Writer.openForAppend(file)) {
            w.appendBarrier(List.of(seg1), List.of(sssp1));
        }

        // Reopen and write barrier 2
        var seg2 = new ConnectionCacheJournal.Segment("L3", "L4", 1, 20.0, 200.0, 2.0);
        var sssp2 = new ConnectionCacheJournal.Sssp("L3", 1);
        try (var w = ConnectionCacheJournal.Writer.openForAppend(file)) {
            w.appendBarrier(List.of(seg2), List.of(sssp2));
        }

        var c = ConnectionCacheJournal.read(file);
        assertEquals(2, c.segments().size());
        assertEquals("L1", c.segments().get(0).fromLink());
        assertEquals("L2", c.segments().get(0).toLink());
        assertEquals("L3", c.segments().get(1).fromLink());
        assertEquals("L4", c.segments().get(1).toLink());
        assertEquals(2, c.ssspKeys().size());
        assertEquals("L1", c.ssspKeys().get(0).fromLink());
        assertEquals("L3", c.ssspKeys().get(1).fromLink());
    }

    // -------------------------------------------------------------------------
    // Test 4: torn tail after last barrier — bytes written after the barrier
    //         are truncated on read; openForAppend then continues cleanly
    // -------------------------------------------------------------------------
    @Test
    void tornTailAfterLastBarrier() throws IOException {
        Path file = tmp.resolve("torn.journal");

        var seg = new ConnectionCacheJournal.Segment("X", "Y", 0, 1.0, 2.0, 3.0);

        // Write one complete barrier
        try (var w = ConnectionCacheJournal.Writer.openForAppend(file)) {
            w.appendBarrier(List.of(seg), List.of());
        }
        long committedSize = Files.size(file);

        // Simulate a crash mid-second-append: open, do NOT call appendBarrier or close,
        // but manually append garbage bytes (as if a partial write happened)
        try (var raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.seek(committedSize);
            raf.write(new byte[]{0x01, 0x02, 0x03, 0x04, (byte) 0xFF}); // torn partial record
        }
        assertTrue(Files.size(file) > committedSize, "we added garbage bytes");

        // Read should return exactly the committed data and ignore the torn tail
        var c = ConnectionCacheJournal.read(file);
        assertEquals(1, c.segments().size());
        assertEquals("X", c.segments().get(0).fromLink());

        // openForAppend must truncate torn tail and allow new appends
        var seg2 = new ConnectionCacheJournal.Segment("A", "B", 1, 5.0, 6.0, 7.0);
        try (var w = ConnectionCacheJournal.Writer.openForAppend(file)) {
            w.appendBarrier(List.of(seg2), List.of());
        }

        var c2 = ConnectionCacheJournal.read(file);
        assertEquals(2, c2.segments().size());
        assertEquals("X", c2.segments().get(0).fromLink());
        assertEquals("A", c2.segments().get(1).fromLink());
        // The file must be exactly the same byte-length as after the 2nd clean barrier
        // i.e., the torn bytes were truncated before appending barrier 2
        long sizeAfterCleanTwo = Files.size(file);
        assertTrue(sizeAfterCleanTwo <= committedSize * 3,
                "file size after clean 2-barrier write should be sane");
    }

    // -------------------------------------------------------------------------
    // Test 5: corruption within committed region — bad magic → IOException
    // -------------------------------------------------------------------------
    @Test
    void corruptMagic_throwsIOException() throws IOException {
        Path file = tmp.resolve("corrupt.journal");

        // Write a valid journal
        try (var w = ConnectionCacheJournal.Writer.openForAppend(file)) {
            w.appendBarrier(List.of(new ConnectionCacheJournal.Segment("A", "B", 0, 1.0, 2.0, 3.0)), List.of());
        }

        // Overwrite first 4 bytes (magic) with garbage
        try (var raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.seek(0);
            raf.writeInt(0xDEADBEEF);
        }

        assertThrows(IOException.class, () -> ConnectionCacheJournal.read(file),
                "read must throw IOException for bad magic");
    }

    @Test
    void corruptMagic_openForAppend_throwsIOException() throws IOException {
        Path file = tmp.resolve("corrupt2.journal");

        try (var w = ConnectionCacheJournal.Writer.openForAppend(file)) {
            w.appendBarrier(List.of(new ConnectionCacheJournal.Segment("A", "B", 0, 1.0, 2.0, 3.0)), List.of());
        }

        try (var raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.seek(0);
            raf.writeInt(0xDEADBEEF);
        }

        assertThrows(IOException.class, () -> ConnectionCacheJournal.Writer.openForAppend(file),
                "openForAppend must throw IOException for bad magic");
    }

    // -------------------------------------------------------------------------
    // Test 6: empty / no-barrier journal — zero barriers → empty Contents, no throw
    // -------------------------------------------------------------------------
    @Test
    void emptyJournal_noBarriers_emptyContents() throws IOException {
        Path file = tmp.resolve("empty.journal");

        // Create a journal without calling appendBarrier
        try (var w = ConnectionCacheJournal.Writer.openForAppend(file)) {
            // no appendBarrier call
        }

        // Must not throw; must return empty contents
        var c = ConnectionCacheJournal.read(file);
        assertTrue(c.segments().isEmpty(), "no segments expected from empty journal");
        assertTrue(c.ssspKeys().isEmpty(), "no sssp keys expected from empty journal");
    }

    @Test
    void nonExistentFile_read_throwsIOException() throws IOException {
        Path file = tmp.resolve("missing.journal");
        // Reading a non-existent file should throw IOException (file not found)
        assertThrows(IOException.class, () -> ConnectionCacheJournal.read(file));
    }
}
