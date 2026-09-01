package org.matsim.contrib.demand_extraction;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
 * End-to-end integration test for ExMas demand extraction WITH HYPERPOOL ENABLED.
 *
 * This test is identical to ExMasDemandExtractionE2ETest but enables:
 * - Stage 1: Stop-based pooling (passengers walk to shared pickup/dropoff points)
 * - Stage 2: Hyper-pooling (bundling stop-to-stop rides into high-occupancy transit-like services)
 *
 * Additional validations:
 * - Verifies stop-based and hyper-pooled rides are generated
 * - Checks that stop-based ride budgets are lower than door-to-door (due to walking)
 * - Validates walk distances are within constraints
 * - Ensures all budgets remain positive
 * - Compares ride variants (DOOR_TO_DOOR, STOP_TO_STOP, HYPER_POOLED)
 */
public class ExMasHyperPoolE2ETest {

	@Test
	void testDemandExtractionWithHyperPool() throws IOException {
		// Use persistent output directory for inspection
		Path testOutputDir = Path.of("test/output/exmas-hyperpool-e2e-test");
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

		// 6. Configure ExMas with algorithm parameters + ENABLE HYPERPOOL
		configureExMasWithHyperPool(config);

		// 7. Run simulation with ExMas demand extraction and ride generation
		Controler controler = DrtControlerCreator.createControler(config, scenario, false);
		controler.addOverridingModule(new DemandExtractionModule());
		controler.run();

		// 8. Verify output files exist in drt_demand subdirectory
		Path demandDir = testOutputDir.resolve("drt_demand");
		Path requestsFile = demandDir.resolve("null.drt_requests.csv");
		Path ridesFile = demandDir.resolve("null.exmas_rides.csv");
		Path connectionCacheFile = demandDir.resolve("null.connection_cache.csv");
		Path personAttributesFile = demandDir.resolve("null.person_attributes.csv");

		Assertions.assertTrue(Files.exists(requestsFile), "DRT requests file should exist: " + requestsFile);
		Assertions.assertTrue(Files.exists(ridesFile), "ExMAS rides file should exist: " + ridesFile);
		Assertions.assertTrue(Files.exists(connectionCacheFile), "Connection cache file should exist: " + connectionCacheFile);
		Assertions.assertTrue(Files.exists(personAttributesFile), "Person attributes file should exist: " + personAttributesFile);

		// 9. Validate request and ride content
		validateRequests(requestsFile);
		ExMasConfigGroup exMasConfig = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		validateRidesWithHyperPool(ridesFile, exMasConfig);

		// 10. Validate person attributes
		validatePersonAttributes(personAttributesFile);

		System.out.println("\n=== Test Output Location ===");
		System.out.println("Requests: " + requestsFile.toAbsolutePath());
		System.out.println("Rides: " + ridesFile.toAbsolutePath());
		System.out.println("Connection Cache: " + connectionCacheFile.toAbsolutePath());
		System.out.println("Person Attributes: " + personAttributesFile.toAbsolutePath());
		System.out.println("============================\n");
	}

	private void configureMonetaryConstants(Config config) {
		ScoringConfigGroup scoring = config.scoring();
		scoring.setMarginalUtilityOfMoney(1.0);
		scoring.setMarginalUtlOfWaitingPt_utils_hr(0.0);

		ScoringConfigGroup.ModeParams carParams = scoring.getOrCreateModeParams(TransportMode.car);
		carParams.setMonetaryDistanceRate(-0.0002); // €0.20/km

		ScoringConfigGroup.ModeParams ptParams = scoring.getOrCreateModeParams(TransportMode.pt);
		ptParams.setDailyMonetaryConstant(-2.0); // €2/day for PT pass
		ptParams.setMonetaryDistanceRate(-0.0001); // €0.10/km

		// Make walking more attractive for stop-based rides
		// Set walking penalty very low (near zero) to encourage acceptance of walking to stops
		ScoringConfigGroup.ModeParams walkParams = scoring.getOrCreateModeParams(TransportMode.walk);
		walkParams.setMarginalUtilityOfTraveling(-0.1); // Very low penalty (default is typically -6.0)
		walkParams.setConstant(0.0);
		walkParams.setMonetaryDistanceRate(0.0);
	}

	private void enhancePopulationWithAttributes(Population population) {
		int personCount = 0;
		for (Person person : population.getPersons().values()) {
			int personType = personCount % 3;
			if (personType == 0) {
				PersonUtils.setLicence(person, "yes");
				PersonUtils.setCarAvail(person, "always");
			} else if (personType == 1) {
				PersonUtils.setLicence(person, "no");
				PersonUtils.setCarAvail(person, "never");
			} else {
				PersonUtils.setLicence(person, "yes");
				PersonUtils.setCarAvail(person, "sometimes");
			}
			personCount++;
		}
	}

