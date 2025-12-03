package org.matsim.contrib.exmas_algorithm.graph;

import it.unimi.dsi.fastutil.ints.*;

import java.util.Arrays;

/**
 * Lightweight sharability graph using edge-list representation.
 * Replaces Python NetworkX MultiDiGraph with minimal memory overhead.
 *
 * Memory footprint: ~13 bytes per edge
 * - 4 bytes: source request ID (int)
 * - 4 bytes: target request ID (int)
 * - 4 bytes: ride index (int)
 * - 1 byte: ride kind (byte: 0=FIFO, 1=LIFO)
 * Plus amortized adjacency index overhead
 *
 * Python reference:
 * - Graph built from degree-2 rides: nx.MultiDiGraph()
 * - Nodes: Request indices
 * - Edges: (request_i, request_j) with attributes {ride index, kind}
 * - Core operation: Find common neighbors for ride extension
 *
 * See: src/exmas_commuters/core/exmas/rides.py lines 412-424 (graph construction)
 *      src/exmas_commuters/core/exmas/extensions.py lines 14-37 (common neighbor finding)
 */
public final class ShareabilityGraph {
    // Edge list storage (parallel arrays)
    private final int[] sourceRequests;
    private final int[] targetRequests;
    private final int[] rideIndices;
    private final byte[] rideKinds;  // 0=FIFO, 1=LIFO
    private final int edgeCount;

    // Adjacency index: request_id -> [outgoing edge indices]
    private final Int2ObjectOpenHashMap<IntArrayList> outgoingEdges;

    // Kind constants
    public static final byte KIND_FIFO = 0;
    public static final byte KIND_LIFO = 1;

    private ShareabilityGraph(Builder builder) {
        this.edgeCount = builder.size;
        this.sourceRequests = builder.sourceRequests;
        this.targetRequests = builder.targetRequests;
        this.rideIndices = builder.rideIndices;
        this.rideKinds = builder.rideKinds;

        // Build adjacency index
        this.outgoingEdges = new Int2ObjectOpenHashMap<>();
        for (int i = 0; i < edgeCount; i++) {
            int source = sourceRequests[i];
            outgoingEdges.computeIfAbsent(source, k -> new IntArrayList())
                        .add(i);
        }
    }

    /**
     * Find requests that are common neighbors to all given requests.
     * This is the core operation for ride extension.
     *
     * Python reference: extensions.py lines 14-37
     * ```
     * common_neighbours = None
     * for req in ride['request_index']:
     *     neighbors = set(self.graph.neighbors(req))
     *     common_neighbours = neighbors if common_neighbours is None
     *                         else common_neighbours & neighbors
     * ```
     *
     * @param requests Array of request IDs
     * @return Set of request IDs that can be paired with all input requests
     */
    public IntSet findCommonNeighbors(int... requests) {
        if (requests.length == 0) {
            return IntSets.EMPTY_SET;
        }

        // Get neighbors of first request
        IntSet result = getNeighbors(requests[0]);
        if (result.isEmpty()) {
            return IntSets.EMPTY_SET;
        }

        // Intersect with neighbors of remaining requests
        for (int i = 1; i < requests.length; i++) {
            IntSet neighbors = getNeighbors(requests[i]);
            result.retainAll(neighbors);  // Set intersection

            if (result.isEmpty()) {
                return IntSets.EMPTY_SET;  // Early termination
            }
        }

        return result;
    }

    /**
     * Get all outgoing neighbors for a request.
     *
     * @param request the request ID
     * @return set of neighboring request IDs
     */
    private IntSet getNeighbors(int request) {
        IntArrayList edgeIndices = outgoingEdges.get(request);
        if (edgeIndices == null) {
            return IntSets.EMPTY_SET;
        }

        IntSet neighbors = new IntOpenHashSet(edgeIndices.size());
        for (int edgeIdx : edgeIndices) {
            neighbors.add(targetRequests[edgeIdx]);
        }
        return neighbors;
    }

    /**
     * Get edges connecting source to target.
     * Returns list of ride indices.
     *
     * Python reference: extensions.py lines 28-30
     * ```
     * edges = [[self.graph[req_index][sharing_candidate_request][i]['index']
     *          for i in list(self.graph[req_index][sharing_candidate_request])]
     *          for req_index in ride['request_index']]
     * ```
     *
     * @param source source request ID
     * @param target target request ID
     * @return list of ride indices connecting source to target
     */
    public IntList getEdges(int source, int target) {
        IntArrayList edgeIndices = outgoingEdges.get(source);
        if (edgeIndices == null) {
            return IntLists.EMPTY_LIST;
        }

        IntArrayList rideIds = new IntArrayList();
        for (int edgeIdx : edgeIndices) {
            if (targetRequests[edgeIdx] == target) {
                rideIds.add(rideIndices[edgeIdx]);
            }
        }
        return rideIds;
    }

