package org.matsim.contrib.demand_extraction.algorithm.hyperpool;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.demand_extraction.algorithm.domain.StopLocation;
import org.matsim.contrib.demand_extraction.algorithm.stops.LinkCandidateFinder;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.core.utils.geometry.CoordUtils;

/**
 * Finds optimal shared stops when bundling multiple stop-to-stop rides into hyper-pooled rides.
 *
 * <p>In HyperPool Stage 2, multiple stop-to-stop rides may have nearby but not identical
 * pickup or dropoff stops. This class finds an optimal shared stop location that:
 * <ul>
 *   <li>Minimizes total additional walk distance for all affected passengers</li>
 *   <li>Respects the maximum relocation constraint (hyperPoolMaxStopRelocationMeters)</li>
 *   <li>Is snapped to a valid network link</li>
 * </ul>
 *
 * <p>The algorithm uses a weighted centroid approach:
 * <ol>
 *   <li>Calculate the weighted centroid of input stops (weight = passenger count)</li>
 *   <li>Find candidate links near the centroid</li>
 *   <li>Select the link that minimizes total relocation distance</li>
 *   <li>Validate that all passengers can reach the new stop within constraints</li>
 * </ol>
 *
 * <p>Example usage:
 * <pre>{@code
 * StopRelocator relocator = new StopRelocator(network, linkCandidateFinder, config);
 *
 * List<StopLocation> nearbyStops = List.of(stop1, stop2, stop3);
 * double[] passengerCounts = {2, 1, 3}; // passengers using each stop
 *
 * Optional<StopLocation> sharedStop = relocator.findSharedStop(
 *     nearbyStops, passengerCounts, config.getHyperPoolMaxStopRelocationMeters());
 *
 * if (sharedStop.isPresent()) {
 *     // Use the shared stop for all rides
 *     double[] relocationDistances = relocator.calculateRelocationDistances(nearbyStops, sharedStop.get());
 * }
 * }</pre>
 *
 * @see StopLocation
 * @see LinkCandidateFinder
 */
public final class StopRelocator {

    private static final Logger log = LogManager.getLogger(StopRelocator.class);

    private final Network network;
    private final LinkCandidateFinder linkCandidateFinder;
    private final double maxRelocationMeters;

    // Statistics tracking
    private final AtomicInteger totalAttempts = new AtomicInteger();
    private final AtomicInteger successfulRelocations = new AtomicInteger();
    private final AtomicInteger failedNoCandidate = new AtomicInteger();
    private final AtomicInteger failedConstraintViolation = new AtomicInteger();
    private final AtomicLong totalRelocationDistance = new AtomicLong();
    private final AtomicInteger totalRelocatedStops = new AtomicInteger();

    /**
     * Creates a new StopRelocator.
     *
     * @param network the MATSim network for snapping stops to links
     * @param linkCandidateFinder finder for candidate links near the centroid
     * @param config ExMAS configuration containing hyperPoolMaxStopRelocationMeters
     */
    public StopRelocator(Network network, LinkCandidateFinder linkCandidateFinder, ExMasConfigGroup config) {
        if (network == null) {
            throw new IllegalArgumentException("Network cannot be null");
        }
        if (linkCandidateFinder == null) {
            throw new IllegalArgumentException("LinkCandidateFinder cannot be null");
        }
        if (config == null) {
            throw new IllegalArgumentException("ExMasConfigGroup cannot be null");
        }

        this.network = network;
        this.linkCandidateFinder = linkCandidateFinder;
        this.maxRelocationMeters = config.getHyperPoolMaxStopRelocationMeters();
    }

    /**
     * Find an optimal shared stop location for a set of nearby stops.
     *
     * <p>Uses the default max relocation from config (hyperPoolMaxStopRelocationMeters).
     *
     * @param stops list of nearby stops to merge (must not be empty)
     * @param passengerCounts number of passengers using each stop (parallel array)
     * @return shared stop location, or empty if no valid location exists
     * @throws IllegalArgumentException if stops is empty or arrays have mismatched lengths
     */
    public Optional<StopLocation> findSharedStop(List<StopLocation> stops, double[] passengerCounts) {
        return findSharedStop(stops, passengerCounts, maxRelocationMeters);
    }

