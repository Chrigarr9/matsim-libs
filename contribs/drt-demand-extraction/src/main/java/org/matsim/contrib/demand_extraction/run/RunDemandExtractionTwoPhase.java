package org.matsim.contrib.demand_extraction.run;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.contrib.demand_extraction.io.lowmem.PhaseOneDumpLayout;
import org.matsim.contrib.demand_extraction.io.lowmem.PhaseOneDumpSubsetter;

/**
 * Orchestrator for the {@code --low-memory} two-phase mode. Forks
 * {@link RunDemandExtractionPhase1} as a subprocess (Controler + dump + exit) then
 * forks {@link RunDemandExtractionPhase2} (algorithm + post-process from dump),
 * each with its own {@code -Xmx} budget, so the Phase-1 heap is released before
 * Phase 2 starts.
 *
 * <p>CLI surface = {@link RunLyonEqasimDemandExtraction} plus:
 * <ul>
 *   <li>{@code --phase1-heap <e.g. 80g>} (default 80g)</li>
 *   <li>{@code --phase2-heap <e.g. 16g>} (default 16g)</li>
 *   <li>{@code --phase1-dump-dir <path>} (default {@code <outputDir>/} + {@link PhaseOneDumpLayout#SUBDIR})</li>
 *   <li>{@code --java <path-to-java>} (default {@code java.home/bin/java})</li>
 *   <li>{@code --network <path>} (default {@code <scenarioDir>/<prefix>network.xml.gz})</li>
 *   <li>{@code --filter-study-yaml <path>} — turns the Phase-1 request pre-filter ON (see below)</li>
 *   <li>{@code --filter-python-cmd <string>} (default {@value #DEFAULT_FILTER_PYTHON_CMD})</li>
 *   <li>{@code --filter-workdir <path>} — the ExmasCommuters checkout (auto-detected at
 *       {@value #DEFAULT_FILTER_WORKDIR}, else required)</li>
 * </ul>
 *
 * <h2>Phase-1 request pre-filter (OFF by default)</h2>
 *
 * <p>Extraction enumerates rides for EVERY request in the radius, but the Python
 * optimizer then discards the noise clusters (below the relative size threshold) and
 * the clusters the anchor gate force-rejects — 76.5% of the enumerated rides at 100%
 * Lyon. With {@code --filter-study-yaml} the orchestrator runs, BETWEEN the two phases,
 * the Python keep-list script {@value #FILTER_SCRIPT} against that study YAML, subsets
 * the Phase-1 dump in-process via {@link PhaseOneDumpSubsetter}, and points Phase 2 at
 * the filtered dump. Extraction happens at price 0, which is a valid superset for every
 * later price, so one filtered dump serves all price scenarios.
 *
 * <p>Artifacts land next to the dump: {@code phase1_filter_keep.txt} (the kept request
 * indices), {@code phase1_filter_meta.json} (the Policy-B demand-accounting sidecar —
 * filtered requests still count as unserved demand downstream) and
 * {@code phase1_dump_filtered/}. Without {@code --filter-study-yaml} the run is
 * byte-identical to before.
 *
 * <p><b>Why a CLI flag and not an {@code ExMasConfigGroup} param.</b> The filter is
 * defined by the Python clustering block plus {@code optimization.solver.anchor_zones}
 * of a study YAML; none of that has a Java-side representation, and reproducing it here
 * would fork the definition of "noise". Equally decisive: {@code ExMasConfigGroup}
 * strictly rejects unknown {@code <param>} entries at XML-parse time, so adding a param
 * would make every existing config unreadable by a new build and every new config
 * unreadable by an old one. A CLI flag keeps the knob where the knowledge is (the study
 * YAML) and leaves the config schema untouched.
 *
 * <pre>
 * cd matsim-libs/contribs/drt-demand-extraction
 * mvn exec:java -Dexec.mainClass="org.matsim.contrib.demand_extraction.run.RunDemandExtractionTwoPhase" \
 *   -Dexec.args="--sample 1 \
 *                --scenario-dir ../../../matsim_scenarios/eqasim-france/output_lyon_drt_1pct/lyon_drt_area \
 *                --prefix lyon_drt_1pct_ \
 *                --travel-times ../../../matsim_scenarios/eqasim-france/output_fullregion_1pct/travel_times.tsv \
 *                --output-dir ../../../outputs/lyon-twophase-1pct \
 *                --algorithm bamas \
 *                --phase1-heap 24g --phase2-heap 8g" \
 *   -Denforcer.skip=true
 * </pre>
 */
