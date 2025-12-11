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
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup.CommuteFilter;
import org.matsim.contrib.demand_extraction.demand.DemandExtractionConfigValidator;
import org.matsim.contrib.demand_extraction.demand.DemandExtractionModule;
import org.matsim.contrib.drt.run.DrtControlerCreator;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.dsim.Activities;

/**
 * Run class for extracting DRT demand from the Kelheim scenario.
 * 
 * <p>This class loads the Kelheim KEXI configuration, configures ExMAS demand extraction,
 * and runs zero iterations to warm up travel times before extracting DRT requests and rides.
 * 
 * <p><b>Key features:</b>
 * <ul>
 *   <li>Supports different sample sizes (1%, 10%, 25%) via command line argument</li>
 *   <li>Filters for commute trips only (home ↔ work)</li>
 *   <li>Excludes freight agents from demand extraction</li>
 *   <li>Uses KEXI DRT mode parameters from Kelheim config</li>
 *   <li>Runs zero iterations (travel times from iteration 0)</li>
 * </ul>
 * 
 * <p><b>Usage:</b>
 * <pre>
 * java RunKelheimDemandExtraction --sample 25
 * java RunKelheimDemandExtraction --sample 10
 * java RunKelheimDemandExtraction --sample 1
 * </pre>
 * 
 * <p><b>Output:</b>
 * <ul>
 *   <li>{runId}.drt_requests.csv - DRT request data with budget, times, coordinates</li>
 *   <li>{runId}.exmas_rides.csv - All feasible ride combinations</li>
 * </ul>
 * 
 * <p><b>Scoring Parameters Note:</b>
 * <p>The Kelheim scenario uses the following DRT scoring parameters:
 * <ul>
 *   <li>constant = 2.45 (mode preference)</li>
 *   <li>marginalUtilityOfDistance_util_m = -2.5E-4 (disutility per meter)</li>
 *   <li>marginalUtilityOfTraveling_util_hr = 0.0 (no time disutility)</li>
 * </ul>
 * 
 * <p>This is unusual - DRT has distance disutility but no time disutility.
 * The demand extraction correctly handles this via ModeRoutingCache which calculates
 * scores using all scoring parameters (constant, distance, time, monetary).
 * 
 * <p><b>Daily Constants Handling:</b>
 * <p>Car has dailyMonetaryConstant = -5.3€ (daily ownership cost).
 * The ModeRoutingCache correctly subtracts daily constants from trip scores
 * since we compare trips, not full-day plans. This prevents unfair advantage
 * to modes with large daily constants.
 */
public class RunKelheimDemandExtraction {
	private static final Logger log = LogManager.getLogger(RunKelheimDemandExtraction.class);
	
	// Base URL for Kelheim scenario plans files (plans are in v3.0 for all sample sizes)
	private static final String KELHEIM_PLANS_BASE = 
			"https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/kelheim/kelheim-v3.0/input/";
	
	// Local config file path - test.with-drt.config.xml is compatible with MATSim 2026.0-SNAPSHOT
	// (v3.1 configs use deprecated module names like 'controler' instead of 'controller')
	private static final String LOCAL_CONFIG_PATH = 
			"../../../matsim_scenarios/matsim-kelheim/input/test.with-drt.config.xml";
	
