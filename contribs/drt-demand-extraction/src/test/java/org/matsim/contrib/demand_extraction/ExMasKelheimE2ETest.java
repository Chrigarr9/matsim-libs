package org.matsim.contrib.demand_extraction;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
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
 * End-to-end integration test for ExMAS demand extraction using Kelheim
 * scenario.
 * 
 * Test approach:
 * - Uses Kelheim scenario WITHOUT DRT (only walk, bike, pt, car)
 * - Runs 5 iterations to warm up the network with realistic travel times
 * - After final iteration, generates ExMAS ride proposals as if DRT was
 * available
 * - Uses shutdown listener to ensure ride generation happens after all
 * iterations
 * 
 * The Kelheim scenario is a realistic small-town scenario with:
 * - Detailed network with PT infrastructure
 * - Real population data (1% sample)
 * - More complex trip patterns than the grid scenario
 */
public class ExMasKelheimE2ETest {

	@Test
	void testDemandExtractionWithKelheimScenario() throws IOException {
		// Use persistent output directory for inspection
		Path testOutputDir = Path.of("test/output/exmas-kelheim-e2e-test");
		Files.createDirectories(testOutputDir);

		// 1. Load Kelheim config WITHOUT DRT simulation
		// DRT config will be auto-configured by DemandExtractionModule
		URL scenarioUrl = ExamplesUtils.getTestScenarioURL("kelheim");
		Config config = ConfigUtils.loadConfig(
				new URL(scenarioUrl, "config.xml").toString(),
				new ExMasConfigGroup());

		// 2. Override output directory and run settings
		config.controller().setOutputDirectory(testOutputDir.toString());
		config.controller()
				.setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);

		// Run 5 iterations to warm up network travel times
		config.controller().setLastIteration(0);

		// 3. Configure scoring
		// Keep default scoring from Kelheim config - don't modify travel utilities as
		// this can cause NaN scores

		// Set all daily monetary constants to zero for testing
		// This eliminates fixed daily costs (e.g., car ownership, PT pass) from budget calculation
		config.scoring().getModes().values().forEach(modeParams -> {
			modeParams.setDailyMonetaryConstant(0.0);
		});

		// Configure DRT scoring parameters (will be auto-created by DemandExtractionModule if not present)
		// Set marginalUtilityOfTraveling to -0.5 to match car's travel time utility
		ExMasConfigGroup exMasConfigPreview = (ExMasConfigGroup) config.getModules().get(ExMasConfigGroup.GROUP_NAME);
		String drtMode = exMasConfigPreview != null ? exMasConfigPreview.getDrtMode() : "drt";
		
		if (!config.scoring().getModes().containsKey(drtMode)) {
			ScoringConfigGroup.ModeParams drtParams = new ScoringConfigGroup.ModeParams(drtMode);
			drtParams.setMarginalUtilityOfTraveling(-0.5); // Match car's travel time disutility
			drtParams.setConstant(0.0);
			drtParams.setMonetaryDistanceRate(0.0);
			config.scoring().addModeParams(drtParams);
		} else {
			config.scoring().getModes().get(drtMode).setMarginalUtilityOfTraveling(-0.5);
		}

		// Add activity params for all standard activity types (home_XXX, work_XXX,
		// etc.)
		// This is required for scenarios like Kelheim that use duration-specific
		// activity types
		org.matsim.dsim.Activities.addScoringParams(config);

		// 4. Configure ExMas algorithm parameters
		// This must be done BEFORE DrtControlerCreator because it sets up required DRT
		// config
		configureExMas(config);

		// 5. Set up required DRT/DVRP configs that DrtControlerCreator expects
		// Normally DemandExtractionModule would do this, but DrtControlerCreator needs
		// them earlier
		// Note: DemandExtractionModule.install() will detect existing configs and skip
		// re-adding them
		DemandExtractionModule.ensureRequiredConfigs(config);

