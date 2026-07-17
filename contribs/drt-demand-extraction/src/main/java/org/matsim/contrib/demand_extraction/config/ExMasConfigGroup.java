package org.matsim.contrib.demand_extraction.config;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.matsim.api.core.v01.TransportMode;
import org.matsim.core.config.Config;
import org.matsim.core.config.ReflectiveConfigGroup;

public class ExMasConfigGroup extends ReflectiveConfigGroup {
    public static final String GROUP_NAME = "exmas";

    private static final String BUDGET_CALCULATION_MODE = "budgetCalculationMode";
    private static final String DRT_MODE = "drtMode";

    public enum BudgetCalculationMode {
        TRIP_LEVEL,
        SUBTOUR_SUM
    }

    /**
     * Stage-1 ride-generation algorithm. Selects the {@link
     * org.matsim.contrib.demand_extraction.algorithm.ExMasAlgorithm} strategy
     * bound by {@link org.matsim.contrib.demand_extraction.algorithm.ExMasAlgorithmModule}.
     * Default is {@code BAMAS} so existing runners and tests keep their current
     * behaviour; {@code EXMAS} opts into the frozen reference port under
     * {@code algorithm/exmas/}.
     */
    public enum Algorithm { EXMAS, BAMAS }

    /**
     * Filter mode for commute trips.
     * - ALL: Include all trips regardless of commute status
     * - COMMUTES_ONLY: Only include commute trips (home->work, work->home)
     * - COMMUTES_AND_EDUCATION: Include commute and education trips (home->education, education->home)
     * - NON_COMMUTES: Exclude commute trips
     */
    public enum CommuteFilter {
        ALL,
        COMMUTES_ONLY,
        COMMUTES_AND_EDUCATION,
        NON_COMMUTES
    }

    private BudgetCalculationMode budgetCalculationMode = BudgetCalculationMode.TRIP_LEVEL;
    private String drtMode = "drt";

    // Commute identification settings
    private String homeActivityType = "home";
    private String workActivityType = "work";
    private String educationActivityType = "education";
    private CommuteFilter commuteFilter = CommuteFilter.ALL;

    // Person filtering settings
    private int minAge = 18; // Minimum age to use DRT (if age attribute exists)
    private String drtAvailabilityAttribute = null; // Person attribute to check for DRT eligibility (e.g. "hasLicense")

    // Trip-level spatial filter: only extract trips where BOTH origin and destination
    // are within a radius of a center point. 0.0 = disabled.
    private double tripFilterRadiusKm = 0.0;
    private double tripFilterCenterX = Double.NaN;
    private double tripFilterCenterY = Double.NaN;

    // Trip-level exclusion zone: drop trips where BOTH origin AND destination fall
    // inside the polygon defined by this shapefile. Intended to strip intra-urban
    // demand from a rural-to-urban service (e.g., exclude pure intra-Métropole
    // de Lyon trips). Null = disabled.
    private String tripFilterExclusionShapefilePath = null;

    // Paper-2 Extension 2: polygon used ONLY to detect which endpoint of a
    // connecting request is urban during virtual-trip expansion. Decoupled from
    // the eligibility exclusion zone above so the URBAN fleet run can supply the
    // metropole geometry WITHOUT it acting as a both-endpoints-inside exclusion
    // (which would drop the urban_intra trips the urban fleet must serve). Null =
    // fall back to tripFilterExclusionShapefilePath (rural run / Kelheim default).
    private String metropolePolygonPath = null;

	// Scoring adapter selection: "auto" (default), "planCalcScore", "dmc", "eqasim"

	// ===========================================
	// Demand extraction — scoring adapter, modes, DRT service quality & flexibility
	// ===========================================
	private String scoringAdapter = "auto";

	// Stage-1 algorithm (BAMAS = current; EXMAS = reference ported from main).
	private Algorithm algorithm = Algorithm.BAMAS;

	// Tour evaluation mode for budget calculation
	public enum TourEvaluationMode { TRIP_INDEPENDENT, GREEDY_PREFIX }
	private TourEvaluationMode tourEvaluationMode = TourEvaluationMode.TRIP_INDEPENDENT;

	/** Opportunity cost model for trip-level scoring. */
	public enum OpportunityCostModel {
		/** No opportunity cost added. */
		NONE,
		/** Constant rate: marginalUtilityOfPerforming_s * travelTime (MATSim default approximation). */
		LINEAR,
		/** Exact log-utility: min over origin/dest of beta_perf * t_typ * ln(t_actual / (t_actual - tt)).
		 *  Falls back to LINEAR for activities without typicalDuration. */
		LOG
	}

	// Override marginalUtilityOfMoney (utils/EUR) for maxCost conversion.
	// Only needed when planCalcScore has dummy values AND no dedicated adapter.
	// Standard MATSim/DMC: auto-detected from planCalcScore.
	// eqasim: auto-detected from eqasim adapter.
	// Custom: must be set here.
	private Double marginalUtilityOfMoneyOverride = null;

	// Base modes to evaluate for budget calculation (e.g., car, pt, walk, bike)
	// Each mode will be routed using its own routing module
	private Set<String> baseModes = Set.of("car", "pt", "walk", "bike");

	// Routing mode to use for DRT when no DRT routing module is registered
	// Typically "car" for network-based routing or the DRT mode name if module
	// exists
	private String drtRoutingMode = "car";

	// Network link mode filter for HyperPool stop-finding (StopFinderFactory ->
	// LinkCandidateFinder). Only links whose allowedModes intersect this set are
	// considered as stop candidates. Empty/null = all links admitted (no filtering).
	// Example: Set.of("car") = only car-accessible links;
	// Set.of("car", "truck") = links where car OR truck is allowed.
	private Set<String> drtAllowedModes = Set.of("car");

	// Modes that represent private vehicles (create subtour dependencies)
	// Default: car and bike (modes that need to return to their origin)
	private Set<String> privateVehicleModes = Set.of("car", "bike");

	// Plan-level modes that should NOT produce DRT requests — e.g., car_passenger
	// in eqasim IDF scenarios, whose estimator is ZeroUtilityEstimator and whose
	// "best baseline" score is therefore meaningless. Without this filter such
	// trips are silently reassigned to pt/walk/bike and inflate demand. Default
	// empty = no filter (preserves Bavaria behaviour).
	private Set<String> excludedTripModes = Set.of();

	// DRT service quality parameters for budget calculation
	// These define the "best possible" service level used to calculate the baseline
	// DRT score
	// During optimization, these constraints can be relaxed until the utility
	// budget is "spent"
	private double minDrtCostPerKm = 0.0; // Minimum fare per kilometer (€/km)
	private double minMaxDetourFactor = 1.0; // Minimum detour factor (1.0 = direct route)
	private double minDrtAccessEgressDistance = 0.0; // Minimum access/egress distance (meters)
	private double minMaxWaitingTime = 0.0; // Minimum maximum waiting time (minutes)
	
	
	// Maximum detour factor: Maximum acceptable detour as factor of direct travel time
	// Example: 1.5 means maximum travel time = 1.5 * direct travel time

	private double maxDetourFactor = 1.5; // Maximum detour factor (50% longer than direct)

	// Per-class maxDetourFactor override. Key = requestTag; value = factor (e.g. 1.05 for connecting,
	// 1.3 for rural_intra). Requests whose tag is absent fall back to maxDetourFactor (global).
	// Not XML-serialized — set programmatically (mirrors pruningCoverageKByDegree).
	private Map<String, Double> maxDetourFactorByClass = new HashMap<>();

	private Integer maxAbsoluteDetour = null; // Absolute detour cap (seconds). If set, limits the max detour time.

	// Sampling settings
	private double requestSampleSize = 1.0; // Fraction of requests to keep (0.0-1.0)
	private Integer requestCount = null; // Absolute number of requests to keep (overrides fraction)

	// Advanced Flexibility Configuration (Attribute-based)
	// Positive Flexibility (Late Departure / Late Arrival)
	private String positiveFlexibilityAttribute = null; // Person attribute name
	private String positiveFlexibilityAbsoluteMap = null; // Map "value:seconds,value:seconds"
	private String positiveFlexibilityRelativeMap = null; // Map "value:factor,value:factor"

	// Negative Flexibility (Early Departure)
	private String negativeFlexibilityAttribute = null; // Person attribute name
	private String negativeFlexibilityAbsoluteMap = null; // Map "value:seconds,value:seconds"
	private String negativeFlexibilityRelativeMap = null; // Map "value:factor,value:factor"

	// ===========================================
	// Routing & connection-cache bucketing
	// ===========================================
	private int networkTimeBinSize = 60 * 60; // Network cache time bin size in seconds (1 hour)

	// ── cache eviction watermark (design 2026-06-12-connection-cache-memory-design §3).
	// Fraction of -Xmx above which the speculative routing-cache tier rotates a generation out.
	// 1.0 = never evict (memory-rich boxes); lower = more aggressive. Eviction in pair-gen /
	// extension is output-invariant — an evicted segment routed within the global-max SSSP bound is
	// settled optimally, so a later re-route reproduces bit-identical values (SpeedyALT ==
	// LeastCostPathTree on every shared OD, CrossEngineRoutingDeterminismTest, 403,785 ODs / 0 diffs).
	// The predecessor pass — the one consumer that needs values beyond global-max — does NOT rely on
	// this: it drops the speculative tier at its barrier and re-routes its handoffs fresh under its
	// own bound.
	private double cacheEvictionWatermark = 0.7;

	// ExMAS algorithm parameters

	// ===========================================
	// Ride generation & enumeration
	// ===========================================
	private double searchHorizon = 600.0; // Time horizon for pairing requests (seconds, 10 minutes)
	private int maxPoolingDegree = Integer.MAX_VALUE; // Maximum number of passengers per ride

	/**
	 * Per-set ordering-enumeration node budget B (Design A). 0 (default) = disabled
	 * = exact, current behaviour. When &gt; 0, the high-degree ordering DFS descends
	 * to the first budget-valid ordering unconditionally (so every feasible set keeps
	 * a ride), then explores at most B more DFS nodes before returning the best ride
	 * found so far. Bounds the post-first-valid tail that dominates high-degree cost
	 * (deg-8/9), making BAMAS extension tractable without reducing pooling degree.
	 *
	 * <p><b>This is an ABSOLUTE node count, not a per-degree factor — by design.</b>
	 * The quality window (nodes from first-valid to the best ordering) grows roughly
	 * 10x per degree (deg6 p95 ~17k -&gt; deg9 ~77M on the 1% urban smoke), so a fixed
	 * B keeps the exact best at low degree (where it never binds: deg6/7 medians are
	 * 27k/400k total nodes) and forces a near-best ride at high degree (where the cap
	 * is needed). A degree-scaling B would loosen the cap exactly where cost explodes,
	 * re-admitting the deg-9 blow-up the cap exists to remove. Do NOT make it adaptive.
	 *
	 * <p><b>Recommended values</b> (fraction = sets keeping the exact-best ride;
	 * speedup = node reduction; 0 rides are ever lost at any B since descend-to-first-
	 * valid is unconditional):
	 * <ul>
	 *   <li>0 — off, exact (current behaviour).</li>
	 *   <li>200000 — aggressive: ~97% exact-best, ~5.4x fewer nodes.</li>
	 *   <li>1000000 — conservative: ~99% exact-best, ~3x fewer nodes.</li>
	 * </ul>
	 * Practical floor ~100k (below the ~108k improvement-window p95 the exact-best
	 * fraction drops steeply). Those fractions are from the 1% urban smoke; at 100%
	 * the high-degree population grows so the exact-best fraction at a given B is
	 * lower (degrades gracefully, still no rides lost).
	 *
	 * <p><b>Caveat:</b> B caps only the post-first-valid tail; it does NOT bound
	 * {@code nodesToFirstValid} (the floor), so a deep-first-valid high-degree set
	 * still costs {@code firstValid + B}. Bounding the floor needs a separate hard
	 * total-node cap (deferred), which would drop those sets' rides.
	 */
	private long maxOrderingNodesAfterFirstValid = 0;

