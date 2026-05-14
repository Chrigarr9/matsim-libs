package org.matsim.contrib.demand_extraction.scenarios;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eqasim.core.components.config.EqasimConfigGroup;
import org.eqasim.core.simulation.mode_choice.EqasimModeChoiceModule;
import org.eqasim.ile_de_france.mode_choice.IDFModeChoiceModule;
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
import org.matsim.core.config.groups.VspExperimentalConfigGroup;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scenario.ScenarioUtils;

/**
 * Lyon eqasim DRT demand extraction fixture. Lifts the setup that was
 * previously inlined in {@code RunLyonEqasimDemandExtraction} so the same
 * setup is used by both the runner (Phase 5a.5) and the parameterised
 * algorithm test (Phase 5b.1).
 *
 * <p>Loads the synpp-produced cut config (which already contains the full IDF
 * eqasim stack) and overrides only the fields that differ between a full
 * simulation and a single-iteration demand-extraction pass.
 *
 * <p>The fixture takes paths and sample size as constructor args. For test
 * usage see {@link #fromEnv()}, which reads {@code LYON_SCENARIO_DIR},
 * {@code LYON_SCENARIO_PREFIX} (default {@code lyon_drt_area_}),
 * {@code LYON_TRAVEL_TIMES_TSV}, and {@code LYON_SAMPLE_PCT} (default 1).
 */
public class LyonEqasimScenarioFixture implements ExMasScenarioFixture {

	private static final Logger log = LogManager.getLogger(LyonEqasimScenarioFixture.class);

	// Trip-filter polygon: 40-km radius around the union centroid of the 3 Ain
	// communes (Loyettes, Saint-Maurice-de-Gourdans, Saint-Jean-de-Niost).
	// EPSG:2154 / Lambert-93. Centroid produced by build_cutter_polygon.py.
	private static final double FILTER_CENTER_X = 870540.4;
	private static final double FILTER_CENTER_Y = 6526302.7;
	private static final double TRIP_FILTER_RADIUS_KM = 40.0;

	// Exclusion zone: drop trips whose O AND D both lie within the Métropole de Lyon.
	// The service is designed for rural↔urban access, not intra-metropolitan rides.
	// Shapefile = union of 58 communes with EPCI 200046977 (EPSG:2154).
	// Path is relative to scenarioDir (e.g. output_lyon_drt_1pct/lyon_drt_area/); ../../ navigates to eqasim-france/.
	private static final String EXCLUSION_ZONE_SHAPEFILE = "../../data/cutter/metropole_lyon.shp";

	// 15-minute bins matching RunExportTravelTimes
	private static final int TRAVEL_TIME_BIN_SIZE = 900;
	private static final int TRAVEL_TIME_END = 36 * 3600;

	/**
	 * Parametric filter configuration for trip-filter focus, radius, and
	 * (optional) exclusion shapefile. A {@code null} {@code exclusionShapefilePath}
	 * means no exclusion zone is applied.
	 */
	public record FilterConfig(double centerX, double centerY, double radiusKm,
			String exclusionShapefilePath) {}

	private final int samplePct;
	private final String scenarioDir;
	private final String prefix;
	private final String travelTimesPath;
	private final FilterConfig filter;

	public LyonEqasimScenarioFixture(int samplePct, String scenarioDir, String prefix,
			String travelTimesPath) {
		this(samplePct, scenarioDir, prefix, travelTimesPath,
				new FilterConfig(FILTER_CENTER_X, FILTER_CENTER_Y, TRIP_FILTER_RADIUS_KM,
						EXCLUSION_ZONE_SHAPEFILE));
	}

	public LyonEqasimScenarioFixture(int samplePct, String scenarioDir, String prefix,
			String travelTimesPath, FilterConfig filter) {
		this.samplePct = samplePct;
		this.scenarioDir = scenarioDir;
		this.prefix = prefix;
		this.travelTimesPath = travelTimesPath;
		this.filter = filter;
	}

	/**
	 * Build a fixture from {@code LYON_SCENARIO_DIR}, {@code LYON_SCENARIO_PREFIX}
	 * (default {@code lyon_drt_area_}), {@code LYON_TRAVEL_TIMES_TSV}, and
	 * {@code LYON_SAMPLE_PCT} (default 1). Throws if required vars are missing.
	 */
	public static LyonEqasimScenarioFixture fromEnv() {
		String scenarioDir = requireEnv("LYON_SCENARIO_DIR");
		String prefix = System.getenv().getOrDefault("LYON_SCENARIO_PREFIX", "lyon_drt_area_");
		String travelTimes = requireEnv("LYON_TRAVEL_TIMES_TSV");
		int samplePct = Integer.parseInt(System.getenv().getOrDefault("LYON_SAMPLE_PCT", "1"));
		return new LyonEqasimScenarioFixture(samplePct, scenarioDir, prefix, travelTimes);
	}

