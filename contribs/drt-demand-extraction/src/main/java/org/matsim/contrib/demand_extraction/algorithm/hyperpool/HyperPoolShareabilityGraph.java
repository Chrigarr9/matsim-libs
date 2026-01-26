package org.matsim.contrib.demand_extraction.algorithm.hyperpool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.matsim.api.core.v01.Coord;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideVariant;

/**
 * Shareability graph for hyper-pooling where nodes represent stop-to-stop rides
 * and edges represent compatible pairs of rides that can be bundled together.
 *
 * <p>This graph is the core data structure for HyperPool Stage 2, which bundles
 * multiple stop-to-stop rides into high-occupancy hyper-pooled rides resembling
 * public transit.
 *
 * <h2>Graph Structure</h2>
 * <ul>
 *   <li><b>Nodes</b>: Stop-to-stop rides wrapped as {@link StopToStopRideWrapper}</li>
 *   <li><b>Edges</b>: Undirected edges between compatible ride pairs</li>
 *   <li><b>Edge Scores</b>: Optional compatibility scores for edge pairs</li>
 * </ul>
 *
 * <h2>Indexing</h2>
 * <ul>
 *   <li><b>Spatial Index</b>: Grid-based index for fast pickup stop proximity queries</li>
 *   <li><b>Temporal Index</b>: Time-binned index for fast time window queries</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Build the graph from stop-to-stop rides
 * List<Ride> s2sRides = ...;
 * StopCompatibilityChecker checker = ...;
 * HyperPoolShareabilityGraph graph = HyperPoolShareabilityGraph.build(s2sRides, checker);
 *
 * // Query compatible rides
 * Set<Integer> compatible = graph.getCompatibleRides(rideIndex);
 *
 * // Find common neighbors for bundling
 * Set<Integer> common = graph.getCommonNeighbors(Set.of(0, 1, 2));
 * }</pre>
 *
 * @see StopToStopRideWrapper
 * @see StopCompatibilityChecker
 */
public final class HyperPoolShareabilityGraph {

    // ==================== Graph Storage ====================

    /** Nodes: list of stop-to-stop ride wrappers, indexed by position */
    private final List<StopToStopRideWrapper> nodes;

    /** Adjacency list: rideIndex -> set of compatible ride indices */
    private final Map<Integer, Set<Integer>> adjacencyList;

    /** Optional edge compatibility scores: EdgeKey -> score */
    private final Map<EdgeKey, Double> edgeScores;

    // ==================== Spatial Index ====================

    /** Grid cell size for spatial indexing (meters) */
    private static final double GRID_CELL_SIZE = 500.0;

    /** Spatial grid index: gridKey -> list of ride indices with pickups in that cell */
    private final Map<Long, List<Integer>> spatialGrid;

    // ==================== Temporal Index ====================

    /** Time bin size for temporal indexing (seconds) */
    private static final double TIME_BIN_SIZE = 300.0; // 5 minutes

    /** Temporal index: timeBin -> list of ride indices departing in that bin */
    private final Map<Integer, List<Integer>> temporalIndex;

    // ==================== Statistics ====================

    private final int nodeCount;
    private final int edgeCount;

    // ==================== Constructor ====================

    /**
     * Private constructor - use {@link #build} factory method.
     */
    private HyperPoolShareabilityGraph(
            List<StopToStopRideWrapper> nodes,
            Map<Integer, Set<Integer>> adjacencyList,
            Map<EdgeKey, Double> edgeScores,
            Map<Long, List<Integer>> spatialGrid,
            Map<Integer, List<Integer>> temporalIndex,
            int edgeCount) {
        this.nodes = Collections.unmodifiableList(new ArrayList<>(nodes));
        this.adjacencyList = Collections.unmodifiableMap(new HashMap<>(adjacencyList));
        this.edgeScores = edgeScores != null
            ? Collections.unmodifiableMap(new HashMap<>(edgeScores))
            : Collections.emptyMap();
        this.spatialGrid = Collections.unmodifiableMap(new HashMap<>(spatialGrid));
        this.temporalIndex = Collections.unmodifiableMap(new HashMap<>(temporalIndex));
        this.nodeCount = nodes.size();
        this.edgeCount = edgeCount;
    }