	// PT routing settings
	// If true, allows the PT router to optimize departure time to reduce waiting
	// This means agents can leave earlier/later to catch better PT connections
	private boolean ptOptimizeDepartureTime = true;

	// Opportunity cost model for trip-level scoring:
	// NONE    = no opportunity cost added
	// LINEAR  = constant rate: marginalUtilityOfPerforming_s * travelTime (MATSim default approximation)
	// LOG     = exact log-utility: min over origin/dest of beta_perf * t_typ * ln(t_actual / (t_actual - tt))
	//           Falls back to LINEAR for activities without typicalDuration.
	// Essential when marginalUtilityOfTraveling is zero (e.g. Kelheim PT)
	private OpportunityCostModel opportunityCostModel = OpportunityCostModel.LINEAR;

	// If true, amortizes each mode's dailyMonetaryConstant into trip-level scoring.
	// The daily constant (e.g. car's -5.3 EUR/day) is spread over the person's total
	// daily trip distance: amortizedUtils = dailyConstant * margUtilOfMoney * (tripDist / totalDailyDist).
	// Without this, daily costs are ignored in trip scoring, making car look cheaper than it is.
	private boolean amortizeDailyMonetaryConstants = false;

	// Heuristics and post-processing settings (align with exmas_pipeline.heuristics)
	// Controls parallelism in the ExMAS *core algorithm* (pair generation + extensions)
	// -1 => use parallel streams (default); 1 => force sequential (for reproducible results)
	private int algorithmProcessCount = -1;

	// Controls parallelism for expensive metrics (Shapley, predecessors)
	// -1 => use all available processors; 1 => force sequential
	private int heuristicsProcessCount = -1;

	// Heuristic pruning (to control combinatorial growth during ride extension)

	// ===========================================
	// Pruning & selection — distance gate + post-extension / extension-parent selection
	// ===========================================
	private boolean pruningEnabled = true;
	// Degree-aware distance savings pruning (applied vs serving requests separately)
	// requiredSaving(d) = min(maxSaving, max(0, pruningDistanceSavingsLogScale * log2(d)))
	// Keep ride iff: rideDistance <= (1 - requiredSaving(d)) * sum(request distances)
	// Semantics:
	// - scale < 0 : disable this pruning gate
	// - scale = 0 : legacy behavior (non-improving filter): rideDistance <= sum(request distances)
	// - scale > 0 : require additional distance savings that increases with degree
	private double pruningDistanceSavingsLogScale = 0.0;
	// Clamp for requiredSaving(d) to avoid impossible constraints at high degrees.
	private double pruningDistanceSavingsMax = 0.75;
	// Apply distance-savings pruning only for rides with degree >= this value.
	// Default 3 ensures paired rides (degree 2) are not removed, which is important
	// for shareability graph connectivity.
	private int pruningDistanceSavingsMinDegree = 3;

	// Alternative linear gate: gate(d) = intercept + slope * d, used in place of the
	// log gate when intercept is finite (not NaN). Keep ride iff:
	//   rideDistance <= gate(d) * sum(request distances)
	// equivalent to requiredSaving(d) = 1 - gate(d) (can be negative when gate>1,
	// in which case the gate accepts even rides with ratio > 1).
	// The gate is floored at (1 - pruningDistanceSavingsMax) to avoid impossibly
	// tight gates at high degrees. minDegree and the scale<0 disable rule do NOT
	// apply to the linear gate — it is always-on once configured.
	private double pruningGateLinearIntercept = Double.NaN;
	private double pruningGateLinearSlope = Double.NaN;

	// Post-graph pair pruning: keep top fraction of degree-2 rides (by distance savings)
	// after the shareability graph is built. Applied AFTER best-per-set dedup and AFTER
	// the distance-savings gate. 1.0 = disabled. 0.50 = keep top 50%.
	private double pairKeepTopFraction = 1.0;

	// Inter-degree pruning: keep only the top fraction of rides after EACH degree extension.
	// Applied directly (no sqrt scaling). 1.0 = disabled. 0.10 = keep top 10%.
	// Used only when pruningMode == RATIO_THRESHOLD (legacy).
	private double interDegreeKeepFraction = 0.10;

	// Pruner algorithm selection.
	//   RATIO_THRESHOLD  — legacy: keep top (interDegreeKeepFraction) of each degree by savingsRatio.
	//   COVERAGE_TOPK    — per-request top-K by quality metric; every request keeps up to K options.
	// Default is COVERAGE_TOPK (see .project-memory/pruning-quality-analysis-2026-04-17.md).
	public enum PruningMode { RATIO_THRESHOLD, COVERAGE_TOPK }
	private PruningMode pruningMode = PruningMode.COVERAGE_TOPK;

	// Coverage pruner: per-request top-K cap. 20 is Pareto-minimal in cascade simulation
	// (dominates legacy RATIO_THRESHOLD at interDegreeKeepFraction=0.10 on all metrics).
	// Used only when pruningMode == COVERAGE_TOPK.
	private int pruningCoverageK = 20;

	// Per-degree K override for COVERAGE_TOPK pruning.
	// Key = output ride degree (3, 4, 5, ...). If empty, pruningCoverageK is used for all degrees.
	// Not XML-serialized — set programmatically for K-schedule sweeps.
	private Map<Integer, Integer> pruningCoverageKByDegree = new HashMap<>();

	// Quality metric used to rank rides inside a pruning pass.
	//   ABS_SAVINGS   — meters saved = sum(request.directDistance) - ride.rideDistance.
	//   RATIO_SAVINGS — 1 - ride.rideDistance / sum(request.directDistance). Degree-invariant.
	// Coverage pruner benefits from ABS_SAVINGS (no seed-pool collapse under coverage cap).
	public enum PruningQualityMetric { ABS_SAVINGS, RATIO_SAVINGS, OP_COST_PER_PAX }
	private PruningQualityMetric pruningQualityMetric = PruningQualityMetric.ABS_SAVINGS;

	// ── extension_parents_top_k knob (Plan B) — demand-shaped top-K parent pruning.
	// Default OFF/exact (K=0). Marks a degree's stub EXTEND if it is among the top-K
	// stubs for at least one member request; only marked stubs enter the producer loop.
	private int extensionParentsTopK = 0;                     // 0 = off = exact
	private int extensionParentsTopKMinDegree = 4;            // marks apply only at degree >= this
	private PruningQualityMetric extensionParentsTopKMetric = PruningQualityMetric.ABS_SAVINGS;
	public enum ExtensionParentsSelectionRule { TOP_K, MMR }
	private ExtensionParentsSelectionRule extensionParentsSelectionRule = ExtensionParentsSelectionRule.TOP_K;
	private double extensionParentsMmrLambda = 0.0;           // diversity penalty; 0 == plain TOP_K
	private long extensionParentsTier2NodeCap = 0L;          // 0 = hard filter; >0 = PER-unmarked-parent nodes-to-first-valid cap (per-parent ⇒ deterministic, no shared budget)

	// ── pairgen_top_k knob — degree-2 partner cap applied DURING pair generation,
	// before the shareability graph is built. 0 = off = byte-identical to pre-knob.
	// Each request keeps its top-K partners by absolute distance saving. Being a
	// @StringGetter param it is included in RunFingerprint.getParams() (changes the
	// degree-2 universe, so it is intentionally NOT forkable). See
	// docs/plans/2026-06-18-degree2-topk-pairgen-design.md.
	private int pairgenTopK = 0;

	// ── checkpoint/resume (Plan A3). Directory for per-degree stub checkpoints + the
	// connection-cache journal. Empty string ("") = checkpointing OFF (no code path active).
	// When set, every degree barrier persists its RideLayer + (at loop entry) the pre-prune
	// pair universe + cache journal, so a week-long exact 100% run resumes byte-identically
	// after a crash. The journal is correctness-required (not an optimization): SSSP-populated
	// cache entries from the skipped pair-gen phase are not bit-reproducible by re-routing, so
	// a checkpoint dir without a journal can only support inspection, never bit-parity resume.
	// One knob, one contract: checkpointDir set ⇒ stubs + pair universe + journal all written.

	// ===========================================
	// Checkpoint & resume
	// ===========================================
	private String checkpointDir = "";

	// ── checkpoint FORK resume (Plan B2). When true AND the checkpoint sits strictly below
	// extensionParentsTopKMinDegree, a resume is accepted even if the parent-pruning knobs changed
	// (those knobs have not yet shaped any stub at that degree). PLAIN field on purpose — it is a
	// resume-time decision and must NOT enter the config identity / fingerprint / getParams(),
	// otherwise it would perturb the hash and defeat its own purpose. Default false = today's strict
	// guard.
	private boolean checkpointForkBelowMinDegree = false;

	// ── trust an existing routing journal for the maxDegree<=2 universe dump. When true, the fat
	// early-exit path WARM-LOADS the checkpoint's cache.journal even if the fingerprint does not match,
	// downgrading the mismatch to a warning. Use ONLY when pointing at a journal produced from the SAME
	// routing inputs (network, travel-times, requests dump) as the current run — the operator then
	// asserts the cached routes are valid; the config differences that break the fingerprint
	// (maxPoolingDegree, post-processing flags) do not affect routing. PLAIN field on purpose (a
	// resume-time decision; must not enter the fingerprint). Default false = strict.
	private boolean trustCheckpointJournal = false;

	// Calculate Shapley values for rides (distance contribution per passenger)

	// ===========================================
	// Post-processing & export — Shapley, predecessors/successors, connection-cache export
	// ===========================================
	private boolean calcShapleyValues = true;

	// Calculate predecessor/successor relationships between rides
	// When enabled, connection cache is automatically written
	private boolean calcPredecessors = true;

	// Paper-2 merged run: emit BOTH connecting leg-sides (access O->hub AND
	// continuation hub->D) per hub for each connecting commuter, in one run,
	// by calling expandConnecting twice (RURAL then URBAN) and unioning. Lets
	// fleetSide stay null so both zones' intra rides are kept (no off-fleet
	// drop). Default false preserves single-side (per-fleetSide) behavior.
	private boolean expandConnectingBothSides = false;

	// Maximum time gap (seconds) between predecessor end and successor start.
	// null/omitted => use this default (1800 s = 30 min), -1 => unbounded (explicit).
	// 1800 s covers the realistic empty-vehicle redeployment window; at 100% an
	// unbounded value balloons the connection cache to O(n²) and OOMs in practice.
	// Set to match Python's path_cover_max_time_gap for complete connection cache coverage.
	private Double predecessorsFilterTime = 1800.0;

	// Maximum connection distance as factor of predecessor ride distance.
	// null/omitted => unbounded, -1 => unbounded (explicit).
	private Double predecessorsFilterDistanceFactor = null;

	// Maximum number of successors to keep per ride (closest by distance).
	// 0 or -1 => keep all (no pruning). Default: 50
	private int maxSuccessors = 50;

	// Spatial pre-filter for the predecessor/successor pass (default true).
	// A handoff i->j is feasible only if the empty vehicle can drive from ride i's
	// last dropoff to ride j's first pickup within the time gap (startTime_j - endTime_i).
	// Network travel time is always >= euclidean(lastDest_i, firstOrigin_j) / maxSpeed, so
	// when euclidean/maxSpeed > gap the pair is provably infeasible and is skipped BEFORE
	// routing. This never drops a feasible successor (feasible => routed_tt <= gap =>
	// euclidean/maxSpeed <= gap), so successor output is identical; it only avoids routing
	// the far-and-infeasible pairs that dominate the time window at 100% scale. Requires a
	// real Network (production path); with a stub lookup it is a no-op regardless of this flag.
	private boolean predecessorsSpatialPrefilter = true;

