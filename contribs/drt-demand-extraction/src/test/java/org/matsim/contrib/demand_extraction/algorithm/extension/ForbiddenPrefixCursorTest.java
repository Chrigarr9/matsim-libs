package org.matsim.contrib.demand_extraction.algorithm.extension;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ForbiddenPrefixCursorTest {

	@Test
	void emptyIndexNeverForbids() {
		ForbiddenPrefixIndex index = new ForbiddenPrefixIndex();
		index.commit();
		ForbiddenPrefixCursor cursor = new ForbiddenPrefixCursor(index, 4);

		cursor.place(10);
		cursor.place(20);
		assertFalse(cursor.isForbidden(30));
		assertFalse(cursor.isForbidden(40));
	}

	@Test
	void recordedTripleForbidsThirdStop() {
		ForbiddenPrefixIndex index = new ForbiddenPrefixIndex();
		index.recordPending(new int[]{10, 20, 30});
		index.commit();

		ForbiddenPrefixCursor cursor = new ForbiddenPrefixCursor(index, 4);
		cursor.place(10);
		cursor.place(20);
		assertTrue(cursor.isForbidden(30));
		assertFalse(cursor.isForbidden(40));
	}

	@Test
	void differentOrderingDoesNotTrigger() {
		// Recorded: (10, 20, 30) infeasible. Sequence (20, 10, 30) is NOT.
		ForbiddenPrefixIndex index = new ForbiddenPrefixIndex();
		index.recordPending(new int[]{10, 20, 30});
		index.commit();

		ForbiddenPrefixCursor cursor = new ForbiddenPrefixCursor(index, 4);
		cursor.place(20);
		cursor.place(10);
		assertFalse(cursor.isForbidden(30));
	}

	@Test
	void backtrackRemovesForbiddenAdditions() {
		ForbiddenPrefixIndex index = new ForbiddenPrefixIndex();
		index.recordPending(new int[]{10, 20, 30});
		index.commit();

		ForbiddenPrefixCursor cursor = new ForbiddenPrefixCursor(index, 4);
		cursor.place(10);
		cursor.place(20);
		assertTrue(cursor.isForbidden(30));
		cursor.unplace(); // pop "20"
		assertFalse(cursor.isForbidden(30)); // now only (10) placed -> no triple to fire
	}

	@Test
	void quadKeyForbidsFourthStop() {
		ForbiddenPrefixIndex index = new ForbiddenPrefixIndex();
		index.recordPending(new int[]{10, 20, 30, 40});
		index.commit();

		ForbiddenPrefixCursor cursor = new ForbiddenPrefixCursor(index, 5);
		cursor.place(10);
		cursor.place(20);
		cursor.place(30);
		assertTrue(cursor.isForbidden(40));
		assertFalse(cursor.isForbidden(50));
	}

	@Test
	void subsequenceMatchesNotJustContiguous() {
		// Recorded: (10, 30, 50) infeasible. Place 10, 20, 30, 40 - then 50 should be forbidden
		// because the placed sequence contains (10, 30) as ordered subsequence.
		ForbiddenPrefixIndex index = new ForbiddenPrefixIndex();
		index.recordPending(new int[]{10, 30, 50});
		index.commit();

		ForbiddenPrefixCursor cursor = new ForbiddenPrefixCursor(index, 6);
		cursor.place(10);
		cursor.place(20);
		cursor.place(30);
		cursor.place(40);
		assertTrue(cursor.isForbidden(50));
	}

	@Test
	void deltaOverlapPreservesForbiddenAcrossUnplace() {
		// The load-bearing invariant: if a stop is independently forbidden by two
		// different sub-sequences at different depths, unplacing the deeper one
		// must NOT remove the stop from forbiddenSet (it's still forbidden by
		// the shallower one).
		//
		// Setup: index records (10, 20, 99) and (10, 30, 99). After placing
		// 10, 20, 30:
		//   - depth 0: place(10) -> no triples possible
		//   - depth 1: place(20) -> look up (10, 20) -> {99}. forbiddenSet={99}, delta[1]={99}
		//   - depth 2: place(30) -> look up (10, 30) -> {99}. 99 already in forbiddenSet,
		//              so delta[2]={} (empty)
		// Now unplace() at depth 2: removes nothing (delta[2] empty). 99 stays forbidden.
		// Then unplace() at depth 1: removes {99}. 99 no longer forbidden.
		ForbiddenPrefixIndex index = new ForbiddenPrefixIndex();
		index.recordPending(new int[]{10, 20, 99});
		index.recordPending(new int[]{10, 30, 99});
		index.commit();

		ForbiddenPrefixCursor cursor = new ForbiddenPrefixCursor(index, 5);
		cursor.place(10);
		cursor.place(20);
		assertTrue(cursor.isForbidden(99));
		cursor.place(30);
		assertTrue(cursor.isForbidden(99));
		cursor.unplace(); // pop 30 - must NOT remove 99
		assertTrue(cursor.isForbidden(99), "99 must still be forbidden because (10,20) still forbids it");
		cursor.unplace(); // pop 20 - now removes 99
		assertFalse(cursor.isForbidden(99));
	}

	@Test
	void unplaceThenPlaceDifferentStopReusesSlot() {
		// After unplace, the next place at the same depth should not see stale delta state.
		ForbiddenPrefixIndex index = new ForbiddenPrefixIndex();
		index.recordPending(new int[]{10, 20, 30});
		index.recordPending(new int[]{10, 40, 50});
		index.commit();

		ForbiddenPrefixCursor cursor = new ForbiddenPrefixCursor(index, 5);
		cursor.place(10);
		cursor.place(20);
		assertTrue(cursor.isForbidden(30));
		cursor.unplace();           // back to depth 1 (just 10)
		assertFalse(cursor.isForbidden(30));
		cursor.place(40);           // depth 2: 10, 40
		assertTrue(cursor.isForbidden(50));
		assertFalse(cursor.isForbidden(30));
	}

	@Test
	void capacityOverflowThrows() {
		ForbiddenPrefixIndex index = new ForbiddenPrefixIndex();
		index.commit();
		ForbiddenPrefixCursor cursor = new ForbiddenPrefixCursor(index, 2);
		cursor.place(10);
		cursor.place(20);
		assertThrows(IllegalStateException.class, () -> cursor.place(30));
	}

	@Test
	void unplaceUnderflowThrows() {
		ForbiddenPrefixIndex index = new ForbiddenPrefixIndex();
		index.commit();
		ForbiddenPrefixCursor cursor = new ForbiddenPrefixCursor(index, 4);
		assertThrows(IllegalStateException.class, () -> cursor.unplace());
	}
}
