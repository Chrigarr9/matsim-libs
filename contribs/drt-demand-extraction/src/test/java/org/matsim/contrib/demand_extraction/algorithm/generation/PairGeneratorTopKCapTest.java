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
}