	// Explicit upper-bound speed (m/s) for the pre-filter reachability test. 0 (default) => derive
	// from the network's max FINITE freespeed x 1.5. Set a realistic value (e.g. 45.0 = 162 km/h)
	// when the network has artifact links (e.g. 300 km/h or Infinity freespeed) that inflate the
	// auto bound and weaken pruning; the caller then asserts no real empty-vehicle handoff exceeds
	// this effective speed. Must stay >= the true max achievable handoff speed to remain sound.
	private double predecessorsPrefilterMaxSpeedMps = 0.0;

	// Connection cache export mode (allowed: window|all|successors_only):
	// - "window" (default): Export only the OD/bin segments the predecessor/successor pass
	//   evaluated (accepted AND rejected handoffs) — the lookup domain of Python's
	//   compute_dynamic_successors. Rows are promoted-to-retained so eviction never drops them.
	// - "all": Export ALL cached connections. Debug-only (full cache footprint, much larger).
	// - "successors_only": Export only connections between top-K-capped successor ride pairs.
	private String connectionCacheExportMode = "window";

	// Optional intermediate writes (parity with Python, currently unused)

	// Default walk speed for access/egress calculations (m/s)
	// 0.833333333 m/s = 3 km/h (typical walking speed)
	public static final double DEFAULT_WALK_SPEED = 0.833333333;

	// ===========================================
	// Stop-Based Pooling (Stage 1) Settings
	// ===========================================

	/** Master switch to enable stop-based ride generation */
	private boolean enableStopBased = false;

	/** Hard cap on walking distance (meters) - regardless of budget */
	private double maxWalkDistanceMeters = 500.0;

	/** Radius to search for optimal stops around passenger origins/destinations */
	private double stopSearchRadiusMeters = 300.0;

	/** Stop finding strategy: GEOMETRIC, NETWORK_NODE, NETWORK_LINK, PREDEFINED */
	private String stopFindingStrategy = "GEOMETRIC";

	/** Max link length to consider for stops (optional filter). Default: no filter */
	private double maxLinkLengthForStopMeters = Double.MAX_VALUE;

	/** Walking speed for time calculations (m/s) - default 1.2 m/s = 4.3 km/h */
	private double walkSpeedMps = 1.2;

	/** Path to predefined stops file (MATSim TransitStops/Facilities XML) */

	/** Whether to use MATSim's walk router for distance/time calculations */

	// ===========================================
	// Hyper-Pooling (Stage 2) Settings
	// ===========================================

	/** Enable second-stage bundling of stop-to-stop rides */
	private boolean enableHyperPooling = false;

	/** Max walking distance to relocated stop in hyper-pooling (meters) */
	private double hyperPoolMaxStopRelocationMeters = 200.0;

	/** Max number of stops in a hyper-pooled ride (-1 for unlimited, matches original ExMAS/HyperPool) */
	private int hyperPoolMaxStops = -1;

	/** Time window for compatible stop-to-stop rides (seconds) */
	private double hyperPoolTimeWindowSeconds = 900.0;

	/** Minimum occupancy for hyper-pooled rides to be attractive */
	private int hyperPoolMinOccupancy = 4;

	/** Max simultaneous in-vehicle passengers (peak_pax) a hyper-pooled ride may
	 *  imply — the fleet's vehicle capacity. -1 = unlimited (legacy behaviour).
	 *  Checked against {@code HyperPooledRide.getPeakPax()} before a cluster is
	 *  accepted (HYP-5). */
	private int hyperPoolMaxVehicleCapacity = -1;

	/** Stop proximity threshold for considering stops as "same" (meters) */
	private double hyperPoolStopProximityMeters = 100.0;

	/**
	 * Enable spatial proximity filtering for HyperPool stage 2 bundling.
	 * If true (default): Pre-filters ride pairs based on stop proximity (pickup OR dropoff within threshold).
	 * If false: Uses original utility-based approach (evaluates all ride pairs, slower but more comprehensive).
	 *
	 * Trade-off:
	 * - true: Faster (3-15x), finds most patterns (85-95%), misses long-distance directional bundles
	 * - false: Slower, finds all valid patterns (100%), matches original ExMAS HyperPool behavior
	 */
	private boolean hyperPoolEnableSpatialFilter = true;

	/**
	 * Enable stop relocation (merging nearby stops using weighted centroid).
	 * Default: false (matches original ExMAS/HyperPool which works with actual stop locations).
	 * When true: Production optimization that reduces route complexity.
	 */
	private boolean hyperPoolEnableStopRelocation = false;

	/**
	 * Enable directional compatibility filter (rejects rides moving in opposite directions).
	 * Default: false (matches original ExMAS/HyperPool which uses utility-based matching only).
	 * When true: Production optimization that filters incompatible ride pairs early.
	 */
	private boolean hyperPoolEnableDirectionalFilter = false;

	/**
	 * Master switch for budget-derived walk and wait caps.
	 * Default: false — preserves current pipeline exactly.
	 * When true: later tasks derive per-passenger walk and wait caps from their
	 * utility budget, tightening the DRT service envelope to what each person
	 * can actually afford.
	 */
	private boolean enableBudgetAwareConstraints = false;

	/**
	 * Memoize the result of pooled-ride binary searches in
	 * {@link org.matsim.contrib.demand_extraction.demand.BudgetToConstraintsCalculator}.
	 * Cache is per-{@code DrtRequest}, fixed at 4 entries, keyed on the
	 * quantized pooled-ride params (see {@link #cacheTimeBucketSec},
	 * {@link #cacheDistBucketM}, {@link #cacheDelayBucketSec}).
	 *
	 * <p>Only consulted by the pooled-ride overloads, which are themselves
	 * gated on {@link #enableBudgetAwareConstraints}. Default {@code true}.
	 */
	private boolean enableConstraintCalcCache = true;

	/** Time-bucket size for cache-key quantization (seconds). Default 5. */
	private int cacheTimeBucketSec = 5;

	/** Distance-bucket size for cache-key quantization (meters). Default 50. */
	private int cacheDistBucketM = 50;

	/** Delay-bucket size for cache-key quantization (seconds). Default 5. */
	private int cacheDelayBucketSec = 5;

	// ===========================================
	// Paper 2 Extension 2 — hub-service settings
	// ===========================================

	/**
	 * Which fleet this extraction is producing demand for in the two-stage hub
	 * service. Selects which endpoint of a {@code connecting} request gets
	 * rewritten by virtual-trip expansion:
	 * <ul>
	 *   <li>{@code RURAL} — replace the URBAN endpoint (the one inside the
	 *       metropole polygon) with the hub coordinate, so the rural fleet
	 *       sees a rural-to-hub trip.</li>
	 *   <li>{@code URBAN} — replace the RURAL endpoint with the hub
	 *       coordinate, so the urban fleet sees a hub-to-urban trip.</li>
	 * </ul>
	 * Null (default) disables virtual-trip expansion entirely, preserving
	 * pre-Extension-2 behaviour.
	 */
	public enum FleetSide { RURAL, URBAN }

	/**
	 * Path to the hub-set GeoJSON file produced by the Phase-3 Python hub
	 * discovery. Consumed in Phase 4 by virtual-trip expansion in
	 * {@link org.matsim.contrib.demand_extraction.demand.DrtRequestFactory} once
	 * that path is wired up. Null = disabled (no hub-service rewriting).
	 */
	private String hubSetGeoJsonPath = null;

	/**
	 * Which fleet side this extraction is producing demand for. See
	 * {@link FleetSide}. Null (default) = no virtual-trip expansion.
	 *
	 * <p>Expansion only fires when {@link #hubSetGeoJsonPath} is also non-null;
	 * both must be set together. With a hub set but no fleet side configured,
	 * {@link org.matsim.contrib.demand_extraction.demand.DrtRequestFactory}
	 * fails fast at startup to surface the misconfiguration.
	 */
	private FleetSide fleetSide = null;

	/**
	 * Path to the request-classifications CSV produced by the Phase-2 Python
	 * classifier (columns: {@code personId}, {@code tripIndex},
	 * {@code requestTag}). When set, {@link
	 * org.matsim.contrib.demand_extraction.demand.DrtRequestFactory} loads it
	 * via {@link org.matsim.contrib.demand_extraction.demand.RequestClassificationLoader}
	 * and stamps each {@link org.matsim.contrib.demand_extraction.demand.DrtRequest}
	 * with the corresponding tag. Null = disabled — requests get
	 * {@code requestTag == null}, preserving pre-Extension-2 (Kelheim) behaviour.
	 */
	private String requestClassificationsPath = null;

	/**
	 * Paper-2 Extension 2: scheduled transfer slack at the hub, in seconds. The
	 * urban (continuation) virtual leg is scheduled this long after the rural
	 * leg's direct arrival at the hub, and the slack is charged as waiting
	 * disutility on the continuation leg. Only used when virtual-trip expansion
	 * is active (hubSetGeoJsonPath + fleetSide set).
	 */
	private double hubTransferBufferSeconds = 300.0;

	/**
	 * Paper-2 hub-sync v1 (Task 10b): width of the hub-departure/wait window for
	 * CONTINUATION (hub→urban) virtual legs, in seconds. When {@code > 0}, the
	 * continuation leg may depart anywhere in
	 * {@code [hubArrival, hubArrival + maxHubWaitSeconds]} (where
	 * {@code hubArrival = requestTime + ruralLegTime}), so the urban shareability
	 * graph enumerates pooled continuation bundles at different departure slots —
	 * the runtime feed for the v1 MIP per-bundle nesting constraint. The served
	 * wait is realized by bundling, not a fixed buffer, so the continuation leg's
	 * {@code transferWaitSeconds} is set to 0.
	 *
	 * <p><b>Default 0.0 = byte-identical to the legacy fixed-buffer behavior</b>
	 * (the continuation departure stays pinned at
	 * {@code requestTime + ruralLegTime + hubTransferBufferSeconds} with
	 * {@code transferWaitSeconds = buffer}). Only used when virtual-trip expansion
	 * is active (hubSetGeoJsonPath + fleetSide/both-sides set).
	 */
	private double maxHubWaitSeconds = 0.0;

	/**
	 * Paper-2 hub-sync v2 (Task 11c, two-sided): when {@code true}, the ACCESS
	 * (rural→hub) virtual leg of each connecting commuter is emitted as MULTIPLE
	 * variants at earlier-departure offsets {@code 0, step, 2·step, …} (where
	 * {@code step = maxHubWaitSeconds}, bounded by {@link #hubSyncMaxAdvanceSeconds}).
	 * Each variant departs at {@code requestTime − offset} (hub arrival shifts
	 * earlier by the same amount) and is re-routed for the new departure, so the
	 * resulting hub-arrival diversity lets the Python side cluster sync slots and
	 * the MIP align both legs. Offset 0 reproduces today's single access leg, so
	 * <b>default {@code false} is byte-identical to the v1 behavior</b>. Requires
	 * {@code maxHubWaitSeconds > 0} when enabled (the step must be positive to
	 * enumerate variants). Has NO effect on CONTINUATION legs (already wide via
	 * {@link #maxHubWaitSeconds}).
	 */
	private boolean hubSyncTwoSided = false;

	/**
	 * Paper-2 hub-sync v2 (Task 11c): maximum seconds a commuter may depart
	 * earlier than their original request to reach an earlier hub-arrival sync
	 * slot, bounding the ACCESS variant ladder when {@link #hubSyncTwoSided} is
	 * on. The number of variants is {@code floor(hubSyncMaxAdvanceSeconds /
	 * maxHubWaitSeconds) + 1}. Default 900 s. Ignored when
	 * {@code hubSyncTwoSided == false}.
	 */
	private double hubSyncMaxAdvanceSeconds = 900.0;

    public ExMasConfigGroup() {
        super(GROUP_NAME);
    }

