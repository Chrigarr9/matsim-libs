package org.matsim.contrib.demand_extraction.algorithm.bamas;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.demand_extraction.algorithm.domain.HyperPooledRide;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideKind;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideVariant;
import org.matsim.contrib.demand_extraction.algorithm.bamas.extension.BamasRideExtender;
import org.matsim.contrib.demand_extraction.algorithm.bamas.checkpoint.CheckpointKillSwitch;
import org.matsim.contrib.demand_extraction.algorithm.bamas.checkpoint.CheckpointManager;
import org.matsim.contrib.demand_extraction.algorithm.bamas.checkpoint.RunFingerprint;
import org.matsim.contrib.demand_extraction.algorithm.bamas.graph.DegreeGraph;
import org.matsim.contrib.demand_extraction.algorithm.engine.PostExtensionPruner;
import org.matsim.contrib.demand_extraction.algorithm.generation.PairGenerator;
import org.matsim.contrib.demand_extraction.algorithm.bamas.generation.BamasSingleRideGenerator;
import org.matsim.contrib.demand_extraction.algorithm.generation.StopBasedRideGenerator;
import org.matsim.contrib.demand_extraction.algorithm.graph.ShareabilityGraph;
import org.matsim.contrib.demand_extraction.algorithm.hyperpool.HyperPoolGenerator;
import org.matsim.contrib.demand_extraction.algorithm.hyperpool.StopCompatibilityChecker;
import org.matsim.contrib.demand_extraction.algorithm.network.ConnectionCacheJournal;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.bamas.stub.MaterializedRideStore;
import org.matsim.contrib.demand_extraction.algorithm.bamas.stub.RideStore;
import org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubRideStore;
import org.matsim.contrib.demand_extraction.algorithm.stops.StopFinder;
import org.matsim.contrib.demand_extraction.algorithm.stops.StopFinderFactory;
import org.matsim.contrib.demand_extraction.algorithm.stops.WalkingDistanceCalculator;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.demand.BudgetToConstraintsCalculator;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;
import org.matsim.facilities.ActivityFacilities;

/**
 * Main orchestrator for ExMAS algorithm with MATSim integration.
 * 
 * Generates shareable rides from DRT requests using:
 * - Budget-based feasibility validation
 * - MATSim network routing
 * - Iterative ride extension up to maxDegree
 */
public final class BamasEngine {
	private static final Logger log = LogManager.getLogger(BamasEngine.class);

	private final MatsimNetworkCache network;
	private final BudgetValidator budgetValidator;
	private final double horizon;
	private final int maxDegree;
	private final org.matsim.contrib.demand_extraction.config.ExMasConfigGroup exMasConfig;
	private final ActivityFacilities facilities; // Optional, for predefined stop finder
	private final BudgetToConstraintsCalculator budgetToConstraints; // Optional, for budget-aware stop search

	private List<DrtRequest> requests;
	private List<Ride> allRides;
	private List<HyperPooledRide> hyperPooledRides;
	private ShareabilityGraph graph;

	// Plan A3 — optional routing-input file paths for the checkpoint fingerprint. When set,
	// their content hashes enrich the (otherwise config-only) RunFingerprint so a resume refuses
	// to continue against changed requests/travel-times/network even when the config is identical.
	// Left null by the DI adapter (BamasAlgorithm) today — the engine only sees in-memory requests
	// and an in-memory network, not their source files; the runner that owns those paths calls
	// setFingerprintInputs() once a checkpoint CLI surface exists. Null ⇒ config-only fingerprint
	// (exactly what Task 3 wrote), so write/resume stay symmetric and existing checkpoints remain
	// compatible. See Plan A3 Task 4 review note.
	private java.nio.file.Path fpRequestsPath;
	private java.nio.file.Path fpTravelTimesPath;
	private java.nio.file.Path fpNetworkPath;

	public BamasEngine(MatsimNetworkCache network, BudgetValidator budgetValidator,
					   double horizon, int maxDegree,
					   org.matsim.contrib.demand_extraction.config.ExMasConfigGroup exMasConfig) {
		this(network, budgetValidator, horizon, maxDegree, exMasConfig, null, null);
	}

	public BamasEngine(MatsimNetworkCache network, BudgetValidator budgetValidator,
					   double horizon, int maxDegree,
					   org.matsim.contrib.demand_extraction.config.ExMasConfigGroup exMasConfig,
					   ActivityFacilities facilities) {
		this(network, budgetValidator, horizon, maxDegree, exMasConfig, facilities, null);
	}

	public BamasEngine(MatsimNetworkCache network, BudgetValidator budgetValidator,
					   double horizon, int maxDegree,
					   org.matsim.contrib.demand_extraction.config.ExMasConfigGroup exMasConfig,
					   ActivityFacilities facilities,
					   BudgetToConstraintsCalculator budgetToConstraints) {
		this.network = network;
		this.budgetValidator = budgetValidator;
		this.horizon = horizon;
		this.maxDegree = maxDegree;
		this.exMasConfig = exMasConfig;
		this.facilities = facilities;
		this.budgetToConstraints = budgetToConstraints;
	}

	/**
	 * Plan A3 — supply the routing-input file paths hashed into the checkpoint fingerprint. Any
	 * argument may be {@code null} (then that input is omitted from the hash). Must be called
	 * before {@link #run} to take effect. Both a fresh write and a later resume must pass the same
	 * paths for the fingerprints to match.
	 */
	public void setFingerprintInputs(java.nio.file.Path requestsPath,
			java.nio.file.Path travelTimesPath, java.nio.file.Path networkPath) {
		this.fpRequestsPath = requestsPath;
		this.fpTravelTimesPath = travelTimesPath;
		this.fpNetworkPath = networkPath;
	}