	private static String requireEnv(String name) {
		String value = System.getenv(name);
		if (value == null || value.isEmpty()) {
			throw new IllegalStateException("Required environment variable not set: " + name);
		}
		return value;
	}

	@Override
	public String getName() {
		return "lyon-" + samplePct + "pct";
	}

	@Override
	public Config createConfig(Path outputDir) throws IOException {
		Files.createDirectories(outputDir);

		String configPath = Path.of(scenarioDir).resolve(prefix + "config.xml").toString();
		log.info("=== Lyon eqasim DRT demand extraction ===");
		log.info("  Sample:        {}%", samplePct);
		log.info("  Cut config:    {}", configPath);
		log.info("  Travel times:  {}", travelTimesPath);
		log.info("  Output:        {}", outputDir);

		Config config = ConfigUtils.loadConfig(configPath,
				new ExMasConfigGroup(),
				new MultiModeDrtConfigGroup(),
				new DvrpConfigGroup(),
				new EqasimConfigGroup(),
				new org.eqasim.core.components.raptor.EqasimRaptorConfigGroup(),
				new org.eqasim.core.simulation.termination.EqasimTerminationConfigGroup(),
				new DiscreteModeChoiceConfigGroup());

		overrideForDemandExtraction(config, outputDir);
		configureDrtEstimator(config);
		applyExMasDefaults(config);

		DemandExtractionConfigValidator.prepareConfigForDemandExtraction(config);
		return config;
	}

	private void overrideForDemandExtraction(Config config, Path outputDir) {
		config.vspExperimental().setVspDefaultsCheckingLevel(
				VspExperimentalConfigGroup.VspDefaultsCheckingLevel.info);

		config.controller().setOutputDirectory(outputDir.toString());
		// overwriteExistingFiles (not deleteDirectoryIfExists) because exec:java runs
		// in Maven's JVM and log4j2 opens the MATSim logfile.log early; Windows won't
		// allow deleting an open file, causing Guice injection to fail at startup.
		config.controller().setOverwriteFileSetting(
				OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles);
		config.controller().setRunId("lyon-drt-" + samplePct + "pct-eqasim-exmas");
		config.controller().setLastIteration(0);
		config.controller().setWriteEventsInterval(0);
		config.controller().setWritePlansInterval(0);
		config.controller().setRoutingAlgorithmType(
				ControllerConfigGroup.RoutingAlgorithmType.SpeedyALT);

		double sampleFactor = samplePct / 100.0;
		config.qsim().setFlowCapFactor(sampleFactor);
		config.qsim().setStorageCapFactor(sampleFactor);
		config.qsim().setMainModes(java.util.List.of("car"));
		config.qsim().setVehiclesSource(QSimConfigGroup.VehiclesSource.fromVehiclesData);
		config.qsim().setUsePersonIdForMissingVehicleId(true);

		// Cut config runs full-sim tour-level choice; demand-extraction needs
		// trip-level scoring with no filtering.
		DiscreteModeChoiceConfigGroup dmc = ConfigUtils.addOrGetModule(config,
				DiscreteModeChoiceConfigGroup.class);
		dmc.setModelType(ModelModule.ModelType.Trip);
		dmc.setTripEstimator(EqasimModeChoiceModule.UTILITY_ESTIMATOR_NAME);
		dmc.setSelector("Maximum");
		dmc.setTripConstraints(Collections.emptySet());
	}

