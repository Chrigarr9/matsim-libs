package org.matsim.contrib.demand_extraction.algorithm.hyperpool;

import org.matsim.api.core.v01.Coord;
import org.matsim.contrib.demand_extraction.algorithm.domain.StopLocation;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.core.utils.geometry.CoordUtils;

/**
 * Checks if two stop-to-stop rides are compatible for hyper-pooling.
 *
 * <p>In HyperPool Stage 2, stop-to-stop rides from Stage 1 are bundled together
 * into hyper-pooled routes. This class provides various compatibility checks
 * to determine which rides can be bundled efficiently.
 *
 * <p>Compatibility is evaluated across multiple dimensions:
 * <ul>
 *   <li><b>Temporal compatibility</b>: Rides must depart within a configurable time window</li>
 *   <li><b>Spatial compatibility (pickup)</b>: Pickup stops must be within proximity threshold</li>
 *   <li><b>Spatial compatibility (dropoff)</b>: Dropoff stops must be within proximity threshold</li>
 *   <li><b>Directional compatibility</b>: Rides must travel in similar directions</li>
 * </ul>
 *
 * <p>The class also provides a scoring mechanism to rank compatible ride pairs,
 * with higher scores indicating better compatibility for bundling.
 *
 * <p>Example usage:
 * <pre>{@code
 * ExMasConfigGroup config = ...;
 * StopCompatibilityChecker checker = new StopCompatibilityChecker(config);
 *
 * StopToStopRideWrapper ride1 = ...;
 * StopToStopRideWrapper ride2 = ...;
 *
 * // Check full compatibility using config defaults
 * boolean compatible = checker.areCompatible(ride1, ride2);
 *
 * // Or with custom thresholds
 * boolean compatible = checker.areCompatible(ride1, ride2, 900.0, 150.0);
 *
 * // Get compatibility score for ranking
 * double score = checker.calculateCompatibilityScore(ride1, ride2, 900.0, 150.0);
 * }</pre>
 *
 * @see StopToStopRideWrapper
 * @see StopLocation
 */
public final class StopCompatibilityChecker {

    private final double defaultTimeWindowSeconds;
    private final double defaultProximityMeters;
    private final boolean enableSpatialFilter;

    /**
     * Creates a new compatibility checker with default thresholds from configuration.
     *
     * @param config the ExMAS configuration group providing default thresholds
     * @throws IllegalArgumentException if config is null
     */
    public StopCompatibilityChecker(ExMasConfigGroup config) {
        if (config == null) {
            throw new IllegalArgumentException("Config cannot be null");
        }
        this.defaultTimeWindowSeconds = config.getHyperPoolTimeWindowSeconds();
        this.defaultProximityMeters = config.getHyperPoolStopProximityMeters();
        this.enableSpatialFilter = config.getHyperPoolEnableSpatialFilter();
    }

    // ==================== Temporal Compatibility ====================

    /**
     * Checks if two rides are temporally compatible.
     *
     * <p>Two rides are temporally compatible if their departure times are within
     * the specified time window. This ensures that rides can potentially be
     * served together without excessive waiting times.
     *
     * @param r1 the first ride
     * @param r2 the second ride
     * @param timeWindowSeconds the maximum allowed time difference between departures (seconds)
     * @return true if rides depart within the time window of each other
     * @throws IllegalArgumentException if either ride is null or timeWindowSeconds is negative
     */
    public boolean checkTemporalCompatibility(StopToStopRideWrapper r1, StopToStopRideWrapper r2,
                                               double timeWindowSeconds) {
        validateRidePair(r1, r2);
        if (timeWindowSeconds < 0) {
            throw new IllegalArgumentException("Time window cannot be negative: " + timeWindowSeconds);
        }

        double timeDifference = Math.abs(r1.getDepartureTime() - r2.getDepartureTime());
        return timeDifference <= timeWindowSeconds;
    }

