package org.matsim.contrib.demand_extraction.algorithm.extension;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

class ForbiddenPrefixIndexTest {

	@Test
	void recordTripleAndLookup() {
		ForbiddenPrefixIndex index = new ForbiddenPrefixIndex();

		// Record: sequence (10, 20, 30) is infeasible.
		// Insert: index[(10, 20)] += 30
		index.recordPending(new int[]{10, 20, 30});
		index.commit();

		IntOpenHashSet forbidden = index.lookup(new int[]{10, 20});
		assertNotNull(forbidden);
		assertTrue(forbidden.contains(30));
	}

	@Test
	void lookupMissingReturnsNull() {
		ForbiddenPrefixIndex index = new ForbiddenPrefixIndex();
		assertNull(index.lookup(new int[]{1, 2}));
	}

	@Test
	void multipleForbiddenForSamePrefix() {
		ForbiddenPrefixIndex index = new ForbiddenPrefixIndex();
		index.recordPending(new int[]{10, 20, 30});
		index.recordPending(new int[]{10, 20, 40});
		index.commit();

		IntOpenHashSet forbidden = index.lookup(new int[]{10, 20});
		assertEquals(2, forbidden.size());
		assertTrue(forbidden.contains(30));
		assertTrue(forbidden.contains(40));
	}
}
