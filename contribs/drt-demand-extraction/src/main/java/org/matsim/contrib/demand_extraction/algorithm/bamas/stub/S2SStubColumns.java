package org.matsim.contrib.demand_extraction.algorithm.bamas.stub;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;

/**
 * Struct-of-arrays (SoA) container for stop-to-stop (S2S) ride stubs of a fixed degree.
 *
 * <p>Composes (does NOT extend — {@link StubColumns} is {@code final}) an inner
 * {@link StubColumns} for the door-to-door (D2D) columns, and adds FOUR parallel S2S
 * columns:
 * <ul>
 *   <li>{@code pickupStopId} — dense int id of the pickup stop per row
 *       (from {@link StopLocationDictionary})</li>
 *   <li>{@code dropoffStopId} — dense int id of the dropoff stop per row</li>
 *   <li>{@code accessWalkFlat} — flattened {@code degree*n} doubles; row {@code r}
 *       occupies {@code [r*degree, r*degree+degree)}; stored with exact double precision
 *       (no float conversion, no rounding)</li>
 *   <li>{@code egressWalkFlat} — same layout for egress walk distances</li>
 * </ul>
 *
 * <h3>Double precision guarantee</h3>
 * Walk distances are NOT 0.1-quantised, unlike ride distances (stored as dm integers).
 * Using exact {@code double} storage keeps the downstream parity gate at SHA-256 (a
 * float conversion would silently lose the last ~7 decimal digits and corrupt hashes).
 *
 * <h3>Memory layout</h3>
 * Each row contributes:
 * <ul>
 *   <li>D2D layer: ~30–40 bytes (see {@link StubColumns})</li>
 *   <li>2 ints (8 bytes) for pickup/dropoff stop ids</li>
 *   <li>2 × degree × 8 bytes for access/egress walk flats</li>
 * </ul>
 * At degree 3 the S2S overhead per row is 8 + 48 = 56 bytes on top of the D2D layer.
 *
 * <h3>Thread safety</h3>
 * Not thread-safe. Use per-thread instances.
 */
public final class S2SStubColumns {

	/** Inner D2D column store — composition, not inheritance. */
	private final StubColumns d2d;

	/** Pickup stop ids, one per row (fastutil IntArrayList for primitive storage). */
	private final IntArrayList pickupStopIds;

	/** Dropoff stop ids, one per row. */
	private final IntArrayList dropoffStopIds;

	/**
	 * Flattened access walk distances.  Row {@code r} occupies
	 * {@code [r*degree, r*degree+degree)}.  Stored as exact doubles.
	 */
	private final DoubleArrayList accessWalkFlat;

	/**
	 * Flattened egress walk distances.  Same layout as {@link #accessWalkFlat}.
	 */
	private final DoubleArrayList egressWalkFlat;

	/** Fixed degree for this container. */
	private final int degree;

	/**
	 * Create a new S2S container for rides of the given degree.
	 *
	 * @param degree number of passengers per ride; fixed for the lifetime of this instance
	 * @throws IllegalArgumentException if degree < 1
	 */
	public S2SStubColumns(int degree) {
		this.degree = degree;
		this.d2d = new StubColumns(degree);
		this.pickupStopIds  = new IntArrayList();
		this.dropoffStopIds = new IntArrayList();
		this.accessWalkFlat = new DoubleArrayList();
		this.egressWalkFlat = new DoubleArrayList();
	}

	// -----------------------------------------------------------------------
	// Write
	// -----------------------------------------------------------------------