    /**
     * Get all ride indices for edges connecting source to target.
     * Similar to getEdges() but returns all edge metadata.
     *
     * @param source source request ID
     * @param target target request ID
     * @return list of edge info (ride index, kind)
     */
    public IntList[] getEdgesWithKinds(int source, int target) {
        IntArrayList edgeIndices = outgoingEdges.get(source);
        if (edgeIndices == null) {
            return new IntList[]{IntLists.EMPTY_LIST, IntLists.EMPTY_LIST};
        }

        IntArrayList rideIds = new IntArrayList();
        IntArrayList kinds = new IntArrayList();
        for (int edgeIdx : edgeIndices) {
            if (targetRequests[edgeIdx] == target) {
                rideIds.add(rideIndices[edgeIdx]);
                kinds.add(rideKinds[edgeIdx]);
            }
        }
        return new IntList[]{rideIds, kinds};
    }

    /**
     * Get the total number of edges in the graph.
     *
     * @return edge count
     */
    public int getEdgeCount() {
        return edgeCount;
    }

    /**
     * Get the number of nodes (requests) with outgoing edges.
     *
     * @return node count
     */
    public int getNodeCount() {
        return outgoingEdges.size();
    }

    /**
     * Check if a request has any outgoing edges.
     *
     * @param request the request ID
     * @return true if the request has neighbors
     */
    public boolean hasNeighbors(int request) {
        return outgoingEdges.containsKey(request);
    }

    /**
     * Get the out-degree of a request (number of outgoing edges).
     *
     * @param request the request ID
     * @return number of outgoing edges
     */
    public int getOutDegree(int request) {
        IntArrayList edgeIndices = outgoingEdges.get(request);
        return edgeIndices != null ? edgeIndices.size() : 0;
    }

    /**
     * Create a builder for constructing a ShareabilityGraph.
     *
     * @param estimatedEdges estimated number of edges (for initial capacity)
     * @return new builder instance
     */
    public static Builder builder(int estimatedEdges) {
        return new Builder(estimatedEdges);
    }

    public static final class Builder {
        private int size;
        private int capacity;
        private int[] sourceRequests;
        private int[] targetRequests;
        private int[] rideIndices;
        private byte[] rideKinds;

        Builder(int initialCapacity) {
            if (initialCapacity <= 0) {
                throw new IllegalArgumentException("Initial capacity must be positive: " + initialCapacity);
            }
            this.capacity = initialCapacity;
            this.size = 0;
            this.sourceRequests = new int[capacity];
            this.targetRequests = new int[capacity];
            this.rideIndices = new int[capacity];
            this.rideKinds = new byte[capacity];
        }

        /**
         * Add an edge to the graph.
         *
         * @param source source request ID
         * @param target target request ID
         * @param rideIndex the ride index connecting these requests
         * @param kind ride kind (KIND_FIFO or KIND_LIFO)
         * @return this builder for chaining
         */
        public Builder addEdge(int source, int target, int rideIndex, byte kind) {
            ensureCapacity(size + 1);

            sourceRequests[size] = source;
            targetRequests[size] = target;
            rideIndices[size] = rideIndex;
            rideKinds[size] = kind;
            size++;

            return this;
        }

        private void ensureCapacity(int required) {
            if (required > capacity) {
                int newCapacity = Math.max(required, capacity * 2);
                sourceRequests = Arrays.copyOf(sourceRequests, newCapacity);
                targetRequests = Arrays.copyOf(targetRequests, newCapacity);
                rideIndices = Arrays.copyOf(rideIndices, newCapacity);
                rideKinds = Arrays.copyOf(rideKinds, newCapacity);
                capacity = newCapacity;
            }
        }

        /**
         * Build the ShareabilityGraph instance.
         *
         * @return new ShareabilityGraph with all added edges
         */
        public ShareabilityGraph build() {
            // Trim to exact size
            sourceRequests = Arrays.copyOf(sourceRequests, size);
            targetRequests = Arrays.copyOf(targetRequests, size);
            rideIndices = Arrays.copyOf(rideIndices, size);
            rideKinds = Arrays.copyOf(rideKinds, size);

            return new ShareabilityGraph(this);
        }
    }
}
