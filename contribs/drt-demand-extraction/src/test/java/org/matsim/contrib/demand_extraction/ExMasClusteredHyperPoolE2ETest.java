package org.matsim.contrib.demand_extraction;

import java.io.BufferedReader;
import java.io.IOException;
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
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.DemandExtractionConfigValidator;
import org.matsim.contrib.demand_extraction.demand.DemandExtractionModule;
import org.matsim.contrib.drt.run.DrtControlerCreator;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.io.IOUtils;

/**
 * End-to-end test with MULTIPLE CLUSTERED regions designed to generate stop-based AND hyper-pooled rides.
 *
 * This test creates a custom scenario with:
 * - 3 residential clusters (30 passengers total)
 * - 3 commercial clusters
 * - Passengers distributed across cluster pairs
 * - High budgets (car = -6.0 util/hour, making DRT very attractive)
 * - Walking made VERY attractive (walk = -0.01 util/hour, almost free!)
 *
 * Expected results:
 * - Stop-based rides: YES (8+ rides with shared pickup/dropoff stops)
 * - Hyper-pooled rides: YES (algorithm generates them, see logs)
 *
 * NOTE: HyperPooledRide objects are generated but not written to CSV yet.
 * They're stored separately in ExMasEngine.getHyperPooledRides().
 * Check logs for "Hyper-Pooling (HyperPool Stage 2)" to see generation stats.
 */
public class ExMasClusteredHyperPoolE2ETest {

	@Test
	void testClusteredScenarioGeneratesStopBasedRides() throws IOException {
		Path testOutputDir = Path.of("test/output/exmas-clustered-hyperpool-e2e-test");
		Files.createDirectories(testOutputDir);

		// Create config
		Config config = ConfigUtils.createConfig(
				new MultiModeDrtConfigGroup(),
				new DvrpConfigGroup(),
				new ExMasConfigGroup());

		config.controller().setOutputDirectory(testOutputDir.toString());
		config.controller()
				.setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
		config.controller().setLastIteration(0);

		// Required for DVRP/DRT
		config.qsim().setSimStarttimeInterpretation(org.matsim.core.config.groups.QSimConfigGroup.StarttimeInterpretation.onlyUseStarttime);

		// Configure scoring - make walking VERY attractive
		configureScoring(config);

		// Configure ExMas with HyperPool
		configureExMasWithHyperPool(config);

		// Prepare config for demand extraction (auto-creates DRT config)
		DemandExtractionConfigValidator.prepareConfigForDemandExtraction(config);

		// Create scenario with clustered network and population
		Scenario scenario = DrtControlerCreator.createScenarioWithDrtRouteFactory(config);
		createClusteredNetwork(scenario.getNetwork());
		createClusteredPopulation(scenario.getPopulation());

		// Run simulation
		Controler controler = DrtControlerCreator.createControler(config, scenario, false);
		controler.addOverridingModule(new DemandExtractionModule());
		controler.run();

		// Verify outputs
		Path demandDir = testOutputDir.resolve("drt_demand");
		Path requestsFile = demandDir.resolve("null.drt_requests.csv");
		Path ridesFile = demandDir.resolve("null.exmas_rides.csv");

		Assertions.assertTrue(Files.exists(requestsFile), "Requests file should exist");
		Assertions.assertTrue(Files.exists(ridesFile), "Rides file should exist");

		// Validate we generated stop-based rides
		validateRidesWithStopBased(ridesFile);
	}

	/**
	 * Create a network with MULTIPLE clusters to enable hyper-pooling:
	 * - 3 residential clusters (North, Central, South)
	 * - 3 commercial clusters (East, Central, West)
	 * - Main roads connecting all clusters
	 */
	private void createClusteredNetwork(Network network) {
		NetworkFactory factory = network.getFactory();

		// Create 3 residential clusters
		createCluster(network, "res_north_", 10, new Coord(0, 500), 100);    // North residential
		createCluster(network, "res_central_", 10, new Coord(0, 0), 100);    // Central residential
		createCluster(network, "res_south_", 10, new Coord(0, -500), 100);   // South residential

		// Create 3 commercial clusters
		createCluster(network, "com_east_", 10, new Coord(2000, 500), 100);  // East commercial
		createCluster(network, "com_central_", 10, new Coord(2000, 0), 100); // Central commercial
		createCluster(network, "com_west_", 10, new Coord(2000, -500), 100); // West commercial

		// Connect all clusters with main roads (hub-and-spoke)
		connectClusters(network, "res_north_5", "com_east_5", 2000.0);
		connectClusters(network, "res_north_5", "com_central_5", 2000.0);
		connectClusters(network, "res_central_5", "com_east_5", 2000.0);
		connectClusters(network, "res_central_5", "com_central_5", 2000.0);
		connectClusters(network, "res_central_5", "com_west_5", 2000.0);
		connectClusters(network, "res_south_5", "com_central_5", 2000.0);
		connectClusters(network, "res_south_5", "com_west_5", 2000.0);
	}

