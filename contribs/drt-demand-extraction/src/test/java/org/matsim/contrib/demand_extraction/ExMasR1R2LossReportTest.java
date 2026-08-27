package org.matsim.contrib.demand_extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.scenarios.ExMasScenarioFixture;
import org.matsim.contrib.demand_extraction.scenarios.KelheimScenarioFixture;
import org.matsim.contrib.demand_extraction.scenarios.LyonEqasimScenarioFixture;
import org.matsim.contrib.demand_extraction.scenarios.RideSetLossReport;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;

/**
 * Measures the ride-set loss between vanilla ExMAS (R1, no pruning) and BAMAS
 * (R2, no pruning). BAMAS keeps the {@code DegreeGraph} downward-closure prune,
 * which is NOT sound for ExMAS reachability, so R2 ⊊ R1 by design. This is a
 * deliberate, documented methodology limitation (see
 * {@code DegreeGraph.findExtensions} and
 * {@code .project-memory/r1r2-parity-degreegraph-downward-closure-2026-06-16.md}).
 *
 * <p>This is a <b>loss measurement</b>, not an equivalence assertion (it
 * replaced {@code ExMasR1R2ParityTest}, whose {@code assertEquivalent} could
 * never pass under kept closure). The Kelheim case is a fast regression guard
 * that pins the qualitative shape (R2 ⊆ R1, loss > 0). The Lyon case
 * (env-gated, heavy) produces the realistic loss number on the rural DRT
 * region used in the Paper One workflow.
 */
class ExMasR1R2LossReportTest {

	private static final Logger log = LogManager.getLogger(ExMasR1R2LossReportTest.class);

	/** R1 = vanilla ExMAS reference, no pruning. {@code maxDegree} caps pooling degree. */
	private static Consumer<Config> r1Configurator(int maxDegree) {
		return config -> {
			ExMasConfigGroup exMas = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
			exMas.setAlgorithm(ExMasConfigGroup.Algorithm.EXMAS);
			exMas.setPruningDistanceSavingsLogScale(-1.0);
			exMas.setPruningMode(ExMasConfigGroup.PruningMode.RATIO_THRESHOLD);
			exMas.setInterDegreeKeepFraction(1.0);
			exMas.clearPruningCoverageKByDegree();
			exMas.setCalcPredecessors(false);
			exMas.setMaxPoolingDegree(maxDegree);
		};
	}

	/** R2 = BAMAS, no pruning. {@code maxDegree} caps pooling degree. */
	private static Consumer<Config> r2Configurator(int maxDegree) {
		return config -> {
			ExMasConfigGroup exMas = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
			exMas.setAlgorithm(ExMasConfigGroup.Algorithm.BAMAS);
			exMas.setPruningDistanceSavingsLogScale(-1.0);
			exMas.setPruningMode(ExMasConfigGroup.PruningMode.RATIO_THRESHOLD);
			exMas.setInterDegreeKeepFraction(1.0);
			exMas.clearPruningCoverageKByDegree();
			exMas.setCalcPredecessors(false);
			exMas.setMaxPoolingDegree(maxDegree);
		};
	}

	@Test
	@Tag("fast")
	void kelheimQuantifiesClosureLoss() throws IOException {
		ExMasScenarioFixture scenario = new KelheimScenarioFixture();
		Path baseOutputDir = Path.of("test/output/kelheim-r1-r2-loss-report");

		Path r1Rides = runScenarioAndGetRidesCsv(
				scenario, baseOutputDir.resolve("r1"), r1Configurator(Integer.MAX_VALUE), "planCalcScore");
		Path r2Rides = runScenarioAndGetRidesCsv(
				scenario, baseOutputDir.resolve("r2"), r2Configurator(Integer.MAX_VALUE), "planCalcScore");

		RideSetLossReport report = RideSetLossReport.compare(r1Rides, r2Rides);
		log.info("Kelheim closure-loss report:\n{}", report.format());

		// Closure ON ⇒ BAMAS is a strict subset of vanilla ExMAS: it never invents
		// a set ExMAS lacks. A non-zero only-R2 would be a genuine divergence
		// (the failure mode the old equivalence test caught once it cascaded).
		assertEquals(0, report.totalOnlyR2(),
				"BAMAS produced ride sets vanilla ExMAS does not: " + report.format());
		// The loss is real and is the whole point of this test — guard against a
		// silent change that makes closure sound (then update the docs/comment).
		assertTrue(report.totalOnlyR1() > 0,
				"Expected BAMAS to lose some rides to downward closure, but loss=0: "
						+ report.format());
	}

