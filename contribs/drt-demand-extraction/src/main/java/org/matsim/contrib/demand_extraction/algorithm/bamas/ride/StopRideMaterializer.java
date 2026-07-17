package org.matsim.contrib.demand_extraction.algorithm.bamas.ride;

import java.util.Map;

import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideKind;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideVariant;
import org.matsim.contrib.demand_extraction.algorithm.domain.StopLocation;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

/**
 * Pinned-stop replay materializer for stop-to-stop (S2S) stub rows.
 *
 * <h3>What it does</h3>
 * Reconstructs a full {@link Ride} with {@link RideVariant#STOP_TO_STOP} from a compact
 * {@link StopRideLayer} row WITHOUT re-running stop discovery.  The stops are PINNED
 * (read from the {@link StopLocationDictionary}), the walk distances are read from the
 * stub's exact-double columns, and the S2S segment is re-routed via
 * {@link MatsimNetworkCache#getSegment} at the stored {@code startTime} — the same call
 * the master path made, so the result is a cache hit and the connection arrays match
 * bit-exactly.
 *
 * <h3>Why this reproduces master bit-exactly</h3>
 * {@link org.matsim.contrib.demand_extraction.algorithm.generation.StopBasedRideGenerator#convertToStopBasedBudgetAware}
 * (and the legacy variant) constructs the S2S {@link Ride} with:
 * <ol>
 *   <li>Requests from the D2D source ride (preserved in the stub's request-index column).</li>
 *   <li>Pickup/dropoff stops pinned from the conversion result (preserved in the stub's
 *       stop-id columns via {@link StopLocationDictionary}).</li>
 *   <li>The S2S segment from
 *       {@code networkCache.getSegment(pickupLinkId, dropoffLinkId, doorToDoor.getStartTime())}
 *       — the start time is stored in the stub so this re-routes at the same bin.</li>
 *   <li>Walk distances stored as exact doubles (no float conversion).</li>
 *   <li>Per-passenger arrays recomputed from the above (arithmetic order is the same).</li>
 *   <li>Remaining budgets via {@link BudgetValidator#calculateDrtScoreWithWalks} — same
 *       arguments, same result.</li>
 * </ol>
 *
 * <h3>Ordering (origins / destinations)</h3>
 * The per-passenger arrays are indexed by pickup order ({@code originsOrdered}), matching
 * the fat path's {@code requests[] IS originsOrdered} convention (HYP-3). The S2S ride
 * inherits the D2D parent's pickup and dropoff orderings — the {@link Ride} builder
 * receives {@code originsOrderedRequests} and {@code destinationsOrderedRequests} resolved
 * from the stub's {@code requestIndices} + {@code originOrder} / {@code destOrder}.
 *
 * <h3>Budget-aware vs legacy path</h3>
 * Replay uses the <em>budget-aware</em> delay convention (
 * {@code delays[i] = accessTime[i]}) when {@link ExMasConfigGroup#isEnableBudgetAwareConstraints()}
 * is true, matching
 * {@link org.matsim.contrib.demand_extraction.algorithm.generation.StopBasedRideGenerator#convertToStopBasedBudgetAware}.
 * Otherwise the legacy formula {@code delays[i] = totalTime - directTime} is used.
 */
public final class StopRideMaterializer {

	private final MatsimNetworkCache network;
	private final BudgetValidator budgetValidator;
	private final StopLocationDictionary stopDictionary;
	private final ExMasConfigGroup config;

	/**
	 * @param network        network cache (deterministic re-route on miss)
	 * @param budgetValidator for {@code calculateDrtScoreWithWalks}
	 * @param stopDictionary  the dictionary built during Phase 5 stubbification
	 * @param config          for walk speed and {@code enableBudgetAwareConstraints}
	 */
	public StopRideMaterializer(MatsimNetworkCache network, BudgetValidator budgetValidator,
			StopLocationDictionary stopDictionary, ExMasConfigGroup config) {
		this.network = network;
		this.budgetValidator = budgetValidator;
		this.stopDictionary = stopDictionary;
		this.config = config;
	}

