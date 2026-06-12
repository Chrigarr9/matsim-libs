package org.matsim.contrib.demand_extraction.algorithm.bamas.stub;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.demand_extraction.algorithm.domain.StopLocation;

class StopLocationDictionaryTest {

	private static StopLocation stop(String linkId) {
		return new StopLocation(Id.createLinkId(linkId), new Coord(0.0, 0.0));
	}

	// -----------------------------------------------------------------------
	// Intern same stop (two distinct instances, same linkId) → same id, size==1
	// -----------------------------------------------------------------------

	@Test
	void sameLinkIdReturnsSameId() {
		StopLocationDictionary dict = new StopLocationDictionary();
		StopLocation s1 = new StopLocation(Id.createLinkId("a"), new Coord(1.0, 2.0));
		StopLocation s2 = new StopLocation(Id.createLinkId("a"), new Coord(9.9, 8.8), 3.5);

		int id1 = dict.idOf(s1);
		int id2 = dict.idOf(s2);

		assertEquals(id1, id2, "two instances with same linkId must return the same interned id");
		assertEquals(1, dict.size(), "only one distinct stop should be registered");
	}

	// -----------------------------------------------------------------------
	// Two distinct linkIds → ids 0 then 1; size==2
	// -----------------------------------------------------------------------

	@Test
	void distinctLinkIdsGetDistinctIds() {
		StopLocationDictionary dict = new StopLocationDictionary();
		StopLocation sA = stop("link-1");
		StopLocation sB = stop("link-2");

		int idA = dict.idOf(sA);
		int idB = dict.idOf(sB);

		assertNotEquals(idA, idB, "distinct linkIds must yield distinct ids");
		assertEquals(0, idA, "first inserted stop gets id 0");
		assertEquals(1, idB, "second inserted stop gets id 1");
		assertEquals(2, dict.size());
	}

	// -----------------------------------------------------------------------
	// byId(idOf(s)) returns a stop equal to s
	// -----------------------------------------------------------------------

	@Test
	void byIdReturnsEqualStop() {
		StopLocationDictionary dict = new StopLocationDictionary();
		StopLocation s = stop("q");
		int id = dict.idOf(s);
		StopLocation retrieved = dict.byId(id);
		assertEquals(s, retrieved, "byId must return a stop equal to the interned stop");
	}

	// -----------------------------------------------------------------------
	// byId on unknown id throws IndexOutOfBoundsException
	// -----------------------------------------------------------------------

	@Test
	void byIdUnknownIdThrows() {
		StopLocationDictionary dict = new StopLocationDictionary();
		dict.idOf(stop("x"));    // id 0 is valid; 1 is not
		assertThrows(IndexOutOfBoundsException.class, () -> dict.byId(1),
				"byId on unknown id must throw IndexOutOfBoundsException");
		assertThrows(IndexOutOfBoundsException.class, () -> dict.byId(-1),
				"byId on negative id must throw IndexOutOfBoundsException");
	}

	// -----------------------------------------------------------------------
	// Insertion-order determinism: A→0, B→1, C→2; re-intern A still returns 0
	// -----------------------------------------------------------------------

	@Test
	void insertionOrderDeterminism() {
		StopLocationDictionary dict = new StopLocationDictionary();
		StopLocation sA = stop("aaa");
		StopLocation sB = stop("bbb");
		StopLocation sC = stop("ccc");

		int idA = dict.idOf(sA);
		int idB = dict.idOf(sB);
		int idC = dict.idOf(sC);

		assertEquals(0, idA);
		assertEquals(1, idB);
		assertEquals(2, idC);
		assertEquals(3, dict.size());

		// Re-intern A — must still return 0, size unchanged
		int idA2 = dict.idOf(new StopLocation(Id.createLinkId("aaa"), new Coord(42.0, 42.0)));
		assertEquals(0, idA2, "re-interning A must still return id 0");
		assertEquals(3, dict.size(), "re-interning must not grow the dictionary");
	}
}
