package org.matsim.contrib.demand_extraction.algorithm.network;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.demand_extraction.scenarios.LyonEqasimScenarioFixture;
import org.matsim.core.config.Config;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility;
import org.matsim.core.router.speedy.LeastCostPathTree;
import org.matsim.core.router.speedy.SpeedyGraph;
import org.matsim.core.router.speedy.SpeedyGraphBuilder;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;
import org.matsim.core.utils.misc.OptionalTime;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.population.PopulationUtils;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

/**
 * Microbenchmark: cost of bounded vs unbounded SSSP from sample request origins on the Lyon
 * road network.
 *
 * <p>Compares three variants per origin:
 * <ul>
 *   <li><b>mean-bound</b>: TravelTimeStopCriterion(1644s) — mean maxTravelTime over Lyon 1%</li>
 *   <li><b>globalMax-bound</b>: TravelTimeStopCriterion(5136s) — max maxTravelTime over Lyon 1%</li>
 *   <li><b>unbounded</b>: full SSSP, no stop criterion</li>
 * </ul>
 *
 * <p>Reports avg ms, avg nodes-reached, and ratios so we can decide whether the global-max
 * fix is "free" (≈ same cost as today) or whether further work (lazy backfill, smarter
 * bounds) is warranted.
 */
@Tag("scenario-lyon")
@EnabledIfEnvironmentVariable(named = "LYON_SCENARIO_DIR", matches = ".+")
class SsspBoundBenchmarkTest {

    private static final Logger log = LogManager.getLogger(SsspBoundBenchmarkTest.class);

    private static final int N_SAMPLES = 20;
    private static final int N_WARMUP = 3;
    private static final double MEAN_BOUND = 1644.0;
    private static final double GLOBAL_MAX_BOUND = 5136.0;

    @Test
    void compareBoundedVsUnbounded() throws Exception {
        String scenarioDir = System.getenv("LYON_SCENARIO_DIR");
        String prefix = System.getenv().getOrDefault("LYON_SCENARIO_PREFIX", "lyon_drt_area_");
        String requestsCsv = System.getenv("LYON_REQUESTS_CSV");

        // Load network only — no plans/vehicles/transit needed for SSSP timing.
        LyonEqasimScenarioFixture fixture = new LyonEqasimScenarioFixture(1, scenarioDir, prefix, "");
        Config config = fixture.createConfig(java.nio.file.Path.of("test/output/sssp-bench/matsim-output"));
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

        // Sample N origin link ids from the requests CSV.
        List<Id<Link>> origins = sampleOrigins(requestsCsv, network, N_SAMPLES + N_WARMUP);
        log.info("Sampled {} origins from {}", origins.size(), requestsCsv);

        // Build SSSP infrastructure (mirrors MatsimNetworkCache constructor).
        TravelTime tt = new FreeSpeedTravelTime();
        TravelDisutility td = new OnlyTimeDependentTravelDisutility(tt);
        SpeedyGraph speedyGraph = SpeedyGraphBuilder.build(network);
        LeastCostPathTree tree = new LeastCostPathTree(speedyGraph, tt, td);

        Person dummyPerson = PopulationUtils.getFactory().createPerson(Id.createPersonId("bench"));
        VehicleType dummyType = VehicleUtils.createVehicleType(Id.create("car", VehicleType.class));
        Vehicle dummyVehicle = VehicleUtils.createVehicle(Id.createVehicleId("bench"), dummyType);

        double startTime = 26000.0;  // mid-bin reference time

        int totalNodes = network.getNodes().size();

        // Warmup
        for (int i = 0; i < N_WARMUP; i++) {
            Link from = network.getLinks().get(origins.get(i));
            tree.calculate(from, startTime, dummyPerson, dummyVehicle,
                    new LeastCostPathTree.TravelTimeStopCriterion(GLOBAL_MAX_BOUND));
        }

        // Three variants × N samples
        long[] meanNanos = new long[N_SAMPLES];
        long[] maxNanos = new long[N_SAMPLES];
        long[] unbNanos = new long[N_SAMPLES];
        int[] meanReached = new int[N_SAMPLES];
        int[] maxReached = new int[N_SAMPLES];
        int[] unbReached = new int[N_SAMPLES];

        for (int s = 0; s < N_SAMPLES; s++) {
            Link from = network.getLinks().get(origins.get(N_WARMUP + s));

            long t0 = System.nanoTime();
            tree.calculate(from, startTime, dummyPerson, dummyVehicle,
                    new LeastCostPathTree.TravelTimeStopCriterion(MEAN_BOUND));
            meanNanos[s] = System.nanoTime() - t0;
            meanReached[s] = countReached(tree, totalNodes);

            t0 = System.nanoTime();
            tree.calculate(from, startTime, dummyPerson, dummyVehicle,
                    new LeastCostPathTree.TravelTimeStopCriterion(GLOBAL_MAX_BOUND));
            maxNanos[s] = System.nanoTime() - t0;
            maxReached[s] = countReached(tree, totalNodes);

            t0 = System.nanoTime();
            tree.calculate(from, startTime, dummyPerson, dummyVehicle);
            unbNanos[s] = System.nanoTime() - t0;
            unbReached[s] = countReached(tree, totalNodes);
        }

        report("MEAN-bound (1644s)", meanNanos, meanReached, totalNodes);
        report("GLOBAL-MAX-bound (5136s)", maxNanos, maxReached, totalNodes);
        report("UNBOUNDED", unbNanos, unbReached, totalNodes);

        // Summary ratios
        double meanAvgMs = avgMs(meanNanos);
        double maxAvgMs = avgMs(maxNanos);
        double unbAvgMs = avgMs(unbNanos);
        log.info("");
        log.info("=== RATIO SUMMARY ===");
        log.info(String.format(Locale.ROOT, "GLOBAL-MAX / MEAN     = %.2fx", maxAvgMs / meanAvgMs));
        log.info(String.format(Locale.ROOT, "UNBOUNDED  / MEAN     = %.2fx", unbAvgMs / meanAvgMs));
        log.info(String.format(Locale.ROOT, "UNBOUNDED  / GLOBAL-MAX = %.2fx", unbAvgMs / maxAvgMs));
    }

