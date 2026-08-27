package org.matsim.contrib.demand_extraction;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.scenarios.KelheimScenarioFixture;
import org.matsim.core.config.ConfigUtils;

/**
 * End-to-end integration test for ExMAS demand extraction on the Kelheim
 * scenario. Delegates to {@link KelheimScenarioFixture} for setup and
 * validation; this class only pins the algorithm/pruning profile.
 *
 * <p>Uses the BAMAS production setup (distance gate scale=0.25 + post-extension
 * COVERAGE_TOPK K=20, predecessors on). Other algorithm/pruning combinations
 * are covered by {@code ExMasAlgorithmE2ETest}.
 */
public class ExMasKelheimE2ETest {

	@Test
	void testDemandExtractionWithKelheimScenario() throws IOException {
		Path outputDir = Path.of("test/output/exmas-kelheim-e2e-test");
		new KelheimScenarioFixture().runFullPipeline(outputDir, config -> {
			ExMasConfigGroup exMas = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
			exMas.setAlgorithm(ExMasConfigGroup.Algorithm.BAMAS);
			exMas.setPruningDistanceSavingsLogScale(0.25);
			exMas.setPruningMode(ExMasConfigGroup.PruningMode.COVERAGE_TOPK);
			exMas.setPruningCoverageK(20);
			exMas.clearPruningCoverageKByDegree();
			exMas.setCalcPredecessors(true);
			exMas.setMaxPoolingDegree(Integer.MAX_VALUE);
		});
	}
}
