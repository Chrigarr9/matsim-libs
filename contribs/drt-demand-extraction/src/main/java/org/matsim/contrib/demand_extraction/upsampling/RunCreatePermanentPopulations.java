package org.matsim.contrib.demand_extraction.upsampling;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.core.scenario.MutableScenario;
import org.matsim.core.scenario.ScenarioUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Create permanent pre-filtered populations at multiple sample rates.
 *
 * <p>Agent-level filter: keep agents with any activity inside the specified radius.
 * Deterministic hash-based downsampling (same hash as MATSim's standard approach).</p>
 *
 * <pre>
 * mvn exec:java -o -Dexec.mainClass="org.matsim.contrib.demand_extraction.upsampling.RunCreatePermanentPopulations" \
 *   -Dexec.args="--population path/to/adapted.xml.gz \
 *                --output-dir path/to/output \
 *                --center-x 709432.34 --center-y 5421450.16 --radius 30000 \
 *                --samples 1,10,25,100" \
 *   -Denforcer.skip=true
 * </pre>
 */
public class RunCreatePermanentPopulations {

	private static final Logger log = LogManager.getLogger(RunCreatePermanentPopulations.class);

	public static void main(String[] args) throws IOException {
		String populationPath = null;
		String outputDir = null;
		double centerX = 709432.34; // Kelheim (Stadt) centroid EPSG:25832
		double centerY = 5421450.16;
		double radius = 30000; // 30km
		String samplesStr = "1,10,25,100";

		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--population" -> populationPath = args[++i];
				case "--output-dir" -> outputDir = args[++i];
				case "--center-x" -> centerX = Double.parseDouble(args[++i]);
				case "--center-y" -> centerY = Double.parseDouble(args[++i]);
				case "--radius" -> radius = Double.parseDouble(args[++i]);
				case "--samples" -> samplesStr = args[++i];
				default -> log.warn("Unknown argument: {}", args[i]);
			}
		}

		if (populationPath == null || outputDir == null) {
			System.err.println("Usage: RunCreatePermanentPopulations "
					+ "--population <path> --output-dir <path> "
					+ "[--center-x <x>] [--center-y <y>] [--radius <m>] "
					+ "[--samples 1,10,25,100]");
			System.exit(1);
		}

		int[] samples = java.util.Arrays.stream(samplesStr.split(","))
				.mapToInt(Integer::parseInt).toArray();

		log.info("=== Create Permanent Populations ===");
		log.info("Population: {}", populationPath);
		log.info("Output dir: {}", outputDir);
		log.info("Filter: {}m radius around ({}, {})", radius, centerX, centerY);
		log.info("Samples: {}", samplesStr);

		// Load population
		MutableScenario scenario = (MutableScenario) ScenarioUtils.createScenario(ConfigUtils.createConfig());
		new PopulationReader(scenario).readFile(populationPath);
		Population population = scenario.getPopulation();
		int totalAgents = population.getPersons().size();
		log.info("Loaded {} agents", totalAgents);

		// Filter: keep agents with any activity inside radius
		double radiusSq = radius * radius;
		final double cx = centerX, cy = centerY;

		List<Id<Person>> toRemove = new ArrayList<>();
		for (Person person : population.getPersons().values()) {
			if (person.getSelectedPlan() == null) {
				toRemove.add(person.getId());
				continue;
			}
			boolean hasActivityInside = person.getSelectedPlan().getPlanElements().stream()
					.filter(Activity.class::isInstance)
					.map(Activity.class::cast)
					.filter(act -> act.getCoord() != null)
					.anyMatch(act -> {
						double dx = act.getCoord().getX() - cx;
						double dy = act.getCoord().getY() - cy;
						return (dx * dx + dy * dy) <= radiusSq;
					});
			if (!hasActivityInside) {
				toRemove.add(person.getId());
			}
		}

		for (Id<Person> id : toRemove) {
			population.removePerson(id);
		}

		int insideAgents = population.getPersons().size();
		log.info("Radius filter: {} -> {} agents ({} removed, {:.1f}% kept)",
				totalAgents, insideAgents, toRemove.size(),
				100.0 * insideAgents / totalAgents);

		// Write population files for each sample rate
		Path outDir = Path.of(outputDir);
		Files.createDirectories(outDir);

		for (int pct : samples) {
			// Create a copy with downsampled agents
			MutableScenario sampleScenario = (MutableScenario) ScenarioUtils.createScenario(ConfigUtils.createConfig());
			Population samplePop = sampleScenario.getPopulation();

			int kept = 0;
			for (Person person : population.getPersons().values()) {
				if (pct >= 100 || (Math.abs(person.getId().toString().hashCode()) % 100) < pct) {
					samplePop.addPerson(person);
					kept++;
				}
			}

			Path outFile = outDir.resolve(String.format("population_%dpct_kelheim30km.xml.gz", pct));
			log.info("Writing {}% population: {} agents -> {}", pct, kept, outFile);
			new PopulationWriter(samplePop).write(outFile.toString());
		}

		log.info("=== All populations written ===");
	}
}
