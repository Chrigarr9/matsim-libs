package org.matsim.contrib.demand_extraction.algorithm.bamas.ride;

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
	// Intern same stop (two distinct instances, same link/coord/penalty) → same id
	// -----------------------------------------------------------------------

	@Test
	void identicalStopReturnsSameId() {
		StopLocationDictionary dict = new StopLocationDictionary();
		StopLocation s1 = new StopLocation(Id.createLinkId("a"), new Coord(1.0, 2.0), 3.5);
		StopLocation s2 = new StopLocation(Id.createLinkId("a"), new Coord(1.0, 2.0), 3.5);

		int id1 = dict.idOf(s1);
		int id2 = dict.idOf(s2);

		assertEquals(id1, id2, "two stops with identical full identity must return the same id");
		assertEquals(1, dict.size(), "only one distinct stop should be registered");
	}

	// -----------------------------------------------------------------------
	// Same linkId but DIFFERENT coordinate → DISTINCT ids (full-identity interning).
	// Request-derived stop coords vary per ride on the same link; the dictionary must
	// preserve each so byId replays the exact coordinate (exmas_rides byte-parity, A2).
	// -----------------------------------------------------------------------

	@Test
	void sameLinkDifferentCoordGetsDistinctId() {
		StopLocationDictionary dict = new StopLocationDictionary();
		StopLocation s1 = new StopLocation(Id.createLinkId("a"), new Coord(727254.60, 100.0));
		StopLocation s2 = new StopLocation(Id.createLinkId("a"), new Coord(727254.59, 100.0));

		int id1 = dict.idOf(s1);
		int id2 = dict.idOf(s2);

		assertNotEquals(id1, id2, "same link but distinct coord must get distinct ids");
		assertEquals(2, dict.size(), "both distinct-coord stops must be registered");
		assertEquals(727254.60, dict.byId(id1).getCoord().getX(), 0.0,
				"byId must replay the exact coordinate of the first stop");
		assertEquals(727254.59, dict.byId(id2).getCoord().getX(), 0.0,
				"byId must replay the exact coordinate of the second stop");
	}

	// -----------------------------------------------------------------------
	// Same link/coord but DIFFERENT snapping penalty → DISTINCT ids.
	// -----------------------------------------------------------------------

	@Test
	void sameLinkAndCoordDifferentPenaltyGetsDistinctId() {
		StopLocationDictionary dict = new StopLocationDictionary();
		StopLocation s1 = new StopLocation(Id.createLinkId("a"), new Coord(1.0, 2.0), 0.0);
		StopLocation s2 = new StopLocation(Id.createLinkId("a"), new Coord(1.0, 2.0), 3.5);

		assertNotEquals(dict.idOf(s1), dict.idOf(s2),
				"distinct snapping penalties must get distinct ids");
		assertEquals(2, dict.size());
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

		// Re-intern A with the SAME coord — must still return 0, size unchanged
		int idA2 = dict.idOf(new StopLocation(Id.createLinkId("aaa"), new Coord(0.0, 0.0)));
		assertEquals(0, idA2, "re-interning identical A must still return id 0");
		assertEquals(3, dict.size(), "re-interning an identical stop must not grow the dictionary");
	}
}
