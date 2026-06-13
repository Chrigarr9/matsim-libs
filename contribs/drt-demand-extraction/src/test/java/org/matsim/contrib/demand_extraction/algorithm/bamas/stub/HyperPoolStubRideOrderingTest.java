package org.matsim.contrib.demand_extraction.algorithm.bamas.stub;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;

/**
 * Plan A2 (hyperpool index canonicalization) — unit guard for
 * {@link HyperPoolStubRideStore#computeSortPermutation}.
 *
 * <p>This permutation is shared between the export ({@link HyperPoolStubRideStore}) and the
 * pre-bundling index stamping in {@code BamasEngine.generateHyperPooledRidesFromStubs}. The fix
 * rests on one property: the final S2S ride index is a pure function of
 * {@code (variant, degree, firstPickup) + insertion order} and is INDEPENDENT of the
 * mode-dependent pre-final {@code rideIndex} stamped on each stub row. These tests lock that
 * property so a future change cannot silently reintroduce the mode-dependent labeling that the
 * fix removed.
 */
class HyperPoolStubRideOrderingTest {

	/** Identity origin/dest ordering for degree d (so firstPickup == requestIndices[0]). */
	private static long identity(int d) {
		int[] pos = new int[d];
		for (int i = 0; i < d; i++) pos[i] = i;
		return OrderingCodec.pack(pos);
	}

	/** Append one S2S row with the given request set and pre-final rideIndex; other fields are filler. */
	private static void addRow(S2SStubColumns cols, int[] set, int rideIdx) {
		int d = set.length;
		double[] walk = new double[d];
		cols.addRow(set, identity(d), identity(d), 100, 200, (byte) 0, 1, 2, 28800.0, rideIdx, walk, walk);
	}

	/**
	 * Two S2S layers (degree 2 then degree 3); rows deliberately out of firstPickup order with a
	 * tie. The permutation must be stably sorted by (degree asc, firstPickup asc), and the final
	 * index of each row is its position in that order.
	 */
	@Test
	void permutationSortsS2SByDegreeThenFirstPickupStably() {
		S2SStubColumns d2 = new S2SStubColumns(2);
		addRow(d2, new int[]{5, 9}, 77);   // row 0: firstPickup 5
		addRow(d2, new int[]{1, 3}, 11);   // row 1: firstPickup 1
		addRow(d2, new int[]{1, 8}, 33);   // row 2: firstPickup 1 (ties row 1; row 1 inserted first)

		S2SStubColumns d3 = new S2SStubColumns(3);
		addRow(d3, new int[]{2, 4, 6}, 88); // row 0: degree 3, firstPickup 2

		List<S2SStubColumns> s2sLayers = List.of(d2, d3);
		int[][] perm = HyperPoolStubRideStore.computeSortPermutation(
				new ArrayList<>(), new ArrayList<>(), s2sLayers);
		int[] sourceOf   = perm[0];
		int[] localRowOf = perm[1];

		// nD2D == 0 (no D2D stub layers) → S2S source index == s2s layer index.
		// Expected order: d2.row1 (fp1), d2.row2 (fp1, stable after row1), d2.row0 (fp5), d3.row0.
		assertArrayEquals(new int[]{0, 0, 0, 1}, sourceOf,   "source layer per final position");
		assertArrayEquals(new int[]{1, 2, 0, 0}, localRowOf, "local row per final position");
	}

	/**
	 * The permutation must NOT depend on the pre-final {@code rideIndex} stamped on the rows — that
	 * value is threaded differently in the fat vs stub Phase-5 paths and is exactly what the fix
	 * stops consuming. Scrambling it must leave the permutation (and thus every final index)
	 * unchanged.
	 */
	@Test
	void permutationIndependentOfPreFinalRideIndex() {
		List<S2SStubColumns> a = buildTwoLayers(new int[]{77, 11, 33, 88});
		List<S2SStubColumns> b = buildTwoLayers(new int[]{999, 111, 222, 444});

		int[][] permA = HyperPoolStubRideStore.computeSortPermutation(new ArrayList<>(), new ArrayList<>(), a);
		int[][] permB = HyperPoolStubRideStore.computeSortPermutation(new ArrayList<>(), new ArrayList<>(), b);

		assertArrayEquals(permA[0], permB[0], "sourceOf must be independent of pre-final rideIndex");
		assertArrayEquals(permA[1], permB[1], "localRowOf must be independent of pre-final rideIndex");
	}

	/** Builds the same two-layer fixture with caller-supplied pre-final rideIndex values. */
	private static List<S2SStubColumns> buildTwoLayers(int[] rideIdx) {
		S2SStubColumns d2 = new S2SStubColumns(2);
		addRow(d2, new int[]{5, 9}, rideIdx[0]);
		addRow(d2, new int[]{1, 3}, rideIdx[1]);
		addRow(d2, new int[]{1, 8}, rideIdx[2]);
		S2SStubColumns d3 = new S2SStubColumns(3);
		addRow(d3, new int[]{2, 4, 6}, rideIdx[3]);
		return List.of(d2, d3);
	}

	/** Empty inputs must produce an empty permutation (no rides). */
	@Test
	void emptyInputsProduceEmptyPermutation() {
		int[][] perm = HyperPoolStubRideStore.computeSortPermutation(
				new ArrayList<Ride>(), new ArrayList<>(), new ArrayList<>());
		assertEquals(0, perm[0].length);
		assertEquals(0, perm[1].length);
	}
}
