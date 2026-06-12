package org.matsim.contrib.demand_extraction.algorithm.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.router.util.TravelTime;

class OfflineTravelTimesTest {

	@TempDir
	Path tempDir;

	@Test
	void clampsTravelTimesToFreespeedAndEndTime() throws Exception {
		// Link "a": 1000 m @ 10 m/s -> freespeed time 100 s.
		Network network = NetworkUtils.createNetwork();
		Node n0 = NetworkUtils.createAndAddNode(network, Id.createNodeId("n0"), new Coord(0, 0));
		Node n1 = NetworkUtils.createAndAddNode(network, Id.createNodeId("n1"), new Coord(1000, 0));
		NetworkUtils.createAndAddLink(network, Id.createLinkId("a"), n0, n1, 1000.0, 10.0, 1000.0, 1.0);
		Link a = network.getLinks().get(Id.createLinkId("a"));

		int bins = OfflineTravelTimes.TRAVEL_TIME_END / OfflineTravelTimes.TRAVEL_TIME_BIN_SIZE + 1; // 145: TimeDiscretizer adds the closing boundary bin
		StringBuilder sb = new StringBuilder("linkId");
		for (int i = 0; i < bins; i++) {
			sb.append('\t').append((double) (i * OfflineTravelTimes.TRAVEL_TIME_BIN_SIZE));
		}
		sb.append('\n').append("a");
		for (int i = 0; i < bins; i++) {
			// First bin BELOW freespeed time (inadmissible raw value), rest above.
			sb.append('\t').append(i == 0 ? 60.0 : 150.0);
		}
		sb.append('\n');
		Path tsv = tempDir.resolve("travel_times.tsv");
		Files.writeString(tsv, sb.toString());

		TravelTime tt = OfflineTravelTimes.load(tsv.toString());

		// Bin 0: raw 60 s is faster than free flow -> clamped UP to 100 s (heuristic admissibility).
		assertEquals(100.0, tt.getLinkTravelTime(a, 10.0, null, null), 1e-12);
		// Bin 1: raw 150 s is slower than free flow -> kept.
		assertEquals(150.0, tt.getLinkTravelTime(a, 1000.0, null, null), 1e-12);
		// Past 36 h: time clamped to TRAVEL_TIME_END (last bin value 150 s).
		assertEquals(150.0, tt.getLinkTravelTime(a, 40 * 3600.0, null, null), 1e-12);
	}
}
