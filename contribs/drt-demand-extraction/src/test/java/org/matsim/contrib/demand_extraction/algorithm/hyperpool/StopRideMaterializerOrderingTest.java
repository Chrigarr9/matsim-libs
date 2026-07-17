package org.matsim.contrib.demand_extraction.algorithm.hyperpool;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.contrib.demand_extraction.algorithm.bamas.ride.OrderingCodec;
import org.matsim.contrib.demand_extraction.algorithm.bamas.ride.RideMetricScaling;
import org.matsim.contrib.demand_extraction.algorithm.bamas.ride.RideRow;
import org.matsim.contrib.demand_extraction.algorithm.bamas.ride.StopLocationDictionary;
import org.matsim.contrib.demand_extraction.algorithm.bamas.ride.StopRideLayer;
import org.matsim.contrib.demand_extraction.algorithm.bamas.ride.StopRideMaterializer;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideKind;
import org.matsim.contrib.demand_extraction.algorithm.domain.StopLocation;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCacheTestFixture;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;

/**
 * HYP-3 regression: a ride whose PICKUP order is a non-identity permutation of
 * ascending-index order must be replayed with each passenger's OWN metrics.
 *
 * <p>Fat-path convention: {@code requests[] IS originsOrdered} (pickup order),
 * and the stub stores the walk arrays verbatim in that order. The pre-fix
 * replay paired {@code accessWalk[i]} (pickup order) with
 * {@code requestById.get(sortedSet[i])} (sorted order) — cross-wiring detours,
 * legacy delays, and budgets across passengers.
 */
class StopRideMaterializerOrderingTest {

    private static final double WALK_SPEED = 1.2;
    private static final double T0 = 8.0 * 3600;
    private static final double SEG_TT = 60.0;    // s
    private static final double SEG_DIST = 500.0; // m

    @Test
    void permutedPickupOrder_scoresEachPassengerWithOwnMetrics() {
        // Requests with global indices 2 and 9; pickup order = (9, 2), a
        // non-identity permutation of the sorted set (2, 9).
        DrtRequest req2 = request(2, "p2", 600.0);
        DrtRequest req9 = request(9, "p9", 300.0);
        Map<Integer, DrtRequest> byId = Map.of(2, req2, 9, req9);

        StopLocationDictionary dict = new StopLocationDictionary();
        StopLocation pickup = new StopLocation(
                Id.createLinkId("link_pickup"), new Coord(0.0, 0.0), 0.0);
        StopLocation dropoff = new StopLocation(
                Id.createLinkId("link_dropoff"), new Coord(500.0, 0.0), 0.0);
        int pickupId = dict.idOf(pickup);
        int dropoffId = dict.idOf(dropoff);

        // Walk arrays stored in PICKUP order: pax req9 walks 120 m, pax req2 walks 60 m.
        double[] accessWalk = {120.0, 60.0};
        double[] egressWalk = {0.0, 0.0};

        StopRideLayer layer = new StopRideLayer(2);
        // sortedSet = (2, 9); pickup order (9, 2) -> local positions (1, 0).
        layer.addRow(new int[]{2, 9},
                OrderingCodec.pack(new int[]{1, 0}),
                OrderingCodec.pack(new int[]{1, 0}),
                RideMetricScaling.toDeci(SEG_DIST), RideMetricScaling.toDeci(SEG_TT),
                RideRow.kindToFlags(RideKind.FIFO),
                pickupId, dropoffId, T0, /*rideIndex*/ 7,
                accessWalk, egressWalk);

        ExMasConfigGroup cfg = new ExMasConfigGroup();
        cfg.setEnableBudgetAwareConstraints(true);
        cfg.setWalkSpeedMps(WALK_SPEED);

        // Score encodes BOTH the scored request and the walk it was scored
        // with: budget[i] = scoredRequest.index + accessWalkDist. Cross-wiring
        // is detectable: correct = [9+120, 2+60] = [129, 62]; buggy (sorted-
        // order requests with pickup-order walks) = [2+120, 9+60] = [122, 69].
        BudgetValidator validator = new BudgetValidator(null, cfg, WALK_SPEED) {
            @Override
            public double calculateDrtScoreWithWalks(DrtRequest request, double delay,
                    double actualTravelTime, double actualDistance,
                    double accessWalkDist, double egressWalkDist) {
                return request.bestModeScore + request.index + accessWalkDist;
            }
        };

        StopRideMaterializer mat = new StopRideMaterializer(
                buildCache(), validator, dict, cfg);

        Ride ride = mat.materialize(layer, 0, byId);

        // Emitted request list is pickup order.
        assertEquals(9, ride.getRequests()[0].index);
        assertEquals(2, ride.getRequests()[1].index);

        // Budgets aligned with the emitted request list: position 0 = req9
        // scored with ITS OWN 120 m walk.
        assertEquals(129.0, ride.getRemainingBudgets()[0], 1e-9);
        assertEquals(62.0, ride.getRemainingBudgets()[1], 1e-9);

        // Detours use the aligned passenger's OWN directTravelTime:
        // tt_i = accessTime_i + segTT + egressTime_i.
        double tt0 = 120.0 / WALK_SPEED + SEG_TT;
        double tt1 = 60.0 / WALK_SPEED + SEG_TT;
        assertEquals(tt0 / 300.0, ride.getDetours()[0], 1e-9,
                "detour[0] must divide by req9's direct time (300 s)");
        assertEquals(tt1 / 600.0, ride.getDetours()[1], 1e-9,
                "detour[1] must divide by req2's direct time (600 s)");

        // Budget-aware delays = per-pax access walk time (order preserved).
        assertEquals(120.0 / WALK_SPEED, ride.getDelays()[0], 1e-9);
        assertEquals(60.0 / WALK_SPEED, ride.getDelays()[1], 1e-9);
    }