public final class RunDemandExtractionTwoPhase {

	private static final Logger log = LogManager.getLogger(RunDemandExtractionTwoPhase.class);

	private static final String DEFAULT_PHASE1_HEAP = "80g";
	private static final String DEFAULT_PHASE2_HEAP = "16g";

	/** Launcher for the Python keep-list script; split on whitespace into argv. */
	static final String DEFAULT_FILTER_PYTHON_CMD = "uv run python";
	/** Keep-list script path, relative to the filter workdir. */
	static final String FILTER_SCRIPT = "scripts/cut_phase1_requests_to_universe.py";
	/** Conventional ExmasCommuters checkout, relative to this contrib's working dir. */
	static final String DEFAULT_FILTER_WORKDIR = "../../../ExmasCommuters";
	/** Kept request indices, one per line. */
	static final String FILTER_KEEP_FILE = "phase1_filter_keep.txt";
	/** Policy-B demand-accounting sidecar. */
	static final String FILTER_META_FILE = "phase1_filter_meta.json";
	/** Subset dump Phase 2 reads when the filter is on. */
	static final String FILTERED_DUMP_SUBDIR = "phase1_dump_filtered";

	private RunDemandExtractionTwoPhase() {}

	/** Two-phase-only flags parsed out of the raw arg list. {@link #ORCHESTRATOR_FLAGS}
	 *  carries the flags that are consumed here (and must NOT be forwarded to the
	 *  subprocess Phase-1 args). */
	record OrchestratorArgs(
			String phase1Heap, String phase2Heap, String javaBin, String networkXmlOverride,
			String phase1DumpDirOverride, String filterStudyYaml, String filterPythonCmd,
			String filterWorkdir) {

		static final java.util.Set<String> ORCHESTRATOR_FLAGS = java.util.Set.of(
				"--phase1-heap", "--phase2-heap", "--java", "--network", "--phase1-dump-dir",
				"--filter-study-yaml", "--filter-python-cmd", "--filter-workdir");

		static OrchestratorArgs parse(String[] args) {
			String phase1Heap = DEFAULT_PHASE1_HEAP;
			String phase2Heap = DEFAULT_PHASE2_HEAP;
			String javaBin = defaultJavaBin();
			String network = null;
			String dumpDir = null;
			String filterStudyYaml = null;
			String filterPythonCmd = DEFAULT_FILTER_PYTHON_CMD;
			String filterWorkdir = null;
			for (int i = 0; i < args.length; i++) {
				switch (args[i]) {
					case "--phase1-heap" -> phase1Heap = args[++i];
					case "--phase2-heap" -> phase2Heap = args[++i];
					case "--java" -> javaBin = args[++i];
					case "--network" -> network = args[++i];
					case "--phase1-dump-dir" -> dumpDir = args[++i];
					case "--filter-study-yaml" -> filterStudyYaml = args[++i];
					case "--filter-python-cmd" -> filterPythonCmd = args[++i];
					case "--filter-workdir" -> filterWorkdir = args[++i];
					default -> { /* forwarded; see stripOrchestratorFlags */ }
				}
			}
			return new OrchestratorArgs(phase1Heap, phase2Heap, javaBin, network, dumpDir,
					filterStudyYaml, filterPythonCmd, filterWorkdir);
		}

		/** True when {@code --filter-study-yaml} was supplied. */
		boolean filterEnabled() {
			return filterStudyYaml != null;
		}
	}

