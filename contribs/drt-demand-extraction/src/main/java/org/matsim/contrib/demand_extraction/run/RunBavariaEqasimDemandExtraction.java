package org.matsim.contrib.demand_extraction.run;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eqasim.bavaria.mode_choice.BavariaModeChoiceModule;
import org.eqasim.bavaria.mode_choice.parameters.BavariaModeParameters;
import org.eqasim.core.components.config.EqasimConfigGroup;
import org.eqasim.core.simulation.mode_choice.EqasimModeChoiceModule;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup.CommuteFilter;
import org.matsim.contrib.demand_extraction.demand.DemandExtractionConfigValidator;
import org.matsim.contrib.demand_extraction.demand.DemandExtractionModule;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.contribs.discrete_mode_choice.modules.DiscreteModeChoiceModule;
import org.matsim.contribs.discrete_mode_choice.modules.ModelModule;
import org.matsim.contribs.discrete_mode_choice.modules.config.DiscreteModeChoiceConfigGroup;
import org.matsim.core.config.CommandLine;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ControllerConfigGroup;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup.ActivityParams;
import org.matsim.core.config.groups.VspExperimentalConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scenario.ScenarioUtils;

/**
 * Eqasim-native DRT demand extraction for Bavaria 30 km.
 *
 * <p>Uses raw eqasim population (no Kelheim adaptation) with calibrated ASCs from
 * MiD 2017 Niederbayern mode-share calibration. Installs BavariaModeChoiceModule +
 * EqasimModeChoiceModule, loads offline travel times from the congested 10% base
 * simulation, and routes demand extraction through EqasimScoringAdapter.
 *
 * <p>DRT scoring is car-equivalent via eqasim-drt-mode-parameters.yml (already
 * present in src/main/resources). DRT fare is zero in this runner — applied in
 * post-processing.
 *
 * <pre>
 * cd matsim-libs/contribs/drt-demand-extraction
 * mvn exec:java -o -Dexec.mainClass="org.matsim.contrib.demand_extraction.run.RunBavariaEqasimDemandExtraction" \
 *   -Dexec.args="--sample 10 --asc-yaml ../../../matsim_scenarios/bavaria/calibration/boptx/calibrated_asc.yml \
 *                --travel-times ../../../outputs/eqasim-base-10pct/travel_times_10pct_eqasim.tsv \
 *                --output-dir ../../../outputs/eqasim-demand-extraction-10pct" \
 *   -Denforcer.skip=true
 * </pre>
 */
public class RunBavariaEqasimDemandExtraction {

	private static final Logger log = LogManager.getLogger(RunBavariaEqasimDemandExtraction.class);

	private static final String SCENARIO_PATH =
			"../../../matsim_scenarios/bavaria/output/kelheim_30km_100pct";
	private static final String POPULATION_DIR =
			"../../../matsim_scenarios/bavaria/output/populations_eqasim";
	private static final String FILE_PREFIX = "kelheim_30km_100pct_";

	// Kelheim center EPSG:25832 for trip filter
	private static final double FILTER_CENTER_X = 709000.0;
	private static final double FILTER_CENTER_Y = 5423000.0;
	private static final double TRIP_FILTER_RADIUS_KM = 30.0;

	// 15-minute bins matching RunBavariaBaseSimulation / RunExportTravelTimes
	private static final int TRAVEL_TIME_BIN_SIZE = 900;
	private static final int TRAVEL_TIME_END = 36 * 3600;

	public static final class ParsedArgs {
		public final int sample;
		public final String ascYaml;
		public final String travelTimesPath;
		public final String outputDir;
		public final double searchHorizon;
		public final double maxDetourFactor;
		public final double minDrtCostPerKm;
		public final double interDegreeKeepFraction;

