package org.matsim.contrib.demand_extraction.algorithm.network;

import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.vehicles.Vehicle;

/**
 * Routing disutility: {@code cost = timeCoef * travelTime(link) + distCoef * length(link)}.
 *
 * <p>Decouples routing from eqasim scoring: scoring in this project uses MNL predictors
 * (BudgetToConstraintsCalculator), not MATSim's TravelDisutility, so changing routing
 * coefficients here has no effect on calibration or budget validation.
 *
 * <p>Why both terms must be strictly positive:
 * <ul>
 *   <li>Non-zero {@code distCoef} makes shortest-cost paths unique. Pure time-only routing
 *       leaves many OD pairs with multiple equal-time paths of different distances, which
 *       Dijkstra (LeastCostPathTree) and A* (SpeedyALT) tie-break differently.</li>
 *   <li>Non-zero gradient keeps the SpeedyALT landmark heuristic informative — zero gradient
 *       degenerates A* to exhaustive Dijkstra (the original 70x slowdown).</li>
 * </ul>
 *
 * <p>The minimum-disutility method (used by ALT for the lower-bound heuristic) assumes the
 * underlying TravelTime is bounded below by free-speed time. This holds for FreeSpeedTravelTime
 * and for any congestion-aware TravelTime that returns at least free-speed time.
 */
public final class TimeDistanceTravelDisutility implements TravelDisutility {

	private final TravelTime travelTime;
	private final double timeCoef;
	private final double distCoef;

	public TimeDistanceTravelDisutility(TravelTime travelTime, double timeCoef, double distCoef) {
		if (timeCoef < 0.0) {
			throw new IllegalArgumentException("timeCoef must be >= 0, got " + timeCoef);
		}
		if (distCoef < 0.0) {
			throw new IllegalArgumentException("distCoef must be >= 0, got " + distCoef);
		}
		this.travelTime = travelTime;
		this.timeCoef = timeCoef;
		this.distCoef = distCoef;
	}

	@Override
	public double getLinkTravelDisutility(Link link, double time, Person person, Vehicle vehicle) {
		double tt = this.travelTime.getLinkTravelTime(link, time, person, vehicle);
		return this.timeCoef * tt + this.distCoef * link.getLength();
	}

	@Override
	public double getLinkMinimumTravelDisutility(Link link) {
		double minTime = link.getLength() / link.getFreespeed();
		return this.timeCoef * minTime + this.distCoef * link.getLength();
	}
}
