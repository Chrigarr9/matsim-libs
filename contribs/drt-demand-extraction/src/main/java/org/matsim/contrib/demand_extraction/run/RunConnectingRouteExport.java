package org.matsim.contrib.demand_extraction.run;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.vehicles.Vehicle;

/**
 * Export MATSim-routed OD paths for DRT requests, using the SAME routing the
 * phase-2 demand extraction uses: offline travel times ({@code travel_times.tsv},
 * 15-min bins, 36 h clamp) + SpeedyALT +
 * {@link org.matsim.contrib.demand_extraction.algorithm.network.DeterministicTravelDisutility}
 * wrapping {@code OnlyTimeDependentTravelDisutility}.
 *
 * <p>Motivation: {@link org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache}
 * computes the same {@code Path} but discards {@code path.links}, keeping only
 * time/distance/utility. Hub discovery needs the route geometry (to see which
 * boundary links connecting trips funnel through), so this runner re-routes the
 * requested OD links and emits the node polyline.
 *
 * <p>O/D links are read straight from {@code drt_requests.csv}
 * ({@code originLinkId} / {@code destinationLinkId}), i.e. the exact links the
 * extraction assigned via {@code NetworkUtils.getNearestLink} — no independent
 * snapping, so geometry matches phase 2.
 *
 * <pre>
 * mvn exec:java -o -Dexec.mainClass="...RunConnectingRouteExport" \
 *   -Dexec.args="--network path/to/lyon_drt_area_network.xml.gz \
 *                --travel-times path/to/travel_times.tsv \
 *                --requests path/to/run.drt_requests.csv \
 *                --output path/to/routed_paths.csv" \
 *   -Denforcer.skip=true
 * </pre>
 *
 * <p>The input requests CSV may be the full requests file or any subset of its
 * rows (e.g. only the connecting trips); every row is routed. Output schema:
 * {@code requestIndex,seq,x,y} — one row per polyline vertex, ordered, starting
 * at the origin link coord and ending at the destination link coord.
 */
public class RunConnectingRouteExport {

	private static final Logger log = LogManager.getLogger(RunConnectingRouteExport.class);

	public static void main(String[] args) throws IOException {
		String networkPath = null;
		String travelTimesPath = null;
		String requestsPath = null;
		String outputPath = null;

		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--network" -> networkPath = args[++i];
				case "--travel-times" -> travelTimesPath = args[++i];
				case "--requests" -> requestsPath = args[++i];
				case "--output" -> outputPath = args[++i];
				default -> log.warn("Unknown argument: {}", args[i]);
			}
		}
		if (networkPath == null || travelTimesPath == null || requestsPath == null || outputPath == null) {
			System.err.println("Usage: RunConnectingRouteExport --network <path> "
					+ "--travel-times <path> --requests <path> --output <path>");
			System.exit(1);
		}

		log.info("=== Connecting-route export (phase-2 routing) ===");
		log.info("  Network:      {}", networkPath);
		log.info("  Travel times: {}", travelTimesPath);
		log.info("  Requests:     {}", requestsPath);
		log.info("  Output:       {}", outputPath);

		Phase2RoutingSetup setup = Phase2RoutingSetup.load(networkPath, travelTimesPath);
		Network network = setup.network;
		LeastCostPathCalculator router = setup.router;
		Person dummyPerson = setup.dummyPerson;
		Vehicle dummyVehicle = setup.dummyVehicle;
		log.info("Loaded network: {} links", network.getLinks().size());

		int routed = 0;
		int failed = 0;
		try (BufferedReader reader = new BufferedReader(new FileReader(requestsPath));
		     BufferedWriter writer = new BufferedWriter(new FileWriter(outputPath))) {

			writer.write("requestIndex,seq,x,y\n");

			String headerLine = reader.readLine();
			if (headerLine == null) {
				throw new IOException("Empty requests file: " + requestsPath);
			}
			Map<String, Integer> col = headerIndex(headerLine);
			int cIdx = require(col, "index");
			int cOrig = require(col, "originLinkId");
			int cDest = require(col, "destinationLinkId");
			int cTime = require(col, "requestTime");

			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank()) continue;
				String[] f = line.split(",");
				String reqIndex = f[cIdx];
				Id<Link> originLinkId = Id.createLinkId(f[cOrig]);
				Id<Link> destLinkId = Id.createLinkId(f[cDest]);
				double departureTime = Double.parseDouble(f[cTime]);

				Link originLink = network.getLinks().get(originLinkId);
				Link destLink = network.getLinks().get(destLinkId);
				if (originLink == null || destLink == null) {
					log.warn("Request {}: link not in network (orig={}, dest={}) - skipping",
							reqIndex, originLinkId, destLinkId);
					failed++;
					continue;
				}

				LeastCostPathCalculator.Path path = router.calcLeastCostPath(
						originLink, destLink, departureTime, dummyPerson, dummyVehicle);
				if (path == null) {
					log.warn("Request {}: no path {}->{} - skipping", reqIndex, originLinkId, destLinkId);
					failed++;
					continue;
				}

				// Polyline: origin link toNode, then each routed intermediate node, then
				// destination link toNode. path.nodes runs originLink.toNode .. destLink.fromNode
				// (router convention: traversal of the O/D links themselves excluded), so
				// appending destLink.toNode closes the geometry onto the destination.
				int seq = 0;
				Node oTo = originLink.getToNode();
				writer.write(row(reqIndex, seq++, oTo.getCoord().getX(), oTo.getCoord().getY()));
				List<Node> nodes = path.nodes;
				for (Node n : nodes) {
					if (n.getId().equals(oTo.getId())) continue; // avoid duplicate leading vertex
					writer.write(row(reqIndex, seq++, n.getCoord().getX(), n.getCoord().getY()));
				}
				Node dTo = destLink.getToNode();
				writer.write(row(reqIndex, seq++, dTo.getCoord().getX(), dTo.getCoord().getY()));
				routed++;
			}
		}

		log.info("Routed {} requests ({} failed/skipped) -> {}", routed, failed, outputPath);
	}

	private static String row(String reqIndex, int seq, double x, double y) {
		return String.format(java.util.Locale.US, "%s,%d,%.2f,%.2f\n", reqIndex, seq, x, y);
	}

	private static Map<String, Integer> headerIndex(String headerLine) {
		String[] cols = headerLine.split(",");
		Map<String, Integer> idx = new HashMap<>();
		for (int i = 0; i < cols.length; i++) {
			idx.put(cols[i].trim(), i);
		}
		return idx;
	}

	private static int require(Map<String, Integer> col, String name) {
		Integer i = col.get(name);
		if (i == null) {
			throw new IllegalArgumentException("Requests CSV missing required column: " + name);
		}
		return i;
	}

}