	/**
	 * Get the walk speed from MATSim routing config, falling back to
	 * {@link #DEFAULT_WALK_SPEED} if the configured speed is zero or negative.
	 *
	 * <p>Both BudgetValidator and BudgetToConstraintsCalculator need the same
	 * walk speed for scoring synthetic DRT trips. This method centralizes the
	 * extraction logic.
	 *
	 * @param config the MATSim config
	 * @return walk speed in m/s
	 */
	public static double getWalkSpeed(Config config) {
		double configuredSpeed = config.routing()
				.getOrCreateModeRoutingParams(TransportMode.walk)
				.getTeleportedModeSpeed();
		return (configuredSpeed > 0) ? configuredSpeed : DEFAULT_WALK_SPEED;
	}

	@StringGetter("scoringAdapter")
	public String getScoringAdapter() {
		return scoringAdapter;
	}

	@StringSetter("scoringAdapter")
	public void setScoringAdapter(String scoringAdapter) {
		this.scoringAdapter = scoringAdapter;
	}

	@StringGetter("algorithm")
	public Algorithm getAlgorithm() {
		return algorithm;
	}

	@StringSetter("algorithm")
	public void setAlgorithm(Algorithm algorithm) {
		this.algorithm = algorithm;
	}

	@StringGetter("tourEvaluationMode")
	public TourEvaluationMode getTourEvaluationMode() {
		return tourEvaluationMode;
	}

	@StringSetter("tourEvaluationMode")
	public void setTourEvaluationMode(TourEvaluationMode tourEvaluationMode) {
		this.tourEvaluationMode = tourEvaluationMode;
	}

	@StringGetter("marginalUtilityOfMoney")
	public Double getMarginalUtilityOfMoneyOverride() {
		return marginalUtilityOfMoneyOverride;
	}

	@StringSetter("marginalUtilityOfMoney")
	public void setMarginalUtilityOfMoneyOverride(Double marginalUtilityOfMoney) {
		this.marginalUtilityOfMoneyOverride = marginalUtilityOfMoney;
	}

	@StringGetter("algorithmProcessCount")
	public int getAlgorithmProcessCount() {
		return algorithmProcessCount;
	}

	@StringSetter("algorithmProcessCount")
	public void setAlgorithmProcessCount(int algorithmProcessCount) {
		this.algorithmProcessCount = algorithmProcessCount;
	}

    @StringGetter(BUDGET_CALCULATION_MODE)
    public BudgetCalculationMode getBudgetCalculationMode() {
        return budgetCalculationMode;
    }

    @StringSetter(BUDGET_CALCULATION_MODE)
    public void setBudgetCalculationMode(BudgetCalculationMode budgetCalculationMode) {
        this.budgetCalculationMode = budgetCalculationMode;
    }

    @StringGetter(DRT_MODE)
    public String getDrtMode() {
        return drtMode;
    }

    @StringSetter(DRT_MODE)
    public void setDrtMode(String drtMode) {
        this.drtMode = drtMode;
    }

    // Commute configuration getters/setters
    @StringGetter("homeActivityType")
    public String getHomeActivityType() {
        return homeActivityType;
    }

    @StringSetter("homeActivityType")
    public void setHomeActivityType(String homeActivityType) {
        this.homeActivityType = homeActivityType;
    }

    @StringGetter("workActivityType")
    public String getWorkActivityType() {
        return workActivityType;
    }

    @StringSetter("workActivityType")
    public void setWorkActivityType(String workActivityType) {
        this.workActivityType = workActivityType;
    }

    @StringGetter("educationActivityType")
    public String getEducationActivityType() {
        return educationActivityType;
    }

    @StringSetter("educationActivityType")
    public void setEducationActivityType(String educationActivityType) {
        this.educationActivityType = educationActivityType;
    }

    @StringGetter("commuteFilter")
    public CommuteFilter getCommuteFilter() {
        return commuteFilter;
    }

    @StringSetter("commuteFilter")
    public void setCommuteFilter(CommuteFilter commuteFilter) {
        this.commuteFilter = commuteFilter;
    }

    @StringGetter("minAge")
    public int getMinAge() {
        return minAge;
    }

    @StringSetter("minAge")
    public void setMinAge(int minAge) {
        this.minAge = minAge;
    }

    @StringGetter("drtAvailabilityAttribute")
    public String getDrtAvailabilityAttribute() {
        return drtAvailabilityAttribute;
    }

    @StringSetter("drtAvailabilityAttribute")
    public void setDrtAvailabilityAttribute(String drtAvailabilityAttribute) {
        this.drtAvailabilityAttribute = drtAvailabilityAttribute;
    }

    @StringGetter("tripFilterRadiusKm")
    public double getTripFilterRadiusKm() {
        return tripFilterRadiusKm;
    }

    @StringSetter("tripFilterRadiusKm")
    public void setTripFilterRadiusKm(double tripFilterRadiusKm) {
        this.tripFilterRadiusKm = tripFilterRadiusKm;
    }

    @StringGetter("tripFilterCenterX")
    public double getTripFilterCenterX() {
        return tripFilterCenterX;
    }

    @StringSetter("tripFilterCenterX")
    public void setTripFilterCenterX(double tripFilterCenterX) {
        this.tripFilterCenterX = tripFilterCenterX;
    }

    @StringGetter("tripFilterCenterY")
    public double getTripFilterCenterY() {
        return tripFilterCenterY;
    }

    @StringSetter("tripFilterCenterY")
    public void setTripFilterCenterY(double tripFilterCenterY) {
        this.tripFilterCenterY = tripFilterCenterY;
    }

    public boolean hasTripSpatialFilter() {
        return tripFilterRadiusKm > 0 && Double.isFinite(tripFilterCenterX) && Double.isFinite(tripFilterCenterY);
    }

    @StringGetter("tripFilterExclusionShapefilePath")
    public String getTripFilterExclusionShapefilePath() {
        return tripFilterExclusionShapefilePath;
    }

    @StringSetter("tripFilterExclusionShapefilePath")
    public void setTripFilterExclusionShapefilePath(String tripFilterExclusionShapefilePath) {
        this.tripFilterExclusionShapefilePath = tripFilterExclusionShapefilePath;
    }

    public boolean hasTripExclusionZone() {
        return tripFilterExclusionShapefilePath != null && !tripFilterExclusionShapefilePath.isBlank();
    }

    @StringGetter("metropolePolygonPath")
    public String getMetropolePolygonPath() {
        return metropolePolygonPath;
    }

    @StringSetter("metropolePolygonPath")
    public void setMetropolePolygonPath(String metropolePolygonPath) {
        this.metropolePolygonPath = metropolePolygonPath;
    }

    public boolean hasMetropolePolygon() {
        return metropolePolygonPath != null && !metropolePolygonPath.isBlank();
    }

    public Set<String> getBaseModes() {
        return baseModes;
    }
    
	public void setBaseModes(Set<String> modes) {
        this.baseModes = modes;
    }

	@StringGetter("drtRoutingMode")
	public String getDrtRoutingMode() {
		return drtRoutingMode;
	}

	@StringSetter("drtRoutingMode")
	public void setDrtRoutingMode(String drtRoutingMode) {
		this.drtRoutingMode = drtRoutingMode;
	}

	public Set<String> getDrtAllowedModes() {
		return drtAllowedModes;
	}

	public void setDrtAllowedModes(Set<String> drtAllowedModes) {
		this.drtAllowedModes = drtAllowedModes != null ? drtAllowedModes : Set.of();
	}

	public Set<String> getPrivateVehicleModes() {
		return privateVehicleModes;
	}

	public void setPrivateVehicleModes(Set<String> privateVehicleModes) {
		this.privateVehicleModes = privateVehicleModes;
	}

	public Set<String> getExcludedTripModes() {
		return excludedTripModes;
	}

	public void setExcludedTripModes(Set<String> excludedTripModes) {
		this.excludedTripModes = excludedTripModes != null ? excludedTripModes : Set.of();
	}

	@StringGetter("minDrtCostPerKm")
	public double getMinDrtCostPerKm() {
		return minDrtCostPerKm;
	}

	@StringSetter("minDrtCostPerKm")
	public void setMinDrtCostPerKm(double minDrtCostPerKm) {
		this.minDrtCostPerKm = minDrtCostPerKm;
	}

	@StringGetter("minMaxDetourFactor")
	public double getMinMaxDetourFactor() {
		return minMaxDetourFactor;
	}

	@StringSetter("minMaxDetourFactor")
	public void setMinMaxDetourFactor(double minMaxDetourFactor) {
		this.minMaxDetourFactor = minMaxDetourFactor;
	}

	@StringGetter("minMaxWaitingTime")
	public double getMinMaxWaitingTime() {
		return minMaxWaitingTime;
	}

	@StringSetter("minMaxWaitingTime")
	public void setMinMaxWaitingTime(double minMaxWaitingTime) {
		this.minMaxWaitingTime = minMaxWaitingTime;
	}

    @StringGetter("minDrtAccessEgressDistance")
    public double getMinDrtAccessEgressDistance() {
        return minDrtAccessEgressDistance;
    }

    @StringSetter("minDrtAccessEgressDistance")
    public void setMinDrtAccessEgressDistance(double minDrtAccessEgressDistance) {
        this.minDrtAccessEgressDistance = minDrtAccessEgressDistance;
    }

	@StringGetter("cacheEvictionWatermark")
	public double getCacheEvictionWatermark() {
		return cacheEvictionWatermark;
	}

	@StringSetter("cacheEvictionWatermark")
	public void setCacheEvictionWatermark(double cacheEvictionWatermark) {
		this.cacheEvictionWatermark = cacheEvictionWatermark;
	}

	@StringGetter("networkTimeBinSize")
	public int getNetworkTimeBinSize() {
		return networkTimeBinSize;
	}

	@StringSetter("networkTimeBinSize")
	public void setNetworkTimeBinSize(int networkTimeBinSize) {
		this.networkTimeBinSize = networkTimeBinSize;
	}

	// Max detour factor getter/setter
	@StringGetter("maxDetourFactor")
	public double getMaxDetourFactor() {
		return maxDetourFactor;
	}

	@StringSetter("maxDetourFactor")
	public void setMaxDetourFactor(double maxDetourFactor) {
		this.maxDetourFactor = maxDetourFactor;
	}

	public Map<String, Double> getMaxDetourFactorByClass() {
		return Collections.unmodifiableMap(maxDetourFactorByClass);
	}

	public void setMaxDetourFactorByClass(Map<String, Double> m) {
		this.maxDetourFactorByClass = new HashMap<>(m);
	}

	public void clearMaxDetourFactorByClass() {
		this.maxDetourFactorByClass = new HashMap<>();
	}

	@StringGetter("maxAbsoluteDetour")
	public Integer getMaxAbsoluteDetour() {
		return maxAbsoluteDetour;
	}

	@StringSetter("maxAbsoluteDetour")
	public void setMaxAbsoluteDetour(Integer maxAbsoluteDetour) {
		this.maxAbsoluteDetour = maxAbsoluteDetour;
	}

	@StringGetter("requestSampleSize")
	public double getRequestSampleSize() {
		return requestSampleSize;
	}

	@StringSetter("requestSampleSize")
	public void setRequestSampleSize(double requestSampleSize) {
		this.requestSampleSize = requestSampleSize;
	}

	@StringGetter("requestCount")
	public Integer getRequestCount() {
		return requestCount;
	}

	@StringSetter("requestCount")
	public void setRequestCount(Integer requestCount) {
		this.requestCount = requestCount;
	}

	@StringGetter("positiveFlexibilityAttribute")
	public String getPositiveFlexibilityAttribute() {
		return positiveFlexibilityAttribute;
	}

	@StringSetter("positiveFlexibilityAttribute")
	public void setPositiveFlexibilityAttribute(String positiveFlexibilityAttribute) {
		this.positiveFlexibilityAttribute = positiveFlexibilityAttribute;
	}

