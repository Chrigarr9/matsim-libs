package org.matsim.contrib.demand_extraction.run;

import org.junit.jupiter.api.Test;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup.FleetSide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunLyonEqasimDemandExtractionParseArgsTest {

    @Test
    void defaultsLeaveStopBasedKnobsOff() {
        var p = RunLyonEqasimDemandExtraction.parseArgs(new String[] {
                "--sample", "1",
                "--scenario-dir", "/tmp/scenario",
                "--travel-times", "/tmp/tt.tsv",
        });
        assertFalse(p.enableStopBased, "stop-based off by default");
        assertFalse(p.enableHyperPooling, "hyper-pool off by default");
        assertFalse(p.enableBudgetAwareConstraints, "budget-aware off by default");
        assertTrue(Double.isNaN(p.maxWalkDistanceMeters),
                "max-walk-distance NaN sentinel by default");
    }

    @Test
    void parsesAllFourNewFlags() {
        var p = RunLyonEqasimDemandExtraction.parseArgs(new String[] {
                "--sample", "1",
                "--scenario-dir", "/tmp/scenario",
                "--travel-times", "/tmp/tt.tsv",
                "--enable-stop-based",
                "--enable-hyperpooling",
                "--enable-budget-aware-constraints",
                "--max-walk-distance-meters", "1000",
        });
        assertTrue(p.enableStopBased);
        assertTrue(p.enableHyperPooling);
        assertTrue(p.enableBudgetAwareConstraints);
        assertEquals(1000.0, p.maxWalkDistanceMeters, 0.0);
    }

    @Test
    void defaultsLeaveRequestClassificationsPathNull() {
        var p = RunLyonEqasimDemandExtraction.parseArgs(new String[] {
                "--sample", "1",
                "--scenario-dir", "/tmp/scenario",
                "--travel-times", "/tmp/tt.tsv",
        });
        assertNull(p.requestClassificationsPath,
                "request-classifications path null by default");
    }

    @Test
    void parsesRequestClassificationsFlag() {
        var p = RunLyonEqasimDemandExtraction.parseArgs(new String[] {
                "--sample", "1",
                "--scenario-dir", "/tmp/scenario",
                "--travel-times", "/tmp/tt.tsv",
                "--request-classifications", "/tmp/cls.csv",
        });
        assertEquals("/tmp/cls.csv", p.requestClassificationsPath);
    }

    @Test
    void defaultsLeaveFleetSideNull() {
        var p = RunLyonEqasimDemandExtraction.parseArgs(new String[] {
                "--sample", "1",
                "--scenario-dir", "/tmp/scenario",
                "--travel-times", "/tmp/tt.tsv",
        });
        assertNull(p.fleetSide, "fleet-side null by default (Kelheim / Paper-1 path)");
    }

    @Test
    void parsesFleetSideUrban() {
        var p = RunLyonEqasimDemandExtraction.parseArgs(new String[] {
                "--sample", "1",
                "--scenario-dir", "/tmp/scenario",
                "--travel-times", "/tmp/tt.tsv",
                "--fleet-side", "URBAN",
        });
        assertEquals(FleetSide.URBAN, p.fleetSide);
    }

    @Test
    void parsesFleetSideRuralCaseInsensitive() {
        var p = RunLyonEqasimDemandExtraction.parseArgs(new String[] {
                "--sample", "1",
                "--scenario-dir", "/tmp/scenario",
                "--travel-times", "/tmp/tt.tsv",
                "--fleet-side", "rural",
        });
        assertEquals(FleetSide.RURAL, p.fleetSide);
    }

    @Test
    void defaultsLeaveMetropolePolygonNull() {
        var p = RunLyonEqasimDemandExtraction.parseArgs(new String[] {
                "--sample", "1",
                "--scenario-dir", "/tmp/scenario",
                "--travel-times", "/tmp/tt.tsv",
        });
        assertNull(p.metropolePolygonPath, "metropole-polygon null by default");
    }

    @Test
    void parsesMetropolePolygonFlag() {
        var p = RunLyonEqasimDemandExtraction.parseArgs(new String[] {
                "--sample", "1",
                "--scenario-dir", "/tmp/scenario",
                "--travel-times", "/tmp/tt.tsv",
                "--metropole-polygon", "/tmp/metropole.shp",
        });
        assertEquals("/tmp/metropole.shp", p.metropolePolygonPath);
    }

    @Test
    void defaultsLeaveExpandConnectingBothSidesFalse() {
        var p = RunLyonEqasimDemandExtraction.parseArgs(new String[] {
                "--sample", "1",
                "--scenario-dir", "/tmp/scenario",
                "--travel-times", "/tmp/tt.tsv",
        });
        assertFalse(p.expandConnectingBothSides,
                "expand-connecting-both-sides off by default");
    }

    @Test
    void parsesExpandConnectingBothSidesFlag() {
        var p = RunLyonEqasimDemandExtraction.parseArgs(new String[] {
                "--sample", "1",
                "--scenario-dir", "/tmp/scenario",
                "--travel-times", "/tmp/tt.tsv",
                "--expand-connecting-both-sides",
        });
        assertTrue(p.expandConnectingBothSides);
    }

    @Test
    void defaultsLeaveMaxDetourFactorByClassEmpty() {
        var p = RunLyonEqasimDemandExtraction.parseArgs(new String[] {
                "--sample", "1",
                "--scenario-dir", "/tmp/scenario",
                "--travel-times", "/tmp/tt.tsv",
        });
        assertTrue(p.maxDetourFactorByClass.isEmpty(),
                "max-detour-factor-by-class empty by default");
    }

    @Test
    void parsesMaxDetourFactorByClassSpec() {
        var p = RunLyonEqasimDemandExtraction.parseArgs(new String[] {
                "--sample", "1",
                "--scenario-dir", "/tmp/scenario",
                "--travel-times", "/tmp/tt.tsv",
                "--max-detour-factor-by-class", "connecting=1.05,rural_intra=1.3",
        });
        assertEquals(2, p.maxDetourFactorByClass.size());
        assertEquals(1.05, p.maxDetourFactorByClass.get("connecting"), 0.0);
        assertEquals(1.3, p.maxDetourFactorByClass.get("rural_intra"), 0.0);
    }
}