    /**
     * Run ExMAS algorithm on DRT requests with budget validation.
     * 
     * @param drtRequests MATSim requests with budget constraints
     * @return a {@link RideStore} over all feasible rides (single, pairs, and extensions
     *         up to maxDegree). On the memory-critical D2D path
     *         ({@code stubModeEnabled && !enableStopBased}) this is a streaming
     *         {@link StubRideStore} that materializes rows lazily; otherwise a
     *         {@link MaterializedRideStore} wrapping the fat, sorted, reindexed list.
     */
    public RideStore run(List<DrtRequest> drtRequests) {
		log.info("======================================================================");
		log.info("Starting ExMAS algorithm");
		log.info("  Requests: {}", drtRequests.size());
		log.info("  Horizon: {}s", horizon);
		log.info("  Max degree: {}", maxDegree);
		log.info("======================================================================");
		long algorithmStartTime = System.currentTimeMillis();

        this.requests = drtRequests;
        this.allRides = new ArrayList<>();
        this.hyperPooledRides = new ArrayList<>();
        
        DrtRequest[] reqArray = drtRequests.toArray(new DrtRequest[0]);

        // Global-index → request lookup, built identically to BamasRideExtender.requestMap
        // (same iteration order ⇒ same last-write-wins winner on a shared index). Required
        // for stub materialization + stub pruning: Paper-2 Extension-2 hub expansion emits
        // virtual copies that share the parent's DrtRequest.index, so index != array position
        // and several requests collide on one index. The fat extender resolves every set
        // member through this map, so winning rides are built from the map's canonical copy;
        // resolving stubs the same way reproduces the exact origin/dest links (positional
        // reqArray indexing would pick a different colliding copy → wrong/unreachable OD).
        java.util.Map<Integer, DrtRequest> requestById = new java.util.HashMap<>();
        for (DrtRequest r : drtRequests) requestById.put(r.index, r);

		int algorithmProcessCount = exMasConfig.getAlgorithmProcessCount();

		// Phase 1: Generate single rides with budget validation
		log.info("");
		log.info("PHASE 1: Single Ride Generation");
		log.info("======================================================================");
		BamasSingleRideGenerator singleGen = new BamasSingleRideGenerator(network, budgetValidator, algorithmProcessCount);
        List<Ride> singleRides = singleGen.generate(drtRequests);
		allRides.addAll(singleRides);

		// Check if we should stop before generating pairs
		if (maxDegree < 2) {
			return completeEarly(algorithmStartTime, "maxDegree < 2, skipping pair generation");
		}

		final boolean stubMode = exMasConfig.isStubModeEnabled();
		// Task 12 — streaming export path. Only the memory-critical D2D run streams: when
		// stop-based pooling is enabled, Phase 5 needs the materialized degree-3+ D2D rides
		// as generation INPUT, so we stay on the existing fat path (batch-materialize →
		// Phase 5/6 → sort/reindex → MaterializedRideStore). The gate and the 100% target run
		// both have stop_based=false, so they take the streaming branch.
		final boolean streamingD2D = stubMode && !exMasConfig.isEnableStopBased();
		// Task 13 — degree-2 pair stubs. On the streaming D2D path with extension (maxDegree>2),
		// the 6.8M-pair universe is generated, graphed, and deduped as compact StubColumns,
		// never as a 27 GB fat List<Ride>. The maxDegree<=2 early exit and the fat/stop-based
		// fallback keep the original fat pair flow (those are not the memory-critical runs).
		final boolean pairStubPath = streamingD2D && maxDegree > 2;

		// Plan A3 — per-degree checkpoint/resume (off unless checkpointDir is set). Only the
		// streaming pair-stub D2D path is supported: that IS the week-long exact 100% run the
		// checkpoints exist for. On any other path the knob is a logged no-op (HyperPool
		// checkpointing is Plan A2's concern; maxDegree<=2 has no extension to resume).
		//
		// The fingerprint is computed ONCE here (Task 4) over the config plus, when supplied via
		// setFingerprintInputs(), the routing-input file hashes — and is used by BOTH the
		// checkpoint writes below and the resume gate. If a manifest already exists in the dir its
		// fingerprint must MATCH (else refuse: a different config/requests would silently corrupt);
		// a matching manifest puts the run in RESUME mode (load completed degrees, skip pair-gen
		// + the completed extension loop). No manifest ⇒ a fresh, checkpoint-writing run.
		CheckpointManager checkpointMgr = null;
		boolean resuming = false;
		CheckpointManager.Manifest resumeManifest = null;
		// Plan A3 Task 5 — connection-cache journal writer (open only on the checkpointing
		// pair-stub path; null otherwise). Persists the SSSP-populated routing entries from the
		// phases resume SKIPS, so a resumed run reproduces those (non-point-to-point-reproducible)
		// cache values exactly. Closed before the streaming-export return.
		ConnectionCacheJournal.Writer cacheJournal = null;
		if (exMasConfig.isCheckpointingEnabled()) {
			if (pairStubPath) {
				String fingerprint = RunFingerprint.compute(exMasConfig,
						fpRequestsPath, fpTravelTimesPath, fpNetworkPath, "bamas");
				checkpointMgr = new CheckpointManager(
						java.nio.file.Path.of(exMasConfig.getCheckpointDir()), fingerprint);
				checkpointMgr.init();
				if (checkpointMgr.hasManifest()) {
					CheckpointManager.Manifest m = checkpointMgr.readManifest();
					if (!RunFingerprint.matches(m.fingerprint, fingerprint)) {
						throw new IllegalStateException(
								"Checkpoint in " + exMasConfig.getCheckpointDir()
								+ " is for a different config/requests (fingerprint mismatch) — "
								+ "delete the checkpoint dir or use a fresh one before resuming.");
					}
					resumeManifest = m;
					resuming = true;
					checkpointMgr.adoptManifest(m);
					log.info("Plan A3 RESUME from {} — highest completed degree = {}",
							exMasConfig.getCheckpointDir(), m.highestDegree);
				} else {
					log.info("Plan A3 checkpointing ENABLED (fresh) -> {}", exMasConfig.getCheckpointDir());
				}

				// Journal setup. Enable capture BEFORE pair generation routes (singles, generated
				// earlier above, route point-to-point and are regenerated identically on resume, so
				// they need no journal). On resume, pair-gen is skipped, so the journal is the ONLY
				// source of its SSSP cache values — bulk-load them before any routing; then reopen
				// the same journal to append entries from any degrees this resumed run recomputes.
				network.enableJournaling();
				java.nio.file.Path journalPath = java.nio.file.Path.of(
						exMasConfig.getCheckpointDir()).resolve("cache.journal");
				try {
					if (resuming) {
						if (!java.nio.file.Files.exists(journalPath)) {
							throw new IllegalStateException("Resume requires the connection-cache journal "
									+ journalPath + " but it is missing — the checkpoint is incomplete; "
									+ "delete the dir and rerun.");
						}
						ConnectionCacheJournal.Contents journalContents =
								ConnectionCacheJournal.read(journalPath);
						// Plan A3 Task 6: refuse a journal truncated/damaged below the completed-degree
						// high-water mark (a torn tail beyond the last barrier is fine; missing a whole
						// completed barrier is not).
						checkpointMgr.requireJournalCoversCompletedDegrees(journalContents.committedBarrierCount());
						network.bulkLoadFromJournal(journalContents);
					}
					cacheJournal = ConnectionCacheJournal.Writer.openForAppend(journalPath);
				} catch (java.io.IOException e) {
					throw new java.io.UncheckedIOException(
							"Cannot open/read connection-cache journal " + journalPath
							+ " — corrupt or unreadable checkpoint; delete the dir and rerun.", e);
				}
			} else {
				log.warn("checkpointDir is set but checkpointing is only supported on the streaming "
						+ "pair-stub D2D path (stub mode + stop-based OFF + maxDegree>2). "
						+ "Running WITHOUT checkpoints.");
			}
		}

		// Plan A3 Task 5: release the connection-cache journal's FileChannel on ANY abnormal exit
		// from generation, not only via the normal close before the streaming return below. In the
		// production process-abort model the OS reclaims the FD and the per-barrier fsync keeps the
		// on-disk journal durable regardless; this guard additionally stops a leaked channel from
		// blocking journal reopen inside a long-lived (or Windows-locked) JVM. The success path
		// still closes the writer at the streaming return, so this catch fires only when an
		// exception propagates first. The body keeps its original indentation under the try.
		try {
        // Phase 2: Generate pair rides with budget validation
		log.info("");
		log.info("PHASE 2: Pair Ride Generation");
		log.info("======================================================================");
		PairGenerator pairGen = new PairGenerator(network, budgetValidator, horizon, algorithmProcessCount,
				exMasConfig.isEnableBudgetAwareConstraints());

		// On the fat path these hold the pruned degree-2 survivors as fat rides; on the
		// pairStubPath the survivors live in `pairSurvivorStubs` instead (added to stubLayers).
		List<Ride> currentDegreeRides = null;
		org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns pairSurvivorStubs = null;

		if (pairStubPath) {
			// Phase 2 (stub): the full pair universe as a degree-2 StubColumns. On resume it is
			// LOADED from the base checkpoint (review addendum F6) instead of re-generated —
			// generatePairStubs is the routing-heavy phase the checkpoint exists to skip. The
			// loaded universe carries the positionsFlat copy-identity column (F1), so the graph
			// build + survivor prune below reproduce the original run bit-for-bit.
			org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns allPairStubs;
			if (resuming) {
				allPairStubs = checkpointMgr.readBase();
				log.info("");
				log.info("PHASE 2 (resume): loaded pre-prune pair universe ({} rows) from checkpoint",
						allPairStubs.size());
			} else {
				allPairStubs = pairGen.generatePairStubs(reqArray);
			}

			// Phase 3 (stub): build the shareability graph from ALL pair stubs (pre-dedup).
			// Deterministic from allPairStubs on both fresh and resume — no routing.
			log.info("");
			log.info("PHASE 3: Building Shareability Graph");
			log.info("======================================================================");
			long graphStartTime = System.currentTimeMillis();
			graph = buildGraph(allPairStubs);
			long graphElapsed = System.currentTimeMillis() - graphStartTime;
			log.info("Graph built: {} edges, {} nodes in {}s",
					graph.getEdgeCount(), graph.getNodeCount(), String.format("%.1f", graphElapsed / 1000.0));

			// Plan A3: persist the PRE-PRUNE pair universe before it is dropped (review addendum
			// F6) — on resume the shareability graph rebuilds via buildGraph(allPairStubs) and the
			// degree-2 survivors via maybePrunePairStubsAfterGraph, both deterministic, so no
			// separate graph/edge-list serializer is needed. Written before the prune so the
			// persisted universe matches what the graph was built from. Skipped on resume (the
			// base checkpoint is already on disk and was just read back from it).
			if (checkpointMgr != null && !resuming) {
				// Journal the pair-gen SSSP entries durably BEFORE the manifest records base done,
				// so a crash after the manifest still finds those entries on resume.
				drainJournalBarrier(cacheJournal);
				checkpointMgr.writeBase(allPairStubs);
				// Plan A3 Task 7: test-only crash injection at the pre-loop base barrier
				// (degrees 1+2 committed). No-op unless -Dbamas.checkpoint.killAfterDegree=2.
				CheckpointKillSwitch.maybeHaltAfterDegree(2);
			}

			// Best-per-set dedup + distance gate + top-fraction over the stub universe. The
			// full pair universe is dropped here (only the survivor layer is retained), so
			// the full pair universe never coexists with the extension cascade.
			pairSurvivorStubs = maybePrunePairStubsAfterGraph(allPairStubs, reqArray);
			// Cache-memory tiers (Task 7 Step 5): pair generation is complete and its feasible
			// chain segments were promoted at acceptance (PairGenerator.promotePairChainSegments).
			// Compact the retained overlay into a frozen snapshot at this single-threaded barrier,
			// before the extension cascade begins.
			network.compactRetained();
		} else {
			List<Ride> pairRides = pairGen.generatePairs(reqArray);

			if (maxDegree <= 2) {
				allRides.addAll(pairRides);
				return completeEarly(algorithmStartTime, "maxDegree <= 2");
			}

			// Phase 3: Build shareability graph from ALL pairs (before pruning)
			log.info("");
			log.info("PHASE 3: Building Shareability Graph");
			log.info("======================================================================");
			long graphStartTime = System.currentTimeMillis();
			graph = buildGraph(pairRides);
			long graphElapsed = System.currentTimeMillis() - graphStartTime;
			log.info("Graph built: {} edges, {} nodes in {}s",
					graph.getEdgeCount(), graph.getNodeCount(), String.format("%.1f", graphElapsed / 1000.0));

			// Prune pair rides AFTER graph construction. Graph stays complete (built from
			// all pairs). Pruned pairs are removed from both the output AND the extension
			// base set — the MIP only needs one ride per request set, and the extension
			// re-enumerates orderings independently of the base ride's FIFO/LIFO variant.
			currentDegreeRides = maybePrunePairRidesAfterGraph(pairRides);
			allRides.addAll(currentDegreeRides);
		}

        // Phase 4: Iteratively extend rides with budget validation
		// The ordering-based BamasRideExtender enumerates valid orderings directly from
		// pairwise constraints in the shareability graph — no rideMap needed.
		// It returns top-1 per set, so MaxPerSet pruning is redundant.
		// Percentile pruning across sets is still applied to bound memory.
		log.info("");
		log.info("PHASE 4: Iterative Ride Extension");
		log.info("======================================================================");
		int nextRideIndex = allRides.size();
		int extensionStartIdx = nextRideIndex; // first index of extension rides in allRides
		// Stub mode (Task 11): degree-3+ extension rides are held as per-degree StubColumns,
		// NOT appended to allRides as fat Ride objects. We accumulate the per-degree layers
		// here and batch-materialize them once at the end, concatenating with the still-fat
		// singles + pairs already in allRides. The fat `extended` list returned by extendRides
		// is still produced (Task 10 is additive) but used only for control flow + degree-graph;
		// it is not retained past each iteration. Task 12 makes export streaming.
		List<org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns> stubLayers =
				stubMode ? new ArrayList<>() : null;
		// Task 13: on the pairStubPath the degree-2 survivor layer is the FIRST stub layer, so
		// the StubRideStore concatenation is fat-singles → pair-layer → degree-3 → … (contract
		// #3). extensionLayerStart marks where degree-3+ layers begin, so the post-loop
		// COVERAGE_TOPK pass skips the pair layer (master applies COVERAGE_TOPK only to
		// extension rides, never to pairs — pairs have their own distance gate above).
		int extensionLayerStart = 0;
		if (pairStubPath) {
			stubLayers.add(pairSurvivorStubs);
			extensionLayerStart = 1;
		}
		// Previous degree's captured (and possibly RATIO-pruned) stub layer, fed as the
		// next degree's parents. On the pairStubPath this is the degree-2 survivor layer
		// (degree 2→3 extends stubs); on the fat path it is null (degree 2→3 uses fat pairs).
		org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns prevStubLayer = pairStubPath ? pairSurvivorStubs : null;
		DegreeGraph prevDegreeGraph = null;

		// Plan A3 resume: replay the COMPLETED extension degrees from the checkpoint instead of
		// re-extending them. Load degree layers 3..H into stubLayers, restore prevStubLayer (the
		// parents for degree H+1), restore nextRideIndex from the manifest GENERATED counts (the
		// reserved index space, not the surviving row count), and seed prevDegreeGraph — the
		// degree-H DegreeGraph the next iteration's extender consumes — by rebuilding it from the
		// degree-H layer's request sets (DegreeGraph.buildFromRequestSets is order-independent of
		// its input, so this reproduces the original buildDegreeGraph(H) output exactly). The loop
		// then continues at degree=H. Reductions: H==2 (base only) leaves the fresh degree-2 start
		// state unchanged (prevDegreeGraph stays null, exactly like a fresh first iteration);
		// H==maxDegree skips the loop entirely (a fully-complete checkpoint → straight to export).
		int loopStart = 2;
		if (resuming) {
			int highest = resumeManifest.highestDegree;
			long generatedSum = 0;
			for (int d = 3; d <= highest; d++) {
				org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns layer =
						checkpointMgr.readDegree(d);
				stubLayers.add(layer);
				prevStubLayer = layer;
				generatedSum += resumeManifest.generatedFor(d);
			}
			if (highest >= 3) {
				List<int[]> sets = new ArrayList<>(prevStubLayer.size());
				for (int row = 0; row < prevStubLayer.size(); row++) {
					sets.add(prevStubLayer.requestIndices(row));
				}
				prevDegreeGraph = DegreeGraph.buildFromRequestSets(sets, highest);
			}
			nextRideIndex = allRides.size() + (int) generatedSum;
			loopStart = highest;
			log.info("Plan A3 resume: loaded degrees 3..{} ({} stub layers incl. pair layer), "
					+ "nextRideIndex restored to {}, extension loop continues at degree {}",
					highest, stubLayers.size(), nextRideIndex, loopStart);
		}

		for (int degree = loopStart; degree < maxDegree; degree++) {
			BamasRideExtender extender = new BamasRideExtender(network, graph, budgetValidator,
													 requests, exMasConfig, prevDegreeGraph);

			// Seam (a): STUB parents whenever a stub layer is available — the previous
			// iteration's captured layer (degree 3→4+), or, on the Task-13 pairStubPath,
			// the degree-2 survivor layer at the first iteration (degree 2→3). prevStubLayer
			// is null only on the fat pair path's first iteration, where the fat overload runs.
			List<Ride> extended;
			if (stubMode && prevStubLayer != null) {
				org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns extensionParents = prevStubLayer;
				if (exMasConfig.getExtensionParentsTopK() > 0
						&& (degree + 1) >= exMasConfig.getExtensionParentsTopKMinDegree()
						&& prevStubLayer.degree() >= 3) {
					extensionParents = filterExtensionParents(prevStubLayer, requestById);
				}
				extended = extender.extendRides(extensionParents, reqArray, nextRideIndex);
			} else {
				extended = extender.extendRides(currentDegreeRides, nextRideIndex);
			}
			long graphBuildStart = System.currentTimeMillis();
			prevDegreeGraph = extender.buildDegreeGraph(degree + 1);
			long graphBuildMs = System.currentTimeMillis() - graphBuildStart;
			log.info("  Degree-{} graph: {} feasible sets, built in {}ms",
					degree + 1, extender.getFeasibleSetCount(), graphBuildMs);

			if (extended.isEmpty()) {
				log.info("No extensions possible at degree {}. Stopping.", (degree + 1));
				break;
			}

			int generatedCount = extended.size();
			if (stubMode) {
				// Capture this degree's compact layer (sorted lex, same total order as the
				// fat `extended` list). RATIO_THRESHOLD inter-degree pruning, when active,
				// runs over the stub layer (seam b); COVERAGE_TOPK runs once post-loop.
				org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns layer =
						extender.getLastDegreeStubs();
				if (exMasConfig.getPruningMode() == org.matsim.contrib.demand_extraction.config.ExMasConfigGroup.PruningMode.RATIO_THRESHOLD) {
					PostExtensionPruner pruner = buildPruner(exMasConfig);
					if (pruner != null) {
						layer = pruner.pruneStubLayer(layer, requestById);
					}
				}
				stubLayers.add(layer);
				prevStubLayer = layer; // next degree extends this (pruned) layer

				// Cache-memory tiers (Task 7 Step 3): promote each surviving row's leg segments into
				// the never-evicted retained tier so a later watermark eviction cannot drop them
				// before export re-reads them. forEachLegSegment walks the SAME cumulative-time leg
				// sequence the materializer routes at export, so promotion adds no new cache key (the
				// legs are already cached from enumeration) and promotion-keys == export-keys by
				// construction. Then compact the retained overlay into a frozen snapshot (single-
				// threaded barrier). degree+1 >= 3 here, so every row is a valid forEachLegSegment input.
				for (int row = 0; row < layer.size(); row++) {
					org.matsim.contrib.demand_extraction.algorithm.bamas.extension.RideMaterializer
							.forEachLegSegment(network, layer, row, requestById,
									network::promoteSegment);
				}
				network.compactRetained();

				// Plan A3 barrier: the degree-(degree+1) layer is now finalized (post in-loop
				// RATIO prune; post-loop COVERAGE_TOPK re-applies uniformly on resume too).
				// generatedCount (pre-prune) restores nextRideIndex exactly. Manifest written last.
				if (checkpointMgr != null) {
					// Journal this degree's new SSSP entries durably before the manifest records
					// the degree complete (same crash-consistency ordering as the base barrier).
					drainJournalBarrier(cacheJournal);
					checkpointMgr.writeDegree(degree + 1, layer, generatedCount);
					// Plan A3 Task 7: test-only crash injection at the in-loop barrier, right
					// after the degree-(degree+1) manifest is durable. No-op unless
					// -Dbamas.checkpoint.killAfterDegree=(degree+1).
					CheckpointKillSwitch.maybeHaltAfterDegree(degree + 1);
				}
			} else {
				// RATIO_THRESHOLD inter-degree pruning only (legacy keep-fraction gate).
				// COVERAGE_TOPK is applied once after the full cascade — see post-loop block below.
				if (exMasConfig.getPruningMode() == org.matsim.contrib.demand_extraction.config.ExMasConfigGroup.PruningMode.RATIO_THRESHOLD) {
					PostExtensionPruner pruner = buildPruner(exMasConfig);
					if (pruner != null) {
						extended = pruner.prune(extended);
					}
				}
				allRides.addAll(extended);
				currentDegreeRides = extended;
			}

			nextRideIndex += generatedCount; // index space reserved for all generated rides
		}

		// Post-extension COVERAGE_TOPK pruning: applied once to all extension rides after the
		// cascade terminates, so the full cascade runs unimpeded and K compression is a final step.
		if (exMasConfig.getPruningMode() == org.matsim.contrib.demand_extraction.config.ExMasConfigGroup.PruningMode.COVERAGE_TOPK) {
			PostExtensionPruner pruner = buildPruner(exMasConfig);
			if (pruner != null) {
				if (stubMode) {
					// Prune each per-degree stub layer in place (each layer is one degree,
					// so per-degree COVERAGE_TOPK == per-layer pruning). On the pairStubPath
					// the degree-2 pair layer is stubLayers[0] and is skipped: master never
					// applies COVERAGE_TOPK to pairs (they have a separate distance gate).
					for (int i = extensionLayerStart; i < stubLayers.size(); i++) {
						stubLayers.set(i, pruner.pruneStubLayer(stubLayers.get(i), requestById));
					}
				} else {
					List<Ride> extensionRides = new java.util.ArrayList<>(allRides.subList(extensionStartIdx, allRides.size()));
					extensionRides = pruner.prune(extensionRides);
					allRides = new java.util.ArrayList<>(allRides.subList(0, extensionStartIdx));
					allRides.addAll(extensionRides);
				}
			}
		}

		// Fat-stub path only (stub mode WITH stop-based): batch-materialize the degree-3+
		// layers into fat Ride objects and concatenate with the still-fat singles + pairs in
		// allRides, so Phase 5 stop-based generation can consume the full D2D set. The final
		// sort + reindex (below) then runs UNCHANGED, exactly as in the fat path. The
		// streaming D2D path (stubMode && !enableStopBased) skips this entirely — it folds
		// materialize + sort + reindex into StubRideStore and returns below, before Phase 5.
		// NON-DEFERRED: materialize replays buildRideFromOrdering + validateAndPopulateBudgets,
		// so remainingBudgets is populated inline (see RideMaterializer).
		if (stubMode && !streamingD2D) {
			org.matsim.contrib.demand_extraction.algorithm.bamas.extension.RideMaterializer materializer =
					new org.matsim.contrib.demand_extraction.algorithm.bamas.extension.RideMaterializer(
							network, budgetValidator);
			int materialized = 0;
			for (org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns layer : stubLayers) {
				for (int row = 0; row < layer.size(); row++) {
					allRides.add(materializer.materialize(layer, row, requestById));
					materialized++;
				}
			}
			log.info("Stub mode (fat path, stop-based enabled): materialized {} degree-3+ extension rides from {} per-degree layers",
					materialized, stubLayers.size());
		}

		// Seam (c) — deferred budgets relocated to the export pass.
		// stub mode (either branch): remainingBudgets is populated inline by
		// RideMaterializer.validateAndPopulateBudgets — the streaming path does it lazily
		// during forEachMaterialized, the fat-stub path did it in the batch-materialize loop
		// above. Either way the stub layers themselves carry NO budgets, so populateBudgetsBatch
		// must NOT also run in stub mode (it would double-populate / run on stub-less rows).
		// Only the non-stub deferred path needs the batch. NOTE: the parity gate scenario is
		// NON-DEFERRED (deferExtensionBudgetValidation=false), so it does NOT exercise this
		// branch — the correctness of the stub-mode skip is reasoned, not gate-verified.
		// Safe on scenarios where budget never rejects (e.g. Bavaria); see BudgetValidator docs.
		if (!stubMode && exMasConfig.isDeferExtensionBudgetValidation()) {
			log.info("");
			log.info("Populating deferred budgets for {} rides...", allRides.size());
			org.matsim.contrib.demand_extraction.algorithm.profiling.MemoryProfiler
					.snapshot("before-deferred-budget-population");
			long budgetStart = System.currentTimeMillis();
			allRides = budgetValidator.populateBudgetsBatch(allRides);
			log.info("  Deferred budget population took {}s",
					String.format("%.1f", (System.currentTimeMillis() - budgetStart) / 1000.0));
			org.matsim.contrib.demand_extraction.algorithm.profiling.MemoryProfiler
					.snapshotAtEndOfDegree(-1, allRides.size());
		}

		// True D2D total: fat singles+pairs already in allRides, plus stub-layer rows (which
		// in the streaming path have NOT been added to allRides). Log-only — for traceability.
		int stubLayerRows = 0;
		if (streamingD2D) {
			for (org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns layer : stubLayers) {
				stubLayerRows += layer.size();
			}
		}
		int totalD2D = allRides.size() + stubLayerRows;
		long totalElapsed = System.currentTimeMillis() - algorithmStartTime;
		double totalSeconds = totalElapsed / 1000.0;
		int[] rideCounts = summarizeRideCounts(allRides);
		// On the pairStubPath, pairs live in stubLayers[0] (degree 2), not allRides — split
		// the stub-row count into the pair layer and the degree-3+ layers for an accurate log.
		int pairStubRows = pairStubPath && !stubLayers.isEmpty() ? stubLayers.get(0).size() : 0;
		int higherStubRows = stubLayerRows - pairStubRows;
		log.info("");
		log.info("======================================================================");
		log.info("ExMAS Algorithm Complete (Door-to-Door)");
		log.info("  Total D2D rides generated: {}", totalD2D);
		log.info("  Single: {}, Pairs: {}, Higher: {}",
				rideCounts[0],
				streamingD2D ? (rideCounts[1] + pairStubRows) : rideCounts[1],
				streamingD2D ? higherStubRows : rideCounts[2]);
		log.info("  Total execution time: {}s", String.format("%.1f", totalSeconds));
		log.info("======================================================================");
		org.matsim.contrib.demand_extraction.algorithm.profiling.MemoryProfiler
				.snapshotAtEndOfDegree(-1, totalD2D);

		// Log network routing statistics
		log.info("");
		network.logRoutingStatistics();

		// Task 12 — streaming export: fold materialize + global stable sort + sequential
		// reindex into a lazy StubRideStore. Stub rows are materialized one at a time during
		// forEachMaterialized; only lightweight per-row sort keys are held. This path never
		// reaches Phase 5/6 (stop-based disabled) nor the fat sort/reindex below, so the 2×
		// peak-memory hazard of "full fat list + rebuilt clone" is gone.
		if (streamingD2D) {
			// Task 13: pass pairGen so RideMaterializer can rebuild degree-2 pair stubs via the
			// generator's fixed-time routing (buildRideFromOrdering's cumulative clock would miss
			// unwarmed time bins for pairs). On the pairStubPath stubLayers[0] is the pair layer.
			// Task 13: wire the SAME reqArray instance whose positions generatePairStubs recorded,
			// so the degree-2 materialize resolves each pair request by reqArray[position] (the
			// exact generation copy) instead of the index-collision-prone requestById.
			org.matsim.contrib.demand_extraction.algorithm.bamas.extension.RideMaterializer materializer =
					new org.matsim.contrib.demand_extraction.algorithm.bamas.extension.RideMaterializer(
							network, budgetValidator, pairGen, reqArray);
			log.info("Stub mode (streaming): exporting {} D2D rides ({} fat singles+pairs + {} stub rows) "
					+ "via StubRideStore — no batch-materialize, no fat sort/reindex",
					totalD2D, allRides.size(), stubLayerRows);
			// Plan A3 Task 5: all checkpoint barriers are drained. The lazy export below routes
			// only the never-cached backstop class (point-to-point, reproduced identically on
			// resume), so stop capturing — otherwise export-time inserts would grow the pending
			// queue unbounded with no barrier to drain them — and close the writer.
			if (cacheJournal != null) {
				network.disableJournaling();
				try {
					cacheJournal.close();
				} catch (java.io.IOException e) {
					throw new java.io.UncheckedIOException("Cannot close connection-cache journal", e);
				}
			}
			return new StubRideStore(allRides, stubLayers, materializer, requestById);
		}
		} catch (Throwable t) {
			// Best-effort release on the failure path; the propagating exception is the real signal.
			if (cacheJournal != null) {
				try {
					network.disableJournaling();
					cacheJournal.close();
				} catch (java.io.IOException ignored) {
					// nothing actionable — the original throwable is rethrown below
				}
			}
			throw t;
		}

		// Phase 5: Stop-Based Ride Generation (HyperPool Stage 1)
		// Only runs if enableStopBased = true
		List<Ride> stopBasedRides = new ArrayList<>();
		if (exMasConfig.isEnableStopBased()) {
			log.info("");
			log.info("PHASE 5: Stop-Based Ride Generation (HyperPool Stage 1)");
			log.info("======================================================================");

			stopBasedRides = generateStopBasedRides(allRides);
			if (!stopBasedRides.isEmpty()) {
				allRides.addAll(stopBasedRides);
				log.info("Added {} stop-to-stop ride variants", stopBasedRides.size());
			}
		}

		// Phase 6: Hyper-Pooling (HyperPool Stage 2)
		// Only runs if enableHyperPooling = true (and enableStopBased = true)
		if (exMasConfig.isEnableHyperPooling()) {
			if (!exMasConfig.isEnableStopBased()) {
				log.warn("Hyper-pooling requires stop-based pooling to be enabled. Skipping Phase 6.");
			} else {
				log.info("");
				log.info("PHASE 6: Hyper-Pooling (HyperPool Stage 2)");
				log.info("======================================================================");

				hyperPooledRides = generateHyperPooledRides(stopBasedRides);
				log.info("Generated {} hyper-pooled rides", hyperPooledRides.size());
			}
		}

		// Sort rides for deterministic output (parallel processing can create non-deterministic order)
		// Sort by: variant (D2D first), then degree (ascending), then by first request index (ascending)
		allRides.sort(java.util.Comparator
				.comparing(Ride::getVariant)
				.thenComparingInt(Ride::getDegree)
				.thenComparingInt(r -> {
					int[] indices = r.getRequestIndices();
					return indices.length > 0 ? indices[0] : Integer.MAX_VALUE;
				}));

		// Re-assign indices sequentially after sorting
		for (int i = 0; i < allRides.size(); i++) {
			Ride oldRide = allRides.get(i);
			Ride newRide = oldRide.toBuilder()
					.index(i)  // New sequential index
					.build();
			allRides.set(i, newRide);
		}

		// Final summary
		if (exMasConfig.isEnableStopBased()) {
			long d2dCount = allRides.stream().filter(r -> r.getVariant() == RideVariant.DOOR_TO_DOOR).count();
			long s2sCount = allRides.stream().filter(r -> r.getVariant() == RideVariant.STOP_TO_STOP).count();
			log.info("");
			log.info("======================================================================");
			if (exMasConfig.isEnableHyperPooling()) {
				log.info("Final Summary (with Stop-Based Pooling and Hyper-Pooling)");
				log.info("  Door-to-Door rides: {}", d2dCount);
				log.info("  Stop-to-Stop rides: {}", s2sCount);
				log.info("  Hyper-Pooled rides: {}", hyperPooledRides.size());
				log.info("  Total Ride objects: {}", allRides.size());
				log.info("  Total HyperPooledRide objects: {}", hyperPooledRides.size());
			} else {
				log.info("Final Summary (with Stop-Based Pooling)");
				log.info("  Door-to-Door rides: {}", d2dCount);
				log.info("  Stop-to-Stop rides: {}", s2sCount);
				log.info("  Total rides: {}", allRides.size());
			}
			log.info("======================================================================");
		}

		// Fat path (non-stub, OR stub mode with stop-based enabled): allRides is the
		// sorted + reindexed full list. Wrap as-is, preserving byte-identical output.
		return new MaterializedRideStore(allRides);
	}

