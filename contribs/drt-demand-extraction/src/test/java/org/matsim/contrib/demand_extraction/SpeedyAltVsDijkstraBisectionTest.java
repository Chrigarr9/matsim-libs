package org.matsim.contrib.demand_extraction;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
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
import org.matsim.contrib.demand_extraction.demand.DrtRequest;
import org.matsim.contrib.demand_extraction.scenarios.LyonEqasimScenarioFixture;
import org.matsim.core.config.Config;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility;
import org.matsim.core.router.speedy.SpeedyALTFactory;
import org.matsim.core.router.speedy.SpeedyDijkstraFactory;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.LeastCostPathCalculator.Path;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;

/**
 * Bisects the 52.3-second routing gap discovered in
 * {@link D3VsD4FeasibilityComparisonTest}: is it caused by SpeedyALT
 * landmark non-optimality, or by genuine directed-link asymmetry the direct
 * query can't exploit?
 *
 * <p>Compares {@link SpeedyALT} (current router) against {@link SpeedyDijkstra}
 * (vanilla, exact) on the **same SpeedyGraph** with the **same TravelTime/
 * TravelDisutility**. Any disagreement is purely the algorithm.
 *
 * <p>Probes:
 * <ol>
 *   <li>Targeted: the (607-orig → 1637-orig) pair from the [124,607,697,1637]
 *       case. If Dijkstra returns a shorter path, SpeedyALT is sub-optimal.</li>
 *   <li>Bulk: sample N sets from r1_only_d4.csv. For each, take all 16
 *       origin↔destination link pairs and 6 origin-origin pairs. Compare both
 *       routers. Report disagreement rate and worst-case gap.</li>
 * </ol>
 *
 * <p>Required: {@code LYON_SCENARIO_DIR} + {@code LYON_REQUESTS_CSV}.
 * Optional: {@code LYON_R1_ONLY_CSV} (default points at the artifact path),
 * {@code LYON_BISECT_SAMPLE} (default 50 sets).
 */
@Tag("scenario-lyon")
@EnabledIfEnvironmentVariable(named = "LYON_SCENARIO_DIR", matches = ".+")
class SpeedyAltVsDijkstraBisectionTest {

	private static final Logger log = LogManager.getLogger(SpeedyAltVsDijkstraBisectionTest.class);

