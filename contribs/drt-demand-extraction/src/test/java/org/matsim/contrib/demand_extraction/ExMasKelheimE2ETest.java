package org.matsim.contrib.demand_extraction;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.matsim.contrib.demand_extraction.scenarios.AlgorithmProfile;
import org.matsim.contrib.demand_extraction.scenarios.KelheimScenarioFixture;

/**
 * End-to-end integration test for ExMAS demand extraction on the Kelheim
 * scenario. Delegates to {@link KelheimScenarioFixture} for setup and
 * validation; this class only pins the algorithm/pruning profile.
 *
 * <p>Uses {@link AlgorithmProfile#R3} (BAMAS with production-default coverage
 * pruning) — the historical behaviour of this test before Phase 5a refactor.
 *
 * <p>The R1/R2 paths are covered by {@code ExMasAlgorithmE2ETest}.
 */
public class ExMasKelheimE2ETest {

	@Test
	void testDemandExtractionWithKelheimScenario() throws IOException {
		Path outputDir = Path.of("test/output/exmas-kelheim-e2e-test");
		new KelheimScenarioFixture().runFullPipeline(outputDir, AlgorithmProfile.R3);
	}
}
