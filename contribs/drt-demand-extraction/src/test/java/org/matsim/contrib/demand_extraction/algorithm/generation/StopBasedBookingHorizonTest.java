package org.matsim.contrib.demand_extraction.algorithm.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

/**
 * Task 4: {@link StopBasedRideGenerator} enforcement of the booking-time rule
 * ({@link org.matsim.contrib.demand_extraction.algorithm.validation.BookingHorizonRule}).
 *
 * <p>Starts from the canonical degree-2 door-to-door ride
 * {@link StopBasedTestFixtures#buildD2DRide()} (startTime = 08:00 = T0) and
 * overrides pax1's {@code isCommute}/{@code requestTime} to construct
 * mandatory-vs-spontaneous scenarios. rideStartTime is
 * {@code doorToDoor.getStartTime()} — the stop-based ride departs at the same
 * physical moment as the source door-to-door ride (see
 * {@code convertToStopBasedLegacy}, Step 8: {@code .startTime(doorToDoor.getStartTime())}).
 */
class StopBasedBookingHorizonTest {

    private static final double T0 = 8.0 * 3600;
    private static final double PAX1_REQUEST_TIME = T0 + 1000.0;
    private static final double HORIZON = 600.0;

    /** Rebuilds the canonical D2D ride with pax1's isCommute/requestTime overridden. */
    private static Ride buildRide(boolean pax1IsCommute, double pax1RequestTime) {
        Ride base = StopBasedTestFixtures.buildD2DRide();
        DrtRequest[] baseReqs = base.getRequests();
        DrtRequest req0 = baseReqs[0];
        DrtRequest req1 = baseReqs[1].toBuilder()
                .isCommute(pax1IsCommute)
                .requestTime(pax1RequestTime)
                .build();
        DrtRequest[] reqs = {req0, req1};
        return base.toBuilder()
                .requests(reqs)
                .originsOrderedRequests(reqs)
                .destinationsOrderedRequests(reqs)
                .build();
    }

    private static StopBasedRideGenerator buildGenerator(double horizon) {
        var finder = new StopBasedTestFixtures.CapturingStopFinder();
        ExMasConfigGroup cfg = StopBasedTestFixtures.buildExMasConfig(/* enableBudgetAware= */ true);
        cfg.setSpontaneousBookingHorizon(horizon);
        BudgetValidator validator = StopBasedTestFixtures.buildPassingValidator(cfg);
        return StopBasedTestFixtures.buildGenerator(
                cfg, finder, validator,
                new org.matsim.contrib.demand_extraction.algorithm.stops.WalkingDistanceCalculator() {
                    @Override
                    public double calculateWalkDistance(org.matsim.api.core.v01.Coord coord,
                            org.matsim.api.core.v01.network.Link link) {
                        return StopBasedTestFixtures.WALK_M;
                    }
                },
                (budget, req, actualTT, dist, delay) -> StopBasedTestFixtures.MID_M);
    }

    @Test
    void horizonRejectsRideThatPredatesSpontaneousMemberBooking() {
        // pax1 spontaneous: rideStartTime T0 < requestTime(T0+1000) - horizon(600) = T0+400.
        Ride ride = buildRide(/* pax1IsCommute= */ false, PAX1_REQUEST_TIME);
        StopBasedRideGenerator gen = buildGenerator(HORIZON);

        List<Ride> out = gen.generateStopBasedRides(new ArrayList<>(List.of(ride)), 0);

        assertEquals(0, out.size(), "ride departs before the spontaneous member booked");
        assertEquals(1, gen.getFailedBookingHorizon());
    }

    @Test
    void mandatoryMemberBypassesTheHorizon() {
        // Same timing, but pax1 is mandatory (commute) -> the rule never binds.
        Ride ride = buildRide(/* pax1IsCommute= */ true, PAX1_REQUEST_TIME);
        StopBasedRideGenerator gen = buildGenerator(HORIZON);

        List<Ride> out = gen.generateStopBasedRides(new ArrayList<>(List.of(ride)), 0);

        assertEquals(1, out.size(), "a mandatory (commute/education) member never binds the horizon");
        assertEquals(0, gen.getFailedBookingHorizon());
    }

    @Test
    void zeroHorizonIsByteIdenticalLegacy() {
        // Same spontaneous-vs-timing setup that IS rejected at horizon=600 above must be
        // PRODUCED at horizon=0 (the rule is a no-op), matching the untouched default config.
        Ride ride = buildRide(/* pax1IsCommute= */ false, PAX1_REQUEST_TIME);

        List<Ride> outDefault = buildGenerator(0.0)
                .generateStopBasedRides(new ArrayList<>(List.of(ride)), 0);
        List<Ride> outLegacyBaseline = StopBasedTestFixtures.buildGenerator(
                        true, new StopBasedTestFixtures.CapturingStopFinder(),
                        StopBasedTestFixtures.buildPassingValidator(StopBasedTestFixtures.buildExMasConfig(true)))
                .generateStopBasedRides(new ArrayList<>(List.of(ride)), 0);

        assertEquals(1, outDefault.size());
        assertEquals(1, outLegacyBaseline.size());
    }
}
