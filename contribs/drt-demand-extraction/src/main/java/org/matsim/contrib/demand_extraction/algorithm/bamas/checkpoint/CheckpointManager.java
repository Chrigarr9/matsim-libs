package org.matsim.contrib.demand_extraction.algorithm.bamas.checkpoint;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.TreeMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.contrib.demand_extraction.algorithm.bamas.ride.RideLayer;
import org.matsim.contrib.demand_extraction.algorithm.bamas.ride.RideLayerIO;

/**
 * Per-degree checkpoint writer for the BAMAS extraction (Plan A3).
 *
 * <p>On the streaming D2D path each completed degree leaves a {@link RideLayer} layer; this
 * manager persists those layers (plus the pre-prune pair universe, from which the shareability
 * graph + degree-2 survivors are rebuilt — review addendum F6) so a crashed week-long exact
 * 100% run resumes byte-identically from the last completed degree.
 *
 * <h3>Files (in {@code checkpointDir})</h3>
 * <ul>
 *   <li>{@code pair_stubs_preprune.bin} — the pre-prune degree-2 pair universe ({@link RideLayer},
 *       carries the {@code positionsFlat} copy-identity column). Written once at loop entry.</li>
 *   <li>{@code degree_<d>.stubs.bin} — each completed extension degree's survivor layer.</li>
 *   <li>{@code manifest.txt} — fingerprint + highest completed degree + per-degree row/generated
 *       counts. Written LAST after each barrier (and atomically), so a crash mid-write leaves the
 *       prior manifest authoritative; any orphaned {@code degree_<d>.bin} ahead of the manifest is
 *       simply ignored and recomputed on resume.</li>
 * </ul>
 *
 * <p>Every write is atomic (temp file + atomic rename). The manifest is the source of truth for
 * "highest completed degree"; stub files ahead of it are stale.
 *
 * <p>The connection-cache journal (Plan A3 Task 5) is a sibling file written by
 * {@code MatsimNetworkCache}; it is part of the same checkpoint contract but lives outside this
 * class.
 */
public final class CheckpointManager {

	private static final Logger log = LogManager.getLogger(CheckpointManager.class);

	static final String MANIFEST = "manifest.txt";
	static final String PAIR_ROWS = "pair_stubs_preprune.bin";
	/** Sentinel highest-degree value meaning "only the base (pair) checkpoint exists". */
	static final int BASE_ONLY_DEGREE = 2;

	private final Path dir;
	private final String fingerprint;
	/** Forkable-knob-free hash (Plan B2), persisted alongside {@link #fingerprint} for fork resume. */
	private final String baseFingerprint;

	// In-memory manifest state, rewritten atomically after every barrier.
	private boolean baseWritten = false;
	private int highestDegree = 0;
	private final TreeMap<Integer, long[]> perDegree = new TreeMap<>(); // d -> {rows, generated}

	public CheckpointManager(Path dir, String fingerprint, String baseFingerprint) {
		this.dir = dir;
		this.fingerprint = fingerprint;
		this.baseFingerprint = baseFingerprint;
	}

	/** Create the checkpoint directory if needed. */
	public void init() {
		try {
			Files.createDirectories(dir);
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot create checkpoint dir " + dir, e);
		}
	}

	/**
	 * Persist the pre-prune pair universe (review addendum F6) and mark the base checkpoint done.
	 * Called once at degree-loop entry on the streaming pair-stub path.
	 */
	public void writeBase(RideLayer allPairsPreprune) {
		writeRideFile(PAIR_ROWS, allPairsPreprune);
		baseWritten = true;
		if (highestDegree < BASE_ONLY_DEGREE) {
			highestDegree = BASE_ONLY_DEGREE;
		}
		writeManifest();
		log.info("[checkpoint] wrote base pair universe ({} rows) to {}/{}",
				allPairsPreprune.size(), dir, PAIR_ROWS);
	}

	/**
	 * Persist one completed extension degree's survivor layer and advance the manifest.
	 *
	 * @param outputDegree   the degree of the rides in {@code layer} (3, 4, ...)
	 * @param layer          the post-prune survivor {@link RideLayer} for that degree
	 * @param generatedCount the number of rides GENERATED at that degree (pre-prune) — restores
	 *                       {@code nextRideIndex} exactly on resume (reserved index space, not the
	 *                       surviving count)
	 */
	public void writeDegree(int outputDegree, RideLayer layer, int generatedCount) {
		writeRideFile("degree_" + outputDegree + ".stubs.bin", layer);
		perDegree.put(outputDegree, new long[] {layer.size(), generatedCount});
		if (outputDegree > highestDegree) {
			highestDegree = outputDegree;
		}
		writeManifest();
		log.info("[checkpoint] wrote degree-{} layer ({} rows, {} generated) — manifest highest={}",
				outputDegree, layer.size(), generatedCount, highestDegree);
	}

