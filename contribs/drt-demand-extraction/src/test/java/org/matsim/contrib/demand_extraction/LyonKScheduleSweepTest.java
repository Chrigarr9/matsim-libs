package org.matsim.contrib.demand_extraction;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCacheTestFixture;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;
import org.matsim.contrib.demand_extraction.io.ExMasCsvWriter;
import org.matsim.contrib.demand_extraction.scenarios.LyonEqasimScenarioFixture;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;

/**
 * K-schedule sweep: runs BamasEngine (R4 pruning profile) with different per-degree
 * COVERAGE_TOPK values to find the schedule that maximises absolute-savings retention
 * per unit of DB size.
 *
 * <p>All schedules share a single SpeedyALT routing cache (built once) and load
 * requests from a pre-computed CSV, making each sweep iteration cheap (~1.5 min
 * pair-gen + variable extension time).
 *
 * <p>K schedules defined in {@link #SCHEDULES}. Each key is an OUTPUT ride degree
 * (3, 4, 5, …); {@code pruningCoverageK} on the config serves as the catch-all default
 * for unspecified degrees. Degree-2 pairs are NOT subject to PostExtensionPruner —
 * they go through {@code maybePrunePairRidesAfterGraph} separately.
 *
 * <p>Required env vars (same as {@code ExMasLyonR1R2FastComparisonTest}):
 * <ul>
 *   <li>{@code LYON_SCENARIO_DIR} — path to Lyon eqasim scenario dir</li>
 *   <li>{@code LYON_REQUESTS_CSV} — path to pre-computed {@code drt_requests.csv}</li>
 * </ul>
 * Optional:
 * <ul>
 *   <li>{@code LYON_SCENARIO_PREFIX} — config/network file prefix (default {@code lyon_drt_area_})</li>
 *   <li>{@code LYON_SAMPLE_PCT} — sample percentage, e.g. {@code 10} (default 1)</li>
 * </ul>
 *
 * Output: {@code test/output/lyon-k-schedule-sweep/<label>_rides.csv} per schedule,
 * plus {@code sweep_summary.csv} with per-degree counts for all schedules.
 */
@Tag("scenario-lyon")
@EnabledIfEnvironmentVariable(named = "LYON_SCENARIO_DIR", matches = ".+")
class LyonKScheduleSweepTest {

	private static final Logger log = LogManager.getLogger(LyonKScheduleSweepTest.class);

	/**
	 * K schedules to sweep. Each entry: (label -> (defaultK, perDegreeK)).
	 * defaultK is set as {@code pruningCoverageK}; perDegreeK overrides per degree.
	 * Empty perDegreeK = flat schedule using only defaultK.
	 */
	static final Map<String, KSchedule> SCHEDULES = new LinkedHashMap<>();

	static {
		// Baseline: flat K=20 (matches R4 production default — reference)
		SCHEDULES.put("flat_K20",       new KSchedule(20, Map.of()));
		// Flat alternatives
		SCHEDULES.put("flat_K30",       new KSchedule(30, Map.of()));
		SCHEDULES.put("flat_K50",       new KSchedule(50, Map.of()));
		// Growing K: low K at low degrees (cheap rides), ramp up toward high-value high-degree rides.
		// Python cascade sim: growing efficiency=0.976 vs flat_K20=0.686 (savings retained / DB size).
		// Degree-2 pairs are NOT pruned by PostExtensionPruner — key 3 is the first active degree.
		SCHEDULES.put("growing_5_50",   new KSchedule(50, Map.of(3, 5,  4, 10, 5, 20, 6, 30)));
		SCHEDULES.put("growing_10_50",  new KSchedule(50, Map.of(3, 10, 4, 20, 5, 30, 6, 50)));
		// Seed-preserving: high K at low degrees keeps more extension seeds for high-degree generation.
		// Python sim: seed_high efficiency=0.506 (worse — wastes budget on low-value low-degree rides).
		SCHEDULES.put("seed_high",      new KSchedule(5,  Map.of(3, 50, 4, 30, 5, 20, 6, 10)));
		// Shrinking K: very high K at low degrees (100 triples) shrinks to a floor of 50.
		// Tests whether generous low-degree retention seeds more high-degree rides than flat_K50.
		SCHEDULES.put("shrinking_100_50", new KSchedule(50, Map.of(3, 100, 4, 75, 5, 60, 6, 50)));
	}

