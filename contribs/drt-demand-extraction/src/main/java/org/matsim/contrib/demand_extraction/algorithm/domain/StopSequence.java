package org.matsim.contrib.demand_extraction.algorithm.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable representation of an ordered sequence of stops for a hyper-pooled ride.
 *
 * <p>In HyperPool Stage 2, multiple stop-to-stop rides are bundled together into a single
 * vehicle route that visits a sequence of pickup and dropoff stops. This class represents
 * that ordered stop sequence along with mappings from passenger indices to their boarding
 * and alighting positions within the sequence.
 *
 * <p>Key concepts:
 * <ul>
 *   <li><b>Stop sequence:</b> Ordered list of {@link StopLocation} objects representing
 *       the vehicle's route through pickup and dropoff points</li>
 *   <li><b>Boarding index:</b> The position in the sequence where a passenger boards</li>
 *   <li><b>Alighting index:</b> The position in the sequence where a passenger alights</li>
 *   <li><b>Passenger index:</b> Zero-based identifier for each passenger in the ride</li>
 * </ul>
 *
 * <p>Invariants:
 * <ul>
 *   <li>The stop sequence must contain at least 2 stops (minimum: one pickup, one dropoff)</li>
 *   <li>For each passenger, boarding index must be strictly less than alighting index</li>
 *   <li>All indices must be within bounds of the stop sequence</li>
 *   <li>Each passenger must have exactly one boarding and one alighting stop</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * StopSequence sequence = StopSequence.builder()
 *     .addStop(pickupStopA)
 *     .addStop(pickupStopB)
 *     .addStop(dropoffStopA)
 *     .addStop(dropoffStopB)
 *     .setPassengerBoarding(0, 0)  // Passenger 0 boards at stop index 0
 *     .setPassengerAlighting(0, 2) // Passenger 0 alights at stop index 2
 *     .setPassengerBoarding(1, 1)  // Passenger 1 boards at stop index 1
 *     .setPassengerAlighting(1, 3) // Passenger 1 alights at stop index 3
 *     .build();
 * }</pre>
 *
 * @see StopLocation
 * @see HyperPooledRide
 */
public final class StopSequence {

    private final List<StopLocation> stops;
    private final Map<Integer, Integer> boardingIndices;
    private final Map<Integer, Integer> alightingIndices;
    private final int passengerCount;

    /**
     * Private constructor - use {@link Builder} to create instances.
     */
    private StopSequence(Builder builder) {
        this.stops = Collections.unmodifiableList(new ArrayList<>(builder.stops));
        this.boardingIndices = Collections.unmodifiableMap(new HashMap<>(builder.boardingIndices));
        this.alightingIndices = Collections.unmodifiableMap(new HashMap<>(builder.alightingIndices));
        this.passengerCount = builder.boardingIndices.size();
    }

    // ==================== Getters ====================

    /**
     * Returns an unmodifiable view of the stop sequence.
     * The sequence is ordered as the vehicle visits the stops.
     *
     * @return unmodifiable list of stops in visitation order
     */
    public List<StopLocation> getStops() {
        return stops;
    }

    /**
     * Returns the stop at the given index in the sequence.
     *
     * @param index the zero-based index in the sequence
     * @return the stop at the given index
     * @throws IndexOutOfBoundsException if index is out of range
     */
    public StopLocation getStop(int index) {
        return stops.get(index);
    }

    /**
     * Returns the number of stops in the sequence.
     *
     * @return the stop count
     */
    public int getStopCount() {
        return stops.size();
    }

    /**
     * Returns the total number of passengers served by this stop sequence.
     *
     * @return the passenger count
     */
    public int getTotalPassengers() {
        return passengerCount;
    }

    /**
     * Returns an unmodifiable view of the boarding index map.
     * Keys are passenger indices, values are stop sequence indices.
     *
     * @return unmodifiable map from passenger index to boarding stop index
     */
    public Map<Integer, Integer> getBoardingIndices() {
        return boardingIndices;
    }

    /**
     * Returns the boarding stop index for the given passenger.
     *
     * @param passengerIndex the zero-based passenger index
     * @return the stop sequence index where this passenger boards
     * @throws IllegalArgumentException if passenger index is not found
     */
    public int getBoardingIndex(int passengerIndex) {
        Integer index = boardingIndices.get(passengerIndex);
        if (index == null) {
            throw new IllegalArgumentException("Unknown passenger index: " + passengerIndex);
        }
        return index;
    }

    /**
     * Returns the boarding stop for the given passenger.
     *
     * @param passengerIndex the zero-based passenger index
     * @return the stop where this passenger boards
     * @throws IllegalArgumentException if passenger index is not found
     */
    public StopLocation getBoardingStop(int passengerIndex) {
        return stops.get(getBoardingIndex(passengerIndex));
    }

