package org.matsim.contrib.demand_extraction.algorithm.network;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Append-only binary journal for the BAMAS routing connection cache (Plan A3 — checkpoint/resume).
 *
 * <p>Persists two kinds of records so a crashed week-long run can resume byte-identically:
 * <ul>
 *   <li><b>Segment records</b>: {@code (fromLink, toLink, bin, tt, dist, utility)} — one per
 *       cache entry populated during the run.</li>
 *   <li><b>SSSP records</b>: {@code (fromLink, bin)} — one per {@code ssspCompleted} key.</li>
 * </ul>
 *
 * <p>Link strings are dictionary-encoded (first-appearance order, compact int ids) so the
 * per-record payload is fixed-width once the dict is stable.
 *
 * <h3>On-disk format</h3>
 * <pre>
 *   int   MAGIC          (0x434A524C = "CJRL")
 *   int   VERSION        (== {@link #VERSION})
 *   then interleaved tagged records until EOF:
 *     byte  tag           (DICT=1, SEGMENT=2, SSSP=3, BARRIER=4)
 *     -- DICT    : int id, UTF-8 string (2-byte length + bytes)
 *     -- SEGMENT : int fromId, int toId, int bin, double tt, double dist, double utility
 *     -- SSSP    : int fromId, int bin
 *     -- BARRIER : (no payload — marks a durable high-water mark)
 * </pre>
 *
 * <p>All doubles are written raw via {@link DataOutputStream#writeDouble} (bit-identical
 * round-trip, no rounding). The BARRIER record is fsync'd before returning from
 * {@link Writer#appendBarrier}. {@link #read} returns only data up to and including the
 * last BARRIER; {@link Writer#openForAppend} truncates any torn tail beyond the last BARRIER.
 *
 * <p>This class is purely a leaf serializer with no MATSim dependencies — it knows only
 * Strings, ints, and doubles. The caller passes link identities as their {@code toString()}
 * representations.
 */
public final class ConnectionCacheJournal {

    /** Magic number "CJRL" — guards against reading an unrelated/corrupt file. */
    static final int MAGIC = 0x434A524C;
    /** Format version — bumped on any layout change; read refuses a mismatch. */
    static final int VERSION = 1;

    // Record tags — 1-byte discriminator written before each record payload.
    private static final byte TAG_DICT    = 1;
    private static final byte TAG_SEGMENT = 2;
    private static final byte TAG_SSSP    = 3;
    private static final byte TAG_BARRIER = 4;

    private ConnectionCacheJournal() {}

    // =========================================================================
    // Public value types (Java records)
    // =========================================================================

    /** One connection-cache entry. */
    public record Segment(String fromLink, String toLink, int bin,
                          double tt, double dist, double utility) {}

    /** One ssspCompleted key. */
    public record Sssp(String fromLink, int bin) {}

    // =========================================================================
    // Writer
    // =========================================================================

    /** Append-only writer; must be used in a try-with-resources block. */
    public static final class Writer implements AutoCloseable {

        private final FileChannel channel;   // for fsync
        private final DataOutputStream out;
        private final Map<String, Integer> dict;     // link string → compact id
        private int nextId;

        private Writer(FileChannel channel, OutputStream raw,
                       Map<String, Integer> dict, int nextId) throws IOException {
            this.channel = channel;
            this.out = new DataOutputStream(new BufferedOutputStream(raw));
            this.dict = dict;
            this.nextId = nextId;
        }

        /**
         * Open an existing journal for continued append, or create a new one.
         *
         * <p>If the file exists: load the existing link dictionary, validate the committed
         * (up-to-last-barrier) region, and TRUNCATE any incomplete tail written after the
         * last committed barrier. Throws {@link IOException} if the committed region is
         * corrupt (bad magic/version, or a torn record BEFORE the last barrier marker).
         */
        public static Writer openForAppend(Path journalFile) throws IOException {
            boolean isNew = !Files.exists(journalFile);
            Map<String, Integer> dict = new HashMap<>();
            int nextId = 0;
            // Default keep-point = header only (8 bytes = MAGIC + VERSION); overwritten below
            // for an existing file that has at least one committed BARRIER.
            long truncateTo = 8L;

            if (!isNew) {
                // Pass 1: scan committed region to rebuild dict + find the truncation point.
                ScanResult scan = scanCommittedRegion(journalFile);
                dict = scan.dict;
                nextId = scan.nextId;
                // No BARRIER yet ⇒ keep only the header; else truncate to the last barrier.
                truncateTo = scan.lastBarrierOffset == -1 ? 8L : scan.lastBarrierOffset;
            }

            // Open the channel, then guard every fallible setup step: if anything throws, close
            // the channel before propagating so a failed open in a crash-recovery retry loop does
            // not leak the OS file handle (on Windows that would block reopen with AccessDenied).
            FileChannel channel = isNew
                    ? FileChannel.open(journalFile, StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE, StandardOpenOption.READ)
                    : FileChannel.open(journalFile,
                            StandardOpenOption.WRITE, StandardOpenOption.READ);
            try {
                if (isNew) {
                    java.nio.ByteBuffer hdr = java.nio.ByteBuffer.allocate(8);
                    hdr.putInt(MAGIC);
                    hdr.putInt(VERSION);
                    hdr.flip();
                    channel.write(hdr);
                    channel.force(true);
                } else if (channel.size() > truncateTo) {
                    channel.truncate(truncateTo); // drop any torn tail beyond the last barrier
                }
                channel.position(channel.size()); // append at end
                OutputStream raw = java.nio.channels.Channels.newOutputStream(channel);
                return new Writer(channel, raw, dict, nextId);
            } catch (IOException e) {
                channel.close();
                throw e;
            }
        }

        /**
         * Append one barrier's worth of new entries, then write a BARRIER marker and fsync.
         *
         * <p>Link strings already in the dictionary are NOT re-emitted as DICT records.
         * New link strings are assigned the next sequential id and emitted before use.
         */
        public void appendBarrier(Collection<Segment> newSegments,
                                  Collection<Sssp> newSsspKeys) throws IOException {
            // Emit dict records for any new link strings, then segment/sssp records.
            for (Segment seg : newSegments) {
                ensureDict(seg.fromLink());
                ensureDict(seg.toLink());
            }
            for (Sssp sssp : newSsspKeys) {
                ensureDict(sssp.fromLink());
            }

            for (Segment seg : newSegments) {
                out.writeByte(TAG_SEGMENT);
                out.writeInt(dict.get(seg.fromLink()));
                out.writeInt(dict.get(seg.toLink()));
                out.writeInt(seg.bin());
                out.writeDouble(seg.tt());
                out.writeDouble(seg.dist());
                out.writeDouble(seg.utility());
            }
            for (Sssp sssp : newSsspKeys) {
                out.writeByte(TAG_SSSP);
                out.writeInt(dict.get(sssp.fromLink()));
                out.writeInt(sssp.bin());
            }

            // BARRIER marker — high-water mark.
            out.writeByte(TAG_BARRIER);
            out.flush();
            channel.force(false); // fsync data (not metadata) for durability
        }

        @Override
        public void close() throws IOException {
            try {
                out.flush();
            } finally {
                channel.close();
            }
        }

        // --- private helpers ---

        private void ensureDict(String link) throws IOException {
            if (!dict.containsKey(link)) {
                int id = nextId++;
                dict.put(link, id);
                out.writeByte(TAG_DICT);
                out.writeInt(id);
                writeUtf8(out, link);
            }
        }
    }

    // =========================================================================
    // Contents (read result)
    // =========================================================================

    /** Immutable snapshot of all committed data in the journal. */
    public static final class Contents {
        private final List<Segment> segments;
        private final List<Sssp> ssspKeys;

        Contents(List<Segment> segments, List<Sssp> ssspKeys) {
            this.segments = List.copyOf(segments);
            this.ssspKeys = List.copyOf(ssspKeys);
        }

        /** All committed segments, across all barriers, in write order. */
        public List<Segment> segments() { return segments; }

        /** All committed SSSP keys, in write order. */
        public List<Sssp> ssspKeys()    { return ssspKeys; }
    }

    // =========================================================================
    // read
    // =========================================================================

    /**
     * Read the journal up to and including its LAST complete BARRIER marker.
     *
     * <p>A torn tail from a crash mid-append is silently ignored: a clean crash always leaves a
     * prefix of valid records ending in a truncated (EOF) record, so the EOF is swallowed and the
     * data up to the last BARRIER is returned. A corrupt magic/version, an <b>unrecognised record
     * tag</b>, or an unknown link id throws {@link IOException} — those indicate genuine corruption
     * (not a clean truncation), and resume must refuse rather than silently load a partial cache.
     */
    public static Contents read(Path journalFile) throws IOException {
        try (InputStream raw = Files.newInputStream(journalFile);
             DataInputStream in = new DataInputStream(new BufferedInputStream(raw))) {

            readHeader(in);

            // We scan the full file, accumulating state between barriers, and
            // "commit" to result lists only when we see a BARRIER marker.
            // After the last BARRIER, we stop committing; remaining bytes are ignored.

            Map<Integer, String> idToLink = new HashMap<>();
            List<Segment> committed   = new ArrayList<>();
            List<Sssp>    committedS  = new ArrayList<>();
            List<Segment> pending     = new ArrayList<>();
            List<Sssp>    pendingS    = new ArrayList<>();

            try {
                while (true) {
                    int tagInt = in.read(); // returns -1 at EOF (no exception)
                    if (tagInt == -1) break;
                    byte tag = (byte) tagInt;

                    switch (tag) {
                        case TAG_DICT -> {
                            int id = in.readInt();
                            String link = readUtf8(in);
                            idToLink.put(id, link);
                        }
                        case TAG_SEGMENT -> {
                            int fromId = in.readInt();
                            int toId   = in.readInt();
                            int bin    = in.readInt();
                            double tt  = in.readDouble();
                            double dist= in.readDouble();
                            double u   = in.readDouble();
                            String from = resolveLink(idToLink, fromId);
                            String to   = resolveLink(idToLink, toId);
                            pending.add(new Segment(from, to, bin, tt, dist, u));
                        }
                        case TAG_SSSP -> {
                            int fromId = in.readInt();
                            int bin    = in.readInt();
                            String from = resolveLink(idToLink, fromId);
                            pendingS.add(new Sssp(from, bin));
                        }
                        case TAG_BARRIER -> {
                            // Commit pending to result.
                            committed.addAll(pending);
                            committedS.addAll(pendingS);
                            pending.clear();
                            pendingS.clear();
                        }
                        default ->
                            throw new IOException(String.format(
                                    "ConnectionCacheJournal: unknown tag 0x%02X within committed region — corrupt", tag));
                    }
                }
            } catch (java.io.EOFException eof) {
                // Torn tail: partial record after the last committed BARRIER.
                // All data up to the last BARRIER is in `committed` — return that.
                // If the tear is within the committed region (before the last BARRIER),
                // it means we hit EOF while reading a record that precedes a BARRIER we
                // already consumed, which cannot happen in this streaming scan.
                // (The outer loop commits to `committed` only on BARRIER; a tear in a
                //  pre-BARRIER record would be in `pending`, which we discard — correct.)
            }

            return new Contents(committed, committedS);
        }
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /** Scan the file to find the last BARRIER offset and rebuild the dict. */
    private static ScanResult scanCommittedRegion(Path file) throws IOException {
        try (InputStream raw = Files.newInputStream(file);
             DataInputStream in = new DataInputStream(new BufferedInputStream(raw))) {

            readHeader(in); // validates magic + version; throws if corrupt
            long pos = 8L;
            long lastBarrierOffset = -1L;
            Map<String, Integer> dict = new HashMap<>();
            int nextId = 0;

            try {
                while (true) {
                    int tagInt = in.read();
                    if (tagInt == -1) break;
                    byte tag = (byte) tagInt;
                    pos += 1;

                    switch (tag) {
                        case TAG_DICT -> {
                            int id = in.readInt(); pos += 4;
                            String link = readUtf8(in);
                            pos += 2 + link.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                            dict.put(link, id);
                            if (id >= nextId) nextId = id + 1;
                        }
                        case TAG_SEGMENT -> {
                            // 3 ints + 3 doubles = 12 + 24 = 36 bytes
                            in.readFully(new byte[36]);
                            pos += 36;
                        }
                        case TAG_SSSP -> {
                            // 2 ints = 8 bytes
                            in.readFully(new byte[8]);
                            pos += 8;
                        }
                        case TAG_BARRIER -> {
                            lastBarrierOffset = pos; // pos is just after the BARRIER byte
                        }
                        default ->
                            throw new IOException(String.format(
                                    "ConnectionCacheJournal: unknown tag 0x%02X within committed region — corrupt", tag));
                    }
                }
            } catch (java.io.EOFException eof) {
                // Torn tail — stop; lastBarrierOffset is the truncation point.
            }

            return new ScanResult(dict, nextId, lastBarrierOffset == -1 ? -1L : lastBarrierOffset);
        }
    }

    private record ScanResult(Map<String, Integer> dict, int nextId, long lastBarrierOffset) {}

    private static void readHeader(DataInputStream in) throws IOException {
        int magic = in.readInt();
        if (magic != MAGIC) {
            throw new IOException(String.format(
                    "ConnectionCacheJournal: bad magic 0x%08X (expected 0x%08X) — not a journal file or corrupt",
                    magic, MAGIC));
        }
        int version = in.readInt();
        if (version != VERSION) {
            throw new IOException("ConnectionCacheJournal: version " + version
                    + " != supported " + VERSION
                    + " — incompatible checkpoint, rerun from scratch");
        }
    }

    private static String resolveLink(Map<Integer, String> idToLink, int id) throws IOException {
        String s = idToLink.get(id);
        if (s == null) {
            throw new IOException("ConnectionCacheJournal: unknown link id " + id
                    + " — corrupt journal (dict record missing before use)");
        }
        return s;
    }

    /** Write a UTF-8 string as 2-byte length followed by UTF-8 bytes. */
    private static void writeUtf8(DataOutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > 65535) {
            throw new IOException("ConnectionCacheJournal: link string too long (" + bytes.length + " bytes)");
        }
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    /** Read a UTF-8 string as 2-byte length followed by UTF-8 bytes. */
    private static String readUtf8(DataInputStream in) throws IOException {
        int len = in.readUnsignedShort();
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }
}
