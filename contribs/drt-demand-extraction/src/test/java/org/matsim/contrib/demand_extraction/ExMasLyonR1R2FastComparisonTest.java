package org.matsim.contrib.demand_extraction;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
import org.matsim.contrib.demand_extraction.algorithm.exmas.ExMasReferenceEngine;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCacheTestFixture;
import org.matsim.contrib.demand_extraction.algorithm.profiling.ReferenceProgressCheckpointWriter;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;
import org.matsim.contrib.demand_extraction.io.ExMasCsvWriter;
import org.matsim.contrib.demand_extraction.scenarios.AlgorithmProfile;
import org.matsim.contrib.demand_extraction.scenarios.GoldenAsserter;
import org.matsim.contrib.demand_extraction.scenarios.LyonEqasimScenarioFixture;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;

/**
 * Fast R1 vs R2 algorithm comparison that skips the ~20-minute mode-routing-cache
 * phase by loading a pre-computed {@code drt_requests.csv} directly.
 *
 * <p>Both algorithms run single-threaded on identical requests using a shared
 * Dijkstra-based {@link MatsimNetworkCache} (test constructor) and a
 * {@link PassthroughBudgetValidator} that sets remaining budget = request.budget
 * so no scoring context is required.  Routing uses {@link FreeSpeedTravelTime} —
 * this proves <em>algorithmic</em> equivalence under deterministic routing but does
 * <em>not</em> reproduce the 4-pair mismatch seen in the real run (which comes from
 * SpeedyALT vs LeastCostPathTree mixing for uncovered segments).
 *
 * <p>Required env vars:
 * <ul>
 *   <li>{@code LYON_SCENARIO_DIR} — path to Lyon eqasim scenario dir (containing {@code <prefix>config.xml})</li>
 *   <li>{@code LYON_REQUESTS_CSV} — path to a pre-computed {@code drt_requests.csv}</li>
 * </ul>
 * Optional:
 * <ul>
 *   <li>{@code LYON_SCENARIO_PREFIX} — config/network file prefix (default {@code lyon_drt_area_})</li>
 *   <li>{@code LYON_SAMPLE_PCT} — sample percentage, e.g. {@code 1} (default)</li>
 * </ul>
 */
@Tag("scenario-lyon")
@EnabledIfEnvironmentVariable(named = "LYON_SCENARIO_DIR", matches = ".+")
class ExMasLyonR1R2FastComparisonTest {

	private static final Logger log = LogManager.getLogger(ExMasLyonR1R2FastComparisonTest.class);

