package org.matsim.contrib.demand_extraction.algorithm.bamas.stub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup.CommuteFilter;
import org.matsim.contrib.demand_extraction.demand.DemandExtractionConfigValidator;
import org.matsim.contrib.demand_extraction.demand.DemandExtractionModule;
import org.matsim.contrib.drt.run.DrtControlerCreator;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.examples.ExamplesUtils;

/**
 * Plan A2 Task 7 parity gate — non-vacuous variant.
 *
 * <p>Verifies that stub mode ({@code stubModeEnabled=true}) produces byte-identical output
 * to fat mode ({@code stubModeEnabled=false}) on the Kelheim scenario, which actually
 * generates S2S and HyperPooled rides (unlike the dvrp-grid scenario used by
 * {@link HyperPoolStubParityTest}, which produces only DOOR_TO_DOOR rows).
 *
 * <p>Files compared:
 * <ul>
 *   <li>{@code kelheim-mini.exmas_rides.csv} — Phase 1-5 output (D2D + S2S rows)</li>
 *   <li>{@code kelheim-mini.hyperpool_rides.csv} — Phase 6 output (HyperPooled bundles)</li>
 * </ul>
 *
 * <p>The test exercises the core Task 4-6 changes:
 * <ul>
 *   <li>Task 4: S2S ride stubs (deferred materialisation) vs fat List&lt;Ride&gt;</li>
 *   <li>Task 5: stub-column materialisation replay bit-exactness</li>
 *   <li>Task 6: per-cluster {@code buildClusterRideCache} — each wrapper materialised once</li>
 * </ul>
 *
 * <p>Both runs are single-threaded and use deterministic network routing to avoid the
 * known connection-cache non-determinism under parallel execution (documented in plan A2).
 * Separate output directories avoid the Windows file-lock race condition.
 *
 * <h3>Acceptance criterion</h3>
 * SHA-256({@code exmas_rides.csv} fat) == SHA-256({@code exmas_rides.csv} stub) AND
 * SHA-256({@code hyperpool_rides.csv} fat) == SHA-256({@code hyperpool_rides.csv} stub).
 */
class KelheimHyperPoolStubParityTest {

    private static final String RUN_ID = "kelheim-mini";

    @Test
    void kelheimStubModeProducesIdenticalOutputToFatMode() throws IOException, NoSuchAlgorithmException {
        Path baseDir = Path.of("test/output/kelheim-hyperpool-stub-parity-test");
        Files.createDirectories(baseDir);

        // Run 1: fat mode (stubModeEnabled=false) — reference path.
        Path fatDir = baseDir.resolve("fat");
        RunOutput fat = runKelheimHyperPool(fatDir, false);

        // Run 2: stub mode (stubModeEnabled=true) — Plan A2 new path.
        Path stubDir = baseDir.resolve("stub");
        RunOutput stub = runKelheimHyperPool(stubDir, true);

        // Assert both runs produced the Phase 6 output file (guards against silent vacuity).
        assertTrue(Files.exists(fat.hyperPoolRidesCsv),
                "Fat mode must produce hyperpool_rides.csv at: " + fat.hyperPoolRidesCsv);
        assertTrue(Files.exists(stub.hyperPoolRidesCsv),
                "Stub mode must produce hyperpool_rides.csv at: " + stub.hyperPoolRidesCsv);

        // SHA-256 gate on exmas_rides.csv (Phase 1-5: D2D + S2S rows).
        String fatRidesSha  = sha256(fat.ridesCsv);
        String stubRidesSha = sha256(stub.ridesCsv);
        if (!fatRidesSha.equals(stubRidesSha)) {
            throwParityError("exmas_rides.csv", fatRidesSha, stubRidesSha,
                    fat.ridesCsv, stub.ridesCsv);
        }
        assertEquals(fatRidesSha, stubRidesSha,
                "exmas_rides.csv must be byte-identical between fat and stub paths");

        // SHA-256 gate on hyperpool_rides.csv (Phase 6: HyperPooled bundles).
        String fatHpSha  = sha256(fat.hyperPoolRidesCsv);
        String stubHpSha = sha256(stub.hyperPoolRidesCsv);
        if (!fatHpSha.equals(stubHpSha)) {
            throwParityError("hyperpool_rides.csv", fatHpSha, stubHpSha,
                    fat.hyperPoolRidesCsv, stub.hyperPoolRidesCsv);
        }
        assertEquals(fatHpSha, stubHpSha,
                "hyperpool_rides.csv must be byte-identical between fat and stub paths");
    }

    // -----------------------------------------------------------------------
    // Run helpers
    // -----------------------------------------------------------------------

    private record RunOutput(Path ridesCsv, Path hyperPoolRidesCsv) {}

