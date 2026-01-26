package org.matsim.contrib.demand_extraction.algorithm.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.matsim.contrib.demand_extraction.demand.DrtRequest;

/**
 * Immutable representation of a hyper-pooled ride from HyperPool Stage 2.
 *
 * In the HyperPool algorithm, Stage 2 bundles multiple stop-to-stop rides together
 * where passengers walk to/from designated stop locations rather than being picked
 * up at their exact origin/destination. This class represents such a bundled ride
 * with a sequence of pickup and dropoff stops.
 *
 * Key differences from regular {@link Ride}:
 * <ul>
 *   <li>Uses stop locations instead of exact origins/destinations</li>
 *   <li>Tracks walk distances (access/egress) for each passenger</li>
 *   <li>Stop sequence represents the ordered vehicle route through stops</li>
 *   <li>Boarding/alighting indices map each passenger to their stops in the sequence</li>
 * </ul>
 *
 * The stop sequence is ordered as the vehicle visits them: typically all pickups first,
 * then all dropoffs, but the algorithm may produce interleaved sequences.
 *
 * @see StopLocation
 * @see Ride
 */
public final class HyperPooledRide {

    // Core identification
    private final int index;

    // Stop sequence (ordered list of stops the vehicle visits)
    private final StopLocation[] stopSequence;

    // Per-passenger information (arrays indexed by passenger position)
    private final DrtRequest[] requests;
    private final int[] boardingStopIndices;
    private final int[] alightingStopIndices;
    private final double[] accessWalkDistances;
    private final double[] egressWalkDistances;
    private final double[] inVehicleTimes;
    private final double[] remainingBudgets;

    // Aggregated metrics
    private final double totalRideTime;
    private final double totalRideDistance;
    private final double startTime;
    private final double endTime;

    // HyperPool Stage 2 extensions
    private final List<Ride> sourceRides;
    private final StopSequence orderedStopSequence;
    private final double[] passengerTotalWalkDistances;
    private final int[] passengerBoardingStopIndices;
    private final int[] passengerAlightingStopIndices;
    private final double[] passengerInVehicleTimes;

    /**
     * Private constructor - use {@link Builder} to create instances.
     */
    private HyperPooledRide(Builder builder) {
        this.index = builder.index;

        // Copy arrays (defensive)
        this.stopSequence = builder.stopSequence.clone();
        this.requests = builder.requests.clone();
        this.boardingStopIndices = builder.boardingStopIndices.clone();
        this.alightingStopIndices = builder.alightingStopIndices.clone();
        this.accessWalkDistances = builder.accessWalkDistances.clone();
        this.egressWalkDistances = builder.egressWalkDistances.clone();
        this.inVehicleTimes = builder.inVehicleTimes.clone();
        this.remainingBudgets = builder.remainingBudgets.clone();

        // Aggregated metrics
        this.totalRideTime = builder.totalRideTime;
        this.totalRideDistance = builder.totalRideDistance;
        this.startTime = builder.startTime;
        this.endTime = builder.endTime;

        // HyperPool Stage 2 extensions
        this.sourceRides = builder.sourceRides != null
            ? Collections.unmodifiableList(new ArrayList<>(builder.sourceRides))
            : Collections.emptyList();
        this.orderedStopSequence = builder.orderedStopSequence;
        this.passengerTotalWalkDistances = builder.passengerTotalWalkDistances != null
            ? builder.passengerTotalWalkDistances.clone()
            : computeTotalWalkDistances(builder.accessWalkDistances, builder.egressWalkDistances);
        this.passengerBoardingStopIndices = builder.passengerBoardingStopIndices != null
            ? builder.passengerBoardingStopIndices.clone()
            : builder.boardingStopIndices.clone();
        this.passengerAlightingStopIndices = builder.passengerAlightingStopIndices != null
            ? builder.passengerAlightingStopIndices.clone()
            : builder.alightingStopIndices.clone();
        this.passengerInVehicleTimes = builder.passengerInVehicleTimes != null
            ? builder.passengerInVehicleTimes.clone()
            : builder.inVehicleTimes.clone();
    }

    /**
     * Helper to compute total walk distances from access and egress arrays.
     */
    private static double[] computeTotalWalkDistances(double[] access, double[] egress) {
        if (access == null || egress == null) {
            return new double[0];
        }
        double[] total = new double[access.length];
        for (int i = 0; i < access.length; i++) {
            total[i] = access[i] + egress[i];
        }
        return total;
    }

