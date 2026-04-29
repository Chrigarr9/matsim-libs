package org.matsim.contrib.demand_extraction;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
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
import org.matsim.contrib.demand_extraction.scenarios.AlgorithmProfile;
import org.matsim.contrib.demand_extraction.scenarios.LyonEqasimScenarioFixture;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility;
import org.matsim.core.router.speedy.LeastCostPathTree;
import org.matsim.core.router.speedy.SpeedyGraphBuilder;
import org.matsim.core.router.speedy.SpeedyALTFactory;
import org.matsim.core.router.speedy.SpeedyDijkstraFactory;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;
import org.matsim.core.utils.misc.OptionalTime;

/**
 * Side-by-side feasibility comparison of d=3 [124, 607, 1637] vs d=4
 * [124, 607, 697, 1637]. Answers: how can the 4-tuple be feasible when the
 * 3-tuple containing 3 of its members is not?
 *
 * <p>For each set, enumerates all constraint-feasible orderings and prints
 * per-passenger detail (pttActual, directTT, detour, maxTT, delay, [L,U]
 * contribution). Identifies WHICH constraint fails for each d=3 ordering
 * and HOW the d=4 valid ordering differs (e.g., does adding 697 reroute
 * pickup order to absorb a blocked detour?).
 */
@Tag("scenario-lyon")
@EnabledIfEnvironmentVariable(named = "LYON_SCENARIO_DIR", matches = ".+")
class D3VsD4FeasibilityComparisonTest {

	private static final Logger log = LogManager.getLogger(D3VsD4FeasibilityComparisonTest.class);
	private static final double EPS = 1e-9;
	private static final double TIME_FEASIBILITY_EPSILON = 1.0;