	@Test
	void bisectSpeedyAltVsDijkstra() throws Exception {
		String requestsCsv = System.getenv("LYON_REQUESTS_CSV");
		assumeTrue(requestsCsv != null && !requestsCsv.isBlank(),
				"LYON_REQUESTS_CSV required");
		String scenarioDir = System.getenv("LYON_SCENARIO_DIR");
		String prefix = System.getenv().getOrDefault("LYON_SCENARIO_PREFIX", "lyon_drt_area_");
		int samplePct = Integer.parseInt(System.getenv().getOrDefault("LYON_SAMPLE_PCT", "1"));
		String r1OnlyCsv = System.getenv().getOrDefault("LYON_R1_ONLY_CSV",
				"C:\\Users\\VWAUCCY\\dev\\msf\\projects\\Dissertation\\papers\\paper1\\analysis\\_d4_diff_artifacts\\r1_only_d4.csv");
		int bisectSample = Integer.parseInt(
				System.getenv().getOrDefault("LYON_BISECT_SAMPLE", "50"));

		// Scenario
		LyonEqasimScenarioFixture fixture = new LyonEqasimScenarioFixture(
				samplePct, scenarioDir, prefix, "");
		Config config = fixture.createConfig(java.nio.file.Path.of("test/output/bisect"));
		config.plans().setInputFile(null);
		config.vehicles().setVehiclesFile(null);
		config.facilities().setInputFile(null);
		config.transit().setTransitScheduleFile(null);
		config.transit().setVehiclesFile(null);

		Scenario scenario = ScenarioUtils.createScenario(config);
		ScenarioUtils.loadScenario(scenario);
		Network network = scenario.getNetwork();
		log.info("Loaded network: {} links", network.getLinks().size());

		TravelTime travelTime = new FreeSpeedTravelTime();
		TravelDisutility travelDisutility = new OnlyTimeDependentTravelDisutility(travelTime);

		// Use the factories — they handle SpeedyGraph + landmark construction internally
		SpeedyALTFactory altFactory = new SpeedyALTFactory();
		SpeedyDijkstraFactory dijFactory = new SpeedyDijkstraFactory();
		LeastCostPathCalculator alt = altFactory.createPathCalculator(network, travelDisutility, travelTime);
		LeastCostPathCalculator dij = dijFactory.createPathCalculator(network, travelDisutility, travelTime);

		// Load all requests
		List<DrtRequest> allReq = loadRequestsFromCsv(requestsCsv, network);
		Map<Integer, DrtRequest> reqByIdx = new HashMap<>();
		for (DrtRequest r : allReq) reqByIdx.put(r.index, r);
		log.info("Loaded {} requests", allReq.size());

		// ── PROBE 1: Targeted [124, 607, 697, 1637] ──
		log.info("");
		log.info("========================================================");
		log.info("PROBE 1: targeted query (607-orig → 1637-orig)");
		log.info("========================================================");
		DrtRequest r607 = reqByIdx.get(607);
		DrtRequest r697 = reqByIdx.get(697);
		DrtRequest r1637 = reqByIdx.get(1637);
		double t = 26677;

		double directALT = pathTT(alt, network, r607.originLinkId, r1637.originLinkId, t);
		double directDIJ = pathTT(dij, network, r607.originLinkId, r1637.originLinkId, t);
		log.info("  direct (607→1637): SpeedyALT={}s, SpeedyDijkstra={}s, gap={}s",
				f1(directALT), f1(directDIJ), f1(directALT - directDIJ));

		double leg1ALT = pathTT(alt, network, r607.originLinkId, r697.originLinkId, t);
		double leg1DIJ = pathTT(dij, network, r607.originLinkId, r697.originLinkId, t);
		log.info("  leg1 (607→697):   SpeedyALT={}s, SpeedyDijkstra={}s, gap={}s",
				f1(leg1ALT), f1(leg1DIJ), f1(leg1ALT - leg1DIJ));

		double leg2ALT = pathTT(alt, network, r697.originLinkId, r1637.originLinkId, t);
		double leg2DIJ = pathTT(dij, network, r697.originLinkId, r1637.originLinkId, t);
		log.info("  leg2 (697→1637):  SpeedyALT={}s, SpeedyDijkstra={}s, gap={}s",
				f1(leg2ALT), f1(leg2DIJ), f1(leg2ALT - leg2DIJ));

		log.info("  Triangle:  ALT direct={}s, ALT via_697={}s",
				f1(directALT), f1(leg1ALT + leg2ALT));
		log.info("             DIJ direct={}s, DIJ via_697={}s",
				f1(directDIJ), f1(leg1DIJ + leg2DIJ));
		log.info("  ALT triangle gap (direct - via_697) = {}s "
				+ "(positive = direct is sub-optimal)",
				f1(directALT - (leg1ALT + leg2ALT)));
		log.info("  DIJ triangle gap (direct - via_697) = {}s",
				f1(directDIJ - (leg1DIJ + leg2DIJ)));

		// ── PROBE 2: Bulk over R1-only d=4 sets ──
		log.info("");
		log.info("========================================================");
		log.info("PROBE 2: bulk bisection over {} R1-only d=4 sets", bisectSample);
		log.info("========================================================");
		List<int[]> r1OnlySets = loadRequestSets(r1OnlyCsv);
		log.info("Loaded {} R1-only sets from {}", r1OnlySets.size(), r1OnlyCsv);
		Random rng = new Random(42);
		java.util.Collections.shuffle(r1OnlySets, rng);
		int sampleN = Math.min(bisectSample, r1OnlySets.size());

		// Collect unique link pairs across the sample (origin-origin, dest-dest, etc.)
		Set<LinkPair> pairsToTest = new HashSet<>();
		int setsSkipped = 0;
		for (int s = 0; s < sampleN; s++) {
			int[] set = r1OnlySets.get(s);
			DrtRequest[] reqs = new DrtRequest[set.length];
			boolean ok = true;
			for (int i = 0; i < set.length; i++) {
				DrtRequest r = reqByIdx.get(set[i]);
				if (r == null) { ok = false; break; }
				reqs[i] = r;
			}
			if (!ok) { setsSkipped++; continue; }
			// origin-origin
			for (int i = 0; i < reqs.length; i++) {
				for (int j = 0; j < reqs.length; j++) {
					if (i == j) continue;
					pairsToTest.add(new LinkPair(reqs[i].originLinkId, reqs[j].originLinkId));
				}
			}
			// dest-dest
			for (int i = 0; i < reqs.length; i++) {
				for (int j = 0; j < reqs.length; j++) {
					if (i == j) continue;
					pairsToTest.add(new LinkPair(reqs[i].destinationLinkId, reqs[j].destinationLinkId));
				}
			}
			// origin → dest (own & cross)
			for (int i = 0; i < reqs.length; i++) {
				for (int j = 0; j < reqs.length; j++) {
					pairsToTest.add(new LinkPair(reqs[i].originLinkId, reqs[j].destinationLinkId));
				}
			}
			// dest → origin (next-pickup transitions)
			for (int i = 0; i < reqs.length; i++) {
				for (int j = 0; j < reqs.length; j++) {
					pairsToTest.add(new LinkPair(reqs[i].destinationLinkId, reqs[j].originLinkId));
				}
			}
		}
		log.info("Sampled {} sets ({} skipped due to missing requests), "
				+ "collected {} unique link pairs to probe",
				sampleN - setsSkipped, setsSkipped, pairsToTest.size());

		int agree = 0;
		int altShorter = 0;
		int dijShorter = 0;
		double maxDijShorter = 0;
		double sumAbsGap = 0;
		double sumAltShorter = 0;
		double sumDijShorter = 0;
		LinkPair worstDijExample = null;
		double worstDijGap = 0;
		// Collect all discrepancies for top-N reporting
		List<double[]> discrepancies = new ArrayList<>(); // [altTT, dijTT, gap]
		List<LinkPair> discrepancyPairs = new ArrayList<>();
		for (LinkPair lp : pairsToTest) {
			double a = pathTT(alt, network, lp.from, lp.to, t);
			double d = pathTT(dij, network, lp.from, lp.to, t);
			if (Double.isInfinite(a) || Double.isInfinite(d)) continue;
			double gap = a - d;
			if (Math.abs(gap) < 0.05) {
				agree++;
			} else if (gap > 0) {
				// ALT is longer (= sub-optimal for ALT)
				dijShorter++;
				sumDijShorter += gap;
				if (gap > worstDijGap) { worstDijGap = gap; worstDijExample = lp; }
				discrepancies.add(new double[]{a, d, gap});
				discrepancyPairs.add(lp);
			} else {
				// ALT is shorter (= Dijkstra found a longer path?? should be impossible if both exact)
				altShorter++;
				sumAltShorter += -gap;
				discrepancies.add(new double[]{a, d, gap});
				discrepancyPairs.add(lp);
			}
			sumAbsGap += Math.abs(gap);
		}
		int totalReachable = agree + altShorter + dijShorter;
		log.info("");
		log.info("─── BULK RESULTS ({} reachable pairs probed) ───", totalReachable);
		log.info("  agree (gap < 0.05s):    {} ({}%)", agree,
				String.format("%.2f", 100.0 * agree / totalReachable));
		log.info("  Dijkstra strictly shorter: {} ({}%)  — SpeedyALT sub-optimal",
				dijShorter, String.format("%.2f", 100.0 * dijShorter / totalReachable));
		log.info("  ALT strictly shorter:      {} ({}%)  — SHOULD BE 0 if Dijkstra is exact",
				altShorter, String.format("%.2f", 100.0 * altShorter / totalReachable));
		if (dijShorter > 0) {
			log.info("  Mean gap when ALT longer:  {}s",
					String.format("%.2f", sumDijShorter / dijShorter));
			log.info("  Worst case: ALT={}s, Dijkstra={}s, gap={}s",
					f1(pathTT(alt, network, worstDijExample.from, worstDijExample.to, t)),
					f1(pathTT(dij, network, worstDijExample.from, worstDijExample.to, t)),
					f1(worstDijGap));
		}
		if (altShorter > 0) {
			log.info("  Mean gap when ALT shorter (anomaly): {}s",
					String.format("%.2f", sumAltShorter / altShorter));
		}

		// Show top-10 worst Dijkstra-shorter cases
		log.info("");
		log.info("─── TOP-10 WORST CASES (ALT longer than Dijkstra) ───");
		int[] order = new int[discrepancies.size()];
		for (int i = 0; i < order.length; i++) order[i] = i;
		Integer[] boxed = new Integer[order.length];
		for (int i = 0; i < boxed.length; i++) boxed[i] = i;
		Arrays.sort(boxed, (i1, i2) -> Double.compare(discrepancies.get(i2)[2], discrepancies.get(i1)[2]));
		int show = Math.min(10, boxed.length);
		for (int k = 0; k < show; k++) {
			int idx = boxed[k];
			double[] disc = discrepancies.get(idx);
			LinkPair lp = discrepancyPairs.get(idx);
			log.info("  {}. {} → {}: ALT={}s, DIJ={}s, gap={}s",
					k + 1, lp.from, lp.to, f1(disc[0]), f1(disc[1]), f1(disc[2]));
		}
	}

