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

	// ---- Optional per-set probe sink (enabled by -Dbamas.orderingProbe=<csv path>) ----
	// Sizes a per-set ordering node budget: records, per high-degree set, how many
	// DFS nodes were entered in total, before the first valid ordering (feasibility
	// floor for any budget), and before the last bestValidDist improvement (quality
	// target). Inert unless the property is set; counters themselves are always live
	// (one long++ per node) so the same counter can later drive a runtime cap.
	private static final String PROBE_PATH = System.getProperty("bamas.orderingProbe");
	/** Minimum degree to record (the explosion lives at high degree; low degrees are noise). */
	private static final int PROBE_MIN_DEGREE = Integer.getInteger("bamas.orderingProbeMinDegree", 6);
	private static java.io.Writer probeWriter;

	static {
		if (PROBE_PATH != null) {
			Runtime.getRuntime().addShutdownHook(new Thread(EnumerationStats::probeClose));
		}
	}

	public static EnumerationStats get() { return THREAD_LOCAL.get(); }

	/** Reset the per-set probe scratch at the start of one set's enumeration. */
	public void probeSetStart() {
		curSetNodes = 0;
		curSetNodesFirstValid = -1;
		curSetNodesBest = -1;
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

	/** Emit one per-set record (no-op unless the probe is enabled and degree >= min). */
	public void probeSetEnd(int degree) {
		if (PROBE_PATH == null || degree < PROBE_MIN_DEGREE) return;
		writeProbeRow(degree, curSetNodes, curSetNodesFirstValid, curSetNodesBest);
	}

	private static synchronized void writeProbeRow(int degree, long nodes, long firstValid, long best) {
		try {
			if (probeWriter == null) {
				probeWriter = new java.io.BufferedWriter(new java.io.FileWriter(PROBE_PATH));
				probeWriter.write("degree,nodesTotal,nodesToFirstValid,nodesToBest\n");
			}
			probeWriter.write(degree + "," + nodes + "," + firstValid + "," + best + "\n");
			probeWriter.flush();
		} catch (java.io.IOException e) {
			// best-effort probe; ignore IO failures
		}
	}

	private static synchronized void probeClose() {
		if (probeWriter != null) {
			try { probeWriter.close(); } catch (java.io.IOException e) { /* ignore */ } finally { probeWriter = null; }
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
			total.timeTotal += s.timeTotal;
			total.timeEnumeration += s.timeEnumeration;
			total.timeRideConstruction += s.timeRideConstruction;
			total.timeBudgetValidation += s.timeBudgetValidation;
		}
		return total;
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
