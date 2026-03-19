package org.matsim.contrib.demand_extraction.scoring;

/**
 * Context from a preceding trip in a greedy-prefix tour evaluation.
 * Records the best non-DRT mode chosen for a preceding trip.
 *
 * @param tripIndex     index of the preceding trip in the person's plan
 * @param mode          the best non-DRT mode selected for this trip
 * @param departureTime departure time of this trip (seconds after midnight)
 * @param travelTime    travel time of this trip (seconds)
 */
public record PreviousTripContext(
		int tripIndex,
		String mode,
		double departureTime,
		double travelTime
) {}
