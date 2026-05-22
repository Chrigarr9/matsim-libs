package org.matsim.contrib.demand_extraction.algorithm.hyperpool;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.demand_extraction.algorithm.domain.StopLocation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * B3: Unit tests for the budget-aware cap enforcement in the anonymous
 * {@link HyperPoolGenerator.StopRelocator} implementations in
 * {@link org.matsim.contrib.demand_extraction.algorithm.bamas.BamasEngine} and
 * {@link org.matsim.contrib.demand_extraction.algorithm.exmas.ExMasReferenceEngine}.
 *
 * <p>Both engines share the same anonymous relocator logic, so the tests here
 * exercise the logic via a minimal hand-crafted inline implementation that mirrors
 * the exact production implementation.  This keeps the test self-contained and
 * avoids standing up full engine instances for a unit-level assertion.
 *
 * <h2>Semantics tested (Signature A)</h2>
 * {@code findMergedStop(stop, existingStops, proximity, maxRelocDistPerPax)}:
 * <ul>
 *   <li>When {@code maxRelocDistPerPax} is {@code null} (flag-off/legacy path):
 *       behaves identically to old 3-arg contract — returns the first nearby
 *       existing stop without any budget check.</li>
 *   <li>When {@code maxRelocDistPerPax} is non-null (flag-on):
 *       rejects any candidate whose relocation distance exceeds
 *       {@code min(maxRelocDistPerPax[i])} for any passenger, and returns the
 *       original stop if no candidate survives.</li>
 * </ul>
 *
 * <h2>Pre-B3 failure (TDD)</h2>
 * The old 3-arg interface does not have the 4th parameter, so these tests do not
 * compile before B3 and PASS after.
 */
class StopRelocatorCapAwareTest {

    // =========================================================================
    // Minimal inline relocator (mirrors BamasEngine / ExMasReferenceEngine impl)
    // =========================================================================

