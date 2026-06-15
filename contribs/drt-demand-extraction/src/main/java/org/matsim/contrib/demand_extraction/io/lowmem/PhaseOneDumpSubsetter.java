package org.matsim.contrib.demand_extraction.io.lowmem;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Index-based subsetter for a Phase-1 demand-extraction dump. Reads a dump directory
 * ({@link PhaseOneDumpLayout}) and writes a new dump restricted to a chosen set of request
 * indices, so {@code RunDemandExtractionPhase2} can regenerate a ridepooling corpus for
 * that subset without re-running the heavy MATSim extraction.
 *
 * <p>What it does, file by file:
 * <ul>
 *   <li><b>{@code drt_requests_phase1.csv}</b> — copies the header verbatim and every data row
 *       whose {@code index} (column 0) is in {@code keepIndices}, <em>byte-for-byte</em> and in
 *       original dump order. Unrelated columns are never reformatted.</li>
 *   <li><b>{@code scoring_contexts.bin}</b> — re-read via {@link ScoringContextsBinReader}; the
 *       full activity-type table is preserved, and each surviving {@link ScoringContextsBinWriter.RequestRow}
 *       is passed straight through to {@link ScoringContextsBinWriter} (so {@code maxWalkDistance}/
 *       {@code maxWaitTime} are carried automatically). {@code numRequests} is updated.</li>
 *   <li><b>{@code phase1_config.xml}</b> — copied verbatim.</li>
 *   <li><b>{@code phase1_meta.json}</b> — copied with only the {@code numRequests} value rewritten
 *       to the kept count; every other byte is preserved.</li>
 * </ul>
 *
 * <p><b>CSV↔BIN alignment.</b> {@link PhaseOneDumpReader} associates a BIN row with a CSV row by
 * the {@code requestIndex} field (a {@code byIdx} map lookup), and asserts the BIN row count equals
 * the CSV row count. This subsetter keeps both filtered sets per-index and in the same original
 * order, so the invariant holds regardless of how the reader matches.
 *
 * <p><b>YAGNI:</b> index subsetting only — no {@code rel} flex/budget rewrite, no sampling logic.
 *
 * <p><b>v1 dumps are rejected.</b> {@link ScoringContextsBinWriter} always writes version 2; a v1
 * input (no walk/wait caps) would be silently upgraded to "v2 with zeros", falsely advertising
 * budget-derived caps. We fail fast instead. Real Lyon dumps and the test fixtures are v2.
 */
public final class PhaseOneDumpSubsetter {

	private PhaseOneDumpSubsetter() {}

