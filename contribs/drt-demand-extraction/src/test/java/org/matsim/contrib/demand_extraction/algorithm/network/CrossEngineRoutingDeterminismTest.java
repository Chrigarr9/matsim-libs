package org.matsim.contrib.demand_extraction.algorithm.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility;
import org.matsim.core.router.speedy.LeastCostPathTree;
import org.matsim.core.router.speedy.SpeedyALTFactory;
import org.matsim.core.router.speedy.SpeedyGraphBuilder;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.LeastCostPathCalculator.Path;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;
import org.matsim.core.utils.misc.OptionalTime;

/**
 * Always-on determinism gate on a synthetic tie-heavy network.
 *
 * <p>Chain of K diamonds s_0 .. s_K; in each diamond the TOP branch is 2 links of
 * 100 m @ 10 m/s (20 s, 200 m) and the BOTTOM branch is 2 links of 120 m @ 12 m/s
 * (20 s, 240 m). Every source-to-sink path costs the same TIME (20K s) but a different
 * DISTANCE — 2^K tied paths under a time-only disutility, the worst case for
 * Dijkstra-vs-A* tie-breaking. {@link DeterministicTravelDisutility} must make all
 * engines return the identical all-top path (200 m per diamond).
 */
class CrossEngineRoutingDeterminismTest {

	private static final int K = 6; // diamonds -> 2^6 = 64 tied paths

	private record Net(Network network, Id<Link> entryLink, Id<Link> exitLink) {}

	private static Net diamondChain() {
		Network network = NetworkUtils.createNetwork();
		Node prev = NetworkUtils.createAndAddNode(network, Id.createNodeId("s0"), new Coord(0, 0));
		// Entry stub link so the source of routing is a link, as in production.
		Node entryTail = NetworkUtils.createAndAddNode(network, Id.createNodeId("entry"), new Coord(-50, 0));
		NetworkUtils.createAndAddLink(network, Id.createLinkId("entry"), entryTail, prev, 50.0, 10.0, 1000.0, 1.0);

		for (int i = 0; i < K; i++) {
			double x = (i + 1) * 300.0;
			Node top = NetworkUtils.createAndAddNode(network, Id.createNodeId("t" + i), new Coord(x - 150, 100));
			Node bot = NetworkUtils.createAndAddNode(network, Id.createNodeId("b" + i), new Coord(x - 150, -100));
			Node next = NetworkUtils.createAndAddNode(network, Id.createNodeId("s" + (i + 1)), new Coord(x, 0));
			// top branch: 2 x (100 m @ 10 m/s) = 20 s / 200 m
			NetworkUtils.createAndAddLink(network, Id.createLinkId("t" + i + "a"), prev, top, 100.0, 10.0, 1000.0, 1.0);
			NetworkUtils.createAndAddLink(network, Id.createLinkId("t" + i + "b"), top, next, 100.0, 10.0, 1000.0, 1.0);
			// bottom branch: 2 x (120 m @ 12 m/s) = 20 s / 240 m
			NetworkUtils.createAndAddLink(network, Id.createLinkId("b" + i + "a"), prev, bot, 120.0, 12.0, 1000.0, 1.0);
			NetworkUtils.createAndAddLink(network, Id.createLinkId("b" + i + "b"), bot, next, 120.0, 12.0, 1000.0, 1.0);
			prev = next;
		}
		Node exitHead = NetworkUtils.createAndAddNode(network, Id.createNodeId("exit"), new Coord(K * 300.0 + 50, 0));
		NetworkUtils.createAndAddLink(network, Id.createLinkId("exit"), prev, exitHead, 50.0, 10.0, 1000.0, 1.0);
		return new Net(network, Id.createLinkId("entry"), Id.createLinkId("exit"));
	}

