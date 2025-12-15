package org.matsim.contrib.demand_extraction.io;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;
import org.matsim.core.utils.io.IOUtils;

/**
 * Utility class for writing person attributes to a separate CSV file.
 * 
 * This creates a lookup table linking personId to all person attributes,
 * which can be joined with requests in Python for cluster analysis.
 * Only persons who have at least one DRT request are exported.
 * 
 * Output format: personId,attribute1,attribute2,...
 * Values are escaped if they contain commas or quotes.
 */
public final class PersonAttributesWriter {

	private PersonAttributesWriter() {
		// Utility class - prevent instantiation
	}

	/**
	 * Write person attributes to CSV file for all persons with DRT requests.
	 * 
	 * Only persons appearing in the requests list are exported to save space.
	 * All attributes from each person are written as columns.
	 * Complex MATSim objects that don't serialize well are skipped.
	 * 
	 * @param filename   output file path
	 * @param population MATSim population containing person attributes
	 * @param requests   list of DRT requests (used to filter which persons to export)
	 * @throws RuntimeException if writing fails
	 */
	public static void writePersonAttributes(String filename, Population population, List<DrtRequest> requests) {
		// Collect unique person IDs from requests
		Set<Id<Person>> personIdsInRequests = requests.stream()
				.map(r -> r.personId)
				.collect(Collectors.toSet());

		// Collect all unique attribute names across all persons, filtering out complex objects
		Set<String> allAttributeNames = new TreeSet<>(); // TreeSet for consistent ordering
		for (Id<Person> personId : personIdsInRequests) {
			Person person = population.getPersons().get(personId);
			if (person != null) {
				for (Map.Entry<String, Object> entry : person.getAttributes().getAsMap().entrySet()) {
					if (isSerializableValue(entry.getValue())) {
						allAttributeNames.add(entry.getKey());
					}
				}
			}
		}

		// Convert to ordered list for consistent column ordering
		List<String> attributeColumns = new ArrayList<>(allAttributeNames);

		try (BufferedWriter writer = IOUtils.getBufferedWriter(filename)) {
			// Write header
			writer.write("personId");
			for (String attr : attributeColumns) {
				writer.write(",");
				writer.write(escapeCSV(attr));
			}
			writer.newLine();

			// Write rows for each person with requests
			for (Id<Person> personId : personIdsInRequests) {
				Person person = population.getPersons().get(personId);
				if (person == null) {
					continue;
				}

				writer.write(escapeCSV(personId.toString()));

				Map<String, Object> attrs = person.getAttributes().getAsMap();
				for (String attrName : attributeColumns) {
					writer.write(",");
					Object value = attrs.get(attrName);
					writer.write(formatValue(value));
				}
				writer.newLine();
			}
		} catch (IOException e) {
			throw new RuntimeException("Could not write person attributes CSV: " + filename, e);
		}
	}

	/**
	 * Check if a value can be serialized meaningfully to CSV.
	 * Returns false for complex MATSim objects that don't have sensible string representation.
	 */
	private static boolean isSerializableValue(Object value) {
		if (value == null) {
			return true; // Null can be serialized as empty
		}
		
		String className = value.getClass().getName();
		
		// Skip complex MATSim internal objects (e.g., PersonVehicles, Routes)
		if (className.startsWith("org.matsim.") && 
			!className.startsWith("org.matsim.api.core.v01.Id")) {
			return false;
		}
		
		// Allow primitives, wrappers, strings, collections, maps
		return true;
	}

	/**
	 * Format a value for CSV output.
	 * Handles null, collections, arrays, maps, and primitive types.
	 * Skips complex MATSim objects that don't serialize meaningfully.
	 */
	private static String formatValue(Object value) {
		if (value == null) {
			return "";
		}

		// Skip MATSim objects that don't serialize well (e.g., PersonVehicles)
		String className = value.getClass().getName();
		if (className.startsWith("org.matsim.") && 
			!className.startsWith("org.matsim.api.core.v01.Id")) {
			// Skip complex MATSim internal objects
			return "";
		}

		// Handle collections
		if (value instanceof Collection<?>) {
			Collection<?> coll = (Collection<?>) value;
			String joined = coll.stream()
					.map(v -> v != null ? v.toString() : "")
					.collect(Collectors.joining(" | "));
			return escapeCSV("[" + joined + "]");
		}

		// Handle arrays
		if (value.getClass().isArray()) {
			if (value instanceof int[]) {
				return escapeCSV(Arrays.toString((int[]) value));
			} else if (value instanceof double[]) {
				return escapeCSV(Arrays.toString((double[]) value));
			} else if (value instanceof boolean[]) {
				return escapeCSV(Arrays.toString((boolean[]) value));
			} else if (value instanceof Object[]) {
				return escapeCSV(Arrays.toString((Object[]) value));
			}
		}

		// Handle maps (e.g., mode constants)
		if (value instanceof Map<?, ?>) {
			Map<?, ?> map = (Map<?, ?>) value;
			String mapStr = map.entrySet().stream()
					.map(e -> e.getKey() + "=" + e.getValue())
					.collect(Collectors.joining(";"));
			return escapeCSV("{" + mapStr + "}");
		}

		// Handle numbers with proper formatting
		if (value instanceof Double) {
			return String.format(Locale.US, "%.6f", (Double) value);
		} else if (value instanceof Float) {
			return String.format(Locale.US, "%.6f", (Float) value);
		}

		// Default: convert to string and escape
		return escapeCSV(value.toString());
	}

	/**
	 * Escape a value for CSV format.
	 * Wraps in quotes if contains comma, quote, or newline.
	 * Doubles any internal quotes.
	 */
	private static String escapeCSV(String value) {
		if (value == null) {
			return "";
		}

		boolean needsQuoting = value.contains(",") || value.contains("\"") ||
				value.contains("\n") || value.contains("\r");

		if (needsQuoting) {
			// Double any quotes and wrap in quotes
			return "\"" + value.replace("\"", "\"\"") + "\"";
		}
		return value;
	}
}
