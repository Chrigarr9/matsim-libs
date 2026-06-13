package org.matsim.contrib.demand_extraction.algorithm.selection;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unified ride-selection engine operating over a primitive view of the ride pool.
 *
 * <p>Inputs are parallel arrays: {@code sets[row]} is the sorted ascending int[] of
 * request ids served by that row, and {@code metric[row]} is its quality score
 * (higher = better). Outputs are {@link IntOpenHashSet} of KEPT row indices.
 *
 * <p>All rules use the shared {@link SelectionTieBreak} comparator:
 * metric descending → lex-smaller set → lower row index.
 *
 * <p>{@code k <= 0} means "keep everything" for all per-request rules.
 *
 * <p>Request ids are iterated in ascending order so output is independent of
 * map iteration order.
 *
 * <p>This class is stateless and non-instantiable.
 */
public final class RideSelector {

    private RideSelector() { /* non-instantiable */ }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Select kept row indices according to the given rule.
     *
     * @param sets   {@code sets[row]} — request ids for each row (sorted ascending)
     * @param metric {@code metric[row]} — quality score (higher = better)
     * @param rule   {@link SelectionRule} to apply
     * @param k      per-request quota; {@code k <= 0} keeps everything
     * @param lambda MMR diversity penalty in [0, 1]; ignored for non-MMR rules
     * @return {@link IntOpenHashSet} of kept row indices
     */
    public static IntOpenHashSet select(
            int[][] sets, double[] metric,
            SelectionRule rule, int k, double lambda) {

        int n = sets.length;
        IntOpenHashSet kept = new IntOpenHashSet();

        // k <= 0: keep everything regardless of rule
        if (k <= 0) {
            for (int i = 0; i < n; i++) kept.add(i);
            return kept;
        }

        return switch (rule) {
            case PER_REQUEST_TOP_K -> selectPerRequestTopK(sets, metric, k);
            case COVERAGE_TOPK     -> selectCoverageTopK(sets, metric, k);
            case MMR               -> selectMmr(sets, metric, k, lambda);
            case RATIO_THRESHOLD   -> {
                // Delegate to the dedicated method with a default fraction
                // (caller should prefer selectRatioThreshold directly).
                yield selectRatioThreshold(sets, metric, (double) k / n);
            }
        };
    }

    /**
     * Keep the top {@code ceil(keepFraction * nRows)} rows by metric (tie-break order).
     *
     * @param sets          request sets (sorted ascending per row)
     * @param metric        quality scores
     * @param keepFraction  fraction in (0, 1]; {@code ceil(keepFraction * nRows)} rows are kept
     * @return {@link IntOpenHashSet} of kept row indices
     */
    public static IntOpenHashSet selectRatioThreshold(
            int[][] sets, double[] metric, double keepFraction) {

        int n = sets.length;
        int toKeep = (int) Math.ceil(keepFraction * n);
        toKeep = Math.min(toKeep, n);

        // Build sorted order of all row indices by tie-break (best first)
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, SelectionTieBreak.comparator(metric, sets));

