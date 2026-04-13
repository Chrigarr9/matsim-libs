package org.matsim.contrib.demand_extraction.algorithm.extension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideKind;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;
import org.matsim.contrib.demand_extraction.algorithm.graph.DegreeGraph;
import org.matsim.contrib.demand_extraction.algorithm.graph.ShareabilityGraph;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

/**
 * Extends degree-D rides to degree-(D+1) using ordering-based enumeration.
 *
 * <p>For each candidate request set, extracts pairwise FIFO/LIFO constraints from the
 * shareability graph and enumerates all valid (origin, destination) orderings via
 * topological sort. Each valid ordering is routed (all segments are cache hits from
 * pair rides) and validated. The best ride per set is kept.
 *
 * <p>This replaces the previous decomposition-based approach (base ride + insertion
 * position + cartesian product of FIFO/LIFO combos) which was ordering-dependent on
 * base rides and missed valid orderings due to top-1-per-set pruning at lower degrees.
 */
public final class RideExtender {
	private static final Logger log = LogManager.getLogger(RideExtender.class);

	private final MatsimNetworkCache network;
	private final ShareabilityGraph graph;
	private final BudgetValidator budgetValidator;
	private final Map<Integer, DrtRequest> requestMap;
	private final ExMasConfigGroup exMasConfig;
	private static final double EPSILON = 1e-9;

	// DegreeGraph from previous degree for candidate generation (null at degree 3)
	private final DegreeGraph prevDegreeGraph;
	// Sub-set ordering feasibility for cross-degree sub-set lookup (null if disabled)
	private final SubSetOrderingFeasibility subsetFeasibility;
	// Forbidden prefix index — wired in parallel to subsetFeasibility (Phase 2 no-op wiring)
	@SuppressWarnings("unused")
	private final ForbiddenPrefixIndex prefixIndex;
	// Stored after extendRides completes: valid rides by set hash, used for graph building
	private ConcurrentHashMap<Long, Ride> lastResultBySetHash;
	// Consensus bitmasks accumulated during processSet, keyed by set hash
	private ConcurrentHashMap<Long, long[]> lastConsensusBySetHash;

	public RideExtender(MatsimNetworkCache network, ShareabilityGraph graph, BudgetValidator budgetValidator,
						List<DrtRequest> requests, ExMasConfigGroup exMasConfig) {
		this(network, graph, budgetValidator, requests, exMasConfig, null, null, null);
	}

	public RideExtender(MatsimNetworkCache network, ShareabilityGraph graph, BudgetValidator budgetValidator,
						List<DrtRequest> requests, ExMasConfigGroup exMasConfig,
						DegreeGraph prevDegreeGraph) {
		this(network, graph, budgetValidator, requests, exMasConfig, prevDegreeGraph, null, null);
	}

	public RideExtender(MatsimNetworkCache network, ShareabilityGraph graph, BudgetValidator budgetValidator,
						List<DrtRequest> requests, ExMasConfigGroup exMasConfig,
						DegreeGraph prevDegreeGraph,
						SubSetOrderingFeasibility subsetFeasibility) {
		this(network, graph, budgetValidator, requests, exMasConfig, prevDegreeGraph, subsetFeasibility, null);
	}

	public RideExtender(MatsimNetworkCache network, ShareabilityGraph graph, BudgetValidator budgetValidator,
						List<DrtRequest> requests, ExMasConfigGroup exMasConfig,
						DegreeGraph prevDegreeGraph,
						SubSetOrderingFeasibility subsetFeasibility,
						ForbiddenPrefixIndex prefixIndex) {
		this.network = network;
		this.graph = graph;
		this.budgetValidator = budgetValidator;
		this.requestMap = new HashMap<>();
		for (DrtRequest r : requests) requestMap.put(r.index, r);
		this.exMasConfig = exMasConfig;
		this.prevDegreeGraph = prevDegreeGraph;
		this.subsetFeasibility = subsetFeasibility;
		this.prefixIndex = prefixIndex;
	}

	/** Build a DegreeGraph from the valid rides produced by the last extendRides call. */
	public DegreeGraph buildDegreeGraph(int degree) {
		if (lastResultBySetHash == null || lastResultBySetHash.isEmpty()) return null;
		return DegreeGraph.buildFromRides(lastResultBySetHash.values(), lastConsensusBySetHash, degree);
	}

