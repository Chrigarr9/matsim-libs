package org.matsim.contrib.demand_extraction.algorithm.bamas.stub;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.matsim.contrib.demand_extraction.algorithm.bamas.extension.RideMaterializer;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideVariant;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

/**
 * Stub-backed {@link RideStore} that streams the engine's final door-to-door output
 * without ever holding the full fat ride list in memory.
 *
 * <h3>When it is used</h3>
 * Only on the memory-critical D2D path: {@code stubModeEnabled && !enableStopBased}
 * (the Lyon parity gate and the 100% target run). When stop-based pooling is enabled,
 * Phase 5 needs the materialized D2D rides as <em>input</em>, so the engine stays on the
 * existing fat path ({@link MaterializedRideStore}) — this store is never constructed
 * there. It is therefore strictly D2D and carries no stop-to-stop source.
 *
 * <h3>What it replaces</h3>
 * The old engine path (pre-Task-12) materialized every degree-3+ stub layer into fat
 * {@link Ride} objects, appended them to {@code allRides} alongside the fat singles +
 * pairs, then ran a single global stable sort by {@code (variant, degree, firstPickup)}
 * followed by a sequential reindex loop ({@code toBuilder().index(i).build()}). That held
 * the full fat list AND its rebuilt clone simultaneously — the 2× peak-memory hazard.
 *
 * <p>This store folds the batch-materialize, the sort and the reindex into a single
 * streaming pass. Stub rows are materialized lazily, one at a time, via
 * {@link RideMaterializer}; only the lightweight per-row sort keys live in memory.
 *
 * <h3>Byte-identical to the old sort, by construction</h3>
 * The old code built {@code allRides} in a fixed insertion order — singles, then pairs,
 * then each extension degree's lex-sorted result list — and applied a STABLE sort by
 * {@code (variant, degree, firstPickup)}. Java's {@link List#sort} is stable, so rows that
 * tie on all three keys keep their insertion order. We reproduce this exactly:
 * <ul>
 *   <li>We build a row-reference array in the SAME concatenation order: fat singles →
 *       fat pairs → stub layer 3 → stub layer 4 → … </li>
 *   <li>We stable-sort the row-refs (cheap int tuples, not {@link Ride} objects) by the
 *       same three keys.</li>
 * </ul>
 * Same elements, same initial order, same comparator, same stable sort ⇒ identical
 * permutation, hence identical output order and identical sequential indices.
 *
 * <h3>Why a global sort, not a k-way merge</h3>
 * Each stub layer is stored in {@code setsFlat}-lex (sorted-set ascending) order, which
 * is NOT {@code firstPickup} order — {@code firstPickup = sortedSet[unpack(originOrder)[0]]}
 * is independent of the sorted-set lex order. So the layers are not pre-sorted runs on the
 * sort key, and a merge that assumed that would silently reorder. A single global stable
 * sort sidesteps the trap and is the literal mirror of the old {@code allRides.sort(...)}.
 *
 * <h3>Index stamping</h3>
 * The old reindex overwrote EVERY ride's index (singles and pairs included). We do the
 * same: each emitted ride (fat single/pair or freshly materialized stub) gets
 * {@link Ride#assignIndex} to its post-sort sequential position. {@link RideMaterializer}
 * returns a fresh, transient ride per call, and the fat sources are no longer hash-map keys
 * at export time, so the in-place stamp is safe.
 *
 * <h3>Contract</h3>
 * {@link #forEachMaterialized} hands the visitor a freshly built ride per stub row; the
 * fat-source rides are long-lived. Neither is reused across calls, so retaining is benign
 * in practice — but callers must still honour the {@link RideStore} no-retain contract.
 */
public final class StubRideStore implements RideStore {

	/** Source tag: a fat ride (single or pair). */
	private static final int SRC_FAT = -1;

	private final List<Ride> fatSingularPairs; // singles + pairs (D2D), in insertion order
	private final List<StubColumns> stubLayers; // degree-3+ D2D layers, in degree order
	private final RideMaterializer materializer;
	private final java.util.Map<Integer, DrtRequest> requestById;

	/** Total number of rides across all sources. */
	private final int total;

	/**
	 * Post-sort permutation. {@code sourceOf[r]} / {@code localRowOf[r]} identify which
	 * source and local row the r-th output ride comes from:
	 * <ul>
	 *   <li>{@code sourceOf[r] == SRC_FAT}: {@code localRowOf[r]} indexes {@code fatSingularPairs}.</li>
	 *   <li>{@code sourceOf[r] >= 0}: {@code sourceOf[r]} is the stub-layer index and
	 *       {@code localRowOf[r]} the row within that layer.</li>
	 * </ul>
	 */
	private final int[] sourceOf;
	private final int[] localRowOf;

