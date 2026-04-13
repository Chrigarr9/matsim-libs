package org.matsim.contrib.demand_extraction.algorithm.extension;

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

	// ---- Hard-constraint pruning ----
	public long prunedByTravelTime;
	public long prunedByDropoffCheck;
	public long prunedByDelayWindowOrigin;
	public long prunedByDelayWindowDropoff;

	// ---- B&B distance-bound diagnostics ----
	public long bnbOriginCuts;              // origin-phase break events
	public long bnbOriginSkippedCandidates; // candidates skipped by origin cuts
	public long bnbDestCuts;                // dest-phase break events
	public long bnbDestSkippedCandidates;   // candidates skipped by dest cuts

	// ---- Evaluator outcome funnel ----
	public long rideNullFailures;     // buildRideFromOrdering returned null
	public long validButWorseThanBest; // valid ride, distance ≥ bestValidDist
	public long newBestRides;         // valid ride that tightened the bound

	// ---- Timing (nanos) ----
	public long timeTotal;
	public long timeEnumeration;
	public long timeRideConstruction;
	public long timeBudgetValidation;

	public static EnumerationStats get() { return THREAD_LOCAL.get(); }

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
		prunedByTravelTime = 0;
		prunedByDropoffCheck = 0;
		prunedByDelayWindowOrigin = 0;
		prunedByDelayWindowDropoff = 0;
		bnbOriginCuts = 0;
		bnbOriginSkippedCandidates = 0;
		bnbDestCuts = 0;
		bnbDestSkippedCandidates = 0;
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
			total.prunedByTravelTime += s.prunedByTravelTime;
			total.prunedByDropoffCheck += s.prunedByDropoffCheck;
			total.prunedByDelayWindowOrigin += s.prunedByDelayWindowOrigin;
			total.prunedByDelayWindowDropoff += s.prunedByDelayWindowDropoff;
			total.bnbOriginCuts += s.bnbOriginCuts;
			total.bnbOriginSkippedCandidates += s.bnbOriginSkippedCandidates;
			total.bnbDestCuts += s.bnbDestCuts;
			total.bnbDestSkippedCandidates += s.bnbDestSkippedCandidates;
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
