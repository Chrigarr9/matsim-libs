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
import java.util.List;
import java.util.Locale;

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
 * Paper 1 ablation run: R3 → R4 → R5 → R6, sharing one routing cache.
 *
 * <p>Produces {@code r3/r4/r5/r6_rides.csv} and {@code ablation_summary.csv} in
 * {@code test/output/lyon-pruning-ablation/}. These files are consumed by the
 * Python analysis pipeline to generate the pruning progression figures.
 *
 * <p>Profile narrative:
 * <ul>
 *   <li><b>R3</b> — BAMAS + distance gate scale=0.15. Mild gate; d12 cascade; 5.4M rides (~32 min).</li>
 *   <li><b>R4</b> — BAMAS + distance gate scale=0.25. Sweet-spot; d10 cascade; 321K rides (~40 s).</li>
 *   <li><b>R5</b> — BAMAS + distance gate scale=0.30. Over-pruning ceiling; d7 cascade; 64K rides (~20 s).</li>
 *   <li><b>R6</b> — BAMAS + scale=0.25 + per-deg COVERAGE_TOPK K=20. Production profile for MIP (~50 s).</li>
 * </ul>
 *
 * <p>Required env vars:
 * <ul>
 *   <li>{@code LYON_SCENARIO_DIR} — path to Lyon eqasim scenario dir</li>
 *   <li>{@code LYON_REQUESTS_CSV} — path to a pre-computed {@code drt_requests.csv}</li>
 * </ul>
 * Optional env vars:
 * <ul>
 *   <li>{@code LYON_SCENARIO_PREFIX} — default {@code lyon_drt_area_}</li>
 *   <li>{@code LYON_SAMPLE_PCT} — sample percentage (default 1)</li>
 * </ul>
 *
 * <p>System properties (pass via {@code -DflagName=true}):
 * <ul>
 *   <li>{@code skipR3} — skip R3 engine run (use {@code -DskipR3=true} when R3 data already
 *       exists in {@code test/output/lyon-dist-gate-sweep/s015_m075_rides.csv}; ~32 min saved)</li>
 *   <li>{@code skipR4}, {@code skipR5}, {@code skipR6} — analogous skip flags</li>
 *   <li>{@code maxPoolingDegree} — cap all profiles at degree N (for smoke tests)</li>
 * </ul>
 */
@Tag("scenario-lyon")
@EnabledIfEnvironmentVariable(named = "LYON_SCENARIO_DIR", matches = ".+")
class LyonPruningAblationTest {

	private static final Logger log = LogManager.getLogger(LyonPruningAblationTest.class);

	/** R3 = BAMAS + heuristic distance gate (scale=0.15), no post-extension pruning. */
	private static void applyR3(ExMasConfigGroup exMas) {
		exMas.setAlgorithm(ExMasConfigGroup.Algorithm.BAMAS);
		exMas.setHeuristicPruningEnabled(true);
		exMas.setPruningDistanceSavingsLogScale(0.15);
		exMas.setPruningMode(ExMasConfigGroup.PruningMode.RATIO_THRESHOLD);
		exMas.setInterDegreeKeepFraction(1.0);
		exMas.clearPruningCoverageKByDegree();
		exMas.setCalcPredecessors(false);
		exMas.setMaxPoolingDegree(Integer.MAX_VALUE);
	}

	/** R4 = BAMAS + heuristic distance gate (scale=0.25), no post-extension pruning. */
	private static void applyR4(ExMasConfigGroup exMas) {
		exMas.setAlgorithm(ExMasConfigGroup.Algorithm.BAMAS);
		exMas.setHeuristicPruningEnabled(true);
		exMas.setPruningDistanceSavingsLogScale(0.25);
		exMas.setPruningMode(ExMasConfigGroup.PruningMode.RATIO_THRESHOLD);
		exMas.setInterDegreeKeepFraction(1.0);
		exMas.clearPruningCoverageKByDegree();
		exMas.setCalcPredecessors(false);
		exMas.setMaxPoolingDegree(Integer.MAX_VALUE);
	}

