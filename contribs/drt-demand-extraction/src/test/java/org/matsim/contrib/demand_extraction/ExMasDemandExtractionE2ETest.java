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
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.DemandExtractionModule;
import org.matsim.contrib.drt.run.DrtControlerCreator;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.examples.ExamplesUtils;

/**
 * End-to-end integration test for ExMas demand extraction.
 * 
 * Test approach:
 * - Uses the dvrp-grid network from MATSim examples (11x11 grid, 200m spacing)
 * - Creates test population with various person attributes (license, car
 * availability)
 * - Includes subtour structures (Home-Work-Home, Work-Lunch-Work)
 * - Runs WITH DRT in the config for a simple 1-iteration test
 * - Uses shutdown listener to generate ExMAS rides after simulation completes
 * - Verifies DRT requests are generated correctly with proper budgets and
 * grouping
 * 
 * Note: For a more realistic test without DRT in the simulation, see
 * ExMasKelheimE2ETest.
 */
public class ExMasDemandExtractionE2ETest {

	@Test
	void testDemandExtractionWithDvrpGridScenario() throws IOException {
		// Use persistent output directory for inspection
		Path testOutputDir = Path.of("test/output/exmas-e2e-test");
		Files.createDirectories(testOutputDir);
		// 1. Load base config from dvrp-grid example with proper DRT config groups
		URL scenarioUrl = ExamplesUtils.getTestScenarioURL("dvrp-grid");
		Config config = ConfigUtils.loadConfig(
				new URL(scenarioUrl, "one_shared_taxi_config.xml").toString(),
				new MultiModeDrtConfigGroup(),
				new DvrpConfigGroup(),
				new ExMasConfigGroup());

		// Remove otfvis module if present (not needed for this test)
		config.removeModule("otfvis");

		// 2. Override output directory
		config.controller().setOutputDirectory(testOutputDir.toString());
		config.controller()
				.setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
		
		// 3. Configure monetary constants for car and PT scoring
		configureMonetaryConstants(config);

		// 4. Create scenario with DRT route factory to handle DRT routes properly
		Scenario scenario = DrtControlerCreator.createScenarioWithDrtRouteFactory(config);
		ScenarioUtils.loadScenario(scenario);

		// 5. Enhance population with person attributes for testing
		enhancePopulationWithAttributes(scenario.getPopulation());

		// 6. Configure ExMas with algorithm parameters
		configureExMas(config);

		// 7. Run simulation with ExMas demand extraction and ride generation
		Controler controler = DrtControlerCreator.createControler(config, scenario, false);
		controler.addOverridingModule(new DemandExtractionModule());
		controler.run();

		// 8. Verify output files exist
		Path requestsFile = testOutputDir.resolve("drt_requests.csv");
		Path ridesFile = testOutputDir.resolve("exmas_rides.csv");
		Assertions.assertTrue(Files.exists(requestsFile), "DRT requests file should exist: " + requestsFile);
		Assertions.assertTrue(Files.exists(ridesFile), "ExMAS rides file should exist: " + ridesFile);

		// 9. Validate request and ride content
		validateRequests(requestsFile);
		ExMasConfigGroup exMasConfig = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		validateRides(ridesFile, exMasConfig);
		
		System.out.println("\n=== Test Output Location ===");
		System.out.println("Requests: " + requestsFile.toAbsolutePath());
		System.out.println("Rides: " + ridesFile.toAbsolutePath());
		System.out.println("============================\n");
	}

	/**
	 * Configures monetary constants for car and PT to ensure proper budget calculation.
	 * Sets utility-of-money parameters that affect mode choice scoring.
	 */
	private void configureMonetaryConstants(Config config) {
		ScoringConfigGroup scoring = config.scoring();
		
		// Set marginal utility of money (€^-1)
		// This converts monetary costs to utility values
		scoring.setMarginalUtilityOfMoney(1.0);
		
		scoring.setMarginalUtlOfWaitingPt_utils_hr(0.0);

		
		// Configure car mode monetary constant
		// Daily monetary constant represents fixed costs (€/day)
		ScoringConfigGroup.ModeParams carParams = scoring.getOrCreateModeParams(TransportMode.car);
		carParams.setDailyMonetaryConstant(-5.0); // €5/day for car ownership
		carParams.setMonetaryDistanceRate(-0.0002); // €0.0002/m = €0.20/km
		
		// Configure PT mode monetary constant
		ScoringConfigGroup.ModeParams ptParams = scoring.getOrCreateModeParams(TransportMode.pt);
		ptParams.setDailyMonetaryConstant(-2.0); // €2/day for PT pass
		ptParams.setMonetaryDistanceRate(-0.0001); // €0.0001/m = €0.10/km
	}
	
