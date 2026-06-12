package org.matsim.contrib.demand_extraction.algorithm.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
import org.matsim.contrib.demand_extraction.scenarios.LyonEqasimScenarioFixture;
import org.matsim.core.config.Config;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility;
import org.matsim.core.router.speedy.LeastCostPathTree;
import org.matsim.core.router.speedy.SpeedyALTFactory;
import org.matsim.core.router.speedy.SpeedyGraph;
import org.matsim.core.router.speedy.SpeedyGraphBuilder;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.LeastCostPathCalculator.Path;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;
import org.matsim.core.utils.misc.OptionalTime;

/**
 * Routing determinism on the Lyon network with {@link TimeDistanceTravelDisutility}:
 * Dijkstra ({@link LeastCostPathTree}) and A* ({@link SpeedyALTFactory}) must agree
 * byte-for-byte on cost, travel time, and distance for the same OD pairs.
 *
 * <p>Background: with a time-only disutility (the previous default for the deterministic
 * routing branch), many OD pairs in Lyon have multiple shortest-time paths with different
 * network distances. Dijkstra and A* expand the graph in different orders and pick
 * different equal-time paths, producing the +3.1% pair-count drift documented in
 * {@code docs/plans/2026-04-22-sssp-benchmark-findings.md}. A non-zero distance term
 * makes the shortest-cost path unique, so both algorithms converge.
 */
@Tag("scenario-lyon")
@EnabledIfEnvironmentVariable(named = "LYON_SCENARIO_DIR", matches = ".+")
class RoutingDeterminismTest {

	private static final Logger log = LogManager.getLogger(RoutingDeterminismTest.class);

	private static final double TIME_COEF = 1.0;
	private static final double DIST_COEF = 1e-4;
	private static final int N_OD_PAIRS = 2000;
	private static final long SEED = 42L;
	private static final int REQUEST_1010 = 1010;
	private static final int REQUEST_3259 = 3259;
	private static final int REQUEST_3265 = 3265;

	@Test
	void dijkstraAndSpeedyAltProduceIdenticalPathsUnderTimeDistanceDisutility() throws Exception {
		Network network = loadLyonNetwork();
		TravelTime tt = new FreeSpeedTravelTime();
		TravelDisutility td = new TimeDistanceTravelDisutility(tt, TIME_COEF, DIST_COEF);

		SpeedyGraph speedyGraph = SpeedyGraphBuilder.build(network);
		LeastCostPathTree tree = new LeastCostPathTree(speedyGraph, tt, td);
		LeastCostPathCalculator alt = new SpeedyALTFactory().createPathCalculator(network, td, tt);

		List<Id<Link>> linkIds = new ArrayList<>(network.getLinks().keySet());
		Random rng = new Random(SEED);
		int compared = 0;
		int unreachable = 0;
		Id<Link> currentTreeOrigin = null;

		for (int i = 0; i < N_OD_PAIRS; i++) {
			Link from = network.getLinks().get(linkIds.get(rng.nextInt(linkIds.size())));
			Link to = network.getLinks().get(linkIds.get(rng.nextInt(linkIds.size())));
			if (from == to) continue;

			// Lazy SSSP recompute: only when origin changes (huge speedup for repeated origins,
			// but in practice we draw OD pairs at random so each iteration is a fresh tree).
			if (!from.getId().equals(currentTreeOrigin)) {
				tree.calculate(from, 0.0, null, null);
				currentTreeOrigin = from.getId();
			}

			int toNodeIdx = to.getFromNode().getId().index();
			OptionalTime arrival = tree.getTime(toNodeIdx);
			if (arrival.isUndefined()) {
				unreachable++;
				continue;
			}

			double dijkstraCost = tree.getCost(toNodeIdx);
			double dijkstraTime = arrival.seconds(); // startTime was 0
			double dijkstraDist = tree.getDistance(toNodeIdx);

			Path altPath = alt.calcLeastCostPath(from, to, 0.0, null, null);
			assertTrue(altPath != null,
					"SpeedyALT returned null for " + from.getId() + " -> " + to.getId());

			double altDist = 0.0;
			for (Link l : altPath.links) altDist += l.getLength();

			assertEquals(dijkstraCost, altPath.travelCost, 1e-9,
					"cost mismatch at OD #" + i + " " + from.getId() + " -> " + to.getId());
			assertEquals(dijkstraTime, altPath.travelTime, 1e-9,
					"travel-time mismatch at OD #" + i + " " + from.getId() + " -> " + to.getId());
			assertEquals(dijkstraDist, altDist, 1e-6,
					"distance mismatch at OD #" + i + " " + from.getId() + " -> " + to.getId());
			compared++;
		}
		log.info("Compared {} reachable OD pairs ({} unreachable, {} self-pairs skipped)",
				compared, unreachable, N_OD_PAIRS - compared - unreachable);
		assertTrue(compared > N_OD_PAIRS / 2,
				"compared only " + compared + " OD pairs; sample size suspiciously low");
	}

