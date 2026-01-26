package org.matsim.contrib.demand_extraction.algorithm.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideKind;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideVariant;
import org.matsim.contrib.demand_extraction.algorithm.extension.RideExtender;
import org.matsim.contrib.demand_extraction.algorithm.generation.PairGenerator;
import org.matsim.contrib.demand_extraction.algorithm.generation.SingleRideGenerator;
import org.matsim.contrib.demand_extraction.algorithm.generation.StopBasedRideGenerator;
import org.matsim.contrib.demand_extraction.algorithm.graph.ShareabilityGraph;
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
public final class ExMasEngine {
	private static final Logger log = LogManager.getLogger(ExMasEngine.class);

	private final MatsimNetworkCache network;
	private final BudgetValidator budgetValidator;
	private final double horizon;
	private final int maxDegree;
	private final org.matsim.contrib.demand_extraction.config.ExMasConfigGroup exMasConfig;
	private final ActivityFacilities facilities; // Optional, for predefined stop finder

	private List<DrtRequest> requests;
	private List<Ride> allRides;
	private ShareabilityGraph graph;

	public ExMasEngine(MatsimNetworkCache network, BudgetValidator budgetValidator,
					   double horizon, int maxDegree,
					   org.matsim.contrib.demand_extraction.config.ExMasConfigGroup exMasConfig) {
		this(network, budgetValidator, horizon, maxDegree, exMasConfig, null);
	}

