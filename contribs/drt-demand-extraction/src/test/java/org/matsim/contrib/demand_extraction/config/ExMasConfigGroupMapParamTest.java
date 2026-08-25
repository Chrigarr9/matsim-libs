package org.matsim.contrib.demand_extraction.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The two Map-typed params must survive a config XML round trip, or the config file
 * cannot express per-class detour overrides and K-schedule sweeps (spec 5.2).
 */
class ExMasConfigGroupMapParamTest {

	@Test
	void detourByClassRoundTrips() {
		ExMasConfigGroup g = new ExMasConfigGroup();
		g.setMaxDetourFactorByClassAsString("connecting=1.3,rural_intra=1.2");
		assertEquals(Map.of("connecting", 1.3, "rural_intra", 1.2),
				g.getMaxDetourFactorByClass());
		assertEquals("connecting=1.3,rural_intra=1.2",
				g.getMaxDetourFactorByClassAsString());
	}

	@Test
	void coverageKByDegreeRoundTrips() {
		ExMasConfigGroup g = new ExMasConfigGroup();
		g.setPruningCoverageKByDegreeAsString("4=32,5=16");
		assertEquals(Map.of(4, 32, 5, 16), g.getPruningCoverageKByDegree());
		assertEquals("4=32,5=16", g.getPruningCoverageKByDegreeAsString());
	}

	@Test
	void emptyMapsSerializeToEmptyString() {
		ExMasConfigGroup g = new ExMasConfigGroup();
		assertEquals("", g.getMaxDetourFactorByClassAsString());
		assertEquals("", g.getPruningCoverageKByDegreeAsString());
		g.setMaxDetourFactorByClassAsString("");
		assertTrue(g.getMaxDetourFactorByClass().isEmpty());
	}

	@Test
	void bothParamsAppearInGetParams() {
		Map<String, String> params = new ExMasConfigGroup().getParams();
		assertTrue(params.containsKey("maxDetourFactorByClass"));
		assertTrue(params.containsKey("pruningCoverageKByDegree"));
	}

	@Test
	void malformedEntryFailsLoudly() {
		ExMasConfigGroup g = new ExMasConfigGroup();
		assertThrows(IllegalArgumentException.class,
				() -> g.setMaxDetourFactorByClassAsString("connecting"));
	}

	@Test
	void serializationIsDeterministic() {
		// Order must not depend on HashMap iteration, or the fingerprint is unstable.
		//
		// The key sets below are chosen so that HashMap iteration order DIFFERS from
		// sorted order, which is what makes this test able to fail. Verified on JDK 25:
		//   {"connecting","rural_intra"} iterates [rural_intra, connecting]
		//   {16,4}                       iterates [16, 4]
		// Do not "simplify" these to {"a","b"} or {4,5}: both of those happen to
		// iterate in sorted order already, so the assertions would still pass after
		// deleting the .sorted() call and the test would prove nothing.
		ExMasConfigGroup a = new ExMasConfigGroup();
		a.setMaxDetourFactorByClassAsString("rural_intra=1.2,connecting=1.3");
		ExMasConfigGroup b = new ExMasConfigGroup();
		b.setMaxDetourFactorByClassAsString("connecting=1.3,rural_intra=1.2");
		assertEquals(a.getMaxDetourFactorByClassAsString(),
				b.getMaxDetourFactorByClassAsString());
		assertEquals("connecting=1.3,rural_intra=1.2", a.getMaxDetourFactorByClassAsString());

		// Integer keys must sort numerically, not lexicographically: "16" < "4" as
		// strings, so a string-ordered encoding would yield "16=8,4=32" here.
		ExMasConfigGroup c = new ExMasConfigGroup();
		c.setPruningCoverageKByDegreeAsString("16=8,4=32");
		assertEquals("4=32,16=8", c.getPruningCoverageKByDegreeAsString());
	}
}
