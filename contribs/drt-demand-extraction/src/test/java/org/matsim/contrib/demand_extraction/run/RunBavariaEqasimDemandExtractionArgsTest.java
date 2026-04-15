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
}