    // ==================== Factory Methods ====================

    /**
     * Builds a HyperPoolShareabilityGraph from a list of rides.
     *
     * <p>Only rides with variant == STOP_TO_STOP are included. For each pair of
     * rides, compatibility is checked using the provided checker, and an edge
     * is added if they are compatible.
     *
     * <p>This method supports parallel construction for large ride sets.
     *
     * @param allRides list of all rides (will filter for STOP_TO_STOP variant)
     * @param checker compatibility checker for determining if rides can be bundled
     * @return a new HyperPoolShareabilityGraph
     * @throws NullPointerException if allRides or checker is null
     */
    public static HyperPoolShareabilityGraph build(
            List<Ride> allRides,
            StopCompatibilityChecker checker) {
        return build(allRides, checker, true);
    }

    /**
     * Builds a HyperPoolShareabilityGraph from a list of rides.
     *
     * @param allRides list of all rides (will filter for STOP_TO_STOP variant)
     * @param checker compatibility checker for determining if rides can be bundled
     * @param parallel whether to use parallel processing
     * @return a new HyperPoolShareabilityGraph
     * @throws NullPointerException if allRides or checker is null
     */
    public static HyperPoolShareabilityGraph build(
            List<Ride> allRides,
            StopCompatibilityChecker checker,
            boolean parallel) {
        Objects.requireNonNull(allRides, "allRides cannot be null");
        Objects.requireNonNull(checker, "checker cannot be null");

        // Filter for STOP_TO_STOP rides and wrap them
        List<StopToStopRideWrapper> nodes = allRides.stream()
            .filter(ride -> ride.getVariant() == RideVariant.STOP_TO_STOP)
            .map(StopToStopRideWrapper::new)
            .collect(Collectors.toList());

        if (nodes.isEmpty()) {
            return emptyGraph();
        }

        // Build spatial and temporal indices
        Map<Long, List<Integer>> spatialGrid = buildSpatialGrid(nodes);
        Map<Integer, List<Integer>> temporalIndex = buildTemporalIndex(nodes);

        // Build adjacency list and edge scores
        Map<Integer, Set<Integer>> adjacencyList;
        Map<EdgeKey, Double> edgeScores;
        int edgeCount;

        if (parallel && nodes.size() > 100) {
            // Parallel construction for large graphs
            ConcurrentHashMap<Integer, Set<Integer>> concurrentAdjList = new ConcurrentHashMap<>();
            ConcurrentHashMap<EdgeKey, Double> concurrentEdgeScores = new ConcurrentHashMap<>();

            IntStream.range(0, nodes.size()).parallel().forEach(i -> {
                StopToStopRideWrapper ride1 = nodes.get(i);
                Set<Integer> neighbors = ConcurrentHashMap.newKeySet();

                for (int j = i + 1; j < nodes.size(); j++) {
                    StopToStopRideWrapper ride2 = nodes.get(j);
                    double score = checker.checkCompatibility(ride1, ride2);

                    if (score > 0) {
                        neighbors.add(j);

                        // Add reverse edge
                        concurrentAdjList.computeIfAbsent(j, k -> ConcurrentHashMap.newKeySet()).add(i);

                        // Store edge score
                        EdgeKey key = new EdgeKey(i, j);
                        concurrentEdgeScores.put(key, score);
                    }
                }

                if (!neighbors.isEmpty()) {
                    concurrentAdjList.merge(i, neighbors, (existing, newSet) -> {
                        existing.addAll(newSet);
                        return existing;
                    });
                }
            });

            // Convert concurrent collections to regular collections
            adjacencyList = new HashMap<>();
            for (Map.Entry<Integer, Set<Integer>> entry : concurrentAdjList.entrySet()) {
                adjacencyList.put(entry.getKey(), new HashSet<>(entry.getValue()));
            }
            edgeScores = new HashMap<>(concurrentEdgeScores);
            edgeCount = concurrentEdgeScores.size();

        } else {
            // Sequential construction for small graphs
            adjacencyList = new HashMap<>();
            edgeScores = new HashMap<>();
            int edges = 0;

            for (int i = 0; i < nodes.size(); i++) {
                StopToStopRideWrapper ride1 = nodes.get(i);

                for (int j = i + 1; j < nodes.size(); j++) {
                    StopToStopRideWrapper ride2 = nodes.get(j);
                    double score = checker.checkCompatibility(ride1, ride2);

                    if (score > 0) {
                        // Add edges in both directions (undirected graph)
                        adjacencyList.computeIfAbsent(i, k -> new HashSet<>()).add(j);
                        adjacencyList.computeIfAbsent(j, k -> new HashSet<>()).add(i);

                        // Store edge score with canonical key
                        EdgeKey key = new EdgeKey(i, j);
                        edgeScores.put(key, score);
                        edges++;
                    }
                }
            }
            edgeCount = edges;
        }

        return new HyperPoolShareabilityGraph(
            nodes, adjacencyList, edgeScores, spatialGrid, temporalIndex, edgeCount);
    }

