package org.matsim.contrib.demand_extraction.algorithm.bamas.ride;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RideLayer#mergeSorted}.
 *
 * <p>The merge must produce a deterministic total order over all rows from all
 * input buffers regardless of which buffer a row originated from or the order
 * in which the buffers appear in the collection.
 */
class RideLayerMergeTest {

	// ── helpers ───────────────────────────────────────────────────────────────

	/** Pack a simple identity origin/dest ordering for degree d. */
	private static long identityPacked(int d) {
		int[] perm = new int[d];
		for (int i = 0; i < d; i++) perm[i] = i;
		return OrderingCodec.pack(perm);
	}

	/** Build a single row into a fresh RideLayer. */
	private static RideLayer single(int[] sortedSet, long origPacked, long destPacked,
			int distDm, int ttDs, byte flags) {
		RideLayer sc = new RideLayer(sortedSet.length);
		sc.addRow(sortedSet, origPacked, destPacked, distDm, ttDs, flags);
		return sc;
	}

	// ── reference row tuple for assertions ───────────────────────────────────

	private record RowTuple(int[] set, long originOrder, long destOrder,
			int rideDistanceDm, int travelTimeDs, byte flags) {
		/** Extract from a RideLayer at a given row. */
		static RowTuple at(RideLayer sc, int row) {
			return new RowTuple(
					sc.requestIndices(row),
					sc.originOrder(row),
					sc.destOrder(row),
					sc.rideDistanceDm(row),
					sc.travelTimeDs(row),
					sc.flags(row)
			);
		}

		void assertEqualTo(RowTuple other, String context) {
			assertArrayEquals(set, other.set, context + " set");
			assertEquals(originOrder, other.originOrder, context + " originOrder");
			assertEquals(destOrder, other.destOrder, context + " destOrder");
			assertEquals(rideDistanceDm, other.rideDistanceDm, context + " rideDistanceDm");
			assertEquals(travelTimeDs, other.travelTimeDs, context + " travelTimeDs");
			assertEquals(flags, other.flags, context + " flags");
		}
	}

	// ── 3 buffers, degree-3, scrambled order → merged ascending lex ──────────

