package org.matsim.contrib.demand_extraction.algorithm.domain;

import java.util.Objects;

/**
 * Immutable value object representing travel metrics between two locations.
 * Corresponds to an entry in the Python network dictionary.
 *
 * Python reference: Network data accessed via self.network.dict
 * Example: {(origin, destination): {'travel_time': 120.5, 'distance': 2500.0, 'network_utility': 0.8}}
 */
public final class TravelSegment {
    private final int origin;
    private final int destination;
    private final double travelTime;
    private final double distance;
    private final double networkUtility;

	//C: instead of ints wouldnt it make more sense to move this to link ids instaed!?
	// also this seems redundant to the Travel Segment class in the MatsimNetworkCache file
    public TravelSegment(int origin, int destination, double travelTime,
                        double distance, double networkUtility) {
        if (travelTime < 0 && !Double.isInfinite(travelTime)) {
            throw new IllegalArgumentException("Travel time cannot be negative: " + travelTime);
        }
        if (distance < 0 && !Double.isInfinite(distance)) {
            throw new IllegalArgumentException("Distance cannot be negative: " + distance);
        }

        this.origin = origin;
        this.destination = destination;
        this.travelTime = travelTime;
        this.distance = distance;
        this.networkUtility = networkUtility;
    }

    // Getters
    public int getOrigin() { return origin; }
    public int getDestination() { return destination; }
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
        return new TravelSegment(0, 0, Double.POSITIVE_INFINITY, 
                               Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TravelSegment that = (TravelSegment) o;
        return origin == that.origin &&
               destination == that.destination &&
               Double.compare(that.travelTime, travelTime) == 0 &&
               Double.compare(that.distance, distance) == 0 &&
               Double.compare(that.networkUtility, networkUtility) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(origin, destination, travelTime, distance, networkUtility);
    }

    @Override
    public String toString() {
        if (!isReachable()) {
            return String.format("Segment[%d->%d: UNREACHABLE]", origin, destination);
        }
        return String.format("Segment[%d->%d: tt=%.1fs, dist=%.1fm, util=%.3f]",
            origin, destination, travelTime, distance, networkUtility);
    }
}
