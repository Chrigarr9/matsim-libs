package org.matsim.contrib.demand_extraction.algorithm.graph;

import java.util.Arrays;
import java.util.Collection;

import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * Degree-specific feasible-set index for higher-degree candidate generation.
 *
 * <p>Built from constraint-feasible sets at degree k, used to generate
 * degree-(k+1) candidates. For each (k-1)-subset of a feasible set, records
 * which extra elements extended it into a feasible k-set. When extending a
 * k-set at the next degree, we intersect the extension lists from its
 * k (k-1)-subsets: an element that appears in all k lists is guaranteed to
 * form a feasible k-sub-set with every combination of k-1 existing elements.
 *
 * <p>This replaces pair-graph-based candidate generation at degree 4+.
 * The shareability graph is still needed for FIFO/LIFO ordering constraints.
 */
public final class DegreeGraph {

    private final int degree;
    private final Long2ObjectOpenHashMap<int[]> extensionIndex;

    private DegreeGraph(int degree, Long2ObjectOpenHashMap<int[]> extensionIndex) {
        this.degree = degree;
        this.extensionIndex = extensionIndex;
    }

    public int getDegree() { return degree; }

    /**
     * Find all requests that extend baseSet into a feasible (degree+1)-set.
     *
     * <p>For each (k-1)-subset of baseSet, looks up extension elements in the index.
     * Returns the intersection of all k lists, minus base set elements.
     * This guarantees ALL k+1 sub-sets of the result are feasible.
     *
     * @param baseSet sorted request indices of size {@code degree}
     * @return sorted extension request indices (may be empty)
     */
    public int[] findExtensions(int[] baseSet) {
        int k = baseSet.length;
        if (k != degree) {
            throw new IllegalArgumentException("Base set size " + k + " != graph degree " + degree);
        }

        // Look up k extension lists (one per (k-1)-subset)
        int[][] lists = new int[k][];
        for (int skip = 0; skip < k; skip++) {
            long subHash = hashSubsetSkipping(baseSet, skip);
            int[] extensions = extensionIndex.get(subHash);
            if (extensions == null) return EMPTY;
            lists[skip] = extensions;
        }

        // k-way sorted intersection
        int[] result = lists[0];
        for (int i = 1; i < k; i++) {
            result = intersectSorted(result, lists[i]);
            if (result.length == 0) return EMPTY;
        }

        // Remove base set elements
        return removeSorted(result, baseSet);
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

        int estimatedBuckets = rides.size() * degree;
        Long2ObjectOpenHashMap<IntArrayList> tempIndex =
            new Long2ObjectOpenHashMap<>(estimatedBuckets);

        for (Ride ride : rides) {
            int[] reqIndices = ride.getRequestIndices();
            int k = reqIndices.length;
            if (k != degree) continue;

            int[] sorted = reqIndices.clone();
            Arrays.sort(sorted);

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

        return new DegreeGraph(degree, extIndex);
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

    /** Must match RideExtender.hashRequestSet */
    public static long hashRequestSet(int[] sortedIndices) {
        long h = 0;
        for (int idx : sortedIndices) {
            h = h * 1000003L + idx;
        }
        return h;
    }

    private static int[] intersectSorted(int[] a, int[] b) {
        int[] buf = new int[Math.min(a.length, b.length)];
        int ai = 0, bi = 0, ri = 0;
        while (ai < a.length && bi < b.length) {
            if (a[ai] < b[bi]) ai++;
            else if (a[ai] > b[bi]) bi++;
            else { buf[ri++] = a[ai]; ai++; bi++; }
        }
        return ri == buf.length ? buf : Arrays.copyOf(buf, ri);
    }

    private static int[] removeSorted(int[] source, int[] toRemove) {
        int[] buf = new int[source.length];
        int si = 0, ri = 0, wi = 0;
        while (si < source.length) {
            if (ri < toRemove.length && source[si] == toRemove[ri]) {
                si++; ri++;
            } else if (ri < toRemove.length && source[si] > toRemove[ri]) {
                ri++;
            } else {
                buf[wi++] = source[si++];
            }
        }
        return wi == buf.length ? buf : Arrays.copyOf(buf, wi);
    }
}
