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
import java.nio.file.StandardCopyOption;
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
 *     byte  tag           (DICT=1, SEGMENT=2, SSSP=3, BARRIER=4, COMPACTION=5)
 *     -- DICT       : int id, UTF-8 string (2-byte length + bytes)
 *     -- SEGMENT    : int fromId, int toId, int bin, double tt, double dist, double utility
 *     -- SSSP       : int fromId, int bin
 *     -- BARRIER    : (no payload — marks a durable high-water mark)
 *     -- COMPACTION : int priorBarriers — see below. Legal ONLY as the FIRST record
 *                     (file offset 8, immediately after the header); anywhere else it is
 *                     rejected as corruption.
 * </pre>
 *
 * <p>All doubles are written raw via {@link DataOutputStream#writeDouble} (bit-identical
 * round-trip, no rounding). The BARRIER record is fsync'd before returning from
 * {@link Writer#appendBarrier}. {@link #read} returns only data up to and including the
 * last BARRIER; {@link Writer#openForAppend} truncates any torn tail beyond the last BARRIER.
 *
 * <h3>Compaction (2026-09-01) — why COMPACTION exists</h3>
 *
 * <p>Every barrier writes a FULL snapshot of the live cache (see
 * {@code MatsimNetworkCache.snapshotToJournal}), so an append-only journal costs
 * {@code barriers x liveCacheSize} on disk while carrying only {@code liveCacheSize} of
 * information. On 2026-08-31 that grew to 83.5 GB at degree 14 of the 100% run, filled the
 * disk and killed the extraction. {@link Writer#compactWithBarrier} rewrites the journal as
 * a single fresh snapshot instead of appending one: the new file holds a COMPACTION record,
 * a fresh dictionary, one SEGMENT per live cache entry, one SSSP per completed key, and one
 * terminal BARRIER. Replaying it reconstructs the same cache map as replaying the original,
 * because every SEGMENT/SSSP replay is an idempotent put keyed on (from, to, bin) and the
 * snapshot carries the post-fold value of every key.
 *
 * <p><b>The integrity gate stays coherent.</b>
 * {@code CheckpointManager.requireJournalCoversCompletedDegrees} compares committed barriers
 * against completed degrees, so a compaction that physically dropped {@code N-1} BARRIER
 * bytes would look like a truncated journal and refuse every later resume. The COMPACTION
 * record therefore carries {@code priorBarriers} = how many committed barriers existed before
 * the compacted snapshot, and every reader adds it to the count at the NEXT barrier. A
 * compaction performed AT a barrier (the only place it happens) writes
 * {@code COMPACTION(priorBarriers) ... BARRIER}, which counts as {@code priorBarriers + 1} —
 * exactly what a plain {@link Writer#appendBarrier} at that same point would have counted.
 * The logical barrier count is therefore invariant under compaction and no gate arithmetic
 * changes.
 *
 * <p><b>Atomicity.</b> Compaction writes {@code <journal>.compact.tmp}, fsyncs it, and
 * atomically renames it over the journal — the same posture as the checkpoint manifest
 * writes. A crash at any point leaves either the old journal or the new one, never neither
 * and never a half-file: before the rename the old journal is untouched and still valid;
 * after it the new one is complete and fsync'd. A stale {@code .compact.tmp} from a crash is
 * deleted by {@link Writer#openForAppend} on the next open.
 *
 * <p>This class is purely a leaf serializer with no MATSim dependencies — it knows only
 * Strings, ints, and doubles. The caller passes link identities as their {@code toString()}
 * representations.
 */
public final class ConnectionCacheJournal {

    /** Magic number "CJRL" — guards against reading an unrelated/corrupt file. */
    static final int MAGIC = 0x434A524C;
    /**
     * Format version — bumped on any layout change; read refuses a mismatch.
     * <p>v2 (2026-09-01): added the COMPACTION record. A v1 journal has no way to express
     * "these barriers were folded away", so it is refused rather than migrated — the only
     * large v1 journal in existence (the 83.5 GB one) was deleted when it filled the disk.
     */
    static final int VERSION = 2;

    /** Suffix of the temp file a compaction writes before its atomic rename. */
    static final String COMPACT_TMP_SUFFIX = ".compact.tmp";

    /** File offset of the first record — just past MAGIC + VERSION. */
    private static final long HEADER_BYTES = 8L;

    // Record tags — 1-byte discriminator written before each record payload.
    private static final byte TAG_DICT       = 1;
    private static final byte TAG_SEGMENT    = 2;
    private static final byte TAG_SSSP       = 3;
    private static final byte TAG_BARRIER    = 4;
    private static final byte TAG_COMPACTION = 5;

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

        /** The journal path — retained so {@link #compactWithBarrier} can rewrite and reopen it. */
        private final Path journalFile;

        // Non-final: a compaction swaps the whole open-file state in place, so the Writer object
        // identity survives (callers hold this reference in shutdown guards and resume records).
        private FileChannel channel;         // for fsync
        private DataOutputStream out;
        private Map<String, Integer> dict;   // link string → compact id
        private int nextId;

        /**
         * Committed BARRIER count of the file as it stands, INCLUDING barriers folded away by a
         * COMPACTION record. Seeded from the open-time scan and advanced by every barrier, so it
         * is the number {@code CheckpointManager.requireJournalCoversCompletedDegrees} compares
         * against — see the compaction section of the class javadoc.
         */
        private int committedBarriers;

        private boolean closed;

        private Writer(Path journalFile, FileChannel channel, OutputStream raw,
                       Map<String, Integer> dict, int nextId, int committedBarriers) {
            this.journalFile = journalFile;
            this.channel = channel;
            this.out = new DataOutputStream(new BufferedOutputStream(raw));
            this.dict = dict;
            this.nextId = nextId;
            this.committedBarriers = committedBarriers;
        }

        /**
         * Open an existing journal for continued append, or create a new one.
         *
         * <p>If the file exists: load the existing link dictionary, validate the committed
         * (up-to-last-barrier) region, and TRUNCATE any incomplete tail written after the
         * last committed barrier. Throws {@link IOException} if the committed region is
         * corrupt (bad magic/version, or a torn record BEFORE the last barrier marker).
         *
         * <p>A stale {@code .compact.tmp} left by a crash mid-compaction is deleted here. It is
         * never the authoritative file: the rename is what promotes a compaction, so a surviving
         * temp means the compaction did not complete and the journal it was derived from is the
         * one being opened.
         */
        public static Writer openForAppend(Path journalFile) throws IOException {
            boolean isNew = !Files.exists(journalFile);
            Map<String, Integer> dict = new HashMap<>();
            int nextId = 0;
            int committedBarriers = 0;
            // Default keep-point = header only (8 bytes = MAGIC + VERSION); overwritten below
            // for an existing file that has at least one committed BARRIER.
            long truncateTo = HEADER_BYTES;

            Files.deleteIfExists(compactTmpPath(journalFile));

            if (!isNew) {
                // Pass 1: scan committed region to rebuild dict + find the truncation point.
                ScanResult scan = scanCommittedRegion(journalFile);
                dict = scan.dict;
                nextId = scan.nextId;
                committedBarriers = scan.committedBarriers;
                // No BARRIER yet ⇒ keep only the header; else truncate to the last barrier.
                truncateTo = scan.lastBarrierOffset == -1 ? HEADER_BYTES : scan.lastBarrierOffset;
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
                    writeHeader(channel);
                    channel.force(true);
                } else if (channel.size() > truncateTo) {
                    channel.truncate(truncateTo); // drop any torn tail beyond the last barrier
                }
                channel.position(channel.size()); // append at end
                OutputStream raw = java.nio.channels.Channels.newOutputStream(channel);
                return new Writer(journalFile, channel, raw, dict, nextId, committedBarriers);
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
            writeRecords(out, dict, newSegments, newSsspKeys, this::ensureDict);

            // BARRIER marker — high-water mark.
            out.writeByte(TAG_BARRIER);
            out.flush();
            channel.force(false); // fsync data (not metadata) for durability
            committedBarriers++;
        }

        /**
         * Compaction barrier: REPLACE the journal with a single fresh snapshot that ends in this
         * barrier, instead of appending this barrier to an ever-growing file.
         *
         * <p>Semantically indistinguishable from {@link #appendBarrier} to every reader — same
         * replayed cache map (each record is an idempotent keyed put and the snapshot carries the
         * folded value of every key), and the same committed-barrier count, because the emitted
         * COMPACTION record carries the barriers folded away. Only the on-disk size differs: the
         * new file costs one snapshot instead of one per barrier ever taken.
         *
         * <p>Caller passes the SAME collections it would pass to {@link #appendBarrier} — i.e. a
         * full snapshot of the live cache, which is what every barrier already writes.
         *
         * <p>Atomic: writes {@code <journal>.compact.tmp}, fsyncs, renames over the journal, then
         * reopens it for append. If anything throws before the rename the old journal is untouched
         * and still open for append; if the rename itself fails the temp is removed.
         */
        public void compactWithBarrier(Collection<Segment> segments,
                                       Collection<Sssp> ssspKeys) throws IOException {
            if (closed) {
                throw new IOException("ConnectionCacheJournal.Writer already closed — cannot compact");
            }
            int priorBarriers = committedBarriers;
            Path tmp = compactTmpPath(journalFile);

            // 1. Build the replacement file in full before touching the live journal.
            Files.deleteIfExists(tmp);
            Map<String, Integer> freshDict = new HashMap<>();
            int[] freshNextId = {0};
            try (FileChannel tmpChannel = FileChannel.open(tmp, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, StandardOpenOption.READ)) {
                writeHeader(tmpChannel);
                DataOutputStream tmpOut = new DataOutputStream(new BufferedOutputStream(
                        java.nio.channels.Channels.newOutputStream(tmpChannel)));
                tmpOut.writeByte(TAG_COMPACTION);
                tmpOut.writeInt(priorBarriers);
                DictWriter ensureFresh = link -> {
                    if (!freshDict.containsKey(link)) {
                        int id = freshNextId[0]++;
                        freshDict.put(link, id);
                        tmpOut.writeByte(TAG_DICT);
                        tmpOut.writeInt(id);
                        writeUtf8(tmpOut, link);
                    }
                };
                writeRecords(tmpOut, freshDict, segments, ssspKeys, ensureFresh);
                tmpOut.writeByte(TAG_BARRIER);
                tmpOut.flush();
                tmpChannel.force(true); // full fsync: the rename below makes this the only copy
            } catch (IOException e) {
                Files.deleteIfExists(tmp);
                throw e;
            }

            // 2. Release the live journal's handle. Windows refuses a rename onto an open file,
            //    and the old bytes are superseded anyway.
            try {
                out.flush();
            } finally {
                channel.close();
            }

            // 3. Promote the replacement. This single rename is the commit point: before it the
            //    old journal is authoritative, after it the new one is.
            try {
                Files.move(tmp, journalFile, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicFailed) {
                Files.move(tmp, journalFile, StandardCopyOption.REPLACE_EXISTING);
            }

            // 4. Reopen for append, adopting the fresh dictionary written above.
            FileChannel reopened = FileChannel.open(journalFile,
                    StandardOpenOption.WRITE, StandardOpenOption.READ);
            try {
                reopened.position(reopened.size());
                this.channel = reopened;
                this.out = new DataOutputStream(new BufferedOutputStream(
                        java.nio.channels.Channels.newOutputStream(reopened)));
                this.dict = freshDict;
                this.nextId = freshNextId[0];
                this.committedBarriers = priorBarriers + 1;
            } catch (IOException e) {
                reopened.close();
                throw e;
            }
        }

        /** Current on-disk size of the journal, in bytes — the compaction trigger's input. */
        public long sizeBytes() throws IOException {
            return channel.size();
        }

        /**
         * Committed barriers this journal covers, folded barriers included. Equals what
         * {@link #read} would report for the same file.
         */
        public int committedBarriers() {
            return committedBarriers;
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
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

    /** Emits a DICT record for a link string the target file has not seen yet. */
    @FunctionalInterface
    private interface DictWriter {
        void ensure(String link) throws IOException;
    }

    /**
     * Write the dict + SEGMENT + SSSP records for one barrier's payload. Shared by the append and
     * the compaction paths so both emit byte-identical record streams for the same input — the
     * property that makes a compacted journal replay to the same cache map.
     */
    private static void writeRecords(DataOutputStream out, Map<String, Integer> dict,
                                     Collection<Segment> segments, Collection<Sssp> ssspKeys,
                                     DictWriter ensureDict) throws IOException {
        for (Segment seg : segments) {
            ensureDict.ensure(seg.fromLink());
            ensureDict.ensure(seg.toLink());
        }
        for (Sssp sssp : ssspKeys) {
            ensureDict.ensure(sssp.fromLink());
        }

        for (Segment seg : segments) {
            out.writeByte(TAG_SEGMENT);
            out.writeInt(dict.get(seg.fromLink()));
            out.writeInt(dict.get(seg.toLink()));
            out.writeInt(seg.bin());
            out.writeDouble(seg.tt());
            out.writeDouble(seg.dist());
            out.writeDouble(seg.utility());
        }
        for (Sssp sssp : ssspKeys) {
            out.writeByte(TAG_SSSP);
            out.writeInt(dict.get(sssp.fromLink()));
            out.writeInt(sssp.bin());
        }
    }

    /** The temp file a compaction of {@code journalFile} writes before its atomic rename. */
    static Path compactTmpPath(Path journalFile) {
        return journalFile.resolveSibling(journalFile.getFileName() + COMPACT_TMP_SUFFIX);
    }

    private static void writeHeader(FileChannel channel) throws IOException {
        java.nio.ByteBuffer hdr = java.nio.ByteBuffer.allocate((int) HEADER_BYTES);
        hdr.putInt(MAGIC);
        hdr.putInt(VERSION);
        hdr.flip();
        while (hdr.hasRemaining()) {
            channel.write(hdr);
        }
    }

    // =========================================================================
    // Contents (read result)
    // =========================================================================

    /** Immutable snapshot of all committed data in the journal. */
    public static final class Contents {
        private final List<Segment> segments;
        private final List<Sssp> ssspKeys;
        private final int committedBarrierCount;

        Contents(List<Segment> segments, List<Sssp> ssspKeys, int committedBarrierCount) {
            this.segments = List.copyOf(segments);
            this.ssspKeys = List.copyOf(ssspKeys);
            this.committedBarrierCount = committedBarrierCount;
        }

        /** All committed segments, across all barriers, in write order. */
        public List<Segment> segments() { return segments; }

        /** All committed SSSP keys, in write order. */
        public List<Sssp> ssspKeys()    { return ssspKeys; }

        /**
         * Number of durable checkpoint drains the journal covers = complete BARRIER markers read
         * PLUS any barriers folded away by a COMPACTION record. Each barrier is fsync'd before the
         * manifest records the corresponding degree done, so a clean journal has exactly as many
         * committed barriers as the manifest implies
         * ({@code (baseWritten?1:0) + perDegree.size()}) — compacted or not.
         */
        public int committedBarrierCount() { return committedBarrierCount; }
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
            int committedBarriers = 0;
            // Barriers a leading COMPACTION record folded away. Held pending like the records
            // themselves and banked at the next BARRIER, so a compaction torn before its own
            // barrier cannot inflate the count.
            int pendingPriorBarriers = 0;
            boolean firstRecord = true;

            try {
                while (true) {
                    int tagInt = in.read(); // returns -1 at EOF (no exception)
                    if (tagInt == -1) break;
                    byte tag = (byte) tagInt;
                    boolean isFirst = firstRecord;
                    firstRecord = false;

                    switch (tag) {
                        case TAG_COMPACTION -> {
                            requireCompactionIsFirstRecord(isFirst);
                            pendingPriorBarriers = in.readInt();
                        }
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
                            committedBarriers += 1 + pendingPriorBarriers;
                            pendingPriorBarriers = 0;
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

            return new Contents(committed, committedS, committedBarriers);
        }
    }

    /**
     * Stream the committed region of the journal, invoking {@code onSegment}/{@code onSssp} for each
     * record as it is read — WITHOUT materializing the full {@link Contents} lists. For a large
     * journal (e.g. a 100%-pair-gen base barrier holding hundreds of millions of segments) this keeps
     * peak heap to just the consumer's sink (the cache being filled) instead of holding a second full
     * copy in {@code List<Segment>}/{@code List<Sssp>} — the difference between fitting and OOM when
     * warm-loading a multi-GB journal into an already-large cache.
     *
     * <p>Correctness matches {@link #read}: a first lightweight pass ({@link #scanCommittedRegion})
     * finds the offset just past the LAST complete BARRIER; this pass then re-reads from the start and
     * stops there, so every record handed to a consumer lies before that barrier (i.e. is committed)
     * and a torn tail after it is never visited. A single base barrier covers the whole pair-gen, so
     * records cannot be buffered per-barrier (that would reproduce the full-list memory cost) — they
     * are emitted immediately, which is why the offset bound (not a barrier count) is used.
     *
     * @return the number of complete BARRIER markers in the committed region (== committed barriers).
     */
    public static int streamCommitted(Path journalFile,
            java.util.function.Consumer<Segment> onSegment,
            java.util.function.Consumer<Sssp> onSssp) throws IOException {
        ScanResult scan = scanCommittedRegion(journalFile);
        final long limit = scan.lastBarrierOffset();
        if (limit < 0L) {
            return 0; // no complete barrier — nothing committed
        }
        int barriers = 0;
        int pendingPriorBarriers = 0;
        try (InputStream raw = Files.newInputStream(journalFile);
             DataInputStream in = new DataInputStream(new BufferedInputStream(raw))) {
            readHeader(in);
            long pos = HEADER_BYTES;
            Map<Integer, String> idToLink = new HashMap<>();
            while (pos < limit) {
                int tagInt = in.read();
                if (tagInt == -1) break;
                byte tag = (byte) tagInt;
                boolean isFirst = pos == HEADER_BYTES;
                pos += 1;
                switch (tag) {
                    case TAG_COMPACTION -> {
                        requireCompactionIsFirstRecord(isFirst);
                        pendingPriorBarriers = in.readInt();
                        pos += 4;
                    }
                    case TAG_DICT -> {
                        int id = in.readInt();
                        String link = readUtf8(in);
                        pos += 4 + 2 + link.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                        idToLink.put(id, link);
                    }
                    case TAG_SEGMENT -> {
                        int fromId = in.readInt();
                        int toId   = in.readInt();
                        int bin    = in.readInt();
                        double tt  = in.readDouble();
                        double dist= in.readDouble();
                        double u   = in.readDouble();
                        pos += 36;
                        onSegment.accept(new Segment(resolveLink(idToLink, fromId),
                                resolveLink(idToLink, toId), bin, tt, dist, u));
                    }
                    case TAG_SSSP -> {
                        int fromId = in.readInt();
                        int bin    = in.readInt();
                        pos += 8;
                        onSssp.accept(new Sssp(resolveLink(idToLink, fromId), bin));
                    }
                    case TAG_BARRIER -> {
                        barriers += 1 + pendingPriorBarriers;
                        pendingPriorBarriers = 0;
                    }
                    default ->
                        throw new IOException(String.format(
                                "ConnectionCacheJournal: unknown tag 0x%02X within committed region — corrupt", tag));
                }
            }
        }
        return barriers;
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /** Scan the file to find the last BARRIER offset, rebuild the dict, and count committed barriers. */
    private static ScanResult scanCommittedRegion(Path file) throws IOException {
        try (InputStream raw = Files.newInputStream(file);
             DataInputStream in = new DataInputStream(new BufferedInputStream(raw))) {

            readHeader(in); // validates magic + version; throws if corrupt
            long pos = HEADER_BYTES;
            long lastBarrierOffset = -1L;
            Map<String, Integer> dict = new HashMap<>();
            int nextId = 0;
            int committedBarriers = 0;
            int pendingPriorBarriers = 0;

            try {
                while (true) {
                    int tagInt = in.read();
                    if (tagInt == -1) break;
                    byte tag = (byte) tagInt;
                    boolean isFirst = pos == HEADER_BYTES;
                    pos += 1;

                    switch (tag) {
                        case TAG_COMPACTION -> {
                            requireCompactionIsFirstRecord(isFirst);
                            pendingPriorBarriers = in.readInt();
                            pos += 4;
                        }
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
                            committedBarriers += 1 + pendingPriorBarriers;
                            pendingPriorBarriers = 0;
                        }
                        default ->
                            throw new IOException(String.format(
                                    "ConnectionCacheJournal: unknown tag 0x%02X within committed region — corrupt", tag));
                    }
                }
            } catch (java.io.EOFException eof) {
                // Torn tail — stop; lastBarrierOffset is the truncation point.
            }

            return new ScanResult(dict, nextId, lastBarrierOffset == -1 ? -1L : lastBarrierOffset,
                    committedBarriers);
        }
    }

    private record ScanResult(Map<String, Integer> dict, int nextId, long lastBarrierOffset,
            int committedBarriers) {}

    /**
     * COMPACTION is legal only as the first record of a file. Elsewhere it would mean either a
     * mid-file rewrite this class never performs, or a stray byte inside a payload — both
     * corruption, and both would silently inflate the barrier count the integrity gate trusts.
     */
    private static void requireCompactionIsFirstRecord(boolean isFirstRecord) throws IOException {
        if (!isFirstRecord) {
            throw new IOException("ConnectionCacheJournal: COMPACTION record after the first record"
                    + " — corrupt journal (a compaction always rewrites the whole file)");
        }
    }

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
                    + " — incompatible checkpoint, rerun from scratch."
                    + " (v1 journals predate the COMPACTION record added on 2026-09-01 and are not"
                    + " migrated: delete the checkpoint dir and start a fresh extraction.)");
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
