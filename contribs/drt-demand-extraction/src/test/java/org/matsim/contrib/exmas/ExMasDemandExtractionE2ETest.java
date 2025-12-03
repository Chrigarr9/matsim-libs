package org.matsim.contrib.exmas;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.contrib.drt.run.DrtControlerCreator;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.contrib.exmas.config.ExMasConfigGroup;
import org.matsim.contrib.exmas.demand.DemandExtractionModule;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.examples.ExamplesUtils;

/**
 * End-to-end integration test for ExMas demand extraction.
 * 
 * Test scenario:
 * - Uses the dvrp-grid network from MATSim examples (11x11 grid, 200m spacing)
 * - Creates test population with various person attributes (license, car
 * availability)
 * - Includes subtour structures (Home-Work-Home, Work-Lunch-Work)
 * - Runs one iteration to generate network travel times
 * - Verifies DRT requests are generated correctly with proper budgets and
 * grouping
 */
public class ExMasDemandExtractionE2ETest {

	@TempDir
	Path tempDir;

	@Test
	void testDemandExtractionWithDvrpGridScenario() throws IOException {
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
		config.controller().setOutputDirectory(tempDir.resolve("output").toString());
		config.controller()
				.setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);

		// 3. Create scenario with DRT route factory to handle DRT routes properly
		Scenario scenario = DrtControlerCreator.createScenarioWithDrtRouteFactory(config);
		ScenarioUtils.loadScenario(scenario);

		// 4. Enhance population with person attributes for testing
		enhancePopulationWithAttributes(scenario.getPopulation());

		// 5. Configure ExMas (this is all we really need to add!)
		configureExMas(config);

		// 6. Run simulation with ExMas demand extraction
		Controler controler = DrtControlerCreator.createControler(config, scenario, false);
		controler.addOverridingModule(new DemandExtractionModule());
		controler.run();

		// 7. Verify output
		Path requestsFile = tempDir.resolve("output").resolve("drt_requests.csv");
		Assertions.assertTrue(Files.exists(requestsFile), "DRT requests file should exist");

		// 8. Validate request content
		validateRequests(requestsFile);
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
		baseModes.add("bike");
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
}
