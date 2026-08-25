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
		ExMasConfigGroup a = new ExMasConfigGroup();
		a.setMaxDetourFactorByClassAsString("b=2.0,a=1.0");
		ExMasConfigGroup b = new ExMasConfigGroup();
		b.setMaxDetourFactorByClassAsString("a=1.0,b=2.0");
		assertEquals(a.getMaxDetourFactorByClassAsString(),
				b.getMaxDetourFactorByClassAsString());
		// The equality check alone is not conclusive for a 2-key {"a","b"} map: plain
		// (unsorted) HashMap iteration happens to land on "a=1.0,b=2.0" regardless of
		// insertion order for this specific key pair, so that check alone would pass
		// even without sorting. Pin the exact key-sorted string to actually prove it.
		assertEquals("a=1.0,b=2.0", a.getMaxDetourFactorByClassAsString());
	}
}
