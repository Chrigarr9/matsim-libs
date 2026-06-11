package org.matsim.contrib.demand_extraction.algorithm.bamas.stub;

import java.util.Arrays;
import java.util.Collection;

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

	/**
	 * OPTIONAL parallel reqArray-position column, same layout as {@link #setsFlat}
	 * (row r occupies {@code [r*d, r*d+d)}). {@code positionsFlat[r*d + k]} is the
	 * {@code reqArray} position of the request whose sorted global index is
	 * {@code setsFlat[r*d + k]}.
	 *
	 * <p>NULL by default and only ever allocated on the degree-2 PAIR path
	 * (Task 13). Degree-3+ layers never carry positions → zero memory overhead for
	 * the memory-critical extension layers (the whole point of Plan A). The pair
	 * layer needs it because Paper-2 Extension-2 hub expansion emits virtual request
	 * COPIES sharing one {@link DrtRequest#index}, so the global index alone cannot
	 * recover the exact generation copy a pair was routed from; the {@code reqArray}
	 * position can.
	 */
	private int[] positionsFlat;

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

	/**
	 * Append one row that ALSO carries the {@code reqArray}-position column (degree-2
	 * pair path only — Task 13).
	 *
	 * <p>Lazily allocates {@link #positionsFlat} on first use, sized like
	 * {@link #setsFlat} (capacity × degree) so it grows with {@link #ensureCapacity}.
	 * Pre-existing rows added through the no-position {@link #addRow(int[],long,long,int,int,byte)}
	 * before the first positions row would have undefined position slices — the pair
	 * path always uses this overload exclusively, so that mixed case never arises.
	 *
	 * @param positions reqArray positions aligned to {@code sortedSet}: {@code positions[k]}
	 *                  is the reqArray position of the request whose sorted global index is
	 *                  {@code sortedSet[k]} (length must equal {@link #degree()})
	 * @return row index of the newly added row (= previous {@link #size()})
	 * @throws IllegalArgumentException if {@code sortedSet.length != degree()} or
	 *         {@code positions.length != degree()}
	 */
	public int addRow(int[] sortedSet, long originPacked, long destPacked,
			int distDm, int ttDs, byte flags, int[] positions) {
		if (positions.length != degree) {
			throw new IllegalArgumentException(
					"positions length " + positions.length + " != degree " + degree);
		}
		int row = addRow(sortedSet, originPacked, destPacked, distDm, ttDs, flags);
		if (positionsFlat == null) {
			// Size like setsFlat (capacity × degree) so ensureCapacity grows it in lockstep.
			positionsFlat = new int[setsFlat.length];
		}
		System.arraycopy(positions, 0, positionsFlat, row * degree, degree);
		return row;
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

	/**
	 * Return a length-{@code d} copy of the {@code reqArray}-position slice for row
	 * {@code row}, aligned to {@link #requestIndices(int)} (Task 13, pair path only).
	 *
	 * <p>Returns a defensive copy.
	 *
	 * @throws IllegalStateException if this layer never stored positions (i.e. it is a
	 *         degree-3+ layer, or a pair layer built via the no-position {@code addRow})
	 */
	public int[] positionIndices(int row) {
		if (positionsFlat == null) {
			throw new IllegalStateException(
					"positionIndices requested on a layer that never stored reqArray positions "
					+ "(row " + row + "); only the degree-2 pair layer carries positions");
		}
		return Arrays.copyOfRange(positionsFlat, row * degree, row * degree + degree);
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
	// Sorted merge factory
	// -----------------------------------------------------------------------

	/**
	 * Merge a non-empty collection of same-degree {@link StubColumns} buffers into a
	 * single new {@link StubColumns} whose rows are in ascending lexicographic order
	 * of their {@code setsFlat} slices.
	 *
	 * <h3>Determinism guarantee</h3>
	 * In {@code BamasRideExtender.extendRides}, each candidate request-set hash is
	 * claimed <em>exactly once</em> by the producer (via {@code claimedHashes.add}),
	 * so each set is processed by exactly one worker and appended to exactly one
	 * per-thread buffer exactly once.  Consequently the slice keys are globally
	 * unique across all input buffers, and the lexicographic order is a <em>total
	 * order</em>.  A total order on unique keys is independent of (a) which buffer
	 * a row originated from, (b) the order in which buffers appear in
	 * {@code parts}.  Task 11 wires the engine to use this merged container; the
	 * iteration-order-independence is the guarantee that parallel enumeration
	 * continues to produce byte-identical output.
	 *
	 * <h3>Algorithm</h3>
	 * Builds an index-permutation array of (buffer index, row index) references,
	 * sorts it by the per-element lex comparator over the degree-length slice, then
	 * calls {@link #addRow} in sorted order into a fresh container.  The backing
	 * arrays are never copied twice.
	 *
	 * <h3>Empty collection</h3>
	 * Requires non-empty input: without any part there is no degree information
	 * available.  Use {@code new StubColumns(degree)} directly for the empty case.
	 *
	 * @param parts non-empty collection of same-degree buffers; buffers may be empty
	 * @return a new {@link StubColumns} containing all rows in ascending lex order
	 * @throws IllegalArgumentException if {@code parts} is empty or buffers have
	 *         differing degrees
	 */
	// NOTE: mergeSorted is degree-3+ only — the degree-2 pair layer is never merged
	// through here (it is built/pruned via sequential addRow in BamasEngine), so the
	// optional positionsFlat column need not be propagated by this method.
	public static StubColumns mergeSorted(Collection<StubColumns> parts) {
		if (parts.isEmpty()) {
			throw new IllegalArgumentException(
					"mergeSorted requires non-empty collection; use new StubColumns(degree) for the empty case");
		}

		// Validate all parts share one degree
		int degree = -1;
		for (StubColumns part : parts) {
			if (degree == -1) {
				degree = part.degree;
			} else if (part.degree != degree) {
				throw new IllegalArgumentException(
						"All parts must share one degree; found " + degree + " and " + part.degree);
			}
		}

		// Count total rows and build flat index array: each entry encodes (partIndex, rowIndex).
		// We keep parallel object + int arrays to avoid boxing.
		int totalRows = 0;
		for (StubColumns part : parts) {
			totalRows += part.size;
		}

		// Store parts as an array for indexed access during sort.
		StubColumns[] partArray = parts.toArray(new StubColumns[0]);

		// partIdx[i] and rowIdx[i] together identify the i-th candidate row.
		int[] partIdx = new int[totalRows];
		int[] rowIdx  = new int[totalRows];
		int pos = 0;
		for (int p = 0; p < partArray.length; p++) {
			int sz = partArray[p].size;
			for (int r = 0; r < sz; r++) {
				partIdx[pos] = p;
				rowIdx[pos]  = r;
				pos++;
			}
		}

		// Sort by lex order of setsFlat slice. Within a single degree all slices are
		// equal-length (degree elements), so the length-tie-break is never needed.
		// Using an Integer[] sort-by-index rather than sorting primitives directly.
		final int d = degree; // effectively final for lambda
		Integer[] order = new Integer[totalRows];
		for (int i = 0; i < totalRows; i++) order[i] = i;
		Arrays.sort(order, (ia, ib) -> {
			StubColumns pa = partArray[partIdx[ia]];
			int ra = rowIdx[ia];
			StubColumns pb = partArray[partIdx[ib]];
			int rb = rowIdx[ib];
			int baseA = ra * d;
			int baseB = rb * d;
			for (int k = 0; k < d; k++) {
				int cmp = Integer.compare(pa.setsFlat[baseA + k], pb.setsFlat[baseB + k]);
				if (cmp != 0) return cmp;
			}
			return 0;
		});

		// Construct merged container in sorted order.
		StubColumns merged = new StubColumns(d);
		for (int i = 0; i < totalRows; i++) {
			int idx = order[i];
			StubColumns src = partArray[partIdx[idx]];
			int r = rowIdx[idx];
			merged.addRow(
					Arrays.copyOfRange(src.setsFlat, r * d, r * d + d),
					src.originOrder[r],
					src.destOrder[r],
					src.rideDistanceDm[r],
					src.travelTimeDs[r],
					src.flags[r]
			);
		}
		return merged;
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
		// Grow the optional positions column in lockstep, but only if it was allocated
		// (degree-2 pair path). Degree-3+ layers keep it null and pay zero overhead.
		if (positionsFlat != null) {
			positionsFlat = Arrays.copyOf(positionsFlat, newCap * degree);
		}
	}
}
