package org.matsim.contrib.demand_extraction.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@code --low-memory} is the one flag {@link RunLyonEqasimDemandExtraction#main} still
 * inspects directly, to decide whether to delegate to {@link RunDemandExtractionTwoPhase}.
 * Every other CLI knob is parsed by {@link RunLyonEqasimDemandExtraction#parseArgs}.
 */
class RunLyonEqasimDemandExtractionLowMemoryFlagTest {

	@Test
	void detectsLowMemoryFlagAndStripsIt() {
		String[] args = {"--sample", "1", "--low-memory", "--scenario-dir", "/tmp/scn",
				"--travel-times", "/tmp/tt"};
		assertTrue(RunLyonEqasimDemandExtraction.hasLowMemoryFlag(args));
		String[] stripped = RunLyonEqasimDemandExtraction.stripLowMemoryFlag(args);
		List<String> kept = List.of(stripped);
		assertFalse(kept.contains("--low-memory"));
		// Surrounding args still present, in original order.
		assertEquals(
				List.of("--sample", "1", "--scenario-dir", "/tmp/scn",
						"--travel-times", "/tmp/tt"),
				kept);
	}

	@Test
	void hasLowMemoryFlagReturnsFalseWhenAbsent() {
		assertFalse(RunLyonEqasimDemandExtraction.hasLowMemoryFlag(
				new String[] {"--sample", "1", "--scenario-dir", "/x"}));
	}
}