	// ------------------------------------------------------------------
	// Resume (read) side — Plan A3 Task 4.
	// ------------------------------------------------------------------

	/** Immutable parsed view of {@code manifest.txt} (Plan A3 Task 4 resume). */
	public static final class Manifest {
		public final String fingerprint;
		/** Forkable-knob-free hash (Plan B2) — the comparison key for fork resume below minDegree. */
		public final String baseFingerprint;
		public final boolean baseWritten;
		public final int highestDegree;
		/** outputDegree -> {rows, generated}; keys are 3..highestDegree (base degree 2 has none). */
		public final TreeMap<Integer, long[]> perDegree;

		Manifest(String fingerprint, String baseFingerprint, boolean baseWritten, int highestDegree,
				TreeMap<Integer, long[]> perDegree) {
			this.fingerprint = fingerprint;
			this.baseFingerprint = baseFingerprint;
			this.baseWritten = baseWritten;
			this.highestDegree = highestDegree;
			this.perDegree = perDegree;
		}

		/** Rides GENERATED (pre-prune) at output degree {@code d}, or 0 if absent. */
		public long generatedFor(int d) {
			long[] v = perDegree.get(d);
			return v == null ? 0L : v[1];
		}
	}

	/** True if a {@code manifest.txt} exists in the checkpoint dir (a resume candidate). */
	public boolean hasManifest() {
		return Files.exists(dir.resolve(MANIFEST));
	}

