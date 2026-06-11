package org.matsim.contrib.demand_extraction.algorithm.bamas.extension;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Selects which degree-D stub rows are marked EXTEND based on per-request ranking.
 *
 * <p>A row is marked EXTEND if it is among the top-K stubs for at least one of its
 * member requests (union across all requests). Two selection rules are supported:
 *
 * <ul>
 *   <li><b>TOP_K</b> — for each request, keep the K incident stubs with the highest
 *       quality metric (tie-broken by lex-smaller request set).</li>
 *   <li><b>MMR</b> — greedy Maximal Marginal Relevance per request: first pick =
 *       highest metric, then repeatedly pick the candidate with the highest
 *       {@code metric * (1 - λ * maxJaccard)} score (tie-broken by lex-smaller request
 *       set). With λ=0 this reduces to TOP_K.</li>
 * </ul>
 *
 * <p>This class is stateless and non-instantiable; all methods are static.
 */
public final class ExtensionParentRanker {

    /** Selection rule for per-request top-K filtering. */
    public enum SelectionRule { TOP_K, MMR }

    private ExtensionParentRanker() { /* non-instantiable */ }

    // ── Lex-comparator on request sets (tie-break: lex-smaller wins) ─────────

    /**
     * Compares two request-set arrays element-by-element. Returns negative if {@code a} is
     * lex-smaller (i.e. {@code a} should be preferred / ranked higher in ties).
     */
    private static int lexCompare(int[] a, int[] b) {
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            if (a[i] != b[i]) return Integer.compare(a[i], b[i]);
        }
        return Integer.compare(a.length, b.length);
    }

    /**
     * Comparator that ranks stubs by descending metric, with lex-smaller request set as
     * tie-break (lex-smaller = higher rank). When lex order is also equal (identical request
     * sets), the lower row index wins — making the comparator a total order so sort is
     * deterministic regardless of input row order.
     */
    private static Comparator<Integer> metricThenLexComparator(
            double[] metric, int[][] requestSets) {
        return (rowA, rowB) -> {
            int cmp = Double.compare(metric[rowB], metric[rowA]); // descending metric
            if (cmp != 0) return cmp;
            int lexCmp = lexCompare(requestSets[rowA], requestSets[rowB]); // lex-smaller wins
            if (lexCmp != 0) return lexCmp;
            return Integer.compare(rowA, rowB); // lower row index wins (total order tie-break)
        };
    }

    // ── Jaccard similarity on sorted int arrays ───────────────────────────────

    /**
     * Jaccard similarity between two sorted int arrays: |intersection| / |union|.
     */
    private static double jaccard(int[] a, int[] b) {
        int ia = 0, ib = 0;
        int intersection = 0;
        while (ia < a.length && ib < b.length) {
            if (a[ia] == b[ib]) {
                intersection++;
                ia++;
                ib++;
            } else if (a[ia] < b[ib]) {
                ia++;
            } else {
                ib++;
            }
        }
        int union = a.length + b.length - intersection;
        return union == 0 ? 1.0 : (double) intersection / union;
    }

    // ── Build inverted index: request → list of incident row indices ──────────

    private static Map<Integer, List<Integer>> buildIncidentMap(int[][] requestSets) {
        Map<Integer, List<Integer>> map = new HashMap<>();
        for (int row = 0; row < requestSets.length; row++) {
            for (int req : requestSets[row]) {
                map.computeIfAbsent(req, k -> new ArrayList<>()).add(row);
            }
        }
        return map;
    }

    // ── TOP_K selection for a single request's incident rows ─────────────────

    private static void selectTopK(List<Integer> incident, int k,
                                    double[] metric, int[][] requestSets,
                                    IntOpenHashSet marked) {
        int n = incident.size();
        if (n == 0) return;
        if (k >= n) {
            marked.addAll(incident);
            return;
        }
        // Sort by descending metric, lex tie-break
        Integer[] sorted = incident.toArray(new Integer[0]);
        Arrays.sort(sorted, metricThenLexComparator(metric, requestSets));
        for (int i = 0; i < k; i++) {
            marked.add(sorted[i]);
        }
    }

    // ── MMR selection for a single request's incident rows ───────────────────

    private static void selectMMR(List<Integer> incident, int k, double mmrLambda,
                                   double[] metric, int[][] requestSets,
                                   IntOpenHashSet marked) {
        int n = incident.size();
        if (n == 0) return;
        if (k >= n) {
            marked.addAll(incident);
            return;
        }

        // Candidate set (mutable copy)
        List<Integer> candidates = new ArrayList<>(incident);

        // Sort candidates by descending metric, lex tie-break (deterministic initial order)
        candidates.sort(metricThenLexComparator(metric, requestSets));

        List<Integer> kept = new ArrayList<>(k);

        while (kept.size() < k && !candidates.isEmpty()) {
            if (kept.isEmpty()) {
                // First pick: highest metric (candidates already sorted)
                int pick = candidates.remove(0);
                kept.add(pick);
                marked.add(pick);
            } else {
                // Subsequent picks: maximise MMR score
                // score(s) = metric[s] * (1 - λ * maxJaccard(s, kept))
                double bestScore = Double.NEGATIVE_INFINITY;
                int bestIdx = 0;

                for (int ci = 0; ci < candidates.size(); ci++) {
                    int row = candidates.get(ci);
                    double maxOverlap = 0.0;
                    for (int keptRow : kept) {
                        double j = jaccard(requestSets[row], requestSets[keptRow]);
                        if (j > maxOverlap) maxOverlap = j;
                    }
                    double score = metric[row] * (1.0 - mmrLambda * maxOverlap);

                    // Tie-break: lex-smaller request set wins; equal lex → lower row index wins
                    boolean isBetter;
                    if (score > bestScore + 1e-15) {
                        isBetter = true;
                    } else if (Math.abs(score - bestScore) <= 1e-15) {
                        int lexCmp = lexCompare(requestSets[row], requestSets[candidates.get(bestIdx)]);
                        if (lexCmp != 0) {
                            isBetter = lexCmp < 0; // lex-smaller wins
                        } else {
                            isBetter = row < candidates.get(bestIdx); // lower row index wins
                        }
                    } else {
                        isBetter = false;
                    }

                    if (isBetter) {
                        bestScore = score;
                        bestIdx = ci;
                    }
                }

                int pick = candidates.remove(bestIdx);
                kept.add(pick);
                marked.add(pick);
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the set of stub ROW indices marked EXTEND: a row is marked if it is
     * selected for at least one of its member requests. k &lt;= 0 marks every row
     * (exact passthrough, returns all of [0, N)).
     *
     * @param requestSets {@code requestSets[row]} — global request indices for row, sorted ascending
     * @param metric      {@code metric[row]} — quality value; higher = better; must be non-negative
     * @param k           top-K per request; k &lt;= 0 marks everything
     * @param rule        {@link SelectionRule#TOP_K} or {@link SelectionRule#MMR}
     * @param mmrLambda   diversity penalty for MMR (0.0 reduces to TOP_K); ignored for TOP_K
     * @return set of marked row indices (union over all requests)
     * @throws IllegalArgumentException if {@code requestSets.length != metric.length}
     */
    public static IntOpenHashSet markExtend(
            int[][] requestSets, double[] metric, int k,
            SelectionRule rule, double mmrLambda) {

        if (requestSets.length != metric.length) {
            throw new IllegalArgumentException(
                "requestSets.length (" + requestSets.length +
                ") must equal metric.length (" + metric.length + ")");
        }

        int n = requestSets.length;
        IntOpenHashSet marked = new IntOpenHashSet();

        // k <= 0: mark everything
        if (k <= 0) {
            for (int i = 0; i < n; i++) marked.add(i);
            return marked;
        }

        // Build inverted index: request → incident row list
        Map<Integer, List<Integer>> incidentMap = buildIncidentMap(requestSets);

        // For each request, select top-K stubs and union into marked
        for (List<Integer> incident : incidentMap.values()) {
            if (rule == SelectionRule.MMR) {
                selectMMR(incident, k, mmrLambda, metric, requestSets, marked);
            } else {
                selectTopK(incident, k, metric, requestSets, marked);
            }
        }

        return marked;
    }
}