    // ==================== Getters ====================

    /**
     * Returns the unique ride index.
     */
    public int getIndex() {
        return index;
    }

    /**
     * Returns the number of passengers in this hyper-pooled ride.
     */
    public int getDegree() {
        return requests.length;
    }

    /**
     * Returns a defensive copy of the stop sequence.
     * The sequence is ordered as the vehicle visits the stops.
     */
    public StopLocation[] getStopSequence() {
        return stopSequence.clone();
    }

    /**
     * Returns the stop at the given index in the sequence.
     */
    public StopLocation getStop(int sequenceIndex) {
        return stopSequence[sequenceIndex];
    }

    /**
     * Returns the number of stops in the sequence.
     */
    public int getStopCount() {
        return stopSequence.length;
    }

    /**
     * Returns a defensive copy of the requests array.
     * Requests are in passenger index order.
     */
    public DrtRequest[] getRequests() {
        return requests.clone();
    }

    /**
     * Returns the request for the given passenger index.
     */
    public DrtRequest getRequest(int passengerIndex) {
        return requests[passengerIndex];
    }

    /**
     * Returns a defensive copy of boarding stop indices.
     * Each value is the index into stopSequence where that passenger boards.
     */
    public int[] getBoardingStopIndices() {
        return boardingStopIndices.clone();
    }

    /**
     * Returns the boarding stop index for the given passenger.
     */
    public int getBoardingStopIndex(int passengerIndex) {
        return boardingStopIndices[passengerIndex];
    }

    /**
     * Returns the boarding stop for the given passenger.
     */
    public StopLocation getBoardingStop(int passengerIndex) {
        return stopSequence[boardingStopIndices[passengerIndex]];
    }

    /**
     * Returns a defensive copy of alighting stop indices.
     * Each value is the index into stopSequence where that passenger alights.
     */
    public int[] getAlightingStopIndices() {
        return alightingStopIndices.clone();
    }

    /**
     * Returns the alighting stop index for the given passenger.
     */
    public int getAlightingStopIndex(int passengerIndex) {
        return alightingStopIndices[passengerIndex];
    }

    /**
     * Returns the alighting stop for the given passenger.
     */
    public StopLocation getAlightingStop(int passengerIndex) {
        return stopSequence[alightingStopIndices[passengerIndex]];
    }

    /**
     * Returns a defensive copy of access walk distances.
     * Each value is the walk distance from origin to boarding stop (meters).
     */
    public double[] getAccessWalkDistances() {
        return accessWalkDistances.clone();
    }

    /**
     * Returns the access walk distance for the given passenger.
     */
    public double getAccessWalkDistance(int passengerIndex) {
        return accessWalkDistances[passengerIndex];
    }

    /**
     * Returns a defensive copy of egress walk distances.
     * Each value is the walk distance from alighting stop to destination (meters).
     */
    public double[] getEgressWalkDistances() {
        return egressWalkDistances.clone();
    }

    /**
     * Returns the egress walk distance for the given passenger.
     */
    public double getEgressWalkDistance(int passengerIndex) {
        return egressWalkDistances[passengerIndex];
    }

    /**
     * Returns a defensive copy of in-vehicle times.
     * Each value is the time spent in vehicle from boarding to alighting (seconds).
     */
    public double[] getInVehicleTimes() {
        return inVehicleTimes.clone();
    }

    /**
     * Returns the in-vehicle time for the given passenger.
     */
    public double getInVehicleTime(int passengerIndex) {
        return inVehicleTimes[passengerIndex];
    }

    /**
     * Returns a defensive copy of remaining budgets.
     * Each value is the budget remaining after hyper-pooling (validated against base score).
     */
    public double[] getRemainingBudgets() {
        return remainingBudgets.clone();
    }

    /**
     * Returns the remaining budget for the given passenger.
     */
    public double getRemainingBudget(int passengerIndex) {
        return remainingBudgets[passengerIndex];
    }

    /**
     * Returns the total ride time from first pickup to last dropoff (seconds).
     */
    public double getTotalRideTime() {
        return totalRideTime;
    }

    /**
     * Returns the total ride distance traveled (meters).
     */
    public double getTotalRideDistance() {
        return totalRideDistance;
    }

    /**
     * Returns the start time at first pickup (seconds).
     */
    public double getStartTime() {
        return startTime;
    }

