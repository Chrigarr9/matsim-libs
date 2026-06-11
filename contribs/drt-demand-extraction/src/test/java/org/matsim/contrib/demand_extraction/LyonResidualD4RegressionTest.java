package org.matsim.contrib.demand_extraction;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.demand_extraction.algorithm.bamas.BamasEngine;
import org.matsim.contrib.demand_extraction.algorithm.bamas.stub.RideStores;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.exmas.ExMasReferenceEngine;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCacheTestFixture;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;
import org.matsim.contrib.demand_extraction.scenarios.LyonEqasimScenarioFixture;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;

@Tag("scenario-lyon")
@EnabledIfEnvironmentVariable(named = "LYON_SCENARIO_DIR", matches = ".+")
class LyonResidualD4RegressionTest {
	private static final String LYON_SCENARIO_DIR_ENV = "LYON_SCENARIO_DIR";
	private static final String LYON_SCENARIO_PREFIX_ENV = "LYON_SCENARIO_PREFIX";
	private static final String LYON_REQUESTS_CSV_ENV = "LYON_REQUESTS_CSV";
	private static final String LYON_REQUESTS_CSV_REQUIRED = "LYON_REQUESTS_CSV required";
	private static final String LYON_SAMPLE_PCT_ENV = "LYON_SAMPLE_PCT";
	private static final String DEFAULT_LYON_SCENARIO_PREFIX = "lyon_drt_area_";
	private static final String DEFAULT_SAMPLE_PCT = "1";

	private static final int[][] RESIDUAL_D4_SETS = {
			{ 1010, 1062, 3259, 3265 },
			{ 1206, 1567, 3285, 7170 },
			{ 1415, 6781, 6910, 7234 },
			{ 146, 467, 533, 6896 },
			{ 1525, 6010, 6757, 6941 },
			{ 282, 615, 652, 697 },
			{ 304, 1689, 6755, 7616 },
			{ 467, 4974, 5479, 6896 },
			{ 541, 790, 7242, 7616 },
			{ 592, 791, 6016, 6874 },
			{ 603, 1697, 7174, 7311 },
			{ 791, 3288, 6016, 6874 },
	};

	private static final int[] BROAD_R2_ONLY_D4_SET = { 1232, 3729, 4395, 7480 };
	private static final int[] R2_ONLY_D3_DELAY_BOUNDARY_SET = { 21, 484, 1098 };

	/** R1 (vanilla ExMAS reference, no pruning) configurator. */
	private static void applyR1(ExMasConfigGroup exMas) {
		exMas.setAlgorithm(ExMasConfigGroup.Algorithm.EXMAS);
		exMas.setHeuristicPruningEnabled(false);
		exMas.setPruningDistanceSavingsLogScale(-1.0);
		exMas.setPruningMode(ExMasConfigGroup.PruningMode.RATIO_THRESHOLD);
		exMas.setInterDegreeKeepFraction(1.0);
		exMas.clearPruningCoverageKByDegree();
		exMas.setCalcPredecessors(false);
		exMas.setMaxPoolingDegree(Integer.MAX_VALUE);
	}

	/** R2 (BAMAS, no pruning) configurator. */
	private static void applyR2(ExMasConfigGroup exMas) {
		exMas.setAlgorithm(ExMasConfigGroup.Algorithm.BAMAS);
		exMas.setHeuristicPruningEnabled(false);
		exMas.setPruningDistanceSavingsLogScale(-1.0);
		exMas.setPruningMode(ExMasConfigGroup.PruningMode.RATIO_THRESHOLD);
		exMas.setInterDegreeKeepFraction(1.0);
		exMas.clearPruningCoverageKByDegree();
		exMas.setCalcPredecessors(false);
		exMas.setMaxPoolingDegree(Integer.MAX_VALUE);
	}

