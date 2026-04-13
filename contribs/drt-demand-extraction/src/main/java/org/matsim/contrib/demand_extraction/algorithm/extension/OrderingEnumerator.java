package org.matsim.contrib.demand_extraction.algorithm.extension;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;
import org.matsim.contrib.demand_extraction.algorithm.graph.ShareabilityGraph;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

import it.unimi.dsi.fastutil.ints.IntList;

/**
 * Enumerates all valid (origin ordering, destination ordering) combinations
 * for a request set, using pairwise FIFO/LIFO constraints from pair rides.
 *
 * <p>Origin ordering is constrained by pair ride DIRECTIONS: if only pair(A,B)
 * exists (A first), then O_A must come before O_B. If both directions exist,
 * either origin order is valid for that pair.
 *
 * <p>Destination ordering is constrained by pair ride KINDS within the chosen
 * direction: FIFO → D_A before D_B; LIFO → D_B before D_A; both → no constraint.
 *
 * <p>Enumeration uses topological sort of constraint DAGs. Typical counts:
 * degree 3: 2-6 orderings, degree 4: 3-18, degree 5: 4-40.
 *
 * <h3>Pruning strategies applied during enumeration</h3>
 * <ul>
 *   <li><b>Travel-time Check A/B/Dropoff</b>: prune when any picked-up passenger's
 *       in-vehicle time would exceed their {@code maxTravelTime}.</li>
 *   <li><b>Delay-window intersection</b>: maintain the running feasible
 *       departure-offset interval across all picked-up and dropped-off passengers
 *       (sound over-approximation at origin placement, exact value at dropoff).
 *       Prune when the intersection goes empty — no single departure can satisfy
 *       all passengers' delay windows simultaneously.</li>
 *   <li><b>Distance branch-and-bound</b>: track the partial routed distance; prune
 *       when {@code partialDist > bestValidDist[0]} (the shortest valid ride found
 *       so far for this set). Candidates are sorted by next-segment distance so
 *       the cut fires on the weakest sibling first.</li>
 * </ul>
 */
public final class OrderingEnumerator {

	/** Feasibility tolerance for delay-window intersection check (seconds). */
	private static final double DELAY_WINDOW_EPSILON = 1e-6;

	/** Pairwise constraint: which FIFO/LIFO pair ride kinds exist in each direction */
	public record PairInfo(
			boolean forwardFifo, boolean forwardLifo,
			boolean reverseFifo, boolean reverseLifo
	) {
		boolean forwardExists() { return forwardFifo || forwardLifo; }
		boolean reverseExists() { return reverseFifo || reverseLifo; }
		boolean forwardOnly() { return forwardExists() && !reverseExists(); }
		boolean reverseOnly() { return !forwardExists() && reverseExists(); }
	}

	/** A valid ordering with its total ride distance and pre-routed segment data. */
	public record Ordering(int[] originPerm, int[] destPerm, double rideDistance,
						   double[] connTT, double[] connDist, double[] connUtil) {
		/** Convenience constructor without segment data (for non-inline-eval paths). */
		public Ordering(int[] originPerm, int[] destPerm, double rideDistance) {
			this(originPerm, destPerm, rideDistance, null, null, null);
		}
	}

	/**
	 * Enumerate all valid orderings for the given request set.
	 *
	 * @param requestIndices sorted request indices for the set
	 * @param graph the shareability graph
	 * @return list of valid orderings, or empty if set is infeasible (disconnected pair)
	 */
	public static List<Ordering> enumerate(int[] requestIndices, ShareabilityGraph graph) {
		int n = requestIndices.length;

		PairInfo[] pairs = extractConstraints(requestIndices, n, graph);
		if (pairs == null) return List.of();

		List<Ordering> result = new ArrayList<>();

		List<int[]> originPerms = enumerateOriginOrderings(n, pairs);

		for (int[] origPerm : originPerms) {
			List<int[]> destPerms = enumerateDestOrderings(n, origPerm, pairs);
			for (int[] destPerm : destPerms) {
				result.add(new Ordering(origPerm, destPerm, Double.NaN));
			}
		}
		return result;
	}

	/**
	 * Enumerate valid orderings with distance-based branch pruning.
	 *
	 * <p>At each recursion depth, candidates are sorted by routed segment distance
	 * from the previous stop. The accumulated partial ride distance is tracked with
	 * cumulative departure times (matching {@code RideExtender.buildRideFromOrdering}
	 * exactly). When partial distance exceeds the threshold, all remaining sorted
	 * candidates are pruned via {@code break}.
	 *
	 * <p>Provably complete: any ordering whose total ride distance &le; threshold is
	 * found. Proof: partial distance is monotonically increasing, so if it exceeds
	 * threshold at step k, total distance (adding more steps) also exceeds.
	 *
	 * @param requestIndices sorted request indices for the set
	 * @param graph the shareability graph
	 * @param network network cache for routed segment lookups
	 * @param requests DrtRequest objects (requests[i] corresponds to requestIndices[i])
	 * @param maxRideDistance maximum allowed total ride distance (meters)
	 * @return list of valid orderings within distance threshold
	 */
	public static List<Ordering> enumeratePruned(
			int[] requestIndices, ShareabilityGraph graph,
			MatsimNetworkCache network, DrtRequest[] requests,
			double maxRideDistance) {

		// Delegate to unpruned enumeration if pruning is disabled
		if (maxRideDistance >= Double.MAX_VALUE / 2) {
			return enumerate(requestIndices, graph);
		}

		int n = requestIndices.length;
		PairInfo[] pairs = extractConstraints(requestIndices, n, graph);
		if (pairs == null) return List.of();

		// Build origin adjacency matrix (same logic as enumerateOriginOrderings)
		Boolean[][] origAdj = new Boolean[n][n];
		for (int a = 0; a < n; a++) {
			for (int b = a + 1; b < n; b++) {
				PairInfo p = lookup(pairs, n, a, b);
				if (p.forwardOnly()) {
					origAdj[a][b] = true; origAdj[b][a] = false;
				} else if (p.reverseOnly()) {
					origAdj[b][a] = true; origAdj[a][b] = false;
				}
			}
		}

		double[] bestFoundDist = { maxRideDistance };

		List<Ordering> result = new ArrayList<>();
		enumerateOriginsPruned(origAdj, n, pairs, network, requests,
				maxRideDistance, bestFoundDist, new boolean[n], new int[n], 0,
				0.0, 0.0, result);
		return result;
	}