	/**
	 * Three buffers with degree-3 rows added in scrambled order across buffers.
	 * After merge, rows must appear in ascending lex order of their request-index slices.
	 * All per-row fields (originOrder, destOrder, rideDistanceDm, travelTimeDs, flags)
	 * must travel with their row.
	 */
	@Test
	void threeBuffersScrambledProducesAscendingLexOrder() {
		int d = 3;

		// Define 6 distinct sets and their expected row data.
		// Sets are intentionally given in non-ascending order across buffers.
		int[] setA = {1, 2, 3};   // smallest
		int[] setB = {1, 2, 5};
		int[] setC = {1, 4, 6};
		int[] setD = {2, 3, 7};
		int[] setE = {2, 4, 8};
		int[] setF = {3, 5, 9};   // largest

		long origA = OrderingCodec.pack(new int[]{0, 1, 2});
		long destA = OrderingCodec.pack(new int[]{2, 1, 0});

		long origB = OrderingCodec.pack(new int[]{1, 0, 2});
		long destB = OrderingCodec.pack(new int[]{0, 2, 1});

		long origC = OrderingCodec.pack(new int[]{2, 0, 1});
		long destC = OrderingCodec.pack(new int[]{1, 0, 2});

		long origD = OrderingCodec.pack(new int[]{0, 2, 1});
		long destD = OrderingCodec.pack(new int[]{2, 0, 1});

		long origE = OrderingCodec.pack(new int[]{1, 2, 0});
		long destE = OrderingCodec.pack(new int[]{0, 1, 2});

		long origF = OrderingCodec.pack(new int[]{2, 1, 0});
		long destF = OrderingCodec.pack(new int[]{1, 2, 0});

		// Buffer 0: sets D, A (out of order)
		RideLayer buf0 = new RideLayer(d);
		buf0.addRow(setD, origD, destD, 400, 40, (byte) 4);
		buf0.addRow(setA, origA, destA, 100, 10, (byte) 1);

		// Buffer 1: sets F, B, E (out of order)
		RideLayer buf1 = new RideLayer(d);
		buf1.addRow(setF, origF, destF, 600, 60, (byte) 6);
		buf1.addRow(setB, origB, destB, 200, 20, (byte) 2);
		buf1.addRow(setE, origE, destE, 500, 50, (byte) 5);

		// Buffer 2: set C only
		RideLayer buf2 = new RideLayer(d);
		buf2.addRow(setC, origC, destC, 300, 30, (byte) 3);

		List<RideLayer> buffers = List.of(buf0, buf1, buf2);
		RideLayer merged = RideLayer.mergeSorted(buffers);

		assertEquals(6, merged.size(), "merged must contain all 6 rows");
		assertEquals(d, merged.degree(), "merged degree must equal input degree");

		// Expected order: A, B, C, D, E, F (lex on set)
		int[][] expectedSets = { setA, setB, setC, setD, setE, setF };
		long[] expectedOrigOrder = { origA, origB, origC, origD, origE, origF };
		long[] expectedDestOrder = { destA, destB, destC, destD, destE, destF };
		int[] expectedDist  = { 100, 200, 300, 400, 500, 600 };
		int[] expectedTt    = {  10,  20,  30,  40,  50,  60 };
		byte[] expectedFlags = { 1, 2, 3, 4, 5, 6 };

		for (int r = 0; r < 6; r++) {
			assertArrayEquals(expectedSets[r], merged.requestIndices(r),
					"row " + r + " set must be " + Arrays.toString(expectedSets[r]));
			assertEquals(expectedOrigOrder[r], merged.originOrder(r),
					"row " + r + " originOrder");
			assertEquals(expectedDestOrder[r], merged.destOrder(r),
					"row " + r + " destOrder");
			assertEquals(expectedDist[r], merged.rideDistanceDm(r),
					"row " + r + " rideDistanceDm");
			assertEquals(expectedTt[r], merged.travelTimeDs(r),
					"row " + r + " travelTimeDs");
			assertEquals(expectedFlags[r], merged.flags(r),
					"row " + r + " flags");
		}
	}

	// ── order-independence: same result regardless of buffer iteration order ──

	/**
	 * Merging the same set of buffers in reversed collection order must produce
	 * an identical merged container (same size, same per-row tuples).
	 *
	 * <p>This is the determinism guarantee: because each request-set is claimed
	 * exactly once by the producer (see {@code claimedHashes} in
	 * {@code BamasRideExtender.extendRides}), slice keys are globally unique across
	 * all buffers, yielding a total order independent of buffer iteration.
	 */
	@Test
	void orderIndependentOfBufferIterationOrder() {
		int d = 3;

		RideLayer buf0 = new RideLayer(d);
		buf0.addRow(new int[]{5, 6, 7}, identityPacked(d), identityPacked(d), 500, 50, (byte) 5);
		buf0.addRow(new int[]{1, 2, 3}, identityPacked(d), identityPacked(d), 100, 10, (byte) 1);

		RideLayer buf1 = new RideLayer(d);
		buf1.addRow(new int[]{3, 4, 5}, identityPacked(d), identityPacked(d), 300, 30, (byte) 3);

		RideLayer buf2 = new RideLayer(d);
		buf2.addRow(new int[]{2, 4, 6}, identityPacked(d), identityPacked(d), 200, 20, (byte) 2);
		buf2.addRow(new int[]{4, 7, 8}, identityPacked(d), identityPacked(d), 400, 40, (byte) 4);

		// Forward order
		List<RideLayer> forward  = List.of(buf0, buf1, buf2);
		// Reversed order
		List<RideLayer> reversed = List.of(buf2, buf1, buf0);

		RideLayer mergedFwd = RideLayer.mergeSorted(forward);
		RideLayer mergedRev = RideLayer.mergeSorted(reversed);

		assertEquals(mergedFwd.size(), mergedRev.size(),
				"merged size must be independent of buffer order");

		// Assert the full per-row tuple matches, not just size.
		for (int r = 0; r < mergedFwd.size(); r++) {
			RowTuple fwd = RowTuple.at(mergedFwd, r);
			RowTuple rev = RowTuple.at(mergedRev, r);
			fwd.assertEqualTo(rev, "row " + r);
		}
	}

