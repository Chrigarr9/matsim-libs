package org.matsim.contrib.demand_extraction.upsampling;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;

import java.util.*;

public class StratifiedPopulationSampler {

    private static final Logger log = LogManager.getLogger(StratifiedPopulationSampler.class);

    private final Random random;

    public StratifiedPopulationSampler(long seed) {
        this.random = new Random(seed);
    }

    /**
     * Merge base + donor populations with stratified sampling and attribute adaptation.
     * Uses targetSamplingRate=1.0 (upsample to 100%).
     */
    public Population merge(
            Population basePopulation,
            Map<Id<Person>, String> baseMunicipalityMapping,
            Population donorPopulation,
            Map<Id<Person>, String> donorMunicipalityMapping,
            Map<Integer, Integer> householdSizes) {
        return merge(basePopulation, baseMunicipalityMapping, donorPopulation,
                donorMunicipalityMapping, householdSizes, 1.0);
    }

    /**
     * Merge base + donor populations with stratified sampling and attribute adaptation.
     * Uses baseRetentionRate=1.0 (keep all base agents).
     */
    public Population merge(
            Population basePopulation,
            Map<Id<Person>, String> baseMunicipalityMapping,
            Population donorPopulation,
            Map<Id<Person>, String> donorMunicipalityMapping,
            Map<Integer, Integer> householdSizes,
            double targetSamplingRate) {
        return merge(basePopulation, baseMunicipalityMapping, donorPopulation,
                donorMunicipalityMapping, householdSizes, targetSamplingRate, 1.0);
    }

