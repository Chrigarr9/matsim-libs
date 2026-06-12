package org.matsim.contrib.demand_extraction;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCacheTestFixture;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;
import org.matsim.contrib.demand_extraction.scenarios.LyonEqasimScenarioFixture;
import org.matsim.core.config.Config;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;

/**
 * Quantifies the link-traversal accounting bug in {@link MatsimNetworkCache}.
 *
 * <p>Cache returns {@code path.travelTime} from
 * {@code fromLink.toNode → toLink.fromNode} — which does NOT include traversal
 * of either {@code fromLink} or {@code toLink}. Production code in
 * {@code BamasRideExtender.buildRideFromOrdering} sums these values directly to
 * compute {@code pttActual}, so every intermediate-stop link traversal is missed.
 *
 * <p>This test:
 * <ol>
 *   <li>Compares {@code req.directTravelTime} (from MATSim Leg routing) against
 *       {@code cache.getSegment(origin, dest)} (from SpeedyALT-via-cache). If
 *       they differ, the conventions don't match and the floor
 *       {@code pttActual = max(pttActual, directTT)} compares incompatible quantities.</li>
 *   <li>Computes "physical" pttActual for the d=3 [124,607,1637] vs d=4
 *       [124,607,697,1637] orderings, including all intermediate link traversals.
 *       Reports whether the d=4 set is still feasible under the corrected accounting.</li>
 * </ol>
 */
@Tag("scenario-lyon")
@EnabledIfEnvironmentVariable(named = "LYON_SCENARIO_DIR", matches = ".+")
class PttActualTraversalAccountingTest {

	private static final Logger log = LogManager.getLogger(PttActualTraversalAccountingTest.class);

	@Test
	void quantifyTraversalAccounting() throws Exception {
		String requestsCsv = System.getenv("LYON_REQUESTS_CSV");
		assumeTrue(requestsCsv != null && !requestsCsv.isBlank(),
				"LYON_REQUESTS_CSV required");
		String scenarioDir = System.getenv("LYON_SCENARIO_DIR");
		String prefix = System.getenv().getOrDefault("LYON_SCENARIO_PREFIX", "lyon_drt_area_");
		int samplePct = Integer.parseInt(System.getenv().getOrDefault("LYON_SAMPLE_PCT", "1"));

		LyonEqasimScenarioFixture fixture = new LyonEqasimScenarioFixture(
				samplePct, scenarioDir, prefix, "");
		Config config = fixture.createConfig(java.nio.file.Path.of("test/output/ptt-traversal"));
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
				.createWithRouting(network, tt, td, 900);

		List<DrtRequest> all = loadRequestsFromCsv(requestsCsv, network);
		Map<Integer, DrtRequest> byIdx = new HashMap<>();
		for (DrtRequest r : all) byIdx.put(r.index, r);
		DrtRequest r124 = byIdx.get(124);
		DrtRequest r607 = byIdx.get(607);
		DrtRequest r697 = byIdx.get(697);
		DrtRequest r1637 = byIdx.get(1637);
		double t = 26677;

		// ── PROBE A: directTT vs cache(origin, dest) ──
		log.info("");
		log.info("══════════════════════════════════════════════════════════════");
		log.info("PROBE A: req.directTravelTime vs cache(origin, dest)");
		log.info("══════════════════════════════════════════════════════════════");
		for (DrtRequest r : List.of(r124, r607, r697, r1637)) {
			TravelSegment seg = cache.getSegment(r.originLinkId, r.destinationLinkId, t);
			Link orig = network.getLinks().get(r.originLinkId);
			Link dest = network.getLinks().get(r.destinationLinkId);
			double origTT = orig.getLength() / orig.getFreespeed(t);
			double destTT = dest.getLength() / dest.getFreespeed(t);
			log.info("  req {}: directTT={} (Leg routing), cache(orig,dest)={} (path-only), "
					+ "orig.tt={}, dest.tt={}, diff(directTT - cache) = {}",
					r.index, f1(r.getTravelTime()), f1(seg.getTravelTime()),
					f1(origTT), f1(destTT),
					f1(r.getTravelTime() - seg.getTravelTime()));
		}

		// ── PROBE B: per-link traversal times for the relevant links ──
		log.info("");
		log.info("══════════════════════════════════════════════════════════════");
		log.info("PROBE B: link traversal times for stops in {124,607,697,1637}");
		log.info("══════════════════════════════════════════════════════════════");
		Id<Link>[] linksOfInterest = (Id<Link>[]) new Id[]{
				r124.originLinkId, r124.destinationLinkId,
				r607.originLinkId, r607.destinationLinkId,
				r697.originLinkId, r697.destinationLinkId,
				r1637.originLinkId, r1637.destinationLinkId
		};
		String[] labels = {"124-orig", "124-dest", "607-orig", "607-dest",
				"697-orig", "697-dest", "1637-orig", "1637-dest"};
		Map<Id<Link>, Double> linkTT = new HashMap<>();
		for (int i = 0; i < linksOfInterest.length; i++) {
			Link L = network.getLinks().get(linksOfInterest[i]);
			double linkTt = L.getLength() / L.getFreespeed(t);
			linkTT.put(linksOfInterest[i], linkTt);
			log.info("  {} (link {}): length={}m, freespeed={}m/s, tt={}s",
					labels[i], linksOfInterest[i], f1(L.getLength()),
					f1(L.getFreespeed(t)), f1(linkTt));
		}

		// ── PROBE C: corrected pttActual for d=3 [124,607,1637] orderings ──
		log.info("");
		log.info("══════════════════════════════════════════════════════════════");
		log.info("PROBE C: corrected pttActual for d=3 [124, 607, 1637]");
		log.info("══════════════════════════════════════════════════════════════");
		// Two constraint-feasible orderings observed in earlier test:
		//   (origin=[124,607,1637], dest=[1637,124,607]) and dest=[1637,607,124]
		analyseOrdering("d=3 ord 0", new DrtRequest[]{r124, r607, r1637},
				new int[]{0, 1, 2}, new int[]{2, 0, 1}, cache, network, linkTT, t);
		analyseOrdering("d=3 ord 1", new DrtRequest[]{r124, r607, r1637},
				new int[]{0, 1, 2}, new int[]{2, 1, 0}, cache, network, linkTT, t);

		// ── PROBE D: corrected pttActual for d=4 [124,607,697,1637] valid orderings ──
		log.info("");
		log.info("══════════════════════════════════════════════════════════════");
		log.info("PROBE D: corrected pttActual for d=4 [124, 607, 697, 1637]");
		log.info("══════════════════════════════════════════════════════════════");
		// Two valid orderings observed in earlier test:
		//   ord 0: origin=[124,607,697,1637], dest=[1637,124,607,697]
		//   ord 1: origin=[124,607,697,1637], dest=[1637,124,697,607]
		analyseOrdering("d=4 ord 0", new DrtRequest[]{r124, r607, r697, r1637},
				new int[]{0, 1, 2, 3}, new int[]{3, 0, 1, 2}, cache, network, linkTT, t);
		analyseOrdering("d=4 ord 1", new DrtRequest[]{r124, r607, r697, r1637},
				new int[]{0, 1, 2, 3}, new int[]{3, 0, 2, 1}, cache, network, linkTT, t);
	}

