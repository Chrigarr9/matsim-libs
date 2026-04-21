package org.matsim.contrib.demand_extraction.run;

import java.io.IOException;
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
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scenario.ScenarioUtils;

/**
 * Eqasim-native DRT demand extraction for the Lyon 40-km cut scenario.
 *
 * <p>Loads the synpp-produced cut config directly (which already contains the
 * full IDF eqasim stack: activity params, estimator/cost-model bindings, DMC
 * setup, coord system, vehicles source) and only overrides the fields that
 * differ between a full simulation and a single-iteration demand extraction
 * pass — controller iteration count, sample-rate-scaled flow/storage capacity,
 * DMC model type ({@code EfficientTour} -&gt; {@code Trip}), and the DRT-specific
 * mode-parameter YAML.
 *
 * <p>Mode-choice parameters are the IDF defaults (Hörl &amp; Balac 2021) unchanged.
 * DRT is scored as PT-equivalent via {@code eqasim-drt-mode-parameters-idf.yml}
 * (in src/main/resources) — conservative assumption for a shared-ride service,
 * chosen over car-equivalent in the Bavaria calibration discussion (2026-04-19).
 * DRT fare is zero here — applied in post-processing.
 *
 * <pre>
 * cd matsim-libs/contribs/drt-demand-extraction
 * mvn exec:java -o -Dexec.mainClass="org.matsim.contrib.demand_extraction.run.RunLyonEqasimDemandExtraction" \
 *   -Dexec.args="--sample 10 \
 *                --scenario-dir ../../../matsim_scenarios/eqasim-france/output_lyon_drt_10pct/lyon_drt_area \
 *                --prefix lyon_drt_area_ \
 *                --travel-times ../../../matsim_scenarios/eqasim-france/output_fullregion_10pct/travel_times.tsv \
 *                --output-dir ../../../outputs/lyon-eqasim-demand-extraction-10pct \
 *                --algorithm bamas" \
 *   -Denforcer.skip=true
 * </pre>
 *
 * <p>Use {@code --algorithm exmas} to opt into the frozen reference ExMAS port
 * (under {@code algorithm/exmas/}) instead of the current BAMAS algorithm.
 */
public class RunLyonEqasimDemandExtraction {

	private static final Logger log = LogManager.getLogger(RunLyonEqasimDemandExtraction.class);

	// Trip-filter: 40-km radius around the union centroid of the 3 Ain communes
	// (Loyettes, Saint-Maurice-de-Gourdans, Saint-Jean-de-Niost). Same polygon
	// used by scripts/run_cutter.sh (EPSG:2154 / Lambert-93). Centroid produced
	// by scenario-selection/build_cutter_polygon.py.
	private static final double FILTER_CENTER_X = 870540.4;
	private static final double FILTER_CENTER_Y = 6526302.7;
	private static final double TRIP_FILTER_RADIUS_KM = 40.0;

	// 15-minute bins matching RunExportTravelTimes
	private static final int TRAVEL_TIME_BIN_SIZE = 900;
	private static final int TRAVEL_TIME_END = 36 * 3600;

	public static final class ParsedArgs {
		public final int sample;
		public final String scenarioDir;
		public final String prefix;
		public final String travelTimesPath;
		public final String outputDir;
		public final double searchHorizon;
		public final double maxDetourFactor;
		public final double minDrtCostPerKm;
		public final int pruningCoverageK;
		public final ExMasConfigGroup.Algorithm algorithm;

		ParsedArgs(int sample, String scenarioDir, String prefix, String travelTimesPath,
				String outputDir, double searchHorizon, double maxDetourFactor,
				double minDrtCostPerKm, int pruningCoverageK,
				ExMasConfigGroup.Algorithm algorithm) {
			this.sample = sample;
			this.scenarioDir = scenarioDir;
			this.prefix = prefix;
			this.travelTimesPath = travelTimesPath;
			this.outputDir = outputDir;
			this.searchHorizon = searchHorizon;
			this.maxDetourFactor = maxDetourFactor;
			this.minDrtCostPerKm = minDrtCostPerKm;
			this.pruningCoverageK = pruningCoverageK;
			this.algorithm = algorithm;
		}
	}