	@Test
	void compareTriangleVsQuadruple() throws Exception {
		String requestsCsv = System.getenv("LYON_REQUESTS_CSV");
		assumeTrue(requestsCsv != null && !requestsCsv.isBlank(),
				"LYON_REQUESTS_CSV required");
		String scenarioDir = System.getenv("LYON_SCENARIO_DIR");
		String prefix = System.getenv().getOrDefault("LYON_SCENARIO_PREFIX", "lyon_drt_area_");
		int samplePct = Integer.parseInt(System.getenv().getOrDefault("LYON_SAMPLE_PCT", "1"));

		String tripleSpec = System.getProperty("triple",
				System.getenv().getOrDefault("LYON_TRIPLE", "124,607,1637"));
		String quadSpec = System.getProperty("quad",
				System.getenv().getOrDefault("LYON_QUAD", "124,607,697,1637"));
		int[] tripleIdx = parseSorted(tripleSpec);
		int[] quadIdx = parseSorted(quadSpec);

		// ── Scenario ──
		LyonEqasimScenarioFixture fixture = new LyonEqasimScenarioFixture(
				samplePct, scenarioDir, prefix, "");
		Config config = fixture.createConfig(java.nio.file.Path.of("test/output/d3-vs-d4-comparison"));
		config.plans().setInputFile(null);
		config.vehicles().setVehiclesFile(null);
		config.facilities().setInputFile(null);
		config.transit().setTransitScheduleFile(null);
		config.transit().setVehiclesFile(null);

		Scenario scenario = ScenarioUtils.createScenario(config);
		ScenarioUtils.loadScenario(scenario);
		Network network = scenario.getNetwork();
		log.info("Loaded network: {} links", network.getLinks().size());

		TravelTime tt = new FreeSpeedTravelTime();
		TravelDisutility td = new OnlyTimeDependentTravelDisutility(tt);
		MatsimNetworkCache cache = MatsimNetworkCacheTestFixture
				.createWithSpeedyAltRoutingDeterministic(network, tt, td, 900);

		// Load all requests in either set
		Set<Integer> wanted = new HashSet<>();
		for (int x : tripleIdx) wanted.add(x);
		for (int x : quadIdx) wanted.add(x);
		List<DrtRequest> all = loadRequestsFromCsv(requestsCsv, network);
		List<DrtRequest> set4 = new ArrayList<>();
		for (DrtRequest r : all) if (wanted.contains(r.index)) set4.add(r);
		set4.sort((a, b) -> Integer.compare(a.index, b.index));
		log.info("Loaded requests for analysis: {}", set4.stream().map(r -> r.index).toList());
		for (DrtRequest r : set4) {
			log.info("  req {}: orig={}, dest={}, reqTime={}, directTT={}, maxTT={}, "
					+ "maxDetour={}, maxNegDelay={}, maxPosDelay={}, "
					+ "negRelComp={}, posRelComp={}",
					r.index, r.originLinkId, r.destinationLinkId,
					f0(r.getRequestTime()), f0(r.getTravelTime()), f0(r.getMaxTravelTime()),
					f3(r.maxDetourFactor),
					f0(r.getMaxNegativeDelay()), f0(r.getMaxPositiveDelay()),
					f0(r.getNegativeDelayRelComponent()), f0(r.getPositiveDelayRelComponent()));
		}

		// ── Config + validator + pair generator ──
		ExMasConfigGroup exMasConfig = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		exMasConfig.setCalcPredecessors(false);
		exMasConfig.setCalcShapleyValues(false);
		AlgorithmProfile.R2.apply(config);
		exMasConfig.setAlgorithmProcessCount(1);
		BudgetValidator validator = new PassthroughValidator(exMasConfig, config);

		// ── Generate pairs across the union (so both sets see the same edges) ──
		PairGenerator pairGen = new PairGenerator(cache, validator,
				exMasConfig.getSearchHorizon(), 1);
		List<Ride> allPairs = pairGen.generatePairs(set4);
		log.info("Pair rides on union of 4 requests: {} edges", allPairs.size());

		log.info("");
		log.info("─── WATCHED CACHE-WRITE PROBE: (O_7072,O_7076, bin 32 / canonical 29250) ───");
		probeWatchedCacheWritePath(set4, network, tt, td, cache,
				validator, exMasConfig.getSearchHorizon());

		log.info("");
		log.info("─── DIRECT LEASTCOSTPATHTREE PROBE: (O_7072,O_7076) at canonical 29250 ───");
		probeLeastCostPathTreeWatchedPair(set4, network, tt, td);

		// ── Cross-time-bin reachability probe ──
		log.info("");
		log.info("─── CROSS-TIME-BIN REACHABILITY PROBE (cache, SpeedyALT-backed) ───");
		log.info("Probing every (i,j) origin pair at several canonical times to detect");
		log.info("router non-determinism (FreeSpeedTravelTime is time-independent so each");
		log.info("(from,to) should give the same answer at every t).");
		probeCrossTime(set4, cache);

		// ── Direct router probe (SpeedyALT vs SpeedyDijkstra, bypassing cache) ──
		log.info("");
		log.info("─── DIRECT ROUTER PROBE (SpeedyALT vs SpeedyDijkstra) ───");
		log.info("Calls each router directly at multiple canonical times. Dijkstra is exact;");
		log.info("any cross-time inconsistency in SpeedyALT vs constant Dijkstra = SpeedyALT bug.");
		LeastCostPathCalculator alt = new SpeedyALTFactory().createPathCalculator(network, td, tt);
		LeastCostPathCalculator dij = new SpeedyDijkstraFactory().createPathCalculator(network, td, tt);
		probeRoutersCrossTime(set4, network, alt, dij);

		// ── Targeted probe: many fresh SpeedyALT instances on (O_7072,O_7076) ──
		log.info("");
		log.info("─── MANY-INSTANCE PROBE: (O_7072,O_7076) at canonical 29250 ───");
		DrtRequest r7072 = null, r7076 = null;
		for (DrtRequest r : set4) {
			if (r.index == 7072) r7072 = r;
			if (r.index == 7076) r7076 = r;
		}
		if (r7072 != null && r7076 != null) {
			Link from = network.getLinks().get(r7072.originLinkId);
			Link to = network.getLinks().get(r7076.originLinkId);
			org.matsim.api.core.v01.population.Person dp = org.matsim.core.population.PopulationUtils
					.getFactory().createPerson(org.matsim.api.core.v01.Id.createPersonId("inst_dummy"));
			org.matsim.vehicles.Vehicle dv = org.matsim.vehicles.VehicleUtils.createVehicle(
					org.matsim.api.core.v01.Id.createVehicleId("inst_dummy_v"),
					org.matsim.vehicles.VehicleUtils.createVehicleType(
							org.matsim.api.core.v01.Id.create("car", org.matsim.vehicles.VehicleType.class)));
			for (int trial = 0; trial < 5; trial++) {
				LeastCostPathCalculator a2 = new SpeedyALTFactory().createPathCalculator(network, td, tt);
				LeastCostPathCalculator d2 = new SpeedyDijkstraFactory().createPathCalculator(network, td, tt);
				var pa = a2.calcLeastCostPath(from, to, 29250.0, dp, dv);
				var pd = d2.calcLeastCostPath(from, to, 29250.0, dp, dv);
				log.info("  trial {}: ALT={}, DIJ={}", trial,
						(pa == null || pa.links.isEmpty()) ? "NULL" : "TT=" + (int) pa.travelTime,
						(pd == null || pd.links.isEmpty()) ? "NULL" : "TT=" + (int) pd.travelTime);
			}
			// Also probe via the cache's shared router (the one that produced 16s)
			log.info("  (Note: cache showed tt=16 for this segment at canonical 29250 — see CROSS-TIME-BIN PROBE above)");
		}

		// ── Analyse d=3 ──
		log.info("");
		log.info("================================================================");
		log.info("ANALYSIS: d=3 set {}", Arrays.toString(tripleIdx));
		log.info("================================================================");
		analyseSet(tripleIdx, set4, allPairs, cache);

		// ── Analyse d=4 ──
		log.info("");
		log.info("================================================================");
		log.info("ANALYSIS: d=4 set {}", Arrays.toString(quadIdx));
		log.info("================================================================");
		analyseSet(quadIdx, set4, allPairs, cache);
	}