	/** Returns the number of feasible sets from the last extendRides call. */
	public int getFeasibleSetCount() {
		return lastResultBySetHash != null ? lastResultBySetHash.size() : 0;
	}

	/**
	 * Extend rides from degree D to degree D+1 using ordering-based enumeration.
	 *
	 * <p>Parallelizes over base sets using a ForkJoinPool. Each thread independently
	 * discovers candidate sets from its base sets, claims them via atomic
	 * {@code ConcurrentHashMap.add()} for dedup, then processes inline (enumerate
	 * orderings, route, validate). Zero intermediate storage of candidate sets.
	 *
	 * @param ridesToExtend degree-D rides (1 per request set)
	 * @param nextRideIndex starting index for new rides
	 * @return list of degree-(D+1) rides, one per feasible set
	 */
	public List<Ride> extendRides(List<Ride> ridesToExtend, int nextRideIndex) {
		int targetDegree = ridesToExtend.isEmpty() ? 0 : ridesToExtend.get(0).getDegree() + 1;
		log.info("Extending {} sets from degree {} to {} ...",
				ridesToExtend.size(), targetDegree - 1, targetDegree);
		long phaseStartTime = System.currentTimeMillis();

		// Collect unique base sets for neighbor enumeration
		List<int[]> uniqueBaseSets = new ArrayList<>();
		{
			var seen = new java.util.HashSet<String>();
			for (Ride ride : ridesToExtend) {
				int[] idx = ride.getRequestIndices().clone();
				Arrays.sort(idx);
				if (seen.add(Arrays.toString(idx))) {
					uniqueBaseSets.add(idx);
				}
			}
		}

		// Determine parallelism
		int parallelism = exMasConfig.getAlgorithmProcessCount();
		if (parallelism <= 0) parallelism = Runtime.getRuntime().availableProcessors();
		log.info("  {} base rides in {} unique request sets, {} threads",
				ridesToExtend.size(), uniqueBaseSets.size(), parallelism);

		// Shared concurrent state:
		// - claimedSets: atomic dedup (first thread to add a hash processes it)
		// - resultBySetHash: successful rides
		ConcurrentHashMap.KeySetView<Long, Boolean> claimedSets = ConcurrentHashMap.newKeySet();
		ConcurrentHashMap<Long, Ride> resultBySetHash = new ConcurrentHashMap<>();
		ConcurrentHashMap<Long, long[]> consensusBySetHash = new ConcurrentHashMap<>();

		// Progress counters (thread-safe)
		AtomicInteger baseSetsCompleted = new AtomicInteger();
		AtomicInteger setsProcessed = new AtomicInteger();
		AtomicInteger setsSkippedDedup = new AtomicInteger();
		int totalBaseSets = uniqueBaseSets.size();

		// Per-thread profiling stats (keyed by thread ID for reliable collection)
		ConcurrentHashMap<Long, EnumerationStats> threadStatsMap = new ConcurrentHashMap<>();

		// Parallel processing over base sets
		ForkJoinPool pool = new ForkJoinPool(parallelism);
		try {
			pool.submit(() ->
				uniqueBaseSets.parallelStream().forEach(baseSetIndices -> {
					// Register this thread's stats for collection
					threadStatsMap.putIfAbsent(Thread.currentThread().getId(), EnumerationStats.get());

					int[] neighbors;
					if (prevDegreeGraph != null) {
						neighbors = prevDegreeGraph.findExtensions(baseSetIndices);
					} else {
						neighbors = graph.findCommonNeighborsSorted(baseSetIndices);
					}

					for (int newReq : neighbors) {
						int[] newSet = buildSortedRequestSet(baseSetIndices, newReq);
						long newSetHash = hashRequestSet(newSet);

						// Atomic dedup: only first thread to add this hash processes it
						if (!claimedSets.add(newSetHash)) {
							setsSkippedDedup.incrementAndGet();
							continue;
						}

						setsProcessed.incrementAndGet();

						Ride bestRide = processSet(newSet, newSetHash, targetDegree, consensusBySetHash);
						if (bestRide != null) {
							resultBySetHash.put(newSetHash, bestRide);
						}
					}

					// Progress logging per base set (coarser, less contention)
					int done = baseSetsCompleted.incrementAndGet();
					if (Integer.bitCount(done) == 1 && done >= 64) {
						double elapsed = (System.currentTimeMillis() - phaseStartTime) / 1000.0;
						double baseSetsPerSec = done / Math.max(0.001, elapsed);
						int remaining = totalBaseSets - done;
						double etaSeconds = remaining / Math.max(1, baseSetsPerSec);
						log.info("  Progress: {}/{} base sets ({} candidate sets, {} results, {} dedup), {} base/s, ETA {}",
								done, totalBaseSets, setsProcessed.get(), resultBySetHash.size(),
								setsSkippedDedup.get(), String.format("%.0f", baseSetsPerSec),
								formatEta(etaSeconds));
					}
				})
			).get();
		} catch (InterruptedException | ExecutionException e) {
			throw new RuntimeException("Parallel extension failed", e);
		} finally {
			pool.shutdown();
		}

		// Log profiling stats (thread-local values captured via threadStatsMap)
		if (!threadStatsMap.isEmpty()) {
			EnumerationStats total = EnumerationStats.sum(threadStatsMap.values());
			total.log(log, targetDegree, parallelism);
			// Reset thread-local stats for next degree
			threadStatsMap.values().forEach(s -> {
				s.setsProcessed = 0; s.orderingsEvaluated = 0; s.ridesBuilt = 0;
				s.ridesPassedConstraints = 0; s.budgetValidations = 0; s.budgetPassed = 0;
				s.segmentLookups = 0; s.prunedByTravelTime = 0; s.prunedByDropoffCheck = 0; s.prunedBySubsetLookup = 0; s.allDestFailRecorded = 0;
				s.setsConstraintFeasible = 0; s.setsBudgetFeasible = 0;
				s.timeTotal = 0; s.timeEnumeration = 0;
				s.timeRideConstruction = 0; s.timeBudgetValidation = 0;
			});
		}

		// Store results for graph building (accessed by buildDegreeGraph)
		this.lastResultBySetHash = resultBySetHash;
		this.lastConsensusBySetHash = consensusBySetHash;

		// Assign sequential indices (sequential, fast)
		List<Ride> results = new ArrayList<>(resultBySetHash.values());
		for (int i = 0; i < results.size(); i++) {
			results.set(i, rebuildWithIndex(results.get(i), nextRideIndex + i));
		}

		long elapsed = System.currentTimeMillis() - phaseStartTime;
		log.info("Extension complete: {} rides at degree {} in {}s ({} candidate sets, {} threads, {} skipped dedup, {} base sets)",
				results.size(), targetDegree, String.format("%.1f", elapsed / 1000.0),
				setsProcessed.get(), parallelism, setsSkippedDedup.get(), uniqueBaseSets.size());

		return results;
	}

