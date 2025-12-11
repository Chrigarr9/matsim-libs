package org.matsim.contrib.demand_extraction.demand;

import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.scoring.functions.ModeUtilityParameters;
import org.matsim.core.scoring.functions.ScoringParameters;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;

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
	private final ExMasConfigGroup exMasConfig;
	private final DrtConfigGroup drtConfig;
	private final ScoringParametersForPerson scoringParametersForPerson;
	private final Population population;
	
	// DRT fare parameters (not person-specific)
	private final double baseFare;
	private final double timeFare_h;
	private final double distanceFare_m;
	private final double minFarePerTrip;
	
	@Inject
	public BudgetToConstraintsCalculator(
			Config config,
			ExMasConfigGroup exMasConfig,
			ScoringParametersForPerson scoringParametersForPerson,
			Population population) {
		this.config = config;
		this.exMasConfig = exMasConfig;
		this.drtConfig = DrtConfigGroup.getSingleModeDrtConfig(config);
		this.scoringParametersForPerson = scoringParametersForPerson;
		this.population = population;
		
		// Verify DRT mode is configured in scoring
		if (!config.scoring().getModes().containsKey(exMasConfig.getDrtMode())) {
			throw new IllegalStateException("No scoring parameters configured for DRT mode: " + exMasConfig.getDrtMode());
		}
		
		// Get DRT fare parameters (these are not person-specific)
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
	 * Calculate maximum acceptable fare from remaining budget.
	 * 
	 * The max cost represents the maximum fare a passenger can pay while still
	 * maintaining a non-negative utility budget (DRT remains at least as good as
	 * their best alternative mode).
	 * 
	 * Calculation:
	 * - Base fare: What the trip would cost at minimum service level (from DRT config)
	 * - Additional affordable fare: remaining budget (utils) / marginalUtilityOfMoney
	 * - Max cost = base fare + additional affordable fare
	 * 
	 * This aligns with other budgetToXxx methods - takes budget and person as inputs.
	 * 
	 * @param budget remaining utility budget (utils, must be >= 0)
	 * @param person the person for whom to calculate (used for marginalUtilityOfMoney)
	 * @param travelTimeSeconds actual travel time for this trip segment (seconds)
	 * @param distanceMeters actual distance for this trip segment (meters)
	 * @return maximum acceptable fare in currency units, or 0.0 if budget is insufficient
	 */
	public double budgetToMaxCost(double budget, Person person, double travelTimeSeconds, double distanceMeters) {
		if (budget < 0 || !Double.isFinite(budget)) {
			return 0.0; // No budget for any cost
		}
		
		// Get person-specific marginal utility of money
		ScoringParameters params = scoringParametersForPerson.getScoringParameters(person);
		double marginalUtilityOfMoney = params.marginalUtilityOfMoney;
		
		if (!Double.isFinite(marginalUtilityOfMoney) || marginalUtilityOfMoney <= 0) {
			return 0.0; // Cannot convert budget to currency
		}
		
		// Calculate base fare for this trip (minimum fare based on time/distance)
		// Integrated fare calculation (was previously in calculateDrtFare)
		double calculatedFare = this.baseFare 
				+ (timeFare_h * (travelTimeSeconds / 3600.0)) 
				+ (distanceFare_m * distanceMeters);
		double baseFare = Math.max(calculatedFare, minFarePerTrip);
		
		// Convert remaining utility budget to additional affordable fare
		// budget (utils) / marginalUtilityOfMoney (utils/currency) = currency
		double additionalAffordableFare = budget / marginalUtilityOfMoney;
		
		// Maximum cost = base fare + what they can additionally afford from their budget
		double maxCost = baseFare + additionalAffordableFare;
		
		return Math.max(maxCost, minFarePerTrip);
	}
	
	/**
	 * Calculate maximum acceptable detour time from remaining budget using
	 * person-specific parameters.
	 * 
	 * Detour increases:
	 * - Travel time disutility: marginalUtilityOfTraveling_s * detourTime
	 * - Distance disutility: marginalUtilityOfDistance_m * extraDistance
	 * (approximated)
	 * - Fare: distanceFare_m * extraDistance + timeFare_h * detourTime
	 * 
	 * @param budget           remaining utility budget (utils)
	 * @param person           the person for whom to calculate (used for
	 *                         person-specific scoring)
	 * @param directTravelTime direct travel time (seconds)
	 * @param directDistance   direct distance (meters)
	 * @return maximum detour time in seconds, or Double.POSITIVE_INFINITY if
	 *         unconstrained
	 */
	public double budgetToMaxDetourTime(double budget, Person person, double directTravelTime, double directDistance) {
		if (budget <= 0) {
			return 0.0; // No budget for detours
		}
		
		// Get person-specific scoring parameters
		ScoringParameters params = scoringParametersForPerson.getScoringParameters(person);
		ModeUtilityParameters drtParams = params.modeParams.get(exMasConfig.getDrtMode());
		if (drtParams == null) {
			throw new IllegalStateException("No mode parameters for DRT mode: " + exMasConfig.getDrtMode());
		}

		// Person-specific parameters (per second)
		double marginalUtilityOfTraveling_s = drtParams.marginalUtilityOfTraveling_s;
		double marginalUtilityOfDistance_m = drtParams.marginalUtilityOfDistance_m;
		double marginalUtilityOfMoney = params.marginalUtilityOfMoney;
		double monetaryDistanceCostRate = drtParams.monetaryDistanceCostRate;

		// Approximate: detour distance ≈ detour time * (directDistance / directTravelTime)
		// Assume detour maintains similar speed as direct route
		double speedMps = directDistance / directTravelTime; // meters per second
		
		// Total marginal disutility per second of detour:
		// = travel time disutility + distance disutility + fare + monetary cost
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
	 * Calculate maximum acceptable waiting time (departure delay) from budget using
	 * person-specific parameters.
	 * 
	 * Waiting time is scored using marginalUtilityOfWaiting parameter.
	 * In MATSim DRT, stop duration (pickup/dropoff time) is included in the route's
	 * travel time and thus scored as travel time, not waiting time.
	 * 
	 * @param budget remaining utility budget (utils)
	 * @param person the person for whom to calculate (used for person-specific
	 *               scoring)
	 * @return maximum waiting time in seconds
	 */
	public double budgetToMaxWaitingTime(double budget, Person person) {
		if (budget <= 0) {
			return 0.0;
		}
		
		// Get person-specific waiting time utility
		ScoringParameters params = scoringParametersForPerson.getScoringParameters(person);
		double marginalUtilityOfWaiting_s = params.marginalUtilityOfWaitingPt_s;

		// Use marginalUtilityOfWaiting for waiting time (departure delay before pickup)
		if (marginalUtilityOfWaiting_s >= 0) {
			return Double.POSITIVE_INFINITY; // Positive utility for waiting (unusual)
		}
		
		return Math.abs(budget) / Math.abs(marginalUtilityOfWaiting_s);
	}
	
	/**
	 * Calculate maximum acceptable access/egress walk distance from budget using
	 * person-specific parameters.
	 * 
	 * @param budget remaining utility budget (utils)
	 * @param person the person for whom to calculate (used for person-specific
	 *               scoring)
	 * @return maximum walk distance in meters
	 */
	public double budgetToMaxWalkDistance(double budget, Person person) {
		if (budget <= 0) {
			return exMasConfig.getMinDrtAccessEgressDistance();
		}
		
		// Get person-specific walk parameters
		ScoringParameters params = scoringParametersForPerson.getScoringParameters(person);
		ModeUtilityParameters walkParams = params.modeParams.get(TransportMode.walk);
		if (walkParams == null) {
			// No walk configured, use minimum
			return exMasConfig.getMinDrtAccessEgressDistance();
		}
		
		double walkSpeed = config.routing().getOrCreateModeRoutingParams(TransportMode.walk).getTeleportedModeSpeed();
		if (walkSpeed == 0.0) {
			walkSpeed = ExMasConfigGroup.DEFAULT_WALK_SPEED;
		}
		
		double walkMarginalUtilityTraveling_s = walkParams.marginalUtilityOfTraveling_s;
		double walkMarginalUtilityDistance_m = walkParams.marginalUtilityOfDistance_m;
		
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
