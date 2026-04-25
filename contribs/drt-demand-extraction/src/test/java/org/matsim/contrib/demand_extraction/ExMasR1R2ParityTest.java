package org.matsim.contrib.demand_extraction;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.scenarios.AlgorithmProfile;
import org.matsim.contrib.demand_extraction.scenarios.ExMasScenarioFixture;
import org.matsim.contrib.demand_extraction.scenarios.GoldenAsserter;
import org.matsim.contrib.demand_extraction.scenarios.KelheimScenarioFixture;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;

class ExMasR1R2ParityTest {

	@Test
	@Tag("fast")
	void kelheimR1AndR2ProduceEquivalentCanonicalRideSets() throws IOException {
		ExMasScenarioFixture scenario = new KelheimScenarioFixture();
		Path baseOutputDir = Path.of("test/output/kelheim-r1-r2-parity-test");

		Path r1Rides = runScenarioAndGetRidesCsv(scenario, baseOutputDir.resolve("r1"), AlgorithmProfile.R1);
		Path r2Rides = runScenarioAndGetRidesCsv(scenario, baseOutputDir.resolve("r2"), AlgorithmProfile.R2);

		GoldenAsserter.assertEquivalent(r1Rides, r2Rides, 1e-9);
	}

	private static Path runScenarioAndGetRidesCsv(
			ExMasScenarioFixture scenario,
			Path outputDir,
			AlgorithmProfile profile) throws IOException {
		Config config = scenario.createConfig(outputDir);
		ExMasConfigGroup exMasConfig = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		exMasConfig.setScoringAdapter("planCalcScore");
		scenario.configureAlgorithm(config, profile);
		scenario.runDemandExtraction(scenario.createControler(config));
		scenario.validateOutput(config, outputDir);

		String runId = config.controller().getRunId();
		return outputDir.resolve("drt_demand").resolve(runId + ".exmas_rides.csv");
	}
}