	/**
	 * Enumerate orderings with inline evaluation and tightening on valid results.
	 *
	 * <p>For each complete ordering, calls the {@code evaluator} which should build the
	 * ride, validate budget, and — if valid — update {@code bestValidDist[0]} to the
	 * ride's distance. The enumeration uses {@code bestValidDist[0]} as the pruning
	 * bound, so the bound tightens only on VALID orderings.
	 *
	 * <p>This is provably correct: if a valid ordering with distance V exists, its
	 * partial distance never exceeds V at any step, and V &le; bestValidDist[0]
	 * (which only decreases), so it is never pruned.
	 *
	 * @param requestIndices sorted request indices for the set
	 * @param graph the shareability graph
	 * @param network network cache for routed segment lookups
	 * @param requests DrtRequest objects (requests[i] corresponds to requestIndices[i])
	 * @param bestValidDist mutable single-element array; starts at maxRideDistance,
	 *        updated by evaluator when a valid ride is found. Used as pruning bound.
	 * @param evaluator called for each complete ordering; should update bestValidDist[0]
	 *        if the ordering produces a valid ride shorter than the current bound
	 */
	public static void enumerateAndEvaluate(
			int[] requestIndices, ShareabilityGraph graph,
			MatsimNetworkCache network, DrtRequest[] requests,
			double[] bestValidDist,
			Consumer<Ordering> evaluator) {

		PairInfo[] constraints = extractConstraints(requestIndices, graph);
		if (constraints == null) return;
		int n = requestIndices.length;

		Boolean[][] origAdj = new Boolean[n][n];
		for (int a = 0; a < n; a++) {
			for (int b = a + 1; b < n; b++) {
				PairInfo p = lookup(constraints, n, a, b);
				if (p.forwardOnly()) {
					origAdj[a][b] = true; origAdj[b][a] = false;
				} else if (p.reverseOnly()) {
					origAdj[b][a] = true; origAdj[a][b] = false;
				}
			}
		}

		double[] connTT = new double[2 * n - 1];
		double[] connDist = new double[2 * n - 1];
		double[] connUtil = new double[2 * n - 1];
		enumerateOriginsPrunedWithEval(origAdj, n, constraints, network, requests,
				requestIndices, bestValidDist, new boolean[n], new int[n], new double[n], 0,
				0.0, 0.0,
				Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
				evaluator,
				connTT, connDist, connUtil);
	}

	/**
	 * Enumerate orderings with a parent-ordering seed for DFS sort bias.
	 *
	 * <p>The DFS visits candidates in a sort order that places parent-consistent
	 * candidates (the next un-placed request from the parent's order, or the new
	 * inserted request) first, with cheapest-next-segment as tie-breaker. This
	 * reaches a valid parent-insertion ordering early, tightening bestValidDist[0]
	 * so that subsequent branches are B&B-cut aggressively.
	 *
	 * @param seedParentOrigin global request indices in parent's origin order (length k-1)
	 * @param seedParentDest   global request indices in parent's dest order (length k-1)
	 * @param seedNewRequest   global request index of the new element (not in parent)
	 */
	public static void enumerateAndEvaluateSeeded(
			int[] requestIndices, ShareabilityGraph graph,
			MatsimNetworkCache network, DrtRequest[] requests,
			double[] bestValidDist,
			int[] seedParentOrigin, int[] seedParentDest, int seedNewRequest,
			Consumer<Ordering> evaluator) {

		PairInfo[] constraints = extractConstraints(requestIndices, graph);
		if (constraints == null) return;
		int n = requestIndices.length;

		Boolean[][] origAdj = new Boolean[n][n];
		for (int a = 0; a < n; a++) {
			for (int b = a + 1; b < n; b++) {
				PairInfo p = lookup(constraints, n, a, b);
				if (p.forwardOnly()) {
					origAdj[a][b] = true; origAdj[b][a] = false;
				} else if (p.reverseOnly()) {
					origAdj[b][a] = true; origAdj[a][b] = false;
				}
			}
		}

		// Remap global seed parent indices to child-local indices (0..n-1).
		int[] seedLocalOrigin = remapToLocal(seedParentOrigin, requestIndices);
		int[] seedLocalDest = remapToLocal(seedParentDest, requestIndices);
		int seedLocalNewRequest = localIndexOf(seedNewRequest, requestIndices);

		double[] connTT = new double[2 * n - 1];
		double[] connDist = new double[2 * n - 1];
		double[] connUtil = new double[2 * n - 1];
		enumerateOriginsSeededWithEval(origAdj, n, constraints, network, requests,
				requestIndices, bestValidDist, new boolean[n], new int[n], new double[n], 0,
				0.0, 0.0,
				Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
				seedLocalOrigin, seedLocalDest, seedLocalNewRequest,
				evaluator, connTT, connDist, connUtil);
	}

	private static int[] remapToLocal(int[] globalOrder, int[] requestIndices) {
		int[] local = new int[globalOrder.length];
		for (int i = 0; i < globalOrder.length; i++) {
			local[i] = localIndexOf(globalOrder[i], requestIndices);
		}
		return local;
	}

	private static int localIndexOf(int globalIdx, int[] requestIndices) {
		for (int i = 0; i < requestIndices.length; i++) {
			if (requestIndices[i] == globalIdx) return i;
		}
		throw new IllegalStateException("global index " + globalIdx + " not in requestIndices");
	}

	/**
	 * Returns the child-local index of the next parent request not yet placed, or -1 if all placed.
	 */
	private static int nextUnplacedInSeed(int[] seedLocalOrigin, boolean[] used) {
		for (int r : seedLocalOrigin) {
			if (!used[r]) return r;
		}
		return -1;
	}

	/**
	 * Rank for parent-consistent sort: 0 if candidate is the next parent request or
	 * the new request (both are parent-consistent choices), 1 otherwise.
	 */
	private static int parentConsistentRank(int candidate, int nextParentLocal, int newRequestLocal) {
		if (candidate == nextParentLocal) return 0;
		if (candidate == newRequestLocal) return 0;
		return 1;
	}

