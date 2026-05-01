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
import org.matsim.contrib.demand_extraction.algorithm.bamas.graph.DegreeGraph;
import org.matsim.contrib.demand_extraction.algorithm.engine.PostExtensionPruner;
import org.matsim.contrib.demand_extraction.algorithm.generation.PairGenerator;
import org.matsim.contrib.demand_extraction.algorithm.bamas.generation.BamasSingleRideGenerator;
import org.matsim.contrib.demand_extraction.algorithm.generation.StopBasedRideGenerator;
import org.matsim.contrib.demand_extraction.algorithm.graph.ShareabilityGraph;
import org.matsim.contrib.demand_extraction.algorithm.hyperpool.HyperPoolGenerator;
import org.matsim.contrib.demand_extraction.algorithm.hyperpool.StopCompatibilityChecker;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.stops.StopFinder;
import org.matsim.contrib.demand_extraction.algorithm.stops.StopFinderFactory;
import org.matsim.contrib.demand_extraction.algorithm.stops.WalkingDistanceCalculator;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
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

	private List<DrtRequest> requests;
	private List<Ride> allRides;
	private List<HyperPooledRide> hyperPooledRides;
	private ShareabilityGraph graph;

	public BamasEngine(MatsimNetworkCache network, BudgetValidator budgetValidator,
					   double horizon, int maxDegree,
					   org.matsim.contrib.demand_extraction.config.ExMasConfigGroup exMasConfig) {
		this(network, budgetValidator, horizon, maxDegree, exMasConfig, null);
	}

	public BamasEngine(MatsimNetworkCache network, BudgetValidator budgetValidator,
					   double horizon, int maxDegree,
					   org.matsim.contrib.demand_extraction.config.ExMasConfigGroup exMasConfig,
					   ActivityFacilities facilities) {
		this.network = network;
		this.budgetValidator = budgetValidator;
		this.horizon = horizon;
		this.maxDegree = maxDegree;
		this.exMasConfig = exMasConfig;
		this.facilities = facilities;
	}

    /**
     * Run ExMAS algorithm on DRT requests with budget validation.
     * 
     * @param drtRequests MATSim requests with budget constraints
     * @return list of all feasible rides (single, pairs, and extensions up to maxDegree)
     */
    public List<Ride> run(List<DrtRequest> drtRequests) {
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

        // Phase 2: Generate pair rides with budget validation
		log.info("");
		log.info("PHASE 2: Pair Ride Generation");
		log.info("======================================================================");
		PairGenerator pairGen = new PairGenerator(network, budgetValidator, horizon, algorithmProcessCount);
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
		List<Ride> currentDegreeRides = maybePrunePairRidesAfterGraph(pairRides);
		allRides.addAll(currentDegreeRides);

        // Phase 4: Iteratively extend rides with budget validation
		// The ordering-based BamasRideExtender enumerates valid orderings directly from
		// pairwise constraints in the shareability graph — no rideMap needed.
		// It returns top-1 per set, so MaxPerSet pruning is redundant.
		// Percentile pruning across sets is still applied to bound memory.
		log.info("");
		log.info("PHASE 4: Iterative Ride Extension");
		log.info("======================================================================");
		int nextRideIndex = allRides.size();
		DegreeGraph prevDegreeGraph = null;
		for (int degree = 2; degree < maxDegree; degree++) {
			BamasRideExtender extender = new BamasRideExtender(network, graph, budgetValidator,
													 requests, exMasConfig, prevDegreeGraph);
			List<Ride> extended = extender.extendRides(currentDegreeRides, nextRideIndex);
			long graphBuildStart = System.currentTimeMillis();
			prevDegreeGraph = extender.buildDegreeGraph(degree + 1);
			long graphBuildMs = System.currentTimeMillis() - graphBuildStart;
			log.info("  Degree-{} graph: {} feasible sets, built in {}ms",
					degree + 1, extender.getFeasibleSetCount(), graphBuildMs);

			if (extended.isEmpty()) {
				log.info("No extensions possible at degree {}. Stopping.", (degree + 1));
				break;
			}

			// Inter-degree pruning: delegates to PostExtensionPruner. Mode selected from config:
			//   RATIO_THRESHOLD — legacy per-degree top-X% by savingsRatio (gated by interDegreeKeepFraction<1)
			//   COVERAGE_TOPK   — per-request top-K by quality metric (default, always active)
			// Survivors become base sets for next degree AND final output.
			int generatedCount = extended.size();
			PostExtensionPruner pruner = buildPruner(exMasConfig);
			if (pruner != null) {
				extended = pruner.prune(extended);
			}

			nextRideIndex += generatedCount; // index space reserved for all generated rides
			allRides.addAll(extended);
			currentDegreeRides = extended;
		}

		// If extension skipped per-ordering budget validation, populate remainingBudgets now.
		// Safe on scenarios where budget never rejects (e.g. Bavaria); see BudgetValidator docs.
		if (exMasConfig.isDeferExtensionBudgetValidation()) {
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

		long totalElapsed = System.currentTimeMillis() - algorithmStartTime;
		double totalSeconds = totalElapsed / 1000.0;
		int[] rideCounts = summarizeRideCounts(allRides);
		log.info("");
		log.info("======================================================================");
		log.info("ExMAS Algorithm Complete (Door-to-Door)");
		log.info("  Total D2D rides generated: {}", allRides.size());
		log.info("  Single: {}, Pairs: {}, Higher: {}",
				rideCounts[0], rideCounts[1], rideCounts[2]);
		log.info("  Total execution time: {}s", String.format("%.1f", totalSeconds));
		log.info("======================================================================");
		org.matsim.contrib.demand_extraction.algorithm.profiling.MemoryProfiler
				.snapshotAtEndOfDegree(-1, allRides.size());

		// Log network routing statistics
		log.info("");
		network.logRoutingStatistics();

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

		return allRides;
	}

	/**
	 * Log completion summary and return rides for early exit.
	 */
	private List<Ride> completeEarly(long algorithmStartTime, String reason) {
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
		return allRides;
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
		StopBasedRideGenerator generator = new StopBasedRideGenerator(
				network, stopFinder, walkCalculator, budgetValidator, exMasConfig, algorithmProcessCount);

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
						double proximityMeters) {
					// Find the first existing stop that is nearby, or return the original stop
					for (org.matsim.contrib.demand_extraction.algorithm.domain.StopLocation existing : existingStops) {
						if (areStopsNearby(stop, existing, proximityMeters)) {
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

		// Create HyperPoolGenerator
		HyperPoolGenerator generator = new HyperPoolGenerator(
				network, stopRelocator, compatibilityChecker, exMasConfig, budgetValidator);

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

		// --- Pass 2: Distance-savings gate (existing behavior) ---
		double scale = exMasConfig.getPruningDistanceSavingsLogScale();
		int minDegree = Math.max(2, exMasConfig.getPruningDistanceSavingsMinDegree());
		if (scale >= 0 && minDegree <= 2) {
			double maxSaving = Math.min(0.99, Math.max(0.0, exMasConfig.getPruningDistanceSavingsMax()));
			double requiredSaving = computeRequiredSavingForDegree(2, scale, maxSaving, minDegree);
			int beforeGate = result.size();
			result = result.stream().filter(r -> {
				double sumDistances = Arrays.stream(r.getRequests()).mapToDouble(DrtRequest::getDistance).sum();
				if (!(sumDistances > 0)) return true;
				return r.getRideDistance() <= (1.0 - requiredSaving) * sumDistances;
			}).collect(Collectors.toList());
			log.info("Pair-ride distance-savings gate (after graph): kept {}/{} (removed {}); requiredSaving>={}%%",
					result.size(), beforeGate, beforeGate - result.size(),
					String.format(java.util.Locale.ROOT, "%.1f", 100.0 * requiredSaving));
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

	private static double computeRequiredSavingForDegree(int degree, double scale, double maxSaving, int minDegree) {
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
