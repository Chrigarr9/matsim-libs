package org.matsim.contrib.exmas_algorithm;

import com.exmas.ridesharing.domain.*;
import com.exmas.ridesharing.network.Network;
import com.exmas.ridesharing.engine.ExMasEngine;
import com.exmas.ridesharing.io.*;

import java.util.List;

/**
 * Main entry point for ExMas algorithm.
 */
public class Main {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: java Main <requests.csv> <network.csv> <output.csv> [horizon] [maxDegree]");
            System.exit(1);
        }

        String requestsFile = args[0];
        String networkFile = args[1];
        String outputFile = args[2];
        double horizon = args.length > 3 ? Double.parseDouble(args[3]) : 0.0;
        int maxDegree = args.length > 4 ? Integer.parseInt(args[4]) : 100;

        System.out.println("ExMas Ridesharing Algorithm - Java Implementation");
        System.out.println("=================================================");
        System.out.println("Configuration:");
        System.out.println("  Requests file: " + requestsFile);
        System.out.println("  Network file: " + networkFile);
        System.out.println("  Output file: " + outputFile);
        System.out.println("  Horizon: " + (horizon > 0 ? horizon + "s" : "unlimited"));
        System.out.println("  Max degree: " + maxDegree);
        System.out.println();

        // Load data
        System.out.println("Loading requests...");
        List<Request> requests = CsvReader.readRequests(requestsFile);
        System.out.println("  Loaded " + requests.size() + " requests");

        System.out.println("Loading network...");
        Network network = CsvReader.readNetwork(networkFile);
        System.out.println("  Loaded " + network.size() + " segments");
        System.out.println();

        // Run algorithm
        long startTime = System.currentTimeMillis();
        ExMasEngine engine = new ExMasEngine(network, horizon, maxDegree);
        List<Ride> rides = engine.run(requests);
        long endTime = System.currentTimeMillis();

        System.out.println();
        System.out.println("Summary:");
        System.out.println("  Total rides: " + rides.size());
        System.out.println("  Execution time: " + (endTime - startTime) / 1000.0 + "s");
        System.out.println();

        // Write output
        System.out.println("Writing output to " + outputFile + "...");
        CsvWriter.writeRides(rides, outputFile);
        System.out.println("Done!");
    }
}
