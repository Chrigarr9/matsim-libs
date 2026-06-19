package org.matsim.contrib.demand_extraction.algorithm.network;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;

/**
 * Unit test for the Task 7 promotion API on {@link MatsimNetworkCache}:
 * {@link MatsimNetworkCache#promoteSegment(Id, Id, double)} moves a speculative
 * segment into the never-evicted retained tier, so it survives speculative-tier
 * eviction. Uses the package-visible {@code evictSpeculativeForTesting()} hook.
 */
class MatsimNetworkCachePromoteTest {

	@Test
	void promotedSegmentSurvivesEviction() {
		MatsimNetworkCache cache = MatsimNetworkCache.forTesting();
		Id<Link> a = Id.createLinkId("a");
		Id<Link> b = Id.createLinkId("b");
		cache.putForTesting(a, b, new TravelSegment(12.5, 340.0, -1.25));

		// Promote at any departure time — forTesting() maps every time to bin 0,
		// matching putForTesting's key.
		cache.promoteSegment(a, b, 8 * 3600);

		// Two speculative rotations drop both generations; a non-promoted segment
		// would be gone.
		cache.evictSpeculativeForTesting();
		cache.evictSpeculativeForTesting();

		TravelSegment seg = cache.peekForTesting(a, b, 0);
		assertNotNull(seg, "promoted segment must survive eviction");
		assertEquals(12.5, seg.getTravelTime());
		assertEquals(340.0, seg.getDistance());
		assertEquals(-1.25, seg.getNetworkUtility());
	}

	@Test
	void unpromotedSegmentIsEvicted() {
		MatsimNetworkCache cache = MatsimNetworkCache.forTesting();
		Id<Link> a = Id.createLinkId("a");
		Id<Link> b = Id.createLinkId("b");
		cache.putForTesting(a, b, new TravelSegment(12.5, 340.0, -1.25));

		// No promotion: both rotations drop it.
		cache.evictSpeculativeForTesting();
		cache.evictSpeculativeForTesting();

		assertNull(cache.peekForTesting(a, b, 0), "un-promoted segment must be evicted");
	}

	@Test
	void promoteOfMissingSegmentIsNoOp() {
		MatsimNetworkCache cache = MatsimNetworkCache.forTesting();
		Id<Link> a = Id.createLinkId("a");
		Id<Link> b = Id.createLinkId("b");
		// nothing cached — must not throw, must not invent
		cache.promoteSegment(a, b, 8 * 3600);
		assertNull(cache.peekForTesting(a, b, 0));
	}

	/**
	 * The pair-generation bug, reproduced at the cache API level: a chain segment is routed during
	 * the parallel collection and then watermark-evicted BEFORE the single-threaded promotion loop
	 * runs. {@link MatsimNetworkCache#promoteSegment} would no-op (nothing cached) and the segment
	 * would be absent from the checkpoint journal, forcing a re-route at export. {@code retainSegment}
	 * writes the value the accepted candidate already holds straight into the never-evicted retained
	 * tier, so it survives and resume/export reads it as a hit.
	 */
	@Test
	void retainSegmentSurvivesEvictionWithoutPriorCacheEntry() {
		MatsimNetworkCache cache = MatsimNetworkCache.forTesting();
		Id<Link> a = Id.createLinkId("a");
		Id<Link> b = Id.createLinkId("b");

		// Routed-then-evicted before promotion could run.
		cache.putForTesting(a, b, new TravelSegment(12.5, 340.0, -1.25));
		cache.evictSpeculativeForTesting();
		cache.evictSpeculativeForTesting();
		assertNull(cache.peekForTesting(a, b, 0), "precondition: evicted before promotion");

		// promoteSegment here would no-op; retainSegment writes the held value into retained.
		cache.retainSegment(a, b, 8 * 3600, 12.5, 340.0, -1.25);
		cache.evictSpeculativeForTesting();
		cache.evictSpeculativeForTesting();

		TravelSegment seg = cache.peekForTesting(a, b, 0);
		assertNotNull(seg, "retained segment must survive eviction");
		assertEquals(12.5, seg.getTravelTime());
		assertEquals(340.0, seg.getDistance());
		assertEquals(-1.25, seg.getNetworkUtility());
	}
}
