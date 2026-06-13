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
import org.matsim.contrib.demand_extraction.algorithm.bamas.stub.HyperPoolStubRideStore;
import org.matsim.contrib.demand_extraction.algorithm.bamas.stub.MaterializedRideStore;
import org.matsim.contrib.demand_extraction.algorithm.bamas.stub.RideStore;
import org.matsim.contrib.demand_extraction.algorithm.bamas.stub.S2SRideMaterializer;
import org.matsim.contrib.demand_extraction.algorithm.bamas.stub.S2SStubColumns;
import org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StopLocationDictionary;
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
	// Plan A2 Task 3 — stub layers and D2D materializer, set during run() and consumed by
	// generateStopBasedRidesBatched (Phase 5) which executes outside the inner try block.
	private List<org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns> runStubLayers;
	private org.matsim.contrib.demand_extraction.algorithm.bamas.extension.RideMaterializer runD2DMaterializer;
	// Plan A2 Task 4 — S2S stubs and stop dictionary populated by generateStopBasedRidesBatched;
	// consumed when building HyperPoolStubRideStore for the stub-mode export path.
	private List<org.matsim.contrib.demand_extraction.algorithm.bamas.stub.S2SStubColumns> runS2SStubs;
	private org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StopLocationDictionary runStopDictionary;
	// Plan A2 Task 5 — requestById map needed by generateHyperPooledRidesFromStubs; set early in run()
	// alongside the other run* fields so Phase 6 can access it without parameter threading.
	private java.util.Map<Integer, DrtRequest> runRequestById;

	// Cleanup — cross-phase locals promoted to fields so the run() phases (initRun, generateSingles,
	// generatePairUniverse, …) can share them without threading every value through method
	// signatures. These hold the SAME values the inline run() locals held, in the same order.
	private long runAlgorithmStartTime;
	private DrtRequest[] runReqArray;
	private org.matsim.contrib.demand_extraction.algorithm.generation.PairGenerator runPairGen;
	private boolean runStreamingD2D;
	private boolean runPairStubPath;
	private int runExtensionLayerStart;

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
	 * Plan A3 — resume/checkpoint handles for one {@link #run} invocation, computed by
	 * {@link #resumeStateOrFresh()}. Carries the live {@link CheckpointManager} (nullable when
	 * checkpointing is off or unsupported on the active path), whether the run is RESUMING, the
	 * matched {@link CheckpointManager.Manifest} (nullable; non-null iff {@code resuming}), and the
	 * open connection-cache journal {@link ConnectionCacheJournal.Writer} (nullable).
	 *
	 * <p>Deliberately carries HANDLES, not eagerly-read layers: the actual {@code readBase()} /
	 * {@code readDegree()} calls (and their phase-scoped log lines) stay at their original sites
	 * inside {@code generatePairUniverse}/{@code extendDegrees}, so resume logs do not jump ahead of
	 * their PHASE headers (HARD RULE 1: no reordered side effects). {@code hasBase()} mirrors
	 * {@code resuming} — a resumed run loads the pair universe from the base checkpoint instead of
	 * regenerating it.
	 */
	private record ResumeState(
			CheckpointManager checkpointMgr,
			boolean resuming,
			CheckpointManager.Manifest resumeManifest,
			ConnectionCacheJournal.Writer cacheJournal) {
		boolean hasBase() {
			return resuming;
		}
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
		initRun(drtRequests);

		generateSingles(drtRequests);

		// Check if we should stop before generating pairs
		if (maxDegree < 2) {
			return completeEarly(runAlgorithmStartTime, "maxDegree < 2, skipping pair generation");
		}

		// streamingD2D distinguishes "return after door-to-door" (stop-based OFF) from
		// "continue into Phase 5/6 stop-based+hyperpool". When stop-based is ON, the degree-2
		// pair stubs and degree-3+ extension stubs are fed directly into Phase 5
		// (generateStopBasedRidesBatched) via on-demand materialization; allRides then holds
		// only the fat singles. The 100% target run has stop_based=false.
		this.runStreamingD2D = !exMasConfig.isEnableStopBased();
		// Degree-2 pair stubs: the full pair universe is generated, graphed, and deduped as a
		// compact StubColumns layer (never a fat List<Ride>), then carried as stubLayers[0] and
		// extended. The stop-based path consumes the same pair-stub layer through Phase 5 (Pass 2
		// of generateStopBasedRidesBatched materializes it first, before the degree-3+ layers).
		// Only the maxDegree<=2 early exit keeps a fat pair flow (no extension cascade).
		this.runPairStubPath = maxDegree > 2;
		final boolean pairStubPath = runPairStubPath;

		ResumeState resume = resumeStateOrFresh();
		ConnectionCacheJournal.Writer cacheJournal = resume.cacheJournal();

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
		this.runPairGen = new PairGenerator(network, budgetValidator, horizon,
				exMasConfig.getAlgorithmProcessCount(),
				exMasConfig.isEnableBudgetAwareConstraints());

		if (!pairStubPath) {
			// maxDegree == 2 (maxDegree < 2 already returned above): no extension cascade,
			// so there is no stub layer to build — emit the fat pair universe and finish.
			List<Ride> pairRides = runPairGen.generatePairs(runReqArray);
			allRides.addAll(pairRides);
			return completeEarly(runAlgorithmStartTime, "maxDegree <= 2");
		}

		org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns allPairStubs =
				generatePairUniverse(resume);
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

		checkpointBaseIfFresh(allPairStubs, resume);

		// Degree-2 survivors live in `pairSurvivorStubs` (added to stubLayers as the first layer).
		org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns pairSurvivorStubs =
				prunePairUniverse(allPairStubs);

		List<org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns> stubLayers =
				extendDegrees(pairSurvivorStubs, resume);

		applyPostExtensionSelection(stubLayers);

		RideStore d2dReturn = finishDoorToDoor(stubLayers, resume);
		if (d2dReturn != null) {
			return d2dReturn;
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

		stopBasedLayers();

		hyperPool();

		// The HyperPoolStubRideStore performs the equivalent stable sort + sequential reindex on
		// its row-reference array at construction (the fat in-place sort+reindex is gone with the
		// fat path; allRides here holds only fat singles, never the S2S universe).

		logSummary();

		return store();
	}

	/**
	 * initRun: build the index→request lookup, reset the run-scoped accumulators, snapshot the
	 * generation-copy {@code reqArray}, start the wall-clock timer, and log the run header.
	 */
	private void initRun(List<DrtRequest> drtRequests) {
		log.info("======================================================================");
		log.info("Starting ExMAS algorithm");
		log.info("  Requests: {}", drtRequests.size());
		log.info("  Horizon: {}s", horizon);
		log.info("  Max degree: {}", maxDegree);
		log.info("======================================================================");
		this.runAlgorithmStartTime = System.currentTimeMillis();

        this.requests = drtRequests;
        this.allRides = new ArrayList<>();
        this.hyperPooledRides = new ArrayList<>();

        this.runReqArray = drtRequests.toArray(new DrtRequest[0]);

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
        runRequestById = requestById; // Plan A2 Task 5: needed by generateHyperPooledRidesFromStubs
	}

	/**
	 * generateSingles: Phase 1 — generate single rides with budget validation and append them
	 * to {@code allRides}.
	 */
	private void generateSingles(List<DrtRequest> drtRequests) {
		int algorithmProcessCount = exMasConfig.getAlgorithmProcessCount();

		// Phase 1: Generate single rides with budget validation
		log.info("");
		log.info("PHASE 1: Single Ride Generation");
		log.info("======================================================================");
		BamasSingleRideGenerator singleGen = new BamasSingleRideGenerator(network, budgetValidator, algorithmProcessCount);
        List<Ride> singleRides = singleGen.generate(drtRequests);
		allRides.addAll(singleRides);
	}

	/**
	 * resumeStateOrFresh: the resume/checkpoint setup block. Computes the checkpoint manager,
	 * resume flag + manifest, and opens the connection-cache journal — exactly as the inline block
	 * did. Carries HANDLES only: the {@code readBase()}/{@code readDegree()} loads (and their
	 * phase-scoped logs) stay at their original sites in {@code generatePairUniverse}/
	 * {@code extendDegrees}, so resume logs are not reordered ahead of their PHASE headers.
	 */
	private ResumeState resumeStateOrFresh() {
		final boolean pairStubPath = runPairStubPath;

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
		return new ResumeState(checkpointMgr, resuming, resumeManifest, cacheJournal);
	}

	/**
	 * generatePairUniverse: Phase 2 (stub) — the full pair universe as a degree-2 StubColumns.
	 * On resume it is LOADED from the base checkpoint instead of re-generated. Branches internally
	 * on {@code resume.hasBase()} so the resume log stays under the PHASE 2 header (printed by the
	 * caller before this method runs).
	 */
	private org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns generatePairUniverse(
			ResumeState resume) {
		// Phase 2 (stub): the full pair universe as a degree-2 StubColumns. On resume it is
		// LOADED from the base checkpoint (review addendum F6) instead of re-generated —
		// generatePairStubs is the routing-heavy phase the checkpoint exists to skip. The
		// loaded universe carries the positionsFlat copy-identity column (F1), so the graph
		// build + survivor prune below reproduce the original run bit-for-bit.
		org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns allPairStubs;
		if (resume.resuming()) {
			allPairStubs = resume.checkpointMgr().readBase();
			log.info("");
			log.info("PHASE 2 (resume): loaded pre-prune pair universe ({} rows) from checkpoint",
					allPairStubs.size());
		} else {
			allPairStubs = runPairGen.generatePairStubs(runReqArray);
		}
		return allPairStubs;
	}

	/**
	 * checkpointBaseIfFresh: persist the PRE-PRUNE pair universe before it is dropped (skipped on
	 * resume — the base checkpoint is already on disk and was just read back).
	 */
	private void checkpointBaseIfFresh(
			org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns allPairStubs,
			ResumeState resume) {
		CheckpointManager checkpointMgr = resume.checkpointMgr();
		// Plan A3: persist the PRE-PRUNE pair universe before it is dropped (review addendum
		// F6) — on resume the shareability graph rebuilds via buildGraph(allPairStubs) and the
		// degree-2 survivors via maybePrunePairStubsAfterGraph, both deterministic, so no
		// separate graph/edge-list serializer is needed. Written before the prune so the
		// persisted universe matches what the graph was built from. Skipped on resume (the
		// base checkpoint is already on disk and was just read back from it).
		if (checkpointMgr != null && !resume.resuming()) {
			// Journal the pair-gen SSSP entries durably BEFORE the manifest records base done,
			// so a crash after the manifest still finds those entries on resume.
			drainJournalBarrier(resume.cacheJournal());
			checkpointMgr.writeBase(allPairStubs);
			// Plan A3 Task 7: test-only crash injection at the pre-loop base barrier
			// (degrees 1+2 committed). No-op unless -Dbamas.checkpoint.killAfterDegree=2.
			CheckpointKillSwitch.maybeHaltAfterDegree(2);
		}
	}

	/**
	 * prunePairUniverse: best-per-set dedup + distance gate + top-fraction over the stub universe,
	 * then compact the retained overlay. The full pair universe is dropped here (only the survivor
	 * layer is retained), so the full pair universe never coexists with the extension cascade.
	 */
	private org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns prunePairUniverse(
			org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns allPairStubs) {
		// Best-per-set dedup + distance gate + top-fraction over the stub universe. The
		// full pair universe is dropped here (only the survivor layer is retained), so
		// the full pair universe never coexists with the extension cascade.
		org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns pairSurvivorStubs =
				maybePrunePairStubsAfterGraph(allPairStubs, runReqArray);
		// Cache-memory tiers (Task 7 Step 5): pair generation is complete and its feasible
		// chain segments were promoted at acceptance (PairGenerator.promotePairChainSegments).
		// Compact the retained overlay into a frozen snapshot at this single-threaded barrier,
		// before the extension cascade begins.
		network.compactRetained();
		return pairSurvivorStubs;
	}

	/**
	 * extendDegrees: Phase 4 — the iterative ride-extension loop. Accumulates per-degree stub
	 * layers (with the degree-2 pair survivor layer first), restores completed degrees on resume,
	 * applies in-loop RATIO pruning, promotes survivor legs, drains checkpoint barriers, and
	 * returns the per-degree stub layers ({@code runStubLayers}).
	 */
	private List<org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns> extendDegrees(
			org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns pairSurvivorStubs,
			ResumeState resume) {
		final boolean pairStubPath = runPairStubPath;
		CheckpointManager checkpointMgr = resume.checkpointMgr();
		boolean resuming = resume.resuming();
		CheckpointManager.Manifest resumeManifest = resume.resumeManifest();
		java.util.Map<Integer, DrtRequest> requestById = runRequestById;

        // Phase 4: Iteratively extend rides with budget validation
		// The ordering-based BamasRideExtender enumerates valid orderings directly from
		// pairwise constraints in the shareability graph — no rideMap needed.
		// It returns top-1 per set, so MaxPerSet pruning is redundant.
		// Percentile pruning across sets is still applied to bound memory.
		log.info("");
		log.info("PHASE 4: Iterative Ride Extension");
		log.info("======================================================================");
		int nextRideIndex = allRides.size();
		// Stub mode (Task 11): degree-3+ extension rides are held as per-degree StubColumns,
		// NOT appended to allRides as fat Ride objects. We accumulate the per-degree layers
		// here and batch-materialize them once at the end, concatenating with the still-fat
		// singles + pairs already in allRides. The fat `extended` list returned by extendRides
		// is still produced (Task 10 is additive) but used only for control flow + degree-graph;
		// it is not retained past each iteration. Task 12 makes export streaming.
		List<org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns> stubLayers =
				new ArrayList<>();
		this.runStubLayers = stubLayers; // Plan A2: expose to Phase 5 outside the try block
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
		this.runExtensionLayerStart = extensionLayerStart; // consumed by applyPostExtensionSelection
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

			// Seam (a): STUB parents — the previous iteration's captured layer (degree 3→4+),
			// or the degree-2 survivor layer at the first iteration (degree 2→3). The loop only
			// runs for maxDegree>2 (pairStubPath), so prevStubLayer is always the pair-survivor
			// layer at first entry and a captured stub layer thereafter — never null.
			org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns extensionParents = prevStubLayer;
			if (exMasConfig.getExtensionParentsTopK() > 0
					&& (degree + 1) >= exMasConfig.getExtensionParentsTopKMinDegree()
					&& prevStubLayer.degree() >= 3) {
				extensionParents = filterExtensionParents(prevStubLayer, requestById);
			}
			List<Ride> extended = extender.extendRides(extensionParents, runReqArray, nextRideIndex);
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
			// Degree barrier: sample heap and rotate the speculative tier under pressure.
			// Output-invariant (cross-engine value identity); survivor legs were just promoted
			// to the retained tier above, so eviction here cannot drop a segment export re-reads.
			network.checkWatermark();

			// Plan A3 barrier: the degree-(degree+1) layer is now finalized (post in-loop
			// RATIO prune; post-loop COVERAGE_TOPK re-applies uniformly on resume too).
			// generatedCount (pre-prune) restores nextRideIndex exactly. Manifest written last.
			if (checkpointMgr != null) {
				// Journal this degree's new SSSP entries durably before the manifest records
				// the degree complete (same crash-consistency ordering as the base barrier).
				drainJournalBarrier(resume.cacheJournal());
				checkpointMgr.writeDegree(degree + 1, layer, generatedCount);
				// Plan A3 Task 7: test-only crash injection at the in-loop barrier, right
				// after the degree-(degree+1) manifest is durable. No-op unless
				// -Dbamas.checkpoint.killAfterDegree=(degree+1).
				CheckpointKillSwitch.maybeHaltAfterDegree(degree + 1);
			}

			nextRideIndex += generatedCount; // index space reserved for all generated rides
		}
		return stubLayers;
	}

	/**
	 * applyPostExtensionSelection: post-extension COVERAGE_TOPK pruning, applied once to all
	 * extension rides after the cascade terminates (skips the degree-2 pair layer).
	 */
	private void applyPostExtensionSelection(
			List<org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns> stubLayers) {
		java.util.Map<Integer, DrtRequest> requestById = runRequestById;
		int extensionLayerStart = runExtensionLayerStart;

		// Post-extension COVERAGE_TOPK pruning: applied once to all extension rides after the
		// cascade terminates, so the full cascade runs unimpeded and K compression is a final step.
		if (exMasConfig.getPruningMode() == org.matsim.contrib.demand_extraction.config.ExMasConfigGroup.PruningMode.COVERAGE_TOPK) {
			PostExtensionPruner pruner = buildPruner(exMasConfig);
			if (pruner != null) {
				// Prune each per-degree stub layer in place (each layer is one degree,
				// so per-degree COVERAGE_TOPK == per-layer pruning). On the pairStubPath
				// the degree-2 pair layer is stubLayers[0] and is skipped: master never
				// applies COVERAGE_TOPK to pairs (they have a separate distance gate).
				for (int i = extensionLayerStart; i < stubLayers.size(); i++) {
					stubLayers.set(i, pruner.pruneStubLayer(stubLayers.get(i), requestById));
				}
			}
		}
	}

	/**
	 * finishDoorToDoor: build the D2D materializer, log the door-to-door completion summary, and —
	 * on the streamingD2D path (stop-based OFF) — close the journal and return a streaming
	 * {@link StubRideStore}. Returns {@code null} when the run must continue into Phase 5/6
	 * (stop-based ON), leaving the terminal return to the caller after the try/catch.
	 */
	private RideStore finishDoorToDoor(
			List<org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns> stubLayers,
			ResumeState resume) {
		final boolean streamingD2D = runStreamingD2D;
		final boolean pairStubPath = runPairStubPath;
		java.util.Map<Integer, DrtRequest> requestById = runRequestById;
		ConnectionCacheJournal.Writer cacheJournal = resume.cacheJournal();

		// Plan A2 Task 3 — stub mode WITH stop-based: Phase 5 consumes D2D rides via batched
		// on-demand materialization instead of bulk-materializing all stubs into allRides first.
		// The materializer is stored as an instance field so Phase 5 (outside this try block) can
		// call generateStopBasedRidesBatched with it. allRides holds only the fat singles; the
		// degree-2 pair layer (stubLayers[0]) and degree-3+ layers are fed to Phase 5 directly.
		// The 4-arg form (pairGen + reqArray) is REQUIRED: Phase 5 Pass 2 now materializes the
		// degree-2 pair layer, and degree-2 rebuild needs pairGen's fixed-time routing
		// (buildRideFromOrdering's cumulative clock misses unwarmed time bins for pairs) and the
		// generation-copy reqArray (positional resolution, avoiding index-collision via requestById).
		// NON-DEFERRED contract is preserved: RideMaterializer always populates remainingBudgets.
		// Non-streaming (stop-based ON) path: Phase 5 consumes D2D rides via on-demand
		// materialization, so build the materializer here. On the streamingD2D path the
		// per-row materializer is built later, at the StubRideStore export return.
		this.runD2DMaterializer = !streamingD2D
				? new org.matsim.contrib.demand_extraction.algorithm.bamas.extension.RideMaterializer(
						network, budgetValidator, runPairGen, runReqArray)
				: null;

		// Seam (c): remainingBudgets is populated inline by RideMaterializer.validateAndPopulateBudgets
		// — the streaming path does it lazily during forEachMaterialized, the stop-based path (Plan A2)
		// does it per-batch inside generateStopBasedRidesBatched. Either way the stub layers carry NO
		// budgets, so there is no separate deferred-budget batch pass (the old fat populateBudgetsBatch
		// is gone with the fat path).

		// True D2D total: fat singles+pairs already in allRides, plus stub-layer rows (which
		// in the streaming path have NOT been added to allRides). Log-only — for traceability.
		int stubLayerRows = 0;
		if (streamingD2D) {
			for (org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns layer : stubLayers) {
				stubLayerRows += layer.size();
			}
		}
		int totalD2D = allRides.size() + stubLayerRows;
		long totalElapsed = System.currentTimeMillis() - runAlgorithmStartTime;
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
							network, budgetValidator, runPairGen, runReqArray);
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
		return null;
	}

	/**
	 * stopBasedLayers: Phase 5 — stop-based ride generation (HyperPool Stage 1). No-op (empty list)
	 * when stop-based is disabled.
	 */
	private List<Ride> stopBasedLayers() {
		// Phase 5: Stop-Based Ride Generation (HyperPool Stage 1)
		// Only runs if enableStopBased = true
		List<Ride> stopBasedRides = new ArrayList<>();
		if (exMasConfig.isEnableStopBased()) {
			log.info("");
			log.info("PHASE 5: Stop-Based Ride Generation (HyperPool Stage 1)");
			log.info("======================================================================");

			// Batched materialization + S2S stubbification. The returned list is used ONLY for
			// Phase 6 (intermediate, full-rides path). S2S rides are NOT added to allRides — the
			// HyperPoolStubRideStore handles them from runS2SStubs without materialising the
			// entire S2S universe. runD2DMaterializer is always non-null here (stop-based ON ⇒
			// !streamingD2D); the streamingD2D path returned earlier via StubRideStore.
			stopBasedRides = generateStopBasedRidesBatched(
					allRides, runStubLayers, runD2DMaterializer, runRequestById);
			log.info("Generated {} stop-to-stop rides (stored as stubs, not in allRides)",
					stopBasedRides.size());
		}
		return stopBasedRides;
	}

	/**
	 * hyperPool: Phase 6 — hyper-pooling (HyperPool Stage 2). No-op when hyper-pooling is disabled
	 * (or stop-based is off). Stores the result into {@code hyperPooledRides}.
	 */
	private void hyperPool() {
		// Phase 6: Hyper-Pooling (HyperPool Stage 2)
		// Only runs if enableHyperPooling = true (and enableStopBased = true)
		if (exMasConfig.isEnableHyperPooling()) {
			if (!exMasConfig.isEnableStopBased()) {
				log.warn("Hyper-pooling requires stop-based pooling to be enabled. Skipping Phase 6.");
			} else {
				log.info("");
				log.info("PHASE 6: Hyper-Pooling (HyperPool Stage 2)");
				log.info("======================================================================");

				// S2S universe stays compact; full rides are materialized lazily per cluster
				// during bundling only. runS2SStubs is always non-null here (Phase 5 ran).
				hyperPooledRides = generateHyperPooledRidesFromStubs();
				log.info("Generated {} hyper-pooled rides", hyperPooledRides.size());
			}
		}
	}

	/**
	 * logSummary: final summary for the stop-based path (counts D2D + S2S from stubs).
	 */
	private void logSummary() {
		// Final summary
		if (exMasConfig.isEnableStopBased()) {
			log.info("");
			log.info("======================================================================");
			// Count from stubs (runS2SStubs is always non-null here — Phase 5 ran).
			int d2dTotal = allRides.size() + (runStubLayers == null ? 0
					: runStubLayers.stream().mapToInt(
							org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns::size
					).sum());
			int s2sTotal = runS2SStubs.stream().mapToInt(S2SStubColumns::size).sum();
			if (exMasConfig.isEnableHyperPooling()) {
				log.info("Final Summary (with Stop-Based Pooling and Hyper-Pooling)");
				log.info("  Door-to-Door rides: {}", d2dTotal);
				log.info("  Stop-to-Stop rides (stubs): {}", s2sTotal);
				log.info("  Hyper-Pooled rides: {}", hyperPooledRides.size());
			} else {
				log.info("Final Summary (with Stop-Based Pooling)");
				log.info("  Door-to-Door rides: {}", d2dTotal);
				log.info("  Stop-to-Stop rides (stubs): {}", s2sTotal);
				log.info("  Total rides: {}", d2dTotal + s2sTotal);
			}
			log.info("======================================================================");
		}
	}

	/**
	 * store: build the terminal {@link HyperPoolStubRideStore} for the stop-based path (D2D stubs +
	 * S2S stubs), streaming instead of materialising all rides into allRides.
	 */
	private RideStore store() {
		java.util.Map<Integer, DrtRequest> requestById = runRequestById;

		// Stop-based path → stream via HyperPoolStubRideStore (D2D stubs + S2S stubs) instead of
		// materialising all rides into allRides. Reaching here means enableStopBased was ON
		// (stop-based OFF returned earlier via StubRideStore), so runS2SStubs is always non-null.
		S2SRideMaterializer s2sMat = new S2SRideMaterializer(
				network, budgetValidator, runStopDictionary, exMasConfig);
		log.info("HyperPool streaming: exporting via HyperPoolStubRideStore — "
				+ "{} fat D2D + {} D2D stub rows + {} S2S stub rows",
				allRides.size(),
				runStubLayers == null ? 0 : runStubLayers.stream().mapToInt(
					org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns::size
				).sum(),
				runS2SStubs.stream().mapToInt(S2SStubColumns::size).sum());
		return new HyperPoolStubRideStore(
				allRides, runStubLayers != null ? runStubLayers : new ArrayList<>(),
				runD2DMaterializer, runS2SStubs, s2sMat, requestById);
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
	 * Plan A2 Task 3 — batched D2D materialization feeding {@link StopBasedRideGenerator}.
	 *
	 * <p>Replaces the former bulk-materialize-then-Phase-5 flow. Instead of expanding all
	 * stub layers into {@code allRides} before calling {@code generateStopBasedRides}, this
	 * method feeds D2D rides to the generator in two passes:
	 * <ol>
	 *   <li>Fat rides already in {@code fatRides} (singles + pairs, already materialised).
	 *   <li>Degree-3+ stub layers, materialised in fixed-size batches (100 k rows).
	 * </ol>
	 *
	 * <p><strong>Parity contract:</strong> The {@link StopBasedRideGenerator} sorts conversion
	 * candidates by {@code sourceRide.getIndex()} before assigning S2S indices. In the current
	 * code, all pairs and all materialised stubs carry index 0 (pairs from
	 * {@code PairGenerator.buildRide(c, 0)}, stubs from {@code RideMaterializer} which also
	 * returns index 0). A stable sort over index-0 elements preserves input order. Therefore
	 * two-pass feeding (fat rides first, stubs second — each pass sorted by index 0 in input
	 * order) produces the SAME candidate sequence as the former single-pass feed, and thus the
	 * same S2S output. The {@code nextIndex} is threaded across passes by actual produced
	 * count, not by batch size, so no index gaps occur when conversions fail.
	 *
	 * @param fatRides     fat singles + pairs already in allRides
	 * @param stubs        degree-3+ per-degree stub layers (never null in stub mode)
	 * @param materializer D2D stub materializer (RideMaterializer instance)
	 * @param requestById  global request lookup (passed to materializer)
	 * @return all generated stop-based rides, with sequentially threaded S2S indices
	 */
	/**
	 * Plan A2 Task 4 helper: extract S2S stub fields from a full {@link Ride} produced by
	 * {@link StopBasedRideGenerator} and append a row to the appropriate per-degree
	 * {@link S2SStubColumns} in {@code stubs}, creating the layer if absent.
	 *
	 * <p>The sorted set and origin/dest packed orderings are derived following the same
	 * approach as {@link org.matsim.contrib.demand_extraction.algorithm.bamas.stub.RideStub#fromRide}:
	 * sort the pickup-order indices to obtain the sorted set, then convert each ordering
	 * array (pickup, dropoff) from global to local positions.
	 *
	 * <p>{@code distDm}/{@code ttDs} capture the S2S in-vehicle segment (the only connection
	 * segment on an S2S ride); they are used for the self-check in
	 * {@link S2SRideMaterializer} and as the sort key for
	 * {@link HyperPoolStubRideStore}. They are NOT fed into the connection arrays during
	 * pinned-stop replay (which re-routes for exact values).
	 */
	private static void stubbifyS2SRide(Ride ride,
			java.util.Map<Integer, S2SStubColumns> stubs,
			StopLocationDictionary dictionary) {
		int degree = ride.getDegree();
		S2SStubColumns layer = stubs.computeIfAbsent(degree, S2SStubColumns::new);

		// Build sorted set (ascending global indices) — same approach as RideStub.fromRide.
		int[] pickupGlobal = ride.getOriginsIndex();      // global indices in pickup order
		int[] sortedSet = pickupGlobal.clone();
		java.util.Arrays.sort(sortedSet);

		// Convert pickup ordering from global to local positions in the sorted set.
		int[] originsLocal = toLocalPositions(sortedSet, pickupGlobal);
		long originPacked = org.matsim.contrib.demand_extraction.algorithm.bamas.stub.OrderingCodec
				.pack(originsLocal);

		// Convert dropoff ordering from global to local positions in the sorted set.
		int[] destGlobal = ride.getDestinationsIndex();  // global indices in dropoff order
		int[] destsLocal = toLocalPositions(sortedSet, destGlobal);
		long destPacked = org.matsim.contrib.demand_extraction.algorithm.bamas.stub.OrderingCodec
				.pack(destsLocal);

		// S2S ride has exactly ONE connection segment.
		// Use getRideDistance()/getRideTravelTime() which are already 0.1-rounded.
		int distDm = org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubScaling
				.toDeci(ride.getRideDistance());
		int ttDs = org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubScaling
				.toDeci(ride.getRideTravelTime());
		byte flags = org.matsim.contrib.demand_extraction.algorithm.bamas.stub.RideStub
				.kindToFlags(ride.getKind());

		int pickupId  = dictionary.idOf(ride.getPickupStop());
		int dropoffId = dictionary.idOf(ride.getDropoffStop());

		layer.addRow(sortedSet, originPacked, destPacked, distDm, ttDs, flags,
				pickupId, dropoffId, ride.getStartTime(), ride.getIndex(),
				ride.getAccessWalkDistances(), ride.getEgressWalkDistances());
	}

	/**
	 * Convert an array of global request indices to their local positions within
	 * the sorted set (ascending). Mirrors the same helper in
	 * {@link org.matsim.contrib.demand_extraction.algorithm.bamas.stub.RideStub}.
	 */
	private static int[] toLocalPositions(int[] sortedSet, int[] globals) {
		int[] locals = new int[globals.length];
		for (int i = 0; i < globals.length; i++) {
			int local = java.util.Arrays.binarySearch(sortedSet, globals[i]);
			if (local < 0) {
				throw new IllegalStateException(
						"Global request index " + globals[i]
						+ " not found in S2S ride's sorted request set");
			}
			locals[i] = local;
		}
		return locals;
	}

	private List<Ride> generateStopBasedRidesBatched(
			List<Ride> fatRides,
			List<org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns> stubs,
			org.matsim.contrib.demand_extraction.algorithm.bamas.extension.RideMaterializer materializer,
			java.util.Map<Integer, DrtRequest> requestById) {

		// Total D2D count (fat + stub rows): the S2S startIndex must follow ALL D2D rides,
		// matching master's pre-Phase-5 index = allRides.size() after all D2D are appended.
		int totalStubRows = 0;
		for (org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns layer : stubs) {
			totalStubRows += layer.size();
		}
		int totalD2DCount = fatRides.size() + totalStubRows;

		if (totalD2DCount == 0) {
			// Plan A2 Task 4: initialise stub store to empty even on the zero-ride path.
			runS2SStubs = new ArrayList<>();
			runStopDictionary = new StopLocationDictionary();
			return new ArrayList<>();
		}

		// Create stop finder and generator ONCE (shared across all batches).
		Network matsimNetwork = network.getNetwork();
		StopFinderFactory factory = new StopFinderFactory(matsimNetwork, facilities, exMasConfig);
		StopFinder stopFinder = factory.create();
		WalkingDistanceCalculator walkCalculator = factory.createWalkingDistanceCalculator();
		int algorithmProcessCount = exMasConfig.getAlgorithmProcessCount();
		org.matsim.contrib.demand_extraction.algorithm.generation.WalkBudgetProvider walkBudgetProvider =
				budgetToConstraints == null ? null :
				(budget, req, tt, dist, delay) ->
						budgetToConstraints.budgetToMaxWalkDistance(budget, null, req, tt, dist, delay);
		StopBasedRideGenerator generator = new StopBasedRideGenerator(
				network, stopFinder, walkCalculator, budgetValidator, exMasConfig, algorithmProcessCount,
				walkBudgetProvider);

		// Plan A2 Task 4: accumulate S2S stubs instead of full Ride objects.
		// Key = degree; value = per-degree S2SStubColumns (one row per S2S ride produced).
		java.util.Map<Integer, S2SStubColumns> s2sStubMap = new java.util.LinkedHashMap<>();
		StopLocationDictionary stopDictionary = new StopLocationDictionary();

		// For Phase 6 intermediate (Task 4): re-materialise from stubs so we never hold
		// the full S2S universe as full rides while also holding the stubs.
		// We collect stubs first, then materialise at the end of this method.
		// (Task 5 will eliminate this re-materialisation entirely.)
		List<Ride> allS2SRidesForPhase6 = new ArrayList<>();

		// S2S indices start at totalD2DCount and are threaded across batches by actual
		// produced count (condition 3 from the plan: not startIndex + batchSize·k).
		int nextIndex = totalD2DCount;

		// Pass 1: fat rides (singles + pairs). Singles are filtered by the generator (degree < 2).
		// All pairs have index 0 → stable sort within this pass keeps them in input order.
		List<Ride> fatD2DBatch = fatRides.stream()
				.filter(r -> r.getVariant() == RideVariant.DOOR_TO_DOOR)
				.collect(Collectors.toList());
		if (!fatD2DBatch.isEmpty()) {
			List<Ride> batch1S2S = generator.generateStopBasedRides(fatD2DBatch, nextIndex);
			for (Ride s2sRide : batch1S2S) {
				stubbifyS2SRide(s2sRide, s2sStubMap, stopDictionary);
			}
			allS2SRidesForPhase6.addAll(batch1S2S);
			nextIndex += batch1S2S.size();
		}

		// Pass 2: stub layers, materialised in batches of STUB_BATCH_SIZE rows.
		// Each materialised stub ride carries index 0 → parity condition holds (see Javadoc).
		final int STUB_BATCH_SIZE = 100_000;
		log.info("Plan A2 Task 4: feeding {} stub rows in batches of {} to Phase 5, stubbifying S2S output",
				totalStubRows, STUB_BATCH_SIZE);
		int totalMaterialized = 0;
		for (org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns layer : stubs) {
			int layerSize = layer.size();
			for (int batchStart = 0; batchStart < layerSize; batchStart += STUB_BATCH_SIZE) {
				int batchEnd = Math.min(batchStart + STUB_BATCH_SIZE, layerSize);
				List<Ride> stubBatch = new ArrayList<>(batchEnd - batchStart);
				for (int row = batchStart; row < batchEnd; row++) {
					stubBatch.add(materializer.materialize(layer, row, requestById));
				}
				totalMaterialized += stubBatch.size();
				List<Ride> batchS2S = generator.generateStopBasedRides(stubBatch, nextIndex);
				for (Ride s2sRide : batchS2S) {
					stubbifyS2SRide(s2sRide, s2sStubMap, stopDictionary);
				}
				allS2SRidesForPhase6.addAll(batchS2S);
				nextIndex += batchS2S.size();
			}
		}
		int totalS2S = allS2SRidesForPhase6.size();
		log.info("Plan A2 Task 4: materialised {} D2D rows, stubbified {} S2S rides into {} degree layers",
				totalMaterialized, totalS2S, s2sStubMap.size());

		// Store stubs and dictionary for the export pass (HyperPoolStubRideStore).
		runS2SStubs = new ArrayList<>(s2sStubMap.values());
		runStopDictionary = stopDictionary;

		// Return full S2S rides. In fat-mode Phase 6, these are consumed via generateHyperPooledRides.
		// In stub-mode Phase 6 (Task 5+), generateHyperPooledRidesFromStubs reads runS2SStubs directly
		// and never materialises the full S2S universe — but we still need this list as a no-op return
		// value (the caller ignores it in stub mode: Phase 6 switches on runS2SStubs != null).
		return allS2SRidesForPhase6;
	}

	/**
	 * Plan A2 Task 5 — Phase 6 stub path: generate hyper-pooled rides from the S2S stub layers
	 * accumulated during Phase 5 ({@code runS2SStubs}).
	 *
	 * <p>Constructs {@link StopToStopRideWrapper} instances backed by stub scalars (no full
	 * {@link Ride} allocation) and deferred materializers.  The wrapper getters suffice for the
	 * entire clustering phase (graph construction, compatibility checks, stop-sequence generation);
	 * full rides are materialised lazily per cluster during bundling, keeping peak memory ≈ one
	 * cluster's rides instead of the entire S2S universe.
	 *
	 * <p>The S2S start index = total D2D count (fat singles+pairs + stub rows), matching the index
	 * assigned by {@link #generateStopBasedRidesBatched} so wrapper indices are bit-identical to
	 * the fat path.
	 */
	private List<HyperPooledRide> generateHyperPooledRidesFromStubs() {
		// S2S start index = number of D2D rides (fat + stub rows) — mirrors generateStopBasedRidesBatched
		int fatD2DCount  = allRides.size();
		List<org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns> d2dStubLayers =
				runStubLayers != null ? runStubLayers : new ArrayList<>();
		int stubD2DCount = d2dStubLayers.stream().mapToInt(
				org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns::size).sum();
		int s2sStartIndex = fatD2DCount + stubD2DCount;

		// Plan A2 (hyperpool index canonicalization): compute the SAME export sort permutation
		// HyperPoolStubRideStore will use at export time, BEFORE bundling. Each S2S row's final
		// ride index is its position r in this permutation. Feed those final indices to the
		// wrappers so clustering + sourceRideIndices match the fat path (which stamps the
		// identical final index via stampFinalS2SIndicesForHyperPool). The three inputs here are
		// the exact objects passed to the HyperPoolStubRideStore constructor in the same state,
		// so the permutation — and thus every S2S final index — is identical to export.
		int[][] perm = org.matsim.contrib.demand_extraction.algorithm.bamas.stub.HyperPoolStubRideStore
				.computeSortPermutation(allRides, d2dStubLayers, runS2SStubs);
		int[] sourceOf   = perm[0];
		int[] localRowOf = perm[1];
		int nD2DLayers = d2dStubLayers.size();
		int[][] s2sFinalIndex = new int[runS2SStubs.size()][];
		for (int s = 0; s < runS2SStubs.size(); s++) {
			s2sFinalIndex[s] = new int[runS2SStubs.get(s).size()];
		}
		for (int r = 0; r < sourceOf.length; r++) {
			int src = sourceOf[r];
			if (src >= nD2DLayers) { // S2S row: src == nD2DLayers + s2sLayerIndex
				s2sFinalIndex[src - nD2DLayers][localRowOf[r]] = r;
			}
		}

		// Build S2SRideMaterializer (same args as in the export-pass construction)
		org.matsim.contrib.demand_extraction.algorithm.bamas.stub.S2SRideMaterializer s2sMat =
				new org.matsim.contrib.demand_extraction.algorithm.bamas.stub.S2SRideMaterializer(
						network, budgetValidator, runStopDictionary, exMasConfig);

		// Create adapters for HyperPoolGenerator
		StopCompatibilityChecker externalChecker = new StopCompatibilityChecker(exMasConfig);
		HyperPoolGenerator.StopCompatibilityChecker compatibilityChecker =
				(r1, r2) -> externalChecker.areCompatible(r1, r2);

		HyperPoolGenerator.StopRelocator stopRelocator = null;
		if (exMasConfig.getHyperPoolEnableStopRelocation()) {
			log.info("HyperPool (stub): Stop relocation enabled");
			stopRelocator = createHyperPoolStopRelocator();
		} else {
			log.info("HyperPool (stub): Stop relocation disabled");
		}

		org.matsim.contrib.demand_extraction.algorithm.generation.WalkBudgetProvider hyperPoolWalkBudgetProvider =
				budgetToConstraints == null ? null :
				(budget, req, tt, dist, delay) ->
						budgetToConstraints.budgetToMaxWalkDistance(budget, null, req, tt, dist, delay);

		HyperPoolGenerator generator = new HyperPoolGenerator(
				network, stopRelocator, compatibilityChecker, exMasConfig, budgetValidator,
				hyperPoolWalkBudgetProvider);

		List<HyperPooledRide> result = generator.generateFromStubs(
				runS2SStubs, s2sMat, runStopDictionary, runRequestById, network, s2sStartIndex,
				s2sFinalIndex);

		generator.logStatistics();
		return result;
	}

	/**
	 * Creates the {@link HyperPoolGenerator.StopRelocator} used by the stub-path
	 * {@link #generateHyperPooledRidesFromStubs}.
	 * Returns a Euclidean-distance-based relocator with budget-aware merge rejection.
	 */
	private HyperPoolGenerator.StopRelocator createHyperPoolStopRelocator() {
		return new HyperPoolGenerator.StopRelocator() {
			@Override
			public boolean areStopsNearby(
					org.matsim.contrib.demand_extraction.algorithm.domain.StopLocation stop1,
					org.matsim.contrib.demand_extraction.algorithm.domain.StopLocation stop2,
					double proximityMeters) {
				return org.matsim.core.utils.geometry.CoordUtils.calcEuclideanDistance(
						stop1.getCoord(), stop2.getCoord()) <= proximityMeters;
			}

			@Override
			public org.matsim.contrib.demand_extraction.algorithm.domain.StopLocation findMergedStop(
					org.matsim.contrib.demand_extraction.algorithm.domain.StopLocation stop,
					java.util.List<org.matsim.contrib.demand_extraction.algorithm.domain.StopLocation> existingStops,
					double proximityMeters,
					double[] maxRelocDistPerPax) {
				for (org.matsim.contrib.demand_extraction.algorithm.domain.StopLocation existing : existingStops) {
					if (areStopsNearby(stop, existing, proximityMeters)) {
						if (maxRelocDistPerPax != null) {
							double relocDist = calculateRelocationDistance(stop, existing);
							double minBudget = Double.MAX_VALUE;
							for (double b : maxRelocDistPerPax) {
								if (b < minBudget) minBudget = b;
							}
							if (relocDist > minBudget) {
								continue;
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
	}

	/**
	 * Degree-2 pair-stub dedup + distance-savings gate over a degree-2 {@link StubColumns}.
	 * Produces the deduped + gated degree-2 survivor layer that (a) feeds the extender as the
	 * degree-2 parents and (b) slots into {@link StubRideStore} between the fat singles and the
	 * degree-3 layer. (Task 13 introduced this as the stub mirror of the former fat
	 * pair-ride prune; the fat path has since been removed.)
	 *
	 * <h3>Internal parity contracts (dedup/gate determinism)</h3>
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
