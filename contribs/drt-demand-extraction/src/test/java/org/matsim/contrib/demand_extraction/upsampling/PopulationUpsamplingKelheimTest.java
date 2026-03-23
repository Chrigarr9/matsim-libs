package org.matsim.contrib.demand_extraction.upsampling;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.scenario.ScenarioUtils;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@Disabled("Requires Kelheim scenario data and VG250 shapefile — run manually with actual data paths")
class PopulationUpsamplingKelheimTest {

    @TempDir
    Path tempDir;

    @Test
    void testKelheimUpsampling() {
        // Paths to actual data (adjust as needed)
        String basePath = "path/to/kelheim-v3.0-25pct-output-plans.xml.gz";
        String donorPath = "path/to/kelheim_100pct_population.xml.gz";
        String donorHouseholdsCsv = "path/to/kelheim_100pct_households.csv";
        String vg250Path = "path/to/vg250-ew_12-31.utm32s.gpkg.ebenen.zip";
        String outputPath = tempDir.resolve("kelheim-100pct-merged.xml.gz").toString();

        RunPopulationUpsampling.run(basePath, donorPath, donorHouseholdsCsv, vg250Path, outputPath, 4711L);

        // Verify
        var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        new PopulationReader(scenario).readFile(outputPath);
        Population merged = scenario.getPopulation();

        // Rough sanity checks
        assertTrue(merged.getPersons().size() > 30000,
                "Merged population should be significantly larger than 25% base");

        for (Person p : merged.getPersons().values()) {
            assertFalse(p.getPlans().isEmpty(),
                    "Person " + p.getId() + " should have at least one plan");
        }
    }
}
