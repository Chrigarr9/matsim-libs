package org.matsim.contrib.demand_extraction.algorithm.util;

import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Route;
import org.matsim.core.scoring.functions.ModeUtilityParameters;
import org.matsim.core.scoring.functions.ScoringParameters;

/**
 * Utility class for calculating trip scores consistently across the demand extraction pipeline.
 *
 * <h2>Handling of Daily Constants</h2>
 * MATSim's scoring includes several types of constants:
 * <ul>
 *   <li><b>constant (ASC)</b>: Alternative-Specific Constant, added per trip. Captures utility
 *       not explained by time/distance (e.g., comfort, reliability). INCLUDED in trip scoring.</li>
 *   <li><b>dailyUtilityConstant</b>: Fixed utility for using a mode at all today. Should only
 *       be counted ONCE per day regardless of number of trips. EXCLUDED from trip scoring.</li>
 *   <li><b>dailyMoneyConstant</b>: Fixed monetary cost for using a mode today (e.g., parking fee).
 *       Should only be counted ONCE per day. EXCLUDED from trip scoring.</li>
 * </ul>
 *
 * For demand extraction comparing individual trips:
 * <ul>
 *   <li>Daily constants don't affect the marginal utility of DRT vs other modes for a specific trip</li>
 *   <li>The decision "should I take DRT for THIS trip" depends on trip-level utility, not day-level</li>
 *   <li>Including daily constants per-trip would over-count them (person may make multiple trips)</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * Use these methods instead of creating new ScoringFunction instances per trip to ensure
 * consistent handling of constants across ModeRoutingCache and BudgetValidator.
 */
public final class TripScoringUtils {

	private TripScoringUtils() {
		// Utility class - no instantiation
	}

	/**
	 * Calculate the utility score for a leg, EXCLUDING daily constants.
	 *
	 * This includes:
	 * - Travel time utility (marginalUtilityOfTraveling_s * travelTime)
	 * - Distance utility (marginalUtilityOfDistance_m * distance)
	 * - Monetary distance cost (monetaryDistanceCostRate * distance * marginalUtilityOfMoney)
	 * - Mode constant/ASC (constant) - captures utility not in other parameters
	 *
	 * This EXCLUDES:
	 * - dailyUtilityConstant (should only be counted once per day)
	 * - dailyMoneyConstant (should only be counted once per day)
	 *
	 * @param mode         the transport mode
	 * @param travelTime   travel time in seconds
	 * @param distance     distance in meters
	 * @param params       scoring parameters (can be person-specific)
	 * @return utility score (negative value = disutility)
	 */
	public static double calculateLegScore(String mode, double travelTime, double distance, ScoringParameters params) {
		ModeUtilityParameters modeParams = params.modeParams.get(mode);
		if (modeParams == null) {
			throw new IllegalArgumentException("No scoring parameters for mode: " + mode);
		}

		double score = 0.0;

		// Travel time utility (typically negative)
		score += travelTime * modeParams.marginalUtilityOfTraveling_s;

		// Distance utility (typically negative or zero)
		score += distance * modeParams.marginalUtilityOfDistance_m;

		// Monetary distance cost (converted to utility via marginalUtilityOfMoney)
		score += distance * modeParams.monetaryDistanceCostRate * params.marginalUtilityOfMoney;

		// Mode constant (ASC) - captures utility not explained by other parameters
		// This IS added per trip (MATSim standard behavior)
		score += modeParams.constant;

		// NOTE: We explicitly DO NOT add:
		// - modeParams.dailyUtilityConstant
		// - modeParams.dailyMoneyConstant * params.marginalUtilityOfMoney
		// These are day-level constants that should only be counted once per day,
		// not per trip. For demand extraction comparing individual trips, excluding
		// them gives the correct marginal utility comparison.

		return score;
	}

	/**
	 * Calculate the utility score for a leg from a Leg object, EXCLUDING daily constants.
	 *
	 * @param leg    the leg to score
	 * @param params scoring parameters
	 * @return utility score
	 */
	public static double calculateLegScore(Leg leg, ScoringParameters params) {
		double travelTime = leg.getTravelTime().orElse(0.0);
		double distance = 0.0;
		Route route = leg.getRoute();
		if (route != null) {
			distance = route.getDistance();
			if (Double.isNaN(distance)) {
				distance = 0.0;
			}
		}
		return calculateLegScore(leg.getMode(), travelTime, distance, params);
	}

	/**
	 * Calculate the opportunity cost of travel time.
	 *
	 * In MATSim, the opportunity cost is performing_utils_hr, which represents the utility
	 * foregone by traveling instead of performing an activity. This is typically added
	 * to travel time disutility.
	 *
	 * @param travelTime travel time in seconds
	 * @param params     scoring parameters
	 * @return opportunity cost (negative value = disutility)
	 */
	public static double calculateOpportunityCost(double travelTime, ScoringParameters params) {
		// Convert performing utility from per-hour to per-second
		double performing_utils_s = params.marginalUtilityOfPerforming_s;
		return -travelTime * performing_utils_s;
	}

	/**
	 * Get the daily utility constant for a mode (for informational purposes).
	 *
	 * This is NOT included in trip scoring but may be useful for reporting or
	 * day-level analysis.
	 *
	 * @param mode   the transport mode
	 * @param params scoring parameters
	 * @return daily utility constant
	 */
	public static double getDailyUtilityConstant(String mode, ScoringParameters params) {
		ModeUtilityParameters modeParams = params.modeParams.get(mode);
		if (modeParams == null) {
			return 0.0;
		}
		return modeParams.dailyUtilityConstant;
	}

	/**
	 * Get the daily money constant for a mode (for informational purposes).
	 *
	 * This is NOT included in trip scoring but may be useful for reporting or
	 * day-level analysis.
	 *
	 * @param mode   the transport mode
	 * @param params scoring parameters
	 * @return daily money constant (in currency units, not utility)
	 */
	public static double getDailyMoneyConstant(String mode, ScoringParameters params) {
		ModeUtilityParameters modeParams = params.modeParams.get(mode);
		if (modeParams == null) {
			return 0.0;
		}
		return modeParams.dailyMoneyConstant;
	}
}
