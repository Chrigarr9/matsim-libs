package org.matsim.contrib.demand_extraction.algorithm.generation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideKind;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideVariant;
import org.matsim.contrib.demand_extraction.algorithm.domain.StopLocation;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCacheTestFixture;
import org.matsim.contrib.demand_extraction.algorithm.stops.StopFinder;
import org.matsim.contrib.demand_extraction.algorithm.stops.WalkingDistanceCalculator;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;

/**
 * Shared test infrastructure for {@link StopBasedRideGenerator} unit tests.
 *
 * <p>All methods and inner classes are package-private so they are accessible
 * to tests in the same package but not exported to the rest of the test tree.
 *
 * <h2>Network topology</h2>
 * A minimal 3-node, 4-link directed chain:
 * <pre>
 *   n0 -[origin/stop]-> n1 -[dest/dropoff]-> n2
 *   (0,0)              (1000,0)              (2000,0)
 * </pre>
 * Link IDs: {@code "origin"}, {@code "stop"} (both n0→n1, 1000 m, 13.89 m/s),
 * {@code "dest"}, {@code "dropoff"} (both n1→n2, 1000 m, 13.89 m/s).
 *
 * <h2>Stub constants</h2>
 * <ul>
 *   <li>{@link #WALK_M} — fixed walk returned by the stub
 *       {@link WalkingDistanceCalculator}.
 *   <li>{@link #MID_M} — fixed mid returned by the stub
 *       {@link WalkBudgetProvider}.
 *   <li>{@link #HARD_CAP_M} — {@code maxWalkDistanceMeters} in test configs.
 * </ul>
 */
final class StopBasedTestFixtures {

    /** Fixed walk returned by the stub WalkingDistanceCalculator (metres). */
    static final double WALK_M = 50.0;
    /** Fixed mid returned by the stub WalkBudgetProvider (metres). */
    static final double MID_M = 200.0;
    /** Hard cap set in ExMasConfigGroup (metres). */
    static final double HARD_CAP_M = 600.0;

    private StopBasedTestFixtures() {
        // utility class
    }

    // =========================================================================
    // Network builder
    // =========================================================================

    /**
     * Build the minimal 3-node, 4-link directed chain network.
     * All links are 1000 m at 13.89 m/s (50 km/h), so in-vehicle time ≈ 72 s.
     */
    static Network buildMinimalNetwork() {
        Network net = NetworkUtils.createNetwork();
        NetworkFactory f = net.getFactory();

        Node n0 = f.createNode(Id.createNodeId("n0"), new Coord(0.0, 0.0));
        Node n1 = f.createNode(Id.createNodeId("n1"), new Coord(1000.0, 0.0));
        Node n2 = f.createNode(Id.createNodeId("n2"), new Coord(2000.0, 0.0));
        net.addNode(n0);
        net.addNode(n1);
        net.addNode(n2);

        addLink(net, f, "stop",    n0, n1);  // Phase A finder returns this
        addLink(net, f, "dropoff", n1, n2);  // Phase B finder returns this
        addLink(net, f, "origin",  n0, n1);  // pax origin links
        addLink(net, f, "dest",    n1, n2);  // pax destination links

        return net;
    }

    private static void addLink(Network net, NetworkFactory f,
            String id, Node from, Node to) {
        Link lnk = f.createLink(Id.createLinkId(id), from, to);
        lnk.setLength(1000.0);
        lnk.setFreespeed(13.89); // ~50 km/h → in-vehicle time ≈ 72 s
        lnk.setCapacity(1000.0);
        lnk.setNumberOfLanes(1.0);
        net.addLink(lnk);
    }

    // =========================================================================
    // Config builder
    // =========================================================================

    /**
     * Build a minimal {@link ExMasConfigGroup} with {@link #HARD_CAP_M} as the
     * hard walk cap.
     *
     * @param enableBudgetAware value for {@code enableBudgetAwareConstraints}
     */
    static ExMasConfigGroup buildExMasConfig(boolean enableBudgetAware) {
        ExMasConfigGroup cfg = new ExMasConfigGroup();
        cfg.setEnableStopBased(true);
        cfg.setMaxWalkDistanceMeters(HARD_CAP_M);
        cfg.setStopSearchRadiusMeters(HARD_CAP_M);
        cfg.setEnableBudgetAwareConstraints(enableBudgetAware);
        cfg.setDrtMode("drt");
        cfg.setMinDrtAccessEgressDistance(0.0);
        return cfg;
    }

    // =========================================================================
    // Ride builder
    // =========================================================================

