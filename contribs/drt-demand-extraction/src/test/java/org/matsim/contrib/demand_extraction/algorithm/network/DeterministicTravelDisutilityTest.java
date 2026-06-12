package org.matsim.contrib.demand_extraction.algorithm.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;

class DeterministicTravelDisutilityTest {

	/** Two links: "slow" 1000 m @ 10 m/s (gradient 0.1 s/m), "fast" 1000 m @ 25 m/s (0.04 s/m). */
	private static Network twoLinkNetwork() {
		Network network = NetworkUtils.createNetwork();
		Node n0 = NetworkUtils.createAndAddNode(network, Id.createNodeId("n0"), new Coord(0, 0));
		Node n1 = NetworkUtils.createAndAddNode(network, Id.createNodeId("n1"), new Coord(1000, 0));
		Node n2 = NetworkUtils.createAndAddNode(network, Id.createNodeId("n2"), new Coord(2000, 0));
		NetworkUtils.createAndAddLink(network, Id.createLinkId("slow"), n0, n1, 1000.0, 10.0, 1000.0, 1.0);
		NetworkUtils.createAndAddLink(network, Id.createLinkId("fast"), n1, n2, 1000.0, 25.0, 1000.0, 1.0);
		return network;
	}

	/** The eqasim trap: a base disutility with zero gradient on every link. */
	private static final TravelDisutility ZERO_BASE = new TravelDisutility() {
		@Override
		public double getLinkTravelDisutility(Link link, double time,
				org.matsim.api.core.v01.population.Person person, org.matsim.vehicles.Vehicle vehicle) {
			return 0.0;
		}
		@Override
		public double getLinkMinimumTravelDisutility(Link link) {
			return 0.0;
		}
	};

	@Test
	void epsilonAutoScalesToMinCostPerMeterOfBase() {
		Network network = twoLinkNetwork();
		TravelTime tt = new FreeSpeedTravelTime();
		DeterministicTravelDisutility wrapped = DeterministicTravelDisutility.wrap(
				new OnlyTimeDependentTravelDisutility(tt), tt, network);

		// min cost-per-meter = fast link: (1000/25)/1000 = 0.04 s/m -> eps = 1e-6 * 0.04
		assertEquals(1e-6 * 0.04, wrapped.getEpsilon(), 1e-20);
		assertFalse(wrapped.isDegenerateBaseFallback());
	}

	@Test
	void costIsBasePlusEpsilonTimesLength() {
		Network network = twoLinkNetwork();
		TravelTime tt = new FreeSpeedTravelTime();
		DeterministicTravelDisutility wrapped = DeterministicTravelDisutility.wrap(
				new OnlyTimeDependentTravelDisutility(tt), tt, network);
		Link slow = network.getLinks().get(Id.createLinkId("slow"));

		double expected = 100.0 /* 1000m / 10m/s */ + wrapped.getEpsilon() * 1000.0;
		assertEquals(expected, wrapped.getLinkTravelDisutility(slow, 0.0, null, null), 1e-15);
		assertEquals(expected, wrapped.getLinkMinimumTravelDisutility(slow), 1e-15);
	}

	@Test
	void minimumStaysAdmissibleUnderCongestedTravelTime() {
		Network network = twoLinkNetwork();
		// Congested: 2x freespeed time -> actual cost is always >= the freespeed-based minimum.
		TravelTime congested = (link, time, person, vehicle) -> 2.0 * link.getLength() / link.getFreespeed();
		DeterministicTravelDisutility wrapped = DeterministicTravelDisutility.wrap(
				new OnlyTimeDependentTravelDisutility(congested), congested, network);

		for (Link link : network.getLinks().values()) {
			assertTrue(wrapped.getLinkMinimumTravelDisutility(link)
							<= wrapped.getLinkTravelDisutility(link, 8 * 3600.0, null, null) + 1e-15,
					"minimum must never exceed actual cost on " + link.getId());
		}
	}

	@Test
	void zeroGradientBaseFallsBackToTravelTime() {
		Network network = twoLinkNetwork();
		TravelTime tt = new FreeSpeedTravelTime();
		DeterministicTravelDisutility wrapped = DeterministicTravelDisutility.wrap(ZERO_BASE, tt, network);

		assertTrue(wrapped.isDegenerateBaseFallback());
		// Time-gradient eps: min over links of 1/freespeed = 1/25 = 0.04 s/m -> eps = 1e-6 * 0.04
		assertEquals(1e-6 * 0.04, wrapped.getEpsilon(), 1e-20);
		Link slow = network.getLinks().get(Id.createLinkId("slow"));
		// cost = travelTime + eps*length, the base's 0.0 is ignored
		assertEquals(100.0 + wrapped.getEpsilon() * 1000.0,
				wrapped.getLinkTravelDisutility(slow, 0.0, null, null), 1e-15);
		assertEquals(100.0 + wrapped.getEpsilon() * 1000.0,
				wrapped.getLinkMinimumTravelDisutility(slow), 1e-15);
	}

	@Test
	void wrapIsIdempotent() {
		Network network = twoLinkNetwork();
		TravelTime tt = new FreeSpeedTravelTime();
		DeterministicTravelDisutility once = DeterministicTravelDisutility.wrap(
				new OnlyTimeDependentTravelDisutility(tt), tt, network);
		assertSame(once, DeterministicTravelDisutility.wrap(once, tt, network));
	}
}
