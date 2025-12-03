package org.matsim.contrib.exmas.config;

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

    private BudgetCalculationMode budgetCalculationMode = BudgetCalculationMode.TRIP_LEVEL;
    private String drtMode = "drt";

	// Base modes to evaluate for budget calculation (e.g., car, pt, walk, bike)
	// Each mode will be routed using its own routing module
	private Set<String> baseModes = Set.of("car", "pt", "walk", "bike");

	// Routing mode to use for DRT when no DRT routing module is registered
	// Typically "car" for network-based routing or the DRT mode name if module
	// exists
	private String drtRoutingMode = "car";

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
	
	// ExMAS algorithm parameters
	// Flexible temporal windows for departure and arrival
	// Origin flexibility (departure window): How much earlier/later can passenger depart?
	// - Absolute: Fixed time buffer regardless of trip (seconds)
	// - Relative: Fraction of max detour budget (dimensionless, 0.0-1.0)
	private double originFlexibilityAbsolute = 300.0; // Absolute origin flex (5 minutes)
	private double originFlexibilityRelative = 0.5; // Relative origin flex (50% of detour budget)
	
	// Destination flexibility (arrival window): How much earlier/later can passenger arrive?
	private double destinationFlexibilityAbsolute = 300.0; // Absolute destination flex (5 minutes)
	private double destinationFlexibilityRelative = 0.5; // Relative destination flex (50% of detour budget)
	
	// Maximum detour factor: Maximum acceptable detour as factor of direct travel time
	// Example: 1.5 means maximum travel time = 1.5 * direct travel time
	//C: this should limit the detour to this max factor during the max detour calculation from the remaining budget for each requests
	private double maxDetourFactor = 1.5; // Maximum detour factor (50% longer than direct)
	
	private int networkTimeBinSize = 900; // Network cache time bin size in seconds (15 minutes)

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

	@StringGetter("departureFlexibilitySeconds")
	public double getDepartureFlexibilitySeconds() {
		return departureFlexibilitySeconds;
	}

	@StringSetter("departureFlexibilitySeconds")
	public void setDepartureFlexibilitySeconds(double departureFlexibilitySeconds) {
		this.departureFlexibilitySeconds = departureFlexibilitySeconds;
	}

	@StringGetter("maxDetourSeconds")
	public double getMaxDetourSeconds() {
		return maxDetourSeconds;
	}

	@StringSetter("maxDetourSeconds")
	public void setMaxDetourSeconds(double maxDetourSeconds) {
		this.maxDetourSeconds = maxDetourSeconds;
	}

	@StringGetter("networkTimeBinSize")
	public int getNetworkTimeBinSize() {
		return networkTimeBinSize;
	}

	@StringSetter("networkTimeBinSize")
	public void setNetworkTimeBinSize(int networkTimeBinSize) {
		this.networkTimeBinSize = networkTimeBinSize;
	}
	
	// Origin flexibility getters/setters
	@StringGetter("originFlexibilityAbsolute")
	public double getOriginFlexibilityAbsolute() {
		return originFlexibilityAbsolute;
	}

	@StringSetter("originFlexibilityAbsolute")
	public void setOriginFlexibilityAbsolute(double originFlexibilityAbsolute) {
		this.originFlexibilityAbsolute = originFlexibilityAbsolute;
	}
	
	@StringGetter("originFlexibilityRelative")
	public double getOriginFlexibilityRelative() {
		return originFlexibilityRelative;
	}

	@StringSetter("originFlexibilityRelative")
	public void setOriginFlexibilityRelative(double originFlexibilityRelative) {
		this.originFlexibilityRelative = originFlexibilityRelative;
	}
	
	// Destination flexibility getters/setters
	@StringGetter("destinationFlexibilityAbsolute")
	public double getDestinationFlexibilityAbsolute() {
		return destinationFlexibilityAbsolute;
	}

	@StringSetter("destinationFlexibilityAbsolute")
	public void setDestinationFlexibilityAbsolute(double destinationFlexibilityAbsolute) {
		this.destinationFlexibilityAbsolute = destinationFlexibilityAbsolute;
	}
	
	@StringGetter("destinationFlexibilityRelative")
	public double getDestinationFlexibilityRelative() {
		return destinationFlexibilityRelative;
	}

	@StringSetter("destinationFlexibilityRelative")
	public void setDestinationFlexibilityRelative(double destinationFlexibilityRelative) {
		this.destinationFlexibilityRelative = destinationFlexibilityRelative;
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

    @Override
    public Map<String, String> getComments() {
        Map<String, String> map = super.getComments();
        map.put(BUDGET_CALCULATION_MODE, "Mode for calculating utility budget. Options: [TRIP_LEVEL, SUBTOUR_SUM]. Default: TRIP_LEVEL.");
        map.put(DRT_MODE, "The mode name of the DRT service to be optimized. Default: 'drt'.");
		map.put("drtRoutingMode",
				"Routing mode to use for DRT when no DRT routing module exists. Typically 'car' for network-based routing. Default: 'car'");
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
		map.put("originFlexibilityAbsolute",
				"Absolute origin flexibility (seconds). Fixed time buffer for early/late departure. Default: 300 (5 min)");
		map.put("originFlexibilityRelative",
				"Relative origin flexibility (fraction of max detour). 0.5 means 50% of detour budget can be used for departure shifts. Default: 0.5");
		map.put("destinationFlexibilityAbsolute",
				"Absolute destination flexibility (seconds). Fixed time buffer for early/late arrival. Default: 300 (5 min)");
		map.put("destinationFlexibilityRelative",
				"Relative destination flexibility (fraction of max detour). 0.5 means 50% of detour budget can be used for arrival shifts. Default: 0.5");
		map.put("maxDetourFactor",
				"Maximum detour factor. Maximum travel time = factor * direct travel time. 1.5 means 50% longer. Default: 1.5");
		map.put("networkTimeBinSize",
				"Time bin size for network travel time caching (seconds). Queries within same bin reuse cached values. Default: 900 (15 min)");
        return map;
    }
}
