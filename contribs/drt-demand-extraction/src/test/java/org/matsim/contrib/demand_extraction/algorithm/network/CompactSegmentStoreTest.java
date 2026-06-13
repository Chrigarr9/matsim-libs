package org.matsim.contrib.demand_extraction.algorithm.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;

class CompactSegmentStoreTest {

	@Test
	void missReturnsNull() {
		CompactSegmentStore store = new CompactSegmentStore();
		assertNull(store.get(42L));
	}

	@Test
	void putThenGetReturnsBitIdenticalValues() {
		CompactSegmentStore store = new CompactSegmentStore();
		store.put(42L, new TravelSegment(123.456789, 9876.54321, -0.000123));
		TravelSegment seg = store.get(42L);
		assertEquals(123.456789, seg.getTravelTime()); // exact double equality, no delta
		assertEquals(9876.54321, seg.getDistance());
		assertEquals(-0.000123, seg.getNetworkUtility());
	}

	@Test
	void overlayEntriesSurviveCompaction() {
		CompactSegmentStore store = new CompactSegmentStore();
		for (long k = 0; k < 1000; k++) {
			store.put(k, new TravelSegment(k + 0.5, k * 2.0, -k));
		}
		store.compact(); // merge overlay into frozen snapshot
		for (long k = 0; k < 1000; k++) {
			assertEquals(k + 0.5, store.get(k).getTravelTime());
			assertEquals(k * 2.0, store.get(k).getDistance());
		}
		// post-compaction puts land in a fresh overlay and are still found
		store.put(5000L, new TravelSegment(1.0, 2.0, -3.0));
		assertEquals(1.0, store.get(5000L).getTravelTime());
		assertEquals(1001, store.size());
	}

	@Test
	void repeatedCompactionIsStable() {
		CompactSegmentStore store = new CompactSegmentStore();
		store.put(1L, new TravelSegment(1.0, 1.0, -1.0));
		store.compact();
		store.put(2L, new TravelSegment(2.0, 2.0, -2.0));
		store.compact();
		store.compact(); // empty-overlay compaction must be a no-op
		assertEquals(1.0, store.get(1L).getTravelTime());
		assertEquals(2.0, store.get(2L).getTravelTime());
		assertEquals(2, store.size());
	}

	@Test
	void putIsFirstWriteWins() {
		// Values are history-independent by the value-source determinism rule, so
		// duplicate puts always carry identical values; first-write-wins is the
		// cheap contract (overwrites are pointless work).
		CompactSegmentStore store = new CompactSegmentStore();
		store.put(7L, new TravelSegment(1.0, 1.0, -1.0));
		store.put(7L, new TravelSegment(2.0, 2.0, -2.0));
		assertEquals(1.0, store.get(7L).getTravelTime());
		assertEquals(1, store.size());

		// also across a compaction boundary
		store.compact();
		store.put(7L, new TravelSegment(3.0, 3.0, -3.0));
		assertEquals(1.0, store.get(7L).getTravelTime());
		assertEquals(1, store.size());
	}
}
