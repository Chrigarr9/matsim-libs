package org.matsim.contrib.demand_extraction.demand;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.drt.optimizer.insertion.selective.SelectiveInsertionSearchParams;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.config.groups.RoutingConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.config.groups.TravelTimeCalculatorConfigGroup;

import ch.sbb.matsim.config.SwissRailRaptorConfigGroup;

/**
 * Comprehensive configuration validator and setup utility for ExMAS demand
 * extraction.
 * 
 * This class ensures that all required configurations are properly set up
 * before
 * demand extraction runs. It should be called BEFORE scenario/controller
 * creation.
 * 
 * Configuration areas covered:
 * - DRT service configuration (routing and budget calculation)
 * - DVRP infrastructure setup
 * - PT routing (SwissRailRaptor) configuration
 * - QSim settings
 * - Scoring parameters for all modes
 * - Network routing (travel time calculation, randomness)
 * - Walking speed and access/egress settings
 * 
 * Usage:
 * 
 * <pre>
 * Config config = ConfigUtils.loadConfig(configPath, new ExMasConfigGroup());
 * DemandExtractionConfigValidator.prepareConfigForDemandExtraction(config);
 * Scenario scenario = DrtControlerCreator.createScenarioWithDrtRouteFactory(config);
 * </pre>
 */
public class DemandExtractionConfigValidator {
	private static final Logger log = LogManager.getLogger(DemandExtractionConfigValidator.class);

	/**
	 * Main entry point - validates and prepares all configuration for demand
	 * extraction.
	 * Safe to call multiple times (idempotent).
	 * 
	 * @param config MATSim config to validate and prepare
	 * @throws RuntimeException if ExMasConfigGroup is missing or configuration is
	 *                          invalid
	 */
	public static void prepareConfigForDemandExtraction(Config config) {
		log.info("=== DemandExtractionConfigValidator: Preparing configuration ===");

		// 1. Verify ExMasConfigGroup exists
		ExMasConfigGroup exMasConfig = verifyExMasConfigPresent(config);

		// 2. Set up DRT infrastructure (required for routing and budget calculation)
		ensureDrtConfigExists(config, exMasConfig);
		ensureDvrpConfigExists(config);

		// 3. Configure PT routing if optimization is enabled
		ensurePtRouterConfigCorrect(config, exMasConfig);

		// 4. Configure network routing for deterministic results
		ensureNetworkRoutingCorrect(config, exMasConfig);

		// 5. Configure QSim (required by DVRP)
		ensureQSimConfigCorrect(config);

		// 6. Set up scoring parameters
		ensureScoringParamsCorrect(config, exMasConfig);

		// 7. Validate walking speed configuration
		validateWalkingSpeed(config, exMasConfig);

		// 8. Validate ExMAS algorithm parameters
		validateExMasParameters(exMasConfig);

		log.info("=== Configuration preparation complete ===");
		log.info("DRT Configuration:");
		log.info("  Mode: {}", exMasConfig.getDrtMode());
		log.info("  Routing mode: {}", exMasConfig.getDrtRoutingMode());
		log.info("  Allowed network modes: {}",
				exMasConfig.getDrtAllowedModes().isEmpty() ? "all" : exMasConfig.getDrtAllowedModes());
		log.info("Budget Calculation:");
		log.info("  Mode: {}", exMasConfig.getBudgetCalculationMode());
		log.info("  Base modes: {}", exMasConfig.getBaseModes());
		log.info("  Private vehicle modes: {}", exMasConfig.getPrivateVehicleModes());
		log.info("Trip Filtering:");
		log.info("  Commute filter: {}", exMasConfig.getCommuteFilter());
		log.info("  Home activity: {}", exMasConfig.getHomeActivityType());
		log.info("  Work activity: {}", exMasConfig.getWorkActivityType());
		log.info("ExMAS Algorithm:");
		log.info("  Max detour factor: {}", exMasConfig.getMaxDetourFactor());
		log.info("  Max pooling degree: {}",
				exMasConfig.getMaxPoolingDegree() == Integer.MAX_VALUE ? "unlimited"
						: exMasConfig.getMaxPoolingDegree());
		log.info("  Search horizon: {}s", exMasConfig.getSearchHorizon());
		log.info("  Origin flexibility: {}s absolute, {}% relative",
				exMasConfig.getOriginFlexibilityAbsolute(), exMasConfig.getOriginFlexibilityRelative() * 100);
		log.info("  Destination flexibility: {}s absolute, {}% relative",
				exMasConfig.getDestinationFlexibilityAbsolute(), exMasConfig.getDestinationFlexibilityRelative() * 100);
		log.info("Routing Settings:");
		log.info("  PT optimization: {}", exMasConfig.isPtOptimizeDepartureTime() ? "enabled" : "disabled");
		log.info("  Deterministic network routing: {}",
				exMasConfig.isUseDeterministicNetworkRouting() ? "yes (ignores tolls)" : "no (includes tolls)");
		log.info("  Routing randomness: {}", config.routing().getRoutingRandomness());
		log.info("========================================");
	}