	public static void main(String[] args) throws IOException {
		// Parse sample size argument
		int sampleSize = 25; // Default: 25%
		for (int i = 0; i < args.length; i++) {
			if ("--sample".equals(args[i]) && i + 1 < args.length) {
				sampleSize = Integer.parseInt(args[i + 1]);
			}
		}
		
		if (sampleSize != 1 && sampleSize != 10 && sampleSize != 25) {
			log.error("Invalid sample size: {}. Must be 1, 10, or 25.", sampleSize);
			System.exit(1);
		}
		
		log.info("=== Kelheim DRT Demand Extraction ===");
		log.info("Sample size: {}%", sampleSize);
		
		// Create output directory
		Path outputDir = Path.of("output/kelheim-demand-extraction-" + sampleSize + "pct");
		Files.createDirectories(outputDir);
		
		// Load config
		Config config = loadKelheimConfig(sampleSize);
		
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

		// Guard against Guice JIT-disabled errors by providing explicit bindings for core types
		controler.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				bind(Scenario.class).toInstance(scenario);
				bind(Network.class).toInstance(scenario.getNetwork());
			}
		});
		
		controler.run();
		
		// Print output summary
		String runId = config.controller().getRunId();
		log.info("\n=== Demand Extraction Complete ===");
		log.info("Output directory: {}", outputDir.toAbsolutePath());
		log.info("Requests file: {}.drt_requests.csv", runId);
		log.info("Rides file: {}.exmas_rides.csv", runId);
		log.info("===================================\n");
	}
	
	/**
	 * Load Kelheim config and adjust for the specified sample size.
	 */
	private static Config loadKelheimConfig(int sampleSize) {
		log.info("Loading Kelheim KEXI config...");
		
		// Load v3.1 25% config as base (only 25% exists, we adjust for other sample sizes)
		Config config = ConfigUtils.loadConfig(LOCAL_CONFIG_PATH, new ExMasConfigGroup());
		log.info("Using config: {}", LOCAL_CONFIG_PATH);
		
		// Adjust capacity factors and plans file for the requested sample size
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
	 */
	private static void configureExMas(Config config) {
		ExMasConfigGroup exMasConfig = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		
		// DRT mode must match Kelheim config
		exMasConfig.setDrtMode("drt");
		
		// Base modes for budget calculation
		Set<String> baseModes = new HashSet<>();
		baseModes.add(TransportMode.car);
		baseModes.add(TransportMode.pt);
		baseModes.add(TransportMode.walk);
		baseModes.add(TransportMode.bike);
		baseModes.add(TransportMode.ride);
		exMasConfig.setBaseModes(baseModes);
		
		// DRT routing uses car network
		exMasConfig.setDrtRoutingMode(TransportMode.car);
		
		// Private vehicle modes (create subtour dependencies)
		Set<String> privateVehicles = new HashSet<>();
		privateVehicles.add(TransportMode.car);
		privateVehicles.add(TransportMode.bike);
		exMasConfig.setPrivateVehicleModes(privateVehicles);
		
		// === COMMUTE FILTERING ===
		// Only extract commute trips (home ↔ work)
		exMasConfig.setCommuteFilter(CommuteFilter.COMMUTES_ONLY);
		exMasConfig.setHomeActivityType("home");
		exMasConfig.setWorkActivityType("work");
		
		// DRT service quality parameters (baseline for budget calculation)
		exMasConfig.setMinDrtCostPerKm(0.0);
		exMasConfig.setMinMaxDetourFactor(1.0);
		exMasConfig.setMinMaxWaitingTime(0.0);
		exMasConfig.setMinDrtAccessEgressDistance(100.0);
		
		// ExMAS algorithm parameters
		exMasConfig.setSearchHorizon(0.0);  // 10 minute time window for pairing
		exMasConfig.setMaxDetourFactor(1.5);  // Max 50% longer than direct
		exMasConfig.setOriginFlexibilityAbsolute(600.0);  // 10 min departure flexibility
		exMasConfig.setDestinationFlexibilityAbsolute(600.0);  // 10 min arrival flexibility
		exMasConfig.setMaxPoolingDegree(8);  // Max 8 passengers per ride (reasonable for KEXI)
		
		// Disable PT departure optimization to avoid SwissRailRaptor configuration issues
		exMasConfig.setPtOptimizeDepartureTime(false);
		
		log.info("ExMAS config:");
		log.info("  DRT mode: {}", exMasConfig.getDrtMode());
		log.info("  Commute filter: {}", exMasConfig.getCommuteFilter());
		log.info("  Base modes: {}", exMasConfig.getBaseModes());
		log.info("  Max pooling degree: {}", exMasConfig.getMaxPoolingDegree());
		log.info("  Max detour factor: {}", exMasConfig.getMaxDetourFactor());
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