    /**
     * Checks temporal compatibility using the default time window from configuration.
     *
     * @param r1 the first ride
     * @param r2 the second ride
     * @return true if rides depart within the default time window
     */
    public boolean checkTemporalCompatibility(StopToStopRideWrapper r1, StopToStopRideWrapper r2) {
        return checkTemporalCompatibility(r1, r2, defaultTimeWindowSeconds);
    }

    // ==================== Spatial Compatibility (Pickup) ====================

    /**
     * Checks if two rides have compatible pickup stops.
     *
     * <p>Pickup stops are compatible if the Euclidean distance between their
     * coordinates is within the specified proximity threshold. Close pickup stops
     * can potentially be served by the same vehicle stop.
     *
     * @param r1 the first ride
     * @param r2 the second ride
     * @param proximityMeters the maximum allowed distance between pickup stops (meters)
     * @return true if pickup stops are within the proximity threshold
     * @throws IllegalArgumentException if either ride is null or proximityMeters is negative
     */
    public boolean checkPickupCompatibility(StopToStopRideWrapper r1, StopToStopRideWrapper r2,
                                             double proximityMeters) {
        validateRidePair(r1, r2);
        if (proximityMeters < 0) {
            throw new IllegalArgumentException("Proximity threshold cannot be negative: " + proximityMeters);
        }

        Coord pickup1 = r1.getPickupStop().getCoord();
        Coord pickup2 = r2.getPickupStop().getCoord();
        double distance = CoordUtils.calcEuclideanDistance(pickup1, pickup2);
        return distance <= proximityMeters;
    }

    /**
     * Checks pickup compatibility using the default proximity from configuration.
     *
     * @param r1 the first ride
     * @param r2 the second ride
     * @return true if pickup stops are within the default proximity threshold
     */
    public boolean checkPickupCompatibility(StopToStopRideWrapper r1, StopToStopRideWrapper r2) {
        return checkPickupCompatibility(r1, r2, defaultProximityMeters);
    }

    // ==================== Spatial Compatibility (Dropoff) ====================

    /**
     * Checks if two rides have compatible dropoff stops.
     *
     * <p>Dropoff stops are compatible if the Euclidean distance between their
     * coordinates is within the specified proximity threshold. Close dropoff stops
     * can potentially be served by the same vehicle stop.
     *
     * @param r1 the first ride
     * @param r2 the second ride
     * @param proximityMeters the maximum allowed distance between dropoff stops (meters)
     * @return true if dropoff stops are within the proximity threshold
     * @throws IllegalArgumentException if either ride is null or proximityMeters is negative
     */
    public boolean checkDropoffCompatibility(StopToStopRideWrapper r1, StopToStopRideWrapper r2,
                                              double proximityMeters) {
        validateRidePair(r1, r2);
        if (proximityMeters < 0) {
            throw new IllegalArgumentException("Proximity threshold cannot be negative: " + proximityMeters);
        }

        Coord dropoff1 = r1.getDropoffStop().getCoord();
        Coord dropoff2 = r2.getDropoffStop().getCoord();
        double distance = CoordUtils.calcEuclideanDistance(dropoff1, dropoff2);
        return distance <= proximityMeters;
    }

    /**
     * Checks dropoff compatibility using the default proximity from configuration.
     *
     * @param r1 the first ride
     * @param r2 the second ride
     * @return true if dropoff stops are within the default proximity threshold
     */
    public boolean checkDropoffCompatibility(StopToStopRideWrapper r1, StopToStopRideWrapper r2) {
        return checkDropoffCompatibility(r1, r2, defaultProximityMeters);
    }

    // ==================== Directional Compatibility ====================

