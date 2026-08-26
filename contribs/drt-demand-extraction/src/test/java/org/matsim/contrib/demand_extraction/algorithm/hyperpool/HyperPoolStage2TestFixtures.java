package org.matsim.contrib.demand_extraction.algorithm.hyperpool;

import java.util.ArrayList;
import java.util.List;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideKind;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideVariant;
import org.matsim.contrib.demand_extraction.algorithm.domain.StopLocation;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCacheTestFixture;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;
import org.matsim.core.utils.geometry.CoordUtils;

/**
 * Shared infrastructure for the Stage-2 (HyperPool) validation tests
 * (Tasks 3-5, HYP-1/4/5/6).
 *
 * <p>Canonical two-ride cluster:
 * <pre>
 *   P1 (link p1, x=0) --60s/500m--> P2 (link p2, x=500) --60s/500m--> D (link d, x=1000)
 *   P1 -------------------------120s/1000m------------------------->  D
 * </pre>
 * Ride 0 (pax index 0): P1 -> D, departs T0.
 * Ride 1 (pax index 1): P2 -> D, departs T0 + 60.
 * Without relocation the stop sequence is [P1, P2, D]; cumulative route
 * profile cumTime = [0, 60, 120], cumDist = [0, 500, 1000].
 */
final class HyperPoolStage2TestFixtures {

    static final double WALK_SPEED = 1.2;
    static final double T0 = 8.0 * 3600;

    private HyperPoolStage2TestFixtures() {}

    static StopLocation stop(String linkId, double x) {
        return new StopLocation(Id.createLinkId(linkId), new Coord(x, 0.0), 0.0);
    }

    static ExMasConfigGroup buildConfig() {
        ExMasConfigGroup cfg = new ExMasConfigGroup();
        cfg.setEnableBudgetAwareConstraints(false); // per-pax reloc caps not under test
        cfg.setHyperPoolEnableStopRelocation(false);
        cfg.setHyperPoolMinOccupancy(2);
        cfg.setHyperPoolTimeWindowSeconds(3600.0);
        cfg.setHyperPoolStopProximityMeters(50.0);  // P1-P2 (500 m apart) NOT merged
        cfg.setHyperPoolMaxStops(-1);
        cfg.setWalkSpeedMps(WALK_SPEED);
        cfg.setMaxWalkDistanceMeters(10_000.0);     // walk caps not binding by default
        return cfg;
    }

    static MatsimNetworkCache buildCache() {
        MatsimNetworkCache cache = MatsimNetworkCacheTestFixture.create();
        MatsimNetworkCacheTestFixture.put(cache, Id.createLinkId("p1"),
                Id.createLinkId("p2"), new TravelSegment(60.0, 500.0, 0.0));
        MatsimNetworkCacheTestFixture.put(cache, Id.createLinkId("p2"),
                Id.createLinkId("d"), new TravelSegment(60.0, 500.0, 0.0));
        MatsimNetworkCacheTestFixture.put(cache, Id.createLinkId("p1"),
                Id.createLinkId("d"), new TravelSegment(120.0, 1000.0, 0.0));
        return cache;
    }

    static DrtRequest request(int index, String personId, double requestTime,
            double maxWaitTime) {
        return request(index, personId, requestTime, maxWaitTime, /* isCommute= */ true);
    }

    /** Full-control overload allowing a spontaneous (non-mandatory) request. */
    static DrtRequest request(int index, String personId, double requestTime,
            double maxWaitTime, boolean isCommute) {
        return DrtRequest.builder()
                .index(index)
                .personId(Id.createPersonId(personId))
                .groupId(personId + "_g0")
                .tripIndex(0)
                .isCommute(isCommute)
                .isEducation(false)
                .budget(5.0)
                .bestModeScore(-2.0)
                .bestMode("car")
                .originLinkId(Id.createLinkId("p1"))
                .destinationLinkId(Id.createLinkId("d"))
                .originX(0.0).originY(0.0)
                .destinationX(1000.0).destinationY(0.0)
                .requestTime(requestTime)
                .earliestDeparture(requestTime - 300)
                .latestArrival(requestTime + 7200)
                .directTravelTime(120.0)
                .directDistance(1000.0)
                .maxDetourFactor(1.5)
                .maxWaitTime(maxWaitTime)
                .carTravelTime(120.0)
                .ptTravelTime(300.0)
                .ptAccessibility(1.0)
                .build();
    }