	// ── single buffer passes through sorted ───────────────────────────────────

	@Test
	void singleBufferPassesThroughSorted() {
		int d = 2;
		RideLayer buf = new RideLayer(d);
		buf.addRow(new int[]{5, 9}, identityPacked(d), identityPacked(d), 90, 9, (byte) 9);
		buf.addRow(new int[]{1, 3}, identityPacked(d), identityPacked(d), 13, 1, (byte) 1);
		buf.addRow(new int[]{2, 7}, identityPacked(d), identityPacked(d), 27, 2, (byte) 2);

		RideLayer merged = RideLayer.mergeSorted(List.of(buf));

		assertEquals(3, merged.size());
		assertArrayEquals(new int[]{1, 3}, merged.requestIndices(0));
		assertArrayEquals(new int[]{2, 7}, merged.requestIndices(1));
		assertArrayEquals(new int[]{5, 9}, merged.requestIndices(2));
		assertEquals(13, merged.rideDistanceDm(0));
		assertEquals(27, merged.rideDistanceDm(1));
		assertEquals(90, merged.rideDistanceDm(2));
	}

	// ── mixed-size buffers ───────────────────────────────────────────────────

	@Test
	void mixedSizeBuffersProduceCorrectCount() {
		int d = 2;
		RideLayer buf0 = new RideLayer(d);
		buf0.addRow(new int[]{1, 2}, identityPacked(d), identityPacked(d), 12, 1, (byte) 0);

		RideLayer buf1 = new RideLayer(d);
		buf1.addRow(new int[]{3, 4}, identityPacked(d), identityPacked(d), 34, 3, (byte) 0);
		buf1.addRow(new int[]{5, 6}, identityPacked(d), identityPacked(d), 56, 5, (byte) 0);
		buf1.addRow(new int[]{7, 8}, identityPacked(d), identityPacked(d), 78, 7, (byte) 0);

		RideLayer buf2 = new RideLayer(d);
		// empty

		RideLayer merged = RideLayer.mergeSorted(List.of(buf0, buf1, buf2));

		assertEquals(4, merged.size());
		assertArrayEquals(new int[]{1, 2}, merged.requestIndices(0));
		assertArrayEquals(new int[]{3, 4}, merged.requestIndices(1));
		assertArrayEquals(new int[]{5, 6}, merged.requestIndices(2));
		assertArrayEquals(new int[]{7, 8}, merged.requestIndices(3));
	}

	// ── degree mismatch rejects ───────────────────────────────────────────────

	@Test
	void degreeMismatchThrowsIllegalArgument() {
		RideLayer d2 = new RideLayer(2);
		d2.addRow(new int[]{1, 2}, 0L, 0L, 10, 1, (byte) 0);

		RideLayer d3 = new RideLayer(3);
		d3.addRow(new int[]{1, 2, 3}, 0L, 0L, 10, 1, (byte) 0);

		assertThrows(IllegalArgumentException.class,
				() -> RideLayer.mergeSorted(List.of(d2, d3)),
				"mergeSorted must reject mixed-degree input");
	}

	// ── empty collection returns empty container of specified degree ──────────

	@Test
	void emptyCollectionThrows() {
		// The single-parameter mergeSorted requires non-empty (no degree info available).
		assertThrows(IllegalArgumentException.class,
				() -> RideLayer.mergeSorted(List.of()),
				"mergeSorted with empty collection must throw IllegalArgumentException");
	}
}
