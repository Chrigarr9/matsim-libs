package org.matsim.contrib.demand_extraction.algorithm.bamas.extension;

/**
 * Policy deciding how far a candidate extension ride may be routed before the distance
 * gate rejects it.
 *
 * <p>The gate derives a maximum allowed ride distance from the sum of per-passenger direct
 * distances and the pooling degree. The threshold shape is implementation-defined (linear
 * slope/intercept or log-scale). The threshold is the primary operation because the live
 * BAMAS extension loop consumes it as a <em>ratcheting ceiling</em>: it seeds
 * {@code bestValidDist[0] = maxAllowedRideDistance(...)} and keeps a ride iff
 * {@code dist < bestValidDist[0]} (strict {@code <}), tightening the ceiling to the best
 * valid distance found so far. A boolean accept/reject cannot express that tightening, so
 * call sites compare against the threshold directly and own their boundary direction.
 */
public interface ExtensionGate {

	/**
	 * Returns the maximum routed ride distance admitted at this degree, in metres, or
	 * {@link Double#MAX_VALUE} when the gate is disabled (or {@code sumDirectDistances <= 0}).
	 *
	 * @param degree              number of passengers in the candidate pool (≥ 2).
	 * @param sumDirectDistances  sum of each passenger's direct (solo) trip distance, in
	 *                            metres. Corresponds to summing {@code DrtRequest.directDistance}
	 *                            over the request set.
	 * @return the distance ceiling for a pool at this degree.
	 */
	double maxAllowedRideDistance(int degree, double sumDirectDistances);

	/**
	 * Convenience boundary check: admits the candidate iff its routed distance is within the
	 * threshold (inclusive {@code <=}). Used by the {@code passesDistanceSavingsPruning}
	 * filter; the live extension loop instead seeds {@link #maxAllowedRideDistance} and uses
	 * a strict {@code <} (see the type-level note on the ratcheting ceiling).
	 *
	 * @param candidateRideDistance  routed end-to-end distance of the candidate pooled ride,
	 *                               in metres ({@code Ride.getRideDistance()}).
	 * @param sumDirectDistances     sum of per-passenger direct distances, in metres.
	 * @param degree                 number of passengers in the candidate pool (≥ 2).
	 * @return {@code true} iff {@code candidateRideDistance <= maxAllowedRideDistance(degree, sumDirectDistances)}.
	 */
	default boolean admit(double candidateRideDistance, double sumDirectDistances, int degree) {
		return candidateRideDistance <= maxAllowedRideDistance(degree, sumDirectDistances);
	}
}