	/**
	 * Log completion summary and return rides for early exit.
	 *
	 * <p>The early-exit paths ({@code maxDegree < 2} and {@code maxDegree <= 2}) never
	 * produce stubs (degree &ge; 3) and never sort/reindex on master, so the fat
	 * {@code allRides} list is wrapped as-is in a {@link MaterializedRideStore}.
	 */
	private RideStore completeEarly(long algorithmStartTime, String reason) {
		long totalElapsed = System.currentTimeMillis() - algorithmStartTime;
		double totalSeconds = totalElapsed / 1000.0;
		log.info("");
		log.info("======================================================================");
		log.info("ExMAS Algorithm Complete ({})", reason);
		log.info("  Total rides: {}", allRides.size());
		log.info("  Total time: {}s", String.format("%.1f", totalSeconds));
		log.info("======================================================================");
		log.info("");
		network.logRoutingStatistics();
		return new MaterializedRideStore(allRides);
	}

	/**
	 * Plan A3 Task 5 — append the cache entries inserted since the last barrier to the connection
	 * cache journal and fsync, at a checkpoint barrier. No-op when journaling is off
	 * ({@code journal == null}). Wraps the checked IO as unchecked so the barrier call sites stay
	 * uncluttered; an IO failure here aborts the run (the checkpoint contract cannot be honored).
	 */
	private void drainJournalBarrier(ConnectionCacheJournal.Writer journal) {
		if (journal == null) {
			return;
		}
		try {
			network.drainPendingToJournal(journal);
		} catch (java.io.IOException e) {
			throw new java.io.UncheckedIOException("Cannot append to connection-cache journal", e);
		}
	}

