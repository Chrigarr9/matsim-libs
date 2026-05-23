package org.matsim.contrib.demand_extraction;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
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
import org.matsim.contrib.demand_extraction.algorithm.bamas.extension.EnumerationStats;
import org.matsim.contrib.demand_extraction.algorithm.bamas.extension.OrderingEnumerator;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideKind;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;
import org.matsim.contrib.demand_extraction.algorithm.generation.PairGenerator;
import org.matsim.contrib.demand_extraction.algorithm.graph.ShareabilityGraph;
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
 * Diagnostic test for the d=3 enumeration gap discovered on Lyon 10%
 * (see {@code .planning/2026-04-28-d3-enumeration-gap-investigation.md}).
 *
 * <p>2,735 d=3 sub-sets are missing from BOTH R1 and R2 d=3 catalogs despite
 * all three required d=2 pair edges existing in both. R1 still emits 3,445
 * d=4 sets containing those missing sub-sets because R1 does not enforce
 * sub-set monotonicity. The question this test answers: is R2's d=3
 * enumeration over-pruning (soundness bug, hypotheses H1/H2) or is the set
 * genuinely infeasible at d=3 with R1's d=4 admission anti-monotone (H3/H4)?
 *
 * <p>Test target: triangle {@code [124, 607, 1637]} — the d=3 sub-set of R1-only
 * d=4 set {@code [124, 607, 697, 1637]}. Req 607 is the largest R1-only cluster
 * (224 of 2,735 missing sub-sets contain it). The other 3 d=3 sub-sets of
 * {@code [124, 607, 697, 1637]} are present in both catalogs.
 *
 * <p>What this test does:
 * <ol>
 *   <li>Loads the Lyon 10% scenario + drt_requests.csv (mirrors
 *       {@link ExMasLyonR1R2FastComparisonTest}).</li>
 *   <li>Filters to just {@code {124, 607, 1637}}.</li>
 *   <li>Runs PairGenerator + builds the ShareabilityGraph.</li>
 *   <li>Reports the pair-edge directions and FIFO/LIFO kinds.</li>
 *   <li>Calls {@link OrderingEnumerator#enumerate} (no pruning) — gets every
 *       constraint-feasible (origin, dest) ordering.</li>
 *   <li>For each ordering, routes the segments, computes per-passenger
 *       in-vehicle times and delays, runs {@code optimizeDelays}, reports
 *       VALID / FAILS_MAX_TT / FAILS_DELAY_OPTIMIZATION.</li>
 *   <li>Calls {@link OrderingEnumerator#enumerateAndEvaluateSeeded} with
 *       {@code bestValidDist=Double.MAX_VALUE} and a parent seed; captures
 *       every {@link EnumerationStats} counter.</li>
 *   <li>Prints a discriminator summary identifying which hypothesis the
 *       data confirms (H1/H2/H3/H4).</li>
 * </ol>
 *
 * <p>Required env vars: {@code LYON_SCENARIO_DIR} + {@code LYON_REQUESTS_CSV}.
 * Optional: {@code LYON_SCENARIO_PREFIX} (default {@code lyon_drt_area_}),
 * {@code LYON_SAMPLE_PCT} (default 1), {@code LYON_TARGET_SET} (comma-sep,
 * default {@code 124,607,1637}).
 */
@Tag("scenario-lyon")
@EnabledIfEnvironmentVariable(named = "LYON_SCENARIO_DIR", matches = ".+")
class D3EnumerationGapDiagnosticTest {

	private static final Logger log = LogManager.getLogger(D3EnumerationGapDiagnosticTest.class);
	private static final double TIME_FEASIBILITY_EPSILON = 1.0;

	@Test
	void diagnoseD3EnumerationGap() throws Exception {
		String requestsCsv = System.getenv("LYON_REQUESTS_CSV");
		assumeTrue(requestsCsv != null && !requestsCsv.isBlank(),
				"LYON_REQUESTS_CSV required");

		String scenarioDir = System.getenv("LYON_SCENARIO_DIR");
		String prefix = System.getenv().getOrDefault("LYON_SCENARIO_PREFIX", "lyon_drt_area_");
		int samplePct = Integer.parseInt(System.getenv().getOrDefault("LYON_SAMPLE_PCT", "1"));

		// Default target: the [124, 607, 1637] triangle (req 607 = largest R1-only cluster).
		// Override with -DtargetSet=a,b,c or env LYON_TARGET_SET to investigate other triangles.
		String targetSetSpec = System.getProperty("targetSet",
				System.getenv().getOrDefault("LYON_TARGET_SET", "124,607,1637"));
		int[] targetIndices = Arrays.stream(targetSetSpec.split(","))
				.map(String::trim)
				.mapToInt(Integer::parseInt)
				.sorted()
				.toArray();
		log.info("================================================================");
		log.info("d=3 ENUMERATION GAP DIAGNOSTIC — target triangle {}",
				Arrays.toString(targetIndices));
		log.info("================================================================");

		// ── 1. Network only ───────────────────────────────────────────────────
		LyonEqasimScenarioFixture fixture = new LyonEqasimScenarioFixture(
				samplePct, scenarioDir, prefix, "");
		Config config = fixture.createConfig(java.nio.file.Path.of("test/output/d3-gap-diagnostic"));
		config.plans().setInputFile(null);
		config.vehicles().setVehiclesFile(null);
		config.facilities().setInputFile(null);
		config.transit().setTransitScheduleFile(null);
		config.transit().setVehiclesFile(null);

		Scenario scenario = ScenarioUtils.createScenario(config);
		ScenarioUtils.loadScenario(scenario);
		Network network = scenario.getNetwork();
		log.info("Loaded network: {} links", network.getLinks().size());

		// ── 2. Routing — mirrors the comparison test's deterministic SpeedyALT ─
		TravelTime tt = new FreeSpeedTravelTime();
		TravelDisutility td = new OnlyTimeDependentTravelDisutility(tt);
		MatsimNetworkCache cache = MatsimNetworkCacheTestFixture
				.createWithSpeedyAltRoutingDeterministic(network, tt, td, 900);

		// ── 3. Requests from CSV — filter to target triangle ──────────────────
		List<DrtRequest> allRequests = loadRequestsFromCsv(requestsCsv, network);
		List<DrtRequest> targetRequests = new ArrayList<>();
		for (int idx : targetIndices) {
			DrtRequest match = null;
			for (DrtRequest r : allRequests) {
				if (r.index == idx) { match = r; break; }
			}
			if (match == null) {
				throw new IllegalStateException("Request index " + idx + " not found in " + requestsCsv);
			}
			targetRequests.add(match);
		}
		log.info("Loaded {} target requests", targetRequests.size());
		for (DrtRequest r : targetRequests) {
			log.info("  Request {}: origin={}, dest={}, requestTime={}, "
					+ "directTT={}, maxTT={}, maxDetour={}, "
					+ "maxNegDelay={}, maxPosDelay={}, "
					+ "negRelComp={}, posRelComp={}",
					r.index, r.originLinkId, r.destinationLinkId,
					String.format("%.0f", r.getRequestTime()),
					String.format("%.0f", r.getTravelTime()),
					String.format("%.0f", r.getMaxTravelTime()),
					String.format("%.3f", r.maxDetourFactor),
					String.format("%.0f", r.getMaxNegativeDelay()),
					String.format("%.0f", r.getMaxPositiveDelay()),
					String.format("%.0f", r.getNegativeDelayRelComponent()),
					String.format("%.0f", r.getPositiveDelayRelComponent()));
		}

		// ── 4. ExMasConfigGroup with R2 setup (BAMAS, no pruning) ─────────────
		ExMasConfigGroup exMasConfig = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		exMasConfig.setCalcShapleyValues(false);
		// R2 = BAMAS, no pruning.
		exMasConfig.setAlgorithm(ExMasConfigGroup.Algorithm.BAMAS);
		exMasConfig.setHeuristicPruningEnabled(false);
		exMasConfig.setPruningDistanceSavingsLogScale(-1.0);
		exMasConfig.setPruningMode(ExMasConfigGroup.PruningMode.RATIO_THRESHOLD);
		exMasConfig.setInterDegreeKeepFraction(1.0);
		exMasConfig.clearPruningCoverageKByDegree();
		exMasConfig.setCalcPredecessors(false);
		exMasConfig.setMaxPoolingDegree(Integer.MAX_VALUE);
		exMasConfig.setAlgorithmProcessCount(1);

		BudgetValidator validator = new ExMasLyonR1R2FastComparisonTestPassthroughValidator(
				exMasConfig, config);

		// ── 5. PairGenerator on the 3 requests ───────────────────────────────
		log.info("");
		log.info("─── PHASE: pair generation ───");
		PairGenerator pairGen = new PairGenerator(cache, validator,
				exMasConfig.getSearchHorizon(), 1);
		List<Ride> pairRides = pairGen.generatePairs(targetRequests);
		log.info("Pair rides: {} total", pairRides.size());

		// ── 6. ShareabilityGraph from pair rides ────────────────────────────
		ShareabilityGraph graph = buildGraph(pairRides);
		log.info("ShareabilityGraph: {} edges, {} nodes",
				graph.getEdgeCount(), graph.getNodeCount());

		// ── 7. Pair-edge structure ──────────────────────────────────────────
		log.info("");
		log.info("─── PAIR-EDGE STRUCTURE ───");
		printPairEdges(graph, targetIndices);

		// Sanity: are all 3 pair edges present (in EITHER direction)?
		boolean allPresent = true;
		for (int i = 0; i < targetIndices.length; i++) {
			for (int j = i + 1; j < targetIndices.length; j++) {
				int a = targetIndices[i], b = targetIndices[j];
				boolean fwd = graph.getEdges(a, b).size() > 0;
				boolean rev = graph.getEdges(b, a).size() > 0;
				if (!fwd && !rev) {
					log.error("  PAIR ({},{}) HAS NO EDGE IN EITHER DIRECTION!", a, b);
					allPresent = false;
				}
			}
		}
		log.info("All 3 pair edges present: {}", allPresent);

		// ── 8. Unpruned enumeration ─────────────────────────────────────────
		log.info("");
		log.info("─── UNPRUNED ENUMERATION (OrderingEnumerator.enumerate) ───");
		List<OrderingEnumerator.Ordering> unpruned =
				OrderingEnumerator.enumerate(targetIndices, graph);
		log.info("Unpruned: {} constraint-feasible orderings", unpruned.size());

		DrtRequest[] reqArray = targetRequests.toArray(new DrtRequest[0]);
		int validCount = 0;
		int failsMaxTtCount = 0;
		int failsDelayCount = 0;
		double bestValidDistanceUnpruned = Double.POSITIVE_INFINITY;
		java.util.Set<String> validOrderingKeys = new java.util.HashSet<>();
		for (int o = 0; o < unpruned.size(); o++) {
			OrderingEnumerator.Ordering ord = unpruned.get(o);
			OrderingEvaluation eval = evaluateOrdering(ord, reqArray, cache);
			log.info("  [{}] origin={}, dest={}: status={}, dist={}, "
					+ "ptt={}, max_ratio={}, optimizeDelays={}",
					o,
					formatPerm(ord.originPerm(), targetIndices),
					formatPerm(ord.destPerm(), targetIndices),
					eval.status, String.format("%.0f", eval.totalDistance),
					Arrays.toString(formatArr0(eval.pttActual)),
					String.format("%.3f", eval.maxDetourRatioObserved),
					eval.optimizeDelaysOk ? "OK" : "FAIL");
			if (eval.status == OrderingStatus.VALID) {
				validCount++;
				bestValidDistanceUnpruned = Math.min(bestValidDistanceUnpruned, eval.totalDistance);
				validOrderingKeys.add(orderingKey(ord));
			} else if (eval.status == OrderingStatus.FAILS_MAX_TT) {
				failsMaxTtCount++;
			} else {
				failsDelayCount++;
			}
		}
		log.info("Unpruned summary: VALID={}, FAILS_MAX_TT={}, FAILS_DELAY={}, total={}",
				validCount, failsMaxTtCount, failsDelayCount, unpruned.size());
		if (validCount > 0) {
			log.info("Best-valid unpruned distance: {}",
					String.format("%.0f", bestValidDistanceUnpruned));
		}

		// ── 8b. Manual replay of the delay-window over-approximation ─────────
		// For unpruned orderings that are VALID, walk the SAME over-approximation
		// the seeded DFS uses and print [L,U] at each depth so we can see
		// whether the over-approximation closes the interval prematurely.
		log.info("");
		log.info("─── DELAY-WINDOW OVER-APPROXIMATION REPLAY ───");
		if (!unpruned.isEmpty()) {
			OrderingEnumerator.Ordering firstOrd = unpruned.get(0);
			int[] originPerm = firstOrd.originPerm();
			log.info("Replaying for origin order: {}",
					formatPerm(originPerm, targetIndices));
			double startTime = reqArray[originPerm[0]].getRequestTime();
			double currentTime = startTime;
			double L = Double.NEGATIVE_INFINITY;
			double U = Double.POSITIVE_INFINITY;
			for (int depth = 0; depth < originPerm.length; depth++) {
				DrtRequest reqC = reqArray[originPerm[depth]];
				double delayC;
				if (depth == 0) {
					delayC = 0;
				} else {
					DrtRequest prev = reqArray[originPerm[depth - 1]];
					TravelSegment seg = cache.getSegment(prev.originLinkId,
							reqC.originLinkId, currentTime);
					currentTime += seg.getTravelTime();
					delayC = currentTime - reqC.getRequestTime();
				}
				double newLowC = -delayC - reqC.getMaxNegativeDelay();
				double newHighC = (reqC.getMaxPositiveDelay()
						- Math.max(0.0, reqC.getPositiveDelayRelComponent())) - delayC;
				L = Math.max(L, newLowC);
				U = Math.min(U, newHighC);
				log.info("  depth={}: place req {} (delayC={}), contribution=[{},{}], "
						+ "running [L,U]=[{},{}], feasible={}",
						depth, reqC.index,
						String.format("%.0f", delayC),
						String.format("%.0f", newLowC),
						String.format("%.0f", newHighC),
						String.format("%.0f", L),
						String.format("%.0f", U),
						L <= U + 1e-6);
			}
			log.info("Final over-approximation interval [L,U] = [{},{}], width={}",
					String.format("%.0f", L), String.format("%.0f", U),
					String.format("%.0f", U - L));

			// Also: replay TRUE optimizeDelays for the first VALID ordering to
			// compare. Pick first VALID for direct comparison.
			OrderingEnumerator.Ordering validOrd = null;
			for (OrderingEnumerator.Ordering o : unpruned) {
				OrderingEvaluation e = evaluateOrdering(o, reqArray, cache);
				if (e.status == OrderingStatus.VALID) { validOrd = o; break; }
			}
			if (validOrd != null) {
				log.info("Comparing to TRUE optimize for ordering: origin={}, dest={}",
						formatPerm(validOrd.originPerm(), targetIndices),
						formatPerm(validOrd.destPerm(), targetIndices));
				int n = reqArray.length;
				DrtRequest[] originsOrdered = new DrtRequest[n];
				DrtRequest[] destsOrdered = new DrtRequest[n];
				for (int i = 0; i < n; i++) {
					originsOrdered[i] = reqArray[validOrd.originPerm()[i]];
					destsOrdered[i] = reqArray[validOrd.destPerm()[i]];
				}
				@SuppressWarnings("unchecked")
				Id<Link>[] sequence = (Id<Link>[]) new Id[n * 2];
				for (int i = 0; i < n; i++) sequence[i] = originsOrdered[i].originLinkId;
				for (int i = 0; i < n; i++) sequence[n + i] = destsOrdered[i].destinationLinkId;

				double[] connTT = new double[n * 2 - 1];
				double t = startTime;
				for (int i = 0; i < n * 2 - 1; i++) {
					TravelSegment seg = cache.getSegment(sequence[i], sequence[i + 1], t);
					connTT[i] = seg.getTravelTime();
					t += seg.getTravelTime();
				}
				log.info("  Routed connTT = {}", Arrays.toString(formatArr0(connTT)));

				// True (exact) per-passenger contribution
				double Lexact = Double.NEGATIVE_INFINITY;
				double Uexact = Double.POSITIVE_INFINITY;
				for (int i = 0; i < n; i++) {
					DrtRequest req = originsOrdered[i];
					int origIdx = i;
					int destPosInDestArray = -1;
					for (int k = 0; k < n; k++) {
						if (destsOrdered[k].index == req.index) { destPosInDestArray = k; break; }
					}
					int destIdx = n + destPosInDestArray;
					double ptt = 0;
					for (int j = origIdx; j < destIdx; j++) ptt += connTT[j];
					if (ptt < req.getTravelTime() - 1e-9) ptt = req.getTravelTime();
					double detour = ptt - req.getTravelTime();
					double posAdj = req.getPositiveDelayRelComponent() > 0
							? Math.max(0.0, req.getPositiveDelayRelComponent() - detour) : 0.0;
					double negAdj = req.getNegativeDelayRelComponent() > 0
							? Math.max(0.0, req.getNegativeDelayRelComponent() - detour) : 0.0;
					double effMaxPos = (req.getMaxPositiveDelay() - detour) - posAdj;
					double effMaxNeg = req.getMaxNegativeDelay() - negAdj;
					double pickupTime = startTime;
					for (int j = 0; j < origIdx; j++) pickupTime += connTT[j];
					double delay = pickupTime - req.getRequestTime();
					double Lc = -delay - effMaxNeg;
					double Uc = effMaxPos - delay;
					Lexact = Math.max(Lexact, Lc);
					Uexact = Math.min(Uexact, Uc);
					log.info("    req {}: ptt={}, detour={}, effMaxPos={}, "
							+ "delay={}, contribution=[{},{}]",
							req.index, String.format("%.0f", ptt),
							String.format("%.0f", detour),
							String.format("%.0f", effMaxPos),
							String.format("%.0f", delay),
							String.format("%.0f", Lc),
							String.format("%.0f", Uc));
				}
				log.info("  EXACT [L,U]=[{},{}], width={}",
						String.format("%.0f", Lexact),
						String.format("%.0f", Uexact),
						String.format("%.0f", Uexact - Lexact));
				log.info("  OVER-APPROX [L,U] should be SUPERSET of EXACT — checking:");
				log.info("    OVER-APPROX L={}, EXACT L={}: over-approx ≤ exact ? {}",
						String.format("%.0f", L), String.format("%.0f", Lexact),
						L <= Lexact + 1e-6);
				log.info("    OVER-APPROX U={}, EXACT U={}: over-approx ≥ exact ? {}",
						String.format("%.0f", U), String.format("%.0f", Uexact),
						U >= Uexact - 1e-6);
			}
		}

		// ── 9. Seeded-pruned enumeration ─────────────────────────────────────
		log.info("");
		log.info("─── SEEDED-PRUNED ENUMERATION (enumerateAndEvaluateSeeded) ───");
		EnumerationStats.reset();
		EnumerationStats stats = EnumerationStats.get();

		// Pick a parent seed: any d=2 pair ride among the target's first two requests.
		Ride parentRide = pickParentRide(pairRides, targetIndices);
		log.info("Parent seed: ride index={}, kind={}, requestSet={}, distance={}",
				parentRide.getIndex(), parentRide.getKind(),
				Arrays.toString(parentRide.getRequestIndices()),
				String.format("%.0f", parentRide.getRideDistance()));

		int[] seedParentOrigin = parentRide.getOriginsIndex();
		int[] seedParentDest = parentRide.getDestinationsIndex();
		int seedNewRequest = -1;
		for (int idx : targetIndices) {
			boolean inParent = false;
			for (int p : parentRide.getRequestIndices()) {
				if (p == idx) { inParent = true; break; }
			}
			if (!inParent) { seedNewRequest = idx; break; }
		}
		log.info("Seed parent origin={}, dest={}, newRequest={}",
				Arrays.toString(seedParentOrigin), Arrays.toString(seedParentDest),
				seedNewRequest);

		List<OrderingEnumerator.Ordering> seededVisited = new ArrayList<>();
		double[] bestValidDist = { Double.MAX_VALUE };
		OrderingEnumerator.enumerateAndEvaluateSeeded(
				targetIndices, graph, cache, reqArray, bestValidDist,
				seedParentOrigin, seedParentDest, seedNewRequest,
				/* budgetAwareConstraints= */ false,
				ord -> seededVisited.add(ord));

		log.info("Seeded-pruned: {} orderings reached evaluator", seededVisited.size());
		for (int o = 0; o < seededVisited.size(); o++) {
			OrderingEnumerator.Ordering ord = seededVisited.get(o);
			log.info("  [{}] origin={}, dest={}, partialDist={}",
					o,
					formatPerm(ord.originPerm(), targetIndices),
					formatPerm(ord.destPerm(), targetIndices),
					String.format("%.0f", ord.rideDistance()));
		}

		log.info("");
		log.info("─── PRUNING-SITE COUNTERS ───");
		log.info("  prunedByTravelTime:        {}", stats.prunedByTravelTime);
		log.info("  prunedByDropoffCheck:      {}", stats.prunedByDropoffCheck);
		log.info("  prunedByDelayWindowOrigin: {}", stats.prunedByDelayWindowOrigin);
		log.info("  prunedByDelayWindowDropoff:{}", stats.prunedByDelayWindowDropoff);
		log.info("  bnbOriginCuts:             {} (skipped candidates: {})",
				stats.bnbOriginCuts, stats.bnbOriginSkippedCandidates);
		log.info("  bnbDestCuts:               {} (skipped candidates: {})",
				stats.bnbDestCuts, stats.bnbDestSkippedCandidates);
		log.info("  bnbOriginLbCuts:           {}", stats.bnbOriginLbCuts);
		log.info("  bnbDestLbCuts:             {}", stats.bnbDestLbCuts);

		// ── 10. Discriminator ─────────────────────────────────────────────────
		// Compare the SET of valid orderings (unpruned) to the SET of orderings
		// the seeded enumerator reached. The seeded path is sound iff every
		// unpruned-valid ordering appears in seededVisited.
		java.util.Set<String> seededKeys = new java.util.HashSet<>();
		for (OrderingEnumerator.Ordering o : seededVisited) seededKeys.add(orderingKey(o));
		java.util.Set<String> validMissingFromSeeded = new java.util.HashSet<>(validOrderingKeys);
		validMissingFromSeeded.removeAll(seededKeys);

		log.info("");
		log.info("─── DISCRIMINATOR ───");
		log.info("Unpruned: {} constraint-feasible orderings, {} VALID (pass routing+delays)",
				unpruned.size(), validCount);
		log.info("Seeded:   {} orderings reached evaluator", seededVisited.size());
		log.info("Valid orderings missing from seeded path: {}", validMissingFromSeeded.size());
		if (unpruned.isEmpty()) {
			log.info("CONCLUSION: H3 — set is structurally infeasible at d=3.");
			log.info("  Constraint DAG admits zero topological sorts. Pair-edge");
			log.info("  directions/cycles prevent any ordering. R1's d=4 admission");
			log.info("  goes via a different base whose direction structure works.");
			log.info("  The set is NOT physically feasible at d=3 — R2 correctly rejects.");
		} else if (validCount == 0) {
			log.info("CONCLUSION: H4 — set is genuinely infeasible at d=3 (validation).");
			log.info("  Constraint DAG admits {} ordering(s), but all fail max-TT or", unpruned.size());
			log.info("  delay-window optimization. Adding the 4th request to make d=4");
			log.info("  feasible is anti-monotone: removing any of {0,1,2,3} from a");
			log.info("  feasible 4-tuple does NOT yield a feasible 3-tuple.");
			log.info("  No soundness bug. Either: (a) accept R2's monotone catalog");
			log.info("  (lose ~0.1% of d=4 sets), (b) relax DegreeGraph to match R1.");
		} else if (validMissingFromSeeded.isEmpty()) {
			log.info("CONCLUSION: SEEDED ENUMERATOR IS SOUND.");
			log.info("  All {} valid orderings reached the seeded evaluator.", validCount);
			log.info("  R2 admits this triangle (it's in the d=3 catalog).");
			log.info("  If R2 still excluded this triangle from d=3, look at downstream");
			log.info("  filters (post-extension pruning, set-hash dedup, parent canonical");
			log.info("  ordering). Otherwise the test target is mis-selected.");
		} else {
			log.info("CONCLUSION: H1 or H2 — soundness bug in seeded enumerator.");
			log.info("  {} unpruned-valid ordering(s) NOT reached by seeded path:",
					validMissingFromSeeded.size());
			for (String k : validMissingFromSeeded) log.info("    {}", k);
			log.info("  Pruning-site counter that fired:");
			if (stats.prunedByDelayWindowOrigin > 0) {
				log.info("  → H1 candidate: prunedByDelayWindowOrigin={}",
						stats.prunedByDelayWindowOrigin);
			}
			if (stats.bnbOriginLbCuts + stats.bnbDestLbCuts > 0) {
				log.info("  → H2 candidate: bnbOriginLbCuts={}, bnbDestLbCuts={}",
						stats.bnbOriginLbCuts, stats.bnbDestLbCuts);
			}
			if (stats.prunedByTravelTime + stats.prunedByDropoffCheck > 0) {
				log.info("  → travel-time/dropoff cuts: prunedByTravelTime={}, prunedByDropoffCheck={}",
						stats.prunedByTravelTime, stats.prunedByDropoffCheck);
			}
		}
		log.info("================================================================");
	}

	// ── helpers ───────────────────────────────────────────────────────────────

	private enum OrderingStatus { VALID, FAILS_MAX_TT, FAILS_DELAY_OPTIMIZATION }

	private static final class OrderingEvaluation {
		OrderingStatus status;
		double totalDistance;
		double[] pttActual;
		double maxDetourRatioObserved;
		boolean optimizeDelaysOk;
	}

	/**
	 * Evaluate an ordering manually: route every segment, compute per-passenger
	 * in-vehicle time, check max-TT cap, run optimizeDelays. This is a stripped
	 * copy of {@code BamasRideExtender.buildRideFromOrdering} that avoids private
	 * access and adds explicit success/failure reporting.
	 */
	private static OrderingEvaluation evaluateOrdering(
			OrderingEnumerator.Ordering ord, DrtRequest[] reqArray,
			MatsimNetworkCache cache) {
		OrderingEvaluation result = new OrderingEvaluation();
		int n = reqArray.length;
		int[] originPerm = ord.originPerm();
		int[] destPerm = ord.destPerm();

		DrtRequest[] originsOrdered = new DrtRequest[n];
		DrtRequest[] destsOrdered = new DrtRequest[n];
		for (int i = 0; i < n; i++) {
			originsOrdered[i] = reqArray[originPerm[i]];
			destsOrdered[i] = reqArray[destPerm[i]];
		}

		@SuppressWarnings("unchecked")
		Id<Link>[] sequence = (Id<Link>[]) new Id[n * 2];
		for (int i = 0; i < n; i++) sequence[i] = originsOrdered[i].originLinkId;
		for (int i = 0; i < n; i++) sequence[n + i] = destsOrdered[i].destinationLinkId;

		double[] connTT = new double[n * 2 - 1];
		double[] connDist = new double[n * 2 - 1];
		double startTime = originsOrdered[0].getRequestTime();
		double currentTime = startTime;
		double totalDist = 0;
		for (int i = 0; i < n * 2 - 1; i++) {
			TravelSegment seg = cache.getSegment(sequence[i], sequence[i + 1], currentTime);
			if (!seg.isReachable()) {
				result.status = OrderingStatus.FAILS_MAX_TT; // route gap = treat as infeasible
				result.totalDistance = Double.NaN;
				return result;
			}
			connTT[i] = seg.getTravelTime();
			connDist[i] = seg.getDistance();
			totalDist += seg.getDistance();
			currentTime += seg.getTravelTime();
		}
		result.totalDistance = totalDist;

		double[] pttActual = new double[n];
		double maxRatio = 0;
		for (int i = 0; i < n; i++) {
			DrtRequest req = originsOrdered[i];
			int origIdx = i;
			int destPosInDestArray = -1;
			for (int k = 0; k < n; k++) {
				if (destsOrdered[k].index == req.index) { destPosInDestArray = k; break; }
			}
			int destIdx = n + destPosInDestArray;
			for (int j = origIdx; j < destIdx; j++) pttActual[i] += connTT[j];
			// Floor pttActual to directTT — mirrors BamasRideExtender.buildRideFromOrdering
			// line 545. Without this floor, geometric routing quirks (intermediate
			// route shorter than direct) produce a NEGATIVE detour which incorrectly
			// inflates effMaxPos in optimizeDelays.
			if (pttActual[i] < req.getTravelTime() - 1e-9) {
				pttActual[i] = req.getTravelTime();
			}
			double ratio = pttActual[i] / req.getTravelTime();
			if (ratio > maxRatio) maxRatio = ratio;
			if (pttActual[i] > req.getMaxTravelTime() + TIME_FEASIBILITY_EPSILON) {
				result.status = OrderingStatus.FAILS_MAX_TT;
				result.pttActual = pttActual;
				result.maxDetourRatioObserved = maxRatio;
				return result;
			}
		}
		result.pttActual = pttActual;
		result.maxDetourRatioObserved = maxRatio;

		// optimizeDelays — same logic as BamasRideExtender.optimizeDelays
		double[] delays = new double[n];
		double arrivalAtOrigin = startTime;
		for (int i = 0; i < n; i++) {
			delays[i] = arrivalAtOrigin - originsOrdered[i].getRequestTime();
			if (i < n - 1) arrivalAtOrigin += connTT[i];
		}
		double[] effMaxNeg = new double[n];
		double[] effMaxPos = new double[n];
		for (int i = 0; i < n; i++) {
			DrtRequest req = originsOrdered[i];
			double detourTime = pttActual[i] - req.getTravelTime();
			double posAdj = req.getPositiveDelayRelComponent() > 0
					? Math.max(0.0, req.getPositiveDelayRelComponent() - detourTime) : 0.0;
			double negAdj = req.getNegativeDelayRelComponent() > 0
					? Math.max(0.0, req.getNegativeDelayRelComponent() - detourTime) : 0.0;
			effMaxPos[i] = (req.getMaxPositiveDelay() - detourTime) - posAdj;
			effMaxNeg[i] = req.getMaxNegativeDelay() - negAdj;
		}
		boolean delaysOk = optimizeDelaysCheck(delays, effMaxNeg, effMaxPos);
		result.optimizeDelaysOk = delaysOk;
		result.status = delaysOk ? OrderingStatus.VALID : OrderingStatus.FAILS_DELAY_OPTIMIZATION;
		return result;
	}

	private static boolean optimizeDelaysCheck(double[] delays, double[] maxNeg, double[] maxPos) {
		for (int i = 0; i < delays.length; i++) {
			if (maxPos[i] < -maxNeg[i] - TIME_FEASIBILITY_EPSILON) return false;
		}
		double lower = Double.NEGATIVE_INFINITY, upper = Double.POSITIVE_INFINITY;
		for (int i = 0; i < delays.length; i++) {
			lower = Math.max(lower, -delays[i] - maxNeg[i]);
			upper = Math.min(upper, maxPos[i] - delays[i]);
		}
		if (lower > upper + TIME_FEASIBILITY_EPSILON) return false;

		double maxDelay = Double.NEGATIVE_INFINITY, minDelay = Double.POSITIVE_INFINITY;
		for (double delay : delays) {
			maxDelay = Math.max(maxDelay, delay);
			minDelay = Math.min(minDelay, delay);
		}

		double depOpt = -(maxDelay + minDelay) / 2.0;
		depOpt = Math.max(lower, Math.min(upper, depOpt));

		for (int i = 0; i < delays.length; i++) {
			double adjusted = delays[i] + depOpt;
			if (adjusted < -maxNeg[i] - TIME_FEASIBILITY_EPSILON
					|| adjusted > maxPos[i] + TIME_FEASIBILITY_EPSILON) return false;
		}
		return true;
	}

	private static ShareabilityGraph buildGraph(List<Ride> pairRides) {
		int initialCapacity = Math.max(1, pairRides.size() * 2);
		ShareabilityGraph.Builder builder = ShareabilityGraph.builder(initialCapacity);
		for (Ride ride : pairRides) {
			if (ride.getDegree() != 2) continue;
			int reqI = ride.getRequestIndices()[0];
			int reqJ = ride.getRequestIndices()[1];
			byte kind = ride.getKind() == RideKind.FIFO
					? ShareabilityGraph.KIND_FIFO : ShareabilityGraph.KIND_LIFO;
			builder.addEdge(reqI, reqJ, ride.getIndex(), kind);
		}
		return builder.build();
	}

	private static void printPairEdges(ShareabilityGraph graph, int[] indices) {
		for (int i = 0; i < indices.length; i++) {
			for (int j = i + 1; j < indices.length; j++) {
				int a = indices[i], b = indices[j];
				it.unimi.dsi.fastutil.ints.IntList[] fwd = graph.getEdgesWithKinds(a, b);
				it.unimi.dsi.fastutil.ints.IntList[] rev = graph.getEdgesWithKinds(b, a);
				log.info("  pair ({},{}): forward edges={} (kinds={}), reverse edges={} (kinds={})",
						a, b, fwd[0], formatKinds(fwd[1]), rev[0], formatKinds(rev[1]));
			}
		}
	}

	private static String formatKinds(it.unimi.dsi.fastutil.ints.IntList kinds) {
		if (kinds.isEmpty()) return "[]";
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < kinds.size(); i++) {
			if (i > 0) sb.append(",");
			sb.append(kinds.getInt(i) == ShareabilityGraph.KIND_FIFO ? "FIFO" : "LIFO");
		}
		sb.append("]");
		return sb.toString();
	}

	private static String orderingKey(OrderingEnumerator.Ordering ord) {
		return Arrays.toString(ord.originPerm()) + "|" + Arrays.toString(ord.destPerm());
	}

	private static String formatPerm(int[] localPerm, int[] globalIndices) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < localPerm.length; i++) {
			if (i > 0) sb.append(",");
			sb.append(globalIndices[localPerm[i]]);
		}
		sb.append("]");
		return sb.toString();
	}

	private static String[] formatArr0(double[] arr) {
		if (arr == null) return new String[]{"null"};
		String[] out = new String[arr.length];
		for (int i = 0; i < arr.length; i++) out[i] = String.format("%.0f", arr[i]);
		return out;
	}

	/**
	 * Pick a d=2 pair ride to use as the parent seed. Prefer the FIFO pair
	 * involving the first two indices; fall back to any d=2 ride.
	 */
	private static Ride pickParentRide(List<Ride> pairRides, int[] targetIndices) {
		int a = targetIndices[0], b = targetIndices[1];
		Ride best = null;
		for (Ride ride : pairRides) {
			if (ride.getDegree() != 2) continue;
			int[] reqs = ride.getRequestIndices().clone();
			Arrays.sort(reqs);
			if (reqs[0] == a && reqs[1] == b) {
				if (best == null || ride.getKind() == RideKind.FIFO) best = ride;
			}
		}
		if (best != null) return best;
		// Fallback: any d=2 pair ride
		for (Ride ride : pairRides) {
			if (ride.getDegree() == 2) return ride;
		}
		throw new IllegalStateException("No d=2 pair rides found for triangle "
				+ Arrays.toString(targetIndices));
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

	/**
	 * Passthrough budget validator that always passes. Mirrors
	 * {@link ExMasLyonR1R2FastComparisonTest}'s validator without re-using the
	 * private inner class. Geometric maxTT bound (via maxDetourFactor) is the
	 * actual feasibility constraint.
	 */
	private static final class ExMasLyonR1R2FastComparisonTestPassthroughValidator
			extends BudgetValidator {
		ExMasLyonR1R2FastComparisonTestPassthroughValidator(
				ExMasConfigGroup exMasConfig, Config config) {
			super(null, null, exMasConfig, config);
		}

		@Override
		public Ride validateAndPopulateBudgets(Ride ride) {
			double[] budgets = Arrays.stream(ride.getRequests())
					.mapToDouble(r -> r.budget).toArray();
			return ride.toBuilder().remainingBudgets(budgets).build();
		}

		@Override
		public Ride populateBudgetsInPlace(Ride ride) {
			double[] budgets = Arrays.stream(ride.getRequests())
					.mapToDouble(r -> r.budget).toArray();
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