    /** Degree-1 STOP_TO_STOP ride for one request. */
    static Ride s2sRide(int index, DrtRequest req, StopLocation pickup,
            StopLocation dropoff, double departureTime,
            double accessWalk, double egressWalk) {
        return Ride.builder()
                .index(index).degree(1).kind(RideKind.SINGLE)
                .requests(new DrtRequest[]{req})
                .originsOrderedRequests(new DrtRequest[]{req})
                .destinationsOrderedRequests(new DrtRequest[]{req})
                .passengerTravelTimes(new double[]{120.0})
                .passengerDistances(new double[]{1000.0})
                .passengerNetworkUtilities(new double[]{0.0})
                .delays(new double[]{accessWalk / WALK_SPEED})
                .detours(new double[]{1.0})
                .remainingBudgets(new double[]{1.0})
                .connectionTravelTimes(new double[]{120.0})
                .connectionDistances(new double[]{1000.0})
                .connectionNetworkUtilities(new double[]{0.0})
                .startTime(departureTime)
                .variant(RideVariant.STOP_TO_STOP)
                .pickupStop(pickup)
                .dropoffStop(dropoff)
                .accessWalkDistances(new double[]{accessWalk})
                .egressWalkDistances(new double[]{egressWalk})
                .build();
    }

    /**
     * Two degree-1 rides sharing ONE dropoff instance D: pax0 boards P1 at T0,
     * pax1 boards P2 (ride departs T0+60), with configurable request time,
     * wait cap, and access walk for pax1 / pax0.
     */
    static List<Ride> twoRideCluster(double pax0AccessWalk,
            double pax1RequestTime, double pax1MaxWait) {
        return twoRideCluster(pax0AccessWalk, pax1RequestTime, pax1MaxWait, /* pax1IsCommute= */ true);
    }

    /** Full-control overload allowing pax1 to be a spontaneous (non-mandatory) request. */
    static List<Ride> twoRideCluster(double pax0AccessWalk,
            double pax1RequestTime, double pax1MaxWait, boolean pax1IsCommute) {
        StopLocation p1 = stop("p1", 0.0);
        StopLocation p2 = stop("p2", 500.0);
        StopLocation d = stop("d", 1000.0); // SHARED instance -> no dropoff dedup ambiguity
        DrtRequest r0 = request(0, "pax0", T0, 0.0);
        DrtRequest r1 = request(1, "pax1", pax1RequestTime, pax1MaxWait, pax1IsCommute);
        List<Ride> rides = new ArrayList<>();
        rides.add(s2sRide(0, r0, p1, d, T0, pax0AccessWalk, 0.0));
        rides.add(s2sRide(1, r1, p2, d, T0 + 60.0, 0.0, 0.0));
        return rides;
    }

    /**
     * Budget validator that records every scoring call and returns
     * {@code bestModeScore + scoreOffset} (offset >= 0 accepts, < 0 rejects).
     */
    static final class RecordingValidator extends BudgetValidator {
        record Call(int requestIndex, double delay, double travelTime,
                double distance, double accessWalk, double egressWalk) {}

        final List<Call> calls = new ArrayList<>();
        private final double scoreOffset;

        RecordingValidator(ExMasConfigGroup cfg, double scoreOffset) {
            super(null, cfg, WALK_SPEED);
            this.scoreOffset = scoreOffset;
        }

        @Override
        public double calculateDrtScoreWithWalks(DrtRequest request, double delay,
                double actualTravelTime, double actualDistance,
                double accessWalkDist, double egressWalkDist) {
            calls.add(new Call(request.index, delay, actualTravelTime,
                    actualDistance, accessWalkDist, egressWalkDist));
            return request.bestModeScore + scoreOffset;
        }
    }

    static HyperPoolGenerator generator(ExMasConfigGroup cfg,
            MatsimNetworkCache cache, BudgetValidator validator,
            HyperPoolGenerator.StopRelocator relocator) {
        return new HyperPoolGenerator(cache, relocator,
                (r1, r2) -> true, cfg, validator, null);
    }

    /** Production-style relocator (mirrors BamasEngine.createHyperPoolStopRelocator). */
    static HyperPoolGenerator.StopRelocator mergingRelocator() {
        return new HyperPoolGenerator.StopRelocator() {
            @Override
            public boolean areStopsNearby(StopLocation a, StopLocation b, double prox) {
                return CoordUtils.calcEuclideanDistance(a.getCoord(), b.getCoord()) <= prox;
            }

            @Override
            public StopLocation findMergedStop(StopLocation stopLoc,
                    List<StopLocation> existing, double prox, double[] caps) {
                for (StopLocation e : existing) {
                    if (areStopsNearby(stopLoc, e, prox)) {
                        return e;
                    }
                }
                return stopLoc;
            }

            @Override
            public double calculateRelocationDistance(StopLocation original,
                    StopLocation merged) {
                return CoordUtils.calcEuclideanDistance(
                        original.getCoord(), merged.getCoord());
            }
        };
    }
}
