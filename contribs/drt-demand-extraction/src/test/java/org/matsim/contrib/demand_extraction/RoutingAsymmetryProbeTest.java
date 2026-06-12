package org.matsim.contrib.demand_extraction;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
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
 * Probe whether the 52-second 818 vs 766 discrepancy in
 * {@link D3VsD4FeasibilityComparisonTest} comes from time-dependent routing.
 *
 * <p>If time-dependent routing were the cause, querying d(607-orig, 1637-orig)
 * at time t1 vs t2 would give different results. We test:
 * <ul>
 *   <li>Same time-bin: t1=26677, t2=26825 — both fall in bin 29 (timeBinSize=900),
 *       so the cache forces canonical departure time 26550 for BOTH. Should give
 *       identical results.</li>
 *   <li>Many different times: bin 0..N at fixed scale — does d(607,1637) vary?</li>
 *   <li>Directly compare: d(607,1637) vs d(607,697) + d(697,1637) at every probe
 *       time. Triangle inequality should hold if the router is optimal.</li>
 * </ul>
 */
@Tag("scenario-lyon")
@EnabledIfEnvironmentVariable(named = "LYON_SCENARIO_DIR", matches = ".+")
class RoutingAsymmetryProbeTest {

	private static final Logger log = LogManager.getLogger(RoutingAsymmetryProbeTest.class);

