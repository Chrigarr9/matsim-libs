package org.matsim.contrib.demand_extraction.algorithm.hyperpool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.matsim.contrib.demand_extraction.algorithm.hyperpool.HyperPoolStage2TestFixtures.T0;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.matsim.contrib.demand_extraction.algorithm.domain.HyperPooledRide;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;

/**
 * HYP-5: rider-level temporal re-validation against the bundled schedule and
 * the vehicle-capacity cap. In the canonical two-ride cluster (see fixtures),
 * pax1 boards the second stop at T0 + 60 s.
 */
class HyperPoolStage2TemporalCapacityTest {

    private static List<HyperPooledRide> run(ExMasConfigGroup cfg,
            List<org.matsim.contrib.demand_extraction.algorithm.domain.Ride> cluster,
            HyperPoolGenerator[] genOut) {
        var validator = new HyperPoolStage2TestFixtures.RecordingValidator(cfg, 1.0);
        HyperPoolGenerator gen = HyperPoolStage2TestFixtures.generator(
                cfg, HyperPoolStage2TestFixtures.buildCache(), validator, null);
        genOut[0] = gen;
        return gen.generate(cluster, HyperPoolStage2TestFixtures.buildCache(), 0);
    }

    @Test
    void boardingBeforeReadiness_rejectsCluster() {
        // pax1 requests at T0+120 but the bundle reaches P2 at T0+60 -> delay -60 s.
        var genOut = new HyperPoolGenerator[1];
        List<HyperPooledRide> out = run(HyperPoolStage2TestFixtures.buildConfig(),
                HyperPoolStage2TestFixtures.twoRideCluster(0.0, T0 + 120.0, 0.0), genOut);
        assertTrue(out.isEmpty(), "vehicle scheduled before pax readiness must reject");
        assertEquals(1, genOut[0].getFailedTemporalInfeasible());
    }

    @Test
    void waitBeyondMaxWaitTime_rejectsCluster() {
        // pax1 ready at T0, boards at T0+60, maxWaitTime 30 s -> reject.
        var genOut = new HyperPoolGenerator[1];
        List<HyperPooledRide> out = run(HyperPoolStage2TestFixtures.buildConfig(),
                HyperPoolStage2TestFixtures.twoRideCluster(0.0, T0, 30.0), genOut);
        assertTrue(out.isEmpty());
        assertEquals(1, genOut[0].getFailedWaitTimeExceeded());
    }

    @Test
    void zeroWaitCap_meansUncapped_likeStage1() {
        var genOut = new HyperPoolGenerator[1];
        List<HyperPooledRide> out = run(HyperPoolStage2TestFixtures.buildConfig(),
                HyperPoolStage2TestFixtures.twoRideCluster(0.0, T0, 0.0), genOut);
        assertEquals(1, out.size(), "maxWaitTime == 0 must not cap (S2S step-7b semantics)");
    }

    @Test
    void peakPaxAboveCapacity_rejectsCluster() {
        ExMasConfigGroup cfg = HyperPoolStage2TestFixtures.buildConfig();
        cfg.setHyperPoolMaxVehicleCapacity(1); // both pax overlap between P2 and D
        var genOut = new HyperPoolGenerator[1];
        List<HyperPooledRide> out = run(cfg,
                HyperPoolStage2TestFixtures.twoRideCluster(0.0, T0, 0.0), genOut);
        assertTrue(out.isEmpty());
        assertEquals(1, genOut[0].getFailedCapacityExceeded());
    }

    @Test
    void peakPaxAtCapacity_accepted() {
        ExMasConfigGroup cfg = HyperPoolStage2TestFixtures.buildConfig();
        cfg.setHyperPoolMaxVehicleCapacity(2);
        var genOut = new HyperPoolGenerator[1];
        List<HyperPooledRide> out = run(cfg,
                HyperPoolStage2TestFixtures.twoRideCluster(0.0, T0, 0.0), genOut);
        assertEquals(1, out.size());
        assertEquals(0, genOut[0].getFailedCapacityExceeded());
    }
}
