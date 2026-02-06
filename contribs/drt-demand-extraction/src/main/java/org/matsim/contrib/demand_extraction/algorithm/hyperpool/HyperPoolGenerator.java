package org.matsim.contrib.demand_extraction.algorithm.hyperpool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.demand_extraction.algorithm.domain.HyperPooledRide;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideVariant;
import org.matsim.contrib.demand_extraction.algorithm.domain.StopLocation;
import org.matsim.contrib.demand_extraction.algorithm.domain.StopSequence;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

/**
 * Main orchestrator for generating hyper-pooled rides from stop-to-stop rides.
 *
 * <p>HyperPool Stage 2 bundles multiple stop-to-stop rides from Stage 1 together
 * into single vehicle routes that visit multiple pickup and dropoff stops in sequence.
 * This creates higher-occupancy rides by allowing nearby stops to be served by the
 * same vehicle.
 *
 * <p>Key features:
 * <ul>
 *   <li><b>Cluster Finding:</b> Groups compatible stop-to-stop rides using a greedy algorithm
 *       that starts with highest-degree nodes and adds compatible neighbors</li>
 *   <li><b>Stop Sequence Generation:</b> Orders stops for each cluster (pickups first, then dropoffs)</li>
 *   <li><b>Route Calculation:</b> Routes through stop sequences using MatsimNetworkCache</li>
 *   <li><b>Passenger Metrics:</b> Calculates walk distances, in-vehicle times, and delays per passenger</li>
 *   <li><b>Statistics:</b> Logs cluster success/failure rates, average occupancy, and VKT reduction</li>
 * </ul>
 *
 * <p>The generator respects configuration parameters from {@link ExMasConfigGroup}:
 * <ul>
 *   <li>{@code hyperPoolMinOccupancy}: Minimum passengers required for a hyper-pooled ride</li>
 *   <li>{@code hyperPoolMaxStops}: Maximum stops in a single stop sequence</li>
 *   <li>{@code hyperPoolTimeWindowSeconds}: Time window for compatible rides</li>
 *   <li>{@code hyperPoolStopProximityMeters}: Distance threshold for considering stops as "same"</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * HyperPoolGenerator generator = new HyperPoolGenerator(
 *     networkCache, stopRelocator, compatibilityChecker, config, budgetValidator);
 *
 * List<Ride> stopToStopRides = ...; // From Stage 1
 * List<HyperPooledRide> hyperPooledRides = generator.generate(stopToStopRides, networkCache, 0);
 * generator.logStatistics();
 * }</pre>
 *
 * @see HyperPooledRide
 * @see StopToStopRideWrapper
 * @see StopSequence
 */
public class HyperPoolGenerator {

    private static final Logger log = LogManager.getLogger(HyperPoolGenerator.class);

    private final MatsimNetworkCache networkCache;
    private final StopRelocator stopRelocator;
    private final StopCompatibilityChecker compatibilityChecker;
    private final ExMasConfigGroup config;
    private final BudgetValidator budgetValidator;

    // Configuration parameters (cached for efficiency)
    private final int minOccupancy;
    private final int maxStops;
    private final double timeWindowSeconds;
    private final double stopProximityMeters;
    private final double walkSpeed;

    // Statistics
    private int clustersAttempted = 0;
    private int clustersSucceeded = 0;
    private int clustersFailed = 0;
    private int totalHyperPooledRides = 0;
    private int totalPassengersHyperPooled = 0;
    private double totalVktHyperPooled = 0.0;
    private double totalVktOriginalS2S = 0.0;

    /**
     * Creates a new HyperPoolGenerator.
     *
     * @param networkCache the MATSim network cache for routing
     * @param stopRelocator the stop relocator for merging nearby stops
     * @param compatibilityChecker the checker for stop-to-stop ride compatibility
     * @param config the ExMAS configuration with hyper-pooling parameters
     * @param budgetValidator the budget validator for validation phase
     */
    public HyperPoolGenerator(
            MatsimNetworkCache networkCache,
            StopRelocator stopRelocator,
            StopCompatibilityChecker compatibilityChecker,
            ExMasConfigGroup config,
            BudgetValidator budgetValidator) {

        if (networkCache == null) {
            throw new IllegalArgumentException("networkCache cannot be null");
        }
        if (config == null) {
            throw new IllegalArgumentException("config cannot be null");
        }

        this.networkCache = networkCache;
        this.stopRelocator = stopRelocator;
        this.compatibilityChecker = compatibilityChecker;
        this.config = config;
        this.budgetValidator = budgetValidator;

        // Cache configuration parameters
        this.minOccupancy = config.getHyperPoolMinOccupancy();
        this.maxStops = config.getHyperPoolMaxStops();
        this.timeWindowSeconds = config.getHyperPoolTimeWindowSeconds();
        this.stopProximityMeters = config.getHyperPoolStopProximityMeters();
        this.walkSpeed = config.getWalkSpeedMps();

        // Log configuration
        if (maxStops > 0) {
            log.info("HyperPool: Max stops constraint = {} (optimization, not in original)", maxStops);
        } else {
            log.info("HyperPool: Max stops unlimited (matches original ExMAS/HyperPool)");
        }
    }

