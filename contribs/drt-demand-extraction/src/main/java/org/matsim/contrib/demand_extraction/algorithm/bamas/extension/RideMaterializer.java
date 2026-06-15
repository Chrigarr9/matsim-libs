package org.matsim.contrib.demand_extraction.algorithm.bamas.extension;

import org.matsim.contrib.demand_extraction.algorithm.bamas.ride.OrderingCodec;
import org.matsim.contrib.demand_extraction.algorithm.bamas.ride.RideRow;
import org.matsim.contrib.demand_extraction.algorithm.bamas.ride.RideLayer;
import org.matsim.contrib.demand_extraction.algorithm.bamas.ride.RideMetricScaling;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideKind;
import org.matsim.contrib.demand_extraction.algorithm.generation.PairGenerator;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;
import org.matsim.contrib.demand_extraction.demand.RequestResolver;

import java.util.Map;

/**
 * Replays a winning ride ordering held as a compact {@link RideLayer} row back
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
 * Task 12 wraps this same core in a streaming {@code ColumnarRideStore}; the
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
	 * {@code PairGenerator.generatePairs} recorded into the pair layer). The degree-2
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
			PairGenerator pairGenerator, RequestResolver resolver) {
		this.network = network;
		this.budgetValidator = budgetValidator;
		this.pairGenerator = pairGenerator;
		// Positional (generation-copy) view of the engine's shared resolver; the degree-2 pair
		// branch resolves each request by reqArray[position] — see the class javadoc / RequestResolver.
		this.reqArray = resolver == null ? null : resolver.positionalArray();
	}

	/**
	 * Materialize one stub row into a full {@link Ride}.
	 *
	 * <p>This always populates {@code remainingBudgets} via
	 * {@link BudgetValidator#validateAndPopulateBudgets}. Budget validation is always inline
	 * during extension, so every stored (winning) ordering already passed budget validation
	 * and the result here is non-null; a null is a hard error (a stored ordering should never
	 * be budget-infeasible).
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
	public Ride materialize(RideLayer cols, int row, Map<Integer, DrtRequest> requestById) {
		int degree = cols.degree();
		int[] originsLocal = OrderingCodec.unpack(cols.originOrder(row), degree);
		int[] sortedSet = cols.requestIndices(row); // sorted ascending global indices
		RideKind kind = RideRow.flagsToKind(cols.flags(row));

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
						+ "(row " + row + "); pair stubs can only be materialized on the streaming pairLayerPath");
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
			// MIXED (RideRow.fromRide), so passing the decoded kind is a no-op for them.
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
		int distDm = RideMetricScaling.toDeci(ride.getRideDistance());
		int ttDs = RideMetricScaling.toDeci(ride.getRideTravelTime());
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

	/**
	 * Receiver of one leg {@code (from, to, departureTime)} as it is enumerated by
	 * {@link #forEachLegSegment}.
	 */
	@FunctionalInterface
	public interface SegmentConsumer {
		void accept(org.matsim.api.core.v01.Id<org.matsim.api.core.v01.network.Link> from,
				org.matsim.api.core.v01.Id<org.matsim.api.core.v01.network.Link> to,
				double departureTime);
	}

	/**
	 * Enumerate the leg segments a degree-3+ stub row is built from, in the SAME order and at the
	 * SAME cumulative departure times as {@link BamasRideExtender#buildRideFromOrdering} (the
	 * {@code preConn == null} branch). This is the single source of the row's segment key set: the
	 * materializer routes exactly these legs at export, and the engine promotes exactly these legs
	 * at the degree barrier (Task 7 Step 3). Sharing the walk guarantees promotion keys equal export
	 * keys by construction, so promotion adds no new cache key.
	 *
	 * <p>Walking advances the clock by the cached travel time of each leg ({@code currentTime +=
	 * connTT}), so the consumer sees the exact bins {@code getSegment} will use. The lookups are
	 * cache hits (these legs were routed during enumeration); an unreachable leg short-circuits the
	 * walk exactly as {@code buildRideFromOrdering} returns null there.
	 *
	 * <p>Degree-2 is intentionally rejected: pair stubs route at a fixed bin (not cumulative), and
	 * their segments are promoted at pair-generation acceptance (Task 7 Step 2), not here.
	 *
	 * @param network      routing cache (its {@code getSegment} both warms the clock and returns
	 *                     the cached value)
	 * @param cols         the per-degree layer (degree must be {@code >= 3})
	 * @param row          row index within {@code cols}
	 * @param requestById  global request lookup keyed by {@link DrtRequest#index}, built identically
	 *                     to {@link #materialize}'s
	 * @param consumer     invoked once per leg with {@code (from, to, departureTime)}
	 */
	public static void forEachLegSegment(MatsimNetworkCache network, RideLayer cols, int row,
			Map<Integer, DrtRequest> requestById, SegmentConsumer consumer) {
		int degree = cols.degree();
		if (degree < 3) {
			throw new IllegalArgumentException(
					"forEachLegSegment is degree-3+ only (degree " + degree + "); degree-2 pair "
					+ "segments are promoted at pair-generation acceptance");
		}
		int[] sortedSet = cols.requestIndices(row);
		int[] originsLocal = OrderingCodec.unpack(cols.originOrder(row), degree);
		int[] destsLocal = OrderingCodec.unpack(cols.destOrder(row), degree);

		// Connection sequence [O_1..O_n, D_1..D_n], identical to buildRideFromOrdering.
		@SuppressWarnings("unchecked")
		org.matsim.api.core.v01.Id<org.matsim.api.core.v01.network.Link>[] sequence =
				(org.matsim.api.core.v01.Id<org.matsim.api.core.v01.network.Link>[])
						new org.matsim.api.core.v01.Id[degree * 2];
		for (int i = 0; i < degree; i++) {
			sequence[i] = requestById.get(sortedSet[originsLocal[i]]).originLinkId;
		}
		for (int i = 0; i < degree; i++) {
			sequence[degree + i] = requestById.get(sortedSet[destsLocal[i]]).destinationLinkId;
		}

		double startTime = requestById.get(sortedSet[originsLocal[0]]).getRequestTime();
		double currentTime = startTime;
		for (int i = 0; i < degree * 2 - 1; i++) {
			consumer.accept(sequence[i], sequence[i + 1], currentTime);
			org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment seg =
					network.getSegment(sequence[i], sequence[i + 1], currentTime);
			if (!seg.isReachable()) return; // mirrors buildRideFromOrdering's early null
			currentTime += seg.getTravelTime();
		}
	}
}