	/**
	 * Seeded variant of {@link #enumerateOriginsPrunedWithEval}: identical except the candidate
	 * sort uses a two-level comparator — primary: parent-consistent rank (0 = next parent request
	 * or the new request, 1 = other), secondary: cheapest-next-segment distance. This ensures
	 * the DFS visits a parent-consistent ordering first, tightening bestValidDist[0] early.
	 *
	 * <p>At depth == n, calls the seeded {@link #enumerateDestPrunedSeededWithEval} so the
	 * dest DFS also visits parent-consistent candidates first.
	 */
	private static void enumerateOriginsSeededWithEval(
			Boolean[][] adj, int n, PairInfo[] pairs,
			MatsimNetworkCache network, DrtRequest[] requests,
			int[] requestIndices,
			double[] bestValidDist,
			boolean[] used, int[] perm, double[] pickupTimes, int depth,
			double partialDist, double currentTime,
			double currentL, double currentU,
			int[] seedLocalOrigin, int[] seedLocalDest, int seedLocalNewRequest,
			Consumer<Ordering> evaluator,
			double[] connTT, double[] connDist, double[] connUtil) {

		if (depth == n) {
			// All origins placed — enumerate destination orderings with parent-consistent seed.
			enumerateDestPrunedSeededWithEval(n, perm, pairs, network, requests,
					requestIndices, bestValidDist, partialDist, currentTime, pickupTimes,
					currentL, currentU,
					seedLocalDest, seedLocalNewRequest,
					evaluator, connTT, connDist, connUtil);
			return;
		}

		// Origin-phase Check A: prune if any picked-up passenger already exceeds
		// maxTravelTime from origin traversal alone. No destination ordering can help.
		if (depth > 1) {
			EnumerationStats stats = EnumerationStats.get();
			for (int p = 0; p < depth; p++) {
				double inVehicle = currentTime - pickupTimes[perm[p]];
				if (inVehicle > requests[perm[p]].getMaxTravelTime()) {
					stats.prunedByTravelTime++;
					return;
				}
			}
		}

		List<Integer> candidates = new ArrayList<>();
		for (int c = 0; c < n; c++) {
			if (used[c]) continue;
			boolean valid = true;
			for (int other = 0; other < n; other++) {
				if (other == c || used[other]) continue;
				if (adj[other][c] != null && adj[other][c]) {
					valid = false; break;
				}
			}
			if (!valid) continue;
			candidates.add(c);
		}

		if (depth == 0) {
			// At depth 0 there is no previous link, so segment distances are unavailable.
			// Sort by parent-consistent rank only; ties broken by local index for stability.
			int nextParentLocal0 = nextUnplacedInSeed(seedLocalOrigin, used);
			int newRequestLocal0 = used[seedLocalNewRequest] ? -1 : seedLocalNewRequest;
			candidates.sort((a, b) -> {
				int rankA = parentConsistentRank(a, nextParentLocal0, newRequestLocal0);
				int rankB = parentConsistentRank(b, nextParentLocal0, newRequestLocal0);
				if (rankA != rankB) return Integer.compare(rankA, rankB);
				return Integer.compare(a, b); // stable tie-break by local index
			});

			for (int c : candidates) {
				DrtRequest reqC = requests[c];
				double newLowC = -reqC.getMaxNegativeDelay();
				double newHighC = reqC.getMaxPositiveDelay()
						- Math.max(0.0, reqC.getPositiveDelayRelComponent());
				double newL = currentL > newLowC ? currentL : newLowC;
				double newU = currentU < newHighC ? currentU : newHighC;
				if (newL > newU + DELAY_WINDOW_EPSILON) {
					EnumerationStats.get().prunedByDelayWindowOrigin++;
					continue;
				}

				used[c] = true;
				perm[0] = c;
				pickupTimes[c] = reqC.getRequestTime();
				enumerateOriginsSeededWithEval(adj, n, pairs, network, requests,
						requestIndices, bestValidDist, used, perm, pickupTimes, 1,
						0.0, reqC.getRequestTime(),
						newL, newU,
						seedLocalOrigin, seedLocalDest, seedLocalNewRequest,
						evaluator,
						connTT, connDist, connUtil);
				used[c] = false;
			}
			return;
		}

		Id<Link> prevLink = requests[perm[depth - 1]].originLinkId;
		Map<Integer, TravelSegment> segMap = new HashMap<>();
		for (int c : candidates) {
			segMap.put(c, network.getSegment(prevLink, requests[c].originLinkId, currentTime));
		}

		// Two-level sort: primary = parent-consistent rank, secondary = cheapest-next-segment.
		int nextParentLocal = nextUnplacedInSeed(seedLocalOrigin, used);
		int newRequestLocal = used[seedLocalNewRequest] ? -1 : seedLocalNewRequest;
		candidates.sort((a, b) -> {
			int rankA = parentConsistentRank(a, nextParentLocal, newRequestLocal);
			int rankB = parentConsistentRank(b, nextParentLocal, newRequestLocal);
			if (rankA != rankB) return Integer.compare(rankA, rankB);
			return Double.compare(segMap.get(a).getDistance(), segMap.get(b).getDistance());
		});

		int candCount = candidates.size();
		for (int idx = 0; idx < candCount; idx++) {
			int c = candidates.get(idx);
			TravelSegment seg = segMap.get(c);
			double newPartialDist = partialDist + seg.getDistance();
			if (newPartialDist > bestValidDist[0]) {
				EnumerationStats s = EnumerationStats.get();
				s.bnbOriginCuts++;
				s.bnbOriginSkippedCandidates += (candCount - idx);
				break;
			}

			double newPickupTime = currentTime + seg.getTravelTime();
			DrtRequest reqC = requests[c];
			double delayC = newPickupTime - reqC.getRequestTime();
			double newLowC = -delayC - reqC.getMaxNegativeDelay();
			double newHighC = (reqC.getMaxPositiveDelay()
					- Math.max(0.0, reqC.getPositiveDelayRelComponent())) - delayC;
			double newL = currentL > newLowC ? currentL : newLowC;
			double newU = currentU < newHighC ? currentU : newHighC;
			if (newL > newU + DELAY_WINDOW_EPSILON) {
				EnumerationStats.get().prunedByDelayWindowOrigin++;
				continue;
			}

			used[c] = true;
			perm[depth] = c;
			pickupTimes[c] = newPickupTime;
			connTT[depth - 1] = seg.getTravelTime();
			connDist[depth - 1] = seg.getDistance();
			connUtil[depth - 1] = seg.getNetworkUtility();
			enumerateOriginsSeededWithEval(adj, n, pairs, network, requests,
					requestIndices, bestValidDist, used, perm, pickupTimes, depth + 1,
					newPartialDist, newPickupTime,
					newL, newU,
					seedLocalOrigin, seedLocalDest, seedLocalNewRequest,
					evaluator,
					connTT, connDist, connUtil);
			used[c] = false;
		}
	}