    /**
     * Returns the end time at last dropoff (seconds).
     */
    public double getEndTime() {
        return endTime;
    }

    // ==================== HyperPool Stage 2 Extension Getters ====================

    /**
     * Returns the source rides that were bundled into this hyper-pooled ride.
     * These are the original stop-to-stop rides from Stage 1.
     *
     * @return an unmodifiable list of source rides
     */
    public List<Ride> getSourceRides() {
        return sourceRides;
    }

    /**
     * Returns the ordered stop sequence for this hyper-pooled ride.
     * The StopSequence provides the vehicle's route through all pickup and dropoff stops.
     *
     * @return the stop sequence, or null if not set
     */
    public StopSequence getOrderedStopSequence() {
        return orderedStopSequence;
    }

    /**
     * Returns a defensive copy of total walk distances per passenger.
     * Each value is the sum of access walk distance (origin to pickup stop) and
     * egress walk distance (dropoff stop to destination) in meters.
     */
    public double[] getPassengerTotalWalkDistances() {
        return passengerTotalWalkDistances.clone();
    }

    /**
     * Returns the total walk distance for the given passenger.
     */
    public double getPassengerTotalWalkDistance(int passengerIndex) {
        return passengerTotalWalkDistances[passengerIndex];
    }

    /**
     * Returns a defensive copy of in-vehicle times per passenger.
     * Each value is the time spent in the vehicle from boarding to alighting (seconds).
     */
    public double[] getPassengerInVehicleTimes() {
        return passengerInVehicleTimes.clone();
    }

    /**
     * Returns the in-vehicle time for the given passenger (seconds).
     */
    public double getPassengerInVehicleTime(int passengerIndex) {
        return passengerInVehicleTimes[passengerIndex];
    }

    /**
     * Returns a defensive copy of boarding stop indices per passenger.
     * Each value is the index into the stop sequence where that passenger boards.
     */
    public int[] getPassengerBoardingStopIndices() {
        return passengerBoardingStopIndices.clone();
    }

    /**
     * Returns the boarding stop index for the given passenger.
     */
    public int getPassengerBoardingStopIndex(int passengerIndex) {
        return passengerBoardingStopIndices[passengerIndex];
    }

    /**
     * Returns a defensive copy of alighting stop indices per passenger.
     * Each value is the index into the stop sequence where that passenger alights.
     */
    public int[] getPassengerAlightingStopIndices() {
        return passengerAlightingStopIndices.clone();
    }

    /**
     * Returns the alighting stop index for the given passenger.
     */
    public int getPassengerAlightingStopIndex(int passengerIndex) {
        return passengerAlightingStopIndices[passengerIndex];
    }

    /**
     * Returns the total number of passengers across all source rides.
     * This sums up the degree (passenger count) of each source ride that was bundled.
     *
     * @return total passenger count, or the degree if no source rides are set
     */
    public int getTotalPassengerCount() {
        if (sourceRides.isEmpty()) {
            return getDegree();
        }
        int total = 0;
        for (Ride ride : sourceRides) {
            total += ride.getDegree();
        }
        return total;
    }

    /**
     * Returns the total vehicle kilometers for this hyper-pooled ride.
     * This is the total distance traveled by the vehicle, converted from meters to kilometers.
     *
     * @return total VKT (vehicle kilometers traveled)
     */
    public double getTotalVehicleKilometers() {
        return totalRideDistance / 1000.0;
    }

    // ==================== Builder ====================

    /**
     * Creates a new Builder for constructing HyperPooledRide instances.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a new Builder pre-populated with all values from this ride.
     * Use this to create modified copies without manually copying all fields.
     */
    public Builder toBuilder() {
        return new Builder()
            .index(this.index)
            .stopSequence(this.stopSequence)
            .requests(this.requests)
            .boardingStopIndices(this.boardingStopIndices)
            .alightingStopIndices(this.alightingStopIndices)
            .accessWalkDistances(this.accessWalkDistances)
            .egressWalkDistances(this.egressWalkDistances)
            .inVehicleTimes(this.inVehicleTimes)
            .remainingBudgets(this.remainingBudgets)
            .totalRideTime(this.totalRideTime)
            .totalRideDistance(this.totalRideDistance)
            .startTime(this.startTime)
            .endTime(this.endTime)
            // HyperPool Stage 2 extensions
            .sourceRides(new ArrayList<>(this.sourceRides))
            .orderedStopSequence(this.orderedStopSequence)
            .passengerTotalWalkDistances(this.passengerTotalWalkDistances)
            .passengerBoardingStopIndices(this.passengerBoardingStopIndices)
            .passengerAlightingStopIndices(this.passengerAlightingStopIndices)
            .passengerInVehicleTimes(this.passengerInVehicleTimes);
    }

