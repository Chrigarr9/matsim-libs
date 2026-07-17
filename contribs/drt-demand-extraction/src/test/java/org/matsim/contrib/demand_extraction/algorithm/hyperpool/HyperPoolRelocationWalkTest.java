package org.matsim.contrib.demand_extraction.algorithm.hyperpool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.matsim.contrib.demand_extraction.algorithm.hyperpool.HyperPoolStage2TestFixtures.T0;
import static org.matsim.contrib.demand_extraction.algorithm.hyperpool.HyperPoolStage2TestFixtures.WALK_SPEED;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.matsim.contrib.demand_extraction.algorithm.domain.HyperPooledRide;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.StopLocation;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;

/**
 * HYP-6: when the relocator merges a wrapper's stop into another stop, the
 * relocation distance must be charged to that wrapper's passengers' walks
 * BEFORE metrics and budget scoring — previously it was charged to no one.
 */
class HyperPoolRelocationWalkTest {

    @Test
    void mergedPickup_chargesRelocationWalk_toMetricsAndBudget() {
        ExMasConfigGroup cfg = HyperPoolStage2TestFixtures.buildConfig();
        cfg.setHyperPoolStopProximityMeters(600.0); // P2 (500 m from P1) merges into P1
        var validator = new HyperPoolStage2TestFixtures.RecordingValidator(cfg, 1.0);
        HyperPoolGenerator gen = HyperPoolStage2TestFixtures.generator(
                cfg, HyperPoolStage2TestFixtures.buildCache(), validator,
                HyperPoolStage2TestFixtures.mergingRelocator());

        // pax1 requests one hour early so the 500 m relocation walk cannot
        // trip the temporal check: delay = 3600 - 500/1.2 > 0.
        StopLocation p1 = HyperPoolStage2TestFixtures.stop("p1", 0.0);
        StopLocation p2 = HyperPoolStage2TestFixtures.stop("p2", 500.0);
        StopLocation d = HyperPoolStage2TestFixtures.stop("d", 1000.0);
        var r0 = HyperPoolStage2TestFixtures.request(0, "pax0", T0, 0.0);
        var r1 = HyperPoolStage2TestFixtures.request(1, "pax1", T0 - 3600.0, 0.0);
        List<Ride> rides = new ArrayList<>();
        rides.add(HyperPoolStage2TestFixtures.s2sRide(0, r0, p1, d, T0, 0.0, 0.0));
        rides.add(HyperPoolStage2TestFixtures.s2sRide(1, r1, p2, d, T0 + 60.0, 0.0, 0.0));

        List<HyperPooledRide> out = gen.generate(rides,
                HyperPoolStage2TestFixtures.buildCache(), 0);

        assertEquals(1, out.size(), "merged cluster must be accepted");
        HyperPooledRide ride = out.get(0);

        // Sequence collapses to [P1, D]; pax1's pickup was relocated 500 m.
        assertEquals(2, ride.getStopCount());
        assertEquals(0.0, ride.getAccessWalkDistances()[0], 1e-9,
                "pax0's stop was not relocated");
        assertEquals(500.0, ride.getAccessWalkDistances()[1], 1e-9,
                "pax1 must be charged the realized relocation walk");
        assertEquals(0.0, ride.getEgressWalkDistances()[1], 1e-9,
                "shared dropoff instance -> zero egress relocation");

        // Budget scoring saw the increased walk and the walk-adjusted delay.
        var call1 = validator.calls.stream()
                .filter(c -> c.requestIndex() == 1).findFirst().orElseThrow();
        assertEquals(500.0, call1.accessWalk(), 1e-9);
        assertEquals(500.0 + 1000.0 + 0.0, call1.distance(), 1e-9);
        assertEquals(3600.0 - 500.0 / WALK_SPEED, call1.delay(), 1e-9);

        // Total walk metric includes the relocation.
        assertEquals(500.0, ride.getPassengerTotalWalkDistances()[1], 1e-9);
    }

    @Test
    void noRelocator_walksUnchanged() {
        ExMasConfigGroup cfg = HyperPoolStage2TestFixtures.buildConfig();
        var validator = new HyperPoolStage2TestFixtures.RecordingValidator(cfg, 1.0);
        HyperPoolGenerator gen = HyperPoolStage2TestFixtures.generator(
                cfg, HyperPoolStage2TestFixtures.buildCache(), validator, null);

        // pax0 walks 40 m and requests 60 s BEFORE T0 so the Task-4 temporal
        // check stays clear: delay = T0 - (T0 - 60 + 40/1.2) = 26.7 s >= 0.
        StopLocation p1 = HyperPoolStage2TestFixtures.stop("p1", 0.0);
        StopLocation p2 = HyperPoolStage2TestFixtures.stop("p2", 500.0);
        StopLocation d = HyperPoolStage2TestFixtures.stop("d", 1000.0);
        var r0 = HyperPoolStage2TestFixtures.request(0, "pax0", T0 - 60.0, 0.0);
        var r1 = HyperPoolStage2TestFixtures.request(1, "pax1", T0, 0.0);
        List<Ride> rides = new ArrayList<>();
        rides.add(HyperPoolStage2TestFixtures.s2sRide(0, r0, p1, d, T0, 40.0, 0.0));
        rides.add(HyperPoolStage2TestFixtures.s2sRide(1, r1, p2, d, T0 + 60.0, 0.0, 0.0));

        List<HyperPooledRide> out = gen.generate(rides,
                HyperPoolStage2TestFixtures.buildCache(), 0);

        assertEquals(1, out.size());
        assertEquals(40.0, out.get(0).getAccessWalkDistances()[0], 1e-9,
                "Stage-1 walks pass through verbatim when relocation is off");
    }
}
