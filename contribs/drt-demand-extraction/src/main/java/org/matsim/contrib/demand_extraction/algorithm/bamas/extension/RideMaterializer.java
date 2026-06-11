package org.matsim.contrib.demand_extraction.algorithm.bamas.extension;

import org.matsim.contrib.demand_extraction.algorithm.bamas.stub.OrderingCodec;
import org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns;
import org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubScaling;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

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

	public RideMaterializer(MatsimNetworkCache network, BudgetValidator budgetValidator) {
		this.network = network;
		this.budgetValidator = budgetValidator;
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
	 * @param requestTable global request array indexed by {@link DrtRequest#index}
	 * @return the materialized, budget-populated ride (index 0; the engine re-indexes
	 *         after the final sort)
	 */
	public Ride materialize(StubColumns cols, int row, DrtRequest[] requestTable) {
		int degree = cols.degree();
		int[] originsLocal = OrderingCodec.unpack(cols.originOrder(row), degree);
		int[] destsLocal = OrderingCodec.unpack(cols.destOrder(row), degree);
		int[] sortedSet = cols.requestIndices(row); // sorted ascending global indices

		DrtRequest[] originsOrdered = new DrtRequest[degree];
		DrtRequest[] destsOrdered = new DrtRequest[degree];
		for (int i = 0; i < degree; i++) {
			originsOrdered[i] = requestTable[sortedSet[originsLocal[i]]];
			destsOrdered[i] = requestTable[sortedSet[destsLocal[i]]];
		}

		// Faithful replay: same buildRideFromOrdering call with null preConn (cache-hit
		// re-route), then the same non-deferred budget population.
		Ride ride = BamasRideExtender.buildRideFromOrdering(
				network, originsOrdered, destsOrdered, 0, null, null, null);
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