	@Test
	void parallelSpeedyAltMatchesSequentialUnderTimeDistanceDisutility() throws Exception {
		Network network = loadLyonNetwork();
		TravelTime tt = new FreeSpeedTravelTime();
		assertParallelMatchesSequential(network, tt,
				new TimeDistanceTravelDisutility(tt, TIME_COEF, DIST_COEF), "TimeDistance");
	}

	@Test
	void parallelSpeedyAltMatchesSequentialUnderOnlyTimeDependentDisutility() throws Exception {
		// Belt-and-braces check for option 1 of the routing-determinism plan: keep the
		// existing OnlyTimeDependent disutility but enable parallel SpeedyALT in the
		// fast comparison test. This proves parallel ↔ sequential is byte-identical
		// even without the TimeDistance fix.
		Network network = loadLyonNetwork();
		TravelTime tt = new FreeSpeedTravelTime();
		assertParallelMatchesSequential(network, tt,
				new org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility(tt),
				"OnlyTimeDependent");
	}

	@Test
	void deterministicCacheMissDoesNotRouteDirectShortcutSlowerThanKnownViaOrigins() throws Exception {
		String requestsCsv = System.getenv("LYON_REQUESTS_CSV");
		assumeTrue(requestsCsv != null && !requestsCsv.isBlank(), "LYON_REQUESTS_CSV required");

		Network network = loadLyonNetwork();
		TravelTime tt = new FreeSpeedTravelTime();
		TravelDisutility td = new OnlyTimeDependentTravelDisutility(tt);
		MatsimNetworkCache cache = MatsimNetworkCacheTestFixture
				.createWithRouting(network, tt, td, 900);

		Map<Integer, RoutePoint> routePoints = loadRoutePoints(requestsCsv,
				REQUEST_1010, REQUEST_3259, REQUEST_3265);
		RoutePoint r1010 = routePoints.get(REQUEST_1010);
		RoutePoint r3259 = routePoints.get(REQUEST_3259);
		RoutePoint r3265 = routePoints.get(REQUEST_3265);
		assertTrue(r1010 != null && r3259 != null && r3265 != null,
				"Lyon regression requests must exist in the demand CSV");

		double departureTime = r1010.requestTime;
		@SuppressWarnings("unchecked")
		Id<Link>[] firstTargets = new Id[] { r3259.originLinkId };
		cache.batchPrecompute(r1010.originLinkId, departureTime, firstTargets, 5000.0);
		TravelSegment first = cache.getSegment(r1010.originLinkId, r3259.originLinkId, departureTime);
		assertTrue(first.isReachable(), "1010 origin -> 3259 origin must be reachable");

		double secondDepartureTime = departureTime + first.getTravelTime();
		@SuppressWarnings("unchecked")
		Id<Link>[] secondTargets = new Id[] { r3265.originLinkId };
		cache.batchPrecompute(r3259.originLinkId, secondDepartureTime, secondTargets, 5000.0);
		TravelSegment second = cache.getSegment(r3259.originLinkId, r3265.originLinkId, secondDepartureTime);
		assertTrue(second.isReachable(), "3259 origin -> 3265 origin must be reachable");

		TravelSegment direct = cache.getSegment(r1010.originLinkId, r3265.originLinkId, departureTime);
		assertTrue(direct.isReachable(), "1010 origin -> 3265 origin must be reachable");

		double via = first.getTravelTime() + second.getTravelTime();
		assertTrue(direct.getTravelTime() <= via + 1e-9,
				"direct deterministic cache miss must not be slower than known via-origin path: direct="
						+ direct.getTravelTime() + ", via=" + via);
	}

	private void assertParallelMatchesSequential(Network network, TravelTime tt,
			TravelDisutility td, String disutilityLabel) throws Exception {
		SpeedyALTFactory factory = new SpeedyALTFactory();

		List<Id<Link>> linkIds = new ArrayList<>(network.getLinks().keySet());
		Random rng = new Random(SEED);
		final int n = 1000;
		List<Link[]> ods = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			Link a = network.getLinks().get(linkIds.get(rng.nextInt(linkIds.size())));
			Link b = network.getLinks().get(linkIds.get(rng.nextInt(linkIds.size())));
			if (a != b) ods.add(new Link[] { a, b });
		}

