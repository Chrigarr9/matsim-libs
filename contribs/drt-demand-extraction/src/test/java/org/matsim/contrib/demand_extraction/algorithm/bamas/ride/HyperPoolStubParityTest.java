package org.matsim.contrib.demand_extraction.algorithm.bamas.ride;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.DemandExtractionModule;
import org.matsim.contrib.drt.run.DrtControlerCreator;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.examples.ExamplesUtils;

/**
 * Stop-based + HyperPool regression gate: verifies that the (now single-path) BAMAS engine
 * produces byte-identical {@code exmas_rides.csv} to a frozen golden on the dvrp-grid HyperPool
 * scenario (stop-based ON, hyper-pooling ON).
 *
 * <h3>History</h3>
 * Originally a fat-vs-stub parity gate (Plan A2 Task 4): it ran the engine twice
 * ({@code stubModeEnabled} false then true) and asserted the two {@code exmas_rides.csv} were
 * SHA-256 equal. The BAMAS cleanup deleted the fat path, so the fat comparand no longer exists.
 * Before deleting it, the fat output (== the then-passing stub output) was frozen as the committed
 * golden {@code hyperpool-stopbased-golden.exmas_rides.csv} (SHA-256
 * {@code aca73f22257b4295d9d66af9288f472600c920847a76e326bc9a90fceffde5fe}). This test now pins the
 * single-path output to that golden, so the original fat-vs-stub guarantee survives the deletion:
 * a regression that changed the stop-based/hyperpool output would change the SHA here.
 *
 * <p>Single-threaded ({@code algorithmProcessCount=heuristicsProcessCount=1}) and
 * {@code routingRandomness=0} for byte reproducibility (see {@link #configureMonetaryConstants}).
 */
@Tag("fast")
class HyperPoolStubParityTest {

    /** SHA-256 of the frozen golden — recorded for traceability; the test compares the file bytes. */
    private static final String GOLDEN_SHA =
            "aca73f22257b4295d9d66af9288f472600c920847a76e326bc9a90fceffde5fe";
    private static final String GOLDEN_RESOURCE = "hyperpool-stopbased-golden.exmas_rides.csv";