    // ==================== Main Generation Method ====================

    /**
     * Generates hyper-pooled rides from a list of stop-to-stop rides.
     *
     * <p>The algorithm:
     * <ol>
     *   <li>Wraps stop-to-stop rides into StopToStopRideWrapper objects</li>
     *   <li>Builds a shareability graph of compatible rides</li>
     *   <li>Finds clusters of compatible rides using a greedy algorithm</li>
     *   <li>For each cluster, generates optimal stop sequence and route</li>
     *   <li>Calculates passenger metrics and validates against budgets</li>
     *   <li>Returns valid hyper-pooled rides</li>
     * </ol>
     *
     * @param stopToStopRides the list of stop-to-stop rides from Stage 1
     * @param networkCache the network cache for routing (same as constructor param, for API compatibility)
     * @param startIndex the starting index for hyper-pooled ride numbering
     * @return list of hyper-pooled rides
     */
    public List<HyperPooledRide> generate(
            List<Ride> stopToStopRides,
            MatsimNetworkCache networkCache,
            int startIndex) {

        if (stopToStopRides == null || stopToStopRides.isEmpty()) {
            log.info("No stop-to-stop rides to process for hyper-pooling");
            return Collections.emptyList();
        }

        // Reset statistics for this run
        resetStatistics();

        log.info("Starting hyper-pool generation for {} stop-to-stop rides", stopToStopRides.size());

        // Step 1: Filter and wrap stop-to-stop rides
        List<StopToStopRideWrapper> wrappers = wrapStopToStopRides(stopToStopRides);
        if (wrappers.isEmpty()) {
            log.info("No valid stop-to-stop rides after filtering");
            return Collections.emptyList();
        }
        log.info("Wrapped {} stop-to-stop rides", wrappers.size());

        // Calculate original S2S VKT for comparison
        for (StopToStopRideWrapper wrapper : wrappers) {
            totalVktOriginalS2S += wrapper.getRideDistance() / 1000.0;
        }

        // Step 2: Build shareability graph
        HyperPoolShareabilityGraph graph = buildShareabilityGraph(wrappers);
        log.info("Built shareability graph with {} edges", graph.getEdgeCount());

        // Step 3: Find clusters
        List<Set<StopToStopRideWrapper>> clusters = findClusters(graph);
        log.info("Found {} potential clusters", clusters.size());

        // Step 4: Generate hyper-pooled rides from clusters
        List<HyperPooledRide> hyperPooledRides = new ArrayList<>();
        int currentIndex = startIndex;

        for (Set<StopToStopRideWrapper> cluster : clusters) {
            clustersAttempted++;

            try {
                HyperPooledRide ride = generateHyperPooledRide(cluster, currentIndex);
                if (ride != null) {
                    hyperPooledRides.add(ride);
                    currentIndex++;
                    clustersSucceeded++;

                    // Update statistics
                    totalHyperPooledRides++;
                    totalPassengersHyperPooled += ride.getTotalPassengerCount();
                    totalVktHyperPooled += ride.getTotalVehicleKilometers();
                }
            } catch (Exception e) {
                clustersFailed++;
                log.warn("Failed to generate hyper-pooled ride from cluster: {}", e.getMessage());
            }
        }

        log.info("Generated {} hyper-pooled rides from {} clusters", hyperPooledRides.size(), clusters.size());
        return hyperPooledRides;
    }

    // ==================== Cluster Finding ====================

