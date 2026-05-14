package org.matsim.contrib.demand_extraction.run;

import java.nio.file.Path;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.scenarios.AlgorithmProfile;
import org.matsim.contrib.demand_extraction.scenarios.FocusRegistry;
import org.matsim.contrib.demand_extraction.scenarios.LyonEqasimScenarioFixture;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;

/**
 * Eqasim-native DRT demand extraction runner for the Lyon 40-km cut scenario.
 *
 * <p>Setup is owned by {@link LyonEqasimScenarioFixture}; this class only
 * parses CLI arguments, applies algorithm + sweep overrides, and runs the
 * controler.
 *
 * <pre>
 * cd matsim-libs/contribs/drt-demand-extraction
 * mvn exec:java -Dexec.mainClass="org.matsim.contrib.demand_extraction.run.RunLyonEqasimDemandExtraction" \
 *   -Dexec.args="--sample 10 \
 *                --scenario-dir ../../../matsim_scenarios/eqasim-france/output_lyon_drt_10pct \
 *                --prefix lyon_drt_10pct_ \
 *                --travel-times ../../../matsim_scenarios/eqasim-france/output_fullregion_10pct/travel_times.tsv \
 *                --output-dir ../../../outputs/R2 \
 *                --profile r2" \
 *   -Denforcer.skip=true
 * </pre>
 *
 * <p>Use {@code --profile r1|r2|r3|r4} to apply the Paper 1 {@link AlgorithmProfile}
 * (sets both algorithm and pruning knobs). R1 = ExMAS reference, R2 = BAMAS no
 * pruning, R3 = BAMAS distance-only pruning (heuristic gate ON, post-extension
 * OFF), R4 = BAMAS production defaults (heuristic gate ON + post-extension
 * COVERAGE_TOPK with K=20). The profiles form a strict-subset progression
 * R2 ⊂ R3 ⊂ R4 by enabled gates. Overrides {@code --algorithm} when both are
 * specified.
 */
public class RunLyonEqasimDemandExtraction {

	private static final Logger log = LogManager.getLogger(RunLyonEqasimDemandExtraction.class);

	/**
	 * Default location of the focus registry, relative to the repo root (which is the
	 * working directory when {@link #main(String[])} is invoked by the notebook
	 * pipeline / Maven exec from {@code Dissertation/}).
	 */
	private static final Path DEFAULT_FOCUS_REGISTRY = Path.of(
			"matsim_scenarios/eqasim-france/scenario-selection/data/foci.json");

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
		/** Paper 1 profile (R1/R2/R3/R4). When set, overrides algorithm + all pruning knobs. */
		public final AlgorithmProfile profile;
		/** Override trip-filter radius (km). NaN = keep fixture default. */
		public final double tripFilterRadiusKm;
		/** When true, clears the exclusion-zone shapefile path set by the fixture. */
		public final boolean noExclusionZone;
		/** When true, disables predecessor/successor export. */
		public final boolean noPredecessors;
		/** When true, disables Shapley-value calculation. */
		public final boolean noShapley;
		/** When true, forces all cache-miss routing through a single shared locked router
		 *  (OnlyTimeDependentTravelDisutility). Eliminates thread-local SpeedyALT variation
		 *  for uncovered segments, making parallel runs byte-identical. */
		public final boolean deterministicRouting;
		/** Override maxPoolingDegree. -1 = keep config/profile default. Use 1 to skip
		 *  pair generation entirely (drt_requests.csv + singles only — fast path for
		 *  regenerating the requests CSV). */
		public final int maxPoolingDegree;
		/** Override predecessorsFilterTime (seconds). NaN = keep config default (unbounded).
		 *  Set to e.g. 7200 to limit successor search to a 2h window — required for 100% scale. */
		public final double predecessorsFilterTime;