	record KSchedule(int defaultK, Map<Integer, Integer> perDegreeK) {}

	@Test
	void sweepKSchedules() throws Exception {
		String requestsCsv = System.getenv("LYON_REQUESTS_CSV");
		assumeTrue(requestsCsv != null && !requestsCsv.isBlank(),
				"LYON_REQUESTS_CSV required — point to a pre-computed drt_requests.csv");

		String scenarioDir = System.getenv("LYON_SCENARIO_DIR");
		String prefix = System.getenv().getOrDefault("LYON_SCENARIO_PREFIX", "lyon_drt_area_");
		int samplePct = Integer.parseInt(System.getenv().getOrDefault("LYON_SAMPLE_PCT", "1"));

		Path outputDir = Path.of("test/output/lyon-k-schedule-sweep");
		Files.createDirectories(outputDir);

		// ── 1. Network ───────────────────────────────────────────────────────────
		LyonEqasimScenarioFixture fixture = new LyonEqasimScenarioFixture(
				samplePct, scenarioDir, prefix, "");
		Config config = fixture.createConfig(outputDir.resolve("matsim-output"));
		config.plans().setInputFile(null);
		config.vehicles().setVehiclesFile(null);
		config.facilities().setInputFile(null);
		config.transit().setTransitScheduleFile(null);
		config.transit().setVehiclesFile(null);

		Scenario scenario = ScenarioUtils.createScenario(config);
		ScenarioUtils.loadScenario(scenario);
		Network network = scenario.getNetwork();
		log.info("Loaded network: {} links, {} nodes",
				network.getLinks().size(), network.getNodes().size());

		// ── 2. Routing cache (built once, shared across all sweeps) ──────────────
		TravelTime tt = new FreeSpeedTravelTime();
		TravelDisutility td = new OnlyTimeDependentTravelDisutility(tt);
		MatsimNetworkCache cache = MatsimNetworkCacheTestFixture
				.createWithSpeedyAltRoutingDeterministic(network, tt, td, 900);
		log.info("Routing cache built (SpeedyALT deterministic).");

		// ── 3. Requests from CSV ─────────────────────────────────────────────────
		List<DrtRequest> requests = loadRequestsFromCsv(requestsCsv, network);
		log.info("Loaded {} requests from: {}", requests.size(), requestsCsv);

		// ── 4. Shared config knobs ───────────────────────────────────────────────
		ExMasConfigGroup exMasConfig = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		exMasConfig.setCalcPredecessors(false);
		exMasConfig.setCalcShapleyValues(false);
		exMasConfig.setAlgorithmProcessCount(-1); // parallel
		int maxPoolingDegreeOverride = Integer.getInteger("maxPoolingDegree", -1);

		BudgetValidator validator = new PassthroughBudgetValidator(exMasConfig, config);

		// ── 5. Sweep ─────────────────────────────────────────────────────────────
		List<SweepResult> results = new ArrayList<>();

		for (Map.Entry<String, KSchedule> entry : SCHEDULES.entrySet()) {
			String label = entry.getKey();
			KSchedule schedule = entry.getValue();

			log.info("");
			log.info("======================================================================");
			log.info("SWEEP: {} (defaultK={}, perDegreeK={})", label, schedule.defaultK(), schedule.perDegreeK());
			log.info("======================================================================");

			// R6 setup: BAMAS + distance gate scale=0.25 + COVERAGE_TOPK K=20, predecessors on.
			exMasConfig.setAlgorithm(ExMasConfigGroup.Algorithm.BAMAS);
			exMasConfig.setHeuristicPruningEnabled(true);
			exMasConfig.setPruningDistanceSavingsLogScale(0.25);
			exMasConfig.setPruningMode(ExMasConfigGroup.PruningMode.COVERAGE_TOPK);
			exMasConfig.setPruningCoverageK(20);
			exMasConfig.clearPruningCoverageKByDegree();
			exMasConfig.setCalcPredecessors(true);
			exMasConfig.setMaxPoolingDegree(Integer.MAX_VALUE);
			if (maxPoolingDegreeOverride > 0) exMasConfig.setMaxPoolingDegree(maxPoolingDegreeOverride);

			// Override flat K and per-degree schedule
			exMasConfig.setPruningCoverageK(schedule.defaultK());
			exMasConfig.setPruningCoverageKByDegree(schedule.perDegreeK());

			log.info("Config: K={}, perDegree={}, distScale={}",
					exMasConfig.getPruningCoverageK(),
					exMasConfig.getPruningCoverageKByDegree(),
					exMasConfig.getPruningDistanceSavingsLogScale());

			long startMs = System.currentTimeMillis();
			List<Ride> rides = RideStores.toList(new BamasEngine(
					cache, validator,
					exMasConfig.getSearchHorizon(),
					exMasConfig.getMaxPoolingDegree(),
					exMasConfig)
					.run(new ArrayList<>(requests)));
			long elapsedMs = System.currentTimeMillis() - startMs;

			log.info("SWEEP {}: {} rides in {}s", label, rides.size(),
					String.format(Locale.US, "%.1f", elapsedMs / 1000.0));

			Path ridesCsv = outputDir.resolve(label + "_rides.csv");
			ExMasCsvWriter.writeRides(ridesCsv.toString(), rides);
			log.info("  Written: {}", ridesCsv);

			results.add(new SweepResult(label, rides, elapsedMs));
		}

		// ── 6. Summary CSV ───────────────────────────────────────────────────────
		Path summaryPath = outputDir.resolve("sweep_summary.csv");
		writeSummaryCsv(summaryPath, results);
		log.info("Summary written to: {}", summaryPath);
	}

