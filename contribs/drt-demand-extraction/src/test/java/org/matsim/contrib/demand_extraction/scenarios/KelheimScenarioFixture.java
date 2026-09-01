package org.matsim.contrib.demand_extraction.scenarios;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
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
import org.matsim.core.utils.io.IOUtils;
import org.matsim.examples.ExamplesUtils;

/**
 * Kelheim scenario fixture for ExMAS demand extraction. Lifts the setup that
 * was previously inlined in {@code ExMasKelheimE2ETest}: scenario URL load,
 * DRT scoring params, freight-agent filter, controler creation, and CSV
 * validation.
 *
 * <p>Note this fixture lives in {@code src/main/java} (not {@code src/test/java})
 * so runners can use it too — see Phase 5a.5 of the reference-fork plan.
 * Validation throws {@link AssertionError} on failure (no JUnit compile dep).
 */
public class KelheimScenarioFixture implements ExMasScenarioFixture {

	@Override
	public String getName() {
		return "kelheim";
	}

	@Override
	public Config createConfig(Path outputDir) throws IOException {
		Files.createDirectories(outputDir);

		URL scenarioUrl = ExamplesUtils.getTestScenarioURL("kelheim");
		Config config = ConfigUtils.loadConfig(
				new URL(scenarioUrl, "config.xml").toString(),
				new ExMasConfigGroup());

		config.controller().setOutputDirectory(outputDir.toString());
		config.controller().setOverwriteFileSetting(
				OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
		config.controller().setLastIteration(0);

		ExMasConfigGroup exMasConfig = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		String drtMode = exMasConfig.getDrtMode();

		// Match car's travel-time disutility for the synthetic DRT mode so the
		// budget calculation has a realistic baseline.
		if (!config.scoring().getModes().containsKey(drtMode)) {
			ScoringConfigGroup.ModeParams drtParams = new ScoringConfigGroup.ModeParams(drtMode);
			drtParams.setMarginalUtilityOfTraveling(-0.5);
			drtParams.setConstant(0.0);
			drtParams.setMonetaryDistanceRate(0.0);
			config.scoring().addModeParams(drtParams);
		} else {
			config.scoring().getModes().get(drtMode).setMarginalUtilityOfTraveling(-0.5);
		}

		// Kelheim uses duration-specific activity types (home_3600, work_28800, …).
		org.matsim.dsim.Activities.addScoringParams(config);

		applyExMasDefaults(exMasConfig);

		DemandExtractionConfigValidator.prepareConfigForDemandExtraction(config);
		return config;
	}

	private void applyExMasDefaults(ExMasConfigGroup exMasConfig) {
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
		exMasConfig.setMinDrtAccessEgressDistance(0.0);

		exMasConfig.setSearchHorizon(600.0);
		exMasConfig.setMaxDetourFactor(1.5);
		exMasConfig.setMaxPoolingDegree(10);

		exMasConfig.setPtOptimizeDepartureTime(true);

		// Pruning: state explicitly, pinned to this fixture's pre-2026-08-26 EFFECTIVE
		// behavior (it never set these before and silently inherited the old
		// ExMasConfigGroup class defaults: pruningDistanceSavingsLogScale=0.0,
		// pruningMode=COVERAGE_TOPK, pruningCoverageK=20). The class default is now
		// "no pruning" (RATIO_THRESHOLD + interDegreeKeepFraction=1.0), so an omitted key
		// here would make this fixture's full-Kelheim-scenario tests enumerate unpruned
		// and run orders of magnitude slower. (heuristicPruningEnabled was never the
		// pruning on/off switch -- pruningMode / pruningCoverageK /
		// pruningDistanceSavingsLogScale below are the real switches.)
		exMasConfig.setPruningDistanceSavingsLogScale(0.0);
		exMasConfig.setPruningMode(ExMasConfigGroup.PruningMode.COVERAGE_TOPK);
		exMasConfig.setPruningCoverageK(20);
	}

	@Override
	public Controler createControler(Config config) {
		Scenario scenario = DrtControlerCreator.createScenarioWithDrtRouteFactory(config);
		ScenarioUtils.loadScenario(scenario);

		// Freight agents have freight activities without link IDs and crash routing.
		scenario.getPopulation().getPersons().values().removeIf(person ->
				person.getSelectedPlan().getPlanElements().stream()
						.filter(org.matsim.api.core.v01.population.Activity.class::isInstance)
						.map(org.matsim.api.core.v01.population.Activity.class::cast)
						.anyMatch(act -> act.getType().startsWith("freight")));

		Controler controler = DrtControlerCreator.createControler(config, scenario, false);
		controler.addOverridingModule(new DemandExtractionModule());
		return controler;
	}

	@Override
	public void validateOutput(Config config, Path outputDir) throws IOException {
		String runId = config.controller().getRunId();
		Path drtDemandDir = outputDir.resolve("drt_demand");
		Path requestsFile = drtDemandDir.resolve(runId + ".drt_requests.csv");
		Path ridesFile = drtDemandDir.resolve(runId + ".exmas_rides.csv");

		check(Files.exists(requestsFile), "DRT requests file should exist: " + requestsFile);
		check(Files.exists(ridesFile), "ExMAS rides file should exist: " + ridesFile);

		validateRequests(requestsFile);
		ExMasConfigGroup exMasConfig = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		validateRides(ridesFile, exMasConfig);
	}

	private void validateRequests(Path requestsFile) throws IOException {
		Set<String> personIds = new HashSet<>();
		int requestCount = 0;

		try (BufferedReader reader = IOUtils.getBufferedReader(requestsFile.toString())) {
			String header = reader.readLine();
			check(header != null, "File should have header");
			check(header.contains("personId"), "Header should contain personId");
			check(header.contains("groupId"), "Header should contain groupId");
			check(header.contains("budget"), "Header should contain budget");

			String line;
			while ((line = reader.readLine()) != null) {
				String[] parts = line.split(",");
				// 39 baseline columns + 2 Extension-2 tag columns (requestTag, hubId)
				// + 3 Task-11 columns (hubLegRole, transferWaitSeconds, marginalUtilityOfMoney) = 44
				check(parts.length == 44,
						"Each request should have 44 fields, got " + parts.length);

				String personId = parts[1];
				double budget = Double.parseDouble(parts[6]);
				personIds.add(personId);

				check(!Double.isNaN(budget), "Budget should be a valid number");
				requestCount++;
			}
		}

		check(personIds.size() >= 10,
				"Should have requests from at least 10 persons in Kelheim scenario, got "
						+ personIds.size());
		check(requestCount >= 20,
				"Should have at least 20 trip requests in Kelheim scenario, got " + requestCount);
	}

	private void validateRides(Path ridesFile, ExMasConfigGroup exMasConfig) throws IOException {
		int rideCount = 0;
		Map<Integer, Integer> ridesByDegree = new HashMap<>();

		try (BufferedReader reader = IOUtils.getBufferedReader(ridesFile.toString())) {
			String header = reader.readLine();
			check(header != null, "File should have header");
			check(header.contains("rideIndex"), "Header should contain rideIndex");
			check(header.contains("degree"), "Header should contain degree");
			check(header.contains("requestIndices"), "Header should contain requestIndices");
			check(header.contains("remainingBudgets"), "Header should contain remainingBudgets");

			String line;
			while ((line = reader.readLine()) != null) {
				String[] parts = line.split(",");
				// 35 baseline columns (maxCostsPerKm dropped 2026-09-01) + 4 Extension-2
				// columns appended at the end of exmas_rides.csv: per-pax requestTags +
				// hubIds, per-ride peak_pax (Task 7.2) + reposTimeMeanOutgoing (Task 4)
				// = 39 total.
				check(parts.length == 39,
						"Each ride should have 39 fields, got " + parts.length);

				int degree = Integer.parseInt(parts[1]);
				int maxDegree = exMasConfig.getMaxPoolingDegree();
				check(degree >= 1 && degree <= maxDegree,
						"Degree should be between 1 and " + maxDegree + ", got " + degree);

				ridesByDegree.merge(degree, 1, Integer::sum);

				double duration = Double.parseDouble(parts[22]); // rideTravelTime (was 23 before the maxCostsPerKm drop)
				check(duration >= 0, "Duration should be non-negative, got " + duration);

				double distance = Double.parseDouble(parts[23]); // rideDistance (was 24 before the maxCostsPerKm drop)
				check(distance >= 0, "Distance should be non-negative, got " + distance);

				String budgetsStr = parts[14];
				check(!budgetsStr.trim().isEmpty(),
						"Remaining budgets should be present for all rides");

				rideCount++;
			}
		}

		check(rideCount > 0, "Should have generated at least one ride");
		check(ridesByDegree.getOrDefault(1, 0) > 0,
				"Should have generated single-passenger rides");
	}

	private static void check(boolean cond, String message) {
		if (!cond) {
			throw new AssertionError(message);
		}
	}
}
