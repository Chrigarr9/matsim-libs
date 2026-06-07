package org.matsim.contrib.demand_extraction.run;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.router.util.LeastCostPathCalculator;

/**
 * Route O->hub and hub->D (plus direct O->D) for every connecting request x
 * candidate hub, using phase-2 routing. Feeds the bi-objective hub-discovery
 * detour matrix. Hub coords snap to the nearest link, matching the virtual-trip
 * expansion in DrtRequestFactory.
 */
public class RunHubDetourMatrix {
    private static final Logger log = LogManager.getLogger(RunHubDetourMatrix.class);

    private record Hub(String id, Link link) {}

    public static void main(String[] args) throws IOException {
        String networkPath = null, travelTimesPath = null, requestsPath = null,
               hubsPath = null, outputPath = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--network" -> networkPath = args[++i];
                case "--travel-times" -> travelTimesPath = args[++i];
                case "--requests" -> requestsPath = args[++i];
                case "--hubs" -> hubsPath = args[++i];
                case "--output" -> outputPath = args[++i];
                default -> log.warn("Unknown argument: {}", args[i]);
            }
        }
        if (networkPath == null || travelTimesPath == null || requestsPath == null
                || hubsPath == null || outputPath == null) {
            System.err.println("Usage: RunHubDetourMatrix --network <p> --travel-times <p> "
                    + "--requests <p> --hubs <p> --output <p>");
            System.exit(1);
        }

        Phase2RoutingSetup setup = Phase2RoutingSetup.load(networkPath, travelTimesPath);
        Network network = setup.network;
        LeastCostPathCalculator router = setup.router;

        List<Hub> hubs = readHubs(hubsPath, network);
        log.info("Loaded {} candidate hubs", hubs.size());

        int rows = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(requestsPath));
             BufferedWriter writer = new BufferedWriter(new FileWriter(outputPath))) {
            writer.write("requestIndex,hubId,hubLinkX,hubLinkY,directTime,oToHubTime,hubToDTime\n");
            String header = reader.readLine();
            Map<String, Integer> col = headerIndex(header);
            int cIdx = req(col, "index"), cOrig = req(col, "originLinkId"),
                cDest = req(col, "destinationLinkId"), cTime = req(col, "requestTime");

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] f = line.split(",");
                String reqIndex = f[cIdx];
                Link oLink = network.getLinks().get(Id.createLinkId(f[cOrig]));
                Link dLink = network.getLinks().get(Id.createLinkId(f[cDest]));
                double dep = Double.parseDouble(f[cTime]);
                if (oLink == null || dLink == null) { log.warn("Req {}: missing O/D link", reqIndex); continue; }

                double direct = legTime(router, setup, oLink, dLink, dep);
                for (Hub h : hubs) {
                    // Depart O at dep; depart hub at dep+oToHub so hub->D uses a consistent clock.
                    double oToHub = legTime(router, setup, oLink, h.link(), dep);
                    double depAtHub = Double.isNaN(oToHub) ? dep : dep + oToHub;
                    double hubToD = legTime(router, setup, h.link(), dLink, depAtHub);
                    Coord c = h.link().getToNode().getCoord();
                    writer.write(String.format(java.util.Locale.US, "%s,%s,%.2f,%.2f,%s,%s,%s\n",
                            reqIndex, h.id(), c.getX(), c.getY(),
                            fmt(direct), fmt(oToHub), fmt(hubToD)));
                    rows++;
                }
            }
        }
        log.info("Wrote {} detour-matrix rows ({} hubs) -> {}", rows, hubs.size(), outputPath);
    }

    /** Travel time of a routed leg incl. destination-link traversal; NaN if unreachable. */
    private static double legTime(LeastCostPathCalculator router, Phase2RoutingSetup s,
                                  Link from, Link to, double dep) {
        if (from.getId().equals(to.getId())) {
            return 0.0; // same link: negligible access time
        }
        LeastCostPathCalculator.Path p = router.calcLeastCostPath(from, to, dep, s.dummyPerson, s.dummyVehicle);
        if (p == null) return Double.NaN;
        return p.travelTime; // router convention: excludes O/D link traversal; fine for detour deltas
    }

    private static String fmt(double v) { return Double.isNaN(v) ? "" : String.format(java.util.Locale.US, "%.2f", v); }

    private static List<Hub> readHubs(String path, Network network) throws IOException {
        List<Hub> hubs = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            Map<String, Integer> col = headerIndex(r.readLine());
            int cId = req(col, "hub_id"), cX = req(col, "x"), cY = req(col, "y");
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] f = line.split(",");
                Coord coord = new Coord(Double.parseDouble(f[cX]), Double.parseDouble(f[cY]));
                Link link = NetworkUtils.getNearestLink(network, coord);
                hubs.add(new Hub(f[cId], link));
            }
        }
        return hubs;
    }

    private static Map<String, Integer> headerIndex(String header) {
        String[] cols = header.split(",");
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < cols.length; i++) idx.put(cols[i].trim(), i);
        return idx;
    }

    private static int req(Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        if (i == null) throw new IllegalArgumentException("Missing column: " + name);
        return i;
    }
}
