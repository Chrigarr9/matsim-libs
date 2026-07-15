package org.matsim.contrib.demand_extraction.algorithm.generation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

class PairGeneratorTopKCapTest {

	@Test
	void kZeroKeepsEverything() {
		int[] partner = {10, 11, 12};
		double[] saving = {1.0, 2.0, 3.0};
		assertArrayEquals(new boolean[]{true, true, true},
				PairGenerator.keepMask(partner, saving, 0));
	}

	@Test
	void fewerPartnersThanKKeepsEverything() {
		int[] partner = {10, 11};
		double[] saving = {1.0, 2.0};
		assertArrayEquals(new boolean[]{true, true},
				PairGenerator.keepMask(partner, saving, 5));
	}

	@Test
	void keepsTopKPartnersByBestSaving() {
		// partners 10,11,12,13 with savings 5,1,9,3 → top-2 = {12 (9), 10 (5)}
		int[] partner = {10, 11, 12, 13};
		double[] saving = {5.0, 1.0, 9.0, 3.0};
		assertArrayEquals(new boolean[]{true, false, true, false},
				PairGenerator.keepMask(partner, saving, 2));
	}

	@Test
	void multipleRowsPerPartnerRankedByPartnerBest() {
		// partner 10 has FIFO row saving 2 and LIFO row saving 8 → best=8 (top)
		// partner 11 has one row saving 5; partner 12 one row saving 1.
		// top-2 partners = {10 (best 8), 11 (5)} → both of 10's rows kept, 12 dropped.
		int[] partner = {10, 10, 11, 12};
		double[] saving = {2.0, 8.0, 5.0, 1.0};
		assertArrayEquals(new boolean[]{true, true, true, false},
				PairGenerator.keepMask(partner, saving, 2));
	}

	@Test
	void tieBrokenByPartnerIndexAscending() {
		// partners 20 and 21 both best-saving 4.0; K=1 → keep lower index 20.
		int[] partner = {21, 20};
		double[] saving = {4.0, 4.0};
		assertArrayEquals(new boolean[]{false, true},
				PairGenerator.keepMask(partner, saving, 1));
	}

	// --- validity-aware selection (keepMaskValid): top-K counts only budget-valid partners ---

	@Test
	void invalidRowsAreNeverKept() {
		int[] partner = {10};
		double[] saving = {5.0};
		boolean[] valid = {false};
		assertArrayEquals(new boolean[]{false},
				PairGenerator.keepMaskValid(partner, saving, valid, 5));
	}

	@Test
	void partnerWithOnlyInvalidVariantsNotCounted() {
		// partner 10 has two invalid variants; partner 11 one valid. K=1 → keep 11 only.
		int[] partner = {10, 10, 11};
		double[] saving = {9.0, 8.0, 1.0};
		boolean[] valid = {false, false, true};
		assertArrayEquals(new boolean[]{false, false, true},
				PairGenerator.keepMaskValid(partner, saving, valid, 1));
	}

	@Test
	void rankedByBestVALIDsavingNotPreValidationSaving() {
		// partner 10: FIFO saving 100 INVALID, LIFO saving 50 valid → ranks at 50.
		// partner 11: saving 80 valid. K=1 → 11 wins (80 > 50), not 10 (pre-val 100).
		int[] partner = {10, 10, 11};
		double[] saving = {100.0, 50.0, 80.0};
		boolean[] valid = {false, true, true};
		assertArrayEquals(new boolean[]{false, false, true},
				PairGenerator.keepMaskValid(partner, saving, valid, 1));
	}

	@Test
	void keepsBothValidVariantsOfKeptPartner() {
		// partner 10 has two valid variants (8, 6); partner 11 one (5). K=1 → keep both of 10.
		int[] partner = {10, 10, 11};
		double[] saving = {8.0, 6.0, 5.0};
		boolean[] valid = {true, true, true};
		assertArrayEquals(new boolean[]{true, true, false},
				PairGenerator.keepMaskValid(partner, saving, valid, 1));
	}

	@Test
	void dropsInvalidVariantOfKeptPartner() {
		// partner 10: FIFO 8 valid, LIFO 6 INVALID. K=1 → keep partner 10, but only its valid row.
		int[] partner = {10, 10};
		double[] saving = {8.0, 6.0};
		boolean[] valid = {true, false};
		assertArrayEquals(new boolean[]{true, false},
				PairGenerator.keepMaskValid(partner, saving, valid, 1));
	}

	// --- EXT-13: partners are COMMUTERS, not request copies ---
	// The 5-arg overload groups rows by a per-commuter slot id (all hub copies / sync
	// variants of one person share a slot), while ties are broken on the separate
	// reqJ.index tie-key so the no-copies path stays byte-identical to the old cap.

	@Test
	void copiesOfOnePersonCollapseToOneSlotSoSingleRequestPartnerSurvives() {
		// Anchor's partners: person B contributes 3 request copies (distinct reqJ.index
		// 10,11,12) each with a BETTER saving than person C's single request (reqJ.index 13).
		// slot: B→0 (rows 0-2), C→1 (row 3). pairgenTopK=2 → only 2 distinct commuters, keep all.
		// OLD (group by reqJ.index): partners {10,11,12,13}, top-2 = {10,11} → C DROPPED (the bug).
		int[] slot      = {0, 0, 0, 1};
		int[] reqJIndex = {10, 11, 12, 13};
		double[] saving = {9.0, 8.0, 7.0, 5.0};
		boolean[] valid = {true, true, true, true};
		assertArrayEquals(new boolean[]{true, true, true, true},
				PairGenerator.keepMaskValid(slot, reqJIndex, saving, valid, 2));
	}

	@Test
	void oneCommuterCopiesFillOnlyOneOfTwoSlots() {
		// person B: 3 copies (slot 0) savings 9,8,7; persons C (slot 1, saving 6) and
		// D (slot 2, saving 5). pairgenTopK=2 → distinct commuters ranked by best saving:
		// B(9), C(6), D(5) → keep {B, C}. B's 3 copies occupy ONE slot, D dropped.
		int[] slot      = {0, 0, 0, 1, 2};
		int[] reqJIndex = {10, 11, 12, 13, 14};
		double[] saving = {9.0, 8.0, 7.0, 6.0, 5.0};
		boolean[] valid = {true, true, true, true, true};
		assertArrayEquals(new boolean[]{true, true, true, true, false},
				PairGenerator.keepMaskValid(slot, reqJIndex, saving, valid, 2));
	}

	@Test
	void tieBrokenByReqJIndexNotSlotFirstSeenOrder() {
		// Two distinct commuters, equal best saving. Slots are assigned in first-seen order
		// (P→0, Q→1), but Q's reqJ.index (20) is lower than P's (21). K=1 → the tie must break
		// on reqJ.index ascending (keep Q), NOT on slot order (which would keep P). This locks
		// the byte-parity of the no-copies path against the old reqJ.index tiebreak.
		int[] slot      = {0, 1};
		int[] reqJIndex = {21, 20};
		double[] saving = {4.0, 4.0};
		boolean[] valid = {true, true};
		assertArrayEquals(new boolean[]{false, true},
				PairGenerator.keepMaskValid(slot, reqJIndex, saving, valid, 1));
	}
}
