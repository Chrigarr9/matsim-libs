package org.matsim.contrib.demand_extraction.algorithm.bamas.extension;

import java.util.Collection;

import org.apache.logging.log4j.Logger;

/**
 * Per-thread profiling counters for the ordering enumeration hot loop. Updated
 * lock-free via a {@link ThreadLocal}, then summed across threads between
 * degrees for logging.
 *
 * <p>Counters fall into four groups:
 * <ul>
 *   <li>Enumeration flow: sets processed, orderings reaching the evaluator,
 *       rides built, budget outcomes, set-level feasibility totals.</li>
 *   <li>Hard-constraint pruning sites: travel-time, dropoff check,
 *       delay-window (origin + dropoff phases).</li>
 *   <li>Distance branch-and-bound diagnostics: cut events and candidates
 *       skipped at origin + dest phases — used to tune the B&amp;B tightness.</li>
 *   <li>Evaluator outcome funnel: how each completed ordering ends up
 *       (ride-null, valid-but-worse, new-best).</li>
 * </ul>
 *
 * <p>All of the above are ALSO serialised per degree to
 * {@code <stats>/enumeration_stats.csv} via {@link #csvHeader()} / {@link #toCsvRow}
 * (see {@link EnumerationStatsCsvWriter}). The CSV column order is fixed and documented
 * on those two methods; keep them in lock-step when adding a counter.
 */
public final class EnumerationStats {
	private static final ThreadLocal<EnumerationStats> THREAD_LOCAL =
			ThreadLocal.withInitial(EnumerationStats::new);

	// ---- Enumeration flow ----
	public long setsProcessed;
	public long orderingsEvaluated;
	public long ridesBuilt;
	public long ridesPassedConstraints;
	public long budgetValidations;
	public long budgetPassed;
	public long segmentLookups;
	public long setsConstraintFeasible;
	public long setsBudgetFeasible;
	public long parentSeedRidesFound;

	// ---- Hard-constraint pruning ----
	public long prunedByTravelTime;
	public long prunedByDropoffCheck;
	public long prunedByDelayWindowOrigin;
	public long prunedByDelayWindowDropoff;

	// ---- B&B distance-bound diagnostics ----
	public long bnbOriginCuts;              // origin-phase break events
	public long bnbOriginSkippedCandidates; // candidates skipped by origin cuts
	public long bnbOriginLbCuts;            // LB-based outer cut events (origin DFS, depth >= 1)
	public long bnbOriginLbSkippedCandidates; // candidates skipped by origin LB cuts (future use)
	public long bnbDestCuts;                // dest-phase break events
	public long bnbDestSkippedCandidates;   // candidates skipped by dest cuts
	public long bnbDestLbCuts;             // LB-based outer cut events (dest DFS, all depths)
	public long bnbDestLbSkippedCandidates; // candidates skipped by dest LB cuts (future use)

	// ---- Evaluator outcome funnel ----
	public long rideNullFailures;     // buildRideFromOrdering returned null
	public long validButWorseThanBest; // valid ride, distance ≥ bestValidDist
	public long newBestRides;         // valid ride that tightened the bound

	// ---- Ordering-budget cap diagnostics ----
	// Number of sets whose post-first-valid ordering node budget was exhausted (i.e. where
	// orderingBudgetExhausted() caused the DFS to unwind). Counted at most once per set via the
	// transient curSetBudgetHit flag below. Documents how often the
	// --max-ordering-nodes-after-first-valid cap actually bit at this degree.
	public long orderingBudgetHits;

	// ---- Timing (nanos) ----
	public long timeTotal;
	public long timeEnumeration;
	public long timeRideConstruction;
	public long timeBudgetValidation;

	// ---- Per-set ordering-budget probe (transient scratch, reset per set) ----
	/** DFS nodes entered while processing the current set (origin + dest recursion calls). */
	public long curSetNodes;
	/** curSetNodes value when the first budget-valid ordering was accepted (-1 = none yet). */
	public long curSetNodesFirstValid = -1;
	/** curSetNodes value at the last bestValidDist improvement (-1 = none). */
	public long curSetNodesBest = -1;
	/** Transient: true once the current set has been counted in {@link #orderingBudgetHits}. */
	private boolean curSetBudgetHit;

