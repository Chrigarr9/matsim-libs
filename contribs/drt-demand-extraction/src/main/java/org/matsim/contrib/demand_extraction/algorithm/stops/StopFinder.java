package org.matsim.contrib.demand_extraction.algorithm.stops;

import java.util.List;
import java.util.Optional;

import org.matsim.api.core.v01.Coord;
import org.matsim.contrib.demand_extraction.algorithm.domain.StopLocation;

/**
 * Interface for finding optimal stop locations for stop-based ride-pooling.
 *
 * Implementations of this interface find shared pickup or dropoff points
 * that satisfy walking distance constraints for all passengers while
 * minimizing total walking distance.
 *
 * Different strategies (GEOMETRIC, NETWORK_NODE, NETWORK_LINK, PREDEFINED)
 * are implemented by different classes.
 */
public interface StopFinder {

	/**
	 * Find an optimal stop location for the given passenger locations.
	 *
	 * @param passengerLocations The coordinates of passenger origins (for pickup)
	 *                          or destinations (for dropoff)
	 * @param maxWalkDistances Maximum acceptable walking distance for each passenger (meters).
	 *                         Array length must match passengerLocations.size()
	 * @param departureTime The departure time (for time-dependent routing if needed)
	 * @return Optional containing the optimal StopLocation, or empty if no valid stop exists
	 *         that satisfies all constraints
	 */
	Optional<StopLocation> findStop(
			List<Coord> passengerLocations,
			double[] maxWalkDistances,
			double departureTime);

	/**
	 * Returns the name/type of this stop finder for logging purposes.
	 */
	String getName();
}
