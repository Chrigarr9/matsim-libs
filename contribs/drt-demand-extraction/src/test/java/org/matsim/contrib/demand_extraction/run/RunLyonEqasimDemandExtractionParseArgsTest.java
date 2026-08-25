package org.matsim.contrib.demand_extraction.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The trimmed {@code parseArgs} keeps only location flags. Every result-affecting ExMAS
 * knob was removed 2026-08-19 (spec D4) in favor of the {@code --exmas-config} overlay,
 * and -- unlike the old {@code default -> log.warn(...)} CLI -- an unrecognized flag now
 * fails loudly rather than being silently dropped.
 */
class RunLyonEqasimDemandExtractionParseArgsTest {

	@Test
	void parsesAllLocationFlags() {
		var p = RunLyonEqasimDemandExtraction.parseArgs(new String[] {
				"--sample", "10",
				"--scenario-dir", "/tmp/scenario",
				"--prefix", "lyon_drt_area_",
				"--travel-times", "/tmp/tt.tsv",
				"--output-dir", "/tmp/out",
				"--exmas-config", "/tmp/out/exmas-config.xml",
		});
		assertEquals(10, p.sample);
		assertEquals("/tmp/scenario", p.scenarioDir);
		assertEquals("lyon_drt_area_", p.prefix);
		assertEquals("/tmp/tt.tsv", p.travelTimesPath);
		assertEquals("/tmp/out", p.outputDir);
		assertEquals("/tmp/out/exmas-config.xml", p.exmasConfig);
	}

	@Test
	void prefixDefaultsToLyonDrtArea() {
		var p = RunLyonEqasimDemandExtraction.parseArgs(new String[] {
				"--sample", "1",
				"--scenario-dir", "/tmp/scenario",
				"--travel-times", "/tmp/tt.tsv",
		});
		assertEquals("lyon_drt_area_", p.prefix);
	}

	@Test
	void exmasConfigIsNullWhenAbsent() {
		var p = RunLyonEqasimDemandExtraction.parseArgs(new String[] {
				"--sample", "1",
				"--scenario-dir", "/tmp/scenario",
				"--travel-times", "/tmp/tt.tsv",
		});
		assertNull(p.exmasConfig);
	}

	@Test
	void toleratesTheLowMemoryFlagAsANoOp() {
		// main() strips --low-memory before calling parseArgs in the real flow; parseArgs
		// itself tolerates it too so it stays safe to call on the raw two-phase CLI surface.
		var p = RunLyonEqasimDemandExtraction.parseArgs(new String[] {
				"--sample", "1",
				"--scenario-dir", "/tmp/scenario",
				"--travel-times", "/tmp/tt.tsv",
				"--low-memory",
		});
		assertEquals(1, p.sample);
	}

	@Test
	void toleratesOrchestratorOnlyFlagsWithoutMisreadingTheirValues() {
		// RunDemandExtractionTwoPhase strips these before forwarding, but parseArgs must
		// not choke (or misread the next flag as a value) if it ever sees the full surface.
		var p = RunLyonEqasimDemandExtraction.parseArgs(new String[] {
				"--sample", "1",
				"--scenario-dir", "/tmp/scenario",
				"--travel-times", "/tmp/tt.tsv",
				"--phase1-heap", "110g",
				"--phase2-heap", "110g",
				"--java", "/opt/jdk/bin/java",
				"--network", "/tmp/net.xml.gz",
				"--phase1-dump-dir", "/tmp/dump",
		});
		assertEquals(1, p.sample);
		assertEquals("/tmp/scenario", p.scenarioDir);
	}

	@Test
	void unknownFlagFailsLoudlyInsteadOfBeingSilentlyDropped() {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> RunLyonEqasimDemandExtraction.parseArgs(new String[] {
						"--sample", "1",
						"--scenario-dir", "/tmp/scenario",
						"--travel-times", "/tmp/tt.tsv",
						"--max-detour-factr", "1.5",
				}));
		assertTrue(ex.getMessage().contains("--max-detour-factr"),
				"the error must name the offending flag: " + ex.getMessage());
	}

	@Test
	void removedResultAffectingFlagFailsLoudly() {
		// --algorithm was one of the ~50 result-affecting flags removed 2026-08-19; it
		// must not silently no-op the way the old default-branch log.warn did.
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> RunLyonEqasimDemandExtraction.parseArgs(new String[] {
						"--sample", "1",
						"--scenario-dir", "/tmp/scenario",
						"--travel-times", "/tmp/tt.tsv",
						"--algorithm", "bamas",
				}));
		assertTrue(ex.getMessage().contains("--algorithm"));
	}

	@Test
	void deprecatedDeterministicRoutingFlagIsNowUnknown() {
		// Formerly a no-op (log.warn + ignore); the whole default-branch silent-drop path
		// is gone, so this legacy flag now fails loudly like any other unknown flag.
		assertThrows(IllegalArgumentException.class,
				() -> RunLyonEqasimDemandExtraction.parseArgs(new String[] {
						"--sample", "1",
						"--scenario-dir", "/tmp/scenario",
						"--travel-times", "/tmp/tt.tsv",
						"--deterministic-routing",
				}));
	}
}
