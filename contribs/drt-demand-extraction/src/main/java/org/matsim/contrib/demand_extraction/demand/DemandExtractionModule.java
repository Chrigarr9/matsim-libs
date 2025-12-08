package org.matsim.contrib.demand_extraction.demand;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.contrib.demand_extraction.algorithm.ExMasAlgorithmModule;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.AbstractModule;

import ch.sbb.matsim.config.SwissRailRaptorConfigGroup;

public class DemandExtractionModule extends AbstractModule {
	private static final Logger log = LogManager.getLogger(DemandExtractionModule.class);

	@Override
    public void install() {
		Config config = getConfig();

		// Check that ExMasConfigGroup is present in config
		// (it will be automatically bound by MATSim's ExplodedConfigModule)
		if (!config.getModules().containsKey(ExMasConfigGroup.GROUP_NAME)) {
            throw new RuntimeException("ExMasConfigGroup is required but not found in config. "
                    + "Please add it to your config file using: "
                    + "config.addModule(new ExMasConfigGroup())");
        }

		// Auto-configure required DRT/DVRP settings for demand extraction
		// This is idempotent - safe to call even if configs were already set up
		// externally
		ensureRequiredConfigs(config);

		log.info("DemandExtractionModule: Auto-configured DRT/DVRP settings for demand extraction");
		log.info("  Note: DRT will NOT be simulated - only used for routing and budget calculation");

        // Bind demand extraction components
        bind(ModeRoutingCache.class).asEagerSingleton();
        bind(ChainIdentifier.class).asEagerSingleton();
		bind(CommuteIdentifier.class).asEagerSingleton();
		bind(DrtRequestFactory.class).asEagerSingleton();
        
        // Install ExMAS algorithm module (validators, network cache, etc.)
        install(new ExMasAlgorithmModule());
        
		// Register shutdown listener (runs after all iterations complete)
        addControllerListenerBinding().to(DemandExtractionListener.class);
    }

	/**
	 * Static helper to ensure all required configs are set up for demand
	 * extraction.
	 * This can be called before creating DrtControlerCreator (which requires DRT
	 * config to exist).
	 * Safe to call multiple times - will skip if configs already exist.
	 */
	public static void ensureRequiredConfigs(Config config) {
		ExMasConfigGroup exMasConfig = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		ensureDrtConfigExists(config, exMasConfig);
		ensureDvrpConfigExists(config);
		ensureQSimConfigCorrect(config);
		ensureDrtScoringParamsExist(config, exMasConfig.getDrtMode());
		ensureTravelTimeCalculatorCorrect(config);
		ensurePtRouterConfigCorrect(config, exMasConfig);
	}

	/**
	 * Ensures TravelTimeCalculator is configured with mode-specific travel times.
	 * This fixes the VSP config consistency warning:
	 * "travelTimeCalculator is not analyzing different modes separately"
	 *
	 * Mode-specific travel times are important for accurate routing, especially when
	 * comparing modes like car vs bike that have very different speeds on the network.
	 */
	private static void ensureTravelTimeCalculatorCorrect(Config config) {
		if (!config.travelTimeCalculator().getSeparateModes()) {
			log.info("Setting travelTimeCalculator.separateModes to true (required for mode-specific travel times)");
			config.travelTimeCalculator().setSeparateModes(true);
		}
	}

	/**
	 * Configures SwissRailRaptor (PT router) based on ExMas settings.
	 *
	 * When ptOptimizeDepartureTime is true, enables range query mode which allows
	 * the PT router to find connections within a time window around the requested
	 * departure time. This reduces waiting times by allowing agents to leave
	 * earlier or later to catch better PT connections.
	 *
	 * Range query parameters:
	 * - maxEarlierDeparture: How much earlier can agent leave (default: 10 min = 600s)
	 * - maxLaterDeparture: How much later can agent leave (default: 15 min = 900s)
	 */
	private static void ensurePtRouterConfigCorrect(Config config, ExMasConfigGroup exMasConfig) {
		if (!exMasConfig.isPtOptimizeDepartureTime()) {
			return; // Don't modify PT router config if optimization is disabled
		}

		// Get or create SwissRailRaptorConfigGroup
		SwissRailRaptorConfigGroup raptorConfig = ConfigUtils.addOrGetModule(config, SwissRailRaptorConfigGroup.class);

		if (!raptorConfig.isUseRangeQuery()) {
			log.info("Enabling SwissRailRaptor range query for flexible PT departure times");
			raptorConfig.setUseRangeQuery(true);

			// Add default range query settings if none exist
			if (raptorConfig.getRangeQuerySettings(null) == null) {
				SwissRailRaptorConfigGroup.RangeQuerySettingsParameterSet rangeSettings =
						new SwissRailRaptorConfigGroup.RangeQuerySettingsParameterSet();
				// Default: 10 min earlier, 15 min later (same as SwissRailRaptor defaults)
				rangeSettings.setMaxEarlierDeparture(600);
				rangeSettings.setMaxLaterDeparture(900);
				raptorConfig.addRangeQuerySettings(rangeSettings);
				log.info("  Added range query settings: maxEarlierDeparture=600s, maxLaterDeparture=900s");
			}
		}
	}