	/**
	 * Verifies that ExMasConfigGroup is present in the config.
	 */
	private static ExMasConfigGroup verifyExMasConfigPresent(Config config) {
		if (!config.getModules().containsKey(ExMasConfigGroup.GROUP_NAME)) {
			throw new RuntimeException(
					"ExMasConfigGroup is required but not found in config. " +
							"Please add it when loading the config: " +
							"ConfigUtils.loadConfig(path, new ExMasConfigGroup())");
		}
		return ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
	}

	/**
	 * Configures network routing for accurate and deterministic results.
	 * 
	 * Sets:
	 * - Travel time calculator to analyze modes separately
	 * - Routing randomness to 0.0 for deterministic results
	 * - Network routing mode based on ExMas settings
	 */
	private static void ensureNetworkRoutingCorrect(Config config, ExMasConfigGroup exMasConfig) {
		// Enable mode-specific travel time calculation
		TravelTimeCalculatorConfigGroup ttcConfig = config.travelTimeCalculator();
		if (!ttcConfig.getSeparateModes()) {
			log.info("Setting travelTimeCalculator.separateModes = true (required for accurate mode-specific routing)");
			ttcConfig.setSeparateModes(true);
		}

		// Disable routing randomness for deterministic results
		RoutingConfigGroup routingConfig = config.routing();
		if (routingConfig.getRoutingRandomness() != 0.0) {
			log.info("Setting routing.routingRandomness = 0.0 (required for deterministic results)");
			log.info("  Note: This still includes tolls and distance costs, just without randomization");
			routingConfig.setRoutingRandomness(0.0);
		}

		// Log network routing mode
		String networkRoutingMode = exMasConfig.getDrtRoutingMode();
		log.info("Network routing mode for DRT: {}", networkRoutingMode);
		if (exMasConfig.isUseDeterministicNetworkRouting()) {
			log.info("  Using deterministic routing (ignores tolls and road pricing)");
		} else {
			log.info("  Using mode-specific routing (includes tolls if configured)");
		}
	}

	/**
	 * Configures SwissRailRaptor (PT router) based on ExMas settings.
	 * 
	 * When ptOptimizeDepartureTime is enabled, sets up range query mode to allow
	 * flexible departure times for better PT connections.
	 * 
	 * Range query settings are derived from ExMas flexibility parameters:
	 * - maxEarlierDeparture: originFlexibilityAbsolute
	 * - maxLaterDeparture: originFlexibilityAbsolute
	 */
	private static void ensurePtRouterConfigCorrect(Config config, ExMasConfigGroup exMasConfig) {
		if (!exMasConfig.isPtOptimizeDepartureTime()) {
			log.info("PT departure time optimization disabled - skipping SwissRailRaptor configuration");
			return;
		}

		// Get or create SwissRailRaptorConfigGroup
		SwissRailRaptorConfigGroup raptorConfig = ConfigUtils.addOrGetModule(config, SwissRailRaptorConfigGroup.class);

		// Always enable range query for PT optimization
		raptorConfig.setUseRangeQuery(true);

		// Use ExMas origin flexibility for PT range query
		// This allows agents to depart earlier/later to catch better connections
		int maxEarlierDeparture = (int) exMasConfig.getOriginFlexibilityAbsolute();
		int maxLaterDeparture = (int) exMasConfig.getOriginFlexibilityAbsolute();

		// Check if ANY range query settings exist by checking the parameter sets
		var existingSettings = raptorConfig.getParameterSets("RangeQuerySettings");
		boolean hasRangeSettings = !existingSettings.isEmpty();

		if (!hasRangeSettings) {
			log.info("Configuring SwissRailRaptor DEFAULT range query for flexible PT departure times");

			// Create DEFAULT range query settings (no subpopulation = applies to all)
			SwissRailRaptorConfigGroup.RangeQuerySettingsParameterSet defaultSettings = new SwissRailRaptorConfigGroup.RangeQuerySettingsParameterSet();
			// NOT setting subpopulation makes this the DEFAULT for all subpopulations
			defaultSettings.setMaxEarlierDeparture(maxEarlierDeparture);
			defaultSettings.setMaxLaterDeparture(maxLaterDeparture);

			raptorConfig.addParameterSet(defaultSettings);

			log.info("  maxEarlierDeparture: {}s (from ExMas originFlexibilityAbsolute)", maxEarlierDeparture);
			log.info("  maxLaterDeparture: {}s (from ExMas originFlexibilityAbsolute)", maxLaterDeparture);
			log.info("  Applies to: ALL subpopulations (default settings)");
		} else {
			log.info("SwissRailRaptor range query settings already configured");
			log.info("  Number of parameter sets: {}", existingSettings.size());
		}
	}