	private ShareabilityGraph buildGraph(List<Ride> pairRides) {
		// Use at least capacity 1 to avoid IllegalArgumentException when no pair rides
		// exist
		int initialCapacity = Math.max(1, pairRides.size() * 2);
		ShareabilityGraph.Builder builder = ShareabilityGraph.builder(initialCapacity);

		for (Ride ride : pairRides) {
			if (ride.getDegree() != 2) continue;

			int reqI = ride.getRequestIndices()[0];
			int reqJ = ride.getRequestIndices()[1];
			byte kind = ride.getKind() == RideKind.FIFO ? ShareabilityGraph.KIND_FIFO : ShareabilityGraph.KIND_LIFO;

			builder.addEdge(reqI, reqJ, ride.getIndex(), kind);
		}

		return builder.build();
	}

	/**
	 * Stub-path (Task 13) shareability-graph build over a degree-2 {@link StubColumns}.
	 *
	 * <p>Byte-identical to {@link #buildGraph(List)}: master's edge tuple is
	 * {@code (getRequestIndices()[0], getRequestIndices()[1], getIndex(), kind)} where
	 * {@code requests[] == originsOrdered} — i.e. the endpoints are in <b>pickup order</b>,
	 * NOT sorted-set order. The edge is directional ({@code getEdgesWithKinds(source,target)}),
	 * so we must reproduce that exact tuple. We therefore read the endpoints from the
	 * unpacked pickup ordering (origin order) mapped to global indices, not from the sorted
	 * slice. The kind comes from {@code flags} (FIFO/LIFO, set by {@link RideStub#fromRide}).
	 *
	 * <p>The per-edge ride index is consumed only by the frozen ExMAS reference path
	 * (never the BAMAS path, which reads only edge kinds via {@code getEdgesWithKinds}); the
	 * row number is supplied as a stable placeholder so the field is well-defined.
	 */
	private ShareabilityGraph buildGraph(org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns pairStubs) {
		int initialCapacity = Math.max(1, pairStubs.size() * 2);
		ShareabilityGraph.Builder builder = ShareabilityGraph.builder(initialCapacity);

		for (int row = 0; row < pairStubs.size(); row++) {
			int[] originLocal = org.matsim.contrib.demand_extraction.algorithm.bamas.stub.OrderingCodec
					.unpack(pairStubs.originOrder(row), 2);
			int[] sortedSet = pairStubs.requestIndices(row);
			int reqI = sortedSet[originLocal[0]]; // pickup-order first  == getRequestIndices()[0]
			int reqJ = sortedSet[originLocal[1]]; // pickup-order second == getRequestIndices()[1]
			RideKind k = org.matsim.contrib.demand_extraction.algorithm.bamas.stub.RideStub
					.flagsToKind(pairStubs.flags(row));
			byte kind = k == RideKind.FIFO ? ShareabilityGraph.KIND_FIFO : ShareabilityGraph.KIND_LIFO;
			builder.addEdge(reqI, reqJ, row, kind);
		}

		return builder.build();
	}

