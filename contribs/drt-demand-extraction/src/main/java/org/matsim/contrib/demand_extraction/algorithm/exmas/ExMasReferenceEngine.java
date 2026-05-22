package org.matsim.contrib.demand_extraction.algorithm.exmas;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.demand_extraction.algorithm.domain.HyperPooledRide;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideKind;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideVariant;
import org.matsim.contrib.demand_extraction.algorithm.exmas.ReferenceRideExtender;
import org.matsim.contrib.demand_extraction.algorithm.generation.PairGenerator;
import org.matsim.contrib.demand_extraction.algorithm.exmas.ReferenceSingleRideGenerator;
import org.matsim.contrib.demand_extraction.algorithm.generation.StopBasedRideGenerator;
import org.matsim.contrib.demand_extraction.algorithm.graph.ShareabilityGraph;
import org.matsim.contrib.demand_extraction.algorithm.hyperpool.HyperPoolGenerator;
import org.matsim.contrib.demand_extraction.algorithm.hyperpool.StopCompatibilityChecker;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.profiling.ReferenceProgressSink;
import org.matsim.contrib.demand_extraction.algorithm.stops.StopFinder;
import org.matsim.contrib.demand_extraction.algorithm.stops.StopFinderFactory;
import org.matsim.contrib.demand_extraction.algorithm.stops.WalkingDistanceCalculator;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.demand.BudgetToConstraintsCalculator;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;
import org.matsim.contrib.demand_extraction.io.ExMasCsvWriter;
import org.matsim.facilities.ActivityFacilities;

/**
 * Main orchestrator for ExMAS algorithm with MATSim integration.
 * 
 * Generates shareable rides from DRT requests using:
 * - Budget-based feasibility validation
 * - MATSim network routing
 * - Iterative ride extension up to maxDegree
 */
public final class ExMasReferenceEngine {
	private static final Logger log = LogManager.getLogger(ExMasReferenceEngine.class);

	private final MatsimNetworkCache network;
	private final BudgetValidator budgetValidator;
	private final double horizon;
	private final int maxDegree;
	private final org.matsim.contrib.demand_extraction.config.ExMasConfigGroup exMasConfig;
	private final ActivityFacilities facilities; // Optional, for predefined stop finder
	private final String progressRunLabel;
	private final ReferenceProgressSink progressSink;
	private final long checkpointIntervalMs;
	private final BudgetToConstraintsCalculator budgetToConstraints; // Optional, for budget-aware stop search

	private List<DrtRequest> requests;
	private List<Ride> allRides;
	private List<Ride> partialExtendedRidesAtFailure;
	private List<HyperPooledRide> hyperPooledRides;
	private ShareabilityGraph graph;

	public ExMasReferenceEngine(MatsimNetworkCache network, BudgetValidator budgetValidator,
					   double horizon, int maxDegree,
					   org.matsim.contrib.demand_extraction.config.ExMasConfigGroup exMasConfig) {
		this(network, budgetValidator, horizon, maxDegree, exMasConfig, null, null, null, 30_000L, null);
	}

	public ExMasReferenceEngine(MatsimNetworkCache network, BudgetValidator budgetValidator,
					   double horizon, int maxDegree,
					   org.matsim.contrib.demand_extraction.config.ExMasConfigGroup exMasConfig,
					   ActivityFacilities facilities) {
		this(network, budgetValidator, horizon, maxDegree, exMasConfig, facilities, null, null, 30_000L, null);
	}

	public ExMasReferenceEngine(MatsimNetworkCache network, BudgetValidator budgetValidator,
					   double horizon, int maxDegree,
					   org.matsim.contrib.demand_extraction.config.ExMasConfigGroup exMasConfig,
					   String progressRunLabel,
					   ReferenceProgressSink progressSink,
					   long checkpointIntervalMs) {
		this(network, budgetValidator, horizon, maxDegree, exMasConfig, null, progressRunLabel, progressSink,
				checkpointIntervalMs, null);
	}

	public ExMasReferenceEngine(MatsimNetworkCache network, BudgetValidator budgetValidator,
					   double horizon, int maxDegree,
					   org.matsim.contrib.demand_extraction.config.ExMasConfigGroup exMasConfig,
					   ActivityFacilities facilities,
					   String progressRunLabel,
					   ReferenceProgressSink progressSink,
					   long checkpointIntervalMs) {
		this(network, budgetValidator, horizon, maxDegree, exMasConfig, facilities, progressRunLabel, progressSink,
				checkpointIntervalMs, null);
	}

