package org.matsim.contrib.demand_extraction.algorithm.graph;

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
 * DETERMINISM: Neighbors are pre-sorted during construction to ensure
 * deterministic iteration order in findCommonNeighbors().
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

    // Pre-sorted neighbors: request_id -> sorted int[] of neighbor IDs
    // Built once during construction for deterministic iteration
    private final Int2ObjectOpenHashMap<int[]> sortedNeighbors;

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

        // Pre-build sorted neighbors for deterministic iteration
        this.sortedNeighbors = new Int2ObjectOpenHashMap<>();
        for (Int2ObjectMap.Entry<IntArrayList> entry : outgoingEdges.int2ObjectEntrySet()) {
            int source = entry.getIntKey();
            IntArrayList edgeIndices = entry.getValue();

            // Collect unique neighbors
            IntOpenHashSet neighborSet = new IntOpenHashSet(edgeIndices.size());
            for (int edgeIdx : edgeIndices) {
                neighborSet.add(targetRequests[edgeIdx]);
            }

            // Convert to sorted array
            int[] sorted = neighborSet.toIntArray();
            Arrays.sort(sorted);
            sortedNeighbors.put(source, sorted);
        }
    }

    /**
     * Find requests that are common neighbors to all given requests.
     * Returns a SORTED array for deterministic iteration.
     *
     * Python reference: extensions.py lines 14-37
     *
     * @param requests Array of request IDs
     * @return Sorted array of request IDs that can be paired with all input requests
     */
    public int[] findCommonNeighborsSorted(int... requests) {
        if (requests.length == 0) {
            return new int[0];
        }

        // Get sorted neighbors of first request
        int[] result = sortedNeighbors.get(requests[0]);
        if (result == null || result.length == 0) {
            return new int[0];
        }
        // Copy since we'll modify
        result = result.clone();

        // Intersect with neighbors of remaining requests
        for (int i = 1; i < requests.length; i++) {
            int[] neighbors = sortedNeighbors.get(requests[i]);
            if (neighbors == null || neighbors.length == 0) {
                return new int[0];
            }
            result = intersectSorted(result, neighbors);
            if (result.length == 0) {
                return new int[0];  // Early termination
            }
        }

        return result;
    }

    /**
     * Intersect two sorted arrays efficiently.
     */
    private int[] intersectSorted(int[] a, int[] b) {
        int[] temp = new int[Math.min(a.length, b.length)];
        int i = 0, j = 0, k = 0;
        while (i < a.length && j < b.length) {
            if (a[i] < b[j]) {
                i++;
            } else if (a[i] > b[j]) {
                j++;
            } else {
                temp[k++] = a[i];
                i++;
                j++;
            }
        }
        return Arrays.copyOf(temp, k);
    }

    /**
     * Find requests that are common neighbors to all given requests.
     * This is the core operation for ride extension.
     *
     * @param requests Array of request IDs
     * @return Set of request IDs that can be paired with all input requests
     * @deprecated Use findCommonNeighborsSorted() for deterministic iteration
     */
    @Deprecated
    public IntSet findCommonNeighbors(int... requests) {
        int[] sorted = findCommonNeighborsSorted(requests);
        IntOpenHashSet set = new IntOpenHashSet(sorted.length);
        for (int v : sorted) set.add(v);
        return set;
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
