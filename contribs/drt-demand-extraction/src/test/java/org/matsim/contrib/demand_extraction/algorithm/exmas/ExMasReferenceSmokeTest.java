package org.matsim.contrib.demand_extraction.algorithm.exmas;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup.Algorithm;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup.CommuteFilter;
import org.matsim.contrib.demand_extraction.demand.DemandExtractionConfigValidator;
import org.matsim.contrib.demand_extraction.demand.DemandExtractionModule;
import org.matsim.contrib.drt.run.DrtControlerCreator;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.examples.ExamplesUtils;

/**
 * Phase 2.6 smoke test — verifies the reconstructed ExMAS reference algorithm
 * runs end-to-end on Kelheim without throwing. Not a regression check (that's
 * Phase 6's {@code ExMasReferencePortRegressionTest}). Just asserts output
 * files exist and are non-empty.
 *
 * <p>This class becomes obsolete once Phase 5b's parameterised
 * {@code ExMasAlgorithmE2ETest} covers the Kelheim × R1 matrix cell. Delete
 * in Phase 5b.
 */
public class ExMasReferenceSmokeTest {

	@Test
	void referenceExMasRunsEndToEndOnKelheim() throws IOException {
		Path testOutputDir = Path.of("test/output/exmas-reference-smoke");
		Files.createDirectories(testOutputDir);

		URL scenarioUrl = ExamplesUtils.getTestScenarioURL("kelheim");
		Config config = ConfigUtils.loadConfig(
				new URL(scenarioUrl, "config.xml").toString(),
				new ExMasConfigGroup());

		config.controller().setOutputDirectory(testOutputDir.toString());
		config.controller()
				.setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
		config.controller().setLastIteration(0);

		ExMasConfigGroup exMasConfigPreview = (ExMasConfigGroup) config.getModules().get(ExMasConfigGroup.GROUP_NAME);
		String drtMode = exMasConfigPreview != null ? exMasConfigPreview.getDrtMode() : "drt";

		if (!config.scoring().getModes().containsKey(drtMode)) {
			ScoringConfigGroup.ModeParams drtParams = new ScoringConfigGroup.ModeParams(drtMode);
			drtParams.setMarginalUtilityOfTraveling(-0.5);
			drtParams.setConstant(0.0);
			drtParams.setMonetaryDistanceRate(0.0);
			config.scoring().addModeParams(drtParams);
		} else {
			config.scoring().getModes().get(drtMode).setMarginalUtilityOfTraveling(-0.5);
		}

		org.matsim.dsim.Activities.addScoringParams(config);

		configureExMasForReference(config);

		DemandExtractionConfigValidator.prepareConfigForDemandExtraction(config);

		Scenario scenario = DrtControlerCreator.createScenarioWithDrtRouteFactory(config);
		ScenarioUtils.loadScenario(scenario);

		// Drop freight agents (same as ExMasKelheimE2ETest).
		scenario.getPopulation().getPersons().values()
				.removeIf(person -> person.getSelectedPlan().getPlanElements().stream()
						.filter(org.matsim.api.core.v01.population.Activity.class::isInstance)
						.map(org.matsim.api.core.v01.population.Activity.class::cast)
						.anyMatch(act -> act.getType().startsWith("freight")));

		Controler controler = DrtControlerCreator.createControler(config, scenario, false);
		controler.addOverridingModule(new DemandExtractionModule());

		controler.run();

		String runId = config.controller().getRunId();
		Path drtDemandDir = testOutputDir.resolve("drt_demand");
		Path requestsFile = drtDemandDir.resolve(runId + ".drt_requests.csv");
		Path ridesFile = drtDemandDir.resolve(runId + ".exmas_rides.csv");

		Assertions.assertTrue(Files.exists(requestsFile), "requests CSV should exist: " + requestsFile);
		Assertions.assertTrue(Files.exists(ridesFile), "rides CSV should exist: " + ridesFile);

		long requestLines = Files.lines(requestsFile).count();
		long rideLines = Files.lines(ridesFile).count();
		Assertions.assertTrue(requestLines > 1, "requests CSV should have at least one data row");
		Assertions.assertTrue(rideLines > 1, "rides CSV should have at least one data row");
	}

	private void configureExMasForReference(Config config) {
		ExMasConfigGroup exMasConfig = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);

		// The one difference from ExMasKelheimE2ETest: run the reference strategy.
		exMasConfig.setAlgorithm(Algorithm.EXMAS);

		exMasConfig.setDrtMode("drt");

		Set<String> baseModes = new HashSet<>();
		baseModes.add(TransportMode.car);
		baseModes.add(TransportMode.pt);
		baseModes.add(TransportMode.walk);
		baseModes.add(TransportMode.bike);
		exMasConfig.setBaseModes(baseModes);

		exMasConfig.setDrtRoutingMode(TransportMode.car);
		exMasConfig.setCommuteFilter(CommuteFilter.COMMUTES_ONLY);

		Set<String> privateVehicles = new HashSet<>();
		privateVehicles.add(TransportMode.car);
		privateVehicles.add(TransportMode.bike);
		exMasConfig.setPrivateVehicleModes(privateVehicles);

		exMasConfig.setMinDrtCostPerKm(0.0);
		exMasConfig.setMinMaxDetourFactor(1.0);
		exMasConfig.setMinMaxWaitingTime(0.0);
		exMasConfig.setMinDrtAccessEgressDistance(0.0);

		exMasConfig.setSearchHorizon(600.0);
		exMasConfig.setMaxDetourFactor(1.5);
		exMasConfig.setMaxPoolingDegree(10);

		exMasConfig.setPtOptimizeDepartureTime(true);
	}
}
