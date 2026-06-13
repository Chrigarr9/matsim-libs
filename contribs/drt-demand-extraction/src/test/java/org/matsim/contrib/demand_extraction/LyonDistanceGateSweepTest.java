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
import org.matsim.contrib.demand_extraction.algorithm.selection.RideSelector;
import org.matsim.contrib.demand_extraction.algorithm.selection.SelectionRule;
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

/**
 * Distance gate parameter sweep: runs BamasEngine with R3 profile (heuristic distance gate ON,
 * no per-degree COVERAGE_TOPK during enumeration) across a range of
 * {@code pruningDistanceSavingsLogScale} and {@code pruningDistanceSavingsMax} values.
 *
 * <p>Motivation: per-degree K pruning during enumeration collapses the cascade (kills high-degree
 * seeds). Tightening the distance gate instead reduces ride count during enumeration without
 * destroying seed diversity. Post-hoc coverage_topK(20) is applied globally once after enumeration
 * to compress the final DB.
 *
 * <p>Goal: find the gate setting that makes Lyon 100% tractable while retaining cascade structure
 * and high-value high-degree rides.
 *
 * <p>Gate formula: {@code requiredSaving(d) = min(max, max(0, scale * log2(d)))}
 * A ride at degree d is kept iff {@code rideDistance <= (1 - requiredSaving(d)) * sum(request.directDistance)}.
 *
 * <p>Required env vars (same as other Lyon tests):
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
 * Output: {@code test/output/lyon-dist-gate-sweep/<label>_rides.csv} per gate config,
 * plus {@code sweep_summary.csv}.
 */
@Tag("scenario-lyon")
@EnabledIfEnvironmentVariable(named = "LYON_SCENARIO_DIR", matches = ".+")
class LyonDistanceGateSweepTest {

	private static final Logger log = LogManager.getLogger(LyonDistanceGateSweepTest.class);

	/**
	 * Post-hoc compression K applied globally after enumeration. Simulates the "R5" architecture:
	 * distance gate reduces enumeration output; coverage_topK compresses the final DB once.
	 */
	private static final int POST_HOC_K = 20;

	/**
	 * Distance gate configurations to sweep.
	 * scale = pruningDistanceSavingsLogScale; max = pruningDistanceSavingsMax.
	 * minDegree=2 (matches Lyon fixture — includes pair filtering).
	 */
	record GateConfig(double scale, double max) {
		String requiredSavingStr(int degree) {
			double req = Math.min(max, Math.max(0, scale * (Math.log(degree) / Math.log(2))));
			return String.format(Locale.US, "d%d=%.1f%%", degree, req * 100);
		}
	}

	static final Map<String, GateConfig> GATES = new LinkedHashMap<>();

	static {
		// Baseline: current R3/R4 production settings from LyonEqasimScenarioFixture
		GATES.put("s015_m075", new GateConfig(0.15, 0.75));
		// Mild tightening — cascade analytically safe (infeasible only at d>21)
		GATES.put("s020_m075", new GateConfig(0.20, 0.75));
		// Scale=0.25 variants: cap effect on d8-d12 isolated by varying max
		//   max=0.60 — cap kicks in at d6 (protective for d6+: stops ramp early)
		//   max=0.75 — cap kicks in at d8 (current production cap, protects d8+)
		//   max=0.90 — cap deferred to d13, so d9-d12 face full scale ramp (stricter)
		GATES.put("s025_m060", new GateConfig(0.25, 0.60));
		GATES.put("s025_m075", new GateConfig(0.25, 0.75));
		GATES.put("s025_m090", new GateConfig(0.25, 0.90));
		// Aggressive upper bound: analytically kills d8+ (infeasible at d>7); informative ceiling
		GATES.put("s030_m075", new GateConfig(0.30, 0.75));
	}