	/**
	 * Generate stop-based ride variants from door-to-door rides.
	 * Only converts rides with degree >= 2.
	 */
	private List<Ride> generateStopBasedRides(List<Ride> doorToDoorRides) {
		// Filter to D2D rides only
		List<Ride> d2dRides = doorToDoorRides.stream()
				.filter(r -> r.getVariant() == RideVariant.DOOR_TO_DOOR)
				.collect(Collectors.toList());

		if (d2dRides.isEmpty()) {
			return new ArrayList<>();
		}

		// Create stop finder based on configuration
		Network matsimNetwork = network.getNetwork();
		StopFinderFactory factory = new StopFinderFactory(matsimNetwork, facilities, exMasConfig);
		StopFinder stopFinder = factory.create();
		WalkingDistanceCalculator walkCalculator = factory.createWalkingDistanceCalculator();

		// Create generator
		int algorithmProcessCount = exMasConfig.getAlgorithmProcessCount();
		// Wrap BudgetToConstraintsCalculator as WalkBudgetProvider (null-safe: null passes through)
		org.matsim.contrib.demand_extraction.algorithm.generation.WalkBudgetProvider walkBudgetProvider =
				budgetToConstraints == null ? null :
				(budget, req, tt, dist, delay) ->
						budgetToConstraints.budgetToMaxWalkDistance(budget, null, req, tt, dist, delay);
		StopBasedRideGenerator generator = new StopBasedRideGenerator(
				network, stopFinder, walkCalculator, budgetValidator, exMasConfig, algorithmProcessCount,
				walkBudgetProvider);

		// Generate stop-based rides (indices will be assigned after the main algorithm)
		int startIndex = doorToDoorRides.size();
		return generator.generateStopBasedRides(d2dRides, startIndex);
	}

