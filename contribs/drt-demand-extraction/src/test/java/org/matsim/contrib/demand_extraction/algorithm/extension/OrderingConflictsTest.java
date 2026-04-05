package org.matsim.contrib.demand_extraction.algorithm.extension;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.contrib.demand_extraction.algorithm.extension.OrderingConflicts.*;

/**
 * Unit tests for {@link OrderingConflicts}.
 *
 * <p>Covers stop encoding, hashing properties, record/commit lifecycle,
 * subsequence matching, edge cases, and thread-safety contract boundaries.
 */
class OrderingConflictsTest {

	// ── Stop encoding ───────────────────────────────────────────────────

	@Test
	void testStopEncoding() {
		// Request 0: origin=0, dest=1
		assertEquals(0, originStop(0));
		assertEquals(1, destStop(0));

		// Request 1: origin=2, dest=3
		assertEquals(2, originStop(1));
		assertEquals(3, destStop(1));

		// Request 5: origin=10, dest=11
		assertEquals(10, originStop(5));
		assertEquals(11, destStop(5));

		// Origins are always even, destinations always odd
		for (int i = 0; i < 100; i++) {
			assertEquals(0, originStop(i) % 2, "Origin of request " + i + " should be even");
			assertEquals(1, destStop(i) % 2, "Dest of request " + i + " should be odd");
		}
	}

	// ── Hashing ─────────────────────────────────────────────────────────

	@Test
	void testHashDeterministic() {
		int[] seq = {0, 2, 4};
		long h1 = hash(seq, 0, 3);
		long h2 = hash(seq, 0, 3);
		assertEquals(h1, h2);
	}

	@Test
	void testHashOrderSensitive() {
		int[] forward = {0, 2, 4};
		int[] reversed = {4, 2, 0};
		assertNotEquals(hash(forward, 0, 3), hash(reversed, 0, 3));
	}

	// ── Record + commit lifecycle ───────────────────────────────────────

	@Test
	void testRecordAndCommitTriple() {
		OrderingConflicts conflicts = new OrderingConflicts(6);
		int[] seq = {0, 2, 4};
		conflicts.recordPending(seq, 0, 3);

		// Before commit: nothing visible
		assertEquals(0, conflicts.getConflictCount());

		conflicts.commit();
		assertEquals(1, conflicts.getConflictCount());
		assertEquals(1, conflicts.getConflictCount(3));
	}

	// ── hasConflict: basic triple ───────────────────────────────────────

	@Test
	void testHasConflictBasicTriple() {
		OrderingConflicts conflicts = new OrderingConflicts(6);
		// Record conflict (0, 2, 4)
		int[] seq = {0, 2, 4};
		conflicts.recordPending(seq, 0, 3);
		conflicts.commit();

		// Path [0, 2] + candidate 4 should match
		int[] path = {0, 2};
		assertTrue(conflicts.hasConflict(path, 2, 4));
	}

	// ── hasConflict: subsequence matching ───────────────────────────────

	@Test
	void testHasConflictSubsequenceMatch() {
		OrderingConflicts conflicts = new OrderingConflicts(6);
		// Record conflict (0, 2, 4)
		int[] seq = {0, 2, 4};
		conflicts.recordPending(seq, 0, 3);
		conflicts.commit();

		// Path [0, 6, 2] + candidate 4: subsequence (0, 2, 4) is present
		int[] path = {0, 6, 2};
		assertTrue(conflicts.hasConflict(path, 3, 4));
	}

	// ── hasConflict: no match ───────────────────────────────────────────

	@Test
	void testHasConflictNoMatch() {
		OrderingConflicts conflicts = new OrderingConflicts(6);
		// Record conflict (0, 2, 4)
		int[] seq = {0, 2, 4};
		conflicts.recordPending(seq, 0, 3);
		conflicts.commit();

		// Path [6, 8] + candidate 4: none of conflict stops in path
		int[] path = {6, 8};
		assertFalse(conflicts.hasConflict(path, 2, 4));
	}

	// ── hasConflict: order matters ──────────────────────────────────────

