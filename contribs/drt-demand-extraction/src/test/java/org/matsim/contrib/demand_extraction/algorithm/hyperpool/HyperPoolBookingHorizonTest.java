package org.matsim.contrib.demand_extraction.algorithm.hyperpool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.matsim.contrib.demand_extraction.algorithm.hyperpool.HyperPoolStage2TestFixtures.T0;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.matsim.contrib.demand_extraction.algorithm.domain.HyperPooledRide;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;

/**
 * Task 4: {@link HyperPoolGenerator} enforcement of the booking-time rule
 * ({@link org.matsim.contrib.demand_extraction.algorithm.validation.BookingHorizonRule}).
 *
 * <p>Reuses the canonical two-ride cluster from {@link HyperPoolStage2TestFixtures}:
 * pax0 (P1, departs T0) + pax1 (P2, departs T0+60), sequence [P1, P2, D],
 * cumTime = [0, 60, 120]. The bundle's rideStartTime (the physical moment the
 * vehicle first departs to serve it) is {@code min(departureTime) == T0} — the
 * same quantity {@link HyperPoolGenerator#generateHyperPooledRide} computes as
 * {@code startTime} and stamps onto the built {@code HyperPooledRide}.
 *
 * <p>pax1.requestTime = T0+50 keeps pax1's own boarding-time check (HYP-5: the
 * bundle must not reach pax1's stop before pax1 could be ready, boarding at
 * T0+60 here) satisfied regardless of the horizon, so a rejection in these
 * tests is attributable ONLY to {@code BookingHorizonRule}, not to the
 * pre-existing per-passenger temporal check.
 */
class HyperPoolBookingHorizonTest {

    private static final double PAX1_REQUEST_TIME = T0 + 50.0;
    private static final double HORIZON = 30.0;

    @Test
    void horizonRejectsBundleThatPredatesSpontaneousMemberBooking() {
        ExMasConfigGroup cfg = HyperPoolStage2TestFixtures.buildConfig();
        cfg.setSpontaneousBookingHorizon(HORIZON);
        var validator = new HyperPoolStage2TestFixtures.RecordingValidator(cfg, 1.0);
        HyperPoolGenerator gen = HyperPoolStage2TestFixtures.generator(
                cfg, HyperPoolStage2TestFixtures.buildCache(), validator, null);

        // pax1 is spontaneous (isCommute=false): rideStartTime T0 < requestTime(T0+50) - horizon(30) = T0+20.
        List<Ride> cluster = HyperPoolStage2TestFixtures.twoRideCluster(
                0.0, PAX1_REQUEST_TIME, 0.0, /* pax1IsCommute= */ false);
        List<HyperPooledRide> out = gen.generate(cluster, HyperPoolStage2TestFixtures.buildCache(), 0);

        assertTrue(out.isEmpty(), "bundle departs before the spontaneous member booked");
        assertEquals(1, gen.getFailedBookingHorizon());
    }

    @Test
    void mandatoryMemberBypassesTheHorizon() {
        ExMasConfigGroup cfg = HyperPoolStage2TestFixtures.buildConfig();
        cfg.setSpontaneousBookingHorizon(HORIZON);
        var validator = new HyperPoolStage2TestFixtures.RecordingValidator(cfg, 1.0);
        HyperPoolGenerator gen = HyperPoolStage2TestFixtures.generator(
                cfg, HyperPoolStage2TestFixtures.buildCache(), validator, null);

        // Same timing as above, but pax1 is mandatory (commute) -> BookingHorizonRule
        // never binds on a mandatory member, so the bundle must be produced.
        List<Ride> cluster = HyperPoolStage2TestFixtures.twoRideCluster(
                0.0, PAX1_REQUEST_TIME, 0.0, /* pax1IsCommute= */ true);
        List<HyperPooledRide> out = gen.generate(cluster, HyperPoolStage2TestFixtures.buildCache(), 0);

        assertEquals(1, out.size(), "a mandatory (commute/education) member never binds the horizon");
        assertEquals(0, gen.getFailedBookingHorizon());
    }

    @Test
    void zeroHorizonIsByteIdenticalLegacy() {
        ExMasConfigGroup cfgDefault = HyperPoolStage2TestFixtures.buildConfig(); // spontaneousBookingHorizon defaults to 0.0
        ExMasConfigGroup cfgExplicitZero = HyperPoolStage2TestFixtures.buildConfig();
        cfgExplicitZero.setSpontaneousBookingHorizon(0.0);

        List<Ride> cluster = HyperPoolStage2TestFixtures.twoRideCluster(
                0.0, PAX1_REQUEST_TIME, 0.0, /* pax1IsCommute= */ false);

        var validatorDefault = new HyperPoolStage2TestFixtures.RecordingValidator(cfgDefault, 1.0);
        HyperPoolGenerator genDefault = HyperPoolStage2TestFixtures.generator(
                cfgDefault, HyperPoolStage2TestFixtures.buildCache(), validatorDefault, null);
        List<HyperPooledRide> outDefault = genDefault.generate(cluster, HyperPoolStage2TestFixtures.buildCache(), 0);

        var validatorZero = new HyperPoolStage2TestFixtures.RecordingValidator(cfgExplicitZero, 1.0);
        HyperPoolGenerator genZero = HyperPoolStage2TestFixtures.generator(
                cfgExplicitZero, HyperPoolStage2TestFixtures.buildCache(), validatorZero, null);
        List<HyperPooledRide> outZero = genZero.generate(cluster, HyperPoolStage2TestFixtures.buildCache(), 0);

        // Legacy path: the same spontaneous-vs-timing setup that IS rejected at horizon=30
        // above must be PRODUCED at horizon=0 (the rule is a no-op) — same as the untouched
        // default config.
        assertEquals(1, outDefault.size());
        assertEquals(1, outZero.size());
        assertEquals(0, genDefault.getFailedBookingHorizon());
        assertEquals(0, genZero.getFailedBookingHorizon());
    }
}
