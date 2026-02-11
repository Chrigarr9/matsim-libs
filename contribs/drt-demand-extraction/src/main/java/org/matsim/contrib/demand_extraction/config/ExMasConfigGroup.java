package org.matsim.contrib.demand_extraction.config;

import java.util.Map;
import java.util.Set;

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

	// Base modes to evaluate for budget calculation (e.g., car, pt, walk, bike)
	// Each mode will be routed using its own routing module
	private Set<String> baseModes = Set.of("car", "pt", "walk", "bike");

	// Routing mode to use for DRT when no DRT routing module is registered
	// Typically "car" for network-based routing or the DRT mode name if module
	// exists
	private String drtRoutingMode = "car";

	// Network modes allowed for DRT routing (filters links by allowedModes)
	// If empty/null, all links are used (for ease of use)
	// Example: Set.of("car") = only links where car is allowed
	// Set.of("car", "truck") = links where car OR truck allowed
	// Set.of() or null = all links (no filtering)
	private Set<String> drtAllowedModes = Set.of("car");

	// Modes that represent private vehicles (create subtour dependencies)
	// Default: car and bike (modes that need to return to their origin)
	private Set<String> privateVehicleModes = Set.of("car", "bike");

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

	private int networkTimeBinSize = 60 * 60; // Network cache time bin size in seconds (15 minutes)
	
	// ExMAS algorithm parameters
	private double searchHorizon = 600.0; // Time horizon for pairing requests (seconds, 10 minutes)
	private int maxPoolingDegree = Integer.MAX_VALUE; // Maximum number of passengers per ride

	// Network routing settings
	// If true, uses OnlyTimeDependentTravelDisutility for deterministic routing (ignores tolls)
	// If false, uses mode-specific TravelDisutility which may include tolls and other costs
	private boolean useDeterministicNetworkRouting = false;

	// PT routing settings
	// If true, allows the PT router to optimize departure time to reduce waiting
	// This means agents can leave earlier/later to catch better PT connections
	private boolean ptOptimizeDepartureTime = true;

	// If true, includes opportunity cost of time (lost activity time) in trip scoring
	// Effective marginal utility of travel = marginalUtilityOfTraveling - marginalUtilityOfPerforming
	// This is important when marginalUtilityOfTraveling is zero (e.g. Kelheim PT)
	private boolean includeOpportunityCost = true;

	// Heuristics and post-processing settings (align with exmas_pipeline.heuristics)
	// Controls parallelism in the ExMAS *core algorithm* (pair generation + extensions)
	// -1 => use parallel streams (default); 1 => force sequential (for reproducible results)
	private int algorithmProcessCount = -1;

	// Controls parallelism for expensive metrics (Shapley, predecessors)
	// -1 => use all available processors; 1 => force sequential
	private int heuristicsProcessCount = -1;

	// Heuristic pruning (to control combinatorial growth during ride extension)
	private boolean pruningEnabled = true;
	// Fraction of rides KEPT (not pruned) within each request-set group. Range
	// (0,1].
	// Grouping key is the request index set (same passengers) and objective ranking
	// is applied within each group.
	private double pruningFraction = 0.5;
	// Lower bound on how many rides to keep per request-set group (if that many
	// exist).
	private int pruningMinToKeep = 3;
	// Optional hard cap on how many rides to keep per request-set group (0
	// disables).
	private int pruningMaxRidesToKeepPerRequestSet = 0;
	// Degree-aware distance savings pruning (applied vs serving requests separately)
	// requiredSaving(d) = min(maxSaving, max(0, pruningDistanceSavingsLogScale * log2(d)))
	// Keep ride iff: rideDistance <= (1 - requiredSaving(d)) * sum(request distances)
	// Semantics:
	// - scale < 0 : disable this pruning gate
	// - scale = 0 : legacy behavior (non-improving filter): rideDistance <= sum(request distances)
	// - scale > 0 : require additional distance savings that increases with degree
	private double pruningDistanceSavingsLogScale = 0.0;
	// Clamp for requiredSaving(d) to avoid impossible constraints at high degrees.
	private double pruningDistanceSavingsMax = 0.9;
	// Apply distance-savings pruning only for rides with degree >= this value.
	// Default 3 ensures paired rides (degree 2) are not removed, which is important
	// for shareability graph connectivity.
	private int pruningDistanceSavingsMinDegree = 3;
	private String pruningObjective = "rideDistance"; // rideDistance | passengerTravelTime | passengerUtility
	private String pruningGoal = "minimize"; // minimize | maximize
	// If >0, limit per-base extension candidates (for each base ride, keep only the
	// top-N extensions by objective).
	// This is applied before the per-request-set group pruning.
	private int pruningTopNPerBase = 0;

	// Calculate Shapley values for rides (distance contribution per passenger)
	private boolean calcShapleyValues = true;

	// Calculate predecessor/successor relationships between rides
	// When enabled, connection cache is automatically written
	private boolean calcPredecessors = true;

	// Maximum time gap (seconds) between predecessor end and successor start.
	// null/omitted => unbounded, -1 => unbounded (explicit).
	// Set to match Python's path_cover_max_time_gap for complete connection cache coverage.
	private Double predecessorsFilterTime = null;

	// Maximum connection distance as factor of predecessor ride distance.
	// null/omitted => unbounded, -1 => unbounded (explicit).
	private Double predecessorsFilterDistanceFactor = null;

	// Maximum number of successors to keep per ride (closest by distance).
	// 0 or -1 => keep all (no pruning). Default: 50
	private int maxSuccessors = 50;

	// Connection cache export mode:
	// - "all": Export ALL cached connections (default — needed for dynamic successor computation in Python)
	// - "successors_only": Export only connections between successor ride pairs (legacy behavior, smaller file)
	private String connectionCacheExportMode = "all";

	// Optional intermediate writes (parity with Python, currently unused)
	private boolean intermediateWrite = false;

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
	private String predefinedStopsFile = null;

	/** Whether to use MATSim's walk router for distance/time calculations */
	private boolean useMatsimWalkRouter = true;

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

    public ExMasConfigGroup() {
        super(GROUP_NAME);
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

	@StringGetter("useDeterministicNetworkRouting")
	public boolean isUseDeterministicNetworkRouting() {
		return useDeterministicNetworkRouting;
	}

	@StringSetter("useDeterministicNetworkRouting")
	public void setUseDeterministicNetworkRouting(boolean useDeterministicNetworkRouting) {
		this.useDeterministicNetworkRouting = useDeterministicNetworkRouting;
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

	@StringGetter("pruningKeepTopFractionPerRequestSet")
	public double getPruningKeepTopFractionPerRequestSet() {
		return pruningFraction;
	}

	@StringSetter("pruningKeepTopFractionPerRequestSet")
	public void setPruningKeepTopFractionPerRequestSet(double pruningKeepTopFractionPerRequestSet) {
		this.pruningFraction = pruningKeepTopFractionPerRequestSet;
	}

	@StringGetter("pruningMinRidesToKeepPerRequestSet")
	public int getPruningMinRidesToKeepPerRequestSet() {
		return pruningMinToKeep;
	}

	@StringSetter("pruningMinRidesToKeepPerRequestSet")
	public void setPruningMinRidesToKeepPerRequestSet(int pruningMinRidesToKeepPerRequestSet) {
		this.pruningMinToKeep = pruningMinRidesToKeepPerRequestSet;
	}

	@StringGetter("pruningMaxRidesToKeepPerRequestSet")
	public int getPruningMaxRidesToKeepPerRequestSet() {
		return pruningMaxRidesToKeepPerRequestSet;
	}

	@StringSetter("pruningMaxRidesToKeepPerRequestSet")
	public void setPruningMaxRidesToKeepPerRequestSet(int pruningMaxRidesToKeepPerRequestSet) {
		this.pruningMaxRidesToKeepPerRequestSet = pruningMaxRidesToKeepPerRequestSet;
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

	@StringGetter("pruningRankingObjective")
	public String getPruningRankingObjective() {
		return pruningObjective;
	}

	@StringSetter("pruningRankingObjective")
	public void setPruningRankingObjective(String pruningRankingObjective) {
		this.pruningObjective = pruningRankingObjective;
	}

	@StringGetter("pruningRankingGoal")
	public String getPruningRankingGoal() {
		return pruningGoal;
	}

	@StringSetter("pruningRankingGoal")
	public void setPruningRankingGoal(String pruningRankingGoal) {
		this.pruningGoal = pruningRankingGoal;
	}

	@StringGetter("pruningKeepTopNExtensionsPerBaseRide")
	public int getPruningKeepTopNExtensionsPerBaseRide() {
		return pruningTopNPerBase;
	}

	@StringSetter("pruningKeepTopNExtensionsPerBaseRide")
	public void setPruningKeepTopNExtensionsPerBaseRide(int pruningKeepTopNExtensionsPerBaseRide) {
		this.pruningTopNPerBase = pruningKeepTopNExtensionsPerBaseRide;
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

	@StringGetter("connectionCacheExportMode")
	public String getConnectionCacheExportMode() {
		return connectionCacheExportMode;
	}

	@StringSetter("connectionCacheExportMode")
	public void setConnectionCacheExportMode(String connectionCacheExportMode) {
		this.connectionCacheExportMode = connectionCacheExportMode;
	}

	@StringGetter("intermediateWrite")
	public boolean isIntermediateWrite() {
		return intermediateWrite;
	}

	@StringSetter("intermediateWrite")
	public void setIntermediateWrite(boolean intermediateWrite) {
		this.intermediateWrite = intermediateWrite;
	}

	@StringGetter("includeOpportunityCost")
	public boolean isIncludeOpportunityCost() {
		return includeOpportunityCost;
	}

	@StringSetter("includeOpportunityCost")
	public void setIncludeOpportunityCost(boolean includeOpportunityCost) {
		this.includeOpportunityCost = includeOpportunityCost;
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

	@StringGetter("predefinedStopsFile")
	public String getPredefinedStopsFile() {
		return predefinedStopsFile;
	}

	@StringSetter("predefinedStopsFile")
	public void setPredefinedStopsFile(String predefinedStopsFile) {
		this.predefinedStopsFile = predefinedStopsFile;
	}

	@StringGetter("useMatsimWalkRouter")
	public boolean isUseMatsimWalkRouter() {
		return useMatsimWalkRouter;
	}

	@StringSetter("useMatsimWalkRouter")
	public void setUseMatsimWalkRouter(boolean useMatsimWalkRouter) {
		this.useMatsimWalkRouter = useMatsimWalkRouter;
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
		map.put("drtRoutingMode",
				"Routing mode to use for DRT when no DRT routing module exists. Typically 'car' for network-based routing. Default: 'car'");
		map.put("drtAllowedModes",
				"Network modes allowed for DRT routing (comma-separated). Filters links by allowedModes. Empty = all links allowed. Example: 'car' or 'car,truck'. Default: empty (all links)");
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
		map.put("searchHorizon",
				"Time horizon for pairing requests in ExMAS algorithm (seconds). Requests within this window can be paired. Default: 600 (10 min)");
		map.put("maxPoolingDegree",
				"Maximum number of passengers per shared ride. Default: 2");
		map.put("useDeterministicNetworkRouting",
				"If true, uses time-only travel disutility (deterministic but ignores tolls). " +
				"If false, uses mode-specific travel disutility (includes tolls but may have slight variation). Default: false");
		map.put("ptOptimizeDepartureTime",
				"If true, PT router can optimize departure time to reduce waiting times. " +
				"Agent can leave earlier/later to catch better connections. Default: true");
		map.put("includeOpportunityCost",
				"If true, includes opportunity cost of time (lost activity time) in trip scoring. " +
				"Essential when marginalUtilityOfTraveling is zero. Default: true");
		map.put("algorithmProcessCount",
				"Parallelism for core ExMAS pair generation and ride extension. -1 = all processors, 1 = sequential (more deterministic). Default: -1");
		map.put("heuristicsProcessCount",
				"Parallelism for Shapley/predecessor calculations. -1 = all processors, 1 = sequential. Default: -1");
		map.put("heuristicPruningEnabled",
				"Enable heuristic pruning during ride extension to avoid combinatorial explosion. Default: true");
		map.put("pruningKeepTopFractionPerRequestSet",
				"Keep top fraction of rides per request-set group after each extension. Range (0,1]. Default: 0.5");
		map.put("pruningMinRidesToKeepPerRequestSet",
				"Minimum number of rides to keep per request-set group regardless of fraction. Default: 3");
		map.put("pruningMaxRidesToKeepPerRequestSet",
				"Maximum number of rides to keep per request-set group (0 disables). Default: 0");
		map.put("pruningDistanceSavingsLogScale",
				"Degree-aware distance savings pruning: requiredSaving(d)=scale*log2(d), clamped; keep iff rideDistance <= (1-requiredSaving)*sum(request distances). scale<0 disables; scale=0 matches legacy non-improving (rideDistance <= sumDistances). Default: 0.0");
		map.put("pruningDistanceSavingsMax",
				"Maximum requiredSaving(d) clamp for distance savings pruning (0-0.99). Default: 0.9");
		map.put("pruningDistanceSavingsMinDegree",
				"Minimum pooling degree for applying distance savings pruning. Default: 3 (do not prune paired rides). ");
		map.put("pruningRankingObjective",
				"Objective for ranking rides within groups: rideDistance | passengerTravelTime | passengerUtility. Default: rideDistance");
		map.put("pruningRankingGoal", "Ranking goal for pruning objective: minimize | maximize. Default: minimize");
		map.put("pruningKeepTopNExtensionsPerBaseRide",
				"Limit number of extensions per base ride to top N by objective (0 disables). Default: 0");
		map.put("calcShapleyValues", "Calculate Shapley values for each ride (distance contribution per passenger). Default: true");
		map.put("calcPredecessors",
				"Calculate predecessor/successor relationships between rides. When enabled, connection cache is automatically written. Default: true");
		map.put("predecessorsFilterTime",
				"Maximum time gap (seconds) between predecessor end and successor start. " +
				"-1 or null/omitted => unbounded (all ride pairs considered, creates complete connection cache). " +
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
		map.put("intermediateWrite",
				"Write intermediate outputs during heuristics (parity with Python implementation). Default: false");

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
		map.put("predefinedStopsFile",
				"Path to predefined stops file (MATSim TransitStops/Facilities XML). Required when stopFindingStrategy=PREDEFINED. Default: null");
		map.put("useMatsimWalkRouter",
				"Whether to use MATSim's walk router for distance/time calculations. If false, uses Euclidean distance. Default: true");

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
		map.put("hyperPoolStopProximityMeters",
				"Stop proximity threshold for considering stops as 'same' (meters). Stops within this distance can be merged. Default: 100.0");
		map.put("hyperPoolEnableSpatialFilter",
				"Enable spatial proximity pre-filtering for stage 2 bundling. If true (default): only evaluates ride pairs with nearby stops (faster, finds 85-95% of patterns). If false: evaluates all pairs like original ExMAS HyperPool (slower, finds 100% of patterns). Default: true");
		map.put("hyperPoolEnableStopRelocation",
				"Enable stop relocation (merging nearby stops). Default: false (matches original ExMAS/HyperPool). If true: Production optimization that merges nearby stops using weighted centroid to reduce route complexity.");
		map.put("hyperPoolEnableDirectionalFilter",
				"Enable directional compatibility filter (rejects rides moving opposite directions). Default: false (matches original ExMAS/HyperPool). If true: Production optimization that filters incompatible ride pairs early.");

        return map;
    }
}
