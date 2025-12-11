package org.matsim.contrib.demand_extraction.demand;

import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Encapsulates the logic for calculating temporal flexibility for DRT requests.
 * 
 * Flexibility determines the time window for departure and arrival:
 * - Negative Flexibility (Origin): How much earlier can the passenger depart?
 * - Positive Flexibility (Destination): How much later can the passenger arrive?
 * 
 * Calculations can be based on attributes of the Person or the Activity (e.g., activity type).
 */
@Singleton
public class FlexibilityCalculator {
	private static final Logger log = LogManager.getLogger(FlexibilityCalculator.class);

	private final ExMasConfigGroup config;
	
	// Parsed maps
	private final Map<String, Double> posAbsMap;
	private final Map<String, Double> posRelMap;
	private final Map<String, Double> negAbsMap;
	private final Map<String, Double> negRelMap;

	@Inject
	public FlexibilityCalculator(ExMasConfigGroup config) {
		this.config = config;
		this.posAbsMap = parseFlexibilityMap(config.getPositiveFlexibilityAbsoluteMap());
		this.posRelMap = parseFlexibilityMap(config.getPositiveFlexibilityRelativeMap());
		this.negAbsMap = parseFlexibilityMap(config.getNegativeFlexibilityAbsoluteMap());
		this.negRelMap = parseFlexibilityMap(config.getNegativeFlexibilityRelativeMap());
	}

	/**
	 * Calculates the negative flexibility (early departure allowance) for a trip.
	 * Relevant context is the Origin Activity.
	 */
	public double calculateOriginFlexibility(Person person, Activity originActivity, double maxDetour) {
		return calculate(person, originActivity, 
				config.getNegativeFlexibilityAttribute(),
				negAbsMap,
				negRelMap,
				config.getOriginFlexibilityAbsolute(),
				config.getOriginFlexibilityRelative(),
				maxDetour);
	}

	/**
	 * Calculates the positive flexibility (late arrival allowance) for a trip.
	 * Relevant context is the Destination Activity.
	 */
	public double calculateDestinationFlexibility(Person person, Activity destinationActivity, double maxDetour) {
		return calculate(person, destinationActivity, 
				config.getPositiveFlexibilityAttribute(),
				posAbsMap,
				posRelMap,
				config.getDestinationFlexibilityAbsolute(),
				config.getDestinationFlexibilityRelative(),
				maxDetour);
	}

	private double calculate(Person person, Activity activity, String attribute, 
			Map<String, Double> absMap, Map<String, Double> relMap, 
			double defaultAbs, double defaultRel, double maxDetour) {
		
		if (attribute == null) {
			// Fallback to simple scalar config if no attribute specified
			return defaultAbs + (defaultRel * maxDetour);
		}

		String attrVal = "default";
		
		// Determine attribute value
		if ("activityType".equalsIgnoreCase(attribute)) {
			if (activity != null) {
				attrVal = activity.getType().toLowerCase();
			}
		} else {
			// Check person attributes
			Object val = person.getAttributes().getAttribute(attribute);
			if (val != null) {
				attrVal = val.toString().toLowerCase();
			}
		}

		double abs = absMap.getOrDefault(attrVal, absMap.getOrDefault("default", defaultAbs));
		double relFactor = relMap.getOrDefault(attrVal, relMap.getOrDefault("default", defaultRel));

		return abs + (relFactor * maxDetour);
	}

	private Map<String, Double> parseFlexibilityMap(String mapString) {
		if (mapString == null || mapString.isEmpty()) {
			return Map.of();
		}
		Map<String, Double> map = new HashMap<>();
		for (String entry : mapString.split(",")) {
			String[] parts = entry.split(":");
			if (parts.length == 2) {
				try {
					map.put(parts[0].trim().toLowerCase(), Double.parseDouble(parts[1].trim()));
				} catch (NumberFormatException e) {
					log.warn("Invalid number format in flexibility map: {}", entry);
				}
			}
		}
		return map;
	}
}