	@Test
	void bamasReconstructsPostFixResidualD4Sets() throws Exception {
		String requestsCsv = System.getenv(LYON_REQUESTS_CSV_ENV);
		assumeTrue(requestsCsv != null && !requestsCsv.isBlank(),
				LYON_REQUESTS_CSV_REQUIRED);

		String scenarioDir = System.getenv(LYON_SCENARIO_DIR_ENV);
		String prefix = System.getenv().getOrDefault(LYON_SCENARIO_PREFIX_ENV, DEFAULT_LYON_SCENARIO_PREFIX);
		int samplePct = Integer.parseInt(System.getenv().getOrDefault(LYON_SAMPLE_PCT_ENV, DEFAULT_SAMPLE_PCT));

		LyonEqasimScenarioFixture fixture = new LyonEqasimScenarioFixture(
				samplePct, scenarioDir, prefix, "");
		Config config = fixture.createConfig(Path.of("test/output/lyon-residual-d4-regression"));
		config.plans().setInputFile(null);
		config.vehicles().setVehiclesFile(null);
		config.facilities().setInputFile(null);
		config.transit().setTransitScheduleFile(null);
		config.transit().setVehiclesFile(null);

		Scenario scenario = ScenarioUtils.createScenario(config);
		ScenarioUtils.loadScenario(scenario);
		Network network = scenario.getNetwork();

		TravelTime tt = new FreeSpeedTravelTime();
		TravelDisutility td = new OnlyTimeDependentTravelDisutility(tt);
		MatsimNetworkCache cache = MatsimNetworkCacheTestFixture
				.createWithSpeedyAltRoutingDeterministic(network, tt, td, 900);

		ExMasConfigGroup exMasConfig = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		applyR2(exMasConfig);
		exMasConfig.setCalcPredecessors(false);
		exMasConfig.setCalcShapleyValues(false);
		exMasConfig.setMaxPoolingDegree(4);
		exMasConfig.setAlgorithmProcessCount(1);

		BudgetValidator validator = new PassThroughBudgetValidator(exMasConfig, config);
		Map<Integer, DrtRequest> requests = loadRequestsFromCsv(requestsCsv, network);
		List<String> missing = new ArrayList<>();

		for (int[] residualSet : RESIDUAL_D4_SETS) {
			List<DrtRequest> targetRequests = new ArrayList<>();
			for (int requestIndex : residualSet) {
				DrtRequest request = requests.get(requestIndex);
				if (request == null) {
					throw new IllegalStateException("Request index " + requestIndex + " not found in " + requestsCsv);
				}
				targetRequests.add(request);
			}

			List<Ride> rides = RideStores.toList(new BamasEngine(
					cache,
					validator,
					exMasConfig.getSearchHorizon(),
					exMasConfig.getMaxPoolingDegree(),
					exMasConfig)
					.run(targetRequests));

			if (!containsRequestSet(rides, residualSet)) {
				missing.add(Arrays.toString(residualSet) + " missing d3 parents="
						+ missingParents(rides, residualSet)
						+ " actual d3=" + setsAtDegree(rides, 3)
						+ " actual d4=" + setsAtDegree(rides, 4));
			}
		}

		assertTrue(missing.isEmpty(), "BAMAS missed residual d=4 sets: " + missing);
	}

