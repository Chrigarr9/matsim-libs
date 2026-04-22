package org.matsim.contrib.demand_extraction.upsampling;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.core.scenario.ScenarioUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RunPopulationUpsamplingTest {

    @TempDir
    Path tempDir;

    private Population createDonorPopulation(String prefix, int count, Coord homeCoord) {
        Population pop = PopulationUtils.createPopulation(ConfigUtils.createConfig());
        PopulationFactory fac = pop.getFactory();

        for (int i = 0; i < count; i++) {
            Person p = fac.createPerson(Id.createPersonId(prefix + i));
            p.getAttributes().putAttribute("age", 30 + i);
            p.getAttributes().putAttribute("sex", i % 2 == 0 ? "m" : "f");
            p.getAttributes().putAttribute("carAvailability", i % 3 == 0 ? "none" : "all");
            p.getAttributes().putAttribute("householdIncome", "3000-3500");
            p.getAttributes().putAttribute("householdId", i);
            p.getAttributes().putAttribute("hasPtSubscription", i % 5 == 0);

            Plan plan = fac.createPlan();
            Activity home = fac.createActivityFromCoord("home", homeCoord);
            home.setEndTime(8 * 3600);
            plan.addActivity(home);
            plan.addLeg(fac.createLeg("car"));
            Activity work = fac.createActivityFromCoord("work",
                    new Coord(homeCoord.getX() + 5000, homeCoord.getY()));
            work.setEndTime(17 * 3600);
            plan.addActivity(work);
            plan.addLeg(fac.createLeg("car"));
            plan.addActivity(fac.createActivityFromCoord("home", homeCoord));
            p.addPlan(plan);
            pop.addPerson(p);
        }
        return pop;
    }

    private String createHouseholdsCsv(int count) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("household_id;income;household_size\n");
        for (int i = 0; i < count; i++) {
            String size = (i % 4 == 0) ? "5+" : String.valueOf((i % 4) + 1);
            sb.append(i).append(";3000-3500;").append(size).append("\n");
        }
        Path csvPath = tempDir.resolve("households.csv");
        Files.writeString(csvPath, sb.toString());
        return csvPath.toString();
    }

    // Requires VG250 GeoPackage at matsim_scenarios/bavaria/data/germany/ — not checked into git
    @Disabled("Requires external VG250 GeoPackage data file")
    @Test
    void testEndToEndMerge() throws IOException {
        Coord kelheimCoord = new Coord(709000, 5418000);

        // Write base population (25% = 5 agents)
        Population basePop = createDonorPopulation("base_", 5, kelheimCoord);
        String basePath = tempDir.resolve("base_plans.xml.gz").toString();
        new PopulationWriter(basePop).write(basePath);

        // Write donor population (100% = 20 agents)
        Population donorPop = createDonorPopulation("donor_", 20, kelheimCoord);
        String donorPath = tempDir.resolve("donor_plans.xml.gz").toString();
        new PopulationWriter(donorPop).write(donorPath);

        // Create households CSV for donor
        String hhCsvPath = createHouseholdsCsv(20);

        String outputPath = tempDir.resolve("merged_plans.xml.gz").toString();
        String vg250Path = Path.of("../../../matsim_scenarios/bavaria/data/germany/vg250-ew_12-31.utm32s.gpkg.ebenen.zip")
                .toAbsolutePath().normalize().toString();

        RunPopulationUpsampling.run(basePath, donorPath, hhCsvPath, vg250Path, outputPath, 42L);

        // Read merged population
        var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        new PopulationReader(scenario).readFile(outputPath);
        Population merged = scenario.getPopulation();

        // Should have 20 total (target from donor)
        assertEquals(20, merged.getPersons().size());

        // All 5 base agents should be preserved
        for (int i = 0; i < 5; i++) {
            assertTrue(merged.getPersons().containsKey(Id.createPersonId("base_" + i)));
        }

        // Sampled donor agents should have adapted attributes
        long withCarAvail = merged.getPersons().values().stream()
                .filter(p -> {
                    Object ca = p.getAttributes().getAttribute("sim_carAvailability");
                    return ca != null && ("always".equals(ca) || "never".equals(ca));
                })
                .count();
        assertTrue(withCarAvail > 0, "Some donor agents should have adapted carAvailability");
    }

    @Test
    void testLoadHouseholdSizes() throws IOException {
        String csv = createHouseholdsCsv(10);
        var sizes = RunPopulationUpsampling.loadHouseholdSizes(csv);

        assertEquals(10, sizes.size());
        assertEquals(5, sizes.get(0)); // i=0: i%4==0 -> "5+"
        assertEquals(2, sizes.get(1)); // i=1: i%4+1=2
        assertEquals(3, sizes.get(2)); // i=2: i%4+1=3
        assertEquals(4, sizes.get(3)); // i=3: i%4+1=4
        assertEquals(5, sizes.get(4)); // i=4: i%4==0 -> "5+"
    }
}