    /**
     * Checks if two rides travel in similar directions.
     *
     * <p>Directional compatibility is determined using the dot product of the
     * direction vectors (from pickup to dropoff) of each ride. If the dot product
     * is positive, the rides are traveling in the same general direction and are
     * compatible for bundling.
     *
     * <p>The dot product formula:
     * <pre>
     * v1 = (dropoff1.x - pickup1.x, dropoff1.y - pickup1.y)
     * v2 = (dropoff2.x - pickup2.x, dropoff2.y - pickup2.y)
     * dotProduct = v1.x * v2.x + v1.y * v2.y
     * </pre>
     *
     * <p>Interpretation:
     * <ul>
     *   <li>dotProduct &gt; 0: Rides travel in the same general direction (compatible)</li>
     *   <li>dotProduct = 0: Rides are perpendicular</li>
     *   <li>dotProduct &lt; 0: Rides travel in opposite directions (incompatible)</li>
     * </ul>
     *
     * @param r1 the first ride
     * @param r2 the second ride
     * @return true if rides travel in the same general direction (dot product &gt; 0)
     * @throws IllegalArgumentException if either ride is null
     */
    public boolean checkDirectionalCompatibility(StopToStopRideWrapper r1, StopToStopRideWrapper r2) {
        validateRidePair(r1, r2);

        double dotProduct = calculateDirectionDotProduct(r1, r2);
        return dotProduct > 0;
    }

    /**
     * Calculates the dot product of direction vectors for two rides.
     *
     * <p>This method is useful for understanding the degree of directional
     * alignment between rides. Higher positive values indicate stronger
     * alignment.
     *
     * @param r1 the first ride
     * @param r2 the second ride
     * @return the dot product of the direction vectors
     */
    public double calculateDirectionDotProduct(StopToStopRideWrapper r1, StopToStopRideWrapper r2) {
        validateRidePair(r1, r2);

        // Get coordinates
        Coord pickup1 = r1.getPickupStop().getCoord();
        Coord dropoff1 = r1.getDropoffStop().getCoord();
        Coord pickup2 = r2.getPickupStop().getCoord();
        Coord dropoff2 = r2.getDropoffStop().getCoord();

        // Calculate direction vectors
        double v1x = dropoff1.getX() - pickup1.getX();
        double v1y = dropoff1.getY() - pickup1.getY();
        double v2x = dropoff2.getX() - pickup2.getX();
        double v2y = dropoff2.getY() - pickup2.getY();

        // Calculate dot product
        return v1x * v2x + v1y * v2y;
    }

    // ==================== Compatibility Scoring ====================

