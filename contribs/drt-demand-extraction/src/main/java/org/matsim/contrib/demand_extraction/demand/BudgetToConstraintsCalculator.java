package org.matsim.contrib.demand_extraction.demand;

import org.matsim.api.core.v01.TransportMode;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.groups.ScoringConfigGroup;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Translates utility budget into physical constraints (detour time, waiting time, walk distance, fare).
 * 
 * Uses MATSim scoring parameters to convert utility (utils) into time/distance/money:
 * - Detour time: Based on marginal utility of traveling
 * - Waiting time: Typically scored same as travel time
 * - Walk distance: Based on walk mode parameters
 * - Fare: Based on marginal utility of money
 * 
 * The budget represents maximum utility loss acceptable for DRT vs best alternative mode.
 * This calculator determines how much service quality degradation that budget allows.
 */
@Singleton
public class BudgetToConstraintsCalculator {
	
	private final Config config;
	private final ScoringConfigGroup scoringConfig;
	private final ExMasConfigGroup exMasConfig;
	private final DrtConfigGroup drtConfig;
	
	// Scoring parameters for DRT mode
	private final double marginalUtilityOfTraveling_s;
	private final double marginalUtilityOfDistance_m;
	private final double marginalUtilityOfMoney;
	private final double monetaryDistanceCostRate;
	private final double marginalUtilityOfWaiting_s;
	
	// DRT fare parameters
	private final double baseFare;
	private final double timeFare_h;
	private final double distanceFare_m;
	private final double minFarePerTrip;
	
	@Inject

	public BudgetToConstraintsCalculator(Config config, ExMasConfigGroup exMasConfig) {
		this.config = config;
		this.scoringConfig = config.scoring();
		this.exMasConfig = exMasConfig;
		this.drtConfig = DrtConfigGroup.getSingleModeDrtConfig(config);
		
		// Get DRT mode scoring parameters
		ScoringConfigGroup.ModeParams drtParams = scoringConfig.getModes().get(exMasConfig.getDrtMode());
		if (drtParams == null) {
			throw new IllegalStateException("No scoring parameters configured for DRT mode: " + exMasConfig.getDrtMode());
		}
		//C: do these need to be personalized per agent/person??
		this.marginalUtilityOfTraveling_s = drtParams.getMarginalUtilityOfTraveling() / 3600.0; // per second
		this.marginalUtilityOfDistance_m = drtParams.getMarginalUtilityOfDistance(); // per meter
		this.marginalUtilityOfMoney = scoringConfig.getMarginalUtilityOfMoney();
		this.monetaryDistanceCostRate = drtParams.getMonetaryDistanceRate();
		this.marginalUtilityOfWaiting_s = scoringConfig.getMarginalUtlOfWaiting_utils_hr() / 3600.0; // per second
		
		// Get DRT fare parameters
		var drtFareParams = drtConfig.getDrtFareParams().orElse(null);
		if (drtFareParams != null) {
			this.baseFare = drtFareParams.getBaseFare();
			this.timeFare_h = drtFareParams.getTimeFare_h();
			this.distanceFare_m = drtFareParams.getDistanceFare_m();
			this.minFarePerTrip = drtFareParams.getMinFarePerTrip();
		} else {
			// No fare configured
			this.baseFare = 0.0;
			this.timeFare_h = 0.0;
			this.distanceFare_m = 0.0;
			this.minFarePerTrip = 0.0;
		}
	}
	
