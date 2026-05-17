package org.matsim.contrib.demand_extraction.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class RunDemandExtractionTwoPhaseArgsTest {

	@Test
	void parsesAllOrchestratorFlags() {
		String[] args = {
				"--sample", "1",
				"--scenario-dir", "/tmp/scn",
				"--travel-times", "/tmp/tt.tsv",
				"--output-dir", "/tmp/out",
				"--phase1-heap", "24g",
				"--phase2-heap", "8g",
				"--java", "/opt/jdk/bin/java",
				"--network", "/tmp/explicit.net.xml.gz",
				"--phase1-dump-dir", "/tmp/explicit-dump"
		};
		RunDemandExtractionTwoPhase.OrchestratorArgs orch =
				RunDemandExtractionTwoPhase.OrchestratorArgs.parse(args);
		assertEquals("24g", orch.phase1Heap());
		assertEquals("8g", orch.phase2Heap());
		assertEquals("/opt/jdk/bin/java", orch.javaBin());
		assertEquals("/tmp/explicit.net.xml.gz", orch.networkXmlOverride());
		assertEquals("/tmp/explicit-dump", orch.phase1DumpDirOverride());
	}

	@Test
	void leavesNonOrchestratorFlagsForForwarding() {
		String[] args = {
				"--sample", "1",
				"--scenario-dir", "/tmp/scn",
				"--prefix", "lyon_drt_1pct_",
				"--travel-times", "/tmp/tt.tsv",
				"--output-dir", "/tmp/out",
				"--algorithm", "bamas",
				"--phase1-heap", "24g",
				"--phase2-heap", "8g",
				"--phase1-dump-dir", "/tmp/dump"
		};
		String[] stripped = RunDemandExtractionTwoPhase.stripOrchestratorFlags(args);
		List<String> kept = List.of(stripped);
		// Orchestrator-only flags and their values are gone.
		assertFalse(kept.contains("--phase1-heap"));
		assertFalse(kept.contains("24g"));
		assertFalse(kept.contains("--phase2-heap"));
		assertFalse(kept.contains("8g"));
		assertFalse(kept.contains("--phase1-dump-dir"));
		assertFalse(kept.contains("/tmp/dump"));
		// Lyon flags survive.
		assertTrue(kept.contains("--sample"));
		assertTrue(kept.contains("1"));
		assertTrue(kept.contains("--scenario-dir"));
		assertTrue(kept.contains("--prefix"));
		assertTrue(kept.contains("--algorithm"));
		assertTrue(kept.contains("bamas"));
	}

	@Test
	void defaultsArePopulatedWhenFlagsOmitted() {
		String[] args = {"--sample", "1", "--scenario-dir", "/tmp/scn"};
		RunDemandExtractionTwoPhase.OrchestratorArgs orch =
				RunDemandExtractionTwoPhase.OrchestratorArgs.parse(args);
		assertEquals("80g", orch.phase1Heap());
		assertEquals("16g", orch.phase2Heap());
		assertNull(orch.networkXmlOverride());
		assertNull(orch.phase1DumpDirOverride());
		// javaBin defaults to JDK that ran this test (non-null).
		assertTrue(orch.javaBin() != null && !orch.javaBin().isEmpty());
	}

	@Test
	void derivesDefaultNetworkPathFromScenarioDirAndPrefix() {
		RunLyonEqasimDemandExtraction.ParsedArgs p = RunLyonEqasimDemandExtraction.parseArgs(
				new String[] {"--sample", "1", "--scenario-dir", "/tmp/scn",
						"--prefix", "lyon_drt_1pct_", "--travel-times", "/tmp/tt"});
		String expected = java.nio.file.Path.of("/tmp/scn", "lyon_drt_1pct_network.xml.gz").toString();
		assertEquals(expected, RunDemandExtractionTwoPhase.defaultNetworkPath(p));
	}
}
