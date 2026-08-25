package org.matsim.contrib.demand_extraction.run;

import java.nio.file.Path;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.contrib.demand_extraction.demand.DemandExtractionListener;
import org.matsim.contrib.demand_extraction.demand.Phase1DumpListener;
import org.matsim.contrib.demand_extraction.demand.Phase1DumpListener.Phase1Config;
import org.matsim.contrib.demand_extraction.io.lowmem.PhaseOneDumpLayout;
import org.matsim.contrib.demand_extraction.scenarios.LyonEqasimScenarioFixture;
import org.matsim.core.config.Config;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;

/**
 * Phase-1 runner for the {@code --low-memory} two-phase mode: builds the eqasim
 * Controler exactly like {@link RunLyonEqasimDemandExtraction}, but installs a
 * Guice override that replaces {@link DemandExtractionListener} with
 * {@link Phase1DumpListener}. The replacement listener runs steps 0–3, writes the
 * Phase-1 dump, then calls {@code System.exit(0)} so the Controler heap is
 * released before Phase 2 starts in a fresh JVM.
 *
 * <p>CLI: identical to {@link RunLyonEqasimDemandExtraction} plus
 * {@code --phase1-dump-dir <path>}; if omitted, the dump root defaults to
 * {@code <outputDir>/} + {@link PhaseOneDumpLayout#SUBDIR}. Every result-affecting
 * ExMAS knob comes from the {@code --exmas-config} overlay (spec D4), applied by
 * {@link ExMasConfigOverlay} exactly like the single-JVM runner.
 *
 * <pre>
 * cd matsim-libs/contribs/drt-demand-extraction
 * mvn exec:java -Dexec.mainClass="org.matsim.contrib.demand_extraction.run.RunDemandExtractionPhase1" \
 *   -Dexec.args="--sample 1 \
 *                --scenario-dir ../../../matsim_scenarios/eqasim-france/output_lyon_drt_1pct/lyon_drt_area \
 *                --prefix lyon_drt_1pct_ \
 *                --travel-times ../../../matsim_scenarios/eqasim-france/output_fullregion_1pct/travel_times.tsv \
 *                --output-dir ../../../outputs/lyon-phase1-smoke-1pct \
 *                --exmas-config ../../../outputs/lyon-phase1-smoke-1pct/exmas-config.xml" \
 *   -Denforcer.skip=true
 * </pre>
 */
public final class RunDemandExtractionPhase1 {

	private static final Logger log = LogManager.getLogger(RunDemandExtractionPhase1.class);

	private RunDemandExtractionPhase1() {}

	/** Parses {@code --phase1-dump-dir <path>} out of an arg list; returns
	 *  {@code null} when the flag is absent so the caller can fall back to
	 *  {@code <outputDir>/phase1_dump}. Other flags are ignored here — they are
	 *  parsed by {@link RunLyonEqasimDemandExtraction#parseArgs(String[])}. */
	static String parsePhase1DumpDir(String[] args) {
		for (int i = 0; i < args.length; i++) {
			if ("--phase1-dump-dir".equals(args[i]) && i + 1 < args.length) {
				return args[i + 1];
			}
		}
		return null;
	}

	/** Resolves the dump root using either the explicit {@code --phase1-dump-dir}
	 *  value or the {@code <outputDir>/phase1_dump} default. */
	static Path resolveDumpRoot(String phase1DumpDir, Path outputDir) {
		return phase1DumpDir != null
				? Path.of(phase1DumpDir)
				: outputDir.resolve(PhaseOneDumpLayout.SUBDIR);
	}

	public static void main(String[] args) throws Exception {
		LoggingSetup.configure();
		RunLyonEqasimDemandExtraction.ParsedArgs p = RunLyonEqasimDemandExtraction.parseArgs(args);
		String phase1DumpDir = parsePhase1DumpDir(args);

		if (p.sample < 0 || p.scenarioDir == null || p.travelTimesPath == null) {
			System.err.println("Usage: --sample <N> --scenario-dir <path> [--prefix <s>] "
					+ "--travel-times <path> [--output-dir <path>] [--phase1-dump-dir <path>] "
					+ "--exmas-config <path>");
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
				: "../../../outputs/lyon-eqasim-demand-extraction-phase1-" + p.sample + "pct";
		Path outDir = Path.of(outputDir);
		Path dumpRoot = resolveDumpRoot(phase1DumpDir, outDir);

		log.info("PHASE 1 RUNNER — output: {}, dump: {}", outDir.toAbsolutePath(), dumpRoot.toAbsolutePath());

		LyonEqasimScenarioFixture fixture = new LyonEqasimScenarioFixture(
				p.sample, p.scenarioDir, p.prefix, p.travelTimesPath, null);

		Config config = fixture.createConfig(outDir);
		ExMasConfigOverlay.apply(config, p.exmasConfig);

		Controler controler = fixture.createControler(config);
		controler.addOverridingModule(phase1ListenerOverride(dumpRoot, p.sample));
		controler.run();

		// Phase1DumpListener.notifyShutdown calls System.exit(0); reaching this line
		// means dump-write was skipped (e.g. Controler aborted before shutdown).
		log.warn("Phase-1 runner reached end of main without listener-driven exit — Controler "
				+ "may have aborted before STEP 3. Inspect the log for upstream errors.");
	}

	/** Override module that swaps {@link DemandExtractionListener} for
	 *  {@link Phase1DumpListener} and provides the dump-root payload. Installed
	 *  after {@code DemandExtractionModule} so it wins the binding contest.
	 *
	 *  <p>Delegates to {@link #phase1ListenerBindings(Path, int)} so the same
	 *  binding logic can be exercised in a plain-Guice test without MATSim's
	 *  bootstrap-injector ceremony. */
	static AbstractModule phase1ListenerOverride(Path dumpRoot, int samplePct) {
		com.google.inject.Module bindings = phase1ListenerBindings(dumpRoot, samplePct);
		return new AbstractModule() {
			@Override
			public void install() {
				install(bindings);
			}
		};
	}

	/** Pure-Guice version of the Phase-1 override bindings: binds the
	 *  {@link Phase1Config} payload and links {@link DemandExtractionListener}
	 *  to {@link Phase1DumpListener}. Kept separate from the MATSim wrapper so
	 *  it is testable via {@code Guice.createInjector} without a bootstrap. */
	static com.google.inject.Module phase1ListenerBindings(Path dumpRoot, int samplePct) {
		return binder -> {
			binder.bind(Phase1Config.class).toInstance(new Phase1Config(dumpRoot, samplePct));
			binder.bind(DemandExtractionListener.class).to(Phase1DumpListener.class)
					.in(com.google.inject.Scopes.SINGLETON);
		};
	}
}