		// 6. Create scenario with DRT route factory (needed for DRT routing)
		// DRT will NOT be simulated (no vehicles), only used for routing during demand
		// extraction
		Scenario scenario = DrtControlerCreator.createScenarioWithDrtRouteFactory(config);
		ScenarioUtils.loadScenario(scenario);

		// Filter out freight agents (they have freight activities without link IDs that
		// cause routing failures)
		scenario.getPopulation().getPersons().values()
				.removeIf(person -> person.getSelectedPlan().getPlanElements().stream()
						.filter(org.matsim.api.core.v01.population.Activity.class::isInstance)
						.map(org.matsim.api.core.v01.population.Activity.class::cast)
						.anyMatch(act -> act.getType().startsWith("freight")));

		// 7. Run simulation with ExMas demand extraction
		// Use DrtControlerCreator for proper DRT routing setup (but no DRT
		// vehicles/simulation)
		Controler controler = DrtControlerCreator.createControler(config, scenario, false);
		controler.addOverridingModule(new DemandExtractionModule());

		controler.run();

		// 8. Verify output files exist
		// Files are prefixed with the run ID from the scenario config
		String runId = config.controller().getRunId();
		Path requestsFile = testOutputDir.resolve(runId + ".drt_requests.csv");
		Path ridesFile = testOutputDir.resolve(runId + ".exmas_rides.csv");
		Assertions.assertTrue(Files.exists(requestsFile), "DRT requests file should exist: " + requestsFile);
		Assertions.assertTrue(Files.exists(ridesFile), "ExMAS rides file should exist: " + ridesFile);

		// 8. Validate request and ride content
		validateRequests(requestsFile);
		ExMasConfigGroup exMasConfig = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		validateRides(ridesFile, exMasConfig);