	@StringGetter("positiveFlexibilityAbsoluteMap")
	public String getPositiveFlexibilityAbsoluteMap() {
		return positiveFlexibilityAbsoluteMap;
	}

	@StringSetter("positiveFlexibilityAbsoluteMap")
	public void setPositiveFlexibilityAbsoluteMap(String positiveFlexibilityAbsoluteMap) {
		this.positiveFlexibilityAbsoluteMap = positiveFlexibilityAbsoluteMap;
	}

	@StringGetter("positiveFlexibilityRelativeMap")
	public String getPositiveFlexibilityRelativeMap() {
		return positiveFlexibilityRelativeMap;
	}

	@StringSetter("positiveFlexibilityRelativeMap")
	public void setPositiveFlexibilityRelativeMap(String positiveFlexibilityRelativeMap) {
		this.positiveFlexibilityRelativeMap = positiveFlexibilityRelativeMap;
	}

	@StringGetter("negativeFlexibilityAttribute")
	public String getNegativeFlexibilityAttribute() {
		return negativeFlexibilityAttribute;
	}

	@StringSetter("negativeFlexibilityAttribute")
	public void setNegativeFlexibilityAttribute(String negativeFlexibilityAttribute) {
		this.negativeFlexibilityAttribute = negativeFlexibilityAttribute;
	}

	@StringGetter("negativeFlexibilityAbsoluteMap")
	public String getNegativeFlexibilityAbsoluteMap() {
		return negativeFlexibilityAbsoluteMap;
	}

	@StringSetter("negativeFlexibilityAbsoluteMap")
	public void setNegativeFlexibilityAbsoluteMap(String negativeFlexibilityAbsoluteMap) {
		this.negativeFlexibilityAbsoluteMap = negativeFlexibilityAbsoluteMap;
	}

	@StringGetter("negativeFlexibilityRelativeMap")
	public String getNegativeFlexibilityRelativeMap() {
		return negativeFlexibilityRelativeMap;
	}

	@StringSetter("negativeFlexibilityRelativeMap")
	public void setNegativeFlexibilityRelativeMap(String negativeFlexibilityRelativeMap) {
		this.negativeFlexibilityRelativeMap = negativeFlexibilityRelativeMap;
	}
	
	@StringGetter("searchHorizon")
	public double getSearchHorizon() {
		return searchHorizon;
	}

	@StringSetter("searchHorizon")
	public void setSearchHorizon(double searchHorizon) {
		this.searchHorizon = searchHorizon;
	}
	
	@StringGetter("maxPoolingDegree")
	public int getMaxPoolingDegree() {
		return maxPoolingDegree;
	}

	@StringSetter("maxPoolingDegree")
	public void setMaxPoolingDegree(int maxPoolingDegree) {
		this.maxPoolingDegree = maxPoolingDegree;
	}

	@StringGetter("maxOrderingNodesAfterFirstValid")
	public long getMaxOrderingNodesAfterFirstValid() {
		return maxOrderingNodesAfterFirstValid;
	}

	@StringSetter("maxOrderingNodesAfterFirstValid")
	public void setMaxOrderingNodesAfterFirstValid(long maxOrderingNodesAfterFirstValid) {
		this.maxOrderingNodesAfterFirstValid = Math.max(0L, maxOrderingNodesAfterFirstValid);
	}

	@StringGetter("ptOptimizeDepartureTime")
	public boolean isPtOptimizeDepartureTime() {
		return ptOptimizeDepartureTime;
	}

	@StringSetter("ptOptimizeDepartureTime")
	public void setPtOptimizeDepartureTime(boolean ptOptimizeDepartureTime) {
		this.ptOptimizeDepartureTime = ptOptimizeDepartureTime;
	}

	// Heuristics/post-processing
	@StringGetter("heuristicsProcessCount")
	public int getHeuristicsProcessCount() {
		return heuristicsProcessCount;
	}

	@StringSetter("heuristicsProcessCount")
	public void setHeuristicsProcessCount(int heuristicsProcessCount) {
		this.heuristicsProcessCount = heuristicsProcessCount;
	}

	@StringGetter("heuristicPruningEnabled")
	public boolean isHeuristicPruningEnabled() {
		return pruningEnabled;
	}

	@StringSetter("heuristicPruningEnabled")
	public void setHeuristicPruningEnabled(boolean heuristicPruningEnabled) {
		this.pruningEnabled = heuristicPruningEnabled;
	}

	@StringGetter("pruningDistanceSavingsLogScale")
	public double getPruningDistanceSavingsLogScale() {
		return pruningDistanceSavingsLogScale;
	}

	@StringSetter("pruningDistanceSavingsLogScale")
	public void setPruningDistanceSavingsLogScale(double pruningDistanceSavingsLogScale) {
		this.pruningDistanceSavingsLogScale = pruningDistanceSavingsLogScale;
	}

	@StringGetter("pruningDistanceSavingsMax")
	public double getPruningDistanceSavingsMax() {
		return pruningDistanceSavingsMax;
	}

	@StringSetter("pruningDistanceSavingsMax")
	public void setPruningDistanceSavingsMax(double pruningDistanceSavingsMax) {
		this.pruningDistanceSavingsMax = pruningDistanceSavingsMax;
	}

	@StringGetter("pruningDistanceSavingsMinDegree")
	public int getPruningDistanceSavingsMinDegree() {
		return pruningDistanceSavingsMinDegree;
	}

	@StringSetter("pruningDistanceSavingsMinDegree")
	public void setPruningDistanceSavingsMinDegree(int pruningDistanceSavingsMinDegree) {
		this.pruningDistanceSavingsMinDegree = pruningDistanceSavingsMinDegree;
	}

	@StringGetter("pruningGateLinearIntercept")
	public double getPruningGateLinearIntercept() {
		return pruningGateLinearIntercept;
	}

	@StringSetter("pruningGateLinearIntercept")
	public void setPruningGateLinearIntercept(double pruningGateLinearIntercept) {
		this.pruningGateLinearIntercept = pruningGateLinearIntercept;
	}

	@StringGetter("pruningGateLinearSlope")
	public double getPruningGateLinearSlope() {
		return pruningGateLinearSlope;
	}

	@StringSetter("pruningGateLinearSlope")
	public void setPruningGateLinearSlope(double pruningGateLinearSlope) {
		this.pruningGateLinearSlope = pruningGateLinearSlope;
	}

	/**
	 * @return true iff the linear gate is configured (intercept is a finite number).
	 * When true, callers should use {@code intercept + slope*d} as the gate;
	 * when false, fall back to the log gate parameterised by
	 * {@link #getPruningDistanceSavingsLogScale()}.
	 */
	public boolean hasLinearGate() {
		return Double.isFinite(pruningGateLinearIntercept) && Double.isFinite(pruningGateLinearSlope);
	}

	@StringGetter("pairKeepTopFraction")
	public double getPairKeepTopFraction() {
		return pairKeepTopFraction;
	}

	@StringSetter("pairKeepTopFraction")
	public void setPairKeepTopFraction(double pairKeepTopFraction) {
		this.pairKeepTopFraction = pairKeepTopFraction;
	}

	@StringGetter("interDegreeKeepFraction")
	public double getInterDegreeKeepFraction() {
		return interDegreeKeepFraction;
	}

	@StringSetter("interDegreeKeepFraction")
	public void setInterDegreeKeepFraction(double interDegreeKeepFraction) {
		this.interDegreeKeepFraction = interDegreeKeepFraction;
	}

	@StringGetter("pruningMode")
	public PruningMode getPruningMode() {
		return pruningMode;
	}

	@StringSetter("pruningMode")
	public void setPruningMode(PruningMode pruningMode) {
		this.pruningMode = pruningMode;
	}

	@StringGetter("pruningCoverageK")
	public int getPruningCoverageK() {
		return pruningCoverageK;
	}

	@StringSetter("pruningCoverageK")
	public void setPruningCoverageK(int pruningCoverageK) {
		this.pruningCoverageK = pruningCoverageK;
	}

	public Map<Integer, Integer> getPruningCoverageKByDegree() {
		return Collections.unmodifiableMap(pruningCoverageKByDegree);
	}

	public void setPruningCoverageKByDegree(Map<Integer, Integer> m) {
		this.pruningCoverageKByDegree = new HashMap<>(m);
	}

	public void clearPruningCoverageKByDegree() {
		this.pruningCoverageKByDegree = new HashMap<>();
	}

	@StringGetter("pruningQualityMetric")
	public PruningQualityMetric getPruningQualityMetric() {
		return pruningQualityMetric;
	}

	@StringSetter("pruningQualityMetric")
	public void setPruningQualityMetric(PruningQualityMetric pruningQualityMetric) {
		this.pruningQualityMetric = pruningQualityMetric;
	}

	@StringGetter("extensionParentsTopK")
	public int getExtensionParentsTopK() {
		return extensionParentsTopK;
	}

	@StringSetter("extensionParentsTopK")
	public void setExtensionParentsTopK(int extensionParentsTopK) {
		this.extensionParentsTopK = Math.max(0, extensionParentsTopK);
	}

	@StringGetter("extensionParentsTopKMinDegree")
	public int getExtensionParentsTopKMinDegree() {
		return extensionParentsTopKMinDegree;
	}

	@StringSetter("extensionParentsTopKMinDegree")
	public void setExtensionParentsTopKMinDegree(int extensionParentsTopKMinDegree) {
		this.extensionParentsTopKMinDegree = Math.max(0, extensionParentsTopKMinDegree);
	}

	@StringGetter("extensionParentsTopKMetric")
	public PruningQualityMetric getExtensionParentsTopKMetric() {
		return extensionParentsTopKMetric;
	}

	@StringSetter("extensionParentsTopKMetric")
	public void setExtensionParentsTopKMetric(PruningQualityMetric extensionParentsTopKMetric) {
		this.extensionParentsTopKMetric = extensionParentsTopKMetric;
	}

	@StringGetter("pairgenTopK")
	public int getPairgenTopK() {
		return pairgenTopK;
	}

	@StringSetter("pairgenTopK")
	public void setPairgenTopK(int pairgenTopK) {
		this.pairgenTopK = Math.max(0, pairgenTopK);
	}

	@StringGetter("extensionParentsSelectionRule")
	public ExtensionParentsSelectionRule getExtensionParentsSelectionRule() {
		return extensionParentsSelectionRule;
	}

	@StringSetter("extensionParentsSelectionRule")
	public void setExtensionParentsSelectionRule(ExtensionParentsSelectionRule extensionParentsSelectionRule) {
		this.extensionParentsSelectionRule = extensionParentsSelectionRule;
	}

	@StringGetter("extensionParentsMmrLambda")
	public double getExtensionParentsMmrLambda() {
		return extensionParentsMmrLambda;
	}

	@StringSetter("extensionParentsMmrLambda")
	public void setExtensionParentsMmrLambda(double extensionParentsMmrLambda) {
		this.extensionParentsMmrLambda = extensionParentsMmrLambda;
	}

	@StringGetter("extensionParentsTier2NodeCap")
	public long getExtensionParentsTier2NodeCap() {
		return extensionParentsTier2NodeCap;
	}

	@StringGetter("checkpointDir")
	public String getCheckpointDir() {
		return checkpointDir;
	}

	@StringSetter("checkpointDir")
	public void setCheckpointDir(String checkpointDir) {
		this.checkpointDir = checkpointDir == null ? "" : checkpointDir;
	}

	/** True when per-degree checkpointing/resume is enabled (i.e. {@link #getCheckpointDir()} is non-empty). */
	public boolean isCheckpointingEnabled() {
		return checkpointDir != null && !checkpointDir.isEmpty();
	}

	// Run-scoped directory for enumeration analytics CSVs (enumeration_stats.csv, menu-depth files).
	// Deliberately NOT a persisted MATSim param (no @StringGetter/@StringSetter): it is an output
	// location set programmatically by the runner, so it must never land in phase1_config.xml, the
	// resume fingerprint, or the strict unknown-<param> XML validation. Empty = analytics off
	// (behaviour unchanged), which is the default for the single-process / test paths.
	private String statsDir = "";

