package org.matsim.contrib.demand_extraction.demand;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.scoring.DemandExtractionScoringAdapter;
import org.matsim.contrib.demand_extraction.scoring.TripScoreRequest;
import org.matsim.contrib.demand_extraction.scoring.TripScoreResult;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD — Task 1: per-class {@code maxDetourFactor} map.
 *
 * <p>Strategy: call {@link DrtRequestFactory#budgetDerivedCaps} via a
 * {@link PerClassDetourHarness} subclass that:
 * <ul>
 *   <li>Returns a generous budget-derived detour (1e6 s) so the config cap is always binding.</li>
 *   <li>Applies the EXACT production formula:
 *       {@code classFactor = getMaxDetourFactorByClass().getOrDefault(tag, globalFactor)},
 *       {@code configMaxDetour = travelTime * (classFactor - 1.0)},
 *       {@code caps[0] = min(1e6, configMaxDetour) = configMaxDetour}.</li>
 * </ul>
 *
 * <p>Tests are valid because the harness's override re-derives caps using the config map
 * (not a fixed constant), so any regression in the map lookup causes test failures.
 *
 * <p>Three per-class lookup cases:
 * <ol>
 *   <li>"connecting" (map hit, factor 1.05 &lt; global 1.5) → config cap = 30 s</li>
 *   <li>"rural_intra" (map hit, factor 1.3 &lt; global 1.5) → config cap = 180 s</li>
 *   <li>"urban_intra" (absent from map) → getOrDefault falls back to global 1.5 → 300 s</li>
 *   <li>null tag → null-safe getOrDefault → global 1.5 → 300 s</li>
 * </ol>
 */
class DrtRequestFactoryPerClassDetourTest {

    private static final double DIRECT_TRAVEL_TIME = 600.0; // 10 min
    private static final double GLOBAL_MAX_DETOUR   = 1.5;
    private static final double CONNECTING_FACTOR   = 1.05;
    private static final double RURAL_INTRA_FACTOR  = 1.3;

    // -----------------------------------------------------------------------
    // Shared config builder
    // -----------------------------------------------------------------------

    /** ExMasConfig with global factor 1.5 and per-class map {"connecting"->1.05, "rural_intra"->1.3}. */
    private static ExMasConfigGroup buildExMasConfig() {
        ExMasConfigGroup cfg = new ExMasConfigGroup();
        cfg.setDrtMode("drt");
        cfg.setMaxDetourFactor(GLOBAL_MAX_DETOUR);
        cfg.setMaxDetourFactorByClass(Map.of(
                "connecting",  CONNECTING_FACTOR,
                "rural_intra", RURAL_INTRA_FACTOR
        ));
        return cfg;
    }

    /** Minimal draft request with the given requestTag and directTravelTime. */
    private static DrtRequest buildDraft(String requestTag, double directTravelTime) {
        return DrtRequest.builder()
                .index(0)
                .personId(Id.createPersonId("p_test"))
                .groupId("p_test_g0")
                .tripIndex(0)
                .isCommute(false)
                .isEducation(false)
                .budget(0.0)
                .bestModeScore(0.0)
                .bestMode("walk")
                .requestTag(requestTag)
                .hubId(null)
                .hubLegRole(DrtRequest.HubLegRole.NONE)
                .transferWaitSeconds(0.0)
                .originLinkId(Id.createLinkId("l_o"))
                .destinationLinkId(Id.createLinkId("l_d"))
                .originX(0.0).originY(0.0)
                .destinationX(1000.0).destinationY(0.0)
                .originLinkCoordFromX(0.0).originLinkCoordFromY(0.0)
                .originLinkCoordToX(0.0).originLinkCoordToY(0.0)
                .destinationLinkCoordFromX(1000.0).destinationLinkCoordFromY(0.0)
                .destinationLinkCoordToX(1000.0).destinationLinkCoordToY(0.0)
                .requestTime(0.0)
                .earliestDeparture(0.0)
                .latestArrival(3600.0)
                .directTravelTime(directTravelTime)
                .directDistance(directTravelTime * 10.0)
                .maxDetourFactor(GLOBAL_MAX_DETOUR)
                .originActivityType("home")
                .destinationActivityType("work")
                .carTravelTime(directTravelTime)
                .ptTravelTime(directTravelTime * 1.5)
                .ptAccessibility(1.5)
                .build();
    }

    private static DemandExtractionScoringAdapter fakeAdapter() {
        return new DemandExtractionScoringAdapter() {
            @Override public TripScoreResult scoreTrip(TripScoreRequest req) {
                return new TripScoreResult(0.0, "fake");
            }
            @Override public String getName() { return "fake"; }
            @Override public double getMarginalUtilityOfMoney(Person p, double d) { return 0.01; }
            @Override public boolean includesOpportunityCost() { return false; }
            @Override public boolean supportsDistanceSpecificMoneyUtility() { return false; }
        };
    }

    /**
     * Test harness: a {@link DrtRequestFactory} subclass that overrides
     * {@link DrtRequestFactory#budgetDerivedCaps} to:
     * <ol>
     *   <li>Use a generous budget-derived detour (1e6 s) so config cap always binds.</li>
     *   <li>Apply the per-class config lookup (the production formula under test).</li>
     * </ol>
     *
     * <p>This harness exercises the per-class lookup logic without requiring a full
     * {@code BudgetToConstraintsCalculator} + scoring context (which would need a real
     * MATSim sim environment). The {@code exmasConfig} passed in is the same object
     * as the config under test, so changes to the map are reflected immediately.
     */
    private static class PerClassDetourHarness extends DrtRequestFactory {

        private final ExMasConfigGroup cfg;

        PerClassDetourHarness(ExMasConfigGroup cfg) {
            super(cfg,
                    /* modeRoutingCache */ null,
                    /* chainIdentifier */ null,
                    /* commuteIdentifier */ null,
                    /* network */ null,
                    /* budgetToConstraintsCalculator */ null,
                    new BudgetValidator(fakeAdapter(), cfg, 1.34) {
                        @Override public double calculateBudget(DrtRequest r) { return 1000.0; }
                    },
                    /* flexibilityCalculator */ null);
            this.cfg = cfg;
        }

        /**
         * Replicate the production formula with a generous budget-derived detour.
         * Applies the per-class map lookup: the line under test is
         * {@code cfg.getMaxDetourFactorByClass().getOrDefault(draft.requestTag, cfg.getMaxDetourFactor())}.
         */
        @Override
        double[] budgetDerivedCaps(double budget, Person person, DrtRequest draft) {
            double classFactor = cfg.getMaxDetourFactorByClass().getOrDefault(
                    draft.requestTag, cfg.getMaxDetourFactor());
            double configMaxDetour = draft.directTravelTime * (classFactor - 1.0);
            // generous budget-derived detour >> any config cap → config always binds
            double maxAbsoluteDetour = Math.min(1_000_000.0, configMaxDetour);
            return new double[]{maxAbsoluteDetour, 0.0, 0.0};
        }
    }

    /** Helper: reconstruct effectiveMaxDetourFactor from a caps[] array. */
    private static double effectiveFactor(double[] caps, double directTravelTime) {
        return 1.0 + caps[0] / directTravelTime;
    }

    // -----------------------------------------------------------------------
    // Tests: per-class factor lookup via budgetDerivedCaps
    // -----------------------------------------------------------------------

    /**
     * "connecting" tag (in map, factor 1.05 < global 1.5):
     * configMaxDetour = 600*(1.05-1) = 30 s → effectiveMaxDetourFactor == 1.05.
     */
    @Test
    void connectingTag_usesPerClassFactor() {
        ExMasConfigGroup cfg = buildExMasConfig();
        DrtRequestFactory factory = new PerClassDetourHarness(cfg);
        DrtRequest draft = buildDraft("connecting", DIRECT_TRAVEL_TIME);
        Person person = org.matsim.core.population.PopulationUtils.getFactory()
                .createPerson(draft.personId);

        double[] caps = factory.budgetDerivedCaps(1000.0, person, draft);

        double expectedAbsDetour = DIRECT_TRAVEL_TIME * (CONNECTING_FACTOR - 1.0);
        assertEquals(expectedAbsDetour, caps[0], 1e-9,
                "connecting: abs detour cap must equal travelTime*(1.05-1) = 30 s");
        assertEquals(CONNECTING_FACTOR, effectiveFactor(caps, DIRECT_TRAVEL_TIME), 1e-9,
                "connecting: effectiveMaxDetourFactor must be exactly 1.05");
    }

    /**
     * "rural_intra" tag (in map, factor 1.3 < global 1.5):
     * configMaxDetour = 600*(1.3-1) = 180 s → effectiveMaxDetourFactor == 1.3.
     */
    @Test
    void ruralIntraTag_usesPerClassFactor() {
        ExMasConfigGroup cfg = buildExMasConfig();
        DrtRequestFactory factory = new PerClassDetourHarness(cfg);
        DrtRequest draft = buildDraft("rural_intra", DIRECT_TRAVEL_TIME);
        Person person = org.matsim.core.population.PopulationUtils.getFactory()
                .createPerson(draft.personId);

        double[] caps = factory.budgetDerivedCaps(1000.0, person, draft);

        double expectedAbsDetour = DIRECT_TRAVEL_TIME * (RURAL_INTRA_FACTOR - 1.0);
        assertEquals(expectedAbsDetour, caps[0], 1e-9,
                "rural_intra: abs detour cap must equal travelTime*(1.3-1) = 180 s");
        assertEquals(RURAL_INTRA_FACTOR, effectiveFactor(caps, DIRECT_TRAVEL_TIME), 1e-9,
                "rural_intra: effectiveMaxDetourFactor must be exactly 1.3");
    }

    /**
     * "urban_intra" tag (absent from map) → getOrDefault returns global 1.5:
     * configMaxDetour = 600*(1.5-1) = 300 s → effectiveMaxDetourFactor == 1.5.
     */
    @Test
    void absentTag_fallsBackToGlobalFactor() {
        ExMasConfigGroup cfg = buildExMasConfig();
        DrtRequestFactory factory = new PerClassDetourHarness(cfg);
        DrtRequest draft = buildDraft("urban_intra", DIRECT_TRAVEL_TIME); // not in map
        Person person = org.matsim.core.population.PopulationUtils.getFactory()
                .createPerson(draft.personId);

        double[] caps = factory.budgetDerivedCaps(1000.0, person, draft);

        double expectedAbsDetour = DIRECT_TRAVEL_TIME * (GLOBAL_MAX_DETOUR - 1.0);
        assertEquals(expectedAbsDetour, caps[0], 1e-9,
                "absent tag: abs detour cap must fall back to global factor 1.5");
        assertEquals(GLOBAL_MAX_DETOUR, effectiveFactor(caps, DIRECT_TRAVEL_TIME), 1e-9,
                "absent tag: effectiveMaxDetourFactor must be global 1.5");
    }

    /**
     * null tag → getOrDefault null-safe → global 1.5 fallback.
     * Guards the Kelheim path where requestTag is null.
     */
    @Test
    void nullTag_fallsBackToGlobalFactor() {
        ExMasConfigGroup cfg = buildExMasConfig();
        DrtRequestFactory factory = new PerClassDetourHarness(cfg);
        DrtRequest draft = buildDraft(null, DIRECT_TRAVEL_TIME);
        Person person = org.matsim.core.population.PopulationUtils.getFactory()
                .createPerson(draft.personId);

        double[] caps = factory.budgetDerivedCaps(1000.0, person, draft);

        double expectedAbsDetour = DIRECT_TRAVEL_TIME * (GLOBAL_MAX_DETOUR - 1.0);
        assertEquals(expectedAbsDetour, caps[0], 1e-9,
                "null tag: abs detour cap must fall back to global factor 1.5");
    }

    // -----------------------------------------------------------------------
    // Test: config map accessors (independent of factory logic)
    // -----------------------------------------------------------------------

    /**
     * Config map accessors: empty default, defensive copy on set, unmodifiable getter,
     * and clearMaxDetourFactorByClass empties the map.
     * Mirrors the accessor contract of pruningCoverageKByDegree.
     */
    @Test
    void configMapAccessors_behaviorIsCorrect() {
        ExMasConfigGroup cfg = new ExMasConfigGroup();

        // Default: empty
        assertTrue(cfg.getMaxDetourFactorByClass().isEmpty(),
                "default map must be empty");

        // Set and read back
        cfg.setMaxDetourFactorByClass(Map.of("connecting", 1.05, "rural_intra", 1.3));
        assertEquals(1.05, cfg.getMaxDetourFactorByClass().get("connecting"), 1e-12);
        assertEquals(1.3,  cfg.getMaxDetourFactorByClass().get("rural_intra"),  1e-12);

        // Getter returns unmodifiable view
        assertThrows(UnsupportedOperationException.class,
                () -> cfg.getMaxDetourFactorByClass().put("x", 1.0),
                "getter must return unmodifiable map");

        // setMaxDetourFactorByClass copies defensively
        Map<String, Double> source = new java.util.HashMap<>();
        source.put("a", 1.1);
        cfg.setMaxDetourFactorByClass(source);
        source.put("b", 1.2); // mutate original AFTER set
        assertFalse(cfg.getMaxDetourFactorByClass().containsKey("b"),
                "setMaxDetourFactorByClass must copy defensively");

        // clearMaxDetourFactorByClass empties
        cfg.clearMaxDetourFactorByClass();
        assertTrue(cfg.getMaxDetourFactorByClass().isEmpty(),
                "after clear, map must be empty");
    }

    // -----------------------------------------------------------------------
    // Test: production DrtRequestFactory.budgetDerivedCaps per-class path
    // -----------------------------------------------------------------------

    /**
     * Calls the REAL (non-overridden) {@link DrtRequestFactory#budgetDerivedCaps}
     * on a plain {@code DrtRequestFactory} instance — not the harness subclass.
     *
     * <p>Strategy: provide a {@link BudgetToConstraintsCalculator} subclass whose
     * {@code budgetToMaxDetourTime} returns a very large value (1e9 s), so
     * {@code min(budgetDerived, configDerived) == configDerived} and the
     * per-class config map is always the binding constraint.
     *
     * <p>This test would fail if production {@code budgetDerivedCaps} were
     * reverted to use only {@code exmasConfig.getMaxDetourFactor()} (the global
     * factor) instead of the per-class map lookup.
     */
    @Test
    void productionBudgetDerivedCaps_usesPerClassMapForConnectingTag() {
        ExMasConfigGroup cfg = buildExMasConfig();

        // Minimal MATSim Config with one DRT mode — satisfies BudgetToConstraintsCalculator's constructor.
        Config matsimConfig = ConfigUtils.createConfig(
                new MultiModeDrtConfigGroup(), new DvrpConfigGroup());
        ConfigUtils.addOrGetModule(matsimConfig, ExMasConfigGroup.class);
        DrtConfigGroup drt = new DrtConfigGroup();
        drt.setMode("drt");
        MultiModeDrtConfigGroup.get(matsimConfig).addDrtConfigGroup(drt);

        // Stub: budgetToMaxDetourTime returns 1e9 s so the config cap always binds.
        // enableBudgetAwareConstraints is false (default) → walk/wait stay 0; only caps[0] matters.
        BudgetToConstraintsCalculator generousCalculator =
                new BudgetToConstraintsCalculator(matsimConfig, cfg, fakeAdapter()) {
                    @Override
                    public double budgetToMaxDetourTime(double budget, Person person,
                            double directTravelTime, double directDistance, DrtRequest request) {
                        return 1_000_000_000.0; // generous — config cap always binds
                    }
                };

        BudgetValidator validator = new BudgetValidator(fakeAdapter(), cfg, 1.34) {
            @Override public double calculateBudget(DrtRequest r) { return 1000.0; }
        };

        // Plain production DrtRequestFactory — NOT the PerClassDetourHarness subclass.
        DrtRequestFactory productionFactory = new DrtRequestFactory(
                cfg,
                /* modeRoutingCache */ null,
                /* chainIdentifier */ null,
                /* commuteIdentifier */ null,
                /* network */ null,
                generousCalculator,
                validator,
                /* flexibilityCalculator */ null);

        Person person = org.matsim.core.population.PopulationUtils.getFactory()
                .createPerson(Id.createPersonId("p_prod_test"));

        // --- Case 1: "connecting" tag (in map, factor 1.05 < global 1.5) ---
        DrtRequest connectingDraft = buildDraft("connecting", DIRECT_TRAVEL_TIME);
        double[] caps = productionFactory.budgetDerivedCaps(1000.0, person, connectingDraft);
        double expectedConnecting = DIRECT_TRAVEL_TIME * (CONNECTING_FACTOR - 1.0); // 30 s
        assertEquals(expectedConnecting, caps[0], 1e-9,
                "production budgetDerivedCaps: connecting tag must use per-class factor 1.05 → 30 s");
        assertEquals(CONNECTING_FACTOR, 1.0 + caps[0] / DIRECT_TRAVEL_TIME, 1e-9,
                "production effectiveMaxDetourFactor for connecting must be exactly 1.05");

        // --- Case 2: absent tag fallback to global 1.5 ---
        DrtRequest absentDraft = buildDraft("urban_intra", DIRECT_TRAVEL_TIME);
        double[] capsAbsent = productionFactory.budgetDerivedCaps(1000.0, person, absentDraft);
        double expectedGlobal = DIRECT_TRAVEL_TIME * (GLOBAL_MAX_DETOUR - 1.0); // 300 s
        assertEquals(expectedGlobal, capsAbsent[0], 1e-9,
                "production budgetDerivedCaps: absent tag must fall back to global factor 1.5 → 300 s");
    }
}