	/**
	 * Ensures MultiModeDrtConfigGroup exists with minimal DRT config.
	 * This is needed for BudgetToConstraintsCalculator to read fare parameters.
	 * DRT will NOT actually be simulated.
	 */
	private static void ensureDrtConfigExists(Config config, ExMasConfigGroup exMasConfig) {
		MultiModeDrtConfigGroup multiModeDrtConfig = ConfigUtils.addOrGetModule(config,
				MultiModeDrtConfigGroup.class);

		String drtMode = exMasConfig.getDrtMode();
		boolean drtExists = multiModeDrtConfig.getModalElements().stream()
				.anyMatch(drtCfg -> drtMode.equals(drtCfg.getMode()));

		if (!drtExists) {
			log.info("Adding minimal DRT config for mode: {}", drtMode);
			DrtConfigGroup drtConfig = new DrtConfigGroup();
			drtConfig.setMode(drtMode);
			drtConfig.setStopDuration(30.0); // Required parameter

			// Set optimization constraints (validation requirements)
			var constraintsSet = drtConfig.addOrGetDrtOptimizationConstraintsParams()
					.addOrGetDefaultDrtOptimizationConstraintsSet();
			constraintsSet.setMaxWaitTime(600.0); // Must be >= stopDuration
			constraintsSet.setMaxTravelTimeAlpha(1.0); // Must be >= 1.0
			constraintsSet.setMaxTravelTimeBeta(0.0); // Must be >= 0.0

			// Add required insertion search params (even though DRT won't run)
			org.matsim.contrib.drt.optimizer.insertion.selective.SelectiveInsertionSearchParams insertionParams = new org.matsim.contrib.drt.optimizer.insertion.selective.SelectiveInsertionSearchParams();
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
		}
	}

	/**
	 * Ensures DvrpConfigGroup exists with correct settings.
	 * Required because we use DRT routing infrastructure.
	 */
	private static void ensureDvrpConfigExists(Config config) {
		if (!config.getModules().containsKey(DvrpConfigGroup.GROUP_NAME)) {
			log.info("Adding DvrpConfigGroup with default settings");
			DvrpConfigGroup dvrpConfig = new DvrpConfigGroup();
			config.addModule(dvrpConfig);
		}
	}

	/**
	 * Ensures QSim config has correct simStarttimeInterpretation.
	 * DVRP requires "onlyUseStarttime" to work properly.
	 */
	private static void ensureQSimConfigCorrect(Config config) {
		QSimConfigGroup qsim = config.qsim();
		if (qsim.getSimStarttimeInterpretation() != QSimConfigGroup.StarttimeInterpretation.onlyUseStarttime) {
			log.info("Setting QSim.simStarttimeInterpretation to 'onlyUseStarttime' (required by DVRP)");
			qsim.setSimStarttimeInterpretation(QSimConfigGroup.StarttimeInterpretation.onlyUseStarttime);
		}
	}

	/**
	 * Ensures DRT mode has scoring parameters defined.
	 * This is needed for utility calculation in budget determination.
	 * Does NOT add DRT to mode choice - only adds scoring params.
	 */
	private static void ensureDrtScoringParamsExist(Config config, String drtMode) {
		ScoringConfigGroup scoring = config.scoring();
		if (!scoring.getModes().containsKey(drtMode)) {
			log.info("Adding DRT scoring parameters for mode: {}", drtMode);
			ScoringConfigGroup.ModeParams drtParams = new ScoringConfigGroup.ModeParams(drtMode);

			// Use neutral/conservative parameters for DRT
			// These will be used for budget calculation (score comparison)
			drtParams.setMarginalUtilityOfTraveling(0.0); // Neutral travel time utility
			drtParams.setConstant(0.0); // No mode preference
			drtParams.setMonetaryDistanceRate(0.0); // Fare handled separately

			scoring.addModeParams(drtParams);

			log.warn("  WARNING: DRT scoring params were auto-generated with neutral values.");
			log.warn("  Consider configuring them explicitly based on your scenario's mode utilities.");
		}
	}
}