	/** The run-scoped enumeration-analytics output directory ({@code ""} = analytics disabled). */
	public String getStatsDir() {
		return statsDir;
	}

	/** Set the run-scoped enumeration-analytics output directory ({@code null}/empty disables it). */
	public void setStatsDir(String statsDir) {
		this.statsDir = statsDir == null ? "" : statsDir;
	}

	/**
	 * Opt-in fork resume (Plan B2): when true, a checkpoint written strictly below
	 * {@link #getExtensionParentsTopKMinDegree()} may be resumed even if the parent-pruning knobs
	 * changed. PLAIN getter (not {@code @StringGetter}) so it stays out of the fingerprint/identity.
	 */
	public boolean isCheckpointForkBelowMinDegree() {
		return checkpointForkBelowMinDegree;
	}

	/** @see #isCheckpointForkBelowMinDegree() */
	public void setCheckpointForkBelowMinDegree(boolean checkpointForkBelowMinDegree) {
		this.checkpointForkBelowMinDegree = checkpointForkBelowMinDegree;
	}

	/**
	 * When true, the maxDegree&lt;=2 fat path warm-loads an existing checkpoint's routing journal even
	 * under a fingerprint mismatch (downgraded to a warning). For reusing a journal from the SAME
	 * routing inputs under a config that legitimately differs only in routing-irrelevant ways
	 * (degree bound, post-processing flags) — e.g. a degree-2 universe dump off a checkpoint written by
	 * an older build whose baseFingerprint hashed those params. PLAIN getter (out of the fingerprint).
	 */
	public boolean isTrustCheckpointJournal() {
		return trustCheckpointJournal;
	}

	/** @see #isTrustCheckpointJournal() */
	public void setTrustCheckpointJournal(boolean trustCheckpointJournal) {
		this.trustCheckpointJournal = trustCheckpointJournal;
	}

	@StringSetter("extensionParentsTier2NodeCap")
	public void setExtensionParentsTier2NodeCap(long extensionParentsTier2NodeCap) {
		this.extensionParentsTier2NodeCap = Math.max(0L, extensionParentsTier2NodeCap);
	}

	@StringGetter("calcShapleyValues")
	public boolean isCalcShapleyValues() {
		return calcShapleyValues;
	}

	@StringSetter("calcShapleyValues")
	public void setCalcShapleyValues(boolean calcShapleyValues) {
		this.calcShapleyValues = calcShapleyValues;
	}

	@StringGetter("calcPredecessors")
	public boolean isCalcPredecessors() {
		return calcPredecessors;
	}

	@StringSetter("calcPredecessors")
	public void setCalcPredecessors(boolean calcPredecessors) {
		this.calcPredecessors = calcPredecessors;
	}

	@StringGetter("expandConnectingBothSides")
	public boolean isExpandConnectingBothSides() {
		return expandConnectingBothSides;
	}

	@StringSetter("expandConnectingBothSides")
	public void setExpandConnectingBothSides(boolean expandConnectingBothSides) {
		this.expandConnectingBothSides = expandConnectingBothSides;
	}

	@StringGetter("predecessorsFilterTime")
	public Double getPredecessorsFilterTime() {
		return predecessorsFilterTime;
	}

	@StringSetter("predecessorsFilterTime")
	public void setPredecessorsFilterTime(Double predecessorsFilterTime) {
		this.predecessorsFilterTime = predecessorsFilterTime;
	}

	@StringGetter("predecessorsFilterDistanceFactor")
	public Double getPredecessorsFilterDistanceFactor() {
		return predecessorsFilterDistanceFactor;
	}

	@StringSetter("predecessorsFilterDistanceFactor")
	public void setPredecessorsFilterDistanceFactor(Double predecessorsFilterDistanceFactor) {
		this.predecessorsFilterDistanceFactor = predecessorsFilterDistanceFactor;
	}

	@StringGetter("maxSuccessors")
	public int getMaxSuccessors() {
		return maxSuccessors;
	}

	@StringSetter("maxSuccessors")
	public void setMaxSuccessors(int maxSuccessors) {
		this.maxSuccessors = maxSuccessors;
	}

	@StringGetter("predecessorsSpatialPrefilter")
	public boolean isPredecessorsSpatialPrefilter() {
		return predecessorsSpatialPrefilter;
	}

	@StringSetter("predecessorsSpatialPrefilter")
	public void setPredecessorsSpatialPrefilter(boolean predecessorsSpatialPrefilter) {
		this.predecessorsSpatialPrefilter = predecessorsSpatialPrefilter;
	}

	@StringGetter("predecessorsPrefilterMaxSpeedMps")
	public double getPredecessorsPrefilterMaxSpeedMps() {
		return predecessorsPrefilterMaxSpeedMps;
	}

	@StringSetter("predecessorsPrefilterMaxSpeedMps")
	public void setPredecessorsPrefilterMaxSpeedMps(double predecessorsPrefilterMaxSpeedMps) {
		this.predecessorsPrefilterMaxSpeedMps = predecessorsPrefilterMaxSpeedMps;
	}

	@StringGetter("connectionCacheExportMode")
	public String getConnectionCacheExportMode() {
		return connectionCacheExportMode;
	}

	@StringSetter("connectionCacheExportMode")
	public void setConnectionCacheExportMode(String connectionCacheExportMode) {
		if (!"window".equals(connectionCacheExportMode)
				&& !"all".equals(connectionCacheExportMode)
				&& !"successors_only".equals(connectionCacheExportMode)) {
			throw new IllegalArgumentException("Unknown connectionCacheExportMode '"
					+ connectionCacheExportMode + "' (allowed: window|all|successors_only)");
		}
		this.connectionCacheExportMode = connectionCacheExportMode;
	}

	@StringGetter("opportunityCostModel")
	public OpportunityCostModel getOpportunityCostModel() {
		return opportunityCostModel;
	}

	@StringSetter("opportunityCostModel")
	public void setOpportunityCostModel(OpportunityCostModel opportunityCostModel) {
		this.opportunityCostModel = opportunityCostModel;
	}

	/** @deprecated Use {@link #setOpportunityCostModel} instead. */
	@Deprecated
	public void setIncludeOpportunityCost(boolean include) {
		this.opportunityCostModel = include ? OpportunityCostModel.LINEAR : OpportunityCostModel.NONE;
	}

	/** @deprecated Use {@link #getOpportunityCostModel} instead. */
	@Deprecated
	public boolean isIncludeOpportunityCost() {
		return opportunityCostModel != OpportunityCostModel.NONE;
	}

	@StringGetter("amortizeDailyMonetaryConstants")
	public boolean isAmortizeDailyMonetaryConstants() {
		return amortizeDailyMonetaryConstants;
	}

	@StringSetter("amortizeDailyMonetaryConstants")
	public void setAmortizeDailyMonetaryConstants(boolean amortizeDailyMonetaryConstants) {
		this.amortizeDailyMonetaryConstants = amortizeDailyMonetaryConstants;
	}

	// ===========================================
	// Stop-Based Pooling (Stage 1) Getters/Setters
	// ===========================================

	@StringGetter("enableStopBased")
	public boolean isEnableStopBased() {
		return enableStopBased;
	}

	@StringSetter("enableStopBased")
	public void setEnableStopBased(boolean enableStopBased) {
		this.enableStopBased = enableStopBased;
	}

	@StringGetter("maxWalkDistanceMeters")
	public double getMaxWalkDistanceMeters() {
		return maxWalkDistanceMeters;
	}

	@StringSetter("maxWalkDistanceMeters")
	public void setMaxWalkDistanceMeters(double maxWalkDistanceMeters) {
		this.maxWalkDistanceMeters = maxWalkDistanceMeters;
	}

	@StringGetter("stopSearchRadiusMeters")
	public double getStopSearchRadiusMeters() {
		return stopSearchRadiusMeters;
	}

	@StringSetter("stopSearchRadiusMeters")
	public void setStopSearchRadiusMeters(double stopSearchRadiusMeters) {
		this.stopSearchRadiusMeters = stopSearchRadiusMeters;
	}

	@StringGetter("stopFindingStrategy")
	public String getStopFindingStrategy() {
		return stopFindingStrategy;
	}

	@StringSetter("stopFindingStrategy")
	public void setStopFindingStrategy(String stopFindingStrategy) {
		this.stopFindingStrategy = stopFindingStrategy;
	}

	@StringGetter("maxLinkLengthForStopMeters")
	public double getMaxLinkLengthForStopMeters() {
		return maxLinkLengthForStopMeters;
	}

	@StringSetter("maxLinkLengthForStopMeters")
	public void setMaxLinkLengthForStopMeters(double maxLinkLengthForStopMeters) {
		this.maxLinkLengthForStopMeters = maxLinkLengthForStopMeters;
	}

	@StringGetter("walkSpeedMps")
	public double getWalkSpeedMps() {
		return walkSpeedMps;
	}

	@StringSetter("walkSpeedMps")
	public void setWalkSpeedMps(double walkSpeedMps) {
		this.walkSpeedMps = walkSpeedMps;
	}

	// ===========================================
	// Hyper-Pooling (Stage 2) Getters/Setters
	// ===========================================

	@StringGetter("enableHyperPooling")
	public boolean isEnableHyperPooling() {
		return enableHyperPooling;
	}

	@StringSetter("enableHyperPooling")
	public void setEnableHyperPooling(boolean enableHyperPooling) {
		this.enableHyperPooling = enableHyperPooling;
	}

	@StringGetter("hyperPoolMaxStopRelocationMeters")
	public double getHyperPoolMaxStopRelocationMeters() {
		return hyperPoolMaxStopRelocationMeters;
	}

	@StringSetter("hyperPoolMaxStopRelocationMeters")
	public void setHyperPoolMaxStopRelocationMeters(double hyperPoolMaxStopRelocationMeters) {
		this.hyperPoolMaxStopRelocationMeters = hyperPoolMaxStopRelocationMeters;
	}

	@StringGetter("hyperPoolMaxStops")
	public int getHyperPoolMaxStops() {
		return hyperPoolMaxStops;
	}

	@StringSetter("hyperPoolMaxStops")
	public void setHyperPoolMaxStops(int hyperPoolMaxStops) {
		this.hyperPoolMaxStops = hyperPoolMaxStops;
	}

	@StringGetter("hyperPoolTimeWindowSeconds")
	public double getHyperPoolTimeWindowSeconds() {
		return hyperPoolTimeWindowSeconds;
	}

	@StringSetter("hyperPoolTimeWindowSeconds")
	public void setHyperPoolTimeWindowSeconds(double hyperPoolTimeWindowSeconds) {
		this.hyperPoolTimeWindowSeconds = hyperPoolTimeWindowSeconds;
	}

	@StringGetter("hyperPoolMinOccupancy")
	public int getHyperPoolMinOccupancy() {
		return hyperPoolMinOccupancy;
	}

	@StringSetter("hyperPoolMinOccupancy")
	public void setHyperPoolMinOccupancy(int hyperPoolMinOccupancy) {
		this.hyperPoolMinOccupancy = hyperPoolMinOccupancy;
	}

	@StringGetter("hyperPoolMaxVehicleCapacity")
	public int getHyperPoolMaxVehicleCapacity() {
		return hyperPoolMaxVehicleCapacity;
	}

	@StringSetter("hyperPoolMaxVehicleCapacity")
	public void setHyperPoolMaxVehicleCapacity(int hyperPoolMaxVehicleCapacity) {
		this.hyperPoolMaxVehicleCapacity = hyperPoolMaxVehicleCapacity;
	}

	@StringGetter("hyperPoolStopProximityMeters")
	public double getHyperPoolStopProximityMeters() {
		return hyperPoolStopProximityMeters;
	}

