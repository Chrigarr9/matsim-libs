package org.matsim.contrib.demand_extraction.algorithm.engine;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideKind;
import org.matsim.contrib.demand_extraction.algorithm.extension.RideExtender;
import org.matsim.contrib.demand_extraction.algorithm.generation.PairGenerator;
import org.matsim.contrib.demand_extraction.algorithm.generation.SingleRideGenerator;
import org.matsim.contrib.demand_extraction.algorithm.graph.ShareabilityGraph;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

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

    private List<DrtRequest> requests;
    private List<Ride> allRides;
    private ShareabilityGraph graph;

    public ExMasEngine(MatsimNetworkCache network, BudgetValidator budgetValidator, 
                       double horizon, int maxDegree) {
        this.network = network;
        this.budgetValidator = budgetValidator;
        this.horizon = horizon;
        this.maxDegree = maxDegree;
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
        PairGenerator pairGen = new PairGenerator(network, budgetValidator, horizon);
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

        // Phase 4: Iteratively extend rides with budget validation
		log.info("");
		log.info("PHASE 4: Iterative Ride Extension");
		log.info("======================================================================");
        List<Ride> currentDegreeRides = pairRides;
		for (int degree = 2; degree < maxDegree; degree++) {
            RideExtender extender = new RideExtender(network, graph, budgetValidator,
                                                     requests, allRides);
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
		log.info("ExMAS Algorithm Complete");
		log.info("  Total rides generated: {}", allRides.size());
		log.info("  Single: {}, Pairs: {}, Higher: {}",
				singleRides.size(), pairRides.size(), allRides.size() - singleRides.size() - pairRides.size());
		log.info("  Total execution time: {}s", String.format("%.1f", totalSeconds));
		log.info("======================================================================");
		
		// Log network routing statistics
		log.info("");
		network.logRoutingStatistics();

		// Sort rides for deterministic output (parallel processing can create non-deterministic order)
		// Sort by: degree (ascending), then by first request index (ascending)
		allRides.sort(java.util.Comparator
				.comparingInt(Ride::getDegree)
				.thenComparingInt(r -> {
					int[] indices = r.getRequestIndices();
					return indices.length > 0 ? indices[0] : Integer.MAX_VALUE;
				}));

		// Re-assign indices sequentially after sorting
		for (int i = 0; i < allRides.size(); i++) {
			Ride oldRide = allRides.get(i);
			Ride newRide = Ride.builder()
					.index(i)  // New sequential index
					.degree(oldRide.getDegree())
					.kind(oldRide.getKind())
					.requests(oldRide.getRequests())
					.originsOrderedRequests(oldRide.getOriginsOrderedRequests())
					.destinationsOrderedRequests(oldRide.getDestinationsOrderedRequests())
					.passengerTravelTimes(oldRide.getPassengerTravelTimes())
					.passengerDistances(oldRide.getPassengerDistances())
					.passengerNetworkUtilities(oldRide.getPassengerNetworkUtilities())
					.delays(oldRide.getDelays())
					.detours(oldRide.getDetours())
					.remainingBudgets(oldRide.getRemainingBudgets())
					.connectionTravelTimes(oldRide.getConnectionTravelTimes())
					.connectionDistances(oldRide.getConnectionDistances())
					.connectionNetworkUtilities(oldRide.getConnectionNetworkUtilities())
					.startTime(oldRide.getStartTime())
					.shapleyValues(oldRide.getShapleyValues())
					.predecessors(oldRide.getPredecessors())
					.successors(oldRide.getSuccessors())
					.build();
			allRides.set(i, newRide);
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

    public List<DrtRequest> getRequests() {
        return requests;
    }

    public List<Ride> getAllRides() {
        return allRides;
    }
}
