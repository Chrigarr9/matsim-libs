package org.matsim.contrib.demand_extraction.config;

import java.util.Map;
import java.util.Set;

import org.matsim.core.config.ReflectiveConfigGroup;
import org.matsim.core.config.ReflectiveConfigGroup.StringGetter;
import org.matsim.core.config.ReflectiveConfigGroup.StringSetter;

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
     * - NON_COMMUTES: Exclude commute trips
     */
    public enum CommuteFilter {
        ALL,
        COMMUTES_ONLY,
        NON_COMMUTES
    }

    private BudgetCalculationMode budgetCalculationMode = BudgetCalculationMode.TRIP_LEVEL;
    private String drtMode = "drt";

    // Commute identification settings
    private String homeActivityType = "home";
    private String workActivityType = "work";
    private CommuteFilter commuteFilter = CommuteFilter.ALL;

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
	
	private Double maxAbsoluteDetour = null; // Absolute detour cap (seconds). If set, limits the max detour time.

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

	private int networkTimeBinSize = 900; // Network cache time bin size in seconds (15 minutes)
	
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

	// Heuristics and post-processing settings (align with exmas_pipeline.heuristics)
	// Controls parallelism for expensive metrics (Shapley, predecessors)
	// -1 => use all available processors; 1 => force sequential
	private int heuristicsProcessCount = -1;

	// Calculate Shapley values for rides (distance contribution per passenger)
	private boolean calcShapleyValues = true;

	// Calculate predecessor/successor relationships between rides
	// When enabled, connection cache is automatically written
	private boolean calcPredecessors = true;

	// Maximum time gap (seconds) between predecessor end and successor start; null => unbounded
	private Double predecessorsFilterTime = null;

	// Maximum connection distance as factor of predecessor ride distance; null => unbounded
	private Double predecessorsFilterDistanceFactor = null;

	// Optional intermediate writes (parity with Python, currently unused)
	private boolean intermediateWrite = false;

	// Default walk speed for access/egress calculations (m/s)
	// 0.833333333 m/s = 3 km/h (typical walking speed)
	public static final double DEFAULT_WALK_SPEED = 0.833333333;

    public ExMasConfigGroup() {
        super(GROUP_NAME);
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

    @StringGetter("commuteFilter")
    public CommuteFilter getCommuteFilter() {
        return commuteFilter;
    }

    @StringSetter("commuteFilter")
    public void setCommuteFilter(CommuteFilter commuteFilter) {
        this.commuteFilter = commuteFilter;
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
	public Double getMaxAbsoluteDetour() {
		return maxAbsoluteDetour;
	}

	@StringSetter("maxAbsoluteDetour")
	public void setMaxAbsoluteDetour(Double maxAbsoluteDetour) {
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

	@StringGetter("intermediateWrite")
	public boolean isIntermediateWrite() {
		return intermediateWrite;
	}

	@StringSetter("intermediateWrite")
	public void setIntermediateWrite(boolean intermediateWrite) {
		this.intermediateWrite = intermediateWrite;
	}

    @Override
    public Map<String, String> getComments() {
        Map<String, String> map = super.getComments();
        map.put(BUDGET_CALCULATION_MODE, "Mode for calculating utility budget. Options: [TRIP_LEVEL, SUBTOUR_SUM]. Default: TRIP_LEVEL.");
        map.put(DRT_MODE, "The mode name of the DRT service to be optimized. Default: 'drt'.");
        map.put("homeActivityType", "Activity type prefix for home activities (used for commute identification). Default: 'home'");
        map.put("workActivityType", "Activity type prefix for work activities (used for commute identification). Default: 'work'");
        map.put("commuteFilter", "Filter for commute trips. Options: [ALL, COMMUTES_ONLY, NON_COMMUTES]. Default: ALL");
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
		map.put("heuristicsProcessCount",
				"Parallelism for Shapley/predecessor calculations. -1 = all processors, 1 = sequential. Default: -1");
		map.put("calcShapleyValues", "Calculate Shapley values for each ride (distance contribution per passenger). Default: true");
		map.put("calcPredecessors",
				"Calculate predecessor/successor relationships between rides. When enabled, connection cache is automatically written. Default: true");
		map.put("predecessorsFilterTime",
				"Maximum time gap (seconds) between predecessor end and successor start. Null/omitted => unbounded.");
		map.put("predecessorsFilterDistanceFactor",
				"Maximum connection distance as factor of predecessor ride distance. Null/omitted => unbounded.");
		map.put("intermediateWrite",
				"Write intermediate outputs during heuristics (parity with Python implementation). Default: false");
        return map;
    }
}