	/**
	 * Append one S2S row to the container.
	 *
	 * <p>The D2D part is delegated to the inner {@link StubColumns}. The S2S columns
	 * (stop ids and walk slices) are appended in parallel, keeping all columns
	 * index-aligned.
	 *
	 * @param sortedSet    sorted global request indices (length must equal {@link #degree()})
	 * @param originPacked pickup ordering packed via {@link OrderingCodec} (local positions)
	 * @param destPacked   dropoff ordering packed via {@link OrderingCodec} (local positions)
	 * @param distDm       ride distance in decimetres
	 * @param ttDs         ride travel time in deciseconds
	 * @param flags        flags byte
	 * @param pickupStopId dense id of the pickup stop
	 * @param dropoffStopId dense id of the dropoff stop
	 * @param accessWalk   walk distances from each passenger's origin to the pickup stop
	 *                     (length must equal {@link #degree()}); stored with exact double precision
	 * @param egressWalk   walk distances from the dropoff stop to each passenger's destination
	 *                     (length must equal {@link #degree()}); stored with exact double precision
	 * @return row index of the newly added row (= previous {@link #size()})
	 * @throws IllegalArgumentException if {@code sortedSet.length}, {@code accessWalk.length},
	 *         or {@code egressWalk.length} != {@link #degree()}
	 */
	public int addRow(int[] sortedSet, long originPacked, long destPacked,
			int distDm, int ttDs, byte flags,
			int pickupStopId, int dropoffStopId,
			double[] accessWalk, double[] egressWalk) {

		if (accessWalk.length != degree) {
			throw new IllegalArgumentException(
					"accessWalk length " + accessWalk.length + " != degree " + degree);
		}
		if (egressWalk.length != degree) {
			throw new IllegalArgumentException(
					"egressWalk length " + egressWalk.length + " != degree " + degree);
		}

		// Delegate D2D columns (also validates sortedSet.length == degree).
		int row = d2d.addRow(sortedSet, originPacked, destPacked, distDm, ttDs, flags);

		// Append S2S columns, keeping index alignment with d2d.
		pickupStopIds.add(pickupStopId);
		dropoffStopIds.add(dropoffStopId);
		for (int i = 0; i < degree; i++) {
			accessWalkFlat.add(accessWalk[i]);
		}
		for (int i = 0; i < degree; i++) {
			egressWalkFlat.add(egressWalk[i]);
		}

		return row;
	}

	// -----------------------------------------------------------------------
	// Getters — D2D delegates
	// -----------------------------------------------------------------------

	/** Fixed degree of this container. */
	public int degree() { return degree; }

	/** Number of rows stored. */
	public int size() { return d2d.size(); }

	/** Sorted global request indices for row {@code row} (defensive copy). */
	public int[] requestIndices(int row) { return d2d.requestIndices(row); }

	/** Packed pickup ordering for row {@code row}. */
	public long originOrder(int row)    { return d2d.originOrder(row); }

	/** Packed dropoff ordering for row {@code row}. */
	public long destOrder(int row)      { return d2d.destOrder(row); }

	/** Ride distance in decimetres for row {@code row}. */
	public int rideDistanceDm(int row)  { return d2d.rideDistanceDm(row); }

	/** Ride travel time in deciseconds for row {@code row}. */
	public int travelTimeDs(int row)    { return d2d.travelTimeDs(row); }

	/** Flags byte for row {@code row}. */
	public byte flags(int row)          { return d2d.flags(row); }

	// -----------------------------------------------------------------------
	// Getters — S2S columns
	// -----------------------------------------------------------------------

	/** Dense id of the pickup stop for row {@code row}. */
	public int pickupStopId(int row) { return pickupStopIds.getInt(row); }

	/** Dense id of the dropoff stop for row {@code row}. */
	public int dropoffStopId(int row) { return dropoffStopIds.getInt(row); }

	/**
	 * Return a fresh length-{@code degree} array containing the access walk distances
	 * for row {@code row}, copied from the flattened backing store.
	 *
	 * <p>Callers may mutate the returned array safely.  Values are bit-identical to those
	 * passed to {@link #addRow}.
	 */
	public double[] accessWalk(int row) {
		return copySlice(accessWalkFlat, row);
	}

	/**
	 * Return a fresh length-{@code degree} array containing the egress walk distances
	 * for row {@code row}, copied from the flattened backing store.
	 *
	 * <p>Callers may mutate the returned array safely.
	 */
	public double[] egressWalk(int row) {
		return copySlice(egressWalkFlat, row);
	}

	// -----------------------------------------------------------------------
	// Internal helpers
	// -----------------------------------------------------------------------

	/** Copy {@code [row*degree, row*degree+degree)} from a flat DoubleArrayList into a fresh array. */
	private double[] copySlice(DoubleArrayList flat, int row) {
		int base = row * degree;
		double[] result = new double[degree];
		for (int i = 0; i < degree; i++) {
			result[i] = flat.getDouble(base + i);
		}
		return result;
	}
}
