package org.matsim.contrib.demand_extraction.algorithm.generation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideKind;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;
import org.matsim.contrib.demand_extraction.algorithm.bamas.ride.RideRow;
import org.matsim.contrib.demand_extraction.algorithm.bamas.ride.RideLayer;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

/**
 * Generates FIFO and LIFO ride pairs with delay optimization.
 *
 * Uses parallel processing with deterministic output ordering.
 * Stores direct DrtRequest references in generated Rides.
 *
 * Python reference: rides.py lines 55-370
 */
public final class PairGenerator {
	private static final Logger log = LogManager.getLogger(PairGenerator.class);

	private final MatsimNetworkCache network;
	private final BudgetValidator budgetValidator;
	private final double horizon;
	private final boolean useParallel;
	private final boolean budgetAwareConstraints;
	private static final double EPSILON = 1e-9;
	private final AtomicLong beelineRejected = new AtomicLong();

	public PairGenerator(MatsimNetworkCache network, BudgetValidator budgetValidator, double horizon, int algorithmProcessCount) {
		this(network, budgetValidator, horizon, algorithmProcessCount, false);
	}

	public PairGenerator(MatsimNetworkCache network, BudgetValidator budgetValidator, double horizon, int algorithmProcessCount, boolean budgetAwareConstraints) {
		this.network = network;
		this.budgetValidator = budgetValidator;
		this.horizon = horizon;
		this.useParallel = algorithmProcessCount != 1;
		this.budgetAwareConstraints = budgetAwareConstraints;
	}

	/**
	 * Generate pairs with parallel processing and deterministic results.
	 */
	public List<Ride> generatePairs(List<DrtRequest> requests) {
		return generatePairs(requests.toArray(new DrtRequest[0]));
	}

	/**
	 * Intermediate candidate holding ride data before index assignment.
	 * Used for parallel collection followed by deterministic sorting.
	 */
	private record PairCandidate(
			DrtRequest reqI, DrtRequest reqJ, RideKind kind,
			DrtRequest[] originsOrderedRequests, DrtRequest[] destinationsOrderedRequests,
			double[] passengerTravelTimes, double[] passengerDistances,
			double[] passengerNetworkUtilities, double[] delays, double[] detours,
			double[] connectionTravelTimes, double[] connectionDistances,
			double[] connectionNetworkUtilities, double startTime) {

		/**
		 * Comparator for deterministic ordering: by (reqI.index, reqJ.index, kind).
		 */
		static final Comparator<PairCandidate> COMPARATOR = Comparator
				.comparingInt((PairCandidate c) -> c.reqI.index)
				.thenComparingInt(c -> c.reqJ.index)
				.thenComparing(c -> c.kind);
	}

	/**
	 * Generate FIFO and LIFO pairs from requests.
	 * Parallel processing with deterministic output order.
	 */
	public List<Ride> generatePairs(DrtRequest[] requests) {
		long startTime = System.currentTimeMillis();
		List<PairCandidate> candidates = collectSortedCandidates(requests, startTime);

		// Phase 3: Validate and assign indices sequentially
		List<Ride> pairs = new ArrayList<>();
		int nextRideIndex = requests.length; // Start after single rides
		int fifoCreated = 0;
		int lifoCreated = 0;

		for (PairCandidate c : candidates) {
			Ride ride = buildRide(c, nextRideIndex);
			Ride validated = budgetValidator.validateAndPopulateBudgets(ride);
			if (validated != null) {
				pairs.add(validated);
				retainPairChainSegments(c);
				nextRideIndex++;
				if (c.kind == RideKind.FIFO) fifoCreated++;
				else lifoCreated++;
			}
		}

		logCompletion(requests.length, pairs.size(), fifoCreated, lifoCreated, candidates.size(), startTime);
		return pairs;
	}