	private static void enumerateOriginsPrunedWithEval(
			Boolean[][] adj, int n, PairInfo[] pairs,
			MatsimNetworkCache network, DrtRequest[] requests,
			int[] requestIndices,
			double[] bestValidDist,
			boolean[] used, int[] perm, double[] pickupTimes, int depth,
			double partialDist, double currentTime,
			double currentL, double currentU,
			Consumer<Ordering> evaluator,
			double[] connTT, double[] connDist, double[] connUtil) {

		if (depth == n) {
			// All origins placed — enumerate destination orderings.
			enumerateDestPrunedWithEval(n, perm, pairs, network, requests,
					requestIndices, bestValidDist, partialDist, currentTime, pickupTimes,
					currentL, currentU,
					evaluator, connTT, connDist, connUtil);
			return;
		}

		// Origin-phase Check A: prune if any picked-up passenger already exceeds
		// maxTravelTime from origin traversal alone. No destination ordering can help.
		if (depth > 1) {
			EnumerationStats stats = EnumerationStats.get();
			for (int p = 0; p < depth; p++) {
				double inVehicle = currentTime - pickupTimes[perm[p]];
				if (inVehicle > requests[perm[p]].getMaxTravelTime()) {
					stats.prunedByTravelTime++;
					return;
				}
			}
		}

		List<Integer> candidates = new ArrayList<>();
		for (int c = 0; c < n; c++) {
			if (used[c]) continue;
			boolean valid = true;
			for (int other = 0; other < n; other++) {
				if (other == c || used[other]) continue;
				if (adj[other][c] != null && adj[other][c]) {
					valid = false; break;
				}
			}
			if (!valid) continue;
			candidates.add(c);
		}

		if (depth == 0) {
			for (int c : candidates) {
				DrtRequest reqC = requests[c];
				// Delay-window contribution at depth 0: delay = 0 (currentTime = reqTime).
				// Origin-time UB:
				//   paxLow = -0 - maxNeg = -maxNeg
				//   paxHigh = (maxPos - max(0, posRelComp)) - 0
				double newLowC = -reqC.getMaxNegativeDelay();
				double newHighC = reqC.getMaxPositiveDelay()
						- Math.max(0.0, reqC.getPositiveDelayRelComponent());
				double newL = currentL > newLowC ? currentL : newLowC;
				double newU = currentU < newHighC ? currentU : newHighC;
				if (newL > newU + DELAY_WINDOW_EPSILON) {
					EnumerationStats.get().prunedByDelayWindowOrigin++;
					continue;
				}

				used[c] = true;
				perm[0] = c;
				pickupTimes[c] = reqC.getRequestTime();
				enumerateOriginsPrunedWithEval(adj, n, pairs, network, requests,
						requestIndices, bestValidDist, used, perm, pickupTimes, 1,
						0.0, reqC.getRequestTime(),
						newL, newU,
						evaluator,
						connTT, connDist, connUtil);
				used[c] = false;
			}
			return;
		}

		Id<Link> prevLink = requests[perm[depth - 1]].originLinkId;
		Map<Integer, TravelSegment> segMap = new HashMap<>();
		for (int c : candidates) {
			segMap.put(c, network.getSegment(prevLink, requests[c].originLinkId, currentTime));
		}
		candidates.sort(Comparator.comparingDouble(c -> segMap.get(c).getDistance()));

		int candCount = candidates.size();
		for (int idx = 0; idx < candCount; idx++) {
			int c = candidates.get(idx);
			TravelSegment seg = segMap.get(c);
			double newPartialDist = partialDist + seg.getDistance();
			if (newPartialDist > bestValidDist[0]) {
				EnumerationStats s = EnumerationStats.get();
				s.bnbOriginCuts++;
				s.bnbOriginSkippedCandidates += (candCount - idx);
				break;
			}

			// Delay-window feasibility (origin-time over-approximation).
			// effMaxNeg_UB = maxNegativeDelay (detour → ∞ → negAdj → 0)
			// effMaxPos_UB = maxPos - max(0, posRelComp) (detour ∈ [0, posRelComp])
			double newPickupTime = currentTime + seg.getTravelTime();
			DrtRequest reqC = requests[c];
			double delayC = newPickupTime - reqC.getRequestTime();
			double newLowC = -delayC - reqC.getMaxNegativeDelay();
			double newHighC = (reqC.getMaxPositiveDelay()
					- Math.max(0.0, reqC.getPositiveDelayRelComponent())) - delayC;
			double newL = currentL > newLowC ? currentL : newLowC;
			double newU = currentU < newHighC ? currentU : newHighC;
			if (newL > newU + DELAY_WINDOW_EPSILON) {
				EnumerationStats.get().prunedByDelayWindowOrigin++;
				continue;
			}

			used[c] = true;
			perm[depth] = c;
			pickupTimes[c] = newPickupTime;
			connTT[depth - 1] = seg.getTravelTime();
			connDist[depth - 1] = seg.getDistance();
			connUtil[depth - 1] = seg.getNetworkUtility();
			enumerateOriginsPrunedWithEval(adj, n, pairs, network, requests,
					requestIndices, bestValidDist, used, perm, pickupTimes, depth + 1,
					newPartialDist, newPickupTime,
					newL, newU,
					evaluator,
					connTT, connDist, connUtil);
			used[c] = false;
		}
	}

	private static void enumerateDestPrunedWithEval(
			int n, int[] origPerm, PairInfo[] pairs,
			MatsimNetworkCache network, DrtRequest[] requests,
			int[] requestIndices,
			double[] bestValidDist,
			double partialDist, double currentTime,
			double[] pickupTimes,
			double currentL, double currentU,
			Consumer<Ordering> evaluator,
			double[] connTT, double[] connDist, double[] connUtil) {

		int[] origPos = new int[n];
		for (int i = 0; i < n; i++) origPos[origPerm[i]] = i;

		Boolean[][] adj = new Boolean[n][n];
		for (int a = 0; a < n; a++) {
			for (int b = a + 1; b < n; b++) {
				PairInfo p = lookup(pairs, n, a, b);
				boolean aBeforeB = origPos[a] < origPos[b];
				boolean hasFifo = aBeforeB ? p.forwardFifo() : p.reverseFifo();
				boolean hasLifo = aBeforeB ? p.forwardLifo() : p.reverseLifo();

				if (hasFifo && hasLifo) {
					// no constraint
				} else if (hasFifo) {
					if (aBeforeB) { adj[a][b] = true; adj[b][a] = false; }
					else          { adj[b][a] = true; adj[a][b] = false; }
				} else if (hasLifo) {
					if (aBeforeB) { adj[b][a] = true; adj[a][b] = false; }
					else          { adj[a][b] = true; adj[b][a] = false; }
				} else {
					// Structural infeasibility — no pair ride in this direction.
					return;
				}
			}
		}

		Id<Link> prevLink = requests[origPerm[n - 1]].originLinkId;

		enumerateDestTopoWithEval(adj, n, origPerm, origPos, requestIndices, network, requests,
				bestValidDist, new boolean[n], new int[n], 0,
				partialDist, currentTime, prevLink, pickupTimes,
				currentL, currentU,
				evaluator, connTT, connDist, connUtil);
	}