    /**
     * Find an optimal shared stop location for a set of nearby stops.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Calculate weighted centroid of stops (weight = passenger count)</li>
     *   <li>Find candidate links within maxRelocationMeters of centroid</li>
     *   <li>For each candidate, check if all stops are within maxRelocationMeters</li>
     *   <li>Select the candidate that minimizes total weighted relocation distance</li>
     * </ol>
     *
     * @param stops list of nearby stops to merge (must not be empty)
     * @param passengerCounts number of passengers using each stop (parallel array)
     * @param maxRelocationMeters maximum allowed relocation distance per stop
     * @return shared stop location, or empty if no valid location exists
     * @throws IllegalArgumentException if stops is empty or arrays have mismatched lengths
     */
    public Optional<StopLocation> findSharedStop(
            List<StopLocation> stops,
            double[] passengerCounts,
            double maxRelocationMeters) {

        totalAttempts.incrementAndGet();

        // Validate inputs
        if (stops == null || stops.isEmpty()) {
            throw new IllegalArgumentException("Stops list cannot be null or empty");
        }
        if (passengerCounts == null || passengerCounts.length != stops.size()) {
            throw new IllegalArgumentException(
                    "passengerCounts must have same length as stops list");
        }

        // Single stop case - return as-is
        if (stops.size() == 1) {
            successfulRelocations.incrementAndGet();
            return Optional.of(stops.get(0));
        }

        // Step 1: Calculate weighted centroid
        Coord centroid = calculateWeightedCentroid(stops, passengerCounts);

        // Step 2: Find candidate links near centroid
        Collection<Link> candidates = linkCandidateFinder.findCandidateLinks(
                centroid, maxRelocationMeters);

        if (candidates.isEmpty()) {
            failedNoCandidate.incrementAndGet();
            log.debug("No candidate links found within {}m of centroid for {} stops",
                    maxRelocationMeters, stops.size());
            return Optional.empty();
        }

        // Step 3: Find best link that satisfies all constraints
        Link bestLink = null;
        double bestTotalWeightedDistance = Double.MAX_VALUE;
        Coord bestStopCoord = null;

        for (Link candidate : candidates) {
            // Calculate stop coordinate on this link (closest point to centroid)
            Coord stopCoord = getClosestPointOnLink(centroid, candidate);

            // Check if all original stops can relocate within constraint
            boolean allValid = true;
            double totalWeightedDistance = 0;

            for (int i = 0; i < stops.size(); i++) {
                double relocationDistance = CoordUtils.calcEuclideanDistance(
                        stops.get(i).getCoord(), stopCoord);

                if (relocationDistance > maxRelocationMeters) {
                    allValid = false;
                    break;
                }

                // Weight by passenger count
                totalWeightedDistance += relocationDistance * passengerCounts[i];
            }

            if (allValid && totalWeightedDistance < bestTotalWeightedDistance) {
                bestTotalWeightedDistance = totalWeightedDistance;
                bestLink = candidate;
                bestStopCoord = stopCoord;
            }
        }

        if (bestLink == null) {
            failedConstraintViolation.incrementAndGet();
            log.debug("No candidate link satisfies relocation constraints for {} stops",
                    stops.size());
            return Optional.empty();
        }

        // Step 4: Create shared stop location
        double snappingPenalty = CoordUtils.calcEuclideanDistance(centroid, bestStopCoord);
        StopLocation sharedStop = new StopLocation(bestLink.getId(), bestStopCoord, snappingPenalty);

        // Update statistics
        successfulRelocations.incrementAndGet();
        totalRelocatedStops.addAndGet(stops.size());
        totalRelocationDistance.addAndGet((long) (bestTotalWeightedDistance * 100)); // Store as cm

        log.trace("Found shared stop at {} for {} stops, total weighted relocation: {}m",
                bestLink.getId(), stops.size(), String.format("%.1f", bestTotalWeightedDistance));

        return Optional.of(sharedStop);
    }

    /**
     * Calculate the weighted centroid of input stops.
     *
     * <p>Each stop is weighted by the number of passengers using it, giving more
     * influence to heavily-used stops in determining the shared location.
     *
     * @param stops list of stop locations
     * @param passengerCounts number of passengers at each stop
     * @return weighted centroid coordinate
     */
    private Coord calculateWeightedCentroid(List<StopLocation> stops, double[] passengerCounts) {
        double sumX = 0, sumY = 0, sumWeights = 0;

        for (int i = 0; i < stops.size(); i++) {
            double weight = Math.max(passengerCounts[i], 1.0); // Minimum weight of 1
            Coord coord = stops.get(i).getCoord();

            sumX += coord.getX() * weight;
            sumY += coord.getY() * weight;
            sumWeights += weight;
        }

        return new Coord(sumX / sumWeights, sumY / sumWeights);
    }

    /**
     * Get the closest point on a link to a given coordinate.
     *
     * @param point the reference point
     * @param link the link to project onto
     * @return closest point on the link to the given coordinate
     */
    private Coord getClosestPointOnLink(Coord point, Link link) {
        return CoordUtils.orthogonalProjectionOnLineSegment(
                link.getFromNode().getCoord(),
                link.getToNode().getCoord(),
                point);
    }

