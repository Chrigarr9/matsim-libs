package org.matsim.contrib.demand_extraction.algorithm.selection;

import static org.junit.jupiter.api.Assertions.*;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import org.junit.jupiter.api.Test;

class RideSelectorTest {

    // rows: 0:{0,1} m=10 | 1:{0,2} m=9 | 2:{1,2} m=8 | 3:{0,1} m=7 | 4:{2} m=6
    private static final int[][] SETS = {{0,1},{0,2},{1,2},{0,1},{2}};
    private static final double[] METRIC = {10, 9, 8, 7, 6};

    @Test void perRequestTopKIsUnionOfIndependentTopK() {
        IntOpenHashSet kept = RideSelector.select(SETS, METRIC, SelectionRule.PER_REQUEST_TOP_K, 1, 0.0);
        assertEquals(IntOpenHashSet.of(0, 1), kept);
    }
    @Test void coverageTopKChargesAllMembersQuotas() {
        IntOpenHashSet kept = RideSelector.select(SETS, METRIC, SelectionRule.COVERAGE_TOPK, 1, 0.0);
        assertEquals(IntOpenHashSet.of(0, 1), kept);
    }
    @Test void coverageTopKEqualsPerRequestTopKAsKeptSet() {
        // COVERAGE_TOPK (port of PostExtensionPruner.pruneCoverageTopK: keep a row iff
        // some member request still has cov<K, then charge ALL members) yields the SAME
        // kept-set as PER_REQUEST_TOP_K (union of each request's independent top-K). Proof:
        // a row kept by coverage via member q implies every higher-quality row incident to q
        // was also kept (a higher row could only be dropped if q were already saturated, which
        // would block this row too) -> the row is among q's top-K -> per-request keeps it; and
        // conversely. They are one kept-set function; the two enum names exist for pipeline-stage
        // config clarity (parent filter vs post-extension), NOT to denote different survivors.
        // Only MMR (diversity penalty) is a genuinely different selection rule.
        for (int k : new int[]{1, 2, 3}) {
            assertEquals(
                RideSelector.select(SETS, METRIC, SelectionRule.PER_REQUEST_TOP_K, k, 0.0),
                RideSelector.select(SETS, METRIC, SelectionRule.COVERAGE_TOPK, k, 0.0),
                "coverage and per-request must agree on the kept set at K=" + k);
        }
    }
    @Test void mmrWithLambdaZeroEqualsPerRequestTopK() {
        assertEquals(
            RideSelector.select(SETS, METRIC, SelectionRule.PER_REQUEST_TOP_K, 2, 0.0),
            RideSelector.select(SETS, METRIC, SelectionRule.MMR, 2, 0.0));
    }
    @Test void mmrPenalizesOverlap() {
        IntOpenHashSet kept = RideSelector.select(SETS, METRIC, SelectionRule.MMR, 2, 1.0);
        assertTrue(kept.contains(0) && kept.contains(1));
        assertFalse(kept.contains(3));
    }
    @Test void ratioThresholdKeepsTopFraction() {
        IntOpenHashSet kept = RideSelector.selectRatioThreshold(SETS, METRIC, 0.4);
        assertEquals(IntOpenHashSet.of(0, 1), kept);
    }
    @Test void tieBreakIsMetricDescThenLexThenRowIndex() {
        int[][] sets = {{5}, {3}, {3}};
        double[] metric = {1.0, 1.0, 1.0};
        IntOpenHashSet kept = RideSelector.select(sets, metric, SelectionRule.PER_REQUEST_TOP_K, 1, 0.0);
        assertEquals(IntOpenHashSet.of(0, 1), kept);
    }
    @Test void kZeroOrNegativeKeepsEverything() {
        IntOpenHashSet kept = RideSelector.select(SETS, METRIC, SelectionRule.COVERAGE_TOPK, 0, 0.0);
        assertEquals(5, kept.size());
    }
}
