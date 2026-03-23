package org.matsim.contrib.demand_extraction.upsampling;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.core.scenario.MutableScenario;
import org.matsim.core.scenario.ScenarioUtils;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class RunPopulationUpsampling {

    private static final Logger log = LogManager.getLogger(RunPopulationUpsampling.class);

    public static void main(String[] args) {
        String basePath = null;
        String donorPath = null;
        String donorHouseholdsCsv = null;
        String shpPath = null;
        String outputPath = null;
        long seed = 4711L;
        double targetSamplingRate = 1.0;
        double baseRetentionRate = 1.0;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--base-population" -> basePath = args[++i];
                case "--donor-population" -> donorPath = args[++i];
                case "--donor-households" -> donorHouseholdsCsv = args[++i];
                case "--municipalities-shp" -> shpPath = args[++i];
                case "--output-population" -> outputPath = args[++i];
                case "--random-seed" -> seed = Long.parseLong(args[++i]);
                case "--target-sampling-rate" -> targetSamplingRate = Double.parseDouble(args[++i]);
                case "--base-retention-rate" -> baseRetentionRate = Double.parseDouble(args[++i]);
                default -> log.warn("Unknown argument: {}", args[i]);
            }
        }

        if (basePath == null || donorPath == null || donorHouseholdsCsv == null
                || shpPath == null || outputPath == null) {
            System.err.println("Usage: RunPopulationUpsampling " +
                    "--base-population <path> --donor-population <path> " +
                    "--donor-households <path> --municipalities-shp <path> " +
                    "--output-population <path> [--target-sampling-rate <0.0-1.0>] " +
                    "[--base-retention-rate <0.0-1.0>] [--random-seed <seed>]");
            System.exit(1);
        }

        run(basePath, donorPath, donorHouseholdsCsv, shpPath, outputPath, seed,
                targetSamplingRate, baseRetentionRate);
    }

    /** Convenience overload — defaults to 100% target (targetSamplingRate=1.0). */
    public static void run(String basePath, String donorPath, String donorHouseholdsCsv,
                           String shpPath, String outputPath, long seed) {
        run(basePath, donorPath, donorHouseholdsCsv, shpPath, outputPath, seed, 1.0);
    }

    /** Convenience overload — defaults to baseRetentionRate=1.0 (keep all base agents). */
    public static void run(String basePath, String donorPath, String donorHouseholdsCsv,
                           String shpPath, String outputPath, long seed, double targetSamplingRate) {
        run(basePath, donorPath, donorHouseholdsCsv, shpPath, outputPath, seed, targetSamplingRate, 1.0);
    }

    /**
     * @param targetSamplingRate fraction of the donor (census 100%) population to target per municipality.
     *                           1.0 = upsample to 100%, 0.25 = fill up to 25% of census count.
     * @param baseRetentionRate  fraction of base agents to retain per municipality (1.0 = keep all, 0.0 = use donor only).
     *                           Retained base agents count toward the target; the deficit is filled from the donor.
     */
    public static void run(String basePath, String donorPath, String donorHouseholdsCsv,
                           String shpPath, String outputPath, long seed,
                           double targetSamplingRate, double baseRetentionRate) {
        log.info("=== Population Upsampling ===");
        log.info("Base population: {}", basePath);
        log.info("Donor population: {}", donorPath);
        log.info("Donor households CSV: {}", donorHouseholdsCsv);
        log.info("Municipality shapefile: {}", shpPath);
        log.info("Output: {}", outputPath);
        log.info("Random seed: {}", seed);
        log.info("Target sampling rate: {}", targetSamplingRate);
        log.info("Base retention rate: {}", baseRetentionRate);

        // Load populations
        log.info("Loading base population...");
        Population basePop = loadPopulation(basePath);
        log.info("Loaded {} base agents", basePop.getPersons().size());

        log.info("Loading donor population...");
        Population donorPop = loadPopulation(donorPath);
        log.info("Loaded {} donor agents", donorPop.getPersons().size());

        // Load household sizes from CSV (householdSize is NOT in the population XML)
        log.info("Loading donor household sizes from CSV...");
        Map<Integer, Integer> householdSizes = loadHouseholdSizes(donorHouseholdsCsv);
        log.info("Loaded {} household size entries", householdSizes.size());

        // Map to municipalities
        log.info("Mapping populations to municipalities...");
        MunicipalityMapper mapper = new MunicipalityMapper(shpPath, "vg250_gem", "ARS");
        Map<Id<Person>, String> baseMapping = mapper.mapPopulation(basePop);
        Map<Id<Person>, String> donorMapping = mapper.mapPopulation(donorPop);

        // Merge with target sampling rate and base retention
        log.info("Performing stratified sampling + attribute adaptation...");
        StratifiedPopulationSampler sampler = new StratifiedPopulationSampler(seed);
        Population merged = sampler.merge(basePop, baseMapping, donorPop, donorMapping,
                householdSizes, targetSamplingRate, baseRetentionRate);

        // Write output
        log.info("Writing merged population to {}...", outputPath);
        new PopulationWriter(merged).write(outputPath);
        log.info("=== Done: {} total agents ===", merged.getPersons().size());
    }

    /**
     * Load household_id -> household_size mapping from eqasim households CSV.
     * CSV format: semicolon-delimited, columns include household_id, household_size.
     * household_size values: "1", "2", "3", "4", "5+" (string, parsed to int).
     */
    static Map<Integer, Integer> loadHouseholdSizes(String csvPath) {
        Map<Integer, Integer> sizes = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(csvPath))) {
            String header = reader.readLine();
            String[] cols = header.split(";");
            int idIdx = -1, sizeIdx = -1;
            for (int i = 0; i < cols.length; i++) {
                if ("household_id".equals(cols[i].trim())) idIdx = i;
                if ("household_size".equals(cols[i].trim())) sizeIdx = i;
            }
            if (idIdx < 0 || sizeIdx < 0) {
                throw new IllegalArgumentException(
                        "CSV must have household_id and household_size columns, found: " + header);
            }

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] parts = line.split(";");
                int hhId = Integer.parseInt(parts[idIdx].trim());
                String sizeStr = parts[sizeIdx].trim();
                int size = sizeStr.endsWith("+")
                        ? Integer.parseInt(sizeStr.replace("+", ""))
                        : Integer.parseInt(sizeStr);
                sizes.put(hhId, size);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read households CSV: " + csvPath, e);
        }
        return sizes;
    }

    private static Population loadPopulation(String path) {
        MutableScenario scenario = (MutableScenario) ScenarioUtils.createScenario(ConfigUtils.createConfig());
        new PopulationReader(scenario).readFile(path);
        return scenario.getPopulation();
    }
}