	@Test
	void testHasConflictOrderMatters() {
		OrderingConflicts conflicts = new OrderingConflicts(6);
		// Record conflict (0, 2, 4)
		int[] seq = {0, 2, 4};
		conflicts.recordPending(seq, 0, 3);
		conflicts.commit();

		// Path [2, 0] + candidate 4: wrong order (2 before 0), should NOT match
		int[] path = {2, 0};
		assertFalse(conflicts.hasConflict(path, 2, 4));
	}

	// ── Mixed origin/dest conflicts ─────────────────────────────────────

	@Test
	void testMixedOriginDestConflict() {
		OrderingConflicts conflicts = new OrderingConflicts(6);
		// Conflict: (O_0, O_1, D_1) = (0, 2, 3)
		int[] seq = {originStop(0), originStop(1), destStop(1)};
		conflicts.recordPending(seq, 0, 3);
		conflicts.commit();

		// Path [O_0, O_1] + candidate D_1 should match
		int[] path = {originStop(0), originStop(1)};
		assertTrue(conflicts.hasConflict(path, 2, destStop(1)));

		// Path [O_0, D_0, O_1] + candidate D_1: subsequence (O_0, O_1, D_1) present
		int[] path2 = {originStop(0), destStop(0), originStop(1)};
		assertTrue(conflicts.hasConflict(path2, 3, destStop(1)));
	}

	// ── Edge cases ──────────────────────────────────────────────────────

	@Test
	void testLengthBeyondMaxIgnored() {
		OrderingConflicts conflicts = new OrderingConflicts(4);
		// Try to record a length-5 conflict
		int[] seq = {0, 2, 4, 6, 8};
		conflicts.recordPending(seq, 0, 5);
		conflicts.commit();

		assertEquals(0, conflicts.getConflictCount());
	}

	@Test
	void testLength2Ignored() {
		OrderingConflicts conflicts = new OrderingConflicts(6);
		// Length 2 is below MIN_LENGTH (3)
		int[] seq = {0, 2};
		conflicts.recordPending(seq, 0, 2);
		conflicts.commit();

		assertEquals(0, conflicts.getConflictCount());
	}

	@Test
	void testEmptyConflictsNoMatch() {
		OrderingConflicts conflicts = new OrderingConflicts(6);
		int[] path = {0, 2};
		assertFalse(conflicts.hasConflict(path, 2, 4));
	}

	@Test
	void testNullConflictsNoMatch() {
		int[] path = {0, 2};
		assertFalse(hasConflictSafe(null, path, 2, 4));
	}

	// ── Multiple lengths ────────────────────────────────────────────────

	@Test
	void testMultipleLengths() {
		OrderingConflicts conflicts = new OrderingConflicts(6);

		// Record a triple (0, 2, 4) and a quad (0, 2, 4, 6)
		int[] triple = {0, 2, 4};
		int[] quad = {0, 2, 4, 6};
		conflicts.recordPending(triple, 0, 3);
		conflicts.recordPending(quad, 0, 4);
		conflicts.commit();

		assertEquals(2, conflicts.getConflictCount());
		assertEquals(1, conflicts.getConflictCount(3));
		assertEquals(1, conflicts.getConflictCount(4));

		// Triple match: path [0, 2] + candidate 4
		assertTrue(conflicts.hasConflict(new int[]{0, 2}, 2, 4));

		// Quad match: path [0, 2, 4] + candidate 6
		assertTrue(conflicts.hasConflict(new int[]{0, 2, 4}, 3, 6));

		// Quad as subsequence: path [0, 8, 2, 10, 4] + candidate 6
		assertTrue(conflicts.hasConflict(new int[]{0, 8, 2, 10, 4}, 5, 6));
	}

	// ── Duplicate deduplication ─────────────────────────────────────────

	@Test
	void testDuplicateRecordingNoDuplicate() {
		OrderingConflicts conflicts = new OrderingConflicts(6);
		int[] seq = {0, 2, 4};
		conflicts.recordPending(seq, 0, 3);
		conflicts.recordPending(seq, 0, 3);
		conflicts.commit();

		// LongOpenHashSet deduplicates, so count should be 1
		assertEquals(1, conflicts.getConflictCount());
	}
}
