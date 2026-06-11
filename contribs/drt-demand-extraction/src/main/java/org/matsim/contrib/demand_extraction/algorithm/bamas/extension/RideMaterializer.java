package org.matsim.contrib.demand_extraction.algorithm.bamas.extension;

import org.matsim.contrib.demand_extraction.algorithm.bamas.stub.OrderingCodec;
import org.matsim.contrib.demand_extraction.algorithm.bamas.stub.RideStub;
import org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns;
import org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubScaling;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideKind;
import org.matsim.contrib.demand_extraction.algorithm.generation.PairGenerator;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

import java.util.Map;

/**
 * Replays a winning ride ordering held as a compact {@link StubColumns} row back
 * into a full {@link Ride}, bit-exactly reproducing what the extension phase
 * produced.
 *
 * <h3>Why this reproduces the fat path exactly</h3>
 * Materialization is the SAME two calls the extender made on the SAME ordering:
 * <ol>
 *   <li>{@link BamasRideExtender#buildRideFromOrdering} with {@code preConn == null},
 *       which re-routes each segment via the {@link MatsimNetworkCache}. Every
 *       winning ordering was routed during extension, so each segment is a cache
 *       hit; on a (deterministic) cache miss the network re-routes to the same
 *       result. The resulting {@code connDist}/{@code connTT} arrays therefore
 *       match the enumerator's pre-routed arrays.</li>
 *   <li>{@link BudgetValidator#validateAndPopulateBudgets} — exactly the
 *       non-deferred path in {@code evaluateOrdering}. Because the connection
 *       arrays match bit-for-bit, {@code remainingBudgets} matches bit-for-bit.</li>
 * </ol>
 *
 * <h3>Self-check</h3>
 * As a defensive guard (and the single highest-value local check before the
 * 18-minute Lyon parity gate), {@link #materialize} asserts that the rounded
 * ride distance and travel time of the rebuilt ride match the stub's stored
 * decimetre / decisecond columns. A mismatch means the re-routed connection
 * arrays diverged from the enumerator's — caught here, not at the gate.
 *
 * <h3>Reuse</h3>
 * Task 12 wraps this same core in a streaming {@code StubRideStore}; the
 * stateless instance ({@code network} + {@code budgetValidator}) is the unit of
 * reuse.
 */
public final class RideMaterializer {

	private final MatsimNetworkCache network;
	private final BudgetValidator budgetValidator;
	/**
	 * Task 13: degree-2 pair stubs must be rebuilt by reproducing the generator's
	 * fixed-{@code reqI.requestTime} routing, NOT the cumulative-clock
	 * {@code buildRideFromOrdering} used for degree-3+. See {@link #materialize} and
	 * {@link PairGenerator#rebuildPair}. Null on paths that never carry a degree-2 stub
	 * layer (fat-stub path), in which case a degree-2 materialize is a hard error.
	 */
	private final PairGenerator pairGenerator;
	/**
	 * Task 13: the raw generation request array (same instance whose positions
	 * {@code PairGenerator.generatePairStubs} recorded into the pair layer). The degree-2
	 * branch resolves a pair's requests by {@code reqArray[position]} — the exact generation
	 * COPY — NOT by {@code requestById.get(index)}, because Paper-2 Extension-2 hub copies
	 * collide on one {@code index} and the map's last-write-wins canonical copy can carry a
	 * different origin/dest than the one the pair was routed from. Null on the no-pair paths
	 * (degree-2 materialize is a hard error there).
	 */
	private final DrtRequest[] reqArray;

	public RideMaterializer(MatsimNetworkCache network, BudgetValidator budgetValidator) {
		this(network, budgetValidator, null, null);
	}

	public RideMaterializer(MatsimNetworkCache network, BudgetValidator budgetValidator,
			PairGenerator pairGenerator, DrtRequest[] reqArray) {
		this.network = network;
		this.budgetValidator = budgetValidator;
		this.pairGenerator = pairGenerator;
		this.reqArray = reqArray;
	}

