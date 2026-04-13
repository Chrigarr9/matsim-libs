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
	public long prunedByDropoffCheck;
	public long prunedBySubsetLookup;
	public long prunedByForbidden;
	public long allDestFailRecorded;
	// B&B distance-bound diagnostic counters (2026-04-13)
	public long bnbOriginCuts;              // times origin-phase break fired on partialDist>bound
	public long bnbOriginSkippedCandidates; // candidates skipped by those breaks (depth-agnostic)
	public long bnbDestCuts;                // times dest-phase break fired
	public long bnbDestSkippedCandidates;   // candidates skipped by those breaks
	// Ordering outcome at evaluator (post-enumeration)
	public long rideNullFailures;           // buildRideFromOrdering returned null (constraint viol)
	public long budgetFailures;             // budget validation rejected
	public long validButWorseThanBest;      // valid ride dist >= current bestValidDist
	public long newBestRides;               // valid ride that tightened the bound
	// Delay-window incremental feasibility check (2026-04-13)
	public long prunedByDelayWindowOrigin;  // origin-phase intersection went empty
	public long prunedByDelayWindowDropoff; // dropoff-phase intersection went empty
	// DegreeGraph consensus tightening (tightenConstraints)
	public long tightenedPairDirections;    // pair-direction eliminations by prev-degree consensus
	public long setsWithTightenings;        // sets where ≥1 pair was tightened
	// tightenDAG from SubSetOrderingFeasibility (triple/quad/quint levels)
	public long tightenDAGEdgesAdded;       // sum over sets of edges added by tightenDAG (all levels)
	public long tightenDAGSetsAffected;     // sets where tightenDAG (any level) added ≥1 edge
	public long tightenDAGEdges3;           // edges added by triple-level tightenDAG
	public long tightenDAGEdges4;           // edges added by quad-level tightenDAG (marginal over triples)
	public long tightenDAGEdges5;           // edges added by quint-level tightenDAG (marginal over tri+quad)
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
			total.prunedByDropoffCheck += s.prunedByDropoffCheck;
			total.prunedBySubsetLookup += s.prunedBySubsetLookup;
			total.prunedByForbidden += s.prunedByForbidden;
			total.allDestFailRecorded += s.allDestFailRecorded;
			total.bnbOriginCuts += s.bnbOriginCuts;
			total.bnbOriginSkippedCandidates += s.bnbOriginSkippedCandidates;
			total.bnbDestCuts += s.bnbDestCuts;
			total.bnbDestSkippedCandidates += s.bnbDestSkippedCandidates;
			total.rideNullFailures += s.rideNullFailures;
			total.budgetFailures += s.budgetFailures;
			total.validButWorseThanBest += s.validButWorseThanBest;
			total.newBestRides += s.newBestRides;
			total.prunedByDelayWindowOrigin += s.prunedByDelayWindowOrigin;
			total.prunedByDelayWindowDropoff += s.prunedByDelayWindowDropoff;
			total.tightenedPairDirections += s.tightenedPairDirections;
			total.setsWithTightenings += s.setsWithTightenings;
			total.tightenDAGEdgesAdded += s.tightenDAGEdgesAdded;
			total.tightenDAGSetsAffected += s.tightenDAGSetsAffected;
			total.tightenDAGEdges3 += s.tightenDAGEdges3;
			total.tightenDAGEdges4 += s.tightenDAGEdges4;
			total.tightenDAGEdges5 += s.tightenDAGEdges5;
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
		log.info("  Pruned by dropoff check: {} ({} per set)", prunedByDropoffCheck,
				setsProcessed > 0 ? String.format("%.1f", (double) prunedByDropoffCheck / setsProcessed) : "N/A");
		log.info("  Pruned by sub-set lookup: {} ({} per set)", prunedBySubsetLookup,
				setsProcessed > 0 ? String.format("%.1f", (double) prunedBySubsetLookup / setsProcessed) : "N/A");
		log.info("  Pruned by forbidden prefix: {} ({} per set)", prunedByForbidden,
				setsProcessed > 0 ? String.format("%.1f", (double) prunedByForbidden / setsProcessed) : "N/A");
		log.info("  All-dest-fail recorded: {}", allDestFailRecorded);
		// Delay-window feasibility (new pre-filter)
		log.info("  Pruned by delay-window (origin): {} ({} per set)", prunedByDelayWindowOrigin,
				setsProcessed > 0 ? String.format("%.1f", (double) prunedByDelayWindowOrigin / setsProcessed) : "N/A");
		log.info("  Pruned by delay-window (dropoff): {} ({} per set)", prunedByDelayWindowDropoff,
				setsProcessed > 0 ? String.format("%.1f", (double) prunedByDelayWindowDropoff / setsProcessed) : "N/A");
		// Ordering-conflict tightening via DegreeGraph consensus
		log.info("  Pair-directions tightened (prev-deg consensus): {} across {} sets ({}% of sets tightened)",
				tightenedPairDirections, setsWithTightenings,
				setsProcessed > 0 ? String.format("%.1f", 100.0 * setsWithTightenings / setsProcessed) : "N/A");
		// tightenDAG via SubSetOrderingFeasibility
		log.info("  tightenDAG edges added: {} across {} sets ({}% of sets affected)",
				tightenDAGEdgesAdded, tightenDAGSetsAffected,
				setsProcessed > 0 ? String.format("%.1f", 100.0 * tightenDAGSetsAffected / setsProcessed) : "N/A");
		log.info("    Per level: triples={}, quads={}, quints={}",
				tightenDAGEdges3, tightenDAGEdges4, tightenDAGEdges5);
		// B&B diagnostic block
		log.info("  === B&B distance-bound ===");
		log.info("  Origin B&B cuts: {} events, {} candidates skipped ({} skipped/cut)",
				bnbOriginCuts, bnbOriginSkippedCandidates,
				bnbOriginCuts > 0 ? String.format("%.2f", (double) bnbOriginSkippedCandidates / bnbOriginCuts) : "N/A");
		log.info("  Dest B&B cuts:   {} events, {} candidates skipped ({} skipped/cut)",
				bnbDestCuts, bnbDestSkippedCandidates,
				bnbDestCuts > 0 ? String.format("%.2f", (double) bnbDestSkippedCandidates / bnbDestCuts) : "N/A");
		// Ordering outcome funnel
		log.info("  === Ordering outcomes at evaluator ===");
		long totalEvalOutcomes = rideNullFailures + budgetFailures + validButWorseThanBest + newBestRides;
		log.info("  Evaluated orderings (sanity): {} (should equal {})", totalEvalOutcomes, orderingsEvaluated);
		log.info("    ride-null (constraint):    {} ({}%)", rideNullFailures,
				totalEvalOutcomes > 0 ? String.format("%.1f", 100.0 * rideNullFailures / totalEvalOutcomes) : "N/A");
		log.info("    budget-fail:               {} ({}%)", budgetFailures,
				totalEvalOutcomes > 0 ? String.format("%.1f", 100.0 * budgetFailures / totalEvalOutcomes) : "N/A");
		log.info("    valid-but-worse:           {} ({}%)", validButWorseThanBest,
				totalEvalOutcomes > 0 ? String.format("%.1f", 100.0 * validButWorseThanBest / totalEvalOutcomes) : "N/A");
		log.info("    new-best (tightened):      {} ({}%)", newBestRides,
				totalEvalOutcomes > 0 ? String.format("%.1f", 100.0 * newBestRides / totalEvalOutcomes) : "N/A");
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