	/**
	 * Parse {@code manifest.txt}. A manifest is only ever written by {@link #writeBase} (first) or
	 * {@link #writeDegree}, so a well-formed manifest always has {@code base=1}; a manifest without
	 * it is treated as corrupt (refuse, don't silently drift — same posture as the fingerprint and
	 * journal refusals).
	 */
	public Manifest readManifest() {
		Path target = dir.resolve(MANIFEST);
		String fp = null;
		String baseFp = null;
		boolean base = false;
		int highest = 0;
		TreeMap<Integer, long[]> perDeg = new TreeMap<>();
		try {
			List<String> lines = Files.readAllLines(target, StandardCharsets.UTF_8);
			for (String line : lines) {
				if (line.isEmpty() || line.charAt(0) == '#') {
					continue;
				}
				int eq = line.indexOf('=');
				if (eq < 0) {
					continue;
				}
				String key = line.substring(0, eq);
				String val = line.substring(eq + 1);
				if (key.equals("fingerprint")) {
					fp = val;
				} else if (key.equals("baseFingerprint")) {
					baseFp = val;
				} else if (key.equals("base")) {
					base = val.equals("1");
				} else if (key.equals("highestDegree")) {
					highest = Integer.parseInt(val.trim());
				} else if (key.startsWith("degree.")) {
					// degree.<d>.rows / degree.<d>.generated
					int secondDot = key.indexOf('.', "degree.".length());
					int d = Integer.parseInt(key.substring("degree.".length(), secondDot));
					String field = key.substring(secondDot + 1);
					long[] slot = perDeg.computeIfAbsent(d, k -> new long[2]);
					if (field.equals("rows")) {
						slot[0] = Long.parseLong(val.trim());
					} else if (field.equals("generated")) {
						slot[1] = Long.parseLong(val.trim());
					}
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot read checkpoint manifest " + target, e);
		}
		if (fp == null || !base) {
			throw new IllegalStateException("Corrupt checkpoint manifest " + target
					+ " (missing fingerprint or base flag) — delete the checkpoint dir and rerun.");
		}
		if (baseFp == null) {
			// A pre-Plan-B2 (v1) manifest lacks baseFingerprint=. The schema changed (manifest v2);
			// refuse rather than silently mis-read — a stale checkpoint can no longer guarantee
			// bit-identical resume.
			throw new IllegalStateException("Stale checkpoint manifest " + target
					+ " (no baseFingerprint — written by an older manifest schema, pre-v"
					+ RunFingerprint.CHECKPOINT_VERSION + ") — delete the checkpoint dir and rerun.");
		}
		return new Manifest(fp, baseFp, base, highest, perDeg);
	}

	/**
	 * Adopt a previously-written manifest as this manager's in-memory state, so that a resume that
	 * continues the degree loop appends new {@link #writeDegree} entries on top of the loaded ones
	 * (rather than starting a fresh manifest that would drop the completed degrees).
	 */
	public void adoptManifest(Manifest m) {
		this.baseWritten = m.baseWritten;
		this.highestDegree = m.highestDegree;
		this.perDegree.clear();
		for (var e : m.perDegree.entrySet()) {
			this.perDegree.put(e.getKey(), e.getValue().clone());
		}
	}

	// ------------------------------------------------------------------
	// Plan A3 Task 6 — journal integrity gate.
	// ------------------------------------------------------------------

	/**
	 * Number of connection-cache journal barriers a journal must contain to cover the completed
	 * degrees recorded in this manifest: one base-barrier (drained before {@link #writeBase}) plus
	 * one per completed extension degree (drained before each {@link #writeDegree}). A clean journal
	 * always has exactly this many committed barriers (each is fsync'd before its manifest update),
	 * so a journal with fewer has been truncated/corrupted below the high-water mark.
	 *
	 * <p><b>Journal compaction does not disturb this arithmetic</b> (2026-09-01). A compacted
	 * journal physically holds one BARRIER but reports the full logical count, because its
	 * COMPACTION record carries the barriers it folded away — see the compaction section of
	 * {@code ConnectionCacheJournal}. Without that record, bounding the journal would have made
	 * every later resume look truncated and refuse.
	 */
	public int expectedJournalBarriers() {
		return (baseWritten ? 1 : 0) + perDegree.size();
	}

	/**
	 * Refuse the resume if the journal does not cover every completed degree (truncated/corrupted
	 * below the last barrier's high-water mark). A torn tail BEYOND the last committed barrier is
	 * fine (those records belong to an unfinished degree the manifest never recorded); fewer
	 * committed barriers than {@link #expectedJournalBarriers()} is a defect.
	 */
	public void requireJournalCoversCompletedDegrees(int committedBarriers) {
		int expected = expectedJournalBarriers();
		if (committedBarriers < expected) {
			throw new IllegalStateException("Connection-cache journal in " + dir
					+ " covers only " + committedBarriers + " checkpoint barrier(s) but the manifest"
					+ " records " + expected + " completed (highestDegree=" + highestDegree + ") — the"
					+ " journal is truncated/damaged below the last barrier; resume cannot guarantee"
					+ " bit-identical output. Delete the checkpoint dir and rerun.");
		}
	}

	/** Read back the pre-prune pair universe persisted by {@link #writeBase}. */
	public RideLayer readBase() {
		return readRideFile(PAIR_ROWS);
	}

	/** Read back the survivor layer for one completed extension degree. */
	public RideLayer readDegree(int outputDegree) {
		return readRideFile("degree_" + outputDegree + ".stubs.bin");
	}

	private RideLayer readRideFile(String name) {
		Path target = dir.resolve(name);
		try (InputStream in = Files.newInputStream(target)) {
			return RideLayerIO.read(in);
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot read checkpoint stub file " + target
					+ " — checkpoint incomplete/corrupt; delete the checkpoint dir and rerun.", e);
		}
	}

	// ------------------------------------------------------------------

	private void writeRideFile(String name, RideLayer sc) {
		Path target = dir.resolve(name);
		Path tmp = dir.resolve(name + ".tmp");
		try (OutputStream out = Files.newOutputStream(tmp)) {
			RideLayerIO.write(sc, out);
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot write checkpoint stub file " + target, e);
		}
		atomicRename(tmp, target);
	}

	private void writeManifest() {
		Path target = dir.resolve(MANIFEST);
		Path tmp = dir.resolve(MANIFEST + ".tmp");
		try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
			w.write("# BAMAS checkpoint manifest v" + RunFingerprint.CHECKPOINT_VERSION);
			w.newLine();
			w.write("fingerprint=" + fingerprint);
			w.newLine();
			w.write("baseFingerprint=" + baseFingerprint);
			w.newLine();
			w.write("base=" + (baseWritten ? 1 : 0));
			w.newLine();
			w.write("highestDegree=" + highestDegree);
			w.newLine();
			for (var e : perDegree.entrySet()) {
				w.write("degree." + e.getKey() + ".rows=" + e.getValue()[0]);
				w.newLine();
				w.write("degree." + e.getKey() + ".generated=" + e.getValue()[1]);
				w.newLine();
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot write checkpoint manifest " + target, e);
		}
		atomicRename(tmp, target);
	}

	private static void atomicRename(Path tmp, Path target) {
		try {
			Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException atomicFailed) {
			// Some filesystems reject ATOMIC_MOVE across the same dir rarely; fall back to a plain
			// replace (still a single rename syscall on common platforms).
			try {
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				throw new UncheckedIOException("Cannot rename checkpoint file " + tmp + " -> " + target, e);
			}
		}
	}
}