    /**
     * Build a degree-2 DOOR_TO_DOOR ride.
     *
     * <p>Passenger origins are at (50, 0) and (100, 0) — not on the stop link.
     * The stub {@link WalkingDistanceCalculator} ignores geometry and always
     * returns {@link #WALK_M}, so exact coordinates do not affect walk distances.
     *
     * <p>Remaining budgets are [0.5, 0.5] (positive) so budget validation will
     * not reject the ride before the stop search unless the scorer is configured
     * otherwise.
     *
     * @param directTravelTime direct travel time in seconds for each passenger
     */
    static Ride buildD2DRide(double directTravelTime) {
        DrtRequest req0 = buildRequest(0, "pax0", "g0", 50.0, 1050.0, directTravelTime);
        DrtRequest req1 = buildRequest(1, "pax1", "g1", 100.0, 1100.0, directTravelTime);

        DrtRequest[] reqs = {req0, req1};

        return Ride.builder()
                .index(0)
                .degree(2)
                .kind(RideKind.FIFO)
                .requests(reqs)
                .originsOrderedRequests(reqs)
                .destinationsOrderedRequests(reqs)
                .passengerTravelTimes(new double[]{directTravelTime + 120.0, directTravelTime + 120.0})
                .passengerDistances(new double[]{7200.0, 7200.0})
                .passengerNetworkUtilities(new double[]{0.0, 0.0})
                .delays(new double[]{120.0, 120.0})
                .detours(new double[]{1.2, 1.2})
                .remainingBudgets(new double[]{0.5, 0.5})
                .connectionTravelTimes(new double[]{directTravelTime})
                .connectionDistances(new double[]{6000.0})
                .connectionNetworkUtilities(new double[]{0.0})
                .startTime(8.0 * 3600)
                .variant(RideVariant.DOOR_TO_DOOR)
                .build();
    }

    /**
     * Convenience overload using a default {@code directTravelTime} of 600 s.
     */
    static Ride buildD2DRide() {
        return buildD2DRide(600.0);
    }

    /**
     * Degree-2 D2D ride whose SECOND pax is a hub-leg copy (ACCESS_LEG).
     * HYP-8: such rides must never receive an S2S variant — the physical
     * transfer point must stay at the hub.
     */
    static Ride buildD2DRideWithHubLegPax() {
        DrtRequest req0 = buildRequest(0, "pax0", "g0", 50.0, 1050.0, 600.0);
        DrtRequest req1 = buildRequest(1, "pax1", "g1", 100.0, 1100.0, 600.0)
                .toBuilder()
                .requestTag("connecting")
                .hubId("hub_01")
                .hubLegRole(DrtRequest.HubLegRole.ACCESS_LEG)
                .build();
        DrtRequest[] reqs = {req0, req1};
        return Ride.builder()
                .index(1)
                .degree(2)
                .kind(RideKind.FIFO)
                .requests(reqs)
                .originsOrderedRequests(reqs)
                .destinationsOrderedRequests(reqs)
                .passengerTravelTimes(new double[]{720.0, 720.0})
                .passengerDistances(new double[]{7200.0, 7200.0})
                .passengerNetworkUtilities(new double[]{0.0, 0.0})
                .delays(new double[]{120.0, 120.0})
                .detours(new double[]{1.2, 1.2})
                .remainingBudgets(new double[]{0.5, 0.5})
                .connectionTravelTimes(new double[]{600.0})
                .connectionDistances(new double[]{6000.0})
                .connectionNetworkUtilities(new double[]{0.0})
                .startTime(8.0 * 3600)
                .variant(RideVariant.DOOR_TO_DOOR)
                .build();
    }

    private static DrtRequest buildRequest(int index, String personId, String groupId,
            double originX, double destX, double directTravelTime) {
        return DrtRequest.builder()
                .index(index)
                .personId(Id.createPersonId(personId))
                .groupId(groupId)
                .tripIndex(0)
                .isCommute(true)
                .isEducation(false)
                .budget(1.0)
                .bestModeScore(-1.0)
                .bestMode("car")
                .originLinkId(Id.createLinkId("origin"))
                .destinationLinkId(Id.createLinkId("dest"))
                .originX(originX).originY(0.0)
                .destinationX(destX).destinationY(0.0)
                .originLinkCoordFromX(0.0).originLinkCoordFromY(0.0)
                .originLinkCoordToX(1000.0).originLinkCoordToY(0.0)
                .destinationLinkCoordFromX(1000.0).destinationLinkCoordFromY(0.0)
                .destinationLinkCoordToX(2000.0).destinationLinkCoordToY(0.0)
                .requestTime(8.0 * 3600)
                .earliestDeparture(8.0 * 3600 - 300)
                .latestArrival(8.0 * 3600 + 3000)
                .directTravelTime(directTravelTime)
                .directDistance(6000.0)
                .maxDetourFactor(1.5)
                .maxWalkDistance(0.0)
                .carTravelTime(directTravelTime)
                .ptTravelTime(directTravelTime + 300.0)
                .ptAccessibility(1.0)
                .build();
    }

