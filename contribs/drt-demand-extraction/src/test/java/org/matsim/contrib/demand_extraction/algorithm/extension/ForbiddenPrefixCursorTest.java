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
}
