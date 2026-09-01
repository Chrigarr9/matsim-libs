package org.matsim.contrib.demand_extraction.algorithm.domain;

import java.util.Arrays;
import java.util.stream.Collectors;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideVariant;
import org.matsim.contrib.demand_extraction.algorithm.domain.StopLocation;

/**
 * Immutable representation of a shared ride.
 * Corresponds to a row in the Python rides DataFrame.
 *
 * Python reference: src/exmas_commuters/core/exmas/rides.py
 *
 * Uses direct DrtRequest references. Link IDs are derived from requests when needed.
 */
public final class Ride {
    // Non-final so the extension phase can stamp the deterministic post-sort index
    // in place via assignIndex(), instead of a full toBuilder().build() rebuild that
    // deep-clones all ~17 arrays. The rebuild doubled per-degree Ride retention at
    // scale (originals pinned in resultBySetHash + cloned copies in the result list)
    // and OOM'd at 100%. Same motivation as the non-final remainingBudgets below.
    private int index;
    private final int degree;
    private final RideKind kind;

    // Request arrays (length = degree)
    private final DrtRequest[] requests;                    // Requests in index order
    private final DrtRequest[] originsOrderedRequests;      // Requests in pickup order
    private final DrtRequest[] destinationsOrderedRequests; // Requests in dropoff order

    // Travel metrics per passenger (length = degree)
    private final double[] passengerTravelTimes;
    private final double[] passengerDistances;
    private final double[] passengerNetworkUtilities;
    private final double[] delays;
	private final double[] detours; // Detour factor: passengerTravelTime / directTravelTime (1.0 = no detour, 2.0 =
									// 100% detour)
    private double[] remainingBudgets;  // Budget remaining after scoring (utils); set via the builder
    private final double[] maxCosts; // Maximum willingness-to-pay per passenger (currency units)

    // Connection segments (length = degree*2 - 1 for most rides)
    private final double[] connectionTravelTimes;
    private final double[] connectionDistances;
    private final double[] connectionNetworkUtilities;

    // Aggregated ride metrics
    private final double rideTravelTime;
    private final double rideDistance;
    private final double rideNetworkUtility;
    private final double startTime;
    private final double endTime;

    // Optional advanced metrics (can be null)
    private final double[] shapleyValues;
    private final double reposTimeMeanOutgoing;

    // Stop-based pooling support (HyperPool integration)
    private final RideVariant variant;
    private final StopLocation pickupStop;
    private final StopLocation dropoffStop;
    private final double[] accessWalkDistances;
    private final double[] egressWalkDistances;

    // Private constructor - use Builder
    private Ride(Builder builder) {
        this.index = builder.index;
        this.degree = builder.degree;
        this.kind = builder.kind;

        // Copy arrays (defensive)
        this.requests = builder.requests.clone();
        this.originsOrderedRequests = builder.originsOrderedRequests.clone();
        this.destinationsOrderedRequests = builder.destinationsOrderedRequests.clone();
        this.passengerTravelTimes = builder.passengerTravelTimes.clone();
        this.passengerDistances = builder.passengerDistances.clone();
        this.passengerNetworkUtilities = builder.passengerNetworkUtilities.clone();
        this.delays = builder.delays.clone();
		this.detours = builder.detours.clone();
        this.remainingBudgets = builder.remainingBudgets != null ? builder.remainingBudgets.clone() : null;
        this.maxCosts = builder.maxCosts != null ? builder.maxCosts.clone() : null;
        this.connectionTravelTimes = builder.connectionTravelTimes.clone();
        this.connectionDistances = builder.connectionDistances.clone();
        this.connectionNetworkUtilities = builder.connectionNetworkUtilities.clone();

        // Calculate aggregates
        this.rideTravelTime = Math.round(sum(connectionTravelTimes) * 10.0) / 10.0;  // Round to 1 decimal
        this.rideDistance = Math.round(sum(connectionDistances) * 10.0) / 10.0;      // Round to 1 decimal
        this.rideNetworkUtility = sum(connectionNetworkUtilities);
        this.startTime = builder.startTime;
        this.endTime = startTime + rideTravelTime;

        // Optional fields
        this.shapleyValues = builder.shapleyValues != null ? builder.shapleyValues.clone() : null;
        this.reposTimeMeanOutgoing = builder.reposTimeMeanOutgoing;

        // Stop-based pooling fields
        this.variant = builder.variant != null ? builder.variant : RideVariant.DOOR_TO_DOOR;
        this.pickupStop = builder.pickupStop;
        this.dropoffStop = builder.dropoffStop;
        this.accessWalkDistances = builder.accessWalkDistances != null ? builder.accessWalkDistances.clone() : null;
        this.egressWalkDistances = builder.egressWalkDistances != null ? builder.egressWalkDistances.clone() : null;
    }

