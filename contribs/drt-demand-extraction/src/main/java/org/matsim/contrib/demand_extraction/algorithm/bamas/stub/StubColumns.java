package org.matsim.contrib.demand_extraction.algorithm.bamas.stub;

import java.util.Arrays;

import org.matsim.contrib.demand_extraction.demand.DrtRequest;

/**
 * Struct-of-arrays (SoA) container for ride stubs of a fixed degree.
 *
 * <p>One {@code StubColumns} instance holds all surviving rides of exactly one degree
 * as parallel primitive arrays — roughly 30-40 bytes per row — instead of heavyweight
 * {@link org.matsim.contrib.demand_extraction.algorithm.domain.Ride} objects (~4 KB each).
 *
 * <h3>Columns per row</h3>
 * <ul>
 *   <li>{@code setsFlat} — {@code int[d*capacity]} packed sorted global request indices;
 *       slice for row {@code r} at {@code [r*d, r*d+d)}</li>
 *   <li>{@code originOrder} — {@code long[capacity]} pickup ordering packed via
 *       {@link OrderingCodec} (local positions 0..d-1)</li>
 *   <li>{@code destOrder}   — {@code long[capacity]} dropoff ordering (local positions)</li>
 *   <li>{@code rideDistanceDm} — {@code int[capacity]} ride distance in decimetres</li>
 *   <li>{@code travelTimeDs}   — {@code int[capacity]} ride travel time in deciseconds</li>
 *   <li>{@code flags}          — {@code byte[capacity]}</li>
 * </ul>
 *
 * <h3>No {@code startTime} column</h3>
 * Start time is derived bit-exactly via {@link #startTime(int, DrtRequest[])}:
 * {@code firstLocal = OrderingCodec.unpack(originOrder[row], d)[0]}, then
 * {@code globalIdx = setsFlat[row*d + firstLocal]}, then
 * {@code requestTable[globalIdx].requestTime}.
 * This avoids a lossy int-scaled column for a value that is not 0.1-rounded in practice.
 *
 * <h3>Growth</h3>
 * Amortised doubling on all backing arrays.  No per-row objects are allocated.
 */
public final class StubColumns {

	private static final int INITIAL_CAPACITY = 16;

	private final int degree;

	/** Flat packed sorted request indices: row r occupies [r*d, r*d+d). */
	private int[] setsFlat;
	/** Pickup ordering, one packed long per row (local positions 0..d-1). */
	private long[] originOrder;
	/** Dropoff ordering, one packed long per row (local positions 0..d-1). */
	private long[] destOrder;
	/** Ride distance in decimetres, one int per row. */
	private int[] rideDistanceDm;
	/** Ride travel time in deciseconds, one int per row. */
	private int[] travelTimeDs;
	/** Flags byte per row. */
	private byte[] flags;

	/** Number of rows currently stored. */
	private int size;

	/**
	 * Create a new container for rides of the given degree.
	 *
	 * @param degree number of passengers per ride; fixed for the lifetime of this instance
	 */
	public StubColumns(int degree) {
		if (degree < 1) {
			throw new IllegalArgumentException("degree must be >= 1, got " + degree);
		}
		this.degree = degree;
		setsFlat      = new int[INITIAL_CAPACITY * degree];
		originOrder   = new long[INITIAL_CAPACITY];
		destOrder     = new long[INITIAL_CAPACITY];
		rideDistanceDm = new int[INITIAL_CAPACITY];
		travelTimeDs  = new int[INITIAL_CAPACITY];
		flags         = new byte[INITIAL_CAPACITY];
		size          = 0;
	}

	// -----------------------------------------------------------------------
	// Write
	// -----------------------------------------------------------------------