	/**
	 * Enhances the existing dvrp-grid population with person attributes needed for
	 * ExMas testing.
	 * Sets license and car availability attributes to test different scenarios:
	 * - Some passengers with car access (to test subtour detection)
	 * - Some without car (to test independent trip handling)
	 */
	private void enhancePopulationWithAttributes(Population population) {
		int personCount = 0;
		for (Person person : population.getPersons().values()) {
			// Alternate between different person types for testing
			int personType = personCount % 3;
			if (personType == 0) {
				// Car owner - should create subtour groups if trips form closed loops
				PersonUtils.setLicence(person, "yes");
				PersonUtils.setCarAvail(person, "always");
			} else if (personType == 1) {
				// No car - trips should be independent
				PersonUtils.setLicence(person, "no");
				PersonUtils.setCarAvail(person, "never");
			} else {
				// Sometimes car available - depends on household dynamics
				PersonUtils.setLicence(person, "yes");
				PersonUtils.setCarAvail(person, "sometimes");
			}
			personCount++;
		}
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

		// Set DRT routing mode (fallback when no DRT routing module exists)
		exMasConfig.setDrtRoutingMode(TransportMode.car);

		// Set private vehicle modes (for subtour detection)
		Set<String> privateVehicles = new HashSet<>();
		privateVehicles.add(TransportMode.car);
		privateVehicles.add("bike");
		exMasConfig.setPrivateVehicleModes(privateVehicles);

		// Set DRT service quality parameters for budget calculation
		exMasConfig.setMinDrtCostPerKm(0.0); // Best possible pricing
		exMasConfig.setMinMaxDetourFactor(1.0); // Direct route
		exMasConfig.setMinMaxWaitingTime(0.0); // No waiting
		exMasConfig.setMinDrtAccessEgressDistance(0.0); // Minimal access/egress distance
		
		// Set ExMAS algorithm parameters
		exMasConfig.setSearchHorizon(0.0); // 10 minutes time window for pairing
		exMasConfig.setOriginFlexibilityAbsolute(9000.0); // 15 minutes departure flexibility
		exMasConfig.setDestinationFlexibilityAbsolute(9000.0); // 15 minutes arrival flexibility
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
				if (parts.length != 9) {
					System.err.println("ERROR: Expected 9 fields but got " + parts.length);
					System.err.println("Line: " + line);
					System.err.println("Fields: " + java.util.Arrays.toString(parts));
				}
				Assertions.assertEquals(9, parts.length, "Each request should have 9 fields");

				String personId = parts[0];
				double budget = Double.parseDouble(parts[3]);
				personIds.add(personId);

				// Budget can be positive (DRT better), negative (DRT worse), or zero (equal)
				// Just verify it's a valid number
				Assertions.assertFalse(Double.isNaN(budget), "Budget should be a valid number");

				requestCount++;
			}
		}

		// Validate we have requests from the population (dvrp-grid has 10 passengers)
		Assertions.assertTrue(personIds.size() >= 3, "Should have requests from multiple persons");

		// Validate we got trip requests from the population
		Assertions.assertTrue(requestCount >= 3, "Should have multiple trip requests");

		// Test passed - all validations successful
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
			Assertions.assertTrue(header.contains("startTime"), "Header should contain startTime");
			Assertions.assertTrue(header.contains("duration"), "Header should contain duration");
			Assertions.assertTrue(header.contains("distance"), "Header should contain distance");

			String line;
			while ((line = reader.readLine()) != null) {
				String[] parts = line.split(",");
				Assertions.assertTrue(parts.length >= 7, "Each ride should have at least 7 fields");

				int degree = Integer.parseInt(parts[1]);
				int maxDegree = exMasConfig.getMaxPoolingDegree();
				Assertions.assertTrue(degree >= 1 && degree <= maxDegree,
						"Degree should be between 1 and " + maxDegree + " (max pooling degree)");
				
				ridesByDegree.put(degree, ridesByDegree.getOrDefault(degree, 0) + 1);
				
				double duration = Double.parseDouble(parts[4]);
				Assertions.assertTrue(duration >= 0, "Duration should be non-negative");
				
				double distance = Double.parseDouble(parts[5]);
				Assertions.assertTrue(distance >= 0, "Distance should be non-negative");

				rideCount++;
			}
		}

		// Validate we generated some rides
		Assertions.assertTrue(rideCount > 0, "Should have generated at least one ride");
		Assertions.assertTrue(ridesByDegree.getOrDefault(1, 0) > 0, "Should have generated at least one single-passenger ride");
		
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
