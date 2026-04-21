package org.matsim.contrib.demand_extraction.upsampling;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

// Requires VG250 GeoPackage at matsim_scenarios/bavaria/data/germany/ — not checked into git
@Disabled("Requires external VG250 GeoPackage data file")
class MunicipalityMapperTest {

    // VG250 GeoPackage in the repo (relative from drt-demand-extraction module root)
    private static final String VG250_PATH = Path.of("../../../matsim_scenarios/bavaria/data/germany/vg250-ew_12-31.utm32s.gpkg.ebenen.zip")
            .toAbsolutePath().normalize().toString();

    @Test
    void testMapPersonToMunicipality() {
        // Kelheim town center is approximately at EPSG:25832: x=709000, y=5418000
        // This should map to a municipality in Landkreis Kelheim (ARS starts with "09273")
        MunicipalityMapper mapper = new MunicipalityMapper(VG250_PATH, "vg250_gem", "ARS");

        Coord kelheimCoord = new Coord(709000, 5418000);
        String ars = mapper.getMunicipality(kelheimCoord);

        assertNotNull(ars, "Should find a municipality for Kelheim coordinates");
        assertTrue(ars.startsWith("09273"), "Kelheim should be in Landkreis Kelheim (09273), got: " + ars);
    }

    @Test
    void testMapPopulationToMunicipalities() {
        MunicipalityMapper mapper = new MunicipalityMapper(VG250_PATH, "vg250_gem", "ARS");

        Population pop = PopulationUtils.createPopulation(ConfigUtils.createConfig());
        PopulationFactory fac = pop.getFactory();

        // Agent in Kelheim
        Person p1 = fac.createPerson(Id.createPersonId("1"));
        Plan plan1 = fac.createPlan();
        Activity home1 = fac.createActivityFromCoord("home_77400", new Coord(709000, 5418000));
        home1.setEndTime(8 * 3600);
        plan1.addActivity(home1);
        plan1.addLeg(fac.createLeg("car"));
        plan1.addActivity(fac.createActivityFromCoord("work_28800", new Coord(720000, 5430000)));
        p1.addPlan(plan1);
        pop.addPerson(p1);

        // Agent outside any municipality (invalid coord)
        Person p2 = fac.createPerson(Id.createPersonId("2"));
        Plan plan2 = fac.createPlan();
        plan2.addActivity(fac.createActivityFromCoord("home", new Coord(0, 0)));
        p2.addPlan(plan2);
        pop.addPerson(p2);

        Map<Id<Person>, String> mapping = mapper.mapPopulation(pop);

        assertEquals(1, mapping.size(), "Only one person should have a valid municipality");
        assertTrue(mapping.containsKey(Id.createPersonId("1")));
        assertTrue(mapping.get(Id.createPersonId("1")).startsWith("09273"));
    }

    @Test
    void testHomeActivityDetection() {
        MunicipalityMapper mapper = new MunicipalityMapper(VG250_PATH, "vg250_gem", "ARS");

        Population pop = PopulationUtils.createPopulation(ConfigUtils.createConfig());
        PopulationFactory fac = pop.getFactory();

        Person p = fac.createPerson(Id.createPersonId("1"));
        Plan plan = fac.createPlan();
        // First activity is NOT home
        Activity work = fac.createActivityFromCoord("work_28800", new Coord(720000, 5430000));
        work.setEndTime(17 * 3600);
        plan.addActivity(work);
        plan.addLeg(fac.createLeg("car"));
        // Second activity IS home
        plan.addActivity(fac.createActivityFromCoord("home_61200", new Coord(709000, 5418000)));
        p.addPlan(plan);
        pop.addPerson(p);

        Map<Id<Person>, String> mapping = mapper.mapPopulation(pop);

        assertEquals(1, mapping.size());
        assertTrue(mapping.get(Id.createPersonId("1")).startsWith("09273"));
    }
}
