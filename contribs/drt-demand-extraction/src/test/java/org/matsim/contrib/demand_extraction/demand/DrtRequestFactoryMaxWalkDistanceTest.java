package org.matsim.contrib.demand_extraction.demand;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that {@link DrtRequestFactory} populates {@code DrtRequest.maxWalkDistance}
 * when {@code enableBudgetAwareConstraints=true}, and leaves it at 0.0 otherwise.
 *
 * <p>Strategy: run the full E2E pipeline on the dvrp-grid scenario (fast, ~30 s),
 * then call {@code factory.buildRequests(population)} a second time after the
 * Controler run (caches are already warm). Inspect the in-memory request list
 * directly rather than reading CSV output (maxWalkDistance is not yet in the CSV).
 */
class DrtRequestFactoryMaxWalkDistanceTest {

    // -----------------------------------------------------------------------
    // Flag-ON test: maxWalkDistance must be positive and consistent with the
    // calculator's 3-arg ideal-DRT overload.
    // -----------------------------------------------------------------------
    @Test
    void flagOn_populatesMaxWalkDistance() throws Exception {
        Path outDir = Path.of("test/output/drt-request-factory-maxwalk-flag-on");
        Files.createDirectories(outDir);

        Config config = buildConfig(outDir);
        ExMasConfigGroup exMas = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
        exMas.setEnableBudgetAwareConstraints(true);

        Controler controler = buildControler(config);
        controler.run();

        // Re-run factory with warm caches to get in-memory DrtRequest list
        DrtRequestFactory factory = controler.getInjector().getInstance(DrtRequestFactory.class);
        Population population = controler.getInjector().getInstance(Population.class);
        BudgetToConstraintsCalculator calculator =
                controler.getInjector().getInstance(BudgetToConstraintsCalculator.class);

        List<DrtRequest> requests = factory.buildRequests(population);

        // Need at least one positive-budget request to make the assertion meaningful
        List<DrtRequest> positive = requests.stream()
                .filter(r -> r.budget > 0)
                .toList();
        assertFalse(positive.isEmpty(),
                "Expected at least one positive-budget request in the dvrp-grid scenario");

        for (DrtRequest r : positive) {
            assertNotNull(r.getScoringContext(),
                    "ScoringContext must be set before asserting walk distance (request " + r.index + ")");
            assertTrue(r.maxWalkDistance > 0,
                    "maxWalkDistance should be positive when flag is on (request " + r.index
                            + ", budget=" + r.budget + ")");

            Person person = population.getPersons().get(r.personId);
            double expectedWalk = calculator.budgetToMaxWalkDistance(r.budget, person, r);
            assertEquals(expectedWalk, r.maxWalkDistance, 1e-6,
                    "maxWalkDistance must equal budgetToMaxWalkDistance(budget, person, request) "
                            + "(request " + r.index + ")");

            assertTrue(r.maxWaitTime > 0,
                    "maxWaitTime should be positive when flag is on (request " + r.index
                            + ", budget=" + r.budget + ")");

            double expectedWait = calculator.budgetToMaxWaitingTime(r.budget, person, r);
            assertEquals(expectedWait, r.maxWaitTime, 1e-6,
                    "maxWaitTime must equal budgetToMaxWaitingTime(budget, person, request) "
                            + "(request " + r.index + ")");
        }
    }

    // -----------------------------------------------------------------------
    // Flag-OFF test: maxWalkDistance must be 0.0 (default / unchanged behaviour).
    // -----------------------------------------------------------------------
    @Test
    void flagOff_maxWalkDistanceIsZero() throws Exception {
        Path outDir = Path.of("test/output/drt-request-factory-maxwalk-flag-off");
        Files.createDirectories(outDir);

        Config config = buildConfig(outDir);
        // Flag off is the default; set it explicitly for documentation
        ExMasConfigGroup exMas = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
        exMas.setEnableBudgetAwareConstraints(false);

        Controler controler = buildControler(config);
        controler.run();

        DrtRequestFactory factory = controler.getInjector().getInstance(DrtRequestFactory.class);
        Population population = controler.getInjector().getInstance(Population.class);

        List<DrtRequest> requests = factory.buildRequests(population);

        assertFalse(requests.isEmpty(),
                "Expected at least one DRT request in the dvrp-grid scenario (flag-off)");

        for (DrtRequest r : requests) {
            assertEquals(0.0, r.maxWalkDistance, 1e-12,
                    "maxWalkDistance must be 0.0 when flag is off (request " + r.index + ")");
            assertEquals(0.0, r.maxWaitTime, 1e-12,
                    "maxWaitTime must be 0.0 when flag is off (request " + r.index + ")");
        }
    }

    // -----------------------------------------------------------------------
    // Shared helpers (identical setup, flag differs per test)
    // -----------------------------------------------------------------------

    private static Config buildConfig(Path outputDir) throws Exception {
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

        // Scoring setup (mirrors ExMasDemandExtractionE2ETest)
        ScoringConfigGroup scoring = config.scoring();
        scoring.setMarginalUtilityOfMoney(1.0);
        scoring.setMarginalUtlOfWaitingPt_utils_hr(0.0);
        scoring.getOrCreateModeParams(TransportMode.car).setMonetaryDistanceRate(-0.0002);
        scoring.getOrCreateModeParams(TransportMode.pt).setDailyMonetaryConstant(-2.0);
        scoring.getOrCreateModeParams(TransportMode.pt).setMonetaryDistanceRate(-0.0001);

        // ExMAS config (minimal viable setup)
        ExMasConfigGroup exMas = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
        exMas.setDrtMode("drt");
        exMas.setBaseModes(Set.of(TransportMode.car));
        exMas.setDrtRoutingMode(TransportMode.car);
        Set<String> privateVehicles = new HashSet<>();
        privateVehicles.add(TransportMode.car);
        privateVehicles.add(TransportMode.bike);
        exMas.setPrivateVehicleModes(privateVehicles);
        exMas.setMinDrtCostPerKm(0.0);
        exMas.setMinDrtAccessEgressDistance(0.0);
        exMas.setMaxDetourFactor(2.0);
        exMas.setSearchHorizon(0.0);
        exMas.setNegativeFlexibilityAbsoluteMap("default:9000.0");
        exMas.setPositiveFlexibilityAbsoluteMap("default:9000.0");
        exMas.setIncludeOpportunityCost(false);

        return config;
    }

    private static Controler buildControler(Config config) {
        Scenario scenario = DrtControlerCreator.createScenarioWithDrtRouteFactory(config);
        ScenarioUtils.loadScenario(scenario);

        // Add person attributes (mirrors ExMasDemandExtractionE2ETest)
        int personCount = 0;
        for (Person person : scenario.getPopulation().getPersons().values()) {
            int personType = personCount % 3;
            if (personType == 0) {
                PersonUtils.setLicence(person, "yes");
                PersonUtils.setCarAvail(person, "always");
            } else if (personType == 1) {
                PersonUtils.setLicence(person, "no");
                PersonUtils.setCarAvail(person, "never");
            } else {
                PersonUtils.setLicence(person, "yes");
                PersonUtils.setCarAvail(person, "sometimes");
            }
            personCount++;
        }

        Controler controler = DrtControlerCreator.createControler(config, scenario, false);
        controler.addOverridingModule(new DemandExtractionModule());
        return controler;
    }
}
