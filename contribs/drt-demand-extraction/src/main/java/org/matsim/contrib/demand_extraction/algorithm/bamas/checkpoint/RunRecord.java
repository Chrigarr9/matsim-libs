package org.matsim.contrib.demand_extraction.algorithm.bamas.checkpoint;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;

/**
 * Writes {@code <outDir>/_extraction_run.json}: the provenance record for one extraction run,
 * spec 6.2 / risk row 1.
 *
 * <p>Unlike {@link CheckpointManager}'s {@code manifest.txt}, this file is written
 * UNCONDITIONALLY -- checkpointing on or off -- because Python must never recompute
 * {@link RunFingerprint}; it can only read what Java already wrote. Before this class existed a
 * run without {@code checkpointDir} set left no fingerprint record at all.
 *
 * <p>Callers write it BEFORE the algorithm runs (immediately after the effective config is
 * settled), so a crashed run still says what it was trying to do.
 */
public final class RunRecord {

	public static final String FILE_NAME = "_extraction_run.json";

	private RunRecord() {}

	/**
	 * Compute the run fingerprint (the same full-hash {@link RunFingerprint#compute} the
	 * checkpoint manifest uses) and write {@code <outDir>/_extraction_run.json}. Idempotent: a
	 * second call with the same {@code config}/inputs/{@code algorithmVersion} reproduces the same
	 * {@code fingerprint}, {@code checkpointVersion}, {@code params}, and {@code inputs} (only
	 * {@code startedAt} legitimately differs between calls).
	 *
	 * @param outDir           extraction output directory (created if missing)
	 * @param config           the algorithm config
	 * @param requests         DRT requests file, or {@code null} when it does not exist yet at
	 *                         write time (e.g. the single-JVM Controler path derives requests
	 *                         live rather than reading a pre-existing dump)
	 * @param travelTimes      travel-times TSV, or {@code null}
	 * @param network          network file, or {@code null}
	 * @param algorithmVersion algorithm/version tag (e.g. {@code "bamas"}) -- pass the same
	 *                         literal the caller feeds to {@link RunFingerprint#compute} elsewhere
	 *                         for the same run, so the recorded fingerprint matches
	 */
	public static void write(Path outDir, ExMasConfigGroup config, Path requests,
			Path travelTimes, Path network, String algorithmVersion) {
		String fingerprint = RunFingerprint.compute(config, requests, travelTimes, network, algorithmVersion);

		Map<String, String> params = new TreeMap<>(config.getParams());

		Map<String, String> inputs = new TreeMap<>();
		putInputDigest(inputs, "requests", requests);
		putInputDigest(inputs, "travelTimes", travelTimes);
		putInputDigest(inputs, "network", network);

		try {
			Files.createDirectories(outDir);
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot create output dir " + outDir, e);
		}

		Path target = outDir.resolve(FILE_NAME);
		Path tmp = outDir.resolve(FILE_NAME + ".tmp");
		try (BufferedWriter bw = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
			bw.write("{\n");
			writeStringField(bw, "fingerprint", fingerprint, true);
			writeNumberField(bw, "checkpointVersion", RunFingerprint.CHECKPOINT_VERSION, true);
			writeStringField(bw, "algorithmVersion", algorithmVersion, true);
			writeStringMapField(bw, "params", params, true);
			writeStringMapField(bw, "inputs", inputs, true);
			writeStringField(bw, "startedAt", Instant.now().toString(), false);
			bw.write("}\n");
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot write run record " + target, e);
		}
		atomicRename(tmp, target);
	}

	private static void putInputDigest(Map<String, String> inputs, String key, Path path) {
		if (path != null) {
			inputs.put(key, RunFingerprint.fileDigest(path));
		}
	}

	private static void atomicRename(Path tmp, Path target) {
		try {
			Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException atomicFailed) {
			// Some filesystems reject ATOMIC_MOVE across the same dir rarely; fall back to a plain
			// replace (still a single rename syscall on common platforms). Mirrors CheckpointManager.
			try {
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				throw new UncheckedIOException("Cannot rename run record " + tmp + " -> " + target, e);
			}
		}
	}

	// ---- minimal hand-rolled JSON writer (mirrors PhaseOneDumpWriter's convention; no JSON
	// library dependency is declared on this module's compile classpath) ----

	private static void writeStringField(BufferedWriter bw, String key, String value,
			boolean trailingComma) throws IOException {
		bw.write("  \"" + key + "\": " + jsonStringLiteral(value) + (trailingComma ? ",\n" : "\n"));
	}

	private static void writeNumberField(BufferedWriter bw, String key, long value,
			boolean trailingComma) throws IOException {
		bw.write("  \"" + key + "\": " + value + (trailingComma ? ",\n" : "\n"));
	}

	private static void writeStringMapField(BufferedWriter bw, String key, Map<String, String> map,
			boolean trailingComma) throws IOException {
		bw.write("  \"" + key + "\": {\n");
		int i = 0;
		int n = map.size();
		for (Map.Entry<String, String> e : map.entrySet()) {
			bw.write("    \"" + jsonEscape(e.getKey()) + "\": " + jsonStringLiteral(e.getValue()));
			bw.write(++i < n ? ",\n" : "\n");
		}
		bw.write("  }" + (trailingComma ? ",\n" : "\n"));
	}

	private static String jsonStringLiteral(String s) {
		return s == null ? "null" : "\"" + jsonEscape(s) + "\"";
	}

	private static String jsonEscape(String s) {
		StringBuilder sb = new StringBuilder(s.length() + 2);
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> {
					if (c < 0x20) {
						sb.append(String.format("\\u%04x", (int) c));
					} else {
						sb.append(c);
					}
				}
			}
		}
		return sb.toString();
	}
}
