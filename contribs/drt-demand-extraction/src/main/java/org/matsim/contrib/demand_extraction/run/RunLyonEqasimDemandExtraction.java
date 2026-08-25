package org.matsim.contrib.demand_extraction.run;

import java.nio.file.Path;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.contrib.demand_extraction.scenarios.LyonEqasimScenarioFixture;
import org.matsim.core.config.Config;
import org.matsim.core.controler.Controler;

/**
 * Eqasim-native DRT demand extraction runner for the Lyon 40-km cut scenario.
 *
 * <p>Setup is owned by {@link LyonEqasimScenarioFixture}; this class only parses the
 * *location* CLI flags (which scenario, which output dir, which config overlay) and
 * runs the controler. Every result-affecting ExMAS knob is driven by an {@code exmas}
 * MATSim-config-XML overlay rendered by {@code exmas_commuters.pipeline.matsim_xml}
 * and applied by {@link ExMasConfigOverlay} -- see spec D4. The ~50-flag orthogonal
 * CLI surface (algorithm/gate/pruning/trip-filter/hub-sync/... overrides) that used to
 * live here was removed 2026-08-19: an unrecognized flag now fails loudly instead of
 * being silently dropped with a log warning.
 *
 * <pre>
 * cd matsim-libs/contribs/drt-demand-extraction
 * mvn exec:java -Dexec.mainClass="org.matsim.contrib.demand_extraction.run.RunLyonEqasimDemandExtraction" \
 *   -Dexec.args="--sample 10 \
 *                --scenario-dir ../../../matsim_scenarios/eqasim-france/output_lyon_drt_10pct \
 *                --prefix lyon_drt_10pct_ \
 *                --travel-times ../../../matsim_scenarios/eqasim-france/output_fullregion_10pct/travel_times.tsv \
 *                --output-dir ../../../outputs/R2 \
 *                --exmas-config ../../../outputs/R2/exmas-config.xml" \
 *   -Denforcer.skip=true
 * </pre>
 */
public class RunLyonEqasimDemandExtraction {

	private static final Logger log = LogManager.getLogger(RunLyonEqasimDemandExtraction.class);

	/**
	 * Location flags recognized by {@link #parseArgs(String[])} that carry no
	 * ExMAS-algorithm meaning. The last six are orchestrator-only (consumed by
	 * {@link RunDemandExtractionTwoPhase}, never forwarded past it) but are accepted
	 * here too so a caller may pass the full two-phase CLI surface to this parser
	 * without tripping the unknown-flag guard.
	 */
	private static final Set<String> VALUE_FLAGS = Set.of(
			"--sample", "--scenario-dir", "--prefix", "--travel-times", "--output-dir",
			"--exmas-config", "--phase1-heap", "--phase2-heap", "--java", "--network",
			"--phase1-dump-dir");

	public static final class ParsedArgs {
		public final int sample;
		public final String scenarioDir;
		public final String prefix;
		public final String travelTimesPath;
		public final String outputDir;
		public final String exmasConfig;

		ParsedArgs(int sample, String scenarioDir, String prefix, String travelTimesPath,
				String outputDir, String exmasConfig) {
			this.sample = sample;
			this.scenarioDir = scenarioDir;
			this.prefix = prefix;
			this.travelTimesPath = travelTimesPath;
			this.outputDir = outputDir;
			this.exmasConfig = exmasConfig;
		}
	}

	static ParsedArgs parseArgs(String[] args) {
		int sample = -1;
		String scenarioDir = null;
		String prefix = "lyon_drt_area_";
		String travelTimesPath = null;
		String outputDir = null;
		String exmasConfig = null;

		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--sample" -> sample = Integer.parseInt(args[++i]);
				case "--scenario-dir" -> scenarioDir = args[++i];
				case "--prefix" -> prefix = args[++i];
				case "--travel-times" -> travelTimesPath = args[++i];
				case "--output-dir" -> outputDir = args[++i];
				case "--exmas-config" -> exmasConfig = args[++i];
				case "--low-memory" -> { /* consumed by main() before parseArgs runs */ }
				case "--phase1-heap", "--phase2-heap", "--java", "--network",
						"--phase1-dump-dir" -> {
					// Orchestrator-only flags. RunDemandExtractionTwoPhase strips these
					// before forwarding to Phase 1/2, so they never reach here in
					// practice; recognized (and their value skipped) so parseArgs is
					// safe to call on the full two-phase CLI surface.
					++i;
				}
				default -> throw new IllegalArgumentException(
						"Unknown argument: " + args[i] + ". Result-affecting ExMAS knobs "
						+ "are no longer CLI flags (removed 2026-08-19, spec D4) -- they "
						+ "come from the pipeline.yaml `exmas:` block, rendered to XML by "
						+ "exmas_commuters.pipeline.matsim_xml and passed via "
						+ "--exmas-config. Recognized flags: " + VALUE_FLAGS + " plus "
						+ "--low-memory.");
			}
		}
		return new ParsedArgs(sample, scenarioDir, prefix, travelTimesPath, outputDir, exmasConfig);
	}

	/** Returns true iff {@code args} contains the orchestrator-trigger flag. */
	static boolean hasLowMemoryFlag(String[] args) {
		for (String a : args) {
			if ("--low-memory".equals(a)) {
				return true;
			}
		}
		return false;
	}

	/** Returns a copy of {@code args} with every {@code --low-memory} occurrence removed
	 *  (boolean flag — no associated value to skip). */
	static String[] stripLowMemoryFlag(String[] args) {
		java.util.List<String> kept = new java.util.ArrayList<>(args.length);
		for (String a : args) {
			if (!"--low-memory".equals(a)) {
				kept.add(a);
			}
		}
		return kept.toArray(new String[0]);
	}

	public static void main(String[] args) throws Exception {
		LoggingSetup.configure();
		if (hasLowMemoryFlag(args)) {
			log.info("--low-memory present — delegating to RunDemandExtractionTwoPhase orchestrator");
			RunDemandExtractionTwoPhase.main(stripLowMemoryFlag(args));
			return;
		}
		ParsedArgs p = parseArgs(args);

		if (p.sample < 0 || p.scenarioDir == null || p.travelTimesPath == null) {
			System.err.println("Usage: --sample <N> --scenario-dir <path> [--prefix <s>] "
					+ "--travel-times <path> [--output-dir <path>] "
					+ "--exmas-config <path> [--low-memory]");
			System.exit(1);
		}
		if (p.exmasConfig == null) {
			throw new IllegalArgumentException(
					"--exmas-config is required. Extraction is configured by the "
					+ "pipeline.yaml `exmas:` block, rendered to XML by "
					+ "exmas_commuters.pipeline.matsim_xml. The result-affecting CLI "
					+ "flags were removed on 2026-08-19 (spec D4).");
		}

		String outputDir = p.outputDir != null
				? p.outputDir
				: "../../../outputs/lyon-eqasim-demand-extraction-" + p.sample + "pct";
		Path outDir = Path.of(outputDir);

		LyonEqasimScenarioFixture fixture = new LyonEqasimScenarioFixture(
				p.sample, p.scenarioDir, p.prefix, p.travelTimesPath, null);

		Config config = fixture.createConfig(outDir);
		ExMasConfigOverlay.apply(config, p.exmasConfig);

		Controler controler = fixture.createControler(config);
		controler.run();

		log.info("\n=== Lyon eqasim DRT demand extraction complete ===");
		log.info("Output: {}", outDir.toAbsolutePath());
	}
}
