package org.matsim.contrib.demand_extraction.algorithm.selection;

/**
 * Ride-selection rule applied by {@link RideSelector}.
 *
 * <ul>
 *   <li>{@link #PER_REQUEST_TOP_K} — for each request keep the top-K incident rows
 *       independently; result is the union across all requests.</li>
 *   <li>{@link #COVERAGE_TOPK} — greedy best-first walk; a row is kept if at least
 *       one of its members still has remaining quota; on keep, all members' quotas
 *       are decremented.</li>
 *   <li>{@link #MMR} — Maximal Marginal Relevance per request; diversity-penalised
 *       greedy selection; λ=0 reduces to {@link #PER_REQUEST_TOP_K}.</li>
 *   <li>{@link #RATIO_THRESHOLD} — keep the top {@code ceil(keepFraction * N)} rows
 *       by metric; exposed via
 *       {@link RideSelector#selectRatioThreshold(int[][], double[], double)}.</li>
 * </ul>
 */
public enum SelectionRule {
    PER_REQUEST_TOP_K,
    COVERAGE_TOPK,
    MMR,
    RATIO_THRESHOLD
}
