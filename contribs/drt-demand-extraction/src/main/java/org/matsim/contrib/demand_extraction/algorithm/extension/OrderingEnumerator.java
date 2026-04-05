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
 */
public final class OrderingEnumerator {

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

	/** A valid ordering with its total ride distance (from cumulative segment routing). */
	public record Ordering(int[] originPerm, int[] destPerm, double rideDistance) {}

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
			Consumer<Ordering> evaluator,
			OrderingConflicts conflicts) {

		PairInfo[] constraints = extractConstraints(requestIndices, graph);
		enumerateAndEvaluate(requestIndices, graph, constraints, network, requests,
				bestValidDist, evaluator, conflicts);
	}

	/**
	 * Enumerate orderings using pre-computed pairwise constraints.
	 * This allows callers to tighten constraints (e.g., from sub-set orderings)
	 * before enumeration.
	 *
	 * @param requestIndices sorted request indices for the set
	 * @param graph the shareability graph (used for FIFO/LIFO kinds if needed)
	 * @param pairConstraints pre-computed/tightened pairwise constraints
	 * @param network network cache for routed segment lookups
	 * @param requests DrtRequest objects
	 * @param bestValidDist mutable single-element array for distance pruning bound
	 * @param evaluator called for each complete ordering
	 */
	public static void enumerateAndEvaluate(
			int[] requestIndices, ShareabilityGraph graph,
			PairInfo[] pairConstraints,
			MatsimNetworkCache network, DrtRequest[] requests,
			double[] bestValidDist,
			Consumer<Ordering> evaluator,
			OrderingConflicts conflicts) {

		if (pairConstraints == null) return;
		int n = requestIndices.length;

		Boolean[][] origAdj = new Boolean[n][n];
		for (int a = 0; a < n; a++) {
			for (int b = a + 1; b < n; b++) {
				PairInfo p = lookup(pairConstraints, n, a, b);
				if (p.forwardOnly()) {
					origAdj[a][b] = true; origAdj[b][a] = false;
				} else if (p.reverseOnly()) {
					origAdj[b][a] = true; origAdj[a][b] = false;
				}
			}
		}

		int[] pathStops = new int[2 * n];
		enumerateOriginsPrunedWithEval(origAdj, n, pairConstraints, network, requests,
				bestValidDist, new boolean[n], new int[n], new double[n], 0,
				0.0, 0.0, evaluator, conflicts, pathStops);
	}

	private static void enumerateOriginsPrunedWithEval(
			Boolean[][] adj, int n, PairInfo[] pairs,
			MatsimNetworkCache network, DrtRequest[] requests,
			double[] bestValidDist,
			boolean[] used, int[] perm, double[] pickupTimes, int depth,
			double partialDist, double currentTime,
			Consumer<Ordering> evaluator,
			OrderingConflicts conflicts, int[] pathStops) {

		if (depth == n) {
			if (conflicts != null) {
				boolean[] anyValid = {false};
				Consumer<Ordering> wrappedEvaluator = (ordering) -> {
					anyValid[0] = true;
					evaluator.accept(ordering);
				};
				enumerateDestPrunedWithEval(n, perm, pairs, network, requests,
						bestValidDist, partialDist, currentTime, pickupTimes,
						wrappedEvaluator, conflicts, pathStops);
				if (!anyValid[0] && n >= 3) {
					int[] conflict = new int[n];
					for (int i = 0; i < n; i++)
						conflict[i] = OrderingConflicts.originStop(requests[perm[i]].index);
					conflicts.recordPending(conflict, 0, n);
				}
			} else {
				enumerateDestPrunedWithEval(n, perm, pairs, network, requests,
						bestValidDist, partialDist, currentTime, pickupTimes,
						evaluator, conflicts, pathStops);
			}
			return;
		}

		// Origin-phase Check A: prune if any picked-up passenger already exceeds
		// maxTravelTime from origin traversal alone. No destination ordering can help.
		if (depth > 1) {
			EnumerationStats stats = EnumerationStats.get();
			for (int p = 0; p < depth; p++) {
				double inVehicle = currentTime - pickupTimes[perm[p]];
				if (inVehicle > requests[perm[p]].getMaxTravelTime()) {
					// Record conflict: origin stops from victim p to current depth
					if (conflicts != null) {
						int len = depth - p;
						if (len >= 3) {
							int[] conflict = new int[len];
							for (int i = 0; i < len; i++)
								conflict[i] = OrderingConflicts.originStop(requests[perm[p + i]].index);
							conflicts.recordPending(conflict, 0, len);
						}
					}
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
			if (conflicts != null) {
				int candidateStop = OrderingConflicts.originStop(requests[c].index);
				if (conflicts.hasConflict(pathStops, depth, candidateStop)) {
					EnumerationStats.get().prunedByConflict++;
					continue;
				}
			}
			candidates.add(c);
		}

		if (depth == 0) {
			for (int c : candidates) {
				used[c] = true;
				perm[0] = c;
				pathStops[0] = OrderingConflicts.originStop(requests[c].index);
				pickupTimes[c] = requests[c].getRequestTime();
				enumerateOriginsPrunedWithEval(adj, n, pairs, network, requests,
						bestValidDist, used, perm, pickupTimes, 1,
						0.0, requests[c].getRequestTime(), evaluator,
						conflicts, pathStops);
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
			if (newPartialDist > bestValidDist[0]) break;

			used[c] = true;
			perm[depth] = c;
			pathStops[depth] = OrderingConflicts.originStop(requests[c].index);
			pickupTimes[c] = currentTime + seg.getTravelTime();
			enumerateOriginsPrunedWithEval(adj, n, pairs, network, requests,
					bestValidDist, used, perm, pickupTimes, depth + 1,
					newPartialDist, currentTime + seg.getTravelTime(), evaluator,
					conflicts, pathStops);
			used[c] = false;
		}
	}

	private static void enumerateDestPrunedWithEval(
			int n, int[] origPerm, PairInfo[] pairs,
			MatsimNetworkCache network, DrtRequest[] requests,
			double[] bestValidDist,
			double partialDist, double currentTime,
			double[] pickupTimes,
			Consumer<Ordering> evaluator,
			OrderingConflicts conflicts, int[] pathStops) {

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

		enumerateDestTopoWithEval(adj, n, origPerm, network, requests,
				bestValidDist, new boolean[n], new int[n], 0,
				partialDist, currentTime, prevLink, pickupTimes, evaluator,
				conflicts, pathStops);
	}

	private static void enumerateDestTopoWithEval(
			Boolean[][] adj, int n, int[] origPerm,
			MatsimNetworkCache network, DrtRequest[] requests,
			double[] bestValidDist,
			boolean[] used, int[] perm, int depth,
			double partialDist, double currentTime,
			Id<Link> prevLinkId,
			double[] pickupTimes,
			Consumer<Ordering> evaluator,
			OrderingConflicts conflicts, int[] pathStops) {

		if (depth == n) {
			// Complete ordering — call evaluator inline.
			// The evaluator may update bestValidDist[0] if this ordering is valid,
			// which tightens the bound for all subsequent branches.
			evaluator.accept(new Ordering(origPerm.clone(), perm.clone(), partialDist));
			return;
		}

		// Check A: prune entire subtree if any in-vehicle passenger already exceeds maxTravelTime.
		// Their time can only increase as more stops are visited before their dropoff.
		EnumerationStats stats = EnumerationStats.get();
		for (int p = 0; p < n; p++) {
			if (used[p]) continue; // already dropped off
			double inVehicleTime = currentTime - pickupTimes[p];
			if (inVehicleTime > requests[p].getMaxTravelTime()) {
				if (conflicts != null) {
					// Find victim's origin position
					int victimOrigPos = -1;
					for (int i = 0; i < n; i++) {
						if (origPerm[i] == p) { victimOrigPos = i; break; }
					}
					if (victimOrigPos >= 0) {
						int origCount = n - victimOrigPos;
						int len = origCount + depth;
						if (len >= 3) {
							int[] conflict = new int[len];
							int idx = 0;
							for (int i = victimOrigPos; i < n; i++)
								conflict[idx++] = OrderingConflicts.originStop(requests[origPerm[i]].index);
							for (int i = 0; i < depth; i++)
								conflict[idx++] = OrderingConflicts.destStop(requests[perm[i]].index);
							conflicts.recordPending(conflict, 0, len);
						}
					}
				}
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
			// Conflict lookup disabled during destination enumeration:
			// path length (n + depth) makes subsequence enumeration O(2^(n+d)),
			// which costs more than just trying the ordering and letting Check A/B prune.
			// Conflicts are still RECORDED from dest-phase Check A (above) for cross-degree transfer.
			candidates.add(c);
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
			if (newPartialDist > bestValidDist[0]) break;

			// Check B: after routing to this candidate's destination, would any
			// remaining in-vehicle passenger exceed their maxTravelTime?
			double newTime = currentTime + seg.getTravelTime();
			boolean busted = false;
			for (int p = 0; p < n; p++) {
				if (used[p] || p == c) continue; // already dropped off, or being dropped off now
				if (newTime - pickupTimes[p] > requests[p].getMaxTravelTime()) {
					busted = true;
					break;
				}
			}
			if (busted) {
				stats.prunedByTravelTime++;
				continue;
			}

			used[c] = true;
			perm[depth] = c;
			pathStops[n + depth] = OrderingConflicts.destStop(requests[c].index);
			enumerateDestTopoWithEval(adj, n, origPerm, network, requests,
					bestValidDist, used, perm, depth + 1,
					newPartialDist, newTime,
					requests[c].destinationLinkId, pickupTimes, evaluator,
					conflicts, pathStops);
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
