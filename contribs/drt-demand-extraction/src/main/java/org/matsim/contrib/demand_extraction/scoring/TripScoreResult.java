package org.matsim.contrib.demand_extraction.scoring;

/**
 * Result DTO from adapter trip scoring.
 *
 * <p>{@code utility} is the adapter's trip utility. The adapter is responsible for
 * documenting what is included via flags:
 * <ul>
 *   <li>Daily constants: adapters SHOULD exclude them (trip-level extraction, not day-level).
 *       All built-in adapters exclude them by construction.</li>
 *   <li>Opportunity cost: adapter-dependent, reported via
 *       {@link DemandExtractionScoringAdapter#includesOpportunityCost()}.</li>
 *   <li>Waiting disutility: adapter-dependent, reported via
 *       {@link TripScoreResult#waitingDisutilityIncluded()}.</li>
 * </ul>
 *
 * @param utility                   pure trip utility (no daily constants; opportunity cost
 *                                  inclusion depends on adapter)
 * @param waitingDisutilityIncluded whether the adapter already scored waiting time
 * @param sourceDescription         human-readable description of how the score was computed
 */
public record TripScoreResult(
		double utility,
		boolean waitingDisutilityIncluded,
		String sourceDescription
) {

	/**
	 * Convenience constructor for adapters that do not score waiting time.
	 */
	public TripScoreResult(double utility, String sourceDescription) {
		this(utility, false, sourceDescription);
	}
}