	private static void enumerateDestTopoWithEval(
			Boolean[][] adj, int n, int[] origPerm, int[] origPos, int[] requestIndices,
			MatsimNetworkCache network, DrtRequest[] requests,
			double[] bestValidDist,
			boolean[] used, int[] perm, int depth,
			double partialDist, double currentTime,
			Id<Link> prevLinkId,
			double[] pickupTimes,
			double currentL, double currentU,
			Consumer<Ordering> evaluator,
			double[] connTT, double[] connDist, double[] connUtil) {

		if (depth == n) {
			// Complete ordering — call evaluator inline with pre-routed segment data.
			// The evaluator may update bestValidDist[0] if this ordering is valid,
			// which tightens the bound for all subsequent branches.
			evaluator.accept(new Ordering(origPerm.clone(), perm.clone(), partialDist,
					connTT.clone(), connDist.clone(), connUtil.clone()));
			return;
		}

		// Check A: prune entire subtree if any in-vehicle passenger already exceeds maxTravelTime.
		// Their time can only increase as more stops are visited before their dropoff.
		EnumerationStats stats = EnumerationStats.get();
		for (int p = 0; p < n; p++) {
			if (used[p]) continue; // already dropped off
			double inVehicleTime = currentTime - pickupTimes[p];
			if (inVehicleTime > requests[p].getMaxTravelTime()) {
				stats.prunedByTravelTime++;
				return;
			}
		}

		List<Integer> candidates = new ArrayList<>();
		for (int c = 0; c < n; c++) {
			if (used[c]) continue;
			boolean valid = true;
			for (int other = 0; other < n; other++) {
				if (other == c || used[other]) continue;
				if (adj[other][c] != null && adj[other][c]) {
					valid = false; break;
				}
			}
			if (!valid) continue;
			candidates.add(c);
		}

		Map<Integer, TravelSegment> segMap = new HashMap<>();
		for (int c : candidates) {
			segMap.put(c, network.getSegment(prevLinkId,
					requests[c].destinationLinkId, currentTime));
		}
		candidates.sort(Comparator.comparingDouble(c -> segMap.get(c).getDistance()));

		int candCount = candidates.size();
		for (int idx = 0; idx < candCount; idx++) {
			int c = candidates.get(idx);
			TravelSegment seg = segMap.get(c);
			double newPartialDist = partialDist + seg.getDistance();
			if (newPartialDist > bestValidDist[0]) {
				EnumerationStats sDest = EnumerationStats.get();
				sDest.bnbDestCuts++;
				sDest.bnbDestSkippedCandidates += (candCount - idx);
				break;
			}

			double newTime = currentTime + seg.getTravelTime();

			// Check at Dropoff: passenger c's ride is now complete.
			// Their full in-vehicle time (pickup to this dropoff) must not exceed maxTravelTime.
			double fullInVehicle = newTime - pickupTimes[c];
			if (fullInVehicle > requests[c].getMaxTravelTime()) {
				stats.prunedByDropoffCheck++;
				continue;
			}

			// Check B: after routing to this candidate's destination, would any
			// remaining in-vehicle passenger exceed their maxTravelTime?
			int bustedVictim = -1;
			for (int p = 0; p < n; p++) {
				if (used[p] || p == c) continue; // already dropped off, or being dropped off now
				if (newTime - pickupTimes[p] > requests[p].getMaxTravelTime()) {
					bustedVictim = p;
					break;
				}
			}
			if (bustedVictim >= 0) {
				stats.prunedByTravelTime++;
				continue;
			}

			// Delay-window dropoff tightening: c's detour is now known.
			// Replace c's origin-time UB contribution with its exact post-detour value.
			// Both updates are monotone (paxLow only grows, paxHigh only shrinks),
			// so newL/newU are O(1) updates from (currentL, currentU).
			DrtRequest reqC = requests[c];
			double detourTimeC = fullInVehicle - reqC.getTravelTime();
			double posRelC = reqC.getPositiveDelayRelComponent();
			double negRelC = reqC.getNegativeDelayRelComponent();
			double posAdjC = posRelC > 0 ? Math.max(0.0, posRelC - detourTimeC) : 0.0;
			double negAdjC = negRelC > 0 ? Math.max(0.0, negRelC - detourTimeC) : 0.0;
			double effMaxPosC = reqC.getMaxPositiveDelay() - detourTimeC - posAdjC;
			double effMaxNegC = reqC.getMaxNegativeDelay() - negAdjC;
			double delayC = pickupTimes[c] - reqC.getRequestTime();
			double actualLowC = -delayC - effMaxNegC;
			double actualHighC = effMaxPosC - delayC;
			double newL = currentL > actualLowC ? currentL : actualLowC;
			double newU = currentU < actualHighC ? currentU : actualHighC;
			if (newL > newU + DELAY_WINDOW_EPSILON) {
				EnumerationStats.get().prunedByDelayWindowDropoff++;
				continue;
			}

			used[c] = true;
			perm[depth] = c;
			int connIdx = n - 1 + depth;
			connTT[connIdx] = seg.getTravelTime();
			connDist[connIdx] = seg.getDistance();
			connUtil[connIdx] = seg.getNetworkUtility();
			enumerateDestTopoWithEval(adj, n, origPerm, origPos, requestIndices, network, requests,
					bestValidDist, used, perm, depth + 1,
					newPartialDist, newTime,
					requests[c].destinationLinkId, pickupTimes,
					newL, newU,
					evaluator, connTT, connDist, connUtil);
			used[c] = false;
		}
	}