	/** R5 = BAMAS + heuristic distance gate (scale=0.30), no post-extension pruning. */
	private static void applyR5(ExMasConfigGroup exMas) {
		exMas.setAlgorithm(ExMasConfigGroup.Algorithm.BAMAS);
		exMas.setHeuristicPruningEnabled(true);
		exMas.setPruningDistanceSavingsLogScale(0.30);
		exMas.setPruningMode(ExMasConfigGroup.PruningMode.RATIO_THRESHOLD);
		exMas.setInterDegreeKeepFraction(1.0);
		exMas.clearPruningCoverageKByDegree();
		exMas.setCalcPredecessors(false);
		exMas.setMaxPoolingDegree(Integer.MAX_VALUE);
	}

	/** R6 = BAMAS + distance gate (scale=0.25) + COVERAGE_TOPK (K=20), predecessors on. */
	private static void applyR6(ExMasConfigGroup exMas) {
		exMas.setAlgorithm(ExMasConfigGroup.Algorithm.BAMAS);
		exMas.setHeuristicPruningEnabled(true);
		exMas.setPruningDistanceSavingsLogScale(0.25);
		exMas.setPruningMode(ExMasConfigGroup.PruningMode.COVERAGE_TOPK);
		exMas.setPruningCoverageK(20);
		exMas.clearPruningCoverageKByDegree();
		exMas.setCalcPredecessors(true);
		exMas.setMaxPoolingDegree(Integer.MAX_VALUE);
	}