	@Test
	void sweepDistanceGate() throws Exception {
		String requestsCsv = System.getenv("LYON_REQUESTS_CSV");
		assumeTrue(requestsCsv != null && !requestsCsv.isBlank(),
				"LYON_REQUESTS_CSV required — point to a pre-computed drt_requests.csv");

		String scenarioDir = System.getenv("LYON_SCENARIO_DIR");
		String prefix = System.getenv().getOrDefault("LYON_SCENARIO_PREFIX", "lyon_drt_area_");
		int samplePct = Integer.parseInt(System.getenv().getOrDefault("LYON_SAMPLE_PCT", "1"));

		Path outputDir = Path.of("test/output/lyon-dist-gate-sweep");
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
				.createWithRouting(network, tt, td, 900);
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

		for (Map.Entry<String, GateConfig> entry : GATES.entrySet()) {
			String label = entry.getKey();
			GateConfig gate = entry.getValue();

			log.info("");
			log.info("======================================================================");
			log.info("SWEEP: {} (scale={}, max={})", label, gate.scale(), gate.max());
			log.info("  requiredSaving: {}, {}, {}, {}",
					gate.requiredSavingStr(2), gate.requiredSavingStr(3),
					gate.requiredSavingStr(4), gate.requiredSavingStr(6));
			log.info("======================================================================");

			// R3 setup: BAMAS + heuristic gate ON, post-extension pruner OFF (no per-degree K during enumeration).
			// (Scale is overridden below to the swept value; the base value here is irrelevant.)
			exMasConfig.setAlgorithm(ExMasConfigGroup.Algorithm.BAMAS);
			exMasConfig.setHeuristicPruningEnabled(true);
			exMasConfig.setPruningDistanceSavingsLogScale(0.15);
			exMasConfig.setPruningMode(ExMasConfigGroup.PruningMode.RATIO_THRESHOLD);
			exMasConfig.setInterDegreeKeepFraction(1.0);
			exMasConfig.clearPruningCoverageKByDegree();
			exMasConfig.setCalcPredecessors(false);
			exMasConfig.setMaxPoolingDegree(Integer.MAX_VALUE);
			if (maxPoolingDegreeOverride > 0) exMasConfig.setMaxPoolingDegree(maxPoolingDegreeOverride);

			// Override gate parameters (the R3 setup above sets scale=0.15 and leaves max at fixture default).
			exMasConfig.setPruningDistanceSavingsLogScale(gate.scale());
			exMasConfig.setPruningDistanceSavingsMax(gate.max());
			exMasConfig.setPruningDistanceSavingsMinDegree(2); // match Lyon fixture (filter pairs too)

			log.info("Config: scale={}, max={}, minDegree={}",
					exMasConfig.getPruningDistanceSavingsLogScale(),
					exMasConfig.getPruningDistanceSavingsMax(),
					exMasConfig.getPruningDistanceSavingsMinDegree());

			long startMs = System.currentTimeMillis();
			List<Ride> rawRides = RideStores.toList(new BamasEngine(
					cache, validator,
					exMasConfig.getSearchHorizon(),
					exMasConfig.getMaxPoolingDegree(),
					exMasConfig)
					.run(new ArrayList<>(requests)));
			long elapsedMs = System.currentTimeMillis() - startMs;

			log.info("SWEEP {}: {} raw rides in {}s", label, rawRides.size(),
					String.format(Locale.US, "%.1f", elapsedMs / 1000.0));

			// Post-hoc compression: apply coverage_topK(K) globally once
			long compressStart = System.currentTimeMillis();
			List<Ride> compressedRides = coverageCompress(rawRides, POST_HOC_K);
			long compressMs = System.currentTimeMillis() - compressStart;
			log.info("  Post-hoc K={}: {} compressed rides in {}ms",
					POST_HOC_K, compressedRides.size(), compressMs);

			Path ridesCsv = outputDir.resolve(label + "_rides.csv");
			org.matsim.contrib.demand_extraction.io.ExMasCsvWriter.writeRides(ridesCsv.toString(), rawRides);
			log.info("  Raw rides written: {}", ridesCsv);

			results.add(new SweepResult(label, rawRides, compressedRides, elapsedMs));
		}

		// ── 6. Summary CSV ───────────────────────────────────────────────────────
		Path summaryPath = outputDir.resolve("sweep_summary.csv");
		writeSummaryCsv(summaryPath, results);
		log.info("Summary written to: {}", summaryPath);
	}

	// ── helpers ──────────────────────────────────────────────────────────────────

	/**
	 * Post-hoc COVERAGE_TOPK compression over a fat {@link Ride} list (experiment harness only;
	 * the production path is stub-based). Groups by degree, runs {@link RideSelector} per degree
	 * with the ABS_SAVINGS metric, and passes singles through. Only the survivor COUNT is used by
	 * the sweep, so emission order is irrelevant here.
	 */
	private static List<Ride> coverageCompress(List<Ride> rides, int k) {
		java.util.Map<Integer, List<Ride>> byDegree = new java.util.TreeMap<>();
		for (Ride r : rides) byDegree.computeIfAbsent(r.getDegree(), d -> new ArrayList<>()).add(r);
		List<Ride> kept = new ArrayList<>();
		for (var entry : byDegree.entrySet()) {
			List<Ride> group = entry.getValue();
			if (entry.getKey() <= 1) { kept.addAll(group); continue; }
			int n = group.size();
			int[][] sets = new int[n][];
			double[] metric = new double[n];
			for (int i = 0; i < n; i++) {
				Ride ride = group.get(i);
				int deg = ride.getDegree();
				int[] s = new int[deg];
				double sumDirect = 0;
				for (int j = 0; j < deg; j++) {
					s[j] = ride.getRequest(j).index;
					sumDirect += ride.getRequest(j).getDistance();
				}
				java.util.Arrays.sort(s); // RideSelector requires ascending request sets
				sets[i] = s;
				metric[i] = sumDirect - ride.getRideDistance(); // ABS_SAVINGS
			}
			var keptRows = RideSelector.select(sets, metric, SelectionRule.COVERAGE_TOPK, k, 0.0);
			for (int i = 0; i < n; i++) if (keptRows.contains(i)) kept.add(group.get(i));
		}
		return kept;
	}