	/** Strip orchestrator-only flags from {@code args} so the remainder can be
	 *  forwarded verbatim to the Phase-1 subprocess. */
	static String[] stripOrchestratorFlags(String[] args) {
		List<String> kept = new ArrayList<>(args.length);
		for (int i = 0; i < args.length; i++) {
			if (OrchestratorArgs.ORCHESTRATOR_FLAGS.contains(args[i])) {
				i++; // skip value
				continue;
			}
			kept.add(args[i]);
		}
		return kept.toArray(new String[0]);
	}

	static String defaultJavaBin() {
		String javaHome = System.getProperty("java.home");
		String os = System.getProperty("os.name", "").toLowerCase();
		String exe = os.contains("win") ? "java.exe" : "java";
		return Path.of(javaHome, "bin", exe).toString();
	}

	/** Build the runtime classpath for a child JVM. Under {@code mvn exec:java}
	 *  the system property {@code java.class.path} only contains the Maven boot
	 *  classworlds jar — the project classpath is loaded through Plexus's
	 *  {@code ClassRealm} (a {@link java.net.URLClassLoader}). Walk the
	 *  classloader chain and union any URL-classloader URLs into a
	 *  pathseparator-joined string. Falls back to the system property when no
	 *  URL classloader is found (e.g. when this class is invoked directly via
	 *  {@code java -cp ...}, which is how the orchestrator forks its own
	 *  children). */
	static String resolveRuntimeClasspath() {
		java.util.LinkedHashSet<String> entries = new java.util.LinkedHashSet<>();
		for (ClassLoader cl = RunDemandExtractionTwoPhase.class.getClassLoader();
				cl != null; cl = cl.getParent()) {
			if (cl instanceof java.net.URLClassLoader ucl) {
				for (java.net.URL u : ucl.getURLs()) {
					try {
						entries.add(java.nio.file.Path.of(u.toURI()).toString());
					} catch (java.net.URISyntaxException | IllegalArgumentException ex) {
						entries.add(u.getPath());
					}
				}
			}
		}
		if (!entries.isEmpty()) {
			return String.join(java.io.File.pathSeparator, entries);
		}
		return System.getProperty("java.class.path");
	}

	/** Default Phase-2 {@code --network} path: {@code <scenarioDir>/<prefix>network.xml.gz}.
	 *  Mirrors how the eqasim cut config wires the network internally. */
	static String defaultNetworkPath(RunLyonEqasimDemandExtraction.ParsedArgs p) {
		return Path.of(p.scenarioDir, p.prefix + "network.xml.gz").toString();
	}

