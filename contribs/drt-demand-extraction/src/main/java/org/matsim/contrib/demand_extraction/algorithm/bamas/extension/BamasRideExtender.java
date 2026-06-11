package org.matsim.contrib.demand_extraction.algorithm.bamas.extension;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideKind;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;
import org.matsim.contrib.demand_extraction.algorithm.bamas.graph.DegreeGraph;
import org.matsim.contrib.demand_extraction.algorithm.bamas.stub.OrderingCodec;
import org.matsim.contrib.demand_extraction.algorithm.bamas.stub.RideStub;
import org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns;
import org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubScaling;
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
public final class BamasRideExtender {
	private static final Logger log = LogManager.getLogger(BamasRideExtender.class);

	private final MatsimNetworkCache network;
	private final ShareabilityGraph graph;
	private final BudgetValidator budgetValidator;
	private final Map<Integer, DrtRequest> requestMap;
	private final ExMasConfigGroup exMasConfig;
	private static final double EPSILON = 1e-9;
	private static final double TIME_FEASIBILITY_EPSILON = 1.0;

	// DegreeGraph from previous degree for candidate generation (null at degree 3)
	private final DegreeGraph prevDegreeGraph;
	// Stored after extendRides completes: valid rides by set hash, used for graph building
	private ConcurrentHashMap<Long, Ride> lastResultBySetHash;
	// Stub-mode shadow: compact SoA container for the last degree's winning rides,
	// sorted in the same lex order as the fat results list. Null when stub mode is off.
	// Task 11: the engine captures this via getLastDegreeStubs() and feeds it as the next
	// degree's parents (degree 3→4+) and as the per-degree layer to materialize at the end.
	// buildDegreeGraph still consumes the fat resultBySetHash (populated unconditionally and
	// order-independent → identical graph); Task 12 migrates the graph build to stubs.
	private StubColumns lastDegreeStubs;

	public BamasRideExtender(MatsimNetworkCache network, ShareabilityGraph graph, BudgetValidator budgetValidator,
						List<DrtRequest> requests, ExMasConfigGroup exMasConfig) {
		this(network, graph, budgetValidator, requests, exMasConfig, null);
	}

	public BamasRideExtender(MatsimNetworkCache network, ShareabilityGraph graph, BudgetValidator budgetValidator,
						List<DrtRequest> requests, ExMasConfigGroup exMasConfig,
						DegreeGraph prevDegreeGraph) {
		this.network = network;
		this.graph = graph;
		this.budgetValidator = budgetValidator;
		this.requestMap = new HashMap<>();
		for (DrtRequest r : requests) requestMap.put(r.index, r);
		this.exMasConfig = exMasConfig;
		this.prevDegreeGraph = prevDegreeGraph;
	}

	/** Build a DegreeGraph from the valid rides produced by the last extendRides call. */
	public DegreeGraph buildDegreeGraph(int degree) {
		if (lastResultBySetHash == null || lastResultBySetHash.isEmpty()) return null;
		return DegreeGraph.buildFromRides(lastResultBySetHash.values(), degree);
	}

	/** Returns the number of feasible sets from the last extendRides call. */
	public int getFeasibleSetCount() {
		return lastResultBySetHash != null ? lastResultBySetHash.size() : 0;
	}

	/**
	 * Stub-mode shadow container for the last degree's winning rides, sorted in the
	 * same lex order as the fat result list. Null when stub mode is off or no
	 * {@code extendRides} call has run yet.
	 *
	 * <p>Task 11: the engine captures this after each {@code extendRides} and feeds it
	 * as the next degree's parents (degree 3→4 and up). It is also the per-degree layer
	 * accumulated for end-of-run batch materialization.
	 */
	public StubColumns getLastDegreeStubs() {
		return lastDegreeStubs;
	}

	/**
	 * Extend rides from degree D to degree D+1 using ordering-based enumeration.
	 *
	 * <p>Streaming producer/consumer: a single producer thread walks parent rides
	 * in deterministic sort order, claims each unique child-set hash in a
	 * {@link LongOpenHashSet}, and offers {@link ExtensionTask}s to a bounded
	 * {@link ArrayBlockingQueue}. N workers drain the queue in parallel and
	 * call {@link #processSet} (enumerate orderings, route, validate). Claim
	 * order follows producer iteration, so canonical parent selection is
	 * deterministic regardless of worker completion order.
	 *
	 * @param ridesToExtend degree-D rides (1 per request set)
	 * @param nextRideIndex starting index for new rides
	 * @return list of degree-(D+1) rides, one per feasible set
	 */
	public List<Ride> extendRides(List<Ride> ridesToExtend, int nextRideIndex) {
		int targetDegree = ridesToExtend.isEmpty() ? 0 : ridesToExtend.get(0).getDegree() + 1;
		List<ParentView> parentViews = new ArrayList<>(ridesToExtend.size());
		for (Ride r : ridesToExtend) parentViews.add(new RideParentView(r));
		return extendParents(parentViews, targetDegree, nextRideIndex);
	}

