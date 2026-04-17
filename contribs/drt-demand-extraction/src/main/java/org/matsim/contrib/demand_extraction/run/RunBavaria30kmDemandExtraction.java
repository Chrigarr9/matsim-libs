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
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ControllerConfigGroup;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.config.groups.ReplanningConfigGroup.StrategySettings;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup.ActivityParams;
import org.matsim.core.config.groups.ScoringConfigGroup.ModeParams;
import org.matsim.core.config.groups.VspExperimentalConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.replanning.annealing.ReplanningAnnealerConfigGroup;
import org.matsim.core.replanning.modules.SubtourModeChoice;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.gis.GeoFileReader;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;
import org.matsim.vehicles.VehicleType;

import com.google.inject.Singleton;

import playground.vsp.scoring.IncomeDependentUtilityOfMoneyPersonScoringParameters;

/**
 * Run class for extracting DRT demand from the Bavaria 30km eqasim scenario.
 *
 * <p>Uses Bavaria 30km infrastructure (network, transit, facilities) with Kelheim v3.0
 * calibrated scoring parameters and income-dependent marginal utility of money.
 *
 * <p><b>Prerequisites:</b>
 * <ul>
 *   <li>Bavaria 30km scenario output in {@code --scenario-path}</li>
 *   <li>Population XML (raw eqasim or pre-upsampled) via {@code --population}</li>
 * </ul>
 *
 * <p><b>Usage:</b>
 * <pre>
 * # 1% sample, free-flow travel times
 * mvn exec:java -Dexec.mainClass="org.matsim.contrib.demand_extraction.run.RunBavaria30kmDemandExtraction" \
 *   -Dexec.args="--scenario-path ../matsim_scenarios/bavaria/output/kelheim_30km_100pct \
 *                --population path/to/population.xml.gz --sample 1"
 *
 * # With warm-up iterations and DMC annealing
 * mvn exec:java -Dexec.mainClass="org.matsim.contrib.demand_extraction.run.RunBavaria30kmDemandExtraction" \
 *   -Dexec.args="--scenario-path ../matsim_scenarios/bavaria/output/kelheim_30km_100pct \
 *                --population path/to/upsampled.xml.gz --sample 1 --iterations 50"
 * </pre>
 */
public class RunBavaria30kmDemandExtraction {

	private static final Logger log = LogManager.getLogger(RunBavaria30kmDemandExtraction.class);
	private static final String FILE_PREFIX = "kelheim_30km_100pct_";

