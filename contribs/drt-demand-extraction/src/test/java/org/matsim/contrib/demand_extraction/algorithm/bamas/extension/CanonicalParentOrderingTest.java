package org.matsim.contrib.demand_extraction.algorithm.bamas.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link BamasRideExtender#compareParentCanonicalKey(double, int[], double, int[])}.
 *
 * <p>Canonical parent rule: shortest routed distance wins; ties broken by lex order
 * on the parent's sorted request indices. This must be a strict total order so the
 * streaming producer can claim child-set hashes in a deterministic sequence.
 */
class CanonicalParentOrderingTest {

    @Test
    void shorterDistanceWinsOverLongerDistance() {
        int cmp = BamasRideExtender.compareParentCanonicalKey(
                100.0, new int[] { 5, 6, 7 },
                200.0, new int[] { 1, 2, 3 });
        assertTrue(cmp < 0, "shorter (100) must rank before longer (200) regardless of indices");
    }

    @Test
    void lexTiebreakWhenDistancesEqual() {
        int cmp = BamasRideExtender.compareParentCanonicalKey(
                150.0, new int[] { 1, 2, 3 },
                150.0, new int[] { 1, 2, 4 });
        assertTrue(cmp < 0, "equal distance → lex smaller indices wins");
    }

    @Test
    void lexTiebreakUsesFullIntArrayComparison() {
        int cmp = BamasRideExtender.compareParentCanonicalKey(
                150.0, new int[] { 1, 2, 9 },
                150.0, new int[] { 1, 3, 0 });
        assertTrue(cmp < 0, "lex on sorted indices: {1,2,9} < {1,3,0}");
    }

    @Test
    void equalDistanceAndEqualIndicesCompareToZero() {
        int cmp = BamasRideExtender.compareParentCanonicalKey(
                150.0, new int[] { 1, 2, 3 },
                150.0, new int[] { 1, 2, 3 });
        assertEquals(0, cmp, "identical inputs must compare equal");
    }

    @Test
    void antisymmetric() {
        double dA = 120.0, dB = 130.0;
        int[] iA = { 2, 4, 6 }, iB = { 1, 3, 5 };
        int forward = BamasRideExtender.compareParentCanonicalKey(dA, iA, dB, iB);
        int reverse = BamasRideExtender.compareParentCanonicalKey(dB, iB, dA, iA);
        assertTrue(forward < 0 && reverse > 0,
                "forward/reverse must have opposite sign (strict total order)");
    }

    @Test
    void epsilonTieTreatedAsExactTieFallsThroughToLex() {
        // Distances within EPSILON (1e-9) should be considered equal and fall
        // through to lex tiebreak. This guards against FP noise in routed
        // distance splitting canonical parents for structurally identical sets.
        int cmp = BamasRideExtender.compareParentCanonicalKey(
                150.0, new int[] { 1, 2, 3 },
                150.0 + 5e-10, new int[] { 1, 2, 4 });
        assertTrue(cmp < 0, "near-equal distances must fall through to lex (indices {1,2,3} < {1,2,4})");
    }
}