	/**
	 * Stub-mode parent consumption (seam a): extend a degree-D layer held as a
	 * {@link StubColumns} to degree D+1. Each parent row is wrapped in a
	 * {@link StubParentView} that reconstructs {@code originsGlobal()} /
	 * {@code destsGlobal()} / {@code requestIndices()} from the packed local
	 * positions, and reports {@code rideDistance()} via {@link StubScaling#fromDeci}
	 * — bit-identical to the old {@code getRideDistance()} double (Task 4), so the
	 * EPSILON comparison in {@link #compareParentCanonicalKey} reproduces the exact
	 * canonical parent choice the fat path made.
	 *
	 * @param parentStubs  degree-D winning rides as a SoA container (sorted lex)
	 * @param requestTable global request array indexed by {@link DrtRequest#index}
	 * @param nextRideIndex starting index for new rides
	 * @return list of degree-(D+1) rides, one per feasible set
	 */
	public List<Ride> extendRides(StubColumns parentStubs, DrtRequest[] requestTable, int nextRideIndex) {
		int targetDegree = parentStubs.size() == 0 ? 0 : parentStubs.degree() + 1;
		List<ParentView> parentViews = new ArrayList<>(parentStubs.size());
		for (int row = 0; row < parentStubs.size(); row++) {
			parentViews.add(new StubParentView(parentStubs, row, requestTable));
		}
		return extendParents(parentViews, targetDegree, nextRideIndex);
	}

