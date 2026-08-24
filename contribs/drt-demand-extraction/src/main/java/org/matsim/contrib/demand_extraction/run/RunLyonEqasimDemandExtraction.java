package org.matsim.contrib.demand_extraction.run;

import java.nio.file.Path;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
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
 *                --algorithm bamas" \
 *   -Denforcer.skip=true
 * </pre>
 *
 * <p>Drive algorithm + pruning via the orthogonal triple
 * {@code --algorithm bamas|exmas}, {@code --gate-scale <f>} (heuristic
 * distance gate; {@code -1} disables) and {@code --coverage-k <int>}
 * (post-extension COVERAGE_TOPK budget; {@code 0} disables). The legacy
 * {@code --profile R1..R8} bundle was retired in task A6.
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
		/** Override trip-filter radius (km). NaN = keep fixture default. */
		public final double tripFilterRadiusKm;
		/** When true, clears the exclusion-zone shapefile path set by the fixture. */
		public final boolean noExclusionZone;
		/** When true, disables predecessor/successor export. */
		public final boolean noPredecessors;
		/** When true, disables Shapley-value calculation. */
		public final boolean noShapley;
		/** Override maxPoolingDegree. -1 = keep config/profile default. Use 1 to skip
		 *  pair generation entirely (drt_requests.csv + singles only — fast path for
		 *  regenerating the requests CSV). */
		public final int maxPoolingDegree;
		/** Override predecessorsFilterTime (seconds). NaN = keep config default (unbounded).
		 *  Set to e.g. 7200 to limit successor search to a 2h window — required for 100% scale. */
		public final double predecessorsFilterTime;
		/** Enable stop-based ride generation in Stage 1 (HyperPool §). Default false. */
		public final boolean enableStopBased;
		/** Enable HyperPool Stage 2 (multi-stop sequences). Default false. */
		public final boolean enableHyperPooling;
		/** Enable BAMAS budget-aware per-pax caps (Phase A/B/C wiring). Default false. */
		public final boolean enableBudgetAwareConstraints;
		/** Global hard cap on per-leg walk distance (m). NaN = leave fixture default. */
		public final double maxWalkDistanceMeters;
		/** Paper-2 Extension 2: path to hub-set GeoJSON (Phase 3 output). Null = disabled. */
		public final String hubSetGeoJsonPath;
		/** Paper-2 Extension 2: scheduled hub transfer slack (seconds). NaN = keep config default (300). */
		public final double hubTransferBufferSeconds;
		/** Paper-2 Extension 2: path to request-classifications CSV (Phase 2 output). Null = disabled. */
		public final String requestClassificationsPath;
		/** Paper-2 Extension 2: which fleet leg this run generates (RURAL or URBAN).
		 *  Null = no virtual-trip expansion / no fleetSide tag drop (Kelheim + Paper-1 default). */
		public final ExMasConfigGroup.FleetSide fleetSide;
		/** Paper-2 Extension 2: metropole polygon shapefile used ONLY for connecting-request
		 *  endpoint detection during expansion, decoupled from the eligibility exclusion zone
		 *  (URBAN run sets this + no exclusion zone). Null = fall back to the exclusion polygon. */
		public final String metropolePolygonPath;
		/** Per-set ordering-enumeration node budget B (Design A). -1 = off (exact);
		 *  >=0 caps the post-first-valid DFS tail per set without dropping feasible rides.
		 *  See {@link ExMasConfigGroup#getMaxOrderingNodesAfterFirstValid()}. */
		public final long maxOrderingNodes;
		/** Plan B: per-request top-K parent pruning threshold. 0 = off (exact). */
		public final int extensionParentsTopK;
		/** Plan B: minimum degree at which the top-K parent marks apply. */
		public final int extensionParentsTopKMinDegree;
		/** Plan B: quality metric used to rank parent stubs for top-K selection. */
		public final ExMasConfigGroup.PruningQualityMetric extensionParentsTopKMetric;
		/** Plan B: selection rule (TOP_K or MMR). */
		public final ExMasConfigGroup.ExtensionParentsSelectionRule extensionParentsSelectionRule;
		/** Plan B: MMR diversity penalty lambda; 0 == plain TOP_K. */
		public final double extensionParentsMmrLambda;
		/** Plan B: total DFS node cap for unmarked parents. 0 = hard filter. */
		public final long extensionParentsTier2NodeCap;
		/** Plan A3 fork: when true, a resume accepts a pre-minDegree checkpoint under
		 *  changed parent-pruning knobs. Corresponds to {@code --checkpoint-fork-below-min-degree}. */
		public final boolean checkpointForkBelowMinDegree;
		/** Merged run (Paper-2): expand each connecting commuter on BOTH fleet sides
		 *  (rural ACCESS ∪ urban CONTINUATION legs). Default false. */
		public final boolean expandConnectingBothSides;
		/** Merged run (Paper-2): per-request-class max-detour-factor overrides
		 *  (class-key → factor). Empty = use the single global maxDetourFactor. */
		public final java.util.Map<String, Double> maxDetourFactorByClass;
		/** Merged run (Paper-2, EXT-4 rel half): per-request-class relative-flexibility
		 *  overrides (class-key → rel). Empty = FlexibilityCalculator default (0.5). */
		public final java.util.Map<String, Double> flexRelByClass;
		/** Hub-sync v1 (Paper-2): continuation-leg hub-departure window width in
		 *  seconds. NaN = unset (config default 0.0 = legacy fixed-buffer). */
		public final double maxHubWait;
		/** Hub-sync v2 (Paper-2, Task 11c): emit multiple ACCESS variants per
		 *  (commuter, hub) at earlier-departure offsets. Default false. */
		public final boolean hubSyncTwoSided;
		/** Hub-sync v2 (Paper-2, Task 11c): max seconds a commuter may depart
		 *  earlier than their request to reach an earlier sync slot. NaN = unset
		 *  (config default 900.0). */
		public final double hubSyncMaxAdvance;
		/** Degree-2 top-K partner cap (Paper-2, EXT-8): keep only the K best degree-2
		 *  partners per request during pair generation. 0 = uncapped (setter clamps
		 *  negatives to 0 = no-op). Mandatory at scale (design 2026-06-22 §3). */
		public final int pairgenTopK;
		/** HyperPool Stage-2 vehicle capacity cap (peak_pax), HYP-5. -1 = unlimited (config default). */
		public final int hyperPoolVehicleCapacity;

		ParsedArgs(int sample, String scenarioDir, String prefix, String travelTimesPath,
				String outputDir, double searchHorizon, double maxDetourFactor,
				double minDrtCostPerKm, int pruningCoverageK,
				ExMasConfigGroup.Algorithm algorithm,
				double tripFilterRadiusKm, boolean noExclusionZone,
				boolean noPredecessors, boolean noShapley,
				int maxPoolingDegree, double predecessorsFilterTime,
				boolean enableStopBased, boolean enableHyperPooling,
				boolean enableBudgetAwareConstraints, double maxWalkDistanceMeters,
				String hubSetGeoJsonPath, double hubTransferBufferSeconds,
				String requestClassificationsPath,
				ExMasConfigGroup.FleetSide fleetSide, String metropolePolygonPath,
				long maxOrderingNodes,
				int extensionParentsTopK, int extensionParentsTopKMinDegree,
				ExMasConfigGroup.PruningQualityMetric extensionParentsTopKMetric,
				ExMasConfigGroup.ExtensionParentsSelectionRule extensionParentsSelectionRule,
				double extensionParentsMmrLambda, long extensionParentsTier2NodeCap,
				boolean checkpointForkBelowMinDegree,
				boolean expandConnectingBothSides,
				java.util.Map<String, Double> maxDetourFactorByClass,
				java.util.Map<String, Double> flexRelByClass,
				double maxHubWait,
				boolean hubSyncTwoSided,
				double hubSyncMaxAdvance,
				int pairgenTopK,
				int hyperPoolVehicleCapacity) {
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
			this.tripFilterRadiusKm = tripFilterRadiusKm;
			this.noExclusionZone = noExclusionZone;
			this.noPredecessors = noPredecessors;
			this.noShapley = noShapley;
			this.maxPoolingDegree = maxPoolingDegree;
			this.predecessorsFilterTime = predecessorsFilterTime;
			this.enableStopBased = enableStopBased;
			this.enableHyperPooling = enableHyperPooling;
			this.enableBudgetAwareConstraints = enableBudgetAwareConstraints;
			this.maxWalkDistanceMeters = maxWalkDistanceMeters;
			this.hubSetGeoJsonPath = hubSetGeoJsonPath;
			this.hubTransferBufferSeconds = hubTransferBufferSeconds;
			this.requestClassificationsPath = requestClassificationsPath;
			this.fleetSide = fleetSide;
			this.metropolePolygonPath = metropolePolygonPath;
			this.maxOrderingNodes = maxOrderingNodes;
			this.extensionParentsTopK = extensionParentsTopK;
			this.extensionParentsTopKMinDegree = extensionParentsTopKMinDegree;
			this.extensionParentsTopKMetric = extensionParentsTopKMetric;
			this.extensionParentsSelectionRule = extensionParentsSelectionRule;
			this.extensionParentsMmrLambda = extensionParentsMmrLambda;
			this.extensionParentsTier2NodeCap = extensionParentsTier2NodeCap;
			this.checkpointForkBelowMinDegree = checkpointForkBelowMinDegree;
			this.expandConnectingBothSides = expandConnectingBothSides;
			this.maxDetourFactorByClass = maxDetourFactorByClass;
			this.flexRelByClass = flexRelByClass;
			this.maxHubWait = maxHubWait;
			this.hubSyncTwoSided = hubSyncTwoSided;
			this.hubSyncMaxAdvance = hubSyncMaxAdvance;
			this.pairgenTopK = pairgenTopK;
			this.hyperPoolVehicleCapacity = hyperPoolVehicleCapacity;
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
		double tripFilterRadiusKm = Double.NaN;
		boolean noExclusionZone = false;
		boolean noPredecessors = false;
		boolean noShapley = false;
		int maxPoolingDegree = -1;
		double predecessorsFilterTime = Double.NaN;
		boolean enableStopBased = false;
		boolean enableHyperPooling = false;
		boolean enableBudgetAwareConstraints = false;
		double maxWalkDistanceMeters = Double.NaN;
		String hubSetGeoJsonPath = null;
		double hubTransferBufferSeconds = Double.NaN;
		String requestClassificationsPath = null;
		ExMasConfigGroup.FleetSide fleetSide = null;
		String metropolePolygonPath = null;
		long maxOrderingNodes = -1;
		int extensionParentsTopK = 0;
		int extensionParentsTopKMinDegree = 4;
		ExMasConfigGroup.PruningQualityMetric extensionParentsTopKMetric = ExMasConfigGroup.PruningQualityMetric.ABS_SAVINGS;
		ExMasConfigGroup.ExtensionParentsSelectionRule extensionParentsSelectionRule = ExMasConfigGroup.ExtensionParentsSelectionRule.TOP_K;
		double extensionParentsMmrLambda = 0.0;
		long extensionParentsTier2NodeCap = 0L;
		boolean checkpointForkBelowMinDegree = false;
		boolean expandConnectingBothSides = false;
		java.util.Map<String, Double> maxDetourFactorByClass = new java.util.HashMap<>();
		java.util.Map<String, Double> flexRelByClass = new java.util.HashMap<>();
		double maxHubWait = Double.NaN;
		boolean hubSyncTwoSided = false;
		double hubSyncMaxAdvance = Double.NaN;
		int pairgenTopK = 0;
		int hyperPoolVehicleCapacity = -1;

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
				case "--deterministic-routing" -> log.warn(
						"--deterministic-routing is deprecated and ignored: routing is always "
						+ "deterministic (DeterministicTravelDisutility tie-breaker).");
				case "--max-pooling-degree" -> maxPoolingDegree = Integer.parseInt(args[++i]);
				case "--predecessors-filter-time" -> predecessorsFilterTime = Double.parseDouble(args[++i]);
				case "--enable-stop-based" -> enableStopBased = true;
				case "--enable-hyperpooling" -> enableHyperPooling = true;
				case "--enable-budget-aware-constraints" -> enableBudgetAwareConstraints = true;
				case "--max-walk-distance-meters" -> maxWalkDistanceMeters = Double.parseDouble(args[++i]);
				case "--hub-set" -> hubSetGeoJsonPath = args[++i];
				case "--hub-transfer-buffer" -> hubTransferBufferSeconds = Double.parseDouble(args[++i]);
				case "--request-classifications" -> requestClassificationsPath = args[++i];
				case "--fleet-side" -> fleetSide = ExMasConfigGroup.FleetSide.valueOf(
						args[++i].trim().toUpperCase(java.util.Locale.ROOT));
				case "--metropole-polygon" -> metropolePolygonPath = args[++i];
				case "--max-ordering-nodes" -> maxOrderingNodes = Long.parseLong(args[++i]);
				case "--extension-parents-top-k" -> extensionParentsTopK = Integer.parseInt(args[++i]);
				case "--extension-parents-top-k-min-degree" -> extensionParentsTopKMinDegree = Integer.parseInt(args[++i]);
				case "--extension-parents-top-k-metric" -> extensionParentsTopKMetric = ExMasConfigGroup.PruningQualityMetric.valueOf(args[++i].toUpperCase());
				case "--extension-parents-selection-rule" -> extensionParentsSelectionRule = ExMasConfigGroup.ExtensionParentsSelectionRule.valueOf(args[++i].toUpperCase());
				case "--extension-parents-mmr-lambda" -> extensionParentsMmrLambda = Double.parseDouble(args[++i]);
				case "--extension-parents-tier2-node-cap" -> extensionParentsTier2NodeCap = Long.parseLong(args[++i]);
				case "--checkpoint-fork-below-min-degree" -> checkpointForkBelowMinDegree = true;
				case "--expand-connecting-both-sides" -> expandConnectingBothSides = true;
				case "--max-detour-factor-by-class" -> maxDetourFactorByClass = parseClassFactorMap(args[++i]);
				case "--flex-rel-by-class" -> flexRelByClass = parseClassFactorMap(args[++i]);
				case "--max-hub-wait" -> maxHubWait = Double.parseDouble(args[++i]);
				case "--hub-sync-twosided" -> hubSyncTwoSided = true;
				case "--hub-sync-max-advance" -> hubSyncMaxAdvance = Double.parseDouble(args[++i]);
				case "--pairgen-top-k" -> pairgenTopK = Integer.parseInt(args[++i]);
				case "--hyperpool-vehicle-capacity" -> hyperPoolVehicleCapacity = Integer.parseInt(args[++i]);
				default -> log.warn("Unknown argument: {}", args[i]);
			}
		}
		return new ParsedArgs(sample, scenarioDir, prefix, travelTimesPath, outputDir,
				searchHorizon, maxDetourFactor, minDrtCostPerKm, pruningCoverageK, algorithm,
				tripFilterRadiusKm, noExclusionZone, noPredecessors, noShapley,
				maxPoolingDegree, predecessorsFilterTime,
				enableStopBased, enableHyperPooling, enableBudgetAwareConstraints, maxWalkDistanceMeters,
				hubSetGeoJsonPath, hubTransferBufferSeconds, requestClassificationsPath, fleetSide, metropolePolygonPath,
				maxOrderingNodes,
				extensionParentsTopK, extensionParentsTopKMinDegree, extensionParentsTopKMetric,
				extensionParentsSelectionRule, extensionParentsMmrLambda, extensionParentsTier2NodeCap,
				checkpointForkBelowMinDegree,
				expandConnectingBothSides, maxDetourFactorByClass, flexRelByClass, maxHubWait,
				hubSyncTwoSided, hubSyncMaxAdvance, pairgenTopK, hyperPoolVehicleCapacity);
	}

	/**
	 * Parses a {@code k=v,k=v} per-class detour-factor spec (e.g.
	 * {@code "connecting=1.3,rural_intra=1.2"}) into a class-key → factor map.
	 * Whitespace around keys/values is trimmed; an empty / blank spec yields an empty map.
	 *
	 * <p>Accepted key forms (resolved most-specific first by
	 * {@link org.matsim.contrib.demand_extraction.demand.DrtRequestFactory#resolveClassFactor},
	 * EXT-4):
	 * <ul>
	 *   <li>{@code tag} — a bare request class, e.g. {@code connecting},
	 *       {@code rural_intra}, {@code urban_intra}, {@code connecting-direct}
	 *       (the direct O→D copy of a connecting request).</li>
	 *   <li>{@code tag:ACCESS_LEG} — the role-specific factor for the hub-leg copy
	 *       serving the journey ORIGIN leg (O→hub), e.g. {@code connecting:ACCESS_LEG}.</li>
	 *   <li>{@code tag:CONTINUATION_LEG} — the role-specific factor for the hub-leg
	 *       copy serving the journey DESTINATION leg (hub→D), e.g.
	 *       {@code connecting:CONTINUATION_LEG}.</li>
	 * </ul>
	 * A role-specific {@code tag:ROLE} key overrides the bare {@code tag} key, which
	 * in turn overrides the global {@code --max-detour-factor}.
	 */
	private static java.util.Map<String, Double> parseClassFactorMap(String spec) {
		java.util.Map<String, Double> out = new java.util.HashMap<>();
		if (spec == null || spec.isBlank()) {
			return out;
		}
		for (String entry : spec.split(",")) {
			if (entry.isBlank()) {
				continue;
			}
			String[] kv = entry.split("=", 2);
			if (kv.length != 2) {
				throw new IllegalArgumentException(
						"Invalid --max-detour-factor-by-class entry (expected k=v): " + entry);
			}
			out.put(kv[0].trim(), Double.parseDouble(kv[1].trim()));
		}
		return out;
	}

	/**
	 * Orthogonal CLI surface for the paper-1 pruning pipeline. Exposes
	 * {@code --algorithm}, {@code --gate-scale}, {@code --coverage-k} as the
	 * sole way to drive algorithm + pruning since task A6 retired the legacy
	 * {@code --profile R1..R8} bundle.
	 */
	public static final class CliArgs {
		/** Algorithm name in lowercase. Default: {@code "bamas"}. */
		public String algorithm = "bamas";
		/** Heuristic distance gate scale (log gate). {@code -1.0} = gate disabled (default). */
		public double gateScale = -1.0;
		/** Linear-gate intercept a in gate(d)=a+b·d. NaN = linear gate disabled. */
		public double gateIntercept = Double.NaN;
		/** Linear-gate slope b in gate(d)=a+b·d. NaN = linear gate disabled. */
		public double gateSlope = Double.NaN;
		/** Post-extension COVERAGE_TOPK budget. {@code 0} = pruning disabled (default). */
		public int coverageK = 0;
		/** Short focus name resolved by {@link org.matsim.contrib.demand_extraction.scenarios.FocusRegistry}.
		 *  Default {@code "loyettes-3communes"} preserves pre-A2 behaviour. */
		public String tripFilterFocus = "loyettes-3communes";
		/** Explicit override of the focus x-coordinate (EPSG:2154). {@code null} = use the focus registry. */
		public Double tripFilterCenterX = null;
		/** Explicit override of the focus y-coordinate (EPSG:2154). {@code null} = use the focus registry. */
		public Double tripFilterCenterY = null;
		/** Trip-filter radius in km. Default matches the historical Lyon fixture
		 *  default; the orthogonal CliArgs path always seeds the
		 *  {@link LyonEqasimScenarioFixture.FilterConfig} from this value. */
		public double tripFilterRadiusKm = LyonEqasimScenarioFixture.TRIP_FILTER_RADIUS_KM;
		/** Exclusion-zone identifier (e.g. {@code "metropole_lyon"} or {@code "none"}).
		 *  Default {@code "metropole_lyon"} preserves pre-A2 behaviour. {@code "none"} disables the exclusion. */
		public String exclusionZone = "metropole_lyon";

		public static CliArgs parse(String[] args) {
			CliArgs out = new CliArgs();
			for (int i = 0; i < args.length; i++) {
				switch (args[i]) {
					case "--algorithm" -> out.algorithm = args[++i].toLowerCase();
					case "--gate-scale" -> out.gateScale = Double.parseDouble(args[++i]);
					case "--gate-intercept" -> out.gateIntercept = Double.parseDouble(args[++i]);
					case "--gate-slope" -> out.gateSlope = Double.parseDouble(args[++i]);
					case "--coverage-k" -> out.coverageK = Integer.parseInt(args[++i]);
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
						"--trip-filter-center-y", "--exclusion-zone", "--gate-intercept",
						"--gate-slope", "--max-walk-distance-meters", "--hub-set",
						"--hub-transfer-buffer",
						"--request-classifications", "--fleet-side", "--metropole-polygon",
						"--max-ordering-nodes",
						"--extension-parents-top-k", "--extension-parents-top-k-min-degree",
						"--extension-parents-top-k-metric", "--extension-parents-selection-rule",
						"--extension-parents-mmr-lambda", "--extension-parents-tier2-node-cap",
						"--pairgen-top-k" -> true;
				default -> false;
			};
		}
	}

	/** Returns true iff {@code args} contains the orchestrator-trigger flag. */
	static boolean hasLowMemoryFlag(String[] args) {
		for (String a : args) {
			if ("--low-memory".equals(a)) {
				return true;
			}
		}
		return false;
	}

	/** Returns a copy of {@code args} with every {@code --low-memory} occurrence removed
	 *  (boolean flag — no associated value to skip). */
	static String[] stripLowMemoryFlag(String[] args) {
		java.util.List<String> kept = new java.util.ArrayList<>(args.length);
		for (String a : args) {
			if (!"--low-memory".equals(a)) {
				kept.add(a);
			}
		}
		return kept.toArray(new String[0]);
	}

	public static void main(String[] args) throws Exception {
		LoggingSetup.configure();
		if (hasLowMemoryFlag(args)) {
			log.info("--low-memory present — delegating to RunDemandExtractionTwoPhase orchestrator");
			RunDemandExtractionTwoPhase.main(stripLowMemoryFlag(args));
			return;
		}
		CliArgs cli = CliArgs.parse(args);
		ParsedArgs p = parseArgs(args);

		if (p.sample < 0 || p.scenarioDir == null || p.travelTimesPath == null) {
			System.err.println("Usage: --sample <N> --scenario-dir <path> [--prefix <s>] "
					+ "--travel-times <path> [--output-dir <path>] "
					+ "[--low-memory] "
					+ "[--algorithm bamas|exmas] "
					+ "[--gate-scale <f> | --gate-intercept <a> --gate-slope <b>] [--coverage-k <int>] "
					+ "[--search-horizon <s>] [--max-detour-factor <f>] "
					+ "[--min-drt-cost-per-km <eur>] [--pruning-coverage-k <int>] "
					+ "[--trip-filter-radius-km <km>] [--no-exclusion-zone] "
					+ "[--no-predecessors] [--no-shapley] "
					+ "[--max-pooling-degree <int>] "
					+ "[--enable-stop-based] [--enable-hyperpooling] "
					+ "[--hyperpool-vehicle-capacity <int>] "
					+ "[--enable-budget-aware-constraints] [--max-walk-distance-meters <m>] "
					+ "[--hub-set <geojson-path>] "
					+ "[--hub-transfer-buffer <seconds>] "
					+ "[--request-classifications <csv-path>] "
					+ "[--fleet-side RURAL|URBAN] "
					+ "[--metropole-polygon <shapefile-path>]");
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
		// Orthogonal-flag path: --algorithm + (--gate-scale | --gate-intercept/--gate-slope) + --coverage-k.
		log.info("Applying orthogonal flags: algorithm={}, gateScale={}, gateIntercept={}, gateSlope={}, coverageK={}",
				cli.algorithm, cli.gateScale, cli.gateIntercept, cli.gateSlope, cli.coverageK);
		applyAlgorithmAndPruning(config, cli);
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
	 * setters. {@code calcPredecessors} and {@code maxPoolingDegree} remain
	 * whatever the scenario fixture sets unless the caller passes
	 * {@code --no-predecessors} / {@code --max-pooling-degree} (those flags
	 * are still consumed by {@code applyCliOverrides}).
	 *
	 * <p>Idempotent: every relevant knob is written in BOTH directions so
	 * repeated calls do not leave stale state from a previous configuration.
	 */
	static void applyAlgorithmAndPruning(Config config, CliArgs args) {
		ExMasConfigGroup exMas = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);

		exMas.setAlgorithm("exmas".equalsIgnoreCase(args.algorithm)
				? ExMasConfigGroup.Algorithm.EXMAS
				: ExMasConfigGroup.Algorithm.BAMAS);

		// Distance-savings gate: linear gate takes precedence if both intercept
		// and slope are finite. Otherwise fall back to log gate via gate-scale.
		boolean linearGate = Double.isFinite(args.gateIntercept) && Double.isFinite(args.gateSlope);
		boolean logGateOn = args.gateScale >= 0.0;
		exMas.setHeuristicPruningEnabled(linearGate || logGateOn);
		if (linearGate) {
			exMas.setPruningGateLinearIntercept(args.gateIntercept);
			exMas.setPruningGateLinearSlope(args.gateSlope);
			exMas.setPruningDistanceSavingsLogScale(-1.0);
		} else {
			exMas.setPruningGateLinearIntercept(Double.NaN);
			exMas.setPruningGateLinearSlope(Double.NaN);
			exMas.setPruningDistanceSavingsLogScale(logGateOn ? args.gateScale : -1.0);
		}

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
		if (p.maxPoolingDegree > 0) {
			log.info("  Override: maxPoolingDegree = {}", p.maxPoolingDegree);
			exMas.setMaxPoolingDegree(p.maxPoolingDegree);
		}
		if (!Double.isNaN(p.predecessorsFilterTime)) {
			log.info("  Override: predecessorsFilterTime = {}s", p.predecessorsFilterTime);
			exMas.setPredecessorsFilterTime(p.predecessorsFilterTime);
		}
		if (p.enableStopBased) {
			log.info("  Override: stop-based ride generation enabled");
			exMas.setEnableStopBased(true);
		}
		if (p.enableHyperPooling) {
			log.info("  Override: hyper-pooling enabled");
			exMas.setEnableHyperPooling(true);
		}
		if (p.hyperPoolVehicleCapacity > 0) {
			log.info("  Override: hyperPoolMaxVehicleCapacity = {}", p.hyperPoolVehicleCapacity);
			exMas.setHyperPoolMaxVehicleCapacity(p.hyperPoolVehicleCapacity);
		}
		if (p.enableBudgetAwareConstraints) {
			log.info("  Override: budget-aware per-pax caps enabled");
			exMas.setEnableBudgetAwareConstraints(true);
		}
		if (!Double.isNaN(p.maxWalkDistanceMeters)) {
			log.info("  Override: maxWalkDistanceMeters = {} m", p.maxWalkDistanceMeters);
			exMas.setMaxWalkDistanceMeters(p.maxWalkDistanceMeters);
		}
		if (p.hubSetGeoJsonPath != null) {
			log.info("  Override: hubSetGeoJsonPath = {}", p.hubSetGeoJsonPath);
			exMas.setHubSetGeoJsonPath(p.hubSetGeoJsonPath);
		}
		if (!Double.isNaN(p.hubTransferBufferSeconds)) {
			log.info("  Override: hubTransferBufferSeconds = {}", p.hubTransferBufferSeconds);
			exMas.setHubTransferBufferSeconds(p.hubTransferBufferSeconds);
		}
		if (p.requestClassificationsPath != null) {
			log.info("  Override: requestClassificationsPath = {}", p.requestClassificationsPath);
			exMas.setRequestClassificationsPath(p.requestClassificationsPath);
		}
		if (p.fleetSide != null) {
			log.info("  Override: fleetSide = {}", p.fleetSide);
			exMas.setFleetSide(p.fleetSide);
		}
		if (p.metropolePolygonPath != null) {
			log.info("  Override: metropolePolygonPath = {}", p.metropolePolygonPath);
			exMas.setMetropolePolygonPath(p.metropolePolygonPath);
		}
		if (p.expandConnectingBothSides) {
			log.info("  Override: expandConnectingBothSides = true (merged both-sides run)");
			exMas.setExpandConnectingBothSides(true);
		}
		if (!p.maxDetourFactorByClass.isEmpty()) {
			log.info("  Override: maxDetourFactorByClass = {}", p.maxDetourFactorByClass);
			exMas.setMaxDetourFactorByClass(p.maxDetourFactorByClass);
		}
		if (!p.flexRelByClass.isEmpty()) {
			log.info("  Override: flexRelativeByClass = {}", p.flexRelByClass);
			exMas.setFlexRelativeByClass(p.flexRelByClass);
		}
		if (!Double.isNaN(p.maxHubWait)) {
			log.info("  Override: maxHubWaitSeconds = {}", p.maxHubWait);
			exMas.setMaxHubWaitSeconds(p.maxHubWait);
		}
		if (p.hubSyncTwoSided) {
			log.info("  Override: hubSyncTwoSided = true (hub-sync v2 access variants)");
			exMas.setHubSyncTwoSided(true);
		}
		if (!Double.isNaN(p.hubSyncMaxAdvance)) {
			log.info("  Override: hubSyncMaxAdvanceSeconds = {}", p.hubSyncMaxAdvance);
			exMas.setHubSyncMaxAdvanceSeconds(p.hubSyncMaxAdvance);
		}
		if (p.pairgenTopK > 0) {
			log.info("  Override: pairgenTopK = {} (degree-2 top-K partner cap)", p.pairgenTopK);
		}
		exMas.setPairgenTopK(p.pairgenTopK);  // setter clamps at 0 = no-op default
		if (p.maxOrderingNodes >= 0) {
			log.info("  Override: maxOrderingNodesAfterFirstValid = {}", p.maxOrderingNodes);
			exMas.setMaxOrderingNodesAfterFirstValid(p.maxOrderingNodes);
		}
		exMas.setExtensionParentsTopK(p.extensionParentsTopK);
		exMas.setExtensionParentsTopKMinDegree(p.extensionParentsTopKMinDegree);
		exMas.setExtensionParentsTopKMetric(p.extensionParentsTopKMetric);
		exMas.setExtensionParentsSelectionRule(p.extensionParentsSelectionRule);
		exMas.setExtensionParentsMmrLambda(p.extensionParentsMmrLambda);
		exMas.setExtensionParentsTier2NodeCap(p.extensionParentsTier2NodeCap);
		if (p.checkpointForkBelowMinDegree) {
			log.info("  Override: checkpointForkBelowMinDegree = true");
			exMas.setCheckpointForkBelowMinDegree(true);
		}
	}
}
