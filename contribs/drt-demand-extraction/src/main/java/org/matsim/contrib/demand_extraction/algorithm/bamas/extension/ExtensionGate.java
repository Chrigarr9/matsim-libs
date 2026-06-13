package org.matsim.contrib.demand_extraction.algorithm.bamas.extension;

/**
 * Policy deciding whether a candidate extension ride is admitted by the distance gate.
 *
 * <p>The gate compares the candidate ride's own routed distance against a threshold
 * derived from the sum of per-passenger direct distances and the pooling degree. The
 * threshold shape is implementation-defined (linear slope/intercept or log-scale).
 */
public interface ExtensionGate {

    /**
     * Returns {@code true} if the candidate ride is admitted (kept), {@code false} if
     * gated out (rejected).
     *
     * @param candidateRideDistance  routed end-to-end distance of the candidate pooled
     *                               ride, in metres. Corresponds to
     *                               {@code Ride.getRideDistance()}.
     * @param sumDirectDistances     sum of each passenger's direct (solo) trip distance,
     *                               in metres. Corresponds to summing
     *                               {@code DrtRequest.directDistance} over the request set
     *                               (or {@code DrtRequest.getDistance()} in the reference
     *                               extender's {@code passesDistanceSavingsPruning} path).
     * @param degree                 number of passengers in the candidate pool (≥ 2).
     * @return {@code true} iff the candidate is within the allowed distance threshold.
     */
    boolean admit(double candidateRideDistance, double sumDirectDistances, int degree);
}
