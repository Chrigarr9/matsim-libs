package org.matsim.contrib.demand_extraction.run;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RunLyonEqasimDemandExtractionCliTest {

    @Test
    void parsesAlgorithmGateAndK() {
        RunLyonEqasimDemandExtraction.CliArgs args = RunLyonEqasimDemandExtraction.CliArgs.parse(new String[] {
                "--algorithm", "bamas",
                "--gate-scale", "0.30",
                "--coverage-k", "20",
                "--sample", "10",
                "--scenario-dir", "/tmp/x",
                "--prefix", "lyon_drt_area_",
                "--travel-times", "/tmp/y",
                "--output-dir", "/tmp/z"
        });
        assertEquals("bamas", args.algorithm);
        assertEquals(0.30, args.gateScale);
        assertEquals(20, args.coverageK);
    }

    @Test
    void defaultsAreUnpruned() {
        RunLyonEqasimDemandExtraction.CliArgs args = RunLyonEqasimDemandExtraction.CliArgs.parse(new String[] {
                "--sample", "10",
                "--scenario-dir", "/tmp/x",
                "--prefix", "lyon_drt_area_",
                "--travel-times", "/tmp/y",
                "--output-dir", "/tmp/z"
        });
        assertEquals("bamas", args.algorithm);
        assertEquals(-1.0, args.gateScale);
        assertEquals(0, args.coverageK);
    }

    @Test
    void parsesFocusAndExclusionZone() {
        RunLyonEqasimDemandExtraction.CliArgs args = RunLyonEqasimDemandExtraction.CliArgs.parse(new String[] {
                "--trip-filter-focus", "saint-vulbas",
                "--trip-filter-radius-km", "25",
                "--exclusion-zone", "none",
                "--sample", "10",
                "--scenario-dir", "/tmp/x",
                "--prefix", "lyon_drt_area_",
                "--travel-times", "/tmp/y",
                "--output-dir", "/tmp/z"
        });
        assertEquals("saint-vulbas", args.tripFilterFocus);
        assertEquals(25.0, args.tripFilterRadiusKm);
        assertEquals("none", args.exclusionZone);
    }

	@org.junit.jupiter.api.Test
	void detectsLowMemoryFlagAndStripsIt() {
		String[] args = {"--sample", "1", "--low-memory", "--scenario-dir", "/tmp/scn",
				"--travel-times", "/tmp/tt"};
		org.junit.jupiter.api.Assertions.assertTrue(
				RunLyonEqasimDemandExtraction.hasLowMemoryFlag(args));
		String[] stripped = RunLyonEqasimDemandExtraction.stripLowMemoryFlag(args);
		java.util.List<String> kept = java.util.List.of(stripped);
		org.junit.jupiter.api.Assertions.assertFalse(kept.contains("--low-memory"));
		// Surrounding args still present, in original order.
		org.junit.jupiter.api.Assertions.assertEquals(
				java.util.List.of("--sample", "1", "--scenario-dir", "/tmp/scn",
						"--travel-times", "/tmp/tt"),
				kept);
	}

	@org.junit.jupiter.api.Test
	void hasLowMemoryFlagReturnsFalseWhenAbsent() {
		org.junit.jupiter.api.Assertions.assertFalse(
				RunLyonEqasimDemandExtraction.hasLowMemoryFlag(
						new String[] {"--sample", "1", "--scenario-dir", "/x"}));
	}
}
