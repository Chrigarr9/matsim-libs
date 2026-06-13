package org.matsim.contrib.demand_extraction.algorithm.bamas.stub;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.matsim.contrib.demand_extraction.algorithm.bamas.extension.RideMaterializer;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideVariant;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

/**
 * Stub-backed {@link RideStore} that streams D2D and S2S output for the HyperPool path
 * ({@code stubModeEnabled && enableStopBased}) without ever holding the full fat ride list
 * in memory.
 *
 * <h3>Relationship to {@link StubRideStore}</h3>
 * {@link StubRideStore} covers only the D2D path ({@code stubModeEnabled && !enableStopBased}).
 * This store extends that design to include S2S stubs: it holds fat D2D singles+pairs,
 * D2D degree-3+ stub layers (materialized on demand via {@link RideMaterializer}), and
 * S2S stub layers (materialized on demand via {@link S2SRideMaterializer}).
 *
 * <h3>Sort order</h3>
 * The master code sorts {@code allRides} by
 * {@code (variant, degree, firstPickup)} after Phase 5/6 and before export.
 * This store replicates that stable sort using the same lightweight row-reference array
 * approach as {@link StubRideStore}:
 * <ul>
 *   <li>{@link RideVariant#DOOR_TO_DOOR} (ordinal 0) sorts before
 *       {@link RideVariant#STOP_TO_STOP} (ordinal 1) — all D2D rows before S2S rows.</li>
 *   <li>Within variant, rows sort by degree ascending then by {@code firstPickup} ascending.
 *       {@code firstPickup = sortedSet[unpack(originOrder)[0]]}.</li>
 *   <li>Ties (same variant, degree, firstPickup) keep insertion order — matching Java's
 *       stable {@code List.sort}.</li>
 * </ul>
 *
 * <h3>Index stamping</h3>
 * Each emitted ride is stamped with its post-sort sequential index via
 * {@link Ride#assignIndex}, matching the old {@code toBuilder().index(i).build()} loop.
 *
 * <h3>Insertion order (concatenation) for reproducibility</h3>
 * Fat D2D singles+pairs (in engine insertion order) → D2D stub layers (degree order) →
 * S2S stub layers (degree order, Phase-5 row order within each degree).
 * This matches the master's {@code allRides} construction: fat D2D appended first, then
 * D2D stubs materialized into allRides during earlier phases, then S2S rides appended in
 * Phase-5 output order.
 */
public final class HyperPoolStubRideStore implements RideStore {

	/** Source tag: a fat D2D ride (single or pair). */
	private static final int SRC_FAT_D2D = -1;

	private final List<Ride> fatSingularPairs;
	private final List<StubColumns> d2dStubLayers;
	private final RideMaterializer d2dMaterializer;
	private final List<S2SStubColumns> s2sStubLayers; // per-degree, Phase-5 row order
	private final S2SRideMaterializer s2sMaterializer;
	private final Map<Integer, DrtRequest> requestById;

	/** Total number of rides across all sources. */
	private final int total;

	/**
	 * Post-sort permutation.
	 * <ul>
	 *   <li>{@code sourceOf[r] == SRC_FAT_D2D}: fat D2D, local row = {@code localRowOf[r]}
	 *       in {@code fatSingularPairs}.</li>
	 *   <li>{@code 0 <= sourceOf[r] < d2dStubLayers.size()}: D2D stub layer at that index,
	 *       local row in that layer.</li>
	 *   <li>{@code sourceOf[r] >= d2dStubLayers.size()}: S2S stub layer at index
	 *       {@code sourceOf[r] - d2dStubLayers.size()}, local row = {@code localRowOf[r]}.</li>
	 * </ul>
	 */
	private final int[] sourceOf;
	private final int[] localRowOf;