	/**
	 * Write a new dump under {@code outDumpDir} restricted to {@code keepIndices}.
	 *
	 * @throws IllegalArgumentException if any kept index is absent from the input dump's CSV.
	 * @throws IOException on any read/write failure, or if the input BIN is not version 2.
	 */
	public static void subsetDump(Path inDumpDir, Path outDumpDir, Set<Integer> keepIndices)
			throws IOException {
		// --- 0. Guard same-directory: verbatim Files.copy would truncate on Windows. ---
		if (inDumpDir.toAbsolutePath().normalize().equals(outDumpDir.toAbsolutePath().normalize())) {
			throw new IllegalArgumentException("out-dump must differ from in-dump");
		}

		PhaseOneDumpLayout in = new PhaseOneDumpLayout(inDumpDir);
		PhaseOneDumpLayout out = new PhaseOneDumpLayout(outDumpDir);

		// --- 1. Read CSV lines; validate keepIndices ⊆ present BEFORE writing anything. ---
		List<String> lines = Files.readAllLines(in.requestsCsv(), StandardCharsets.UTF_8);
		if (lines.isEmpty()) {
			throw new IOException("input dump CSV is empty: " + in.requestsCsv());
		}
		String header = lines.get(0);
		int indexCol = indexColumn(header); // guaranteed 0 by the writer, but resolve by name.

		// Surviving data lines in original order + the set of all present indices.
		List<String> keptLines = new ArrayList<>();
		List<Integer> keptOrder = new ArrayList<>();
		Set<Integer> present = new LinkedHashSet<>();
		for (int li = 1; li < lines.size(); li++) {
			String line = lines.get(li);
			if (line.isEmpty()) continue;
			int idx = parseIndex(line, indexCol);
			present.add(idx);
			if (keepIndices.contains(idx)) {
				keptLines.add(line);
				keptOrder.add(idx);
			}
		}

		Set<Integer> missing = new TreeSet<>();
		for (Integer k : keepIndices) {
			if (!present.contains(k)) missing.add(k);
		}
		if (!missing.isEmpty()) {
			throw new IllegalArgumentException(
					"keep indices not present in input dump " + in.requestsCsv() + ": " + missing);
		}

		// --- 2. Filter the BIN: full activity-type table, surviving rows pass-through. ---
		// Collect surviving rows keyed by index, then emit them in keptOrder (CSV order).
		ScoringContextsBinReader.Header binHeader;
		Map<Integer, ScoringContextsBinWriter.RequestRow> keptRows =
				new HashMap<>(keptOrder.size() * 2);
		try (ScoringContextsBinReader r = new ScoringContextsBinReader(in.scoringContextsBin())) {
			binHeader = r.readHeader();
			if (binHeader.version() != PhaseOneDumpLayout.SCORING_CONTEXTS_VERSION) {
				throw new IOException(String.format(
						"scoring_contexts.bin is version %d; subsetter only supports version %d "
								+ "(the writer would silently upgrade v1 to v2-with-zeros, fabricating "
								+ "walk/wait caps). Re-dump with the current Phase 1.",
						binHeader.version(), PhaseOneDumpLayout.SCORING_CONTEXTS_VERSION));
			}
			for (int i = 0; i < binHeader.numRequests(); i++) {
				ScoringContextsBinWriter.RequestRow row = r.readRow();
				if (keepIndices.contains(row.requestIndex())) {
					keptRows.put(row.requestIndex(), row);
				}
			}
		}
		// Every kept CSV index must have a matching BIN row, or the input dump is inconsistent.
		for (Integer idx : keptOrder) {
			if (!keptRows.containsKey(idx)) {
				throw new IOException(
						"scoring_contexts.bin has no row for request index " + idx
								+ " present in the CSV — input dump is inconsistent");
			}
		}

		// --- Validate meta BEFORE any writes (fail-fast: a missing/malformed meta must not
		// leave a partial out-dump). Read+rewrite now; write the result during the write phase.
		String rewrittenMeta = rewriteNumRequests(
				Files.readString(in.metaJson(), StandardCharsets.UTF_8), keptOrder.size());

		// --- All inputs validated. Now write the out-dump. ---
		Files.createDirectories(out.root());

		// 2b. Write CSV: header verbatim + kept data lines verbatim, in original order.
		try (BufferedWriter bw = Files.newBufferedWriter(out.requestsCsv(), StandardCharsets.UTF_8)) {
			bw.write(header);
			bw.write('\n');
			for (String line : keptLines) {
				bw.write(line);
				bw.write('\n');
			}
		}

		// 2c. Write BIN: same header table, kept rows in CSV order, numRequests updated.
		try (ScoringContextsBinWriter w = new ScoringContextsBinWriter(out.scoringContextsBin())) {
			w.writeHeader(keptOrder.size(), binHeader.activityTypes());
			for (Integer idx : keptOrder) {
				w.writeRow(keptRows.get(idx)); // straight pass-through — preserves all fields.
			}
		}

		// 3. Copy config verbatim. Real dumps always carry phase1_config.xml (written by the
		// Phase-1 runner, not PhaseOneDumpWriter); copy it when present so writer-only fixtures
		// that omit it still subset cleanly.
		if (Files.exists(in.configXml())) {
			Files.copy(in.configXml(), out.configXml(), StandardCopyOption.REPLACE_EXISTING);
		}

		// 4. Write meta with rewritten numRequests (validated above).
		Files.writeString(out.metaJson(), rewrittenMeta, StandardCharsets.UTF_8);
	}

	/** Resolve the column carrying the request index by header name {@code "index"}. */
	private static int indexColumn(String header) {
		String[] cols = header.split(",", -1);
		for (int i = 0; i < cols.length; i++) {
			if ("index".equals(cols[i].trim())) return i;
		}
		throw new IllegalArgumentException("input dump CSV header has no 'index' column: " + header);
	}

	private static int parseIndex(String line, int indexCol) {
		String[] f = line.split(",", -1);
		if (indexCol >= f.length) {
			throw new IllegalStateException("CSV row has too few columns for index: " + line);
		}
		return Integer.parseInt(f[indexCol].trim());
	}

	/**
	 * Replace the value of the {@code "numRequests"} field in the flat meta JSON, preserving every
	 * other byte. The key appears exactly once and no other key contains that substring, so a
	 * targeted regex is byte-faithful for the rest of the document.
	 */
	static String rewriteNumRequests(String json, int newCount) {
		Matcher m = Pattern.compile("(\"numRequests\"\\s*:\\s*)\\d+").matcher(json);
		if (!m.find()) {
			throw new IllegalStateException("phase1_meta.json has no numRequests field to rewrite");
		}
		return m.replaceFirst("$1" + newCount);
	}
}
