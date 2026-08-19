package org.matsim.contrib.demand_extraction.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Arg parsing and command assembly for the optional Phase-1 request pre-filter
 * ({@code --filter-study-yaml}). The filter is OFF unless that flag is present; when it
 * is present the orchestrator must locate the ExmasCommuters checkout, build the Python
 * argv, and keep all three new flags out of the args it forwards to Phase 1.
 */
class RunDemandExtractionTwoPhaseFilterArgsTest {

	private static Path workdirWithScript(Path root) throws IOException {
		Path script = root.resolve(RunDemandExtractionTwoPhase.FILTER_SCRIPT);
		Files.createDirectories(script.getParent());
		Files.writeString(script, "# stub\n");
		return root;
	}

	@Test
	void filterIsOffAndDefaultedWhenTheFlagIsAbsent() {
		RunDemandExtractionTwoPhase.OrchestratorArgs orch =
				RunDemandExtractionTwoPhase.OrchestratorArgs.parse(
						new String[] {"--sample", "1", "--scenario-dir", "/tmp/scn"});

		assertFalse(orch.filterEnabled());
		assertNull(orch.filterStudyYaml());
		assertNull(orch.filterWorkdir());
		assertEquals("uv run python", orch.filterPythonCmd());
	}

	@Test
	void parsesAllThreeFilterFlags() {
		RunDemandExtractionTwoPhase.OrchestratorArgs orch =
				RunDemandExtractionTwoPhase.OrchestratorArgs.parse(new String[] {
						"--sample", "1",
						"--scenario-dir", "/tmp/scn",
						"--filter-study-yaml", "/tmp/study.yaml",
						"--filter-python-cmd", "python3",
						"--filter-workdir", "/tmp/ExmasCommuters"
				});

		assertTrue(orch.filterEnabled());
		assertEquals("/tmp/study.yaml", orch.filterStudyYaml());
		assertEquals("python3", orch.filterPythonCmd());
		assertEquals("/tmp/ExmasCommuters", orch.filterWorkdir());
	}

	@Test
	void filterFlagsAreNotForwardedToPhaseOne() {
		String[] stripped = RunDemandExtractionTwoPhase.stripOrchestratorFlags(new String[] {
				"--sample", "1",
				"--scenario-dir", "/tmp/scn",
				"--algorithm", "bamas",
				"--filter-study-yaml", "/tmp/study.yaml",
				"--filter-python-cmd", "python3",
				"--filter-workdir", "/tmp/ExmasCommuters"
		});
		List<String> kept = List.of(stripped);

		assertFalse(kept.contains("--filter-study-yaml"));
		assertFalse(kept.contains("/tmp/study.yaml"));
		assertFalse(kept.contains("--filter-python-cmd"));
		assertFalse(kept.contains("python3"));
		assertFalse(kept.contains("--filter-workdir"));
		assertFalse(kept.contains("/tmp/ExmasCommuters"));
		// Phase-1 flags survive untouched.
		assertTrue(kept.contains("--algorithm"));
		assertTrue(kept.contains("bamas"));
	}

	@Test
	void resolvesAnExplicitWorkdirThatHostsTheScript(@TempDir Path tmp) throws IOException {
		Path dir = workdirWithScript(tmp.resolve("ExmasCommuters"));

		assertEquals(dir, RunDemandExtractionTwoPhase.resolveFilterWorkdir(dir.toString()));
	}

	@Test
	void refusesAWorkdirWithoutTheScript(@TempDir Path tmp) {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> RunDemandExtractionTwoPhase.resolveFilterWorkdir(tmp.toString()));

		assertTrue(ex.getMessage().contains(RunDemandExtractionTwoPhase.FILTER_SCRIPT),
				"the message must name the script that is missing: " + ex.getMessage());
	}

	@Test
	void buildsThePythonArgvWithAbsolutePaths(@TempDir Path tmp) {
		Path dump = tmp.resolve("phase1_dump");
		Path yaml = tmp.resolve("study.yaml");
		Path keep = tmp.resolve("phase1_filter_keep.txt");
		Path meta = tmp.resolve("phase1_filter_meta.json");

		List<String> cmd = RunDemandExtractionTwoPhase.buildFilterCommand(
				"uv run python", dump, yaml, keep, meta);

		// The launcher is split into separate argv tokens (plus cmd /c on Windows).
		int scriptAt = cmd.indexOf(RunDemandExtractionTwoPhase.FILTER_SCRIPT);
		assertTrue(scriptAt > 0, "script must follow the launcher tokens: " + cmd);
		assertEquals(List.of("uv", "run", "python"), cmd.subList(scriptAt - 3, scriptAt));
		if (RunDemandExtractionTwoPhase.isWindows()) {
			assertEquals(List.of("cmd", "/c"), cmd.subList(0, 2));
		}

		assertEquals(dump.resolve("drt_requests_phase1.csv").toAbsolutePath().toString(),
				cmd.get(cmd.indexOf("--phase1-csv") + 1));
		assertEquals(yaml.toAbsolutePath().toString(),
				cmd.get(cmd.indexOf("--study-yaml") + 1));
		assertEquals(keep.toAbsolutePath().toString(),
				cmd.get(cmd.indexOf("--out-keep") + 1));
		assertEquals(meta.toAbsolutePath().toString(),
				cmd.get(cmd.indexOf("--out-meta") + 1));
	}

	@Test
	void collapsesRepeatedWhitespaceInTheLauncher(@TempDir Path tmp) {
		List<String> cmd = RunDemandExtractionTwoPhase.buildFilterCommand(
				"  uv   run  python ", tmp, tmp.resolve("s.yaml"),
				tmp.resolve("k.txt"), tmp.resolve("m.json"));

		int scriptAt = cmd.indexOf(RunDemandExtractionTwoPhase.FILTER_SCRIPT);
		assertEquals(List.of("uv", "run", "python"), cmd.subList(scriptAt - 3, scriptAt));
	}
}
