package org.matsim.contrib.demand_extraction.checkpoint;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.matsim.contrib.demand_extraction.algorithm.bamas.checkpoint.RunFingerprint;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ONE-OFF diagnostic (not a behavioural test): prints the BAMAS checkpoint fingerprints for a given
 * phase1_config.xml + routing inputs, computed via the exact production code path Phase2 uses
 * (ConfigUtils.loadConfig with ExMas/MultiModeDrt/Dvrp groups → RunFingerprint.compute). Used to
 * re-stamp a base-only (highestDegree=2) manifest so the existing pair universe + journal can be
 * reused after the deferExtensionBudgetValidation removal changed the config hash.
 *
 * <p>Skips unless -Dfp.config / -Dfp.requests / -Dfp.tt / -Dfp.net are set, so it is inert in the gate.
 */
class ComputeRebaseFingerprintDiagnostic {

	@Test
	void printFingerprints() {
		String cfg = System.getProperty("fp.config");
		String req = System.getProperty("fp.requests");
		String tt  = System.getProperty("fp.tt");
		String net = System.getProperty("fp.net");
		assumeTrue(cfg != null && req != null && tt != null && net != null,
				"set -Dfp.config -Dfp.requests -Dfp.tt -Dfp.net to run this diagnostic");

		Config config = ConfigUtils.loadConfig(cfg,
				new ExMasConfigGroup(), new MultiModeDrtConfigGroup(), new DvrpConfigGroup());
		ExMasConfigGroup exMas = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		int minDegree = exMas.getExtensionParentsTopKMinDegree();

		String full = RunFingerprint.compute(exMas, Path.of(req), Path.of(tt), Path.of(net), "bamas");
		// Base hash compared by a fork resume below minDegree (forkable parent-pruning knobs excluded).
		String base = RunFingerprint.compute(exMas, Path.of(req), Path.of(tt), Path.of(net), "bamas",
				minDegree - 1);

		System.out.println("REBASE_FINGERPRINT_FULL=" + full);
		System.out.println("REBASE_FINGERPRINT_BASE=" + base);
		System.out.println("REBASE_MINDEGREE=" + minDegree);
		assertNotNull(full);
		assertNotNull(base);
	}
}