	/**
	 * @param fatSingularPairs singles + pairs (already in the engine's insertion order); D2D
	 * @param stubLayers       degree-3+ stub layers in degree order (lex-sorted within layer)
	 * @param materializer     stateless replayer (network + budget validator)
	 * @param requestById      global index → request map (resolve by index, never array pos)
	 */
	public StubRideStore(List<Ride> fatSingularPairs,
			List<StubColumns> stubLayers,
			RideMaterializer materializer,
			java.util.Map<Integer, DrtRequest> requestById) {
		this.fatSingularPairs = fatSingularPairs;
		this.stubLayers = stubLayers;
		this.materializer = materializer;
		this.requestById = requestById;

		int stubRows = 0;
		for (StubColumns layer : stubLayers) {
			stubRows += layer.size();
		}
		this.total = fatSingularPairs.size() + stubRows;

		this.sourceOf = new int[total];
		this.localRowOf = new int[total];
		computeOrder();
	}

	/**
	 * Build the row-reference array in the engine's exact concatenation order, then
	 * stable-sort by {@code (variant, degree, firstPickup)} — the literal mirror of the
	 * old {@code allRides.sort(...)} on a list built in this same order.
	 */
	private void computeOrder() {
		// Row descriptors in concatenation order: fat (singles, pairs) → stub layers
		// (degree order). Each holds its sort keys plus a (source, localRow) reference.
		// Lightweight — int tuples, not Ride objects.
		int fatCount = fatSingularPairs.size();
		List<RowRef> refs = new ArrayList<>(total);

		for (int i = 0; i < fatCount; i++) {
			Ride r = fatSingularPairs.get(i);
			int[] idx = r.getRequestIndices();
			int firstPickup = idx.length > 0 ? idx[0] : Integer.MAX_VALUE;
			refs.add(new RowRef(r.getVariant().ordinal(), r.getDegree(), firstPickup, SRC_FAT, i));
		}
		for (int s = 0; s < stubLayers.size(); s++) {
			StubColumns layer = stubLayers.get(s);
			int degree = layer.degree();
			for (int row = 0; row < layer.size(); row++) {
				int firstLocal = OrderingCodec.unpack(layer.originOrder(row), degree)[0];
				int firstPickup = layer.requestIndices(row)[firstLocal];
				refs.add(new RowRef(RideVariant.DOOR_TO_DOOR.ordinal(), degree, firstPickup, s, row));
			}
		}

		// STABLE sort by (variant, degree, firstPickup). List.sort is guaranteed stable,
		// so all-key ties keep insertion (concatenation) order — matching the old code.
		refs.sort((a, b) -> {
			int c = Integer.compare(a.variant, b.variant);
			if (c != 0) return c;
			c = Integer.compare(a.degree, b.degree);
			if (c != 0) return c;
			return Integer.compare(a.firstPickup, b.firstPickup);
		});

		for (int r = 0; r < total; r++) {
			RowRef ref = refs.get(r);
			sourceOf[r] = ref.source;
			localRowOf[r] = ref.localRow;
		}
	}

	/**
	 * Materialize the r-th output ride WITHOUT stamping its index. Stub rows are replayed
	 * via {@link RideMaterializer} (cache-hit re-route); fat rows are returned by reference.
	 */
	private Ride materializeRaw(int row) {
		int src = sourceOf[row];
		int local = localRowOf[row];
		if (src == SRC_FAT) {
			return fatSingularPairs.get(local);
		}
		return materializer.materialize(stubLayers.get(src), local, requestById);
	}

	@Override
	public int size() {
		return total;
	}

	@Override
	public Ride materialize(int row) {
		Ride ride = materializeRaw(row);
		ride.assignIndex(row); // post-sort sequential index, matching the old reindex loop
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
		int src = sourceOf[row];
		int local = localRowOf[row];
		if (src == SRC_FAT) {
			// Match MaterializedRideStore: pickup-order indices (Ride.getRequestIndices()).
			return fatSingularPairs.get(local).getRequestIndices();
		}
		// Pickup-order indices from the stub: originsOrdered[i] = sortedSet[unpack(origin)[i]].
		// Matches Ride.getRequestIndices() semantics (requests[] == originsOrdered) without
		// a full materialization.
		StubColumns layer = stubLayers.get(src);
		int degree = layer.degree();
		int[] originLocal = OrderingCodec.unpack(layer.originOrder(local), degree);
		int[] sortedSet = layer.requestIndices(local);
		int[] pickupOrder = new int[degree];
		for (int i = 0; i < degree; i++) {
			pickupOrder[i] = sortedSet[originLocal[i]];
		}
		return pickupOrder;
	}

	/** Lightweight per-row sort descriptor: keys plus a (source, localRow) reference. */
	private static final class RowRef {
		final int variant;
		final int degree;
		final int firstPickup;
		final int source;
		final int localRow;

		RowRef(int variant, int degree, int firstPickup, int source, int localRow) {
			this.variant = variant;
			this.degree = degree;
			this.firstPickup = firstPickup;
			this.source = source;
			this.localRow = localRow;
		}
	}
}