    /**
     * Creates an empty graph with no nodes or edges.
     *
     * @return an empty HyperPoolShareabilityGraph
     */
    public static HyperPoolShareabilityGraph emptyGraph() {
        return new HyperPoolShareabilityGraph(
            Collections.emptyList(),
            Collections.emptyMap(),
            Collections.emptyMap(),
            Collections.emptyMap(),
            Collections.emptyMap(),
            0);
    }

    // ==================== Index Building ====================

    /**
     * Builds a spatial grid index based on pickup stop coordinates.
     */
    private static Map<Long, List<Integer>> buildSpatialGrid(List<StopToStopRideWrapper> nodes) {
        Map<Long, List<Integer>> grid = new HashMap<>();

        for (int i = 0; i < nodes.size(); i++) {
            Coord coord = nodes.get(i).getPickupStop().getCoord();
            long gridKey = computeGridKey(coord);
            grid.computeIfAbsent(gridKey, k -> new ArrayList<>()).add(i);
        }

        return grid;
    }

    /**
     * Builds a temporal index based on departure times.
     */
    private static Map<Integer, List<Integer>> buildTemporalIndex(List<StopToStopRideWrapper> nodes) {
        Map<Integer, List<Integer>> index = new HashMap<>();

        for (int i = 0; i < nodes.size(); i++) {
            double departureTime = nodes.get(i).getDepartureTime();
            int timeBin = (int) (departureTime / TIME_BIN_SIZE);
            index.computeIfAbsent(timeBin, k -> new ArrayList<>()).add(i);
        }

        return index;
    }

    /**
     * Computes a grid key from coordinates for spatial indexing.
     */
    private static long computeGridKey(Coord coord) {
        int gridX = (int) (coord.getX() / GRID_CELL_SIZE);
        int gridY = (int) (coord.getY() / GRID_CELL_SIZE);
        return ((long) gridX << 32) | (gridY & 0xFFFFFFFFL);
    }

    // ==================== Node Access ====================

    /**
     * Returns the number of nodes (stop-to-stop rides) in the graph.
     *
     * @return node count
     */
    public int getNodeCount() {
        return nodeCount;
    }

    /**
     * Returns the number of edges (compatible pairs) in the graph.
     *
     * @return edge count
     */
    public int getEdgeCount() {
        return edgeCount;
    }

    /**
     * Returns the average degree (number of compatible rides per ride).
     *
     * @return average degree, or 0 if no nodes
     */
    public double getAverageDegree() {
        if (nodeCount == 0) {
            return 0.0;
        }
        // Each edge contributes to degree of both endpoints
        return (2.0 * edgeCount) / nodeCount;
    }

    /**
     * Returns the node (ride wrapper) at the given index.
     *
     * @param index the node index
     * @return the StopToStopRideWrapper at that index
     * @throws IndexOutOfBoundsException if index is out of range
     */
    public StopToStopRideWrapper getNode(int index) {
        return nodes.get(index);
    }

    /**
     * Returns an unmodifiable view of all nodes.
     *
     * @return list of all ride wrappers
     */
    public List<StopToStopRideWrapper> getNodes() {
        return nodes;
    }

