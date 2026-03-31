package org.matsim.contrib.demand_extraction.run;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Hardcoded run script: Bavaria demand extraction with 30km Kelheim trip-level filter.
 *
 * <p>Uses the adapted population (with numeric income attributes) and filters at the
 * trip level — only trips where BOTH origin and destination activities are within
 * 30km of Kelheim city center are extracted. This is more flexible than agent-level
 * filtering and will work correctly when opening up to non-commute trip purposes.</p>
 *
 * <h3>Settings (all explicit — no CLI args needed)</h3>
 * <ul>
 *   <li>Population: adapted eqasim (with income via {@code RunAdaptEqasimPopulation})</li>
 *   <li>Sample: 25%</li>
 *   <li>Trip filter: 30km radius around Kelheim (O+D both inside)</li>
 *   <li>Scoring: Kelheim v3.0 calibrated + income-dependent margUtilOfMoney</li>
 *   <li>Iterations: 0 (free-flow)</li>
 *   <li>Output: {@code demand-extraction-25pct-kelheim30km/}</li>
 * </ul>
 *
 * <pre>
 * cd matsim-libs/contribs/drt-demand-extraction
 * mvn exec:java -o -Dexec.mainClass="org.matsim.contrib.demand_extraction.run.RunBavariaKelheim30kmComparison" -Denforcer.skip=true
 * </pre>
 */
public class RunBavariaKelheim30kmComparison {

	private static final Logger log = LogManager.getLogger(RunBavariaKelheim30kmComparison.class);

	// ---- All settings explicit ----

	/** Path to Bavaria 30km scenario (network, transit, facilities) */
	private static final String SCENARIO_PATH =
			"../../../matsim_scenarios/bavaria/output/kelheim_30km_100pct";

	/** Pre-filtered 25% population (output of RunCreatePermanentPopulations) */
	private static final String POPULATION_PATH =
			"../../../matsim_scenarios/bavaria/output/populations/population_25pct_kelheim30km.xml.gz";

	/** VG250 administrative boundaries for municipality lookup */
	private static final String VG250_SHAPES_PATH =
			"../../../matsim_scenarios/bavaria/data/germany/tmp_vg250/vg250-ew_12-31.utm32s.gpkg.ebenen/vg250-ew_ebenen_1231/DE_VG250.gpkg";

	/** Municipality name to center the trip filter on */
	private static final String FILTER_MUNICIPALITY = "Kelheim";

	/** Trip-level filter radius in km — only trips with O+D both inside are extracted */
	private static final double TRIP_FILTER_RADIUS_KM = 30.0;

	/** Pre-computed travel times from base simulation (replaces free-flow) */
	private static final String TRAVEL_TIMES_FILE =
			"../../../matsim_scenarios/bavaria/output/base-simulation-10pct/travel_times.tsv";

	/** Population sample — 100 because population file is already pre-filtered to 25% */
	private static final int SAMPLE_PERCENT = 100;

	/** MATSim iterations (0 = uses pre-computed travel times, no simulation needed) */
	private static final int ITERATIONS = 0;

	/** Output directory for this comparison run */
	private static final String OUTPUT_DIR =
			"../../../matsim_scenarios/bavaria/output/demand-extraction-25pct-kelheim30km";

	public static void main(String[] args) throws IOException {
		log.info("=== Bavaria–Kelheim 30km Comparison Run ===");
		log.info("Scenario:      {}", SCENARIO_PATH);
		log.info("Population:    {}", POPULATION_PATH);
		log.info("VG250 shapes:  {}", VG250_SHAPES_PATH);
		log.info("Trip filter:   {}km around {} (O+D both inside)", TRIP_FILTER_RADIUS_KM, FILTER_MUNICIPALITY);
		log.info("Travel times:  {}", TRAVEL_TIMES_FILE);
		log.info("Sample:        {}%", SAMPLE_PERCENT);
		log.info("Iterations:    {}", ITERATIONS);
		log.info("Output:        {}", OUTPUT_DIR);

		// Verify files exist
		for (var entry : new String[][]{
				{"Scenario", SCENARIO_PATH},
				{"Population", POPULATION_PATH},
				{"VG250 shapes", VG250_SHAPES_PATH},
				{"Travel times", TRAVEL_TIMES_FILE}
		}) {
			if (!Path.of(entry[1]).toFile().exists()) {
				log.error("{} not found: {}", entry[0], entry[1]);
				System.exit(1);
			}
		}

		// Delegate to the main runner with explicit args
		// Note: --trip-filter-radius (trip-level O+D filter) NOT --filter-radius (agent-level)
		RunBavaria30kmDemandExtraction.main(new String[]{
				"--scenario-path", SCENARIO_PATH,
				"--population", POPULATION_PATH,
				"--sample", String.valueOf(SAMPLE_PERCENT),
				"--iterations", String.valueOf(ITERATIONS),
				"--trip-filter-radius", String.valueOf(TRIP_FILTER_RADIUS_KM),
				"--filter-municipality", FILTER_MUNICIPALITY,
				"--shapes", VG250_SHAPES_PATH,
				"--travel-times", TRAVEL_TIMES_FILE,
				"--output-dir", OUTPUT_DIR
		});
	}
}
