package org.matsim.contrib.demand_extraction.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
        assertNull(args.profile);
    }
}