	// ---- Insertion-first pass mode (transient per-thread flag, set per seeded pass) ----
	// True while the seeded DFS is running the pure-insertion pass (rank-0/parent-consistent
	// branches only); false for the full-search pass. Set explicitly by OrderingEnumerator before
	// each pass, not summed or cleared — it is a mode flag, not a counter.
	public boolean insertionOnly;

	// ---- Per-set ordering node budget (Design A; 0 = disabled) ----
	// Caps the per-set DFS *after* the first budget-valid ordering is found. The
	// descend-to-first-valid floor is unconditional (the predicate requires
	// curSetNodesFirstValid >= 0), so every feasible set still yields a ride and
	// only the post-first-valid tail is bounded. Global config constant for the
	// whole run, set once before the worker pool starts (write happens-before the
	// worker threads), then read-only on the lock-free per-thread counters above.
	private static volatile long maxOrderingNodesAfterFirstValid = 0;

	/** Set the per-set ordering node budget B (0 = disabled). Negative values clamp to 0. */
	public static void setMaxOrderingNodesAfterFirstValid(long b) {
		maxOrderingNodesAfterFirstValid = Math.max(0L, b);
	}

	/** Current per-set ordering node budget B (0 = disabled). */
	public static long getMaxOrderingNodesAfterFirstValid() {
		return maxOrderingNodesAfterFirstValid;
	}

	/**
	 * True once the current set has spent its post-first-valid node budget. Always
	 * false when B is disabled or before the first valid ordering exists (so the
	 * descend-to-first-valid guard is unconditional). Monotone within a set: once
	 * true it stays true, so checking it at each DFS node entry unwinds the rest of
	 * the set cheaply, leaving the best ride found in [firstValid, firstValid + B].
	 */
	public boolean orderingBudgetExhausted() {
		long b = maxOrderingNodesAfterFirstValid;
		return b > 0 && curSetNodesFirstValid >= 0
				&& (curSetNodes - curSetNodesFirstValid) > b;
	}

	/**
	 * Same predicate as {@link #orderingBudgetExhausted()} but records the FIRST time it fires for
	 * the current set into {@link #orderingBudgetHits}. A set that trips the cap across many DFS
	 * node entries — and across both the origin and dest recursions — is still counted exactly once,
	 * because {@code curSetBudgetHit} is reset per set in {@link #probeSetStart()}. Call this at the
	 * DFS node-entry cap check. OFF-path behaviour is unchanged (returns false when the budget is
	 * disabled or no valid ordering exists yet).
	 */
	public boolean orderingBudgetExhaustedTracked() {
		if (orderingBudgetExhausted()) {
			if (!curSetBudgetHit) {
				curSetBudgetHit = true;
				orderingBudgetHits++;
			}
			return true;
		}
		return false;
	}

	// ---- Optional per-set probe sink (enabled by -Dbamas.orderingProbe=<csv> or --ordering-probe-dir) ----
	// Sizes a per-set ordering node budget: records, per high-degree set, how many
	// DFS nodes were entered in total, before the first valid ordering (feasibility
	// floor for any budget), and before the last bestValidDist improvement (quality
	// target). Inert unless a sink path is set; counters themselves are always live
	// (one long++ per node) so the same counter can later drive a runtime cap.
	//
	// probePath defaults from the JVM system property (back-compat with
	// -Dbamas.orderingProbe=<csv>) but can be redirected at runtime via setProbePath — wired from
	// the CLI --ordering-probe-dir before the algorithm runs. Volatile so the main-thread write
	// happens-before the worker reads during enumeration.
	private static volatile String probePath = System.getProperty("bamas.orderingProbe");
	/** Minimum degree to record (the explosion lives at high degree; low degrees are noise). */
	private static volatile int probeMinDegree = Integer.getInteger("bamas.orderingProbeMinDegree", 6);
	private static boolean probeShutdownHookRegistered;

