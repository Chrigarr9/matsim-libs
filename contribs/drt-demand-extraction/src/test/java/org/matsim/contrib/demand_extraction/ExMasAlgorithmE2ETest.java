package org.matsim.contrib.demand_extraction;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.scenarios.ExMasScenarioFixture;
import org.matsim.contrib.demand_extraction.scenarios.KelheimScenarioFixture;
import org.matsim.contrib.demand_extraction.scenarios.LyonEqasimScenarioFixture;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;

/**
 * Parameterised end-to-end test exercising the full algorithm × scenario
 * matrix for Paper 1 C1/C2/C3 claims.
 *
 * <ul>
 *   <li><b>Kelheim</b> matrix runs by default (uses bundled test scenario).</li>
 *   <li><b>Lyon</b> matrix is gated behind {@code LYON_SCENARIO_DIR} +
 *       {@code LYON_TRAVEL_TIMES_TSV} env vars and runs only when present
 *       (a real Lyon eqasim cut is required).</li>
 * </ul>
 *
 * <p>Tag groups for selective execution (see {@code pom.xml} surefire config):
 * <ul>
 *   <li>{@code fast} — Kelheim cells (default)</li>
 *   <li>{@code scenario-lyon} — Lyon cells (opt-in)</li>
 * </ul>
 */
public class ExMasAlgorithmE2ETest {

	// R1 = vanilla ExMAS reference, no pruning.
	static final Consumer<Config> R1 = config -> {
		ExMasConfigGroup exMas = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		exMas.setAlgorithm(ExMasConfigGroup.Algorithm.EXMAS);
		exMas.setHeuristicPruningEnabled(false);
		exMas.setPruningDistanceSavingsLogScale(-1.0);
		exMas.setPruningMode(ExMasConfigGroup.PruningMode.RATIO_THRESHOLD);
		exMas.setInterDegreeKeepFraction(1.0);
		exMas.clearPruningCoverageKByDegree();
		exMas.setCalcPredecessors(false);
		exMas.setMaxPoolingDegree(Integer.MAX_VALUE);
	};

	// R2 = BAMAS, no pruning.
	static final Consumer<Config> R2 = config -> {
		ExMasConfigGroup exMas = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		exMas.setAlgorithm(ExMasConfigGroup.Algorithm.BAMAS);
		exMas.setHeuristicPruningEnabled(false);
		exMas.setPruningDistanceSavingsLogScale(-1.0);
		exMas.setPruningMode(ExMasConfigGroup.PruningMode.RATIO_THRESHOLD);
		exMas.setInterDegreeKeepFraction(1.0);
		exMas.clearPruningCoverageKByDegree();
		exMas.setCalcPredecessors(false);
		exMas.setMaxPoolingDegree(Integer.MAX_VALUE);
	};

	// R6 = BAMAS + heuristic distance gate (scale=0.25) + post-extension
	// COVERAGE_TOPK (K=20), predecessors on.
	static final Consumer<Config> R6 = config -> {
		ExMasConfigGroup exMas = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		exMas.setAlgorithm(ExMasConfigGroup.Algorithm.BAMAS);
		exMas.setHeuristicPruningEnabled(true);
		exMas.setPruningDistanceSavingsLogScale(0.25);
		exMas.setPruningMode(ExMasConfigGroup.PruningMode.COVERAGE_TOPK);
		exMas.setPruningCoverageK(20);
		exMas.clearPruningCoverageKByDegree();
		exMas.setCalcPredecessors(true);
		exMas.setMaxPoolingDegree(Integer.MAX_VALUE);
	};

	@Tag("fast")
	@ParameterizedTest(name = "{0} + {1}")
	@MethodSource("kelheimMatrix")
	void kelheimAlgorithmRunsEndToEnd(ExMasScenarioFixture scenario, String profileLabel,
			Consumer<Config> configurator) throws IOException {
		Path outputDir = Path.of("test/output/" + scenario.getName() + "-" + profileLabel);
		scenario.runFullPipeline(outputDir, configurator);
	}

	static Stream<Arguments> kelheimMatrix() {
		// R1 = ExMAS reference, R2 = BAMAS no-pruning, R6 = BAMAS production
		// (distance gate scale=0.25 + post-extension top-K=20). R3–R5 are ablation
		// profiles exercised only in the Lyon distance gate sweep.
		return Stream.of(
				Arguments.of(new KelheimScenarioFixture(), "R1", R1),
				Arguments.of(new KelheimScenarioFixture(), "R2", R2),
				Arguments.of(new KelheimScenarioFixture(), "R6", R6));
	}

	@Nested
	@Tag("scenario-lyon")
	@EnabledIfEnvironmentVariable(named = "LYON_SCENARIO_DIR", matches = ".+")
	class Lyon {

		@ParameterizedTest(name = "{0} + {1}")
		@MethodSource("lyonMatrix")
		void lyonAlgorithmRunsEndToEnd(ExMasScenarioFixture scenario, String profileLabel,
				Consumer<Config> configurator) throws IOException {
			Path outputDir = Path.of("test/output/" + scenario.getName() + "-" + profileLabel);
			scenario.runFullPipeline(outputDir, configurator);
		}

		Stream<Arguments> lyonMatrix() {
			// R3–R5 (ablation profiles) are collected by the distance gate sweep test.
			// The E2E gate exercises the production R6.
			return Stream.of(
					Arguments.of(LyonEqasimScenarioFixture.fromEnv(), "R1", R1),
					Arguments.of(LyonEqasimScenarioFixture.fromEnv(), "R2", R2),
					Arguments.of(LyonEqasimScenarioFixture.fromEnv(), "R6", R6));
		}
	}
}
