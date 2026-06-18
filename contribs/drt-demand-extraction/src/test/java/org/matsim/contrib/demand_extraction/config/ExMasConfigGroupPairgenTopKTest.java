package org.matsim.contrib.demand_extraction.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ExMasConfigGroupPairgenTopKTest {

	@Test
	void defaultsToZeroMeaningDisabled() {
		assertEquals(0, new ExMasConfigGroup().getPairgenTopK());
	}

	@Test
	void setterRoundTrips() {
		ExMasConfigGroup cfg = new ExMasConfigGroup();
		cfg.setPairgenTopK(32);
		assertEquals(32, cfg.getPairgenTopK());
	}

	@Test
	void setterClampsNegativeToZero() {
		ExMasConfigGroup cfg = new ExMasConfigGroup();
		cfg.setPairgenTopK(-5);
		assertEquals(0, cfg.getPairgenTopK());
	}

	@Test
	void exposedAsStringParamForFingerprintAndXml() {
		// @StringGetter params appear in getParams(); RunFingerprint hashes getParams(),
		// so this is what puts the knob into the fingerprint.
		ExMasConfigGroup cfg = new ExMasConfigGroup();
		cfg.setPairgenTopK(32);
		assertEquals("32", cfg.getParams().get("pairgenTopK"));
	}
}