	/**
	 * Process a single candidate set: enumerate orderings, route, validate, return best ride.
	 * Thread-safe — only reads shared immutable/thread-safe resources.
	 *
	 * <p>Two code paths:
	 * <ul>
	 *   <li>Degree 3 (prevDegreeGraph == null): use the constraint-extracting overload of
	 *       enumerateAndEvaluate — no extractConstraints or tightenConstraints overhead.</li>
	 *   <li>Degree 4+ (prevDegreeGraph != null): extract + tighten constraints using
	 *       degree graph, then call the pre-constraints overload of enumerateAndEvaluate.</li>
	 * </ul>
	 *
	 * <p>No FeasibleSetResult collection, no consensus bits, no array cloning.
	 * The DegreeGraph is built post-hoc from valid rides in resultBySetHash.
	 *
	 * @return best validated ride for this set, or null if no valid ordering exists
	 */
	private Ride processSet(int[] newSet, long setHash, int targetDegree,
						ConcurrentHashMap<Long, long[]> consensusBySetHash) {
		long t0 = System.nanoTime();
		EnumerationStats stats = EnumerationStats.get();
		stats.setsProcessed++;

		DrtRequest[] setRequests = new DrtRequest[newSet.length];
		for (int i = 0; i < newSet.length; i++) {
			setRequests[i] = requestMap.get(newSet[i]);
		}

		for (int i = 0; i < setRequests.length; i++) {
			for (int j = i + 1; j < setRequests.length; j++) {
				if (setRequests[i].getPaxId().equals(setRequests[j].getPaxId())) {
					stats.timeTotal += System.nanoTime() - t0;
					return null;
				}
			}
		}

		double maxAllowedRideDistance = computeMaxAllowedRideDistance(setRequests);
		double[] bestValidDist = { maxAllowedRideDistance };
		Ride[] bestRide = { null };
		long[] consensusBits = new long[DegreeGraph.consensusLongCount(targetDegree)];

		long tEnum0 = System.nanoTime();

		if (prevDegreeGraph != null) {
			// Degree 4+: extract and tighten constraints using degree graph
			OrderingEnumerator.PairInfo[] pairConstraints =
				OrderingEnumerator.extractConstraints(newSet, graph);
			if (pairConstraints == null) {
				stats.timeTotal += System.nanoTime() - t0;
				return null;
			}
			if (exMasConfig.isEnableConsensusTightening()) {
				pairConstraints = tightenConstraints(pairConstraints, newSet, prevDegreeGraph);
			}
			OrderingEnumerator.enumerateAndEvaluate(
					newSet, graph, pairConstraints, network, setRequests, bestValidDist,
					(ordering) -> evaluateOrdering(ordering, newSet, setRequests,
							bestValidDist, bestRide, consensusBits, stats),
					this.subsetFeasibility, this.prefixIndex);
		} else {
			// Degree 3: use original code path — no graph overhead
			OrderingEnumerator.enumerateAndEvaluate(
					newSet, graph, network, setRequests, bestValidDist,
					(ordering) -> evaluateOrdering(ordering, newSet, setRequests,
							bestValidDist, bestRide, consensusBits, stats),
					this.subsetFeasibility, this.prefixIndex);
		}

		stats.timeEnumeration += System.nanoTime() - tEnum0;
		stats.timeTotal += System.nanoTime() - t0;

		if (bestRide[0] != null) {
			stats.setsConstraintFeasible++;
			stats.setsBudgetFeasible++;
		}

		// Store consensus bits (lightweight long[] per set)
		boolean hasConsensus = false;
		for (long b : consensusBits) if (b != 0L) { hasConsensus = true; break; }
		if (hasConsensus) {
			consensusBySetHash.put(setHash, consensusBits);
		}

		return bestRide[0];
	}

