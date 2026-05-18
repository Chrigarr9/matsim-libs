package org.matsim.contrib.demand_extraction.run;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.contrib.demand_extraction.io.lowmem.PhaseOneDumpLayout;

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
 * </ul>
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

	private RunDemandExtractionTwoPhase() {}

	/** Two-phase-only flags parsed out of the raw arg list. {@code orchestratorOnlyFlags}
	 *  carries the four flags that are consumed here (and must NOT be forwarded to the
	 *  subprocess Phase-1 args). */
	record OrchestratorArgs(
			String phase1Heap, String phase2Heap, String javaBin, String networkXmlOverride,
			String phase1DumpDirOverride) {

		static final java.util.Set<String> ORCHESTRATOR_FLAGS = java.util.Set.of(
				"--phase1-heap", "--phase2-heap", "--java", "--network", "--phase1-dump-dir");

		static OrchestratorArgs parse(String[] args) {
			String phase1Heap = DEFAULT_PHASE1_HEAP;
			String phase2Heap = DEFAULT_PHASE2_HEAP;
			String javaBin = defaultJavaBin();
			String network = null;
			String dumpDir = null;
			for (int i = 0; i < args.length; i++) {
				switch (args[i]) {
					case "--phase1-heap" -> phase1Heap = args[++i];
					case "--phase2-heap" -> phase2Heap = args[++i];
					case "--java" -> javaBin = args[++i];
					case "--network" -> network = args[++i];
					case "--phase1-dump-dir" -> dumpDir = args[++i];
					default -> { /* forwarded; see stripOrchestratorFlags */ }
				}
			}
			return new OrchestratorArgs(phase1Heap, phase2Heap, javaBin, network, dumpDir);
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

	public static void main(String[] args) throws IOException, InterruptedException {
		OrchestratorArgs orch = OrchestratorArgs.parse(args);
		RunLyonEqasimDemandExtraction.ParsedArgs p = RunLyonEqasimDemandExtraction.parseArgs(
				stripOrchestratorFlags(args));

		if (p.sample < 0 || p.scenarioDir == null || p.travelTimesPath == null) {
			System.err.println("Usage: --sample <N> --scenario-dir <path> [--prefix <s>] "
					+ "--travel-times <path> [--output-dir <path>] "
					+ "[--phase1-dump-dir <path>] [--network <path>] "
					+ "[--phase1-heap 80g] [--phase2-heap 16g] [--java <path>] "
					+ "[--algorithm bamas|exmas] [--gate-scale <f>] [--coverage-k <int>]");
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
		log.info("======================================================================");

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

		// Phase 2.
		List<String> phase2Cmd = List.of(
				orch.javaBin,
				"-Xmx" + orch.phase2Heap,
				"@" + cpArgfile.toAbsolutePath(),
				"org.matsim.contrib.demand_extraction.run.RunDemandExtractionPhase2",
				"--phase1-dir", dumpDir.toString(),
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
		log.info("  Phase-2 wall:  {}s", phase2WallS);
		log.info("  Total wall:    {}s", phase1WallS + phase2WallS);
		log.info("  Final rc:      {}", rc);
		log.info("======================================================================");
		System.exit(rc);
	}
}