    /**
     * Validate that all stops can be relocated to the shared stop within constraints.
     *
     * @param originalStops the original stop locations
     * @param sharedStop the proposed shared stop location
     * @param passengerCounts number of passengers at each original stop
     * @param maxRelocationMeters maximum allowed relocation distance
     * @return true if all relocation constraints are satisfied
     */
    public boolean validateRelocationConstraints(
            List<StopLocation> originalStops,
            StopLocation sharedStop,
            double[] passengerCounts,
            double maxRelocationMeters) {

        if (originalStops == null || originalStops.isEmpty()) {
            return false;
        }
        if (sharedStop == null) {
            return false;
        }
        if (passengerCounts == null || passengerCounts.length != originalStops.size()) {
            return false;
        }

        for (int i = 0; i < originalStops.size(); i++) {
            double relocationDistance = CoordUtils.calcEuclideanDistance(
                    originalStops.get(i).getCoord(),
                    sharedStop.getCoord());

            if (relocationDistance > maxRelocationMeters) {
                log.trace("Constraint violated: stop {} relocation {}m > max {}m",
                        i, String.format("%.1f", relocationDistance),
                        String.format("%.1f", maxRelocationMeters));
                return false;
            }
        }

        return true;
    }

    /**
     * Calculate relocation distances from each original stop to the shared stop.
     *
     * @param originalStops the original stop locations
     * @param sharedStop the shared stop location
     * @return array of relocation distances (one per original stop)
     * @throws IllegalArgumentException if inputs are null
     */
    public double[] calculateRelocationDistances(
            List<StopLocation> originalStops,
            StopLocation sharedStop) {

        if (originalStops == null) {
            throw new IllegalArgumentException("originalStops cannot be null");
        }
        if (sharedStop == null) {
            throw new IllegalArgumentException("sharedStop cannot be null");
        }

        double[] distances = new double[originalStops.size()];
        for (int i = 0; i < originalStops.size(); i++) {
            distances[i] = CoordUtils.calcEuclideanDistance(
                    originalStops.get(i).getCoord(),
                    sharedStop.getCoord());
        }

        return distances;
    }

    /**
     * Calculate total weighted relocation distance for a given shared stop.
     *
     * @param originalStops the original stop locations
     * @param sharedStop the shared stop location
     * @param passengerCounts number of passengers at each original stop
     * @return total weighted relocation distance (sum of distance * passengers)
     */
    public double calculateTotalWeightedRelocationDistance(
            List<StopLocation> originalStops,
            StopLocation sharedStop,
            double[] passengerCounts) {

        double[] distances = calculateRelocationDistances(originalStops, sharedStop);
        double total = 0;

        for (int i = 0; i < distances.length; i++) {
            total += distances[i] * passengerCounts[i];
        }

        return total;
    }

    /**
     * Log statistics about relocation attempts and success rates.
     */
    public void logStatistics() {
        int attempts = totalAttempts.get();
        if (attempts == 0) {
            log.info("StopRelocator: No relocation attempts made");
            return;
        }

        int successful = successfulRelocations.get();
        double successRate = (successful * 100.0) / attempts;
        int relocatedStops = totalRelocatedStops.get();
        double avgRelocation = relocatedStops > 0
                ? (totalRelocationDistance.get() / 100.0) / relocatedStops
                : 0.0;

        log.info("StopRelocator statistics:");
        log.info("  Total attempts: {}", attempts);
        log.info("  Successful relocations: {} ({} %)",
                successful, String.format("%.1f", successRate));
        log.info("  Failed - no candidate: {}", failedNoCandidate.get());
        log.info("  Failed - constraint violation: {}", failedConstraintViolation.get());
        log.info("  Total stops relocated: {}", relocatedStops);
        log.info("  Average relocation distance: {} m", String.format("%.1f", avgRelocation));
        log.info("  Max relocation limit: {} m", maxRelocationMeters);
    }

    /**
     * Reset all statistics counters.
     */
    public void resetStatistics() {
        totalAttempts.set(0);
        successfulRelocations.set(0);
        failedNoCandidate.set(0);
        failedConstraintViolation.set(0);
        totalRelocationDistance.set(0);
        totalRelocatedStops.set(0);
    }

    // ==================== Statistics Getters ====================

    /**
     * Returns the total number of relocation attempts.
     */
    public int getTotalAttempts() {
        return totalAttempts.get();
    }

    /**
     * Returns the number of successful relocations.
     */
    public int getSuccessfulRelocations() {
        return successfulRelocations.get();
    }

    /**
     * Returns the success rate as a fraction (0.0 to 1.0).
     */
    public double getSuccessRate() {
        int attempts = totalAttempts.get();
        return attempts > 0 ? (double) successfulRelocations.get() / attempts : 0.0;
    }

    /**
     * Returns the average relocation distance in meters.
     */
    public double getAverageRelocationDistance() {
        int relocated = totalRelocatedStops.get();
        return relocated > 0 ? (totalRelocationDistance.get() / 100.0) / relocated : 0.0;
    }

    /**
     * Returns the configured maximum relocation distance in meters.
     */
    public double getMaxRelocationMeters() {
        return maxRelocationMeters;
    }
}