	/**
	 * Seeded entry point for the destination DFS: mirrors {@link #enumerateDestPrunedWithEval}
	 * but delegates to {@link #enumerateDestTopoSeededWithEval} so that dest candidates are
	 * visited in parent-consistent order (next unplaced parent-dest request or the new request
	 * first, tie-broken by cheapest-next-segment distance).
	 *
	 * @param seedLocalDest      child-local indices of parent's dest order (length n-1)
	 * @param seedLocalNewRequest child-local index of the newly inserted request
	 */
	private static void enumerateDestPrunedSeededWithEval(
			int n, int[] origPerm, PairInfo[] pairs,
			MatsimNetworkCache network, DrtRequest[] requests,
			int[] requestIndices,
			double[] bestValidDist,
			double partialDist, double currentTime,
			double[] pickupTimes,
			double currentL, double currentU,
			int[] seedLocalDest, int seedLocalNewRequest,
			Consumer<Ordering> evaluator,
			double[] connTT, double[] connDist, double[] connUtil) {

		int[] origPos = new int[n];
		for (int i = 0; i < n; i++) origPos[origPerm[i]] = i;

		Boolean[][] adj = new Boolean[n][n];
		for (int a = 0; a < n; a++) {
			for (int b = a + 1; b < n; b++) {
				PairInfo p = lookup(pairs, n, a, b);
				boolean aBeforeB = origPos[a] < origPos[b];
				boolean hasFifo = aBeforeB ? p.forwardFifo() : p.reverseFifo();
				boolean hasLifo = aBeforeB ? p.forwardLifo() : p.reverseLifo();

				if (hasFifo && hasLifo) {
					// no constraint
				} else if (hasFifo) {
					if (aBeforeB) { adj[a][b] = true; adj[b][a] = false; }
					else          { adj[b][a] = true; adj[a][b] = false; }
				} else if (hasLifo) {
					if (aBeforeB) { adj[b][a] = true; adj[a][b] = false; }
					else          { adj[a][b] = true; adj[b][a] = false; }
				} else {
					// Structural infeasibility — no pair ride in this direction.
					return;
				}
			}
		}

		Id<Link> prevLink = requests[origPerm[n - 1]].originLinkId;

		enumerateDestTopoSeededWithEval(adj, n, origPerm, origPos, requestIndices, network, requests,
				bestValidDist, new boolean[n], new int[n], 0,
				partialDist, currentTime, prevLink, pickupTimes,
				currentL, currentU,
				seedLocalDest, seedLocalNewRequest,
				evaluator, connTT, connDist, connUtil);
	}

	/**
	 * Seeded variant of {@link #enumerateDestTopoWithEval}: identical except the candidate sort
	 * uses a two-level comparator — primary: parent-consistent rank (0 = next unplaced parent-dest
	 * request or the new request, 1 = other), secondary: cheapest-next-segment distance.
	 *
	 * <p>Reuses the {@link #nextUnplacedInSeed} and {@link #parentConsistentRank} helpers from T6.
	 *
	 * @param seedLocalDest      child-local indices of parent's dest order (length n-1)
	 * @param seedLocalNewRequest child-local index of the newly inserted request
	 */
	private static void enumerateDestTopoSeededWithEval(
			Boolean[][] adj, int n, int[] origPerm, int[] origPos, int[] requestIndices,
			MatsimNetworkCache network, DrtRequest[] requests,
			double[] bestValidDist,
			boolean[] used, int[] perm, int depth,
			double partialDist, double currentTime,
			Id<Link> prevLinkId,
			double[] pickupTimes,
			double currentL, double currentU,
			int[] seedLocalDest, int seedLocalNewRequest,
			Consumer<Ordering> evaluator,
			double[] connTT, double[] connDist, double[] connUtil) {

		if (depth == n) {
			// Complete ordering — call evaluator inline with pre-routed segment data.
			evaluator.accept(new Ordering(origPerm.clone(), perm.clone(), partialDist,
					connTT.clone(), connDist.clone(), connUtil.clone()));
			return;
		}

		// Check A: prune entire subtree if any in-vehicle passenger already exceeds maxTravelTime.
		EnumerationStats stats = EnumerationStats.get();
		for (int p = 0; p < n; p++) {
			if (used[p]) continue; // already dropped off
			double inVehicleTime = currentTime - pickupTimes[p];
			if (inVehicleTime > requests[p].getMaxTravelTime()) {
				stats.prunedByTravelTime++;
				return;
			}
		}

		List<Integer> candidates = new ArrayList<>();
		for (int c = 0; c < n; c++) {
			if (used[c]) continue;
			boolean valid = true;
			for (int other = 0; other < n; other++) {
				if (other == c || used[other]) continue;
				if (adj[other][c] != null && adj[other][c]) {
					valid = false; break;
				}
			}
			if (!valid) continue;
			candidates.add(c);
		}

		Map<Integer, TravelSegment> segMap = new HashMap<>();
		for (int c : candidates) {
			segMap.put(c, network.getSegment(prevLinkId,
					requests[c].destinationLinkId, currentTime));
		}

		// Two-level sort: primary = parent-consistent rank, secondary = cheapest-next-segment.
		int nextParentLocal = nextUnplacedInSeed(seedLocalDest, used);
		int newRequestLocal = used[seedLocalNewRequest] ? -1 : seedLocalNewRequest;
		candidates.sort((a, b) -> {
			int rankA = parentConsistentRank(a, nextParentLocal, newRequestLocal);
			int rankB = parentConsistentRank(b, nextParentLocal, newRequestLocal);
			if (rankA != rankB) return Integer.compare(rankA, rankB);
			return Double.compare(segMap.get(a).getDistance(), segMap.get(b).getDistance());
		});

		int candCount = candidates.size();
		for (int idx = 0; idx < candCount; idx++) {
			int c = candidates.get(idx);
			TravelSegment seg = segMap.get(c);
			double newPartialDist = partialDist + seg.getDistance();
			if (newPartialDist > bestValidDist[0]) {
				EnumerationStats sDest = EnumerationStats.get();
				sDest.bnbDestCuts++;
				sDest.bnbDestSkippedCandidates += (candCount - idx);
				break;
			}

			double newTime = currentTime + seg.getTravelTime();

			// Check at Dropoff: passenger c's ride is now complete.
			double fullInVehicle = newTime - pickupTimes[c];
			if (fullInVehicle > requests[c].getMaxTravelTime()) {
				stats.prunedByDropoffCheck++;
				continue;
			}

			// Check B: after routing to this candidate's destination, would any
			// remaining in-vehicle passenger exceed their maxTravelTime?
			int bustedVictim = -1;
			for (int p = 0; p < n; p++) {
				if (used[p] || p == c) continue;
				if (newTime - pickupTimes[p] > requests[p].getMaxTravelTime()) {
					bustedVictim = p;
					break;
				}
			}
			if (bustedVictim >= 0) {
				stats.prunedByTravelTime++;
				continue;
			}

			// Delay-window dropoff tightening.
			DrtRequest reqC = requests[c];
			double detourTimeC = fullInVehicle - reqC.getTravelTime();
			double posRelC = reqC.getPositiveDelayRelComponent();
			double negRelC = reqC.getNegativeDelayRelComponent();
			double posAdjC = posRelC > 0 ? Math.max(0.0, posRelC - detourTimeC) : 0.0;
			double negAdjC = negRelC > 0 ? Math.max(0.0, negRelC - detourTimeC) : 0.0;
			double effMaxPosC = reqC.getMaxPositiveDelay() - detourTimeC - posAdjC;
			double effMaxNegC = reqC.getMaxNegativeDelay() - negAdjC;
			double delayC = pickupTimes[c] - reqC.getRequestTime();
			double actualLowC = -delayC - effMaxNegC;
			double actualHighC = effMaxPosC - delayC;
			double newL = currentL > actualLowC ? currentL : actualLowC;
			double newU = currentU < actualHighC ? currentU : actualHighC;
			if (newL > newU + DELAY_WINDOW_EPSILON) {
				EnumerationStats.get().prunedByDelayWindowDropoff++;
				continue;
			}

			used[c] = true;
			perm[depth] = c;
			int connIdx = n - 1 + depth;
			connTT[connIdx] = seg.getTravelTime();
			connDist[connIdx] = seg.getDistance();
			connUtil[connIdx] = seg.getNetworkUtility();
			enumerateDestTopoSeededWithEval(adj, n, origPerm, origPos, requestIndices, network, requests,
					bestValidDist, used, perm, depth + 1,
					newPartialDist, newTime,
					requests[c].destinationLinkId, pickupTimes,
					newL, newU,
					seedLocalDest, seedLocalNewRequest,
					evaluator, connTT, connDist, connUtil);
			used[c] = false;
		}
	}