	static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase().contains("win");
	}

	/**
	 * Locate the ExmasCommuters checkout that hosts {@value #FILTER_SCRIPT}.
	 *
	 * <p>{@code --filter-workdir} wins; otherwise the conventional sibling checkout
	 * {@value #DEFAULT_FILTER_WORKDIR} is probed. Either way the script must actually be
	 * there — a missing script is a setup error that must surface here, not as an opaque
	 * "cannot run program" from {@link ProcessBuilder} after Phase 1 has already burned
	 * hours of wall time.
	 */
	static Path resolveFilterWorkdir(String override) {
		if (override != null) {
			Path dir = Path.of(override);
			if (!Files.isRegularFile(dir.resolve(FILTER_SCRIPT))) {
				throw new IllegalArgumentException(
						"--filter-workdir " + dir.toAbsolutePath() + " does not contain "
								+ FILTER_SCRIPT + " -- point it at the ExmasCommuters checkout.");
			}
			return dir;
		}
		Path candidate = Path.of(DEFAULT_FILTER_WORKDIR);
		if (Files.isRegularFile(candidate.resolve(FILTER_SCRIPT))) {
			return candidate;
		}
		throw new IllegalArgumentException(
				"--filter-study-yaml needs the ExmasCommuters checkout that hosts "
						+ FILTER_SCRIPT + ", and the default " + candidate.toAbsolutePath()
						+ " does not have it. Pass --filter-workdir <path-to-ExmasCommuters>.");
	}

	/**
	 * argv for the Python keep-list script. {@code pythonCmd} is split on whitespace so a
	 * launcher like {@value #DEFAULT_FILTER_PYTHON_CMD} works as-is. On Windows the whole
	 * thing is wrapped in {@code cmd /c}, because {@code uv} is resolved through
	 * {@code PATHEXT} and {@link ProcessBuilder} does not apply it.
	 *
	 * <p>All paths are absolutised: the process runs with its working directory set to
	 * the ExmasCommuters checkout, so a relative dump or YAML path would resolve against
	 * the wrong root.
	 */
	static List<String> buildFilterCommand(String pythonCmd, Path dumpDir, Path studyYaml,
			Path keepFile, Path metaFile) {
		List<String> cmd = new ArrayList<>();
		if (isWindows()) {
			cmd.add("cmd");
			cmd.add("/c");
		}
		for (String token : pythonCmd.trim().split("\\s+")) {
			if (!token.isEmpty()) {
				cmd.add(token);
			}
		}
		cmd.add(FILTER_SCRIPT);
		cmd.add("--phase1-csv");
		cmd.add(dumpDir.resolve(PhaseOneDumpLayout.REQUESTS_CSV).toAbsolutePath().toString());
		cmd.add("--study-yaml");
		cmd.add(studyYaml.toAbsolutePath().toString());
		cmd.add("--out-keep");
		cmd.add(keepFile.toAbsolutePath().toString());
		cmd.add("--out-meta");
		cmd.add(metaFile.toAbsolutePath().toString());
		return cmd;
	}

	/**
	 * Run the Python keep-list script and subset the dump; return the dump directory
	 * Phase 2 must read.
	 *
	 * @throws IOException if the script exits non-zero, yields no kept indices, or the
	 *         subset fails — all of which must abort the run rather than silently letting
	 *         Phase 2 enumerate the unfiltered dump.
	 */
	static Path filterPhaseOneDump(OrchestratorArgs orch, Path outDir, Path dumpDir)
			throws IOException, InterruptedException {
		Path workdir = resolveFilterWorkdir(orch.filterWorkdir());
		Path keepFile = outDir.resolve(FILTER_KEEP_FILE);
		Path metaFile = outDir.resolve(FILTER_META_FILE);
		Path filteredDump = outDir.resolve(FILTERED_DUMP_SUBDIR);
		List<String> filterCmd = buildFilterCommand(
				orch.filterPythonCmd(), dumpDir, Path.of(orch.filterStudyYaml()),
				keepFile, metaFile);

		log.info("Phase-1 request filter (study yaml {}):", orch.filterStudyYaml());
		log.info("  workdir: {}", workdir.toAbsolutePath());
		log.info("  command: {}", String.join(" ", filterCmd));
		long startMs = System.currentTimeMillis();
		int rc = new ProcessBuilder(filterCmd)
				.directory(workdir.toFile())
				.inheritIO()
				.start()
				.waitFor();
		long wallS = (System.currentTimeMillis() - startMs) / 1000;
		log.info("Phase-1 request filter exited rc={}, wall={}s", rc, wallS);
		if (rc != 0) {
			throw new IOException("Phase-1 request filter failed with rc=" + rc
					+ " -- aborting the two-phase run rather than enumerating rides for "
					+ "requests the optimizer would discard. Command: "
					+ String.join(" ", filterCmd));
		}

		Set<Integer> keep = RunSubsetPhase1Dump.readIndexLines(keepFile);
		if (keep.isEmpty()) {
			throw new IOException("Phase-1 request filter kept ZERO requests (" + keepFile
					+ " is empty) -- Phase 2 would have nothing to enumerate.");
		}
		log.info("Subsetting Phase-1 dump {} -> {} ({} kept requests)",
				dumpDir, filteredDump, keep.size());
		PhaseOneDumpSubsetter.subsetDump(dumpDir, filteredDump, keep);
		log.info("Filtered dump written; demand accounting sidecar: {}", metaFile);
		return filteredDump;
	}

	public static void main(String[] args) throws IOException, InterruptedException {
		OrchestratorArgs orch = OrchestratorArgs.parse(args);
		RunLyonEqasimDemandExtraction.ParsedArgs p = RunLyonEqasimDemandExtraction.parseArgs(
				stripOrchestratorFlags(args));

		if (p.sample < 0 || p.scenarioDir == null || p.travelTimesPath == null) {
			System.err.println("Usage: --sample <N> --scenario-dir <path> [--prefix <s>] "
					+ "--travel-times <path> [--output-dir <path>] "
					+ "[--phase1-dump-dir <path>] [--network <path>] "
					+ "[--phase1-heap 80g] [--phase2-heap 16g] [--java <path>] "
					+ "[--algorithm bamas|exmas] [--gate-scale <f>] [--coverage-k <int>] "
					+ "[--filter-study-yaml <path> [--filter-workdir <ExmasCommuters>] "
					+ "[--filter-python-cmd \"uv run python\"]]");
			System.exit(1);
			return;
		}

		String outputDir = p.outputDir != null
				? p.outputDir
				: "../../../outputs/lyon-twophase-" + p.sample + "pct";
		Path outDir = Path.of(outputDir);
		Path dumpDir = orch.phase1DumpDirOverride != null
				? Path.of(orch.phase1DumpDirOverride)
				: outDir.resolve(PhaseOneDumpLayout.SUBDIR);
		String networkPath = orch.networkXmlOverride != null
				? orch.networkXmlOverride
				: defaultNetworkPath(p);

		log.info("======================================================================");
		log.info("TWO-PHASE ORCHESTRATOR");
		log.info("  Sample:        {}%", p.sample);
		log.info("  Output:        {}", outDir.toAbsolutePath());
		log.info("  Dump dir:      {}", dumpDir.toAbsolutePath());
		log.info("  Network:       {}", networkPath);
		log.info("  Phase-1 -Xmx:  {}", orch.phase1Heap);
		log.info("  Phase-2 -Xmx:  {}", orch.phase2Heap);
		log.info("  java bin:      {}", orch.javaBin);
		log.info("  P1 filter:     {}", orch.filterEnabled() ? orch.filterStudyYaml() : "OFF");
		log.info("======================================================================");

		// Fail before the (hours-long) Phase 1 if the filter is misconfigured.
		if (orch.filterEnabled()) {
			Path filterWorkdir = resolveFilterWorkdir(orch.filterWorkdir());
			Path studyYaml = Path.of(orch.filterStudyYaml());
			if (!Files.isRegularFile(studyYaml)) {
				throw new IllegalArgumentException(
						"--filter-study-yaml " + studyYaml.toAbsolutePath() + " does not exist.");
			}
			log.info("  P1 filter workdir: {}", filterWorkdir.toAbsolutePath());
		}

		String classpath = resolveRuntimeClasspath();
		log.info("  Resolved classpath: {} entries (length={} chars)",
				classpath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)).length,
				classpath.length());

		// Windows' CreateProcess command-line limit is ~32 KiB; this project's
		// classpath alone is ~33 KiB and growing. Write the classpath to a JVM
		// argfile and pass `@argfile` instead of an inline `-cp <huge>`. The
		// launcher expands the argfile internally so the OS-level command line
		// stays short. Both phases share the same argfile.
		//
		// Argfile parsing treats backslashes as escape characters even inside
		// double-quoted strings (per the Java launcher docs), so every `\` in
		// a Windows path must be doubled to survive the round trip.
		java.nio.file.Files.createDirectories(outDir);
		java.nio.file.Path cpArgfile = outDir.resolve(".cp-argfile.txt");
		String escapedCp = classpath.replace("\\", "\\\\").replace("\"", "\\\"");
		java.nio.file.Files.writeString(cpArgfile, "-cp \"" + escapedCp + "\"\n");

		// Phase 1: forward all non-orchestrator args plus the resolved dump-dir + output-dir.
		String[] forwardedToPhase1 = stripOrchestratorFlags(args);
		List<String> phase1Cmd = new ArrayList<>();
		phase1Cmd.add(orch.javaBin);
		phase1Cmd.add("-Xmx" + orch.phase1Heap);
		phase1Cmd.add("@" + cpArgfile.toAbsolutePath());
		phase1Cmd.add("org.matsim.contrib.demand_extraction.run.RunDemandExtractionPhase1");
		phase1Cmd.addAll(Arrays.asList(forwardedToPhase1));
		if (orch.phase1DumpDirOverride == null) {
			// Phase 1 will otherwise default to its own outputDir/phase1_dump — pin it
			// explicitly so Phase 2 finds the dump even if Phase 1's default ever drifts.
			phase1Cmd.add("--phase1-dump-dir");
			phase1Cmd.add(dumpDir.toString());
		}
		// Ensure Phase 1's --output-dir matches the orchestrator's outputDir so
		// rides land alongside the dump.
		if (p.outputDir == null) {
			phase1Cmd.add("--output-dir");
			phase1Cmd.add(outDir.toString());
		}

		log.info("Phase 1 command:");
		log.info("  {}", String.join(" ", phase1Cmd));
		long phase1StartMs = System.currentTimeMillis();
		int rc = new ProcessBuilder(phase1Cmd).inheritIO().start().waitFor();
		long phase1WallS = (System.currentTimeMillis() - phase1StartMs) / 1000;
		log.info("Phase 1 exited rc={}, wall={}s", rc, phase1WallS);
		if (rc != 0) {
			log.error("Phase 1 failed — aborting two-phase run.");
			System.exit(rc);
			return;
		}

		// Optional Phase-1 request pre-filter: cut the dump down to the demand
		// universe so Phase 2 never enumerates rides the optimizer would discard.
		Path phase2DumpDir = dumpDir;
		long filterWallS = 0;
		if (orch.filterEnabled()) {
			long filterStartMs = System.currentTimeMillis();
			phase2DumpDir = filterPhaseOneDump(orch, outDir, dumpDir);
			filterWallS = (System.currentTimeMillis() - filterStartMs) / 1000;
		}

		// Phase 2.
		List<String> phase2Cmd = List.of(
				orch.javaBin,
				"-Xmx" + orch.phase2Heap,
				"@" + cpArgfile.toAbsolutePath(),
				"org.matsim.contrib.demand_extraction.run.RunDemandExtractionPhase2",
				"--phase1-dir", phase2DumpDir.toString(),
				"--network", networkPath,
				"--travel-times", p.travelTimesPath,
				"--output-dir", outDir.toString());
		log.info("Phase 2 command:");
		log.info("  {}", String.join(" ", phase2Cmd));
		long phase2StartMs = System.currentTimeMillis();
		rc = new ProcessBuilder(phase2Cmd).inheritIO().start().waitFor();
		long phase2WallS = (System.currentTimeMillis() - phase2StartMs) / 1000;
		log.info("Phase 2 exited rc={}, wall={}s", rc, phase2WallS);

		log.info("");
		log.info("======================================================================");
		log.info("TWO-PHASE ORCHESTRATOR COMPLETE");
		log.info("  Phase-1 wall:  {}s", phase1WallS);
		if (orch.filterEnabled()) {
			log.info("  P1 filter wall:{}s", filterWallS);
		}
		log.info("  Phase-2 wall:  {}s", phase2WallS);
		log.info("  Total wall:    {}s", phase1WallS + filterWallS + phase2WallS);
		log.info("  Final rc:      {}", rc);
		log.info("======================================================================");
		System.exit(rc);
	}
}
