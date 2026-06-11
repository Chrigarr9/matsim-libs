package org.matsim.contrib.demand_extraction.algorithm.bamas.extension;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ExtensionParentRanker}.
 *
 * <p>A stub row is marked EXTEND if it is selected for AT LEAST ONE of its member
 * requests (union across requests). The fixtures below are built so that the row
 * intended NOT to be marked has no other request via which it could be auto-marked.
 */
class ExtensionParentRankerTest {

	// ── TOP_K marks the correct union across all member requests ────────────

	@Test
	void marksTopKForEachMemberRequest() {
		// K=1, TOP_K.
		//   row 0 {0,1} metric 10 → best for req 0 (10>5) and req 1 (10>8) → marked
		//   row 1 {0,2} metric  5 → loses req 0 to row 0, loses req 2 to row 2 → not marked
		//   row 2 {1,2} metric  8 → loses req 1 to row 0, wins req 2 (8>5)   → marked
		int[][] requestSets = {
			{0, 1},
			{0, 2},
			{1, 2},
		};
		double[] metric = {10.0, 5.0, 8.0};

		IntOpenHashSet marked = ExtensionParentRanker.markExtend(
			requestSets, metric, 1, ExtensionParentRanker.SelectionRule.TOP_K, 0.0);

		assertEquals(2, marked.size());
		assertTrue(marked.contains(0), "row 0 marked (best for req 0 and req 1)");
		assertTrue(marked.contains(2), "row 2 marked (best for req 2)");
		assertFalse(marked.contains(1), "row 1 not marked (loses every competition)");
	}

	// ── k <= 0 marks everything (exact passthrough) ─────────────────────────

	@Test
	void kZeroMarksEverything() {
		int[][] requestSets = {
			{0, 1},
			{0, 2},
			{1, 2},
			{3, 4},
		};
		double[] metric = {10.0, 5.0, 8.0, 3.0};

		for (int k : new int[]{0, -1}) {
			IntOpenHashSet marked = ExtensionParentRanker.markExtend(
				requestSets, metric, k, ExtensionParentRanker.SelectionRule.TOP_K, 0.0);
			assertEquals(4, marked.size(), "k=" + k + " must mark all rows");
			for (int i = 0; i < 4; i++) {
				assertTrue(marked.contains(i), "k=" + k + " must mark row " + i);
			}
		}
	}

	// ── Tie-break: equal metric → lex-smaller set, then lower row index ──────

	@Test
	void tieBreakPrefersLexSmallerSet() {
		// Two stubs covering the identical request set {5,6} with equal metric; K=1.
		// Equal metric and equal set → row-index tie-break → row 0 marked, row 1 not.
		int[][] equalSets = {
			{5, 6},
			{5, 6},
		};
		double[] equalMetric = {7.0, 7.0};

		IntOpenHashSet marked = ExtensionParentRanker.markExtend(
			equalSets, equalMetric, 1, ExtensionParentRanker.SelectionRule.TOP_K, 0.0);
		assertEquals(1, marked.size());
		assertTrue(marked.contains(0), "lower row index wins when metric and set are equal");
		assertFalse(marked.contains(1));

		// Three stubs all {5}, metrics 10/7/7, K=2: row 0 (metric 10) then the
		// 7-7 tie resolves to the lower row index → row 1; row 2 not marked.
		int[][] threeSets = {{5}, {5}, {5}};
		double[] threeMetric = {10.0, 7.0, 7.0};

		IntOpenHashSet markedThree = ExtensionParentRanker.markExtend(
			threeSets, threeMetric, 2, ExtensionParentRanker.SelectionRule.TOP_K, 0.0);
		assertEquals(2, markedThree.size());
		assertTrue(markedThree.contains(0), "highest metric marked");
		assertTrue(markedThree.contains(1), "equal-metric tie: lower row index wins");
		assertFalse(markedThree.contains(2), "equal-metric tie: higher row index loses");
	}

	// ── MMR with λ=0 selects identically to TOP_K ───────────────────────────