	private List<Ride> extendParents(List<ParentView> ridesToExtend, int targetDegree, int nextRideIndex) {
		log.info("Extending {} sets from degree {} to {} ...",
				ridesToExtend.size(), targetDegree - 1, targetDegree);
		long phaseStartTime = System.currentTimeMillis();

		// Sort parents by (routedDistance ASC, sortedRequestIndices lex) so the
		// streaming producer claims each child set with the tightest-seeding
		// parent first. Ties on distance fall through to lex for determinism.
		// See compareParentCanonicalKey for the full order definition.
		List<ParentView> parents = new ArrayList<>(ridesToExtend);
		parents.sort((a, b) -> compareParentCanonicalKey(
				a.rideDistance(), a.sortedRequestIndices(),
				b.rideDistance(), b.sortedRequestIndices()));

		// Collect unique base sets for neighbor enumeration (iteration order is
		// now deterministic since `parents` is sorted).
		List<int[]> uniqueBaseSets = new ArrayList<>();
		{
			var seen = new java.util.HashSet<String>();
			for (ParentView ride : parents) {
				int[] idx = ride.sortedRequestIndices();
				if (seen.add(Arrays.toString(idx))) {
					uniqueBaseSets.add(idx);
				}
			}
		}

		// Determine parallelism
		int parallelism = exMasConfig.getAlgorithmProcessCount();
		if (parallelism <= 0) parallelism = Runtime.getRuntime().availableProcessors();
		log.info("  {} base rides in {} unique request sets, {} threads",
				parents.size(), uniqueBaseSets.size(), parallelism);

		// Per-set ordering node budget B (Design A; 0 = off). Static config constant
		// for the whole run, set once here before the worker pool starts so the write
		// happens-before every worker thread reads it during enumeration.
		long orderingNodeBudget = exMasConfig.getMaxOrderingNodesAfterFirstValid();
		EnumerationStats.setMaxOrderingNodesAfterFirstValid(orderingNodeBudget);
		if (orderingNodeBudget > 0) {
			log.info("  ordering node budget: {} nodes after first-valid per set (high-degree tail cap)",
					orderingNodeBudget);
		}

		// Streaming dedup: single producer walks parents in sorted order,
		// claims each unique child-set hash, and offers tasks to a bounded
		// queue. Workers drain the queue in parallel. Claim order = producer
		// iteration order = sort order, so canonical parent selection is
		// deterministic regardless of worker completion order.
		//
		// Memory win vs the prior two-phase design: the CanonicalExtension
		// map materialized ~64M entries at deg-4/25% (~6-8 GB). Here the only
		// dedup state is a LongOpenHashSet of claimed hashes (~1 GB at the
		// same scale, ~3x leaner than HashSet<Long>) plus a bounded queue of at most 4 * parallelism tasks.
		ConcurrentHashMap<Long, Ride> resultBySetHash = new ConcurrentHashMap<>();
		AtomicInteger setsProcessed = new AtomicInteger();
		AtomicInteger resultsFound = new AtomicInteger();
		AtomicInteger totalEnqueued = new AtomicInteger(0);
		// Progress is reported against the KNOWN input size (parents.size()), not
		// the streamed child-set counts. The producer blocks on the bounded queue
		// when workers lag, so parentsProcessed tracks real end-to-end throughput
		// to within one queue length and yields a meaningful ETA. (The old
		// done/totalEnqueued ratio sat ~one queue-length apart by construction, so
		// its ETA was structurally always ~1s. See git history.)
		AtomicInteger parentsProcessed = new AtomicInteger();
		final int parentsTotal = parents.size();
		AtomicLong lastProgressLogTime = new AtomicLong(System.currentTimeMillis());
		ConcurrentHashMap<Long, EnumerationStats> threadStatsMap = new ConcurrentHashMap<>();
		// Per-thread stub buffers: keyed by Thread.currentThread().getId(). Only populated
		// when stubModeEnabled; otherwise stays empty. Each thread only writes to its own
		// key, so no locking is needed beyond computeIfAbsent's atomicity on the map.
		ConcurrentHashMap<Long, StubColumns> stubBuffers = new ConcurrentHashMap<>();

		BlockingQueue<ExtensionTask> queue = new ArrayBlockingQueue<>(Math.max(16, parallelism * 4));
		ExecutorService workers = Executors.newFixedThreadPool(parallelism);

		// We need the final counts *after* enumeration for the completion log.
		// They're written by the producer and read by the main thread after join.
		int[] enumerationCounters = new int[2]; // [0] = totalEnumerated, [1] = claimedCount

		// Submit N worker tasks
		for (int t = 0; t < parallelism; t++) {
			workers.submit(() -> {
				threadStatsMap.putIfAbsent(Thread.currentThread().getId(), EnumerationStats.get());
				while (true) {
					ExtensionTask task;
					try {
						task = queue.take();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return;
					}
					if (task == POISON) return;

					int done = setsProcessed.incrementAndGet();
					Ride bestRide = processSet(task.newSet, task.newSetHash, targetDegree, task.parentRide);
					if (bestRide != null) {
						resultBySetHash.put(task.newSetHash, bestRide);
						resultsFound.incrementAndGet();
						if (exMasConfig.isStubModeEnabled()) {
							RideStub s = RideStub.fromRide(bestRide);
							stubBuffers.computeIfAbsent(Thread.currentThread().getId(),
									id -> new StubColumns(targetDegree))
									.addRow(s.sortedSet, s.originPacked, s.destPacked,
											s.distDm, s.ttDs, s.flags);
						}
					}

					// Progress log every 30 seconds
					long now = System.currentTimeMillis();
					long prev = lastProgressLogTime.get();
					if (now - prev >= 30_000 && lastProgressLogTime.compareAndSet(prev, now)) {
						double elapsed = (now - phaseStartTime) / 1000.0;
						int pDone = parentsProcessed.get();
						double pRate = pDone / Math.max(0.001, elapsed);
						String etaStr = (parentsTotal > pDone)
								? formatExtensionDuration((parentsTotal - pDone) / Math.max(0.001, pRate))
								: "—";
						log.info("  Progress: {}/{} parents ({} child-sets, {} results), {} parents/s, ETA {}",
								pDone, parentsTotal, done, resultsFound.get(), String.format("%.0f", pRate), etaStr);
					}
				}
			});
		}

		// Producer: single thread walks parents in sort order, claims hashes,
		// enqueues tasks. Sequential by construction so claim order is
		// deterministic.
		//
		// Interrupt safety: if the producer throws (e.g. queue.put interrupted)
		// before the poison pills are sent, the worker threads stay blocked on
		// queue.take() forever. The try/finally calls workers.shutdownNow() on
		// any exceptional exit so blocked workers unblock via InterruptedException
		// and return cleanly. On the happy path the poison pills drain the queue
		// normally and shutdownNow() is skipped.
		boolean producerCompleted = false;
		try {
			// expectedClaimed is the expected element count (~= parents.size() * 4
			// at deg-4/25 %, ~64 M at scale). LongOpenHashSet(int) takes the expected
			// element count directly and handles load factor internally — no pre-division needed.
			int expectedClaimed = Math.max(1024, parents.size() * 4);
			LongOpenHashSet claimedHashes = new LongOpenHashSet(expectedClaimed);
			int totalEnumerated = 0;
			for (ParentView parentRide : parents) {
				int[] baseSetIndices = parentRide.sortedRequestIndices();
				int[] neighbors;
				if (prevDegreeGraph != null) {
					neighbors = prevDegreeGraph.findExtensions(baseSetIndices);
				} else {
					neighbors = graph.findCommonNeighborsSorted(baseSetIndices);
				}
				for (int newReq : neighbors) {
					int[] newSet = buildSortedRequestSet(baseSetIndices, newReq);
					long newSetHash = hashRequestSet(newSet);
					totalEnumerated++;
					if (!claimedHashes.add(newSetHash)) {
						continue; // duplicate child set — a lex-smaller parent already claimed it
					}
					totalEnqueued.incrementAndGet();
					queue.put(new ExtensionTask(parentRide, newSet, newSetHash));
				}
				// All children of this parent are claimed+enqueued. The bounded
				// queue throttles the producer to ~one queue length ahead of the
				// workers, so this counter tracks real progress for the ETA.
				parentsProcessed.incrementAndGet();
			}
			enumerationCounters[0] = totalEnumerated;
			enumerationCounters[1] = claimedHashes.size();

			// Signal workers to finish
			for (int t = 0; t < parallelism; t++) {
				queue.put(POISON);
			}
			producerCompleted = true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("Producer interrupted", e);
		} finally {
			if (!producerCompleted) {
				// Release blocked workers so they exit queue.take() via
				// InterruptedException and return without processing more tasks.
				workers.shutdownNow();
			}
		}

		// Wait for workers to drain (normal path) or exit (abnormal path).
		// shutdown() is a no-op after shutdownNow(); awaitTermination just
		// confirms the workers have actually finished.
		workers.shutdown();
		try {
			if (!workers.awaitTermination(24, TimeUnit.HOURS)) {
				throw new RuntimeException("Workers did not terminate within 24h");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("Interrupted waiting for workers", e);
		}

		int totalEnumerated = enumerationCounters[0];
		int canonicalCount = enumerationCounters[1];
		int dedupSkipped = totalEnumerated - canonicalCount;

		// Log profiling stats (thread-local values captured via threadStatsMap)
		if (!threadStatsMap.isEmpty()) {
			EnumerationStats total = EnumerationStats.sum(threadStatsMap.values());
			total.log(log, targetDegree, parallelism);
			// Reset thread-local stats for next degree
			threadStatsMap.values().forEach(EnumerationStats::clear);
		}

		// Store results for graph building (accessed by buildDegreeGraph)
		this.lastResultBySetHash = resultBySetHash;

		// Stub-mode shadow: merge per-worker buffers into one sorted StubColumns.
		// Guarded so the fat path (flag off) is byte-identical to before this task.
		if (exMasConfig.isStubModeEnabled()) {
			this.lastDegreeStubs = stubBuffers.isEmpty()
					? new StubColumns(targetDegree > 0 ? targetDegree : 1)
					: StubColumns.mergeSorted(stubBuffers.values());
		}

		// Deterministic output order: sort by sorted-request-indices lex.
		// Required because resultBySetHash.values() iteration is not deterministic
		// on ConcurrentHashMap — without this sort, parallel runs produce
		// byte-different CSVs even though the ride set is identical.
		List<Ride> results = new ArrayList<>(resultBySetHash.values());
		results.sort((a, b) -> compareSortedIntArrays(sortedRequestIndices(a), sortedRequestIndices(b)));

		// Assign sequential indices after sort so ride indices are deterministic.
		// Stamp in place rather than rebuilding: a toBuilder().build() clone deep-copies
		// every array, and because the originals stay pinned in resultBySetHash (retained
		// via lastResultBySetHash for the degree-graph build), the clones doubled per-degree
		// Ride retention and OOM'd at 100%. Mutating index keeps the result list and the
		// map sharing the same Ride instances (1x retention); output is byte-identical.
		for (int i = 0; i < results.size(); i++) {
			results.get(i).assignIndex(nextRideIndex + i);
		}

		long elapsed = System.currentTimeMillis() - phaseStartTime;
		log.info("Extension complete: {} rides at degree {} in {}s ({} candidate sets, {} threads, {} skipped dedup, {} base sets)",
				results.size(), targetDegree, String.format("%.1f", elapsed / 1000.0),
				canonicalCount, parallelism, dedupSkipped, uniqueBaseSets.size());
		org.matsim.contrib.demand_extraction.algorithm.profiling.MemoryProfiler
				.snapshotAtEndOfDegree(targetDegree, results.size());

		return results;
	}

	/** Return a freshly allocated sorted copy of a ride's request indices. */
	private static int[] sortedRequestIndices(Ride ride) {
		int[] idx = ride.getRequestIndices().clone();
		Arrays.sort(idx);
		return idx;
	}

	/** Lexicographic total order on sorted int arrays. */
	private static int compareSortedIntArrays(int[] a, int[] b) {
		int len = Math.min(a.length, b.length);
		for (int i = 0; i < len; i++) {
			int cmp = Integer.compare(a[i], b[i]);
			if (cmp != 0) return cmp;
		}
		return Integer.compare(a.length, b.length);
	}

	/**
	 * Total order on (parent routedDistance, parent sortedRequestIndices).
	 * Primary key: routed distance ASC (shortest parent gives the tightest
	 * B&B seed for the child set). Secondary key: lex on sorted indices to
	 * break distance ties deterministically.
	 *
	 * <p>Distances within {@link #EPSILON} are treated as equal to prevent
	 * FP noise from flipping canonical parent choices between runs.
	 *
	 * <p><b>Stub-path equivalence (Plan A seam a):</b> for stub parents the
	 * distance fed here is {@link StubScaling#fromDeci(int)}, i.e. an exact
	 * multiple of 0.1. Since {@code EPSILON}=1e-9 ≪ 0.1, distinct decimetre
	 * values always differ by ≥0.1 and are ordered, while equal decimetre
	 * values give {@code diff==0} and fall through to the lex tie-break —
	 * exactly what an {@code Integer.compare(dmA, dmB)} on the raw decimetre
	 * columns would yield. So this {@code double}+EPSILON form is bit-for-bit
	 * equivalent to a no-epsilon int comparison on every input; the byte-identical
	 * parity gate confirms it. (Replacing it with a literal int comparison is
	 * deferred to the Task-15 scaling-purity review, item (b).)
	 *
	 * <p>Package-private for unit testing — keep the signature primitive
	 * so tests don't need to construct full {@link Ride} fixtures.
	 */
	static int compareParentCanonicalKey(double distA, int[] indicesA,
										 double distB, int[] indicesB) {
		double diff = distA - distB;
		if (diff < -EPSILON) return -1;
		if (diff > EPSILON) return 1;
		return compareSortedIntArrays(indicesA, indicesB);
	}

	/**
	 * Minimal read-only view of a parent ride, abstracting over the two parent
	 * kinds the extender consumes (seam a):
	 * <ul>
	 *   <li>{@link RideParentView} — a fat {@link Ride} (degree 2→3, pairs stay fat).</li>
	 *   <li>{@link StubParentView} — one row of a {@link StubColumns} layer (3→4+).</li>
	 * </ul>
	 *
	 * <p>Exposes exactly what the sort, the producer loop and {@link #processSet}
	 * consume: the canonical-key distance, the sorted request set, and the global
	 * pickup/dropoff orderings. Nothing else off the parent is read.
	 */
	private interface ParentView {
		/** Routed ride distance (canonical-key primary). For stubs this is {@code fromDeci}. */
		double rideDistance();
		/** Sorted (ascending) global request indices. */
		int[] sortedRequestIndices();
		/** Global request indices (unsorted is acceptable; only used for set membership). */
		int[] requestIndices();
		/** Global request indices in parent pickup order. */
		int[] originsGlobal();
		/** Global request indices in parent dropoff order. */
		int[] destsGlobal();
	}

	/** {@link ParentView} backed by a fat {@link Ride}. */
	private static final class RideParentView implements ParentView {
		private final Ride ride;
		RideParentView(Ride ride) { this.ride = ride; }
		@Override public double rideDistance() { return ride.getRideDistance(); }
		@Override public int[] sortedRequestIndices() { return BamasRideExtender.sortedRequestIndices(ride); }
		@Override public int[] requestIndices() { return ride.getRequestIndices(); }
		@Override public int[] originsGlobal() { return ride.getOriginsIndex(); }
		@Override public int[] destsGlobal() { return ride.getDestinationsIndex(); }
	}

	/**
	 * {@link ParentView} backed by one row of a {@link StubColumns} layer.
	 *
	 * <p>{@code rideDistance()} returns {@link StubScaling#fromDeci}, which is
	 * bit-identical to the original 0.1-rounded {@code Ride.getRideDistance()} double
	 * (Task 4 proved this). Combined with {@link #compareParentCanonicalKey}'s EPSILON
	 * (1e-9 ≪ 0.1), the canonical parent choice is identical to the fat path.
	 *
	 * <p>The origin/dest orderings are reconstructed via the same unpack-then-map logic
	 * as {@link RideStub#originsGlobal()} / {@link RideStub#destsGlobal()}.
	 */
	private static final class StubParentView implements ParentView {
		private final StubColumns cols;
		private final int row;
		// Cached sorted request set (the stored slice is already sorted ascending).
		private final int[] sortedSet;
		StubParentView(StubColumns cols, int row, DrtRequest[] requestTable) {
			this.cols = cols;
			this.row = row;
			this.sortedSet = cols.requestIndices(row); // defensive copy, sorted ascending
		}
		@Override public double rideDistance() {
			return StubScaling.fromDeci(cols.rideDistanceDm(row));
		}
		@Override public int[] sortedRequestIndices() { return sortedSet.clone(); }
		@Override public int[] requestIndices() { return sortedSet.clone(); }
		@Override public int[] originsGlobal() {
			return mapLocalsToGlobal(OrderingCodec.unpack(cols.originOrder(row), cols.degree()));
		}
		@Override public int[] destsGlobal() {
			return mapLocalsToGlobal(OrderingCodec.unpack(cols.destOrder(row), cols.degree()));
		}
		private int[] mapLocalsToGlobal(int[] locals) {
			int[] globals = new int[locals.length];
			for (int i = 0; i < locals.length; i++) globals[i] = sortedSet[locals[i]];
			return globals;
		}
	}

	/**
	 * Work item handed from the producer to a worker: one claimed child set
	 * with its seed parent ride. Allocated once per claimed set. No references
	 * beyond the parent ride and the child int[]; GC'd after worker processes.
	 */
	private static final class ExtensionTask {
		final ParentView parentRide;
		final int[] newSet;
		final long newSetHash;

		ExtensionTask(ParentView parentRide, int[] newSet, long newSetHash) {
			this.parentRide = parentRide;
			this.newSet = newSet;
			this.newSetHash = newSetHash;
		}
	}

	/** Sentinel poison pill used to signal worker termination. */
	private static final ExtensionTask POISON = new ExtensionTask(null, null, 0L);

	/**
	 * Process a single candidate set: enumerate orderings, route, validate, return best ride.
	 * Thread-safe — only reads shared immutable/thread-safe resources.
	 *
	 * @param parentRide the best ride for the base set at the previous degree, viewed
	 *                   through {@link ParentView} so it can be backed by either a fat
	 *                   {@link Ride} (degree 2→3) or a {@link StubColumns} row (3→4+).
	 * @return best validated ride for this set, or null if no valid ordering exists
	 */
	private Ride processSet(int[] newSet, long setHash, int targetDegree, ParentView parentRide) {
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

		long tEnum0 = System.nanoTime();

		// Compute seed data from parent ride. Parent indices are global; use the
		// set's own ordering of global indices (newSet is sorted) to keep them as
		// global — the enumerator will remap to its own local indexing via
		// requestIndices[]. seedNewRequest is the element of newSet not in parent.
		int[] seedParentOrigin = parentRide.originsGlobal();
		int[] seedParentDest = parentRide.destsGlobal();
		int seedNewRequest = findNewRequest(newSet, parentRide.requestIndices());

		OrderingEnumerator.enumerateAndEvaluateSeeded(
				newSet, graph, network, setRequests, bestValidDist,
				seedParentOrigin, seedParentDest, seedNewRequest,
				exMasConfig.isEnableBudgetAwareConstraints(),
				(ordering) -> evaluateOrdering(ordering, newSet, setRequests,
						bestValidDist, bestRide, stats));

		stats.timeEnumeration += System.nanoTime() - tEnum0;
		stats.timeTotal += System.nanoTime() - t0;

		if (bestRide[0] != null) {
			stats.setsConstraintFeasible++;
			stats.setsBudgetFeasible++;
		}

		return bestRide[0];
	}

	/** Build a Ride from a completed ordering, validate budget, and track best-so-far. */
	private void evaluateOrdering(OrderingEnumerator.Ordering ordering, int[] newSet,
								   DrtRequest[] setRequests, double[] bestValidDist,
								   Ride[] bestRide, EnumerationStats stats) {
		stats.orderingsEvaluated++;
		int n = newSet.length;
		DrtRequest[] originsOrdered = new DrtRequest[n];
		DrtRequest[] destsOrdered = new DrtRequest[n];
		for (int i = 0; i < n; i++) {
			originsOrdered[i] = setRequests[ordering.originPerm()[i]];
			destsOrdered[i] = setRequests[ordering.destPerm()[i]];
		}

		long tBuild0 = System.nanoTime();
		Ride ride = buildRideFromOrdering(network, originsOrdered, destsOrdered, 0,
				ordering.connTT(), ordering.connDist(), ordering.connUtil());
		stats.timeRideConstruction += System.nanoTime() - tBuild0;
		stats.ridesBuilt++;
		if (ride == null) {
			stats.rideNullFailures++;
			return;
		}
		stats.ridesPassedConstraints++;

		Ride validated;
		if (exMasConfig.isDeferExtensionBudgetValidation()) {
			// Defer budget validation to a single batch step after extension completes.
			// ride.remainingBudgets stays null; BudgetValidator.populateBudgetsBatch fills it later.
			validated = ride;
		} else {
			long tBudget0 = System.nanoTime();
			validated = budgetValidator.validateAndPopulateBudgets(ride);
			stats.timeBudgetValidation += System.nanoTime() - tBudget0;
			stats.budgetValidations++;
			if (validated == null) {
				return;
			}
			stats.budgetPassed++;
		}

		double dist = validated.getRideDistance();
		if (dist < bestValidDist[0]) {
			boolean firstValidForThisSet = (bestRide[0] == null);
			bestValidDist[0] = dist;
			bestRide[0] = validated;
			stats.newBestRides++;
			if (firstValidForThisSet) {
				stats.parentSeedRidesFound++;
			}
		} else {
			stats.validButWorseThanBest++;
		}
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
	static Ride buildRideFromOrdering(MatsimNetworkCache network,
									   DrtRequest[] originsOrdered,
									   DrtRequest[] destsOrdered, int index,
									   double[] preConnTT, double[] preConnDist,
									   double[] preConnUtil) {
		// Extension rides are always MIXED-kind (the ordering enumerator does not
		// distinguish FIFO/LIFO). Pair (degree-2) materialization needs to restore
		// the original FIFO/LIFO kind from the stub's flags — see the kind-aware
		// overload below, used only by RideMaterializer.
		return buildRideFromOrdering(network, originsOrdered, destsOrdered, index,
				preConnTT, preConnDist, preConnUtil, RideKind.MIXED);
	}

	/**
	 * Kind-aware variant of {@link #buildRideFromOrdering}. Identical routing /
	 * metric / budget logic; the only difference is the {@link RideKind} stamped on
	 * the built ride.
	 *
	 * <p>Required for degree-2 pair materialization (Task 13): a pair stub carries
	 * its original FIFO/LIFO kind in {@code flags}, and the CSV output writes
	 * {@code ride.getKind()} verbatim, so a materialized pair must reproduce
	 * FIFO/LIFO — not the {@link RideKind#MIXED} the extension path uses. Degree-3+
	 * stubs encode MIXED in their flags ({@code RideStub.fromRide}), so passing the
	 * decoded kind is a no-op for them.
	 */
	static Ride buildRideFromOrdering(MatsimNetworkCache network,
									   DrtRequest[] originsOrdered,
									   DrtRequest[] destsOrdered, int index,
									   double[] preConnTT, double[] preConnDist,
									   double[] preConnUtil, RideKind rideKind) {
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

		RideKind kind = rideKind;

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

	private static double[] optimizeDelays(double[] delays, double[] maxNeg, double[] maxPos) {
		for (int i = 0; i < delays.length; i++) {
			if (maxPos[i] < -maxNeg[i] - TIME_FEASIBILITY_EPSILON) return null;
		}

		double lower = Double.NEGATIVE_INFINITY, upper = Double.POSITIVE_INFINITY;
		for (int i = 0; i < delays.length; i++) {
			lower = Math.max(lower, -delays[i] - maxNeg[i]);
			upper = Math.min(upper, maxPos[i] - delays[i]);
		}

		if (lower > upper + TIME_FEASIBILITY_EPSILON) return null;

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
			if (adjusted[i] < -maxNeg[i] - TIME_FEASIBILITY_EPSILON
					|| adjusted[i] > maxPos[i] + TIME_FEASIBILITY_EPSILON) return null;
		}
		return adjusted;
	}

	// --- Pruning and objective ---

	private boolean passesDistanceSavingsPruning(Ride ride) {
		if (exMasConfig == null) {
			return true;
		}
		int degree = ride.getRequests() != null ? ride.getRequests().length : 0;
		double sumDistances = sumRequestDistances(ride);
		if (!(sumDistances > 0)) {
			return true;
		}
		double maxRideDistance = computeMaxAllowedRideDistance(degree, sumDistances, exMasConfig);
		return ride.getRideDistance() <= maxRideDistance;
	}

	/**
	 * Compute the maximum allowed ride distance for a request set based on
	 * the distance savings pruning threshold.
	 *
	 * @return max ride distance in meters, or Double.MAX_VALUE if pruning disabled
	 */
	double computeMaxAllowedRideDistance(DrtRequest[] setRequests) {
		if (exMasConfig == null) return Double.MAX_VALUE;
		double sumDirectDistances = 0;
		for (DrtRequest r : setRequests) sumDirectDistances += r.directDistance;
		if (!(sumDirectDistances > 0)) return Double.MAX_VALUE;
		return computeMaxAllowedRideDistance(setRequests.length, sumDirectDistances, exMasConfig);
	}

	/**
	 * Branch on gate shape: linear (intercept + slope*d) when configured, else
	 * the log gate. Returns the maximum allowed ride distance for a pool at the
	 * given degree, or {@link Double#MAX_VALUE} when the gate is disabled.
	 */
	public static double computeMaxAllowedRideDistance(int degree, double sumDistances,
			org.matsim.contrib.demand_extraction.config.ExMasConfigGroup cfg) {
		if (cfg == null || !(sumDistances > 0)) return Double.MAX_VALUE;
		double maxSaving = cfg.getPruningDistanceSavingsMax();
		if (!(maxSaving >= 0)) maxSaving = 0.0;
		maxSaving = Math.min(0.99, maxSaving);

		if (cfg.hasLinearGate()) {
			double gate = cfg.getPruningGateLinearIntercept() + cfg.getPruningGateLinearSlope() * degree;
			// Floor at (1 - maxSaving) to bound how aggressive the gate can get at
			// high degree. Gate > 1.0 is permitted (loose at low degree).
			gate = Math.max(1.0 - maxSaving, gate);
			return gate * sumDistances;
		}

		double scale = cfg.getPruningDistanceSavingsLogScale();
		if (scale < 0) return Double.MAX_VALUE;
		int minDegree = Math.max(2, cfg.getPruningDistanceSavingsMinDegree());
		if (degree < minDegree) return Double.MAX_VALUE;
		double requiredSaving = scale * (Math.log(degree) / Math.log(2.0));
		requiredSaving = Math.max(0.0, Math.min(Math.min(0.99, maxSaving), requiredSaving));
		return (1.0 - requiredSaving) * sumDistances;
	}

	private double sumRequestDistances(Ride r) {
		return Arrays.stream(r.getRequests())
				.mapToDouble(DrtRequest::getDistance)
				.sum();
	}

	// --- Utility methods ---

	private static String formatEta(double seconds) {
		if (seconds < 60) return String.format("%.0fs", seconds);
		if (seconds < 3600) return String.format("%.1fmin", seconds / 60.0);
		return String.format("%.1fh", seconds / 3600.0);
	}

	private static String formatExtensionDuration(double seconds) {
		if (!Double.isFinite(seconds) || seconds <= 0) return "0s";
		long t = (long) Math.ceil(seconds);
		long h = t / 3600, m = (t % 3600) / 60, s = t % 60;
		if (h > 0) return String.format("%dh%02dm%02ds", h, m, s);
		if (m > 0) return String.format("%dm%02ds", m, s);
		return String.format("%ds", s);
	}

	private static int[] buildSortedRequestSet(int[] existing, int newReq) {
		int[] result = new int[existing.length + 1];
		System.arraycopy(existing, 0, result, 0, existing.length);
		result[existing.length] = newReq;
		Arrays.sort(result);
		return result;
	}

	/** Return the global request index in {@code newSet} not present in {@code parentSet}. */
	private static int findNewRequest(int[] newSet, int[] parentSet) {
		int[] sorted = parentSet.clone();
		java.util.Arrays.sort(sorted);
		for (int r : newSet) {
			if (java.util.Arrays.binarySearch(sorted, r) < 0) return r;
		}
		throw new IllegalStateException("newSet does not contain a new request");
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
