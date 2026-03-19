package org.matsim.contrib.demand_extraction.demand;

/**
 * Immutable attributes for a routed mode alternative.
 *
 * <p>The {@code score} field is the trip-level utility from the scoring adapter,
 * excluding daily constants. For budget calculation, only {@code score} is used
 * to compare modes. Travel time and distance are retained for constraint
 * calculation and analytics.
 */
public record ModeAttributes(double travelTime, double distance, double score) {
}
