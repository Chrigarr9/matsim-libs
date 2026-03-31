package org.matsim.contrib.demand_extraction.run;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.HashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.common.timeprofile.TimeDiscretizer;
import org.matsim.contrib.dvrp.trafficmonitoring.DvrpOfflineTravelTimes;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ControllerConfigGroup;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup.ActivityParams;
import org.matsim.core.config.groups.ScoringConfigGroup.ModeParams;
import org.matsim.core.config.groups.VspExperimentalConfigGroup;
import org.matsim.core.config.groups.ReplanningConfigGroup.StrategySettings;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.replanning.annealing.ReplanningAnnealerConfigGroup;
import org.matsim.core.replanning.modules.SubtourModeChoice;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;

import com.google.inject.Key;
import com.google.inject.Singleton;
import com.google.inject.name.Names;

import org.matsim.application.prepare.population.SplitActivityTypesDuration;

import playground.vsp.scoring.IncomeDependentUtilityOfMoneyPersonScoringParameters;

/**
 * Standalone MATSim base simulation for Bavaria 30km scenario.
 *
 * <p>Runs MATSim with Kelheim v3.0 scoring parameters and income-dependent margUtilOfMoney
 * to converge mode shares and travel times. NO DRT — only car, pt, bike, walk.</p>
 *
 * <p>After convergence, exports link travel times via {@link DvrpOfflineTravelTimes}
 * for reuse in demand extraction runs at any sample rate.</p>
 *
 * <h3>Settings</h3>
 * <ul>
 *   <li>Population: adapted eqasim (with income via RunAdaptEqasimPopulation)</li>
 *   <li>Sample: 25% (default)</li>
 *   <li>Iterations: 200 (default)</li>
 *   <li>DMC annealing: 20% → 5% (SubtourModeChoice with sigmoid annealing)</li>
 *   <li>Modes: car, pt, bike, walk (no DRT)</li>
 *   <li>Output: converged plans + travel_times.tsv</li>
 * </ul>
 *
 * <pre>
 * cd matsim-libs/contribs/drt-demand-extraction
 * mvn exec:java -o -Dexec.mainClass="org.matsim.contrib.demand_extraction.run.RunBavariaBaseSimulation" \
 *   -Dexec.args="--scenario-path ../../../matsim_scenarios/bavaria/output/kelheim_30km_100pct \
 *                --population ../../../matsim_scenarios/bavaria/output/kelheim_30km_100pct/kelheim_30km_100pct_population_adapted.xml.gz \
 *                --sample 25 --iterations 100" \
 *   -Denforcer.skip=true
 * </pre>
 */
public class RunBavariaBaseSimulation {

	private static final Logger log = LogManager.getLogger(RunBavariaBaseSimulation.class);
	private static final String FILE_PREFIX = "kelheim_30km_100pct_";

	/** Time bin size for travel time export (seconds). 15 minutes = 96 bins/day. */
	private static final int TRAVEL_TIME_BIN_SIZE = 900;