	@StringSetter("hyperPoolStopProximityMeters")
	public void setHyperPoolStopProximityMeters(double hyperPoolStopProximityMeters) {
		this.hyperPoolStopProximityMeters = hyperPoolStopProximityMeters;
	}

	@StringGetter("hyperPoolEnableSpatialFilter")
	public boolean getHyperPoolEnableSpatialFilter() {
		return hyperPoolEnableSpatialFilter;
	}

	@StringSetter("hyperPoolEnableSpatialFilter")
	public void setHyperPoolEnableSpatialFilter(boolean hyperPoolEnableSpatialFilter) {
		this.hyperPoolEnableSpatialFilter = hyperPoolEnableSpatialFilter;
	}

	@StringGetter("hyperPoolEnableStopRelocation")
	public boolean getHyperPoolEnableStopRelocation() {
		return hyperPoolEnableStopRelocation;
	}

	@StringSetter("hyperPoolEnableStopRelocation")
	public void setHyperPoolEnableStopRelocation(boolean hyperPoolEnableStopRelocation) {
		this.hyperPoolEnableStopRelocation = hyperPoolEnableStopRelocation;
	}

	@StringGetter("hyperPoolEnableDirectionalFilter")
	public boolean getHyperPoolEnableDirectionalFilter() {
		return hyperPoolEnableDirectionalFilter;
	}

	@StringSetter("hyperPoolEnableDirectionalFilter")
	public void setHyperPoolEnableDirectionalFilter(boolean hyperPoolEnableDirectionalFilter) {
		this.hyperPoolEnableDirectionalFilter = hyperPoolEnableDirectionalFilter;
	}

	@StringGetter("enableBudgetAwareConstraints")
	public boolean isEnableBudgetAwareConstraints() {
		return enableBudgetAwareConstraints;
	}

	@StringSetter("enableBudgetAwareConstraints")
	public void setEnableBudgetAwareConstraints(boolean enableBudgetAwareConstraints) {
		this.enableBudgetAwareConstraints = enableBudgetAwareConstraints;
	}

	@StringGetter("enableConstraintCalcCache")
	public boolean isEnableConstraintCalcCache() { return enableConstraintCalcCache; }
	@StringSetter("enableConstraintCalcCache")
	public void setEnableConstraintCalcCache(boolean v) { this.enableConstraintCalcCache = v; }

	@StringGetter("cacheTimeBucketSec")
	public int getCacheTimeBucketSec() { return cacheTimeBucketSec; }
	@StringSetter("cacheTimeBucketSec")
	public void setCacheTimeBucketSec(int v) {
		if (v <= 0) throw new IllegalArgumentException("cacheTimeBucketSec must be positive, got " + v);
		this.cacheTimeBucketSec = v;
	}

	@StringGetter("cacheDistBucketM")
	public int getCacheDistBucketM() { return cacheDistBucketM; }
	@StringSetter("cacheDistBucketM")
	public void setCacheDistBucketM(int v) {
		if (v <= 0) throw new IllegalArgumentException("cacheDistBucketM must be positive, got " + v);
		this.cacheDistBucketM = v;
	}

	@StringGetter("cacheDelayBucketSec")
	public int getCacheDelayBucketSec() { return cacheDelayBucketSec; }
	@StringSetter("cacheDelayBucketSec")
	public void setCacheDelayBucketSec(int v) {
		if (v <= 0) throw new IllegalArgumentException("cacheDelayBucketSec must be positive, got " + v);
		this.cacheDelayBucketSec = v;
	}

	// ===========================================
	// Paper 2 Extension 2 — hub-service getters/setters
	// ===========================================

	@StringGetter("hubSetGeoJsonPath")
	public String getHubSetGeoJsonPath() {
		return hubSetGeoJsonPath;
	}

	@StringSetter("hubSetGeoJsonPath")
	public void setHubSetGeoJsonPath(String hubSetGeoJsonPath) {
		this.hubSetGeoJsonPath = hubSetGeoJsonPath;
	}

	@StringGetter("fleetSide")
	public FleetSide getFleetSide() {
		return fleetSide;
	}

	@StringSetter("fleetSide")
	public void setFleetSide(FleetSide fleetSide) {
		this.fleetSide = fleetSide;
	}

	@StringGetter("hubTransferBufferSeconds")
	public double getHubTransferBufferSeconds() {
		return hubTransferBufferSeconds;
	}

	@StringSetter("hubTransferBufferSeconds")
	public void setHubTransferBufferSeconds(double hubTransferBufferSeconds) {
		this.hubTransferBufferSeconds = hubTransferBufferSeconds;
	}

	@StringGetter("maxHubWaitSeconds")
	public double getMaxHubWaitSeconds() {
		return maxHubWaitSeconds;
	}

	@StringSetter("maxHubWaitSeconds")
	public void setMaxHubWaitSeconds(double maxHubWaitSeconds) {
		this.maxHubWaitSeconds = maxHubWaitSeconds;
	}

	@StringGetter("hubSyncTwoSided")
	public boolean isHubSyncTwoSided() {
		return hubSyncTwoSided;
	}

	@StringSetter("hubSyncTwoSided")
	public void setHubSyncTwoSided(boolean hubSyncTwoSided) {
		this.hubSyncTwoSided = hubSyncTwoSided;
	}

	@StringGetter("hubSyncMaxAdvanceSeconds")
	public double getHubSyncMaxAdvanceSeconds() {
		return hubSyncMaxAdvanceSeconds;
	}

	@StringSetter("hubSyncMaxAdvanceSeconds")
	public void setHubSyncMaxAdvanceSeconds(double hubSyncMaxAdvanceSeconds) {
		this.hubSyncMaxAdvanceSeconds = hubSyncMaxAdvanceSeconds;
	}

	@StringGetter("requestClassificationsPath")
	public String getRequestClassificationsPath() {
		return requestClassificationsPath;
	}

	@StringSetter("requestClassificationsPath")
	public void setRequestClassificationsPath(String requestClassificationsPath) {
		this.requestClassificationsPath = requestClassificationsPath;
	}