	@Test
	void r1AndR2ProduceIdenticalCanonicalRideSets() throws Exception {
		String requestsCsv = System.getenv("LYON_REQUESTS_CSV");
		assumeTrue(requestsCsv != null && !requestsCsv.isBlank(),
				"LYON_REQUESTS_CSV required — point to a pre-computed drt_requests.csv");

		String scenarioDir = System.getenv("LYON_SCENARIO_DIR");
		String prefix = System.getenv().getOrDefault("LYON_SCENARIO_PREFIX", "lyon_drt_area_");
		int samplePct = Integer.parseInt(System.getenv().getOrDefault("LYON_SAMPLE_PCT", "1"));

		Path outputDir = Path.of("test/output/lyon-r1r2-fast-comparison");
		Files.createDirectories(outputDir);

		// ── 1. Network only (no population / vehicles / transit) ────────────────
		// travelTimesPath is not used by createConfig() — pass empty string.
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

		// ── 2. Routing — FreeSpeedTravelTime + SpeedyALT via test constructor ────
		// Mirrors the production routing combination (SpeedyALT cache-miss + LeastCostPathTree
		// batch SSSP). The "Deterministic" variant adds the production safeguards
		// (single shared SpeedyALT + time-distance tie-breaking) needed for deterministic routing — the
		// chain script (run_lyon10pct_chain.sh) runs R1 and R2 in *separate* mvn invocations,
		// and without those safeguards a handful of pair rides at the feasibility boundary
		// flip between JVMs (R1 v8: 182,509 pairs vs R2 v9: 182,506 pairs from identical inputs),
		// cascading via the directional shareability graph into per-degree set deltas.
		TravelTime tt = new FreeSpeedTravelTime();
		TravelDisutility td = new OnlyTimeDependentTravelDisutility(tt);
		MatsimNetworkCache cache = MatsimNetworkCacheTestFixture.createWithSpeedyAltRoutingDeterministic(network, tt, td, 900);

		// ── 3. Requests from CSV (bypasses the ~20-min mode-routing-cache phase) ─
		List<DrtRequest> requests = loadRequestsFromCsv(requestsCsv, network);
		log.info("Loaded {} requests from: {}", requests.size(), requestsCsv);

		// ── 4. Shared ExMasConfigGroup ───────────────────────────────────────────
		ExMasConfigGroup exMasConfig = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		exMasConfig.setCalcPredecessors(false);
		exMasConfig.setCalcShapleyValues(false);

		// Optional overrides:
		//   -DmaxPoolingDegree=N    caps every engine (R1, R2, R3, R4) at degree N.
		//   -DmaxPoolingDegreeR1=N  caps R1 only — used by the single-JVM chain so R1 stops
		//                           at d=4 (avoids OOM) while R2/R3/R4 saturate at d=14.
		//   -DmaxPoolingDegreeR2=N / R3 / R4 likewise per-engine.
		// Per-engine value falls back to the global `maxPoolingDegree` (then to the engine's
		// configured default) if not set. Required because the chain runs all four engines
		// in one mvn invocation — a separate JVM per engine drifts via SpeedyALT landmark
		// non-determinism (~10 pairs / 180k = 0.006% at Lyon 10%, but cascades into
		// per-degree set deltas via the directional shareability graph).
		int maxPoolingDegreeOverride = Integer.getInteger("maxPoolingDegree", -1);
		int maxPoolingDegreeR1 = Integer.getInteger("maxPoolingDegreeR1", maxPoolingDegreeOverride);
		int maxPoolingDegreeR2 = Integer.getInteger("maxPoolingDegreeR2", maxPoolingDegreeOverride);
		int maxPoolingDegreeR3 = Integer.getInteger("maxPoolingDegreeR3", maxPoolingDegreeOverride);
		int maxPoolingDegreeR4 = Integer.getInteger("maxPoolingDegreeR4", maxPoolingDegreeOverride);

		// ── 5. Passthrough budget validator ──────────────────────────────────────
		// maxTravelTime (geometric constraint) is enforced by PairGenerator before
		// validateAndPopulateBudgets is called; remaining budget = request.budget
		// avoids needing a scoring context that is absent from CSV-loaded requests.
		BudgetValidator validator = new PassthroughBudgetValidator(exMasConfig, config);

		boolean skipR1 = Boolean.getBoolean("skipR1");
		boolean skipR2 = Boolean.getBoolean("skipR2");
		boolean runR3 = Boolean.getBoolean("runR3");
		boolean runR4 = Boolean.getBoolean("runR4");
		String r1CheckpointCsv = System.getProperty("r1CheckpointCsv");
		boolean allowR1OomForProfiling = Boolean.getBoolean("allowR1OomForProfiling");
		long r1CheckpointIntervalMs = Long.getLong("r1CheckpointIntervalMs", 30_000L);
		boolean r1ProfilingMode = r1CheckpointCsv != null && !r1CheckpointCsv.isBlank();
		Path r1Csv = outputDir.resolve("r1_rides.csv");
		int r1MaxDegree = 0;
		boolean r1Completed = true;

		if (!skipR1) {
			// ── 6. R1: ExMAS reference, parallel ─────────────────────────────────────
			AlgorithmProfile.R1.apply(config);
			// Re-apply maxPoolingDegree override AFTER profile.apply() — the profile resets
			// maxPoolingDegree to Integer.MAX_VALUE, which would otherwise wipe the cap.
			if (maxPoolingDegreeR1 > 0) exMasConfig.setMaxPoolingDegree(maxPoolingDegreeR1);
			exMasConfig.setAlgorithmProcessCount(-1); // -1 = all cores; parallel SpeedyALT is byte-deterministic
			log.info("R1 config: algorithm={}, processCount={}, maxPoolingDegree={}, distScale={}, pruningMode={}, K={}, keepFrac={}",
					exMasConfig.getAlgorithm(), exMasConfig.getAlgorithmProcessCount(), exMasConfig.getMaxPoolingDegree(),
					exMasConfig.getPruningDistanceSavingsLogScale(), exMasConfig.getPruningMode(),
					exMasConfig.getPruningCoverageK(), exMasConfig.getInterDegreeKeepFraction());
			ExMasReferenceEngine engine = null;

			try (ReferenceProgressCheckpointWriter checkpointWriter = r1ProfilingMode
					? new ReferenceProgressCheckpointWriter(Path.of(r1CheckpointCsv))
					: null) {
				try {
					engine = r1ProfilingMode
							? new ExMasReferenceEngine(
									cache, validator,
									exMasConfig.getSearchHorizon(),
									exMasConfig.getMaxPoolingDegree(),
									exMasConfig,
									"r1",
									checkpointWriter,
									r1CheckpointIntervalMs)
							: new ExMasReferenceEngine(
									cache, validator,
									exMasConfig.getSearchHorizon(),
									exMasConfig.getMaxPoolingDegree(),
									exMasConfig);

					List<Ride> r1Rides = engine.run(new ArrayList<>(requests));
					r1MaxDegree = r1Rides.stream().mapToInt(Ride::getDegree).max().orElse(0);
					log.info("R1: {} total rides", r1Rides.size());
					ExMasCsvWriter.writeRides(r1Csv.toString(), r1Rides);
				} catch (OutOfMemoryError error) {
					r1Completed = false;
					if (r1ProfilingMode && allowR1OomForProfiling) {
						boolean wrotePartialRides = false;
						if (engine != null) {
							try {
								wrotePartialRides = engine.writePartialRideSnapshot(r1Csv.toString());
							} catch (RuntimeException snapshotError) {
								log.warn("Failed to write partial R1 rides snapshot to {} after OOM", r1Csv, snapshotError);
							}
						}

						if (wrotePartialRides) {
							log.warn(
									"R1 terminated with OOM in profiling mode; partial rides CSV preserved at {} and checkpoint CSV preserved at {}",
									r1Csv,
									r1CheckpointCsv);
						} else {
							log.warn("R1 terminated with OOM in profiling mode; checkpoint CSV preserved at {}", r1CheckpointCsv);
						}
					} else {
						throw error;
					}
				}
			}
		} else {
			log.info("R1 skipped (-DskipR1=true) — running R2 (BAMAS) only");
		}

		Path r2Csv = outputDir.resolve("r2_rides.csv");
		if (!skipR2) {
			// ── 7. R2: BAMAS no-pruning, parallel ────────────────────────────────────
			// Shares the warmed-up routing cache from R1 — identical routing for any
			// segment already computed; new segments use thread-local SpeedyALT instances.
			AlgorithmProfile.R2.apply(config);
			if (maxPoolingDegreeR2 > 0) exMasConfig.setMaxPoolingDegree(maxPoolingDegreeR2);
			exMasConfig.setAlgorithmProcessCount(-1);
			log.info("R2 config: algorithm={}, processCount={}, maxPoolingDegree={}, distScale={}, pruningMode={}, K={}, keepFrac={}",
					exMasConfig.getAlgorithm(), exMasConfig.getAlgorithmProcessCount(), exMasConfig.getMaxPoolingDegree(),
					exMasConfig.getPruningDistanceSavingsLogScale(), exMasConfig.getPruningMode(),
					exMasConfig.getPruningCoverageK(), exMasConfig.getInterDegreeKeepFraction());

			List<Ride> r2Rides = new BamasEngine(
					cache, validator,
					exMasConfig.getSearchHorizon(),
					exMasConfig.getMaxPoolingDegree(),
					exMasConfig)
					.run(new ArrayList<>(requests));
			log.info("R2: {} total rides", r2Rides.size());
			ExMasCsvWriter.writeRides(r2Csv.toString(), r2Rides);
		} else {
			log.info("R2 skipped (-DskipR2=true)");
		}

		if (runR3) {
			// ── 7b. R3: BAMAS distance-only / heuristic-only pruning (heuristic gate ON,
			// post-extension OFF). This is the first pruning layer after R2, isolating
			// the in-DFS distance gate before adding the top-K post-extension pruner.
			AlgorithmProfile.R3.apply(config);
			if (maxPoolingDegreeR3 > 0) exMasConfig.setMaxPoolingDegree(maxPoolingDegreeR3);
			exMasConfig.setAlgorithmProcessCount(-1);
			log.info("R3 config: algorithm={}, processCount={}, maxPoolingDegree={}, distScale={}, pruningMode={}, K={}, keepFrac={}",
					exMasConfig.getAlgorithm(), exMasConfig.getAlgorithmProcessCount(), exMasConfig.getMaxPoolingDegree(),
					exMasConfig.getPruningDistanceSavingsLogScale(), exMasConfig.getPruningMode(),
					exMasConfig.getPruningCoverageK(), exMasConfig.getInterDegreeKeepFraction());

			List<Ride> r3Rides = new BamasEngine(
					cache, validator,
					exMasConfig.getSearchHorizon(),
					exMasConfig.getMaxPoolingDegree(),
					exMasConfig)
					.run(new ArrayList<>(requests));
			log.info("R3: {} total rides", r3Rides.size());
			Path r3Csv = outputDir.resolve("r3_rides.csv");
			ExMasCsvWriter.writeRides(r3Csv.toString(), r3Rides);
		}

		if (runR4) {
			// ── 7c. R4 paper profile: distance gate scale=0.25 + post-extension COVERAGE_TOPK K=20.
			// Uses R6 config (same gate + K) but disables predecessor routing — predecessors are
			// only needed by the Python MIP pipeline, not for paper comparison runs.
			AlgorithmProfile.R6.apply(config);
			exMasConfig.setCalcPredecessors(false); // R6.apply() sets true; override for comparison
			if (maxPoolingDegreeR4 > 0) exMasConfig.setMaxPoolingDegree(maxPoolingDegreeR4);
			exMasConfig.setAlgorithmProcessCount(-1);
			log.info("R4 config: algorithm={}, processCount={}, maxPoolingDegree={}, distScale={}, pruningMode={}, K={}, keepFrac={}",
					exMasConfig.getAlgorithm(), exMasConfig.getAlgorithmProcessCount(), exMasConfig.getMaxPoolingDegree(),
					exMasConfig.getPruningDistanceSavingsLogScale(), exMasConfig.getPruningMode(),
					exMasConfig.getPruningCoverageK(), exMasConfig.getInterDegreeKeepFraction());

			List<Ride> r4Rides = new BamasEngine(
					cache, validator,
					exMasConfig.getSearchHorizon(),
					exMasConfig.getMaxPoolingDegree(),
					exMasConfig)
					.run(new ArrayList<>(requests));
			log.info("R4: {} total rides", r4Rides.size());
			Path r4Csv = outputDir.resolve("r4_rides.csv");
			ExMasCsvWriter.writeRides(r4Csv.toString(), r4Rides);
		}

		if (!skipR1 && !skipR2 && r1Completed) {
			// ── 8. Compare canonical ride sets ───────────────────────────────────────
			// relTol=1e-9: with identical SpeedyALT routing and a shared cache, distances
			// for the same canonical set must be bit-identical between R1 and R2.
			GoldenAsserter.assertEquivalent(r1Csv, r2Csv, 1e-9, r1MaxDegree);
		} else if (!skipR1 && !skipR2 && !r1Completed) {
			log.info("Skipping R1/R2 canonical equality assert because R1 ended with OOM in profiling mode.");
		}
	}

