package org.matsim.contrib.demand_extraction.algorithm.bamas.ride;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;

/**
 * Struct-of-arrays (SoA) container for stop-to-stop (S2S) ride stubs of a fixed degree.
 *
 * <p>Composes (does NOT extend — {@link RideLayer} is {@code final}) an inner
 * {@link RideLayer} for the door-to-door (D2D) columns, and adds FIVE parallel S2S
 * columns:
 * <ul>
 *   <li>{@code pickupStopId} — dense int id of the pickup stop per row
 *       (from {@link StopLocationDictionary})</li>
 *   <li>{@code dropoffStopId} — dense int id of the dropoff stop per row</li>
 *   <li>{@code accessWalkFlat} — flattened {@code degree*n} doubles; row {@code r}
 *       occupies {@code [r*degree, r*degree+degree)}; stored with exact double precision
 *       (no float conversion, no rounding)</li>
 *   <li>{@code egressWalkFlat} — same layout for egress walk distances</li>
 *   <li>{@code startTime} — the D2D parent ride's start time (exact double); needed
 *       for routing the S2S segment at the correct time during pinned-stop replay.
 *       {@link org.matsim.contrib.demand_extraction.algorithm.generation.StopBasedRideGenerator}
 *       routes the S2S segment at {@code doorToDoor.getStartTime()} and stores that
 *       same value as the S2S ride's {@code startTime}. Replay must use the IDENTICAL
 *       value to reproduce the cache hit and thus the bit-exact connection arrays.</li>
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
 *   <li>D2D layer: ~30–40 bytes (see {@link RideLayer})</li>
 *   <li>2 ints (8 bytes) for pickup/dropoff stop ids</li>
 *   <li>8 bytes for startTime double</li>
 *   <li>2 × degree × 8 bytes for access/egress walk flats</li>
 * </ul>
 * At degree 3 the S2S overhead per row is 8 + 8 + 48 = 64 bytes on top of the D2D layer.
 *
 * <h3>Thread safety</h3>
 * Not thread-safe. Use per-thread instances.
 */
public final class StopRideLayer {

	/** Inner D2D column store — composition, not inheritance. */
	private final RideLayer d2d;

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

	/**
	 * D2D parent ride start time (exact double), one per row.  The S2S segment is routed
	 * at this time ({@code networkCache.getSegment(pickup, dropoff, startTime)}) during
	 * pinned-stop replay, reproducing the cache hit bit-exactly.
	 */
	private final DoubleArrayList startTimes;

	/**
	 * S2S ride index assigned during Phase 5 (sequential, starting from the D2D count).
	 * Stored here so {@link
	 * org.matsim.contrib.demand_extraction.algorithm.hyperpool.StopToStopRideWrapper} can be
	 * constructed with the correct index in stub mode (Phase 6).  The index matches
	 * {@link org.matsim.contrib.demand_extraction.algorithm.domain.Ride#getIndex()} on the
	 * corresponding full ride and is used by the wrapper's {@code equals}/{@code hashCode}
	 * to key the inner HyperPool shareability graph.
	 */
	private final IntArrayList rideIndices;

	/** Fixed degree for this container. */
	private final int degree;

	/**
	 * Create a new S2S container for rides of the given degree.
	 *
	 * @param degree number of passengers per ride; fixed for the lifetime of this instance
	 * @throws IllegalArgumentException if degree < 1
	 */
	public StopRideLayer(int degree) {
		this.degree = degree;
		this.d2d = new RideLayer(degree);
		this.pickupStopIds  = new IntArrayList();
		this.dropoffStopIds = new IntArrayList();
		this.accessWalkFlat = new DoubleArrayList();
		this.egressWalkFlat = new DoubleArrayList();
		this.startTimes     = new DoubleArrayList();
		this.rideIndices    = new IntArrayList();
	}

	// -----------------------------------------------------------------------
	// Write
	// -----------------------------------------------------------------------

	/**
	 * Append one S2S row to the container.
	 *
	 * <p>The D2D part is delegated to the inner {@link RideLayer}. The S2S columns
	 * (stop ids, start time, and walk slices) are appended in parallel, keeping all columns
	 * index-aligned.
	 *
	 * @param sortedSet    sorted global request indices (length must equal {@link #degree()})
	 * @param originPacked pickup ordering packed via {@link OrderingCodec} (local positions)
	 * @param destPacked   dropoff ordering packed via {@link OrderingCodec} (local positions)
	 * @param distDm       S2S segment distance in decimetres (NOT the D2D parent's distance;
	 *                     used for self-check and sort key — NOT fed into connection arrays
	 *                     during pinned-stop replay, which re-routes for exact values)
	 * @param ttDs         S2S segment travel time in deciseconds (same caveat as distDm)
	 * @param flags        flags byte
	 * @param pickupStopId dense id of the pickup stop
	 * @param dropoffStopId dense id of the dropoff stop
	 * @param startTime    the D2D parent's start time (exact double); used to route the S2S
	 *                     segment during pinned-stop replay at the same time the master used
	 * @param rideIndex    sequential index assigned to this S2S ride during Phase 5 (starts at
	 *                     D2D count); used by the Phase-6 wrapper's {@code equals}/{@code hashCode}
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
			int pickupStopId, int dropoffStopId, double startTime, int rideIndex,
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
		startTimes.add(startTime);
		rideIndices.add(rideIndex);
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
	 * D2D parent ride start time for row {@code row} (exact double, seconds from midnight).
	 * Used by the pinned-stop replay materializer to route the S2S segment at the correct
	 * time bin, reproducing the master cache hit bit-exactly.
	 */
	public double startTime(int row) { return startTimes.getDouble(row); }

	/**
	 * Phase-5 sequential ride index for row {@code row}.
	 * Used by {@link org.matsim.contrib.demand_extraction.algorithm.hyperpool.StopToStopRideWrapper}
	 * (stub mode) to supply the {@code rideIndex} for {@code equals}/{@code hashCode}.
	 */
	public int rideIndex(int row) { return rideIndices.getInt(row); }

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
