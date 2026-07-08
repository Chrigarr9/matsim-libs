package org.matsim.contrib.demand_extraction.algorithm.bamas.extension;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.TreeMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.contrib.demand_extraction.algorithm.graph.ShareabilityGraph;

/**
 * Persists the degree-2 "menu depth" — the number of valid degree-2 partners each request has —
 * the density driver of the pooling scaling law.
 *
 * <p>Written once at the degree-2 boundary (right after the shareability graph is built) into the
 * run's stats directory:
 * <ul>
 *   <li>{@code partners_per_request.csv} — {@code requestId,nPartners}, one row per request. The
 *       {@code requestId} is the global request index (the shareability-graph node id). Requests
 *       with zero partners are included (nPartners = 0) so the full distribution — including the
 *       denominator — is captured.</li>
 *   <li>{@code menu_depth_hist.csv} — {@code nPartners,count}, the summary histogram (partner-count
 *       bucket to request count), always emitted as the compact fallback for 100%-scale analysis.</li>
 * </ul>
 *
 * <p>Partner count per request = number of distinct neighbours in the bidirectional shareability
 * graph ({@link ShareabilityGraph#getNeighbors(int)}), so FIFO and LIFO variants of the same partner
 * collapse to one. IO failures are logged and swallowed — analytics never fail the run.
 */
public final class MenuDepthWriter {

	private static final Logger log = LogManager.getLogger(MenuDepthWriter.class);

	public static final String PER_REQUEST_FILE = "partners_per_request.csv";
	public static final String HISTOGRAM_FILE = "menu_depth_hist.csv";

	private MenuDepthWriter() {}

	/**
	 * Write both the per-request dump and the histogram.
	 *
	 * @param statsDir           the {@code <outputDir>/drt_demand/stats} directory (created if absent)
	 * @param graph              the degree-2 shareability graph (node ids = request indices)
	 * @param allRequestIndices  every request's global index (so zero-partner requests are counted);
	 *                           iterated in ascending order for deterministic output
	 */
	public static void write(Path statsDir, ShareabilityGraph graph, int[] allRequestIndices) {
		int[] indices = allRequestIndices.clone();
		Arrays.sort(indices);
		try {
			Files.createDirectories(statsDir);
			// nPartners bucket -> request count (TreeMap keeps the histogram sorted by bucket).
			TreeMap<Integer, Long> hist = new TreeMap<>();
			Path perReq = statsDir.resolve(PER_REQUEST_FILE);
			try (BufferedWriter w = Files.newBufferedWriter(perReq)) {
				w.write("requestId,nPartners\n");
				for (int idx : indices) {
					int nPartners = graph.getNeighbors(idx).length;
					w.write(idx + "," + nPartners + "\n");
					hist.merge(nPartners, 1L, Long::sum);
				}
			}
			Path histFile = statsDir.resolve(HISTOGRAM_FILE);
			try (BufferedWriter w = Files.newBufferedWriter(histFile)) {
				w.write("nPartners,count\n");
				for (var e : hist.entrySet()) {
					w.write(e.getKey() + "," + e.getValue() + "\n");
				}
			}
			log.info("Menu depth: wrote {} per-request rows to {} and {} histogram buckets to {}",
					indices.length, perReq, hist.size(), histFile);
		} catch (IOException e) {
			log.warn("Could not write menu-depth analytics to {}: {}", statsDir, e.toString());
		}
	}
}
