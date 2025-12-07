package org.matsim.contrib.demand_extraction.algorithm.domain;

import java.util.Objects;

/**
 * Immutable value object representing travel metrics between two locations.
 * 
 * DESIGN: Stores only the computed metrics (time, distance, utility), not the
 * origin/destination links. The MatsimNetworkCache handles Link ID management
 * and caching. This keeps TravelSegment focused purely on travel metrics.
 * 
 * DIFFERENCE FROM MatsimNetworkCache:
 * - TravelSegment: Pure data container (value object) with travel metrics only
 * - MatsimNetworkCache: Active cache manager that maps Link IDs to
 * TravelSegments
 * 
 * Think of it as: Cache[LinkId, LinkId] -> TravelSegment(metrics)
 * Similar to how a Map<Key, Value> separates keys from values.
 *
 * Python reference: Network data accessed via self.network.dict
 * Example: {(origin, destination): {'travel_time': 120.5, 'distance': 2500.0,
 * 'network_utility': 0.8}}
 */
public final class TravelSegment {
    private final double travelTime;
    private final double distance;
    private final double networkUtility;

	public TravelSegment(double travelTime, double distance, double networkUtility) {
        if (travelTime < 0 && !Double.isInfinite(travelTime)) {
            throw new IllegalArgumentException("Travel time cannot be negative: " + travelTime);
        }
        if (distance < 0 && !Double.isInfinite(distance)) {
            throw new IllegalArgumentException("Distance cannot be negative: " + distance);
        }

        this.travelTime = travelTime;
        this.distance = distance;
        this.networkUtility = networkUtility;
    }

	// Getters
    public double getTravelTime() { return travelTime; }
    public double getDistance() { return distance; }
    public double getNetworkUtility() { return networkUtility; }

    /**
     * Check if this segment is reachable (not infinity).
     */
    public boolean isReachable() {
        return !Double.isInfinite(travelTime) && !Double.isInfinite(distance);
    }
    
    /**
     * Creates an unreachable segment (with infinite travel time and distance).
     */
    public static TravelSegment unreachable() {
		return new TravelSegment(Double.POSITIVE_INFINITY,
				Double.POSITIVE_INFINITY,
				Double.NEGATIVE_INFINITY);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TravelSegment that = (TravelSegment) o;
		return Double.compare(that.travelTime, travelTime) == 0 &&
               Double.compare(that.distance, distance) == 0 &&
               Double.compare(that.networkUtility, networkUtility) == 0;
    }

    @Override
    public int hashCode() {
		return Objects.hash(travelTime, distance, networkUtility);
    }

    @Override
    public String toString() {
        if (!isReachable()) {
			return "TravelSegment[UNREACHABLE]";
        }
		return String.format("TravelSegment[tt=%.1fs, dist=%.1fm, util=%.3f]",
				travelTime, distance, networkUtility);
    }
}