    /**
     * Finds groups of compatible stop-to-stop rides that can be bundled.
     *
     * <p>Uses a greedy algorithm:
     * <ol>
     *   <li>Sort nodes by degree (most connections first)</li>
     *   <li>Start with highest-degree unassigned node</li>
     *   <li>Add compatible neighbors that maintain constraints</li>
     *   <li>Repeat until all nodes assigned or constraints prevent further clustering</li>
     * </ol>
     *
     * <p>Respects configuration constraints:
     * <ul>
     *   <li>{@code hyperPoolMinOccupancy}: Minimum total passengers in cluster</li>
     *   <li>{@code hyperPoolMaxStops}: Maximum unique stops in cluster</li>
     * </ul>
     *
     * @param graph the shareability graph
     * @return list of clusters (sets of compatible rides)
     */
    public List<Set<StopToStopRideWrapper>> findClusters(HyperPoolShareabilityGraph graph) {
        List<Set<StopToStopRideWrapper>> clusters = new ArrayList<>();

        // Track which wrappers have been assigned to a cluster
        Set<StopToStopRideWrapper> assigned = new HashSet<>();

        // Get all nodes sorted by degree (descending)
        List<StopToStopRideWrapper> nodesByDegree = graph.getNodesByDegree();

        for (StopToStopRideWrapper seed : nodesByDegree) {
            if (assigned.contains(seed)) {
                continue;
            }

            // Start a new cluster with this seed
            Set<StopToStopRideWrapper> cluster = new LinkedHashSet<>();
            cluster.add(seed);
            assigned.add(seed);

            // Try to add compatible neighbors
            List<StopToStopRideWrapper> neighbors = graph.getNeighbors(seed);

            // Sort neighbors by degree (prefer high-degree nodes)
            neighbors.sort(Comparator.comparingInt(graph::getDegree).reversed());

            for (StopToStopRideWrapper neighbor : neighbors) {
                if (assigned.contains(neighbor)) {
                    continue;
                }

                // Check if adding this neighbor maintains constraints
                if (canAddToCluster(cluster, neighbor, graph)) {
                    cluster.add(neighbor);
                    assigned.add(neighbor);
                }
            }

            // Only keep clusters that meet minimum occupancy
            int totalPassengers = calculateClusterOccupancy(cluster);
            if (totalPassengers >= minOccupancy) {
                clusters.add(cluster);
            } else {
                // Release wrappers back to unassigned pool for potential reassignment
                assigned.removeAll(cluster);
            }
        }

        return clusters;
    }

    /**
     * Checks if a wrapper can be added to an existing cluster while maintaining constraints.
     */
    private boolean canAddToCluster(
            Set<StopToStopRideWrapper> cluster,
            StopToStopRideWrapper candidate,
            HyperPoolShareabilityGraph graph) {

        // Check compatibility with all existing cluster members
        for (StopToStopRideWrapper member : cluster) {
            if (!graph.hasEdge(member, candidate)) {
                return false;
            }
        }

        // Check if adding would exceed max stops
        Set<StopLocation> uniqueStops = new HashSet<>();
        for (StopToStopRideWrapper member : cluster) {
            uniqueStops.add(member.getPickupStop());
            uniqueStops.add(member.getDropoffStop());
        }
        uniqueStops.add(candidate.getPickupStop());
        uniqueStops.add(candidate.getDropoffStop());

        // Merge nearby stops for counting
        int effectiveStopCount = countEffectiveStops(uniqueStops);
        if (maxStops > 0 && effectiveStopCount > maxStops) {
            return false;
        }

        return true;
    }

    /**
     * Counts effective number of stops after merging nearby ones.
     */
    private int countEffectiveStops(Set<StopLocation> stops) {
        if (stopRelocator == null) {
            return stops.size();
        }

        // Use stop relocator to merge nearby stops
        Set<StopLocation> merged = new HashSet<>();
        for (StopLocation stop : stops) {
            boolean foundNearby = false;
            for (StopLocation existing : merged) {
                if (stopRelocator.areStopsNearby(stop, existing, stopProximityMeters)) {
                    foundNearby = true;
                    break;
                }
            }
            if (!foundNearby) {
                merged.add(stop);
            }
        }
        return merged.size();
    }

    /**
     * Calculates total passenger count for a cluster.
     */
    private int calculateClusterOccupancy(Set<StopToStopRideWrapper> cluster) {
        int total = 0;
        for (StopToStopRideWrapper wrapper : cluster) {
            total += wrapper.getPassengerCount();
        }
        return total;
    }

    // ==================== Stop Sequence Generation ====================