	// ── per-set analysis: enumerate, evaluate, print rich per-passenger detail ──
	private static void analyseSet(int[] indices, List<DrtRequest> allReqs,
			List<Ride> allPairs, MatsimNetworkCache cache) {
		// Filter pair rides to those between members of this set
		Set<Integer> idxSet = new HashSet<>();
		for (int i : indices) idxSet.add(i);
		List<Ride> filteredPairs = new ArrayList<>();
		for (Ride r : allPairs) {
			int[] reqs = r.getRequestIndices();
			if (idxSet.contains(reqs[0]) && idxSet.contains(reqs[1])) filteredPairs.add(r);
		}
		log.info("Pair rides within set: {}", filteredPairs.size());
		ShareabilityGraph subGraph = buildGraph(filteredPairs);
		log.info("Sub-graph: {} nodes, {} edges", subGraph.getNodeCount(), subGraph.getEdgeCount());

		// Get the actual DrtRequest objects in the order matching `indices`
		DrtRequest[] reqArr = new DrtRequest[indices.length];
		for (int i = 0; i < indices.length; i++) {
			DrtRequest match = null;
			for (DrtRequest r : allReqs) if (r.index == indices[i]) { match = r; break; }
			if (match == null) throw new IllegalStateException("missing req " + indices[i]);
			reqArr[i] = match;
		}

		// Pair-edge structure
		log.info("");
		log.info("─── PAIR-EDGE STRUCTURE ───");
		for (int i = 0; i < indices.length; i++) {
			for (int j = i + 1; j < indices.length; j++) {
				int a = indices[i], b = indices[j];
				it.unimi.dsi.fastutil.ints.IntList[] fwd = subGraph.getEdgesWithKinds(a, b);
				it.unimi.dsi.fastutil.ints.IntList[] rev = subGraph.getEdgesWithKinds(b, a);
				log.info("  ({},{}): forward kinds={}, reverse kinds={}",
						a, b, fmtKinds(fwd[1]), fmtKinds(rev[1]));
			}
		}

		// Enumerate
		log.info("");
		log.info("─── UNPRUNED ENUMERATION ───");
		List<OrderingEnumerator.Ordering> orderings =
				OrderingEnumerator.enumerate(indices, subGraph);
		log.info("Constraint-feasible orderings: {}", orderings.size());

		int validCount = 0;
		List<EvaluationResult> validResults = new ArrayList<>();
		for (int o = 0; o < orderings.size(); o++) {
			OrderingEnumerator.Ordering ord = orderings.get(o);
			EvaluationResult e = evaluateRich(ord, reqArr, cache, indices);
			log.info("");
			log.info("  ── Ordering [{}] origin={}, dest={} ──", o,
					fmtPerm(ord.originPerm(), indices),
					fmtPerm(ord.destPerm(), indices));
			log.info("    routed connTT={}", Arrays.toString(fmtArr(e.connTT)));
			if (e.pttActual == null) {
				log.info("    => FAILS: unreachable segment found (segment-level inspection follows)");
				diagnoseSegmentReachability(ord, reqArr, cache, indices);
				continue;
			}
			for (int i = 0; i < e.n; i++) {
				int reqGlobal = indices[ord.originPerm()[i]];
				log.info("    req {}: pttActual={}, directTT={}, detourFactor={}, maxTT={}, "
						+ "{}",
						reqGlobal, f0(e.pttActual[i]), f0(reqArr[ord.originPerm()[i]].getTravelTime()),
						f3(e.detourRatio[i]), f0(reqArr[ord.originPerm()[i]].getMaxTravelTime()),
						e.pttExceedsMax[i]
								? "** EXCEEDS MAX-TT by " + f3(e.pttActual[i]
										- reqArr[ord.originPerm()[i]].getMaxTravelTime()) + "s **"
								: "OK");
			}
			if (e.status == Status.FAILS_MAX_TT) {
				log.info("    => FAILS_MAX_TT");
				continue;
			}
			// Print delay-window contributions
			log.info("    Delay-window contributions [L,U]:");
			double L = Double.NEGATIVE_INFINITY, U = Double.POSITIVE_INFINITY;
			int bindL = -1, bindU = -1;
			for (int i = 0; i < e.n; i++) {
				int reqGlobal = indices[ord.originPerm()[i]];
				log.info("      req {}: delay={}, effMaxNeg={}, effMaxPos={}, contribution=[{},{}]",
						reqGlobal, f0(e.delays[i]), f0(e.effMaxNeg[i]), f0(e.effMaxPos[i]),
						f0(e.Lc[i]), f0(e.Uc[i]));
				if (e.Lc[i] > L) { L = e.Lc[i]; bindL = i; }
				if (e.Uc[i] < U) { U = e.Uc[i]; bindU = i; }
			}
			boolean delaysOk = L <= U + EPS;
			log.info("    Tight [L,U]=[{},{}], width={}, OK={}",
					f0(L), f0(U), f0(U - L), delaysOk);
			if (!delaysOk) {
				log.info("    => FAILS_DELAY_OPTIMIZATION (binding L from req {}, "
						+ "binding U from req {})",
						indices[ord.originPerm()[bindL]],
						indices[ord.originPerm()[bindU]]);
				continue;
			}
			// VALID
			log.info("    => VALID (totalDist={})", f0(e.totalDist));
			validCount++;
			e.tightL = L; e.tightU = U;
			validResults.add(e);
		}
		log.info("");
		log.info("Summary: {} valid out of {} constraint-feasible orderings",
				validCount, orderings.size());
		if (!validResults.isEmpty()) {
			EvaluationResult best = validResults.stream()
					.min((a, b) -> Double.compare(a.totalDist, b.totalDist)).get();
			log.info("Shortest valid ordering: dist={}, [L,U]=[{},{}]",
					f0(best.totalDist), f0(best.tightL), f0(best.tightU));
		}
	}