		ParsedArgs(int sample, String ascYaml, String travelTimesPath, String outputDir,
				double searchHorizon, double maxDetourFactor,
				double minDrtCostPerKm, double interDegreeKeepFraction) {
			this.sample = sample;
			this.ascYaml = ascYaml;
			this.travelTimesPath = travelTimesPath;
			this.outputDir = outputDir;
			this.searchHorizon = searchHorizon;
			this.maxDetourFactor = maxDetourFactor;
			this.minDrtCostPerKm = minDrtCostPerKm;
			this.interDegreeKeepFraction = interDegreeKeepFraction;
		}
	}

	static ParsedArgs parseArgs(String[] args) {
		int sample = -1;
		String ascYaml = null;
		String travelTimesPath = null;
		String outputDir = null;
		double searchHorizon = Double.NaN;
		double maxDetourFactor = Double.NaN;
		double minDrtCostPerKm = Double.NaN;
		double interDegreeKeepFraction = Double.NaN;

		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--sample" -> sample = Integer.parseInt(args[++i]);
				case "--asc-yaml" -> ascYaml = args[++i];
				case "--travel-times" -> travelTimesPath = args[++i];
				case "--output-dir" -> outputDir = args[++i];
				case "--search-horizon" -> searchHorizon = Double.parseDouble(args[++i]);
				case "--max-detour-factor" -> maxDetourFactor = Double.parseDouble(args[++i]);
				case "--min-drt-cost-per-km" -> minDrtCostPerKm = Double.parseDouble(args[++i]);
				case "--inter-degree-keep-fraction" -> interDegreeKeepFraction = Double.parseDouble(args[++i]);
				default -> log.warn("Unknown argument: {}", args[i]);
			}
		}
		return new ParsedArgs(sample, ascYaml, travelTimesPath, outputDir,
				searchHorizon, maxDetourFactor, minDrtCostPerKm, interDegreeKeepFraction);
	}

	public static void main(String[] args) throws Exception {
		ParsedArgs p = parseArgs(args);

		if (p.sample < 0 || p.ascYaml == null || p.travelTimesPath == null) {
			System.err.println("Usage: --sample <N> --asc-yaml <path> --travel-times <path> "
					+ "[--output-dir <path>] [--search-horizon <s>] [--max-detour-factor <f>] "
					+ "[--min-drt-cost-per-km <eur>] [--inter-degree-keep-fraction <f>]");
			System.exit(1);
		}

		String populationPath = POPULATION_DIR
				+ "/population_" + p.sample + "pct_kelheim30km.xml.gz";
		String outputDir = p.outputDir;
		if (outputDir == null) {
			outputDir = "../../../outputs/eqasim-demand-extraction-" + p.sample + "pct";
		}

		log.info("=== Bavaria eqasim DRT demand extraction ===");
		log.info("  Sample:        {}%", p.sample);
		log.info("  Population:    {}", populationPath);
		log.info("  ASC YAML:      {}", p.ascYaml);
		log.info("  Travel times:  {}", p.travelTimesPath);
		log.info("  Output:        {}", outputDir);

		run(p.sample, populationPath, p.ascYaml, p.travelTimesPath, outputDir);
	}

	private static void run(int sample, String populationPath, String ascYaml,
			String travelTimesPath, String outputDir) throws Exception {

		Map<String, Double> ascs = loadCalibratedAsc(ascYaml);

		Path outDir = Path.of(outputDir);
		Files.createDirectories(outDir);

		Config config = buildConfig(sample, populationPath);
		configureForDemandExtraction(config, outDir, sample);
		configureEqasim(config);

		DemandExtractionConfigValidator.prepareConfigForDemandExtraction(config);

		Scenario scenario = ScenarioUtils.createScenario(config);
		ScenarioUtils.loadScenario(scenario);
		log.info("Population: {} agents", scenario.getPopulation().getPersons().size());

		Controler controler = new Controler(scenario);

		// Install eqasim stack: DMC base + EqasimModeChoiceModule + BavariaModeChoiceModule
		controler.addOverridingModule(new DiscreteModeChoiceModule());
		controler.addOverridingModule(new EqasimModeChoiceModule());
		controler.addOverridingModule(new BavariaModeChoiceModule(
				new CommandLine.Builder(new String[0]).build()));

		// Bind eqasim dependencies (UtilityPenalty, HomeFinder) same as RunScoringAdapterValidation
		controler.addOverridingModule(new org.matsim.core.controler.AbstractModule() {
			@Override
			public void install() {
				bind(org.eqasim.core.simulation.policies.utility.UtilityPenalty.class)
						.toInstance((mode, person, trip, elements) -> 0.0);
				bind(org.matsim.contribs.discrete_mode_choice.components.utils.home_finder.HomeFinder.class)
						.to(org.eqasim.core.simulation.mode_choice.EqasimHomeFinder.class);
			}
		});

		// Override BavariaModeParameters provider with calibrated ASCs
		controler.addOverridingModule(new org.matsim.core.controler.AbstractModule() {
			@Override
			public void install() {
				bind(org.eqasim.core.simulation.mode_choice.parameters.ModeParameters.class)
						.toProvider(() -> {
							BavariaModeParameters p = BavariaModeParameters.buildDefault();
							applyAscOverrides(p, ascs);
							return p;
						});
			}
		});

		// Load offline travel times, bind as car TravelTime
		TravelTime offlineTravelTime = loadOfflineTravelTimes(travelTimesPath);
		controler.addOverridingModule(new org.matsim.core.controler.AbstractModule() {
			@Override
			public void install() {
				addTravelTimeBinding(TransportMode.car).toInstance(offlineTravelTime);
			}
		});

		controler.addOverridingModule(new DemandExtractionModule());

		controler.run();

		log.info("\n=== Eqasim DRT demand extraction complete ===");
		log.info("Output: {}", outDir.toAbsolutePath());
	}

	// -------------------------------------------------------------------------
	// YAML loading
	// -------------------------------------------------------------------------

	static Map<String, Double> loadCalibratedAsc(String yamlPath) throws IOException {
		org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
		try (InputStream is = Files.newInputStream(Path.of(yamlPath))) {
			Map<String, Object> raw = yaml.load(is);
			if (raw == null) {
				throw new IOException("Empty YAML file: " + yamlPath);
			}
			Map<String, Double> result = new LinkedHashMap<>();
			// Required: the 4 base ASCs. Optional: carPassenger.alpha_u (5-param variant).
			for (String key : List.of("car.alpha_u", "bike.alpha_u", "walk.alpha_u", "pt.alpha_u")) {
				Object v = raw.get(key);
				if (v == null) {
					throw new IOException("Missing key '" + key + "' in " + yamlPath);
				}
				result.put(key, ((Number) v).doubleValue());
			}
			Object cp = raw.get("carPassenger.alpha_u");
			if (cp != null) {
				result.put("carPassenger.alpha_u", ((Number) cp).doubleValue());
			}
			log.info("Loaded calibrated ASCs from {}: {}", yamlPath, result);
			return result;
		}
	}

	// -------------------------------------------------------------------------
	// ASC override via reflection on BavariaModeParameters
	// -------------------------------------------------------------------------

	static void applyAscOverrides(
			org.eqasim.core.simulation.mode_choice.parameters.ModeParameters params,
			Map<String, Double> ascs) {
		for (Map.Entry<String, Double> e : ascs.entrySet()) {
			String[] parts = e.getKey().split("\\.");
			if (parts.length != 2) {
				throw new RuntimeException("Invalid ASC key (expected mode.field): " + e.getKey());
			}
			String mode = parts[0];     // e.g. "car"
			String field = parts[1];    // e.g. "alpha_u"
			try {
				Object modeObj = params.getClass().getField(mode).get(params);
				java.lang.reflect.Field f = modeObj.getClass().getField(field);
				f.setDouble(modeObj, e.getValue());
				log.info("  applied {}.{} = {}", mode, field, String.format("%+.6f", e.getValue()));
			} catch (NoSuchFieldException | IllegalAccessException ex) {
				throw new RuntimeException("Failed to apply " + e.getKey(), ex);
			}
		}
	}

	// -------------------------------------------------------------------------
	// Offline travel times loading (matches RunBavaria30kmDemandExtraction pattern)
	// -------------------------------------------------------------------------

	private static TravelTime loadOfflineTravelTimes(String ttFile) throws IOException {
		log.info("Loading pre-computed travel times from: {}", ttFile);
		var timeDiscretizer = new org.matsim.contrib.common.timeprofile.TimeDiscretizer(
				TRAVEL_TIME_END, TRAVEL_TIME_BIN_SIZE);
		java.net.URL ttUrl = Path.of(ttFile).toUri().toURL();
		double[][] matrix = org.matsim.contrib.dvrp.trafficmonitoring.DvrpOfflineTravelTimes
				.loadLinkTravelTimes(timeDiscretizer, ttUrl, "\t");
		var baseTt = org.matsim.contrib.dvrp.trafficmonitoring.DvrpOfflineTravelTimes
				.asTravelTime(timeDiscretizer, matrix);
		log.info("Bound pre-computed travel times ({} time bins, clamped to {}h)",
				timeDiscretizer.getIntervalCount(), TRAVEL_TIME_END / 3600);
		return (link, time, person, vehicle) ->
				baseTt.getLinkTravelTime(link, Math.min(time, TRAVEL_TIME_END), person, vehicle);
	}

	// -------------------------------------------------------------------------
	// Config building (programmatic, same pattern as RunBavaria30kmDemandExtraction)
	// -------------------------------------------------------------------------

	private static Config buildConfig(int sample, String populationPath) {
		Config config = ConfigUtils.createConfig(
				new ExMasConfigGroup(),
				new MultiModeDrtConfigGroup(),
				new DvrpConfigGroup(),
				new EqasimConfigGroup(),
				new DiscreteModeChoiceConfigGroup());

		Path base = Path.of(SCENARIO_PATH);
		config.network().setInputFile(base.resolve(FILE_PREFIX + "network.xml.gz").toString());
		config.transit().setTransitScheduleFile(base.resolve(FILE_PREFIX + "transit_schedule.xml.gz").toString());
		config.transit().setVehiclesFile(base.resolve(FILE_PREFIX + "transit_vehicles.xml.gz").toString());
		config.vehicles().setVehiclesFile(base.resolve(FILE_PREFIX + "vehicles.xml.gz").toString());
		config.facilities().setInputFile(base.resolve(FILE_PREFIX + "facilities.xml.gz").toString());
		config.plans().setInputFile(populationPath);
		config.transit().setUseTransit(true);

		config.global().setCoordinateSystem("Atlantis");
		config.global().setNumberOfThreads(6);

		double sampleFactor = sample / 100.0;
		config.qsim().setFlowCapFactor(sampleFactor);
		config.qsim().setStorageCapFactor(sampleFactor);
		config.qsim().setMainModes(java.util.List.of("car"));
		config.qsim().setNumberOfThreads(8);
		config.qsim().setStartTime(0);
		config.qsim().setEndTime(36 * 3600);
		config.qsim().setTrafficDynamics(QSimConfigGroup.TrafficDynamics.kinematicWaves);
		config.qsim().setVehiclesSource(QSimConfigGroup.VehiclesSource.fromVehiclesData);
		config.qsim().setUsePersonIdForMissingVehicleId(true);

		config.routing().setNetworkModes(java.util.List.of("car"));

		registerEqasimActivities(config);
		registerMinimalModeParams(config);

		return config;
	}

	/**
	 * Register minimal mode params for MATSim's scoring function, which fires during
	 * QSim event processing even though the DemandExtractionListener uses
	 * EqasimScoringAdapter for its own budget computation. Without these, QSim crashes
	 * with "just encountered mode for which no scoring parameters are defined" when a
	 * leg of the unset mode is emitted.
	 */
	private static void registerMinimalModeParams(Config config) {
		ScoringConfigGroup scoring = config.scoring();
		String[] modes = {"car", "pt", "walk", "bike", "bicycle", "car_passenger", "ride", "drt"};
		for (String mode : modes) {
			ScoringConfigGroup.ModeParams mp = scoring.getModes().get(mode);
			if (mp == null) {
				mp = scoring.getOrCreateModeParams(mode);
			}
			mp.setConstant(0.0);
			mp.setMarginalUtilityOfTraveling(0.0);
			mp.setMarginalUtilityOfDistance(0.0);
			mp.setMonetaryDistanceRate(0.0);
		}
	}

	private static void registerEqasimActivities(Config config) {
		ScoringConfigGroup scoring = config.scoring();
		addActivityParams(scoring, "home", 12 * 3600, true);
		addActivityParams(scoring, "work", 8 * 3600, true);
		addActivityParams(scoring, "education", 6 * 3600, true);
		addActivityParams(scoring, "shop", 1 * 3600, true);
		addActivityParams(scoring, "leisure", 2 * 3600, true);
		addActivityParams(scoring, "other", 2 * 3600, true);
		addActivityParams(scoring, "outside", -1, false);
		addActivityParams(scoring, "freight_loading", -1, false);
		addActivityParams(scoring, "freight_unloading", -1, false);
		for (String mode : new String[]{"car", "pt", "bike", "walk", "drt", "ride",
				"taxi", "other", "car_passenger"}) {
			addActivityParams(scoring, mode + " interaction", -1, false);
		}
	}

	private static void addActivityParams(ScoringConfigGroup scoring, String type,
			double typicalDuration, boolean scored) {
		ActivityParams params = new ActivityParams(type);
		if (typicalDuration > 0) {
			params.setTypicalDuration(typicalDuration);
		}
		params.setScoringThisActivityAtAll(scored);
		scoring.addActivityParams(params);
	}

	// -------------------------------------------------------------------------
	// Eqasim DMC + estimator + cost model setup (adapted from RunScoringAdapterValidation.configureEqasim)
	// -------------------------------------------------------------------------

	private static void configureEqasim(Config config) {
		EqasimConfigGroup eqasimConfig = ConfigUtils.addOrGetModule(config, EqasimConfigGroup.class);
		eqasimConfig.setEstimator("car", BavariaModeChoiceModule.CAR_ESTIMATOR_NAME);
		eqasimConfig.setEstimator("pt", BavariaModeChoiceModule.PT_ESTIMATOR_NAME);
		eqasimConfig.setEstimator("bike", BavariaModeChoiceModule.BICYCLE_ESTIMATOR_NAME);
		eqasimConfig.setEstimator("walk", EqasimModeChoiceModule.WALK_ESTIMATOR_NAME);
		eqasimConfig.setEstimator("drt", EqasimModeChoiceModule.DRT_ESTIMATOR_NAME);
		eqasimConfig.setEstimator("car_passenger", BavariaModeChoiceModule.CAR_PASSENGER_ESTIMATOR_NAME);

		// DRT mode parameters: car-equivalent from bundled resource
		String drtParamsPath = RunBavariaEqasimDemandExtraction.class.getClassLoader()
				.getResource("eqasim-drt-mode-parameters.yml").getPath();
		eqasimConfig.setModeParametersPath(drtParamsPath);
		log.info("DRT mode parameters file: {}", drtParamsPath);

		eqasimConfig.setCostModel("car", BavariaModeChoiceModule.CAR_COST_MODEL_NAME);
		eqasimConfig.setCostModel("pt", BavariaModeChoiceModule.PT_COST_MODEL_NAME);
		eqasimConfig.setCostModel("bike", EqasimModeChoiceModule.ZERO_COST_MODEL_NAME);
		eqasimConfig.setCostModel("walk", EqasimModeChoiceModule.ZERO_COST_MODEL_NAME);
		eqasimConfig.setCostModel("drt", EqasimModeChoiceModule.ZERO_COST_MODEL_NAME);
		eqasimConfig.setCostModel("car_passenger", EqasimModeChoiceModule.ZERO_COST_MODEL_NAME);

		DiscreteModeChoiceConfigGroup dmcConfig = ConfigUtils.addOrGetModule(config,
				DiscreteModeChoiceConfigGroup.class);
		dmcConfig.setModelType(ModelModule.ModelType.Trip);
		dmcConfig.setTripEstimator(EqasimModeChoiceModule.UTILITY_ESTIMATOR_NAME);
		dmcConfig.setSelector("Maximum");
		dmcConfig.setTripConstraints(Collections.emptySet());
		dmcConfig.setModeAvailability(BavariaModeChoiceModule.MODE_AVAILABILITY_NAME);

		// eqasim betaTravelTime includes opportunity cost — zero performing_utils_hr
		config.scoring().setPerforming_utils_hr(0.0);
	}

	// -------------------------------------------------------------------------
	// Demand extraction configuration (mirrors RunBavaria30kmDemandExtraction)
	// -------------------------------------------------------------------------

	private static void configureForDemandExtraction(Config config, Path outputDir, int sample) {
		config.vspExperimental().setVspDefaultsCheckingLevel(
				VspExperimentalConfigGroup.VspDefaultsCheckingLevel.info);

		config.controller().setOutputDirectory(outputDir.toString());
		config.controller().setOverwriteFileSetting(
				OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
		config.controller().setRunId("bavaria-30km-" + sample + "pct-eqasim-exmas");
		config.controller().setLastIteration(0);
		config.controller().setWriteEventsInterval(0);
		config.controller().setWritePlansInterval(0);
		config.controller().setRoutingAlgorithmType(
				ControllerConfigGroup.RoutingAlgorithmType.SpeedyALT);

		ExMasConfigGroup exMasConfig = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		exMasConfig.setDrtMode("drt");
		Set<String> baseModes = new HashSet<>(Set.of("car", "pt", "walk", "bike"));
		exMasConfig.setBaseModes(baseModes);
		exMasConfig.setDrtRoutingMode(TransportMode.car);
		exMasConfig.setCommuteFilter(CommuteFilter.COMMUTES_AND_EDUCATION);
		exMasConfig.setHomeActivityType("home");
		exMasConfig.setWorkActivityType("work");
		exMasConfig.setEducationActivityType("education");
		exMasConfig.setMinAge(13);
		exMasConfig.setMinDrtAccessEgressDistance(100.0);
		exMasConfig.setSearchHorizon(3600.0);
		exMasConfig.setMaxDetourFactor(1.5);
		exMasConfig.setMaxAbsoluteDetour(3600);
		exMasConfig.setMaxPoolingDegree(16);
		exMasConfig.setCalcPredecessors(false);
		exMasConfig.setCalcShapleyValues(false);
		// eqasim betaTravelTime already includes opportunity cost
		exMasConfig.setOpportunityCostModel(ExMasConfigGroup.OpportunityCostModel.NONE);
		exMasConfig.setPtOptimizeDepartureTime(false);
		exMasConfig.setHeuristicPruningEnabled(true);
		exMasConfig.setPruningKeepTopFractionPerRequestSet(0.3);

		Set<String> privateVehicles = new HashSet<>(Set.of("car", "bike"));
		exMasConfig.setPrivateVehicleModes(privateVehicles);

		// Trip-level 30km filter around Kelheim center
		exMasConfig.setTripFilterRadiusKm(TRIP_FILTER_RADIUS_KM);
		exMasConfig.setTripFilterCenterX(FILTER_CENTER_X);
		exMasConfig.setTripFilterCenterY(FILTER_CENTER_Y);

		// Explicit eqasim scoring adapter (not planCalcScore, not dmc)
		exMasConfig.setScoringAdapter("eqasim");

		log.info("Demand extraction configured: scoringAdapter=eqasim, "
				+ "tripFilter={}km around Kelheim", TRIP_FILTER_RADIUS_KM);
	}
}
