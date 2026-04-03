package org.matsim.contrib.demand_extraction.algorithm.graph;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * Degree-specific graph for higher-degree candidate generation.
 *
 * <p>Built from constraint-feasible sets at degree k, used to generate
 * degree-(k+1) candidates. Two components:
 * <ul>
 *   <li><b>Extension index:</b> (k-1)-subset hash → sorted extension elements.
 *       Used by {@link #findExtensions} for candidate generation.</li>
 *   <li><b>Valid orderings:</b> set hash → list of valid (origin, dest) permutations.
 *       Used for ordering constraint propagation to tighten enumeration DAGs.</li>
 * </ul>
 *
 * <p>Replaces pair-graph-based candidate generation at degree 4+.
 * The pair graph is still needed for FIFO/LIFO ordering constraints.
 */
public final class DegreeGraph {

    /** A valid (origin, destination) ordering for a feasible set. */
    public record OrderingPair(byte[] originPerm, byte[] destPerm) {}

    /** Result from processing a candidate set: the set, best ride info, and all valid orderings. */
    public record FeasibleSetResult(int[] sortedRequestSet, long setHash, List<OrderingPair> validOrderings) {}

    private final int degree;
    private final Long2ObjectOpenHashMap<int[]> extensionIndex;
    private final Long2ObjectOpenHashMap<List<OrderingPair>> orderingsBySetHash;

    private DegreeGraph(int degree,
                        Long2ObjectOpenHashMap<int[]> extensionIndex,
                        Long2ObjectOpenHashMap<List<OrderingPair>> orderingsBySetHash) {
        this.degree = degree;
        this.extensionIndex = extensionIndex;
        this.orderingsBySetHash = orderingsBySetHash;
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
     * Get valid orderings for a set (for ordering constraint propagation).
     * @param setHash hash of the sorted request set
     * @return list of valid orderings, or null if not in graph
     */
    public List<OrderingPair> getOrderings(long setHash) {
        return orderingsBySetHash.get(setHash);
    }

    /**
     * Check if request a is always before request b in origin orderings
     * across all sub-sets of fullSet that contain both a and b.
     *
     * @param fullSet sorted request indices of the candidate set (size degree+1)
     * @param idxA index position of request a in fullSet
     * @param idxB index position of request b in fullSet
     * @return Boolean.TRUE if always a before b, Boolean.FALSE if always b before a, null if mixed/unknown
     */
    public Boolean getOriginConsensus(int[] fullSet, int idxA, int idxB) {
        Boolean consensus = null;
        int n = fullSet.length;

        for (int skip = 0; skip < n; skip++) {
            if (skip == idxA || skip == idxB) continue;

            long subHash = hashSubsetSkipping(fullSet, skip);
            List<OrderingPair> orderings = orderingsBySetHash.get(subHash);
            if (orderings == null) continue;

            // Map idxA/idxB to positions in the subset
            // After removing element at 'skip', indices shift:
            int subIdxA = idxA < skip ? idxA : idxA - 1;
            int subIdxB = idxB < skip ? idxB : idxB - 1;

            for (OrderingPair op : orderings) {
                int posA = -1, posB = -1;
                for (int p = 0; p < op.originPerm().length; p++) {
                    if (op.originPerm()[p] == subIdxA) posA = p;
                    if (op.originPerm()[p] == subIdxB) posB = p;
                }
                if (posA < 0 || posB < 0) continue;

                boolean aFirst = posA < posB;
                if (consensus == null) consensus = aFirst;
                else if (consensus != aFirst) return null;
            }
        }
        return consensus;
    }

    /** Same as getOriginConsensus but for destination orderings. */
    public Boolean getDestConsensus(int[] fullSet, int idxA, int idxB) {
        Boolean consensus = null;
        int n = fullSet.length;

        for (int skip = 0; skip < n; skip++) {
            if (skip == idxA || skip == idxB) continue;

            long subHash = hashSubsetSkipping(fullSet, skip);
            List<OrderingPair> orderings = orderingsBySetHash.get(subHash);
            if (orderings == null) continue;

            int subIdxA = idxA < skip ? idxA : idxA - 1;
            int subIdxB = idxB < skip ? idxB : idxB - 1;

            for (OrderingPair op : orderings) {
                int posA = -1, posB = -1;
                for (int p = 0; p < op.destPerm().length; p++) {
                    if (op.destPerm()[p] == subIdxA) posA = p;
                    if (op.destPerm()[p] == subIdxB) posB = p;
                }
                if (posA < 0 || posB < 0) continue;

                boolean aFirst = posA < posB;
                if (consensus == null) consensus = aFirst;
                else if (consensus != aFirst) return null;
            }
        }
        return consensus;
    }

    /**
     * Build a DegreeGraph from feasible set results.
     *
     * @param feasibleSets results from processSet — each contains sorted request set + valid orderings
     * @param degree the degree of sets in this graph
     * @return built graph
     */
    public static DegreeGraph build(Collection<FeasibleSetResult> feasibleSets, int degree) {
        Long2ObjectOpenHashMap<int[]> extIndex = new Long2ObjectOpenHashMap<>();
        Long2ObjectOpenHashMap<List<OrderingPair>> orderings = new Long2ObjectOpenHashMap<>();

        Long2ObjectOpenHashMap<it.unimi.dsi.fastutil.ints.IntArrayList> tempIndex = new Long2ObjectOpenHashMap<>();

        for (FeasibleSetResult fsr : feasibleSets) {
            int[] set = fsr.sortedRequestSet();
            int k = set.length;

            if (fsr.validOrderings() != null && !fsr.validOrderings().isEmpty()) {
                orderings.put(fsr.setHash(), fsr.validOrderings());
            }

            for (int skip = 0; skip < k; skip++) {
                long subHash = hashSubsetSkipping(set, skip);
                int extraElement = set[skip];
                tempIndex.computeIfAbsent(subHash,
                    h -> new it.unimi.dsi.fastutil.ints.IntArrayList()).add(extraElement);
            }
        }

        for (var entry : tempIndex.long2ObjectEntrySet()) {
            int[] arr = entry.getValue().toIntArray();
            Arrays.sort(arr);
            extIndex.put(entry.getLongKey(), arr);
        }

        return new DegreeGraph(degree, extIndex, orderings);
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
