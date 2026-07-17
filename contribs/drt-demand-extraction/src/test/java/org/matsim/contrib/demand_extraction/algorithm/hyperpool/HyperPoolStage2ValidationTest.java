package org.matsim.contrib.demand_extraction.algorithm.hyperpool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.matsim.contrib.demand_extraction.algorithm.hyperpool.HyperPoolStage2TestFixtures.T0;
import static org.matsim.contrib.demand_extraction.algorithm.hyperpool.HyperPoolStage2TestFixtures.WALK_SPEED;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.matsim.contrib.demand_extraction.algorithm.domain.HyperPooledRide;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;

/**
 * HYP-1 + HYP-4: Stage-2 acceptance validation wired inline into
 * {@link HyperPoolGenerator#generateHyperPooledRide} with ONE delay definition:
 * delay_i = (startTime + timeToBoardingStop_i) - (requestTime_i + accessWalkTime_i),
 * per-pax routed distance, budget rejection, walk-cap rejection, and the
 * per-pax alignment invariant.
 */
class HyperPoolStage2ValidationTest {

    @Test
    void delayAndDistance_usePerPaxRoutedPrefix() {
        ExMasConfigGroup cfg = HyperPoolStage2TestFixtures.buildConfig();
        var validator = new HyperPoolStage2TestFixtures.RecordingValidator(cfg, 1.0);
        HyperPoolGenerator gen = HyperPoolStage2TestFixtures.generator(
                cfg, HyperPoolStage2TestFixtures.buildCache(), validator, null);

        // pax1 requestTime = T0, zero walks -> boarding at P2 happens at T0+60.
        List<Ride> cluster = HyperPoolStage2TestFixtures.twoRideCluster(0.0, T0, 0.0);
        List<HyperPooledRide> out = gen.generate(cluster,
                HyperPoolStage2TestFixtures.buildCache(), 0);

        assertEquals(1, out.size(), "cluster must be accepted");
        HyperPooledRide ride = out.get(0);

        // Exported per-pax delays describe THIS ride (HYP-4).
        assertEquals(0.0, ride.getPassengerDelays()[0], 1e-9,
                "pax0 boards the first stop at startTime with zero walk");
        assertEquals(60.0, ride.getPassengerDelays()[1], 1e-9,
                "pax1 boards after the routed P1->P2 prefix (60 s)");

        // In-vehicle times from the cumulative profile.
        assertEquals(120.0, ride.getInVehicleTimes()[0], 1e-9);
        assertEquals(60.0, ride.getInVehicleTimes()[1], 1e-9);

        // The budget score received the SAME delay + per-pax routed distance.
        var call0 = validator.calls.stream()
                .filter(c -> c.requestIndex() == 0).findFirst().orElseThrow();
        var call1 = validator.calls.stream()
                .filter(c -> c.requestIndex() == 1).findFirst().orElseThrow();
        assertEquals(0.0, call0.delay(), 1e-9);
        assertEquals(1000.0, call0.distance(), 1e-9,
                "pax0 rides P1->P2->D = 1000 m, not totalDistance/passengerCount");
        assertEquals(60.0, call1.delay(), 1e-9);
        assertEquals(500.0, call1.distance(), 1e-9,
                "pax1 rides P2->D = 500 m only");
        assertEquals(120.0, call0.travelTime(), 1e-9);
        assertEquals(60.0, call1.travelTime(), 1e-9);
    }

    @Test
    void negativeRemainingBudget_rejectsCluster_withCountedStat() {
        ExMasConfigGroup cfg = HyperPoolStage2TestFixtures.buildConfig();
        var rejecting = new HyperPoolStage2TestFixtures.RecordingValidator(cfg, -1.0);
        HyperPoolGenerator gen = HyperPoolStage2TestFixtures.generator(
                cfg, HyperPoolStage2TestFixtures.buildCache(), rejecting, null);

        List<HyperPooledRide> out = gen.generate(
                HyperPoolStage2TestFixtures.twoRideCluster(0.0, T0, 0.0),
                HyperPoolStage2TestFixtures.buildCache(), 0);

        assertTrue(out.isEmpty(), "budget-infeasible cluster must be rejected");
        assertEquals(1, gen.getFailedBudgetExceeded());
    }

