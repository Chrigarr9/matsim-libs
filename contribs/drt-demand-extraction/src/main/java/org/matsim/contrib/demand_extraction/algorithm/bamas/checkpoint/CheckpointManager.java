package org.matsim.contrib.demand_extraction.algorithm.bamas.checkpoint;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.TreeMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns;
import org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumnsIO;

/**
 * Per-degree checkpoint writer for the BAMAS extraction (Plan A3).
 *
 * <p>On the streaming D2D path each completed degree leaves a {@link StubColumns} layer; this
 * manager persists those layers (plus the pre-prune pair universe, from which the shareability
 * graph + degree-2 survivors are rebuilt — review addendum F6) so a crashed week-long exact
 * 100% run resumes byte-identically from the last completed degree.
 *
 * <h3>Files (in {@code checkpointDir})</h3>
 * <ul>
 *   <li>{@code pair_stubs_preprune.bin} — the pre-prune degree-2 pair universe ({@link StubColumns},
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
	static final String PAIR_STUBS = "pair_stubs_preprune.bin";
	/** Sentinel highest-degree value meaning "only the base (pair) checkpoint exists". */
	static final int BASE_ONLY_DEGREE = 2;

	private final Path dir;
	private final String fingerprint;

	// In-memory manifest state, rewritten atomically after every barrier.
	private boolean baseWritten = false;
	private int highestDegree = 0;
	private final TreeMap<Integer, long[]> perDegree = new TreeMap<>(); // d -> {rows, generated}

	public CheckpointManager(Path dir, String fingerprint) {
		this.dir = dir;
		this.fingerprint = fingerprint;
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
	public void writeBase(StubColumns allPairStubsPreprune) {
		writeStubFile(PAIR_STUBS, allPairStubsPreprune);
		baseWritten = true;
		if (highestDegree < BASE_ONLY_DEGREE) {
			highestDegree = BASE_ONLY_DEGREE;
		}
		writeManifest();
		log.info("[checkpoint] wrote base pair universe ({} rows) to {}/{}",
				allPairStubsPreprune.size(), dir, PAIR_STUBS);
	}

	/**
	 * Persist one completed extension degree's survivor layer and advance the manifest.
	 *
	 * @param outputDegree   the degree of the rides in {@code layer} (3, 4, ...)
	 * @param layer          the post-prune survivor {@link StubColumns} for that degree
	 * @param generatedCount the number of rides GENERATED at that degree (pre-prune) — restores
	 *                       {@code nextRideIndex} exactly on resume (reserved index space, not the
	 *                       surviving count)
	 */
	public void writeDegree(int outputDegree, StubColumns layer, int generatedCount) {
		writeStubFile("degree_" + outputDegree + ".stubs.bin", layer);
		perDegree.put(outputDegree, new long[] {layer.size(), generatedCount});
		if (outputDegree > highestDegree) {
			highestDegree = outputDegree;
		}
		writeManifest();
		log.info("[checkpoint] wrote degree-{} layer ({} rows, {} generated) — manifest highest={}",
				outputDegree, layer.size(), generatedCount, highestDegree);
	}

	// ------------------------------------------------------------------

	private void writeStubFile(String name, StubColumns sc) {
		Path target = dir.resolve(name);
		Path tmp = dir.resolve(name + ".tmp");
		try (OutputStream out = Files.newOutputStream(tmp)) {
			StubColumnsIO.write(sc, out);
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