    /**
     * Builder for creating {@link HyperPooledRide} instances.
     */
    public static final class Builder {
        private int index;
        private StopLocation[] stopSequence;
        private DrtRequest[] requests;
        private int[] boardingStopIndices;
        private int[] alightingStopIndices;
        private double[] accessWalkDistances;
        private double[] egressWalkDistances;
        private double[] inVehicleTimes;
        private double[] remainingBudgets;
        private double totalRideTime;
        private double totalRideDistance;
        private double startTime;
        private double endTime;

        // HyperPool Stage 2 extension fields
        private List<Ride> sourceRides;
        private StopSequence orderedStopSequence;
        private double[] passengerTotalWalkDistances;
        private int[] passengerBoardingStopIndices;
        private int[] passengerAlightingStopIndices;
        private double[] passengerInVehicleTimes;

        private Builder() {}

        public Builder index(int index) {
            this.index = index;
            return this;
        }

        public Builder stopSequence(StopLocation[] stopSequence) {
            this.stopSequence = stopSequence;
            return this;
        }

        public Builder requests(DrtRequest[] requests) {
            this.requests = requests;
            return this;
        }

        public Builder boardingStopIndices(int[] boardingStopIndices) {
            this.boardingStopIndices = boardingStopIndices;
            return this;
        }