    /**
     * Returns the degree (number of compatible rides) for a given ride.
     *
     * @param rideIndex the ride index
     * @return number of compatible rides
     */
    public int getDegree(int rideIndex) {
        Set<Integer> neighbors = adjacencyList.get(rideIndex);
        return neighbors != null ? neighbors.size() : 0;
    }

    // ==================== Compatibility Queries ====================

    /**
     * Returns all rides compatible with the given ride.
     *
     * @param rideIndex the ride index to find compatibles for
     * @return set of indices of compatible rides (empty if none)
     */
    public Set<Integer> getCompatibleRides(int rideIndex) {
        Set<Integer> neighbors = adjacencyList.get(rideIndex);
        if (neighbors == null) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new HashSet<>(neighbors));
    }

    /**
     * Finds rides that are compatible with ALL rides in the given set.
     *
     * <p>This is the key operation for finding candidates to add to a growing
     * hyper-pooled cluster. A ride can only be added to a cluster if it is
     * compatible with all existing members.
     *
     * @param rideIndices set of ride indices
     * @return set of rides compatible with all input rides
     */
    public Set<Integer> getCommonNeighbors(Set<Integer> rideIndices) {
        if (rideIndices == null || rideIndices.isEmpty()) {
            return Collections.emptySet();
        }

        Set<Integer> result = null;

        for (int rideIndex : rideIndices) {
            Set<Integer> neighbors = adjacencyList.get(rideIndex);
            if (neighbors == null || neighbors.isEmpty()) {
                return Collections.emptySet();
            }

            if (result == null) {
                result = new HashSet<>(neighbors);
            } else {
                result.retainAll(neighbors);
                if (result.isEmpty()) {
                    return Collections.emptySet();
                }
            }
        }

        // Remove the input rides themselves from the result
        if (result != null) {
            result.removeAll(rideIndices);
        }

        return result != null ? Collections.unmodifiableSet(result) : Collections.emptySet();
    }

    /**
     * Returns the compatibility score for a pair of rides.
     *
     * @param rideIndex1 first ride index
     * @param rideIndex2 second ride index
     * @return compatibility score (0 if not compatible, &gt;0 if compatible)
     */
    public double getCompatibilityScore(int rideIndex1, int rideIndex2) {
        EdgeKey key = new EdgeKey(rideIndex1, rideIndex2);
        return edgeScores.getOrDefault(key, 0.0);
    }

    /**
     * Checks if two rides are compatible (have an edge between them).
     *
     * @param rideIndex1 first ride index
     * @param rideIndex2 second ride index
     * @return true if the rides are compatible
     */
    public boolean areCompatible(int rideIndex1, int rideIndex2) {
        Set<Integer> neighbors = adjacencyList.get(rideIndex1);
        return neighbors != null && neighbors.contains(rideIndex2);
    }

    // ==================== Spatial Queries ====================

    /**
     * Returns rides with pickups in the same or adjacent grid cells as the given coordinate.
     *
     * <p>This is useful for quickly finding spatially nearby rides without
     * checking all rides.
     *
     * @param coord the reference coordinate
     * @return list of ride indices with nearby pickups
     */
    public List<Integer> getRidesNearPickup(Coord coord) {
        List<Integer> result = new ArrayList<>();

        int gridX = (int) (coord.getX() / GRID_CELL_SIZE);
        int gridY = (int) (coord.getY() / GRID_CELL_SIZE);

        // Check the cell and all 8 adjacent cells
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                long gridKey = ((long) (gridX + dx) << 32) | ((gridY + dy) & 0xFFFFFFFFL);
                List<Integer> cellRides = spatialGrid.get(gridKey);
                if (cellRides != null) {
                    result.addAll(cellRides);
                }
            }
        }

        return result;
    }

    // ==================== Temporal Queries ====================

    /**
     * Returns rides departing within the given time window.
     *
     * @param startTime start of time window (seconds)
     * @param endTime end of time window (seconds)
     * @return list of ride indices departing in the window
     */
    public List<Integer> getRidesInTimeWindow(double startTime, double endTime) {
        if (startTime > endTime) {
            return Collections.emptyList();
        }

        int startBin = (int) (startTime / TIME_BIN_SIZE);
        int endBin = (int) (endTime / TIME_BIN_SIZE);

        List<Integer> result = new ArrayList<>();

        for (int bin = startBin; bin <= endBin; bin++) {
            List<Integer> binRides = temporalIndex.get(bin);
            if (binRides != null) {
                for (int rideIndex : binRides) {
                    double departureTime = nodes.get(rideIndex).getDepartureTime();
                    if (departureTime >= startTime && departureTime <= endTime) {
                        result.add(rideIndex);
                    }
                }
            }
        }

        return result;
    }

    /**
     * Returns rides departing within a time window around the given time.
     *
     * @param centerTime center of time window (seconds)
     * @param windowHalfWidth half-width of time window (seconds)
     * @return list of ride indices departing in the window
     */
    public List<Integer> getRidesInTimeWindowAround(double centerTime, double windowHalfWidth) {
        return getRidesInTimeWindow(centerTime - windowHalfWidth, centerTime + windowHalfWidth);
    }

    // ==================== Statistics ====================

    /**
     * Returns a summary of graph statistics.
     *
     * @return statistics summary string
     */
    public String getStatisticsSummary() {
        int maxDegree = 0;
        int minDegree = Integer.MAX_VALUE;
        int isolatedNodes = 0;

        for (int i = 0; i < nodeCount; i++) {
            int degree = getDegree(i);
            maxDegree = Math.max(maxDegree, degree);
            minDegree = Math.min(minDegree, degree);
            if (degree == 0) {
                isolatedNodes++;
            }
        }

        if (nodeCount == 0) {
            minDegree = 0;
        }

        return String.format(
            "HyperPoolShareabilityGraph[nodes=%d, edges=%d, avgDegree=%.2f, " +
            "minDegree=%d, maxDegree=%d, isolatedNodes=%d, " +
            "spatialCells=%d, temporalBins=%d]",
            nodeCount, edgeCount, getAverageDegree(),
            minDegree, maxDegree, isolatedNodes,
            spatialGrid.size(), temporalIndex.size()
        );
    }

    @Override
    public String toString() {
        return String.format("HyperPoolShareabilityGraph[nodes=%d, edges=%d]", nodeCount, edgeCount);
    }

    // ==================== Inner Classes ====================

    /**
     * Canonical key for undirected edges.
     * Ensures (a,b) and (b,a) map to the same key.
     */
    public static final class EdgeKey {
        private final int minIndex;
        private final int maxIndex;

        /**
         * Creates an edge key from two ride indices.
         * The indices are stored in canonical order (min, max).
         *
         * @param index1 first ride index
         * @param index2 second ride index
         */
        public EdgeKey(int index1, int index2) {
            this.minIndex = Math.min(index1, index2);
            this.maxIndex = Math.max(index1, index2);
        }

        /**
         * Returns the smaller ride index.
         */
        public int getMinIndex() {
            return minIndex;
        }

        /**
         * Returns the larger ride index.
         */
        public int getMaxIndex() {
            return maxIndex;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            EdgeKey edgeKey = (EdgeKey) o;
            return minIndex == edgeKey.minIndex && maxIndex == edgeKey.maxIndex;
        }

        @Override
        public int hashCode() {
            return 31 * minIndex + maxIndex;
        }

        @Override
        public String toString() {
            return String.format("EdgeKey[%d-%d]", minIndex, maxIndex);
        }
    }

    // ==================== Functional Interface ====================

    /**
     * Functional interface for checking compatibility between two stop-to-stop rides.
     *
     * <p>Implementations should check various compatibility criteria such as:
     * <ul>
     *   <li>Temporal compatibility (departure times within window)</li>
     *   <li>Spatial compatibility (pickup/dropoff stops within proximity)</li>
     *   <li>Directional compatibility (rides going in similar direction)</li>
     * </ul>
     *
     * @see HyperPoolShareabilityGraph#build
     */
    @FunctionalInterface
    public interface StopCompatibilityChecker {

        /**
         * Checks if two stop-to-stop rides are compatible for bundling.
         *
         * @param ride1 first ride wrapper
         * @param ride2 second ride wrapper
         * @return compatibility score (&gt;0 if compatible, 0 or negative if not compatible)
         */
        double checkCompatibility(StopToStopRideWrapper ride1, StopToStopRideWrapper ride2);
    }
}
