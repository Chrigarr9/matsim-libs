package org.matsim.contrib.exmas_algorithm.engine;

import com.exmas.ridesharing.domain.*;
import com.exmas.ridesharing.network.Network;
import com.exmas.ridesharing.graph.ShareabilityGraph;
import com.exmas.ridesharing.generation.*;
import com.exmas.ridesharing.extension.RideExtender;
import java.util.*;

/**
 * Main orchestrator for ExMas algorithm.
 * Python reference: engine.py
 */
public final class ExMasEngine {
    private final Network network;
    private final double horizon;
    private final int maxDegree;

    private List<Request> requests;
    private List<Ride> allRides;
    private ShareabilityGraph graph;

    public ExMasEngine(Network network, double horizon, int maxDegree) {
        this.network = network;
        this.horizon = horizon;
        this.maxDegree = maxDegree;
    }

    public List<Ride> run(List<Request> requests) {
        this.requests = requests;
        this.allRides = new ArrayList<>();

        // Phase 1: Generate single rides
        System.out.println("Generating single rides...");
        SingleRideGenerator singleGen = new SingleRideGenerator();
        List<Ride> singleRides = singleGen.generate(requests);
        allRides.addAll(singleRides);
        System.out.println("  Single rides: " + singleRides.size());

        // Phase 2: Generate pair rides
        System.out.println("Generating pair rides...");
        PairGenerator pairGen = new PairGenerator(network, horizon);
        List<Ride> pairRides = pairGen.generatePairs(requests.toArray(new Request[0]));
        allRides.addAll(pairRides);
        System.out.println("  Pair rides: " + pairRides.size());

        if (maxDegree <= 2) {
            return allRides;
        }

        // Phase 3: Build sharability graph from pairs
        System.out.println("Building sharability graph...");
        graph = buildGraph(pairRides);
        System.out.println("  Graph: " + graph.getEdgeCount() + " edges, " + graph.getNodeCount() + " nodes");

        // Phase 4: Iteratively extend rides
        List<Ride> currentDegreeRides = pairRides;
        for (int degree = 2; degree < maxDegree; degree++) {
            System.out.println("Extending to degree " + (degree + 1) + "...");
            RideExtender extender = new RideExtender(network, graph, requests, allRides);
            List<Ride> extended = extender.extendRides(currentDegreeRides, allRides.size());

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

    public List<Request> getRequests() {
        return requests;
    }

    public List<Ride> getAllRides() {
        return allRides;
    }
}