	/**
	 * Materialize one stub row into a full {@link Ride}.
	 *
	 * <p>NON-DEFERRED contract: this always populates {@code remainingBudgets} via
	 * {@link BudgetValidator#validateAndPopulateBudgets}, matching the gate scenario
	 * ({@code deferExtensionBudgetValidation=false}). The winning ordering already
	 * passed budget validation during extension, so the result is non-null.
	 *
	 * @param cols         the per-degree layer
	 * @param row          row index within {@code cols}
	 * @param requestById  global request lookup keyed by {@link DrtRequest#index},
	 *                     built identically to the extender's {@code requestMap}
	 *                     (last-write-wins on a shared index). Used by the DEGREE-3+
	 *                     branch only. Positional indexing into reqArray is NOT safe
	 *                     for degree-3+: Paper-2 Extension-2 hub expansion emits virtual
	 *                     copies that share the parent's {@code index}, so
	 *                     {@code index != array position} and several requests can
	 *                     collide on one index. The fat extender resolves every set
	 *                     member through {@code requestMap.get(newSet[i])} (canonical
	 *                     last-write-wins), so the winning degree-3+ ride is built from
	 *                     the map's canonical copy — the pair's original copy identity
	 *                     is intentionally FORGOTTEN at extension. Resolving here the
	 *                     same way reproduces the exact degree-3+ origin/dest links.
	 *
	 *                     <p>The DEGREE-2 branch does the OPPOSITE: it resolves each
	 *                     pair request by {@code reqArray[position]} (the raw generation
	 *                     copy), using the position column the pair layer stored at
	 *                     generation. A pair was routed from specific reqArray copies;
	 *                     {@code requestById.get(index)} could return a different
	 *                     colliding copy (different OD) and break the route. So degree-2
	 *                     keeps copy identity (positions), degree-3+ uses the canonical
	 *                     copy (index) — mirroring master in BOTH cases.
	 * @return the materialized, budget-populated ride (index 0; the engine re-indexes
	 *         after the final sort)
	 */
	public Ride materialize(StubColumns cols, int row, Map<Integer, DrtRequest> requestById) {
		int degree = cols.degree();
		int[] originsLocal = OrderingCodec.unpack(cols.originOrder(row), degree);
		int[] sortedSet = cols.requestIndices(row); // sorted ascending global indices
		RideKind kind = RideStub.flagsToKind(cols.flags(row));

		Ride ride;
		if (degree == 2) {
			// Task 13 — degree-2 pair layer. These stubs were generated by PairGenerator,
			// which routes ALL pair segments at the single fixed bin reqI.requestTime.
			// buildRideFromOrdering instead routes at a cumulative clock (currentTime += connTT[i]),
			// which lands in time bins the pair generator never warmed and can yield a false
			// on-demand unreachable. So we rebuild via PairGenerator.rebuildPair at the same fixed
			// bin, reproducing the generator's segments (and thus distance/time) bit-for-bit. The
			// distDm/ttDs self-check below guards the reproduction.
			if (pairGenerator == null || reqArray == null) {
				throw new IllegalStateException(
						"Degree-2 stub layer encountered with no PairGenerator/reqArray wired into RideMaterializer "
						+ "(row " + row + "); pair stubs can only be materialized on the streaming pairStubPath");
			}
			// Resolve by reqArray POSITION, not requestById — the pair layer stored the exact
			// generation copy's position (Task 13). Under Ext-2 hub-index collision, resolving
			// by index would pick a different colliding copy (different OD) than the one the pair
			// was routed from, yielding a ~1.8x-wrong route and the distDm/ttDs self-check crash.
			int[] positions = cols.positionIndices(row); // aligned to sortedSet
			// pickup order: reqI = first pickup (local 0), reqJ = second pickup (local 1)
			DrtRequest reqI = reqArray[positions[originsLocal[0]]];
			DrtRequest reqJ = reqArray[positions[originsLocal[1]]];
			ride = pairGenerator.rebuildPair(reqI, reqJ, kind);
		} else {
			int[] destsLocal = OrderingCodec.unpack(cols.destOrder(row), degree);
			DrtRequest[] originsOrdered = new DrtRequest[degree];
			DrtRequest[] destsOrdered = new DrtRequest[degree];
			for (int i = 0; i < degree; i++) {
				originsOrdered[i] = requestById.get(sortedSet[originsLocal[i]]);
				destsOrdered[i] = requestById.get(sortedSet[destsLocal[i]]);
			}
			// Faithful replay: same buildRideFromOrdering call with null preConn (cache-hit
			// re-route), then the same non-deferred budget population. Degree-3+ stubs encode
			// MIXED (RideStub.fromRide), so passing the decoded kind is a no-op for them.
			ride = BamasRideExtender.buildRideFromOrdering(
					network, originsOrdered, destsOrdered, 0, null, null, null, kind);
		}
		if (ride == null) {
			throw new IllegalStateException(
					"Materialize re-route failed for a previously-winning ordering at row " + row
					+ " (degree " + degree + "); the cache miss did not reproduce a reachable route");
		}

		// Self-check: rounded aggregates must match the stub's stored columns. If these
		// match, the connection arrays match, so remainingBudgets matches (gate suspect #1).
		int distDm = StubScaling.toDeci(ride.getRideDistance());
		int ttDs = StubScaling.toDeci(ride.getRideTravelTime());
		if (distDm != cols.rideDistanceDm(row) || ttDs != cols.travelTimeDs(row)) {
			throw new IllegalStateException(String.format(
					"Materialize parity mismatch at degree %d row %d: "
					+ "distDm rebuilt=%d stored=%d, ttDs rebuilt=%d stored=%d. "
					+ "Re-routed connection arrays diverged from the enumerator's pre-routed arrays.",
					degree, row, distDm, cols.rideDistanceDm(row), ttDs, cols.travelTimeDs(row)));
		}

		Ride validated = budgetValidator.validateAndPopulateBudgets(ride);
		if (validated == null) {
			throw new IllegalStateException(
					"Materialize budget validation failed for a previously-winning ordering at row "
					+ row + " (degree " + degree + ")");
		}
		return validated;
	}
}