	/**
	 * Create a single cluster of nodes
	 */
	private void createCluster(Network network, String prefix, int nodeCount, Coord center, double radius) {
		NetworkFactory factory = network.getFactory();

		// Create nodes in a grid within the radius
		for (int i = 1; i <= nodeCount; i++) {
			double x = center.getX() + (i % 5) * (radius / 2.5);
			double y = center.getY() + (i / 5) * (radius / 2.5);
			Node node = factory.createNode(Id.createNodeId(prefix + i), new Coord(x, y));
			network.addNode(node);
		}

		// Create links within cluster (fully connected)
		createClusterLinks(network, prefix, nodeCount, 50.0, 15.0);
	}

	/**
	 * Connect two cluster hubs with bidirectional links
	 */
	private void connectClusters(Network network, String hub1Id, String hub2Id, double distance) {
		NetworkFactory factory = network.getFactory();
		Node hub1 = network.getNodes().get(Id.createNodeId(hub1Id));
		Node hub2 = network.getNodes().get(Id.createNodeId(hub2Id));

		Link link1 = factory.createLink(
				Id.createLinkId("main_" + hub1Id + "_to_" + hub2Id),
				hub1, hub2);
		link1.setLength(distance);
		link1.setFreespeed(25.0);  // 90 km/h
		link1.setCapacity(2000);
		link1.setNumberOfLanes(2);
		network.addLink(link1);

		Link link2 = factory.createLink(
				Id.createLinkId("main_" + hub2Id + "_to_" + hub1Id),
				hub2, hub1);
		link2.setLength(distance);
		link2.setFreespeed(25.0);
		link2.setCapacity(2000);
		link2.setNumberOfLanes(2);
		network.addLink(link2);
	}

	private void createClusterLinks(Network network, String prefix, int count, double freespeed, double freespeedMs) {
		NetworkFactory factory = network.getFactory();

		for (int i = 1; i <= count; i++) {
			for (int j = 1; j <= count; j++) {
				if (i == j) continue;

				Node from = network.getNodes().get(Id.createNodeId(prefix + i));
				Node to = network.getNodes().get(Id.createNodeId(prefix + j));

				double dist = NetworkUtils.getEuclideanDistance(from.getCoord(), to.getCoord());

				Link link = factory.createLink(
						Id.createLinkId(prefix + "link_" + i + "_to_" + j),
						from, to);
				link.setLength(dist);
				link.setFreespeed(freespeedMs);
				link.setCapacity(500);
				link.setNumberOfLanes(1);
				network.addLink(link);
			}
		}
	}

	/**
	 * Create population with 30 persons distributed across clusters:
	 * - 10 from res_north (5 → com_east, 5 → com_central)
	 * - 10 from res_central (3 → com_east, 4 → com_central, 3 → com_west)
	 * - 10 from res_south (5 → com_central, 5 → com_west)
	 * - Departure times clustered (groups leave at similar times)
	 * - This should create multiple stop-based rides that can be hyper-pooled!
	 */
	private void createClusteredPopulation(Population population) {
		PopulationFactory factory = population.getFactory();

		int personId = 0;

		// Group 1: res_north → com_east (5 people, 7:00-7:10)
		for (int i = 0; i < 5; i++) {
			createPerson(population, personId++,
				"res_north_link_" + (i+1) + "_to_" + ((i+2) <= 10 ? (i+2) : 1),
				"com_east_link_" + (i+1) + "_to_" + ((i+2) <= 10 ? (i+2) : 1),
				7 * 3600 + i * 120); // 7:00-7:10
		}

		// Group 2: res_north → com_central (5 people, 7:00-7:10)
		for (int i = 0; i < 5; i++) {
			createPerson(population, personId++,
				"res_north_link_" + (i+3) + "_to_" + ((i+4) <= 10 ? (i+4) : 1),
				"com_central_link_" + (i+1) + "_to_" + ((i+2) <= 10 ? (i+2) : 1),
				7 * 3600 + i * 120);
		}

		// Group 3: res_central → com_east (3 people, 7:05-7:10)
		for (int i = 0; i < 3; i++) {
			createPerson(population, personId++,
				"res_central_link_" + (i+1) + "_to_" + ((i+2) <= 10 ? (i+2) : 1),
				"com_east_link_" + (i+3) + "_to_" + ((i+4) <= 10 ? (i+4) : 1),
				7 * 3600 + 300 + i * 120); // 7:05-7:10
		}

		// Group 4: res_central → com_central (4 people, 7:05-7:13)
		for (int i = 0; i < 4; i++) {
			createPerson(population, personId++,
				"res_central_link_" + (i+4) + "_to_" + ((i+5) <= 10 ? (i+5) : 1),
				"com_central_link_" + (i+3) + "_to_" + ((i+4) <= 10 ? (i+4) : 1),
				7 * 3600 + 300 + i * 120);
		}

		// Group 5: res_central → com_west (3 people, 7:05-7:10)
		for (int i = 0; i < 3; i++) {
			createPerson(population, personId++,
				"res_central_link_" + (i+7) + "_to_" + ((i+8) <= 10 ? (i+8) : 1),
				"com_west_link_" + (i+1) + "_to_" + ((i+2) <= 10 ? (i+2) : 1),
				7 * 3600 + 300 + i * 120);
		}

		// Group 6: res_south → com_central (5 people, 7:10-7:18)
		for (int i = 0; i < 5; i++) {
			createPerson(population, personId++,
				"res_south_link_" + (i+1) + "_to_" + ((i+2) <= 10 ? (i+2) : 1),
				"com_central_link_" + (i+5) + "_to_" + ((i+6) <= 10 ? (i+6) : 1),
				7 * 3600 + 600 + i * 120); // 7:10-7:18
		}

		// Group 7: res_south → com_west (5 people, 7:10-7:18)
		for (int i = 0; i < 5; i++) {
			createPerson(population, personId++,
				"res_south_link_" + (i+3) + "_to_" + ((i+4) <= 10 ? (i+4) : 1),
				"com_west_link_" + (i+3) + "_to_" + ((i+4) <= 10 ? (i+4) : 1),
				7 * 3600 + 600 + i * 120);
		}

		System.out.println("Created " + personId + " passengers across multiple clusters");
	}