	private static double pathTT(LeastCostPathCalculator router, Network network,
			Id<Link> from, Id<Link> to, double t) {
		Link a = network.getLinks().get(from);
		Link b = network.getLinks().get(to);
		if (a == null || b == null) return Double.POSITIVE_INFINITY;
		if (from.equals(to)) return a.getLength() / a.getFreespeed(t);
		// MatsimNetworkCache contract: route fromLink.toNode -> toLink.fromNode, then add toLink.tt
		Path p = router.calcLeastCostPath(a.getToNode(), b.getFromNode(), t, null, null);
		if (p == null) return Double.POSITIVE_INFINITY;
		return p.travelTime + (b.getLength() / b.getFreespeed(t));
	}

	private static List<int[]> loadRequestSets(String path) throws IOException {
		List<int[]> sets = new ArrayList<>();
		try (BufferedReader r = new BufferedReader(new FileReader(path))) {
			r.readLine();  // header
			String line;
			while ((line = r.readLine()) != null) {
				line = line.trim();
				if (line.isEmpty()) continue;
				String[] parts = line.split("\\|");
				int[] set = new int[parts.length];
				for (int i = 0; i < parts.length; i++) set[i] = Integer.parseInt(parts[i].trim());
				sets.add(set);
			}
		}
		return sets;
	}

