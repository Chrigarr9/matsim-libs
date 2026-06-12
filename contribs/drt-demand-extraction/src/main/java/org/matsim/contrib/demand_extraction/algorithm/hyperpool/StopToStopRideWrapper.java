package org.matsim.contrib.demand_extraction.algorithm.hyperpool;

import java.util.Comparator;
import java.util.Objects;
import java.util.function.Supplier;

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
 * <h3>Fat vs stub backing</h3>
 * The wrapper supports two construction modes:
 * <ul>
 *   <li><strong>Fat mode</strong> — backed by a full {@link Ride} object.  All getter values
 *       are derived from the ride at construction time and cached as scalars.  The deferred
 *       materializer is {@code () -> ride}.</li>
 *   <li><strong>Stub mode (Plan A2 Task 5)</strong> — backed by scalar fields resolved from
 *       an {@link org.matsim.contrib.demand_extraction.algorithm.bamas.stub.S2SStubColumns} row.
 *       The full ride is constructed lazily via {@link #materialize()} only when the per-cluster
 *       bundling code needs it (
 *       {@link org.matsim.contrib.demand_extraction.algorithm.hyperpool.HyperPoolGenerator}
 *       lines 720, 785–786, 807).  The scalar fields are sufficient for all clustering decisions
 *       (graph construction, compatibility checks, stop-sequence generation).</li>
 * </ul>
 *
 * <p>Key features:
 * <ul>
 *   <li>Validates that the wrapped ride has variant {@link RideVariant#STOP_TO_STOP}
 *       (fat mode only — stub mode relies on the caller's invariant).</li>
 *   <li>Provides convenient access to pickup/dropoff stops</li>
 *   <li>Exposes passenger count from the underlying ride</li>
 *   <li>Provides departure time based on the ride's start time</li>
 *   <li>Includes a comparator for sorting by departure time</li>
 *   <li>{@link #equals}/{@link #hashCode}/{@link #wrapsSameRide} are based on {@code rideIndex}
 *       — this is what makes the inner {@link HyperPoolGenerator} HashMap-keyed graph
 *       deterministic across fat and stub paths.</li>
 * </ul>
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
        .thenComparingInt(wrapper -> wrapper.rideIndex);

    // ---- cached scalar fields (both fat and stub mode) ----
    private final int rideIndex;
    private final StopLocation pickupStop;
    private final StopLocation dropoffStop;
    private final int passengerCount;
    private final double departureTime;
    private final double rideTravelTime;
    private final double rideDistance;
    private final double endTime;

    /** Deferred materializer; non-null in both modes. */
    private final Supplier<Ride> materializer;

    /**
     * Fat mode constructor — creates a wrapper from a full {@link Ride}.
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

        this.rideIndex      = ride.getIndex();
        this.pickupStop     = ride.getPickupStop();
        this.dropoffStop    = ride.getDropoffStop();
        this.passengerCount = ride.getDegree();
        this.departureTime  = ride.getStartTime();
        this.rideTravelTime = ride.getRideTravelTime();
        this.rideDistance   = ride.getRideDistance();
        this.endTime        = ride.getEndTime();
        this.materializer   = () -> ride;
    }

    /**
     * Stub mode constructor (Plan A2 Task 5) — creates a wrapper from pre-resolved scalar
     * fields plus a deferred materializer that lazily builds the full {@link Ride}.
     *
     * <p>The scalar fields must be bit-identical to what the fat constructor would derive
     * from the materialized ride:
     * <ul>
     *   <li>{@code rideIndex} — post-Phase-5 sequential index already stamped on the stub row</li>
     *   <li>{@code pickupStop}/{@code dropoffStop} — resolved from the stop dictionary</li>
     *   <li>{@code passengerCount} — stub layer's degree</li>
     *   <li>{@code departureTime} — {@code S2SStubColumns.startTime(row)}</li>
     *   <li>{@code rideTravelTime}/{@code rideDistance} — {@code StubScaling.fromDeci(ttDs/distDm)}</li>
     *   <li>{@code endTime} — {@code departureTime + rideTravelTime}</li>
     * </ul>
     *
     * @param rideIndex     post-Phase-5 sequential index of this S2S ride
     * @param pickupStop    pickup stop resolved from the stop dictionary
     * @param dropoffStop   dropoff stop resolved from the stop dictionary
     * @param passengerCount number of passengers (= stub layer degree)
     * @param departureTime departure time from the S2S stub's startTime column (seconds)
     * @param rideTravelTime in-vehicle travel time from the S2S stub (seconds, via StubScaling)
     * @param rideDistance  in-vehicle distance from the S2S stub (metres, via StubScaling)
     * @param endTime       {@code departureTime + rideTravelTime}
     * @param materializer  supplier that lazily constructs the full {@link Ride}; invoked only
     *                      during per-cluster bundling (not during graph construction)
     */
    public StopToStopRideWrapper(
            int rideIndex,
            StopLocation pickupStop,
            StopLocation dropoffStop,
            int passengerCount,
            double departureTime,
            double rideTravelTime,
            double rideDistance,
            double endTime,
            Supplier<Ride> materializer) {
        this.rideIndex      = rideIndex;
        this.pickupStop     = pickupStop;
        this.dropoffStop    = dropoffStop;
        this.passengerCount = passengerCount;
        this.departureTime  = departureTime;
        this.rideTravelTime = rideTravelTime;
        this.rideDistance   = rideDistance;
        this.endTime        = endTime;
        this.materializer   = Objects.requireNonNull(materializer, "materializer");
    }

    // ==================== Getters ====================

    /**
     * Materializes the full {@link Ride} for this wrapper.
     *
     * <p>In fat mode this returns the ride passed to the constructor immediately.
     * In stub mode this triggers pinned-stop replay via {@link
     * org.matsim.contrib.demand_extraction.algorithm.bamas.stub.S2SRideMaterializer} —
     * an expensive operation that should be called only during per-cluster bundling,
     * not during graph construction or compatibility checks.
     *
     * <p>Replaces the former {@code getRide()} method.  Callers that need the full ride
     * (e.g.\ bundling code that reads {@code remainingBudgets}, {@code getRequests()},
     * walk distances) must call this method rather than storing a reference to the ride
     * at wrapper construction time — that would defeat the stub-backed memory savings.
     *
     * @return the full materialized {@link Ride}
     */
    public Ride materialize() {
        return materializer.get();
    }

    /**
     * Returns the wrapped ride (fat-mode alias for {@link #materialize()}).
     *
     * @deprecated Use {@link #materialize()} at call sites that need the full ride.
     *             This method is kept for backward compatibility with the fat path but
     *             triggers full materialization in stub mode.
     * @return the underlying stop-to-stop ride
     */
    @Deprecated
    public Ride getRide() {
        return materialize();
    }

    /**
     * Returns the ride index.
     *
     * <p>This is the post-Phase-5 sequential index used by {@link #equals}/
     * {@link #hashCode}/the inner HyperPool shareability graph.
     *
     * @return the ride index
     */
    public int getRideIndex() {
        return rideIndex;
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
        return departureTime;
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
        return rideTravelTime;
    }

    /**
     * Returns the ride distance (from pickup to dropoff).
     *
     * @return the ride distance in meters
     */
    public double getRideDistance() {
        return rideDistance;
    }

    /**
     * Returns the end time of this ride (when arriving at dropoff stop).
     *
     * @return the end time in seconds
     */
    public double getEndTime() {
        return endTime;
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
        return this.rideIndex == other.rideIndex;
    }

    // ==================== Object methods ====================

    @Override
    public String toString() {
        return String.format(
            "StopToStopRideWrapper[rideIndex=%d, pickup=%s, dropoff=%s, passengers=%d, departure=%.1f]",
            rideIndex,
            pickupStop.getLinkId(),
            dropoffStop.getLinkId(),
            passengerCount,
            departureTime);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        StopToStopRideWrapper other = (StopToStopRideWrapper) obj;
        // Two wrappers are equal if they wrap the same ride
        return rideIndex == other.rideIndex;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(rideIndex);
    }
}