	/** Shared evaluator logic for both degree-3 and degree-4+ code paths. */
	private void evaluateOrdering(OrderingEnumerator.Ordering ordering, int[] newSet,
								   DrtRequest[] setRequests, double[] bestValidDist,
								   Ride[] bestRide, long[] consensusBits,
								   EnumerationStats stats) {
		stats.orderingsEvaluated++;
		int n = newSet.length;
		DrtRequest[] originsOrdered = new DrtRequest[n];
		DrtRequest[] destsOrdered = new DrtRequest[n];
		for (int i = 0; i < n; i++) {
			originsOrdered[i] = setRequests[ordering.originPerm()[i]];
			destsOrdered[i] = setRequests[ordering.destPerm()[i]];
		}

		long tBuild0 = System.nanoTime();
		Ride ride = buildRideFromOrdering(originsOrdered, destsOrdered, 0,
				ordering.connTT(), ordering.connDist(), ordering.connUtil());
		stats.timeRideConstruction += System.nanoTime() - tBuild0;
		stats.ridesBuilt++;
		if (ride == null) {
			stats.rideNullFailures++;
			return;
		}
		stats.ridesPassedConstraints++;

		long tBudget0 = System.nanoTime();
		Ride validated = budgetValidator.validateAndPopulateBudgets(ride);
		stats.timeBudgetValidation += System.nanoTime() - tBudget0;
		stats.budgetValidations++;
		if (validated == null) {
			stats.budgetFailures++;
			return;
		}
		stats.budgetPassed++;

		// Accumulate pairwise consensus bits (works for any degree)
		DegreeGraph.accumulateOrderingBits(
			consensusBits, ordering.originPerm(), ordering.destPerm(), n);

		double dist = validated.getRideDistance();
		if (dist < bestValidDist[0]) {
			bestValidDist[0] = dist;
			bestRide[0] = validated;
			stats.newBestRides++;
		} else {
			stats.validButWorseThanBest++;
		}
	}

