package org.matsim.contrib.demand_extraction.algorithm.generation;

import org.matsim.contrib.demand_extraction.demand.DrtRequest;

/**
 * Functional interface that maps a pooled-ride context to the maximum
 * symmetric half-walk distance (in metres) that keeps the total DRT trip
 * score at or above the passenger's baseline mode score.
 *
 * <p>The return value {@code mid} is the maximum value such that a DRT trip
 * with {@code accessWalk = egress Walk = mid} is still budget-feasible. The
 * total walk envelope is therefore {@code 2·mid}, which the asymmetric
 * two-phase search in {@link StopBasedRideGenerator} may allocate
 * asymmetrically between access and egress.
 *
 * <p>In production this is wired to
 * {@link org.matsim.contrib.demand_extraction.demand.BudgetToConstraintsCalculator#budgetToMaxWalkDistance(double, org.matsim.api.core.v01.population.Person, DrtRequest, double, double, double)}.
 * In tests a fixed or scenario-specific stub can be substituted.
 */
@FunctionalInterface
public interface WalkBudgetProvider {

	/**
	 * @param remainingBudget  remaining utility budget for this passenger after D2D pooling
	 * @param request          the passenger's DRT request (used for scoring params)
	 * @param actualTravelTime actual D2D pooled travel time (seconds)
	 * @param actualDistance   actual D2D pooled distance (metres)
	 * @param delay            D2D pooled delay (seconds)
	 * @return mid-walk distance in metres (half the total walk envelope)
	 */
	double getMid(double remainingBudget, DrtRequest request,
			double actualTravelTime, double actualDistance, double delay);
}