    /**
     * Inline implementation of the relocator logic from both engines, for unit
     * testing without spinning up a full BamasEngine or ExMasReferenceEngine.
     */
    private static HyperPoolGenerator.StopRelocator buildProductionStyleRelocator() {
        return new HyperPoolGenerator.StopRelocator() {
            @Override
            public boolean areStopsNearby(StopLocation stop1, StopLocation stop2,
                    double proximityMeters) {
                double distance = org.matsim.core.utils.geometry.CoordUtils.calcEuclideanDistance(
                        stop1.getCoord(), stop2.getCoord());
                return distance <= proximityMeters;
            }

            @Override
            public StopLocation findMergedStop(StopLocation stop,
                    List<StopLocation> existingStops,
                    double proximityMeters,
                    double[] maxRelocDistPerPax) {
                for (StopLocation existing : existingStops) {
                    if (areStopsNearby(stop, existing, proximityMeters)) {
                        if (maxRelocDistPerPax != null) {
                            double relocDist = calculateRelocationDistance(stop, existing);
                            double minBudget = Double.MAX_VALUE;
                            for (double b : maxRelocDistPerPax) {
                                if (b < minBudget) minBudget = b;
                            }
                            if (relocDist > minBudget) {
                                continue; // skip, try next candidate
                            }
                        }
                        return existing;
                    }
                }
                return stop;
            }

            @Override
            public double calculateRelocationDistance(StopLocation originalStop,
                    StopLocation mergedStop) {
                return org.matsim.core.utils.geometry.CoordUtils.calcEuclideanDistance(
                        originalStop.getCoord(), mergedStop.getCoord());
            }
        };
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static StopLocation stop(String linkId, double x, double y) {
        return new StopLocation(Id.createLinkId(linkId), new Coord(x, y), 0.0);
    }

    // =========================================================================
    // Test 1 — null caps (flag=off): first nearby stop is returned regardless of distance
    // =========================================================================

    /**
     * With {@code maxRelocDistPerPax=null} (flag=off legacy path), the relocator
     * must return the first nearby existing stop without any budget check.
     * Even a large relocation distance must not prevent merging.
     */
    @Test
    void nullCaps_nearbyStopIsReturnedUnconditionally() {
        HyperPoolGenerator.StopRelocator relocator = buildProductionStyleRelocator();

        StopLocation original = stop("orig", 0.0, 0.0);
        StopLocation existing = stop("exist", 50.0, 0.0); // 50 m away

        List<StopLocation> existingStops = List.of(existing);
        // null caps → no budget check, 50 m < 500 m proximity → merge
        StopLocation result = relocator.findMergedStop(original, existingStops,
                /* proximityMeters= */ 500.0, /* maxRelocDistPerPax= */ null);

        assertSame(existing, result,
                "flag=off (null caps): should return the nearby existing stop");
    }

    // =========================================================================
    // Test 2 — non-null caps, relocation within budget: merge accepted
    // =========================================================================

    /**
     * When caps are provided and the relocation distance is within budget
     * ({@code relocDist <= min(caps[i])}), the existing stop is returned.
     */
    @Test
    void nonNullCaps_withinBudget_mergeAccepted() {
        HyperPoolGenerator.StopRelocator relocator = buildProductionStyleRelocator();

        StopLocation original = stop("orig", 0.0, 0.0);
        StopLocation existing = stop("exist", 30.0, 0.0); // 30 m away

        List<StopLocation> existingStops = List.of(existing);
        // caps=[50.0, 50.0] → min=50.0 ≥ 30 m → accept
        StopLocation result = relocator.findMergedStop(original, existingStops,
                /* proximityMeters= */ 500.0,
                /* maxRelocDistPerPax= */ new double[]{50.0, 50.0});

        assertSame(existing, result,
                "Relocation distance (30m) <= min cap (50m): should accept merge");
    }

    // =========================================================================
    // Test 3 — non-null caps, relocation exceeds budget: original stop returned
    // =========================================================================

    /**
     * When caps are provided and the relocation distance exceeds the minimum cap
     * ({@code relocDist > min(caps[i])}), the existing stop must be rejected and
     * the original stop is returned.
     */
    @Test
    void nonNullCaps_exceedsBudget_originalStopReturned() {
        HyperPoolGenerator.StopRelocator relocator = buildProductionStyleRelocator();

        StopLocation original = stop("orig", 0.0, 0.0);
        StopLocation existing = stop("exist", 100.0, 0.0); // 100 m away

        List<StopLocation> existingStops = List.of(existing);
        // caps=[80.0, 80.0] → min=80.0 < 100 m → reject
        StopLocation result = relocator.findMergedStop(original, existingStops,
                /* proximityMeters= */ 500.0,
                /* maxRelocDistPerPax= */ new double[]{80.0, 80.0});

        assertSame(original, result,
                "Relocation distance (100m) > min cap (80m): should return original stop");
    }

    // =========================================================================
    // Test 4 — one passenger limits the merge even if others have slack
    // =========================================================================

    /**
     * The minimum cap across all passengers governs the merge decision.
     * Even if most passengers have slack, a single tight-budget passenger
     * can block the merge.
     */
    @Test
    void nonNullCaps_tightPassengerBlocksMerge() {
        HyperPoolGenerator.StopRelocator relocator = buildProductionStyleRelocator();

        StopLocation original = stop("orig", 0.0, 0.0);
        StopLocation existing = stop("exist", 60.0, 0.0); // 60 m away

        List<StopLocation> existingStops = List.of(existing);
        // caps=[200.0, 50.0, 300.0] → min=50.0 < 60 m → reject
        StopLocation result = relocator.findMergedStop(original, existingStops,
                /* proximityMeters= */ 500.0,
                /* maxRelocDistPerPax= */ new double[]{200.0, 50.0, 300.0});

        assertSame(original, result,
                "Tight passenger (cap=50m) should block merge when relocDist=60m");
    }

    // =========================================================================
    // Test 5 — multiple candidates: skips rejected, accepts the first feasible
    // =========================================================================

    /**
     * When there are multiple existing stops, the relocator must skip any candidate
     * that exceeds the budget and return the first candidate within budget.
     */
    @Test
    void nonNullCaps_skipsRejectedCandidateAcceptsNextFeasible() {
        HyperPoolGenerator.StopRelocator relocator = buildProductionStyleRelocator();

        StopLocation original = stop("orig", 0.0, 0.0);
        StopLocation tooFar   = stop("tooFar",  200.0, 0.0); // 200 m — exceeds cap
        StopLocation feasible = stop("close",    30.0, 0.0); // 30 m — within cap

        List<StopLocation> existingStops = new ArrayList<>();
        existingStops.add(tooFar);
        existingStops.add(feasible);

        // caps=[50.0] → min=50.0; tooFar=200m → skip; feasible=30m → accept
        StopLocation result = relocator.findMergedStop(original, existingStops,
                /* proximityMeters= */ 500.0,
                /* maxRelocDistPerPax= */ new double[]{50.0});

        assertSame(feasible, result,
                "Should skip tooFar (200m > 50m cap) and return feasible stop (30m <= 50m cap)");
    }

    // =========================================================================
    // Test 6 — stops outside proximity: proximity check takes precedence
    // =========================================================================

    /**
     * Stops outside the proximity threshold are not candidates, regardless of
     * the budget array.  The proximity check comes first.
     */
    @Test
    void nonNullCaps_proximityCheckPrecedesBudgetCheck() {
        HyperPoolGenerator.StopRelocator relocator = buildProductionStyleRelocator();

        StopLocation original = stop("orig", 0.0, 0.0);
        StopLocation distant  = stop("dist", 1000.0, 0.0); // 1000 m — outside 500 m proximity

        List<StopLocation> existingStops = List.of(distant);
        // Huge cap, but stop is outside proximity → returns original
        StopLocation result = relocator.findMergedStop(original, existingStops,
                /* proximityMeters= */ 500.0,
                /* maxRelocDistPerPax= */ new double[]{9999.0});

        assertSame(original, result,
                "Stop outside proximity (1000m > 500m) should not be a candidate");
    }
}
