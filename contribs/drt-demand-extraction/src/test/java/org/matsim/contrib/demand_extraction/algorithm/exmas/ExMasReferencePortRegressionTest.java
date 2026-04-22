package org.matsim.contrib.demand_extraction.algorithm.exmas;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.matsim.contrib.demand_extraction.scenarios.AlgorithmProfile;
import org.matsim.contrib.demand_extraction.scenarios.GoldenAsserter;
import org.matsim.contrib.demand_extraction.scenarios.KelheimScenarioFixture;
import org.matsim.core.config.Config;

/**
 * Regression guard for the R1 ExMAS port (algorithm/exmas/).
 *
 * <p>The golden CSVs in {@code src/test/resources/golden/exmas-kelheim/} represent
 * paper Algorithm 2 — full 2^n extension enumeration per candidate. They were
 * regenerated after the {@code getAllPairRideCombinations} fix (2026-04-22) that
 * corrects the Python {@code list(product(*E))[0]} bug; they no longer match
 * {@code main}'s binary, which still takes only the first combination.
 *
 * <p>Tagged {@code regression}: opt-in via
 * {@code -Djunit.groups=regression -Djunit.excludedGroups=}.
 *
 * <p>To regenerate: {@code scripts/regenerate_exmas_reference_golden.sh --force}.
 */
@Tag("regression")
public class ExMasReferencePortRegressionTest {

	@Test
	void reconstructedExMasMatchesMainBinaryOnKelheim() throws IOException {
		Path outputDir = Path.of("test/output/exmas-port-regression");
		KelheimScenarioFixture fixture = new KelheimScenarioFixture();

		Config config = fixture.createConfig(outputDir);
		fixture.configureAlgorithm(config, AlgorithmProfile.R1);
		fixture.runDemandExtraction(fixture.createControler(config));

		String runId = config.controller().getRunId();
		Path actualRides = outputDir.resolve("drt_demand").resolve(runId + ".exmas_rides.csv");
		Path goldenRides = Path.of("src/test/resources/golden/exmas-kelheim/exmas_rides.csv");

		GoldenAsserter.assertEquivalent(goldenRides, actualRides, 1e-9);
	}
}