	private record SweepResult(String label, List<Ride> rawRides, List<Ride> compressedRides, long elapsedMs) {

		int countByDegree(List<Ride> rides, int degree) {
			int n = 0;
			for (Ride r : rides) if (r.getDegree() == degree) n++;
			return n;
		}

		int maxDegree(List<Ride> rides) {
			return rides.stream().mapToInt(Ride::getDegree).max().orElse(0);
		}

		/** Unique request indices appearing in at least one pooled ride (degree >= 2). */
		int nUniqueRequests(List<Ride> rides) {
			java.util.Set<Integer> seen = new java.util.HashSet<>();
			for (Ride r : rides) {
				if (r.getDegree() < 2) continue;
				for (var req : r.getRequests()) seen.add(req.index);
			}
			return seen.size();
		}

		/** Average ride options per pooled request. */
		double avgRidesPerRequest(List<Ride> rides) {
			long totalSlots = 0;
			for (Ride r : rides) {
				if (r.getDegree() >= 2) totalSlots += r.getDegree();
			}
			int uniq = nUniqueRequests(rides);
			return uniq == 0 ? 0.0 : (double) totalSlots / uniq;
		}

		/** Mean passenger detour factor across deg2+ rides. */
		double meanDetour(List<Ride> rides) {
			double sum = 0;
			long count = 0;
			for (Ride r : rides) {
				if (r.getDegree() < 2) continue;
				for (double d : r.getDetours()) { sum += d; count++; }
			}
			return count == 0 ? 0.0 : sum / count;
		}

		/** Vehicle distance savings fraction for deg2+ rides. */
		double vehicleSavingsFraction(List<Ride> rides) {
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
		int globalMaxDegree = results.stream()
				.mapToInt(r -> r.maxDegree(r.rawRides()))
				.max().orElse(12);
		globalMaxDegree = Math.max(globalMaxDegree, 8); // always show at least d8

		try (BufferedWriter bw = new BufferedWriter(new FileWriter(path.toFile()))) {
			// Raw metrics (enumeration output) + compressed metrics (post-hoc K=20)
			StringBuilder header = new StringBuilder(
					"label" +
					",raw_total,compressed_total,elapsed_s" +
					",raw_max_deg,comp_max_deg" +
					",comp_n_unique_req,comp_avg_rides_per_req" +
					",comp_mean_detour,comp_veh_savings_pct");
			for (int d = 2; d <= globalMaxDegree; d++) {
				header.append(",raw_d").append(d);
			}
			for (int d = 2; d <= globalMaxDegree; d++) {
				header.append(",comp_d").append(d);
			}
			bw.write(header.toString());
			bw.newLine();

			for (SweepResult r : results) {
				StringBuilder row = new StringBuilder();
				row.append(r.label()).append(",");
				row.append(r.rawRides().size()).append(",");
				row.append(r.compressedRides().size()).append(",");
				row.append(String.format(Locale.US, "%.1f", r.elapsedMs() / 1000.0)).append(",");
				row.append(r.maxDegree(r.rawRides())).append(",");
				row.append(r.maxDegree(r.compressedRides())).append(",");
				row.append(r.nUniqueRequests(r.compressedRides())).append(",");
				row.append(String.format(Locale.US, "%.2f", r.avgRidesPerRequest(r.compressedRides()))).append(",");
				row.append(String.format(Locale.US, "%.4f", r.meanDetour(r.compressedRides()))).append(",");
				row.append(String.format(Locale.US, "%.2f", r.vehicleSavingsFraction(r.compressedRides()) * 100));
				for (int d = 2; d <= globalMaxDegree; d++) {
					row.append(",").append(r.countByDegree(r.rawRides(), d));
				}
				for (int d = 2; d <= globalMaxDegree; d++) {
					row.append(",").append(r.countByDegree(r.compressedRides(), d));
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
