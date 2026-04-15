package org.matsim.contrib.demand_extraction.run;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.matsim.contrib.demand_extraction.run.RunBavariaEqasimDemandExtraction.ParsedArgs;

class RunBavariaEqasimDemandExtractionArgsTest {

    @Test
    void parsesRequiredArgsOnly() {
        String[] args = {
                "--sample", "10",
                "--asc-yaml", "calib.yml",
                "--travel-times", "tt.tsv"
        };
        ParsedArgs p = RunBavariaEqasimDemandExtraction.parseArgs(args);
        assertEquals(10, p.sample);
        assertEquals("calib.yml", p.ascYaml);
        assertEquals("tt.tsv", p.travelTimesPath);
        assertEquals(null, p.outputDir);
    }

    @Test
    void parsesOutputDir() {
        String[] args = {
                "--sample", "1",
                "--asc-yaml", "a.yml",
                "--travel-times", "t.tsv",
                "--output-dir", "/tmp/out"
        };
        ParsedArgs p = RunBavariaEqasimDemandExtraction.parseArgs(args);
        assertEquals("/tmp/out", p.outputDir);
    }

    @Test
    void parsesAllSweepFlags() {
        String[] args = {
                "--sample", "10",
                "--asc-yaml", "a.yml",
                "--travel-times", "t.tsv",
                "--search-horizon", "1800",
                "--max-detour-factor", "1.3",
                "--min-drt-cost-per-km", "0.05",
                "--inter-degree-keep-fraction", "0.01"
        };
        ParsedArgs p = RunBavariaEqasimDemandExtraction.parseArgs(args);
        assertEquals(1800.0, p.searchHorizon);
        assertEquals(1.3, p.maxDetourFactor);
        assertEquals(0.05, p.minDrtCostPerKm);
        assertEquals(0.01, p.interDegreeKeepFraction);
    }

    @Test
    void sweepFlagsDefaultToNaNWhenAbsent() {
        String[] args = {
                "--sample", "10",
                "--asc-yaml", "a.yml",
                "--travel-times", "t.tsv"
        };
        ParsedArgs p = RunBavariaEqasimDemandExtraction.parseArgs(args);
        assertEquals(true, Double.isNaN(p.searchHorizon));
        assertEquals(true, Double.isNaN(p.maxDetourFactor));
        assertEquals(true, Double.isNaN(p.minDrtCostPerKm));
        assertEquals(true, Double.isNaN(p.interDegreeKeepFraction));
    }
}
