package org.matsim.contrib.demand_extraction.algorithm.graph;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;

import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;

import it.unimi.dsi.fastutil.ints.IntArrayList;
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
 * or destinations respectively). Uses {@code long[]} to support any degree:
 * C(k,2)*4 bits, packed into ceil(C(k,2)*4/64) longs. Works up to degree 16.
 */
public final class DegreeGraph {

    private final int degree;
    private final int consensusLongCount;
    private final Long2ObjectOpenHashMap<int[]> extensionIndex;
    private final Long2ObjectOpenHashMap<long[]> consensusBySetHash;

    private DegreeGraph(int degree,
                        Long2ObjectOpenHashMap<int[]> extensionIndex,
                        Long2ObjectOpenHashMap<long[]> consensusBySetHash) {
        this.degree = degree;
        this.consensusLongCount = consensusLongCount(degree);
        this.extensionIndex = extensionIndex;
        this.consensusBySetHash = consensusBySetHash;
    }

    /** Number of longs needed to store consensus bits for a given degree. */
    public static int consensusLongCount(int degree) {
        int pairs = degree * (degree - 1) / 2;
        int bits = pairs * 4;
        return (bits + 63) / 64;
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
        return getConsensus(fullSet, idxA, idxB, 0); // offset 0 = origins
    }

    /** Same as getOriginConsensus but for destination orderings. */
    public Boolean getDestConsensus(int[] fullSet, int idxA, int idxB) {
        return getConsensus(fullSet, idxA, idxB, 2); // offset 2 = destinations
    }

    private Boolean getConsensus(int[] fullSet, int idxA, int idxB, int bitOffset) {
        int n = fullSet.length;
        boolean seenForward = false;
        boolean seenReverse = false;

        for (int skip = 0; skip < n; skip++) {
            if (skip == idxA || skip == idxB) continue;

            long subHash = hashSubsetSkipping(fullSet, skip);
            long[] bits = consensusBySetHash.get(subHash);
            if (bits == null) continue;

            int subIdxA = idxA < skip ? idxA : idxA - 1;
            int subIdxB = idxB < skip ? idxB : idxB - 1;
            int lo = Math.min(subIdxA, subIdxB);
            int hi = Math.max(subIdxA, subIdxB);
            int subDegree = n - 1;
            int pairIdx = lo * (2 * subDegree - lo - 1) / 2 + (hi - lo - 1);

            // Bit positions for this pair's forward/reverse direction
            int fwdBit = pairIdx * 4 + bitOffset;
            int revBit = fwdBit + 1;

            boolean fwd, rev;
            if (subIdxA < subIdxB) {
                fwd = getBit(bits, fwdBit);
                rev = getBit(bits, revBit);
            } else {
                fwd = getBit(bits, revBit);
                rev = getBit(bits, fwdBit);
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
     * Accumulate pairwise consensus bits for a single ordering into the given array.
     * Call this for each valid ordering found during enumeration.
     * Works for any degree (array size from {@link #consensusLongCount}).
     *
     * @param bits consensus array to OR into (modified in place)
     * @param originPerm origin permutation (position -> request index within set)
     * @param destPerm destination permutation
     * @param degree number of requests in the set
     */
    public static void accumulateOrderingBits(long[] bits, int[] originPerm, int[] destPerm, int degree) {
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
                    setBit(bits, pairIdx * 4 + (posI < posJ ? 0 : 1));
                }
                // Dest: find positions of i and j in destPerm
                posI = -1; posJ = -1;
                for (int p = 0; p < n; p++) {
                    if (destPerm[p] == i) posI = p;
                    if (destPerm[p] == j) posJ = p;
                }
                if (posI >= 0 && posJ >= 0) {
                    setBit(bits, pairIdx * 4 + 2 + (posI < posJ ? 0 : 1));
                }
            }
        }
    }

    private static boolean getBit(long[] bits, int bitPos) {
        return (bits[bitPos >> 6] & (1L << (bitPos & 63))) != 0;
    }

    private static void setBit(long[] bits, int bitPos) {
        bits[bitPos >> 6] |= 1L << (bitPos & 63);
    }

    private static boolean isAllZero(long[] bits) {
        for (long b : bits) if (b != 0L) return false;
        return true;
    }

    /**
     * Build a DegreeGraph from feasible set results.
     *
     * @param feasibleSets results from processSet -- each contains sorted request set + consensus bitmask
     * @param degree the degree of sets in this graph
     * @return built graph
     */

    /**
     * Build a DegreeGraph from valid Ride objects and pre-computed consensus bitmasks.
     *
     * <p>Builds the extension index from rides (for candidate generation) and uses
     * the pre-accumulated consensus map (for ordering constraint tightening).
     * This avoids collecting FeasibleSetResults during the hot parallel loop.
     *
     * @param rides valid rides from resultBySetHash
     * @param consensusMap pre-accumulated consensus bitmasks (setHash -> OR'd consensus bits)
     * @param degree the degree of rides in this graph
     * @return built graph
     */
    public static DegreeGraph buildFromRides(Collection<Ride> rides,
                                              Map<Long, long[]> consensusMap,
                                              int degree) {
        Long2ObjectOpenHashMap<int[]> extIndex = new Long2ObjectOpenHashMap<>();
        Long2ObjectOpenHashMap<long[]> consensus = new Long2ObjectOpenHashMap<>();

        // Copy pre-computed consensus into fastutil map
        if (consensusMap != null) {
            for (Map.Entry<Long, long[]> entry : consensusMap.entrySet()) {
                if (!isAllZero(entry.getValue())) {
                    consensus.put(entry.getKey().longValue(), entry.getValue());
                }
            }
        }

        int estimatedBuckets = rides.size() * degree;
        Long2ObjectOpenHashMap<IntArrayList> tempIndex =
            new Long2ObjectOpenHashMap<>(estimatedBuckets);

        for (Ride ride : rides) {
            int[] reqIndices = ride.getRequestIndices();
            int k = reqIndices.length;
            if (k != degree) continue;

            // Sort request indices (they should already be sorted, but ensure)
            int[] sorted = reqIndices.clone();
            Arrays.sort(sorted);

            // Build extension index: for each element, hash the (k-1)-subset without it
            for (int skip = 0; skip < k; skip++) {
                long subHash = hashSubsetSkipping(sorted, skip);
                int extraElement = sorted[skip];
                tempIndex.computeIfAbsent(subHash, h -> new IntArrayList()).add(extraElement);
            }
        }

        // Convert IntArrayLists to sorted int arrays
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
