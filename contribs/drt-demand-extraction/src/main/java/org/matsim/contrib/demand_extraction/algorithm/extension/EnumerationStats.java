package org.matsim.contrib.demand_extraction.algorithm.extension;

import java.util.Collection;

import org.apache.logging.log4j.Logger;

/**
 * Lightweight per-thread profiling counters for ordering enumeration.
 * Accumulated via ThreadLocal, summed after parallel processing completes.
 */
public final class EnumerationStats {
	private static final ThreadLocal<EnumerationStats> THREAD_LOCAL =
			ThreadLocal.withInitial(EnumerationStats::new);

	// Counters
	public long setsProcessed;
	public long orderingsEvaluated;
	public long ridesBuilt;
	public long ridesPassedConstraints;
	public long budgetValidations;
	public long budgetPassed;
	public long segmentLookups;
	public long prunedByTravelTime;
	// Per-set feasibility counters (for degree-specific graph analysis)
	public long setsConstraintFeasible;  // sets where ≥1 ordering passed constraint checks
	public long setsBudgetFeasible;      // sets where ≥1 ordering also passed budget
	// Sub-set feasibility histogram: index = number of feasible sub-sets, value = candidate count
	// E.g. subsetFeasibilityHisto[3] = N means N candidates had exactly 3 feasible sub-sets
	public long[] subsetFeasibilityHisto = new long[32]; // up to degree 31

	// Timing (nanos)
	public long timeTotal;
	public long timeEnumeration;
	public long timeRideConstruction;
	public long timeBudgetValidation;

	public static EnumerationStats get() { return THREAD_LOCAL.get(); }

	public static void reset() { THREAD_LOCAL.set(new EnumerationStats()); }

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
			total.prunedByTravelTime += s.prunedByTravelTime;
			total.setsConstraintFeasible += s.setsConstraintFeasible;
			total.setsBudgetFeasible += s.setsBudgetFeasible;
			for (int i = 0; i < total.subsetFeasibilityHisto.length; i++) {
				total.subsetFeasibilityHisto[i] += s.subsetFeasibilityHisto[i];
			}
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
		log.info("  Sets constraint-feasible: {} ({}% of processed)", setsConstraintFeasible,
				setsProcessed > 0 ? String.format("%.1f", 100.0 * setsConstraintFeasible / setsProcessed) : "N/A");
		log.info("  Sets budget-feasible: {} ({}% of processed)", setsBudgetFeasible,
				setsProcessed > 0 ? String.format("%.1f", 100.0 * setsBudgetFeasible / setsProcessed) : "N/A");
		// Sub-set feasibility histogram
		StringBuilder histo = new StringBuilder();
		for (int i = 0; i < subsetFeasibilityHisto.length; i++) {
			if (subsetFeasibilityHisto[i] > 0) {
				if (histo.length() > 0) histo.append(", ");
				histo.append(i).append("=").append(subsetFeasibilityHisto[i]);
			}
		}
		if (histo.length() > 0) {
			log.info("  Sub-set feasibility histogram (feasible_sub_count=candidates): {}", histo);
			// Compute graph candidate count: candidates with ALL sub-sets feasible
			long allFeasible = subsetFeasibilityHisto[degree];  // degree = target degree, need all (degree-1 choose degree-2) = degree-1 sub-sets... actually index = degree
			long totalCandidates = 0;
			for (long v : subsetFeasibilityHisto) totalCandidates += v;
			if (totalCandidates > 0) {
				log.info("  Graph would keep: {} / {} candidates ({}% reduction)",
						allFeasible, totalCandidates,
						String.format("%.1f", 100.0 * (1.0 - (double) allFeasible / totalCandidates)));
			}
		}
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
