package org.matsim.contrib.demand_extraction.run;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;

/**
 * Provenance: Phase 2 must persist the effective (post CLI-override) config into the output dir,
 * so "what params did this run use?" is answerable from a durable artifact next to the results —
 * not only from the shell command / tee'd log. The knobs are MATSim ConfigGroup params, so the
 * effective config is plain MATSim XML (same format as the dump's phase1_config.xml).
 */
class RunDemandExtractionPhase2EffectiveConfigTest {

	@Test
	void writesEffectiveConfigWithOverriddenKnob(@TempDir Path dir) throws Exception {
		Config config = ConfigUtils.createConfig();
		ExMasConfigGroup exMas = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		exMas.setPairgenTopK(32); // simulate a CLI override having been applied

		RunDemandExtractionPhase2.writeEffectiveConfig(config, dir);

		Path out = dir.resolve("phase2_effective_config.xml");
		assertTrue(Files.exists(out), "effective config snapshot should be written");
		String xml = Files.readString(out);
		assertTrue(xml.contains("pairgenTopK"), "snapshot should contain the knob name");
		assertTrue(xml.contains("32"), "snapshot should contain the overridden value");
	}

	@Test
	void createsOutputDirIfMissing(@TempDir Path dir) throws Exception {
		Path nested = dir.resolve("does/not/exist/yet");
		Config config = ConfigUtils.createConfig();

		RunDemandExtractionPhase2.writeEffectiveConfig(config, nested);

		assertTrue(Files.exists(nested.resolve("phase2_effective_config.xml")),
				"writer should create the output dir if it does not exist");
	}
}