	private static void enumerateOriginsPruned(
			Boolean[][] adj, int n, PairInfo[] pairs,
			MatsimNetworkCache network, DrtRequest[] requests,
			double maxRideDistance, double[] bestFoundDist,
			boolean[] used, int[] perm, int depth,
			double partialDist, double currentTime,
			List<Ordering> result) {

		if (depth == n) {
			enumerateDestinationsPruned(n, perm, pairs, network, requests,
					maxRideDistance, bestFoundDist, partialDist, currentTime, result);
			return;
		}

		List<Integer> candidates = new ArrayList<>();
		for (int c = 0; c < n; c++) {
			if (used[c]) continue;
			boolean valid = true;
			for (int other = 0; other < n; other++) {
				if (other == c || used[other]) continue;
				if (adj[other][c] != null && adj[other][c]) {
					valid = false; break;
				}
			}
			if (valid) candidates.add(c);
		}

		if (depth == 0) {
			for (int c : candidates) {
				used[c] = true;
				perm[0] = c;
				enumerateOriginsPruned(adj, n, pairs, network, requests,
						maxRideDistance, bestFoundDist, used, perm, 1,
						0.0, requests[c].getRequestTime(), result);
				used[c] = false;
			}
			return;
		}

		Id<Link> prevLink = requests[perm[depth - 1]].originLinkId;
		Map<Integer, TravelSegment> segMap = new HashMap<>();
		for (int c : candidates) {
			segMap.put(c, network.getSegment(prevLink, requests[c].originLinkId, currentTime));
		}
		candidates.sort(Comparator.comparingDouble(c -> segMap.get(c).getDistance()));

		for (int c : candidates) {
			TravelSegment seg = segMap.get(c);
			double newPartialDist = partialDist + seg.getDistance();
			if (newPartialDist > bestFoundDist[0]) break;

			used[c] = true;
			perm[depth] = c;
			enumerateOriginsPruned(adj, n, pairs, network, requests,
					maxRideDistance, bestFoundDist, used, perm, depth + 1,
					newPartialDist, currentTime + seg.getTravelTime(), result);
			used[c] = false;
		}
	}

	private static void enumerateDestinationsPruned(
			int n, int[] origPerm, PairInfo[] pairs,
			MatsimNetworkCache network, DrtRequest[] requests,
			double maxRideDistance, double[] bestFoundDist,
			double partialDist, double currentTime,
			List<Ordering> result) {

		int[] origPos = new int[n];
		for (int i = 0; i < n; i++) origPos[origPerm[i]] = i;

		Boolean[][] adj = new Boolean[n][n];
		for (int a = 0; a < n; a++) {
			for (int b = a + 1; b < n; b++) {
				PairInfo p = lookup(pairs, n, a, b);
				boolean aBeforeB = origPos[a] < origPos[b];
				boolean hasFifo = aBeforeB ? p.forwardFifo() : p.reverseFifo();
				boolean hasLifo = aBeforeB ? p.forwardLifo() : p.reverseLifo();

				if (hasFifo && hasLifo) {
					// no constraint
				} else if (hasFifo) {
					if (aBeforeB) { adj[a][b] = true; adj[b][a] = false; }
					else          { adj[b][a] = true; adj[a][b] = false; }
				} else if (hasLifo) {
					if (aBeforeB) { adj[b][a] = true; adj[a][b] = false; }
					else          { adj[a][b] = true; adj[b][a] = false; }
				} else {
					return;
				}
			}
		}

		Id<Link> prevLink = requests[origPerm[n - 1]].originLinkId;

		enumerateDestTopoSortPruned(adj, n, origPerm, network, requests,
				maxRideDistance, bestFoundDist, new boolean[n], new int[n], 0,
				partialDist, currentTime, prevLink, result);
	}