	/**
	 * Tighten pairwise ordering constraints using sub-set orderings from the degree graph.
	 * For each pair that currently allows both origin directions,
	 * check if all sub-set orderings agree on one direction.
	 */
	private OrderingEnumerator.PairInfo[] tightenConstraints(
			OrderingEnumerator.PairInfo[] original, int[] requestIndices, DegreeGraph degreeGraph) {
		int n = requestIndices.length;
		OrderingEnumerator.PairInfo[] result = original.clone();
		int tightenedHere = 0;

		for (int i = 0; i < n; i++) {
			for (int j = i + 1; j < n; j++) {
				int idx = i * (2 * n - i - 1) / 2 + (j - i - 1);
				OrderingEnumerator.PairInfo pi = result[idx];

				// Only tighten pairs that currently allow both directions
				if (pi.forwardOnly() || pi.reverseOnly()) continue;

				Boolean originDir = degreeGraph.getOriginConsensus(requestIndices, i, j);

				if (originDir != null) {
					if (originDir) {
						// a always before b → remove reverse directions
						result[idx] = new OrderingEnumerator.PairInfo(
							pi.forwardFifo(), pi.forwardLifo(), false, false);
					} else {
						// b always before a → remove forward directions
						result[idx] = new OrderingEnumerator.PairInfo(
							false, false, pi.reverseFifo(), pi.reverseLifo());
					}
					tightenedHere++;
				}
			}
		}
		if (tightenedHere > 0) {
			EnumerationStats ts = EnumerationStats.get();
			ts.tightenedPairDirections += tightenedHere;
			ts.setsWithTightenings++;
		}
		return result;
	}

	// --- Ride construction from explicit orderings ---

	/**
	 * Build a Ride from explicit origin and destination orderings.
	 * Routes the full sequence on the network with cumulative departure times,
	 * validates per-passenger constraints.
	 *
	 * <p>{@code requests[] = originsOrdered} (pickup order). All per-passenger metric
	 * arrays are indexed by pickup position. This eliminates delay remapping.
	 *
	 * @param originsOrdered requests in pickup order (also used as requests[])
	 * @param destsOrdered requests in dropoff order
	 * @param index ride index
	 * @return validated Ride, or null if routing fails or constraints violated
	 */
	private Ride buildRideFromOrdering(DrtRequest[] originsOrdered,
									   DrtRequest[] destsOrdered, int index,
									   double[] preConnTT, double[] preConnDist,
									   double[] preConnUtil) {
		int degree = originsOrdered.length;
		DrtRequest[] requests = originsOrdered; // requests[] IS origin ordering

		double startTime = originsOrdered[0].getRequestTime();
		double[] connTT, connDist, connUtil;

		if (preConnTT != null) {
			// Use pre-routed segment data from enumeration (zero routing calls)
			connTT = preConnTT;
			connDist = preConnDist;
			connUtil = preConnUtil;
		} else {
			// Build connection sequence: [O_1, O_2, ..., O_n, D_1, D_2, ..., D_n]
			@SuppressWarnings("unchecked")
			Id<Link>[] sequence = (Id<Link>[]) new Id[degree * 2];
			for (int i = 0; i < degree; i++) {
				sequence[i] = originsOrdered[i].originLinkId;
			}
			for (int i = 0; i < degree; i++) {
				sequence[degree + i] = destsOrdered[i].destinationLinkId;
			}

			// Route all segments with cumulative departure time
			connTT = new double[degree * 2 - 1];
			connDist = new double[degree * 2 - 1];
			connUtil = new double[degree * 2 - 1];

			double currentTime = startTime;
			EnumerationStats stats = EnumerationStats.get();
			for (int i = 0; i < degree * 2 - 1; i++) {
				TravelSegment seg = network.getSegment(sequence[i], sequence[i + 1], currentTime);
				stats.segmentLookups++;
				if (!seg.isReachable()) return null;
				connTT[i] = seg.getTravelTime();
				connDist[i] = seg.getDistance();
				connUtil[i] = seg.getNetworkUtility();
				currentTime += connTT[i];
			}
		}

		// Calculate per-passenger metrics (indexed by pickup position = requests[] position)
		double[] pttActual = new double[degree];
		double[] pDist = new double[degree];
		double[] pUtil = new double[degree];

		for (int i = 0; i < degree; i++) {
			DrtRequest req = requests[i]; // = originsOrdered[i]
			int origIdx = i; // trivially — requests IS originsOrdered

			// Find destination position
			int destPosInDestArray = -1;
			for (int k = 0; k < degree; k++) {
				if (destsOrdered[k].index == req.index) { destPosInDestArray = k; break; }
			}
			int destIdx = degree + destPosInDestArray;

			for (int j = origIdx; j < destIdx; j++) {
				pttActual[i] += connTT[j];
				pDist[i] += connDist[j];
				pUtil[i] += connUtil[j];
			}

			if (pttActual[i] < req.getTravelTime() - EPSILON) {
				pttActual[i] = req.getTravelTime();
			}
			// maxTravelTime check removed: the enumeration's dropoff check
			// already validated every passenger's full in-vehicle time.
			// The floor above can only raise pttActual to directTT which is always <= maxTT.
		}

		// Calculate delays — indexed by pickup position (= requests[] position)
		double[] delays = new double[degree];
		double arrivalAtOrigin = startTime;
		for (int i = 0; i < degree; i++) {
			delays[i] = arrivalAtOrigin - requests[i].getRequestTime();
			if (i < degree - 1) {
				arrivalAtOrigin += connTT[i];
			}
		}

		// Calculate effective delays and detours
		double[] effMaxNeg = new double[degree];
		double[] effMaxPos = new double[degree];
		double[] detours = new double[degree];

		for (int i = 0; i < degree; i++) {
			DrtRequest req = requests[i];
			double detourFactor = pttActual[i] / req.getTravelTime();
			detours[i] = detourFactor;
			double detourTime = req.getTravelTime() * (detourFactor - 1.0);

			double posAdj = req.getPositiveDelayRelComponent() > 0.0
					? Math.max(0.0, req.getPositiveDelayRelComponent() - detourTime) : 0.0;
			double negAdj = req.getNegativeDelayRelComponent() > 0.0
					? Math.max(0.0, req.getNegativeDelayRelComponent() - detourTime) : 0.0;

			effMaxPos[i] = (req.getMaxPositiveDelay() - detourTime) - posAdj;
			effMaxNeg[i] = req.getMaxNegativeDelay() - negAdj;
		}

		double[] adjDelays = optimizeDelays(delays, effMaxNeg, effMaxPos);
		if (adjDelays == null) return null;

		RideKind kind = RideKind.MIXED;

		return Ride.builder()
				.index(index)
				.degree(degree)
				.kind(kind)
				.requests(requests)
				.originsOrderedRequests(originsOrdered)
				.destinationsOrderedRequests(destsOrdered)
				.passengerTravelTimes(pttActual)
				.passengerDistances(pDist)
				.passengerNetworkUtilities(pUtil)
				.delays(adjDelays)
				.detours(detours)
				.connectionTravelTimes(connTT)
				.connectionDistances(connDist)
				.connectionNetworkUtilities(connUtil)
				.startTime(startTime)
				.build();
	}

