package org.matsim.contrib.demand_extraction.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;

/**
 * The Map-typed params must survive a config XML round trip, or the config file
 * cannot express per-class detour/flexibility overrides and K-schedule sweeps (spec 5.2).
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
	void flexRelativeByClassRoundTrips() {
		ExMasConfigGroup g = new ExMasConfigGroup();
		g.setFlexRelativeByClassAsString("connecting=0.3,rural_intra=0.6");
		assertEquals(Map.of("connecting", 0.3, "rural_intra", 0.6),
				g.getFlexRelativeByClass());
		assertEquals("connecting=0.3,rural_intra=0.6",
				g.getFlexRelativeByClassAsString());
	}

	@Test
	void flexRelativeByClassRoundTripsThroughConfigXmlFile(@TempDir Path tmp) {
		// Proves flexRelativeByClass is reachable from the exmas overlay XML surface,
		// not just the in-memory setter: write a full Config to disk via the same
		// ConfigWriter the extraction pipeline uses, then reload it in a fresh
		// ExMasConfigGroup instance and confirm the map comes back identical.
		Config written = ConfigUtils.createConfig();
		ExMasConfigGroup before = ConfigUtils.addOrGetModule(written, ExMasConfigGroup.class);
		before.setFlexRelativeByClassAsString("rural_intra=0.6,connecting=0.3");

		Path configFile = tmp.resolve("config.xml");
		new ConfigWriter(written).write(configFile.toString());

		Config reloaded = ConfigUtils.loadConfig(configFile.toString(), new ExMasConfigGroup());
		ExMasConfigGroup after = ConfigUtils.addOrGetModule(reloaded, ExMasConfigGroup.class);

		assertEquals(Map.of("connecting", 0.3, "rural_intra", 0.6), after.getFlexRelativeByClass());
		assertEquals("connecting=0.3,rural_intra=0.6", after.getFlexRelativeByClassAsString());
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
		assertEquals("", g.getFlexRelativeByClassAsString());
		g.setMaxDetourFactorByClassAsString("");
		assertTrue(g.getMaxDetourFactorByClass().isEmpty());
		g.setFlexRelativeByClassAsString("");
		assertTrue(g.getFlexRelativeByClass().isEmpty());
	}

	@Test
	void bothParamsAppearInGetParams() {
		Map<String, String> params = new ExMasConfigGroup().getParams();
		assertTrue(params.containsKey("maxDetourFactorByClass"));
		assertTrue(params.containsKey("pruningCoverageKByDegree"));
		assertTrue(params.containsKey("flexRelativeByClass"));
	}

	@Test
	void malformedEntryFailsLoudly() {
		ExMasConfigGroup g = new ExMasConfigGroup();
		assertThrows(IllegalArgumentException.class,
				() -> g.setMaxDetourFactorByClassAsString("connecting"));
		assertThrows(IllegalArgumentException.class,
				() -> g.setFlexRelativeByClassAsString("connecting"));
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

		// flexRelativeByClass shares the same key set as the maxDetourFactorByClass
		// case above, for the same reason: HashMap iteration order must not leak through.
		ExMasConfigGroup d = new ExMasConfigGroup();
		d.setFlexRelativeByClassAsString("rural_intra=0.6,connecting=0.3");
		ExMasConfigGroup e = new ExMasConfigGroup();
		e.setFlexRelativeByClassAsString("connecting=0.3,rural_intra=0.6");
		assertEquals(d.getFlexRelativeByClassAsString(), e.getFlexRelativeByClassAsString());
		assertEquals("connecting=0.3,rural_intra=0.6", d.getFlexRelativeByClassAsString());
	}
}
