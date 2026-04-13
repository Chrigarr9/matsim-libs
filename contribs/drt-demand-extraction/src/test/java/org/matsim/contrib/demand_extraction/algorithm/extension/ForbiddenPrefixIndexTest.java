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

	@Test
	void distinctPrefixesDoNotBleed() {
		// Two different prefixes must NOT share a forbidden set.
		ForbiddenPrefixIndex index = new ForbiddenPrefixIndex();
		index.recordPending(new int[]{10, 20, 30});
		index.recordPending(new int[]{40, 50, 60});
		index.commit();

		IntOpenHashSet f1 = index.lookup(new int[]{10, 20});
		IntOpenHashSet f2 = index.lookup(new int[]{40, 50});
		assertNotNull(f1); assertNotNull(f2);
		assertEquals(1, f1.size());
		assertEquals(1, f2.size());
		assertTrue(f1.contains(30));
		assertTrue(f2.contains(60));
	}

	@Test
	void recordedArrayCanBeMutatedAfterEnqueue() {
		// recordPending must defensively copy the input array.
		ForbiddenPrefixIndex index = new ForbiddenPrefixIndex();
		int[] seq = {10, 20, 30};
		index.recordPending(seq);
		seq[0] = 999; // mutate after enqueue
		seq[1] = 999;
		seq[2] = 999;
		index.commit();

		IntOpenHashSet f = index.lookup(new int[]{10, 20});
		assertNotNull(f);
		assertTrue(f.contains(30));
	}

	@Test
	void emptyCommitIsNoOp() {
		ForbiddenPrefixIndex index = new ForbiddenPrefixIndex();
		index.commit();
		assertEquals(0, index.size());
	}

	@Test
	void duplicateRecordIsIdempotent() {
		ForbiddenPrefixIndex index = new ForbiddenPrefixIndex();
		index.recordPending(new int[]{10, 20, 30});
		index.recordPending(new int[]{10, 20, 30});
		index.commit();

		IntOpenHashSet f = index.lookup(new int[]{10, 20});
		assertEquals(1, f.size());
	}

	@Test
	void recordQuadAndLookup() {
		ForbiddenPrefixIndex index = new ForbiddenPrefixIndex();
		// Record sequence (10, 20, 30, 40) — quad. Insert: index[(10,20,30)] += 40
		index.recordPending(new int[]{10, 20, 30, 40});
		index.commit();

		IntOpenHashSet f = index.lookup(new int[]{10, 20, 30});
		assertNotNull(f);
		assertTrue(f.contains(40));

		// The triple-prefix (10, 20) should NOT be in the index — recording quads
		// does not create entries for shorter prefixes.
		assertNull(index.lookup(new int[]{10, 20}));
	}

	@Test
	void recordsTooShortAreIgnored() {
		ForbiddenPrefixIndex index = new ForbiddenPrefixIndex();
		index.recordPending(new int[]{10, 20}); // length 2 — too short, prefix would be length 1
		index.commit();
		assertEquals(0, index.size());
	}

	@Test
	void maxRecordedKeyLengthTracksLongest() {
		ForbiddenPrefixIndex index = new ForbiddenPrefixIndex();
		index.recordPending(new int[]{10, 20, 30});
		index.recordPending(new int[]{10, 20, 30, 40, 50});
		index.recordPending(new int[]{10, 20, 30, 40});
		index.commit();
		assertEquals(4, index.getMaxRecordedKeyLength()); // quintuple has prefix length 4
	}

	@Test
	void rangeLookupMatchesArrayLookup() {
		// The (scratch, len) overload must match the plain (int[]) lookup, AND
		// any garbage beyond index `len` in the scratch buffer must not affect
		// the content-based equality used by the underlying IntArrayList key.
		ForbiddenPrefixIndex index = new ForbiddenPrefixIndex();
		index.recordPending(new int[]{10, 20, 30, 40});
		index.commit();

		int[] scratch = {10, 20, 30, 99, 99, 99};
		IntOpenHashSet f = index.lookup(scratch, 3);
		assertNotNull(f);
		assertTrue(f.contains(40));

		// Same identity object as the plain-array lookup.
		assertSame(index.lookup(new int[]{10, 20, 30}), f);
	}
}