	private static String f1(double v) {
		if (Double.isInfinite(v)) return "INF";
		return String.format("%.1f", v);
	}

	private static final class LinkPair {
		final Id<Link> from, to;
		LinkPair(Id<Link> from, Id<Link> to) { this.from = from; this.to = to; }
		@Override public boolean equals(Object o) {
			if (!(o instanceof LinkPair)) return false;
			LinkPair p = (LinkPair) o;
			return from.equals(p.from) && to.equals(p.to);
		}
		@Override public int hashCode() { return from.hashCode() * 31 + to.hashCode(); }
	}

	private static List<DrtRequest> loadRequestsFromCsv(String csvPath, Network network)
			throws IOException {
		List<DrtRequest> requests = new ArrayList<>();
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
				double directTT = Double.parseDouble(p[col.get("directTravelTime")].trim());
				double earliestDeparture = Double.parseDouble(p[col.get("earliestDeparture")].trim());
				double maxDetour = Math.min(1.3, directTT > 0
						? Double.parseDouble(p[col.get("maxTravelTime")].trim()) / directTT : 1.3);
				requests.add(DrtRequest.builder()
						.index(Integer.parseInt(p[col.get("index")].trim()))
						.personId(Id.createPersonId(p[col.get("personId")].trim()))
						.groupId(p[col.get("groupId")].trim())
						.tripIndex(Integer.parseInt(p[col.get("tripIndex")].trim()))
						.isCommute(false).isEducation(false).budget(0).requestTime(earliestDeparture)
						.originLinkId(originLinkId).destinationLinkId(destLinkId)
						.originX(0).originY(0).destinationX(0).destinationY(0)
						.originLinkCoordFromX(0).originLinkCoordFromY(0)
						.originLinkCoordToX(0).originLinkCoordToY(0)
						.destinationLinkCoordFromX(0).destinationLinkCoordFromY(0)
						.destinationLinkCoordToX(0).destinationLinkCoordToY(0)
						.directTravelTime(directTT).directDistance(0)
						.earliestDeparture(earliestDeparture)
						.latestArrival(earliestDeparture + directTT * maxDetour)
						.maxDetourFactor(maxDetour).maxWalkDistance(0)
						.originActivityType("a").destinationActivityType("a")
						.bestModeScore(0).bestMode("car")
						.carTravelTime(0).ptTravelTime(0).ptAccessibility(0)
						.build());
			}
		}
		return requests;
	}
}
