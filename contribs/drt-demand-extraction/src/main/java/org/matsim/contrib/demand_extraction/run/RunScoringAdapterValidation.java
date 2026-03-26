package org.matsim.contrib.demand_extraction.run;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
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
import org.matsim.contribs.discrete_mode_choice.modules.DiscreteModeChoiceModule;
import org.matsim.contribs.discrete_mode_choice.modules.ModelModule;
import org.matsim.contribs.discrete_mode_choice.modules.config.DiscreteModeChoiceConfigGroup;
import org.eqasim.core.components.config.EqasimConfigGroup;
import org.eqasim.core.simulation.mode_choice.EqasimModeChoiceModule;
import org.eqasim.bavaria.mode_choice.BavariaModeChoiceModule;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.CommandLine;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.dsim.Activities;
import org.matsim.vehicles.VehicleType;

/**
 * Validation runner: compare demand extraction across scoring paradigms.
 *
 * <p>Runs Kelheim 1% with three scoring configurations using the same plans:
 * <ol>
 *   <li><b>planCalcScore</b> — standard MATSim (Kelheim calibrated params)</li>
 *   <li><b>dmc</b> — DMC MATSimTripScoring (parity check: should match planCalcScore)</li>
 *   <li><b>eqasim-approx</b> — planCalcScore with Bavaria eqasim-equivalent params</li>
 * </ol>
 *
 * <p>Usage:
 * <pre>
 * mvn exec:java -Dexec.mainClass="...RunScoringAdapterValidation" \
 *   -Dexec.args="--scenario-path ../matsim_scenarios/matsim-kelheim --scoring-mode planCalcScore"
 * </pre>
 *
 * <p>Run all three, then compare outputs in Python.
 */
public class RunScoringAdapterValidation {
	private static final Logger log = LogManager.getLogger(RunScoringAdapterValidation.class);

	public static void main(String[] args) throws IOException {
		configureSslTrustStoreIfNeeded();

		String scenarioPath = null;
		String scoringMode = "planCalcScore";
		boolean noOpportunityCost = false;

		for (int i = 0; i < args.length; i++) {
			if ("--scenario-path".equals(args[i]) && i + 1 < args.length) {
				scenarioPath = args[i + 1];
			} else if ("--scoring-mode".equals(args[i]) && i + 1 < args.length) {
				scoringMode = args[i + 1];
			} else if ("--no-opportunity-cost".equals(args[i])) {
				noOpportunityCost = true;
			}
		}

		if (scenarioPath == null) {
			log.error("Usage: --scenario-path <path> --scoring-mode <planCalcScore|dmc|eqasim-approx>");
			System.exit(1);
		}

		if (!Set.of("planCalcScore", "dmc", "eqasim-approx", "eqasim").contains(scoringMode)) {
			log.error("Invalid scoring mode: {}. Use: planCalcScore, dmc, eqasim-approx, eqasim", scoringMode);
			System.exit(1);
		}

		log.info("=== Scoring Adapter Validation ===");
		log.info("Scoring mode: {}", scoringMode);
		log.info("Scenario path: {}", scenarioPath);

		String suffix = noOpportunityCost ? "-no-opp" : "";
		Path outputDir = Path.of(scenarioPath).resolve("output/scoring-validation-" + scoringMode + suffix);
		Files.createDirectories(outputDir);

		// Load Kelheim config
		Config config = loadKelheimConfig(scenarioPath);

		// Configure for demand extraction
		configureForDemandExtraction(config, outputDir, scoringMode);

		// Apply scoring mode
		applyScoringMode(config, scoringMode);

		if (noOpportunityCost) {
			ExMasConfigGroup exMas = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
			exMas.setOpportunityCostModel(ExMasConfigGroup.OpportunityCostModel.NONE);
			log.info("Opportunity cost DISABLED");
		}

		DemandExtractionConfigValidator.prepareConfigForDemandExtraction(config);

		Scenario scenario = DrtControlerCreator.createScenarioWithDrtRouteFactory(config);
		ScenarioUtils.loadScenario(scenario);
		ensureVehicleTypeNetworkModes(scenario);
		filterFreightAgents(scenario);

		log.info("Population: {} agents", scenario.getPopulation().getPersons().size());

		Controler controler = DrtControlerCreator.createControler(config, scenario, false);
		controler.addOverridingModule(new DemandExtractionModule());

		// For DMC mode, install the DiscreteModeChoice module
		if ("dmc".equals(scoringMode)) {
			controler.addOverridingModule(new DiscreteModeChoiceModule());
		}

		// For eqasim mode, install full eqasim + Bavaria modules
		if ("eqasim".equals(scoringMode)) {
			// Install DMC base + eqasim modules (order matters)
			controler.addOverridingModule(new DiscreteModeChoiceModule());
			controler.addOverridingModule(new EqasimModeChoiceModule());
			try {
				controler.addOverridingModule(new BavariaModeChoiceModule(
						new CommandLine.Builder(new String[0]).build()));
			} catch (CommandLine.ConfigurationException e) {
				throw new RuntimeException("Failed to create BavariaModeChoiceModule", e);
			}
			// Bind missing dependencies that eqasim normally gets from its full stack
			controler.addOverridingModule(new org.matsim.core.controler.AbstractModule() {
				@Override
				public void install() {
					// UtilityPenalty: no policies active, use zero penalty
					bind(org.eqasim.core.simulation.policies.utility.UtilityPenalty.class)
							.toInstance((mode, person, trip, elements) -> 0.0);
					// HomeFinder: bind eqasim's default
					bind(org.matsim.contribs.discrete_mode_choice.components.utils.home_finder.HomeFinder.class)
							.to(org.eqasim.core.simulation.mode_choice.EqasimHomeFinder.class);
				}
			});
		}

		controler.run();

		log.info("\n=== Validation Run Complete ===");
		log.info("Scoring mode: {}", scoringMode);
		log.info("Output: {}", outputDir.toAbsolutePath());
		log.info("================================\n");
	}