    @Test
    void hyperPoolOutputMatchesFrozenGolden() throws IOException, NoSuchAlgorithmException {
        Path baseDir = Path.of("test/output/hyperpool-stub-parity-test");
        Files.createDirectories(baseDir);

        Path ridesCsv = runHyperPool(baseDir.resolve("run"));

        byte[] actual = Files.readAllBytes(ridesCsv);
        byte[] golden = readGoldenResource();

        // Sanity: the committed golden bytes still hash to the recorded SHA.
        assertEquals(GOLDEN_SHA, sha256(golden),
                "frozen golden resource was modified — its SHA no longer matches the recorded constant");

        if (!sha256(actual).equals(sha256(golden))) {
            List<String> actualLines = Files.readAllLines(ridesCsv);
            List<String> goldenLines = List.of(new String(golden, StandardCharsets.UTF_8).split("\n", -1));
            int diffLines = 0;
            int minLen = Math.min(actualLines.size(), goldenLines.size());
            for (int i = 0; i < minLen; i++) {
                if (!actualLines.get(i).stripTrailing().equals(goldenLines.get(i).stripTrailing())) diffLines++;
            }
            throw new AssertionError(String.format(
                "Stop-based/HyperPool output regressed vs frozen golden.%n"
                + "  golden SHA: %s (%d lines)%n"
                + "  actual SHA: %s (%d lines)%n"
                + "  differing common lines: %d, +%d golden-only, +%d actual-only%n"
                + "  actual CSV: %s",
                sha256(golden), goldenLines.size(),
                sha256(actual), actualLines.size(),
                diffLines, Math.max(0, goldenLines.size() - minLen), Math.max(0, actualLines.size() - minLen),
                ridesCsv.toAbsolutePath()));
        }
        assertEquals(sha256(golden), sha256(actual),
                "exmas_rides.csv must be byte-identical to the frozen stop-based/HyperPool golden");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Run the dvrp-grid HyperPool scenario and return the path to the generated exmas_rides.csv. */
    private static Path runHyperPool(Path outputDir) throws IOException {
        Files.createDirectories(outputDir);

        URL scenarioUrl = ExamplesUtils.getTestScenarioURL("dvrp-grid");
        Config config = ConfigUtils.loadConfig(
                new URL(scenarioUrl, "one_shared_taxi_config.xml").toString(),
                new MultiModeDrtConfigGroup(),
                new DvrpConfigGroup(),
                new ExMasConfigGroup());

        config.removeModule("otfvis");
        config.controller().setOutputDirectory(outputDir.toString());
        config.controller().setOverwriteFileSetting(
                OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);

        configureMonetaryConstants(config);
        configureExMasWithHyperPool(config);

        Scenario scenario = DrtControlerCreator.createScenarioWithDrtRouteFactory(config);
        ScenarioUtils.loadScenario(scenario);
        enhancePopulationWithAttributes(scenario.getPopulation());

        Controler controler = DrtControlerCreator.createControler(config, scenario, false);
        controler.addOverridingModule(new DemandExtractionModule());
        controler.run();

        // The run-id for this scenario is null (no controller run-id set).
        Path drtDemandDir = outputDir.resolve("drt_demand");
        return drtDemandDir.resolve("null.exmas_rides.csv");
    }

    private static void configureMonetaryConstants(Config config) {
        // Phase-1 TripRouter routing must be deterministic under parallel cacheModes. The
        // dvrp-grid scenario binds the MATSim default RandomizingTimeDistanceTravelDisutility
        // (no explicit car factory here), which draws an independent sigma=3.0 perturbation per
        // router instance — and ModeRoutingCache builds a fresh TripRouter per thread. Different
        // per-thread perturbations pick different equal-time paths, so req.directDistance flips
        // run-to-run (1220<->1230), breaking byte parity. The DeterministicTravelDisutility
        // eps tie-break cannot fix this: eps (~1e-6 x min gradient) is dwarfed by the sigma draws.
        // Turning routing randomness off removes the draw at the source; the car distance-rate term
        // below then orders different-length paths uniquely. (Served distances are already stable —
        // MatsimNetworkCache wraps one shared instance.)
        config.routing().setRoutingRandomness(0.0);

        ScoringConfigGroup scoring = config.scoring();
        scoring.setMarginalUtilityOfMoney(1.0);
        scoring.setMarginalUtlOfWaitingPt_utils_hr(0.0);

        scoring.getOrCreateModeParams(TransportMode.car).setMonetaryDistanceRate(-0.0002);
        scoring.getOrCreateModeParams(TransportMode.pt).setDailyMonetaryConstant(-2.0);
        scoring.getOrCreateModeParams(TransportMode.pt).setMonetaryDistanceRate(-0.0001);

        ScoringConfigGroup.ModeParams walkParams = scoring.getOrCreateModeParams(TransportMode.walk);
        walkParams.setMarginalUtilityOfTraveling(-0.1);
        walkParams.setConstant(0.0);
        walkParams.setMonetaryDistanceRate(0.0);
    }

    private static void configureExMasWithHyperPool(Config config) {
        ExMasConfigGroup exMasConfig = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);

        exMasConfig.setDrtMode("drt");
        Set<String> baseModes = new HashSet<>();
        baseModes.add(TransportMode.car);
        exMasConfig.setBaseModes(baseModes);
        exMasConfig.setDrtRoutingMode(TransportMode.car);

        Set<String> privateVehicles = new HashSet<>();
        privateVehicles.add(TransportMode.car);
        privateVehicles.add("bike");
        exMasConfig.setPrivateVehicleModes(privateVehicles);

        exMasConfig.setMinDrtCostPerKm(0.0);
        exMasConfig.setMinDrtAccessEgressDistance(0.0);
        exMasConfig.setMaxDetourFactor(2.0);
        exMasConfig.setSearchHorizon(0.0);
        exMasConfig.setNegativeFlexibilityAbsoluteMap("default:9000.0");
        exMasConfig.setPositiveFlexibilityAbsoluteMap("default:9000.0");
        exMasConfig.setCalcPredecessors(true);
        exMasConfig.setCalcShapleyValues(true);

        // Single-threaded to avoid connection-cache non-determinism (documented in plan).
        exMasConfig.setAlgorithmProcessCount(1);
        exMasConfig.setHeuristicsProcessCount(1);

        // HyperPool Stage 1: stop-based pooling.
        exMasConfig.setEnableStopBased(true);
        exMasConfig.setMaxWalkDistanceMeters(500.0);
        exMasConfig.setStopSearchRadiusMeters(300.0);

        // HyperPool Stage 2: hyper-pooling.
        exMasConfig.setEnableHyperPooling(true);
        exMasConfig.setHyperPoolMaxStopRelocationMeters(200.0);
        exMasConfig.setHyperPoolMinOccupancy(2);
        exMasConfig.setHyperPoolTimeWindowSeconds(900.0);
        exMasConfig.setHyperPoolStopProximityMeters(100.0);
    }

    private static void enhancePopulationWithAttributes(Population population) {
        int idx = 0;
        for (Person person : population.getPersons().values()) {
            int t = idx % 3;
            if (t == 0) {
                PersonUtils.setLicence(person, "yes");
                PersonUtils.setCarAvail(person, "always");
            } else if (t == 1) {
                PersonUtils.setLicence(person, "no");
                PersonUtils.setCarAvail(person, "never");
            } else {
                PersonUtils.setLicence(person, "yes");
                PersonUtils.setCarAvail(person, "sometimes");
            }
            idx++;
        }
    }

    private static byte[] readGoldenResource() throws IOException {
        try (InputStream in = HyperPoolStubParityTest.class.getResourceAsStream(GOLDEN_RESOURCE)) {
            if (in == null) {
                throw new IOException("Golden resource not found on classpath: " + GOLDEN_RESOURCE);
            }
            return in.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