	/**
	 * Ensures DRT configuration exists with all required parameters.
	 * 
	 * DRT is NOT simulated - only used for:
	 * - Routing infrastructure (network-based routing)
	 * - Budget calculation (fare parameters)
	 */
	private static void ensureDrtConfigExists(Config config, ExMasConfigGroup exMasConfig) {
		MultiModeDrtConfigGroup multiModeDrtConfig = ConfigUtils.addOrGetModule(config,
				MultiModeDrtConfigGroup.class);

		String drtMode = exMasConfig.getDrtMode();
		boolean drtExists = multiModeDrtConfig.getModalElements().stream()
				.anyMatch(drtCfg -> drtMode.equals(drtCfg.getMode()));

		if (!drtExists) {
			log.info("Creating DRT configuration for mode: {}", drtMode);
			log.info("  Note: DRT will NOT be simulated - only used for routing and budget calculation");

			DrtConfigGroup drtConfig = new DrtConfigGroup();
			drtConfig.setMode(drtMode);
			drtConfig.setStopDuration(30.0); // Required parameter

			// Set optimization constraints (used for validation)
			var constraintsSet = drtConfig.addOrGetDrtOptimizationConstraintsParams()
					.addOrGetDefaultDrtOptimizationConstraintsSet();
			constraintsSet.setMaxWaitTime(600.0); // Must be >= stopDuration
			constraintsSet.setMaxTravelTimeAlpha(1.0); // Must be >= 1.0
			constraintsSet.setMaxTravelTimeBeta(0.0); // Must be >= 0.0

			// Add required insertion search params (even though DRT won't run)
			SelectiveInsertionSearchParams insertionParams = new SelectiveInsertionSearchParams();
			drtConfig.addParameterSet(insertionParams);

			// Configure fare parameters for budget calculation
			var fareParams = new org.matsim.contrib.drt.fare.DrtFareParams();
			fareParams.setBaseFare(0.0);
			fareParams.setDailySubscriptionFee(0.0);
			fareParams.setDistanceFare_m(exMasConfig.getMinDrtCostPerKm() / 1000.0);
			fareParams.setTimeFare_h(0.0);
			fareParams.setMinFarePerTrip(0.0);
			drtConfig.addParameterSet(fareParams);

			multiModeDrtConfig.addDrtConfigGroup(drtConfig);

			log.info("  DRT fare: {}/km (from ExMas minDrtCostPerKm)", exMasConfig.getMinDrtCostPerKm());
		} else {
			log.info("DRT configuration already exists for mode: {}", drtMode);
		}
	}

	/**
	 * Ensures DvrpConfigGroup exists (required for DRT routing infrastructure).
	 */
	private static void ensureDvrpConfigExists(Config config) {
		if (!config.getModules().containsKey(DvrpConfigGroup.GROUP_NAME)) {
			log.info("Adding DvrpConfigGroup (required for DRT routing)");
			DvrpConfigGroup dvrpConfig = new DvrpConfigGroup();
			config.addModule(dvrpConfig);
		}
	}

	/**
	 * Ensures QSim configuration is correct for DVRP.
	 * DVRP requires simStarttimeInterpretation = "onlyUseStarttime".
	 */
	private static void ensureQSimConfigCorrect(Config config) {
		QSimConfigGroup qsim = config.qsim();
		if (qsim.getSimStarttimeInterpretation() != QSimConfigGroup.StarttimeInterpretation.onlyUseStarttime) {
			log.info("Setting QSim.simStarttimeInterpretation = onlyUseStarttime (required by DVRP)");
			qsim.setSimStarttimeInterpretation(QSimConfigGroup.StarttimeInterpretation.onlyUseStarttime);
		}
	}

