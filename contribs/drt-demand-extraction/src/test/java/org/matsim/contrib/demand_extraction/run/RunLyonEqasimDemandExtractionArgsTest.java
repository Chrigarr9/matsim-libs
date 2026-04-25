package org.matsim.contrib.demand_extraction.run;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.core.config.ConfigUtils;

class RunLyonEqasimDemandExtractionArgsTest {

	@Test
	void cliOverridesDisablePredecessorsAndShapleyWhenRequested() throws Exception {
		RunLyonEqasimDemandExtraction.ParsedArgs parsed = RunLyonEqasimDemandExtraction.parseArgs(new String[] {
				"--sample", "1",
				"--scenario-dir", "scenario",
				"--travel-times", "travel-times.tsv",
				"--no-predecessors",
				"--no-shapley"
		});

		ExMasConfigGroup exMas = ConfigUtils.addOrGetModule(
				ConfigUtils.createConfig(new ExMasConfigGroup()),
				ExMasConfigGroup.class);
		exMas.setCalcPredecessors(true);
		exMas.setCalcShapleyValues(true);

		Method applyCliOverrides = RunLyonEqasimDemandExtraction.class.getDeclaredMethod(
				"applyCliOverrides",
				ExMasConfigGroup.class,
				RunLyonEqasimDemandExtraction.ParsedArgs.class);
		applyCliOverrides.setAccessible(true);
		applyCliOverrides.invoke(null, exMas, parsed);

		assertFalse(exMas.isCalcPredecessors());
		assertFalse(exMas.isCalcShapleyValues());
	}
}