package org.matsim.contrib.demand_extraction.algorithm.domain;

import java.util.Arrays;
import java.util.stream.Collectors;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

/**
 * Immutable representation of a shared ride.
 * Corresponds to a row in the Python rides DataFrame.
 *
 * Python reference: src/exmas_commuters/core/exmas/rides.py
 * DataFrame columns: request_index, degree, kind, origins_ordered, destinations_ordered,
 *                    passenger_travel_time, passenger_distance, delay, etc.
 *
 * Uses direct object references to DrtRequest objects for efficient access.
 * Indices are derived from request objects when needed (e.g., for CSV output or graph operations).
 */
public final class Ride {
    private final int index;
    private final int degree;
    private final RideKind kind;

    // Request information (arrays of length = degree)
    private final DrtRequest[] requests;           // Direct references to requests
    private final Id<Link>[] originsOrdered;       // Pickup sequence (Link IDs)
    private final Id<Link>[] destinationsOrdered;  // Dropoff sequence (Link IDs)
    private final DrtRequest[] originsOrderedRequests;      // Requests in pickup order
    private final DrtRequest[] destinationsOrderedRequests; // Requests in dropoff order

    // Travel metrics per passenger (length = degree)
    private final double[] passengerTravelTimes;
    private final double[] passengerDistances;
    private final double[] passengerNetworkUtilities;
    private final double[] delays;
    private final double[] remainingBudgets;  // Budget remaining after scoring (utils)

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
    private final int[] predecessors;
    private final int[] successors;

    // Private constructor - use Builder
    private Ride(Builder builder) {
        this.index = builder.index;
        this.degree = builder.degree;
        this.kind = builder.kind;

        // Copy arrays (defensive)
        this.requests = builder.requests.clone();
        this.originsOrdered = builder.originsOrdered.clone();
        this.destinationsOrdered = builder.destinationsOrdered.clone();
        this.originsOrderedRequests = builder.originsOrderedRequests.clone();
        this.destinationsOrderedRequests = builder.destinationsOrderedRequests.clone();
        this.passengerTravelTimes = builder.passengerTravelTimes.clone();
        this.passengerDistances = builder.passengerDistances.clone();
        this.passengerNetworkUtilities = builder.passengerNetworkUtilities.clone();
        this.delays = builder.delays.clone();
        this.remainingBudgets = builder.remainingBudgets != null ? builder.remainingBudgets.clone() : null;
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
        this.predecessors = builder.predecessors != null ? builder.predecessors.clone() : null;
        this.successors = builder.successors != null ? builder.successors.clone() : null;
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
    public int getDegree() { return degree; }
    public RideKind getKind() { return kind; }

    // Direct request access
    public DrtRequest[] getRequests() { return requests.clone(); }

    public DrtRequest getRequest(int passengerIndex) {
        return requests[passengerIndex];
    }

    // Derived indices for backward compatibility, graph operations, and CSV output
    public int[] getRequestIndices() {
        int[] indices = new int[requests.length];
        for (int i = 0; i < requests.length; i++) {
            indices[i] = requests[i].index;
        }
        return indices;
    }

    public Id<Link>[] getOriginsOrdered() {
        return originsOrdered.clone();
    }

    public Id<Link>[] getDestinationsOrdered() {
        return destinationsOrdered.clone();
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
    public double[] getRemainingBudgets() { return remainingBudgets != null ? remainingBudgets.clone() : null; }
    public double[] getConnectionTravelTimes() { return connectionTravelTimes.clone(); }
    public double[] getConnectionDistances() { return connectionDistances.clone(); }
    public double[] getConnectionNetworkUtilities() { return connectionNetworkUtilities.clone(); }

    public double getRideTravelTime() { return rideTravelTime; }
    public double getRideDistance() { return rideDistance; }
    public double getRideNetworkUtility() { return rideNetworkUtility; }
    public double getStartTime() { return startTime; }
    public double getEndTime() { return endTime; }

    public double[] getShapleyValues() { return shapleyValues != null ? shapleyValues.clone() : null; }
    public int[] getPredecessors() { return predecessors != null ? predecessors.clone() : null; }
    public int[] getSuccessors() { return successors != null ? successors.clone() : null; }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int index;
        private int degree;
        private RideKind kind;
        private DrtRequest[] requests;
        private Id<Link>[] originsOrdered;
        private Id<Link>[] destinationsOrdered;
        private DrtRequest[] originsOrderedRequests;
        private DrtRequest[] destinationsOrderedRequests;
        private double[] passengerTravelTimes;
        private double[] passengerDistances;
        private double[] passengerNetworkUtilities;
        private double[] delays;
        private double[] remainingBudgets;
        private double[] connectionTravelTimes;
        private double[] connectionDistances;
        private double[] connectionNetworkUtilities;
        private double startTime;
        private double[] shapleyValues;
        private int[] predecessors;
        private int[] successors;

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

        public Builder originsOrdered(Id<Link>[] originsOrdered) {
            this.originsOrdered = originsOrdered;
            return this;
        }

        public Builder destinationsOrdered(Id<Link>[] destinationsOrdered) {
            this.destinationsOrdered = destinationsOrdered;
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

        public Builder predecessors(int[] predecessors) {
            this.predecessors = predecessors;
            return this;
        }

        public Builder successors(int[] successors) {
            this.successors = successors;
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
            if (originsOrdered == null || originsOrdered.length != degree) {
                throw new IllegalArgumentException(
                    String.format("originsOrdered length must equal degree (%d)", degree)
                );
            }
            if (destinationsOrdered == null || destinationsOrdered.length != degree) {
                throw new IllegalArgumentException(
                    String.format("destinationsOrdered length must equal degree (%d)", degree)
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