    private static double sum(double[] array) {
        double total = 0;
        for (double v : array) {
            total += v;
        }
        return total;
    }

    // Getters
    public int getIndex() { return index; }

    /**
     * Stamp the output index in place, avoiding a full toBuilder().build() clone.
     *
     * <p>{@code index} participates in {@link #equals}/{@link #hashCode}, so this
     * MUST NOT be called while the ride is a live key in a hash-based collection.
     * It is only used by the extension phase to assign the deterministic, sorted
     * sequential index after all rides for a degree have been collected — at which
     * point rides live only in {@code Map<Long,Ride>} values and {@code List<Ride>},
     * never as {@code Ride} keys. See the field comment for the memory rationale.
     */
    public void assignIndex(int newIndex) { this.index = newIndex; }
    public int getDegree() { return degree; }
    public RideKind getKind() { return kind; }

    // Direct request access
    public DrtRequest[] getRequests() { return requests.clone(); }

    public DrtRequest getRequest(int passengerIndex) {
        return requests[passengerIndex];
    }

    // Derived indices for graph operations and CSV output
    public int[] getRequestIndices() {
        int[] indices = new int[requests.length];
        for (int i = 0; i < requests.length; i++) {
            indices[i] = requests[i].index;
        }
        return indices;
    }

    // Derive Link IDs from request arrays
    @SuppressWarnings("unchecked")
    public Id<Link>[] getOriginsOrdered() {
        Id<Link>[] links = (Id<Link>[]) new Id[originsOrderedRequests.length];
        for (int i = 0; i < originsOrderedRequests.length; i++) {
            links[i] = originsOrderedRequests[i].originLinkId;
        }
        return links;
    }

    @SuppressWarnings("unchecked")
    public Id<Link>[] getDestinationsOrdered() {
        Id<Link>[] links = (Id<Link>[]) new Id[destinationsOrderedRequests.length];
        for (int i = 0; i < destinationsOrderedRequests.length; i++) {
            links[i] = destinationsOrderedRequests[i].destinationLinkId;
        }
        return links;
    }

    public DrtRequest[] getOriginsOrderedRequests() {
        return originsOrderedRequests.clone();
    }

    public DrtRequest[] getDestinationsOrderedRequests() {
        return destinationsOrderedRequests.clone();
    }

    // Derived indices for origins/destinations ordering
    public int[] getOriginsIndex() {
        int[] indices = new int[originsOrderedRequests.length];
        for (int i = 0; i < originsOrderedRequests.length; i++) {
            indices[i] = originsOrderedRequests[i].index;
        }
        return indices;
    }

    public int[] getDestinationsIndex() {
        int[] indices = new int[destinationsOrderedRequests.length];
        for (int i = 0; i < destinationsOrderedRequests.length; i++) {
            indices[i] = destinationsOrderedRequests[i].index;
        }
        return indices;
    }

    public double[] getPassengerTravelTimes() { return passengerTravelTimes.clone(); }
    public double[] getPassengerDistances() { return passengerDistances.clone(); }
    public double[] getPassengerNetworkUtilities() { return passengerNetworkUtilities.clone(); }
    public double[] getDelays() { return delays.clone(); }

	public double[] getDetours() {
		return detours.clone();
	}
    public double[] getRemainingBudgets() { return remainingBudgets != null ? remainingBudgets.clone() : null; }

    public double[] getMaxCosts() { return maxCosts != null ? maxCosts.clone() : null; }
    public double[] getConnectionTravelTimes() { return connectionTravelTimes.clone(); }
    public double[] getConnectionDistances() { return connectionDistances.clone(); }
    public double[] getConnectionNetworkUtilities() { return connectionNetworkUtilities.clone(); }

    public double getRideTravelTime() { return rideTravelTime; }
    public double getRideDistance() { return rideDistance; }
    public double getRideNetworkUtility() { return rideNetworkUtility; }
    public double getStartTime() { return startTime; }
    public double getEndTime() { return endTime; }

    public double[] getShapleyValues() { return shapleyValues != null ? shapleyValues.clone() : null; }
    /**
     * Mean travel time of the outgoing handoff repositionings kept by the top-K selection in
     * {@code RidePostProcessor.computePredecessors}, or {@code -1.0} when the ride has no feasible
     * successor or the pass was disabled. The successor and predecessor LISTS the pass used to
     * carry were dropped: Python recomputes successors over the MIP-selected ride set, so the
     * static edges were never read.
     */
    public double getReposTimeMeanOutgoing() { return reposTimeMeanOutgoing; }

