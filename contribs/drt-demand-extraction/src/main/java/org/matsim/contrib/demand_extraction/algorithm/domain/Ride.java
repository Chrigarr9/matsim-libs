package org.matsim.contrib.demand_extraction.algorithm.domain;

import java.util.Arrays;

/**
 * Immutable representation of a shared ride.
 * Corresponds to a row in the Python rides DataFrame.
 *
 * Python reference: src/exmas_commuters/core/exmas/rides.py
 * DataFrame columns: request_index, degree, kind, origins_ordered, destinations_ordered,
 *                    passenger_travel_time, passenger_distance, delay, etc.
 */
public final class Ride {
    private final int index;
    private final int degree;
    private final RideKind kind;

    // Request information (arrays of length = degree)
    private final int[] requestIndices;
    private final int[] originsOrdered;      // Pickup sequence (physical locations)
    private final int[] destinationsOrdered; // Dropoff sequence (physical locations)
    private final int[] originsIndex;        // Request indices in pickup order (matches requestIndices for FIFO)
    private final int[] destinationsIndex;   // Request indices in dropoff order (reversed for LIFO, reordered for MIXED)

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
        this.requestIndices = builder.requestIndices.clone();
        this.originsOrdered = builder.originsOrdered.clone();
        this.destinationsOrdered = builder.destinationsOrdered.clone();
        this.originsIndex = builder.originsIndex.clone();
        this.destinationsIndex = builder.destinationsIndex.clone();
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

    // Array getters return defensive copies to maintain immutability
    public int[] getRequestIndices() { return requestIndices.clone(); }
    public int[] getOriginsOrdered() { return originsOrdered.clone(); }
    public int[] getDestinationsOrdered() { return destinationsOrdered.clone(); }
    public int[] getOriginsIndex() { return originsIndex.clone(); }
    public int[] getDestinationsIndex() { return destinationsIndex.clone(); }
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
        private int[] requestIndices;
        private int[] originsOrdered;
        private int[] destinationsOrdered;
        private int[] originsIndex;
        private int[] destinationsIndex;
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

        public Builder requestIndices(int[] requestIndices) {
            this.requestIndices = requestIndices;
            return this;
        }

        public Builder originsOrdered(int[] originsOrdered) {
            this.originsOrdered = originsOrdered;
            return this;
        }

        public Builder destinationsOrdered(int[] destinationsOrdered) {
            this.destinationsOrdered = destinationsOrdered;
            return this;
        }

        public Builder originsIndex(int[] originsIndex) {
            this.originsIndex = originsIndex;
            return this;
        }

        public Builder destinationsIndex(int[] destinationsIndex) {
            this.destinationsIndex = destinationsIndex;
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
            if (requestIndices == null || requestIndices.length != degree) {
                throw new IllegalArgumentException(
                    String.format("requestIndices length (%d) must equal degree (%d)",
                        requestIndices != null ? requestIndices.length : 0, degree)
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
        return String.format("Ride[index=%d, degree=%d, kind=%s, requests=%s, startTime=%.1f, duration=%.1f]",
            index, degree, kind, Arrays.toString(requestIndices), startTime, rideTravelTime);
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