	/**
	 * Generate hyper-pooled rides from stop-to-stop rides using HyperPool Stage 2.
	 *
	 * <p>Bundles multiple stop-to-stop rides together where passengers walk to/from
	 * designated stop locations. Creates higher-occupancy rides by allowing nearby
	 * stops to be served by the same vehicle.
	 *
	 * @param stopBasedRides the stop-to-stop rides from Phase 5 (Stage 1)
	 * @return list of hyper-pooled rides
	 */
	private List<HyperPooledRide> generateHyperPooledRides(List<Ride> stopBasedRides) {
		if (stopBasedRides == null || stopBasedRides.isEmpty()) {
			log.info("No stop-to-stop rides available for hyper-pooling");
			return Collections.emptyList();
		}

		// Filter to S2S rides only
		List<Ride> s2sRides = stopBasedRides.stream()
				.filter(r -> r.getVariant() == RideVariant.STOP_TO_STOP)
				.collect(Collectors.toList());

		if (s2sRides.isEmpty()) {
			log.info("No STOP_TO_STOP rides found for hyper-pooling");
			return Collections.emptyList();
		}

		log.info("Processing {} stop-to-stop rides for hyper-pooling", s2sRides.size());

		// Create StopCompatibilityChecker adapter that implements HyperPoolGenerator.StopCompatibilityChecker
		StopCompatibilityChecker externalChecker = new StopCompatibilityChecker(exMasConfig);
		HyperPoolGenerator.StopCompatibilityChecker compatibilityChecker =
				(r1, r2) -> externalChecker.areCompatible(r1, r2);

		// Create StopRelocator adapter (conditionally) based on config
		HyperPoolGenerator.StopRelocator stopRelocator = null;
		if (exMasConfig.getHyperPoolEnableStopRelocation()) {
			log.info("HyperPool: Stop relocation enabled (optimization, not in original ExMAS/HyperPool)");
			stopRelocator = new HyperPoolGenerator.StopRelocator() {
				@Override
				public boolean areStopsNearby(
						org.matsim.contrib.demand_extraction.algorithm.domain.StopLocation stop1,
						org.matsim.contrib.demand_extraction.algorithm.domain.StopLocation stop2,
						double proximityMeters) {
					// Use Euclidean distance between stop coordinates
					double distance = org.matsim.core.utils.geometry.CoordUtils.calcEuclideanDistance(
							stop1.getCoord(), stop2.getCoord());
					return distance <= proximityMeters;
				}

				@Override
				public org.matsim.contrib.demand_extraction.algorithm.domain.StopLocation findMergedStop(
						org.matsim.contrib.demand_extraction.algorithm.domain.StopLocation stop,
						List<org.matsim.contrib.demand_extraction.algorithm.domain.StopLocation> existingStops,
						double proximityMeters,
						double[] maxRelocDistPerPax) {
					for (org.matsim.contrib.demand_extraction.algorithm.domain.StopLocation existing : existingStops) {
						if (areStopsNearby(stop, existing, proximityMeters)) {
							// Budget-aware guard: reject merge if it would exceed any passenger's
							// remaining walk budget (Signature A — caller pre-computes the budget).
							if (maxRelocDistPerPax != null) {
								double relocDist = calculateRelocationDistance(stop, existing);
								double minBudget = Double.MAX_VALUE;
								for (double b : maxRelocDistPerPax) {
									if (b < minBudget) minBudget = b;
								}
								if (relocDist > minBudget) {
									continue; // skip this candidate; try next existing stop
								}
							}
							return existing;
						}
					}
					return stop;
				}

				@Override
				public double calculateRelocationDistance(
						org.matsim.contrib.demand_extraction.algorithm.domain.StopLocation originalStop,
						org.matsim.contrib.demand_extraction.algorithm.domain.StopLocation mergedStop) {
					return org.matsim.core.utils.geometry.CoordUtils.calcEuclideanDistance(
							originalStop.getCoord(), mergedStop.getCoord());
				}
			};
		} else {
			log.info("HyperPool: Stop relocation disabled (matches original ExMAS/HyperPool)");
		}

		// Build WalkBudgetProvider (same lambda as Stage 1; null-safe)
		org.matsim.contrib.demand_extraction.algorithm.generation.WalkBudgetProvider hyperPoolWalkBudgetProvider =
				budgetToConstraints == null ? null :
				(budget, req, tt, dist, delay) ->
						budgetToConstraints.budgetToMaxWalkDistance(budget, null, req, tt, dist, delay);

		// Create HyperPoolGenerator
		HyperPoolGenerator generator = new HyperPoolGenerator(
				network, stopRelocator, compatibilityChecker, exMasConfig, budgetValidator,
				hyperPoolWalkBudgetProvider);

		// Generate hyper-pooled rides
		// Start index is based on total rides (will be used for HyperPooledRide indexing)
		int startIndex = allRides.size();
		List<HyperPooledRide> result = generator.generate(s2sRides, network, startIndex);

		// Log statistics
		generator.logStatistics();

		return result;
	}