	@Test
	void bamasMatchesReferenceForFormerBroadR2OnlyD4Set() throws Exception {
		String requestsCsv = System.getenv(LYON_REQUESTS_CSV_ENV);
		assumeTrue(requestsCsv != null && !requestsCsv.isBlank(),
				LYON_REQUESTS_CSV_REQUIRED);

		String scenarioDir = System.getenv(LYON_SCENARIO_DIR_ENV);
		String prefix = System.getenv().getOrDefault(LYON_SCENARIO_PREFIX_ENV, DEFAULT_LYON_SCENARIO_PREFIX);
		int samplePct = Integer.parseInt(System.getenv().getOrDefault(LYON_SAMPLE_PCT_ENV, DEFAULT_SAMPLE_PCT));

		LyonEqasimScenarioFixture fixture = new LyonEqasimScenarioFixture(
				samplePct, scenarioDir, prefix, "");
		Config config = fixture.createConfig(Path.of("test/output/lyon-r2-only-d4-regression"));
		config.plans().setInputFile(null);
		config.vehicles().setVehiclesFile(null);
		config.facilities().setInputFile(null);
		config.transit().setTransitScheduleFile(null);
		config.transit().setVehiclesFile(null);

		Scenario scenario = ScenarioUtils.createScenario(config);
		ScenarioUtils.loadScenario(scenario);
		Network network = scenario.getNetwork();

		TravelTime tt = new FreeSpeedTravelTime();
		TravelDisutility td = new OnlyTimeDependentTravelDisutility(tt);
		MatsimNetworkCache cache = MatsimNetworkCacheTestFixture
				.createWithSpeedyAltRoutingDeterministic(network, tt, td, 900);

		ExMasConfigGroup exMasConfig = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		exMasConfig.setMaxPoolingDegree(4);
		exMasConfig.setAlgorithmProcessCount(1);

		BudgetValidator validator = new PassThroughBudgetValidator(exMasConfig, config);
		Map<Integer, DrtRequest> requests = loadRequestsFromCsv(requestsCsv, network);
		List<DrtRequest> targetRequests = new ArrayList<>();
		for (int requestIndex : BROAD_R2_ONLY_D4_SET) {
			DrtRequest request = requests.get(requestIndex);
			if (request == null) {
				throw new IllegalStateException("Request index " + requestIndex + " not found in " + requestsCsv);
			}
			targetRequests.add(request);
		}

		applyR1(exMasConfig);
		exMasConfig.setMaxPoolingDegree(4);
		exMasConfig.setAlgorithmProcessCount(1);
		List<Ride> referenceRides = new ExMasReferenceEngine(
				cache,
				validator,
				exMasConfig.getSearchHorizon(),
				exMasConfig.getMaxPoolingDegree(),
				exMasConfig)
				.run(targetRequests);

		applyR2(exMasConfig);
		exMasConfig.setCalcPredecessors(false);
		exMasConfig.setCalcShapleyValues(false);
		exMasConfig.setMaxPoolingDegree(4);
		exMasConfig.setAlgorithmProcessCount(1);
		List<Ride> bamasRides = RideStores.toList(new BamasEngine(
				cache,
				validator,
				exMasConfig.getSearchHorizon(),
				exMasConfig.getMaxPoolingDegree(),
				exMasConfig)
				.run(targetRequests));

		assertTrue(containsRequestSet(referenceRides, BROAD_R2_ONLY_D4_SET),
				"Reference ExMAS should admit former broad R2-only d=4 set after routing fixes: "
						+ Arrays.toString(BROAD_R2_ONLY_D4_SET));
		assertTrue(containsRequestSet(bamasRides, BROAD_R2_ONLY_D4_SET),
				"BAMAS should match reference ExMAS for former broad R2-only d=4 set: "
						+ Arrays.toString(BROAD_R2_ONLY_D4_SET));
	}

	@Test
	void referenceMatchesBamasForDelayBoundaryD3Set() throws Exception {
		String requestsCsv = System.getenv(LYON_REQUESTS_CSV_ENV);
		assumeTrue(requestsCsv != null && !requestsCsv.isBlank(),
				LYON_REQUESTS_CSV_REQUIRED);

		String scenarioDir = System.getenv(LYON_SCENARIO_DIR_ENV);
		String prefix = System.getenv().getOrDefault(LYON_SCENARIO_PREFIX_ENV, DEFAULT_LYON_SCENARIO_PREFIX);
		int samplePct = Integer.parseInt(System.getenv().getOrDefault(LYON_SAMPLE_PCT_ENV, DEFAULT_SAMPLE_PCT));

		LyonEqasimScenarioFixture fixture = new LyonEqasimScenarioFixture(
				samplePct, scenarioDir, prefix, "");
		Config config = fixture.createConfig(Path.of("test/output/lyon-r2-only-d3-delay-boundary-regression"));
		config.plans().setInputFile(null);
		config.vehicles().setVehiclesFile(null);
		config.facilities().setInputFile(null);
		config.transit().setTransitScheduleFile(null);
		config.transit().setVehiclesFile(null);

		Scenario scenario = ScenarioUtils.createScenario(config);
		ScenarioUtils.loadScenario(scenario);
		Network network = scenario.getNetwork();

		TravelTime tt = new FreeSpeedTravelTime();
		TravelDisutility td = new OnlyTimeDependentTravelDisutility(tt);
		MatsimNetworkCache cache = MatsimNetworkCacheTestFixture
				.createWithSpeedyAltRoutingDeterministic(network, tt, td, 900);

		ExMasConfigGroup exMasConfig = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		exMasConfig.setMaxPoolingDegree(3);
		exMasConfig.setAlgorithmProcessCount(1);

		BudgetValidator validator = new PassThroughBudgetValidator(exMasConfig, config);
		Map<Integer, DrtRequest> requests = loadRequestsFromCsv(requestsCsv, network);
		List<DrtRequest> targetRequests = new ArrayList<>();
		for (int requestIndex : R2_ONLY_D3_DELAY_BOUNDARY_SET) {
			DrtRequest request = requests.get(requestIndex);
			if (request == null) {
				throw new IllegalStateException("Request index " + requestIndex + " not found in " + requestsCsv);
			}
			targetRequests.add(request);
		}

		applyR1(exMasConfig);
		exMasConfig.setMaxPoolingDegree(3);
		exMasConfig.setAlgorithmProcessCount(1);
		List<Ride> referenceRides = new ExMasReferenceEngine(
				cache,
				validator,
				exMasConfig.getSearchHorizon(),
				exMasConfig.getMaxPoolingDegree(),
				exMasConfig)
				.run(targetRequests);

		applyR2(exMasConfig);
		exMasConfig.setCalcPredecessors(false);
		exMasConfig.setCalcShapleyValues(false);
		exMasConfig.setMaxPoolingDegree(3);
		exMasConfig.setAlgorithmProcessCount(1);
		List<Ride> bamasRides = RideStores.toList(new BamasEngine(
				cache,
				validator,
				exMasConfig.getSearchHorizon(),
				exMasConfig.getMaxPoolingDegree(),
				exMasConfig)
				.run(targetRequests));

		assertTrue(containsRequestSet(bamasRides, R2_ONLY_D3_DELAY_BOUNDARY_SET),
				"BAMAS should admit representative R2-only delay-boundary d=3 set: "
						+ Arrays.toString(R2_ONLY_D3_DELAY_BOUNDARY_SET));
		assertTrue(containsRequestSet(referenceRides, R2_ONLY_D3_DELAY_BOUNDARY_SET),
				"Reference ExMAS should match BAMAS on representative delay-boundary d=3 set: "
						+ Arrays.toString(R2_ONLY_D3_DELAY_BOUNDARY_SET));
	}