    /**
     * Merge base + donor populations with stratified sampling and attribute adaptation.
     *
     * @param householdSizes mapping from householdId -> household size (from CSV, NOT from XML)
     * @param targetSamplingRate fraction of the donor population to target per municipality (1.0 = 100%, 0.25 = 25%).
     *                           The donor population represents the census-matched 100%. A rate of 0.25 means the
     *                           merged population should have 25% of the census count per municipality.
     * @param baseRetentionRate  fraction of base agents to retain per municipality (1.0 = keep all, 0.0 = discard all,
     *                           0.1 = keep 10%). Retained base agents count toward the target; the remaining deficit
     *                           is filled from the donor pool. Use 0.0 to produce a population entirely from the donor.
     */
    public Population merge(
            Population basePopulation,
            Map<Id<Person>, String> baseMunicipalityMapping,
            Population donorPopulation,
            Map<Id<Person>, String> donorMunicipalityMapping,
            Map<Integer, Integer> householdSizes,
            double targetSamplingRate,
            double baseRetentionRate) {

        // Build target counts from donor, scaled by sampling rate
        // Donor = census-matched 100%; target = donor_count * samplingRate
        Map<String, Integer> rawDonorCounts = new HashMap<>();
        for (String mun : donorMunicipalityMapping.values()) {
            rawDonorCounts.merge(mun, 1, Integer::sum);
        }
        Map<String, Integer> targetCounts = new HashMap<>();
        for (Map.Entry<String, Integer> e : rawDonorCounts.entrySet()) {
            targetCounts.put(e.getKey(), (int) Math.round(e.getValue() * targetSamplingRate));
        }
        log.info("Target sampling rate: {} -> {} target agents across {} municipalities",
                targetSamplingRate, targetCounts.values().stream().mapToInt(Integer::intValue).sum(),
                targetCounts.size());
        log.info("Base retention rate: {}", baseRetentionRate);

        // Group base persons by municipality for stratified retention
        Map<String, List<Id<Person>>> baseByMunicipality = new HashMap<>();
        for (Map.Entry<Id<Person>, String> entry : baseMunicipalityMapping.entrySet()) {
            baseByMunicipality.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(entry.getKey());
        }

        // Group donor persons by municipality
        Map<String, List<Id<Person>>> donorPool = new HashMap<>();
        for (Map.Entry<Id<Person>, String> entry : donorMunicipalityMapping.entrySet()) {
            donorPool.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(entry.getKey());
        }

        // Create output population
        Population merged = PopulationUtils.createPopulation(ConfigUtils.createConfig());

        // Determine which base persons to retain (stratified by municipality)
        Set<Id<Person>> retainedBaseIds = new HashSet<>();
        if (baseRetentionRate >= 1.0) {
            // Keep all
            retainedBaseIds.addAll(baseMunicipalityMapping.keySet());
        } else if (baseRetentionRate > 0.0) {
            // Stratified sampling: retain fraction per municipality
            for (Map.Entry<String, List<Id<Person>>> entry : baseByMunicipality.entrySet()) {
                List<Id<Person>> ids = new ArrayList<>(entry.getValue());
                int retain = (int) Math.round(ids.size() * baseRetentionRate);
                Collections.shuffle(ids, random);
                retainedBaseIds.addAll(ids.subList(0, Math.min(retain, ids.size())));
            }
        }
        // baseRetentionRate == 0.0 -> retainedBaseIds stays empty

        // Copy retained base persons
        for (Id<Person> id : retainedBaseIds) {
            merged.addPerson(basePopulation.getPersons().get(id));
        }
        log.info("Retained {} of {} base agents (rate={})",
                retainedBaseIds.size(), basePopulation.getPersons().size(), baseRetentionRate);

        // Build existing counts from retained base persons
        Map<String, Integer> existingCounts = new HashMap<>();
        for (Id<Person> id : retainedBaseIds) {
            String mun = baseMunicipalityMapping.get(id);
            if (mun != null) {
                existingCounts.merge(mun, 1, Integer::sum);
            }
        }

        int totalSampled = 0;
        int municipalitiesWithDeficit = 0;
        int municipalitiesSkipped = 0;

        // Sample deficit per municipality
        for (Map.Entry<String, Integer> entry : targetCounts.entrySet()) {
            String municipality = entry.getKey();
            int target = entry.getValue();
            int existing = existingCounts.getOrDefault(municipality, 0);
            int deficit = target - existing;

            if (deficit <= 0) {
                if (existing > target) {
                    log.info("Municipality {} already has {} agents (target: {}), skipping",
                            municipality, existing, target);
                }
                municipalitiesSkipped++;
                continue;
            }

            List<Id<Person>> pool = donorPool.getOrDefault(municipality, Collections.emptyList());
            if (pool.isEmpty()) {
                log.warn("Municipality {} needs {} agents but donor pool is empty", municipality, deficit);
                continue;
            }

            // Shuffle and take first `deficit` agents
            List<Id<Person>> shuffled = new ArrayList<>(pool);
            Collections.shuffle(shuffled, random);
            int sampleSize = Math.min(deficit, shuffled.size());

            if (sampleSize < deficit) {
                log.warn("Municipality {} needs {} agents but only {} available in donor pool",
                        municipality, deficit, sampleSize);
            }

            for (int i = 0; i < sampleSize; i++) {
                Id<Person> donorId = shuffled.get(i);
                Person donorPerson = donorPopulation.getPersons().get(donorId);

                // Create new person with unique ID to avoid collisions
                Id<Person> newId = createUniqueId(donorId, merged);
                Person newPerson = merged.getFactory().createPerson(newId);

                // Copy plans
                for (Plan plan : donorPerson.getPlans()) {
                    Plan newPlan = PopulationUtils.createPlan();
                    PopulationUtils.copyFromTo(plan, newPlan);
                    newPerson.addPlan(newPlan);
                    if (plan == donorPerson.getSelectedPlan()) {
                        newPerson.setSelectedPlan(newPlan);
                    }
                }

                // Copy attributes
                for (String attr : donorPerson.getAttributes().getAsMap().keySet()) {
                    newPerson.getAttributes().putAttribute(attr,
                            donorPerson.getAttributes().getAttribute(attr));
                }

                // Adapt eqasim attributes to Kelheim format
                Object hhIdObj = donorPerson.getAttributes().getAttribute("householdId");
                int hhId = hhIdObj instanceof Integer ? (Integer) hhIdObj
                        : Integer.parseInt(hhIdObj.toString());
                int hhSize = householdSizes.getOrDefault(hhId, 1);
                AttributeAdapter.adapt(newPerson, hhSize, random);

                merged.addPerson(newPerson);
                totalSampled++;
            }
            municipalitiesWithDeficit++;
        }

        // Log municipalities in base but not in donor
        Set<String> baseMunicipalities = new HashSet<>(baseMunicipalityMapping.values());
        Set<String> donorMunicipalities = targetCounts.keySet();
        Set<String> baseOnly = new HashSet<>(baseMunicipalities);
        baseOnly.removeAll(donorMunicipalities);
        if (!baseOnly.isEmpty()) {
            log.warn("{} municipalities in base population not found in donor: {}",
                    baseOnly.size(), baseOnly);
        }

        log.info("Merge complete: {} base + {} sampled = {} total agents across {} municipalities ({} skipped with no deficit)",
                basePopulation.getPersons().size(), totalSampled, merged.getPersons().size(),
                municipalitiesWithDeficit, municipalitiesSkipped);

        return merged;
    }

    private Id<Person> createUniqueId(Id<Person> donorId, Population existing) {
        String baseId = "donor_" + donorId.toString();
        if (!existing.getPersons().containsKey(Id.createPersonId(baseId))) {
            return Id.createPersonId(baseId);
        }
        // Fallback: append counter
        int counter = 1;
        while (existing.getPersons().containsKey(Id.createPersonId(baseId + "_" + counter))) {
            counter++;
        }
        return Id.createPersonId(baseId + "_" + counter);
    }
}
