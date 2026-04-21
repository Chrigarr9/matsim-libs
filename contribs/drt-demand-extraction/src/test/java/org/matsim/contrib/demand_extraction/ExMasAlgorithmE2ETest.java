package org.matsim.contrib.demand_extraction;

import java.io.IOException;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.matsim.contrib.demand_extraction.scenarios.AlgorithmProfile;
import org.matsim.contrib.demand_extraction.scenarios.ExMasScenarioFixture;
import org.matsim.contrib.demand_extraction.scenarios.KelheimScenarioFixture;
import org.matsim.contrib.demand_extraction.scenarios.LyonEqasimScenarioFixture;

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

	@Tag("fast")
	@ParameterizedTest(name = "{0} + {1}")
	@MethodSource("kelheimMatrix")
	void kelheimAlgorithmRunsEndToEnd(ExMasScenarioFixture scenario, AlgorithmProfile profile)
			throws IOException {
		Path outputDir = Path.of("test/output/" + scenario.getName() + "-" + profile.label());
		scenario.runFullPipeline(outputDir, profile);
	}

	static Stream<Arguments> kelheimMatrix() {
		return Stream.of(
				Arguments.of(new KelheimScenarioFixture(), AlgorithmProfile.R1),
				Arguments.of(new KelheimScenarioFixture(), AlgorithmProfile.R2),
				Arguments.of(new KelheimScenarioFixture(), AlgorithmProfile.R3));
	}

	@Nested
	@Tag("scenario-lyon")
	@EnabledIfEnvironmentVariable(named = "LYON_SCENARIO_DIR", matches = ".+")
	class Lyon {

		@ParameterizedTest(name = "{0} + {1}")
		@MethodSource("lyonMatrix")
		void lyonAlgorithmRunsEndToEnd(ExMasScenarioFixture scenario, AlgorithmProfile profile)
				throws IOException {
			Path outputDir = Path.of("test/output/" + scenario.getName() + "-" + profile.label());
			scenario.runFullPipeline(outputDir, profile);
		}

		Stream<Arguments> lyonMatrix() {
			return Stream.of(
					Arguments.of(LyonEqasimScenarioFixture.fromEnv(), AlgorithmProfile.R1),
					Arguments.of(LyonEqasimScenarioFixture.fromEnv(), AlgorithmProfile.R2),
					Arguments.of(LyonEqasimScenarioFixture.fromEnv(), AlgorithmProfile.R3));
		}
	}
}
