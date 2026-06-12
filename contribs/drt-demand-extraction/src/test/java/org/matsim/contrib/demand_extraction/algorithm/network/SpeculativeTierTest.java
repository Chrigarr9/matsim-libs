package org.matsim.contrib.demand_extraction.algorithm.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;

class SpeculativeTierTest {

	private static TravelSegment seg(double v) {
		return new TravelSegment(v, v, -v);
	}

	@Test
	void insertedEntryIsFound() {
		SpeculativeTier tier = new SpeculativeTier();
		tier.put(1L, seg(1.0));
		assertEquals(1.0, tier.get(1L).getTravelTime());
	}

	@Test
	void putIsFirstWriteWins() {
		SpeculativeTier tier = new SpeculativeTier();
		tier.put(1L, seg(1.0));
		tier.put(1L, seg(2.0));
		assertEquals(1.0, tier.get(1L).getTravelTime());
	}

	@Test
	void rotationDropsOldGenerationOnly() {
		SpeculativeTier tier = new SpeculativeTier();
		tier.put(1L, seg(1.0));      // young
		tier.rotate();               // 1 -> old
		tier.put(2L, seg(2.0));      // young
		assertNotNull(tier.get(1L)); // still readable from old
		assertNotNull(tier.get(2L));
		tier.rotate();               // drop old (1), 2 -> old
		assertNull(tier.get(1L));
		assertNotNull(tier.get(2L));
		tier.rotate();               // drop old (2)
		assertNull(tier.get(2L));
	}

	@Test
	void marksRotateWithSegments() {
		SpeculativeTier tier = new SpeculativeTier();
		tier.markSssp(100L);
		assertTrue(tier.isSsspDone(100L));
		tier.rotate();
		assertTrue(tier.isSsspDone(100L)); // survives one rotation (old generation)
		tier.rotate();
		assertFalse(tier.isSsspDone(100L)); // dropped together with its segments
	}

	@Test
	void removeDeletesFromBothGenerations() {
		// used by promotion: promoted keys are removed from the speculative tier
		SpeculativeTier tier = new SpeculativeTier();
		tier.put(1L, seg(1.0));
		tier.rotate();
		tier.put(1L, seg(1.0)); // same key in young too (idempotent duplicate fill)
		tier.remove(1L);
		assertNull(tier.get(1L));
	}

	@Test
	void sizeCountsBothGenerations() {
		SpeculativeTier tier = new SpeculativeTier();
		tier.put(1L, seg(1.0));
		tier.rotate();
		tier.put(2L, seg(2.0));
		assertEquals(2, tier.size());
		tier.rotate();
		assertEquals(1, tier.size());
	}
}