	// --- Delay optimization ---

	private double[] optimizeDelays(double[] delays, double[] maxNeg, double[] maxPos) {
		for (int i = 0; i < delays.length; i++) {
			if (maxPos[i] < -maxNeg[i]) return null;
		}

		double lower = Double.NEGATIVE_INFINITY, upper = Double.POSITIVE_INFINITY;
		for (int i = 0; i < delays.length; i++) {
			lower = Math.max(lower, -delays[i] - maxNeg[i]);
			upper = Math.min(upper, maxPos[i] - delays[i]);
		}

		if (lower > upper + EPSILON) return null;

		double maxDelay = Double.NEGATIVE_INFINITY, minDelay = Double.POSITIVE_INFINITY;
		for (double d : delays) {
			maxDelay = Math.max(maxDelay, d);
			minDelay = Math.min(minDelay, d);
		}

		double depOpt = -(maxDelay + minDelay) / 2.0;
		depOpt = Math.max(lower, Math.min(upper, depOpt));

		double[] adjusted = new double[delays.length];
		for (int i = 0; i < delays.length; i++) {
			adjusted[i] = delays[i] + depOpt;
			if (adjusted[i] < -maxNeg[i] - EPSILON || adjusted[i] > maxPos[i] + EPSILON) return null;
		}
		return adjusted;
	}

	// --- Pruning and objective ---