    // Stop-based pooling getters
    public RideVariant getVariant() { return variant; }
    public StopLocation getPickupStop() { return pickupStop; }
    public StopLocation getDropoffStop() { return dropoffStop; }
    public double[] getAccessWalkDistances() { return accessWalkDistances != null ? accessWalkDistances.clone() : null; }
    public double[] getEgressWalkDistances() { return egressWalkDistances != null ? egressWalkDistances.clone() : null; }

    /**
     * Returns the maximum simultaneous in-vehicle passenger count over the
     * ride's stop sequence — required by the Extension-2 class-aware path
     * cover (rev-3 §7.4b).
     *
     * <p>For a {@link Ride}, the implicit stop sequence is
     * {@code [all pickups in origin-order, then all dropoffs in
     * destination-order]} — pickups always precede dropoffs in the sweep,
     * so the running max is always {@code degree}. The method is still
     * implemented as an explicit +1/-1 sweep (rather than returning
     * {@code degree} directly) so it stays semantically aligned with the
     * {@link HyperPooledRide} counterpart and survives any future change
     * to ride structure (e.g. interleaved sequences).
     *
     * <p>NB: per-pax wall-clock timestamps are NOT stored on the Ride
     * object; only stop-sequence ordering matters here, so we sweep over
     * the event order directly rather than chasing absolute times.
     */
    public int getPeakPax() {
        int occ = 0;
        int peak = 0;
        // All pickups first.
        for (int i = 0; i < originsOrderedRequests.length; i++) {
            occ++;
            if (occ > peak) {
                peak = occ;
            }
        }
        // Then all dropoffs.
        for (int i = 0; i < destinationsOrderedRequests.length; i++) {
            occ--;
        }
        return peak;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a new Builder pre-populated with all values from this Ride.
     * Use this to create modified copies of existing rides without manually copying all fields.
     * Example: ride.toBuilder().index(newIndex).build()
     */
    public Builder toBuilder() {
        return new Builder()
            .index(this.index)
            .degree(this.degree)
            .kind(this.kind)
            .requests(this.requests)
            .originsOrderedRequests(this.originsOrderedRequests)
            .destinationsOrderedRequests(this.destinationsOrderedRequests)
            .passengerTravelTimes(this.passengerTravelTimes)
            .passengerDistances(this.passengerDistances)
            .passengerNetworkUtilities(this.passengerNetworkUtilities)
            .delays(this.delays)
            .detours(this.detours)
            .remainingBudgets(this.remainingBudgets)
            .maxCosts(this.maxCosts)
            .connectionTravelTimes(this.connectionTravelTimes)
            .connectionDistances(this.connectionDistances)
            .connectionNetworkUtilities(this.connectionNetworkUtilities)
            .startTime(this.startTime)
            .shapleyValues(this.shapleyValues)
            .reposTimeMeanOutgoing(this.reposTimeMeanOutgoing)
            .variant(this.variant)
            .pickupStop(this.pickupStop)
            .dropoffStop(this.dropoffStop)
            .accessWalkDistances(this.accessWalkDistances)
            .egressWalkDistances(this.egressWalkDistances);
    }

    public static final class Builder {
        private int index;
        private int degree;
        private RideKind kind;
        private DrtRequest[] requests;
        private DrtRequest[] originsOrderedRequests;
        private DrtRequest[] destinationsOrderedRequests;
        private double[] passengerTravelTimes;
        private double[] passengerDistances;
        private double[] passengerNetworkUtilities;
        private double[] delays;
		private double[] detours;
        private double[] remainingBudgets;
        private double[] maxCosts;
        private double[] connectionTravelTimes;
        private double[] connectionDistances;
        private double[] connectionNetworkUtilities;
        private double startTime;
        private double[] shapleyValues;
        private double reposTimeMeanOutgoing = -1.0;  // sentinel: no successors / not computed

        // Stop-based pooling fields
        private RideVariant variant;
        private StopLocation pickupStop;
        private StopLocation dropoffStop;
        private double[] accessWalkDistances;
        private double[] egressWalkDistances;

        private Builder() {}

        public Builder index(int index) {
            this.index = index;
            return this;
        }

        public Builder degree(int degree) {
            this.degree = degree;
            return this;
        }

        public Builder kind(RideKind kind) {
            this.kind = kind;
            return this;
        }

        public Builder requests(DrtRequest[] requests) {
            this.requests = requests;
            return this;
        }

        public Builder originsOrderedRequests(DrtRequest[] originsOrderedRequests) {
            this.originsOrderedRequests = originsOrderedRequests;
            return this;
        }

        public Builder destinationsOrderedRequests(DrtRequest[] destinationsOrderedRequests) {
            this.destinationsOrderedRequests = destinationsOrderedRequests;
            return this;
        }

        public Builder passengerTravelTimes(double[] passengerTravelTimes) {
            this.passengerTravelTimes = passengerTravelTimes;
            return this;
        }

        public Builder passengerDistances(double[] passengerDistances) {
            this.passengerDistances = passengerDistances;
            return this;
        }

        public Builder passengerNetworkUtilities(double[] passengerNetworkUtilities) {
            this.passengerNetworkUtilities = passengerNetworkUtilities;
            return this;
        }

        public Builder delays(double[] delays) {
            this.delays = delays;
            return this;
        }

		public Builder detours(double[] detours) {
			this.detours = detours;
			return this;
		}

        public Builder remainingBudgets(double[] remainingBudgets) {
            this.remainingBudgets = remainingBudgets;
            return this;
        }

        public Builder connectionTravelTimes(double[] connectionTravelTimes) {
            this.connectionTravelTimes = connectionTravelTimes;
            return this;
        }

        public Builder connectionDistances(double[] connectionDistances) {
            this.connectionDistances = connectionDistances;
            return this;
        }

        public Builder connectionNetworkUtilities(double[] connectionNetworkUtilities) {
            this.connectionNetworkUtilities = connectionNetworkUtilities;
            return this;
        }

        public Builder startTime(double startTime) {
            this.startTime = startTime;
            return this;
        }

        public Builder shapleyValues(double[] shapleyValues) {
            this.shapleyValues = shapleyValues;
            return this;
        }

        public Builder maxCosts(double[] maxCosts) {
            this.maxCosts = maxCosts;
            return this;
        }

        public Builder reposTimeMeanOutgoing(double value) {
            this.reposTimeMeanOutgoing = value;
            return this;
        }

        public Builder variant(RideVariant variant) {
            this.variant = variant;
            return this;
        }

        public Builder pickupStop(StopLocation pickupStop) {
            this.pickupStop = pickupStop;
            return this;
        }

        public Builder dropoffStop(StopLocation dropoffStop) {
            this.dropoffStop = dropoffStop;
            return this;
        }

        public Builder accessWalkDistances(double[] accessWalkDistances) {
            this.accessWalkDistances = accessWalkDistances;
            return this;
        }

        public Builder egressWalkDistances(double[] egressWalkDistances) {
            this.egressWalkDistances = egressWalkDistances;
            return this;
        }

        public Ride build() {
            // Validation
            if (degree < 1) {
                throw new IllegalArgumentException("Degree must be >= 1, got: " + degree);
            }
            if (kind == null) {
                throw new IllegalArgumentException("RideKind cannot be null");
            }
            if (requests == null || requests.length != degree) {
                throw new IllegalArgumentException(
                    String.format("requests length (%d) must equal degree (%d)",
                        requests != null ? requests.length : 0, degree)
                );
            }
            if (originsOrderedRequests == null || originsOrderedRequests.length != degree) {
                throw new IllegalArgumentException(
                    String.format("originsOrderedRequests length must equal degree (%d)", degree)
                );
            }
            if (destinationsOrderedRequests == null || destinationsOrderedRequests.length != degree) {
                throw new IllegalArgumentException(
                    String.format("destinationsOrderedRequests length must equal degree (%d)", degree)
                );
            }
            if (passengerTravelTimes == null || passengerTravelTimes.length != degree) {
                throw new IllegalArgumentException(
                    String.format("passengerTravelTimes length must equal degree (%d)", degree)
                );
            }
            if (delays == null || delays.length != degree) {
                throw new IllegalArgumentException(
                    String.format("delays length must equal degree (%d)", degree)
                );
            }
			if (detours == null || detours.length != degree) {
				throw new IllegalArgumentException(
						String.format("detours length must equal degree (%d)", degree));
			}
            if (connectionTravelTimes == null || connectionTravelTimes.length == 0) {
                throw new IllegalArgumentException("connectionTravelTimes cannot be null or empty");
            }

            return new Ride(this);
        }
    }

    @Override
    public String toString() {
        String requestIds = Arrays.stream(requests)
            .map(r -> String.valueOf(r.index))
            .collect(Collectors.joining(","));
        return String.format("Ride[index=%d, degree=%d, kind=%s, requests=[%s], startTime=%.1f, duration=%.1f]",
            index, degree, kind, requestIds, startTime, rideTravelTime);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Ride other = (Ride) obj;
        return index == other.index;
    }

    @Override
    public int hashCode() {
        return index;
    }
}