	@Test
	void ablationR3toR6() throws Exception {
		String requestsCsv = System.getenv("LYON_REQUESTS_CSV");
		assumeTrue(requestsCsv != null && !requestsCsv.isBlank(),
				"LYON_REQUESTS_CSV required — point to a pre-computed drt_requests.csv");

		String scenarioDir = System.getenv("LYON_SCENARIO_DIR");
		String prefix = System.getenv().getOrDefault("LYON_SCENARIO_PREFIX", "lyon_drt_area_");
		int samplePct = Integer.parseInt(System.getenv().getOrDefault("LYON_SAMPLE_PCT", "1"));

		boolean skipR3 = Boolean.getBoolean("skipR3");
		boolean skipR4 = Boolean.getBoolean("skipR4");
		boolean skipR5 = Boolean.getBoolean("skipR5");
		boolean skipR6 = Boolean.getBoolean("skipR6");
		int maxDegreeOverride = Integer.getInteger("maxPoolingDegree", -1);

		Path outputDir = Path.of("test/output/lyon-pruning-ablation");
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

		// ── 2. Routing cache (built once, shared across all profiles) ────────────
		TravelTime tt = new FreeSpeedTravelTime();
		TravelDisutility td = new OnlyTimeDependentTravelDisutility(tt);
		MatsimNetworkCache cache = MatsimNetworkCacheTestFixture
				.createWithSpeedyAltRoutingDeterministic(network, tt, td, 900);
		log.info("Routing cache ready (SpeedyALT deterministic).");

		// ── 3. Requests ──────────────────────────────────────────────────────────
		List<DrtRequest> requests = loadRequestsFromCsv(requestsCsv, network);
		log.info("Loaded {} requests from: {}", requests.size(), requestsCsv);

		// ── 4. Shared config knobs ───────────────────────────────────────────────
		ExMasConfigGroup exMasConfig = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		exMasConfig.setCalcShapleyValues(false);
		exMasConfig.setAlgorithmProcessCount(-1); // parallel

		BudgetValidator validator = new PassthroughBudgetValidator(exMasConfig, config);

		List<AblationResult> results = new ArrayList<>();

		// ── 5. R3 ────────────────────────────────────────────────────────────────
		if (skipR3) {
			log.info("R3 skipped (-DskipR3=true). Data in test/output/lyon-dist-gate-sweep/s015_m075_rides.csv");
		} else {
			applyR3(exMasConfig);
			if (maxDegreeOverride > 0) exMasConfig.setMaxPoolingDegree(maxDegreeOverride);
			logConfig("R3", exMasConfig);
			long start = System.currentTimeMillis();
			List<Ride> rides = new BamasEngine(cache, validator,
					exMasConfig.getSearchHorizon(), exMasConfig.getMaxPoolingDegree(), exMasConfig)
					.run(new ArrayList<>(requests));
			long elapsedMs = System.currentTimeMillis() - start;
			log.info("R3: {} rides in {}s", rides.size(), String.format(Locale.US, "%.1f", elapsedMs / 1000.0));
			ExMasCsvWriter.writeRides(outputDir.resolve("r3_rides.csv").toString(), rides);
			results.add(new AblationResult("R3", rides, elapsedMs));
		}

		// ── 6. R4 ────────────────────────────────────────────────────────────────
		if (skipR4) {
			log.info("R4 skipped (-DskipR4=true). Data in test/output/lyon-dist-gate-sweep/s025_m075_rides.csv");
		} else {
			applyR4(exMasConfig);
			if (maxDegreeOverride > 0) exMasConfig.setMaxPoolingDegree(maxDegreeOverride);
			logConfig("R4", exMasConfig);
			long start = System.currentTimeMillis();
			List<Ride> rides = new BamasEngine(cache, validator,
					exMasConfig.getSearchHorizon(), exMasConfig.getMaxPoolingDegree(), exMasConfig)
					.run(new ArrayList<>(requests));
			long elapsedMs = System.currentTimeMillis() - start;
			log.info("R4: {} rides in {}s", rides.size(), String.format(Locale.US, "%.1f", elapsedMs / 1000.0));
			ExMasCsvWriter.writeRides(outputDir.resolve("r4_rides.csv").toString(), rides);
			results.add(new AblationResult("R4", rides, elapsedMs));
		}

		// ── 7. R5 ────────────────────────────────────────────────────────────────
		if (skipR5) {
			log.info("R5 skipped (-DskipR5=true). Data in test/output/lyon-dist-gate-sweep/s030_m075_rides.csv");
		} else {
			applyR5(exMasConfig);
			if (maxDegreeOverride > 0) exMasConfig.setMaxPoolingDegree(maxDegreeOverride);
			logConfig("R5", exMasConfig);
			long start = System.currentTimeMillis();
			List<Ride> rides = new BamasEngine(cache, validator,
					exMasConfig.getSearchHorizon(), exMasConfig.getMaxPoolingDegree(), exMasConfig)
					.run(new ArrayList<>(requests));
			long elapsedMs = System.currentTimeMillis() - start;
			log.info("R5: {} rides in {}s", rides.size(), String.format(Locale.US, "%.1f", elapsedMs / 1000.0));
			ExMasCsvWriter.writeRides(outputDir.resolve("r5_rides.csv").toString(), rides);
			results.add(new AblationResult("R5", rides, elapsedMs));
		}

		// ── 8. R6 (production: scale=0.25 + per-deg COVERAGE_TOPK K=20) ─────────
		if (skipR6) {
			log.info("R6 skipped (-DskipR6=true).");
		} else {
			applyR6(exMasConfig);
			if (maxDegreeOverride > 0) exMasConfig.setMaxPoolingDegree(maxDegreeOverride);
			logConfig("R6", exMasConfig);
			long start = System.currentTimeMillis();
			List<Ride> rides = new BamasEngine(cache, validator,
					exMasConfig.getSearchHorizon(), exMasConfig.getMaxPoolingDegree(), exMasConfig)
					.run(new ArrayList<>(requests));
			long elapsedMs = System.currentTimeMillis() - start;
			log.info("R6: {} rides in {}s", rides.size(), String.format(Locale.US, "%.1f", elapsedMs / 1000.0));
			ExMasCsvWriter.writeRides(outputDir.resolve("r6_rides.csv").toString(), rides);
			results.add(new AblationResult("R6", rides, elapsedMs));
		}

		// ── 9. Summary ───────────────────────────────────────────────────────────
		if (!results.isEmpty()) {
			Path summaryPath = outputDir.resolve("ablation_summary.csv");
			writeSummaryCsv(summaryPath, results);
			log.info("Ablation summary written: {}", summaryPath);
		} else {
			log.warn("All profiles skipped — no summary written.");
		}
	}