    /**
     * Run the Kelheim HyperPool scenario and return paths to the generated output files.
     *
     * @param outputDir       output directory for this run
     * @param stubModeEnabled true = stub path (Plan A2); false = fat/master path
     */
    private static RunOutput runKelheimHyperPool(Path outputDir, boolean stubModeEnabled)
            throws IOException {
        Files.createDirectories(outputDir);

        URL scenarioUrl = ExamplesUtils.getTestScenarioURL("kelheim");
        Config config = ConfigUtils.loadConfig(
                new URL(scenarioUrl, "config.xml").toString(),
                new ExMasConfigGroup());

        config.controller().setOutputDirectory(outputDir.toString());
        config.controller().setOverwriteFileSetting(
                OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
        config.controller().setLastIteration(0);

        configureScoring(config);
        configureExMasWithHyperPool(config, stubModeEnabled);

        DemandExtractionConfigValidator.prepareConfigForDemandExtraction(config);

        Scenario scenario = DrtControlerCreator.createScenarioWithDrtRouteFactory(config);
        ScenarioUtils.loadScenario(scenario);

        // Filter out freight agents (they cause routing failures).
        scenario.getPopulation().getPersons().values()
                .removeIf(person -> person.getSelectedPlan().getPlanElements().stream()
                        .filter(org.matsim.api.core.v01.population.Activity.class::isInstance)
                        .map(org.matsim.api.core.v01.population.Activity.class::cast)
                        .anyMatch(act -> act.getType().startsWith("freight")));

        // Duplicate population 2x to create spatial overlap for hyper-pooling
        // (same setup as ExMasKelheimHyperPoolE2ETest).
        duplicatePopulation(scenario.getPopulation(), 2);

        Controler controler = DrtControlerCreator.createControler(config, scenario, false);
        controler.addOverridingModule(new DemandExtractionModule());
        controler.run();

        Path drtDemandDir = outputDir.resolve("drt_demand");
        return new RunOutput(
                drtDemandDir.resolve(RUN_ID + ".exmas_rides.csv"),
                drtDemandDir.resolve(RUN_ID + ".hyperpool_rides.csv"));
    }

    // -----------------------------------------------------------------------
    // Configuration (mirrors ExMasKelheimHyperPoolE2ETest)
    // -----------------------------------------------------------------------

    private static void configureScoring(Config config) {
        ScoringConfigGroup scoring = config.scoring();
        ExMasConfigGroup exMasConfigPreview =
                (ExMasConfigGroup) config.getModules().get(ExMasConfigGroup.GROUP_NAME);
        String drtMode = exMasConfigPreview != null ? exMasConfigPreview.getDrtMode() : "drt";

        if (!scoring.getModes().containsKey(drtMode)) {
            ScoringConfigGroup.ModeParams drtParams = new ScoringConfigGroup.ModeParams(drtMode);
            drtParams.setMarginalUtilityOfTraveling(-0.5);
            drtParams.setConstant(0.0);
            drtParams.setMonetaryDistanceRate(0.0);
            scoring.addModeParams(drtParams);
        } else {
            scoring.getModes().get(drtMode).setMarginalUtilityOfTraveling(-0.5);
        }

        if (scoring.getModes().containsKey(TransportMode.car)) {
            ScoringConfigGroup.ModeParams carParams = scoring.getModes().get(TransportMode.car);
            carParams.setMarginalUtilityOfTraveling(-6.0);
            carParams.setMonetaryDistanceRate(-0.002);
        }

        if (!scoring.getModes().containsKey(TransportMode.walk)) {
            ScoringConfigGroup.ModeParams walkParams =
                    new ScoringConfigGroup.ModeParams(TransportMode.walk);
            walkParams.setMarginalUtilityOfTraveling(-0.01);
            walkParams.setConstant(0.0);
            walkParams.setMonetaryDistanceRate(0.0);
            scoring.addModeParams(walkParams);
        } else {
            scoring.getModes().get(TransportMode.walk).setMarginalUtilityOfTraveling(-0.01);
        }

        org.matsim.dsim.Activities.addScoringParams(config);
    }

    private static void configureExMasWithHyperPool(Config config, boolean stubModeEnabled) {
        ExMasConfigGroup exMasConfig = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);

        exMasConfig.setDrtMode("drt");

        Set<String> baseModes = new HashSet<>();
        baseModes.add(TransportMode.car);
        exMasConfig.setBaseModes(baseModes);
        exMasConfig.setDrtRoutingMode(TransportMode.car);
        exMasConfig.setCommuteFilter(CommuteFilter.COMMUTES_ONLY);

        Set<String> privateVehicles = new HashSet<>();
        privateVehicles.add(TransportMode.car);
        privateVehicles.add(TransportMode.bike);
        exMasConfig.setPrivateVehicleModes(privateVehicles);

        exMasConfig.setMinDrtCostPerKm(0.0);
        exMasConfig.setMinMaxDetourFactor(1.0);
        exMasConfig.setMinMaxWaitingTime(0.0);
        exMasConfig.setMinDrtAccessEgressDistance(0.0);
        exMasConfig.setSearchHorizon(600.0);
        exMasConfig.setMaxDetourFactor(1.5);
        exMasConfig.setMaxPoolingDegree(5);

        exMasConfig.setPruningDistanceSavingsLogScale(0.15);
        exMasConfig.setPruningDistanceSavingsMinDegree(3);
        exMasConfig.setPtOptimizeDepartureTime(true);

        // Single-threaded for a stable byte-identity reference. Deterministic network
        // routing is now unconditional (the routing-determinism plan deleted the
        // useDeterministicNetworkRouting toggle — DeterministicTravelDisutility always
        // wraps the mode disutility), so no explicit enable is needed.
        exMasConfig.setAlgorithmProcessCount(1);
        exMasConfig.setHeuristicsProcessCount(1);

        // Stage 1: stop-based pooling.
        exMasConfig.setEnableStopBased(true);
        exMasConfig.setMaxWalkDistanceMeters(500.0);
        exMasConfig.setStopSearchRadiusMeters(300.0);

        // Stage 2: hyper-pooling.
        exMasConfig.setEnableHyperPooling(true);
        exMasConfig.setHyperPoolMaxStopRelocationMeters(200.0);
        exMasConfig.setHyperPoolMinOccupancy(2);
        exMasConfig.setHyperPoolTimeWindowSeconds(900.0);
        exMasConfig.setHyperPoolStopProximityMeters(100.0);
        exMasConfig.setHyperPoolEnableStopRelocation(false);
        exMasConfig.setHyperPoolMaxStops(-1);
        exMasConfig.setHyperPoolEnableDirectionalFilter(false);
        exMasConfig.setHyperPoolEnableSpatialFilter(false);

        // The parity knob: fat vs stub path.
        exMasConfig.setStubModeEnabled(stubModeEnabled);
    }