	/**
	 * Stub-mode (Task 13) pair generation: identical to {@link #generatePairs(DrtRequest[])}
	 * but emits a compact degree-2 {@link RideLayer} instead of a fat {@code List<Ride>}.
	 *
	 * <p>Phases 1 (candidate collection) and 2 (deterministic sort) are shared verbatim via
	 * {@link #collectSortedCandidates}. Phase 3 replays the SAME build + validate sequence
	 * in the SAME order; each surviving fat ride is converted to a stub row via
	 * {@link RideRow#fromRide(Ride)} and appended with {@link RideLayer#addRow}, then
	 * discarded. So:
	 * <ul>
	 *   <li>The 6.8M-ride transient fat list (~27 GB at 100%) never exists — only one
	 *       transient {@link Ride} per validated candidate, plus the ~30 B/row stub columns.</li>
	 *   <li>The row order is byte-identical to the fat {@code pairs} list order, which is the
	 *       insertion order the downstream dedup (contract #1) and export (contract #3) depend on.</li>
	 *   <li>The FIFO/LIFO kind is preserved in {@code flags} (RideRow.kindToFlags), so the
	 *       shareability-graph edge kind and the CSV {@code kind} column both reproduce master.</li>
	 * </ul>
	 *
	 * @param requests global request array
	 * @return all valid pairs as a degree-2 {@link RideLayer} (the complete pre-dedup universe)
	 */
	public RideLayer generatePairLayer(DrtRequest[] requests) {
		long startTime = System.currentTimeMillis();
		List<PairCandidate> candidates = collectSortedCandidates(requests, startTime);

		// Identity position lookup: each reqArray element object → its position. The pair
		// candidates' reqI/reqJ are the SAME objects as elements of `requests` (TimeFilter
		// shallow-clones the array), so an identity lookup recovers the exact reqArray
		// position. This is the copy handle the stub must carry: under Paper-2 Extension-2
		// hub expansion several DrtRequest copies share one `index`, so the index alone
		// cannot recover the generation copy — the reqArray POSITION can. (See Task 13.)
		IdentityHashMap<DrtRequest, Integer> idPos = new IdentityHashMap<>(requests.length * 2);
		for (int p = 0; p < requests.length; p++) idPos.put(requests[p], p);

		// Phase 3: Validate sequentially, emit stubs in the same order as the fat path.
		RideLayer pairs = new RideLayer(2);
		int nextRideIndex = requests.length; // Start after single rides (mirrors the fat path)
		int fifoCreated = 0;
		int lifoCreated = 0;

		for (PairCandidate c : candidates) {
			Ride ride = buildRide(c, nextRideIndex);
			Ride validated = budgetValidator.validateAndPopulateBudgets(ride);
			if (validated != null) {
				RideRow s = RideRow.fromRide(validated);
				// Positions aligned to s.sortedSet: for each sorted global index, pick whichever
				// of the pair's two request objects has that index, then look up its reqArray
				// position. A pair always has two DISTINCT indices (different parents), so the
				// mapping is unambiguous; guards below fail loud if either assumption breaks.
				if (c.reqI.index == c.reqJ.index) {
					throw new IllegalStateException(
							"Pair candidate has two requests sharing index " + c.reqI.index
							+ " — degree-2 sorted-set/position alignment is ambiguous");
				}
				int[] positions = new int[s.sortedSet.length];
				for (int k = 0; k < s.sortedSet.length; k++) {
					DrtRequest member = (s.sortedSet[k] == c.reqI.index) ? c.reqI : c.reqJ;
					Integer pos = idPos.get(member);
					if (pos == null) {
						throw new IllegalStateException(
								"Pair request object (index " + member.index + ") is not a reqArray "
								+ "element — identity position lookup failed; reqArray identity assumption broken");
					}
					positions[k] = pos;
				}
				pairs.addRow(s.sortedSet, s.originPacked, s.destPacked, s.distDm, s.ttDs, s.flags, positions);
				retainPairChainSegments(c);
				nextRideIndex++;
				if (c.kind == RideKind.FIFO) fifoCreated++;
				else lifoCreated++;
			}
		}

		logCompletion(requests.length, pairs.size(), fifoCreated, lifoCreated, candidates.size(), startTime);
		return pairs;
	}

