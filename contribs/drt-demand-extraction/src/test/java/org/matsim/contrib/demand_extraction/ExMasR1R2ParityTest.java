package org.matsim.contrib.demand_extraction;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.scenarios.ExMasScenarioFixture;
import org.matsim.contrib.demand_extraction.scenarios.GoldenAsserter;
import org.matsim.contrib.demand_extraction.scenarios.KelheimScenarioFixture;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;

class ExMasR1R2ParityTest {

	private static final Consumer<Config> R1_CONFIGURATOR = config -> {
		ExMasConfigGroup exMas = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		// R1 = vanilla ExMAS reference, no pruning.
		exMas.setAlgorithm(ExMasConfigGroup.Algorithm.EXMAS);
		exMas.setHeuristicPruningEnabled(false);
		exMas.setPruningDistanceSavingsLogScale(-1.0);
		exMas.setPruningMode(ExMasConfigGroup.PruningMode.RATIO_THRESHOLD);
		exMas.setInterDegreeKeepFraction(1.0);
		exMas.clearPruningCoverageKByDegree();
		exMas.setCalcPredecessors(false);
		exMas.setMaxPoolingDegree(Integer.MAX_VALUE);
	};

	private static final Consumer<Config> R2_CONFIGURATOR = config -> {
		ExMasConfigGroup exMas = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		// R2 = BAMAS, no pruning.
		exMas.setAlgorithm(ExMasConfigGroup.Algorithm.BAMAS);
		exMas.setHeuristicPruningEnabled(false);
		exMas.setPruningDistanceSavingsLogScale(-1.0);
		exMas.setPruningMode(ExMasConfigGroup.PruningMode.RATIO_THRESHOLD);
		exMas.setInterDegreeKeepFraction(1.0);
		exMas.clearPruningCoverageKByDegree();
		exMas.setCalcPredecessors(false);
		exMas.setMaxPoolingDegree(Integer.MAX_VALUE);
	};

	@Test
	@Tag("fast")
	void kelheimR1AndR2ProduceEquivalentCanonicalRideSets() throws IOException {
		ExMasScenarioFixture scenario = new KelheimScenarioFixture();
		Path baseOutputDir = Path.of("test/output/kelheim-r1-r2-parity-test");

		Path r1Rides = runScenarioAndGetRidesCsv(scenario, baseOutputDir.resolve("r1"), R1_CONFIGURATOR);
		Path r2Rides = runScenarioAndGetRidesCsv(scenario, baseOutputDir.resolve("r2"), R2_CONFIGURATOR);

		GoldenAsserter.assertEquivalent(r1Rides, r2Rides, 1e-9);
	}

	private static Path runScenarioAndGetRidesCsv(
			ExMasScenarioFixture scenario,
			Path outputDir,
			Consumer<Config> configurator) throws IOException {
		Config config = scenario.createConfig(outputDir);
		ExMasConfigGroup exMasConfig = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		exMasConfig.setScoringAdapter("planCalcScore");
		scenario.configureAlgorithm(config, configurator);
		scenario.runDemandExtraction(scenario.createControler(config));
		scenario.validateOutput(config, outputDir);

		String runId = config.controller().getRunId();
		return outputDir.resolve("drt_demand").resolve(runId + ".exmas_rides.csv");
	}
}