	/**
	 * @param fatSingularPairs fat D2D singles + pairs (insertion order)
	 * @param d2dStubLayers    D2D degree-3+ stub layers (degree order)
	 * @param d2dMaterializer  D2D pinned-ordering replayer
	 * @param s2sStubLayers    S2S stub layers (degree order, Phase-5 row order within degree)
	 * @param s2sMaterializer  S2S pinned-stop replayer
	 * @param requestById      global index → request map
	 */
	public HyperPoolStubRideStore(List<Ride> fatSingularPairs,
			List<StubColumns> d2dStubLayers,
			RideMaterializer d2dMaterializer,
			List<S2SStubColumns> s2sStubLayers,
			S2SRideMaterializer s2sMaterializer,
			Map<Integer, DrtRequest> requestById) {
		this.fatSingularPairs = fatSingularPairs;
		this.d2dStubLayers    = d2dStubLayers;
		this.d2dMaterializer  = d2dMaterializer;
		this.s2sStubLayers    = s2sStubLayers;
		this.s2sMaterializer  = s2sMaterializer;
		this.requestById      = requestById;

		int d2dStubRows = d2dStubLayers.stream().mapToInt(StubColumns::size).sum();
		int s2sStubRows = s2sStubLayers.stream().mapToInt(S2SStubColumns::size).sum();
		this.total = fatSingularPairs.size() + d2dStubRows + s2sStubRows;

		this.sourceOf   = new int[total];
		this.localRowOf = new int[total];
		computeOrder();
	}

	/**
	 * Build the row-reference array in concatenation order, then stable-sort by
	 * {@code (variant, degree, firstPickup)} — the mirror of the master
	 * {@code allRides.sort(...)} on the same element set.
	 *
	 * <p>Delegates to {@link #computeSortPermutation} so the exact same ordering is
	 * available <em>before</em> Phase 6 (the bundling step) — see
	 * {@code BamasEngine.generateHyperPooledRidesFromStubs}. Phase 6 stamps each S2S
	 * wrapper with its final post-sort index (its position {@code r} in this permutation)
	 * so HyperPool clustering and {@code sourceRideIndices} consume the FINAL,
	 * mode-independent index identical to what export assigns here (Plan A2 hyperpool fix).
	 */
	private void computeOrder() {
		int[][] perm = computeSortPermutation(fatSingularPairs, d2dStubLayers, s2sStubLayers);
		System.arraycopy(perm[0], 0, sourceOf, 0, total);
		System.arraycopy(perm[1], 0, localRowOf, 0, total);
	}

	/**
	 * Compute the export sort permutation over the combined D2D + S2S row universe, shared
	 * between this store (export) and Phase 6 (pre-bundling index canonicalization).
	 *
	 * <p>Concatenation order: fat D2D singles+pairs → D2D stub layers (degree order) → S2S
	 * stub layers (degree order, Phase-5 row order within each degree). Stable-sorted by
	 * {@code (variant, degree, firstPickup)}. The returned permutation is the byte-for-byte
	 * order the export uses, so position {@code r} == the final ride index of that row.
	 *
	 * @return {@code int[2][total]}: {@code [0] = sourceOf}, {@code [1] = localRowOf}, with
	 *         the same encoding documented on the {@link #sourceOf} field — {@code SRC_FAT_D2D}
	 *         for fat rows, {@code [0, nD2D)} for D2D stub layers, {@code >= nD2D} for S2S stub
	 *         layers (where {@code nD2D = d2dStubLayers.size()}).
	 */
	public static int[][] computeSortPermutation(
			List<Ride> fatSingularPairs,
			List<StubColumns> d2dStubLayers,
			List<S2SStubColumns> s2sStubLayers) {

		int d2dStubRows = d2dStubLayers.stream().mapToInt(StubColumns::size).sum();
		int s2sStubRows = s2sStubLayers.stream().mapToInt(S2SStubColumns::size).sum();
		int total = fatSingularPairs.size() + d2dStubRows + s2sStubRows;

		List<RowRef> refs = new ArrayList<>(total);

		// Fat D2D singles + pairs
		int fatCount = fatSingularPairs.size();
		for (int i = 0; i < fatCount; i++) {
			Ride r = fatSingularPairs.get(i);
			int[] idx = r.getRequestIndices();
			int firstPickup = idx.length > 0 ? idx[0] : Integer.MAX_VALUE;
			refs.add(new RowRef(r.getVariant().ordinal(), r.getDegree(), firstPickup, SRC_FAT_D2D, i));
		}

		// D2D stub layers
		int nD2D = d2dStubLayers.size();
		for (int s = 0; s < nD2D; s++) {
			StubColumns layer = d2dStubLayers.get(s);
			int degree = layer.degree();
			for (int row = 0; row < layer.size(); row++) {
				int firstLocal = OrderingCodec.unpack(layer.originOrder(row), degree)[0];
				int firstPickup = layer.requestIndices(row)[firstLocal];
				refs.add(new RowRef(RideVariant.DOOR_TO_DOOR.ordinal(), degree, firstPickup, s, row));
			}
		}

		// S2S stub layers (variant = STOP_TO_STOP, ordinal > D2D)
		for (int s = 0; s < s2sStubLayers.size(); s++) {
			S2SStubColumns layer = s2sStubLayers.get(s);
			int degree = layer.degree();
			for (int row = 0; row < layer.size(); row++) {
				int firstLocal = OrderingCodec.unpack(layer.originOrder(row), degree)[0];
				int firstPickup = layer.requestIndices(row)[firstLocal];
				// Source index offset: D2D stubs occupy [0, nD2D), S2S stubs start at nD2D.
				refs.add(new RowRef(RideVariant.STOP_TO_STOP.ordinal(), degree, firstPickup,
						nD2D + s, row));
			}
		}

		// STABLE sort by (variant, degree, firstPickup).
		refs.sort((a, b) -> {
			int c = Integer.compare(a.variant, b.variant);
			if (c != 0) return c;
			c = Integer.compare(a.degree, b.degree);
			if (c != 0) return c;
			return Integer.compare(a.firstPickup, b.firstPickup);
		});

		int[] sourceOf   = new int[total];
		int[] localRowOf = new int[total];
		for (int r = 0; r < total; r++) {
			RowRef ref = refs.get(r);
			sourceOf[r]   = ref.source;
			localRowOf[r] = ref.localRow;
		}
		return new int[][] { sourceOf, localRowOf };
	}

