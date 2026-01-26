package org.matsim.contrib.demand_extraction.algorithm.hyperpool;

import java.util.Comparator;
import java.util.Objects;

import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideVariant;
import org.matsim.contrib.demand_extraction.algorithm.domain.StopLocation;

/**
 * Wrapper class that treats a stop-to-stop ride as a "pseudo-request" for HyperPool Stage 2.
 *
 * <p>In HyperPool Stage 2, multiple stop-to-stop rides from Stage 1 are bundled together
 * into hyper-pooled routes. To enable this bundling, stop-to-stop rides need to be treated
 * similarly to original DRT requests - with an origin (pickup stop), destination (dropoff stop),
 * and departure time.
 *
 * <p>This wrapper provides a convenient interface for accessing a stop-to-stop ride's
 * pickup and dropoff stops as if they were the origin and destination of a request,
 * making it easier to apply the same matching and bundling algorithms used in Stage 1.
 *
 * <p>Key features:
 * <ul>
 *   <li>Validates that the wrapped ride has variant {@link RideVariant#STOP_TO_STOP}</li>
 *   <li>Provides convenient access to pickup/dropoff stops</li>
 *   <li>Exposes passenger count from the underlying ride</li>
 *   <li>Provides departure time based on the ride's start time</li>
 *   <li>Includes a comparator for sorting by departure time</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * Ride stopToStopRide = ...; // A ride with variant == STOP_TO_STOP
 * StopToStopRideWrapper wrapper = new StopToStopRideWrapper(stopToStopRide);
 *
 * StopLocation pickup = wrapper.getPickupStop();
 * StopLocation dropoff = wrapper.getDropoffStop();
 * double departureTime = wrapper.getDepartureTime();
 * int passengers = wrapper.getPassengerCount();
 * }</pre>
 *
 * @see Ride
 * @see RideVariant#STOP_TO_STOP
 * @see StopLocation
 */
public final class StopToStopRideWrapper {

    /**
     * Comparator for sorting StopToStopRideWrapper instances by departure time.
     *
     * <p>Rides with earlier departure times come first. If two rides have the same
     * departure time, they are sorted by their underlying ride index for stability.
     */
    public static final Comparator<StopToStopRideWrapper> BY_DEPARTURE_TIME = Comparator
        .comparingDouble(StopToStopRideWrapper::getDepartureTime)
        .thenComparingInt(wrapper -> wrapper.ride.getIndex());

    private final Ride ride;
    private final StopLocation pickupStop;
    private final StopLocation dropoffStop;
    private final int passengerCount;

    /**
     * Creates a new wrapper for a stop-to-stop ride.
     *
     * @param ride the ride to wrap (must have variant == STOP_TO_STOP)
     * @throws IllegalArgumentException if ride is null, has wrong variant,
     *         or is missing required stop locations
     */
    public StopToStopRideWrapper(Ride ride) {
        if (ride == null) {
            throw new IllegalArgumentException("Ride cannot be null");
        }
        if (ride.getVariant() != RideVariant.STOP_TO_STOP) {
            throw new IllegalArgumentException(
                String.format("Ride must have variant STOP_TO_STOP, got: %s (ride index: %d)",
                    ride.getVariant(), ride.getIndex())
            );
        }
        if (ride.getPickupStop() == null) {
            throw new IllegalArgumentException(
                String.format("Ride is missing pickup stop (ride index: %d)", ride.getIndex())
            );
        }
        if (ride.getDropoffStop() == null) {
            throw new IllegalArgumentException(
                String.format("Ride is missing dropoff stop (ride index: %d)", ride.getIndex())
            );
        }

        this.ride = ride;
        this.pickupStop = ride.getPickupStop();
        this.dropoffStop = ride.getDropoffStop();
        this.passengerCount = ride.getDegree();
    }

    // ==================== Getters ====================

    /**
     * Returns the wrapped ride.
     *
     * @return the underlying stop-to-stop ride
     */
    public Ride getRide() {
        return ride;
    }

    /**
     * Returns the ride index from the wrapped ride.
     *
     * @return the ride index
     */
    public int getRideIndex() {
        return ride.getIndex();
    }

    /**
     * Returns the pickup stop for this ride.
     *
     * <p>This is the location where passengers board the vehicle, equivalent to
     * the "origin" in a traditional request.
     *
     * @return the pickup stop location
     */
    public StopLocation getPickupStop() {
        return pickupStop;
    }

    /**
     * Returns the dropoff stop for this ride.
     *
     * <p>This is the location where passengers alight from the vehicle, equivalent to
     * the "destination" in a traditional request.
     *
     * @return the dropoff stop location
     */
    public StopLocation getDropoffStop() {
        return dropoffStop;
    }

    /**
     * Returns the departure time for this ride.
     *
     * <p>This corresponds to the start time of the underlying ride, representing
     * when the vehicle departs from the pickup stop.
     *
     * @return the departure time in seconds
     */
    public double getDepartureTime() {
        return ride.getStartTime();
    }

    /**
     * Returns the total number of passengers in this ride.
     *
     * <p>For stop-to-stop rides, this equals the degree of the underlying ride
     * (the number of original requests that were pooled together).
     *
     * @return the passenger count
     */
    public int getPassengerCount() {
        return passengerCount;
    }

    /**
     * Returns the ride travel time (time from pickup to dropoff).
     *
     * @return the ride travel time in seconds
     */
    public double getRideTravelTime() {
        return ride.getRideTravelTime();
    }

    /**
     * Returns the ride distance (from pickup to dropoff).
     *
     * @return the ride distance in meters
     */
    public double getRideDistance() {
        return ride.getRideDistance();
    }

    /**
     * Returns the end time of this ride (when arriving at dropoff stop).
     *
     * @return the end time in seconds
     */
    public double getEndTime() {
        return ride.getEndTime();
    }

    /**
     * Checks if this wrapper wraps the same ride as another wrapper.
     *
     * @param other the other wrapper to compare
     * @return true if both wrappers wrap the same ride (by index)
     */
    public boolean wrapsSameRide(StopToStopRideWrapper other) {
        if (other == null) {
            return false;
        }
        return this.ride.getIndex() == other.ride.getIndex();
    }

    // ==================== Object methods ====================

    @Override
    public String toString() {
        return String.format(
            "StopToStopRideWrapper[rideIndex=%d, pickup=%s, dropoff=%s, passengers=%d, departure=%.1f]",
            ride.getIndex(),
            pickupStop.getLinkId(),
            dropoffStop.getLinkId(),
            passengerCount,
            getDepartureTime()
        );
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        StopToStopRideWrapper other = (StopToStopRideWrapper) obj;
        // Two wrappers are equal if they wrap the same ride
        return ride.getIndex() == other.ride.getIndex();
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(ride.getIndex());
    }
}