	private static boolean containsRequestSet(List<Ride> rides, int[] requestIndices) {
		int[] expected = requestIndices.clone();
		Arrays.sort(expected);
		for (Ride ride : rides) {
			int[] actual = ride.getRequestIndices();
			Arrays.sort(actual);
			if (Arrays.equals(expected, actual)) {
				return true;
			}
		}
		return false;
	}

	private static List<String> missingParents(List<Ride> rides, int[] requestIndices) {
		List<String> missing = new ArrayList<>();
		for (int skip = 0; skip < requestIndices.length; skip++) {
			int[] parent = new int[requestIndices.length - 1];
			for (int i = 0, j = 0; i < requestIndices.length; i++) {
				if (i != skip) parent[j++] = requestIndices[i];
			}
			if (!containsRequestSet(rides, parent)) {
				Arrays.sort(parent);
				missing.add(Arrays.toString(parent));
			}
		}
		return missing;
	}

	private static List<String> setsAtDegree(List<Ride> rides, int degree) {
		List<String> sets = new ArrayList<>();
		for (Ride ride : rides) {
			if (ride.getDegree() != degree) continue;
			int[] indices = ride.getRequestIndices();
			Arrays.sort(indices);
			sets.add(Arrays.toString(indices));
		}
		return sets;
	}