        IntOpenHashSet kept = new IntOpenHashSet();
        for (int i = 0; i < toKeep; i++) {
            kept.add(order[i]);
        }
        return kept;
    }

    // ── PER_REQUEST_TOP_K ─────────────────────────────────────────────────────

    private static IntOpenHashSet selectPerRequestTopK(
            int[][] sets, double[] metric, int k) {

        Map<Integer, List<Integer>> incident = buildIncidentMap(sets);
        Comparator<Integer> cmp = SelectionTieBreak.comparator(metric, sets);

        IntOpenHashSet kept = new IntOpenHashSet();

        // Process requests in ascending id order for determinism
        int[] sortedReqs = sortedKeys(incident);
        for (int req : sortedReqs) {
            List<Integer> rows = incident.get(req);
            int take = Math.min(k, rows.size());
            Integer[] sorted = rows.toArray(new Integer[0]);
            Arrays.sort(sorted, cmp);
            for (int i = 0; i < take; i++) {
                kept.add(sorted[i]);
            }
        }
        return kept;
    }

    // ── COVERAGE_TOPK ─────────────────────────────────────────────────────────

    private static IntOpenHashSet selectCoverageTopK(
            int[][] sets, double[] metric, int k) {

        int n = sets.length;

        // Sort all rows by tie-break order (best first)
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, SelectionTieBreak.comparator(metric, sets));

        // Per-request quota map — collect all request ids first
        Map<Integer, Integer> quota = new HashMap<>();
        for (int[] reqSet : sets) {
            for (int req : reqSet) {
                quota.putIfAbsent(req, k);
            }
        }

        IntOpenHashSet kept = new IntOpenHashSet();

        for (int row : order) {
            int[] members = sets[row];
            // Keep if at least one member has remaining quota > 0
            boolean anyQuota = false;
            for (int req : members) {
                if (quota.getOrDefault(req, 0) > 0) {
                    anyQuota = true;
                    break;
                }
            }
            if (anyQuota) {
                kept.add(row);
                // Decrement quota for ALL members
                for (int req : members) {
                    quota.merge(req, -1, Integer::sum);
                }
            }
        }
        return kept;
    }

    // ── MMR ───────────────────────────────────────────────────────────────────

    private static IntOpenHashSet selectMmr(
            int[][] sets, double[] metric, int k, double lambda) {

        Map<Integer, List<Integer>> incident = buildIncidentMap(sets);
        Comparator<Integer> cmp = SelectionTieBreak.comparator(metric, sets);

        IntOpenHashSet kept = new IntOpenHashSet();

        // Process requests in ascending id order for determinism
        int[] sortedReqs = sortedKeys(incident);
        for (int req : sortedReqs) {
            List<Integer> rows = incident.get(req);
            selectMmrForRequest(rows, k, lambda, metric, sets, cmp, kept);
        }
        return kept;
    }

    /**
     * Greedy MMR selection for the incident rows of a single request.
     * score(candidate) = metric[candidate] * (1 - lambda * maxJaccard(candidate, alreadyPickedForThisRequest))
     * Tie-break: lex-smaller set, then lower row index.
     */
    private static void selectMmrForRequest(
            List<Integer> incident, int k, double lambda,
            double[] metric, int[][] sets,
            Comparator<Integer> cmp,
            IntOpenHashSet kept) {

        int n = incident.size();
        if (n == 0) return;
        if (k >= n) {
            kept.addAll(incident);
            return;
        }

        // Mutable candidate list, sorted by tie-break for deterministic first pick
        List<Integer> candidates = new ArrayList<>(incident);
        candidates.sort(cmp);

        // Track rows picked for THIS request (to compute Jaccard against)
        List<Integer> pickedForThisReq = new ArrayList<>(k);

        while (pickedForThisReq.size() < k && !candidates.isEmpty()) {
            if (pickedForThisReq.isEmpty() || lambda == 0.0) {
                // First pick (or lambda=0 → pure metric, already sorted): take head
                int pick = candidates.remove(0);
                pickedForThisReq.add(pick);
                kept.add(pick);
            } else {
                // Subsequent picks under lambda > 0: compute MMR score for each candidate
                double bestScore = Double.NEGATIVE_INFINITY;
                int bestIdx = 0;

                for (int ci = 0; ci < candidates.size(); ci++) {
                    int row = candidates.get(ci);
                    double maxJ = 0.0;
                    for (int pickedRow : pickedForThisReq) {
                        double j = jaccard(sets[row], sets[pickedRow]);
                        if (j > maxJ) maxJ = j;
                    }
                    double score = metric[row] * (1.0 - lambda * maxJ);

                    boolean isBetter;
                    if (score > bestScore + 1e-15) {
                        isBetter = true;
                    } else if (Math.abs(score - bestScore) <= 1e-15) {
                        // Tie-break: lex-smaller set, then lower row index
                        int lexCmp = SelectionTieBreak.lexCompare(
                                sets[row], sets[candidates.get(bestIdx)]);
                        if (lexCmp != 0) {
                            isBetter = lexCmp < 0;
                        } else {
                            isBetter = row < candidates.get(bestIdx);
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
                pickedForThisReq.add(pick);
                kept.add(pick);
            }
        }
    }

    // ── Jaccard similarity on sorted int arrays ───────────────────────────────

    private static double jaccard(int[] a, int[] b) {
        int ia = 0, ib = 0, intersection = 0;
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Build inverted index: request id → list of incident row indices. */
    private static Map<Integer, List<Integer>> buildIncidentMap(int[][] sets) {
        Map<Integer, List<Integer>> map = new HashMap<>();
        for (int row = 0; row < sets.length; row++) {
            for (int req : sets[row]) {
                map.computeIfAbsent(req, ignored -> new ArrayList<>()).add(row);
            }
        }
        return map;
    }

    /** Return keys of the map in ascending int order. */
    private static int[] sortedKeys(Map<Integer, ?> map) {
        int[] keys = new int[map.size()];
        int i = 0;
        for (int k : map.keySet()) keys[i++] = k;
        Arrays.sort(keys);
        return keys;
    }
}