	public static void main(String[] args) throws IOException {
		// Parse CLI arguments
		String scenarioPath = null;
		String populationPath = null;
		int sampleSize = 100; // 100 = no further downsampling (use population as-is)
		int capacityPercent = -1; // QSim flow/storage capacity factor (% of full capacity)
		int iterations = 200;
		double dmcStartRate = 0.20;
		double dmcEndRate = 0.05;
		String outputDir = null;
		String ascOverridesFile = null;

		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--scenario-path" -> scenarioPath = args[++i];
				case "--population" -> populationPath = args[++i];
				case "--sample" -> sampleSize = Integer.parseInt(args[++i]);
				case "--capacity" -> capacityPercent = Integer.parseInt(args[++i]);
				case "--iterations" -> iterations = Integer.parseInt(args[++i]);
				case "--dmc-start-rate" -> dmcStartRate = Double.parseDouble(args[++i]);
				case "--dmc-end-rate" -> dmcEndRate = Double.parseDouble(args[++i]);
				case "--output-dir" -> outputDir = args[++i];
				case "--asc-overrides" -> ascOverridesFile = args[++i];
				default -> log.warn("Unknown argument: {}", args[i]);
			}
		}

		// If capacity not explicitly set, use sample size
		if (capacityPercent < 0) {
			capacityPercent = sampleSize;
		}

		if (scenarioPath == null || populationPath == null) {
			System.err.println("Usage: RunBavariaBaseSimulation "
					+ "--scenario-path <path> --population <path> "
					+ "[--sample <1|10|25|100>] [--capacity <1|10|25|100>] "
					+ "[--iterations <N>] "
					+ "[--dmc-start-rate <0.0-1.0>] [--dmc-end-rate <0.0-1.0>] "
					+ "[--output-dir <path>]");
			System.exit(1);
		}

		log.info("=== Bavaria 30km Base Simulation ===");
		log.info("Scenario path: {}", scenarioPath);
		log.info("Population: {}", populationPath);
		log.info("Sample size: {}% (downsampling)", sampleSize);
		log.info("Capacity factor: {}% (QSim flow/storage)", capacityPercent);
		log.info("Iterations: {}", iterations);
		log.info("DMC annealing: {}% -> {}%", dmcStartRate * 100, dmcEndRate * 100);
		log.info("Modes: car, pt, bike, walk (NO DRT)");

		// Resolve output directory
		Path outDir;
		if (outputDir != null) {
			outDir = Path.of(outputDir);
		} else {
			outDir = Path.of(scenarioPath).getParent()
					.resolve("base-simulation-" + sampleSize + "pct");
		}
		Files.createDirectories(outDir);
		log.info("Output: {}", outDir.toAbsolutePath());

		// Read ASC overrides if provided
		Map<String, Double> ascOverrides = readAscOverrides(ascOverridesFile);
		if (!ascOverrides.isEmpty()) {
			log.info("ASC overrides: {}", ascOverrides);
		}

		// Build config
		Config config = buildConfig(scenarioPath, populationPath, sampleSize,
				capacityPercent, iterations, dmcStartRate, dmcEndRate, ascOverrides);

		// Output settings
		config.controller().setOutputDirectory(outDir.toString());
		config.controller().setOverwriteFileSetting(
				OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
		config.controller().setRunId("bavaria-30km-" + sampleSize + "pct-base");
		config.controller().setWriteEventsInterval(50);
		config.controller().setWritePlansInterval(50);
		config.controller().setRoutingAlgorithmType(
				ControllerConfigGroup.RoutingAlgorithmType.SpeedyALT);

		// VSP defaults
		config.vspExperimental().setVspDefaultsCheckingLevel(
				VspExperimentalConfigGroup.VspDefaultsCheckingLevel.info);

		// Load scenario
		Scenario scenario = ScenarioUtils.createScenario(config);
		ScenarioUtils.loadScenario(scenario);

		// Filter unwanted agents (freight, outside)
		int originalSize = scenario.getPopulation().getPersons().size();
		filterUnwantedAgents(scenario);
		int afterFilter = scenario.getPopulation().getPersons().size();
		log.info("Filtered population: {} -> {} agents ({} removed)",
				originalSize, afterFilter, originalSize - afterFilter);

		// Downsample
		downsamplePopulation(scenario, sampleSize);

		// Split activity types by duration (matching Kelheim PreparePopulation pipeline).
		// Converts e.g. "home" with end_time=10:00 -> "home_36000", "work" 8h -> "work_28800".
		// This is critical for correct log-utility scoring: each activity gets a
		// person-specific typicalDuration instead of a fixed one (home=12h, work=8h).
		splitActivityTypesByDuration(scenario);

		// Reset all leg modes to "walk" — eqasim plans use non-standard mode names
		// (bicycle, car_passenger) that MATSim's router doesn't know.
		// SubtourModeChoice will assign correct modes (car, bike, ride, pt, walk)
		// during the first iterations.
		resetAllModesToWalk(scenario);

		// Create controller
		Controler controler = new Controler(scenario);

		// Enable income-dependent scoring
		boolean hasIncomeAttributes = scenario.getPopulation().getPersons().values().stream()
				.anyMatch(p -> org.matsim.core.population.PersonUtils.getIncome(p) != null);
		if (hasIncomeAttributes) {
			log.info("Income attributes found — enabling income-dependent marginalUtilityOfMoney");
			controler.addOverridingModule(new org.matsim.core.controler.AbstractModule() {
				@Override
				public void install() {
					bind(ScoringParametersForPerson.class)
							.to(IncomeDependentUtilityOfMoneyPersonScoringParameters.class)
							.in(Singleton.class);
				}
			});
		} else {
			log.warn("No income attributes — using uniform marginalUtilityOfMoney");
		}

		// Add shutdown listener to export travel times
		Path travelTimesFile = outDir.resolve("travel_times.tsv");
		controler.addOverridingModule(new org.matsim.core.controler.AbstractModule() {
			@Override
			public void install() {
				addControlerListenerBinding().toInstance(
					new TravelTimeExportListener(travelTimesFile, TRAVEL_TIME_BIN_SIZE));
			}
		});

		// Run simulation
		controler.run();

		log.info("\n=== Base Simulation Complete ===");
		log.info("Output: {}", outDir.toAbsolutePath());
		log.info("Travel times: {}", travelTimesFile.toAbsolutePath());
		log.info("Use travel_times.tsv for demand extraction at any sample rate.");
		log.info("================================\n");
	}

	// -------------------------------------------------------------------------
	// Travel time export listener
	// -------------------------------------------------------------------------

	/**
	 * Exports link travel times at simulation shutdown.
	 */
	private static class TravelTimeExportListener
			implements org.matsim.core.controler.listener.ShutdownListener {

		private final Path outputFile;
		private final int timeBinSize;

		TravelTimeExportListener(Path outputFile, int timeBinSize) {
			this.outputFile = outputFile;
			this.timeBinSize = timeBinSize;
		}

		@Override
		public void notifyShutdown(org.matsim.core.controler.events.ShutdownEvent event) {
			log.info("Exporting link travel times to {}", outputFile);

			try {
				TravelTime travelTime = event.getServices().getInjector()
						.getInstance(Key.get(TravelTime.class, Names.named(TransportMode.car)));

				int endTime = (int) event.getServices().getConfig().qsim().getEndTime().orElse(30 * 3600);
				TimeDiscretizer timeDiscretizer = new TimeDiscretizer(endTime, timeBinSize);

				var links = event.getServices().getScenario().getNetwork().getLinks().values();

				double[][] matrix = DvrpOfflineTravelTimes.convertToLinkTravelTimeMatrix(
						travelTime, links, timeDiscretizer);

				DvrpOfflineTravelTimes.saveLinkTravelTimes(
						timeDiscretizer, matrix, outputFile.toString(), "\t");

				log.info("Exported travel times: {} links, {} time bins ({}s each)",
						links.size(), timeDiscretizer.getIntervalCount(), timeBinSize);
			} catch (Exception e) {
				log.error("Failed to export travel times: {}", e.getMessage(), e);
			}
		}
	}

	// -------------------------------------------------------------------------
	// Config building
	// -------------------------------------------------------------------------

	private static Config buildConfig(String scenarioPath, String populationPath,
			int sampleSize, int capacityPercent, int iterations,
			double dmcStartRate, double dmcEndRate, Map<String, Double> ascOverrides) {

		Config config = ConfigUtils.createConfig();

		// --- Input files ---
		Path base = Path.of(scenarioPath);
		config.network().setInputFile(base.resolve(FILE_PREFIX + "network.xml.gz").toString());
		config.transit().setTransitScheduleFile(base.resolve(FILE_PREFIX + "transit_schedule.xml.gz").toString());
		config.transit().setVehiclesFile(base.resolve(FILE_PREFIX + "transit_vehicles.xml.gz").toString());
		config.vehicles().setVehiclesFile(base.resolve(FILE_PREFIX + "vehicles.xml.gz").toString());
		config.facilities().setInputFile(base.resolve(FILE_PREFIX + "facilities.xml.gz").toString());
		config.plans().setInputFile(populationPath);
		config.transit().setUseTransit(true);

		// --- Global ---
		config.global().setCoordinateSystem("Atlantis");
		config.global().setNumberOfThreads(6);

		// --- QSim ---
		// Capacity factors use capacityPercent (may differ from sampleSize when population is pre-filtered)
		double capacityFactor = capacityPercent / 100.0;
		config.qsim().setFlowCapFactor(capacityFactor);
		config.qsim().setStorageCapFactor(capacityFactor);
		config.qsim().setMainModes(java.util.List.of("car"));
		config.qsim().setNumberOfThreads(8);
		config.qsim().setStartTime(0);
		config.qsim().setEndTime(36 * 3600);
		config.qsim().setTrafficDynamics(QSimConfigGroup.TrafficDynamics.kinematicWaves);
		// Eqasim populations embed vehicle IDs in person attributes (e.g. "364916:car").
		// Use fromVehiclesData to load actual vehicle definitions from vehicles.xml.gz,
		// which maps these IDs correctly. defaultVehicle would create generic vehicles
		// that don't match the embedded IDs, causing "could not find vehicle" crashes.
		config.qsim().setVehiclesSource(QSimConfigGroup.VehiclesSource.fromVehiclesData);
		config.qsim().setUsePersonIdForMissingVehicleId(true);

		// --- Routing ---
		// Car uses network routing. All other modes use MATSim default teleportation.
		// Eqasim mode names (bicycle, car_passenger) are gone — all legs reset to walk,
		// SubtourModeChoice assigns standard modes (car, bike, ride, pt, walk).
		config.routing().setNetworkModes(java.util.List.of("car"));

		// --- Scoring (Kelheim v3.0, with optional ASC overrides) ---
		applyKelheimScoring(config, ascOverrides);
		registerEqasimActivities(config);

		// --- Replanning with DMC annealing ---
		configureReplanning(config, iterations, dmcStartRate, dmcEndRate);

		log.info("Config built: {} iterations, {}% sample, Kelheim v3.0 scoring, DMC {}% -> {}%",
				iterations, sampleSize, dmcStartRate * 100, dmcEndRate * 100);
		return config;
	}

	// -------------------------------------------------------------------------
	// Scoring (Kelheim v3.0 calibrated — same as RunBavaria30kmDemandExtraction)
	// -------------------------------------------------------------------------

	private static void applyKelheimScoring(Config config, Map<String, Double> ascOverrides) {
		ScoringConfigGroup scoring = config.scoring();
		scoring.setMarginalUtilityOfMoney(1.0);
		scoring.setPerforming_utils_hr(6.0);
		scoring.setLateArrival_utils_hr(-18.0);
		scoring.setMarginalUtlOfWaitingPt_utils_hr(-1.6);
		scoring.setUtilityOfLineSwitch(-1.0);

		// Car
		ModeParams car = new ModeParams(TransportMode.car);
		car.setConstant(ascOverrides.getOrDefault("car", 0.1091));
		car.setMarginalUtilityOfTraveling(0.0);
		car.setMarginalUtilityOfDistance(0.0);
		car.setMonetaryDistanceRate(-2.0E-4);
		car.setDailyMonetaryConstant(-5.3);
		scoring.addModeParams(car);

		// Ride (= car_passenger in eqasim)
		ModeParams ride = new ModeParams("car_passenger");
		ride.setConstant(-0.4487);
		ride.setMarginalUtilityOfTraveling(-12.0);
		ride.setMarginalUtilityOfDistance(0.0);
		ride.setMonetaryDistanceRate(-2.0E-4);
		ride.setDailyMonetaryConstant(0.0);
		scoring.addModeParams(ride);

		// PT
		ModeParams pt = new ModeParams(TransportMode.pt);
		pt.setConstant(ascOverrides.getOrDefault("pt", 0.0450));
		pt.setMarginalUtilityOfTraveling(0.0);
		pt.setMarginalUtilityOfDistance(0.0);
		pt.setMonetaryDistanceRate(0.0);
		pt.setDailyMonetaryConstant(0.0);
		scoring.addModeParams(pt);

		// Bike — must use "bike" to match SubtourModeChoice mode name
		ModeParams bike = new ModeParams("bike");
		bike.setConstant(ascOverrides.getOrDefault("bike", -0.9060));
		bike.setMarginalUtilityOfTraveling(-3.0);
		bike.setMarginalUtilityOfDistance(0.0);
		bike.setMonetaryDistanceRate(0.0);
		bike.setDailyMonetaryConstant(0.0);
		scoring.addModeParams(bike);

		// Walk
		ModeParams walk = new ModeParams(TransportMode.walk);
		walk.setConstant(ascOverrides.getOrDefault("walk", 0.0));
		walk.setMarginalUtilityOfTraveling(0.0);
		walk.setMarginalUtilityOfDistance(0.0);
		walk.setMonetaryDistanceRate(0.0);
		walk.setDailyMonetaryConstant(0.0);
		scoring.addModeParams(walk);

		// Other
		ModeParams other = new ModeParams("other");
		other.setConstant(0.0);
		other.setMarginalUtilityOfTraveling(-6.0);
		other.setMarginalUtilityOfDistance(0.0);
		other.setMonetaryDistanceRate(0.0);
		other.setDailyMonetaryConstant(0.0);
		scoring.addModeParams(other);

		log.info("Applied Kelheim v3.0 scoring with ASCs: car={}, bike={}, pt={}, walk={}",
				car.getConstant(), bike.getConstant(), pt.getConstant(), walk.getConstant());
	}

	/**
	 * Register duration-split activity types for scoring, matching Kelheim's
	 * SnzActivities.addScoringParams pattern. Each eqasim activity type gets
	 * variants from 600s to 86400s in 600s bins (e.g., home_600, home_1200, ..., home_86400).
	 * The typicalDuration equals the bin value, giving correct log-utility scoring.
	 */
	private static void registerEqasimActivities(Config config) {
		ScoringConfigGroup scoring = config.scoring();

		// Eqasim activity types with their opening/closing times (matching SnzActivities where applicable)
		String[][] scoredTypes = {
			{"home", "-1", "-1"},
			{"work", "6", "20"},
			{"education", "7", "22"},
			{"shop", "8", "20"},
			{"leisure", "9", "27"},
			{"other", "-1", "-1"},
		};

		for (String[] typeInfo : scoredTypes) {
			String baseType = typeInfo[0];
			double openTime = Double.parseDouble(typeInfo[1]) * 3600;
			double closeTime = Double.parseDouble(typeInfo[2]) * 3600;

			// Register duration-split variants: type_600, type_1200, ..., type_86400
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
		for (String type : new String[]{"outside", "freight_loading", "freight_unloading"}) {
			if (scoring.getActivityParams(type) == null) {
				ActivityParams params = new ActivityParams(type);
				params.setScoringThisActivityAtAll(false);
				scoring.addActivityParams(params);
			}
		}

		// Mode interaction activities (never scored)
		for (String mode : new String[]{"car", "pt", "bike", "walk", "ride",
				"taxi", "other", "car_passenger", "bicycle"}) {
			String type = mode + " interaction";
			if (scoring.getActivityParams(type) == null) {
				ActivityParams params = new ActivityParams(type);
				params.setScoringThisActivityAtAll(false);
				scoring.addActivityParams(params);
			}
		}

		log.info("Registered {} activity types (duration-split, 600s bins)", scoring.getActivityParams().size());
	}

	// -------------------------------------------------------------------------
	// Replanning (same as RunBavaria30kmDemandExtraction)
	// -------------------------------------------------------------------------

	private static void configureReplanning(Config config, int iterations,
			double dmcStartRate, double dmcEndRate) {
		config.controller().setLastIteration(iterations);
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

		// SubtourModeChoice: base modes only (no DRT)
		// Use MATSim-standard mode names (bike not bicycle, ride not car_passenger)
		// that match registered routing modules
		// No "ride" — it's not freely choosable (requires someone to drive you)
		config.subtourModeChoice().setModes(new String[]{"car", "pt", "bike", "walk"});
		config.subtourModeChoice().setChainBasedModes(new String[]{"car", "bike"});
		config.subtourModeChoice().setConsiderCarAvailability(true);
		config.subtourModeChoice().setBehavior(
				SubtourModeChoice.Behavior.betweenAllAndFewerConstraints);
		config.subtourModeChoice().setProbaForRandomSingleTripMode(0.5);

		// Annealing
		ReplanningAnnealerConfigGroup annealerConfig =
				ConfigUtils.addOrGetModule(config, ReplanningAnnealerConfigGroup.class);
		annealerConfig.setActivateAnnealingModule(true);
		ReplanningAnnealerConfigGroup.AnnealingVariable annealVar =
				new ReplanningAnnealerConfigGroup.AnnealingVariable();
		annealVar.setAnnealParameter(
				ReplanningAnnealerConfigGroup.AnnealParameterOption.globalInnovationRate);
		annealVar.setAnnealType(ReplanningAnnealerConfigGroup.AnnealOption.sigmoid);
		annealVar.setDefaultSubpopulation("person");
		annealVar.setHalfLife(0.5);
		annealVar.setShapeFactor(0.01);
		// Innovation strategies: SubtourModeChoice + ReRoute
		double innovationTotal = dmcStartRate + 0.10;
		annealVar.setStartValue(dmcStartRate / innovationTotal);
		annealVar.setEndValue(dmcEndRate / (dmcEndRate + 0.10));
		annealerConfig.addAnnealingVariable(annealVar);

		config.scoring().setFractionOfIterationsToStartScoreMSA(0.9);

		log.info("Replanning: {} iterations, DMC {}% -> {}%, modes: car/pt/bicycle/walk",
				iterations, dmcStartRate * 100, dmcEndRate * 100);
	}

	// -------------------------------------------------------------------------
	// ASC overrides (for calibration)
	// -------------------------------------------------------------------------

	private static Map<String, Double> readAscOverrides(String filePath) {
		Map<String, Double> overrides = new HashMap<>();
		if (filePath == null) return overrides;

		Path path = Path.of(filePath);
		if (!Files.exists(path)) {
			log.warn("ASC overrides file not found: {}", filePath);
			return overrides;
		}

		try {
			String content = Files.readString(path).trim();
			// Simple JSON parsing: {"car": 1.5, "bike": -2.0, "pt": 0.5, "walk": -0.3}
			content = content.replaceAll("[{}\"]", "");
			for (String entry : content.split(",")) {
				String[] kv = entry.split(":");
				if (kv.length == 2) {
					overrides.put(kv[0].trim(), Double.parseDouble(kv[1].trim()));
				}
			}
			log.info("Loaded ASC overrides from {}: {}", filePath, overrides);
		} catch (Exception e) {
			log.error("Failed to read ASC overrides from {}: {}", filePath, e.getMessage());
		}
		return overrides;
	}

	// -------------------------------------------------------------------------
	// Population filtering (same as RunBavaria30kmDemandExtraction)
	// -------------------------------------------------------------------------

	private static void filterUnwantedAgents(Scenario scenario) {
		scenario.getPopulation().getPersons().values().removeIf(person -> {
			Object subpop = person.getAttributes().getAttribute("subpopulation");
			if (subpop != null) {
				String s = subpop.toString();
				if (s.equals("freight") || s.equals("truck") || s.equals("commercial")) {
					return true;
				}
			}
			if (person.getSelectedPlan() == null || person.getSelectedPlan().getPlanElements().isEmpty()) {
				return true;
			}
			var firstElement = person.getSelectedPlan().getPlanElements().get(0);
			if (firstElement instanceof org.matsim.api.core.v01.population.Activity act) {
				String actType = act.getType();
				if (actType != null && actType.equals("outside")) {
					return true;
				}
			}
			return false;
		});
	}

	/**
	 * Split activity types by duration, matching Kelheim's SplitActivityTypesDuration preprocessing.
	 * Converts generic types (home, work, leisure) to duration-encoded types (home_36000, work_28800)
	 * using 600-second bins. This gives each activity a person-specific typicalDuration for
	 * correct log-utility scoring.
	 */
	private static void splitActivityTypesByDuration(Scenario scenario) {
		SplitActivityTypesDuration splitter = new SplitActivityTypesDuration(600, 86400, 1800);
		int count = 0;
		for (Person person : scenario.getPopulation().getPersons().values()) {
			splitter.run(person);
			count++;
		}
		log.info("Split activity types by duration for {} persons (600s bins)", count);
	}

	/**
	 * Replace all legs with single walk legs between real activities.
	 * Eqasim plans have multi-leg trips with interaction activities (e.g.,
	 * walk → car_interaction → walk). This strips interaction activities and
	 * collapses each trip to a single walk leg.
	 * SubtourModeChoice will assign proper modes during iterations.
	 */
	private static void resetAllModesToWalk(Scenario scenario) {
		int personsProcessed = 0;
		for (Person person : scenario.getPopulation().getPersons().values()) {
			if (person.getSelectedPlan() == null) continue;
			var plan = person.getSelectedPlan();

			// Extract real activities only (skip interaction activities)
			var realActivities = plan.getPlanElements().stream()
					.filter(org.matsim.api.core.v01.population.Activity.class::isInstance)
					.map(org.matsim.api.core.v01.population.Activity.class::cast)
					.filter(act -> !act.getType().contains("interaction"))
					.toList();

			if (realActivities.isEmpty()) continue;

			// Rebuild plan: realAct - walk - realAct - walk - realAct ...
			plan.getPlanElements().clear();
			for (int i = 0; i < realActivities.size(); i++) {
				plan.addActivity(realActivities.get(i));
				if (i < realActivities.size() - 1) {
					plan.addLeg(scenario.getPopulation().getFactory().createLeg("walk"));
				}
			}
			personsProcessed++;
		}
		log.info("Reset {} persons' plans (stripped interaction activities, all legs set to walk)", personsProcessed);
	}

	private static void downsamplePopulation(Scenario scenario, int samplePercent) {
		if (samplePercent >= 100) {
			log.info("Sample size 100% — keeping all {} agents",
					scenario.getPopulation().getPersons().size());
			return;
		}
		int before = scenario.getPopulation().getPersons().size();
		double targetRatio = samplePercent / 100.0;
		scenario.getPopulation().getPersons().values()
				.removeIf(person -> (Math.abs(person.getId().toString().hashCode()) % 100)
						>= samplePercent);
		int after = scenario.getPopulation().getPersons().size();
		log.info("Downsampled: {} -> {} agents ({}% sample)", before, after, samplePercent);
	}
}