	// Per-degree accumulator (one bucket per degree, aggregated across all sets of that degree).
	// We only need the AVERAGE ordering effort per degree for the scaling study, not a row per
	// coalition — at 100% the per-set stream is millions of rows and a synchronized flush per row.
	// So each set folds into a fixed-width running total and we emit one averaged row per degree at
	// close. Guarded by PROBE_LOCK; contention is arithmetic-only (no IO on the hot path).
	// Layout per bucket: [0]=sets, [1]=validSets, [2]=sumNodesTotal, [3]=sumFirstValid,
	//                     [4]=sumBest, [5]=maxNodesTotal, [6]=maxFirstValid, [7]=maxBest
	private static final Object PROBE_LOCK = new Object();
	private static final java.util.TreeMap<Integer, long[]> probeByDegree = new java.util.TreeMap<>();
	private static boolean probeSummaryWritten;

	static {
		if (probePath != null) {
			registerProbeShutdownHook();
		}
	}

	private static synchronized void registerProbeShutdownHook() {
		if (!probeShutdownHookRegistered) {
			Runtime.getRuntime().addShutdownHook(new Thread(EnumerationStats::probeClose));
			probeShutdownHookRegistered = true;
		}
	}

	/**
	 * Enable (or redirect) the per-set ordering probe sink at runtime. Wired from the
	 * {@code --ordering-probe-dir} CLI flag before the algorithm runs, so it does not rely on the
	 * class-load-time system property. {@code null} disables the probe. Registers the flush/close
	 * shutdown hook once.
	 */
	public static synchronized void setProbePath(String path) {
		probePath = path;
		synchronized (PROBE_LOCK) {
			probeByDegree.clear();
			probeSummaryWritten = false;
		}
		if (path != null) {
			registerProbeShutdownHook();
		}
	}

	/** The active per-set probe sink path, or {@code null} when the probe is disabled. */
	public static String getProbePath() { return probePath; }

	/** Override the minimum degree recorded by the per-set probe (default {@code 6}). */
	public static void setProbeMinDegree(int minDegree) { probeMinDegree = minDegree; }

	/** The minimum degree recorded by the per-set probe. */
	public static int getProbeMinDegree() { return probeMinDegree; }

	public static EnumerationStats get() { return THREAD_LOCAL.get(); }

	/** Reset the per-set probe scratch at the start of one set's enumeration. */
	public void probeSetStart() {
		curSetNodes = 0;
		curSetNodesFirstValid = -1;
		curSetNodesBest = -1;
		curSetBudgetHit = false;
	}

	/** Record one DFS node entry for the current set. */
	public void probeNode() { curSetNodes++; }

	/**
	 * Observe a just-completed accept: if bestValidDist improved, mark first-valid
	 * (once) and update last-improvement node position. The first valid ordering
	 * always improves the bound (initial bound = maxRideDistance), so
	 * curSetNodesFirstValid is exactly the node index of the first valid ordering.
	 */
	public void probeAccept(double bestBefore, double bestAfter) {
		if (bestAfter < bestBefore) {
			if (curSetNodesFirstValid < 0) curSetNodesFirstValid = curSetNodes;
			curSetNodesBest = curSetNodes;
		}
	}

	/** Fold this set into its per-degree bucket (no-op unless the probe is enabled and degree >= min). */
	public void probeSetEnd(int degree) {
		if (probePath == null || degree < probeMinDegree) return;
		accumulateSet(degree, curSetNodes, curSetNodesFirstValid, curSetNodesBest);
	}

	/**
	 * Fold one finished set into its degree bucket. {@code firstValid}/{@code best} are -1 when the
	 * set produced no budget-valid ordering; those are counted in {@code sets} but excluded from the
	 * first-valid/best sums (and their {@code validSets} denominator) so the averages measure effort
	 * only over sets that actually yielded a ride.
	 */
	private static void accumulateSet(int degree, long nodes, long firstValid, long best) {
		synchronized (PROBE_LOCK) {
			long[] b = probeByDegree.computeIfAbsent(degree, d -> new long[8]);
			b[0]++;                                   // sets
			b[2] += nodes;                            // sumNodesTotal
			if (nodes > b[5]) b[5] = nodes;           // maxNodesTotal
			if (firstValid >= 0) {
				b[1]++;                               // validSets
				b[3] += firstValid;                   // sumFirstValid
				if (firstValid > b[6]) b[6] = firstValid; // maxFirstValid
				if (best >= 0) {
					b[4] += best;                     // sumBest
					if (best > b[7]) b[7] = best;     // maxBest
				}
			}
		}
	}