    private static MatsimNetworkCache buildCache() {
        // Minimal 3-node chain so getNetwork().getLinks() resolves both stop links.
        Network net = NetworkUtils.createNetwork();
        NetworkFactory f = net.getFactory();
        Node n0 = f.createNode(Id.createNodeId("n0"), new Coord(0.0, 0.0));
        Node n1 = f.createNode(Id.createNodeId("n1"), new Coord(500.0, 0.0));
        Node n2 = f.createNode(Id.createNodeId("n2"), new Coord(1000.0, 0.0));
        net.addNode(n0);
        net.addNode(n1);
        net.addNode(n2);
        var pickupLink = f.createLink(Id.createLinkId("link_pickup"), n0, n1);
        var dropoffLink = f.createLink(Id.createLinkId("link_dropoff"), n1, n2);
        pickupLink.setLength(500.0);
        pickupLink.setFreespeed(13.89);
        dropoffLink.setLength(500.0);
        dropoffLink.setFreespeed(13.89);
        net.addLink(pickupLink);
        net.addLink(dropoffLink);

        MatsimNetworkCache cache = MatsimNetworkCacheTestFixture.createWithRouting(
                net, new FreeSpeedTravelTime(),
                new OnlyTimeDependentTravelDisutility(new FreeSpeedTravelTime()),
                Integer.MAX_VALUE);
        // Pre-populate so replay is the same cache hit the master path made,
        // and the stub self-check (distDm/ttDs) passes exactly.
        MatsimNetworkCacheTestFixture.put(cache,
                Id.createLinkId("link_pickup"), Id.createLinkId("link_dropoff"),
                new TravelSegment(SEG_TT, SEG_DIST, 0.0));
        return cache;
    }

    private static DrtRequest request(int index, String personId, double directTravelTime) {
        return DrtRequest.builder()
                .index(index)
                .personId(Id.createPersonId(personId))
                .groupId(personId + "_g0")
                .tripIndex(0)
                .isCommute(true)
                .isEducation(false)
                .budget(5.0)
                .bestModeScore(-2.0)
                .bestMode("car")
                .originLinkId(Id.createLinkId("link_pickup"))
                .destinationLinkId(Id.createLinkId("link_dropoff"))
                .originX(0.0).originY(0.0)
                .destinationX(500.0).destinationY(0.0)
                .requestTime(T0)
                .earliestDeparture(T0 - 300)
                .latestArrival(T0 + 3600)
                .directTravelTime(directTravelTime)
                .directDistance(500.0)
                .maxDetourFactor(1.5)
                .carTravelTime(directTravelTime)
                .ptTravelTime(directTravelTime + 300.0)
                .ptAccessibility(1.0)
                .build();
    }
}
