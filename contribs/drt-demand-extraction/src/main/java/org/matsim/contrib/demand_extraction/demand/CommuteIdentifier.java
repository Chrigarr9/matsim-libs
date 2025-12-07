package org.matsim.contrib.demand_extraction.demand;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.core.router.TripStructureUtils;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Identifies commute trips within a person's daily plan.
 *
 * A commute is defined as a home-to-work and work-to-home pair.
 * This class detects these pairs by analyzing activity types at trip origins
 * and destinations.
 *
 * Key behavior:
 * - Marks trips from home to work and from work back to home as commutes
 * - Intermediate trips during work (e.g., work->lunch->work) are NOT marked as commutes
 * - Uses the groupId (subtour ID) to link morning and evening commute legs
 *
 * Example:
 *   Trip 0: home -> work  (isCommute = true)
 *   Trip 1: work -> lunch (isCommute = false)
 *   Trip 2: lunch -> work (isCommute = false)
 *   Trip 3: work -> home  (isCommute = true)
 */
@Singleton
public class CommuteIdentifier {
	private static final Logger log = LogManager.getLogger(CommuteIdentifier.class);

	private final ExMasConfigGroup config;

	// Maps personId -> tripIndex -> isCommute
	private final Map<Id<Person>, Set<Integer>> commuteTrips = new HashMap<>();

	@Inject
	public CommuteIdentifier(ExMasConfigGroup config) {
		this.config = config;
	}

	/**
	 * Analyze population and identify all commute trips.
	 * Must be called before using isCommute().
	 */
	public void identifyCommutes(Population population) {
		log.info("Identifying commute trips (home={}, work={})...",
				config.getHomeActivityType(), config.getWorkActivityType());

		int totalPersons = 0;
		int personsWithCommutes = 0;
		int totalCommuteTrips = 0;

		for (Person person : population.getPersons().values()) {
			totalPersons++;
			Set<Integer> personCommuteTrips = identifyPersonCommutes(person);

			if (!personCommuteTrips.isEmpty()) {
				commuteTrips.put(person.getId(), personCommuteTrips);
				personsWithCommutes++;
				totalCommuteTrips += personCommuteTrips.size();
			}
		}

		log.info("Commute identification complete: {} commute trips for {} persons (of {} total)",
				totalCommuteTrips, personsWithCommutes, totalPersons);
	}

	/**
	 * Identify commute trips for a single person.
	 *
	 * Algorithm:
	 * 1. Find home->work trips
	 * 2. For each home->work, find matching work->home (may skip intermediate trips)
	 * 3. Mark both as commutes
	 */
	private Set<Integer> identifyPersonCommutes(Person person) {
		Set<Integer> commutes = new HashSet<>();

		if (person.getSelectedPlan() == null) {
			return commutes;
		}

		List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(person.getSelectedPlan());

		for (int i = 0; i < trips.size(); i++) {
			TripStructureUtils.Trip outbound = trips.get(i);
			String originType = getActivityTypePrefix(outbound.getOriginActivity().getType());
			String destType = getActivityTypePrefix(outbound.getDestinationActivity().getType());

			// Check if this is a home -> work trip
			if (isHomeActivity(originType) && isWorkActivity(destType)) {
				// Look for matching work -> home trip
				for (int j = i + 1; j < trips.size(); j++) {
					TripStructureUtils.Trip returnTrip = trips.get(j);
					String returnOriginType = getActivityTypePrefix(returnTrip.getOriginActivity().getType());
					String returnDestType = getActivityTypePrefix(returnTrip.getDestinationActivity().getType());

					if (isWorkActivity(returnOriginType) && isHomeActivity(returnDestType)) {
						// Found a commute pair
						commutes.add(i);
						commutes.add(j);
						break; // Stop looking for this outbound trip's return
					}
				}
			}
		}

		return commutes;
	}

	/**
	 * Check if a trip is a commute trip.
	 *
	 * @param personId the person ID
	 * @param tripIndex the trip index within the person's plan
	 * @return true if this trip is part of a home-work-home commute pattern
	 */
	public boolean isCommute(Id<Person> personId, int tripIndex) {
		Set<Integer> personCommutes = commuteTrips.get(personId);
		return personCommutes != null && personCommutes.contains(tripIndex);
	}

	/**
	 * Get the base activity type (before any suffix like "_7200").
	 * MATSim often adds duration suffixes to activity types.
	 */
	private String getActivityTypePrefix(String activityType) {
		if (activityType == null) {
			return "";
		}
		// Handle common MATSim patterns like "home_7200" or "work_28800"
		int underscoreIdx = activityType.indexOf('_');
		if (underscoreIdx > 0) {
			// Check if what follows is a number (duration suffix)
			String suffix = activityType.substring(underscoreIdx + 1);
			try {
				Double.parseDouble(suffix);
				return activityType.substring(0, underscoreIdx).toLowerCase();
			} catch (NumberFormatException e) {
				// Not a duration suffix, return as-is
			}
		}
		return activityType.toLowerCase();
	}

	private boolean isHomeActivity(String type) {
		return type.startsWith(config.getHomeActivityType().toLowerCase());
	}

	private boolean isWorkActivity(String type) {
		return type.startsWith(config.getWorkActivityType().toLowerCase());
	}
}