	public ExMasReferenceEngine(MatsimNetworkCache network, BudgetValidator budgetValidator,
					   double horizon, int maxDegree,
					   org.matsim.contrib.demand_extraction.config.ExMasConfigGroup exMasConfig,
					   ActivityFacilities facilities,
					   String progressRunLabel,
					   ReferenceProgressSink progressSink,
					   long checkpointIntervalMs,
					   BudgetToConstraintsCalculator budgetToConstraints) {
		this.network = network;
		this.budgetValidator = budgetValidator;
		this.horizon = horizon;
		this.maxDegree = maxDegree;
		this.exMasConfig = exMasConfig;
		this.facilities = facilities;
		this.progressRunLabel = progressRunLabel;
		this.progressSink = progressSink;
		this.checkpointIntervalMs = checkpointIntervalMs;
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
		this.partialExtendedRidesAtFailure = List.of();
        this.hyperPooledRides = new ArrayList<>();
        
        DrtRequest[] reqArray = drtRequests.toArray(new DrtRequest[0]);

		// Phase 1: Generate single rides with budget validation
		log.info("");
		log.info("PHASE 1: Single Ride Generation");
		log.info("======================================================================");
		ReferenceSingleRideGenerator singleGen = new ReferenceSingleRideGenerator(network, budgetValidator);
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
		int algorithmProcessCount = exMasConfig.getAlgorithmProcessCount();
		PairGenerator pairGen = new PairGenerator(network, budgetValidator, horizon, algorithmProcessCount,
				exMasConfig.isEnableBudgetAwareConstraints());
        List<Ride> pairRides = pairGen.generatePairs(reqArray);
		allRides.addAll(pairRides);

        if (maxDegree <= 2) {
			return completeEarly(algorithmStartTime, "maxDegree <= 2");
        }

        // Phase 3: Build sharability graph from pairs
		log.info("");
		log.info("PHASE 3: Building Shareability Graph");
		log.info("======================================================================");
		long graphStartTime = System.currentTimeMillis();
        graph = buildGraph(pairRides);
		long graphElapsed = System.currentTimeMillis() - graphStartTime;
		log.info("Graph built: {} edges, {} nodes in {}s",
				graph.getEdgeCount(), graph.getNodeCount(), String.format("%.1f", graphElapsed / 1000.0));

		// PORT-NOTE: main's maybePrunePairRidesAfterGraph distance-savings filter is removed.
		// R1 is vanilla ExMAS — all pair rides are carried forward as degree-2 bases for extension.
		List<Ride> currentDegreeRides = pairRides;

        // Phase 4: Iteratively extend rides with budget validation
		// ReferenceRideExtender only needs pair rides in rideMap (for getPairRides/tryExtend insertion ordering).
		// Higher-degree rides are NOT needed in rideMap, so we pass only pairRides + singles.
		// Post-extension pruning is applied per degree to bound memory: only pruned rides are
		// kept in allRides and used as bases for the next degree.
		log.info("");
		log.info("PHASE 4: Iterative Ride Extension");
		log.info("======================================================================");
		List<Ride> pairAndSingleRides = new ArrayList<>(allRides); // singles + pairs for rideMap
		// PORT-NOTE: main's inter-degree pruning (postExtensionMaxPerSet + postExtensionKeepTopFraction)
		// is removed. R1 is vanilla ExMAS by design — no pruning. Main's stock defaults (maxPerSet=0,
		// keepTopFraction=1.0) already disabled this pass; removing the block makes that explicit and
		// prevents accidental engagement if those config knobs ever get set on the reference side.
		// Current branch's PostExtensionPruner uses factory methods (ratioThreshold/coverageTopK), so
		// the old `new PostExtensionPruner(...)` constructors don't compile anyway.
		int nextRideIndex = allRides.size();
		for (int degree = 2; degree < maxDegree; degree++) {
			ReferenceRideExtender extender = new ReferenceRideExtender(network, graph, budgetValidator,
											 requests, pairAndSingleRides, exMasConfig,
											 progressRunLabel, progressSink, checkpointIntervalMs);
			List<Ride> extended;
			try {
				extended = extender.extendRides(currentDegreeRides, nextRideIndex);
			} catch (OutOfMemoryError error) {
				partialExtendedRidesAtFailure = extender.getPartialExtendedRidesAtFailure();
				throw error;
			}

			if (extended.isEmpty()) {
				log.info("No extensions possible at degree {}. Stopping.", (degree + 1));
				break;
			}

			nextRideIndex += extended.size(); // index space reserved for all generated rides
			allRides.addAll(extended);
			currentDegreeRides = extended;
		}

		long totalElapsed = System.currentTimeMillis() - algorithmStartTime;
		double totalSeconds = totalElapsed / 1000.0;
		log.info("");
		log.info("======================================================================");
		log.info("ExMAS Algorithm Complete (Door-to-Door)");
		log.info("  Total D2D rides generated: {}", allRides.size());
		log.info("  Single: {}, Pairs: {}, Higher: {}",
				singleRides.size(), pairRides.size(), allRides.size() - singleRides.size() - pairRides.size());
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

	public boolean writePartialRideSnapshot(String filename) {
		if (partialExtendedRidesAtFailure == null || partialExtendedRidesAtFailure.isEmpty()) {
			return false;
		}

		ExMasCsvWriter.writeRideBatches(filename, allRides, partialExtendedRidesAtFailure);
		return true;
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

			// Edge direction = pickup order from PairGenerator (requests[0] = pickup-first).
			// Downstream extension relies on this: getEdges(baseReq, candidate) returns only pair
			// rides where baseReq is pickup-first, which is the only compatible orientation when
			// extending with candidate as pickup-last.
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