	@Test
	void treeAndTwoAltInstancesAgreeByteForByteAndPickShortestDistanceTie() {
		Net net = diamondChain();
		TravelTime tt = new FreeSpeedTravelTime();
		TravelDisutility td = DeterministicTravelDisutility.wrap(
				new OnlyTimeDependentTravelDisutility(tt), tt, net.network());

		Link from = net.network().getLinks().get(net.entryLink());
		Link to = net.network().getLinks().get(net.exitLink());

		LeastCostPathTree tree = new LeastCostPathTree(SpeedyGraphBuilder.build(net.network()), tt, td);
		tree.calculate(from, 0.0, null, null);
		int toNodeIdx = to.getFromNode().getId().index();
		OptionalTime arrival = tree.getTime(toNodeIdx);
		assertTrue(arrival.isDefined(), "sink must be reachable");
		double treeCost = tree.getCost(toNodeIdx);
		double treeDist = tree.getDistance(toNodeIdx);

		SpeedyALTFactory factory = new SpeedyALTFactory();
		LeastCostPathCalculator alt1 = factory.createPathCalculator(net.network(), td, tt);
		LeastCostPathCalculator alt2 = factory.createPathCalculator(net.network(), td, tt);
		Path p1 = alt1.calcLeastCostPath(from, to, 0.0, null, null);
		Path p2 = alt2.calcLeastCostPath(from, to, 0.0, null, null);
		double d1 = p1.links.stream().mapToDouble(Link::getLength).sum();
		double d2 = p2.links.stream().mapToDouble(Link::getLength).sum();

		// Byte-identical across engines and instances.
		assertEquals(treeCost, p1.travelCost, 0.0, "tree vs ALT cost");
		assertEquals(p1.travelCost, p2.travelCost, 0.0, "ALT instance 1 vs 2 cost");
		assertEquals(arrival.seconds(), p1.travelTime, 0.0, "tree vs ALT travel time");
		assertEquals(treeDist, d1, 0.0, "tree vs ALT distance");
		assertEquals(d1, d2, 0.0, "ALT instance 1 vs 2 distance");

		// The eps tie-breaker resolves all 2^K time-ties toward minimal distance:
		// all-top path = K * 200 m (branch links only; entry/exit stubs excluded from
		// path.links distance? p1.links includes intermediate links between fromLink
		// and toLink, i.e. exactly the K*2 branch links).
		assertEquals(K * 200.0, d1, 1e-9, "must pick the shortest-distance tied path");
	}

	@Test
	void batchPrecomputeAndPointToPointPopulateIdenticalSegments() {
		Net net = diamondChain();
		TravelTime tt = new FreeSpeedTravelTime();
		TravelDisutility td = new OnlyTimeDependentTravelDisutility(tt);

		MatsimNetworkCache batchFilled = MatsimNetworkCacheTestFixture
				.createWithRouting(net.network(), tt, td, 900);
		MatsimNetworkCache missFilled = MatsimNetworkCacheTestFixture
				.createWithRouting(net.network(), tt, td, 900);

		@SuppressWarnings("unchecked")
		Id<Link>[] targets = new Id[] { net.exitLink() };
		batchFilled.batchPrecompute(net.entryLink(), 100.0, targets, 1e9);

		TravelSegment viaBatch = batchFilled.getSegment(net.entryLink(), net.exitLink(), 100.0);
		TravelSegment viaMiss = missFilled.getSegment(net.entryLink(), net.exitLink(), 100.0);

		assertTrue(viaBatch.isReachable() && viaMiss.isReachable());
		assertEquals(viaBatch.getTravelTime(), viaMiss.getTravelTime(), 0.0,
				"SSSP-tree fill and SpeedyALT cache-miss fill must produce identical travel time");
		assertEquals(viaBatch.getDistance(), viaMiss.getDistance(), 0.0,
				"... identical distance");
		assertEquals(viaBatch.getNetworkUtility(), viaMiss.getNetworkUtility(), 0.0,
				"... identical utility");

		// Wrap-sensitivity: the DeterministicTravelDisutility eps*length term makes the
		// routing cost strictly exceed pure travel time. networkUtility = -(cost), so
		// -networkUtility must be strictly greater than travelTime. Remove the wrap and
		// this fails (cost == travelTime for a time-only base) — THIS is what makes the
		// gate catch a "wrap removed" regression, which engine tie-break agreement alone does not.
		assertTrue(-viaBatch.getNetworkUtility() > viaBatch.getTravelTime() + 1e-9,
				"wrapped cost must exceed pure travel time by the eps*length tie-breaker (batch fill)");
		assertTrue(-viaMiss.getNetworkUtility() > viaMiss.getTravelTime() + 1e-9,
				"wrapped cost must exceed pure travel time by the eps*length tie-breaker (miss fill)");
	}
}
