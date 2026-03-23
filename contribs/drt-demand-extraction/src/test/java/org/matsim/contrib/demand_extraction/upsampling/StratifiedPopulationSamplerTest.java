package org.matsim.contrib.demand_extraction.upsampling;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class StratifiedPopulationSamplerTest {

    private Population createTestPopulation(String idPrefix, Map<String, List<Coord>> municipalityHomes) {
        Population pop = PopulationUtils.createPopulation(ConfigUtils.createConfig());
        PopulationFactory fac = pop.getFactory();
        int counter = 0;

        for (Map.Entry<String, List<Coord>> entry : municipalityHomes.entrySet()) {
            for (Coord coord : entry.getValue()) {
                Person p = fac.createPerson(Id.createPersonId(idPrefix + counter++));
                p.getAttributes().putAttribute("carAvailability", "all");
                p.getAttributes().putAttribute("householdIncome", "3000-3500");
                p.getAttributes().putAttribute("householdId", counter);
                p.getAttributes().putAttribute("hasPtSubscription", false);
                Plan plan = fac.createPlan();
                Activity home = fac.createActivityFromCoord("home", coord);
                home.setEndTime(8 * 3600);
                plan.addActivity(home);
                plan.addLeg(fac.createLeg("car"));
                plan.addActivity(fac.createActivityFromCoord("work", new Coord(coord.getX() + 1000, coord.getY())));
                p.addPlan(plan);
                pop.addPerson(p);
            }
        }
        return pop;
    }

    @Test
    void testBasicSampling() {
        // Base: 2 agents in municipality A, 1 in B
        // Donor: 8 agents in A, 4 in B (= 100% target)
        // Expected: sample 6 from A, 3 from B = 12 total
        Map<String, List<Coord>> baseHomes = new LinkedHashMap<>();
        baseHomes.put("MUN_A", List.of(new Coord(1, 1), new Coord(2, 2)));
        baseHomes.put("MUN_B", List.of(new Coord(100, 100)));

        Map<String, List<Coord>> donorHomes = new LinkedHashMap<>();
        donorHomes.put("MUN_A", new ArrayList<>());
        for (int i = 0; i < 8; i++) donorHomes.get("MUN_A").add(new Coord(i + 10, i + 10));
        donorHomes.put("MUN_B", new ArrayList<>());
        for (int i = 0; i < 4; i++) donorHomes.get("MUN_B").add(new Coord(i + 200, i + 200));

        Population basePop = createTestPopulation("base_", baseHomes);
        Population donorPop = createTestPopulation("donor_", donorHomes);

        // Create municipality mappings
        Map<Id<Person>, String> baseMapping = new LinkedHashMap<>();
        int idx = 0;
        for (Map.Entry<String, List<Coord>> e : baseHomes.entrySet()) {
            for (int i = 0; i < e.getValue().size(); i++) {
                baseMapping.put(Id.createPersonId("base_" + idx++), e.getKey());
            }
        }

        Map<Id<Person>, String> donorMapping = new LinkedHashMap<>();
        idx = 0;
        for (Map.Entry<String, List<Coord>> e : donorHomes.entrySet()) {
            for (int i = 0; i < e.getValue().size(); i++) {
                donorMapping.put(Id.createPersonId("donor_" + idx++), e.getKey());
            }
        }

        Map<Integer, Integer> emptySizes = Map.of();

        StratifiedPopulationSampler sampler = new StratifiedPopulationSampler(42L);
        Population merged = sampler.merge(basePop, baseMapping, donorPop, donorMapping, emptySizes);

        // Should have 8 + 4 = 12 total agents
        assertEquals(12, merged.getPersons().size());

        // All base persons should be in merged
        for (Id<Person> baseId : basePop.getPersons().keySet()) {
            assertTrue(merged.getPersons().containsKey(baseId),
                    "Base person " + baseId + " should be in merged population");
        }
    }

    @Test
    void testNoDeficit() {
        // Base already has target count - no sampling needed
        Map<String, List<Coord>> homes = new LinkedHashMap<>();
        homes.put("MUN_A", List.of(new Coord(1, 1), new Coord(2, 2)));

        Population basePop = createTestPopulation("base_", homes);
        Population donorPop = createTestPopulation("donor_", homes);

        Map<Id<Person>, String> baseMapping = Map.of(
                Id.createPersonId("base_0"), "MUN_A",
                Id.createPersonId("base_1"), "MUN_A");
        Map<Id<Person>, String> donorMapping = Map.of(
                Id.createPersonId("donor_0"), "MUN_A",
                Id.createPersonId("donor_1"), "MUN_A");

        Map<Integer, Integer> emptySizes = Map.of();
        StratifiedPopulationSampler sampler = new StratifiedPopulationSampler(42L);
        Population merged = sampler.merge(basePop, baseMapping, donorPop, donorMapping, emptySizes);

        // Should only have the 2 base agents (no deficit)
        assertEquals(2, merged.getPersons().size());
    }

    @Test
    void testReproducibility() {
        Map<String, List<Coord>> donorHomes = new LinkedHashMap<>();
        donorHomes.put("MUN_A", new ArrayList<>());
        for (int i = 0; i < 20; i++) donorHomes.get("MUN_A").add(new Coord(i, i));

        Map<String, List<Coord>> baseHomes = new LinkedHashMap<>();
        baseHomes.put("MUN_A", List.of(new Coord(1, 1)));

        Population basePop = createTestPopulation("base_", baseHomes);
        Population donorPop = createTestPopulation("donor_", donorHomes);

        Map<Id<Person>, String> baseMapping = Map.of(Id.createPersonId("base_0"), "MUN_A");
        Map<Id<Person>, String> donorMapping = new LinkedHashMap<>();
        for (int i = 0; i < 20; i++) {
            donorMapping.put(Id.createPersonId("donor_" + i), "MUN_A");
        }

        Map<Integer, Integer> emptySizes = Map.of();

        // Run twice with same seed
        StratifiedPopulationSampler s1 = new StratifiedPopulationSampler(42L);
        Population m1 = s1.merge(basePop, baseMapping, donorPop, donorMapping, emptySizes);

        StratifiedPopulationSampler s2 = new StratifiedPopulationSampler(42L);
        Population m2 = s2.merge(basePop, baseMapping, donorPop, donorMapping, emptySizes);

        assertEquals(m1.getPersons().keySet(), m2.getPersons().keySet());
    }

    @Test
    void testPartialSamplingRate() {
        // Donor: 40 agents in A (= 100% census), 20 in B
        // Base: 2 in A, 0 in B (Kelheim only covers A)
        // With targetSamplingRate=0.25: target_A = 10, target_B = 5
        // Deficit_A = 10 - 2 = 8, Deficit_B = 5 - 0 = 5
        // Total = 2 + 8 + 5 = 15
        Map<String, List<Coord>> baseHomes = new LinkedHashMap<>();
        baseHomes.put("MUN_A", List.of(new Coord(1, 1), new Coord(2, 2)));

        Map<String, List<Coord>> donorHomes = new LinkedHashMap<>();
        donorHomes.put("MUN_A", new ArrayList<>());
        for (int i = 0; i < 40; i++) donorHomes.get("MUN_A").add(new Coord(i + 10, i + 10));
        donorHomes.put("MUN_B", new ArrayList<>());
        for (int i = 0; i < 20; i++) donorHomes.get("MUN_B").add(new Coord(i + 200, i + 200));

        Population basePop = createTestPopulation("base_", baseHomes);
        Population donorPop = createTestPopulation("donor_", donorHomes);

        Map<Id<Person>, String> baseMapping = Map.of(
                Id.createPersonId("base_0"), "MUN_A",
                Id.createPersonId("base_1"), "MUN_A");

        Map<Id<Person>, String> donorMapping = new LinkedHashMap<>();
        int idx = 0;
        for (Map.Entry<String, List<Coord>> e : donorHomes.entrySet()) {
            for (int i = 0; i < e.getValue().size(); i++) {
                donorMapping.put(Id.createPersonId("donor_" + idx++), e.getKey());
            }
        }

        Map<Integer, Integer> emptySizes = Map.of();

        StratifiedPopulationSampler sampler = new StratifiedPopulationSampler(42L);
        Population merged = sampler.merge(basePop, baseMapping, donorPop, donorMapping,
                emptySizes, 0.25);

        // target_A = round(40*0.25) = 10, existing = 2, deficit = 8
        // target_B = round(20*0.25) = 5, existing = 0, deficit = 5
        // total = 2 base + 8 + 5 = 15
        assertEquals(15, merged.getPersons().size());

        // Both base agents preserved
        assertTrue(merged.getPersons().containsKey(Id.createPersonId("base_0")));
        assertTrue(merged.getPersons().containsKey(Id.createPersonId("base_1")));
    }

    @Test
    void testSamplingRateBaseExceedsTarget() {
        // Donor: 4 agents in A (= 100%)
        // Base: 3 in A (Kelheim has more than 25% of census in this municipality)
        // With targetSamplingRate=0.25: target_A = 1
        // Deficit = 1 - 3 = -2 → no sampling, keep base
        Map<String, List<Coord>> baseHomes = new LinkedHashMap<>();
        baseHomes.put("MUN_A", List.of(new Coord(1, 1), new Coord(2, 2), new Coord(3, 3)));

        Map<String, List<Coord>> donorHomes = new LinkedHashMap<>();
        donorHomes.put("MUN_A", new ArrayList<>());
        for (int i = 0; i < 4; i++) donorHomes.get("MUN_A").add(new Coord(i + 10, i + 10));

        Population basePop = createTestPopulation("base_", baseHomes);
        Population donorPop = createTestPopulation("donor_", donorHomes);

        Map<Id<Person>, String> baseMapping = new LinkedHashMap<>();
        for (int i = 0; i < 3; i++) baseMapping.put(Id.createPersonId("base_" + i), "MUN_A");

        Map<Id<Person>, String> donorMapping = new LinkedHashMap<>();
        for (int i = 0; i < 4; i++) donorMapping.put(Id.createPersonId("donor_" + i), "MUN_A");

        Map<Integer, Integer> emptySizes = Map.of();

        StratifiedPopulationSampler sampler = new StratifiedPopulationSampler(42L);
        Population merged = sampler.merge(basePop, baseMapping, donorPop, donorMapping,
                emptySizes, 0.25);

        // Base already exceeds 25% target → no donors added, only 3 base agents
        assertEquals(3, merged.getPersons().size());
    }

    @Test
    void testBaseRetentionZero() {
        // Base: 10 agents in A. Donor: 20 in A. Target 100%, retention 0%.
        // Should discard all base, sample 20 from donor.
        Map<String, List<Coord>> baseHomes = new LinkedHashMap<>();
        baseHomes.put("MUN_A", new ArrayList<>());
        for (int i = 0; i < 10; i++) baseHomes.get("MUN_A").add(new Coord(i, i));

        Map<String, List<Coord>> donorHomes = new LinkedHashMap<>();
        donorHomes.put("MUN_A", new ArrayList<>());
        for (int i = 0; i < 20; i++) donorHomes.get("MUN_A").add(new Coord(i + 100, i + 100));

        Population basePop = createTestPopulation("base_", baseHomes);
        Population donorPop = createTestPopulation("donor_", donorHomes);

        Map<Id<Person>, String> baseMapping = new LinkedHashMap<>();
        for (int i = 0; i < 10; i++) baseMapping.put(Id.createPersonId("base_" + i), "MUN_A");
        Map<Id<Person>, String> donorMapping = new LinkedHashMap<>();
        for (int i = 0; i < 20; i++) donorMapping.put(Id.createPersonId("donor_" + i), "MUN_A");

        Map<Integer, Integer> emptySizes = Map.of();
        StratifiedPopulationSampler sampler = new StratifiedPopulationSampler(42L);
        Population merged = sampler.merge(basePop, baseMapping, donorPop, donorMapping,
                emptySizes, 1.0, 0.0);

        // 0 base retained, 20 donors sampled
        assertEquals(20, merged.getPersons().size());
        // No base agents should be present
        for (int i = 0; i < 10; i++) {
            assertFalse(merged.getPersons().containsKey(Id.createPersonId("base_" + i)),
                    "Base agent should not be in merged with retention=0.0");
        }
    }

    @Test
    void testBaseRetentionPartial() {
        // Base: 20 agents in A. Donor: 40 in A. Target 100%, retention 10%.
        // Retain round(20*0.1) = 2 base agents. Target = 40, deficit = 40-2 = 38.
        // Total = 2 + 38 = 40.
        Map<String, List<Coord>> baseHomes = new LinkedHashMap<>();
        baseHomes.put("MUN_A", new ArrayList<>());
        for (int i = 0; i < 20; i++) baseHomes.get("MUN_A").add(new Coord(i, i));

        Map<String, List<Coord>> donorHomes = new LinkedHashMap<>();
        donorHomes.put("MUN_A", new ArrayList<>());
        for (int i = 0; i < 40; i++) donorHomes.get("MUN_A").add(new Coord(i + 100, i + 100));

        Population basePop = createTestPopulation("base_", baseHomes);
        Population donorPop = createTestPopulation("donor_", donorHomes);

        Map<Id<Person>, String> baseMapping = new LinkedHashMap<>();
        for (int i = 0; i < 20; i++) baseMapping.put(Id.createPersonId("base_" + i), "MUN_A");
        Map<Id<Person>, String> donorMapping = new LinkedHashMap<>();
        for (int i = 0; i < 40; i++) donorMapping.put(Id.createPersonId("donor_" + i), "MUN_A");

        Map<Integer, Integer> emptySizes = Map.of();
        StratifiedPopulationSampler sampler = new StratifiedPopulationSampler(42L);
        Population merged = sampler.merge(basePop, baseMapping, donorPop, donorMapping,
                emptySizes, 1.0, 0.1);

        // 2 base retained + 38 donors = 40 total
        assertEquals(40, merged.getPersons().size());

        // Exactly 2 base agents should be present
        long baseCount = merged.getPersons().keySet().stream()
                .filter(id -> id.toString().startsWith("base_"))
                .count();
        assertEquals(2, baseCount, "Should retain 10% of 20 = 2 base agents");
    }
}