	/**
	 * Emit one averaged row per degree. Safe to call more than once (the shutdown hook and an
	 * explicit end-of-run call both target it) — the {@code probeSummaryWritten} guard writes exactly
	 * once. Public so the Phase-2 runner can flush the summary at PHASE 2 COMPLETE, not only at JVM
	 * exit, so a reused JVM or an abnormal teardown still leaves the file on disk.
	 */
	public static void writeProbeSummary() { probeClose(); }

	/** Emit one averaged row per degree. Guarded to write exactly once. */
	private static void probeClose() {
		synchronized (PROBE_LOCK) {
			if (probeSummaryWritten || probePath == null || probeByDegree.isEmpty()) return;
			probeSummaryWritten = true;
			try {
				java.io.File f = new java.io.File(probePath);
				if (f.getParentFile() != null) f.getParentFile().mkdirs();
				try (java.io.Writer w = new java.io.BufferedWriter(new java.io.FileWriter(f))) {
					w.write("degree,sets,validSets,meanNodesTotal,meanNodesToFirstValid,meanNodesToBest,"
							+ "maxNodesTotal,maxNodesToFirstValid,maxNodesToBest\n");
					for (java.util.Map.Entry<Integer, long[]> e : probeByDegree.entrySet()) {
						long[] b = e.getValue();
						long sets = b[0], valid = b[1];
						double meanTotal = sets > 0 ? (double) b[2] / sets : 0.0;
						double meanFirst = valid > 0 ? (double) b[3] / valid : -1.0;
						double meanBest = valid > 0 ? (double) b[4] / valid : -1.0;
						w.write(e.getKey() + "," + sets + "," + valid + ","
								+ meanTotal + "," + meanFirst + "," + meanBest + ","
								+ b[5] + "," + b[6] + "," + b[7] + "\n");
					}
				}
			} catch (java.io.IOException ex) {
				// best-effort probe; ignore IO failures
			}
		}
	}

	public static void reset() { THREAD_LOCAL.set(new EnumerationStats()); }

	/** Zero all counters on this instance in place. Safe to call from any thread. */
	public void clear() {
		setsProcessed = 0;
		orderingsEvaluated = 0;
		ridesBuilt = 0;
		ridesPassedConstraints = 0;
		budgetValidations = 0;
		budgetPassed = 0;
		segmentLookups = 0;
		setsConstraintFeasible = 0;
		setsBudgetFeasible = 0;
		parentSeedRidesFound = 0;
		prunedByTravelTime = 0;
		prunedByDropoffCheck = 0;
		prunedByDelayWindowOrigin = 0;
		prunedByDelayWindowDropoff = 0;
		bnbOriginCuts = 0;
		bnbOriginSkippedCandidates = 0;
		bnbOriginLbCuts = 0;
		bnbOriginLbSkippedCandidates = 0;
		bnbDestCuts = 0;
		bnbDestSkippedCandidates = 0;
		bnbDestLbCuts = 0;
		bnbDestLbSkippedCandidates = 0;
		rideNullFailures = 0;
		validButWorseThanBest = 0;
		newBestRides = 0;
		orderingBudgetHits = 0;
		curSetBudgetHit = false;
		timeTotal = 0;
		timeEnumeration = 0;
		timeRideConstruction = 0;
		timeBudgetValidation = 0;
	}