	public static void main(String[] args) throws IOException {
		// Parse CLI arguments
		String scenarioPath = null;
		String populationPath = null;
		int sampleSize = 1;
		int iterations = 0;
		double dmcStartRate = 0.2;
		double dmcEndRate = 0.05;
		String outputDir = null;
		boolean deterministic = false;
		Integer algorithmProcessCountArg = null;
		Integer heuristicsProcessCountArg = null;
		boolean cleanup = true;
		double filterRadius = 0.0; // km, 0 = disabled (agent-level: any activity inside)
		double filterCenterX = 709000.0; // Kelheim center EPSG:25832
		double filterCenterY = 5423000.0;
		String filterMunicipality = null;
		String shapesPath = null;
		double tripFilterRadiusKm = 0.0; // km, 0 = disabled (trip-level: O+D both inside)
		String travelTimesFile = null; // path to pre-computed travel_times.tsv
		boolean noPruning = false; // disable all pruning (for baseline comparison)
		boolean noPredecessors = false; // disable predecessor/successor + Shapley calculation
		int maxDegree = 16;
		double interDegreeKeep = 0.10;
		Integer networkTimeBinSize = null; // diagnostic override for MatsimNetworkCache binning

		// Pruner selection — defaults match ExMasConfigGroup (COVERAGE_TOPK, K=20, ABS_SAVINGS).
		String pruningModeArg = null;            // null => config default (COVERAGE_TOPK)
		Integer pruningCoverageKArg = null;      // null => config default (20)
		String pruningQualityMetricArg = null;   // null => config default (ABS_SAVINGS)

		// Sweep knobs — applied as overrides after configureForDemandExtraction when NaN-guarded
		double searchHorizonOverride = Double.NaN;
		double maxDetourFactorOverride = Double.NaN;
		double minDrtCostPerKmOverride = Double.NaN;
		double pairKeepTopFractionOverride = Double.NaN;

		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--scenario-path" -> scenarioPath = args[++i];
				case "--population" -> populationPath = args[++i];
				case "--sample" -> sampleSize = Integer.parseInt(args[++i]);
				case "--iterations" -> iterations = Integer.parseInt(args[++i]);
				case "--dmc-start-rate" -> dmcStartRate = Double.parseDouble(args[++i]);
				case "--dmc-end-rate" -> dmcEndRate = Double.parseDouble(args[++i]);
				case "--output-dir" -> outputDir = args[++i];
				case "--deterministic" -> deterministic = true;
				case "--algorithm-process-count" -> algorithmProcessCountArg = Integer.parseInt(args[++i]);
				case "--heuristics-process-count" -> heuristicsProcessCountArg = Integer.parseInt(args[++i]);
				case "--no-cleanup" -> cleanup = false;
				case "--filter-radius" -> filterRadius = Double.parseDouble(args[++i]);
				case "--filter-center" -> {
					String[] parts = args[++i].split(",");
					filterCenterX = Double.parseDouble(parts[0]);
					filterCenterY = Double.parseDouble(parts[1]);
				}
				case "--filter-municipality" -> filterMunicipality = args[++i];
				case "--shapes" -> shapesPath = args[++i];
				case "--trip-filter-radius" -> tripFilterRadiusKm = Double.parseDouble(args[++i]);
				case "--travel-times" -> travelTimesFile = args[++i];
				case "--no-pruning" -> noPruning = true;
				case "--no-predecessors" -> noPredecessors = true;
				case "--max-degree" -> maxDegree = Integer.parseInt(args[++i]);
				case "--inter-degree-keep", "--inter-degree-keep-fraction"
						-> interDegreeKeep = Double.parseDouble(args[++i]);
				case "--pruning-mode" -> pruningModeArg = args[++i];
				case "--pruning-coverage-k" -> pruningCoverageKArg = Integer.parseInt(args[++i]);
				case "--pruning-quality-metric" -> pruningQualityMetricArg = args[++i];
				case "--search-horizon" -> searchHorizonOverride = Double.parseDouble(args[++i]);
				case "--max-detour-factor" -> maxDetourFactorOverride = Double.parseDouble(args[++i]);
				case "--min-drt-cost-per-km" -> minDrtCostPerKmOverride = Double.parseDouble(args[++i]);
				case "--pair-keep-top-fraction" -> pairKeepTopFractionOverride = Double.parseDouble(args[++i]);
				case "--network-time-bin-size" -> networkTimeBinSize = Integer.parseInt(args[++i]);
				default -> log.warn("Unknown argument: {}", args[i]);
			}
		}

		if (scenarioPath == null || populationPath == null) {
			System.err.println("Usage: RunBavaria30kmDemandExtraction "
					+ "--scenario-path <path> --population <path> "
					+ "[--sample <1|10|25|100>] [--iterations <N>] "
					+ "[--dmc-start-rate <0.0-1.0>] [--dmc-end-rate <0.0-1.0>] "
					+ "[--output-dir <path>] [--deterministic] "
					+ "[--filter-radius <km>] [--filter-center <x,y>] "
					+ "[--filter-municipality <name>] [--shapes <path>]");
			System.exit(1);
		}

		// Resolve municipality name to centroid coordinates
		if (filterMunicipality != null) {
			if (shapesPath == null) {
				System.err.println("--filter-municipality requires --shapes <path-to-VG250.gpkg>");
				System.exit(1);
			}
			double[] center = resolveMunicipalityCentroid(filterMunicipality, shapesPath);
			filterCenterX = center[0];
			filterCenterY = center[1];
		}

		log.info("=== Bavaria 30km DRT Demand Extraction ===");
		log.info("Scenario path: {}", scenarioPath);
		log.info("Population: {}", populationPath);
		log.info("Sample size: {}%", sampleSize);
		log.info("Iterations: {}", iterations);
		if (iterations > 0) {
			log.info("DMC annealing: {}% -> {}%", dmcStartRate * 100, dmcEndRate * 100);
		}
		if (filterRadius > 0) {
			log.info("Radius filter: {}km around ({}, {})", filterRadius, filterCenterX, filterCenterY);
		}

		// Resolve output directory
		Path outDir;
		if (outputDir != null) {
			outDir = Path.of(outputDir);
		} else {
			outDir = Path.of(scenarioPath).getParent()
					.resolve("demand-extraction-" + sampleSize + "pct");
		}
		Files.createDirectories(outDir);

		// Build config, create scenario, run
		Config config = buildConfig(scenarioPath, populationPath, sampleSize, iterations,
				dmcStartRate, dmcEndRate, deterministic);

		int algorithmProcessCount = algorithmProcessCountArg != null
				? algorithmProcessCountArg : (deterministic ? 1 : -1);
		int heuristicsProcessCount = heuristicsProcessCountArg != null
				? heuristicsProcessCountArg : (deterministic ? 1 : -1);
		if (deterministic) {
			config.global().setNumberOfThreads(1);
			config.qsim().setNumberOfThreads(1);
		}

		configureForDemandExtraction(config, outDir, sampleSize, iterations,
				algorithmProcessCount, heuristicsProcessCount, deterministic, noPruning,
				noPredecessors, maxDegree, interDegreeKeep);

		// Pruner CLI overrides applied after configureForDemandExtraction (which registers
		// ExMasConfigGroup with the defaults). Unset flags fall through to config defaults.
		if (pruningModeArg != null || pruningCoverageKArg != null || pruningQualityMetricArg != null) {
			ExMasConfigGroup ex = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
			if (pruningModeArg != null) {
				ex.setPruningMode(ExMasConfigGroup.PruningMode.valueOf(pruningModeArg.toUpperCase()));
				log.info("  Pruner override: pruningMode={}", ex.getPruningMode());
			}
			if (pruningCoverageKArg != null) {
				ex.setPruningCoverageK(pruningCoverageKArg);
				log.info("  Pruner override: pruningCoverageK={}", ex.getPruningCoverageK());
			}
			if (pruningQualityMetricArg != null) {
				ex.setPruningQualityMetric(ExMasConfigGroup.PruningQualityMetric.valueOf(pruningQualityMetricArg.toUpperCase()));
				log.info("  Pruner override: pruningQualityMetric={}", ex.getPruningQualityMetric());
			}
		}

		String runId = config.controller().getRunId();

		// Trip-level spatial filter: uses resolved filter center (from --filter-municipality or --filter-center)
		if (tripFilterRadiusKm > 0) {
			ExMasConfigGroup exMasConfig = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
			exMasConfig.setTripFilterRadiusKm(tripFilterRadiusKm);
			exMasConfig.setTripFilterCenterX(filterCenterX);
			exMasConfig.setTripFilterCenterY(filterCenterY);
			log.info("Trip-level spatial filter: {}km around ({}, {})",
					tripFilterRadiusKm, filterCenterX, filterCenterY);
		}

		// Diagnostic override for MatsimNetworkCache time-bin size.
		// Set to Integer.MAX_VALUE to collapse all routing queries into a single (time-independent) bin.
		if (networkTimeBinSize != null) {
			ExMasConfigGroup exMasConfig = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
			exMasConfig.setNetworkTimeBinSize(networkTimeBinSize);
			log.info("Network time-bin size overridden: {} seconds", networkTimeBinSize);
		}

		// Sweep-flag overrides applied after configureForDemandExtraction. NaN = not set.
		{
			ExMasConfigGroup exMasConfig = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
			if (!Double.isNaN(searchHorizonOverride)) {
				log.info("  Override: searchHorizon = {}", searchHorizonOverride);
				exMasConfig.setSearchHorizon(searchHorizonOverride);
			}
			if (!Double.isNaN(maxDetourFactorOverride)) {
				log.info("  Override: maxDetourFactor = {}", maxDetourFactorOverride);
				exMasConfig.setMaxDetourFactor(maxDetourFactorOverride);
			}
			if (!Double.isNaN(minDrtCostPerKmOverride)) {
				log.info("  Override: minDrtCostPerKm = {}", minDrtCostPerKmOverride);
				exMasConfig.setMinDrtCostPerKm(minDrtCostPerKmOverride);
			}
			if (!Double.isNaN(pairKeepTopFractionOverride)) {
				log.info("  Override: pairKeepTopFraction = {}", pairKeepTopFractionOverride);
				exMasConfig.setPairKeepTopFraction(pairKeepTopFractionOverride);
			}
		}

		DemandExtractionConfigValidator.prepareConfigForDemandExtraction(config);

		// Use plain Scenario + Controler (NOT DrtControlerCreator).
		// DemandExtractionModule only reads DRT config (fare params) — it does NOT require
		// the DVRP/DRT simulation modules (MultiModeDrtModule, DvrpModule).
		// This avoids the expensive DVRP TT matrix computation on the large Bavaria network.
		Scenario scenario = ScenarioUtils.createScenario(config);
		ScenarioUtils.loadScenario(scenario);

		// Filter unwanted agents
		int originalSize = scenario.getPopulation().getPersons().size();
		filterUnwantedAgents(scenario);
		int afterFilter = scenario.getPopulation().getPersons().size();
		log.info("Filtered population: {} -> {} agents ({} removed)",
				originalSize, afterFilter, originalSize - afterFilter);

		// Spatial radius filter: keep only agents with any activity within radius of center
		if (filterRadius > 0) {
			filterByRadius(scenario, filterCenterX, filterCenterY, filterRadius * 1000.0);
		}

		// Downsample population to match --sample percentage.
		// MATSim does NOT auto-sample at import — we do it here.
		downsamplePopulation(scenario, sampleSize);

		// Create plain controller — no DRT simulation, just MATSim + demand extraction
		Controler controler = new Controler(scenario);
		controler.addOverridingModule(new DemandExtractionModule());

		// Load pre-computed travel times if provided (from RunBavariaBaseSimulation)
		final String ttFile = travelTimesFile;
		if (ttFile != null) {
			log.info("Loading pre-computed travel times from: {}", ttFile);
			// Match the discretizer to what was exported (36h endTime, 900s bins)
			int endTime = 36 * 3600;
			int binSize = 900;
			var timeDiscretizer = new org.matsim.contrib.common.timeprofile.TimeDiscretizer(endTime, binSize);
			try {
				java.net.URL ttUrl = java.nio.file.Path.of(ttFile).toUri().toURL();
				double[][] matrix = org.matsim.contrib.dvrp.trafficmonitoring.DvrpOfflineTravelTimes
						.loadLinkTravelTimes(timeDiscretizer, ttUrl, "\t");
				var baseTt = org.matsim.contrib.dvrp.trafficmonitoring.DvrpOfflineTravelTimes
						.asTravelTime(timeDiscretizer, matrix);
				// Wrap to clamp out-of-range times (activities beyond 36h use last bin)
				org.matsim.core.router.util.TravelTime clampedTt = (link, time, person, vehicle) ->
						baseTt.getLinkTravelTime(link, Math.min(time, endTime), person, vehicle);
				controler.addOverridingModule(new org.matsim.core.controler.AbstractModule() {
					@Override
					public void install() {
						addTravelTimeBinding(org.matsim.api.core.v01.TransportMode.car).toInstance(clampedTt);
					}
				});
				log.info("Bound pre-computed travel times ({} time bins, clamped to {}h)",
						timeDiscretizer.getIntervalCount(), endTime / 3600);
			} catch (java.net.MalformedURLException e) {
				throw new RuntimeException("Invalid travel times path: " + ttFile, e);
			}
		}

		// Enable income-dependent scoring if population has income attributes
		// (requires upsampled population with AttributeAdapter-derived income values)
		boolean hasIncomeAttributes = scenario.getPopulation().getPersons().values().stream()
				.anyMatch(p -> org.matsim.core.population.PersonUtils.getIncome(p) != null);
		if (hasIncomeAttributes) {
			log.info("Population has income attributes — enabling income-dependent marginalUtilityOfMoney");
			controler.addOverridingModule(new org.matsim.core.controler.AbstractModule() {
				@Override
				public void install() {
					bind(ScoringParametersForPerson.class)
							.to(IncomeDependentUtilityOfMoneyPersonScoringParameters.class)
							.in(Singleton.class);
				}
			});
		} else {
			log.warn("Population has NO income attributes — using uniform marginalUtilityOfMoney={}",
					config.scoring().getMarginalUtilityOfMoney());
			log.warn("For income-dependent scoring, use an upsampled population (RunPopulationUpsampling)");
		}

		controler.run();

		if (cleanup) {
			RunKelheimDemandExtraction.cleanupOutputDirectory(outDir, runId);
		}

		log.info("\n=== Demand Extraction Complete ===");
		log.info("Output directory: {}", outDir.toAbsolutePath());
		log.info("Demand extraction files in: {}/drt_demand/", outDir.toAbsolutePath());
		log.info("===================================\n");
	}

	// -------------------------------------------------------------------------
	// Config building
	// -------------------------------------------------------------------------

	/**
	 * Build a MATSim Config programmatically using Bavaria 30km infrastructure
	 * and Kelheim v3.0 calibrated scoring parameters.
	 */
	private static Config buildConfig(String scenarioPath, String populationPath,
			int sampleSize, int iterations, double dmcStartRate, double dmcEndRate,
			boolean deterministic) {

		Config config = ConfigUtils.createConfig(
				new ExMasConfigGroup(),
				new MultiModeDrtConfigGroup(),
				new DvrpConfigGroup());

		// --- Input files from Bavaria 30km scenario ---
		Path base = Path.of(scenarioPath);
		config.network().setInputFile(base.resolve(FILE_PREFIX + "network.xml.gz").toString());
		config.transit().setTransitScheduleFile(base.resolve(FILE_PREFIX + "transit_schedule.xml.gz").toString());
		config.transit().setVehiclesFile(base.resolve(FILE_PREFIX + "transit_vehicles.xml.gz").toString());
		config.vehicles().setVehiclesFile(base.resolve(FILE_PREFIX + "vehicles.xml.gz").toString());
		config.facilities().setInputFile(base.resolve(FILE_PREFIX + "facilities.xml.gz").toString());
		config.plans().setInputFile(populationPath);
		config.transit().setUseTransit(true);

		// --- Global settings ---
		// Eqasim uses "Atlantis" as CRS (coordinates are already projected to EPSG:25832).
		// Setting the config CRS to "Atlantis" prevents MATSim from attempting a transformation.
		config.global().setCoordinateSystem("Atlantis");
		config.global().setNumberOfThreads(6);

		// --- QSim ---
		double sampleFactor = sampleSize / 100.0;
		config.qsim().setFlowCapFactor(sampleFactor);
		config.qsim().setStorageCapFactor(sampleFactor);
		config.qsim().setMainModes(java.util.List.of("car"));
		config.qsim().setNumberOfThreads(8);
		config.qsim().setStartTime(0);
		config.qsim().setEndTime(36 * 3600);
		config.qsim().setTrafficDynamics(QSimConfigGroup.TrafficDynamics.kinematicWaves);
		// Eqasim vehicle types use "default_car" naming, not "car".
		// Use defaultVehicle to avoid type mismatch (demand extraction doesn't need real vehicles).
		// Eqasim populations embed vehicle IDs in person attributes (e.g. "368346:car").
		// Use fromVehiclesData to load actual vehicle definitions from vehicles.xml.gz.
		config.qsim().setVehiclesSource(QSimConfigGroup.VehiclesSource.fromVehiclesData);
		// Allow agents to use any vehicle (avoids "could not find vehicle" errors when
		// household vehicle IDs don't match person IDs after downsampling)
		config.qsim().setUsePersonIdForMissingVehicleId(true);

		// --- Routing ---
		config.routing().setNetworkModes(java.util.List.of("car"));

		// --- Kelheim v3.0 calibrated scoring ---
		applyKelheimScoring(config);

		// --- Activity params for eqasim activity types ---
		registerEqasimActivities(config);

		// --- Replanning (only if iterations > 0) ---
		if (iterations > 0) {
			configureReplanning(config, iterations, dmcStartRate, dmcEndRate);
		}

		log.info("Config built: {} iterations, {}% sample, Kelheim v3.0 scoring",
				iterations, sampleSize);
		return config;
	}

	// -------------------------------------------------------------------------
	// Kelheim v3.0 calibrated scoring
	// -------------------------------------------------------------------------

	/**
	 * Apply Kelheim v3.0 calibrated scoring parameters.
	 * Values from kelheim-v3.0-25pct.kexi.config.xml.
	 * These were calibrated WITH income-dependent marginalUtilityOfMoney active.
	 */
	private static void applyKelheimScoring(Config config) {
		ScoringConfigGroup scoring = config.scoring();

		scoring.setPerforming_utils_hr(6.0);
		scoring.setMarginalUtilityOfMoney(1.0);
		scoring.setLateArrival_utils_hr(-18.0);
		scoring.setUtilityOfLineSwitch(-1.0);

		// PT waiting disutility
		ScoringConfigGroup.ScoringParameterSet params = scoring.getOrCreateScoringParameters(null);
		params.setMarginalUtlOfWaitingPt_utils_hr(-1.6);

		// --- Mode params (from kelheim-v3.0-25pct.kexi.config.xml) ---

		// car: ASC=0.109, dailyMonetary=-5.3, monetaryDistRate=-2.0E-4
		ModeParams car = new ModeParams(TransportMode.car);
		car.setConstant(0.10908902922956654);
		car.setMarginalUtilityOfTraveling(0.0);
		car.setMarginalUtilityOfDistance(0.0);
		car.setMonetaryDistanceRate(-2.0E-4);
		car.setDailyMonetaryConstant(-5.3);
		scoring.addModeParams(car);

		// ride: ASC=-0.449, margUtilTravel=-12.0, monetaryDistRate=-2.0E-4
		ModeParams ride = new ModeParams(TransportMode.ride);
		ride.setConstant(-0.44874536876610344);
		ride.setMarginalUtilityOfTraveling(-12.0);
		ride.setMarginalUtilityOfDistance(0.0);
		ride.setMonetaryDistanceRate(-2.0E-4);
		scoring.addModeParams(ride);

		// pt: ASC=0.045
		ModeParams pt = new ModeParams(TransportMode.pt);
		pt.setConstant(0.0449751479497542);
		pt.setMarginalUtilityOfTraveling(0.0);
		pt.setMarginalUtilityOfDistance(0.0);
		pt.setMonetaryDistanceRate(0.0);
		scoring.addModeParams(pt);

		// bike: ASC=-0.906, margUtilTravel=-3.0
		ModeParams bike = new ModeParams(TransportMode.bike);
		bike.setConstant(-0.9059637590522914);
		bike.setMarginalUtilityOfTraveling(-3.0);
		bike.setMarginalUtilityOfDistance(0.0);
		bike.setMonetaryDistanceRate(0.0);
		scoring.addModeParams(bike);

		// walk: all zero
		ModeParams walk = new ModeParams(TransportMode.walk);
		walk.setConstant(0.0);
		walk.setMarginalUtilityOfTraveling(0.0);
		walk.setMarginalUtilityOfDistance(0.0);
		walk.setMonetaryDistanceRate(0.0);
		scoring.addModeParams(walk);

		// bicycle: eqasim uses "bicycle" instead of "bike" — alias with same params
		ModeParams bicycle = new ModeParams("bicycle");
		bicycle.setConstant(bike.getConstant());
		bicycle.setMarginalUtilityOfTraveling(bike.getMarginalUtilityOfTraveling());
		bicycle.setMarginalUtilityOfDistance(bike.getMarginalUtilityOfDistance());
		bicycle.setMonetaryDistanceRate(bike.getMonetaryDistanceRate());
		scoring.addModeParams(bicycle);

		// car_passenger: eqasim mode, use ride params as proxy
		ModeParams carPassenger = new ModeParams("car_passenger");
		carPassenger.setConstant(ride.getConstant());
		carPassenger.setMarginalUtilityOfTraveling(ride.getMarginalUtilityOfTraveling());
		carPassenger.setMarginalUtilityOfDistance(ride.getMarginalUtilityOfDistance());
		carPassenger.setMonetaryDistanceRate(ride.getMonetaryDistanceRate());
		scoring.addModeParams(carPassenger);

		// drt: ASC=2.45, margUtilDist=-2.5E-4 (non-monetary distance disutility)
		ModeParams drt = new ModeParams("drt");
		drt.setConstant(2.45);
		drt.setMarginalUtilityOfTraveling(0.0);
		drt.setMarginalUtilityOfDistance(-2.5E-4);
		drt.setMonetaryDistanceRate(0.0);
		scoring.addModeParams(drt);

		// freight: for any remaining freight agents
		ModeParams freight = new ModeParams("freight");
		freight.setConstant(0.0);
		freight.setMarginalUtilityOfTraveling(0.0);
		freight.setMonetaryDistanceRate(-0.002);
		scoring.addModeParams(freight);

		log.info("Applied Kelheim v3.0 calibrated scoring parameters");
		log.info("  marginalUtilityOfMoney: {} (config-level, person-specific via income scaling)",
				scoring.getMarginalUtilityOfMoney());
	}

	// -------------------------------------------------------------------------
	// Eqasim activity registration
	// -------------------------------------------------------------------------

	/**
	 * Register eqasim activity types with sensible typical durations.
	 * Registers BOTH simple names (home, work, etc.) AND duration-split variants
	 * (home_600, home_1200, ..., home_86400) so populations from either eqasim or
	 * Snz-style base simulations are accepted.
	 */
	private static void registerEqasimActivities(Config config) {
		ScoringConfigGroup scoring = config.scoring();

		// Scored activities with typical durations (simple names)
		addActivityParams(scoring, "home", 12 * 3600, true);
		addActivityParams(scoring, "work", 8 * 3600, true);
		addActivityParams(scoring, "education", 6 * 3600, true);
		addActivityParams(scoring, "shop", 1 * 3600, true);
		addActivityParams(scoring, "leisure", 2 * 3600, true);
		addActivityParams(scoring, "other", 2 * 3600, true);

		// Duration-split variants: <type>_600, <type>_1200, ..., <type>_86400 (600s bins)
		// Needed when population uses Snz-style duration-encoded activity types.
		String[][] scoredDurationTypes = {
			{"home", "-1", "-1"},
			{"work", "6", "20"},
			{"education", "7", "22"},
			{"shop", "8", "20"},
			{"leisure", "9", "27"},
			{"other", "-1", "-1"},
		};
		for (String[] typeInfo : scoredDurationTypes) {
			String baseType = typeInfo[0];
			double openTime = Double.parseDouble(typeInfo[1]) * 3600;
			double closeTime = Double.parseDouble(typeInfo[2]) * 3600;
			for (long duration = 600; duration <= 86400; duration += 600) {
				String typeName = baseType + "_" + duration;
				if (scoring.getActivityParams(typeName) != null) continue;
				ActivityParams params = new ActivityParams(typeName);
				params.setTypicalDuration(duration);
				if (openTime > 0) params.setOpeningTime(openTime);
				if (closeTime > 0) params.setClosingTime(closeTime);
				scoring.addActivityParams(params);
			}
		}

		// Non-scored activities
		addActivityParams(scoring, "outside", -1, false);
		addActivityParams(scoring, "freight_loading", -1, false);
		addActivityParams(scoring, "freight_unloading", -1, false);

		// Interaction activities (never scored)
		for (String mode : new String[]{"car", "pt", "bike", "walk", "drt", "ride",
				"taxi", "other", "car_passenger", "bicycle"}) {
			addActivityParams(scoring, mode + " interaction", -1, false);
		}

		log.info("Registered {} eqasim activity types (simple + duration-split)",
				scoring.getActivityParams().size());
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
	// Replanning configuration
	// -------------------------------------------------------------------------

	/**
	 * Configure replanning strategies with SubtourModeChoice annealing.
	 * Only called when iterations > 0.
	 */
	private static void configureReplanning(Config config, int iterations,
			double dmcStartRate, double dmcEndRate) {
		config.controller().setLastIteration(iterations);

		// Strategy weights
		config.replanning().setFractionOfIterationsToDisableInnovation(0.9);

		StrategySettings changeExpBeta = new StrategySettings();
		changeExpBeta.setStrategyName("ChangeExpBeta");
		changeExpBeta.setSubpopulation("person");
		changeExpBeta.setWeight(0.85);
		config.replanning().addStrategySettings(changeExpBeta);

		StrategySettings reRoute = new StrategySettings();
		reRoute.setStrategyName("ReRoute");
		reRoute.setSubpopulation("person");
		reRoute.setWeight(0.10);
		config.replanning().addStrategySettings(reRoute);

		StrategySettings subtourModeChoice = new StrategySettings();
		subtourModeChoice.setStrategyName("SubtourModeChoice");
		subtourModeChoice.setSubpopulation("person");
		subtourModeChoice.setWeight(dmcStartRate);
		config.replanning().addStrategySettings(subtourModeChoice);

		StrategySettings timeMutator = new StrategySettings();
		timeMutator.setStrategyName("TimeAllocationMutator");
		timeMutator.setSubpopulation("person");
		timeMutator.setWeight(0.10);
		config.replanning().addStrategySettings(timeMutator);

		// TimeAllocationMutator range
		config.timeAllocationMutator().setMutationRange(7200.0);

		// SubtourModeChoice config
		config.subtourModeChoice().setModes(new String[]{"car", "pt", "bike", "walk"});
		config.subtourModeChoice().setChainBasedModes(new String[]{"car", "bike"});
		config.subtourModeChoice().setConsiderCarAvailability(true);
		config.subtourModeChoice().setBehavior(
				SubtourModeChoice.Behavior.betweenAllAndFewerConstraints);
		config.subtourModeChoice().setProbaForRandomSingleTripMode(0.5);

		// ReplanningAnnealer -- sigmoid anneal SubtourModeChoice from startRate to endRate
		ReplanningAnnealerConfigGroup annealerConfig = new ReplanningAnnealerConfigGroup();
		annealerConfig.setActivateAnnealingModule(true);

		ReplanningAnnealerConfigGroup.AnnealingVariable annealVar =
				new ReplanningAnnealerConfigGroup.AnnealingVariable();
		annealVar.setAnnealParameter(
				ReplanningAnnealerConfigGroup.AnnealParameterOption.globalInnovationRate);
		annealVar.setAnnealType(ReplanningAnnealerConfigGroup.AnnealOption.sigmoid);
		annealVar.setDefaultSubpopulation("person");
		annealVar.setHalfLife(0.5);
		annealVar.setShapeFactor(0.01);
		// Normalize innovation rates: innovation share = sum of innovative strategies / total
		double innovationTotal = dmcStartRate + 0.10 + 0.10;
		annealVar.setStartValue(dmcStartRate / innovationTotal);
		annealVar.setEndValue(dmcEndRate / (dmcEndRate + 0.10 + 0.10));
		annealerConfig.addAnnealingVariable(annealVar);

		config.addModule(annealerConfig);

		// Score averaging
		config.scoring().setFractionOfIterationsToStartScoreMSA(0.9);

		log.info("Replanning configured: {} iterations, DMC {}% -> {}%",
				iterations, dmcStartRate * 100, dmcEndRate * 100);
	}

	// -------------------------------------------------------------------------
	// Demand extraction setup
	// -------------------------------------------------------------------------

	/**
	 * Configure MATSim for demand extraction: output settings, ExMAS config,
	 * VSP defaults, iteration count.
	 */
	private static void configureForDemandExtraction(Config config, Path outputDir,
			int sampleSize, int iterations, int algorithmProcessCount,
			int heuristicsProcessCount, boolean deterministic, boolean noPruning,
			boolean noPredecessors, int maxDegree, double interDegreeKeep) {

		// VSP defaults
		config.vspExperimental().setVspDefaultsCheckingLevel(
				VspExperimentalConfigGroup.VspDefaultsCheckingLevel.info);

		// Output settings
		config.controller().setOutputDirectory(outputDir.toString());
		config.controller().setOverwriteFileSetting(
				OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
		config.controller().setRunId("bavaria-30km-" + sampleSize + "pct-exmas");

		if (iterations == 0) {
			config.controller().setLastIteration(0);
		}
		// else: already set by configureReplanning

		config.controller().setWriteEventsInterval(iterations > 0 ? 50 : 0);
		config.controller().setWritePlansInterval(iterations > 0 ? 50 : 0);
		config.controller().setRoutingAlgorithmType(
				ControllerConfigGroup.RoutingAlgorithmType.SpeedyALT);

		// Note: No DVRP/DRT module configuration needed — we use a plain Controler.
		// DemandExtractionModule reads DRT fare params from Config, not from Guice bindings.

		// Configure ExMAS (same as RunKelheimDemandExtraction)
		configureExMas(config, algorithmProcessCount, heuristicsProcessCount, deterministic,
				noPruning, noPredecessors, maxDegree, interDegreeKeep);

		logScoringParameters(config);
	}

	// -------------------------------------------------------------------------
	// ExMAS configuration (copied from RunKelheimDemandExtraction)
	// -------------------------------------------------------------------------

	/**
	 * Configure ExMAS algorithm parameters.
	 * Settings aligned with ExMasKelheimE2ETest for consistency.
	 */
	private static void configureExMas(Config config, int algorithmProcessCount, int heuristicsProcessCount, boolean deterministic, boolean noPruning,
			boolean noPredecessors, int maxDegree, double interDegreeKeep) {
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

		exMasConfig.setAlgorithmProcessCount(algorithmProcessCount);
		exMasConfig.setHeuristicsProcessCount(heuristicsProcessCount);
		if (deterministic) {
			exMasConfig.setUseDeterministicNetworkRouting(true);
		}

		// Private vehicle modes (create subtour dependencies)
		Set<String> privateVehicles = new HashSet<>();
		privateVehicles.add(TransportMode.car);
		privateVehicles.add(TransportMode.bike);
		exMasConfig.setPrivateVehicleModes(privateVehicles);

		// === COMMUTE FILTERING ===
		// Extract commute trips (home <-> work) AND education trips
		exMasConfig.setCommuteFilter(CommuteFilter.COMMUTES_AND_EDUCATION);
		exMasConfig.setHomeActivityType("home");
		exMasConfig.setWorkActivityType("work");
		exMasConfig.setEducationActivityType("educ");

		// Filter agents by age (e.g. only adults >= 18)
		exMasConfig.setMinAge(13);

		// DRT service quality parameters for budget calculation (aligned with E2E test)
		exMasConfig.setMinDrtCostPerKm(0.0);
		exMasConfig.setMinMaxDetourFactor(1.0);
		exMasConfig.setMinMaxWaitingTime(0.0);
		exMasConfig.setMinDrtAccessEgressDistance(100.0);

		// ExMAS algorithm parameters (aligned with E2E test)
		exMasConfig.setSearchHorizon(3600.0); // unlimited
		exMasConfig.setMaxDetourFactor(1.5);  // Max 50% longer than direct
		exMasConfig.setMaxAbsoluteDetour(3600); // Max 1 hour absolute detour
		exMasConfig.setMaxPoolingDegree(maxDegree);  // CLI: --max-degree (default: 16)

		// Predecessor/successor calculation for connection_cache.csv output
		// Needed for optimization empty vehicle kilometer calculations
		if (!noPredecessors) {
			exMasConfig.setCalcPredecessors(true);
			exMasConfig.setPredecessorsFilterDistanceFactor(0.5);
			// Only consider predecessors that ended within 0.5 hours before successor starts
			exMasConfig.setPredecessorsFilterTime(1800.0);
			exMasConfig.setCalcShapleyValues(true);
		} else {
			exMasConfig.setCalcPredecessors(false);
			exMasConfig.setCalcShapleyValues(false);
			log.info("Predecessor/successor and Shapley calculation disabled (--no-predecessors)");
		}

		// Pruning settings: heuristic pruning controls combinatorial growth during ride
		// extension
		exMasConfig.setHeuristicPruningEnabled(true);
		// Degree-aware distance-savings pruning:
		// requiredSaving(d) = scale * log2(d) (clamped).
		// scale < 0 disables; scale = 0 matches legacy non-improving (rideDistance <=
		// sumDistances).
		// Degree 2 pairs are pruned as extension BASES only (after shareability graph
		// construction). Pruned pairs remain in allRides as pair support for tryExtend
		// validation, so higher-degree rides can still be discovered via alternate pair paths.
		exMasConfig.setPruningDistanceSavingsLogScale(0.15);
		exMasConfig.setPruningDistanceSavingsMax(0.75);
		exMasConfig.setPruningDistanceSavingsMinDegree(2); // prune paired rides after graph construction

		if (noPruning) {
			log.info("=== NO-PRUNING MODE: disabling all pruning for baseline comparison ===");
			exMasConfig.setHeuristicPruningEnabled(false);
			exMasConfig.setPruningDistanceSavingsLogScale(-1.0);
			interDegreeKeep = 1.0; // disable inter-degree pruning in no-pruning mode
		}

		// Limit successors to improve performance (Top-K pruning)
		exMasConfig.setMaxSuccessors(50);

		// Scoring
		exMasConfig.setOpportunityCostModel(ExMasConfigGroup.OpportunityCostModel.LOG);
		exMasConfig.setAmortizeDailyMonetaryConstants(true);

		// Disable PT departure optimization to avoid SwissRailRaptor configuration issues
		// TODO: Fix SwissRailRaptor range query settings configuration
		exMasConfig.setPtOptimizeDepartureTime(false);

		// Inter-degree pruning: mandatory, direct fraction (no sqrt scaling)
		exMasConfig.setInterDegreeKeepFraction(interDegreeKeep);
		log.info("  Inter-degree pruning: keepFraction={}", interDegreeKeep);

		// Defer budget validation out of the extension DFS — budget is subsumed by max-travel-time
		// on Bavaria, so per-ordering validation is pure overhead (7-11% CPU at high degrees).
		// BudgetValidator.populateBudgetsBatch runs once after extension completes.
		exMasConfig.setDeferExtensionBudgetValidation(true);

		log.info("ExMAS config:");
		log.info("  DRT mode: {}", exMasConfig.getDrtMode());
		log.info("  Commute filter: {}", exMasConfig.getCommuteFilter());
		log.info("  Base modes: {}", exMasConfig.getBaseModes());
		log.info("  Max pooling degree: {}", exMasConfig.getMaxPoolingDegree());
		log.info("  Max detour factor: {}", exMasConfig.getMaxDetourFactor());
		log.info("  Calc predecessors: {}", exMasConfig.isCalcPredecessors());
		log.info("  algorithmProcessCount: {}", exMasConfig.getAlgorithmProcessCount());
		log.info("  heuristicsProcessCount: {}", exMasConfig.getHeuristicsProcessCount());
		log.info("  deterministicNetworkRouting: {}", exMasConfig.isUseDeterministicNetworkRouting());
		log.info("  Opportunity cost model: {}", exMasConfig.getOpportunityCostModel());
	}

	// -------------------------------------------------------------------------
	// Scoring parameter logging (copied from RunKelheimDemandExtraction)
	// -------------------------------------------------------------------------

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
		log.info("6. Income-dependent marginalUtilityOfMoney active (person-specific)");
		log.info("=======================================\n");
	}

	// -------------------------------------------------------------------------
	// Population filtering
	// -------------------------------------------------------------------------

	/**
	 * Filter out freight, truck, and outside agents from the population.
	 * These agents don't have normal commute patterns and can cause issues.
	 */
	private static void filterUnwantedAgents(Scenario scenario) {
		log.info("Filtering unwanted agents...");

		scenario.getPopulation().getPersons().values().removeIf(person -> {
			// Check subpopulation
			Object subpop = person.getAttributes().getAttribute("subpopulation");
			if ("freight".equals(subpop) || "truck".equals(subpop)) {
				return true;
			}

			// Check first activity for "outside" (agents living outside study area)
			if (person.getSelectedPlan() != null && !person.getSelectedPlan().getPlanElements().isEmpty()) {
				var firstElement = person.getSelectedPlan().getPlanElements().get(0);
				if (firstElement instanceof Activity firstAct
						&& "outside".equals(firstAct.getType())) {
					return true;
				}
				// Check all activities for freight
				return person.getSelectedPlan().getPlanElements().stream()
						.filter(Activity.class::isInstance)
						.map(Activity.class::cast)
						.anyMatch(act -> act.getType() != null
								&& act.getType().startsWith("freight"));
			}

			return false;
		});
	}

	/**
	 * Remove agents who have no activity within the specified radius of the center point.
	 * Uses Euclidean distance (valid for projected CRS like EPSG:25832 at this scale).
	 *
	 * @param scenario the MATSim scenario
	 * @param centerX center X coordinate (EPSG:25832)
	 * @param centerY center Y coordinate (EPSG:25832)
	 * @param radiusMeters radius in meters
	 */
	private static void filterByRadius(Scenario scenario, double centerX,
			double centerY, double radiusMeters) {
		int before = scenario.getPopulation().getPersons().size();
		double radiusSq = radiusMeters * radiusMeters;

		scenario.getPopulation().getPersons().values().removeIf(person -> {
			if (person.getSelectedPlan() == null) return true;
			return person.getSelectedPlan().getPlanElements().stream()
					.filter(Activity.class::isInstance)
					.map(Activity.class::cast)
					.filter(act -> act.getCoord() != null)
					.noneMatch(act -> {
						double dx = act.getCoord().getX() - centerX;
						double dy = act.getCoord().getY() - centerY;
						return (dx * dx + dy * dy) <= radiusSq;
					});
		});

		int after = scenario.getPopulation().getPersons().size();
		log.info("Radius filter ({}km): {} -> {} agents ({} removed)",
				radiusMeters / 1000.0, before, after, before - after);
	}

	/**
	 * Resolve a municipality name to its centroid coordinates by looking it up in VG250 shapes.
	 * Searches vg250_gem (Gemeinde), then vg250_krs (Kreis), then vg250_lan (Land).
	 * If multiple matches, picks the smallest area (most specific).
	 *
	 * @param name municipality name (case-insensitive match on GEN column)
	 * @param shapesPath path to VG250 GeoPackage file
	 * @return double[]{x, y} centroid in the shapefile's CRS (EPSG:25832)
	 */
	private static double[] resolveMunicipalityCentroid(String name, String shapesPath) {
		String[] layers = {"vg250_gem", "vg250_krs", "vg250_lan"};
		String nameLower = name.toLowerCase();

		org.geotools.api.feature.simple.SimpleFeature bestMatch = null;
		double bestArea = Double.MAX_VALUE;
		String bestLayer = null;

		for (String layer : layers) {
			var features = GeoFileReader.getAllFeatures(shapesPath, new org.geotools.feature.NameImpl(layer));
			for (var feature : features) {
				Object gen = feature.getAttribute("GEN");
				if (gen != null && gen.toString().toLowerCase().equals(nameLower)) {
					org.locationtech.jts.geom.Geometry geom =
							(org.locationtech.jts.geom.Geometry) feature.getDefaultGeometry();
					if (geom != null) {
						double area = geom.getArea();
						if (area < bestArea) {
							bestArea = area;
							bestMatch = feature;
							bestLayer = layer;
						}
					}
				}
			}
		}

		if (bestMatch == null) {
			throw new IllegalArgumentException("Municipality '" + name + "' not found in VG250 layers "
					+ java.util.Arrays.toString(layers) + ". Check spelling (GEN column, case-insensitive).");
		}

		org.locationtech.jts.geom.Geometry geom =
				(org.locationtech.jts.geom.Geometry) bestMatch.getDefaultGeometry();
		org.locationtech.jts.geom.Point centroid = geom.getCentroid();

		log.info("Resolved '{}' -> {} ({}) in {}, centroid=({}, {}), pop={}",
				name, bestMatch.getAttribute("GEN"), bestMatch.getAttribute("BEZ"),
				bestLayer, String.format("%.0f", centroid.getX()), String.format("%.0f", centroid.getY()),
				bestMatch.getAttribute("EWZ"));

		return new double[]{centroid.getX(), centroid.getY()};
	}

	/**
	 * Downsample population to the requested percentage.
	 * Uses deterministic sampling (hash-based) so the same seed always selects the same agents.
	 */
	private static void downsamplePopulation(Scenario scenario, int samplePercent) {
		if (samplePercent >= 100) {
			log.info("Sample size 100% — keeping all {} agents", scenario.getPopulation().getPersons().size());
			return;
		}

		int before = scenario.getPopulation().getPersons().size();
		double fraction = samplePercent / 100.0;
		java.util.Random rng = new java.util.Random(4711L);

		scenario.getPopulation().getPersons().values()
				.removeIf(person -> rng.nextDouble() >= fraction);

		int after = scenario.getPopulation().getPersons().size();
		log.info("Downsampled population: {} -> {} agents ({}% sample, target ratio {:.1f}%)",
				before, after, samplePercent, (after * 100.0 / before));
	}

	// -------------------------------------------------------------------------
	// Vehicle type fix
	// -------------------------------------------------------------------------

	/**
	 * MATSim requires a {@code networkMode} for non-car vehicle types when writing vehicles.
	 * Some inputs contain vehicle types without it, which can crash during shutdown output writing.
	 * This method defensively sets missing/invalid network modes to {@code car}.
	 */
	private static void ensureVehicleTypeNetworkModes(Scenario scenario) {
		int fixed = 0;
		fixed += ensureVehicleTypeNetworkModes(scenario.getVehicles().getVehicleTypes().values());
		fixed += ensureVehicleTypeNetworkModes(scenario.getTransitVehicles().getVehicleTypes().values());
		if (fixed > 0) {
			log.warn("Set missing vehicleType networkMode for {} type(s) to 'car'", fixed);
		}
	}

	private static int ensureVehicleTypeNetworkModes(java.util.Collection<VehicleType> types) {
		int fixed = 0;
		for (VehicleType type : types) {
			try {
				String nm = type.getNetworkMode();
				if (nm == null || nm.isBlank()) {
					type.setNetworkMode(TransportMode.car);
					fixed++;
				}
			} catch (NullPointerException e) {
				type.setNetworkMode(TransportMode.car);
				fixed++;
			}
		}
		return fixed;
	}
}