    /**
     * Returns an unmodifiable view of the alighting index map.
     * Keys are passenger indices, values are stop sequence indices.
     *
     * @return unmodifiable map from passenger index to alighting stop index
     */
    public Map<Integer, Integer> getAlightingIndices() {
        return alightingIndices;
    }

    /**
     * Returns the alighting stop index for the given passenger.
     *
     * @param passengerIndex the zero-based passenger index
     * @return the stop sequence index where this passenger alights
     * @throws IllegalArgumentException if passenger index is not found
     */
    public int getAlightingIndex(int passengerIndex) {
        Integer index = alightingIndices.get(passengerIndex);
        if (index == null) {
            throw new IllegalArgumentException("Unknown passenger index: " + passengerIndex);
        }
        return index;
    }

    /**
     * Returns the alighting stop for the given passenger.
     *
     * @param passengerIndex the zero-based passenger index
     * @return the stop where this passenger alights
     * @throws IllegalArgumentException if passenger index is not found
     */
    public StopLocation getAlightingStop(int passengerIndex) {
        return stops.get(getAlightingIndex(passengerIndex));
    }

    /**
     * Returns the stops between boarding and alighting (inclusive) for a passenger.
     *
     * <p>This represents all the stops a passenger experiences while on the vehicle,
     * including their boarding stop and alighting stop.
     *
     * @param passengerIndex the zero-based passenger index
     * @return unmodifiable list of stops from boarding to alighting (inclusive)
     * @throws IllegalArgumentException if passenger index is not found
     */
    public List<StopLocation> getStopsForPassenger(int passengerIndex) {
        int boardingIdx = getBoardingIndex(passengerIndex);
        int alightingIdx = getAlightingIndex(passengerIndex);
        return Collections.unmodifiableList(stops.subList(boardingIdx, alightingIdx + 1));
    }

    /**
     * Returns the number of stops between boarding and alighting (inclusive) for a passenger.
     *
     * @param passengerIndex the zero-based passenger index
     * @return the number of stops experienced by this passenger
     * @throws IllegalArgumentException if passenger index is not found
     */
    public int getStopCountForPassenger(int passengerIndex) {
        int boardingIdx = getBoardingIndex(passengerIndex);
        int alightingIdx = getAlightingIndex(passengerIndex);
        return alightingIdx - boardingIdx + 1;
    }

    /**
     * Checks if a passenger index is valid (has boarding and alighting defined).
     *
     * @param passengerIndex the passenger index to check
     * @return true if the passenger index is valid
     */
    public boolean hasPassenger(int passengerIndex) {
        return boardingIndices.containsKey(passengerIndex);
    }

    // ==================== Builder ====================

    /**
     * Creates a new Builder for constructing StopSequence instances.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for creating {@link StopSequence} instances.
     *
     * <p>The builder enforces that:
     * <ul>
     *   <li>At least 2 stops are added</li>
     *   <li>Each passenger has both boarding and alighting defined</li>
     *   <li>Boarding index is less than alighting index for each passenger</li>
     *   <li>All indices are within bounds</li>
     * </ul>
     */
    public static final class Builder {
        private final List<StopLocation> stops = new ArrayList<>();
        private final Map<Integer, Integer> boardingIndices = new HashMap<>();
        private final Map<Integer, Integer> alightingIndices = new HashMap<>();

        private Builder() {}

        /**
         * Adds a stop to the end of the sequence.
         *
         * @param stop the stop to add (must not be null)
         * @return this builder for chaining
         * @throws IllegalArgumentException if stop is null
         */
        public Builder addStop(StopLocation stop) {
            if (stop == null) {
                throw new IllegalArgumentException("Stop cannot be null");
            }
            stops.add(stop);
            return this;
        }

        /**
         * Adds multiple stops to the end of the sequence.
         *
         * @param stopsToAdd the stops to add (must not be null or contain null)
         * @return this builder for chaining
         * @throws IllegalArgumentException if stopsToAdd is null or contains null
         */
        public Builder addStops(List<StopLocation> stopsToAdd) {
            if (stopsToAdd == null) {
                throw new IllegalArgumentException("Stops list cannot be null");
            }
            for (StopLocation stop : stopsToAdd) {
                addStop(stop);
            }
            return this;
        }

        /**
         * Adds multiple stops from an array to the end of the sequence.
         *
         * @param stopsToAdd the stops to add (must not be null or contain null)
         * @return this builder for chaining
         * @throws IllegalArgumentException if stopsToAdd is null or contains null
         */
        public Builder addStops(StopLocation[] stopsToAdd) {
            if (stopsToAdd == null) {
                throw new IllegalArgumentException("Stops array cannot be null");
            }
            for (StopLocation stop : stopsToAdd) {
                addStop(stop);
            }
            return this;
        }