	/**
	 * Materialize one S2S stub row into a full {@link Ride}.
	 *
	 * @param cols         the per-degree S2S stub layer
	 * @param row          row index within {@code cols}
	 * @param requestById  global request lookup (keyed by {@link DrtRequest#index})
	 * @return the materialized S2S ride (index 0; the caller re-stamps it with the
	 *         post-sort sequential index)
	 * @throws IllegalStateException if the S2S segment cannot be routed or budget
	 *         validation fails for a previously-winning row
	 */
	public Ride materialize(StopRideLayer cols, int row,
			Map<Integer, DrtRequest> requestById) {
		int degree = cols.degree();

		// --- 1. Resolve requests ---
		int[] sortedSet = cols.requestIndices(row);
		int[] originsLocal = OrderingCodec.unpack(cols.originOrder(row), degree);
		int[] destsLocal   = OrderingCodec.unpack(cols.destOrder(row), degree);

		DrtRequest[] originsOrdered = new DrtRequest[degree];
		DrtRequest[] destsOrdered   = new DrtRequest[degree];

		for (int i = 0; i < degree; i++) {
			originsOrdered[i] = requestById.get(sortedSet[originsLocal[i]]);
			destsOrdered[i]   = requestById.get(sortedSet[destsLocal[i]]);
		}

		// --- 2. Resolve stops ---
		StopLocation pickupStop  = stopDictionary.byId(cols.pickupStopId(row));
		StopLocation dropoffStop = stopDictionary.byId(cols.dropoffStopId(row));
		double startTime = cols.startTime(row);

		// --- 3. Re-route the S2S segment (cache hit → bit-exact match) ---
		Link pickupLink  = network.getNetwork().getLinks().get(pickupStop.getLinkId());
		Link dropoffLink = network.getNetwork().getLinks().get(dropoffStop.getLinkId());
		if (pickupLink == null || dropoffLink == null) {
			throw new IllegalStateException(
					"S2S materializer: stop link not found in network at row " + row
					+ " (degree " + degree + ")");
		}

		TravelSegment segment = network.getSegment(
				pickupStop.getLinkId(), dropoffStop.getLinkId(), startTime);
		if (segment == null) {
			throw new IllegalStateException(
					"S2S materializer: cannot re-route S2S segment at row " + row
					+ " (degree " + degree + "); was routable during Phase 5");
		}

		double inVehicleTime     = segment.getTravelTime();
		double inVehicleDistance = segment.getDistance();
		double inVehicleUtility  = segment.getNetworkUtility();

		// --- 4. Walk distances (exact doubles from stub) ---
		double[] accessWalk = cols.accessWalk(row);
		double[] egressWalk = cols.egressWalk(row);
		double walkSpeed    = config.getWalkSpeedMps();

		// --- 5. Per-passenger arrays ---
		double[] passengerTravelTimes    = new double[degree];
		double[] passengerDistances      = new double[degree];
		double[] passengerNetUtilities   = new double[degree];
		double[] delays                  = new double[degree];
		double[] detours                 = new double[degree];

		boolean budgetAware = config.isEnableBudgetAwareConstraints();

		for (int i = 0; i < degree; i++) {
			double accessTime = accessWalk[i] / walkSpeed;
			double egressTime = egressWalk[i] / walkSpeed;

			passengerTravelTimes[i]  = accessTime + inVehicleTime + egressTime;
			passengerDistances[i]    = accessWalk[i] + inVehicleDistance + egressWalk[i];
			passengerNetUtilities[i] = inVehicleUtility;

			// Delay convention mirrors StopBasedRideGenerator exactly.
			// HYP-3: every per-pax quantity is indexed by PICKUP order
			// (originsOrdered) — the order the stub stored the walk arrays in
			// (BamasEngine.materializeS2SRide passes ride.getAccessWalkDistances()
			// verbatim, and the fat path's requests[] IS originsOrdered). The
			// sorted set is used ONLY to resolve the packed orderings above.
			if (budgetAware) {
				// A8 — preplanned service: pickup-wait = access walk time only.
				delays[i] = accessTime;
			} else {
				// Legacy: total travel time - direct travel time.
				delays[i] = passengerTravelTimes[i] - originsOrdered[i].getTravelTime();
			}

			detours[i] = originsOrdered[i].getTravelTime() > 0
					? passengerTravelTimes[i] / originsOrdered[i].getTravelTime()
					: 1.0;
		}

		// --- 6. Remaining budgets ---
		double[] remainingBudgets = calculateBudgets(
				originsOrdered, delays, passengerTravelTimes, passengerDistances,
				accessWalk, egressWalk);
		if (remainingBudgets == null) {
			throw new IllegalStateException(
					"S2S materializer: budget validation failed at row " + row
					+ " (degree " + degree + "); was valid during Phase 5");
		}

		// Self-check: quantised distance and travel time must match stub columns.
		// Recompute from the re-routed segment (NOT from passengerTravelTimes, which
		// include walk time — rideDistance and rideTravelTime are the IN-VEHICLE segment).
		int distDmRebuilt = RideMetricScaling.toDeci(inVehicleDistance);
		int ttDsRebuilt   = RideMetricScaling.toDeci(inVehicleTime);
		if (distDmRebuilt != cols.rideDistanceDm(row) || ttDsRebuilt != cols.travelTimeDs(row)) {
			throw new IllegalStateException(String.format(
					"S2S materializer parity mismatch at degree %d row %d: "
					+ "distDm rebuilt=%d stored=%d, ttDs rebuilt=%d stored=%d. "
					+ "Re-routed S2S segment diverged from master.",
					degree, row, distDmRebuilt, cols.rideDistanceDm(row),
					ttDsRebuilt, cols.travelTimeDs(row)));
		}

		// --- 7. Build Ride ---
		return Ride.builder()
				.index(0) // caller re-stamps with post-sort sequential index
				.degree(degree)
				.kind(RideRow.flagsToKind(cols.flags(row)))
				.requests(originsOrdered)
				.originsOrderedRequests(originsOrdered)
				.destinationsOrderedRequests(destsOrdered)
				.passengerTravelTimes(passengerTravelTimes)
				.passengerDistances(passengerDistances)
				.passengerNetworkUtilities(passengerNetUtilities)
				.delays(delays)
				.detours(detours)
				.remainingBudgets(remainingBudgets)
				.connectionTravelTimes(new double[]{inVehicleTime})
				.connectionDistances(new double[]{inVehicleDistance})
				.connectionNetworkUtilities(new double[]{inVehicleUtility})
				.startTime(startTime)
				.variant(RideVariant.STOP_TO_STOP)
				.pickupStop(pickupStop)
				.dropoffStop(dropoffStop)
				.accessWalkDistances(accessWalk)
				.egressWalkDistances(egressWalk)
				.build();
	}

	/**
	 * Compute remaining budgets for each passenger via
	 * {@link BudgetValidator#calculateDrtScoreWithWalks} — mirrors
	 * {@code StopBasedRideGenerator.calculateStopBasedBudgets}.
	 *
	 * @return array of remaining budgets, or {@code null} if any passenger has negative budget
	 */
	private double[] calculateBudgets(
			DrtRequest[] requests,
			double[] delays,
			double[] travelTimes,
			double[] distances,
			double[] accessWalkDistances,
			double[] egressWalkDistances) {

		double[] budgets = new double[requests.length];
		for (int i = 0; i < requests.length; i++) {
			double drtScore = budgetValidator.calculateDrtScoreWithWalks(
					requests[i],
					delays[i],
					travelTimes[i],
					distances[i],
					accessWalkDistances[i],
					egressWalkDistances[i]);
			budgets[i] = drtScore - requests[i].bestModeScore;
			if (budgets[i] < 0) {
				return null;
			}
		}
		return budgets;
	}
}