	@Test
	void probeRoutingAsymmetry() throws Exception {
		String requestsCsv = System.getenv("LYON_REQUESTS_CSV");
		assumeTrue(requestsCsv != null && !requestsCsv.isBlank(),
				"LYON_REQUESTS_CSV required");
		String scenarioDir = System.getenv("LYON_SCENARIO_DIR");
		String prefix = System.getenv().getOrDefault("LYON_SCENARIO_PREFIX", "lyon_drt_area_");
		int samplePct = Integer.parseInt(System.getenv().getOrDefault("LYON_SAMPLE_PCT", "1"));

		LyonEqasimScenarioFixture fixture = new LyonEqasimScenarioFixture(
				samplePct, scenarioDir, prefix, "");
		Config config = fixture.createConfig(java.nio.file.Path.of("test/output/routing-probe"));
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

		// Find req 124, 607, 697, 1637 to get their origin links
		List<DrtRequest> requests = loadRequestsFromCsv(requestsCsv, network);
		Id<Link> link607 = null, link697 = null, link1637 = null;
		for (DrtRequest r : requests) {
			if (r.index == 607) link607 = r.originLinkId;
			else if (r.index == 697) link697 = r.originLinkId;
			else if (r.index == 1637) link1637 = r.originLinkId;
		}
		log.info("Origin links: 607={}, 697={}, 1637={}", link607, link697, link1637);

		// ── Probe 1: Same time-bin (timeBinSize=900) — should give identical results ──
		log.info("");
		log.info("─── PROBE 1: same-bin queries (timeBinSize=900) ───");
		MatsimNetworkCache cache900 = MatsimNetworkCacheTestFixture
				.createWithRouting(network, tt, td, 900);
		printQuery(cache900, link607, link1637, 26677.0, "d=3 query time");
		printQuery(cache900, link607, link1637, 26825.0, "d=4 segment 2 time");
		printQuery(cache900, link607, link697, 26677.0, "d=4 segment 1 time");
		printQuery(cache900, link697, link1637, 26825.0, "d=4 segment 2 time");
		// Triangle check at the same canonical time
		TravelSegment direct = cache900.getSegment(link607, link1637, 26677.0);
		TravelSegment leg1 = cache900.getSegment(link607, link697, 26677.0);
		TravelSegment leg2 = cache900.getSegment(link697, link1637, 26677.0);
		double sumLegs = leg1.getTravelTime() + leg2.getTravelTime();
		log.info("  Triangle check at t=26677: direct={}s, via_697={}s, gap={}s",
				direct.getTravelTime(), sumLegs, direct.getTravelTime() - sumLegs);

		// ── Probe 2: Different time bins — does direct d(607, 1637) vary? ──
		log.info("");
		log.info("─── PROBE 2: time-bin sweep (timeBinSize=900) ───");
		double[] probeTimes = { 0, 3600, 7200, 14400, 21600, 25200, 26677, 28800,
				32400, 36000, 43200, 50400, 57600, 64800, 72000, 79200, 86400 };
		for (double t : probeTimes) {
			MatsimNetworkCache c = MatsimNetworkCacheTestFixture
					.createWithRouting(network, tt, td, 900);
			TravelSegment d = c.getSegment(link607, link1637, t);
			TravelSegment l1 = c.getSegment(link607, link697, t);
			TravelSegment l2 = c.getSegment(link697, link1637, t);
			double s = l1.getTravelTime() + l2.getTravelTime();
			log.info("  t={}s (bin={}): direct={}s, via_697={}s, gap={}s",
					String.format("%.0f", t), (int)(t / 900),
					String.format("%.1f", d.getTravelTime()),
					String.format("%.1f", s),
					String.format("%.1f", d.getTravelTime() - s));
		}

		// ── Probe 3: timeBinSize=1 (no binning, true time-dependence) ──
		log.info("");
		log.info("─── PROBE 3: timeBinSize=1 (no-binning, exact time-dependence) ───");
		MatsimNetworkCache cache1 = MatsimNetworkCacheTestFixture
				.createWithRouting(network, tt, td, 1);
		printQuery(cache1, link607, link1637, 26677.0, "exact t=26677");
		printQuery(cache1, link607, link1637, 26825.0, "exact t=26825");
		TravelSegment d1 = cache1.getSegment(link607, link1637, 26677.0);
		TravelSegment d2 = cache1.getSegment(link607, link1637, 26825.0);
		log.info("  Direct at 26677={}, at 26825={}, identical={}",
				d1.getTravelTime(), d2.getTravelTime(),
				Math.abs(d1.getTravelTime() - d2.getTravelTime()) < 0.01);

		// ── Probe 4: timeBinSize=1, triangle check at common time ──
		log.info("");
		log.info("─── PROBE 4: triangle check at exact times ───");
		TravelSegment dEx = cache1.getSegment(link607, link1637, 26677.0);
		TravelSegment l1Ex = cache1.getSegment(link607, link697, 26677.0);
		TravelSegment l2Ex = cache1.getSegment(link697, link1637, 26677.0);
		log.info("  ALL at t=26677: direct={}s, leg1(607→697)={}s, leg2(697→1637)={}s",
				dEx.getTravelTime(), l1Ex.getTravelTime(), l2Ex.getTravelTime());
		log.info("  via_697 sum={}s, gap=direct-via_697={}s",
				l1Ex.getTravelTime() + l2Ex.getTravelTime(),
				dEx.getTravelTime() - l1Ex.getTravelTime() - l2Ex.getTravelTime());

		// ── Probe 5: Vary the leg2 time (697→1637) — what does it return at different times? ──
		log.info("");
		log.info("─── PROBE 5: leg2(697→1637) at varying times (timeBinSize=900) ───");
		for (double t : probeTimes) {
			MatsimNetworkCache c = MatsimNetworkCacheTestFixture
					.createWithRouting(network, tt, td, 900);
			TravelSegment seg = c.getSegment(link697, link1637, t);
			log.info("  leg2 at t={}s: {}s",
					String.format("%.0f", t),
					String.format("%.1f", seg.getTravelTime()));
		}
	}

	private static void printQuery(MatsimNetworkCache cache, Id<Link> from, Id<Link> to,
			double t, String label) {
		TravelSegment seg = cache.getSegment(from, to, t);
		log.info("  d({}→{}, t={}, {}): tt={}s, dist={}m, reachable={}",
				from, to, String.format("%.0f", t), label,
				String.format("%.1f", seg.getTravelTime()),
				String.format("%.0f", seg.getDistance()), seg.isReachable());
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
				double directTT = Double.parseDouble(p[col.get("directTravelTime")].trim());
				double maxTT = Double.parseDouble(p[col.get("maxTravelTime")].trim());
				double maxDetour = directTT > 0 ? maxTT / directTT : 1.3;
				maxDetour = Math.min(maxDetour, 1.3);
				double earliestDeparture = Double.parseDouble(p[col.get("earliestDeparture")].trim());
				double cappedMaxTT = directTT * maxDetour;
				double latestArrival = Math.min(
						Double.parseDouble(p[col.get("latestArrival")].trim()),
						earliestDeparture + cappedMaxTT);
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
						.earliestDeparture(earliestDeparture).latestArrival(latestArrival)
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