		System.out.println("\n=== Kelheim Test Output Location ===");
		System.out.println("Requests: " + requestsFile.toAbsolutePath());
		System.out.println("Rides: " + ridesFile.toAbsolutePath());
		System.out.println("====================================\n");
	}

	private void configureExMas(Config config) {
		ExMasConfigGroup exMasConfig = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);

		// Set DRT mode
		exMasConfig.setDrtMode("drt");

		// Set baseline modes
		Set<String> baseModes = new HashSet<>();
		baseModes.add(TransportMode.car);
		baseModes.add(TransportMode.pt);
		baseModes.add(TransportMode.walk);
		baseModes.add(TransportMode.bike);
		exMasConfig.setBaseModes(baseModes);

		// Set DRT routing mode
		exMasConfig.setDrtRoutingMode(TransportMode.car);

		// Set private vehicle modes
		Set<String> privateVehicles = new HashSet<>();
		privateVehicles.add(TransportMode.car);
		privateVehicles.add(TransportMode.bike);
		exMasConfig.setPrivateVehicleModes(privateVehicles);

		// Set DRT service quality parameters for budget calculation
		exMasConfig.setMinDrtCostPerKm(0.2);
		exMasConfig.setMinMaxDetourFactor(1.0);
		exMasConfig.setMinMaxWaitingTime(0.0);
		exMasConfig.setMinDrtAccessEgressDistance(0.0);

		// Set ExMAS algorithm parameters - more conservative for larger scenario
		exMasConfig.setSearchHorizon(0.0); // No time window for pairing (instant matching)
		exMasConfig.setMaxDetourFactor(1.5);
		exMasConfig.setOriginFlexibilityAbsolute(0.0); // 0 minutes departure flexibility
		exMasConfig.setDestinationFlexibilityAbsolute(0.0); // 15 minutes arrival flexibility
		exMasConfig.setMaxPoolingDegree(2); // Allow up to 10 passengers

		// Note: DRT config and scoring params are now auto-configured by
		// DemandExtractionModule
	}

	private void validateRequests(Path requestsFile) throws IOException {
		Set<String> personIds = new HashSet<>();
		int requestCount = 0;

		try (BufferedReader reader = IOUtils.getBufferedReader(requestsFile.toString())) {
			String header = reader.readLine();
			Assertions.assertNotNull(header, "File should have header");
			Assertions.assertTrue(header.contains("personId"), "Header should contain personId");
			Assertions.assertTrue(header.contains("groupId"), "Header should contain groupId");
			Assertions.assertTrue(header.contains("budget"), "Header should contain budget");

			String line;
			while ((line = reader.readLine()) != null) {
				String[] parts = line.split(",");
				Assertions.assertEquals(9, parts.length, "Each request should have 9 fields");

				String personId = parts[0];
				double budget = Double.parseDouble(parts[3]);
				personIds.add(personId);

				// Budget should be a valid number
				Assertions.assertFalse(Double.isNaN(budget), "Budget should be a valid number");

				requestCount++;
			}
		}

		// Kelheim 1% sample should have many persons
		Assertions.assertTrue(personIds.size() >= 10,
				"Should have requests from at least 10 persons in Kelheim scenario");
		Assertions.assertTrue(requestCount >= 20,
				"Should have at least 20 trip requests in Kelheim scenario");

		System.out.println("\n=== Request Statistics ===");
		System.out.println("Total persons: " + personIds.size());
		System.out.println("Total requests: " + requestCount);
		System.out.println("==========================\n");
	}

	private void validateRides(Path ridesFile, ExMasConfigGroup exMasConfig) throws IOException {
		int rideCount = 0;
		Map<Integer, Integer> ridesByDegree = new HashMap<>();

		try (BufferedReader reader = IOUtils.getBufferedReader(ridesFile.toString())) {
			String header = reader.readLine();
			Assertions.assertNotNull(header, "File should have header");
			Assertions.assertTrue(header.contains("rideIndex"), "Header should contain rideIndex");
			Assertions.assertTrue(header.contains("degree"), "Header should contain degree");
			Assertions.assertTrue(header.contains("requestIndices"), "Header should contain requestIndices");
			Assertions.assertTrue(header.contains("remainingBudgets"), "Header should contain remainingBudgets");

			String line;
			while ((line = reader.readLine()) != null) {
				String[] parts = line.split(",");
				Assertions.assertTrue(parts.length >= 7, "Each ride should have at least 7 fields");

				int degree = Integer.parseInt(parts[1]);
				int maxDegree = exMasConfig.getMaxPoolingDegree();
				Assertions.assertTrue(degree >= 1 && degree <= maxDegree,
						"Degree should be between 1 and " + maxDegree);

				ridesByDegree.put(degree, ridesByDegree.getOrDefault(degree, 0) + 1);

				double duration = Double.parseDouble(parts[4]);
				Assertions.assertTrue(duration >= 0, "Duration should be non-negative");

				double distance = Double.parseDouble(parts[5]);
				Assertions.assertTrue(distance >= 0, "Distance should be non-negative");

				// Verify remaining budgets are present (field 8)
				if (parts.length > 8) {
					String budgetsStr = parts[8];
					Assertions.assertFalse(budgetsStr.trim().isEmpty(),
							"Remaining budgets should be present for all rides");
				}

				rideCount++;
			}
		}

		// Validate we generated rides
		Assertions.assertTrue(rideCount > 0, "Should have generated at least one ride");
		Assertions.assertTrue(ridesByDegree.getOrDefault(1, 0) > 0, "Should have generated single-passenger rides");

		System.out.println("\n=== Ride Generation Results ===");
		System.out.println("Total rides: " + rideCount);
		System.out.println("Single-passenger rides (degree 1): " + ridesByDegree.getOrDefault(1, 0));
		int sharedRides = rideCount - ridesByDegree.getOrDefault(1, 0);
		System.out.println("Shared rides (degree 2+): " + sharedRides);
		for (int degree = 2; degree <= exMasConfig.getMaxPoolingDegree(); degree++) {
			int count = ridesByDegree.getOrDefault(degree, 0);
			if (count > 0) {
				System.out.println("  - Degree " + degree + ": " + count);
			}
		}
		System.out.println("================================\n");
	}
}