	public static EnumerationStats sum(Collection<EnumerationStats> perThread) {
		EnumerationStats total = new EnumerationStats();
		for (EnumerationStats s : perThread) {
			total.setsProcessed += s.setsProcessed;
			total.orderingsEvaluated += s.orderingsEvaluated;
			total.ridesBuilt += s.ridesBuilt;
			total.ridesPassedConstraints += s.ridesPassedConstraints;
			total.budgetValidations += s.budgetValidations;
			total.budgetPassed += s.budgetPassed;
			total.segmentLookups += s.segmentLookups;
			total.setsConstraintFeasible += s.setsConstraintFeasible;
			total.setsBudgetFeasible += s.setsBudgetFeasible;
			total.parentSeedRidesFound += s.parentSeedRidesFound;
			total.prunedByTravelTime += s.prunedByTravelTime;
			total.prunedByDropoffCheck += s.prunedByDropoffCheck;
			total.prunedByDelayWindowOrigin += s.prunedByDelayWindowOrigin;
			total.prunedByDelayWindowDropoff += s.prunedByDelayWindowDropoff;
			total.bnbOriginCuts += s.bnbOriginCuts;
			total.bnbOriginSkippedCandidates += s.bnbOriginSkippedCandidates;
			total.bnbOriginLbCuts += s.bnbOriginLbCuts;
			total.bnbOriginLbSkippedCandidates += s.bnbOriginLbSkippedCandidates;
			total.bnbDestCuts += s.bnbDestCuts;
			total.bnbDestSkippedCandidates += s.bnbDestSkippedCandidates;
			total.bnbDestLbCuts += s.bnbDestLbCuts;
			total.bnbDestLbSkippedCandidates += s.bnbDestLbSkippedCandidates;
			total.rideNullFailures += s.rideNullFailures;
			total.validButWorseThanBest += s.validButWorseThanBest;
			total.newBestRides += s.newBestRides;
			total.orderingBudgetHits += s.orderingBudgetHits;
			total.timeTotal += s.timeTotal;
			total.timeEnumeration += s.timeEnumeration;
			total.timeRideConstruction += s.timeRideConstruction;
			total.timeBudgetValidation += s.timeBudgetValidation;
		}
		return total;
	}

	// ---- CSV persistence (analytics; see EnumerationStatsCsvWriter) ----
	// The column order below is FIXED and must stay in lock-step between csvHeader() and toCsvRow().
	// Leading columns are degree-level quantities the extender supplies at the degree boundary
	// (not per-thread counters); the rest mirror the counters aggregated by sum(). Timing columns
	// carry raw nanoseconds (suffix "Ns"); log() converts them to ms for humans, the CSV stays
	// lossless. wallClockMs is the extender's degree wall time; heapUsedBytes is a used-heap sample
	// (totalMemory-freeMemory) taken at the degree boundary.

	/** Fixed CSV header (no trailing newline). Column order matches {@link #toCsvRow}. */
	public static String csvHeader() {
		return String.join(",",
				// degree-level (supplied by the extender)
				"degree", "threads", "ridesEmitted", "wallClockMs", "heapUsedBytes",
				// enumeration flow
				"setsProcessed", "orderingsEvaluated", "ridesBuilt", "ridesPassedConstraints",
				"budgetValidations", "budgetPassed", "segmentLookups",
				"setsConstraintFeasible", "setsBudgetFeasible", "parentSeedRidesFound",
				// hard-constraint pruning
				"prunedByTravelTime", "prunedByDropoffCheck",
				"prunedByDelayWindowOrigin", "prunedByDelayWindowDropoff",
				// B&B distance-bound
				"bnbOriginCuts", "bnbOriginSkippedCandidates", "bnbOriginLbCuts", "bnbOriginLbSkippedCandidates",
				"bnbDestCuts", "bnbDestSkippedCandidates", "bnbDestLbCuts", "bnbDestLbSkippedCandidates",
				// evaluator funnel
				"rideNullFailures", "validButWorseThanBest", "newBestRides",
				// ordering-budget cap
				"orderingBudgetHits",
				// timing (raw nanos)
				"timeTotalNs", "timeEnumerationNs", "timeRideConstructionNs", "timeBudgetValidationNs");
	}