    /**
     * Generates optimal stop sequence for a cluster of rides.
     *
     * <p>Orders stops using a simple strategy:
     * <ol>
     *   <li>Collect all unique pickup stops</li>
     *   <li>Collect all unique dropoff stops</li>
     *   <li>Order pickups by departure time (earliest first)</li>
     *   <li>Order dropoffs to minimize total route distance (or FIFO)</li>
     *   <li>Result: all pickups, then all dropoffs</li>
     * </ol>
     *
     * @param cluster the cluster of compatible rides
     * @param relocator the stop relocator for merging nearby stops
     * @return the optimal stop sequence, or null if generation fails
     */
    public StopSequence generateStopSequence(
            Set<StopToStopRideWrapper> cluster,
            StopRelocator relocator) {

        if (cluster == null || cluster.isEmpty()) {
            return null;
        }

        // Collect and deduplicate stops
        List<StopLocation> pickupStops = new ArrayList<>();
        List<StopLocation> dropoffStops = new ArrayList<>();
        Map<StopLocation, List<StopToStopRideWrapper>> pickupStopToRides = new HashMap<>();
        Map<StopLocation, List<StopToStopRideWrapper>> dropoffStopToRides = new HashMap<>();

        for (StopToStopRideWrapper wrapper : cluster) {
            StopLocation pickup = wrapper.getPickupStop();
            StopLocation dropoff = wrapper.getDropoffStop();

            // Merge nearby stops if relocator available
            if (relocator != null) {
                pickup = relocator.findMergedStop(pickup, pickupStops, stopProximityMeters);
                dropoff = relocator.findMergedStop(dropoff, dropoffStops, stopProximityMeters);
            }

            if (!pickupStops.contains(pickup)) {
                pickupStops.add(pickup);
                pickupStopToRides.put(pickup, new ArrayList<>());
            }
            pickupStopToRides.get(pickup).add(wrapper);

            if (!dropoffStops.contains(dropoff)) {
                dropoffStops.add(dropoff);
                dropoffStopToRides.put(dropoff, new ArrayList<>());
            }
            dropoffStopToRides.get(dropoff).add(wrapper);
        }

        // Sort pickup stops by earliest departure time among rides using that stop
        pickupStops.sort(Comparator.comparingDouble(stop ->
            pickupStopToRides.get(stop).stream()
                .mapToDouble(StopToStopRideWrapper::getDepartureTime)
                .min().orElse(Double.MAX_VALUE)));

        // Sort dropoff stops using FIFO or optimized ordering
        // FIFO: order by the order of pickup stops for corresponding rides
        dropoffStops.sort(Comparator.comparingInt(stop -> {
            // Find earliest pickup position among rides using this dropoff
            return pickupStopToRides.values().stream()
                .flatMap(List::stream)
                .filter(w -> w.getDropoffStop().equals(stop) ||
                        (relocator != null && relocator.areStopsNearby(w.getDropoffStop(), stop, stopProximityMeters)))
                .mapToInt(w -> pickupStops.indexOf(
                    relocator != null ?
                        relocator.findMergedStop(w.getPickupStop(), pickupStops, stopProximityMeters) :
                        w.getPickupStop()))
                .min().orElse(Integer.MAX_VALUE);
        }));

        // Build stop sequence: pickups first, then dropoffs
        StopSequence.Builder builder = StopSequence.builder();

        // Add pickup stops
        for (StopLocation stop : pickupStops) {
            builder.addStop(stop);
        }

        // Add dropoff stops
        for (StopLocation stop : dropoffStops) {
            builder.addStop(stop);
        }

        // Map passengers to boarding/alighting indices
        int passengerIndex = 0;
        for (StopToStopRideWrapper wrapper : cluster) {
            StopLocation pickup = wrapper.getPickupStop();
            StopLocation dropoff = wrapper.getDropoffStop();

            // Find merged stops if relocator available
            if (relocator != null) {
                pickup = relocator.findMergedStop(pickup, pickupStops, stopProximityMeters);
                dropoff = relocator.findMergedStop(dropoff, dropoffStops, stopProximityMeters);
            }

            int boardingIndex = pickupStops.indexOf(pickup);
            int alightingIndex = pickupStops.size() + dropoffStops.indexOf(dropoff);

            // Handle all passengers in this S2S ride
            for (int i = 0; i < wrapper.getPassengerCount(); i++) {
                builder.setPassengerStops(passengerIndex, boardingIndex, alightingIndex);
                passengerIndex++;
            }
        }

        try {
            return builder.build();
        } catch (IllegalStateException e) {
            log.warn("Failed to build stop sequence: {}", e.getMessage());
            return null;
        }
    }

    // ==================== Route Calculation ====================

