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

	@Test
	void retainStoresKnownValueWithoutPriorSpeculativeEntry() {
		TieredSegmentCache cache = new TieredSegmentCache();
		// retain a value for a key that was NEVER putSpeculative — the promote() path would no-op
		// here, but retain() writes the caller's value straight into the retained tier.
		cache.retain(7L, seg(7.0));
		assertEquals(7.0, cache.get(7L).getTravelTime());
		assertEquals(1, cache.retainedSize());
		assertEquals(0, cache.speculativeSize());
	}

	@Test
	void retainedValueSurvivesSpeculativeEviction() {
		TieredSegmentCache cache = new TieredSegmentCache();
		// Simulate the bug scenario: a segment routed-then-evicted before promotion. promote() would
		// no-op (value already gone from speculative); retain() with the known value is complete.
		cache.putSpeculative(5L, seg(5.0));
		cache.evictSpeculative(); cache.evictSpeculative(); // segment dropped before we could promote
		assertNull(cache.get(5L), "precondition: evicted from speculative");
		cache.retain(5L, seg(5.0)); // caller still holds the value (from the accepted candidate)
		cache.evictSpeculative(); cache.evictSpeculative();
		assertEquals(5.0, cache.get(5L).getTravelTime(), "retained value is never evicted");
	}

	@Test
	void retainIsFirstWriteWins() {
		TieredSegmentCache cache = new TieredSegmentCache();
		cache.retain(3L, seg(3.0));
		cache.retain(3L, seg(99.0)); // ignored — value-source determinism makes overwrites pointless
		assertEquals(3.0, cache.get(3L).getTravelTime());
	}
}
