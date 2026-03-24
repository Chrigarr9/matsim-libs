package org.matsim.contrib.demand_extraction.upsampling;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.population.PopulationUtils;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class AttributeAdapterTest {

    @Test
    void testCarAvailabilityMapping() {
        Person person = createEqasimPerson("1", "all", "5000+");
        AttributeAdapter.adapt(person, 2, new Random(42));

        assertEquals("always", PersonUtils.getCarAvail(person));
        assertEquals("always", person.getAttributes().getAttribute("sim_carAvailability"));
    }

    @Test
    void testCarAvailabilityNever() {
        Person person = createEqasimPerson("2", "none", "2000-2500");
        AttributeAdapter.adapt(person, 1, new Random(42));

        assertEquals("never", PersonUtils.getCarAvail(person));
        assertEquals("never", person.getAttributes().getAttribute("sim_carAvailability"));
    }

    @Test
    void testIncomeDerivation() {
        // HH income "3000-3500" with HH size 2 -> incomeGroup 6 -> (3000+rand(1000))/2
        // Expected range: 1500-2000
        Person person = createEqasimPerson("3", "all", "3000-3500");
        AttributeAdapter.adapt(person, 2, new Random(42));

        Double income = PersonUtils.getIncome(person);
        assertNotNull(income, "Income should be set");
        assertTrue(income >= 1500 && income <= 2000,
                "Income should be in range [1500,2000] for group 6 / HH size 2, got: " + income);
    }

    @Test
    void testIncomeGroupMapping() {
        // HH income "4000-5000" -> MiD group 7 (4000-5000 EUR/month)
        Person person = createEqasimPerson("4", "all", "4000-5000");
        AttributeAdapter.adapt(person, 3, new Random(42));

        assertEquals("7", person.getAttributes().getAttribute("MiD:hheink_gr2").toString());
        assertEquals("3", person.getAttributes().getAttribute("MiD:hhgr_gr").toString());
    }

    @Test
    void testSubpopulationSet() {
        Person person = createEqasimPerson("5", "all", "2000-2500");
        AttributeAdapter.adapt(person, 1, new Random(42));

        assertEquals("person", PopulationUtils.getSubpopulation(person));
    }

    @Test
    void testPtSubscriptionMapping() {
        Person person = createEqasimPerson("6", "all", "2000-2500");
        person.getAttributes().putAttribute("hasPtSubscription", true);
        AttributeAdapter.adapt(person, 1, new Random(42));

        assertEquals("full", person.getAttributes().getAttribute("sim_ptAbo"));
    }

    @Test
    void testHouseholdSizeFivePlus() {
        Person person = createEqasimPerson("7", "all", "5000+");
        AttributeAdapter.adapt(person, 5, new Random(42));

        assertEquals("5", person.getAttributes().getAttribute("MiD:hhgr_gr").toString());
    }

    private Person createEqasimPerson(String id, String carAvail, String hhIncome) {
        Population pop = PopulationUtils.createPopulation(ConfigUtils.createConfig());
        PopulationFactory fac = pop.getFactory();
        Person p = fac.createPerson(Id.createPersonId(id));

        p.getAttributes().putAttribute("carAvailability", carAvail);
        p.getAttributes().putAttribute("householdIncome", hhIncome);
        p.getAttributes().putAttribute("age", 35);
        p.getAttributes().putAttribute("sex", "m");
        p.getAttributes().putAttribute("employed", "True");
        p.getAttributes().putAttribute("hasLicense", "yes");
        p.getAttributes().putAttribute("hasPtSubscription", false);
        p.getAttributes().putAttribute("householdId", 100);

        Plan plan = fac.createPlan();
        plan.addActivity(fac.createActivityFromCoord("home", new Coord(709000, 5418000)));
        p.addPlan(plan);
        pop.addPerson(p);
        return p;
    }
}