	/**
	 * Prune pair rides after graph construction. Three sequential passes:
	 *
	 * <ol>
	 *   <li><b>Best-per-set dedup</b> (always on): For each request-set (same two passengers),
	 *       keep only the variant with shortest rideDistance. Collapses FIFO/LIFO duplicates.
	 *       Lossless for the MIP (picks one per set) and effectively lossless for extension
	 *       (re-enumerates orderings anyway).</li>
	 *   <li><b>Distance-savings gate</b>: Drop pairs below the degree-2 savings threshold
	 *       (only if pruningDistanceSavingsMinDegree &le; 2). Existing behavior.</li>
	 *   <li><b>Top-fraction filter</b>: Keep only the top X% of remaining pairs by distance
	 *       savings (controlled by pairKeepTopFraction, default 1.0 = disabled).</li>
	 * </ol>
	 *
	 * All passes run AFTER the shareability graph is built, preserving graph completeness.
	 */
	private List<Ride> maybePrunePairRidesAfterGraph(List<Ride> pairRides) {
		if (pairRides.isEmpty()) {
			return pairRides;
		}
		int initial = pairRides.size();

		// --- Pass 1: Best-per-set dedup (always on) ---
		java.util.Map<String, Ride> bestPerSet = new java.util.HashMap<>();
		for (Ride r : pairRides) {
			int[] indices = r.getRequestIndices().clone();
			Arrays.sort(indices);
			String key = Arrays.toString(indices);
			Ride existing = bestPerSet.get(key);
			if (existing == null || r.getRideDistance() < existing.getRideDistance()) {
				bestPerSet.put(key, r);
			}
		}
		List<Ride> result = new ArrayList<>(bestPerSet.values());
		int afterDedup = result.size();
		int dedupRemoved = initial - afterDedup;
		if (dedupRemoved > 0) {
			log.info("Pair-ride best-per-set dedup (after graph): kept {}/{} (removed {} FIFO/LIFO duplicates)",
					afterDedup, initial, dedupRemoved);
		}

		// --- Pass 2: Distance-savings gate (linear or log) ---
		double scale = exMasConfig.getPruningDistanceSavingsLogScale();
		int minDegree = Math.max(2, exMasConfig.getPruningDistanceSavingsMinDegree());
		boolean linearGateActive = exMasConfig.hasLinearGate();
		boolean logGateAppliesAtD2 = scale >= 0 && minDegree <= 2;
		if (linearGateActive || logGateAppliesAtD2) {
			int beforeGate = result.size();
			final int degree = 2;
			result = result.stream().filter(r -> {
				double sumDistances = Arrays.stream(r.getRequests()).mapToDouble(DrtRequest::getDistance).sum();
				if (!(sumDistances > 0)) return true;
				double maxRideDist = org.matsim.contrib.demand_extraction.algorithm.bamas.extension.BamasRideExtender
						.computeMaxAllowedRideDistance(degree, sumDistances, exMasConfig);
				return r.getRideDistance() <= maxRideDist;
			}).collect(Collectors.toList());
			double diagSum = result.isEmpty() ? 0
					: Arrays.stream(result.get(0).getRequests()).mapToDouble(DrtRequest::getDistance).sum();
			double diagGate = diagSum > 0
					? org.matsim.contrib.demand_extraction.algorithm.bamas.extension.BamasRideExtender
							.computeMaxAllowedRideDistance(degree, diagSum, exMasConfig) / diagSum
					: Double.NaN;
			log.info("Pair-ride distance-savings gate (after graph, shape={}): kept {}/{} (removed {}); gate(d=2) ratio threshold ~ {}",
					linearGateActive ? "linear" : "log",
					result.size(), beforeGate, beforeGate - result.size(),
					Double.isNaN(diagGate) ? "n/a" : String.format(java.util.Locale.ROOT, "%.3f", diagGate));
		}

		// --- Pass 3: Top-fraction filter by distance savings ---
		double pairKeepTop = exMasConfig.getPairKeepTopFraction();
		if (pairKeepTop < 1.0 && !result.isEmpty()) {
			int beforeFrac = result.size();
			// Compute fractional savings for each pair
			double[] savings = new double[result.size()];
			for (int i = 0; i < result.size(); i++) {
				Ride r = result.get(i);
				double sumDist = Arrays.stream(r.getRequests()).mapToDouble(DrtRequest::getDistance).sum();
				savings[i] = sumDist > 0 ? 1.0 - r.getRideDistance() / sumDist : 0;
			}
			// Find threshold at (1 - keepFraction) percentile
			double[] sorted = savings.clone();
			Arrays.sort(sorted);
			int threshIdx = (int) Math.floor(sorted.length * (1.0 - pairKeepTop));
			threshIdx = Math.min(threshIdx, sorted.length - 1);
			double threshold = sorted[threshIdx];

			List<Ride> filtered = new ArrayList<>();
			for (int i = 0; i < result.size(); i++) {
				if (savings[i] >= threshold) {
					filtered.add(result.get(i));
				}
			}
			result = filtered;
			log.info("Pair-ride top-fraction filter (after graph): kept {}/{} (removed {}, threshold={}, keepFraction={})",
					result.size(), beforeFrac, beforeFrac - result.size(),
					String.format(java.util.Locale.ROOT, "%.4f", threshold),
					String.format(java.util.Locale.ROOT, "%.2f", pairKeepTop));
		}

		log.info("Pair-ride base pruning (after graph): {} -> {} total ({} removed, {} reduction)",
				initial, result.size(), initial - result.size(),
				String.format(java.util.Locale.ROOT, "%.1f%%", (1.0 - (double) result.size() / initial) * 100));
		return result;
	}

	/**
	 * Stub-path (Task 13) mirror of {@link #maybePrunePairRidesAfterGraph(List)} over a
	 * degree-2 {@link StubColumns}. Produces the deduped + gated degree-2 survivor layer
	 * that (a) feeds the extender as the degree-2 parents and (b) slots into
	 * {@link StubRideStore} between the fat singles and the degree-3 layer.
	 *
	 * <h3>Parity contracts (must match the fat path bit-for-bit)</h3>
	 * <ul>
	 *   <li><b>Pass-1 dedup iteration order (contract #1):</b> same {@link java.util.HashMap}
	 *       type, same key {@code Arrays.toString(sortedIndices)}, same insertion sequence
	 *       (rows visited in their stub-row order, which equals the fat {@code pairs} list
	 *       order). The map values are source row indices; {@code values()} is emitted in the
	 *       map's iteration order — exactly the fat path's {@code bestPerSet.values()}. The
	 *       survivor layer is built by sequential {@code addRow} in that order (NOT
	 *       {@code StubColumns.mergeSorted}, which would re-sort lex and break the tie-break).</li>
	 *   <li><b>Distance comparison:</b> {@code fromDeci(distDm)} is bit-identical to the
	 *       original {@code getRideDistance()} double (Task 4), so the {@code <} comparison
	 *       picks the same winner.</li>
	 *   <li><b>FP operand order (contract #2):</b> request distances are summed in PICKUP
	 *       order (the fat path sums {@code Arrays.stream(r.getRequests())} where
	 *       {@code requests[] == {reqI, reqJ}}, i.e. pickup order for pairs). We reconstruct
	 *       pickup order from {@code originOrder} and resolve each request via
	 *       {@code reqArray[position]} (the exact generation copy — never {@code requestById},
	 *       which can return a different Ext-2 hub copy on a shared index), then sum with the
	 *       same compensated {@code Arrays.stream(...).sum()}.</li>
	 * </ul>
	 */
	private org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns maybePrunePairStubsAfterGraph(
			org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns pairStubs,
			DrtRequest[] reqArray) {
		if (pairStubs.size() == 0) {
			return pairStubs;
		}
		int initial = pairStubs.size();

		// --- Pass 1: Best-per-set dedup (always on) ---
		// HashMap<key, row>: same map type + key construction + insertion order as the fat
		// path's HashMap<String, Ride>; values are source rows instead of Ride objects.
		java.util.Map<String, Integer> bestPerSet = new java.util.HashMap<>();
		for (int row = 0; row < pairStubs.size(); row++) {
			int[] indices = pairStubs.requestIndices(row).clone();
			Arrays.sort(indices);
			String key = Arrays.toString(indices);
			Integer existing = bestPerSet.get(key);
			if (existing == null
					|| pairDistance(pairStubs, row) < pairDistance(pairStubs, existing)) {
				bestPerSet.put(key, row);
			}
		}
		// Emit values() in the map's iteration order — matches fat bestPerSet.values().
		List<Integer> result = new ArrayList<>(bestPerSet.values());
		int afterDedup = result.size();
		int dedupRemoved = initial - afterDedup;
		if (dedupRemoved > 0) {
			log.info("Pair-ride best-per-set dedup (after graph, stub): kept {}/{} (removed {} FIFO/LIFO duplicates)",
					afterDedup, initial, dedupRemoved);
		}

		// --- Pass 2: Distance-savings gate (linear or log) ---
		double scale = exMasConfig.getPruningDistanceSavingsLogScale();
		int minDegree = Math.max(2, exMasConfig.getPruningDistanceSavingsMinDegree());
		boolean linearGateActive = exMasConfig.hasLinearGate();
		boolean logGateAppliesAtD2 = scale >= 0 && minDegree <= 2;
		if (linearGateActive || logGateAppliesAtD2) {
			int beforeGate = result.size();
			final int degree = 2;
			List<Integer> gated = new ArrayList<>(result.size());
			for (int row : result) {
				double sumDistances = sumRequestDistancesPickupOrder(pairStubs, row, reqArray);
				if (!(sumDistances > 0)) {
					gated.add(row);
					continue;
				}
				double maxRideDist = org.matsim.contrib.demand_extraction.algorithm.bamas.extension.BamasRideExtender
						.computeMaxAllowedRideDistance(degree, sumDistances, exMasConfig);
				if (pairDistance(pairStubs, row) <= maxRideDist) {
					gated.add(row);
				}
			}
			result = gated;
			double diagSum = result.isEmpty() ? 0
					: sumRequestDistancesPickupOrder(pairStubs, result.get(0), reqArray);
			double diagGate = diagSum > 0
					? org.matsim.contrib.demand_extraction.algorithm.bamas.extension.BamasRideExtender
							.computeMaxAllowedRideDistance(degree, diagSum, exMasConfig) / diagSum
					: Double.NaN;
			log.info("Pair-ride distance-savings gate (after graph, stub, shape={}): kept {}/{} (removed {}); gate(d=2) ratio threshold ~ {}",
					linearGateActive ? "linear" : "log",
					result.size(), beforeGate, beforeGate - result.size(),
					Double.isNaN(diagGate) ? "n/a" : String.format(java.util.Locale.ROOT, "%.3f", diagGate));
		}

		// --- Pass 3: Top-fraction filter by distance savings ---
		double pairKeepTop = exMasConfig.getPairKeepTopFraction();
		if (pairKeepTop < 1.0 && !result.isEmpty()) {
			int beforeFrac = result.size();
			double[] savings = new double[result.size()];
			for (int i = 0; i < result.size(); i++) {
				int row = result.get(i);
				double sumDist = sumRequestDistancesPickupOrder(pairStubs, row, reqArray);
				savings[i] = sumDist > 0 ? 1.0 - pairDistance(pairStubs, row) / sumDist : 0;
			}
			double[] sorted = savings.clone();
			Arrays.sort(sorted);
			int threshIdx = (int) Math.floor(sorted.length * (1.0 - pairKeepTop));
			threshIdx = Math.min(threshIdx, sorted.length - 1);
			double threshold = sorted[threshIdx];

			List<Integer> filtered = new ArrayList<>();
			for (int i = 0; i < result.size(); i++) {
				if (savings[i] >= threshold) {
					filtered.add(result.get(i));
				}
			}
			result = filtered;
			log.info("Pair-ride top-fraction filter (after graph, stub): kept {}/{} (removed {}, threshold={}, keepFraction={})",
					result.size(), beforeFrac, beforeFrac - result.size(),
					String.format(java.util.Locale.ROOT, "%.4f", threshold),
					String.format(java.util.Locale.ROOT, "%.2f", pairKeepTop));
		}

		// Materialize the surviving rows into a fresh degree-2 layer in result order.
		org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns survivors =
				new org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns(2);
		for (int row : result) {
			// Carry the reqArray-position column through to the survivor layer so the later
			// degree-2 materialize + sum still resolve the exact generation copy (Task 13).
			survivors.addRow(pairStubs.requestIndices(row), pairStubs.originOrder(row),
					pairStubs.destOrder(row), pairStubs.rideDistanceDm(row),
					pairStubs.travelTimeDs(row), pairStubs.flags(row),
					pairStubs.positionIndices(row));
		}

		log.info("Pair-ride base pruning (after graph, stub): {} -> {} total ({} removed, {} reduction)",
				initial, survivors.size(), initial - survivors.size(),
				String.format(java.util.Locale.ROOT, "%.1f%%", (1.0 - (double) survivors.size() / initial) * 100));
		return survivors;
	}