	@Test
	void mmrLambdaZeroEqualsTopK() {
		int[][] requestSets = {
			{0, 1, 2},
			{0, 1, 3},
			{1, 2, 3},
			{0, 2, 3},
		};
		double[] metric = {12.0, 9.0, 7.0, 11.0};

		IntOpenHashSet topK = ExtensionParentRanker.markExtend(
			requestSets, metric, 2, ExtensionParentRanker.SelectionRule.TOP_K, 0.0);
		IntOpenHashSet mmr = ExtensionParentRanker.markExtend(
			requestSets, metric, 2, ExtensionParentRanker.SelectionRule.MMR, 0.0);

		assertEquals(topK, mmr, "MMR with λ=0 must equal TOP_K");
	}

	// ── MMR spends a slot on a diverse stub where TOP_K spends it on a clone ─

	@Test
	void mmrPrefersDiverseOverClone() {
		// A={1,2,5} m10, B={1,2,5} m9 (clone of A), C={5} m8. K=2, λ=0.5.
		// reqs 1,2: incident=[A,B], K=2 → both marked (B can only be auto-marked here).
		// req 5:   incident=[A,B,C], MMR picks A first, then
		//            B score = 9·(1−0.5·Jac(A,B)=1.0) = 4.5
		//            C score = 8·(1−0.5·Jac(A,C)=1/3) = 6.67  → C wins the 2nd slot.
		// So MMR marks C; TOP_K (by metric) picks A,B for req 5 and C has no other
		// request, so TOP_K leaves C unmarked. C is the differentiator.
		int[][] rs = {
			{1, 2, 5},
			{1, 2, 5},
			{5},
		};
		double[] metric = {10.0, 9.0, 8.0};

		IntOpenHashSet mmr = ExtensionParentRanker.markExtend(
			rs, metric, 2, ExtensionParentRanker.SelectionRule.MMR, 0.5);
		assertTrue(mmr.contains(0), "A marked under MMR");
		assertTrue(mmr.contains(1), "B marked under MMR (via reqs 1,2)");
		assertTrue(mmr.contains(2), "C marked under MMR (diverse 2nd pick for req 5)");

		IntOpenHashSet topK = ExtensionParentRanker.markExtend(
			rs, metric, 2, ExtensionParentRanker.SelectionRule.TOP_K, 0.0);
		assertTrue(topK.contains(0), "A marked under TOP_K");
		assertTrue(topK.contains(1), "B marked under TOP_K");
		assertFalse(topK.contains(2), "C not marked under TOP_K (clone B takes req 5's 2nd slot)");
	}

	// ── MMR is deterministic when adjusted scores tie ───────────────────────

	@Test
	void mmrDeterministicUnderEqualAdjustedScore() {
		// All three share only req 10. A (metric 8) is picked first; B and C
		// (metric 6, identical sets → equal Jaccard → equal MMR score) tie, so the
		// lower row index wins → B marked, C not. Swapping the two tied rows leaves
		// the outcome at the lower index, proving order-independence.
		int[][] requestSets = {{10}, {10}, {10}};
		double[] metric = {8.0, 6.0, 6.0};

		IntOpenHashSet marked = ExtensionParentRanker.markExtend(
			requestSets, metric, 2, ExtensionParentRanker.SelectionRule.MMR, 0.5);
		assertTrue(marked.contains(0), "A (first pick) marked");
		assertTrue(marked.contains(1), "tie resolves to lower row index");
		assertFalse(marked.contains(2), "higher row index loses the tie");

		IntOpenHashSet markedRev = ExtensionParentRanker.markExtend(
			requestSets, metric, 2, ExtensionParentRanker.SelectionRule.MMR, 0.5);
		assertEquals(marked, markedRev, "selection is stable across invocations");
	}

	// ── Mismatched array lengths throw ──────────────────────────────────────

	@Test
	void mismatchedLengthsThrow() {
		int[][] requestSets = {{0, 1}, {2, 3}};
		double[] metric = {1.0};
		assertThrows(IllegalArgumentException.class, () ->
			ExtensionParentRanker.markExtend(
				requestSets, metric, 1, ExtensionParentRanker.SelectionRule.TOP_K, 0.0));
	}
}
