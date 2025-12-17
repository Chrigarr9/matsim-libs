package org.matsim.contrib.demand_extraction.run;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup.CommuteFilter;
import org.matsim.contrib.demand_extraction.demand.DemandExtractionConfigValidator;
import org.matsim.contrib.demand_extraction.demand.DemandExtractionModule;
import org.matsim.contrib.drt.run.DrtControlerCreator;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.dsim.Activities;

/**
 * Run class for extracting DRT demand from the Kelheim scenario.
 * 
 * <p>
 * This class loads the Kelheim KEXI configuration from the local matsim-kelheim
 * repository,
 * configures ExMAS demand extraction, and runs zero iterations to use iteration
 * 0 travel times
 * before extracting DRT requests and rides.
 * 
 * <p>
 * <b>Prerequisites:</b>
 * <ul>
 * <li>Clone matsim-kelheim repo to:
 * ../../../matsim_scenarios/matsim-kelheim</li>
 * <li>The config uses online SVN resources for network, plans, transit,
 * etc.</li>
 * </ul>
 * 
 * <p>
 * <b>Key features:</b>
 * <ul>
 * <li>Uses FULL Kelheim network (not the test mini-network from matsim-libs
 * examples)</li>
 * <li>Supports different sample sizes (1%, 10%, 25%) via command line
 * argument</li>
 * <li>Filters for commute trips only (home ↔ work)</li>
 * <li>Excludes freight agents from demand extraction</li>
 * <li>Uses KEXI DRT mode parameters from Kelheim config</li>
 * <li>Runs zero iterations (travel times from iteration 0)</li>
 * </ul>
 * 
 * <p>
 * <b>Usage:</b>
 * 
 * <pre>
 * # From drt-demand-extraction directory:
 * mvn exec:java -Dexec.mainClass="org.matsim.contrib.demand_extraction.run.RunKelheimDemandExtraction" -Dexec.args="--scenario-path ../matsim_scenarios/matsim-kelheim --sample 1"
 * 
 * # Or with different sample sizes:
 * mvn exec:java -Dexec.mainClass="org.matsim.contrib.demand_extraction.run.RunKelheimDemandExtraction" -Dexec.args="--scenario-path ../matsim_scenarios/matsim-kelheim --sample 10"
 * mvn exec:java -Dexec.mainClass="org.matsim.contrib.demand_extraction.run.RunKelheimDemandExtraction" -Dexec.args="--scenario-path ../matsim_scenarios/matsim-kelheim --sample 25"
 * </pre>
 * 
 * <p>
 * <b>Output:</b>
 * <ul>
 * <li>{runId}.drt_requests.csv - DRT request data with budget, times,
 * coordinates</li>
 * <li>{runId}.exmas_rides.csv - All feasible ride combinations</li>
 * <li>{runId}.person_attributes.csv - Person attributes for cluster
 * analysis</li>
 * <li>{runId}.connection_cache.csv - Network connections for optimization</li>
 * </ul>
 * 
 * <p>
 * <b>Scoring Parameters Note:</b>
 * <p>
 * The Kelheim scenario uses the following DRT scoring parameters:
 * <ul>
 * <li>constant = 2.45 (mode preference)</li>
 * <li>marginalUtilityOfDistance_util_m = -2.5E-4 (disutility per meter)</li>
 * <li>marginalUtilityOfTraveling_util_hr = 0.0 (no time disutility)</li>
 * </ul>
 * 
 * <p>
 * This is unusual - DRT has distance disutility but no time disutility.
 * The demand extraction correctly handles this via ModeRoutingCache which
 * calculates
 * scores using all scoring parameters (constant, distance, time, monetary).
 * 
 * <p>
 * <b>Daily Constants Handling:</b>
 * <p>
 * Car has dailyMonetaryConstant = -5.3€ (daily ownership cost).
 * The ModeRoutingCache correctly subtracts daily constants from trip scores
 * since we compare trips, not full-day plans. This prevents unfair advantage
 * to modes with large daily constants.
 */
public class RunKelheimDemandExtraction {
	private static final Logger log = LogManager.getLogger(RunKelheimDemandExtraction.class);
	