	/**
	 * Parse {@code drt_requests.csv} into {@link DrtRequest} objects.
	 * Link endpoint coordinates (used for B&B lower bounds) are looked up from
	 * the network; all other fields come directly from the CSV.
	 */
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
				// maxTravelTime = directTravelTime * maxDetourFactor; recover the factor
				double maxDetour = directTT > 0 ? maxTT / directTT : 1.3;
				// Cap detour at 1.3 to shrink feasible-pair search space (Lyon 10% R1 OOMs at d4 with 1.5).
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

	/**
	 * Budget validator that sets remaining budget = request.budget for every passenger,
	 * making all geometrically-feasible rides pass budget validation without needing
	 * the scoring context that is absent from CSV-loaded requests.
	 *
	 * <p>This is correct because {@code maxTravelTime} (the geometric detour bound) was
	 * derived from the budget via {@code BudgetToConstraintsCalculator}; satisfying
	 * the geometric constraint implies the budget constraint is satisfied.
	 */
	private static final class PassthroughBudgetValidator extends BudgetValidator {

		PassthroughBudgetValidator(ExMasConfigGroup exMasConfig, Config config) {
			// adapter and scoringParametersForPerson unused — all budget methods overridden
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
			// In-place mutation: critical at 30M+ rides where the rebuild path OOMs at 64GB.
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