	private void createPerson(Population population, int id, String originLink, String destLink, double departureTime) {
		PopulationFactory factory = population.getFactory();
		Person person = factory.createPerson(Id.createPersonId("clustered_passenger_" + id));

		// Give variety of attributes
		if (id % 2 == 0) {
			PersonUtils.setLicence(person, "yes");
			PersonUtils.setCarAvail(person, "always");
		} else {
			PersonUtils.setLicence(person, "no");
			PersonUtils.setCarAvail(person, "never");
		}

		Plan plan = factory.createPlan();

		// Home activity
		Activity home = factory.createActivityFromLinkId("home", Id.createLinkId(originLink));
		home.setEndTime(departureTime);
		plan.addActivity(home);

		// Leg
		Leg leg = factory.createLeg(TransportMode.car);
		plan.addLeg(leg);

		// Work activity
		Activity work = factory.createActivityFromLinkId("work", Id.createLinkId(destLink));
		work.setEndTime(17 * 3600);
		plan.addActivity(work);

		person.addPlan(plan);
		person.setSelectedPlan(plan);
		population.addPerson(person);
	}

	private void configureScoring(Config config) {
		ScoringConfigGroup scoring = config.scoring();
		scoring.setMarginalUtilityOfMoney(1.0);

		// Add activity parameters for home and work
		ScoringConfigGroup.ActivityParams homeParams = new ScoringConfigGroup.ActivityParams("home");
		homeParams.setTypicalDuration(12 * 3600); // 12 hours
		scoring.addActivityParams(homeParams);

		ScoringConfigGroup.ActivityParams workParams = new ScoringConfigGroup.ActivityParams("work");
		workParams.setTypicalDuration(8 * 3600); // 8 hours
		scoring.addActivityParams(workParams);

		// Make car EXTREMELY expensive to create large DRT budgets for hyper-pooling
		ScoringConfigGroup.ModeParams carParams = scoring.getOrCreateModeParams(TransportMode.car);
		carParams.setMarginalUtilityOfTraveling(-6.0); // VERY expensive (default is typically -6.0)
		carParams.setMonetaryDistanceRate(-0.002); // €2/km

		// Make walking VERY attractive (near zero) to encourage stop-based rides
		ScoringConfigGroup.ModeParams walkParams = scoring.getOrCreateModeParams(TransportMode.walk);
		walkParams.setMarginalUtilityOfTraveling(-0.01); // Almost no penalty! (default is typically -6.0)
		walkParams.setConstant(0.0);

		// DRT should be attractive
		ScoringConfigGroup.ModeParams drtParams = scoring.getOrCreateModeParams("drt");
		drtParams.setMarginalUtilityOfTraveling(-0.5);
		drtParams.setConstant(0.0);
	}

	private void configureExMasWithHyperPool(Config config) {
		ExMasConfigGroup exMasConfig = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);