	// ── rich evaluator: returns all per-passenger arrays ──
	private enum Status { VALID, FAILS_MAX_TT, FAILS_DELAY_OPT }

	private static final class EvaluationResult {
		Status status;
		int n;
		double[] connTT;
		double[] pttActual;
		double[] detourRatio;
		boolean[] pttExceedsMax;
		double[] delays;
		double[] effMaxNeg;
		double[] effMaxPos;
		double[] Lc;
		double[] Uc;
		double totalDist;
		double tightL;
		double tightU;
	}

	private static EvaluationResult evaluateRich(OrderingEnumerator.Ordering ord,
			DrtRequest[] reqArr, MatsimNetworkCache cache, int[] indices) {
		EvaluationResult r = new EvaluationResult();
		int n = reqArr.length;
		r.n = n;
		int[] originPerm = ord.originPerm();
		int[] destPerm = ord.destPerm();

		DrtRequest[] originsOrdered = new DrtRequest[n];
		DrtRequest[] destsOrdered = new DrtRequest[n];
		for (int i = 0; i < n; i++) {
			originsOrdered[i] = reqArr[originPerm[i]];
			destsOrdered[i] = reqArr[destPerm[i]];
		}

		@SuppressWarnings("unchecked")
		Id<Link>[] sequence = (Id<Link>[]) new Id[n * 2];
		for (int i = 0; i < n; i++) sequence[i] = originsOrdered[i].originLinkId;
		for (int i = 0; i < n; i++) sequence[n + i] = destsOrdered[i].destinationLinkId;

		double[] connTT = new double[n * 2 - 1];
		double[] connDist = new double[n * 2 - 1];
		double startTime = originsOrdered[0].getRequestTime();
		double t = startTime;
		double totalDist = 0;
		for (int i = 0; i < n * 2 - 1; i++) {
			TravelSegment seg = cache.getSegment(sequence[i], sequence[i + 1], t);
			if (!seg.isReachable()) {
				r.status = Status.FAILS_MAX_TT;
				r.connTT = connTT;
				return r;
			}
			connTT[i] = seg.getTravelTime();
			connDist[i] = seg.getDistance();
			totalDist += seg.getDistance();
			t += seg.getTravelTime();
		}
		r.connTT = connTT;
		r.totalDist = totalDist;

		double[] pttActual = new double[n];
		double[] detourRatio = new double[n];
		boolean[] pttExceedsMax = new boolean[n];
		boolean anyExceeds = false;
		for (int i = 0; i < n; i++) {
			DrtRequest req = originsOrdered[i];
			int origIdx = i;
			int destPosInDestArray = -1;
			for (int k = 0; k < n; k++) {
				if (destsOrdered[k].index == req.index) { destPosInDestArray = k; break; }
			}
			int destIdx = n + destPosInDestArray;
			for (int j = origIdx; j < destIdx; j++) pttActual[i] += connTT[j];
			if (pttActual[i] < req.getTravelTime() - EPS) pttActual[i] = req.getTravelTime();
			detourRatio[i] = pttActual[i] / req.getTravelTime();
			if (pttActual[i] > req.getMaxTravelTime() + TIME_FEASIBILITY_EPSILON) {
				pttExceedsMax[i] = true;
				anyExceeds = true;
			}
		}
		r.pttActual = pttActual;
		r.detourRatio = detourRatio;
		r.pttExceedsMax = pttExceedsMax;
		if (anyExceeds) {
			r.status = Status.FAILS_MAX_TT;
			return r;
		}

		// Delay window per passenger
		double[] delays = new double[n];
		double arrivalAtOrigin = startTime;
		for (int i = 0; i < n; i++) {
			delays[i] = arrivalAtOrigin - originsOrdered[i].getRequestTime();
			if (i < n - 1) arrivalAtOrigin += connTT[i];
		}
		double[] effMaxNeg = new double[n];
		double[] effMaxPos = new double[n];
		double[] Lc = new double[n];
		double[] Uc = new double[n];
		for (int i = 0; i < n; i++) {
			DrtRequest req = originsOrdered[i];
			double detourTime = pttActual[i] - req.getTravelTime();
			double posAdj = req.getPositiveDelayRelComponent() > 0
					? Math.max(0.0, req.getPositiveDelayRelComponent() - detourTime) : 0.0;
			double negAdj = req.getNegativeDelayRelComponent() > 0
					? Math.max(0.0, req.getNegativeDelayRelComponent() - detourTime) : 0.0;
			effMaxPos[i] = (req.getMaxPositiveDelay() - detourTime) - posAdj;
			effMaxNeg[i] = req.getMaxNegativeDelay() - negAdj;
			Lc[i] = -delays[i] - effMaxNeg[i];
			Uc[i] = effMaxPos[i] - delays[i];
		}
		r.delays = delays;
		r.effMaxNeg = effMaxNeg;
		r.effMaxPos = effMaxPos;
		r.Lc = Lc;
		r.Uc = Uc;

		double L = Double.NEGATIVE_INFINITY, U = Double.POSITIVE_INFINITY;
		for (int i = 0; i < n; i++) {
			L = Math.max(L, Lc[i]);
			U = Math.min(U, Uc[i]);
		}
		r.status = (L <= U + EPS) ? Status.VALID : Status.FAILS_DELAY_OPT;
		return r;
	}