	/**
	 * Phases 1+2 shared by the fat and stub generation paths: parallel candidate collection
	 * followed by the deterministic {@code (reqI.index, reqJ.index, kind)} sort. Pure with
	 * respect to output ordering — identical inputs yield an identically ordered candidate list.
	 */
	private List<PairCandidate> collectSortedCandidates(DrtRequest[] requests, long startTime) {
		log.info("Generating pair rides from {} requests (horizon={}s) [{}]...",
				requests.length, horizon, useParallel ? "parallel" : "sequential");

		TimeFilter filter = new TimeFilter(requests);
		AtomicInteger processedRequests = new AtomicInteger(0);
		int total = filter.size();

		// Global SSSP bound — see batchPrecompute call below for rationale.
		double globalMaxTravelTime = 0;
		for (DrtRequest r : requests) {
			if (r.getMaxTravelTime() > globalMaxTravelTime) globalMaxTravelTime = r.getMaxTravelTime();
		}
		final double ssspBound = globalMaxTravelTime;
		log.info("  SSSP stop-criterion bound: {}s (global max maxTravelTime)", String.format("%.1f", ssspBound));

		// Phase 1: Parallel collection of candidates (without indices)
		java.util.stream.IntStream requestStream = IntStream.range(0, total);
		if (useParallel) {
			requestStream = requestStream.parallel();
		}

		List<PairCandidate> candidates = requestStream
				.mapToObj(i -> {
					int processed = processedRequests.incrementAndGet();
					if (processed == total || (int)(100.0 * processed / total) > (int)(100.0 * (processed - 1) / total)) {
						double percent = (processed * 100.0) / total;
						long now = System.currentTimeMillis();
						double elapsedSeconds = Math.max(0.001, (now - startTime) / 1000.0);
						double rate = processed / elapsedSeconds;
						double remainingSeconds = (total - processed) / Math.max(rate, 1e-9);
						log.info("  Pair generation progress: {}/{} ({}%), ETA {}",
								processed, total, String.format("%.1f", percent), formatDuration(remainingSeconds));
					}
					List<PairCandidate> out = generateCandidatesForRequest(filter, i, ssspBound);
					// Origin-batch barrier: sample heap and rotate the speculative tier under
					// pressure. Output-invariant (cross-engine value identity); HeapWatermark is
					// synchronized so a parallel race here is safe and at watermark 1.0 a no-op.
					network.checkWatermark();
					return out;
				})
				.flatMap(List::stream)
				.collect(Collectors.toList());

		// Phase 2: Sort deterministically by (reqI.index, reqJ.index, kind)
		candidates.sort(PairCandidate.COMPARATOR);
		return candidates;
	}

	private void logCompletion(int requestCount, int pairCount, int fifoCreated, int lifoCreated,
			int candidateCount, long startTime) {
		long elapsed = System.currentTimeMillis() - startTime;
		double seconds = elapsed / 1000.0;
		double pairsPerSecond = pairCount / Math.max(seconds, 0.001);
		log.info("Pair generation complete: {} pairs from {} requests in {}s ({} pairs/s)",
				pairCount, requestCount, String.format("%.1f", seconds), String.format("%.1f", pairsPerSecond));
		log.info("  Created: {} FIFO, {} LIFO (from {} candidates)",
				fifoCreated, lifoCreated, candidateCount);
		log.info("  Beeline pre-filter rejected {} candidate pairs before routing", beelineRejected.get());
	}


	private static String formatDuration(double seconds) {
		long totalSeconds = Math.max(0L, Math.round(seconds));
		long hours = totalSeconds / 3600;
		long minutes = (totalSeconds % 3600) / 60;
		long secs = totalSeconds % 60;
		if (hours > 0) {
			return String.format("%dh%02dm%02ds", hours, minutes, secs);
		}
		if (minutes > 0) {
			return String.format("%dm%02ds", minutes, secs);
		}
		return String.format("%ds", secs);
	}

	/**
	 * Euclidean (beeline) distance between two points.
	 */
	private static double beeline(double x1, double y1, double x2, double y2) {
		double dx = x2 - x1, dy = y2 - y1;
		return Math.sqrt(dx * dx + dy * dy);
	}