	// Base URL for Kelheim scenario plans files (plans are in v3.0 for all sample sizes)
	private static final String KELHEIM_PLANS_BASE = 
			"https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/kelheim/kelheim-v3.0/input/";

	public static void main(String[] args) throws IOException {
		// Parse arguments
		String scenarioPath = null;
		int sampleSize = 1;

		for (int i = 0; i < args.length; i++) {
			if ("--scenario-path".equals(args[i]) && i + 1 < args.length) {
				scenarioPath = args[i + 1];
			} else if ("--sample".equals(args[i]) && i + 1 < args.length) {
				sampleSize = Integer.parseInt(args[i + 1]);
			}
		}
		
		if (scenarioPath == null) {
			log.error("Missing required argument: --scenario-path <path>");
			log.error("Usage: java ... --scenario-path <scenario-base-path> [--sample <1|10|25>]");
			System.exit(1);
		}

		if (sampleSize != 1 && sampleSize != 10 && sampleSize != 25) {
			log.error("Invalid sample size: {}. Must be 1, 10, or 25.", sampleSize);
			System.exit(1);
		}
		
		log.info("=== Kelheim DRT Demand Extraction ===");
		log.info("Scenario path: {}", scenarioPath);
		log.info("Sample size: {}%", sampleSize);
		log.info("Using FULL Kelheim network from matsim-kelheim repository");
		
		// Create output directory
		Path outputDir = Path.of(scenarioPath).resolve("output/kelheim-demand-extraction-" + sampleSize + "pct");
		Files.createDirectories(outputDir);
		
		// Load config
		Config config = loadKelheimConfig(scenarioPath, sampleSize);
		
		// Configure for demand extraction
		configureForDemandExtraction(config, outputDir, sampleSize);
		
		// Validate and prepare config
		DemandExtractionConfigValidator.prepareConfigForDemandExtraction(config);
		
		// Create scenario with DRT route factory
		Scenario scenario = DrtControlerCreator.createScenarioWithDrtRouteFactory(config);
		ScenarioUtils.loadScenario(scenario);
		
		// Filter out freight agents BEFORE demand extraction
		int originalSize = scenario.getPopulation().getPersons().size();
		filterFreightAgents(scenario);
		int filteredSize = scenario.getPopulation().getPersons().size();
		log.info("Filtered population: {} → {} agents ({} freight agents removed)",
				originalSize, filteredSize, originalSize - filteredSize);
		
		// Create and run controller
		Controler controler = DrtControlerCreator.createControler(config, scenario, false);
		controler.addOverridingModule(new DemandExtractionModule());
		
		controler.run();
		
		// Clean up output directory - keep only config and demand extraction files
		cleanupOutputDirectory(outputDir, config.controller().getRunId());

		// Print output summary
		String runId = config.controller().getRunId();
		log.info("\n=== Demand Extraction Complete ===");
		log.info("Output directory: {}", outputDir.toAbsolutePath());
	log.info("Demand extraction files in: {}/drt_demand/", outputDir.toAbsolutePath());
	log.info("  - {}.drt_requests.csv", runId);
	log.info("  - {}.exmas_rides.csv", runId);
	log.info("  - {}.person_attributes.csv", runId);
	log.info("  - {}.mode_cache.csv", runId);
	if (config.getModules().get(ExMasConfigGroup.GROUP_NAME) != null) {
		ExMasConfigGroup exMasConfig = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		if (exMasConfig.isCalcPredecessors()) {
			log.info("  - {}.connection_cache.csv", runId);
		}
	}
	log.info("===================================\n");
}

/**
 * Clean up output directory after simulation.
 * Keeps only:
 * - output_config.xml (MATSim's final config)
 * - drt_demand/ subdirectory (our demand extraction files)
 * - log files (*.log, logfile.log, logfileWarningsErrors.log)
 */
private static void cleanupOutputDirectory(Path outputDir, String runId) {
	log.info("Cleaning up output directory...");

	try {
		// List all files and directories in output directory
		java.util.List<Path> allPaths = Files.list(outputDir).collect(java.util.stream.Collectors.toList());

		// Files/directories to keep
		java.util.Set<String> keepNames = new java.util.HashSet<>();
		keepNames.add("output_config.xml");
		keepNames.add(runId + ".output_config.xml");
		keepNames.add("drt_demand");
		keepNames.add("logfile.log");
		keepNames.add("logfileWarningsErrors.log");

		// Delete everything else (except log files)
		int deletedCount = 0;
		for (Path path : allPaths) {
			String name = path.getFileName().toString();
			// Keep if in keepNames set OR if it's a log file
			boolean isLogFile = name.endsWith(".log") || name.endsWith(".log.gz");
			if (!keepNames.contains(name) && !isLogFile) {
				deleteRecursively(path);
				deletedCount++;
			}
		}

		log.info("Cleaned up {} files/directories from output", deletedCount);
	} catch (IOException e) {
		log.warn("Failed to clean up output directory: {}", e.getMessage());
	}
}

/**
 * Recursively delete a file or directory.
 */
private static void deleteRecursively(Path path) throws IOException {
	if (Files.isDirectory(path)) {
		// Delete directory contents first
		Files.list(path).forEach(child -> {
			try {
				deleteRecursively(child);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		});
	}
	Files.delete(path);
}

private static Config loadKelheimConfig(String scenarioPath, int sampleSize) {
		log.info("Loading Kelheim KEXI config from matsim-kelheim repository...");
		String configPath = Path.of(scenarioPath).resolve("input/test.with-drt.config.xml").toString();
		log.info("Config path: {}", configPath);
		
		// Load test.with-drt.config.xml which contains network and DRT configuration
		Config config = ConfigUtils.loadConfig(configPath,
				new ExMasConfigGroup(), 
				new MultiModeDrtConfigGroup(), 
				new DvrpConfigGroup());
		
		log.info("Network: {}", config.network().getInputFile());
		log.info("Transit schedule: {}", config.transit().getTransitScheduleFile());

		// Remove 'av' mode if present to ensure single-mode DRT for ExMAS
		MultiModeDrtConfigGroup multiModeDrt = ConfigUtils.addOrGetModule(config, MultiModeDrtConfigGroup.class);
		if (multiModeDrt != null) {
			// Find the 'av' mode config group
			org.matsim.contrib.drt.run.DrtConfigGroup avMode = null;
			for (org.matsim.contrib.drt.run.DrtConfigGroup drtConfig : multiModeDrt.getModalElements()) {
				if ("av".equals(drtConfig.getMode())) {
					avMode = drtConfig;
					break;
				}
			}
			
			if (avMode != null) {
				log.info("Removing 'av' DRT mode to ensure single-mode compatibility...");
				multiModeDrt.removeParameterSet(avMode);
			}
		}

		// Update DVRP network modes to only include 'drt'
		DvrpConfigGroup dvrp = ConfigUtils.addOrGetModule(config, DvrpConfigGroup.class);
		if (dvrp != null) {
			dvrp.setNetworkModes(java.util.Collections.singleton("drt"));
		}
		
		// Adjust capacity factors for the requested sample size
		double sampleFactor = sampleSize / 100.0;
		config.qsim().setFlowCapFactor(sampleFactor);
		config.qsim().setStorageCapFactor(sampleFactor);
		
		// Set sample-specific plans file from SVN (v3.0 has all sample sizes)
		String plansFile = KELHEIM_PLANS_BASE + "kelheim-v3.0-" + sampleSize + "pct-plans.xml.gz";
		config.plans().setInputFile(plansFile);
		
		log.info("Plans file: {}", plansFile);
		log.info("Flow/storage capacity factor: {}", sampleFactor);
		
		return config;
	}
	
	/**
	 * Configure MATSim for demand extraction.
	 */
	private static void configureForDemandExtraction(Config config, Path outputDir, int sampleSize) {
		log.info("Configuring for demand extraction...");
		
		// Disable VSP config consistency checker - we're not running a full simulation
		config.vspExperimental().setVspDefaultsCheckingLevel(
				org.matsim.core.config.groups.VspExperimentalConfigGroup.VspDefaultsCheckingLevel.info);
		
		// Output settings
		config.controller().setOutputDirectory(outputDir.toString());
		config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
		config.controller().setRunId("kelheim-" + sampleSize + "pct-exmas");
		
		// Zero iterations - only need iteration 0 for travel times
		// The demand extraction happens after the simulation via shutdown listener
		config.controller().setLastIteration(0);
		config.controller().setWriteEventsInterval(0);
		config.controller().setWritePlansInterval(0);
		
		// Configure ExMAS
		configureExMas(config);
		
		// Add activity scoring parameters (required for Kelheim's duration-specific activities)
		// Kelheim uses SnzActivities naming convention (home_7200, work_28800, etc.)
		// The matsim core Activities class covers both edu_* and educ_* naming conventions
		Activities.addScoringParams(config);
		
		// Log scoring parameters for verification
		logScoringParameters(config);
	}
	
	/**
	 * Configure ExMAS algorithm parameters.
	 * Settings aligned with ExMasKelheimE2ETest for consistency.
	 */
	private static void configureExMas(Config config) {
		ExMasConfigGroup exMasConfig = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		
		// DRT mode must match Kelheim config
		exMasConfig.setDrtMode("drt");
		
		// Base modes for budget calculation (aligned with E2E test)
		Set<String> baseModes = new HashSet<>();
		baseModes.add(TransportMode.car);
		baseModes.add(TransportMode.pt);
		baseModes.add(TransportMode.walk);
		baseModes.add(TransportMode.bike);
		exMasConfig.setBaseModes(baseModes);
		
		// DRT routing uses car network
		exMasConfig.setDrtRoutingMode(TransportMode.car);
		
		// Private vehicle modes (create subtour dependencies)
		Set<String> privateVehicles = new HashSet<>();
		privateVehicles.add(TransportMode.car);
		privateVehicles.add(TransportMode.bike);
		exMasConfig.setPrivateVehicleModes(privateVehicles);
		
		// === COMMUTE FILTERING ===
		// Extract commute trips (home ↔ work) AND education trips
		exMasConfig.setCommuteFilter(CommuteFilter.COMMUTES_AND_EDUCATION);
		exMasConfig.setHomeActivityType("home");
		exMasConfig.setWorkActivityType("work");
		exMasConfig.setEducationActivityType("educ");
		
		// Filter agents by age (e.g. only adults >= 18)
		exMasConfig.setMinAge(18);
		
		// Optional: Filter by person attribute (e.g. "hasLicense")
		// exMasConfig.setDrtAvailabilityAttribute("hasLicense");
		
		// DRT service quality parameters for budget calculation (aligned with E2E test)
		exMasConfig.setMinDrtCostPerKm(0.0);
		exMasConfig.setMinMaxDetourFactor(1.0);
		exMasConfig.setMinMaxWaitingTime(0.0);
		exMasConfig.setMinDrtAccessEgressDistance(100.0);  // Changed from 100.0 to match test
		
		// ExMAS algorithm parameters (aligned with E2E test)
		exMasConfig.setSearchHorizon(0.0); // unlimited
		exMasConfig.setMaxDetourFactor(1.5);  // Max 50% longer than direct
		exMasConfig.setMaxPoolingDegree(10);  // Allow up to 10 passengers (aligned with test)

		// Enable predecessor calculation for connection_cache.csv output
		// This is needed for optimization empty vehicle kilometer calculations
		exMasConfig.setCalcPredecessors(true);
		exMasConfig.setPredecessorsFilterDistanceFactor(1.0);
		// Only consider predecessors that ended within 2 hours before successor starts
		exMasConfig.setPredecessorsFilterTime(1800.0);
		exMasConfig.setCalcShapleyValues(true);

		// Pruning settings
		exMasConfig.setPruningEnabled(true);
		exMasConfig.setPruningFraction(0.5);
		exMasConfig.setPruningMinToKeep(3);
		exMasConfig.setPruningRemoveNonImproving(true);
		exMasConfig.setPruningObjective("rideDistance");
		exMasConfig.setPruningGoal("minimize");
		exMasConfig.setPruningTopNPerBase(0);
		
		// Limit successors to improve performance (Top-K pruning)
		exMasConfig.setMaxSuccessors(50);

		// Scoring
		exMasConfig.setIncludeOpportunityCost(true);

		// Disable PT departure optimization to avoid SwissRailRaptor configuration issues
		// TODO: Fix SwissRailRaptor range query settings configuration
		exMasConfig.setPtOptimizeDepartureTime(false);
		
		log.info("ExMAS config:");
		log.info("  DRT mode: {}", exMasConfig.getDrtMode());
		log.info("  Commute filter: {}", exMasConfig.getCommuteFilter());
		log.info("  Base modes: {}", exMasConfig.getBaseModes());
		log.info("  Max pooling degree: {}", exMasConfig.getMaxPoolingDegree());
		log.info("  Max detour factor: {}", exMasConfig.getMaxDetourFactor());
		log.info("  Calc predecessors: {}", exMasConfig.isCalcPredecessors());
		log.info("  Include opportunity cost: {}", exMasConfig.isIncludeOpportunityCost());
	}
	
	/**
	 * Filter out freight agents from the population.
	 * 
	 * Freight agents are identified by:
	 * - Subpopulation attribute = "freight"
	 * - Activities starting with "freight"
	 * 
	 * These agents don't have normal commute patterns and can cause routing
	 * issues (activities without proper link IDs).
	 */
	private static void filterFreightAgents(Scenario scenario) {
		log.info("Filtering freight agents...");
		
		scenario.getPopulation().getPersons().values().removeIf(person -> {
			// Check subpopulation attribute
			Object subpop = person.getAttributes().getAttribute("subpopulation");
			if ("freight".equals(subpop)) {
				return true;
			}
			
			// Check for freight activities
			if (person.getSelectedPlan() != null) {
				return person.getSelectedPlan().getPlanElements().stream()
						.filter(Activity.class::isInstance)
						.map(Activity.class::cast)
						.anyMatch(act -> act.getType() != null && act.getType().startsWith("freight"));
			}
			
			return false;
		});
	}
	
	/**
	 * Log scoring parameters for verification.
	 * 
	 * This helps verify that:
	 * - DRT uses distance-based disutility (marginalUtilityOfDistance)
	 * - Daily constants are handled correctly
	 * - All modes have proper scoring configuration
	 */
	private static void logScoringParameters(Config config) {
		log.info("\n=== Scoring Parameters Verification ===");
		
		ScoringConfigGroup scoring = config.scoring();
		log.info("Marginal utility of money: {}", scoring.getMarginalUtilityOfMoney());
		log.info("Performing (opportunity cost): {} utils/hr", scoring.getPerforming_utils_hr());
		
		String[] modesToCheck = {"drt", "car", "pt", "bike", "walk", "ride"};
		for (String mode : modesToCheck) {
			ScoringConfigGroup.ModeParams params = scoring.getModes().get(mode);
			if (params != null) {
				log.info("\nMode: {}", mode);
				log.info("  constant: {}", params.getConstant());
				log.info("  marginalUtilityOfTraveling_util_hr: {}", params.getMarginalUtilityOfTraveling());
				log.info("  marginalUtilityOfDistance_util_m: {}", params.getMarginalUtilityOfDistance());
				log.info("  monetaryDistanceRate: {}", params.getMonetaryDistanceRate());
				log.info("  dailyMonetaryConstant: {}", params.getDailyMonetaryConstant());
				log.info("  dailyUtilityConstant: {}", params.getDailyUtilityConstant());
			}
		}
		
		log.info("\n=== Important Notes ===");
		log.info("1. DRT uses marginalUtilityOfDistance = -2.5E-4 (distance-based disutility)");
		log.info("2. DRT has marginalUtilityOfTraveling = 0.0 (no time disutility)");
		log.info("3. Car has dailyMonetaryConstant = -5.3 (subtracted for trip comparison)");
		log.info("4. ModeRoutingCache calculates trip scores using ALL parameters");
		log.info("5. Daily constants are subtracted to compare trip-level utilities");
		log.info("=======================================\n");
	}
}