	private static void probeRoutersCrossTime(List<DrtRequest> reqs, Network network,
			LeastCostPathCalculator alt, LeastCostPathCalculator dij) {
		double[] canonicalTimes = new double[]{27450, 28350, 29250, 30150, 31050};
		org.matsim.api.core.v01.population.Person dummyPerson = org.matsim.core.population.PopulationUtils
				.getFactory().createPerson(org.matsim.api.core.v01.Id.createPersonId("probe_dummy"));
		org.matsim.vehicles.Vehicle dummyVehicle = org.matsim.vehicles.VehicleUtils.createVehicle(
				org.matsim.api.core.v01.Id.createVehicleId("probe_dummy_v"),
				org.matsim.vehicles.VehicleUtils.createVehicleType(
						org.matsim.api.core.v01.Id.create("car", org.matsim.vehicles.VehicleType.class)));
		for (DrtRequest a : reqs) {
			for (DrtRequest b : reqs) {
				if (a.index == b.index) continue;
				Link from = network.getLinks().get(a.originLinkId);
				Link to = network.getLinks().get(b.originLinkId);
				StringBuilder sb = new StringBuilder();
				sb.append(String.format("  O_%d->O_%d:", a.index, b.index));
				for (double t : canonicalTimes) {
					var pAlt = alt.calcLeastCostPath(from, to, t, dummyPerson, dummyVehicle);
					var pDij = dij.calcLeastCostPath(from, to, t, dummyPerson, dummyVehicle);
					String sa = (pAlt == null || pAlt.links.isEmpty())
							? "ALT=NULL" : String.format("ALT=%.0f", pAlt.travelTime);
					String sd = (pDij == null || pDij.links.isEmpty())
							? "DIJ=NULL" : String.format("DIJ=%.0f", pDij.travelTime);
					String mismatch = (sa.equals(sd) || sa.replace("ALT", "X").equals(sd.replace("DIJ", "X")))
							? "" : "*";
					sb.append(String.format(" t=%.0f:[%s,%s%s]", t, sa, sd, mismatch));
				}
				log.info(sb.toString());
			}
		}
	}

