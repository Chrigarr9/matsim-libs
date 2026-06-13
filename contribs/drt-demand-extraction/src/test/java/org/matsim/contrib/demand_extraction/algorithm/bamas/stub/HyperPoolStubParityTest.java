package org.matsim.contrib.demand_extraction.algorithm.bamas.stub;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
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
 * Plan A2 Task 4 parity gate: verifies that {@link HyperPoolStubRideStore} (stub mode ON,
 * stop-based ON) produces byte-identical {@code exmas_rides.csv} output vs the fat/master
 * path (stub mode OFF).
 *
 * <p>Both runs are single-threaded ({@code algorithmProcessCount=1,
 * heuristicsProcessCount=1}) to avoid the known ~1% connection-cache non-determinism
 * under parallel execution (documented: two concurrent threads fill the shared cache in
 * non-deterministic order, causing ~1% ride-set divergence across runs even without the
 * stub path — NOT an A2 bug).
 *
 * <p>Uses the lightweight dvrp-grid scenario with HyperPool enabled (same setup as
 * {@link org.matsim.contrib.demand_extraction.ExMasHyperPoolE2ETest}) for a fast,
 * self-contained parity check. The Kelheim HyperPool scenario (with 3× duplicated
 * population) is the correctness reference; the grid scenario is the regression gate.
 *
 * <h3>Acceptance criterion</h3>
 * SHA-256 of {@code exmas_rides.csv} (fat) == SHA-256 of {@code exmas_rides.csv} (stub).
 * This is the primary gate from Plan A2 §Task-4 Step 4.
 */
@Tag("fast")
class HyperPoolStubParityTest {

    @Test
    void hyperPoolStubModeProducesIdenticalRidesCsvToFatMode() throws IOException, NoSuchAlgorithmException {
        Path baseDir = Path.of("test/output/hyperpool-stub-parity-test");
        Files.createDirectories(baseDir);

        // Run 1: fat mode (stubModeEnabled=false) — master/reference path.
        Path fatDir = baseDir.resolve("fat");
        Path fatRidesCsv = runHyperPool(fatDir, false);

        // Run 2: stub mode (stubModeEnabled=true) — Plan A2 Task 4 new path.
        Path stubDir = baseDir.resolve("stub");
        Path stubRidesCsv = runHyperPool(stubDir, true);

        // Primary gate: SHA-256 equality.
        String fatSha  = sha256(fatRidesCsv);
        String stubSha = sha256(stubRidesCsv);

        // Provide a diagnostic diff count on mismatch.
        if (!fatSha.equals(stubSha)) {
            List<String> fatLines  = Files.readAllLines(fatRidesCsv);
            List<String> stubLines = Files.readAllLines(stubRidesCsv);
            int diffLines = 0;
            int minLen = Math.min(fatLines.size(), stubLines.size());
            for (int i = 0; i < minLen; i++) {
                if (!fatLines.get(i).equals(stubLines.get(i))) diffLines++;
            }
            int extraFat  = fatLines.size()  - minLen;
            int extraStub = stubLines.size() - minLen;
            throw new AssertionError(String.format(
                "Plan A2 Task 4 parity FAILED: exmas_rides.csv SHA-256 mismatch.%n"
                + "  fat  SHA: %s (%d lines)%n"
                + "  stub SHA: %s (%d lines)%n"
                + "  Differing lines: %d common, +%d fat-only, +%d stub-only%n"
                + "  fat  CSV: %s%n"
                + "  stub CSV: %s",
                fatSha,  fatLines.size(),
                stubSha, stubLines.size(),
                diffLines, extraFat, extraStub,
                fatRidesCsv.toAbsolutePath(),
                stubRidesCsv.toAbsolutePath()));
        }
        assertEquals(fatSha, stubSha, "exmas_rides.csv must be byte-identical between fat and stub HyperPool paths");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Run the dvrp-grid HyperPool scenario and return the path to the generated
     * {@code exmas_rides.csv}.
     *
     * @param outputDir      output directory for this run
     * @param stubModeEnabled true = stub path (Plan A2); false = fat/master path
     */
    private static Path runHyperPool(Path outputDir, boolean stubModeEnabled) throws IOException {
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
        configureExMasWithHyperPool(config, stubModeEnabled);

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

    private static void configureExMasWithHyperPool(Config config, boolean stubModeEnabled) {
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
        exMasConfig.setMinMaxDetourFactor(1.0);
        exMasConfig.setMinMaxWaitingTime(0.0);
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

        // Deterministic network routing: forces cacheModes() to route serially so the
        // DRT direct-distance for each OD is identical across runs (parallel routing
        // fills the shared SpeedyALT connection cache in non-deterministic order →
        // different OD distances in different runs even for the same person/trip).
        exMasConfig.setUseDeterministicNetworkRouting(true);

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

        // The parity knob: fat vs stub path.
        exMasConfig.setStubModeEnabled(stubModeEnabled);
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

    private static String sha256(Path path) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = Files.readAllBytes(path);
        byte[] hash  = digest.digest(bytes);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