	// ── helpers ──────────────────────────────────────────────────────────────────

	private static void logConfig(String label, ExMasConfigGroup exMas) {
		log.info("{} config: algorithm={}, maxDeg={}, distScale={}, pruningMode={}, K={}, keepFrac={}, predecessors={}",
				label, exMas.getAlgorithm(), exMas.getMaxPoolingDegree(),
				exMas.getPruningDistanceSavingsLogScale(), exMas.getPruningMode(),
				exMas.getPruningCoverageK(), exMas.getInterDegreeKeepFraction(),
				exMas.isCalcPredecessors());
	}

	private record AblationResult(String label, List<Ride> rides, long elapsedMs) {

		int countByDegree(int degree) {
			int n = 0;
			for (Ride r : rides) if (r.getDegree() == degree) n++;
			return n;
		}

		int maxDegree() {
			return rides.stream().mapToInt(Ride::getDegree).max().orElse(0);
		}

		int nUniqueRequests() {
			java.util.Set<Integer> seen = new java.util.HashSet<>();
			for (Ride r : rides) {
				if (r.getDegree() < 2) continue;
				for (var req : r.getRequests()) seen.add(req.index);
			}
			return seen.size();
		}

		double avgRidesPerRequest() {
			long totalSlots = 0;
			for (Ride r : rides) {
				if (r.getDegree() >= 2) totalSlots += r.getDegree();
			}
			int uniq = nUniqueRequests();
			return uniq == 0 ? 0.0 : (double) totalSlots / uniq;
		}

		double meanDetour() {
			double sum = 0;
			long count = 0;
			for (Ride r : rides) {
				if (r.getDegree() < 2) continue;
				for (double d : r.getDetours()) { sum += d; count++; }
			}
			return count == 0 ? 0.0 : sum / count;
		}

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

	private static void writeSummaryCsv(Path path, List<AblationResult> results) throws IOException {
		int globalMaxDeg = results.stream().mapToInt(AblationResult::maxDegree).max().orElse(8);
		globalMaxDeg = Math.max(globalMaxDeg, 8);

		try (BufferedWriter bw = new BufferedWriter(new FileWriter(path.toFile()))) {
			StringBuilder header = new StringBuilder(
					"profile,total_rides,elapsed_s,max_deg" +
					",n_unique_req,avg_rides_per_req" +
					",mean_detour,veh_savings_pct");
			for (int d = 2; d <= globalMaxDeg; d++) header.append(",d").append(d);
			bw.write(header.toString());
			bw.newLine();

			for (AblationResult r : results) {
				StringBuilder row = new StringBuilder();
				row.append(r.label()).append(",");
				row.append(r.rides().size()).append(",");
				row.append(String.format(Locale.US, "%.1f", r.elapsedMs() / 1000.0)).append(",");
				row.append(r.maxDegree()).append(",");
				row.append(r.nUniqueRequests()).append(",");
				row.append(String.format(Locale.US, "%.2f", r.avgRidesPerRequest())).append(",");
				row.append(String.format(Locale.US, "%.4f", r.meanDetour())).append(",");
				row.append(String.format(Locale.US, "%.2f", r.vehicleSavingsFraction() * 100));
				for (int d = 2; d <= globalMaxDeg; d++) row.append(",").append(r.countByDegree(d));
				bw.write(row.toString());
				bw.newLine();
			}
		}
	}

	private static List<DrtRequest> loadRequestsFromCsv(String csvPath, Network network) throws IOException {
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
			double[] budgets = Arrays.stream(ride.getRequests()).mapToDouble(r -> r.budget).toArray();
			return ride.toBuilder().remainingBudgets(budgets).build();
		}

		@Override
		public Ride populateBudgetsInPlace(Ride ride) {
			double[] budgets = Arrays.stream(ride.getRequests()).mapToDouble(r -> r.budget).toArray();
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