	// ── helpers ──────────────────────────────────────────────────────────────────

	private record SweepResult(String label, List<Ride> rides, long elapsedMs) {
		int countByDegree(int degree) {
			int n = 0;
			for (Ride r : rides) if (r.getDegree() == degree) n++;
			return n;
		}
		int maxDegree() {
			return rides.stream().mapToInt(Ride::getDegree).max().orElse(0);
		}

		/** Unique request indices appearing in at least one pooled ride (degree ≥ 2). */
		int nUniqueRequests() {
			java.util.Set<Integer> seen = new java.util.HashSet<>();
			for (Ride r : rides) {
				if (r.getDegree() < 2) continue;
				for (var req : r.getRequests()) seen.add(req.index);
			}
			return seen.size();
		}

		/**
		 * Average number of ride options per pooled request.
		 * = total (request × ride) slots in deg2+ rides / unique requests covered.
		 * Higher → richer MIP input → better optimization headroom.
		 */
		double avgRidesPerRequest() {
			long totalSlots = 0;
			for (Ride r : rides) {
				if (r.getDegree() >= 2) totalSlots += r.getDegree();
			}
			int uniq = nUniqueRequests();
			return uniq == 0 ? 0.0 : (double) totalSlots / uniq;
		}

		/**
		 * Mean passenger detour factor across all passengers in deg2+ rides.
		 * 1.0 = no detour (same as solo), >1.0 = longer route.
		 * Lower is better for passengers.
		 */
		double meanDetour() {
			double sum = 0;
			long count = 0;
			for (Ride r : rides) {
				if (r.getDegree() < 2) continue;
				for (double d : r.getDetours()) { sum += d; count++; }
			}
			return count == 0 ? 0.0 : sum / count;
		}

