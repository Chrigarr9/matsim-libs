package org.matsim.contrib.demand_extraction.algorithm.bamas.stub;

import java.util.Arrays;

import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideKind;

/**
 * Compact value carrier for a winning shared ride, suitable for buffering in
 * per-worker stub storage before final export.
 *
 * <h3>Global-to-local index conversion (the critical trap)</h3>
 * {@link Ride#getOriginsIndex()} and {@link Ride#getDestinationsIndex()} return
 * <em>global</em> request indices (0..~109k at 100% sample fraction).
 * {@link OrderingCodec} packs values as 4-bit nibbles, so any value ≥ 16 would be
 * silently truncated by {@code & 0xF} if packed directly.
 *
 * <p>The conversion is: for each global index {@code g} in the ordering,
 * find its position {@code local = Arrays.binarySearch(sortedSet, g)} in the
 * sorted request set (0-based, 0..degree-1), then pack the local positions.
 * Reconstruction is the inverse: {@code sortedSet[local_i]} for each unpacked
 * local.
 *
 * <h3>Distance and travel time</h3>
 * Stored as decimetres / deciseconds (integer tenths) via {@link StubScaling},
 * which is loss-free for values that went through {@code Ride}'s own
 * {@code Math.round(x * 10.0) / 10.0} rounding step.
 *
 * <h3>Kind encoding</h3>
 * Two lowest bits of {@code flags} hold {@link RideKind#ordinal()} (0..3).
 * Symmetric via {@link #kindToFlags}/{@link #flagsToKind}.
 */
public final class RideStub {

    /** Sorted request indices (ascending), length == degree. */
    public final int[] sortedSet;

    /** Pickup order packed as 4-bit local positions via {@link OrderingCodec}. */
    public final long originPacked;

    /** Dropoff order packed as 4-bit local positions via {@link OrderingCodec}. */
    public final long destPacked;

    /** Ride distance in decimetres (integer tenths of a metre). */
    public final int distDm;

    /** Ride travel time in deciseconds (integer tenths of a second). */
    public final int ttDs;

    /**
     * Flags byte: bits 0-1 hold {@link RideKind#ordinal()}.
     * Additional bits are reserved for future use (zero now).
     */
    public final byte flags;

    // ── constructor ───────────────────────────────────────────────────────────

    public RideStub(int[] sortedSet, long originPacked, long destPacked,
                    int distDm, int ttDs, byte flags) {
        this.sortedSet = sortedSet;
        this.originPacked = originPacked;
        this.destPacked = destPacked;
        this.distDm = distDm;
        this.ttDs = ttDs;
        this.flags = flags;
    }

    // ── factory ───────────────────────────────────────────────────────────────

    /**
     * Extract a {@link RideStub} from a completed {@link Ride}.
     *
     * <p>Converts origin/destination orderings from <em>global</em> request indices
     * to <em>local</em> positions within the sorted request set before packing.
     * Global indices are NOT packed directly — that would silently corrupt every
     * consumer via {@code & 0xF} truncation in {@link OrderingCodec#pack}.
     *
     * @throws IllegalStateException if any global ordering index is not present in
     *                               the ride's request set (would indicate an
     *                               inconsistent Ride state)
     */
    public static RideStub fromRide(Ride ride) {
        // Build sorted set of global request indices.
        int[] indices = ride.getRequestIndices();
        int[] sorted = indices.clone();
        Arrays.sort(sorted);   // sortedSet is ascending global indices

        // Convert pickup order from global indices to local positions.
        int[] originsGlobal = ride.getOriginsIndex();
        int[] originsLocal = toLocalPositions(originsGlobal, sorted);
        long originPacked = OrderingCodec.pack(originsLocal);

        // Convert dropoff order from global indices to local positions.
        int[] destsGlobal = ride.getDestinationsIndex();
        int[] destsLocal = toLocalPositions(destsGlobal, sorted);
        long destPacked = OrderingCodec.pack(destsLocal);

        int distDm = StubScaling.toDeci(ride.getRideDistance());
        int ttDs   = StubScaling.toDeci(ride.getRideTravelTime());
        byte flags  = kindToFlags(ride.getKind());

        return new RideStub(sorted, originPacked, destPacked, distDm, ttDs, flags);
    }

    /**
     * Convert an array of global request indices to their local positions within
     * the sorted set, validated against the set.
     *
     * @param globals     array of global request indices (pickup or dropoff order)
     * @param sortedSet   sorted array of all global request indices for this ride
     * @throws IllegalStateException if any global index is absent from sortedSet
     */
    private static int[] toLocalPositions(int[] globals, int[] sortedSet) {
        int[] locals = new int[globals.length];
        for (int i = 0; i < globals.length; i++) {
            int local = Arrays.binarySearch(sortedSet, globals[i]);
            if (local < 0) {
                throw new IllegalStateException(
                        "Global request index " + globals[i]
                        + " not found in ride's sorted request set — "
                        + "ordering and request set are inconsistent");
            }
            locals[i] = local;
        }
        return locals;
    }

    // ── reconstruction ────────────────────────────────────────────────────────

    /** Number of passengers in this ride. */
    public int degree() {
        return sortedSet.length;
    }

    /**
     * Reconstruct the global pickup ordering.
     *
     * @return array of global request indices in pickup order, exactly reproducing
     *         what {@link Ride#getOriginsIndex()} returned on the source ride
     */
    public int[] originsGlobal() {
        int[] locals = OrderingCodec.unpack(originPacked, degree());
        int[] globals = new int[locals.length];
        for (int i = 0; i < locals.length; i++) {
            globals[i] = sortedSet[locals[i]];
        }
        return globals;
    }

    /**
     * Reconstruct the global dropoff ordering.
     *
     * @return array of global request indices in dropoff order, exactly reproducing
     *         what {@link Ride#getDestinationsIndex()} returned on the source ride
     */
    public int[] destsGlobal() {
        int[] locals = OrderingCodec.unpack(destPacked, degree());
        int[] globals = new int[locals.length];
        for (int i = 0; i < locals.length; i++) {
            globals[i] = sortedSet[locals[i]];
        }
        return globals;
    }

    // ── kind encoding / decoding ──────────────────────────────────────────────

    /**
     * Encode a {@link RideKind} into the two low-order bits of the flags byte.
     *
     * <p>{@link RideKind} has 4 values (SINGLE=0, FIFO=1, LIFO=2, MIXED=3);
     * two bits are sufficient. Using {@link RideKind#ordinal()} keeps the
     * mapping compact and the inverse ({@link #flagsToKind}) symmetric.
     */
    public static byte kindToFlags(RideKind kind) {
        return (byte) (kind.ordinal() & 0x3);
    }

    /**
     * Decode the {@link RideKind} from the two low-order bits of the flags byte.
     * Symmetric inverse of {@link #kindToFlags}.
     */
    public static RideKind flagsToKind(byte flags) {
        return RideKind.values()[flags & 0x3];
    }
}