	/**
	 * Ensures scoring parameters are correctly configured for demand extraction.
	 * 
	 * Validates:
	 * - DRT mode has scoring parameters
	 * - All base modes (used for budget calculation) have scoring parameters
	 * - Walking speed is consistent across scoring and routing
	 */
	private static void ensureScoringParamsCorrect(Config config, ExMasConfigGroup exMasConfig) {
		ScoringConfigGroup scoring = config.scoring();

		// Ensure DRT mode has scoring parameters
		String drtMode = exMasConfig.getDrtMode();
		if (!scoring.getModes().containsKey(drtMode)) {
			log.warn("Adding DRT scoring parameters for mode: {}", drtMode);
			log.warn("  WARNING: Using neutral default values!");
			log.warn(
					"  RECOMMENDATION: Configure DRT scoring parameters explicitly to match your scenario's utilities");

			ScoringConfigGroup.ModeParams drtParams = new ScoringConfigGroup.ModeParams(drtMode);
			drtParams.setMarginalUtilityOfTraveling(0.0); // Neutral travel time utility
			drtParams.setConstant(0.0); // No mode preference
			drtParams.setMonetaryDistanceRate(0.0); // Fare handled separately
			scoring.addModeParams(drtParams);
		}

		// Validate that all base modes have scoring parameters
		for (String baseMode : exMasConfig.getBaseModes()) {
			if (!scoring.getModes().containsKey(baseMode)) {
				log.error("Base mode '{}' is missing scoring parameters!", baseMode);
				log.error("  Budget calculation requires scoring parameters for all base modes");
				log.error("  Please add scoring parameters for mode: {}", baseMode);
				throw new RuntimeException("Missing scoring parameters for base mode: " + baseMode);
			}
		}

		log.info("Scoring parameters validated for all modes");
		log.info("  DRT mode: {} (marginalUtilityOfTraveling: {})",
				drtMode, scoring.getModes().get(drtMode).getMarginalUtilityOfTraveling());
		log.info("  Base modes: {}", exMasConfig.getBaseModes());
	}

	/**
	 * Validates walking speed consistency between ExMas config and routing config.
	 * 
	 * Walking speed is used for:
	 * - PT access/egress time calculation
	 * - Walk mode routing (if included in base modes)
	 */
	private static void validateWalkingSpeed(Config config, ExMasConfigGroup exMasConfig) {
		// Check if walk teleportation config exists and matches ExMas default
		var teleportedModeParams = config.routing().getTeleportedModeParams();
		if (teleportedModeParams.containsKey("walk")) {
			double configuredSpeed = teleportedModeParams.get("walk").getTeleportedModeSpeed();
			double expectedSpeed = ExMasConfigGroup.DEFAULT_WALK_SPEED;

			if (Math.abs(configuredSpeed - expectedSpeed) > 0.01) {
				log.warn("Walk speed mismatch detected!");
				log.warn("  Routing config: {} m/s ({} km/h)",
						configuredSpeed, configuredSpeed * 3.6);
				log.warn("  ExMas default: {} m/s ({} km/h)",
						expectedSpeed, expectedSpeed * 3.6);
				log.warn("  Using routing config value for consistency");
			}
		} else {
			log.info("Walk mode teleportation not configured - using ExMas default: {} m/s",
					ExMasConfigGroup.DEFAULT_WALK_SPEED);
		}
	}

	/**
	 * Validates ExMAS algorithm parameters for consistency and correctness.
	 */
	private static void validateExMasParameters(ExMasConfigGroup exMasConfig) {
		// Validate detour factor
		if (exMasConfig.getMaxDetourFactor() < 1.0) {
			throw new RuntimeException(
					"Invalid maxDetourFactor: " + exMasConfig.getMaxDetourFactor() +
							" (must be >= 1.0)");
		}

		// Validate flexibility parameters
		if (exMasConfig.getOriginFlexibilityAbsolute() < 0 ||
				exMasConfig.getDestinationFlexibilityAbsolute() < 0) {
			throw new RuntimeException("Flexibility parameters must be non-negative");
		}

		if (exMasConfig.getOriginFlexibilityRelative() < 0 ||
				exMasConfig.getOriginFlexibilityRelative() > 1.0 ||
				exMasConfig.getDestinationFlexibilityRelative() < 0 ||
				exMasConfig.getDestinationFlexibilityRelative() > 1.0) {
			throw new RuntimeException("Relative flexibility parameters must be in [0.0, 1.0]");
		}

		// Validate search horizon (0 means instant matching, no time window)
		if (exMasConfig.getSearchHorizon() < 0) {
			throw new RuntimeException("Search horizon must be non-negative (0 = instant matching)");
		}
		if (exMasConfig.getSearchHorizon() == 0) {
			log.info("Search horizon = 0: Using instant matching (no time window for pairing)");
		}

		// Validate pooling degree
		if (exMasConfig.getMaxPoolingDegree() < 2) {
			log.warn("Max pooling degree is less than 2 - no ride sharing will occur!");
		}

		// Validate DRT service quality parameters
		if (exMasConfig.getMinDrtCostPerKm() < 0) {
			throw new RuntimeException("Minimum DRT cost per km must be non-negative");
		}

		log.info("ExMAS algorithm parameters validated successfully");
	}
}