    private static int countReached(LeastCostPathTree tree, int totalNodes) {
        int count = 0;
        for (int i = 0; i < totalNodes; i++) {
            OptionalTime t = tree.getTime(i);
            if (t.isDefined()) count++;
        }
        return count;
    }

    private static double avgMs(long[] nanos) {
        double sum = 0;
        for (long n : nanos) sum += n;
        return sum / nanos.length / 1_000_000.0;
    }

    private static void report(String label, long[] nanos, int[] reached, int totalNodes) {
        double sumMs = 0, sumReach = 0;
        long minNs = Long.MAX_VALUE, maxNs = Long.MIN_VALUE;
        for (int i = 0; i < nanos.length; i++) {
            sumMs += nanos[i] / 1_000_000.0;
            sumReach += reached[i];
            if (nanos[i] < minNs) minNs = nanos[i];
            if (nanos[i] > maxNs) maxNs = nanos[i];
        }
        double avgMs = sumMs / nanos.length;
        double avgReach = sumReach / nanos.length;
        log.info(String.format(Locale.ROOT,
                "%-24s  avg=%6.1fms  min=%6.1fms  max=%6.1fms  reached=%5.0f / %d  (%.1f%%)",
                label, avgMs, minNs / 1e6, maxNs / 1e6, avgReach, totalNodes,
                100.0 * avgReach / totalNodes));
    }

    private static List<Id<Link>> sampleOrigins(String csv, Network network, int n) throws Exception {
        List<Id<Link>> all = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(csv))) {
            String header = r.readLine();
            String[] cols = header.split(",");
            int originIdx = -1;
            for (int i = 0; i < cols.length; i++) {
                if (cols[i].equals("originLinkId")) originIdx = i;
            }
            String line;
            while ((line = r.readLine()) != null) {
                String[] parts = line.split(",");
                Id<Link> id = Id.createLinkId(parts[originIdx]);
                if (network.getLinks().containsKey(id)) all.add(id);
            }
        }
        // Deterministic sample — fixed seed.
        Random rnd = new Random(42);
        List<Id<Link>> sample = new ArrayList<>(n);
        for (int i = 0; i < n && !all.isEmpty(); i++) {
            sample.add(all.remove(rnd.nextInt(all.size())));
        }
        return sample;
    }
}
