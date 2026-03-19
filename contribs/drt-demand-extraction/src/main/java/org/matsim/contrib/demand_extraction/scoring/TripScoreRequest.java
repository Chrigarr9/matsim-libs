package org.matsim.contrib.demand_extraction.scoring;

import java.util.List;

import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.utils.objectattributes.attributable.Attributes;

/**
 * Request DTO for adapter trip scoring.
 *
 * @param person            the person whose trip is being scored
 * @param candidateMode     the transport mode to score (e.g., "car", "pt", "drt")
 * @param routedElements    routed plan elements (legs + intermediate activities)
 * @param originActivity    the origin activity of the trip
 * @param destinationActivity the destination activity of the trip
 * @param departureTime     departure time in seconds after midnight
 * @param tripAttributes    MATSim trip attributes (may be empty)
 * @param tripIndex         index of this trip in the person's plan
 * @param previousTrips     context from preceding trips (empty for TRIP_INDEPENDENT,
 *                          populated for GREEDY_PREFIX)
 */
public record TripScoreRequest(
		Person person,
		String candidateMode,
		List<? extends PlanElement> routedElements,
		Activity originActivity,
		Activity destinationActivity,
		double departureTime,
		Attributes tripAttributes,
		int tripIndex,
		List<PreviousTripContext> previousTrips
) {

	/**
	 * Convenience constructor for trip-independent scoring (no tour context).
	 */
	public TripScoreRequest(Person person, String candidateMode,
			List<? extends PlanElement> routedElements,
			Activity originActivity, Activity destinationActivity,
			double departureTime, Attributes tripAttributes, int tripIndex) {
		this(person, candidateMode, routedElements, originActivity, destinationActivity,
				departureTime, tripAttributes, tripIndex, List.of());
	}
}