	/**
	 * Generate all valid candidates for a single request index.
	 */
	@SuppressWarnings("unchecked")
	private List<PairCandidate> generateCandidatesForRequest(TimeFilter filter, int i, double ssspBound) {
		List<PairCandidate> results = new ArrayList<>();
		DrtRequest reqI = filter.getRequest(i);
		// Per-request window filter (horizon used only as a floor): a lossless
		// superset of the exact temporal-overlap check below. The flat horizon
		// alone drops long-trip pairs whose request times differ by more than the
		// horizon but whose departure/arrival windows still overlap.
		int[] candidateIndices = filter.findCandidatesInWindow(i, horizon);

		// Batch precompute O→O segments: one SSSP from reqI.origin covers all candidates
		Id<Link>[] candidateOrigins = new Id[candidateIndices.length];
		Id<Link>[] candidateDestinations = new Id[candidateIndices.length];
		for (int k = 0; k < candidateIndices.length; k++) {
			DrtRequest reqK = filter.getRequest(candidateIndices[k]);
			candidateOrigins[k] = reqK.originLinkId;
			candidateDestinations[k] = reqK.destinationLinkId;
		}
		// SSSP bound = global max maxTravelTime across all requests. Per-request or per-origin
		// bounds are unsafe: the cache key (origin, dest, timeBin) is anonymous, so a downstream
		// d≥3 lookup can need a segment beyond any single-request bound but within the bound of
		// some other passenger that ends up traversing it. Global-max guarantees that any segment
		// reachable on the network appears in the cache, eliminating false-unreachable misses.
		network.batchPrecompute(reqI.originLinkId, reqI.requestTime, candidateOrigins, ssspBound);

		// Batch precompute D→D segments: one SSSP from reqI.dest covers all FIFO D_i→D_j lookups
		network.batchPrecompute(reqI.destinationLinkId, reqI.requestTime, candidateDestinations, ssspBound);

		for (int j : candidateIndices) {
			DrtRequest reqJ = filter.getRequest(j);

			// Skip same person
			if (reqI.getPaxId().equals(reqJ.getPaxId())) continue;

			// Quick temporal filter
			if (reqJ.getLatestDeparture() < reqI.getEarliestDeparture() ||
					reqJ.getEarliestDeparture() > reqI.getLatestDeparture() + reqI.getTravelTime()) {
				continue;
			}

			// O→O segment: cache hit from batch precompute
			TravelSegment oo = network.getSegment(reqI.originLinkId, reqJ.originLinkId, reqI.requestTime);
			if (!oo.isReachable()) continue;

			// Reject if O→O alone exceeds reqI's detour budget
			if (oo.getDistance() > reqI.directDistance * reqI.maxDetourFactor) continue;

			// Additional temporal check with actual O→O travel time
			if (reqI.getLatestDeparture() + oo.getTravelTime() < reqJ.getEarliestDeparture()) continue;
			if (reqI.getEarliestDeparture() + oo.getTravelTime() > reqJ.getLatestDeparture()) continue;

			// Beeline pre-filter for remaining legs (O→D, D→D)
			double beeOD = beeline(reqJ.originX, reqJ.originY, reqI.destinationX, reqI.destinationY);
			double beeDD = beeline(reqI.destinationX, reqI.destinationY, reqJ.destinationX, reqJ.destinationY);
			double beeOJ = beeline(reqJ.originX, reqJ.originY, reqJ.destinationX, reqJ.destinationY);
			double beeJD = beeline(reqJ.destinationX, reqJ.destinationY, reqI.destinationX, reqI.destinationY);

			// FIFO: use actual O→O distance + beeline for remaining legs
			boolean fifoFeasible =
				(oo.getDistance() + beeOD) <= reqI.directDistance * reqI.maxDetourFactor &&
				(beeOD + beeDD) <= reqJ.directDistance * reqJ.maxDetourFactor;

			// LIFO: use actual O→O distance + beeline for remaining legs
			boolean lifoFeasible =
				(oo.getDistance() + beeOJ + beeJD) <= reqI.directDistance * reqI.maxDetourFactor;

			if (!fifoFeasible && !lifoFeasible) {
				beelineRejected.incrementAndGet();
				continue;
			}

			if (fifoFeasible) {
				PairCandidate fifo = tryFifoCandidate(reqI, reqJ, oo);
				if (fifo != null) results.add(fifo);
			}

			if (lifoFeasible) {
				PairCandidate lifo = tryLifoCandidate(reqI, reqJ, oo);
				if (lifo != null) results.add(lifo);
			}
		}

		return results;
	}