	/**
	 * One CSV data row for this (aggregated) stats instance, in {@link #csvHeader()} column order and
	 * with no trailing newline. The five leading extras are degree-level quantities the extender
	 * knows at the degree boundary rather than per-thread counters:
	 *
	 * @param degree        the degree these stats were produced for (targetDegree)
	 * @param threads       worker parallelism used for the degree
	 * @param ridesEmitted  rides actually kept/emitted for this degree (the corpus histogram bin)
	 * @param wallClockMs   wall-clock milliseconds spent on this degree's extension
	 * @param heapUsedBytes used heap (totalMemory-freeMemory) sampled at the degree boundary
	 */
	public String toCsvRow(int degree, int threads, long ridesEmitted, long wallClockMs, long heapUsedBytes) {
		StringBuilder sb = new StringBuilder(256);
		sb.append(degree).append(',')
				.append(threads).append(',')
				.append(ridesEmitted).append(',')
				.append(wallClockMs).append(',')
				.append(heapUsedBytes).append(',')
				.append(setsProcessed).append(',')
				.append(orderingsEvaluated).append(',')
				.append(ridesBuilt).append(',')
				.append(ridesPassedConstraints).append(',')
				.append(budgetValidations).append(',')
				.append(budgetPassed).append(',')
				.append(segmentLookups).append(',')
				.append(setsConstraintFeasible).append(',')
				.append(setsBudgetFeasible).append(',')
				.append(parentSeedRidesFound).append(',')
				.append(prunedByTravelTime).append(',')
				.append(prunedByDropoffCheck).append(',')
				.append(prunedByDelayWindowOrigin).append(',')
				.append(prunedByDelayWindowDropoff).append(',')
				.append(bnbOriginCuts).append(',')
				.append(bnbOriginSkippedCandidates).append(',')
				.append(bnbOriginLbCuts).append(',')
				.append(bnbOriginLbSkippedCandidates).append(',')
				.append(bnbDestCuts).append(',')
				.append(bnbDestSkippedCandidates).append(',')
				.append(bnbDestLbCuts).append(',')
				.append(bnbDestLbSkippedCandidates).append(',')
				.append(rideNullFailures).append(',')
				.append(validButWorseThanBest).append(',')
				.append(newBestRides).append(',')
				.append(orderingBudgetHits).append(',')
				.append(timeTotal).append(',')
				.append(timeEnumeration).append(',')
				.append(timeRideConstruction).append(',')
				.append(timeBudgetValidation);
		return sb.toString();
	}

