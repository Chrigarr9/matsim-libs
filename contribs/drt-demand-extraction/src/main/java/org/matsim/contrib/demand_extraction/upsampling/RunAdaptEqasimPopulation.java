package org.matsim.contrib.demand_extraction.upsampling;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.core.scenario.MutableScenario;
import org.matsim.core.scenario.ScenarioUtils;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Standalone preprocessing step: adapt eqasim Bavaria population attributes to Kelheim format.
 *
 * <p>The raw eqasim population has attributes in a different format than what the Kelheim scoring
 * parameters expect. This tool converts them in-place and writes an adapted population XML:</p>
 * <ul>
 *   <li>{@code carAvailability}: "all"→"always", "none"→"never" + sets {@code sim_carAvailability}</li>
 *   <li>{@code hasPtSubscription}: boolean → {@code sim_ptAbo} "full"/"none"</li>
 *   <li>{@code subpopulation}: null → "person"</li>
 *   <li>{@code householdIncome} (categorical) → {@code MiD:hheink_gr2} (income group) + numeric {@code income}</li>
 *   <li>household size → {@code MiD:hhgr_gr}</li>
 * </ul>
 *
 * <p>Run this ONCE after eqasim import, before any MATSim simulation or demand extraction.</p>
 *
 * <pre>
 * mvn exec:java -Dexec.mainClass="org.matsim.contrib.demand_extraction.upsampling.RunAdaptEqasimPopulation" \
 *   -Dexec.args="--population path/to/population.xml.gz \
 *                --households path/to/households.csv \
 *                --output path/to/population_adapted.xml.gz"
 * </pre>
 */
public class RunAdaptEqasimPopulation {

	private static final Logger log = LogManager.getLogger(RunAdaptEqasimPopulation.class);

	public static void main(String[] args) throws IOException {
		String populationPath = null;
		String householdsCsvPath = null;
		String outputPath = null;
		long seed = 4711L;

		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--population" -> populationPath = args[++i];
				case "--households" -> householdsCsvPath = args[++i];
				case "--output" -> outputPath = args[++i];
				case "--seed" -> seed = Long.parseLong(args[++i]);
				default -> log.warn("Unknown argument: {}", args[i]);
			}
		}

		if (populationPath == null || householdsCsvPath == null || outputPath == null) {
			System.err.println("Usage: RunAdaptEqasimPopulation "
					+ "--population <path> --households <path> --output <path> [--seed <seed>]");
			System.exit(1);
		}

		run(populationPath, householdsCsvPath, outputPath, seed);
	}

	public static void run(String populationPath, String householdsCsvPath,
						   String outputPath, long seed) throws IOException {

		log.info("=== Adapt Eqasim Population to Kelheim Format ===");
		log.info("Population: {}", populationPath);
		log.info("Households: {}", householdsCsvPath);
		log.info("Output:     {}", outputPath);
		log.info("Seed:       {}", seed);

		// Check if already adapted
		MutableScenario scenario = (MutableScenario) ScenarioUtils.createScenario(ConfigUtils.createConfig());
		new PopulationReader(scenario).readFile(populationPath);
		Population population = scenario.getPopulation();

		long withIncome = population.getPersons().values().stream()
				.filter(p -> PersonUtils.getIncome(p) != null)
				.count();
		if (withIncome > 0) {
			log.warn("{} of {} persons already have numeric income — population may already be adapted",
					withIncome, population.getPersons().size());
		}

		// Load household sizes from eqasim households CSV (semicolon-separated)
		Map<String, Integer> householdSizes = loadHouseholdSizes(householdsCsvPath);
		log.info("Loaded {} household sizes", householdSizes.size());

		// Adapt all persons
		Random rnd = new Random(seed);
		int adapted = 0;
		int skipped = 0;
		for (Person person : population.getPersons().values()) {
			Object hhIdObj = person.getAttributes().getAttribute("householdId");
			if (hhIdObj == null) {
				skipped++;
				continue;
			}
			int hhSize = householdSizes.getOrDefault(hhIdObj.toString(), 1);
			AttributeAdapter.adapt(person, hhSize, rnd);
			adapted++;
		}

		log.info("Adapted {} persons, skipped {} (no householdId)", adapted, skipped);

		// Verify income was set
		long withIncomeAfter = population.getPersons().values().stream()
				.filter(p -> PersonUtils.getIncome(p) != null)
				.count();
		log.info("Persons with numeric income after adaptation: {}/{}", withIncomeAfter, population.getPersons().size());

		// Print income distribution sample
		var incomeStats = population.getPersons().values().stream()
				.map(PersonUtils::getIncome)
				.filter(java.util.Objects::nonNull)
				.mapToDouble(Double::doubleValue)
				.summaryStatistics();
		log.info("Income distribution: mean={}, min={}, max={}, count={}",
				String.format("%.1f", incomeStats.getAverage()),
				String.format("%.1f", incomeStats.getMin()),
				String.format("%.1f", incomeStats.getMax()),
				incomeStats.getCount());

		// Write output
		Files.createDirectories(Path.of(outputPath).getParent());
		new PopulationWriter(population).write(outputPath);
		log.info("Wrote adapted population to {}", outputPath);
	}

	private static Map<String, Integer> loadHouseholdSizes(String csvPath) throws IOException {
		Map<String, Integer> sizes = new HashMap<>();
		try (BufferedReader reader = new BufferedReader(new FileReader(csvPath))) {
			String header = reader.readLine();
			if (header == null) throw new IOException("Empty households CSV");

			String[] cols = header.split(";");
			int idIdx = -1, sizeIdx = -1;
			for (int i = 0; i < cols.length; i++) {
				if ("household_id".equals(cols[i])) idIdx = i;
				if ("household_size".equals(cols[i])) sizeIdx = i;
			}
			if (idIdx < 0 || sizeIdx < 0) {
				throw new IOException("Households CSV missing required columns. Found: " + header
						+ " — need household_id and household_size");
			}

			String line;
			while ((line = reader.readLine()) != null) {
				String[] parts = line.split(";");
				if (parts.length > Math.max(idIdx, sizeIdx)) {
					String hhId = parts[idIdx];
					String sizeStr = parts[sizeIdx].replace("+", "");
					try {
						sizes.put(hhId, Integer.parseInt(sizeStr));
					} catch (NumberFormatException e) {
						sizes.put(hhId, 1);
					}
				}
			}
		}
		return sizes;
	}
}
