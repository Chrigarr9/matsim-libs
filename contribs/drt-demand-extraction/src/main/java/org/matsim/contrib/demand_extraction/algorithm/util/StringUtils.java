package org.matsim.contrib.demand_extraction.algorithm.util;

/**
 * Utility class for common string operations used in demand extraction.
 */
public final class StringUtils {

	private StringUtils() {
		// Utility class - no instantiation
	}

	/**
	 * Capitalizes the first character of a string.
	 *
	 * @param str the string to capitalize
	 * @return the string with first character capitalized, or original if null/empty
	 */
	public static String capitalize(String str) {
		if (str == null || str.isEmpty()) {
			return str;
		}
		return str.substring(0, 1).toUpperCase() + str.substring(1);
	}
}