	/**
	 * Materialize the r-th output ride WITHOUT index stamping.
	 */
	private Ride materializeRaw(int row) {
		int src   = sourceOf[row];
		int local = localRowOf[row];

		if (src == SRC_FAT_D2D) {
			return fatSingularPairs.get(local);
		}

		int nD2D = d2dStubLayers.size();
		if (src < nD2D) {
			// D2D stub layer
			return d2dMaterializer.materialize(d2dStubLayers.get(src), local, requestById);
		} else {
			// S2S stub layer
			return s2sMaterializer.materialize(s2sStubLayers.get(src - nD2D), local, requestById);
		}
	}

	@Override
	public int size() {
		return total;
	}

	@Override
	public Ride materialize(int row) {
		Ride ride = materializeRaw(row);
		ride.assignIndex(row);
		return ride;
	}

	@Override
	public void forEachMaterialized(Consumer<Ride> visitor) {
		for (int row = 0; row < total; row++) {
			Ride ride = materializeRaw(row);
			ride.assignIndex(row);
			visitor.accept(ride);
		}
	}

	@Override
	public int[] requestIndices(int row) {
		int src   = sourceOf[row];
		int local = localRowOf[row];

		if (src == SRC_FAT_D2D) {
			return fatSingularPairs.get(local).getRequestIndices();
		}

		int nD2D = d2dStubLayers.size();
		StubColumns d2dCols;
		int degree;
		int[] sortedSet;
		int[] originLocal;

		if (src < nD2D) {
			d2dCols = d2dStubLayers.get(src);
			degree  = d2dCols.degree();
			originLocal = OrderingCodec.unpack(d2dCols.originOrder(local), degree);
			sortedSet   = d2dCols.requestIndices(local);
		} else {
			S2SStubColumns s2sCols = s2sStubLayers.get(src - nD2D);
			degree  = s2sCols.degree();
			originLocal = OrderingCodec.unpack(s2sCols.originOrder(local), degree);
			sortedSet   = s2sCols.requestIndices(local);
		}

		int[] pickupOrder = new int[degree];
		for (int i = 0; i < degree; i++) {
			pickupOrder[i] = sortedSet[originLocal[i]];
		}
		return pickupOrder;
	}

	/** Lightweight per-row sort descriptor. */
	private static final class RowRef {
		final int variant;
		final int degree;
		final int firstPickup;
		final int source;
		final int localRow;

		RowRef(int variant, int degree, int firstPickup, int source, int localRow) {
			this.variant    = variant;
			this.degree     = degree;
			this.firstPickup = firstPickup;
			this.source     = source;
			this.localRow   = localRow;
		}
	}
}