		// Sequential reference run.
		LeastCostPathCalculator seqAlt = factory.createPathCalculator(network, td, tt);
		double[] seqCost = new double[ods.size()];
		double[] seqTime = new double[ods.size()];
		double[] seqDist = new double[ods.size()];
		for (int i = 0; i < ods.size(); i++) {
			Path p = seqAlt.calcLeastCostPath(ods.get(i)[0], ods.get(i)[1], 0.0, null, null);
			seqCost[i] = p == null ? Double.NaN : p.travelCost;
			seqTime[i] = p == null ? Double.NaN : p.travelTime;
			double d = 0.0;
			if (p != null) for (Link l : p.links) d += l.getLength();
			seqDist[i] = p == null ? Double.NaN : d;
		}

		// Parallel run with thread-local SpeedyALT instances (production pattern).
		int threads = 8;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		ThreadLocal<LeastCostPathCalculator> tlAlt =
				ThreadLocal.withInitial(() -> factory.createPathCalculator(network, td, tt));
		double[] parCost = new double[ods.size()];
		double[] parTime = new double[ods.size()];
		double[] parDist = new double[ods.size()];
		List<Future<?>> futures = new ArrayList<>(ods.size());
		for (int i = 0; i < ods.size(); i++) {
			final int idx = i;
			futures.add(pool.submit(() -> {
				Path p = tlAlt.get().calcLeastCostPath(ods.get(idx)[0], ods.get(idx)[1], 0.0, null, null);
				parCost[idx] = p == null ? Double.NaN : p.travelCost;
				parTime[idx] = p == null ? Double.NaN : p.travelTime;
				double d = 0.0;
				if (p != null) for (Link l : p.links) d += l.getLength();
				parDist[idx] = p == null ? Double.NaN : d;
			}));
		}
		for (Future<?> f : futures) f.get();
		pool.shutdown();
		assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "thread pool did not shut down");

		// Byte-identical comparison: NaN must equal NaN (both unreachable for the same OD).
		for (int i = 0; i < ods.size(); i++) {
			if (Double.isNaN(seqCost[i]) || Double.isNaN(parCost[i])) {
				assertTrue(Double.isNaN(seqCost[i]) && Double.isNaN(parCost[i]),
						"reachability differs at OD #" + i);
				continue;
			}
			assertEquals(seqCost[i], parCost[i], 0.0, "cost differs at OD #" + i);
			assertEquals(seqTime[i], parTime[i], 0.0, "travel time differs at OD #" + i);
			assertEquals(seqDist[i], parDist[i], 0.0, "distance differs at OD #" + i);
		}
		log.info("[{}] Parallel ({} threads) vs sequential SpeedyALT byte-identical on {} OD pairs",
				disutilityLabel, threads, ods.size());
	}

	private static Network loadLyonNetwork() throws Exception {
		String scenarioDir = System.getenv("LYON_SCENARIO_DIR");
		String prefix = System.getenv().getOrDefault("LYON_SCENARIO_PREFIX", "lyon_drt_area_");
		int samplePct = Integer.parseInt(System.getenv().getOrDefault("LYON_SAMPLE_PCT", "1"));

		LyonEqasimScenarioFixture fixture = new LyonEqasimScenarioFixture(samplePct, scenarioDir, prefix, "");
		Config config = fixture.createConfig(java.nio.file.Path.of("test/output/routing-determinism"));
		config.plans().setInputFile(null);
		config.vehicles().setVehiclesFile(null);
		config.facilities().setInputFile(null);
		config.transit().setTransitScheduleFile(null);
		config.transit().setVehiclesFile(null);

		Scenario scenario = ScenarioUtils.createScenario(config);
		ScenarioUtils.loadScenario(scenario);
		return scenario.getNetwork();
	}

	private record RoutePoint(Id<Link> originLinkId, double requestTime) {}

	private static Map<Integer, RoutePoint> loadRoutePoints(String csvPath, int... wantedIndices) throws Exception {
		java.util.Set<Integer> wanted = new java.util.HashSet<>();
		for (int index : wantedIndices) wanted.add(index);

		Map<Integer, RoutePoint> result = new HashMap<>();
		try (BufferedReader reader = new BufferedReader(new FileReader(csvPath))) {
			String headerLine = reader.readLine();
			if (headerLine == null) return result;

			String[] headers = headerLine.split(",", -1);
			Map<String, Integer> columns = new HashMap<>();
			for (int i = 0; i < headers.length; i++) columns.put(headers[i].trim(), i);

			String line;
			while ((line = reader.readLine()) != null && result.size() < wanted.size()) {
				if (line.isBlank()) continue;
				String[] fields = line.split(",", -1);
				int index = Integer.parseInt(fields[columns.get("index")].trim());
				if (!wanted.contains(index)) continue;

				Id<Link> originLinkId = Id.createLinkId(fields[columns.get("originLinkId")].trim());
				double requestTime = Double.parseDouble(fields[columns.get("requestTime")].trim());
				result.put(index, new RoutePoint(originLinkId, requestTime));
			}
		}
		return result;
	}
}