	private void configureDrtEstimator(Config config) {
		EqasimConfigGroup eqasim = ConfigUtils.addOrGetModule(config, EqasimConfigGroup.class);
		eqasim.setEstimator("drt", EqasimModeChoiceModule.DRT_ESTIMATOR_NAME);
		eqasim.setCostModel("drt", EqasimModeChoiceModule.ZERO_COST_MODEL_NAME);
		// IDFModeChoiceModule unconditionally installs IDFMotorcycleUtilityEstimator
		// whose MotorcyclePredictor depends on @Named("motorcycle") CostModel.
		eqasim.setCostModel("motorcycle", EqasimModeChoiceModule.ZERO_COST_MODEL_NAME);

		// IDFModeChoiceModule.provideModeChoiceParameters loads
		// IDFModeParameters.buildDefault() first, then overlays this YAML —
		// only drt.* need to be defined in the file.
		URL drtParamsUrl = LyonEqasimScenarioFixture.class.getClassLoader()
				.getResource("eqasim-drt-mode-parameters-idf.yml");
		if (drtParamsUrl == null) {
			throw new IllegalStateException(
					"eqasim-drt-mode-parameters-idf.yml not found on classpath");
		}
		eqasim.setModeParametersPath(drtParamsUrl.getPath());
		log.info("DRT mode parameters (PT-equivalent): {}", drtParamsUrl);

		// eqasim betaTravelTime already includes opportunity cost
		config.scoring().setPerforming_utils_hr(0.0);
	}

	public void applyExMasDefaults(Config config) {
		ExMasConfigGroup exMas = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		exMas.setDrtMode("drt");
		exMas.setBaseModes(new HashSet<>(Set.of("car", "pt", "walk", "bike")));
		exMas.setDrtRoutingMode(TransportMode.car);
		exMas.setCommuteFilter(CommuteFilter.COMMUTES_AND_EDUCATION);
		exMas.setHomeActivityType("home");
		exMas.setWorkActivityType("work");
		exMas.setEducationActivityType("education");
		exMas.setMinAge(13);

		exMas.setMinDrtCostPerKm(0.0);
		exMas.setMinMaxDetourFactor(1.0);
		exMas.setMinMaxWaitingTime(0.0);
		exMas.setMinDrtAccessEgressDistance(100.0);

		exMas.setSearchHorizon(3600.0);
		exMas.setMaxDetourFactor(1.3);
		exMas.setMaxAbsoluteDetour(3600);
		exMas.setMaxPoolingDegree(16);

		exMas.setCalcPredecessors(true);
		exMas.setCalcShapleyValues(true);

		// eqasim betaTravelTime already includes opportunity cost
		exMas.setOpportunityCostModel(ExMasConfigGroup.OpportunityCostModel.NONE);
		exMas.setPtOptimizeDepartureTime(true);

		exMas.setAlgorithmProcessCount(-1);
		exMas.setHeuristicsProcessCount(-1);

		// Heuristic pruning (Bavaria 30km baseline). AlgorithmProfile may
		// override these — apply runs after this method.
		exMas.setHeuristicPruningEnabled(true);
		exMas.setPruningDistanceSavingsLogScale(0.15);
		exMas.setPruningDistanceSavingsMax(0.75);
		exMas.setPruningDistanceSavingsMinDegree(2);

		exMas.setMaxSuccessors(50);
		exMas.setDeferExtensionBudgetValidation(true);

		// Post-extension pruning (Pareto-minimal coverage TopK; cascade analysis
		// 2026-04-17). AlgorithmProfile may override.
		exMas.setPruningMode(ExMasConfigGroup.PruningMode.COVERAGE_TOPK);
		exMas.setPruningCoverageK(20);
		exMas.setPruningQualityMetric(ExMasConfigGroup.PruningQualityMetric.ABS_SAVINGS);

		exMas.setPrivateVehicleModes(new HashSet<>(Set.of("car", "bike", "motorcycle")));

		// Drop trips whose original plan mode is car_passenger — the IDF
		// estimator for that mode is ZeroUtilityEstimator (a stub) which makes
		// the baseline score meaningless.
		exMas.setExcludedTripModes(Set.of("car_passenger"));

		exMas.setTripFilterRadiusKm(filter.radiusKm());
		exMas.setTripFilterCenterX(filter.centerX());
		exMas.setTripFilterCenterY(filter.centerY());

		String exclusionShapefile;
		if (filter.exclusionShapefilePath() == null) {
			exclusionShapefile = null;
		} else {
			exclusionShapefile = java.nio.file.Path.of(scenarioDir)
					.resolve(filter.exclusionShapefilePath()).normalize().toString();
			exMas.setTripFilterExclusionShapefilePath(exclusionShapefile);
		}

		exMas.setScoringAdapter("eqasim");

		log.info("Demand extraction: scoringAdapter=eqasim, tripFilter={}km around ({}, {}), exclusionZone={}",
				filter.radiusKm(), filter.centerX(), filter.centerY(), exclusionShapefile);
	}