	private static void analyseOrdering(String label, DrtRequest[] reqs,
			int[] originPerm, int[] destPerm, MatsimNetworkCache cache, Network network,
			Map<Id<Link>, Double> linkTT, double t) {
		int n = reqs.length;
		log.info("");
		log.info("─── {} : origin={}, dest={} ───",
				label, fmtPerm(originPerm, reqs), fmtPerm(destPerm, reqs));
		// Build link sequence
		Id<Link>[] seq = (Id<Link>[]) new Id[n * 2];
		for (int i = 0; i < n; i++) seq[i] = reqs[originPerm[i]].originLinkId;
		for (int i = 0; i < n; i++) seq[n + i] = reqs[destPerm[i]].destinationLinkId;

		// Production-style connTT: cache values (no traversal)
		double[] connTT = new double[n * 2 - 1];
		double startTime = reqs[originPerm[0]].getRequestTime();
		double currentTime = startTime;
		for (int i = 0; i < n * 2 - 1; i++) {
			TravelSegment seg = cache.getSegment(seq[i], seq[i + 1], currentTime);
			connTT[i] = seg.getTravelTime();
			currentTime += connTT[i];
		}

		// Print per-passenger pttActual under each convention
		log.info("  Per-passenger pttActual comparison (production vs corrected):");
		for (int i = 0; i < n; i++) {
			DrtRequest req = reqs[originPerm[i]];
			int origIdx = i;
			int destPosInDestArray = -1;
			for (int k = 0; k < n; k++) {
				if (destPerm[k] == originPerm[i]) { destPosInDestArray = k; break; }
			}
			int destIdx = n + destPosInDestArray;

			// Production: sum connTT[origIdx..destIdx-1]
			double prodPtt = 0;
			for (int j = origIdx; j < destIdx; j++) prodPtt += connTT[j];
			double prodPttFloored = Math.max(prodPtt, req.getTravelTime());

			// Corrected: connTT[origIdx..destIdx-1] + traversal of seq[origIdx+1..destIdx]
			// Each "drive between events" = cache + next-link.tt (the link being arrived at)
			double corrPtt = 0;
			for (int j = origIdx; j < destIdx; j++) {
				corrPtt += connTT[j] + linkTT.get(seq[j + 1]);
			}

			double maxTT = req.getMaxTravelTime();
			boolean prodOk = prodPttFloored <= maxTT;
			boolean corrOk = corrPtt <= maxTT;

			log.info("    req {}: prod_ptt={} (floor->{}, OK={}), "
					+ "corrected_ptt={} (OK={}), maxTT={}, gap_corr_to_prod={}",
					req.index, f1(prodPtt), f1(prodPttFloored), prodOk,
					f1(corrPtt), corrOk, f1(maxTT), f1(corrPtt - prodPtt));
		}
	}

	private static String fmtPerm(int[] perm, DrtRequest[] reqs) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < perm.length; i++) {
			if (i > 0) sb.append(",");
			sb.append(reqs[perm[i]].index);
		}
		sb.append("]");
		return sb.toString();
	}

	private static String f1(double v) {
		if (Double.isInfinite(v)) return "INF";
		return String.format("%.1f", v);
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
				double maxTT = Double.parseDouble(p[col.get("maxTravelTime")].trim());
				double maxDetour = Math.min(1.3, directTT > 0 ? maxTT / directTT : 1.3);
				double earliestDeparture = Double.parseDouble(p[col.get("earliestDeparture")].trim());
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