	private static void probeCrossTime(List<DrtRequest> reqs, MatsimNetworkCache cache) {
		double[] canonicalTimes = new double[]{27450, 28350, 29250, 30150, 31050};
		for (DrtRequest a : reqs) {
			for (DrtRequest b : reqs) {
				if (a.index == b.index) continue;
				StringBuilder sb = new StringBuilder();
				sb.append(String.format("  %s_%d(%s) -> %s_%d(%s):", "O", a.index,
						a.originLinkId, "O", b.index, b.originLinkId));
				for (double t : canonicalTimes) {
					TravelSegment seg = cache.getSegment(a.originLinkId, b.originLinkId, t);
					sb.append(String.format(" t=%.0f:[", t));
					if (seg.isReachable()) {
						sb.append(String.format("tt=%.0f,d=%.0f", seg.getTravelTime(), seg.getDistance()));
					} else {
						sb.append("UNREACH");
					}
					sb.append("]");
				}
				log.info(sb.toString());
			}
		}
	}

	private static void probeWatchedCacheWritePath(List<DrtRequest> reqs, Network network,
			TravelTime tt, TravelDisutility td, MatsimNetworkCache warmedCache,
			BudgetValidator validator, double horizon) {
		DrtRequest r7072 = null;
		DrtRequest r7076 = null;
		for (DrtRequest r : reqs) {
			if (r.index == 7072) r7072 = r;
			if (r.index == 7076) r7076 = r;
		}
		if (r7072 == null || r7076 == null) {
			log.info("  skipped: watched requests 7072/7076 not present in current set");
			return;
		}

		double watchedTime = 29250.0;
		int watchedBin = (int) (watchedTime / 900.0);
		Id<Link> from = r7072.originLinkId;
		Id<Link> to = r7076.originLinkId;

		TravelSegment preExisting = MatsimNetworkCacheTestFixture.peek(warmedCache, from, to, watchedBin);
		log.info("  warmed cache before any explicit watched probe: {}", fmtCacheSlot(preExisting));

		MatsimNetworkCache freshCache = MatsimNetworkCacheTestFixture
				.createWithSpeedyAltRoutingDeterministic(network, tt, td, 900);
		TravelSegment freshBefore = MatsimNetworkCacheTestFixture.peek(freshCache, from, to, watchedBin);
		TravelSegment freshLookup = freshCache.getSegment(from, to, watchedTime);
		TravelSegment freshAfter = MatsimNetworkCacheTestFixture.peek(freshCache, from, to, watchedBin);
		log.info("  fresh cache: before={}, lookup@29250={}, after={}",
				fmtCacheSlot(freshBefore), fmtCacheSlot(freshLookup), fmtCacheSlot(freshAfter));

		MatsimNetworkCache replayedCache = MatsimNetworkCacheTestFixture
				.createWithSpeedyAltRoutingDeterministic(network, tt, td, 900);
		new PairGenerator(replayedCache, validator, horizon, 1).generatePairs(reqs);
		TravelSegment replayedBeforeClear = MatsimNetworkCacheTestFixture.peek(replayedCache, from, to, watchedBin);
		replayedCache.clearCache();
		TravelSegment replayedAfterClear = MatsimNetworkCacheTestFixture.peek(replayedCache, from, to, watchedBin);
		TravelSegment replayedLookup = replayedCache.getSegment(from, to, watchedTime);
		TravelSegment replayedAfterLookup = MatsimNetworkCacheTestFixture.peek(replayedCache, from, to, watchedBin);
		log.info("  replay-warmed cache: beforeClear={}, afterClear={}, lookup@29250={}, afterLookup={}",
				fmtCacheSlot(replayedBeforeClear), fmtCacheSlot(replayedAfterClear),
				fmtCacheSlot(replayedLookup), fmtCacheSlot(replayedAfterLookup));
	}

