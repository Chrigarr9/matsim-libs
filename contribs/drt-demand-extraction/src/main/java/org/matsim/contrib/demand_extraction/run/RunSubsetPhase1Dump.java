package org.matsim.contrib.demand_extraction.run;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.contrib.demand_extraction.io.lowmem.PhaseOneDumpSubsetter;

/**
 * Thin CLI over {@link PhaseOneDumpSubsetter#subsetDump}: cut a Phase-1 demand-extraction
 * dump down to a chosen set of request indices, so {@code RunDemandExtractionPhase2} can
 * regenerate a ridepooling corpus for that subset without re-running MATSim extraction.
 *
 * <pre>
 * cd matsim-libs/contribs/drt-demand-extraction
 * mvn exec:java -Dexec.mainClass="org.matsim.contrib.demand_extraction.run.RunSubsetPhase1Dump" \
 *   -Dexec.args="--in-dump  ../../../outputs/lyon-rural-100pct/base/phase1_dump \
 *                --out-dump ../../../outputs/lyon-rural-cellA/phase1_dump \
 *                --keep-requests-csv ../../../outputs/cellA/requests_cut.csv" \
 *   -Denforcer.skip=true
 * </pre>
 *
 * <p>Kept-index source (supply at least one; both may be combined — the union is kept):
 * <ul>
 *   <li>{@code --keep-requests-csv <path>} — a CSV with a header row containing an
 *       {@code index} column (the output of the Python {@code cut_and_sample_requests.py});
 *       the {@code index} column is located by name, not position.</li>
 *   <li>{@code --keep-indices <path>} — a plain text file with one integer index per line
 *       (blank lines and {@code #} comments ignored).</li>
 * </ul>
 *
 * <p>YAGNI: index subsetting only — no {@code rel} flex rewrite, no sampling.
 */
public final class RunSubsetPhase1Dump {

	private static final Logger log = LogManager.getLogger(RunSubsetPhase1Dump.class);

	/** Parsed CLI inputs. */
	record Args(Path inDump, Path outDump, Path keepRequestsCsv, Path keepIndices) {}

	private RunSubsetPhase1Dump() {}

	public static void main(String[] args) throws IOException {
		Args parsed = parseArgs(args);
		Set<Integer> keep = resolveKeepIndices(parsed);
		log.info("Subsetting Phase-1 dump {} -> {} ({} kept indices)",
				parsed.inDump(), parsed.outDump(), keep.size());
		PhaseOneDumpSubsetter.subsetDump(parsed.inDump(), parsed.outDump(), keep);
		log.info("Done. Subset dump written to {}", parsed.outDump());
	}

	static Args parseArgs(String[] args) {
		String inDump = null;
		String outDump = null;
		String keepRequestsCsv = null;
		String keepIndices = null;
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--in-dump" -> inDump = args[++i];
				case "--out-dump" -> outDump = args[++i];
				case "--keep-requests-csv" -> keepRequestsCsv = args[++i];
				case "--keep-indices" -> keepIndices = args[++i];
				default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
			}
		}
		if (inDump == null || outDump == null) {
			throw new IllegalArgumentException(
					"Usage: --in-dump <dir> --out-dump <dir> "
							+ "(--keep-requests-csv <path> | --keep-indices <path>)");
		}
		if (keepRequestsCsv == null && keepIndices == null) {
			throw new IllegalArgumentException(
					"supply a kept-index source: --keep-requests-csv <path> and/or --keep-indices <path>");
		}
		return new Args(Path.of(inDump), Path.of(outDump),
				keepRequestsCsv == null ? null : Path.of(keepRequestsCsv),
				keepIndices == null ? null : Path.of(keepIndices));
	}

	/** Union of the indices from whichever source(s) were supplied. */
	static Set<Integer> resolveKeepIndices(Args args) throws IOException {
		Set<Integer> keep = new LinkedHashSet<>();
		if (args.keepRequestsCsv() != null) {
			keep.addAll(readIndexColumn(args.keepRequestsCsv()));
		}
		if (args.keepIndices() != null) {
			keep.addAll(readIndexLines(args.keepIndices()));
		}
		if (keep.isEmpty()) {
			throw new IllegalArgumentException("kept-index source(s) yielded no indices");
		}
		return keep;
	}

	/** Read the {@code index} column (located by header name) of a requests CSV. */
	static Set<Integer> readIndexColumn(Path csv) throws IOException {
		List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
		if (lines.isEmpty()) {
			throw new IllegalArgumentException("keep-requests-csv is empty: " + csv);
		}
		String[] header = lines.get(0).split(",", -1);
		int idxCol = -1;
		for (int i = 0; i < header.length; i++) {
			if ("index".equals(header[i].trim())) { idxCol = i; break; }
		}
		if (idxCol < 0) {
			throw new IllegalArgumentException("keep-requests-csv has no 'index' column: " + csv);
		}
		Set<Integer> out = new LinkedHashSet<>();
		for (int li = 1; li < lines.size(); li++) {
			String line = lines.get(li);
			if (line.isEmpty()) continue;
			String[] f = line.split(",", -1);
			out.add(Integer.parseInt(f[idxCol].trim()));
		}
		return out;
	}

	/** Read one integer index per line; ignore blank lines and {@code #} comments. */
	static Set<Integer> readIndexLines(Path file) throws IOException {
		Set<Integer> out = new LinkedHashSet<>();
		for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
			String line = raw.trim();
			if (line.isEmpty() || line.startsWith("#")) continue;
			out.add(Integer.parseInt(line));
		}
		return out;
	}
}