	private static Map<Integer, DrtRequest> loadRequestsFromCsv(String csvPath, Network network)
			throws IOException {
		Map<Integer, DrtRequest> requests = new HashMap<>();
		try (BufferedReader reader = new BufferedReader(new FileReader(csvPath))) {
			String headerLine = reader.readLine();
			if (headerLine == null) throw new IOException("Empty CSV: " + csvPath);
			String[] headers = headerLine.split(",", -1);
			Map<String, Integer> col = new HashMap<>();
			for (int i = 0; i < headers.length; i++) col.put(headers[i].trim(), i);

			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank()) continue;
				String[] p = line.split(",", -1);

				Id<Link> originLinkId = Id.createLinkId(p[col.get("originLinkId")].trim());
				Id<Link> destLinkId = Id.createLinkId(p[col.get("destinationLinkId")].trim());
				Link originLink = network.getLinks().get(originLinkId);
				Link destLink = network.getLinks().get(destLinkId);

				double directTT = Double.parseDouble(p[col.get("directTravelTime")].trim());
				double maxTT = Double.parseDouble(p[col.get("maxTravelTime")].trim());
				double maxDetour = directTT > 0 ? maxTT / directTT : 1.3;
				maxDetour = Math.min(maxDetour, 1.3);
				double cappedMaxTT = directTT * maxDetour;
				double earliestDeparture = Double.parseDouble(p[col.get("earliestDeparture")].trim());
				double latestArrival = Math.min(
						Double.parseDouble(p[col.get("latestArrival")].trim()),
						earliestDeparture + cappedMaxTT);

				DrtRequest request = DrtRequest.builder()
						.index(Integer.parseInt(p[col.get("index")].trim()))
						.personId(Id.createPersonId(p[col.get("personId")].trim()))
						.groupId(p[col.get("groupId")].trim())
						.tripIndex(Integer.parseInt(p[col.get("tripIndex")].trim()))
						.isCommute(Boolean.parseBoolean(p[col.get("isCommute")].trim()))
						.isEducation(Boolean.parseBoolean(p[col.get("isEducation")].trim()))
						.budget(Double.parseDouble(p[col.get("budget")].trim()))
						.requestTime(Double.parseDouble(p[col.get("requestTime")].trim()))
						.originLinkId(originLinkId)
						.destinationLinkId(destLinkId)
						.originX(Double.parseDouble(p[col.get("originX")].trim()))
						.originY(Double.parseDouble(p[col.get("originY")].trim()))
						.destinationX(Double.parseDouble(p[col.get("destinationX")].trim()))
						.destinationY(Double.parseDouble(p[col.get("destinationY")].trim()))
						.originLinkCoordFromX(originLink != null ? originLink.getFromNode().getCoord().getX() : 0)
						.originLinkCoordFromY(originLink != null ? originLink.getFromNode().getCoord().getY() : 0)
						.originLinkCoordToX(originLink != null ? originLink.getToNode().getCoord().getX() : 0)
						.originLinkCoordToY(originLink != null ? originLink.getToNode().getCoord().getY() : 0)
						.destinationLinkCoordFromX(destLink != null ? destLink.getFromNode().getCoord().getX() : 0)
						.destinationLinkCoordFromY(destLink != null ? destLink.getFromNode().getCoord().getY() : 0)
						.destinationLinkCoordToX(destLink != null ? destLink.getToNode().getCoord().getX() : 0)
						.destinationLinkCoordToY(destLink != null ? destLink.getToNode().getCoord().getY() : 0)
						.directTravelTime(directTT)
						.directDistance(Double.parseDouble(p[col.get("directDistance")].trim()))
						.earliestDeparture(earliestDeparture)
						.latestArrival(latestArrival)
						.maxDetourFactor(maxDetour)
						.maxWalkDistance(0.0)
						.originActivityType(p[col.get("originActivityType")].trim())
						.destinationActivityType(p[col.get("destinationActivityType")].trim())
						.bestModeScore(Double.parseDouble(p[col.get("baseModeScore")].trim()))
						.bestMode(p[col.get("baseMode")].trim())
						.carTravelTime(Double.parseDouble(p[col.get("carTravelTime")].trim()))
						.ptTravelTime(Double.parseDouble(p[col.get("ptTravelTime")].trim()))
						.ptAccessibility(Double.parseDouble(p[col.get("ptAccessibility")].trim()))
						.build();
				requests.put(request.index, request);
			}
		}
		return requests;
	}

	private static final class PassThroughBudgetValidator extends BudgetValidator {

		PassThroughBudgetValidator(ExMasConfigGroup exMasConfig, Config config) {
			super(null, null, exMasConfig, config);
		}

		@Override
		public Ride validateAndPopulateBudgets(Ride ride) {
			double[] budgets = Arrays.stream(ride.getRequests())
					.mapToDouble(r -> r.budget)
					.toArray();
			return ride.toBuilder().remainingBudgets(budgets).build();
		}

		@Override
		public Ride populateBudgetsInPlace(Ride ride) {
			double[] budgets = Arrays.stream(ride.getRequests())
					.mapToDouble(r -> r.budget)
					.toArray();
			ride.setRemainingBudgets(budgets);
			return ride;
		}

		@Override
		public double[] calculateRemainingBudgets(Ride ride) {
			return Arrays.stream(ride.getRequests()).mapToDouble(r -> r.budget).toArray();
		}

		@Override
		public double calculateBudget(DrtRequest request) {
			return request.budget;
		}
	}
}