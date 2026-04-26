package org.matsim.contrib.demand_extraction.algorithm.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;

class TimeDistanceTravelDisutilityTest {

	@Test
	void disutilityIsTimeCoefTimesTimePlusDistCoefTimesLength() {
		Network net = NetworkUtils.createNetwork();
		Node a = NetworkUtils.createAndAddNode(net, Id.createNodeId("a"), new Coord(0, 0));
		Node b = NetworkUtils.createAndAddNode(net, Id.createNodeId("b"), new Coord(1000, 0));
		// length 1000 m, freespeed 10 m/s -> free-speed time 100 s
		Link link = NetworkUtils.createAndAddLink(net, Id.createLinkId("ab"), a, b, 1000.0, 10.0, 1000.0, 1.0);

		TimeDistanceTravelDisutility td =
				new TimeDistanceTravelDisutility(new FreeSpeedTravelTime(), 1.0, 1e-4);

		// cost = 1.0 * 100 + 1e-4 * 1000 = 100.1
		assertEquals(100.1, td.getLinkTravelDisutility(link, 0.0, null, null), 1e-12);
	}

	@Test
	void minimumDisutilityIsLowerBoundOnActualDisutility() {
		Network net = NetworkUtils.createNetwork();
		Node a = NetworkUtils.createAndAddNode(net, Id.createNodeId("a"), new Coord(0, 0));
		Node b = NetworkUtils.createAndAddNode(net, Id.createNodeId("b"), new Coord(1000, 0));
		Link link = NetworkUtils.createAndAddLink(net, Id.createLinkId("ab"), a, b, 1000.0, 10.0, 1000.0, 1.0);

		TimeDistanceTravelDisutility td =
				new TimeDistanceTravelDisutility(new FreeSpeedTravelTime(), 1.0, 1e-4);

		double min = td.getLinkMinimumTravelDisutility(link);
		double actual = td.getLinkTravelDisutility(link, 0.0, null, null);
		assertTrue(min <= actual,
				"ALT admissibility: min must be a lower bound, got min=" + min + " actual=" + actual);
		// Under FreeSpeedTravelTime, equality holds.
		assertEquals(min, actual, 1e-12);
	}

	@Test
	void rejectsNegativeCoefficients() {
		try {
			new TimeDistanceTravelDisutility(new FreeSpeedTravelTime(), -1.0, 1e-4);
			fail("expected IllegalArgumentException for negative timeCoef");
		} catch (IllegalArgumentException expected) {
			// ok
		}
		try {
			new TimeDistanceTravelDisutility(new FreeSpeedTravelTime(), 1.0, -1e-4);
			fail("expected IllegalArgumentException for negative distCoef");
		} catch (IllegalArgumentException expected) {
			// ok
		}
	}
}
