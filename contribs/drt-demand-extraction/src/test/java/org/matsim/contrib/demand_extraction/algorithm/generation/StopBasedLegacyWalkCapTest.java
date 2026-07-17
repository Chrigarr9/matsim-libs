package org.matsim.contrib.demand_extraction.algorithm.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.stops.WalkingDistanceCalculator;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;

/**
 * HYP-7 (legacy, non-budget-aware path only):
 * (a) the walk cap must bind PER LEG (each leg <= hardCap), not as
 *     sum <= 2*hardCap (which admitted a single 1200 m leg at a 600 m cap);
 * (b) the walk-cap fallback must be dimension-correct — provider mid if a
 *     WalkBudgetProvider is injected, else the hard cap — never
 *     remainingBudget (utils) divided by walk speed.
 */
class StopBasedLegacyWalkCapTest {

    /** Access leg (pickup link "stop") walks 700 m, egress ("dropoff") 0 m. */
    private static WalkingDistanceCalculator asymmetricWalk() {
        return new WalkingDistanceCalculator() {
            @Override
            public double calculateWalkDistance(Coord coord, Link link) {
                return link.getId().toString().equals("stop") ? 700.0 : 0.0;
            }
        };
    }

    /** Stub walk calculator returning a fixed distance for every leg. */
    private static WalkingDistanceCalculator constantWalk(double distanceM) {
        return new WalkingDistanceCalculator() {
            @Override
            public double calculateWalkDistance(Coord coord, Link link) {
                return distanceM;
            }
        };
    }

    @Test
    void singleLegAboveHardCap_isRejected_evenWhenSumWithinTwiceCap() {
        var finder = new StopBasedTestFixtures.CapturingStopFinder();
        ExMasConfigGroup cfg = StopBasedTestFixtures.buildExMasConfig(false);
        BudgetValidator validator = StopBasedTestFixtures.buildPassingValidator(cfg);
        StopBasedRideGenerator gen = StopBasedTestFixtures.buildGenerator(
                false, finder, validator, asymmetricWalk(), null);

        // access 700 > 600 hard cap, egress 0; sum 700 < 1200 = 2*cap ->
        // the OLD check passed this, the per-leg check must reject it.
        List<Ride> out = gen.generateStopBasedRides(
                new ArrayList<>(List.of(StopBasedTestFixtures.buildD2DRide())), 0);

        assertTrue(out.isEmpty(), "a 700 m leg at a 600 m cap must be rejected");
    }

    @Test
    void fallbackCap_isHardCap_whenNoProviderAndNoPrecomputedCap() {
        var finder = new StopBasedTestFixtures.CapturingStopFinder();
        ExMasConfigGroup cfg = StopBasedTestFixtures.buildExMasConfig(false);
        StopBasedRideGenerator gen = StopBasedTestFixtures.buildGenerator(
                false, finder, StopBasedTestFixtures.buildPassingValidator(cfg),
                constantWalk(StopBasedTestFixtures.WALK_M), null);

        gen.generateStopBasedRides(
                new ArrayList<>(List.of(StopBasedTestFixtures.buildD2DRide())), 0);

        // Fixture requests carry maxWalkDistance = 0 -> fallback path. The old
        // fallback was min(0.5 utils / 1.34 m/s, 600)*1.34 ≈ 0.5 m; the fix
        // falls back to the hard cap (600 m).
        double[] caps = finder.calls.get(0).caps;
        assertEquals(StopBasedTestFixtures.HARD_CAP_M, caps[0], 1e-9);
        assertEquals(StopBasedTestFixtures.HARD_CAP_M, caps[1], 1e-9);
    }

    @Test
    void fallbackCap_usesProviderMid_whenProviderInjected() {
        var finder = new StopBasedTestFixtures.CapturingStopFinder();
        ExMasConfigGroup cfg = StopBasedTestFixtures.buildExMasConfig(false);
        StopBasedRideGenerator gen = StopBasedTestFixtures.buildGenerator(
                false, finder, StopBasedTestFixtures.buildPassingValidator(cfg),
                constantWalk(StopBasedTestFixtures.WALK_M),
                (budget, req, tt, dist, delay) -> StopBasedTestFixtures.MID_M);

        gen.generateStopBasedRides(
                new ArrayList<>(List.of(StopBasedTestFixtures.buildD2DRide())), 0);

        // min(provider mid 200, hard cap 600) = 200.
        double[] caps = finder.calls.get(0).caps;
        assertEquals(StopBasedTestFixtures.MID_M, caps[0], 1e-9);
        assertEquals(StopBasedTestFixtures.MID_M, caps[1], 1e-9);
    }
}