	/**
	 * Stub-mode (Task 13) pair MATERIALIZATION: rebuild one degree-2 ride from a stub by
	 * reproducing the exact routing the generator used, NOT {@code buildRideFromOrdering}.
	 *
	 * <p>Why a dedicated path is required: {@code BamasRideExtender.buildRideFromOrdering}
	 * routes each connection segment at a CUMULATIVE clock ({@code currentTime += connTT[i]}),
	 * landing in time bins the pair generator never warmed — the generator routes ALL pair
	 * segments at the single fixed bin {@code reqI.requestTime}. For degree-3+ that is fine
	 * (those orderings were enumerated through {@code buildRideFromOrdering} at cumulative time,
	 * warming exactly those keys), but a degree-2 pair re-routed at cumulative time can hit an
	 * unwarmed bin and an on-demand SSSP that returns unreachable. Routing here at the same
	 * fixed {@code reqI.requestTime} as generation reproduces the generator's segments
	 * bit-for-bit, so the rebuilt ride's distance/time match the stored stub columns.
	 *
	 * <p>The beeline/temporal pre-gates are intentionally skipped: this row was already a
	 * winning candidate, so it passed those gates at generation. Only the gated creation
	 * ({@code tryFifoCandidate}/{@code tryLifoCandidate} + {@code buildRide} + budget
	 * population) is replayed.
	 *
	 * <p>Budget population is intentionally NOT done here: the caller
	 * ({@code RideMaterializer.materialize}) runs the same {@code validateAndPopulateBudgets}
	 * uniformly for every degree after its distance/time self-check, exactly as it does for the
	 * degree-3+ path. Returning the routed-but-unvalidated ride keeps that single validation site
	 * and avoids a redundant budget recompute.
	 *
	 * @param reqI first-pickup request (pickup local position 0)
	 * @param reqJ second-pickup request (pickup local position 1)
	 * @param kind FIFO or LIFO, decoded from the stub's flags
	 * @return the routed ride (index 0; engine re-indexes after sort, caller populates budgets),
	 *         or {@code null} if the route did not reproduce (caller treats as a hard error)
	 */
	public Ride rebuildPair(DrtRequest reqI, DrtRequest reqJ, RideKind kind) {
		TravelSegment oo = network.getSegment(reqI.originLinkId, reqJ.originLinkId, reqI.requestTime);
		if (!oo.isReachable()) return null;

		PairCandidate c = (kind == RideKind.FIFO)
				? tryFifoCandidate(reqI, reqJ, oo)
				: tryLifoCandidate(reqI, reqJ, oo);
		if (c == null) return null;

		return buildRide(c, 0);
	}