	private static void probeLeastCostPathTreeWatchedPair(List<DrtRequest> reqs, Network network,
			TravelTime tt, TravelDisutility td) {
		DrtRequest r7072 = null;
		DrtRequest r7076 = null;
		for (DrtRequest r : reqs) {
			if (r.index == 7072) r7072 = r;
			if (r.index == 7076) r7076 = r;
		}
		if (r7072 == null || r7076 == null) {
			log.info("  skipped: watched requests 7072/7076 not present in current set");
			return;
		}

		double watchedTime = 29250.0;
		double ssspBound = reqs.stream().mapToDouble(DrtRequest::getMaxTravelTime).max().orElse(0.0);
		Link fromLink = network.getLinks().get(r7072.originLinkId);
		Link toLink = network.getLinks().get(r7076.originLinkId);
		if (fromLink == null || toLink == null) {
			log.info("  skipped: watched links missing in network (from={}, to={})",
					r7072.originLinkId, r7076.originLinkId);
			return;
		}
		log.info(
				"  link endpoints: fromLink[fromNode={}, toNode={}], toLink[fromNode={}, toNode={}], from.to==to.from={}, from.from==to.from={}",
				fromLink.getFromNode().getId(), fromLink.getToNode().getId(),
				toLink.getFromNode().getId(), toLink.getToNode().getId(),
				fromLink.getToNode().getId().equals(toLink.getFromNode().getId()),
				fromLink.getFromNode().getId().equals(toLink.getFromNode().getId()));

		org.matsim.api.core.v01.population.Person dummyPerson = org.matsim.core.population.PopulationUtils
				.getFactory().createPerson(org.matsim.api.core.v01.Id.createPersonId("tree_probe_dummy"));
		org.matsim.vehicles.Vehicle dummyVehicle = org.matsim.vehicles.VehicleUtils.createVehicle(
				org.matsim.api.core.v01.Id.createVehicleId("tree_probe_dummy_v"),
				org.matsim.vehicles.VehicleUtils.createVehicleType(
						org.matsim.api.core.v01.Id.create("car", org.matsim.vehicles.VehicleType.class)));

		LeastCostPathTree tree = new LeastCostPathTree(SpeedyGraphBuilder.build(network), tt, td);
		LeastCostPathTree.StopCriterion stopCriterion =
				new LeastCostPathTree.TravelTimeStopCriterion(ssspBound);
		tree.calculate(fromLink, watchedTime, dummyPerson, dummyVehicle, stopCriterion);

		int toNodeIdx = toLink.getFromNode().getId().index();
		OptionalTime time = tree.getTime(toNodeIdx);
		if (!time.isDefined()) {
			log.info("  tree result: UNDEFINED (bound={}, toNode={}, fromLink={}, toLink={})",
					f3(ssspBound), toNodeIdx, r7072.originLinkId, r7076.originLinkId);
			return;
		}

		double interLinkTT = time.seconds() - watchedTime;
		double toLinkTT = tt.getLinkTravelTime(toLink, time.seconds(), dummyPerson, dummyVehicle);
		double toLinkDisutility = td.getLinkTravelDisutility(toLink, time.seconds(), dummyPerson, dummyVehicle);
		double rawTt = interLinkTT + toLinkTT;
		double rawDist = tree.getDistance(toNodeIdx) + toLink.getLength();
		double rawUtility = -(tree.getCost(toNodeIdx) + toLinkDisutility);
		double quantizedTt = quantizeTowardZero(rawTt, 10.0);
		double quantizedDist = quantizeTowardZero(rawDist, 100.0);
		double quantizedUtility = quantizeTowardZero(rawUtility, 10000.0);

		log.info(
				"  tree raw: bound={}, timeToNode={}, interTT={}, toLinkTT={}, nodeDist={}, toLinkLen={}, nodeCost={}, toLinkDisutility={}",
				f3(ssspBound), f6(time.seconds()), f6(interLinkTT), f6(toLinkTT),
				f6(tree.getDistance(toNodeIdx)), f6(toLink.getLength()),
				f6(tree.getCost(toNodeIdx)), f6(toLinkDisutility));
		log.info("  tree derived: raw(tt={}, dist={}, utility={}), quantized(tt={}, dist={}, utility={})",
				f6(rawTt), f6(rawDist), f6(rawUtility),
				f6(quantizedTt), f6(quantizedDist), f6(quantizedUtility));
	}