	static ParsedArgs parseArgs(String[] args) {
		int sample = -1;
		String scenarioDir = null;
		String prefix = "lyon_drt_area_";
		String travelTimesPath = null;
		String outputDir = null;
		double searchHorizon = Double.NaN;
		double maxDetourFactor = Double.NaN;
		double minDrtCostPerKm = Double.NaN;
		int pruningCoverageK = -1;
		ExMasConfigGroup.Algorithm algorithm = ExMasConfigGroup.Algorithm.BAMAS;

		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--sample" -> sample = Integer.parseInt(args[++i]);
				case "--scenario-dir" -> scenarioDir = args[++i];
				case "--prefix" -> prefix = args[++i];
				case "--travel-times" -> travelTimesPath = args[++i];
				case "--output-dir" -> outputDir = args[++i];
				case "--search-horizon" -> searchHorizon = Double.parseDouble(args[++i]);
				case "--max-detour-factor" -> maxDetourFactor = Double.parseDouble(args[++i]);
				case "--min-drt-cost-per-km" -> minDrtCostPerKm = Double.parseDouble(args[++i]);
				case "--pruning-coverage-k" -> pruningCoverageK = Integer.parseInt(args[++i]);
				case "--algorithm" -> algorithm = ExMasConfigGroup.Algorithm.valueOf(args[++i].toUpperCase());
				default -> log.warn("Unknown argument: {}", args[i]);
			}
		}
		return new ParsedArgs(sample, scenarioDir, prefix, travelTimesPath, outputDir,
				searchHorizon, maxDetourFactor, minDrtCostPerKm, pruningCoverageK, algorithm);
	}

	public static void main(String[] args) throws Exception {
		ParsedArgs p = parseArgs(args);

		if (p.sample < 0 || p.scenarioDir == null || p.travelTimesPath == null) {
			System.err.println("Usage: --sample <N> --scenario-dir <path> [--prefix <s>] "
					+ "--travel-times <path> [--output-dir <path>] "
					+ "[--search-horizon <s>] [--max-detour-factor <f>] "
					+ "[--min-drt-cost-per-km <eur>] [--pruning-coverage-k <int>]");
			System.exit(1);
		}

		String outputDir = p.outputDir;
		if (outputDir == null) {
			outputDir = "../../../outputs/lyon-eqasim-demand-extraction-" + p.sample + "pct";
		}

		String configPath = Path.of(p.scenarioDir).resolve(p.prefix + "config.xml").toString();

		log.info("=== Lyon eqasim DRT demand extraction ===");
		log.info("  Sample:        {}%", p.sample);
		log.info("  Cut config:    {}", configPath);
		log.info("  Travel times:  {}", p.travelTimesPath);
		log.info("  Output:        {}", outputDir);

		Path outDir = Path.of(outputDir);
		Files.createDirectories(outDir);

		Config config = ConfigUtils.loadConfig(configPath,
				new ExMasConfigGroup(),
				new MultiModeDrtConfigGroup(),
				new DvrpConfigGroup(),
				new EqasimConfigGroup(),
				new org.eqasim.core.components.raptor.EqasimRaptorConfigGroup(),
				new org.eqasim.core.simulation.termination.EqasimTerminationConfigGroup(),
				new DiscreteModeChoiceConfigGroup());

		overrideForDemandExtraction(config, outDir, p);
		configureDrtEstimator(config);
		configureExMas(config, p);

		DemandExtractionConfigValidator.prepareConfigForDemandExtraction(config);

		Scenario scenario = ScenarioUtils.createScenario(config);
		ScenarioUtils.loadScenario(scenario);
		log.info("Population: {} agents", scenario.getPopulation().getPersons().size());

		Controler controler = new Controler(scenario);

		// Eqasim DMC base + EqasimModeChoiceModule + IDFModeChoiceModule
		controler.addOverridingModule(new DiscreteModeChoiceModule());
		controler.addOverridingModule(new EqasimModeChoiceModule());
		controler.addOverridingModule(new IDFModeChoiceModule(
				new CommandLine.Builder(new String[0]).build()));
		controler.addOverridingModule(new org.eqasim.core.components.raptor.EqasimRaptorModule());
		controler.addOverridingModule(new org.eqasim.core.simulation.termination.EqasimTerminationModule());

		// Eqasim dependencies that aren't wired by the cut config alone
		controler.addOverridingModule(new org.matsim.core.controler.AbstractModule() {
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
				// EqasimTerminationModule injects these maps — register empty MapBinders so
				// Guice can satisfy the injection (JIT is disabled in MATSim)
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
		TravelTime offlineTravelTime = loadOfflineTravelTimes(p.travelTimesPath);
		controler.addOverridingModule(new org.matsim.core.controler.AbstractModule() {
			@Override
			public void install() {
				addTravelTimeBinding(TransportMode.car).toInstance(offlineTravelTime);
				addTravelDisutilityFactoryBinding(TransportMode.car)
						.toInstance(new org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutilityFactory());
			}
		});

		controler.addOverridingModule(new DemandExtractionModule());

		controler.run();

		log.info("\n=== Lyon eqasim DRT demand extraction complete ===");
		log.info("Output: {}", outDir.toAbsolutePath());
	}

	// -------------------------------------------------------------------------
	// Controller / QSim / DMC overrides — everything else comes from the cut config
	// -------------------------------------------------------------------------

	private static void overrideForDemandExtraction(Config config, Path outputDir, ParsedArgs p) {
		config.vspExperimental().setVspDefaultsCheckingLevel(
				VspExperimentalConfigGroup.VspDefaultsCheckingLevel.info);

		config.controller().setOutputDirectory(outputDir.toString());
		config.controller().setOverwriteFileSetting(
				OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
		config.controller().setRunId("lyon-drt-" + p.sample + "pct-eqasim-exmas");
		config.controller().setLastIteration(0);
		config.controller().setWriteEventsInterval(0);
		config.controller().setWritePlansInterval(0);
		config.controller().setRoutingAlgorithmType(
				ControllerConfigGroup.RoutingAlgorithmType.SpeedyALT);

		double sampleFactor = p.sample / 100.0;
		config.qsim().setFlowCapFactor(sampleFactor);
		config.qsim().setStorageCapFactor(sampleFactor);
		config.qsim().setMainModes(java.util.List.of("car"));
		config.qsim().setVehiclesSource(QSimConfigGroup.VehiclesSource.fromVehiclesData);
		config.qsim().setUsePersonIdForMissingVehicleId(true);

		// The cut config runs full-simulation tour-level choice; for one-shot
		// demand extraction we need trip-level scoring with no filtering.
		DiscreteModeChoiceConfigGroup dmc = ConfigUtils.addOrGetModule(config,
				DiscreteModeChoiceConfigGroup.class);
		dmc.setModelType(ModelModule.ModelType.Trip);
		dmc.setTripEstimator(EqasimModeChoiceModule.UTILITY_ESTIMATOR_NAME);
		dmc.setSelector("Maximum");
		dmc.setTripConstraints(Collections.emptySet());
	}

	// -------------------------------------------------------------------------
	// DRT estimator + PT-equivalent mode params (the only thing the cut config
	// doesn't already define, because synpp doesn't know about DRT).
	// -------------------------------------------------------------------------

	private static void configureDrtEstimator(Config config) {
		EqasimConfigGroup eqasim = ConfigUtils.addOrGetModule(config, EqasimConfigGroup.class);
		eqasim.setEstimator("drt", EqasimModeChoiceModule.DRT_ESTIMATOR_NAME);
		eqasim.setCostModel("drt", EqasimModeChoiceModule.ZERO_COST_MODEL_NAME);
		// IDFModeChoiceModule unconditionally installs IDFMotorcycleUtilityEstimator
		// whose MotorcyclePredictor depends on @Named("motorcycle") CostModel.
		eqasim.setCostModel("motorcycle", EqasimModeChoiceModule.ZERO_COST_MODEL_NAME);

		// IDFModeChoiceModule.provideModeChoiceParameters loads
		// IDFModeParameters.buildDefault() first, then overlays this YAML —
		// so only drt.* need to be defined in the file.
		String drtParamsPath = RunLyonEqasimDemandExtraction.class.getClassLoader()
				.getResource("eqasim-drt-mode-parameters-idf.yml").getPath();
		eqasim.setModeParametersPath(drtParamsPath);
		log.info("DRT mode parameters (PT-equivalent): {}", drtParamsPath);

		// eqasim betaTravelTime already includes opportunity cost
		config.scoring().setPerforming_utils_hr(0.0);
	}

	// -------------------------------------------------------------------------
	// Offline travel times — identical to the Bavaria runner
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
	// Demand extraction configuration
	// -------------------------------------------------------------------------

	private static void configureExMas(Config config, ParsedArgs p) {
		ExMasConfigGroup exMas = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		exMas.setAlgorithm(p.algorithm);
		log.info("Stage-1 algorithm: {}", p.algorithm);
		exMas.setDrtMode("drt");
		exMas.setBaseModes(new HashSet<>(Set.of("car", "pt", "walk", "bike")));
		exMas.setDrtRoutingMode(TransportMode.car);
		exMas.setCommuteFilter(CommuteFilter.COMMUTES_AND_EDUCATION);
		exMas.setHomeActivityType("home");
		exMas.setWorkActivityType("work");
		exMas.setEducationActivityType("education");
		exMas.setMinAge(13);

		// DRT service-quality floors for budget calculation (ported from 30km)
		exMas.setMinDrtCostPerKm(0.0);
		exMas.setMinMaxDetourFactor(1.0);
		exMas.setMinMaxWaitingTime(0.0);
		exMas.setMinDrtAccessEgressDistance(100.0);

		// ExMAS algorithm (same baselines as Bavaria 30km)
		exMas.setSearchHorizon(3600.0);
		exMas.setMaxDetourFactor(1.5);
		exMas.setMaxAbsoluteDetour(3600);
		exMas.setMaxPoolingDegree(16);

		exMas.setCalcPredecessors(true);
		exMas.setCalcShapleyValues(true);

		// eqasim betaTravelTime already includes opportunity cost
		exMas.setOpportunityCostModel(ExMasConfigGroup.OpportunityCostModel.NONE);
		exMas.setPtOptimizeDepartureTime(true);

		exMas.setAlgorithmProcessCount(-1);
		exMas.setHeuristicsProcessCount(-1);

		// Heuristic pruning (ported from Bavaria 30km)
		exMas.setHeuristicPruningEnabled(true);
		exMas.setPruningDistanceSavingsLogScale(0.15);
		exMas.setPruningDistanceSavingsMax(0.75);
		exMas.setPruningDistanceSavingsMinDegree(2);

		exMas.setMaxSuccessors(50);
		exMas.setDeferExtensionBudgetValidation(true);

		// Post-extension pruning: coverage-aware per-request top-K (K=20 is the
		// library default, Pareto-minimal per the 2026-04-17 cascade simulation
		// — see .project-memory/pruning-quality-analysis-2026-04-17.md).
		// Legacy interDegreeKeepFraction is only honoured under
		// pruningMode=RATIO_THRESHOLD, which we don't use.
		exMas.setPruningMode(ExMasConfigGroup.PruningMode.COVERAGE_TOPK);
		exMas.setPruningCoverageK(20);
		exMas.setPruningQualityMetric(ExMasConfigGroup.PruningQualityMetric.ABS_SAVINGS);

		// CLI sweep overrides (applied after defaults so the CLI always wins)
		if (!Double.isNaN(p.searchHorizon)) {
			log.info("  Override: searchHorizon = {}", p.searchHorizon);
			exMas.setSearchHorizon(p.searchHorizon);
		}
		if (!Double.isNaN(p.maxDetourFactor)) {
			log.info("  Override: maxDetourFactor = {}", p.maxDetourFactor);
			exMas.setMaxDetourFactor(p.maxDetourFactor);
		}
		if (!Double.isNaN(p.minDrtCostPerKm)) {
			log.info("  Override: minDrtCostPerKm = {}", p.minDrtCostPerKm);
			exMas.setMinDrtCostPerKm(p.minDrtCostPerKm);
		}
		if (p.pruningCoverageK > 0) {
			log.info("  Override: pruningCoverageK = {}", p.pruningCoverageK);
			exMas.setPruningCoverageK(p.pruningCoverageK);
		}

		exMas.setPrivateVehicleModes(new HashSet<>(Set.of("car", "bike", "motorcycle")));

		// Drop trips whose original plan mode is car_passenger — the IDF
		// estimator for that mode is ZeroUtilityEstimator (a stub), which makes
		// the baseline score meaningless and produces spurious DRT demand
		// whenever the DRT utility clears 0. See research entry
		// .project-memory/eqasim-idf-calibration-methodology-2026-04-20.md.
		exMas.setExcludedTripModes(Set.of("car_passenger"));

		exMas.setTripFilterRadiusKm(TRIP_FILTER_RADIUS_KM);
		exMas.setTripFilterCenterX(FILTER_CENTER_X);
		exMas.setTripFilterCenterY(FILTER_CENTER_Y);

		exMas.setScoringAdapter("eqasim");

		log.info("Demand extraction: scoringAdapter=eqasim, tripFilter={}km around ({}, {})",
				TRIP_FILTER_RADIUS_KM, FILTER_CENTER_X, FILTER_CENTER_Y);
	}
}