	@Override
	public Controler createControler(Config config) throws IOException {
		Scenario scenario = ScenarioUtils.createScenario(config);
		ScenarioUtils.loadScenario(scenario);
		log.info("Population: {} agents", scenario.getPopulation().getPersons().size());

		Controler controler = new Controler(scenario);

		// Eqasim DMC base + EqasimModeChoiceModule + IDFModeChoiceModule
		controler.addOverridingModule(new DiscreteModeChoiceModule());
		controler.addOverridingModule(new EqasimModeChoiceModule());
		controler.addOverridingModule(new IDFModeChoiceModule(emptyCommandLine()));
		controler.addOverridingModule(new org.eqasim.core.components.raptor.EqasimRaptorModule());
		controler.addOverridingModule(new org.eqasim.core.simulation.termination.EqasimTerminationModule());

		// Eqasim dependencies the cut config alone doesn't wire
		controler.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				bind(org.eqasim.core.simulation.policies.utility.UtilityPenalty.class)
						.toInstance((mode, person, trip, elements) -> 0.0);
				bind(org.matsim.contribs.discrete_mode_choice.components.utils.home_finder.HomeFinder.class)
						.to(org.eqasim.core.simulation.mode_choice.EqasimHomeFinder.class);
				// IDFModeChoiceModule installs IDFMotorcycleUtilityEstimator which
				// requires MotorcyclePredictor (needs @Named("motorcycle") CostModel) —
				// JIT is disabled so both must be explicit.
				bind(org.eqasim.core.simulation.mode_choice.cost.CostModel.class)
						.annotatedWith(com.google.inject.name.Names.named("motorcycle"))
						.to(org.eqasim.core.simulation.mode_choice.cost.ZeroCostModel.class);
				bind(org.eqasim.core.simulation.mode_choice.utilities.predictors.MotorcyclePredictor.class);
				// EqasimTerminationModule injects these maps — register empty MapBinders
				// so Guice can satisfy the injection (JIT is disabled in MATSim).
				com.google.inject.multibindings.MapBinder.newMapBinder(binder(), String.class,
						org.eqasim.core.simulation.termination.TerminationCriterionCalculator.class);
				com.google.inject.multibindings.MapBinder.newMapBinder(binder(), String.class,
						org.eqasim.core.simulation.termination.TerminationIndicatorSupplier.class);
			}
		});

		// Congested travel times as car TravelTime + time-dependent disutility.
		// Without the explicit disutility binding, MATSim's default randomizing
		// factory collapses SpeedyALT's A* to Dijkstra (zero scoring gradient on
		// every link) and routing becomes ~60× slower — see
		// .project-memory/eqasim-routing-disutility-fix-2026-04-17.md.
		TravelTime offlineTravelTime = loadOfflineTravelTimes(travelTimesPath);
		controler.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				addTravelTimeBinding(TransportMode.car).toInstance(offlineTravelTime);
				addTravelDisutilityFactoryBinding(TransportMode.car).toInstance(
						new org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutilityFactory());
			}
		});

		controler.addOverridingModule(new DemandExtractionModule());
		return controler;
	}

	private static CommandLine emptyCommandLine() {
		try {
			return new CommandLine.Builder(new String[0]).build();
		} catch (CommandLine.ConfigurationException e) {
			throw new IllegalStateException("Empty CommandLine should never fail", e);
		}
	}

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

	@Override
	public void validateOutput(Config config, Path outputDir) throws IOException {
		String runId = config.controller().getRunId();
		Path drtDemandDir = outputDir.resolve("drt_demand");
		Path requestsFile = drtDemandDir.resolve(runId + ".drt_requests.csv");
		Path ridesFile = drtDemandDir.resolve(runId + ".exmas_rides.csv");

		if (!Files.exists(requestsFile)) {
			throw new AssertionError("DRT requests file should exist: " + requestsFile);
		}
		if (!Files.exists(ridesFile)) {
			throw new AssertionError("ExMAS rides file should exist: " + ridesFile);
		}

		long requestLines = Files.lines(requestsFile).count();
		long rideLines = Files.lines(ridesFile).count();
		if (requestLines <= 1) {
			throw new AssertionError("requests CSV should have at least one data row");
		}
		if (rideLines <= 1) {
			throw new AssertionError("rides CSV should have at least one data row");
		}
		log.info("Lyon validation: {} request rows, {} ride rows", requestLines - 1, rideLines - 1);
	}
}