    /**
     * Calculates a compatibility score for a pair of rides.
     *
     * <p>The score combines multiple factors:
     * <ul>
     *   <li><b>Stop proximity score</b>: Based on how close pickup and dropoff stops are</li>
     *   <li><b>Time overlap score</b>: Based on how close departure times are</li>
     *   <li><b>Direction alignment score</b>: Based on cosine similarity of direction vectors</li>
     * </ul>
     *
     * <p>Higher scores indicate better compatibility for bundling. The score is
     * normalized to approximately [0, 3] range where:
     * <ul>
     *   <li>~1.0 for perfect proximity match (both pickup and dropoff at same location)</li>
     *   <li>~1.0 for perfect time match (same departure time)</li>
     *   <li>~1.0 for perfect directional alignment (parallel directions)</li>
     * </ul>
     *
     * <p>Note: Returns 0 if rides are not directionally compatible (traveling in
     * opposite directions).
     *
     * @param r1 the first ride
     * @param r2 the second ride
     * @param timeWindowSeconds the time window for normalization
     * @param proximityMeters the proximity threshold for normalization
     * @return compatibility score (higher is better), 0 if directionally incompatible
     * @throws IllegalArgumentException if inputs are invalid
     */
    public double calculateCompatibilityScore(StopToStopRideWrapper r1, StopToStopRideWrapper r2,
                                               double timeWindowSeconds, double proximityMeters) {
        validateRidePair(r1, r2);
        if (timeWindowSeconds <= 0) {
            throw new IllegalArgumentException("Time window must be positive: " + timeWindowSeconds);
        }
        if (proximityMeters <= 0) {
            throw new IllegalArgumentException("Proximity must be positive: " + proximityMeters);
        }

        // Check directional compatibility first - if opposite directions, score is 0
        double dotProduct = calculateDirectionDotProduct(r1, r2);
        if (dotProduct <= 0) {
            return 0.0;
        }

        // Calculate stop proximity score (average of pickup and dropoff proximity)
        double pickupDistance = CoordUtils.calcEuclideanDistance(
                r1.getPickupStop().getCoord(), r2.getPickupStop().getCoord());
        double dropoffDistance = CoordUtils.calcEuclideanDistance(
                r1.getDropoffStop().getCoord(), r2.getDropoffStop().getCoord());

        // Proximity score: 1.0 at distance 0, approaches 0 as distance increases
        // Using exponential decay for smooth scoring
        double pickupProximityScore = Math.exp(-pickupDistance / proximityMeters);
        double dropoffProximityScore = Math.exp(-dropoffDistance / proximityMeters);
        double proximityScore = (pickupProximityScore + dropoffProximityScore) / 2.0;

        // Calculate time overlap score
        double timeDifference = Math.abs(r1.getDepartureTime() - r2.getDepartureTime());
        // Time score: 1.0 at time difference 0, approaches 0 as difference increases
        double timeScore = Math.exp(-timeDifference / timeWindowSeconds);

        // Calculate direction alignment score using cosine similarity
        Coord pickup1 = r1.getPickupStop().getCoord();
        Coord dropoff1 = r1.getDropoffStop().getCoord();
        Coord pickup2 = r2.getPickupStop().getCoord();
        Coord dropoff2 = r2.getDropoffStop().getCoord();

        double v1x = dropoff1.getX() - pickup1.getX();
        double v1y = dropoff1.getY() - pickup1.getY();
        double v2x = dropoff2.getX() - pickup2.getX();
        double v2y = dropoff2.getY() - pickup2.getY();

        double magnitude1 = Math.sqrt(v1x * v1x + v1y * v1y);
        double magnitude2 = Math.sqrt(v2x * v2x + v2y * v2y);

        double directionScore;
        if (magnitude1 == 0 || magnitude2 == 0) {
            // If either ride has zero-length direction vector, assume neutral alignment
            directionScore = 0.5;
        } else {
            // Cosine similarity: ranges from -1 to 1, we already filtered out negative
            double cosineSimilarity = dotProduct / (magnitude1 * magnitude2);
            // Map [0, 1] to [0, 1] for direction score (already filtered negative)
            directionScore = Math.max(0, cosineSimilarity);
        }

        // Combine scores: sum of all components
        return proximityScore + timeScore + directionScore;
    }

    /**
     * Calculates compatibility score using default thresholds from configuration.
     *
     * @param r1 the first ride
     * @param r2 the second ride
     * @return compatibility score (higher is better)
     */
    public double calculateCompatibilityScore(StopToStopRideWrapper r1, StopToStopRideWrapper r2) {
        return calculateCompatibilityScore(r1, r2, defaultTimeWindowSeconds, defaultProximityMeters);
    }

    // ==================== Full Compatibility Check ====================

