package org.matsim.contrib.demand_extraction.scenarios;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;

class LyonEqasimScenarioFixtureTest {

    @Test
    void parametricFocusOverridesLegacyDefault() {
        LyonEqasimScenarioFixture.FilterConfig filter = new LyonEqasimScenarioFixture.FilterConfig(
                855300.0, 6526000.0, 25.0, null /* no exclusion shapefile */);
        LyonEqasimScenarioFixture fixture = new LyonEqasimScenarioFixture(
                10, "/tmp/scenario", "lyon_drt_area_", "/tmp/tt.tsv", filter);

        Config config = ConfigUtils.createConfig();
        fixture.applyExMasDefaults(config);
        ExMasConfigGroup exMas = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);

        assertEquals(25.0, exMas.getTripFilterRadiusKm(), 1e-9);
        assertEquals(855300.0, exMas.getTripFilterCenterX(), 1e-9);
        assertEquals(6526000.0, exMas.getTripFilterCenterY(), 1e-9);
    }
}