    @Test
    void walkCapViolation_rejectsCluster_withCountedStat() {
        ExMasConfigGroup cfg = HyperPoolStage2TestFixtures.buildConfig();
        cfg.setMaxWalkDistanceMeters(100.0);
        var validator = new HyperPoolStage2TestFixtures.RecordingValidator(cfg, 1.0);
        HyperPoolGenerator gen = HyperPoolStage2TestFixtures.generator(
                cfg, HyperPoolStage2TestFixtures.buildCache(), validator, null);

        // pax0 access walk 150 m > 100 m cap (dead-validator semantics).
        List<HyperPooledRide> out = gen.generate(
                HyperPoolStage2TestFixtures.twoRideCluster(150.0, T0, 0.0),
                HyperPoolStage2TestFixtures.buildCache(), 0);

        assertTrue(out.isEmpty());
        assertEquals(1, gen.getFailedWalkCapExceeded());
    }

    @Test
    void nullValidator_stillEmitsDelays_budgetsZero() {
        ExMasConfigGroup cfg = HyperPoolStage2TestFixtures.buildConfig();
        HyperPoolGenerator gen = HyperPoolStage2TestFixtures.generator(
                cfg, HyperPoolStage2TestFixtures.buildCache(), null, null);

        List<HyperPooledRide> out = gen.generate(
                HyperPoolStage2TestFixtures.twoRideCluster(0.0, T0, 0.0),
                HyperPoolStage2TestFixtures.buildCache(), 0);

        assertEquals(1, out.size());
        assertEquals(60.0, out.get(0).getPassengerDelays()[1], 1e-9);
        assertEquals(0.0, out.get(0).getRemainingBudgets()[1], 1e-9);
    }

    @Test
    void perPaxAlignment_throwsOnDegreeSumMismatch() {
        // The invariant every per-pax CSV column rests on (HYP-4):
        // bundled request count == sum of source-ride degrees.
        List<Ride> sources = HyperPoolStage2TestFixtures.twoRideCluster(0.0, T0, 0.0);
        HyperPoolGenerator.checkPerPaxAlignment(2, sources); // must not throw
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> HyperPoolGenerator.checkPerPaxAlignment(3, sources));
        assertTrue(ex.getMessage().contains("alignment"));
    }

    @Test
    void accessWalkTime_entersDelayDefinition() {
        ExMasConfigGroup cfg = HyperPoolStage2TestFixtures.buildConfig();
        var validator = new HyperPoolStage2TestFixtures.RecordingValidator(cfg, 1.0);
        HyperPoolGenerator gen = HyperPoolStage2TestFixtures.generator(
                cfg, HyperPoolStage2TestFixtures.buildCache(), validator, null);

        // pax0 walks 60 m (50 s at 1.2 m/s) but requests 120 s BEFORE T0 so the
        // delay stays positive: delay = (T0 + 0) - (T0 - 120 + 50) = 70 s.
        var p1 = HyperPoolStage2TestFixtures.stop("p1", 0.0);
        var p2 = HyperPoolStage2TestFixtures.stop("p2", 500.0);
        var d = HyperPoolStage2TestFixtures.stop("d", 1000.0);
        var r0 = HyperPoolStage2TestFixtures.request(0, "pax0", T0 - 120.0, 0.0);
        var r1 = HyperPoolStage2TestFixtures.request(1, "pax1", T0, 0.0);
        List<Ride> rides = new java.util.ArrayList<>();
        rides.add(HyperPoolStage2TestFixtures.s2sRide(0, r0, p1, d, T0, 60.0, 0.0));
        rides.add(HyperPoolStage2TestFixtures.s2sRide(1, r1, p2, d, T0 + 60.0, 0.0, 0.0));

        List<HyperPooledRide> out = gen.generate(rides,
                HyperPoolStage2TestFixtures.buildCache(), 0);

        assertEquals(1, out.size());
        assertEquals(120.0 - 60.0 / WALK_SPEED, out.get(0).getPassengerDelays()[0], 1e-9,
                "delay must subtract requestTime + accessWalkTime, not requestTime alone");
    }
}
