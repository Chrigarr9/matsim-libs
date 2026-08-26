package org.matsim.contrib.demand_extraction.algorithm.bamas.ride;

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
 * Kelheim stop-based + HyperPool regression gate — the non-vacuous S2S/HyperPool golden.
 *
 * <p>Verifies that the (now single-path) BAMAS engine produces byte-identical
 * {@code exmas_rides.csv} AND {@code hyperpool_rides.csv} to frozen goldens on the Kelheim
 * scenario, which actually generates STOP_TO_STOP and HyperPooled rides — unlike the
 * dvrp-grid scenario in {@link HyperPoolStubParityTest}, whose golden contains only
 * DOOR_TO_DOOR rows. This is therefore the only gate that pins the S2S + HyperPool byte
 * output of the engine.
 *
 * <h3>History</h3>
 * Originally a fat-vs-stub parity gate (Plan A2 Task 7): it ran the engine twice
 * ({@code stubModeEnabled} false then true) and asserted the two output sets were SHA-256
 * equal. The BAMAS cleanup deleted the fat path, so the fat comparand no longer exists.
 * Before deleting it, the fat output (== the then-passing stub output) was frozen as the
 * committed goldens below, so the original fat-vs-stub guarantee survives the deletion: a
 * regression that changed the stop-based/hyperpool output would change a SHA here.
 *
 * <p>Single-threaded and deterministic network routing for byte reproducibility (documented
 * in plan A2). The population is duplicated 2x to create spatial overlap for hyper-pooling.
 *
 * <p><b>Re-baselined 2026-06-16</b> after {@code 8348b736} (PairGenerator: per-request
 * flexibility window replacing the flat search horizon). That change is a provable lossless
 * superset of the old flat-600s candidate set with the exact temporal-overlap check unchanged,
 * so it only adds genuinely-feasible pairs the flat horizon was wrongly pre-dropping; Kelheim's
 * flexibility windows exceed 600 s, so the ride count grew 13,223 → 66,521. Attribution was
 * confirmed by toggling only that one PairGenerator line back to the flat horizon on the
 * current tree, which reproduced the previous goldens exactly — so the window is the sole
 * delta (defer-validation removal, the ride/layer rename, tier2 and fork are all inert here).
 *
 * <h3>Golden strategy</h3>
 * The {@code exmas_rides.csv} is ~58 MB (66,521 rows incl. S2S), so rather than commit the
 * blob we pin its SHA-256 — and the 103 KB {@code hyperpool_rides.csv}'s — as constants below.
 * Both are byte-deterministic under the single-threaded + deterministic-routing config. If an
 * intentional output change flips a SHA, re-capture it from the {@code actual CSV} path the
 * failure prints (e.g. {@code sha256sum <path>}).
 *
 * <h3>Acceptance criterion</h3>
 * SHA-256({@code exmas_rides.csv}) == {@link #RIDES_GOLDEN_SHA} AND
 * SHA-256({@code hyperpool_rides.csv}) == {@link #HYPERPOOL_GOLDEN_SHA}.
 */
class KelheimHyperPoolStubParityTest {

    private static final String RUN_ID = "kelheim-mini";
    /** SHA-256 of the frozen kelheim-mini exmas_rides.csv (D2D + S2S rows), single-threaded. */
    private static final String RIDES_GOLDEN_SHA =
            "4e64d53c1c043e242dadb3faa13cd8d48c61e74a22f8a3fc739ca5c350eea80d";
    /** SHA-256 of the frozen kelheim-mini hyperpool_rides.csv (Phase-6 bundles), single-threaded. */
    private static final String HYPERPOOL_GOLDEN_SHA =
            "611db1a30ddaea0fc9ffa0de6380a7744871dd81fc163d5ca7f0428e1acc7422";

    @Test
    void kelheimHyperPoolOutputMatchesFrozenGoldens() throws IOException, NoSuchAlgorithmException {
        Path baseDir = Path.of("test/output/kelheim-hyperpool-stub-parity-test");
        Files.createDirectories(baseDir);

        RunOutput out = runKelheimHyperPool(baseDir.resolve("run"));

        assertTrue(Files.exists(out.hyperPoolRidesCsv),
                "Run must produce hyperpool_rides.csv at: " + out.hyperPoolRidesCsv);

        assertShaMatch("exmas_rides.csv", out.ridesCsv, RIDES_GOLDEN_SHA);
        assertShaMatch("hyperpool_rides.csv", out.hyperPoolRidesCsv, HYPERPOOL_GOLDEN_SHA);
    }

    private static void assertShaMatch(String label, Path actualCsv, String goldenSha)
            throws IOException, NoSuchAlgorithmException {
        String actualSha = sha256(Files.readAllBytes(actualCsv));
        assertEquals(goldenSha, actualSha, String.format(
                "Kelheim stop-based/HyperPool %s regressed vs frozen golden SHA.%n"
                + "  expected SHA: %s%n"
                + "  actual SHA:   %s%n"
                + "  actual CSV:   %s%n"
                + "  (single-threaded deterministic output; if this change is intentional, "
                + "re-capture the SHA from the actual CSV.)",
                label, goldenSha, actualSha, actualCsv.toAbsolutePath()));
    }

    // -----------------------------------------------------------------------
    // Run helpers
    // -----------------------------------------------------------------------

    private record RunOutput(Path ridesCsv, Path hyperPoolRidesCsv) {}

    /** Run the Kelheim HyperPool scenario and return paths to the generated output files. */
    private static RunOutput runKelheimHyperPool(Path outputDir) throws IOException {
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
        configureExMasWithHyperPool(config);

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

    private static void configureExMasWithHyperPool(Config config) {
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

        // Pruning: this test previously relied on the ExMasConfigGroup class defaults for
        // heuristicPruningEnabled (true), pruningMode (COVERAGE_TOPK), and pruningCoverageK
        // (20) while only overriding the distance-savings gate below. The class default is
        // now "no pruning" (heuristicPruningEnabled=false), so those must be stated
        // explicitly here to keep this frozen-golden byte-identity test's ride enumeration
        // unchanged.
        exMasConfig.setHeuristicPruningEnabled(true);
        exMasConfig.setPruningMode(ExMasConfigGroup.PruningMode.COVERAGE_TOPK);
        exMasConfig.setPruningCoverageK(20);
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
    // Golden + SHA-256 helpers
    // -----------------------------------------------------------------------

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