	/**
	 * Realistic Lyon rural DRT region. Skipped unless {@code LYON_SCENARIO_DIR}
	 * (and {@code LYON_TRAVEL_TIMES_TSV}) are set — the 100% bundle is too large
	 * for normal CI. Wire it via {@code scripts/run_lyon_r1r2_loss_report.sh}.
	 *
	 * <p>Env knobs: {@code LYON_SCENARIO_DIR}, {@code LYON_TRAVEL_TIMES_TSV},
	 * {@code LYON_SAMPLE_PCT} (default 1; population must already be at this %),
	 * {@code LYON_FILTER_RADIUS_KM} (default 25 here — the Paper One shareable
	 * region), {@code LYON_MAX_DEGREE} (default 6; caps no-pruning explosion in
	 * BOTH arms so R1 stays tractable).
	 */
	@Test
	@Tag("scenario-lyon")
	void lyonQuantifiesClosureLoss() throws IOException {
		String scenarioDir = System.getenv("LYON_SCENARIO_DIR");
		assumeTrue(scenarioDir != null && !scenarioDir.isEmpty(),
				"LYON_SCENARIO_DIR not set — skipping realistic Lyon loss report");

		int maxDegree = Integer.parseInt(System.getenv().getOrDefault("LYON_MAX_DEGREE", "6"));
		// Default the rural shareable radius to 25 km (Paper One) unless overridden.
		if (System.getenv("LYON_FILTER_RADIUS_KM") == null) {
			log.info("LYON_FILTER_RADIUS_KM unset; LyonEqasimScenarioFixture default applies");
		}
		ExMasScenarioFixture scenario = LyonEqasimScenarioFixture.fromEnv();
		Path baseOutputDir = Path.of("test/output/lyon-r1-r2-loss-report");

		// Lyon uses the fixture's eqasim scoring adapter (null = leave as configured).
		Path r1Rides = runScenarioAndGetRidesCsv(
				scenario, baseOutputDir.resolve("r1"), r1Configurator(maxDegree), null);
		Path r2Rides = runScenarioAndGetRidesCsv(
				scenario, baseOutputDir.resolve("r2"), r2Configurator(maxDegree), null);

		RideSetLossReport report = RideSetLossReport.compare(r1Rides, r2Rides);
		log.info("Lyon (maxDegree={}) closure-loss report:\n{}", maxDegree, report.format());

		// The realistic deliverable is the logged number, so assertions stay
		// loose: just confirm there were shareable rides to measure against.
		assertTrue(report.totalR1() > 0,
				"R1 produced no ride sets — region too small / sample too low to measure loss");
		if (report.totalOnlyR2() > 0) {
			log.warn("Lyon: BAMAS found {} sets vanilla ExMAS missed (fuller ordering "
					+ "enumeration vs restricted pickup-last frame) — a separate finding.",
					report.totalOnlyR2());
		}
	}

	private static Path runScenarioAndGetRidesCsv(
			ExMasScenarioFixture scenario,
			Path outputDir,
			Consumer<Config> configurator,
			String scoringAdapter) throws IOException {
		Config config = scenario.createConfig(outputDir);
		if (scoringAdapter != null) {
			ExMasConfigGroup exMasConfig = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
			exMasConfig.setScoringAdapter(scoringAdapter);
		}
		scenario.configureAlgorithm(config, configurator);
		scenario.runDemandExtraction(scenario.createControler(config));
		scenario.validateOutput(config, outputDir);

		String runId = config.controller().getRunId();
		return outputDir.resolve("drt_demand").resolve(runId + ".exmas_rides.csv");
	}
}
