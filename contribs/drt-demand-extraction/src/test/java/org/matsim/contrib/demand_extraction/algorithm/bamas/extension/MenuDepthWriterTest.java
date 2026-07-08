package org.matsim.contrib.demand_extraction.algorithm.bamas.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.contrib.demand_extraction.algorithm.graph.ShareabilityGraph;

/**
 * Unit coverage for {@link MenuDepthWriter}: derives per-request partner counts (menu depth)
 * from a small degree-2 shareability graph, including zero-partner requests.
 */
class MenuDepthWriterTest {

	@Test
	void writesPerRequestDumpAndHistogramIncludingZeroPartnerRequests(@TempDir Path tmp) throws Exception {
		// Requests 0,1,2 are pairwise shareable (a triangle); request 3 has no partner.
		ShareabilityGraph.Builder b = ShareabilityGraph.builder(4);
		b.addEdge(0, 1, 100, ShareabilityGraph.KIND_FIFO);
		b.addEdge(1, 2, 101, ShareabilityGraph.KIND_FIFO);
		b.addEdge(0, 2, 102, ShareabilityGraph.KIND_LIFO);
		ShareabilityGraph graph = b.build();

		Path statsDir = tmp.resolve("stats");
		MenuDepthWriter.write(statsDir, graph, new int[] {0, 1, 2, 3});

		// Per-request dump: bidirectional neighbours -> each of 0,1,2 has 2 partners; 3 has 0.
		Path perReq = statsDir.resolve(MenuDepthWriter.PER_REQUEST_FILE);
		assertTrue(Files.exists(perReq));
		List<String> lines = Files.readAllLines(perReq);
		assertEquals("requestId,nPartners", lines.get(0));
		assertEquals(5, lines.size(), "header + one row per request (0..3), zeros included");
		assertEquals("0,2", lines.get(1));
		assertEquals("1,2", lines.get(2));
		assertEquals("2,2", lines.get(3));
		assertEquals("3,0", lines.get(4));

		// Histogram: bucket 0 -> 1 request (the isolated request 3); bucket 2 -> 3 requests.
		Path hist = statsDir.resolve(MenuDepthWriter.HISTOGRAM_FILE);
		assertTrue(Files.exists(hist));
		List<String> h = Files.readAllLines(hist);
		assertEquals("nPartners,count", h.get(0));
		assertEquals("0,1", h.get(1));
		assertEquals("2,3", h.get(2));
	}
}