    /**
     * Calculates route metrics through a stop sequence.
     *
     * <p>Routes between consecutive stops using MatsimNetworkCache and sums
     * travel times and distances.
     *
     * @param sequence the stop sequence
     * @param networkCache the network cache for routing
     * @param startTime the departure time from first stop
     * @return array with [totalTravelTime, totalDistance] in seconds and meters
     */
    public double[] calculateRouteMetrics(
            StopSequence sequence,
            MatsimNetworkCache networkCache,
            double startTime) {

        if (sequence == null || sequence.getStopCount() < 2) {
            return new double[]{0.0, 0.0};
        }

        double totalTravelTime = 0.0;
        double totalDistance = 0.0;
        double currentTime = startTime;

        List<StopLocation> stops = sequence.getStops();

        for (int i = 0; i < stops.size() - 1; i++) {
            StopLocation fromStop = stops.get(i);
            StopLocation toStop = stops.get(i + 1);

            TravelSegment segment = networkCache.getSegment(
                fromStop.getLinkId(),
                toStop.getLinkId(),
                currentTime);

            if (!segment.isReachable()) {
                log.warn("No route between stops {} and {}", fromStop.getLinkId(), toStop.getLinkId());
                return new double[]{Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
            }

            totalTravelTime += segment.getTravelTime();
            totalDistance += segment.getDistance();
            currentTime += segment.getTravelTime();
        }

        return new double[]{totalTravelTime, totalDistance};
    }

    // ==================== Passenger Metrics ====================

    /**
     * Container for individual passenger metrics in a hyper-pooled ride.
     */
    public static class PassengerMetrics {
        public final double accessWalkDistance;
        public final double egressWalkDistance;
        public final double totalWalkDistance;
        public final double inVehicleTime;
        public final double totalTravelTime;
        public final double delayVsDirect;
        public final double remainingBudget;

        public PassengerMetrics(
                double accessWalkDistance,
                double egressWalkDistance,
                double inVehicleTime,
                double totalTravelTime,
                double delayVsDirect,
                double remainingBudget) {
            this.accessWalkDistance = accessWalkDistance;
            this.egressWalkDistance = egressWalkDistance;
            this.totalWalkDistance = accessWalkDistance + egressWalkDistance;
            this.inVehicleTime = inVehicleTime;
            this.totalTravelTime = totalTravelTime;
            this.delayVsDirect = delayVsDirect;
            this.remainingBudget = remainingBudget;
        }
    }

    /**
     * Calculates metrics for each passenger in a hyper-pooled ride.
     *
     * <p>For each passenger calculates:
     * <ul>
     *   <li>Access walk distance (origin to boarding stop)</li>
     *   <li>Egress walk distance (alighting stop to destination)</li>
     *   <li>In-vehicle time (boarding to alighting)</li>
     *   <li>Total delay vs direct travel</li>
     * </ul>
     *
     * @param ride the hyper-pooled ride
     * @return array of passenger metrics, one per passenger
     */
    public PassengerMetrics[] calculatePassengerMetrics(HyperPooledRide ride) {
        int passengerCount = ride.getDegree();
        PassengerMetrics[] metrics = new PassengerMetrics[passengerCount];

        for (int i = 0; i < passengerCount; i++) {
            DrtRequest request = ride.getRequest(i);

            double accessWalk = ride.getAccessWalkDistance(i);
            double egressWalk = ride.getEgressWalkDistance(i);
            double inVehicle = ride.getInVehicleTime(i);

            // Calculate total travel time including walks
            double walkTime = (accessWalk + egressWalk) / walkSpeed;
            double totalTravelTime = walkTime + inVehicle;

            // Calculate delay vs direct travel
            double directTime = request.directTravelTime;
            double delayVsDirect = totalTravelTime - directTime;

            // Get remaining budget from ride
            double remainingBudget = ride.getRemainingBudget(i);

            metrics[i] = new PassengerMetrics(
                accessWalk, egressWalk, inVehicle, totalTravelTime, delayVsDirect, remainingBudget);
        }

        return metrics;
    }

    // ==================== Internal Methods ====================

    /**
     * Wraps stop-to-stop rides, filtering out invalid ones.
     */
    private List<StopToStopRideWrapper> wrapStopToStopRides(List<Ride> rides) {
        List<StopToStopRideWrapper> wrappers = new ArrayList<>();

        for (Ride ride : rides) {
            // Only wrap STOP_TO_STOP variant rides
            if (ride.getVariant() != RideVariant.STOP_TO_STOP) {
                continue;
            }

            try {
                StopToStopRideWrapper wrapper = new StopToStopRideWrapper(ride);
                wrappers.add(wrapper);
            } catch (IllegalArgumentException e) {
                log.debug("Skipping invalid stop-to-stop ride {}: {}", ride.getIndex(), e.getMessage());
            }
        }

        return wrappers;
    }

    /**
     * Builds the shareability graph for stop-to-stop rides.
     */
    private HyperPoolShareabilityGraph buildShareabilityGraph(List<StopToStopRideWrapper> wrappers) {
        HyperPoolShareabilityGraph graph = new HyperPoolShareabilityGraph();

        // Add all wrappers as nodes
        for (StopToStopRideWrapper wrapper : wrappers) {
            graph.addNode(wrapper);
        }

        // Check all pairs for compatibility
        for (int i = 0; i < wrappers.size(); i++) {
            for (int j = i + 1; j < wrappers.size(); j++) {
                StopToStopRideWrapper w1 = wrappers.get(i);
                StopToStopRideWrapper w2 = wrappers.get(j);

                if (areRidesCompatible(w1, w2)) {
                    graph.addEdge(w1, w2);
                }
            }
        }

        return graph;
    }

    /**
     * Checks if two stop-to-stop rides are compatible for hyper-pooling.
     */
    private boolean areRidesCompatible(StopToStopRideWrapper w1, StopToStopRideWrapper w2) {
        // Use compatibility checker if available
        if (compatibilityChecker != null) {
            return compatibilityChecker.areCompatible(w1, w2);
        }

        // Default compatibility check: time window
        double timeDiff = Math.abs(w1.getDepartureTime() - w2.getDepartureTime());
        return timeDiff <= timeWindowSeconds;
    }

    /**
     * Generates a hyper-pooled ride from a cluster.
     */
    private HyperPooledRide generateHyperPooledRide(
            Set<StopToStopRideWrapper> cluster,
            int index) {

        // Generate stop sequence
        StopSequence sequence = generateStopSequence(cluster, stopRelocator);
        if (sequence == null) {
            return null;
        }

        // Determine start time (earliest departure among cluster rides)
        double startTime = cluster.stream()
            .mapToDouble(StopToStopRideWrapper::getDepartureTime)
            .min().orElse(0.0);

        // Calculate route metrics
        double[] routeMetrics = calculateRouteMetrics(sequence, networkCache, startTime);
        double totalTravelTime = routeMetrics[0];
        double totalDistance = routeMetrics[1];

        if (Double.isInfinite(totalTravelTime)) {
            log.warn("Could not route through stop sequence for cluster");
            return null;
        }

        // Collect all requests and build passenger arrays
        List<DrtRequest> allRequests = new ArrayList<>();
        List<Ride> sourceRides = new ArrayList<>();

        for (StopToStopRideWrapper wrapper : cluster) {
            sourceRides.add(wrapper.getRide());
            DrtRequest[] rideRequests = wrapper.getRide().getRequests();
            for (DrtRequest req : rideRequests) {
                allRequests.add(req);
            }
        }

        int passengerCount = allRequests.size();

        // Build passenger arrays
        DrtRequest[] requests = allRequests.toArray(new DrtRequest[0]);
        StopLocation[] stopSequenceArray = sequence.getStops().toArray(new StopLocation[0]);
        int[] boardingIndices = new int[passengerCount];
        int[] alightingIndices = new int[passengerCount];
        double[] accessWalkDistances = new double[passengerCount];
        double[] egressWalkDistances = new double[passengerCount];
        double[] inVehicleTimes = new double[passengerCount];
        double[] remainingBudgets = new double[passengerCount];

        // Calculate per-passenger metrics
        int passengerIdx = 0;
        for (StopToStopRideWrapper wrapper : cluster) {
            Ride sourceRide = wrapper.getRide();
            double[] sourceAccessWalks = sourceRide.getAccessWalkDistances();
            double[] sourceEgressWalks = sourceRide.getEgressWalkDistances();

            for (int i = 0; i < wrapper.getPassengerCount(); i++) {
                boardingIndices[passengerIdx] = sequence.getBoardingIndex(passengerIdx);
                alightingIndices[passengerIdx] = sequence.getAlightingIndex(passengerIdx);

                // Use walk distances from source ride, plus any additional relocation walk
                accessWalkDistances[passengerIdx] = sourceAccessWalks != null ? sourceAccessWalks[i] : 0.0;
                egressWalkDistances[passengerIdx] = sourceEgressWalks != null ? sourceEgressWalks[i] : 0.0;

                // Calculate in-vehicle time from boarding to alighting
                int boardIdx = boardingIndices[passengerIdx];
                int alightIdx = alightingIndices[passengerIdx];
                double ivt = calculateInVehicleTime(sequence, boardIdx, alightIdx, networkCache, startTime);
                inVehicleTimes[passengerIdx] = ivt;

                // Budget validation (if validator available)
                if (budgetValidator != null) {
                    DrtRequest request = requests[passengerIdx];
                    double delay = startTime - request.requestTime;
                    double actualTravelTime = ivt + (accessWalkDistances[passengerIdx] + egressWalkDistances[passengerIdx]) / walkSpeed;
                    double score = budgetValidator.calculateDrtScoreWithWalks(
                        request, delay, actualTravelTime, totalDistance / passengerCount,
                        accessWalkDistances[passengerIdx], egressWalkDistances[passengerIdx]);
                    remainingBudgets[passengerIdx] = score - request.bestModeScore;
                } else {
                    remainingBudgets[passengerIdx] = 0.0;
                }

                passengerIdx++;
            }
        }

        // Build the HyperPooledRide
        try {
            return HyperPooledRide.builder()
                .index(index)
                .stopSequence(stopSequenceArray)
                .requests(requests)
                .boardingStopIndices(boardingIndices)
                .alightingStopIndices(alightingIndices)
                .accessWalkDistances(accessWalkDistances)
                .egressWalkDistances(egressWalkDistances)
                .inVehicleTimes(inVehicleTimes)
                .remainingBudgets(remainingBudgets)
                .totalRideTime(totalTravelTime)
                .totalRideDistance(totalDistance)
                .startTime(startTime)
                .endTime(startTime + totalTravelTime)
                .sourceRides(sourceRides)
                .orderedStopSequence(sequence)
                .build();
        } catch (IllegalArgumentException e) {
            log.warn("Failed to build HyperPooledRide: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Calculates in-vehicle time between boarding and alighting stops.
     */
    private double calculateInVehicleTime(
            StopSequence sequence,
            int boardingIndex,
            int alightingIndex,
            MatsimNetworkCache networkCache,
            double baseTime) {

        double ivt = 0.0;
        double currentTime = baseTime;

        // Sum travel times from boarding to alighting
        for (int i = boardingIndex; i < alightingIndex; i++) {
            StopLocation from = sequence.getStop(i);
            StopLocation to = sequence.getStop(i + 1);

            TravelSegment segment = networkCache.getSegment(from.getLinkId(), to.getLinkId(), currentTime);
            if (segment.isReachable()) {
                ivt += segment.getTravelTime();
                currentTime += segment.getTravelTime();
            }
        }

        return ivt;
    }

    // ==================== Statistics ====================

    /**
     * Resets all statistics counters.
     */
    private void resetStatistics() {
        clustersAttempted = 0;
        clustersSucceeded = 0;
        clustersFailed = 0;
        totalHyperPooledRides = 0;
        totalPassengersHyperPooled = 0;
        totalVktHyperPooled = 0.0;
        totalVktOriginalS2S = 0.0;
    }

    /**
     * Logs statistics about hyper-pooling generation.
     */
    public void logStatistics() {
        log.info("=== HyperPool Generation Statistics ===");
        log.info("Clusters attempted: {}", clustersAttempted);
        log.info("Clusters succeeded: {} ({:.1f}%)", clustersSucceeded,
            clustersAttempted > 0 ? 100.0 * clustersSucceeded / clustersAttempted : 0.0);
        log.info("Clusters failed: {}", clustersFailed);
        log.info("Total hyper-pooled rides: {}", totalHyperPooledRides);
        log.info("Total passengers hyper-pooled: {}", totalPassengersHyperPooled);

        if (totalHyperPooledRides > 0) {
            double avgOccupancy = (double) totalPassengersHyperPooled / totalHyperPooledRides;
            log.info("Average occupancy: {:.2f} passengers/ride", avgOccupancy);
        }

        if (totalVktOriginalS2S > 0) {
            double vktReduction = (totalVktOriginalS2S - totalVktHyperPooled) / totalVktOriginalS2S * 100.0;
            log.info("VKT reduction vs individual S2S rides: {:.1f}%", vktReduction);
            log.info("Original S2S VKT: {:.2f} km", totalVktOriginalS2S);
            log.info("HyperPooled VKT: {:.2f} km", totalVktHyperPooled);
        }
    }

    /**
     * Returns the number of clusters that were successfully converted to hyper-pooled rides.
     */
    public int getClustersSucceeded() {
        return clustersSucceeded;
    }

    /**
     * Returns the number of clusters that failed to convert.
     */
    public int getClustersFailed() {
        return clustersFailed;
    }

    /**
     * Returns the total number of hyper-pooled rides generated.
     */
    public int getTotalHyperPooledRides() {
        return totalHyperPooledRides;
    }

    /**
     * Returns the total number of passengers served by hyper-pooled rides.
     */
    public int getTotalPassengersHyperPooled() {
        return totalPassengersHyperPooled;
    }

    // ==================== Helper Interface Stubs ====================
    // These interfaces will be implemented in separate files

    /**
     * Interface for relocating/merging nearby stops.
     *
     * <p>Implementations determine how stops within proximity are merged
     * into single pickup/dropoff locations for hyper-pooling.
     */
    public interface StopRelocator {

        /**
         * Checks if two stops are within proximity threshold.
         *
         * @param stop1 first stop
         * @param stop2 second stop
         * @param proximityMeters distance threshold in meters
         * @return true if stops are within proximity
         */
        boolean areStopsNearby(StopLocation stop1, StopLocation stop2, double proximityMeters);

        /**
         * Finds the merged stop location for a given stop.
         *
         * <p>If a nearby stop already exists in the list, returns that stop.
         * Otherwise returns the original stop.
         *
         * @param stop the stop to merge
         * @param existingStops list of existing stops
         * @param proximityMeters distance threshold for merging
         * @return the merged or original stop
         */
        StopLocation findMergedStop(StopLocation stop, List<StopLocation> existingStops, double proximityMeters);

        /**
         * Calculates the additional walk distance incurred by relocating to a merged stop.
         *
         * @param originalStop the passenger's original stop
         * @param mergedStop the merged stop they will use
         * @return additional walk distance in meters
         */
        double calculateRelocationDistance(StopLocation originalStop, StopLocation mergedStop);
    }

    /**
     * Interface for checking compatibility of stop-to-stop rides for hyper-pooling.
     *
     * <p>Implementations define the criteria for whether two S2S rides can be
     * bundled together in a hyper-pooled ride.
     */
    public interface StopCompatibilityChecker {

        /**
         * Checks if two stop-to-stop rides are compatible for hyper-pooling.
         *
         * <p>Compatibility typically considers:
         * <ul>
         *   <li>Time window overlap</li>
         *   <li>Geographic proximity of stops</li>
         *   <li>Direction alignment</li>
         *   <li>Capacity constraints</li>
         * </ul>
         *
         * @param ride1 first ride
         * @param ride2 second ride
         * @return true if rides can be bundled together
         */
        boolean areCompatible(StopToStopRideWrapper ride1, StopToStopRideWrapper ride2);
    }

    /**
     * Graph structure for hyper-pool shareability.
     *
     * <p>Nodes are stop-to-stop ride wrappers. Edges connect compatible rides
     * that can potentially be bundled together in a hyper-pooled ride.
     */
    public static class HyperPoolShareabilityGraph {
        private final Map<StopToStopRideWrapper, Set<StopToStopRideWrapper>> adjacencyList = new HashMap<>();

        /**
         * Adds a node (ride wrapper) to the graph.
         */
        public void addNode(StopToStopRideWrapper wrapper) {
            adjacencyList.putIfAbsent(wrapper, new HashSet<>());
        }

        /**
         * Adds an edge between two compatible ride wrappers.
         */
        public void addEdge(StopToStopRideWrapper w1, StopToStopRideWrapper w2) {
            adjacencyList.computeIfAbsent(w1, k -> new HashSet<>()).add(w2);
            adjacencyList.computeIfAbsent(w2, k -> new HashSet<>()).add(w1);
        }

        /**
         * Checks if an edge exists between two wrappers.
         */
        public boolean hasEdge(StopToStopRideWrapper w1, StopToStopRideWrapper w2) {
            Set<StopToStopRideWrapper> neighbors = adjacencyList.get(w1);
            return neighbors != null && neighbors.contains(w2);
        }

        /**
         * Returns all neighbors of a wrapper.
         */
        public List<StopToStopRideWrapper> getNeighbors(StopToStopRideWrapper wrapper) {
            Set<StopToStopRideWrapper> neighbors = adjacencyList.get(wrapper);
            return neighbors != null ? new ArrayList<>(neighbors) : Collections.emptyList();
        }

        /**
         * Returns the degree (number of edges) of a wrapper.
         */
        public int getDegree(StopToStopRideWrapper wrapper) {
            Set<StopToStopRideWrapper> neighbors = adjacencyList.get(wrapper);
            return neighbors != null ? neighbors.size() : 0;
        }

        /**
         * Returns all nodes sorted by degree (descending).
         */
        public List<StopToStopRideWrapper> getNodesByDegree() {
            return adjacencyList.keySet().stream()
                .sorted(Comparator.comparingInt(this::getDegree).reversed())
                .collect(Collectors.toList());
        }

        /**
         * Returns the total number of edges in the graph.
         */
        public int getEdgeCount() {
            int count = 0;
            for (Set<StopToStopRideWrapper> neighbors : adjacencyList.values()) {
                count += neighbors.size();
            }
            return count / 2; // Each edge counted twice
        }

        /**
         * Returns the number of nodes in the graph.
         */
        public int getNodeCount() {
            return adjacencyList.size();
        }
    }
}