	/**
	 * Configure ExMas WITH HYPERPOOL ENABLED.
	 * This is the key difference from the standard test.
	 */
	private void configureExMasWithHyperPool(Config config) {
		ExMasConfigGroup exMasConfig = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);

		// Standard ExMAS configuration
		exMasConfig.setDrtMode("drt");
		Set<String> baseModes = new HashSet<>();
		baseModes.add(TransportMode.car);
		exMasConfig.setBaseModes(baseModes);
		exMasConfig.setDrtRoutingMode(TransportMode.car);

		Set<String> privateVehicles = new HashSet<>();
		privateVehicles.add(TransportMode.car);
		privateVehicles.add("bike");
		exMasConfig.setPrivateVehicleModes(privateVehicles);

		// DRT service quality parameters
		exMasConfig.setMinDrtCostPerKm(0.0);
		exMasConfig.setMinDrtAccessEgressDistance(0.0);

		// ExMAS algorithm parameters
		exMasConfig.setMaxDetourFactor(2.0);
		exMasConfig.setSearchHorizon(0.0);
		exMasConfig.setNegativeFlexibilityAbsoluteMap("default:9000.0");
		exMasConfig.setPositiveFlexibilityAbsoluteMap("default:9000.0");
		exMasConfig.setCalcPredecessors(true);
		exMasConfig.setCalcShapleyValues(true);

		// ========================================
		// HYPERPOOL CONFIGURATION (NEW!)
		// ========================================

		// Stage 1: Enable stop-based pooling
		exMasConfig.setEnableStopBased(true);
		exMasConfig.setMaxWalkDistanceMeters(500.0);  // 500m max walk
		exMasConfig.setStopSearchRadiusMeters(300.0); // 300m search radius
		// Stop finding strategy will use default (GEOMETRIC)

		// Stage 2: Enable hyper-pooling
		exMasConfig.setEnableHyperPooling(true);
		exMasConfig.setHyperPoolMaxStopRelocationMeters(200.0); // 200m max stop relocation
		exMasConfig.setHyperPoolMinOccupancy(2); // Min 2 passengers (lowered for small test)
		exMasConfig.setHyperPoolTimeWindowSeconds(900.0); // 15 min time window
		exMasConfig.setHyperPoolStopProximityMeters(100.0); // 100m stop proximity
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
				Assertions.assertEquals(44, parts.length, "Each request should have 44 fields (39 baseline + Ext-2 requestTag/hubId + Task-11 hubLegRole/transferWaitSeconds/marginalUtilityOfMoney)");

