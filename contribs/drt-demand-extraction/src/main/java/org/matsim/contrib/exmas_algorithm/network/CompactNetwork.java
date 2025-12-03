package org.matsim.contrib.exmas_algorithm.network;

import com.exmas.ridesharing.domain.TravelSegment;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import java.util.Arrays;

/**
 * Memory-efficient network implementation using primitive arrays.
 * Uses hash-based index for O(1) lookups.
 *
 * Memory footprint per segment: ~32 bytes (vs 200-400 bytes for object-based approaches)
 * - 4 bytes: origin (int)
 * - 4 bytes: destination (int)
 * - 8 bytes: travel time (double)
 * - 8 bytes: distance (double)
 * - 8 bytes: utility (double)
 * Plus amortized hash table overhead
 *
 * Python reference: network.dict[(origin, dest)] -> {'travel_time': x, 'distance': y, 'network_utility': z}
 */
public final class CompactNetwork implements Network {
    // Parallel arrays for segment data
    private final int[] origins;
    private final int[] destinations;
    private final double[] travelTimes;
    private final double[] distances;
    private final double[] utilities;

    // Hash index: (origin, destination) -> array index
    private final Long2IntOpenHashMap index;

    // Singleton for non-existent segments
    private static final TravelSegment INFINITY_SEGMENT =
        new TravelSegment(-1, -1, Double.POSITIVE_INFINITY,
                         Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);

    private CompactNetwork(Builder builder) {
        int size = builder.size;
        this.origins = builder.origins;
        this.destinations = builder.destinations;
        this.travelTimes = builder.travelTimes;
        this.distances = builder.distances;
        this.utilities = builder.utilities;

        // Build hash index
        this.index = new Long2IntOpenHashMap(size);
        this.index.defaultReturnValue(-1); // Return -1 for missing keys

        for (int i = 0; i < size; i++) {
            long key = packKey(origins[i], destinations[i]);
            index.put(key, i);
        }
    }

    /**
     * Pack two ints into a long for use as hash key.
     * High 32 bits = origin, Low 32 bits = destination
     *
     * @param origin the origin location ID
     * @param destination the destination location ID
     * @return packed long key
     */
    private static long packKey(int origin, int destination) {
        return ((long) origin << 32) | (destination & 0xFFFFFFFFL);
    }

    @Override
    public TravelSegment getSegment(int origin, int destination) {
        long key = packKey(origin, destination);
        int idx = index.get(key);

        if (idx < 0) {
            return INFINITY_SEGMENT;
        }

        return new TravelSegment(
            origins[idx],
            destinations[idx],
            travelTimes[idx],
            distances[idx],
            utilities[idx]
        );
    }

    @Override
    public boolean hasConnection(int origin, int destination) {
        long key = packKey(origin, destination);
        return index.containsKey(key);
    }

    @Override
    public int size() {
        return origins.length;
    }

    /**
     * Create a builder for constructing a CompactNetwork.
     *
     * @param estimatedSize estimated number of segments (for initial capacity)
     * @return new builder instance
     */
    public static Builder builder(int estimatedSize) {
        return new Builder(estimatedSize);
    }

    public static final class Builder {
        private int size;
        private int capacity;
        private int[] origins;
        private int[] destinations;
        private double[] travelTimes;
        private double[] distances;
        private double[] utilities;

        Builder(int initialCapacity) {
            if (initialCapacity <= 0) {
                throw new IllegalArgumentException("Initial capacity must be positive: " + initialCapacity);
            }
            this.capacity = initialCapacity;
            this.size = 0;
            this.origins = new int[capacity];
            this.destinations = new int[capacity];
            this.travelTimes = new double[capacity];
            this.distances = new double[capacity];
            this.utilities = new double[capacity];
        }

        /**
         * Add a network segment.
         *
         * @param origin the origin location ID
         * @param destination the destination location ID
         * @param travelTime travel time for this segment
         * @param distance distance for this segment
         * @param utility network utility for this segment
         * @return this builder for chaining
         */
        public Builder addSegment(int origin, int destination,
                                 double travelTime, double distance, double utility) {
            ensureCapacity(size + 1);

            origins[size] = origin;
            destinations[size] = destination;
            travelTimes[size] = travelTime;
            distances[size] = distance;
            utilities[size] = utility;
            size++;

            return this;
        }

        private void ensureCapacity(int required) {
            if (required > capacity) {
                int newCapacity = Math.max(required, capacity * 2);
                origins = Arrays.copyOf(origins, newCapacity);
                destinations = Arrays.copyOf(destinations, newCapacity);
                travelTimes = Arrays.copyOf(travelTimes, newCapacity);
                distances = Arrays.copyOf(distances, newCapacity);
                utilities = Arrays.copyOf(utilities, newCapacity);
                capacity = newCapacity;
            }
        }

        /**
         * Build the CompactNetwork instance.
         *
         * @return new CompactNetwork with all added segments
         */
        public CompactNetwork build() {
            // Trim arrays to exact size
            origins = Arrays.copyOf(origins, size);
            destinations = Arrays.copyOf(destinations, size);
            travelTimes = Arrays.copyOf(travelTimes, size);
            distances = Arrays.copyOf(distances, size);
            utilities = Arrays.copyOf(utilities, size);

            return new CompactNetwork(this);
        }
    }
}
