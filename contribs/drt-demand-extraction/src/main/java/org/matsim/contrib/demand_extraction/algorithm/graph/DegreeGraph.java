package org.matsim.contrib.demand_extraction.algorithm.graph;

import java.util.Arrays;
import java.util.Collection;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * Degree-specific graph for higher-degree candidate generation.
 *
 * <p>Built from constraint-feasible sets at degree k, used to generate
 * degree-(k+1) candidates. Two components:
 * <ul>
 *   <li><b>Extension index:</b> (k-1)-subset hash -> sorted extension elements.
 *       Used by {@link #findExtensions} for candidate generation.</li>
 *   <li><b>Consensus bitmask:</b> set hash -> compact long encoding pairwise
 *       ordering consensus. Used for ordering constraint propagation to tighten
 *       enumeration DAGs.</li>
 * </ul>
 *
 * <p>Replaces pair-graph-based candidate generation at degree 4+.
 * The pair graph is still needed for FIFO/LIFO ordering constraints.
 *
 * <h3>Bitmask encoding</h3>
 * For a set of degree k, each pair (i,j) with i &lt; j gets 4 bits at offset
 * {@code pairIdx * 4} where {@code pairIdx = i*(2*k-i-1)/2 + (j-i-1)}:
 * <ul>
 *   <li>bit +0: i before j in ORIGINS seen in some valid ordering</li>
 *   <li>bit +1: j before i in ORIGINS seen in some valid ordering</li>
 *   <li>bit +2: i before j in DESTINATIONS seen in some valid ordering</li>
 *   <li>bit +3: j before i in DESTINATIONS seen in some valid ordering</li>
 * </ul>
 * A pair has consensus if exactly one of the two direction bits is set (for origins
 * or destinations respectively). For degree &gt; 5, the bitmask is 0 (disabled)
 * since C(6,2)*4 = 60 bits is fine but C(7,2)*4 = 84 exceeds long capacity.
 * We conservatively enable only for degree &le; 5 (40 bits max).
 */
public final class DegreeGraph {

    /** Result from processing a candidate set: the set, its hash, and consensus bitmask. */
    public record FeasibleSetResult(int[] sortedRequestSet, long setHash, long consensusBitmask) {}

    private final int degree;
    private final Long2ObjectOpenHashMap<int[]> extensionIndex;
    private final Long2LongOpenHashMap consensusBySetHash;

    private DegreeGraph(int degree,
                        Long2ObjectOpenHashMap<int[]> extensionIndex,
                        Long2LongOpenHashMap consensusBySetHash) {
        this.degree = degree;
        this.extensionIndex = extensionIndex;
        this.consensusBySetHash = consensusBySetHash;
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
     * Check if request a is always before request b in origin orderings
     * across all sub-sets of fullSet that contain both a and b.
     *
     * @param fullSet sorted request indices of the candidate set (size degree+1)
     * @param idxA index position of request a in fullSet
     * @param idxB index position of request b in fullSet
     * @return Boolean.TRUE if always a before b, Boolean.FALSE if always b before a, null if mixed/unknown
     */
    public Boolean getOriginConsensus(int[] fullSet, int idxA, int idxB) {
        int n = fullSet.length;
        boolean seenForward = false; // a before b
        boolean seenReverse = false; // b before a

        for (int skip = 0; skip < n; skip++) {
            if (skip == idxA || skip == idxB) continue;

            long subHash = hashSubsetSkipping(fullSet, skip);
            long bitmask = consensusBySetHash.get(subHash);
            if (bitmask == 0L) continue;

            // Map idxA/idxB to positions in the subset
            int subIdxA = idxA < skip ? idxA : idxA - 1;
            int subIdxB = idxB < skip ? idxB : idxB - 1;
            // Ensure lo < hi for the pair index formula
            int lo = Math.min(subIdxA, subIdxB);
            int hi = Math.max(subIdxA, subIdxB);
            int subDegree = n - 1;
            int pairIdx = lo * (2 * subDegree - lo - 1) / 2 + (hi - lo - 1);

            boolean fwd, rev;
            if (subIdxA < subIdxB) {
                // lo=subIdxA, hi=subIdxB: bit 0 = A before B, bit 1 = B before A
                fwd = (bitmask & (1L << (pairIdx * 4))) != 0;
                rev = (bitmask & (1L << (pairIdx * 4 + 1))) != 0;
            } else {
                // lo=subIdxB, hi=subIdxA: bit 0 = B before A, bit 1 = A before B
                fwd = (bitmask & (1L << (pairIdx * 4 + 1))) != 0;
                rev = (bitmask & (1L << (pairIdx * 4))) != 0;
            }

            if (fwd) seenForward = true;
            if (rev) seenReverse = true;
            if (seenForward && seenReverse) return null; // Both directions seen
        }

        if (seenForward && !seenReverse) return Boolean.TRUE;
        if (seenReverse && !seenForward) return Boolean.FALSE;
        return null; // No data or mixed
    }

    /** Same as getOriginConsensus but for destination orderings. */
    public Boolean getDestConsensus(int[] fullSet, int idxA, int idxB) {
        int n = fullSet.length;
        boolean seenForward = false; // a before b
        boolean seenReverse = false; // b before a

        for (int skip = 0; skip < n; skip++) {
            if (skip == idxA || skip == idxB) continue;

            long subHash = hashSubsetSkipping(fullSet, skip);
            long bitmask = consensusBySetHash.get(subHash);
            if (bitmask == 0L) continue;

            int subIdxA = idxA < skip ? idxA : idxA - 1;
            int subIdxB = idxB < skip ? idxB : idxB - 1;
            int lo = Math.min(subIdxA, subIdxB);
            int hi = Math.max(subIdxA, subIdxB);
            int subDegree = n - 1;
            int pairIdx = lo * (2 * subDegree - lo - 1) / 2 + (hi - lo - 1);

            boolean fwd, rev;
            if (subIdxA < subIdxB) {
                // lo=subIdxA, hi=subIdxB: bit 2 = A before B in dests, bit 3 = B before A
                fwd = (bitmask & (1L << (pairIdx * 4 + 2))) != 0;
                rev = (bitmask & (1L << (pairIdx * 4 + 3))) != 0;
            } else {
                // lo=subIdxB, hi=subIdxA: bit 2 = B before A, bit 3 = A before B
                fwd = (bitmask & (1L << (pairIdx * 4 + 3))) != 0;
                rev = (bitmask & (1L << (pairIdx * 4 + 2))) != 0;
            }

            if (fwd) seenForward = true;
            if (rev) seenReverse = true;
            if (seenForward && seenReverse) return null;
        }

        if (seenForward && !seenReverse) return Boolean.TRUE;
        if (seenReverse && !seenForward) return Boolean.FALSE;
        return null;
    }

    /**
     * Compute pairwise consensus bits for a single ordering.
     * Call this for each valid ordering found during enumeration,
     * OR the results together to build the full consensus bitmask.
     *
     * @param originPerm origin permutation (position -> request index within set)
     * @param destPerm destination permutation
     * @param degree number of requests in the set
     * @return bitmask with direction bits set, or 0L if degree > 5
     */
    public static long computeOrderingBits(int[] originPerm, int[] destPerm, int degree) {
        if (degree > 5) return 0L; // Too many pairs for a single long
        long bits = 0L;
        int n = degree;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                int pairIdx = i * (2 * n - i - 1) / 2 + (j - i - 1);
                // Origin: find positions of i and j in originPerm
                int posI = -1, posJ = -1;
                for (int p = 0; p < n; p++) {
                    if (originPerm[p] == i) posI = p;
                    if (originPerm[p] == j) posJ = p;
                }
                if (posI >= 0 && posJ >= 0) {
                    bits |= 1L << (pairIdx * 4 + (posI < posJ ? 0 : 1));
                }
                // Dest: find positions of i and j in destPerm
                posI = -1; posJ = -1;
                for (int p = 0; p < n; p++) {
                    if (destPerm[p] == i) posI = p;
                    if (destPerm[p] == j) posJ = p;
                }
                if (posI >= 0 && posJ >= 0) {
                    bits |= 1L << (pairIdx * 4 + 2 + (posI < posJ ? 0 : 1));
                }
            }
        }
        return bits;
    }

    /**
     * Build a DegreeGraph from feasible set results.
     *
     * @param feasibleSets results from processSet -- each contains sorted request set + consensus bitmask
     * @param degree the degree of sets in this graph
     * @return built graph
     */
    public static DegreeGraph build(Collection<FeasibleSetResult> feasibleSets, int degree) {
        Long2ObjectOpenHashMap<int[]> extIndex = new Long2ObjectOpenHashMap<>();
        Long2LongOpenHashMap consensus = new Long2LongOpenHashMap();
        consensus.defaultReturnValue(0L);

        int estimatedBuckets = feasibleSets.size() * degree;
        Long2ObjectOpenHashMap<IntArrayList> tempIndex = new Long2ObjectOpenHashMap<>(estimatedBuckets);

        for (FeasibleSetResult fsr : feasibleSets) {
            int[] set = fsr.sortedRequestSet();
            int k = set.length;

            if (fsr.consensusBitmask() != 0L) {
                consensus.put(fsr.setHash(), fsr.consensusBitmask());
            }

            for (int skip = 0; skip < k; skip++) {
                long subHash = hashSubsetSkipping(set, skip);
                int extraElement = set[skip];
                tempIndex.computeIfAbsent(subHash, h -> new IntArrayList()).add(extraElement);
            }
        }

        for (var entry : tempIndex.long2ObjectEntrySet()) {
            int[] arr = entry.getValue().toIntArray();
            Arrays.sort(arr);
            extIndex.put(entry.getLongKey(), arr);
        }

        return new DegreeGraph(degree, extIndex, consensus);
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