	private static void enumerateDestTopoSortPruned(
			Boolean[][] adj, int n, int[] origPerm,
			MatsimNetworkCache network, DrtRequest[] requests,
			double maxRideDistance, double[] bestFoundDist,
			boolean[] used, int[] perm, int depth,
			double partialDist, double currentTime,
			Id<Link> prevLinkId,
			List<Ordering> result) {

		if (depth == n) {
			// Note: do NOT tighten bestFoundDist here. The shortest ordering might
			// fail budget validation, and we need longer orderings as fallbacks.
			// The sort + early exit in processSet handles the optimization.
			result.add(new Ordering(origPerm.clone(), perm.clone(), partialDist));
			return;
		}

		List<Integer> candidates = new ArrayList<>();
		for (int c = 0; c < n; c++) {
			if (used[c]) continue;
			boolean valid = true;
			for (int other = 0; other < n; other++) {
				if (other == c || used[other]) continue;
				if (adj[other][c] != null && adj[other][c]) {
					valid = false; break;
				}
			}
			if (valid) candidates.add(c);
		}

		Map<Integer, TravelSegment> segMap = new HashMap<>();
		for (int c : candidates) {
			segMap.put(c, network.getSegment(prevLinkId,
					requests[c].destinationLinkId, currentTime));
		}
		candidates.sort(Comparator.comparingDouble(c -> segMap.get(c).getDistance()));

		for (int c : candidates) {
			TravelSegment seg = segMap.get(c);
			double newPartialDist = partialDist + seg.getDistance();
			if (newPartialDist > bestFoundDist[0]) break;

			used[c] = true;
			perm[depth] = c;
			enumerateDestTopoSortPruned(adj, n, origPerm, network, requests,
					maxRideDistance, bestFoundDist, used, perm, depth + 1,
					newPartialDist, currentTime + seg.getTravelTime(),
					requests[c].destinationLinkId, result);
			used[c] = false;
		}
	}

	// --- Constraint extraction ---

	/**
	 * Extract pairwise ordering constraints from the shareability graph.
	 * @param requestIndices sorted request indices
	 * @param graph the shareability graph
	 * @return PairInfo array, or null if any pair has no shared ride (infeasible set)
	 */
	public static PairInfo[] extractConstraints(int[] requestIndices, ShareabilityGraph graph) {
		return extractConstraints(requestIndices, requestIndices.length, graph);
	}

	private static PairInfo[] extractConstraints(int[] requestIndices, int n, ShareabilityGraph graph) {
		PairInfo[] pairs = new PairInfo[n * (n - 1) / 2];
		int idx = 0;

		for (int a = 0; a < n; a++) {
			for (int b = a + 1; b < n; b++) {
				int reqA = requestIndices[a];
				int reqB = requestIndices[b];

				IntList[] fwd = graph.getEdgesWithKinds(reqA, reqB);
				boolean fwdFifo = false, fwdLifo = false;
				if (fwd[0].size() > 0) {
					for (int k = 0; k < fwd[1].size(); k++) {
						if (fwd[1].getInt(k) == ShareabilityGraph.KIND_FIFO) fwdFifo = true;
						else fwdLifo = true;
					}
				}

				IntList[] rev = graph.getEdgesWithKinds(reqB, reqA);
				boolean revFifo = false, revLifo = false;
				if (rev[0].size() > 0) {
					for (int k = 0; k < rev[1].size(); k++) {
						if (rev[1].getInt(k) == ShareabilityGraph.KIND_FIFO) revFifo = true;
						else revLifo = true;
					}
				}

				if (!(fwdFifo || fwdLifo || revFifo || revLifo)) {
					return null;
				}

				pairs[idx++] = new PairInfo(fwdFifo, fwdLifo, revFifo, revLifo);
			}
		}
		return pairs;
	}

	public static PairInfo lookup(PairInfo[] pairs, int n, int a, int b) {
		return pairs[a * (2 * n - a - 1) / 2 + (b - a - 1)];
	}

	// --- Origin ordering enumeration ---

	private static List<int[]> enumerateOriginOrderings(int n, PairInfo[] pairs) {
		Boolean[][] adj = new Boolean[n][n];
		for (int a = 0; a < n; a++) {
			for (int b = a + 1; b < n; b++) {
				PairInfo p = lookup(pairs, n, a, b);
				if (p.forwardOnly()) {
					adj[a][b] = true;
					adj[b][a] = false;
				} else if (p.reverseOnly()) {
					adj[b][a] = true;
					adj[a][b] = false;
				}
			}
		}

		List<int[]> result = new ArrayList<>();
		enumerateTopoSorts(adj, n, new boolean[n], new int[n], 0, result);
		return result;
	}

	// --- Destination ordering enumeration ---

	private static List<int[]> enumerateDestOrderings(int n, int[] origPerm, PairInfo[] pairs) {
		int[] origPos = new int[n];
		for (int i = 0; i < n; i++) origPos[origPerm[i]] = i;

		Boolean[][] adj = new Boolean[n][n];
		for (int a = 0; a < n; a++) {
			for (int b = a + 1; b < n; b++) {
				PairInfo p = lookup(pairs, n, a, b);

				boolean aBeforeB = origPos[a] < origPos[b];
				boolean hasFifo, hasLifo;

				if (aBeforeB) {
					hasFifo = p.forwardFifo();
					hasLifo = p.forwardLifo();
				} else {
					hasFifo = p.reverseFifo();
					hasLifo = p.reverseLifo();
				}

				if (hasFifo && hasLifo) {
					// no constraint
				} else if (hasFifo) {
					if (aBeforeB) {
						adj[a][b] = true;
						adj[b][a] = false;
					} else {
						adj[b][a] = true;
						adj[a][b] = false;
					}
				} else if (hasLifo) {
					if (aBeforeB) {
						adj[b][a] = true;
						adj[a][b] = false;
					} else {
						adj[a][b] = true;
						adj[b][a] = false;
					}
				} else {
					return List.of();
				}
			}
		}

		List<int[]> result = new ArrayList<>();
		enumerateTopoSorts(adj, n, new boolean[n], new int[n], 0, result);
		return result;
	}

	// --- Topological sort enumeration ---

	private static void enumerateTopoSorts(Boolean[][] adj, int n, boolean[] used, int[] perm, int depth,
										   List<int[]> result) {
		if (depth == n) {
			result.add(perm.clone());
			return;
		}

		for (int candidate = 0; candidate < n; candidate++) {
			if (used[candidate]) continue;

			boolean valid = true;
			for (int other = 0; other < n; other++) {
				if (other == candidate || used[other]) continue;
				if (adj[other][candidate] != null && adj[other][candidate]) {
					valid = false;
					break;
				}
			}
			if (!valid) continue;

			used[candidate] = true;
			perm[depth] = candidate;
			enumerateTopoSorts(adj, n, used, perm, depth + 1, result);
			used[candidate] = false;
		}
	}
}
