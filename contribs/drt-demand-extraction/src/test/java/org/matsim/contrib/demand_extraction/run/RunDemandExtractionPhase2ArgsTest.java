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

	@org.junit.jupiter.api.Test
	void copiesPhase1RequestsCsvIntoCanonicalSlot(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
		java.nio.file.Path phase1Dir = tmp.resolve("phase1_dump");
		java.nio.file.Files.createDirectories(phase1Dir);
		java.nio.file.Path src = phase1Dir.resolve(
				org.matsim.contrib.demand_extraction.io.lowmem.PhaseOneDumpLayout.REQUESTS_CSV);
		java.nio.file.Files.writeString(src, "index,directDistance\n0,1234.5\n");

		java.nio.file.Path outputDir = tmp.resolve("out");
		java.nio.file.Path demandDir = outputDir.resolve("drt_demand");
		java.nio.file.Files.createDirectories(demandDir);
		String runId = "lyon-drt-1pct-eqasim-exmas";

		// Under test: a small static helper we will add to Phase 2.
		java.nio.file.Path dst = RunDemandExtractionPhase2.publishCanonicalRequestsCsv(
				phase1Dir, demandDir, runId);

		assertEquals(demandDir.resolve(runId + ".drt_requests.csv"), dst);
		assertTrue(java.nio.file.Files.exists(dst));
		assertEquals(java.nio.file.Files.readString(src), java.nio.file.Files.readString(dst));
	}
}