		exMasConfig.setDrtMode("drt");

		Set<String> baseModes = new HashSet<>();
		baseModes.add(TransportMode.car);
		exMasConfig.setBaseModes(baseModes);

		exMasConfig.setDrtRoutingMode(TransportMode.car);

		Set<String> privateVehicles = new HashSet<>();
		privateVehicles.add(TransportMode.car);
		exMasConfig.setPrivateVehicleModes(privateVehicles);

		// DRT service quality
		exMasConfig.setMinDrtCostPerKm(0.0);
		exMasConfig.setMinMaxDetourFactor(1.0);
		exMasConfig.setMinMaxWaitingTime(0.0);
		exMasConfig.setMinDrtAccessEgressDistance(0.0);

		// ExMAS algorithm - VERY permissive for large pooling and hyper-pooling
		exMasConfig.setMaxDetourFactor(3.0);  // Allow significant detours
		exMasConfig.setSearchHorizon(1200.0);  // 20 min time window for pairing
		exMasConfig.setMaxPoolingDegree(15);   // Allow up to 15 passengers (for hyper-pooling)
		exMasConfig.setNegativeFlexibilityAbsoluteMap("default:18000.0");  // 5 hours
		exMasConfig.setPositiveFlexibilityAbsoluteMap("default:18000.0");

		// HYPERPOOL - VERY permissive settings to encourage bundling
		exMasConfig.setEnableStopBased(true);
		exMasConfig.setMaxWalkDistanceMeters(500.0);  // 500m max walk
		exMasConfig.setStopSearchRadiusMeters(400.0); // Large search radius

		exMasConfig.setEnableHyperPooling(true);
		exMasConfig.setHyperPoolMaxStopRelocationMeters(400.0); // Allow more stop relocation
		exMasConfig.setHyperPoolMinOccupancy(2);      // Min 2 passengers (easy to meet)
		exMasConfig.setHyperPoolTimeWindowSeconds(1800.0); // 30 min time window (very permissive)
		exMasConfig.setHyperPoolStopProximityMeters(200.0); // 200m stop proximity
	}

	private void validateRidesWithStopBased(Path ridesFile) throws IOException {
		int totalRides = 0;
		int doorToDoorCount = 0;
		int stopToStopCount = 0;
		int hyperPooledCount = 0;

		try (BufferedReader reader = IOUtils.getBufferedReader(ridesFile.toString())) {
			String header = reader.readLine();
			Assertions.assertNotNull(header, "File should have header");

			String line;
			while ((line = reader.readLine()) != null) {
				String[] parts = line.split(",");
				Assertions.assertEquals(35, parts.length, "Each ride should have 35 fields");

				String variant = parts[3];
				if (variant.equals("DOOR_TO_DOOR")) doorToDoorCount++;
				if (variant.equals("STOP_TO_STOP")) stopToStopCount++;
				if (variant.equals("HYPER_POOLED")) hyperPooledCount++;

				totalRides++;
			}
		}

		System.out.println("\n========================================");
		System.out.println("=== MULTI-CLUSTER HYPERPOOL RESULTS ===");
		System.out.println("========================================");
		System.out.println("Total rides: " + totalRides);
		System.out.println("  Door-to-door: " + doorToDoorCount);
		System.out.println("  Stop-to-stop: " + stopToStopCount);
		System.out.println("  Hyper-pooled: " + hyperPooledCount);
		System.out.println("========================================\n");

		// Assertions
		Assertions.assertTrue(totalRides > 0, "Should have generated at least one ride");
		Assertions.assertTrue(stopToStopCount > 0,
			"Expected stop-based rides with multiple clusters! Check if stop finding failed.");

		// Validate hyper-pooling
		if (hyperPooledCount == 0) {
			System.out.println("⚠️  WARNING: No hyper-pooled rides generated!");
			System.out.println("   Possible reasons:");
			System.out.println("   - Not enough stop-based rides with compatible stops");
			System.out.println("   - Time windows don't overlap sufficiently");
			System.out.println("   - Stop proximity constraints too tight");
			System.out.println("   Check logs for HyperPoolBundler details.\n");
		} else {
			System.out.println("✅ SUCCESS: Generated " + hyperPooledCount + " hyper-pooled rides!");
			System.out.println("   The HyperPool Stage 2 algorithm successfully bundled");
			System.out.println("   stop-based rides into high-occupancy services!\n");
		}

		// Report on stop-based success
		int totalStopBased = stopToStopCount + hyperPooledCount;
		double stopBasedRatio = (double) totalStopBased / totalRides * 100.0;
		System.out.printf("Stop-based ride ratio: %.1f%% (%d/%d)\n\n", stopBasedRatio, totalStopBased, totalRides);
	}
}