		/**
		 * Vehicle distance savings fraction for deg2+ rides.
		 * savings = sum(req.directDistance) - rideDistance; pooling efficiency proxy.
		 * Returns value in [0, 1].
		 */
		double vehicleSavingsFraction() {
			double totalDirect = 0, totalRide = 0;
			for (Ride r : rides) {
				if (r.getDegree() < 2) continue;
				for (var req : r.getRequests()) totalDirect += req.directDistance;
				totalRide += r.getRideDistance();
			}
			return totalDirect == 0 ? 0.0 : (totalDirect - totalRide) / totalDirect;
		}
	}

	private static void writeSummaryCsv(Path path, List<SweepResult> results) throws IOException {
		int globalMaxDegree = results.stream().mapToInt(SweepResult::maxDegree).max().orElse(12);

		try (BufferedWriter bw = new BufferedWriter(new FileWriter(path.toFile()))) {
			// Quality + variety columns come first so they're visible without scrolling right.
			StringBuilder header = new StringBuilder(
					"label,n_rides_total,elapsed_s,max_degree" +
					",n_unique_requests,avg_rides_per_request" +
					",mean_detour,veh_savings_pct");
			for (int d = 1; d <= globalMaxDegree; d++) header.append(",deg").append(d).append("_rides");
			bw.write(header.toString());
			bw.newLine();

			for (SweepResult r : results) {
				StringBuilder row = new StringBuilder();
				row.append(r.label()).append(",");
				row.append(r.rides().size()).append(",");
				row.append(String.format("%.1f", r.elapsedMs() / 1000.0)).append(",");
				row.append(r.maxDegree()).append(",");
				row.append(r.nUniqueRequests()).append(",");
				row.append(String.format(Locale.US, "%.2f", r.avgRidesPerRequest())).append(",");
				row.append(String.format(Locale.US, "%.4f", r.meanDetour())).append(",");
				row.append(String.format(Locale.US, "%.2f", r.vehicleSavingsFraction() * 100));
				for (int d = 1; d <= globalMaxDegree; d++) {
					row.append(",").append(r.countByDegree(d));
				}
				bw.write(row.toString());
				bw.newLine();
			}
		}
	}

	private static List<DrtRequest> loadRequestsFromCsv(String csvPath, Network network)
			throws IOException {
		List<DrtRequest> requests = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(new FileReader(csvPath))) {
			String headerLine = reader.readLine();
			if (headerLine == null) throw new IOException("Empty CSV: " + csvPath);
			String[] headers = headerLine.split(",", -1);
			java.util.Map<String, Integer> col = new java.util.HashMap<>();
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

				requests.add(DrtRequest.builder()
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
						.originLinkCoordFromX(originLink != null
								? originLink.getFromNode().getCoord().getX() : 0)
						.originLinkCoordFromY(originLink != null
								? originLink.getFromNode().getCoord().getY() : 0)
						.originLinkCoordToX(originLink != null
								? originLink.getToNode().getCoord().getX() : 0)
						.originLinkCoordToY(originLink != null
								? originLink.getToNode().getCoord().getY() : 0)
						.destinationLinkCoordFromX(destLink != null
								? destLink.getFromNode().getCoord().getX() : 0)
						.destinationLinkCoordFromY(destLink != null
								? destLink.getFromNode().getCoord().getY() : 0)
						.destinationLinkCoordToX(destLink != null
								? destLink.getToNode().getCoord().getX() : 0)
						.destinationLinkCoordToY(destLink != null
								? destLink.getToNode().getCoord().getY() : 0)
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
						.build());
			}
		}
		return requests;
	}

	private static final class PassthroughBudgetValidator extends BudgetValidator {

		PassthroughBudgetValidator(ExMasConfigGroup exMasConfig, Config config) {
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
