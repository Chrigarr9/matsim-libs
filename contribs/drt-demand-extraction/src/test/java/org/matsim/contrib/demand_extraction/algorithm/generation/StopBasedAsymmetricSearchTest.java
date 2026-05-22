package org.matsim.contrib.demand_extraction.algorithm.generation;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A5+A6: Unit tests for the asymmetric two-phase stop search in
 * {@link StopBasedRideGenerator}.
 *
 * <h2>What A6 changes</h2>
 * {@code convertToStopBased} dispatches on {@code enableBudgetAwareConstraints}:
 * <ul>
 *   <li>flag=on → {@code convertToStopBasedBudgetAware}: Phase A gets cap
 *       {@code accessCaps[i] = min(2·mid, hardCap)}; after measuring the actual
 *       access walk, Phase B gets
 *       {@code egressCaps[i] = max(0, min(2·mid − accessWalk[i], hardCap))}.
 *   <li>flag=off → {@code convertToStopBasedLegacy}: both phases receive the
 *       same pre-computed {@code maxWalkDistances} array.
 * </ul>
 *
 * <h2>Test design</h2>
 * Stub {@link WalkingDistanceCalculator} returns {@value WALK_M} m for every
 * walk. Stub {@link WalkBudgetProvider} returns {@code mid = }{@value MID_M} m.
 * With {@code hardCap = }{@value HARD_CAP_M} m:
 * <pre>
 *   Phase A cap = min(2·200, 600)             = 400 m
 *   Phase B cap = max(0, min(400 − 50, 600)) = 350 m  (because accessWalk=50)
 * </pre>
 * So 400 ≠ 350 → flag=on produces asymmetric caps.
 * Legacy path passes the same array to both phases → caps are equal.
 *
 * <h2>Pre-A6 failure</h2>
 * Before A6, {@code convertToStopBased} routes to the legacy path even when
 * flag=on, so Phase A and Phase B caps are equal. The first test therefore
 * FAILS before A6 and PASSES after.
 */
class StopBasedAsymmetricSearchTest {

    // Constants from the shared test fixture — re-imported here so the
    // formula-based assertions remain self-documenting.
    private static final double MID_M     = StopBasedTestFixtures.MID_M;
    private static final double WALK_M    = StopBasedTestFixtures.WALK_M;
    private static final double HARD_CAP_M = StopBasedTestFixtures.HARD_CAP_M;

    // -------------------------------------------------------------------------
    // Test 1 — must FAIL before A6, PASS after A6
    // -------------------------------------------------------------------------

    /**
     * With {@code enableBudgetAwareConstraints=true} (post-A6 budget-aware path)
     * Phase A and Phase B must receive DIFFERENT cap arrays because Phase B
     * subtracts the measured access walk from the total walk envelope.
     *
     * <p>Expected values (derived from stub constants):
     * <pre>
     *   Phase A cap = min(2·MID_M, HARD_CAP_M)              = min(400, 600) = 400 m
     *   Phase B cap = max(0, min(2·MID_M − WALK_M, HARD_CAP_M)) = max(0, min(350, 600)) = 350 m
     * </pre>
     * So Phase A = [400.0, 400.0], Phase B = [350.0, 350.0].
     */
    @Test
    void budgetAwarePath_phaseACapsAndPhaseBCapsDiffer() {
        var capturingFinder = newCapturingFinder();
        StopBasedRideGenerator gen = buildGenerator(/* enableBudgetAware= */ true, capturingFinder);

        gen.generateStopBasedRides(List.of(buildD2DRide()), /* startIndex= */ 0);

        assertTrue(capturingFinder.calls.size() >= 2,
                "Expected at least 2 findStop calls (Phase A + Phase B), got: "
                        + capturingFinder.calls.size());

        double[] phaseACaps = capturingFinder.calls.get(0).caps;
        double[] phaseBCaps = capturingFinder.calls.get(1).caps;

        System.out.printf(
                "%n[budgetAware] Phase A: %s  Phase B: %s%n",
                Arrays.toString(phaseACaps), Arrays.toString(phaseBCaps));

        // Phase A cap = min(2·MID_M, HARD_CAP_M) = min(400, 600) = 400 m
        double expectedPhaseACap = Math.min(2 * MID_M, HARD_CAP_M);
        // Phase B cap = max(0, min(2·MID_M - WALK_M, HARD_CAP_M)) = max(0, min(350, 600)) = 350 m
        double expectedPhaseBCap = Math.max(0, Math.min(2 * MID_M - WALK_M, HARD_CAP_M));

        assertArrayEquals(
                new double[]{expectedPhaseACap, expectedPhaseACap}, phaseACaps, 1e-9,
                "Budget-aware path: Phase A cap should be min(2·mid, hardCap)="
                        + expectedPhaseACap + " m for each passenger");
        assertArrayEquals(
                new double[]{expectedPhaseBCap, expectedPhaseBCap}, phaseBCaps, 1e-9,
                "Budget-aware path: Phase B cap should be max(0, min(2·mid−accessWalk, hardCap))="
                        + expectedPhaseBCap + " m for each passenger");
    }

    // -------------------------------------------------------------------------
    // Test 2 — legacy path symmetry regression guard
    // -------------------------------------------------------------------------

    /**
     * With {@code enableBudgetAwareConstraints=false} (legacy path) both phases
     * must receive the same cap array (same reference passed to both calls).
     */
    @Test
    void legacyPath_phaseACapsAndPhaseBCapsAreEqual() {
        var capturingFinder = newCapturingFinder();
        StopBasedRideGenerator gen = buildGenerator(/* enableBudgetAware= */ false, capturingFinder);

        gen.generateStopBasedRides(List.of(buildD2DRide()), /* startIndex= */ 0);

        assertTrue(capturingFinder.calls.size() >= 2,
                "Expected at least 2 findStop calls, got: " + capturingFinder.calls.size());

        double[] phaseACaps = capturingFinder.calls.get(0).caps;
        double[] phaseBCaps = capturingFinder.calls.get(1).caps;

        System.out.printf(
                "%n[legacy]      Phase A: %s  Phase B: %s%n",
                Arrays.toString(phaseACaps), Arrays.toString(phaseBCaps));

        assertArrayEquals(phaseACaps, phaseBCaps, 1e-9,
                "Legacy path must pass IDENTICAL caps to both findStop calls. "
                + "Phase A: " + Arrays.toString(phaseACaps)
                + "  Phase B: " + Arrays.toString(phaseBCaps));
    }

    // =========================================================================
    // Infrastructure — delegates to StopBasedTestFixtures
    // =========================================================================

    private StopBasedRideGenerator buildGenerator(boolean enableBudgetAware,
            StopBasedTestFixtures.CapturingStopFinder capturingFinder) {
        ExMasConfigGroup cfg = StopBasedTestFixtures.buildExMasConfig(enableBudgetAware);
        BudgetValidator passingValidator = StopBasedTestFixtures.buildPassingValidator(cfg);
        return StopBasedTestFixtures.buildGenerator(enableBudgetAware, capturingFinder, passingValidator);
    }

    private static Ride buildD2DRide() {
        return StopBasedTestFixtures.buildD2DRide();
    }

    // Alias type for convenience
    private static StopBasedTestFixtures.CapturingStopFinder newCapturingFinder() {
        return new StopBasedTestFixtures.CapturingStopFinder();
    }
}