	// ── segment-level diagnostic: print each segment's reachability + tt + dist ──
	private static void diagnoseSegmentReachability(OrderingEnumerator.Ordering ord,
			DrtRequest[] reqArr, MatsimNetworkCache cache, int[] indices) {
		int n = reqArr.length;
		int[] originPerm = ord.originPerm();
		int[] destPerm = ord.destPerm();
		DrtRequest[] originsOrdered = new DrtRequest[n];
		DrtRequest[] destsOrdered = new DrtRequest[n];
		for (int i = 0; i < n; i++) {
			originsOrdered[i] = reqArr[originPerm[i]];
			destsOrdered[i] = reqArr[destPerm[i]];
		}
		@SuppressWarnings("unchecked")
		Id<Link>[] sequence = (Id<Link>[]) new Id[n * 2];
		String[] tag = new String[n * 2];
		for (int i = 0; i < n; i++) {
			sequence[i] = originsOrdered[i].originLinkId;
			tag[i] = "O_" + originsOrdered[i].index;
		}
		for (int i = 0; i < n; i++) {
			sequence[n + i] = destsOrdered[i].destinationLinkId;
			tag[n + i] = "D_" + destsOrdered[i].index;
		}
		double startTime = originsOrdered[0].getRequestTime();
		double t = startTime;
		log.info("    Segment-by-segment diagnosis (startTime={}):", f0(startTime));
		for (int i = 0; i < n * 2 - 1; i++) {
			TravelSegment seg = cache.getSegment(sequence[i], sequence[i + 1], t);
			boolean reach = seg.isReachable();
			double segTT = reach ? seg.getTravelTime() : -1;
			double segDist = reach ? seg.getDistance() : -1;
			log.info("      [{}] {}({}) -> {}({}) at t={}: reachable={}, tt={}, dist={}",
					i, tag[i], sequence[i], tag[i + 1], sequence[i + 1],
					f0(t), reach, f0(segTT), f0(segDist));
			if (reach) t += segTT;
			else break;
		}
	}

	// ── helpers ──
	private static int[] parseSorted(String spec) {
		return Arrays.stream(spec.split(","))
				.map(String::trim).mapToInt(Integer::parseInt).sorted().toArray();
	}

	private static ShareabilityGraph buildGraph(List<Ride> pairRides) {
		ShareabilityGraph.Builder b = ShareabilityGraph.builder(Math.max(1, pairRides.size() * 2));
		for (Ride ride : pairRides) {
			if (ride.getDegree() != 2) continue;
			int reqI = ride.getRequestIndices()[0];
			int reqJ = ride.getRequestIndices()[1];
			byte kind = ride.getKind() == RideKind.FIFO
					? ShareabilityGraph.KIND_FIFO : ShareabilityGraph.KIND_LIFO;
			b.addEdge(reqI, reqJ, ride.getIndex(), kind);
		}
		return b.build();
	}

	private static String fmtPerm(int[] localPerm, int[] globalIndices) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < localPerm.length; i++) {
			if (i > 0) sb.append(",");
			sb.append(globalIndices[localPerm[i]]);
		}
		sb.append("]");
		return sb.toString();
	}

	private static String fmtKinds(it.unimi.dsi.fastutil.ints.IntList kinds) {
		if (kinds.isEmpty()) return "[]";
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < kinds.size(); i++) {
			if (i > 0) sb.append(",");
			sb.append(kinds.getInt(i) == ShareabilityGraph.KIND_FIFO ? "FIFO" : "LIFO");
		}
		sb.append("]");
		return sb.toString();
	}

	private static double quantizeTowardZero(double value, double scale) {
		if (!Double.isFinite(value)) {
			return value;
		}
		double scaled = value * scale;
		double eps = 1e-9;
		double truncated = scaled >= 0.0 ? Math.floor(scaled + eps) : Math.ceil(scaled - eps);
		return truncated / scale;
	}

	private static String[] fmtArr(double[] arr) {
		if (arr == null) return new String[]{"null"};
		String[] out = new String[arr.length];
		for (int i = 0; i < arr.length; i++) out[i] = String.format("%.0f", arr[i]);
		return out;
	}

	private static String fmtCacheSlot(TravelSegment seg) {
		if (seg == null) return "ABSENT";
		if (!seg.isReachable()) return "UNREACH";
		return String.format(java.util.Locale.US, "tt=%.0f,d=%.0f",
				seg.getTravelTime(), seg.getDistance());
	}

	private static String f0(double v) { return String.format("%.0f", v); }
	private static String f3(double v) { return String.format("%.3f", v); }
	private static String f6(double v) { return String.format("%.6f", v); }

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

	private static final class PassthroughValidator extends BudgetValidator {
		PassthroughValidator(ExMasConfigGroup exMasConfig, Config config) {
			super(null, null, exMasConfig, config);
		}
		@Override
		public Ride validateAndPopulateBudgets(Ride ride) {
			double[] budgets = Arrays.stream(ride.getRequests()).mapToDouble(r -> r.budget).toArray();
			return ride.toBuilder().remainingBudgets(budgets).build();
		}
	}
}
