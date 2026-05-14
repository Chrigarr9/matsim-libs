package org.matsim.contrib.demand_extraction.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;

class RunLyonEqasimDemandExtractionWiringTest {

    @Test
    void unprunedBamasLeavesGatesOff() {
        RunLyonEqasimDemandExtraction.CliArgs args = RunLyonEqasimDemandExtraction.CliArgs.parse(new String[] {
                "--algorithm", "bamas",
                "--sample", "10",
                "--scenario-dir", "/tmp/x",
                "--prefix", "lyon_drt_area_",
                "--travel-times", "/tmp/y",
                "--output-dir", "/tmp/z"
        });

        Config config = ConfigUtils.createConfig();
        RunLyonEqasimDemandExtraction.applyAlgorithmAndPruning(config, args);
        ExMasConfigGroup exMas = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);

        assertEquals(ExMasConfigGroup.Algorithm.BAMAS, exMas.getAlgorithm());
        assertFalse(exMas.isHeuristicPruningEnabled());
        // PruningMode.RATIO_THRESHOLD + interDegreeKeepFraction=1.0 is the no-op
        assertEquals(ExMasConfigGroup.PruningMode.RATIO_THRESHOLD, exMas.getPruningMode());
        assertEquals(1.0, exMas.getInterDegreeKeepFraction(), 1e-9);
    }

    @Test
    void prunedBamasEnablesGateAndCoverageK() {
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

        Config config = ConfigUtils.createConfig();
        RunLyonEqasimDemandExtraction.applyAlgorithmAndPruning(config, args);
        ExMasConfigGroup exMas = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);

        assertTrue(exMas.isHeuristicPruningEnabled());
        assertEquals(0.30, exMas.getPruningDistanceSavingsLogScale(), 1e-9);
        assertEquals(ExMasConfigGroup.PruningMode.COVERAGE_TOPK, exMas.getPruningMode());
        assertEquals(20, exMas.getPruningCoverageK());
    }

    @Test
    void exmasAlgorithmDisablesBamasPruning() {
        RunLyonEqasimDemandExtraction.CliArgs args = RunLyonEqasimDemandExtraction.CliArgs.parse(new String[] {
                "--algorithm", "exmas",
                "--sample", "10",
                "--scenario-dir", "/tmp/x",
                "--prefix", "lyon_drt_area_",
                "--travel-times", "/tmp/y",
                "--output-dir", "/tmp/z"
        });

        Config config = ConfigUtils.createConfig();
        RunLyonEqasimDemandExtraction.applyAlgorithmAndPruning(config, args);
        ExMasConfigGroup exMas = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);

        assertEquals(ExMasConfigGroup.Algorithm.EXMAS, exMas.getAlgorithm());
    }
}
