package org.matsim.contrib.demand_extraction.algorithm.bamas.graph;

import java.util.Arrays;
import java.util.Collection;

import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Degree-specific feasible-set index for higher-degree candidate generation.
 *
 * <p>Built from constraint-feasible sets at degree k, used to generate
 * degree-(k+1) candidates. For each (k-1)-subset of a feasible set, records
 * which extra elements extended it into a feasible k-set. When extending a
 * k-set at the next degree, a candidate extension must be supported by every
 * (k-1)-subset of the base set.
 *
 * <p>This replaces pair-graph-based candidate generation at degree 4+.
 * The shareability graph is still needed for FIFO/LIFO ordering constraints.
 */
public final class DegreeGraph {

    private final int degree;
    private final Long2ObjectOpenHashMap<int[]> extensionIndex;
    private final LongOpenHashSet feasibleSetHashes;

    private DegreeGraph(int degree, Long2ObjectOpenHashMap<int[]> extensionIndex,
                        LongOpenHashSet feasibleSetHashes) {
        this.degree = degree;
        this.extensionIndex = extensionIndex;
        this.feasibleSetHashes = feasibleSetHashes;
    }

    public int getDegree() { return degree; }

    /** Return true if the exact sorted request set exists in this degree catalog. */
    public boolean containsSet(int[] sortedSet) {
        if (sortedSet.length != degree) return false;
        return feasibleSetHashes.contains(hashRequestSet(sortedSet));
    }

    /**
     * Find all requests that extend baseSet into a feasible (degree+1)-set.
     *
     * <p>For each (k-1)-subset of baseSet, looks up extension elements in the index.
     * Returns extension elements present in every subset's extension list, minus base set
     * elements. This preserves the BAMAS downward-closure invariant: a feasible
     * (k+1)-set is generated only when all of its k-subsets were feasible.
     *
     * @param baseSet sorted request indices of size {@code degree}
     * @return sorted extension request indices (may be empty)
     */
    public int[] findExtensions(int[] baseSet) {
        int k = baseSet.length;
        if (k != degree) {
            throw new IllegalArgumentException("Base set size " + k + " != graph degree " + degree);
        }

        int[] intersection = null;
        for (int skip = 0; skip < k; skip++) {
            long subHash = hashSubsetSkipping(baseSet, skip);
            int[] extensions = extensionIndex.get(subHash);
            if (extensions == null) return EMPTY;
            intersection = intersection == null
                    ? extensions.clone()
                    : intersectSorted(intersection, extensions);
            if (intersection.length == 0) return EMPTY;
        }

        if (intersection == null || intersection.length == 0) return EMPTY;
        int[] result = intersection;
        int unique = 0;
        for (int i = 0; i < result.length; i++) {
            if (Arrays.binarySearch(baseSet, result[i]) < 0
                    && (unique == 0 || result[i] != result[unique - 1])) {
                result[unique++] = result[i];
            }
        }
        return unique == 0 ? EMPTY : Arrays.copyOf(result, unique);
    }

    private static int[] intersectSorted(int[] left, int[] right) {
        int[] result = new int[Math.min(left.length, right.length)];
        int leftIndex = 0;
        int rightIndex = 0;
        int resultSize = 0;

        while (leftIndex < left.length && rightIndex < right.length) {
            int leftValue = left[leftIndex];
            int rightValue = right[rightIndex];
            if (leftValue == rightValue) {
                result[resultSize++] = leftValue;
                leftIndex++;
                rightIndex++;
            } else if (leftValue < rightValue) {
                leftIndex++;
            } else {
                rightIndex++;
            }
        }

        return resultSize == result.length ? result : Arrays.copyOf(result, resultSize);
    }

    /**
     * Build a DegreeGraph from valid Ride objects.
     *
     * <p>For each ride, hashes each (k-1)-subset of the ride's request indices and
     * adds the skipped element to that subset's extension list. The resulting index
     * supports O(k) extension lookup via {@link #findExtensions}.
     *
     * @param rides valid rides at the current degree
     * @param degree the degree of rides in this graph
     * @return built graph
     */
    public static DegreeGraph buildFromRides(Collection<Ride> rides, int degree) {
        Long2ObjectOpenHashMap<int[]> extIndex = new Long2ObjectOpenHashMap<>();
        LongOpenHashSet feasibleSetHashes = new LongOpenHashSet(rides.size());

        int estimatedBuckets = rides.size() * degree;
        Long2ObjectOpenHashMap<IntArrayList> tempIndex =
            new Long2ObjectOpenHashMap<>(estimatedBuckets);

        for (Ride ride : rides) {
            int[] reqIndices = ride.getRequestIndices();
            int k = reqIndices.length;
            if (k != degree) continue;

            int[] sorted = reqIndices.clone();
            Arrays.sort(sorted);
            feasibleSetHashes.add(hashRequestSet(sorted));

            // For each element, hash the (k-1)-subset without it and record extensibility.
            for (int skip = 0; skip < k; skip++) {
                long subHash = hashSubsetSkipping(sorted, skip);
                int extraElement = sorted[skip];
                tempIndex.computeIfAbsent(subHash, h -> new IntArrayList()).add(extraElement);
            }
        }

        // Convert IntArrayLists to sorted int arrays for O(k) lookup.
        for (var entry : tempIndex.long2ObjectEntrySet()) {
            int[] arr = entry.getValue().toIntArray();
            Arrays.sort(arr);
            extIndex.put(entry.getLongKey(), arr);
        }

        return new DegreeGraph(degree, extIndex, feasibleSetHashes);
    }

    // --- Utility methods ---

    private static final int[] EMPTY = new int[0];

    static long hashSubsetSkipping(int[] sorted, int skipIndex) {
        long h = 0;
        for (int i = 0; i < sorted.length; i++) {
            if (i == skipIndex) continue;
            h = h * 1000003L + sorted[i];
        }
        return h;
    }

    /** Must match BamasRideExtender.hashRequestSet */
    public static long hashRequestSet(int[] sortedIndices) {
        long h = 0;
        for (int idx : sortedIndices) {
            h = h * 1000003L + idx;
        }
        return h;
    }

}