        public Builder alightingStopIndices(int[] alightingStopIndices) {
            this.alightingStopIndices = alightingStopIndices;
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

        public Builder inVehicleTimes(double[] inVehicleTimes) {
            this.inVehicleTimes = inVehicleTimes;
            return this;
        }

        public Builder remainingBudgets(double[] remainingBudgets) {
            this.remainingBudgets = remainingBudgets;
            return this;
        }

        public Builder totalRideTime(double totalRideTime) {
            this.totalRideTime = totalRideTime;
            return this;
        }

        public Builder totalRideDistance(double totalRideDistance) {
            this.totalRideDistance = totalRideDistance;
            return this;
        }

        public Builder startTime(double startTime) {
            this.startTime = startTime;
            return this;
        }

        public Builder endTime(double endTime) {
            this.endTime = endTime;
            return this;
        }

        // HyperPool Stage 2 extension setters

        public Builder sourceRides(List<Ride> sourceRides) {
            this.sourceRides = sourceRides;
            return this;
        }

        public Builder orderedStopSequence(StopSequence orderedStopSequence) {
            this.orderedStopSequence = orderedStopSequence;
            return this;
        }

        public Builder passengerTotalWalkDistances(double[] passengerTotalWalkDistances) {
            this.passengerTotalWalkDistances = passengerTotalWalkDistances;
            return this;
        }

        public Builder passengerBoardingStopIndices(int[] passengerBoardingStopIndices) {
            this.passengerBoardingStopIndices = passengerBoardingStopIndices;
            return this;
        }

        public Builder passengerAlightingStopIndices(int[] passengerAlightingStopIndices) {
            this.passengerAlightingStopIndices = passengerAlightingStopIndices;
            return this;
        }

        public Builder passengerInVehicleTimes(double[] passengerInVehicleTimes) {
            this.passengerInVehicleTimes = passengerInVehicleTimes;
            return this;
        }

        /**
         * Builds the HyperPooledRide after validating all required fields.
         *
         * @return a new immutable HyperPooledRide instance
         * @throws IllegalArgumentException if validation fails
         */
        public HyperPooledRide build() {
            // Validate required fields
            if (stopSequence == null || stopSequence.length == 0) {
                throw new IllegalArgumentException("stopSequence cannot be null or empty");
            }
            if (requests == null || requests.length == 0) {
                throw new IllegalArgumentException("requests cannot be null or empty");
            }

            int degree = requests.length;

            if (boardingStopIndices == null || boardingStopIndices.length != degree) {
                throw new IllegalArgumentException(
                    String.format("boardingStopIndices length (%d) must equal degree (%d)",
                        boardingStopIndices != null ? boardingStopIndices.length : 0, degree)
                );
            }
            if (alightingStopIndices == null || alightingStopIndices.length != degree) {
                throw new IllegalArgumentException(
                    String.format("alightingStopIndices length (%d) must equal degree (%d)",
                        alightingStopIndices != null ? alightingStopIndices.length : 0, degree)
                );
            }
            if (accessWalkDistances == null || accessWalkDistances.length != degree) {
                throw new IllegalArgumentException(
                    String.format("accessWalkDistances length (%d) must equal degree (%d)",
                        accessWalkDistances != null ? accessWalkDistances.length : 0, degree)
                );
            }
            if (egressWalkDistances == null || egressWalkDistances.length != degree) {
                throw new IllegalArgumentException(
                    String.format("egressWalkDistances length (%d) must equal degree (%d)",
                        egressWalkDistances != null ? egressWalkDistances.length : 0, degree)
                );
            }
            if (inVehicleTimes == null || inVehicleTimes.length != degree) {
                throw new IllegalArgumentException(
                    String.format("inVehicleTimes length (%d) must equal degree (%d)",
                        inVehicleTimes != null ? inVehicleTimes.length : 0, degree)
                );
            }
            if (remainingBudgets == null || remainingBudgets.length != degree) {
                throw new IllegalArgumentException(
                    String.format("remainingBudgets length (%d) must equal degree (%d)",
                        remainingBudgets != null ? remainingBudgets.length : 0, degree)
                );
            }

            // Validate stop indices are within bounds
            int stopCount = stopSequence.length;
            for (int i = 0; i < degree; i++) {
                if (boardingStopIndices[i] < 0 || boardingStopIndices[i] >= stopCount) {
                    throw new IllegalArgumentException(
                        String.format("boardingStopIndices[%d] = %d is out of bounds [0, %d)",
                            i, boardingStopIndices[i], stopCount)
                    );
                }
                if (alightingStopIndices[i] < 0 || alightingStopIndices[i] >= stopCount) {
                    throw new IllegalArgumentException(
                        String.format("alightingStopIndices[%d] = %d is out of bounds [0, %d)",
                            i, alightingStopIndices[i], stopCount)
                    );
                }
                if (boardingStopIndices[i] >= alightingStopIndices[i]) {
                    throw new IllegalArgumentException(
                        String.format("boardingStopIndices[%d] (%d) must be less than alightingStopIndices[%d] (%d)",
                            i, boardingStopIndices[i], i, alightingStopIndices[i])
                    );
                }
            }

            // Validate non-negative distances and times
            for (int i = 0; i < degree; i++) {
                if (accessWalkDistances[i] < 0) {
                    throw new IllegalArgumentException(
                        String.format("accessWalkDistances[%d] cannot be negative: %.2f", i, accessWalkDistances[i])
                    );
                }
                if (egressWalkDistances[i] < 0) {
                    throw new IllegalArgumentException(
                        String.format("egressWalkDistances[%d] cannot be negative: %.2f", i, egressWalkDistances[i])
                    );
                }
                if (inVehicleTimes[i] < 0) {
                    throw new IllegalArgumentException(
                        String.format("inVehicleTimes[%d] cannot be negative: %.2f", i, inVehicleTimes[i])
                    );
                }
            }

            if (totalRideTime < 0) {
                throw new IllegalArgumentException("totalRideTime cannot be negative: " + totalRideTime);
            }
            if (totalRideDistance < 0) {
                throw new IllegalArgumentException("totalRideDistance cannot be negative: " + totalRideDistance);
            }
            if (endTime < startTime) {
                throw new IllegalArgumentException(
                    String.format("endTime (%.2f) cannot be before startTime (%.2f)", endTime, startTime)
                );
            }

            return new HyperPooledRide(this);
        }
    }

    // ==================== Object methods ====================

    @Override
    public String toString() {
        String requestIds = Arrays.stream(requests)
            .map(r -> String.valueOf(r.index))
            .collect(Collectors.joining(","));
        String stopLinks = Arrays.stream(stopSequence)
            .map(s -> s.getLinkId().toString())
            .collect(Collectors.joining("->"));
        return String.format(
            "HyperPooledRide[index=%d, degree=%d, requests=[%s], stops=[%s], startTime=%.1f, duration=%.1f]",
            index, getDegree(), requestIds, stopLinks, startTime, totalRideTime
        );
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        HyperPooledRide other = (HyperPooledRide) obj;
        return index == other.index;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(index);
    }
}
