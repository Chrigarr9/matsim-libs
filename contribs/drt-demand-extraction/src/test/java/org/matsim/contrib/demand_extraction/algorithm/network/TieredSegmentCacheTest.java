package org.matsim.contrib.demand_extraction.algorithm.network;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;

class TieredSegmentCacheTest {

	private static TravelSegment seg(double v) { return new TravelSegment(v, v, -v); }

	@Test
	void lookupOrderIsRetainedThenSpeculative() {
		TieredSegmentCache cache = new TieredSegmentCache();
		cache.putSpeculative(1L, seg(1.0));
		cache.promote(1L); // moves to retained, removed from speculative
		cache.evictSpeculative(); cache.evictSpeculative(); // both generations gone
		assertEquals(1.0, cache.get(1L).getTravelTime()); // survives in retained
	}

	@Test
	void computeIfAbsentRoutesOnlyOnce() {
		TieredSegmentCache cache = new TieredSegmentCache();
		java.util.concurrent.atomic.AtomicInteger fills = new java.util.concurrent.atomic.AtomicInteger();
		TravelSegment a = cache.computeIfAbsent(9L, k -> { fills.incrementAndGet(); return seg(9.0); });
		TravelSegment b = cache.computeIfAbsent(9L, k -> { fills.incrementAndGet(); return seg(9.0); });
		assertEquals(1, fills.get());
		assertEquals(a.getTravelTime(), b.getTravelTime());
	}

	@Test
	void promotedKeysNotDuplicatedInSpeculative() {
		TieredSegmentCache cache = new TieredSegmentCache();
		cache.putSpeculative(1L, seg(1.0));
		cache.promote(1L);
		assertEquals(0, cache.speculativeSize());
		assertEquals(1, cache.retainedSize());
	}

	@Test
	void promoteOfMissingKeyIsNoOp() {
		TieredSegmentCache cache = new TieredSegmentCache();
		cache.promote(42L); // nothing cached for 42 — must not throw, must not invent
		assertNull(cache.get(42L));
	}
}