	/**
	 * Retain the chain segments an accepted pair was routed from into the never-evicted retained
	 * tier (design 2026-06-12 §3, Task 7), so degree-3+ extension and the export materializer re-read
	 * them as cache hits instead of re-routing.
	 *
	 * <p><b>Why retain the stored value, not {@code promoteSegment}.</b> The previous implementation
	 * called {@link MatsimNetworkCache#promoteSegment}, which is a <em>no-op on a cache miss</em>. But
	 * a chain segment is routed during the parallel candidate collection, and {@code checkWatermark()}
	 * evicts the speculative tier per origin-batch <em>during</em> that same collection — so by the
	 * time this single-threaded Phase-3 loop runs, an early pair's segments may already be evicted,
	 * the promote no-ops, and the segment is absent from the checkpoint journal. On resume the
	 * materializer then re-routes every such ride (the observed export bottleneck). Here we instead
	 * retain the candidate's STORED connection values directly: {@link #tryFifoCandidate}/
	 * {@link #tryLifoCandidate} captured {@code (tt, dist, util)} for each chain leg at routing time,
	 * and value-source determinism (cross-engine identity) makes the stored value bit-identical to a
	 * later re-route — so retention is complete regardless of eviction timing, and output is unchanged.
	 */
	private void retainPairChainSegments(PairCandidate c) {
		DrtRequest i = c.reqI;
		DrtRequest j = c.reqJ;
		double t = i.requestTime;
		double[] tt = c.connectionTravelTimes;
		double[] ds = c.connectionDistances;
		double[] ut = c.connectionNetworkUtilities;
		// Leg 0 is O_i -> O_j for both kinds; legs 1,2 differ by FIFO/LIFO exactly as the candidate
		// builders routed and stored them (FIFO: {oo, od, dd}; LIFO: {oo, oj, jd}).
		network.retainSegment(i.originLinkId, j.originLinkId, t, tt[0], ds[0], ut[0]);
		if (c.kind == RideKind.FIFO) {
			// O_j -> D_i, D_i -> D_j (mirrors tryFifoCandidate).
			network.retainSegment(j.originLinkId, i.destinationLinkId, t, tt[1], ds[1], ut[1]);
			network.retainSegment(i.destinationLinkId, j.destinationLinkId, t, tt[2], ds[2], ut[2]);
		} else {
			// O_j -> D_j, D_j -> D_i (mirrors tryLifoCandidate).
			network.retainSegment(j.originLinkId, j.destinationLinkId, t, tt[1], ds[1], ut[1]);
			network.retainSegment(j.destinationLinkId, i.destinationLinkId, t, tt[2], ds[2], ut[2]);
		}
	}

	/**
	 * Build final Ride from candidate with assigned index.
	 */
	private Ride buildRide(PairCandidate c, int index) {
		return Ride.builder()
				.index(index)
				.degree(2)
				.kind(c.kind)
				.requests(new DrtRequest[] { c.reqI, c.reqJ })
				.originsOrderedRequests(c.originsOrderedRequests)
				.destinationsOrderedRequests(c.destinationsOrderedRequests)
				.passengerTravelTimes(c.passengerTravelTimes)
				.passengerDistances(c.passengerDistances)
				.passengerNetworkUtilities(c.passengerNetworkUtilities)
				.delays(c.delays)
				.detours(c.detours)
				.connectionTravelTimes(c.connectionTravelTimes)
				.connectionDistances(c.connectionDistances)
				.connectionNetworkUtilities(c.connectionNetworkUtilities)
				.startTime(c.startTime)
				.build();
	}

	/**
	 * Try to create a FIFO candidate (first pickup, first dropoff).
	 */
	private PairCandidate tryFifoCandidate(DrtRequest i, DrtRequest j, TravelSegment oo) {
		TravelSegment od = network.getSegment(j.originLinkId, i.destinationLinkId, i.requestTime);
		TravelSegment dd = network.getSegment(i.destinationLinkId, j.destinationLinkId, i.requestTime);

		if (!od.isReachable() || !dd.isReachable()) return null;

		double pttI = oo.getTravelTime() + od.getTravelTime();
		double pttJ = od.getTravelTime() + dd.getTravelTime();

		pttI = Math.max(pttI, i.getTravelTime());
		pttJ = Math.max(pttJ, j.getTravelTime());

		if (pttI > i.getMaxTravelTime() || pttJ > j.getMaxTravelTime()) return null;

		double detourI = pttI / i.getTravelTime();
		double detourJ = pttJ / j.getTravelTime();

		double[] effMaxPos = calculateEffectiveMaxPos(i, j, detourI, detourJ);
		double[] effMaxNeg = calculateEffectiveMaxNeg(i, j, detourI, detourJ);

		double initialDelayJ = i.getRequestTime() + oo.getTravelTime() - j.getRequestTime();
		double[] delays = { 0.0, initialDelayJ };

		double[] adjusted = optimizeDelays(delays, effMaxNeg, effMaxPos);
		if (adjusted == null) return null;

		// Budget-aware wait filter: reject if reqJ's optimised delay exceeds its budget-derived cap.
		// Guard on flag so that flag-off path is bit-identical to pre-Phase-C behaviour.
		if (budgetAwareConstraints && adjusted[1] > j.maxWaitTime) return null;

		// FIFO: pickup order [i, j], dropoff order [i, j]
		return new PairCandidate(
				i, j, RideKind.FIFO,
				new DrtRequest[] { i, j },
				new DrtRequest[] { i, j },
				new double[] { pttI, pttJ },
				new double[] { oo.getDistance() + od.getDistance(), od.getDistance() + dd.getDistance() },
				new double[] { oo.getNetworkUtility() + od.getNetworkUtility(), od.getNetworkUtility() + dd.getNetworkUtility() },
				adjusted,
				new double[] { detourI, detourJ },
				new double[] { oo.getTravelTime(), od.getTravelTime(), dd.getTravelTime() },
				new double[] { oo.getDistance(), od.getDistance(), dd.getDistance() },
				new double[] { oo.getNetworkUtility(), od.getNetworkUtility(), dd.getNetworkUtility() },
				i.getRequestTime());
	}

