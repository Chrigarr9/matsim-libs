package org.matsim.contrib.demand_extraction.scenarios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup.Algorithm;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup.PruningMode;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;

class AlgorithmProfileTest {

	@Test
	void r3IsDistanceOnlyAblation() {
		Config config = ConfigUtils.createConfig(new ExMasConfigGroup());

		AlgorithmProfile.R3.apply(config);

		ExMasConfigGroup exMas = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		assertEquals(Algorithm.BAMAS, exMas.getAlgorithm());
		assertTrue(exMas.isHeuristicPruningEnabled());
		assertEquals(0.15, exMas.getPruningDistanceSavingsLogScale());
		assertEquals(PruningMode.RATIO_THRESHOLD, exMas.getPruningMode());
		assertEquals(1.0, exMas.getInterDegreeKeepFraction());
		assertFalse(exMas.isCalcPredecessors());
	}

	@Test
	void r4AddsTopKPostExtensionPruningOnTopOfDistancePruning() {
		Config config = ConfigUtils.createConfig(new ExMasConfigGroup());

		AlgorithmProfile.R4.apply(config);

		ExMasConfigGroup exMas = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		assertEquals(Algorithm.BAMAS, exMas.getAlgorithm());
		assertTrue(exMas.isHeuristicPruningEnabled());
		assertEquals(0.15, exMas.getPruningDistanceSavingsLogScale());
		assertEquals(PruningMode.COVERAGE_TOPK, exMas.getPruningMode());
		assertEquals(20, exMas.getPruningCoverageK());
		assertTrue(exMas.isCalcPredecessors());
	}
}