	public void log(Logger log, int degree, int threads) {
		log.info("=== Enumeration Profile (degree {}) ===", degree);
		log.info("  Sets processed: {}", setsProcessed);
		log.info("  Orderings evaluated: {} ({} per set)", orderingsEvaluated,
				setsProcessed > 0 ? String.format("%.1f", (double) orderingsEvaluated / setsProcessed) : "N/A");
		log.info("  Rides built: {} ({} per set)", ridesBuilt,
				setsProcessed > 0 ? String.format("%.1f", (double) ridesBuilt / setsProcessed) : "N/A");
		log.info("  Rides passed constraints: {} ({}% of built)", ridesPassedConstraints,
				ridesBuilt > 0 ? String.format("%.1f", 100.0 * ridesPassedConstraints / ridesBuilt) : "N/A");
		log.info("  Budget validations: {}, passed: {} ({}%)", budgetValidations, budgetPassed,
				budgetValidations > 0 ? String.format("%.1f", 100.0 * budgetPassed / budgetValidations) : "N/A");
		log.info("  Segment lookups: {} ({} per set)", segmentLookups,
				setsProcessed > 0 ? String.format("%.0f", (double) segmentLookups / setsProcessed) : "N/A");
		log.info("  Pruned by travel time: {} ({} per set)", prunedByTravelTime,
				setsProcessed > 0 ? String.format("%.1f", (double) prunedByTravelTime / setsProcessed) : "N/A");
		log.info("  Pruned by dropoff check: {} ({} per set)", prunedByDropoffCheck,
				setsProcessed > 0 ? String.format("%.1f", (double) prunedByDropoffCheck / setsProcessed) : "N/A");
		log.info("  Pruned by delay-window (origin): {} ({} per set)", prunedByDelayWindowOrigin,
				setsProcessed > 0 ? String.format("%.1f", (double) prunedByDelayWindowOrigin / setsProcessed) : "N/A");
		log.info("  Pruned by delay-window (dropoff): {} ({} per set)", prunedByDelayWindowDropoff,
				setsProcessed > 0 ? String.format("%.1f", (double) prunedByDelayWindowDropoff / setsProcessed) : "N/A");
		// B&B diagnostic block
		log.info("  === B&B distance-bound ===");
		log.info("  Origin B&B cuts: {} events, {} candidates skipped ({} skipped/cut)",
				bnbOriginCuts, bnbOriginSkippedCandidates,
				bnbOriginCuts > 0 ? String.format("%.2f", (double) bnbOriginSkippedCandidates / bnbOriginCuts) : "N/A");
		log.info("  LB B&B cuts (origin): {} events, {} candidates skipped",
				bnbOriginLbCuts, bnbOriginLbSkippedCandidates);
		log.info("  LB B&B cuts (dest):   {} events, {} candidates skipped",
				bnbDestLbCuts, bnbDestLbSkippedCandidates);
		log.info("  Dest B&B cuts:   {} events, {} candidates skipped ({} skipped/cut)",
				bnbDestCuts, bnbDestSkippedCandidates,
				bnbDestCuts > 0 ? String.format("%.2f", (double) bnbDestSkippedCandidates / bnbDestCuts) : "N/A");
		// Evaluator outcome funnel
		log.info("  === Ordering outcomes at evaluator ===");
		long totalEvalOutcomes = rideNullFailures + validButWorseThanBest + newBestRides;
		log.info("  Evaluated orderings (sanity): {} (should equal {})", totalEvalOutcomes, orderingsEvaluated);
		log.info("    ride-null (constraint):    {} ({}%)", rideNullFailures,
				totalEvalOutcomes > 0 ? String.format("%.1f", 100.0 * rideNullFailures / totalEvalOutcomes) : "N/A");
		log.info("    valid-but-worse:           {} ({}%)", validButWorseThanBest,
				totalEvalOutcomes > 0 ? String.format("%.1f", 100.0 * validButWorseThanBest / totalEvalOutcomes) : "N/A");
		log.info("    new-best (tightened):      {} ({}%)", newBestRides,
				totalEvalOutcomes > 0 ? String.format("%.1f", 100.0 * newBestRides / totalEvalOutcomes) : "N/A");
		log.info("  Ordering-budget cap hits: {} ({}% of processed)", orderingBudgetHits,
				setsProcessed > 0 ? String.format("%.1f", 100.0 * orderingBudgetHits / setsProcessed) : "N/A");
		log.info("  Sets constraint-feasible: {} ({}% of processed)", setsConstraintFeasible,
				setsProcessed > 0 ? String.format("%.1f", 100.0 * setsConstraintFeasible / setsProcessed) : "N/A");
		log.info("  Sets budget-feasible: {} ({}% of processed)", setsBudgetFeasible,
				setsProcessed > 0 ? String.format("%.1f", 100.0 * setsBudgetFeasible / setsProcessed) : "N/A");
		log.info("  Parent seed rides found: {} ({}% of processed)", parentSeedRidesFound,
				setsProcessed > 0 ? String.format("%.1f", 100.0 * parentSeedRidesFound / setsProcessed) : "N/A");
		// timeEnumeration includes ride construction + budget validation (evaluator runs inside)
		long timePureEnum = timeEnumeration - timeRideConstruction - timeBudgetValidation;
		long timeOther = timeTotal - timeEnumeration;
		log.info("  Time breakdown (CPU-ms across {} threads):", threads);
		log.info("    Total:              {}ms ({}ms/set)",
				fmt(timeTotal), fmtPerSet(timeTotal));
		log.info("    Pure enumeration:   {}ms ({}ms/set) ({}%)",
				fmt(timePureEnum), fmtPerSet(timePureEnum), pct(timePureEnum, timeTotal));
		log.info("    Ride construction:  {}ms ({}ms/set) ({}%)",
				fmt(timeRideConstruction), fmtPerSet(timeRideConstruction), pct(timeRideConstruction, timeTotal));
		log.info("    Budget validation:  {}ms ({}ms/set) ({}%)",
				fmt(timeBudgetValidation), fmtPerSet(timeBudgetValidation), pct(timeBudgetValidation, timeTotal));
		log.info("    Other (setup/dedup): {}ms ({}ms/set) ({}%)",
				fmt(timeOther), fmtPerSet(timeOther), pct(timeOther, timeTotal));
	}

	private String fmt(long nanos) {
		return String.format("%.0f", nanos / 1_000_000.0);
	}

	private String fmtPerSet(long nanos) {
		return setsProcessed > 0 ? String.format("%.3f", nanos / 1_000_000.0 / setsProcessed) : "N/A";
	}

	private String pct(long part, long total) {
		return total > 0 ? String.format("%.1f", 100.0 * part / total) : "N/A";
	}
}