	private static void applyScoringMode(Config config, String scoringMode) {
		ExMasConfigGroup exMasConfig = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);

		switch (scoringMode) {
			case "planCalcScore":
				exMasConfig.setScoringAdapter("planCalcScore");
				log.info("Using standard planCalcScore adapter (Kelheim calibrated params)");
				break;

			case "dmc":
				exMasConfig.setScoringAdapter("dmc");
				configureDmc(config);
				log.info("Using DMC MATSimTripScoring adapter (parity test)");
				break;

			case "eqasim-approx":
				exMasConfig.setScoringAdapter("planCalcScore");
				applyEqasimBavariaParams(config);
				// eqasim betaTravelTime already includes opportunity cost
				exMasConfig.setOpportunityCostModel(ExMasConfigGroup.OpportunityCostModel.NONE);
				// Set marginalUtilityOfMoney override (at reference distance)
				exMasConfig.setMarginalUtilityOfMoneyOverride(0.310998);
				log.info("Using planCalcScore adapter with Bavaria eqasim-approximate params");
				break;

			case "eqasim":
				exMasConfig.setScoringAdapter("eqasim");
				configureEqasim(config);
				// eqasim betaTravelTime already includes opportunity cost
				exMasConfig.setOpportunityCostModel(ExMasConfigGroup.OpportunityCostModel.NONE);
				log.info("Using real eqasim adapter with Bavaria scoring modules");
				break;
		}
	}

	/**
	 * Configure eqasim with Bavaria scoring modules.
	 * Sets up EqasimConfigGroup with mode → estimator mappings and DMC config.
	 */
	private static void configureEqasim(Config config) {
		// Add EqasimConfigGroup with estimator mappings
		EqasimConfigGroup eqasimConfig = ConfigUtils.addOrGetModule(config, EqasimConfigGroup.class);
		eqasimConfig.setEstimator("car", BavariaModeChoiceModule.CAR_ESTIMATOR_NAME);
		eqasimConfig.setEstimator("pt", BavariaModeChoiceModule.PT_ESTIMATOR_NAME);
		eqasimConfig.setEstimator("bike", BavariaModeChoiceModule.BICYCLE_ESTIMATOR_NAME);
		eqasimConfig.setEstimator("walk", EqasimModeChoiceModule.WALK_ESTIMATOR_NAME);
		eqasimConfig.setEstimator("drt", EqasimModeChoiceModule.DRT_ESTIMATOR_NAME);
		eqasimConfig.setEstimator("car_passenger", BavariaModeChoiceModule.CAR_PASSENGER_ESTIMATOR_NAME);

		// DRT mode parameters: set to PT-equivalent via YAML file
		// (eqasim community recommendation, see https://github.com/eqasim-org/eqasim-java/issues/269)
		String drtParamsPath = RunScoringAdapterValidation.class.getClassLoader()
				.getResource("eqasim-drt-mode-parameters.yml").getPath();
		eqasimConfig.setModeParametersPath(drtParamsPath);
		log.info("DRT mode parameters file: {}", drtParamsPath);

		// Cost models
		eqasimConfig.setCostModel("car", BavariaModeChoiceModule.CAR_COST_MODEL_NAME);
		eqasimConfig.setCostModel("pt", BavariaModeChoiceModule.PT_COST_MODEL_NAME);
		eqasimConfig.setCostModel("bike", EqasimModeChoiceModule.ZERO_COST_MODEL_NAME);
		eqasimConfig.setCostModel("walk", EqasimModeChoiceModule.ZERO_COST_MODEL_NAME);
		eqasimConfig.setCostModel("drt", EqasimModeChoiceModule.ZERO_COST_MODEL_NAME);
		eqasimConfig.setCostModel("car_passenger", EqasimModeChoiceModule.ZERO_COST_MODEL_NAME);

		// DMC config — use Trip model with eqasim estimator
		DiscreteModeChoiceConfigGroup dmcConfig = ConfigUtils.addOrGetModule(config,
				DiscreteModeChoiceConfigGroup.class);
		dmcConfig.setModelType(ModelModule.ModelType.Trip);
		dmcConfig.setTripEstimator(EqasimModeChoiceModule.UTILITY_ESTIMATOR_NAME);
		dmcConfig.setSelector("Maximum");
		dmcConfig.setTripConstraints(Collections.emptySet());
		dmcConfig.setModeAvailability(BavariaModeChoiceModule.MODE_AVAILABILITY_NAME);

		// Set planCalcScore performing to 0 (eqasim betaTravelTime includes opportunity cost)
		config.scoring().setPerforming_utils_hr(0.0);

		log.info("Eqasim config: estimators={}, costModels={}",
				eqasimConfig.getEstimators(), eqasimConfig.getCostModels());
	}

	/**
	 * Configure DMC module for MATSimTripScoring estimator.
	 */
	private static void configureDmc(Config config) {
		DiscreteModeChoiceConfigGroup dmcConfig = ConfigUtils.addOrGetModule(config,
				DiscreteModeChoiceConfigGroup.class);
		dmcConfig.setModelType(ModelModule.ModelType.Trip);
		dmcConfig.setTripEstimator("MATSimTripScoring");
		dmcConfig.setSelector("Maximum");

		// Set available modes matching our base modes
		dmcConfig.setTripConstraints(Collections.emptySet());
	}

	/**
	 * Override planCalcScore with Bavaria eqasim-equivalent scoring parameters.
	 *
	 * <p>Source: BavariaModeParameters.java from eqasim-java
	 * <ul>
	 *   <li>betaCost_u_MU = -0.310998</li>
	 *   <li>lambdaCostEuclideanDistance = -0.257501</li>
	 *   <li>referenceEuclideanDistance_km = 4.4</li>
	 *   <li>carCost_EUR_km = 0.2</li>
	 * </ul>
	 *
	 * <p>eqasim betaTravelTime includes implicit opportunity cost, so:
	 * <ul>
	 *   <li>performing_utils_hr = 0 (don't double-count)</li>
	 *   <li>includeOpportunityCost = false</li>
	 * </ul>
	 */
	private static void applyEqasimBavariaParams(Config config) {
		ScoringConfigGroup scoring = config.scoring();

		// Global params
		// At reference distance (4.4km): margUtilMoney = |betaCost| = 0.311
		scoring.setMarginalUtilityOfMoney(0.310998);

		// eqasim betaTravelTime includes opportunity cost — don't add performing on top
		scoring.setPerforming_utils_hr(0.0);

		// Car: alpha=0.4, betaTravelTime=-0.042431/min, monetaryDistRate=0.2 EUR/km
		ScoringConfigGroup.ModeParams car = getOrCreateModeParams(scoring, TransportMode.car);
		car.setConstant(0.4);
		car.setMarginalUtilityOfTraveling(-0.042431 * 60); // -2.546 utils/hr
		car.setMarginalUtilityOfDistance(0.0);
		car.setMonetaryDistanceRate(-0.0002); // -0.2 EUR/km = -0.0002 EUR/m
		car.setDailyMonetaryConstant(0.0);
		car.setDailyUtilityConstant(0.0);

		// PT: alpha=0.0, betaInVehicleTime=-0.025501/min
		ScoringConfigGroup.ModeParams pt = getOrCreateModeParams(scoring, TransportMode.pt);
		pt.setConstant(0.0);
		pt.setMarginalUtilityOfTraveling(-0.025501 * 60); // -1.530 utils/hr
		pt.setMarginalUtilityOfDistance(0.0);
		pt.setMonetaryDistanceRate(0.0);

		// Walk: alpha=0 (not 1.8 — walk ASC is for "walk as main mode", not sub-legs)
		// Setting 1.8 would inflate DRT budgets by ~3.6 utils from walk access/egress ASCs
		ScoringConfigGroup.ModeParams walk = getOrCreateModeParams(scoring, TransportMode.walk);
		walk.setConstant(0.0);
		walk.setMarginalUtilityOfTraveling(-0.162285 * 60); // -9.737 utils/hr
		walk.setMarginalUtilityOfDistance(0.0);
		walk.setMonetaryDistanceRate(0.0);

		// Bike: alpha=-0.5, betaTravelTime=-0.093485/min
		ScoringConfigGroup.ModeParams bike = getOrCreateModeParams(scoring, TransportMode.bike);
		bike.setConstant(-0.5);
		bike.setMarginalUtilityOfTraveling(-0.093485 * 60); // -5.609 utils/hr
		bike.setMarginalUtilityOfDistance(0.0);
		bike.setMonetaryDistanceRate(0.0);

		// DRT: keep Kelheim KEXI params (no eqasim DRT estimator exists)
		// Only baseline mode scoring changes
		ScoringConfigGroup.ModeParams drt = getOrCreateModeParams(scoring, "drt");
		drt.setConstant(2.45); // Keep Kelheim value
		drt.setMarginalUtilityOfTraveling(-0.042431 * 60); // Use car-like travel time
		drt.setMarginalUtilityOfDistance(-2.5E-4); // Keep Kelheim distance disutility
		drt.setMonetaryDistanceRate(0.0);

		// Ride: alpha=-1.4, betaInVehicleTravelTime=-0.069976/min
		ScoringConfigGroup.ModeParams ride = getOrCreateModeParams(scoring, TransportMode.ride);
		ride.setConstant(-1.4);
		ride.setMarginalUtilityOfTraveling(-0.069976 * 60); // -4.199 utils/hr

		// Waiting time
		scoring.setMarginalUtlOfWaitingPt_utils_hr(-0.021801 * 60); // -1.308 utils/hr

		log.info("Applied Bavaria eqasim-approximate parameters:");
		log.info("  marginalUtilityOfMoney: {}", scoring.getMarginalUtilityOfMoney());
		log.info("  performing_utils_hr: {} (disabled — eqasim includes opportunity cost)", scoring.getPerforming_utils_hr());
		log.info("  car: ASC={}, betaTT={} u/hr, monetaryDistRate={} EUR/m",
				car.getConstant(), car.getMarginalUtilityOfTraveling(), car.getMonetaryDistanceRate());
		log.info("  pt:  ASC={}, betaTT={} u/hr", pt.getConstant(), pt.getMarginalUtilityOfTraveling());
		log.info("  walk: ASC={}, betaTT={} u/hr", walk.getConstant(), walk.getMarginalUtilityOfTraveling());
		log.info("  bike: ASC={}, betaTT={} u/hr", bike.getConstant(), bike.getMarginalUtilityOfTraveling());
		log.info("  drt: ASC={}, betaTT={} u/hr, distRate={}", drt.getConstant(), drt.getMarginalUtilityOfTraveling(), drt.getMarginalUtilityOfDistance());
		log.info("  Note: betaCost distance interaction NOT modeled in planCalcScore (uses flat margUtilMoney)");
	}

	private static ScoringConfigGroup.ModeParams getOrCreateModeParams(ScoringConfigGroup scoring, String mode) {
		ScoringConfigGroup.ModeParams params = scoring.getModes().get(mode);
		if (params == null) {
			params = scoring.getOrCreateModeParams(mode);
		}
		return params;
	}

	// ==================== Infrastructure (reused from RunKelheimDemandExtraction) ====================

	private static Config loadKelheimConfig(String scenarioPath) {
		String configPath = Path.of(scenarioPath).resolve("input/test.with-drt.config.xml").toString();
		Config config = ConfigUtils.loadConfig(configPath,
				new ExMasConfigGroup(),
				new MultiModeDrtConfigGroup(),
				new DvrpConfigGroup());

		// Remove 'av' mode
		MultiModeDrtConfigGroup multiModeDrt = ConfigUtils.addOrGetModule(config, MultiModeDrtConfigGroup.class);
		org.matsim.contrib.drt.run.DrtConfigGroup avMode = null;
		for (org.matsim.contrib.drt.run.DrtConfigGroup drtConfig : multiModeDrt.getModalElements()) {
			if ("av".equals(drtConfig.getMode())) { avMode = drtConfig; break; }
		}
		if (avMode != null) multiModeDrt.removeParameterSet(avMode);

		DvrpConfigGroup dvrp = ConfigUtils.addOrGetModule(config, DvrpConfigGroup.class);
		dvrp.setNetworkModes(Collections.singleton("drt"));

		config.qsim().setFlowCapFactor(0.01);
		config.qsim().setStorageCapFactor(0.01);

		// Prefer offline inputs
		Path offlineDir = Path.of(scenarioPath).resolve("input/offline-v3.0");
		if (Files.isDirectory(offlineDir)) {
			applyOfflineInputs(config, offlineDir);
		} else {
			String plansFile = "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/kelheim/kelheim-v3.0/input/kelheim-v3.0-1pct-plans.xml.gz";
			config.plans().setInputFile(plansFile);
		}

		return config;
	}

	private static void applyOfflineInputs(Config config, Path offlineDir) {
		Path f;
		if (Files.exists(f = offlineDir.resolve("network-with-pt.xml.gz")))
			config.network().setInputFile(f.toAbsolutePath().toString());
		if (Files.exists(f = offlineDir.resolve("vehicle-types-with-drt.xml")))
			config.vehicles().setVehiclesFile(f.toAbsolutePath().toString());
		if (Files.exists(f = offlineDir.resolve("transitSchedule.xml.gz")))
			config.transit().setTransitScheduleFile(f.toAbsolutePath().toString());
		if (Files.exists(f = offlineDir.resolve("transitVehicles.xml.gz")))
			config.transit().setVehiclesFile(f.toAbsolutePath().toString());
		if (Files.exists(f = offlineDir.resolve("1pct.plans.xml.gz")))
			config.plans().setInputFile(f.toAbsolutePath().toString());

		MultiModeDrtConfigGroup multiModeDrt = ConfigUtils.addOrGetModule(config, MultiModeDrtConfigGroup.class);
		Path drtStops = offlineDir.resolve("drt-stops.xml");
		for (org.matsim.contrib.drt.run.DrtConfigGroup drtConfig : multiModeDrt.getModalElements()) {
			if ("drt".equals(drtConfig.getMode()) && Files.exists(drtStops))
				drtConfig.setTransitStopFile(drtStops.toAbsolutePath().toString());
		}
		log.info("Using offline inputs from: {}", offlineDir);
	}

	private static void configureForDemandExtraction(Config config, Path outputDir, String scoringMode) {
		config.vspExperimental().setVspDefaultsCheckingLevel(
				org.matsim.core.config.groups.VspExperimentalConfigGroup.VspDefaultsCheckingLevel.info);
		config.controller().setOutputDirectory(outputDir.toString());
		config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
		config.controller().setRunId("kelheim-1pct-" + scoringMode);
		config.controller().setLastIteration(0);
		config.controller().setWriteEventsInterval(0);
		config.controller().setWritePlansInterval(0);

		ExMasConfigGroup exMasConfig = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		exMasConfig.setDrtMode("drt");
		Set<String> baseModes = new HashSet<>(Set.of("car", "pt", "walk", "bike"));
		exMasConfig.setBaseModes(baseModes);
		exMasConfig.setDrtRoutingMode(TransportMode.car);
		exMasConfig.setCommuteFilter(CommuteFilter.COMMUTES_AND_EDUCATION);
		exMasConfig.setHomeActivityType("home");
		exMasConfig.setWorkActivityType("work");
		exMasConfig.setEducationActivityType("educ");
		exMasConfig.setMinAge(13);
		exMasConfig.setMinDrtAccessEgressDistance(100.0);
		exMasConfig.setSearchHorizon(3600.0);
		exMasConfig.setMaxDetourFactor(1.5);
		exMasConfig.setMaxAbsoluteDetour(3600);
		exMasConfig.setMaxPoolingDegree(16);
		exMasConfig.setCalcPredecessors(false); // Skip for speed
		exMasConfig.setCalcShapleyValues(false); // Skip for speed
		exMasConfig.setOpportunityCostModel(ExMasConfigGroup.OpportunityCostModel.LOG);
		exMasConfig.setPtOptimizeDepartureTime(false);
		exMasConfig.setHeuristicPruningEnabled(true);
		exMasConfig.setPruningKeepTopFractionPerRequestSet(0.3);

		Set<String> privateVehicles = new HashSet<>(Set.of("car", "bike"));
		exMasConfig.setPrivateVehicleModes(privateVehicles);

		Activities.addScoringParams(config);
	}

	private static void configureSslTrustStoreIfNeeded() {
		String os = System.getProperty("os.name", "").toLowerCase();
		if (os.contains("windows")) {
			String trustStoreType = System.getProperty("javax.net.ssl.trustStoreType");
			if (trustStoreType == null || trustStoreType.isBlank()) {
				System.setProperty("javax.net.ssl.trustStoreType", "Windows-ROOT");
			}
		}
	}

	private static void ensureVehicleTypeNetworkModes(Scenario scenario) {
		for (VehicleType type : scenario.getVehicles().getVehicleTypes().values()) {
			try { if (type.getNetworkMode() == null || type.getNetworkMode().isBlank()) type.setNetworkMode("car"); }
			catch (NullPointerException e) { type.setNetworkMode("car"); }
		}
		for (VehicleType type : scenario.getTransitVehicles().getVehicleTypes().values()) {
			try { if (type.getNetworkMode() == null || type.getNetworkMode().isBlank()) type.setNetworkMode("car"); }
			catch (NullPointerException e) { type.setNetworkMode("car"); }
		}
	}

	private static void filterFreightAgents(Scenario scenario) {
		scenario.getPopulation().getPersons().values().removeIf(person -> {
			Object subpop = person.getAttributes().getAttribute("subpopulation");
			if ("freight".equals(subpop)) return true;
			if (person.getSelectedPlan() != null) {
				return person.getSelectedPlan().getPlanElements().stream()
						.filter(Activity.class::isInstance)
						.map(Activity.class::cast)
						.anyMatch(act -> act.getType() != null && act.getType().startsWith("freight"));
			}
			return false;
		});
	}
}