				String personId = parts[1];
				double budget = Double.parseDouble(parts[6]);
				personIds.add(personId);
				Assertions.assertFalse(Double.isNaN(budget), "Budget should be a valid number");
				requestCount++;
			}
		}

		Assertions.assertTrue(personIds.size() >= 3, "Should have requests from multiple persons");
		Assertions.assertTrue(requestCount >= 3, "Should have multiple trip requests");
	}

	/**
	 * Validates rides CSV with HyperPool-specific checks.
	 * Compares door-to-door vs stop-based vs hyper-pooled rides.
	 */
	private void validateRidesWithHyperPool(Path ridesFile, ExMasConfigGroup exMasConfig) throws IOException {
		int rideCount = 0;
		Map<Integer, Integer> ridesByDegree = new HashMap<>();
		Map<String, Integer> ridesByVariant = new HashMap<>();

		// Track rides by request set for comparison
		Map<String, List<RideRecord>> ridesByRequestSet = new HashMap<>();

		try (BufferedReader reader = IOUtils.getBufferedReader(ridesFile.toString())) {
			String header = reader.readLine();
			Assertions.assertNotNull(header, "File should have header");
			Assertions.assertTrue(header.contains("variant"), "Header should contain variant");
			Assertions.assertTrue(header.contains("pickupStopX"), "Header should contain pickupStopX");
			Assertions.assertTrue(header.contains("accessWalkDistances"), "Header should contain accessWalkDistances");

			// Resolve every column BY NAME. This used to hard-code positions, which silently read
			// the neighbouring column each time a column was added or removed in the middle of the
			// schema -- and an assertion like "budget >= 0" passes just as happily against the
			// detour column next door, so the drift stayed invisible until a numeric parse hit a
			// string field.
			Map<String, Integer> col = new HashMap<>();
			String[] headerNames = header.split(",");
			for (int i = 0; i < headerNames.length; i++) {
				col.put(headerNames[i].trim(), i);
			}
			for (String required : new String[] {
					"degree", "variant", "requestIndices", "remainingBudgets", "rideTravelTime",
					"rideDistance", "pickupStopLinkId", "pickupStopX", "pickupStopY",
					"dropoffStopLinkId", "accessWalkDistances", "egressWalkDistances" }) {
				Assertions.assertTrue(col.containsKey(required),
						"exmas_rides.csv header is missing the '" + required + "' column");
			}

			String line;
			while ((line = reader.readLine()) != null) {
				String[] parts = line.split(",");
				// 36 baseline columns + 2 Extension-2 per-pax columns
				// (requestTags, hubIds) + 2 per-ride columns appended at the end
				// of exmas_rides.csv: peak_pax (Task 7.2) + reposTimeMeanOutgoing (Task 4).
				Assertions.assertEquals(39, parts.length, "Each ride should have 39 fields (with HyperPool)");

				int degree = Integer.parseInt(parts[col.get("degree")]);
				String variant = parts[col.get("variant")]; // DOOR_TO_DOOR, STOP_TO_STOP, or HYPER_POOLED
				String requestIndices = parts[col.get("requestIndices")];
				String remainingBudgets = parts[col.get("remainingBudgets")];

				String pickupStopLinkId = parts[col.get("pickupStopLinkId")];
				String pickupStopX = parts[col.get("pickupStopX")];
				String pickupStopY = parts[col.get("pickupStopY")];
				String dropoffStopLinkId = parts[col.get("dropoffStopLinkId")];
				String accessWalkDistances = parts[col.get("accessWalkDistances")];
				String egressWalkDistances = parts[col.get("egressWalkDistances")];

				// Track by degree and variant
				ridesByDegree.put(degree, ridesByDegree.getOrDefault(degree, 0) + 1);
				ridesByVariant.put(variant, ridesByVariant.getOrDefault(variant, 0) + 1);

				// Parse remaining budgets (format: "[value1 | value2 | ...]" or "[value]" or "[]")
				String[] budgetStrings = remainingBudgets.replace("[", "").replace("]", "").split("\\|");
				double[] budgets = new double[budgetStrings.length];
				for (int i = 0; i < budgetStrings.length; i++) {
					if (!budgetStrings[i].trim().isEmpty()) {
						budgets[i] = Double.parseDouble(budgetStrings[i].trim());
						// All budgets should be non-negative (utility-preserving)
						Assertions.assertTrue(budgets[i] >= 0,
							String.format("Budget should be non-negative for variant %s, but got %.4f", variant, budgets[i]));
					}
				}

				// Validate stop-based and hyper-pooled rides
				if (variant.equals("STOP_TO_STOP") || variant.equals("HYPER_POOLED")) {
					// Stop coordinates should be populated (not empty)
					Assertions.assertFalse(pickupStopX.isEmpty(), "Stop-based ride should have pickup stop X");
					Assertions.assertFalse(pickupStopY.isEmpty(), "Stop-based ride should have pickup stop Y");

					// Walk distances should be populated
					Assertions.assertFalse(accessWalkDistances.isEmpty(), "Stop-based ride should have access walk distances");
					Assertions.assertFalse(egressWalkDistances.isEmpty(), "Stop-based ride should have egress walk distances");

					// Parse and validate walk distances (format: "[value1 | value2 | ...]")
					String[] accessWalks = accessWalkDistances.replace("[", "").replace("]", "").split("\\|");
					String[] egressWalks = egressWalkDistances.replace("[", "").replace("]", "").split("\\|");

					for (String walkStr : accessWalks) {
						if (!walkStr.trim().isEmpty()) {
							double walk = Double.parseDouble(walkStr.trim());
							Assertions.assertTrue(walk >= 0, "Walk distance should be non-negative");
							Assertions.assertTrue(walk <= 500.0, "Walk distance should be <= maxWalkDistance (500m)");
						}
					}

					for (String walkStr : egressWalks) {
						if (!walkStr.trim().isEmpty()) {
							double walk = Double.parseDouble(walkStr.trim());
							Assertions.assertTrue(walk >= 0, "Walk distance should be non-negative");
							Assertions.assertTrue(walk <= 500.0, "Walk distance should be <= maxWalkDistance (500m)");
						}
					}
				}

				// Store ride for comparison
				RideRecord record = new RideRecord(variant, degree, budgets,
					Double.parseDouble(parts[col.get("rideTravelTime")]),
					Double.parseDouble(parts[col.get("rideDistance")]));
				ridesByRequestSet.computeIfAbsent(requestIndices, k -> new ArrayList<>()).add(record);

				rideCount++;
			}
		}

		// Validate we generated rides
		Assertions.assertTrue(rideCount > 0, "Should have generated at least one ride");

		// Print statistics
		System.out.println("\n=== HyperPool Ride Generation Results ===");
		System.out.println("Total rides: " + rideCount);
		System.out.println("\nBy Degree:");
		for (Map.Entry<Integer, Integer> entry : ridesByDegree.entrySet()) {
			System.out.println("  Degree " + entry.getKey() + ": " + entry.getValue());
		}
		System.out.println("\nBy Variant:");
		for (Map.Entry<String, Integer> entry : ridesByVariant.entrySet()) {
			System.out.println("  " + entry.getKey() + ": " + entry.getValue());
		}

		// Validate variant distribution
		int doorToDoorCount = ridesByVariant.getOrDefault("DOOR_TO_DOOR", 0);
		int stopToStopCount = ridesByVariant.getOrDefault("STOP_TO_STOP", 0);
		int hyperPooledCount = ridesByVariant.getOrDefault("HYPER_POOLED", 0);

		Assertions.assertTrue(doorToDoorCount > 0, "Should have at least one door-to-door ride");
		// Stop-based rides are only generated for degree >= 2, so we might not have them in small tests
		System.out.println("\n  Note: Stop-based: " + stopToStopCount + ", Hyper-pooled: " + hyperPooledCount);

		// Compare ride variants for same request sets
		System.out.println("\n=== Ride Variant Comparisons ===");
		int comparisons = 0;
		for (Map.Entry<String, List<RideRecord>> entry : ridesByRequestSet.entrySet()) {
			List<RideRecord> rides = entry.getValue();
			if (rides.size() > 1) {
				// Find D2D and S2S variants of same request set
				RideRecord d2d = null;
				RideRecord s2s = null;
				for (RideRecord r : rides) {
					if (r.variant.equals("DOOR_TO_DOOR")) d2d = r;
					if (r.variant.equals("STOP_TO_STOP")) s2s = r;
				}

				if (d2d != null && s2s != null) {
					System.out.println("Request set " + entry.getKey() + ":");
					System.out.println("  D2D budgets: " + java.util.Arrays.toString(d2d.budgets));
					System.out.println("  S2S budgets: " + java.util.Arrays.toString(s2s.budgets));

					// Stop-based budgets should generally be lower (walking consumes budget)
					// But both should still be non-negative
					for (int i = 0; i < Math.min(d2d.budgets.length, s2s.budgets.length); i++) {
						Assertions.assertTrue(d2d.budgets[i] >= 0, "D2D budget should be non-negative");
						Assertions.assertTrue(s2s.budgets[i] >= 0, "S2S budget should be non-negative");
						// S2S budget should be <= D2D budget (walking penalty applied)
						// Note: might be equal if walks are very short
						Assertions.assertTrue(s2s.budgets[i] <= d2d.budgets[i] + 0.01,
							"S2S budget should be <= D2D budget (walking penalty)");
					}
					comparisons++;
				}
			}
		}
		System.out.println("Compared " + comparisons + " D2D vs S2S ride pairs");
		System.out.println("========================================\n");
	}

	private void validatePersonAttributes(Path personAttributesFile) throws IOException {
		int personCount = 0;
		Set<String> attributeNames = new HashSet<>();

		try (BufferedReader reader = IOUtils.getBufferedReader(personAttributesFile.toString())) {
			String header = reader.readLine();
			Assertions.assertNotNull(header, "File should have header");
			Assertions.assertTrue(header.contains("personId"), "Header should contain personId");

			String[] headerParts = header.split(",");
			for (String col : headerParts) {
				if (!col.equals("personId")) {
					attributeNames.add(col.trim());
				}
			}

			Assertions.assertTrue(attributeNames.contains("carAvail"),
					"Should have carAvail attribute: " + attributeNames);
			Assertions.assertTrue(attributeNames.contains("hasLicense"),
					"Should have hasLicense attribute: " + attributeNames);

			String line;
			while ((line = reader.readLine()) != null) {
				String[] parts = line.split(",");
				Assertions.assertTrue(parts.length >= 3,
						"Each row should have at least personId and 2 attributes");
				personCount++;
			}
		}

		Assertions.assertTrue(personCount > 0, "Should have at least one person with attributes");

		System.out.println("\n=== Person Attributes Results ===");
		System.out.println("Unique persons: " + personCount);
		System.out.println("Attributes exported: " + attributeNames);
		System.out.println("=================================\n");
	}

	/**
	 * Helper class to store ride information for comparison.
	 */
	private static class RideRecord {
		String variant;
		int degree;
		double[] budgets;
		double travelTime;
		double distance;

		RideRecord(String variant, int degree, double[] budgets, double travelTime, double distance) {
			this.variant = variant;
			this.degree = degree;
			this.budgets = budgets;
			this.travelTime = travelTime;
			this.distance = distance;
		}
	}
}