    @Override
    public Map<String, String> getComments() {
        Map<String, String> map = super.getComments();
        map.put(BUDGET_CALCULATION_MODE, "Mode for calculating utility budget. Options: [TRIP_LEVEL, SUBTOUR_SUM]. Default: TRIP_LEVEL.");
        map.put(DRT_MODE, "The mode name of the DRT service to be optimized. Default: 'drt'.");
        map.put("homeActivityType", "Activity type prefix for home activities (used for commute identification). Default: 'home'");
        map.put("workActivityType", "Activity type prefix for work activities (used for commute identification). Default: 'work'");
        map.put("educationActivityType", "Activity type prefix for education activities (used for commute identification). Default: 'education'");
        map.put("commuteFilter", "Filter for commute trips. Options: [ALL, COMMUTES_ONLY, COMMUTES_AND_EDUCATION, NON_COMMUTES]. Default: ALL");
        map.put("minAge", "Minimum age to use DRT (if 'age' attribute exists). Default: 18");
        map.put("drtAvailabilityAttribute", "Person attribute to check for DRT eligibility. If set, only persons with this attribute=true can use DRT. Default: null");
        map.put("tripFilterRadiusKm", "Trip-level spatial filter: only extract trips where BOTH origin and destination are within this radius (km) of the center point. 0.0 = disabled. Default: 0.0");
        map.put("tripFilterCenterX", "X coordinate of the trip spatial filter center (in scenario CRS). Required if tripFilterRadiusKm > 0.");
        map.put("tripFilterCenterY", "Y coordinate of the trip spatial filter center (in scenario CRS). Required if tripFilterRadiusKm > 0.");
		map.put("drtRoutingMode",
				"Routing mode to use for DRT when no DRT routing module exists. Typically 'car' for network-based routing. Default: 'car'");
		map.put("drtAllowedModes",
				"Network link mode filter for HyperPool stop-finding (StopFinderFactory -> LinkCandidateFinder). "
				+ "Only links whose allowedModes intersect this set are considered as stop candidates. "
				+ "Empty = all links admitted (no filtering). Example: 'car' or 'car,truck'. Default: 'car'");
		map.put("minDrtCostPerKm",
				"Minimum DRT cost per kilometer for budget calculation (€/km). Represents best possible pricing. Default: 0.0");
		map.put("minMaxDetourFactor",
				"Minimum maximum detour factor for budget calculation. 1.0 means direct route. Default: 1.0");
		map.put("minMaxWaitingTime", "Minimum maximum waiting time for budget calculation (minutes). Default: 0.0");
		map.put("minDrtAccessEgressDistance", "Access/egress distance for DRT trips (meters). Default: 0.0");
		map.put("baseModes",
				"List of baseline travel modes to compare against DRT (comma-separated). Default: 'car,pt,bike,walk'");
		map.put("privateVehicleModes",
				"List of modes requiring private vehicles for subtour constraints (comma-separated). Default: 'car,bike'");
		map.put("maxDetourFactor",
				"Maximum detour factor. Maximum travel time = factor * direct travel time. 1.5 means 50% longer. Default: 1.5");
		map.put("maxAbsoluteDetour", "Absolute detour cap (seconds). If set, limits the max detour time regardless of factor. Default: null");
		map.put("requestSampleSize", "Fraction of requests to keep (0.0-1.0). Default: 1.0 (all requests)");
		map.put("requestCount", "Absolute number of requests to keep. Overrides requestSampleSize if set. Default: null");
		map.put("positiveFlexibilityAttribute", "Person attribute for positive flexibility (late departure).");
		map.put("positiveFlexibilityAbsoluteMap", "Map for positive absolute flexibility (value:seconds,value:seconds). Single value sets default. Default: default:0.0");
		map.put("positiveFlexibilityRelativeMap", "Map for positive relative flexibility (value:factor,value:factor). Single value sets default. Default: default:0.5");
		map.put("negativeFlexibilityAttribute", "Person attribute for negative flexibility (early departure).");
		map.put("negativeFlexibilityAbsoluteMap", "Map for negative absolute flexibility (value:seconds,value:seconds). Single value sets default. Default: default:0.0");
		map.put("negativeFlexibilityRelativeMap", "Map for negative relative flexibility (value:factor,value:factor). Single value sets default. Default: default:0.5");
		map.put("networkTimeBinSize",
				"Time bin size for network travel time caching (seconds). Queries within same bin reuse cached values. Default: 900 (15 min)");
		map.put("checkpointDir",
			"Directory for per-degree stub checkpoints + connection-cache journal (Plan A3). " +
			"Empty (\"\") = checkpointing OFF. When set, a crashed week-long exact 100% extraction " +
			"resumes byte-identically from the last completed degree. The journal is part of the " +
			"resume contract (SSSP cache entries are not re-routable bit-exactly), so one knob writes " +
			"stubs + pre-prune pair universe + journal together. Default: \"\"");
		map.put("searchHorizon",
				"Time horizon for pairing requests in ExMAS algorithm (seconds). Requests within this window can be paired. Default: 600 (10 min)");
		map.put("maxPoolingDegree",
				"Maximum number of passengers per shared ride. Default: 2");
		map.put("maxOrderingNodesAfterFirstValid",
				"Per-set ordering-enumeration node budget B (0 = off, exact). When >0, the high-degree " +
				"ordering DFS descends to the first budget-valid ordering, then explores at most B more " +
				"DFS nodes before returning the best ride found (bounds the post-first-valid tail that " +
				"dominates deg-8/9 cost; never loses a feasible ride). Absolute node count, NOT per-degree. " +
				"Recommended: 200000 (~97% exact-best, ~5.4x fewer nodes) or 1000000 (~99% exact, ~3x). " +
				"Default: 0");
		map.put("ptOptimizeDepartureTime",
				"If true, PT router can optimize departure time to reduce waiting times. " +
				"Agent can leave earlier/later to catch better connections. Default: true");
		map.put("opportunityCostModel",
				"Opportunity cost model for trip scoring. NONE = no opportunity cost, " +
				"LINEAR = constant marginalUtilityOfPerforming_s * travelTime (MATSim default), " +
				"LOG = exact log-utility with activity-aware durations " +
				"(min of origin/dest: beta_perf * t_typ * ln(t_actual / (t_actual - tt))). " +
				"Falls back to LINEAR for activities without typicalDuration. Default: LINEAR");
		map.put("amortizeDailyMonetaryConstants",
				"If true, amortizes each mode's dailyMonetaryConstant into trip-level scoring by " +
				"spreading it over the person's total daily trip distance. Without this, daily costs " +
				"(e.g. car ownership -5.3 EUR/day) are ignored in trip scoring. Default: false");
		map.put("algorithmProcessCount",
				"Parallelism for core ExMAS pair generation and ride extension. -1 = all processors, 1 = sequential (more deterministic). Default: -1");
		map.put("heuristicsProcessCount",
				"Parallelism for Shapley/predecessor calculations. -1 = all processors, 1 = sequential. Default: -1");
		map.put("heuristicPruningEnabled",
				"Enable heuristic pruning during ride extension to avoid combinatorial explosion. Default: true");
		map.put("pruningDistanceSavingsLogScale",
				"Degree-aware distance savings pruning: requiredSaving(d)=scale*log2(d), clamped; keep iff rideDistance <= (1-requiredSaving)*sum(request distances). scale<0 disables; scale=0 matches legacy non-improving (rideDistance <= sumDistances). Default: 0.0");
		map.put("pruningDistanceSavingsMax",
				"Maximum requiredSaving(d) clamp for distance savings pruning (0-0.99). Default: 0.9");
		map.put("pruningDistanceSavingsMinDegree",
				"Minimum pooling degree for applying distance savings pruning. Default: 3 (do not prune paired rides). ");
		map.put("pruningGateLinearIntercept",
				"Linear gate intercept a in gate(d)=a+b*d. When both intercept and slope are finite, the linear "
				+ "gate replaces the log gate (rides kept iff rideDistance <= gate(d)*sum(direct distances)). "
				+ "Default: NaN (linear gate disabled).");
		map.put("pruningGateLinearSlope",
				"Linear gate slope b in gate(d)=a+b*d. See pruningGateLinearIntercept. Default: NaN.");
		map.put("pairKeepTopFraction",
				"Post-graph pair pruning: keep only the top fraction of degree-2 rides (by distance savings) "
				+ "after the shareability graph is built and best-per-set dedup is applied. "
				+ "1.0 = disabled. 0.50 = keep top 50%. Default: 1.0 (disabled)");
		map.put("cacheEvictionWatermark",
				"Routing connection-cache eviction watermark: fraction of -Xmx above which the "
				+ "speculative cache tier rotates a generation out. 1.0 = never evict (default for "
				+ "memory-rich runs is to keep this high). Output-invariant: evicted segments "
				+ "re-route bit-identically (cross-engine value identity, see "
				+ "CrossEngineRoutingDeterminismTest). Default: 0.7");
		map.put("interDegreeKeepFraction",
				"Inter-degree pruning (legacy RATIO_THRESHOLD mode only): keep only the top fraction of rides "
				+ "(by savingsRatio) after EACH degree extension. Applied directly (no sqrt scaling). "
				+ "Survivors become base sets for next degree AND final output. "
				+ "1.0 = disabled. 0.10 = keep top 10%. Default: 0.10");
		map.put("pruningMode",
				"Pruner algorithm: RATIO_THRESHOLD (legacy per-degree top-X% by savingsRatio) or "
				+ "COVERAGE_TOPK (per-request top-K by quality metric). Default: COVERAGE_TOPK.");
		map.put("pruningCoverageK",
				"Coverage pruner (COVERAGE_TOPK mode): per-request retention cap. Each request keeps up to "
				+ "K ride options per degree, ranked by pruningQualityMetric. Default: 20.");
		map.put("pruningQualityMetric",
				"Quality metric for ranking rides inside the pruner: ABS_SAVINGS (meters saved) or "
				+ "RATIO_SAVINGS (1 - rideDistance / sum(directDistance)). Default: ABS_SAVINGS.");
		map.put("calcShapleyValues", "Calculate Shapley values for each ride (distance contribution per passenger). Default: true");
		map.put("calcPredecessors",
				"Calculate predecessor/successor relationships between rides. When enabled, connection cache is automatically written. Default: true");
		map.put("expandConnectingBothSides",
				"Paper-2 merged run: emit BOTH connecting leg-sides (access O->hub AND continuation hub->D) "
				+ "per hub for each connecting commuter in one run. Enables hub expansion with fleetSide null "
				+ "(both intra zones kept). Default: false");
		map.put("predecessorsFilterTime",
				"Maximum time gap (seconds) between predecessor end and successor start. " +
				"Default: 1800 s (30 min) — covers realistic empty-vehicle redeployments and keeps the " +
				"connection cache tractable at 100 %. Pass -1 to disable (unbounded — O(n²), OOMs at 100 %). " +
				"Set to match Python's path_cover_max_time_gap for full cache coverage.");
		map.put("predecessorsFilterDistanceFactor",
				"Maximum connection distance as factor of predecessor ride distance. " +
				"-1 or null/omitted => unbounded.");
		map.put("maxSuccessors",
				"Maximum number of successors to keep per ride (closest by distance). " +
				"0 or -1 => keep all (no pruning). Default: 50");
		map.put("connectionCacheExportMode",
				"Connection cache export mode: 'all' exports all cached OD pairs (default, needed for Python dynamic successor computation), " +
				"'successors_only' exports only connections between successor ride pairs (legacy, smaller file). Default: all");

		// Stop-Based Pooling (Stage 1) comments
		map.put("enableStopBased",
				"Master switch to enable stop-based ride generation. When enabled, passengers walk to/from designated stops. Default: false");
		map.put("maxWalkDistanceMeters",
				"Hard cap on walking distance (meters) - regardless of budget. Passengers will never walk further than this. Default: 500.0");
		map.put("stopSearchRadiusMeters",
				"Radius to search for optimal stops around passenger origins/destinations (meters). Default: 300.0");
		map.put("stopFindingStrategy",
				"Stop finding strategy: GEOMETRIC (centroids), NETWORK_NODE (network nodes), NETWORK_LINK (link midpoints), PREDEFINED (from file). Default: GEOMETRIC");
		map.put("maxLinkLengthForStopMeters",
				"Maximum link length to consider for stops when using NETWORK_LINK strategy (meters). Default: Double.MAX_VALUE (no filter)");
		map.put("walkSpeedMps",
				"Walking speed for time calculations (m/s). Default: 1.2 m/s = 4.3 km/h");

		// Hyper-Pooling (Stage 2) comments
		map.put("enableHyperPooling",
				"Enable second-stage bundling of stop-to-stop rides. Requires enableStopBased=true. Default: false");
		map.put("hyperPoolMaxStopRelocationMeters",
				"Maximum walking distance to relocated stop in hyper-pooling (meters). Passengers may be asked to walk to a different stop for bundling. Default: 200.0");
		map.put("hyperPoolMaxStops",
				"Maximum number of stops in a hyper-pooled ride. Use -1 for unlimited (matches original ExMAS/HyperPool). Default: -1");
		map.put("hyperPoolTimeWindowSeconds",
				"Time window for compatible stop-to-stop rides (seconds). Rides within this window can be bundled. Default: 900.0 (15 min)");
		map.put("hyperPoolMinOccupancy",
				"Minimum occupancy for hyper-pooled rides to be attractive. Bundles with fewer passengers are not created. Default: 4");
		map.put("hyperPoolMaxVehicleCapacity",
				"Maximum simultaneous in-vehicle passengers (peak_pax) a hyper-pooled ride may imply (vehicle capacity). -1 = unlimited. Checked before a cluster is accepted (HYP-5). Default: -1");
		map.put("hyperPoolStopProximityMeters",
				"Stop proximity threshold for considering stops as 'same' (meters). Stops within this distance can be merged. Default: 100.0");
		map.put("hyperPoolEnableSpatialFilter",
				"Enable spatial proximity pre-filtering for stage 2 bundling. If true (default): only evaluates ride pairs with nearby stops (faster, finds 85-95% of patterns). If false: evaluates all pairs like original ExMAS HyperPool (slower, finds 100% of patterns). Default: true");
		map.put("hyperPoolEnableStopRelocation",
				"Enable stop relocation (merging nearby stops). Default: false (matches original ExMAS/HyperPool). If true: Production optimization that merges nearby stops using weighted centroid to reduce route complexity.");
		map.put("hyperPoolEnableDirectionalFilter",
				"Enable directional compatibility filter (rejects rides moving opposite directions). Default: false (matches original ExMAS/HyperPool). If true: Production optimization that filters incompatible ride pairs early.");

		map.put("enableBudgetAwareConstraints",
				"Master switch for budget-derived walk and wait caps. Default: false (preserves current pipeline). "
				+ "When true: per-passenger walk and wait caps are derived from each person's utility budget, "
				+ "tightening the DRT service envelope to what they can actually afford.");

		map.put("enableConstraintCalcCache",
				"Memoize pooled-ride binary searches per DrtRequest. 4-entry LRU keyed on quantized params. "
				+ "Only consulted by the pooled-ride overloads, which are themselves gated on enableBudgetAwareConstraints. "
				+ "Default: true.");
		map.put("cacheTimeBucketSec",
				"Time-bucket size for cache-key quantization (seconds). Default: 5.");
		map.put("cacheDistBucketM",
				"Distance-bucket size for cache-key quantization (meters). Default: 50.");
		map.put("cacheDelayBucketSec",
				"Delay-bucket size for cache-key quantization (seconds). Default: 5.");

		map.put("hubSetGeoJsonPath",
				"Paper-2 Extension 2: path to the hub-set GeoJSON file produced by Phase-3 Python "
				+ "hub discovery. FeatureCollection of Point features with a 'hub_id' string property "
				+ "and [x, y] coordinates in the scenario CRS. Consumed by virtual-trip expansion in "
				+ "Phase 4. Default: null (disabled).");

		map.put("fleetSide",
				"Paper-2 Extension 2: which fleet this extraction targets. RURAL = urban endpoint "
				+ "of each connecting request is replaced by the hub coord. URBAN = rural endpoint "
				+ "is replaced. Must be set together with hubSetGeoJsonPath; either being null "
				+ "disables virtual-trip expansion. Default: null (disabled).");

		map.put("requestClassificationsPath",
				"Paper-2 Extension 2: path to the request-classifications CSV emitted by the Phase-2 "
				+ "Python classifier. Columns: personId, tripIndex, requestTag (in any order; extra "
				+ "columns tolerated). When set, DrtRequestFactory loads it via "
				+ "RequestClassificationLoader and stamps each DrtRequest.requestTag. Default: null "
				+ "(disabled — DrtRequest.requestTag stays null, preserving Kelheim behaviour).");

		map.put("hubTransferBufferSeconds",
				"Paper-2 Ext-2: scheduled hub transfer slack in seconds; urban virtual legs are "
				+ "shifted by ruralLegTime + buffer and charged the buffer as waiting disutility. "
				+ "Default: 300.");

		map.put("maxHubWaitSeconds",
				"Paper-2 hub-sync v1: width (s) of the hub-departure/wait window for CONTINUATION "
				+ "(hub->urban) virtual legs. When >0, the continuation leg may depart anywhere in "
				+ "[hubArrival, hubArrival + maxHubWaitSeconds] (hubArrival = requestTime + ruralLegTime), "
				+ "so the urban shareability graph enumerates pooled continuation bundles at different "
				+ "departure slots; transferWaitSeconds is set to 0 (served wait realized by bundling). "
				+ "Default 0.0 = byte-identical to the legacy fixed-buffer behavior.");

        return map;
    }
}
