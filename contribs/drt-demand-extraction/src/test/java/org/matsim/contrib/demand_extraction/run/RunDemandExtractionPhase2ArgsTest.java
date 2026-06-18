package org.matsim.contrib.demand_extraction.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class RunDemandExtractionPhase2ArgsTest {

	// ------------------------------------------------------------------ //
	// Gap 2: assertDumpSupportsConfig guard                                //
	// ------------------------------------------------------------------ //

	@Test
	void guardThrowsWhenV1DumpAndStopBasedEnabled() {
		org.matsim.contrib.demand_extraction.config.ExMasConfigGroup cfg =
				new org.matsim.contrib.demand_extraction.config.ExMasConfigGroup();
		cfg.setEnableStopBased(true);
		org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
				() -> RunDemandExtractionPhase2.assertDumpSupportsConfig(1, cfg),
				"v1 dump with stop-based enabled should throw");
	}

	@Test
	void guardThrowsWhenV1DumpAndHyperPoolEnabled() {
		org.matsim.contrib.demand_extraction.config.ExMasConfigGroup cfg =
				new org.matsim.contrib.demand_extraction.config.ExMasConfigGroup();
		cfg.setEnableHyperPooling(true);
		org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
				() -> RunDemandExtractionPhase2.assertDumpSupportsConfig(1, cfg),
				"v1 dump with hyperpool enabled should throw");
	}

	@Test
	void guardDoesNotThrowWhenV2DumpAndStopBasedEnabled() {
		org.matsim.contrib.demand_extraction.config.ExMasConfigGroup cfg =
				new org.matsim.contrib.demand_extraction.config.ExMasConfigGroup();
		cfg.setEnableStopBased(true);
		// Should not throw
		RunDemandExtractionPhase2.assertDumpSupportsConfig(2, cfg);
	}

	@Test
	void guardDoesNotThrowWhenV1DumpAndBothDisabled() {
		org.matsim.contrib.demand_extraction.config.ExMasConfigGroup cfg =
				new org.matsim.contrib.demand_extraction.config.ExMasConfigGroup();
		// defaults: enableStopBased=false, enableHyperPooling=false
		// Should not throw
		RunDemandExtractionPhase2.assertDumpSupportsConfig(1, cfg);
	}

	@Test
	void guardMessageMentionsRelevantContext() {
		org.matsim.contrib.demand_extraction.config.ExMasConfigGroup cfg =
				new org.matsim.contrib.demand_extraction.config.ExMasConfigGroup();
		cfg.setEnableStopBased(true);
		IllegalStateException ex = org.junit.jupiter.api.Assertions.assertThrows(
				IllegalStateException.class,
				() -> RunDemandExtractionPhase2.assertDumpSupportsConfig(1, cfg));
		String msg = ex.getMessage().toLowerCase();
		assertTrue(msg.contains("v1"), "message should mention v1 dump");
		assertTrue(msg.contains("stop-based") || msg.contains("hyperpool") || msg.contains("stop"),
				"message should mention stop-based/hyperpool");
		assertTrue(msg.contains("re-run") || msg.contains("phase 1") || msg.contains("phase-1"),
				"message should mention re-running Phase 1");
	}

	@Test
	void parsesAllRequiredFlags() {
		String[] args = {
				"--phase1-dir", "/tmp/dump",
				"--network", "/tmp/net.xml.gz",
				"--travel-times", "/tmp/tt.tsv",
				"--output-dir", "/tmp/out"
		};
		RunDemandExtractionPhase2.Phase2Args parsed = RunDemandExtractionPhase2.parseArgs(args);
		assertEquals(Path.of("/tmp/dump"), parsed.phase1Dir());
		assertEquals(Path.of("/tmp/net.xml.gz"), parsed.networkXml());
		assertEquals(Path.of("/tmp/tt.tsv"), parsed.travelTimesTsv());
		assertEquals(Path.of("/tmp/out"), parsed.outputDir());
	}

	@Test
	void rejectsMissingRequiredFlag() {
		String[] args = {
				"--phase1-dir", "/tmp/dump",
				"--network", "/tmp/net.xml.gz",
				"--travel-times", "/tmp/tt.tsv"
				// --output-dir missing
		};
		assertThrows(IllegalArgumentException.class,
				() -> RunDemandExtractionPhase2.parseArgs(args));
	}

	// ------------------------------------------------------------------ //
	// B3: --checkpoint-fork-below-min-degree CLI wiring                   //
	// ------------------------------------------------------------------ //

	@Test
	void applyPhase2KnobOverridesSetsForkFlagWhenPresent() {
		org.matsim.contrib.demand_extraction.config.ExMasConfigGroup cfg =
				new org.matsim.contrib.demand_extraction.config.ExMasConfigGroup();
		RunDemandExtractionPhase2.applyPhase2KnobOverrides(
				new String[]{"--checkpoint-fork-below-min-degree"}, cfg);
		assertTrue(cfg.isCheckpointForkBelowMinDegree(),
				"flag should flip checkpointForkBelowMinDegree to true");
	}

	@Test
	void applyPhase2KnobOverridesLeavesForkFlagFalseWhenAbsent() {
		org.matsim.contrib.demand_extraction.config.ExMasConfigGroup cfg =
				new org.matsim.contrib.demand_extraction.config.ExMasConfigGroup();
		RunDemandExtractionPhase2.applyPhase2KnobOverrides(new String[]{}, cfg);
		org.junit.jupiter.api.Assertions.assertFalse(cfg.isCheckpointForkBelowMinDegree(),
				"flag should stay false when --checkpoint-fork-below-min-degree is absent");
	}

	@Test
	void parseArgsToleratesForkFlagWithoutConsuming() {
		// The valueless flag must not cause parseArgs to misread the following token.
		String[] args = {
				"--phase1-dir", "/tmp/dump",
				"--checkpoint-fork-below-min-degree",
				"--network", "/tmp/net.xml.gz",
				"--travel-times", "/tmp/tt.tsv",
				"--output-dir", "/tmp/out"
		};
		// Should not throw; if the flag wrongly consumed --network's value, the
		// subsequent --network token would be missing and this would throw.
		RunDemandExtractionPhase2.Phase2Args parsed = RunDemandExtractionPhase2.parseArgs(args);
		assertEquals(java.nio.file.Path.of("/tmp/net.xml.gz"), parsed.networkXml());
	}

	// ------------------------------------------------------------------ //
	// --max-degree CLI wiring (degree-2 universe dump)                     //
	// ------------------------------------------------------------------ //

	@Test
	void applyPhase2KnobOverridesSetsMaxDegree() {
		org.matsim.contrib.demand_extraction.config.ExMasConfigGroup cfg =
				new org.matsim.contrib.demand_extraction.config.ExMasConfigGroup();
		RunDemandExtractionPhase2.applyPhase2KnobOverrides(
				new String[]{"--max-degree", "2"}, cfg);
		assertEquals(2, cfg.getMaxPoolingDegree(),
				"--max-degree 2 should cap maxPoolingDegree at 2");
	}

	@Test
	void parseArgsToleratesMaxDegreeWithoutMisreadingNext() {
		// --max-degree takes a value; parseArgs must skip both tokens and still find --network.
		String[] args = {
				"--phase1-dir", "/tmp/dump",
				"--max-degree", "2",
				"--network", "/tmp/net.xml.gz",
				"--travel-times", "/tmp/tt.tsv",
				"--output-dir", "/tmp/out"
		};
		RunDemandExtractionPhase2.Phase2Args parsed = RunDemandExtractionPhase2.parseArgs(args);
		assertEquals(java.nio.file.Path.of("/tmp/net.xml.gz"), parsed.networkXml());
	}

	@Test
	void applyPhase2KnobOverridesSetsTrustJournalFlag() {
		org.matsim.contrib.demand_extraction.config.ExMasConfigGroup cfg =
				new org.matsim.contrib.demand_extraction.config.ExMasConfigGroup();
		RunDemandExtractionPhase2.applyPhase2KnobOverrides(
				new String[]{"--trust-checkpoint-journal"}, cfg);
		assertTrue(cfg.isTrustCheckpointJournal(),
				"--trust-checkpoint-journal should flip trustCheckpointJournal to true");
	}

	@Test
	void parseArgsToleratesTrustJournalFlagWithoutConsuming() {
		String[] args = {
				"--phase1-dir", "/tmp/dump",
				"--trust-checkpoint-journal",
				"--network", "/tmp/net.xml.gz",
				"--travel-times", "/tmp/tt.tsv",
				"--output-dir", "/tmp/out"
		};
		RunDemandExtractionPhase2.Phase2Args parsed = RunDemandExtractionPhase2.parseArgs(args);
		assertEquals(java.nio.file.Path.of("/tmp/net.xml.gz"), parsed.networkXml());
	}

	@Test
	void applyPhase2KnobOverridesTogglesPostProcessFlags() {
		org.matsim.contrib.demand_extraction.config.ExMasConfigGroup cfg =
				new org.matsim.contrib.demand_extraction.config.ExMasConfigGroup();
		cfg.setCalcPredecessors(true);
		cfg.setCalcShapleyValues(true);
		RunDemandExtractionPhase2.applyPhase2KnobOverrides(
				new String[]{"--calc-predecessors", "false", "--calc-shapley-values", "false"}, cfg);
		org.junit.jupiter.api.Assertions.assertFalse(cfg.isCalcPredecessors(),
				"--calc-predecessors false should disable predecessors");
		org.junit.jupiter.api.Assertions.assertFalse(cfg.isCalcShapleyValues(),
				"--calc-shapley-values false should disable Shapley");
	}

	// ------------------------------------------------------------------ //
	// --pairgen-top-k CLI wiring (degree-2 partner cap)                    //
	// ------------------------------------------------------------------ //

	@Test
	void pairgenTopKOverrideAppliesToConfig() {
		org.matsim.contrib.demand_extraction.config.ExMasConfigGroup cfg =
				new org.matsim.contrib.demand_extraction.config.ExMasConfigGroup();
		RunDemandExtractionPhase2.applyPhase2KnobOverrides(
				new String[]{"--pairgen-top-k", "32"}, cfg);
		assertEquals(32, cfg.getPairgenTopK(),
				"--pairgen-top-k 32 should set pairgenTopK on the config");
	}

	@Test
	void parseArgsToleratesPairgenTopKWithoutMisreadingNext() {
		// --pairgen-top-k takes a value; parseArgs must skip both tokens and still find --network.
		String[] args = {
				"--phase1-dir", "/tmp/dump",
				"--pairgen-top-k", "32",
				"--network", "/tmp/net.xml.gz",
				"--travel-times", "/tmp/tt.tsv",
				"--output-dir", "/tmp/out"
		};
		RunDemandExtractionPhase2.Phase2Args parsed = RunDemandExtractionPhase2.parseArgs(args);
		assertEquals(java.nio.file.Path.of("/tmp/net.xml.gz"), parsed.networkXml());
	}

	// Canonical-requests publishing moved to ExtractionDataManager.publishCanonicalRequests;
	// its behavior is covered by ExtractionDataManagerTest.
}