	/**
	 * Append one row to the container.
	 *
	 * @param sortedSet    sorted global request indices (length must equal {@link #degree()})
	 * @param originPacked pickup ordering packed via {@link OrderingCodec} (local positions)
	 * @param destPacked   dropoff ordering packed via {@link OrderingCodec} (local positions)
	 * @param distDm       ride distance in decimetres
	 * @param ttDs         ride travel time in deciseconds
	 * @param flags        flags byte
	 * @return row index of the newly added row (= previous {@link #size()})
	 * @throws IllegalArgumentException if {@code sortedSet.length != degree()}
	 */
	public int addRow(int[] sortedSet, long originPacked, long destPacked,
			int distDm, int ttDs, byte flags) {
		if (sortedSet.length != degree) {
			throw new IllegalArgumentException(
					"sortedSet length " + sortedSet.length + " != degree " + degree);
		}
		ensureCapacity(size + 1);

		int base = size * degree;
		System.arraycopy(sortedSet, 0, setsFlat, base, degree);
		originOrder[size]    = originPacked;
		destOrder[size]      = destPacked;
		rideDistanceDm[size] = distDm;
		travelTimeDs[size]   = ttDs;
		this.flags[size]     = flags;

		return size++;
	}

	// -----------------------------------------------------------------------
	// Getters
	// -----------------------------------------------------------------------

	/** Fixed degree of this container. */
	public int degree() { return degree; }

	/** Number of rows stored. */
	public int size() { return size; }

	/** Packed pickup ordering for row {@code row} (local positions via {@link OrderingCodec}). */
	public long originOrder(int row)     { return originOrder[row]; }

	/** Packed dropoff ordering for row {@code row} (local positions via {@link OrderingCodec}). */
	public long destOrder(int row)       { return destOrder[row]; }

	/** Ride distance in decimetres for row {@code row}. */
	public int  rideDistanceDm(int row)  { return rideDistanceDm[row]; }

	/** Ride travel time in deciseconds for row {@code row}. */
	public int  travelTimeDs(int row)    { return travelTimeDs[row]; }

	/** Flags byte for row {@code row}. */
	public byte flags(int row)           { return flags[row]; }

	/**
	 * Return a length-{@code d} copy of the global request index slice for row {@code row},
	 * i.e. {@code setsFlat[row*d .. row*d+d)}.
	 *
	 * <p>Returns a defensive copy — the caller may mutate it safely.
	 */
	public int[] requestIndices(int row) {
		return Arrays.copyOfRange(setsFlat, row * degree, row * degree + degree);
	}

	// -----------------------------------------------------------------------
	// startTime helper
	// -----------------------------------------------------------------------

	/**
	 * Derive the ride start time (seconds) for row {@code row} from a global request table.
	 *
	 * <p>The start time equals the {@code requestTime} of the first-picked-up passenger.
	 * {@code firstLocal} is the local position (0..d-1) that appears first in the
	 * origin ordering; its global request index is then read from {@code setsFlat}.
	 *
	 * <pre>
	 *   firstLocal = OrderingCodec.unpack(originOrder[row], d)[0]
	 *   globalIdx  = setsFlat[row*d + firstLocal]
	 *   return requestTable[globalIdx].requestTime
	 * </pre>
	 *
	 * <p>This is lossless because {@link DrtRequest#requestTime} is stored as a raw
	 * {@code double} and is never quantised; a decisecond int column would be lossy.
	 *
	 * @param row          row index
	 * @param requestTable global request array indexed by {@link DrtRequest#index}
	 * @return start time in seconds (same semantics as {@link DrtRequest#requestTime})
	 */
	public double startTime(int row, DrtRequest[] requestTable) {
		int firstLocal = OrderingCodec.unpack(originOrder[row], degree)[0];
		int globalIdx  = setsFlat[row * degree + firstLocal];
		return requestTable[globalIdx].requestTime;
	}

	// -----------------------------------------------------------------------
	// Internal growth
	// -----------------------------------------------------------------------

	private void ensureCapacity(int minRowCount) {
		int currentCap = originOrder.length; // row-level capacity
		if (minRowCount <= currentCap) {
			return;
		}
		int newCap = currentCap;
		while (newCap < minRowCount) {
			newCap <<= 1; // double
		}
		setsFlat      = Arrays.copyOf(setsFlat,      newCap * degree);
		originOrder   = Arrays.copyOf(originOrder,   newCap);
		destOrder     = Arrays.copyOf(destOrder,     newCap);
		rideDistanceDm = Arrays.copyOf(rideDistanceDm, newCap);
		travelTimeDs  = Arrays.copyOf(travelTimeDs,  newCap);
		flags         = Arrays.copyOf(flags,         newCap);
	}
}
