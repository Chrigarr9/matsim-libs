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

        // Phase 2: Generate pair rides with budget validation
		log.info("");
		log.info("PHASE 2: Pair Ride Generation");
		log.info("======================================================================");
		PairGenerator pairGen = new PairGenerator(network, budgetValidator, horizon, algorithmProcessCount,
				exMasConfig.isEnableBudgetAwareConstraints());
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
		int extensionStartIdx = nextRideIndex; // first index of extension rides in allRides
		final boolean stubMode = exMasConfig.isStubModeEnabled();
		// Stub mode (Task 11): degree-3+ extension rides are held as per-degree StubColumns,
		// NOT appended to allRides as fat Ride objects. We accumulate the per-degree layers
		// here and batch-materialize them once at the end, concatenating with the still-fat
		// singles + pairs already in allRides. The fat `extended` list returned by extendRides
		// is still produced (Task 10 is additive) but used only for control flow + degree-graph;
		// it is not retained past each iteration. Task 12 makes export streaming.
		List<org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns> stubLayers =
				stubMode ? new ArrayList<>() : null;
		// Previous degree's captured (and possibly RATIO-pruned) stub layer, fed as the
		// next degree's parents. Null on the first iteration (degree 2→3 uses fat pairs).
		org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns prevStubLayer = null;
		DegreeGraph prevDegreeGraph = null;
		for (int degree = 2; degree < maxDegree; degree++) {
			BamasRideExtender extender = new BamasRideExtender(network, graph, budgetValidator,
													 requests, exMasConfig, prevDegreeGraph);

			// Seam (a): degree 2→3 has FAT pair parents; degree 3→4+ has STUB parents
			// (the previous iteration's captured layer). prevStubLayer is null on the
			// first iteration, so the fat overload runs there.
			List<Ride> extended;
			if (stubMode && prevStubLayer != null) {
				extended = extender.extendRides(prevStubLayer, reqArray, nextRideIndex);
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
					// so per-degree COVERAGE_TOPK == per-layer pruning).
					for (int i = 0; i < stubLayers.size(); i++) {
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

		// Stub mode: batch-materialize the degree-3+ layers into fat Ride objects and
		// concatenate with the still-fat singles + pairs already in allRides. The existing
		// final sort + reindex (below) then runs UNCHANGED, exactly as in the fat path.
		// NON-DEFERRED: materialize replays buildRideFromOrdering + validateAndPopulateBudgets,
		// so remainingBudgets is populated inline (see RideMaterializer).
		if (stubMode) {
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
			log.info("Stub mode: materialized {} degree-3+ extension rides from {} per-degree layers",
					materialized, stubLayers.size());
		}

		// Seam (c): the deferred-budget batch is being moved to the export pass (Task 12).
		// In stub mode there are no fat extension rides to batch here — RideMaterializer
		// already populates remainingBudgets inline via validateAndPopulateBudgets during
		// materialization above, exactly as the non-deferred per-ordering path does. The
		// gate scenario is NON-DEFERRED, so this block never runs there regardless.
		// Do NOT run populateBudgetsBatch in stub mode: the materialized rides already carry
		// budgets, and the stub layers themselves carry none.
		//
		// If extension skipped per-ordering budget validation, populate remainingBudgets now.
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