	/** Ride distance for a pair stub row, bit-identical to {@code Ride.getRideDistance()} (Task 4). */
	private static double pairDistance(
			org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns cols, int row) {
		return org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubScaling
				.fromDeci(cols.rideDistanceDm(row));
	}

	/**
	 * Sum the per-request direct distances in PICKUP order for a degree-2 pair stub row,
	 * bit-identical to the fat pair gate. The fat gate computes
	 * {@code Arrays.stream(r.getRequests()).mapToDouble(DrtRequest::getDistance).sum()} where
	 * {@code r.getRequests() == {c.reqI, c.reqJ}} — the raw generation copies, in pickup order
	 * (pairs always pick up reqI then reqJ). We mirror it exactly:
	 * <ul>
	 *   <li>Resolve each request via {@code reqArray[position]} (the exact generation copy),
	 *       NOT {@code requestById.get(index)} — Ext-2 hub copies collide on index, so the map
	 *       can return a different OD copy and shift the sum (see {@code reqArray}/positions docs).</li>
	 *   <li>Use {@code Arrays.stream(...).sum()} (Kahan-Babuška-Neumaier compensated), not a naive
	 *       {@code +=} loop, so the FP result is bit-identical to the fat path even at n=2.</li>
	 * </ul>
	 */
	private static double sumRequestDistancesPickupOrder(
			org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns cols, int row,
			DrtRequest[] reqArray) {
		int degree = cols.degree();
		int[] originLocal = org.matsim.contrib.demand_extraction.algorithm.bamas.stub.OrderingCodec
				.unpack(cols.originOrder(row), degree);
		int[] positions = cols.positionIndices(row); // aligned to sortedSet
		DrtRequest[] pickupOrdered = new DrtRequest[degree];
		for (int i = 0; i < degree; i++) {
			pickupOrdered[i] = reqArray[positions[originLocal[i]]];
		}
		return Arrays.stream(pickupOrdered).mapToDouble(DrtRequest::getDistance).sum();
	}

	/**
	 * Plan B: select the EXTEND-marked subset of degree-D stub parents for top-K
	 * extension pruning, returning a new StubColumns containing only the marked rows
	 * in their original lex order. The full {@code parents} layer is unchanged (it is
	 * already in stubLayers for output and encoded in the prior degree graph); only
	 * the producer parents for the NEXT degree are restricted. K=0 never reaches here.
	 */
	private org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns filterExtensionParents(
			org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns parents,
			java.util.Map<Integer, DrtRequest> requestById) {
		org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns filtered =
				org.matsim.contrib.demand_extraction.algorithm.bamas.extension.ExtensionParentFilter.filter(
						parents, requestById,
						exMasConfig.getExtensionParentsTopK(),
						exMasConfig.getExtensionParentsTopKMetric(),
						exMasConfig.getExtensionParentsSelectionRule(),
						exMasConfig.getExtensionParentsMmrLambda());
		log.info("extension_parents_top_k: degree {} parents kept {}/{} (K={}, metric={}, rule={}, lambda={})",
				parents.degree(), filtered.size(), parents.size(),
				exMasConfig.getExtensionParentsTopK(), exMasConfig.getExtensionParentsTopKMetric(),
				exMasConfig.getExtensionParentsSelectionRule(), exMasConfig.getExtensionParentsMmrLambda());
		return filtered;
	}

	/**
	 * Build the inter-degree pruner from config, or null if pruning is disabled.
	 * RATIO_THRESHOLD with keepTopFraction >= 1.0 returns null (no-op pass-through).
	 */
	private static PostExtensionPruner buildPruner(org.matsim.contrib.demand_extraction.config.ExMasConfigGroup cfg) {
		switch (cfg.getPruningMode()) {
			case RATIO_THRESHOLD:
				double frac = cfg.getInterDegreeKeepFraction();
				return frac < 1.0 ? PostExtensionPruner.ratioThreshold(frac) : null;
			case COVERAGE_TOPK:
				PostExtensionPruner.QualityMetric metric = switch (cfg.getPruningQualityMetric()) {
					case ABS_SAVINGS -> PostExtensionPruner.ABS_SAVINGS;
					case RATIO_SAVINGS -> PostExtensionPruner.RATIO_SAVINGS;
					// OP_COST_PER_PAX is consumed by the extension-parents ranker (Plan B);
					// the post-extension COVERAGE_TOPK pruner falls back to ABS_SAVINGS.
					case OP_COST_PER_PAX -> PostExtensionPruner.ABS_SAVINGS;
				};
				java.util.Map<Integer, Integer> kByDegree = cfg.getPruningCoverageKByDegree();
				if (kByDegree.isEmpty()) {
					return PostExtensionPruner.coverageTopK(cfg.getPruningCoverageK(), metric);
				} else {
					int defaultK = cfg.getPruningCoverageK();
					return PostExtensionPruner.coverageTopK(
							d -> kByDegree.getOrDefault(d, defaultK), metric);
				}
			default:
				throw new IllegalStateException("Unknown pruning mode: " + cfg.getPruningMode());
		}
	}

	private static int[] summarizeRideCounts(List<Ride> rides) {
		int singles = 0;
		int pairs = 0;
		int higher = 0;

		for (Ride ride : rides) {
			if (ride.getDegree() == 1) {
				singles++;
			} else if (ride.getDegree() == 2) {
				pairs++;
			} else {
				higher++;
			}
		}

		return new int[] { singles, pairs, higher };
	}

	public List<DrtRequest> getRequests() {
		return requests;
	}

	public List<Ride> getAllRides() {
		return allRides;
	}

	/**
	 * Returns the list of hyper-pooled rides generated in Phase 6.
	 *
	 * <p>Hyper-pooled rides are kept in a separate list since they have a different
	 * structure than regular Ride objects. They bundle multiple stop-to-stop rides
	 * together where passengers walk to/from designated stop locations.
	 *
	 * @return list of hyper-pooled rides, or empty list if hyper-pooling is disabled
	 */
	public List<HyperPooledRide> getHyperPooledRides() {
		return hyperPooledRides != null ? hyperPooledRides : Collections.emptyList();
	}
}