	/**
	 * Try to create a LIFO candidate (first pickup, last dropoff).
	 */
	private PairCandidate tryLifoCandidate(DrtRequest i, DrtRequest j, TravelSegment oo) {
		TravelSegment oj = network.getSegment(j.originLinkId, j.destinationLinkId, i.requestTime);
		TravelSegment jd = network.getSegment(j.destinationLinkId, i.destinationLinkId, i.requestTime);

		if (!oj.isReachable() || !jd.isReachable()) return null;

		double pttI = oo.getTravelTime() + oj.getTravelTime() + jd.getTravelTime();
		double pttJ = oj.getTravelTime();

		pttI = Math.max(pttI, i.getTravelTime());
		pttJ = Math.max(pttJ, j.getTravelTime());

		if (pttI > i.getMaxTravelTime() || pttJ > j.getMaxTravelTime()) return null;

		double detourI = pttI / i.getTravelTime();
		double detourJ = pttJ / j.getTravelTime();

		double[] effMaxPos = calculateEffectiveMaxPos(i, j, detourI, detourJ);
		double[] effMaxNeg = calculateEffectiveMaxNeg(i, j, detourI, detourJ);

		double initialDelayJ = i.getRequestTime() + oo.getTravelTime() - j.getRequestTime();
		double[] delays = { 0.0, initialDelayJ };

		double[] adjusted = optimizeDelays(delays, effMaxNeg, effMaxPos);
		if (adjusted == null) return null;

		// Budget-aware wait filter: reject if reqJ's optimised delay exceeds its budget-derived cap.
		if (budgetAwareConstraints && adjusted[1] > j.maxWaitTime) return null;

		// LIFO: pickup order [i, j], dropoff order [j, i]
		return new PairCandidate(
				i, j, RideKind.LIFO,
				new DrtRequest[] { i, j },
				new DrtRequest[] { j, i },
				new double[] { pttI, pttJ },
				new double[] { oo.getDistance() + oj.getDistance() + jd.getDistance(), oj.getDistance() },
				new double[] { oo.getNetworkUtility() + oj.getNetworkUtility() + jd.getNetworkUtility(), oj.getNetworkUtility() },
				adjusted,
				new double[] { detourI, detourJ },
				new double[] { oo.getTravelTime(), oj.getTravelTime(), jd.getTravelTime() },
				new double[] { oo.getDistance(), oj.getDistance(), jd.getDistance() },
				new double[] { oo.getNetworkUtility(), oj.getNetworkUtility(), jd.getNetworkUtility() },
				i.getRequestTime());
	}

	private double[] calculateEffectiveMaxPos(DrtRequest i, DrtRequest j, double detourI, double detourJ) {
		// Convert detour factors to absolute time: detourTime = directTime * (factor -
		// 1.0)
		double detourTimeI = i.getTravelTime() * (detourI - 1.0);
		double detourTimeJ = j.getTravelTime() * (detourJ - 1.0);

		double posAdjI = i.getPositiveDelayRelComponent() > 0.0
				? Math.max(0.0, i.getPositiveDelayRelComponent() - detourTimeI)
				: 0.0;
		double posAdjJ = j.getPositiveDelayRelComponent() > 0.0
				? Math.max(0.0, j.getPositiveDelayRelComponent() - detourTimeJ)
				: 0.0;
		return new double[] {
				(i.getMaxPositiveDelay() - detourTimeI) - posAdjI,
				(j.getMaxPositiveDelay() - detourTimeJ) - posAdjJ
		};
	}