    /**
     * Checks if two rides are fully compatible for hyper-pooling.
     *
     * <p>Two rides are fully compatible if ALL of the following conditions are met:
     * <ol>
     *   <li>Temporal compatibility: Departure times within time window</li>
     *   <li>Directional compatibility: Rides travel in same general direction</li>
     *   <li>Spatial compatibility (if enabled): EITHER pickup stops OR dropoff stops within proximity threshold</li>
     * </ol>
     *
     * <p>Spatial filtering behavior (controlled by hyperPoolEnableSpatialFilter config):
     * <ul>
     *   <li>If enabled (default): Pre-filters to only nearby stops (pickup OR dropoff), faster but may miss some valid bundles</li>
     *   <li>If disabled: Uses original utility-based approach, evaluates all ride pairs (slower but comprehensive)</li>
     * </ul>
     *
     * <p>With spatial filtering enabled, supports asymmetric patterns like:
     * <ul>
     *   <li>"Shuttle from downtown" - common pickup, various dropoffs</li>
     *   <li>"Shuttle to airport" - various pickups, common dropoff</li>
     * </ul>
     *
     * @param r1 the first ride
     * @param r2 the second ride
     * @param timeWindowSeconds the maximum allowed time difference between departures
     * @param proximityMeters the maximum allowed distance between stops
     * @return true only if ALL compatibility checks pass
     * @throws IllegalArgumentException if inputs are invalid
     */
    public boolean areCompatible(StopToStopRideWrapper r1, StopToStopRideWrapper r2,
                                  double timeWindowSeconds, double proximityMeters) {
        // Check all compatibility conditions
        // Order matters for short-circuit evaluation - check faster/more likely to fail first

        // Directional check is fast (just computation)
        if (!checkDirectionalCompatibility(r1, r2)) {
            return false;
        }

        // Temporal check is also fast
        if (!checkTemporalCompatibility(r1, r2, timeWindowSeconds)) {
            return false;
        }

        // Spatial check (optional based on configuration)
        if (enableSpatialFilter) {
            // Require EITHER pickup OR dropoff compatibility
            // This allows bundling rides with common origin OR common destination
            boolean pickupCompatible = checkPickupCompatibility(r1, r2, proximityMeters);
            boolean dropoffCompatible = checkDropoffCompatibility(r1, r2, proximityMeters);

            if (!pickupCompatible && !dropoffCompatible) {
                return false;
            }
        }
        // If spatial filter disabled, rely on utility/budget constraints (like original ExMAS HyperPool)

        return true;
    }

    /**
     * Checks full compatibility using default thresholds from configuration.
     *
     * @param r1 the first ride
     * @param r2 the second ride
     * @return true only if ALL compatibility checks pass
     */
    public boolean areCompatible(StopToStopRideWrapper r1, StopToStopRideWrapper r2) {
        return areCompatible(r1, r2, defaultTimeWindowSeconds, defaultProximityMeters);
    }

    // ==================== Utility Methods ====================

    /**
     * Gets the Euclidean distance between pickup stops of two rides.
     *
     * @param r1 the first ride
     * @param r2 the second ride
     * @return the distance between pickup stops in meters
     */
    public double getPickupDistance(StopToStopRideWrapper r1, StopToStopRideWrapper r2) {
        validateRidePair(r1, r2);
        return CoordUtils.calcEuclideanDistance(
                r1.getPickupStop().getCoord(), r2.getPickupStop().getCoord());
    }

    /**
     * Gets the Euclidean distance between dropoff stops of two rides.
     *
     * @param r1 the first ride
     * @param r2 the second ride
     * @return the distance between dropoff stops in meters
     */
    public double getDropoffDistance(StopToStopRideWrapper r1, StopToStopRideWrapper r2) {
        validateRidePair(r1, r2);
        return CoordUtils.calcEuclideanDistance(
                r1.getDropoffStop().getCoord(), r2.getDropoffStop().getCoord());
    }

    /**
     * Gets the absolute time difference between departures of two rides.
     *
     * @param r1 the first ride
     * @param r2 the second ride
     * @return the time difference in seconds
     */
    public double getTimeDifference(StopToStopRideWrapper r1, StopToStopRideWrapper r2) {
        validateRidePair(r1, r2);
        return Math.abs(r1.getDepartureTime() - r2.getDepartureTime());
    }

    /**
     * Gets the default time window from configuration.
     *
     * @return the default time window in seconds
     */
    public double getDefaultTimeWindowSeconds() {
        return defaultTimeWindowSeconds;
    }

    /**
     * Gets the default proximity threshold from configuration.
     *
     * @return the default proximity threshold in meters
     */
    public double getDefaultProximityMeters() {
        return defaultProximityMeters;
    }

    // ==================== Private Helpers ====================

    /**
     * Validates that both rides in a pair are non-null.
     *
     * @param r1 the first ride
     * @param r2 the second ride
     * @throws IllegalArgumentException if either ride is null
     */
    private void validateRidePair(StopToStopRideWrapper r1, StopToStopRideWrapper r2) {
        if (r1 == null) {
            throw new IllegalArgumentException("First ride cannot be null");
        }
        if (r2 == null) {
            throw new IllegalArgumentException("Second ride cannot be null");
        }
    }
}
