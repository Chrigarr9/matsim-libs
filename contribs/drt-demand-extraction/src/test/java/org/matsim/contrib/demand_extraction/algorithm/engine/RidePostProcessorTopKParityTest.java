package org.matsim.contrib.demand_extraction.algorithm.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Parity gate for {@link RidePostProcessor.TopKScratch} against the sort it replaced.
 *
 * <p>The predecessor pass used to allocate one record per feasible candidate, collect them in a
 * growable list, sort by score with a stable comparator and take the first K. That is now a reused
 * bounded max-heap keyed on {@code (score, position)}. The heap is only correct if it selects the
 * SAME K elements, including at score ties, which the previous stable sort resolved in favour of
 * the lower position (candidates were appended in ascending position order).
 *
 * <p>The characterisation fixture in {@code RidePostProcessorWindowKeyTest} pins the boundary
 * tie-break but never fills a heap deeply enough for a sift-down fault to surface. These tests
 * drive it with hundreds of candidates, heavy score collisions, and every K from 1 upward,
 * comparing against a literal transcription of the old selection.
 */
class RidePostProcessorTopKParityTest {

	/** Verbatim transcription of the pre-heap selection: stable sort on score, then take K. */
	private static int[] referenceSelection(double[] score, int[] position, int k) {
		List<Integer> slots = new ArrayList<>();
		for (int i = 0; i < score.length; i++) {
			slots.add(i);
		}
		// Candidates were appended in ascending position order and List.sort is stable, so equal
		// scores retain that order.
		slots.sort(Comparator.comparingDouble(slot -> score[slot]));
		if (k > 0 && slots.size() > k) {
			slots = slots.subList(0, k);
		}
		int[] out = new int[slots.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = position[slots.get(i)];
		}
		Arrays.sort(out);   // the pass sorts successors downstream; only the SET matters here
		return out;
	}

	private static double referenceMean(double[] score, double[] travelTime, int k) {
		Integer[] slots = new Integer[score.length];
		for (int i = 0; i < slots.length; i++) {
			slots[i] = i;
		}
		Arrays.sort(slots, Comparator.comparingDouble(slot -> score[slot]));
		int kept = (k > 0) ? Math.min(k, slots.length) : slots.length;
		double sum = 0.0;
		for (int i = 0; i < kept; i++) {
			sum += travelTime[slots[i]];
		}
		return kept == 0 ? -1.0 : sum / kept;
	}

	private static RidePostProcessor.TopKScratch feed(double[] score, int[] position,
			double[] travelTime, int k) {
		RidePostProcessor.TopKScratch heap = new RidePostProcessor.TopKScratch();
		heap.reset(k);
		for (int i = 0; i < score.length; i++) {
			heap.offer(score[i], position[i], travelTime[i]);
		}
		return heap;
	}

	private static int[] sortedPositions(RidePostProcessor.TopKScratch heap) {
		int[] out = heap.positions();
		Arrays.sort(out);
		return out;
	}

	@Test
	void selectionMatchesTheStableSortOverRandomStreams() {
		Random rnd = new Random(20260731L);
		for (int trial = 0; trial < 400; trial++) {
			int n = 1 + rnd.nextInt(300);
			int k = 1 + rnd.nextInt(60);
			double[] score = new double[n];
			int[] position = new int[n];
			double[] travelTime = new double[n];
			for (int i = 0; i < n; i++) {
				// Coarse score granularity on purpose: forces frequent exact ties, which is where a
				// heap and a stable sort are most likely to disagree.
				score[i] = rnd.nextInt(25) * 100.0;
				position[i] = i;                       // ascending, as the real scan produces
				travelTime[i] = rnd.nextInt(1000);
			}
			assertArrayEquals(referenceSelection(score, position, k), sortedPositions(feed(score, position, travelTime, k)),
					"selection mismatch at trial " + trial + " (n=" + n + ", k=" + k + ")");
			assertEquals(referenceMean(score, travelTime, k), feed(score, position, travelTime, k).meanTravelTime(), 1e-9,
					"mean mismatch at trial " + trial + " (n=" + n + ", k=" + k + ")");
		}
	}

	@Test
	void allScoresIdenticalKeepsTheLowestPositions() {
		int n = 200;
		double[] score = new double[n];
		int[] position = new int[n];
		double[] travelTime = new double[n];
		for (int i = 0; i < n; i++) {
			score[i] = 42.0;               // total tie
			position[i] = i;
			travelTime[i] = i;
		}
		int[] kept = sortedPositions(feed(score, position, travelTime, 10));
		assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, kept,
				"under a total tie the stable sort kept the first ten by position");
	}

	@Test
	void descendingScoresStillKeepTheBestK() {
		// Worst case for a naive "replace root" heap: every candidate beats the current root, so
		// the heap is rebuilt on each offer.
		int n = 150;
		double[] score = new double[n];
		int[] position = new int[n];
		double[] travelTime = new double[n];
		for (int i = 0; i < n; i++) {
			score[i] = n - i;
			position[i] = i;
			travelTime[i] = i;
		}
		assertArrayEquals(referenceSelection(score, position, 7),
				sortedPositions(feed(score, position, travelTime, 7)));
	}

	@Test
	void unlimitedCapacityKeepsEverything() {
		int n = 130;
		double[] score = new double[n];
		int[] position = new int[n];
		double[] travelTime = new double[n];
		for (int i = 0; i < n; i++) {
			score[i] = (n - i) * 3.0;
			position[i] = i;
			travelTime[i] = 2 * i;
		}
		// maxSuccessors <= 0 means "keep every feasible successor"; the arrays must grow past their
		// initial capacity and no selection may occur.
		RidePostProcessor.TopKScratch heap = feed(score, position, travelTime, 0);
		assertEquals(n, heap.positions().length);
		assertArrayEquals(referenceSelection(score, position, 0), sortedPositions(heap));
		assertEquals(referenceMean(score, travelTime, 0), heap.meanTravelTime(), 1e-9);
	}

	@Test
	void resetClearsStateBetweenRides() {
		// The scratch is reused across every ride in a group, so a stale size or a stale array slot
		// would silently attribute one ride's successors to the next.
		RidePostProcessor.TopKScratch heap = new RidePostProcessor.TopKScratch();
		heap.reset(5);
		for (int i = 0; i < 40; i++) {
			heap.offer(i, i, i);
		}
		assertEquals(5, heap.positions().length);

		heap.reset(5);
		assertTrue(heap.isEmpty(), "reset must empty the heap");
		assertEquals(0, heap.positions().length);

		heap.offer(99.0, 7, 3.0);
		assertArrayEquals(new int[] {7}, heap.positions(), "no residue from the previous ride");
		assertEquals(3.0, heap.meanTravelTime(), 1e-9);
	}

	@Test
	void growingCapacityBetweenRidesIsSafe() {
		// reset(k) with a larger k than any previous ride must enlarge the backing arrays.
		RidePostProcessor.TopKScratch heap = new RidePostProcessor.TopKScratch();
		heap.reset(3);
		for (int i = 0; i < 10; i++) {
			heap.offer(i, i, i);
		}
		heap.reset(64);
		for (int i = 0; i < 100; i++) {
			heap.offer(100 - i, i, i);
		}
		assertEquals(64, heap.positions().length);
	}
}
