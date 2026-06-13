package org.matsim.contrib.demand_extraction.algorithm.selection;

import java.util.Comparator;

/**
 * Shared comparator factory for all {@link RideSelector} rules.
 *
 * <p>The total order over row indices is:
 * <ol>
 *   <li><b>Metric descending</b> — higher metric sorts earlier (better).</li>
 *   <li><b>Lex-smaller set</b> — element-by-element comparison of the (already
 *       ascending) {@code int[]} for each row; a strict prefix sorts before the
 *       longer array.</li>
 *   <li><b>Lower row index</b> — absolute tie-breaker, guaranteeing a total
 *       order that is independent of invocation order.</li>
 * </ol>
 *
 * <p>This class is stateless and non-instantiable.
 */
public final class SelectionTieBreak {

    private SelectionTieBreak() { /* non-instantiable */ }

    /**
     * Returns a {@link Comparator} over row indices implementing the 3-level tie-break
     * order described in the class javadoc.
     *
     * @param metric quality values; {@code metric[row]} is the score for that row
     * @param sets   request sets; {@code sets[row]} is the sorted ascending int[] of
     *               request ids for that row
     * @return comparator where smaller = "better" (put first in ascending sort)
     */
    public static Comparator<Integer> comparator(double[] metric, int[][] sets) {
        return (rowA, rowB) -> {
            // 1. Metric descending (higher is better, i.e., sorts earlier)
            int cmp = Double.compare(metric[rowB], metric[rowA]);
            if (cmp != 0) return cmp;
            // 2. Lex-smaller set (lex-smaller = better = sorts earlier)
            int lexCmp = lexCompare(sets[rowA], sets[rowB]);
            if (lexCmp != 0) return lexCmp;
            // 3. Lower row index (absolute tie-breaker)
            return Integer.compare(rowA, rowB);
        };
    }

    /**
     * Lexicographic comparison of two sorted int arrays.
     * Returns negative if {@code a} is lex-smaller (preferred).
     * A strict prefix of another array sorts smaller.
     */
    static int lexCompare(int[] a, int[] b) {
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            if (a[i] != b[i]) return Integer.compare(a[i], b[i]);
        }
        return Integer.compare(a.length, b.length);
    }
}