	private boolean passesDistanceSavingsPruning(Ride ride) {
		if (exMasConfig == null) {
			return true;
		}
		double scale = exMasConfig.getPruningDistanceSavingsLogScale();
		if (scale < 0) {
			return true;
		}

		int degree = ride.getRequests() != null ? ride.getRequests().length : 0;
		int minDegree = Math.max(2, exMasConfig.getPruningDistanceSavingsMinDegree());
		if (degree < minDegree) {
			return true;
		}

		double sumDistances = sumRequestDistances(ride);
		if (!(sumDistances > 0)) {
			return true;
		}

		double maxSaving = exMasConfig.getPruningDistanceSavingsMax();
		if (!(maxSaving >= 0)) {
			maxSaving = 0.0;
		}
		maxSaving = Math.min(0.99, maxSaving);

		double requiredSaving = requiredSavingForDegree(degree, scale, maxSaving, minDegree);
		double maxRideDistance = (1.0 - requiredSaving) * sumDistances;
		return ride.getRideDistance() <= maxRideDistance;
	}

	/**
	 * Compute the maximum allowed ride distance for a request set based on
	 * the distance savings pruning threshold.
	 *
	 * @return max ride distance in meters, or Double.MAX_VALUE if pruning disabled
	 */
	double computeMaxAllowedRideDistance(DrtRequest[] setRequests) {
		if (exMasConfig == null || exMasConfig.getPruningDistanceSavingsLogScale() < 0) {
			return Double.MAX_VALUE;
		}
		int degree = setRequests.length;
		int minDegree = Math.max(2, exMasConfig.getPruningDistanceSavingsMinDegree());
		if (degree < minDegree) return Double.MAX_VALUE;

		double scale = exMasConfig.getPruningDistanceSavingsLogScale();
		double maxSaving = exMasConfig.getPruningDistanceSavingsMax();
		if (!(maxSaving >= 0)) maxSaving = 0.0;
		maxSaving = Math.min(0.99, maxSaving);

		double requiredSaving = requiredSavingForDegree(degree, scale, maxSaving, minDegree);
		double sumDirectDistances = 0;
		for (DrtRequest r : setRequests) sumDirectDistances += r.directDistance;

		return (1.0 - requiredSaving) * sumDirectDistances;
	}

	private static double requiredSavingForDegree(int degree, double scale, double maxSaving, int minDegree) {
		if (scale < 0) {
			return 0.0;
		}
		if (degree < Math.max(2, minDegree)) {
			return 0.0;
		}
		double requiredSaving = scale * (Math.log(degree) / Math.log(2.0));
		requiredSaving = Math.max(0.0, Math.min(Math.min(0.99, maxSaving), requiredSaving));
		return requiredSaving;
	}

	private double objectiveValue(Ride r) {
		String obj = exMasConfig.getPruningRankingObjective();
		if (obj == null)
			obj = "rideDistance";
		switch (obj) {
			case "passengerTravelTime":
				return sumPassengerTravelTimes(r);
			case "passengerUtility":
				return -sumPassengerUtilities(r);
			case "rideDistance":
			default:
				return r.getRideDistance();
		}
	}

	private double sumRequestDistances(Ride r) {
		return Arrays.stream(r.getRequests())
				.mapToDouble(DrtRequest::getDistance)
				.sum();
	}

	private double sumPassengerTravelTimes(Ride r) {
		double[] t = r.getPassengerTravelTimes();
		double s = 0.0;
		if (t != null)
			for (double v : t)
				s += v;
		return s;
	}

	private double sumPassengerUtilities(Ride r) {
		double[] u = r.getPassengerNetworkUtilities();
		double s = 0.0;
		if (u != null)
			for (double v : u)
				s += v;
		return s;
	}

	// --- Utility methods ---

	private Ride rebuildWithIndex(Ride ride, int newIndex) {
		return ride.toBuilder()
				.index(newIndex)
				.build();
	}

	private static String formatEta(double seconds) {
		if (seconds < 60) return String.format("%.0fs", seconds);
		if (seconds < 3600) return String.format("%.1fmin", seconds / 60.0);
		return String.format("%.1fh", seconds / 3600.0);
	}

	private static int[] buildSortedRequestSet(int[] existing, int newReq) {
		int[] result = new int[existing.length + 1];
		System.arraycopy(existing, 0, result, 0, existing.length);
		result[existing.length] = newReq;
		Arrays.sort(result);
		return result;
	}

	/**
	 * Hash a sorted request index array to a long for memory-efficient set deduplication.
	 * Polynomial rolling hash — collision probability ~n^2/2^64 (negligible at 100M+ sets).
	 */
	private static long hashRequestSet(int[] sortedIndices) {
		long h = 0;
		for (int idx : sortedIndices) {
			h = h * 1000003L + idx;
		}
		return h;
	}

}