    // =========================================================================
    // Generator builder
    // =========================================================================

    /**
     * Build a {@link StopBasedRideGenerator} with the given stop finder and
     * validator, backed by the minimal network and stub walk calculator.
     *
     * @param enableBudgetAware value for {@code enableBudgetAwareConstraints}
     * @param stopFinder        stop finder to inject (e.g. {@link CapturingStopFinder})
     * @param validator         budget validator to inject (e.g. a recording stub)
     */
    /**
     * In-vehicle time for the "stop"→"dropoff" segment on the test network
     * (1000 m at 13.89 m/s ≈ 72 s).
     */
    static final double IN_VEHICLE_TIME_S = 1000.0 / 13.89;

    static StopBasedRideGenerator buildGenerator(
            boolean enableBudgetAware,
            StopFinder stopFinder,
            BudgetValidator validator) {

        // Use createWithRouting (real network needed for link lookups in the walk calculator),
        // but pre-populate the "stop"→"dropoff" segment so the cache never falls through to
        // Dijkstra. The Dijkstra would return an infinity segment because "stop".toNode ==
        // "dropoff".fromNode (zero-length path, no edges).
        Network network = buildMinimalNetwork();
        var tt = new FreeSpeedTravelTime();
        var td = new OnlyTimeDependentTravelDisutility(tt);
        MatsimNetworkCache networkCache = MatsimNetworkCacheTestFixture.createWithRouting(
                network, tt, td, Integer.MAX_VALUE);
        MatsimNetworkCacheTestFixture.put(
                networkCache,
                Id.createLinkId("stop"),
                Id.createLinkId("dropoff"),
                new TravelSegment(IN_VEHICLE_TIME_S, 1000.0, 0.0));

        WalkingDistanceCalculator walkCalc = new WalkingDistanceCalculator() {
            @Override
            public double calculateWalkDistance(Coord coord, Link link) {
                return WALK_M;
            }
        };

        ExMasConfigGroup exMasConfig = buildExMasConfig(enableBudgetAware);

        WalkBudgetProvider walkBudgetProvider = enableBudgetAware
                ? (budget, req, actualTT, dist, delay) -> MID_M
                : null;

        return new StopBasedRideGenerator(
                networkCache,
                stopFinder,
                walkCalc,
                validator,
                exMasConfig,
                /* algorithmProcessCount= */ 1,
                walkBudgetProvider);
    }

    /**
     * Build a validator stub that always grants budget (returns
     * {@code request.bestModeScore + 1.0}) so budget validation never rejects.
     */
    static BudgetValidator buildPassingValidator(ExMasConfigGroup exMasConfig) {
        return new BudgetValidator(null, exMasConfig, 1.34) {
            @Override
            public double calculateDrtScoreWithWalks(DrtRequest request, double delay,
                    double actualTravelTime, double actualDistance,
                    double accessWalkDist, double egressWalkDist) {
                return request.bestModeScore + 1.0;
            }
        };
    }

    // =========================================================================
    // CapturingStopFinder
    // =========================================================================

    /**
     * {@link StopFinder} stub that records every {@link #findStop} call.
     *
     * <ul>
     *   <li>Call 1 (Phase A): returns link {@code "stop"} at coord (500, 0).
     *   <li>Call 2+ (Phase B): returns link {@code "dropoff"} at coord (1500, 0).
     * </ul>
     */
    static class CapturingStopFinder implements StopFinder {
        final List<CapturedCall> calls = new ArrayList<>();
        private int callCount = 0;

        @Override
        public Optional<StopLocation> findStop(
                List<Coord> passengerLocations,
                double[] maxWalkDistances,
                double departureTime) {
            calls.add(new CapturedCall(
                    new ArrayList<>(passengerLocations),
                    Arrays.copyOf(maxWalkDistances, maxWalkDistances.length),
                    departureTime));
            callCount++;

            if (callCount == 1) {
                return Optional.of(new StopLocation(
                        Id.createLinkId("stop"),
                        new Coord(500.0, 0.0),
                        0.0));
            } else {
                return Optional.of(new StopLocation(
                        Id.createLinkId("dropoff"),
                        new Coord(1500.0, 0.0),
                        0.0));
            }
        }

        @Override
        public String getName() { return "Capturing"; }

        static class CapturedCall {
            final List<Coord> locations;
            final double[] caps;
            final double time;

            CapturedCall(List<Coord> locations, double[] caps, double time) {
                this.locations = locations;
                this.caps = caps;
                this.time = time;
            }
        }
    }
}
