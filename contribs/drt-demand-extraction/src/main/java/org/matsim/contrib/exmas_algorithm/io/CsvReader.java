package org.matsim.contrib.exmas_algorithm.io;

import com.exmas.ridesharing.domain.Request;
import com.exmas.ridesharing.domain.TravelSegment;
import com.exmas.ridesharing.network.CompactNetwork;
import com.exmas.ridesharing.network.Network;

import java.io.*;
import java.util.*;

/**
 * CSV reader for requests and network data.
 */
public final class CsvReader {

    public static List<Request> readRequests(String filename) throws IOException {
        List<Request> requests = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
            String header = br.readLine();
            String[] headers = header.split(",");
            Map<String, Integer> colIndex = new HashMap<>();
            for (int i = 0; i < headers.length; i++) {
                colIndex.put(headers[i].trim(), i);
            }

            String line;
            while ((line = br.readLine()) != null) {
                String[] vals = line.split(",");
                requests.add(Request.builder()
                    .index(getInt(vals, colIndex, "index"))
                    .paxId(getString(vals, colIndex, "pax_id"))
                    .origin(getInt(vals, colIndex, "origin"))
                    .destination(getInt(vals, colIndex, "destination"))
                    .requestTime(getDouble(vals, colIndex, "treq"))
                    .travelTime(getDouble(vals, colIndex, "travel_time"))
                    .distance(getDouble(vals, colIndex, "distance"))
                    .networkUtility(getDouble(vals, colIndex, "network_utility"))
                    .baseScore(getDouble(vals, colIndex, "base_score"))
                    .maxPositiveDelay(getDouble(vals, colIndex, "max_positive_delay"))
                    .maxNegativeDelay(getDouble(vals, colIndex, "max_negative_delay"))
                    .positiveDelayRelComponent(getDouble(vals, colIndex, "positive_delay_rel_component"))
                    .negativeDelayRelComponent(getDouble(vals, colIndex, "negative_delay_rel_component"))
                    .maxDetour(getDouble(vals, colIndex, "max_detour"))
                    .maxAbsoluteDetour(getDouble(vals, colIndex, "max_absolute_detour"))
                    .maxTravelTime(getDouble(vals, colIndex, "max_travel_time"))
                    .maxCost(getDouble(vals, colIndex, "max_cost"))
                    .earliestDeparture(getDouble(vals, colIndex, "earliest_departure"))
                    .latestDeparture(getDouble(vals, colIndex, "latest_departure"))
                    .mainMode(getString(vals, colIndex, "main_mode"))
                    .cluster(getInt(vals, colIndex, "cluster_id"))
                    .commute(getBoolean(vals, colIndex, "commute"))
                    .withinArea(getBoolean(vals, colIndex, "withinArea"))
                    .startActivityType(getString(vals, colIndex, "start_activity_type"))
                    .endActivityType(getString(vals, colIndex, "end_activity_type"))
                    .build());
            }
        }

        return requests;
    }

    public static Network readNetwork(String filename) throws IOException {
        CompactNetwork.Builder builder = CompactNetwork.builder(100000);

        try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
            String header = br.readLine();
            String[] headers = header.split(",");
            Map<String, Integer> colIndex = new HashMap<>();
            for (int i = 0; i < headers.length; i++) {
                colIndex.put(headers[i].trim(), i);
            }

            String line;
            while ((line = br.readLine()) != null) {
                String[] vals = line.split(",");
                builder.addSegment(
                    getInt(vals, colIndex, "origin"),
                    getInt(vals, colIndex, "destination"),
                    getDouble(vals, colIndex, "travel_time"),
                    getDouble(vals, colIndex, "distance"),
                    getDouble(vals, colIndex, "network_utility")
                );
            }
        }

        return builder.build();
    }

    private static int getInt(String[] vals, Map<String, Integer> colIndex, String col) {
        Integer idx = colIndex.get(col);
        if (idx == null || idx >= vals.length) return 0;
        String val = vals[idx].trim();
        return val.isEmpty() ? 0 : Integer.parseInt(val);
    }

    private static double getDouble(String[] vals, Map<String, Integer> colIndex, String col) {
        Integer idx = colIndex.get(col);
        if (idx == null || idx >= vals.length) return 0.0;
        String val = vals[idx].trim();
        return val.isEmpty() ? 0.0 : Double.parseDouble(val);
    }

    private static String getString(String[] vals, Map<String, Integer> colIndex, String col) {
        Integer idx = colIndex.get(col);
        if (idx == null || idx >= vals.length) return "";
        return vals[idx].trim();
    }

    private static boolean getBoolean(String[] vals, Map<String, Integer> colIndex, String col) {
        Integer idx = colIndex.get(col);
        if (idx == null || idx >= vals.length) return false;
        String val = vals[idx].trim().toLowerCase();
        return val.equals("true") || val.equals("1");
    }
}