	private double[] calculateEffectiveMaxNeg(DrtRequest i, DrtRequest j, double detourI, double detourJ) {
		// Convert detour factors to absolute time: detourTime = directTime * (factor -
		// 1.0)
		double detourTimeI = i.getTravelTime() * (detourI - 1.0);
		double detourTimeJ = j.getTravelTime() * (detourJ - 1.0);

		double negAdjI = i.getNegativeDelayRelComponent() > 0.0
				? Math.max(0.0, i.getNegativeDelayRelComponent() - detourTimeI)
				: 0.0;
		double negAdjJ = j.getNegativeDelayRelComponent() > 0.0
				? Math.max(0.0, j.getNegativeDelayRelComponent() - detourTimeJ)
				: 0.0;
		return new double[] {
				i.getMaxNegativeDelay() - negAdjI,
				j.getMaxNegativeDelay() - negAdjJ
		};
	}

	private double[] optimizeDelays(double[] delays, double[] maxNeg, double[] maxPos) {
		// Check initial feasibility
		for (int i = 0; i < delays.length; i++) {
			if (maxPos[i] < -maxNeg[i]) return null;
		}

		// Calculate bounds
		double lower = Double.NEGATIVE_INFINITY;
		double upper = Double.POSITIVE_INFINITY;

		for (int i = 0; i < delays.length; i++) {
			lower = Math.max(lower, -delays[i] - maxNeg[i]);
			upper = Math.min(upper, maxPos[i] - delays[i]);
		}

		if (lower > upper + EPSILON) return null;

		// Find optimal departure adjustment
		double maxDelay = Double.NEGATIVE_INFINITY;
		double minDelay = Double.POSITIVE_INFINITY;
		for (double d : delays) {
			maxDelay = Math.max(maxDelay, d);
			minDelay = Math.min(minDelay, d);
		}

		double depOpt = -(maxDelay + minDelay) / 2.0;
		depOpt = Math.max(lower, Math.min(upper, depOpt));

		// Apply adjustment
		double[] adjusted = new double[delays.length];
		for (int i = 0; i < delays.length; i++) {
			adjusted[i] = delays[i] + depOpt;
			if (adjusted[i] < -maxNeg[i] - EPSILON || adjusted[i] > maxPos[i] + EPSILON) {
				return null;
			}
		}

		return adjusted;
	}

	/**
	 * Top-K partner cap mask: keep the rows whose partner is among the {@code k} partners
	 * with the highest per-partner best saving. Pure and deterministic — partners are
	 * ranked by best saving descending, ties broken by partner index ascending.
	 *
	 * @param partnerIndex per-row partner global index (length = row count)
	 * @param saving       per-row absolute distance saving (same length)
	 * @param k            partner cap; {@code k <= 0} or "<= k distinct partners" ⇒ keep all
	 * @return per-row boolean keep mask
	 */
	static boolean[] keepMask(int[] partnerIndex, double[] saving, int k) {
		int n = partnerIndex.length;
		boolean[] mask = new boolean[n];
		if (k <= 0) {
			java.util.Arrays.fill(mask, true);
			return mask;
		}
		java.util.Map<Integer, Double> best = new java.util.HashMap<>();
		for (int r = 0; r < n; r++) {
			best.merge(partnerIndex[r], saving[r], Math::max);
		}
		if (best.size() <= k) {
			java.util.Arrays.fill(mask, true);
			return mask;
		}
		java.util.List<Integer> partners = new java.util.ArrayList<>(best.keySet());
		partners.sort((a, b) -> {
			int c = Double.compare(best.get(b), best.get(a)); // saving descending
			if (c != 0) return c;
			return Integer.compare(a, b);                      // tie: index ascending
		});
		java.util.Set<Integer> keep = new java.util.HashSet<>(partners.subList(0, k));
		for (int r = 0; r < n; r++) {
			mask[r] = keep.contains(partnerIndex[r]);
		}
		return mask;
	}
}