        /**
         * Sets the boarding stop index for a passenger.
         *
         * @param passengerIndex the zero-based passenger index
         * @param stopIndex the stop sequence index where this passenger boards
         * @return this builder for chaining
         * @throws IllegalArgumentException if passengerIndex is negative
         */
        public Builder setPassengerBoarding(int passengerIndex, int stopIndex) {
            if (passengerIndex < 0) {
                throw new IllegalArgumentException("Passenger index cannot be negative: " + passengerIndex);
            }
            boardingIndices.put(passengerIndex, stopIndex);
            return this;
        }

        /**
         * Sets the alighting stop index for a passenger.
         *
         * @param passengerIndex the zero-based passenger index
         * @param stopIndex the stop sequence index where this passenger alights
         * @return this builder for chaining
         * @throws IllegalArgumentException if passengerIndex is negative
         */
        public Builder setPassengerAlighting(int passengerIndex, int stopIndex) {
            if (passengerIndex < 0) {
                throw new IllegalArgumentException("Passenger index cannot be negative: " + passengerIndex);
            }
            alightingIndices.put(passengerIndex, stopIndex);
            return this;
        }

        /**
         * Sets both boarding and alighting stop indices for a passenger.
         *
         * @param passengerIndex the zero-based passenger index
         * @param boardingStopIndex the stop sequence index where this passenger boards
         * @param alightingStopIndex the stop sequence index where this passenger alights
         * @return this builder for chaining
         * @throws IllegalArgumentException if passengerIndex is negative
         */
        public Builder setPassengerStops(int passengerIndex, int boardingStopIndex, int alightingStopIndex) {
            setPassengerBoarding(passengerIndex, boardingStopIndex);
            setPassengerAlighting(passengerIndex, alightingStopIndex);
            return this;
        }

        /**
         * Builds the StopSequence after validating all constraints.
         *
         * @return a new immutable StopSequence instance
         * @throws IllegalStateException if validation fails
         */
        public StopSequence build() {
            // Validate minimum stops
            if (stops.size() < 2) {
                throw new IllegalStateException(
                    String.format("Stop sequence must have at least 2 stops, got: %d", stops.size())
                );
            }

            // Validate passenger mappings are complete
            if (!boardingIndices.keySet().equals(alightingIndices.keySet())) {
                throw new IllegalStateException(
                    "Boarding and alighting indices must be defined for the same passengers. " +
                    "Boarding defined for: " + boardingIndices.keySet() +
                    ", Alighting defined for: " + alightingIndices.keySet()
                );
            }

            if (boardingIndices.isEmpty()) {
                throw new IllegalStateException("At least one passenger must be defined");
            }

            // Validate each passenger's indices
            int stopCount = stops.size();
            for (Integer passengerIndex : boardingIndices.keySet()) {
                int boardingIdx = boardingIndices.get(passengerIndex);
                int alightingIdx = alightingIndices.get(passengerIndex);

                // Check bounds
                if (boardingIdx < 0 || boardingIdx >= stopCount) {
                    throw new IllegalStateException(
                        String.format("Boarding index %d for passenger %d is out of bounds [0, %d)",
                            boardingIdx, passengerIndex, stopCount)
                    );
                }
                if (alightingIdx < 0 || alightingIdx >= stopCount) {
                    throw new IllegalStateException(
                        String.format("Alighting index %d for passenger %d is out of bounds [0, %d)",
                            alightingIdx, passengerIndex, stopCount)
                    );
                }

                // Check ordering
                if (boardingIdx >= alightingIdx) {
                    throw new IllegalStateException(
                        String.format("Boarding index (%d) must be less than alighting index (%d) for passenger %d",
                            boardingIdx, alightingIdx, passengerIndex)
                    );
                }
            }

            return new StopSequence(this);
        }
    }

    // ==================== Object methods ====================

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("StopSequence[stops=").append(stops.size());
        sb.append(", passengers=").append(passengerCount);
        sb.append(", route=");
        for (int i = 0; i < stops.size(); i++) {
            if (i > 0) sb.append("->");
            sb.append(stops.get(i).getLinkId());
        }
        sb.append("]");
        return sb.toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        StopSequence other = (StopSequence) obj;
        return Objects.equals(stops, other.stops) &&
               Objects.equals(boardingIndices, other.boardingIndices) &&
               Objects.equals(alightingIndices, other.alightingIndices);
    }

    @Override
    public int hashCode() {
        return Objects.hash(stops, boardingIndices, alightingIndices);
    }
}