		ParsedArgs(int sample, String scenarioDir, String prefix, String travelTimesPath,
				String outputDir, double searchHorizon, double maxDetourFactor,
				double minDrtCostPerKm, int pruningCoverageK,
				ExMasConfigGroup.Algorithm algorithm, AlgorithmProfile profile,
				double tripFilterRadiusKm, boolean noExclusionZone,
				boolean noPredecessors, boolean noShapley, boolean deterministicRouting,
				int maxPoolingDegree, double predecessorsFilterTime) {
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
			this.profile = profile;
			this.tripFilterRadiusKm = tripFilterRadiusKm;
			this.noExclusionZone = noExclusionZone;
			this.noPredecessors = noPredecessors;
			this.noShapley = noShapley;
			this.deterministicRouting = deterministicRouting;
			this.maxPoolingDegree = maxPoolingDegree;
			this.predecessorsFilterTime = predecessorsFilterTime;
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
		AlgorithmProfile profile = null;
		double tripFilterRadiusKm = Double.NaN;
		boolean noExclusionZone = false;
		boolean noPredecessors = false;
		boolean noShapley = false;
		boolean deterministicRouting = false;
		int maxPoolingDegree = -1;
		double predecessorsFilterTime = Double.NaN;

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
				case "--trip-filter-radius-km" -> tripFilterRadiusKm = Double.parseDouble(args[++i]);
				case "--no-exclusion-zone" -> noExclusionZone = true;
				case "--no-predecessors" -> noPredecessors = true;
				case "--no-shapley" -> noShapley = true;
				case "--deterministic-routing" -> deterministicRouting = true;
				case "--max-pooling-degree" -> maxPoolingDegree = Integer.parseInt(args[++i]);
				case "--predecessors-filter-time" -> predecessorsFilterTime = Double.parseDouble(args[++i]);
				case "--profile" -> profile = switch (args[++i].toUpperCase()) {
					case "R1" -> AlgorithmProfile.R1;
					case "R2" -> AlgorithmProfile.R2;
					case "R3" -> AlgorithmProfile.R3;
					case "R4" -> AlgorithmProfile.R4;
					case "R5" -> AlgorithmProfile.R5;
					case "R6" -> AlgorithmProfile.R6;
					case "R7" -> AlgorithmProfile.R7;
					case "R8" -> AlgorithmProfile.R8;
					default -> throw new IllegalArgumentException("Unknown profile: " + args[i] + " (expected r1|r2|r3|r4|r5|r6|r7|r8)");
				};
				default -> log.warn("Unknown argument: {}", args[i]);
			}
		}
		return new ParsedArgs(sample, scenarioDir, prefix, travelTimesPath, outputDir,
				searchHorizon, maxDetourFactor, minDrtCostPerKm, pruningCoverageK, algorithm, profile,
				tripFilterRadiusKm, noExclusionZone, noPredecessors, noShapley, deterministicRouting,
				maxPoolingDegree, predecessorsFilterTime);
	}

	/**
	 * Orthogonal CLI surface introduced for the paper-1 pruning pipeline refactor
	 * (Phase A). Exposes {@code --algorithm}, {@code --gate-scale}, {@code --coverage-k}
	 * alongside the legacy {@code --profile} flag; the latter is still honored by
	 * {@link #main(String[])} via {@link #parseArgs(String[])} until task A6 retires it.
	 */
	public static final class CliArgs {
		/** Algorithm name in lowercase. Default: {@code "bamas"}. */
		public String algorithm = "bamas";
		/** Heuristic distance gate scale. {@code -1.0} = gate disabled (default). */
		public double gateScale = -1.0;
		/** Post-extension COVERAGE_TOPK budget. {@code 0} = pruning disabled (default). */
		public int coverageK = 0;
		/** Legacy paper-1 profile string (e.g. {@code "r2"}); {@code null} when unset. */
		public String profile = null;
		/** Short focus name resolved by {@link org.matsim.contrib.demand_extraction.scenarios.FocusRegistry}.
		 *  Default {@code "loyettes-3communes"} preserves pre-A2 behaviour. */
		public String tripFilterFocus = "loyettes-3communes";
		/** Explicit override of the focus x-coordinate (EPSG:2154). {@code null} = use the focus registry. */
		public Double tripFilterCenterX = null;
		/** Explicit override of the focus y-coordinate (EPSG:2154). {@code null} = use the focus registry. */
		public Double tripFilterCenterY = null;
		/** Trip-filter radius in km. Default {@code 40.0} matches the historical Lyon
		 *  fixture default; the orthogonal CliArgs path always seeds the
		 *  {@link LyonEqasimScenarioFixture.FilterConfig} from this value. */
		public double tripFilterRadiusKm = 40.0;
		/** Exclusion-zone identifier (e.g. {@code "metropole_lyon"} or {@code "none"}).
		 *  Default {@code "metropole_lyon"} preserves pre-A2 behaviour. {@code "none"} disables the exclusion. */
		public String exclusionZone = "metropole_lyon";

		public static CliArgs parse(String[] args) {
			CliArgs out = new CliArgs();
			for (int i = 0; i < args.length; i++) {
				switch (args[i]) {
					case "--algorithm" -> out.algorithm = args[++i].toLowerCase();
					case "--gate-scale" -> out.gateScale = Double.parseDouble(args[++i]);
					case "--coverage-k" -> out.coverageK = Integer.parseInt(args[++i]);
					case "--profile" -> out.profile = args[++i];
					case "--trip-filter-focus" -> out.tripFilterFocus = args[++i];
					case "--trip-filter-center-x" -> out.tripFilterCenterX = Double.parseDouble(args[++i]);
					case "--trip-filter-center-y" -> out.tripFilterCenterY = Double.parseDouble(args[++i]);
					case "--trip-filter-radius-km" -> out.tripFilterRadiusKm = Double.parseDouble(args[++i]);
					case "--exclusion-zone" -> out.exclusionZone = args[++i];
					case "--no-exclusion-zone" -> out.exclusionZone = "none"; // legacy boolean flag, equivalent to --exclusion-zone none
					default -> {
						// Skip non-orthogonal flags; full parsing happens in parseArgs(String[]).
						// Consume the value for known value-bearing flags so we don't misread it
						// as the next flag name on the following iteration.
						if (isValueBearingLegacyFlag(args[i])) {
							i++;
						}
					}
				}
			}
			return out;
		}

		// TODO(post-A6): unify with parseArgs' flag list to remove the drift risk between the two parsers.
		// Flags already handled by switch cases in parse() above (e.g. --trip-filter-radius-km,
		// --trip-filter-focus, --exclusion-zone) need not appear here, but harmlessly may; the
		// authoritative list is parseArgs(String[]).
		private static boolean isValueBearingLegacyFlag(String flag) {
			return switch (flag) {
				case "--sample", "--scenario-dir", "--prefix", "--travel-times", "--output-dir",
						"--search-horizon", "--max-detour-factor", "--min-drt-cost-per-km",
						"--pruning-coverage-k", "--trip-filter-radius-km", "--max-pooling-degree",
						"--predecessors-filter-time", "--trip-filter-focus", "--trip-filter-center-x",
						"--trip-filter-center-y", "--exclusion-zone" -> true;
				default -> false;
			};
		}
	}

	public static void main(String[] args) throws Exception {
		CliArgs cli = CliArgs.parse(args);
		ParsedArgs p = parseArgs(args);

		if (p.sample < 0 || p.scenarioDir == null || p.travelTimesPath == null) {
			System.err.println("Usage: --sample <N> --scenario-dir <path> [--prefix <s>] "
					+ "--travel-times <path> [--output-dir <path>] "
					+ "[--profile r1|r2|r3|r4] [--algorithm bamas|exmas] "
					+ "[--gate-scale <f>] [--coverage-k <int>] "
					+ "[--search-horizon <s>] [--max-detour-factor <f>] "
					+ "[--min-drt-cost-per-km <eur>] [--pruning-coverage-k <int>] "
					+ "[--trip-filter-radius-km <km>] [--no-exclusion-zone] "
					+ "[--no-predecessors] [--no-shapley] [--deterministic-routing] "
					+ "[--max-pooling-degree <int>]");
			System.exit(1);
		}

		String outputDir = p.outputDir != null
				? p.outputDir
				: "../../../outputs/lyon-eqasim-demand-extraction-" + p.sample + "pct";
		Path outDir = Path.of(outputDir);

		LyonEqasimScenarioFixture fixture = new LyonEqasimScenarioFixture(
				p.sample, p.scenarioDir, p.prefix, p.travelTimesPath,
				buildFilterConfig(cli, DEFAULT_FOCUS_REGISTRY));

		Config config = fixture.createConfig(outDir);

		ExMasConfigGroup exMas = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		if (p.profile != null) {
			// Legacy --profile path: profile sets algorithm + all pruning knobs;
			// individual --algorithm flag is ignored. Retired in task A6.
			log.info("Applying AlgorithmProfile.{} (legacy --profile path)", p.profile);
			p.profile.apply(config);
		} else {
			// New orthogonal-flag path: --algorithm + --gate-scale + --coverage-k.
			log.info("Applying orthogonal flags: algorithm={}, gateScale={}, coverageK={}",
					cli.algorithm, cli.gateScale, cli.coverageK);
			applyAlgorithmAndPruning(config, cli);
		}
		applyCliOverrides(exMas, p);

		Controler controler = fixture.createControler(config);
		controler.run();

		log.info("\n=== Lyon eqasim DRT demand extraction complete ===");
		log.info("Output: {}", outDir.toAbsolutePath());
	}

	/**
	 * Resolves {@code --trip-filter-focus} / {@code --trip-filter-center-x|-y} /
	 * {@code --trip-filter-radius-km} / {@code --exclusion-zone} into a
	 * {@link LyonEqasimScenarioFixture.FilterConfig}.
	 *
	 * <p>Explicit {@code centerX}/{@code centerY} override the focus registry.
	 * {@code --exclusion-zone none} (case-insensitive) clears the exclusion
	 * shapefile; any other value is resolved via {@link #resolveExclusionShapefile}.
	 *
	 * <p>The returned {@code exclusionShapefilePath} is a path relative to
	 * {@code scenarioDir} (matching the legacy 4-arg fixture constructor); the
	 * fixture itself joins and normalises it in {@code applyExMasDefaults}.
	 */
	static LyonEqasimScenarioFixture.FilterConfig buildFilterConfig(CliArgs args, Path registryPath) {
		double cx;
		double cy;
		if (args.tripFilterCenterX != null && args.tripFilterCenterY != null) {
			cx = args.tripFilterCenterX;
			cy = args.tripFilterCenterY;
		} else {
			FocusRegistry.Coords c = FocusRegistry.load(registryPath).resolve(args.tripFilterFocus);
			cx = c.x();
			cy = c.y();
		}
		String exclusion = "none".equalsIgnoreCase(args.exclusionZone)
				? null
				: resolveExclusionShapefile(args.exclusionZone);
		return new LyonEqasimScenarioFixture.FilterConfig(cx, cy, args.tripFilterRadiusKm, exclusion);
	}

	private static String resolveExclusionShapefile(String zoneName) {
		if ("metropole_lyon".equals(zoneName)) {
			// Relative to scenarioDir; LyonEqasimScenarioFixture.applyExMasDefaults
			// resolves+normalises it against scenarioDir at config-build time.
			return LyonEqasimScenarioFixture.EXCLUSION_ZONE_SHAPEFILE;
		}
		throw new IllegalArgumentException("Unknown exclusion zone: " + zoneName);
	}

	/**
	 * Routes the orthogonal paper-1 CLI triple ({@code --algorithm},
	 * {@code --gate-scale}, {@code --coverage-k}) into {@link ExMasConfigGroup}
	 * setters. Mirrors the <em>pruning</em> portion of
	 * {@link AlgorithmProfile#apply(Config)} only; {@code calcPredecessors} and
	 * {@code maxPoolingDegree} remain whatever the scenario fixture sets unless
	 * the caller passes {@code --no-predecessors} / {@code --max-pooling-degree}
	 * (those flags are still consumed by {@code applyCliOverrides}).
	 *
	 * <p>Idempotent: every relevant knob is written in BOTH directions so
	 * repeated calls do not leave stale state from a previous configuration.
	 */
	static void applyAlgorithmAndPruning(Config config, CliArgs args) {
		ExMasConfigGroup exMas = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);

		exMas.setAlgorithm("exmas".equalsIgnoreCase(args.algorithm)
				? ExMasConfigGroup.Algorithm.EXMAS
				: ExMasConfigGroup.Algorithm.BAMAS);

		// BamasEngine's distance-savings gate condition is `scale >= 0`.
		// gateScale=-1 (default) disables; gateScale>=0 enables with the value.
		boolean gateOn = args.gateScale >= 0.0;
		exMas.setHeuristicPruningEnabled(gateOn);
		exMas.setPruningDistanceSavingsLogScale(gateOn ? args.gateScale : -1.0);

		if (args.coverageK > 0) {
			exMas.setPruningMode(ExMasConfigGroup.PruningMode.COVERAGE_TOPK);
			exMas.setPruningCoverageK(args.coverageK);
			exMas.clearPruningCoverageKByDegree();
		} else {
			exMas.setPruningMode(ExMasConfigGroup.PruningMode.RATIO_THRESHOLD);
			exMas.setInterDegreeKeepFraction(1.0);
			exMas.clearPruningCoverageKByDegree();
		}
	}

	private static void applyCliOverrides(ExMasConfigGroup exMas, ParsedArgs p) {
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
		if (!Double.isNaN(p.tripFilterRadiusKm)) {
			log.info("  Override: tripFilterRadiusKm = {}", p.tripFilterRadiusKm);
			exMas.setTripFilterRadiusKm(p.tripFilterRadiusKm);
		}
		if (p.noExclusionZone) {
			log.info("  Override: exclusion zone disabled");
			exMas.setTripFilterExclusionShapefilePath(null);
		}
		if (p.noPredecessors) {
			log.info("  Override: predecessors disabled");
			exMas.setCalcPredecessors(false);
		}
		if (p.noShapley) {
			log.info("  Override: Shapley disabled");
			exMas.setCalcShapleyValues(false);
		}
		if (p.deterministicRouting) {
			log.info("  Override: deterministic routing enabled (shared locked router, time-only disutility)");
			exMas.setUseDeterministicNetworkRouting(true);
		}
		if (p.maxPoolingDegree > 0) {
			log.info("  Override: maxPoolingDegree = {}", p.maxPoolingDegree);
			exMas.setMaxPoolingDegree(p.maxPoolingDegree);
		}
		if (!Double.isNaN(p.predecessorsFilterTime)) {
			log.info("  Override: predecessorsFilterTime = {}s", p.predecessorsFilterTime);
			exMas.setPredecessorsFilterTime(p.predecessorsFilterTime);
		}
	}
}