	/**
	 * Calculate maximum acceptable detour time from remaining budget.
	 * 
	 * Detour increases:
	 * - Travel time disutility: marginalUtilityOfTraveling_s * detourTime
	 * - Distance disutility: marginalUtilityOfDistance_m * extraDistance (approximated)
	 * - Fare: distanceFare_m * extraDistance + timeFare_h * detourTime
	 * 
	 * @param budget remaining utility budget (utils)
	 * @param directTravelTime direct travel time (seconds)
	 * @param directDistance direct distance (meters)
	 * @return maximum detour time in seconds, or Double.POSITIVE_INFINITY if unconstrained
	 */
	public double budgetToMaxDetourTime(double budget, double directTravelTime, double directDistance) {
		if (budget <= 0) {
			return 0.0; // No budget for detours
		}
		
		// Approximate: detour distance ≈ detour time * (directDistance / directTravelTime)
		// Assume detour maintains similar speed as direct route
		double speedMps = directDistance / directTravelTime; // meters per second
		
		// Total marginal disutility per second of detour:
		// = travel time disutility + distance disutility + fare
		// C: are there person specific scoring poarams in MATSim? Maybe not in the default but defuinetly in more advaed scoring systems.
		// This means that we have to use the person specific Utilities. Maybe using the scoring function factory r similar?
		// example: rich people do not care so much over hicg prices. Thats the whole point of Agent based simulations
		// this also goes for all the other scoring instances we use.
		double travelDisutilityPerSecond = marginalUtilityOfTraveling_s;
		double distanceDisutilityPerSecond = marginalUtilityOfDistance_m * speedMps;
		double fareDisutilityPerSecond = (distanceFare_m * speedMps + timeFare_h / 3600.0) * marginalUtilityOfMoney;
		double monetaryCostPerSecond = monetaryDistanceCostRate * speedMps * marginalUtilityOfMoney;
		
		double totalDisutilityPerSecond = travelDisutilityPerSecond 
			+ distanceDisutilityPerSecond 
			+ fareDisutilityPerSecond
			+ monetaryCostPerSecond;
		
		if (totalDisutilityPerSecond <= 0) {
			// Detour is beneficial (shouldn't happen with proper scoring)
			return Double.POSITIVE_INFINITY;
		}
		
		// budget = totalDisutilityPerSecond * maxDetourTime
		// Note: budget is negative (disutility), so we negate it
		return Math.abs(budget) / totalDisutilityPerSecond;
	}
	
	/**
	 * Calculate maximum acceptable waiting time (departure delay) from budget.
	 * 
	 * Waiting time is scored using marginalUtilityOfWaiting parameter.
	 * In MATSim DRT, stop duration (pickup/dropoff time) is included in the route's
	 * travel time and thus scored as travel time, not waiting time.
	 * 
	 * @param budget remaining utility budget (utils)
	 * @return maximum waiting time in seconds
	 */
	public double budgetToMaxWaitingTime(double budget) {
		if (budget <= 0) {
			return 0.0;
		}
		
		// Use marginalUtilityOfWaiting for waiting time (departure delay before pickup)
		if (marginalUtilityOfWaiting_s >= 0) {
			return Double.POSITIVE_INFINITY; // Positive utility for waiting (unusual)
		}
		
		return Math.abs(budget) / Math.abs(marginalUtilityOfWaiting_s);
	}
	
	/**
	 * Calculate maximum acceptable access/egress walk distance from budget.
	 * 
	 * @param budget remaining utility budget (utils)
	 * @return maximum walk distance in meters
	 */
	public double budgetToMaxWalkDistance(double budget) {
		if (budget <= 0) {
			return exMasConfig.getMinDrtAccessEgressDistance();
		}
		
		// Get walk mode parameters
		ScoringConfigGroup.ModeParams walkParams = scoringConfig.getModes().get(TransportMode.walk);
		if (walkParams == null) {
			// No walk configured, use minimum
			return exMasConfig.getMinDrtAccessEgressDistance();
		}
		
		double walkSpeed = config.routing().getOrCreateModeRoutingParams(TransportMode.walk).getTeleportedModeSpeed();
		if (walkSpeed == 0.0) {
			walkSpeed = 0.833333333; // 3 km/h default
		}
		
		double walkMarginalUtilityTraveling_s = walkParams.getMarginalUtilityOfTraveling() / 3600.0;
		double walkMarginalUtilityDistance_m = walkParams.getMarginalUtilityOfDistance();
		
		// Disutility per meter of walking:
		// = time disutility + distance disutility
		double timePerMeter = 1.0 / walkSpeed;
		double disutilityPerMeter = walkMarginalUtilityTraveling_s * timePerMeter + walkMarginalUtilityDistance_m;
		
		if (disutilityPerMeter >= 0) {
			return Double.POSITIVE_INFINITY;
		}
		
		double maxWalkDistance = Math.abs(budget) / Math.abs(disutilityPerMeter);
		
		// Apply minimum access/egress distance
		return Math.max(maxWalkDistance, exMasConfig.getMinDrtAccessEgressDistance());
	}
}
