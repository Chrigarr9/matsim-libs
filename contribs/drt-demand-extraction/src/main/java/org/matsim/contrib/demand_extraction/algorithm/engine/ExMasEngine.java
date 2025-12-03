package org.matsim.contrib.demand_extraction.algorithm.engine;

import org.matsim.contrib.demand_extraction.demand.DrtRequest;
import org.matsim.contrib.demand_extraction.algorithm.domain.*;
import org.matsim.contrib.demand_extraction.algorithm.generation.PairGenerator;
import org.matsim.contrib.demand_extraction.algorithm.generation.SingleRideGenerator;
import org.matsim.contrib.demand_extraction.algorithm.extension.RideExtender;
import org.matsim.contrib.demand_extraction.algorithm.graph.ShareabilityGraph;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import java.util.*;

/**
 * Main orchestrator for ExMAS algorithm with MATSim integration.
 * 
 * Generates shareable rides from DRT requests using:
 * - Budget-based feasibility validation
 * - MATSim network routing
 * - Iterative ride extension up to maxDegree
 */
public final class ExMasEngine {
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
        this.requests = drtRequests;
        this.allRides = new ArrayList<>();
        
        DrtRequest[] reqArray = drtRequests.toArray(new DrtRequest[0]);

        // Phase 1: Generate single rides (always budget-feasible by definition)
		// C: Use the matim logger here (and everywhere else)
        System.out.println("Generating single rides...");
        SingleRideGenerator singleGen = new SingleRideGenerator(network);
        List<Ride> singleRides = singleGen.generate(drtRequests);
        allRides.addAll(singleRides);
        System.out.println("  Single rides: " + singleRides.size());

        // Phase 2: Generate pair rides with budget validation
        System.out.println("Generating pair rides...");
        PairGenerator pairGen = new PairGenerator(network, budgetValidator, horizon);
        List<Ride> pairRides = pairGen.generatePairs(reqArray);
        allRides.addAll(pairRides);
        System.out.println("  Pair rides: " + pairRides.size());

        if (maxDegree <= 2) {
            return allRides;
        }

        // Phase 3: Build sharability graph from pairs
        System.out.println("Building sharability graph...");
        graph = buildGraph(pairRides);
        System.out.println("  Graph: " + graph.getEdgeCount() + " edges, " + graph.getNodeCount() + " nodes");

        // Phase 4: Iteratively extend rides with budget validation
        List<Ride> currentDegreeRides = pairRides;
        for (int degree = 2; degree < maxDegree; degree++) {
            System.out.println("Extending to degree " + (degree + 1) + "...");
            RideExtender extender = new RideExtender(network, graph, budgetValidator,
                                                     requests, allRides);
            List<Ride> extended = extender.extendRides(currentDegreeRides, reqArray, allRides.size());

            if (extended.isEmpty()) {
                System.out.println("  No more extensions possible.");
                break;
            }

            allRides.addAll(extended);
            currentDegreeRides = extended;
            System.out.println("  Degree " + (degree + 1) + " rides: " + extended.size());
        }

        return allRides;
    }

    private ShareabilityGraph buildGraph(List<Ride> pairRides) {
        ShareabilityGraph.Builder builder = ShareabilityGraph.builder(pairRides.size() * 2);

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
