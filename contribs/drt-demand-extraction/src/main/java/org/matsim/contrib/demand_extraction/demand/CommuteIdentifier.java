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
	// Maps personId -> tripIndex -> isEducation
	private final Map<Id<Person>, Set<Integer>> educationTrips = new HashMap<>();

	@Inject
	public CommuteIdentifier(ExMasConfigGroup config) {
		this.config = config;
	}

	/**
	 * Analyze population and identify all commute and education trips.
	 * Must be called before using isCommute() or isEducation().
	 */
	public void identifyCommutes(Population population) {
		log.info("Identifying mandatory trips (home={}, work={}, education={})...",
				config.getHomeActivityType(), config.getWorkActivityType(), config.getEducationActivityType());

		int totalPersons = 0;
		int personsWithCommutes = 0;
		int personsWithEducation = 0;
		int totalCommuteTrips = 0;
		int totalEducationTrips = 0;

		for (Person person : population.getPersons().values()) {
			totalPersons++;
			
			// Identify work commutes
			Set<Integer> personCommuteTrips = identifyPersonTrips(person, config.getWorkActivityType());
			if (!personCommuteTrips.isEmpty()) {
				commuteTrips.put(person.getId(), personCommuteTrips);
				personsWithCommutes++;
				totalCommuteTrips += personCommuteTrips.size();
			}

			// Identify education trips
			Set<Integer> personEducationTrips = identifyPersonTrips(person, config.getEducationActivityType());
			if (!personEducationTrips.isEmpty()) {
				educationTrips.put(person.getId(), personEducationTrips);
				personsWithEducation++;
				totalEducationTrips += personEducationTrips.size();
			}
		}

		log.info("Mandatory trip identification complete:");
		log.info("  - Commutes: {} trips for {} persons", totalCommuteTrips, personsWithCommutes);
		log.info("  - Education: {} trips for {} persons", totalEducationTrips, personsWithEducation);
		log.info("  - Total persons scanned: {}", totalPersons);
	}

	/**
	 * Identify trips for a single person matching home -> activity -> home pattern.
	 */
	private Set<Integer> identifyPersonTrips(Person person, String targetActivityType) {
		Set<Integer> tripsOfInterest = new HashSet<>();

		if (person.getSelectedPlan() == null) {
			return tripsOfInterest;
		}

		List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(person.getSelectedPlan());

		for (int i = 0; i < trips.size(); i++) {
			TripStructureUtils.Trip outbound = trips.get(i);
			String originType = getActivityTypePrefix(outbound.getOriginActivity().getType());
			String destType = getActivityTypePrefix(outbound.getDestinationActivity().getType());

			// Check for home -> target
			if (isHomeActivity(originType) && isTargetActivity(destType, targetActivityType)) {
				// Found outbound trip. Now look for return trip.
				for (int j = i + 1; j < trips.size(); j++) {
					TripStructureUtils.Trip inbound = trips.get(j);
					String inOrigin = getActivityTypePrefix(inbound.getOriginActivity().getType());
					String inDest = getActivityTypePrefix(inbound.getDestinationActivity().getType());

					if (isTargetActivity(inOrigin, targetActivityType) && isHomeActivity(inDest)) {
						// Found return trip. Mark both.
						tripsOfInterest.add(i);
						tripsOfInterest.add(j);
						break; 
					}
				}
			}
		}

		return tripsOfInterest;
	}

	public boolean isCommute(Id<Person> personId, int tripIndex) {
		return commuteTrips.containsKey(personId) && commuteTrips.get(personId).contains(tripIndex);
	}

	public boolean isEducation(Id<Person> personId, int tripIndex) {
		return educationTrips.containsKey(personId) && educationTrips.get(personId).contains(tripIndex);
	}

	private boolean isHomeActivity(String type) {
		return type.startsWith(config.getHomeActivityType().toLowerCase());
	}

	private boolean isTargetActivity(String type, String targetType) {
		return type.startsWith(targetType.toLowerCase());
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
}
