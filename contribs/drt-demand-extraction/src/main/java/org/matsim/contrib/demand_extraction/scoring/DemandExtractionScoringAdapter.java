package org.matsim.contrib.demand_extraction.scoring;

import org.matsim.api.core.v01.population.Person;

/**
 * Service Provider Interface for scoring trips in the demand extraction pipeline.
 *
 * <p>Adapters abstract over different MATSim scoring paradigms (standard planCalcScore,
 * DMC with MATSimTripScoring, eqasim, custom) so that budget calculation and constraint
 * conversion use the same utility logic as the configured mode choice model.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #scoreTrip} must return trip-level utility <b>excluding</b> daily constants.
 *       All built-in adapters guarantee this by construction.</li>
 *   <li>Opportunity cost inclusion is adapter-dependent and reported via
 *       {@link #includesOpportunityCost()}. The caller adds opportunity cost only when
 *       both {@code exMasConfig.getOpportunityCostModel()} is not NONE AND
 *       {@code adapter.includesOpportunityCost()} is false.</li>
 *   <li>{@link #getMarginalUtilityOfMoney} is the only explicit parameter adapters must
 *       expose. It is needed for maxCost conversion because DRT fare is external to
 *       the scoring system (applied by {@code DrtFareHandler} as {@code PersonMoneyEvent}).</li>
 * </ul>
 *
 * <h2>Implementations</h2>
 * <ul>
 *   <li>{@code PlanCalcScoreAdapter} — standard MATSim (planCalcScore with real params)</li>
 *   <li>{@code DmcMatSimTripAdapter} — DMC with MATSimTripScoring estimator</li>
 *   <li>{@code EqasimScoringAdapter} — eqasim with distance interaction</li>
 * </ul>
 */
public interface DemandExtractionScoringAdapter {

	/**
	 * Human-readable name for logging and config.
	 */
	String getName();

	/**
	 * Score a candidate trip. Used for budget calculation AND iterative constraint search.
	 *
	 * <p>The routed elements in the request contain legs with routes (travel time, distance).
	 * The adapter scores these elements using its native scoring logic.
	 *
	 * @param request the trip scoring request with person, mode, routed elements, and context
	 * @return the score result with utility and flags
	 */
	TripScoreResult scoreTrip(TripScoreRequest request);

	/**
	 * Marginal utility of 1 EUR of monetary cost (utils/EUR).
	 *
	 * <p>Only needed for maxCost conversion (DRT fare is external to scoring).
	 *
	 * <ul>
	 *   <li>Standard MATSim/DMC: reads {@code planCalcScore.marginalUtilityOfMoney}</li>
	 *   <li>eqasim: returns {@code |betaCost| * interaction(euclidDist)}</li>
	 *   <li>Custom: user provides via {@code ExMasConfigGroup.marginalUtilityOfMoney}</li>
	 * </ul>
	 *
	 * @param person              the person (for person-specific values via subpopulation)
	 * @param euclideanDistance_km trip euclidean distance in km (for eqasim's distance interaction;
	 *                            ignored by adapters without distance-specific money utility)
	 * @return marginal utility of money in utils/EUR (must be positive)
	 */
	double getMarginalUtilityOfMoney(Person person, double euclideanDistance_km);

	/**
	 * Does this adapter's trip utility already include activity opportunity cost?
	 *
	 * <p>In standard MATSim, {@code margUtilTraveling} is the PURE travel disutility.
	 * Opportunity cost (lost activity time = {@code margUtilPerforming}) is separate.
	 * The caller adds it if configured.
	 *
	 * <p>In eqasim, {@code betaTravelTime} is estimated from survey data and captures
	 * the TOTAL disutility of travel including implicit opportunity cost.
	 * The caller MUST NOT add opportunity cost on top.
	 *
	 * @return true if opportunity cost is already included in {@link #scoreTrip} utility
	 */
	boolean includesOpportunityCost();

	/**
	 * Daily monetary constant for a mode, converted to utils.
	 *
	 * <p>Returns {@code dailyMoneyConstant * marginalUtilityOfMoney + dailyUtilityConstant}
	 * for the given mode.
	 * The caller amortizes this over the person's total daily trip distance to produce
	 * a per-trip adjustment: {@code dailyConstantUtils * (tripDistance / totalDailyDistance)}.
	 *
	 * <p>Default returns 0 (no daily constant). Override in adapters that have access
	 * to mode-specific daily monetary constants.
	 *
	 * @param person the person (for subpopulation-specific scoring parameters)
	 * @param mode   the transport mode
	 * @return daily monetary constant in utils (typically negative for costs)
	 */
	default double getDailyMonetaryConstantUtils(Person person, String mode) {
		return 0.0;
	}

	/**
	 * Does {@link #getMarginalUtilityOfMoney} vary by trip distance?
	 *
	 * <p>eqasim: yes ({@code betaCost * (euclidDist/refDist)^lambda}).
	 * Standard MATSim: no (flat scalar from planCalcScore).
	 *
	 * @return true if the euclideanDistance_km parameter affects the result
	 */
	boolean supportsDistanceSpecificMoneyUtility();
}