	public ExMasEngine(MatsimNetworkCache network, BudgetValidator budgetValidator,
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
        
        DrtRequest[] reqArray = drtRequests.toArray(new DrtRequest[0]);

		// Phase 1: Generate single rides with budget validation
		log.info("");
		log.info("PHASE 1: Single Ride Generation");
		log.info("======================================================================");
		SingleRideGenerator singleGen = new SingleRideGenerator(network, budgetValidator);
        List<Ride> singleRides = singleGen.generate(drtRequests);
		allRides.addAll(singleRides);

		// Check if we should stop before generating pairs
		if (maxDegree < 2) {
			long totalElapsed = System.currentTimeMillis() - algorithmStartTime;
			double totalSeconds = totalElapsed / 1000.0;
			log.info("");
			log.info("======================================================================");
			log.info("ExMAS Algorithm Complete (maxDegree < 2, skipping pair generation)");
			log.info("  Total rides: {}", allRides.size());
			log.info("  Total time: {}s", String.format("%.1f", totalSeconds));
			log.info("======================================================================");
			
			// Log network routing statistics
			log.info("");
			network.logRoutingStatistics();
			
			return allRides;
		}

        // Phase 2: Generate pair rides with budget validation
		log.info("");
		log.info("PHASE 2: Pair Ride Generation");
		log.info("======================================================================");
		int algorithmProcessCount = exMasConfig != null ? exMasConfig.getAlgorithmProcessCount() : -1;
		PairGenerator pairGen = new PairGenerator(network, budgetValidator, horizon, algorithmProcessCount);
        List<Ride> pairRides = pairGen.generatePairs(reqArray);
		allRides.addAll(pairRides);

        if (maxDegree <= 2) {
			long totalElapsed = System.currentTimeMillis() - algorithmStartTime;
			double totalSeconds = totalElapsed / 1000.0;
			log.info("");
			log.info("======================================================================");
			log.info("ExMAS Algorithm Complete");
			log.info("  Total rides: {}", allRides.size());
			log.info("  Total time: {}s", String.format("%.1f", totalSeconds));
			log.info("======================================================================");
			
			// Log network routing statistics
			log.info("");
			network.logRoutingStatistics();
			
            return allRides;
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

		// Optional: prune which degree-2 rides are used as extension bases AFTER the shareability graph
		// is constructed. This keeps the shareability graph complete (built from all pair rides) and keeps
		// full pair-ride support available via allRides/rideMap. It only reduces which pair rides we try
		// to extend to higher degrees.
		List<Ride> currentDegreeRides = maybePrunePairRidesAfterGraph(pairRides);

        // Phase 4: Iteratively extend rides with budget validation
		log.info("");
		log.info("PHASE 4: Iterative Ride Extension");
		log.info("======================================================================");
		for (int degree = 2; degree < maxDegree; degree++) {
			RideExtender extender = new RideExtender(network, graph, budgetValidator,
													 requests, allRides, exMasConfig);
			List<Ride> extended = extender.extendRides(currentDegreeRides, allRides.size());

			if (extended.isEmpty()) {
				log.info("No extensions possible at degree {}. Stopping.", (degree + 1));
				break;
			}

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

		// Log network routing statistics
		log.info("");
		network.logRoutingStatistics();

		// Phase 5: Stop-Based Ride Generation (HyperPool Stage 1)
		// Only runs if enableStopBased = true
		if (exMasConfig != null && exMasConfig.isEnableStopBased()) {
			log.info("");
			log.info("PHASE 5: Stop-Based Ride Generation (HyperPool Stage 1)");
			log.info("======================================================================");

			List<Ride> stopBasedRides = generateStopBasedRides(allRides);
			if (!stopBasedRides.isEmpty()) {
				allRides.addAll(stopBasedRides);
				log.info("Added {} stop-to-stop ride variants", stopBasedRides.size());
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
		if (exMasConfig != null && exMasConfig.isEnableStopBased()) {
			long d2dCount = allRides.stream().filter(r -> r.getVariant() == RideVariant.DOOR_TO_DOOR).count();
			long s2sCount = allRides.stream().filter(r -> r.getVariant() == RideVariant.STOP_TO_STOP).count();
			log.info("");
			log.info("======================================================================");
			log.info("Final Summary (with Stop-Based Pooling)");
			log.info("  Door-to-Door rides: {}", d2dCount);
			log.info("  Stop-to-Stop rides: {}", s2sCount);
			log.info("  Total rides: {}", allRides.size());
			log.info("======================================================================");
		}

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
	 * Optionally prune pair rides after graph construction based on distance savings.
	 * Keeps the shareability graph complete but reduces which pair rides we try to extend.
	 */
	private List<Ride> maybePrunePairRidesAfterGraph(List<Ride> pairRides) {
		if (exMasConfig == null || pairRides.isEmpty()) {
			return pairRides;
		}
		double scale = exMasConfig.getPruningDistanceSavingsLogScale();
		if (scale < 0) {
			return pairRides;
		}
		int minDegree = Math.max(2, exMasConfig.getPruningDistanceSavingsMinDegree());
		// If the user wants minDegree=2, they explicitly allow pruning of degree-2 rides.
		// Only do it here (after graph construction) to keep the graph complete.
		if (minDegree > 2) {
			return pairRides;
		}

		double maxSaving = exMasConfig.getPruningDistanceSavingsMax();
		if (!(maxSaving >= 0)) {
			maxSaving = 0.0;
		}
		maxSaving = Math.min(0.99, maxSaving);
		double requiredSaving = computeRequiredSavingForDegree(2, scale, maxSaving, minDegree);

		int before = pairRides.size();
		List<Ride> kept = pairRides.stream().filter(r -> {
			if (r.getDegree() != 2) {
				return true;
			}
			double sumDistances = Arrays.stream(r.getRequests()).mapToDouble(DrtRequest::getDistance).sum();
			if (!(sumDistances > 0)) {
				return true;
			}
			double maxRideDistance = (1.0 - requiredSaving) * sumDistances;
			return r.getRideDistance() <= maxRideDistance;
		}).toList();

		int after = kept.size();
		int removed = before - after;
		log.info("Pair-ride base pruning (after graph): kept {}/{} (removed {}); distance-savings gate for degree 2: requiredSaving>={}%% (scale={}, maxSaving={})",
				after,
				before,
				removed,
				String.format(java.util.Locale.ROOT, "%.1f", 100.0 * requiredSaving),
				String.format(java.util.Locale.ROOT, "%.3f", scale),
				String.format(java.util.Locale.ROOT, "%.2f", maxSaving));

		return kept;
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
}