    // -----------------------------------------------------------------------
    // Population duplication (same as ExMasKelheimHyperPoolE2ETest)
    // -----------------------------------------------------------------------

    private static void duplicatePopulation(Population population, int duplicates) {
        List<Person> originalPersons = new ArrayList<>(population.getPersons().values());
        PopulationFactory factory = population.getFactory();

        for (int d = 1; d <= duplicates; d++) {
            for (Person original : originalPersons) {
                String newId = original.getId().toString() + "_dup" + d;
                Person duplicate = factory.createPerson(Id.createPersonId(newId));

                original.getAttributes().getAsMap().forEach((key, value) ->
                        duplicate.getAttributes().putAttribute(key, value));

                Plan originalPlan = original.getSelectedPlan();
                Plan newPlan = factory.createPlan();

                for (org.matsim.api.core.v01.population.PlanElement pe :
                        originalPlan.getPlanElements()) {
                    if (pe instanceof org.matsim.api.core.v01.population.Activity act) {
                        org.matsim.api.core.v01.population.Activity newAct =
                                factory.createActivityFromCoord(act.getType(), act.getCoord());
                        newAct.setLinkId(act.getLinkId());
                        if (act.getMaximumDuration().isDefined())
                            newAct.setMaximumDuration(act.getMaximumDuration().seconds());
                        if (act.getEndTime().isDefined())
                            newAct.setEndTime(act.getEndTime().seconds());
                        if (act.getStartTime().isDefined())
                            newAct.setStartTime(act.getStartTime().seconds());
                        newPlan.addActivity(newAct);
                    } else if (pe instanceof org.matsim.api.core.v01.population.Leg leg) {
                        newPlan.addLeg(factory.createLeg(leg.getMode()));
                    }
                }

                duplicate.addPlan(newPlan);
                duplicate.setSelectedPlan(newPlan);
                population.addPerson(duplicate);
            }
        }
    }

    // -----------------------------------------------------------------------
    // SHA-256 + error formatting
    // -----------------------------------------------------------------------

    private static String sha256(Path path) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(Files.readAllBytes(path));
        StringBuilder sb = new StringBuilder(64);
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static void throwParityError(
            String fileName,
            String fatSha,
            String stubSha,
            Path fatPath,
            Path stubPath) throws IOException {
        List<String> fatLines  = Files.readAllLines(fatPath);
        List<String> stubLines = Files.readAllLines(stubPath);
        int diffLines = 0;
        int minLen = Math.min(fatLines.size(), stubLines.size());
        for (int i = 0; i < minLen; i++) {
            if (!fatLines.get(i).equals(stubLines.get(i))) diffLines++;
        }
        int extraFat  = fatLines.size()  - minLen;
        int extraStub = stubLines.size() - minLen;
        throw new AssertionError(String.format(
                "Plan A2 Task 7 parity FAILED: %s SHA-256 mismatch.%n"
                + "  fat  SHA: %s (%d lines)%n"
                + "  stub SHA: %s (%d lines)%n"
                + "  Differing lines: %d common, +%d fat-only, +%d stub-only%n"
                + "  fat  CSV: %s%n"
                + "  stub CSV: %s",
                fileName,
                fatSha,  fatLines.size(),
                stubSha, stubLines.size(),
                diffLines, extraFat, extraStub,
                fatPath.toAbsolutePath(),
                stubPath.toAbsolutePath()));
    }
}
