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
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;

/**
 * Pins the FIX for the ONE condition under which {@code batchPrecompute} (SSSP {@link
 * org.matsim.core.router.speedy.LeastCostPathTree}) and {@code getSegment} cache-miss (point-to-point
 * SpeedyALT) could disagree: a <em>truncated</em> tree.
 *
 * <p>{@code CrossEngineRoutingDeterminismTest} proves the two engines are bit-identical — but every
 * one of its {@code batchPrecompute} calls passes bound {@code 1e9} (an untruncated tree). Production
 * {@code batchPrecompute} passes a FINITE bound (the pair-gen detour limit / {@code
 * predecessorsFilterTime}). Under a finite {@link
 * org.matsim.core.router.speedy.LeastCostPathTree.TravelTimeStopCriterion} the Dijkstra loop {@code
 * break}s, and {@code LeastCostPathTree.getTime/getDistance/getCost} read the per-node {@code data[]}
 * that is written on edge <em>relaxation</em>, not on <em>settle</em>. So a target node whose optimal
 * path is cut off by the bound keeps a worse TENTATIVE label from an earlier relaxation. The fix:
 * {@code batchPrecompute} caches a node ONLY when its arrival is strictly within the bound (settled =
 * optimal); a beyond-bound node is left absent, and {@code getSegment} routes it point-to-point
 * (unbounded, optimal) on demand. So the truncated batch never caches a suboptimal value and never
 * diverges from point-to-point — which is what makes a batch fill eviction-invariant (an evicted-then-
 * rerouted key reproduces the same value regardless of eviction timing).
 *
 * <p>Topology (free-speed, time-only disutility, so cost == time):
 * <pre>
 *   entry → S            5 s   stub so the routing source is a link (as in production)
 *   S    → nT  (direct) 100 s  fast to relax, SUBOPTIMAL
 *   S    → nA           70 s
 *   nA   → nT           10 s   ⇒ optimal S→nT = 80 s (via nA)
 *   nT   → exit          5 s   stub so the routing target is a link
 * </pre>
 * With bound 65 s the loop breaks when nA (70 s) is polled — before nA relaxes nT — so nT only holds a
 * tentative 100 s label from the direct edge, which is {@code >=} the 65 s bound and therefore NOT
 * cached. getSegment then routes nT point-to-point and finds the optimum (80 s). With bound 1e9 the
 * tree settles nT at 80 s and caches it. Either way the cached/returned value is the optimum.
 */
class BatchPrecomputeTruncationDivergenceTest {

	private record Net(Network network, Id<Link> entryLink, Id<Link> exitLink) {}

	private static Net fastDirectSlowOptimal() {
		Network network = NetworkUtils.createNetwork();
		Node nEntry = NetworkUtils.createAndAddNode(network, Id.createNodeId("entry"), new Coord(-50, 0));
		Node s = NetworkUtils.createAndAddNode(network, Id.createNodeId("S"), new Coord(0, 0));
		Node nA = NetworkUtils.createAndAddNode(network, Id.createNodeId("A"), new Coord(500, -100));
		Node nT = NetworkUtils.createAndAddNode(network, Id.createNodeId("T"), new Coord(1000, 0));
		Node nExit = NetworkUtils.createAndAddNode(network, Id.createNodeId("exit"), new Coord(1050, 0));

		// entry stub: 50 m @ 10 m/s = 5 s. Routing source is the link "entry" (toNode = S).
		NetworkUtils.createAndAddLink(network, Id.createLinkId("entry"), nEntry, s, 50.0, 10.0, 1000.0, 1.0);
		// direct S→T: 1000 m @ 10 m/s = 100 s — relaxed first (from S), suboptimal.
		NetworkUtils.createAndAddLink(network, Id.createLinkId("direct"), s, nT, 1000.0, 10.0, 1000.0, 1.0);
		// S→A: 700 m @ 10 m/s = 70 s.
		NetworkUtils.createAndAddLink(network, Id.createLinkId("sa"), s, nA, 700.0, 10.0, 1000.0, 1.0);
		// A→T: 100 m @ 10 m/s = 10 s ⇒ optimal S→T = 80 s.
		NetworkUtils.createAndAddLink(network, Id.createLinkId("at"), nA, nT, 100.0, 10.0, 1000.0, 1.0);
		// exit stub: 50 m @ 10 m/s = 5 s. Routing target is the link "exit" (fromNode = T).
		NetworkUtils.createAndAddLink(network, Id.createLinkId("exit"), nT, nExit, 50.0, 10.0, 1000.0, 1.0);
		return new Net(network, Id.createLinkId("entry"), Id.createLinkId("exit"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void untruncatedTreeAgreesWithPointToPoint() {
		Net net = fastDirectSlowOptimal();
		TravelTime tt = new FreeSpeedTravelTime();
		TravelDisutility td = new OnlyTimeDependentTravelDisutility(tt);

		MatsimNetworkCache treeCache = MatsimNetworkCacheTestFixture.createWithRouting(net.network(), tt, td, 900);
		MatsimNetworkCache pointCache = MatsimNetworkCacheTestFixture.createWithRouting(net.network(), tt, td, 900);

		// Bound 1e9 == the determinism-test regime: tree is NOT truncated.
		treeCache.batchPrecompute(net.entryLink(), 0.0, new Id[] { net.exitLink() }, 1e9);

		TravelSegment viaTree = treeCache.getSegment(net.entryLink(), net.exitLink(), 0.0);
		TravelSegment viaPoint = pointCache.getSegment(net.entryLink(), net.exitLink(), 0.0);

		assertEquals(viaPoint.getTravelTime(), viaTree.getTravelTime(), 0.0,
				"untruncated tree must equal point-to-point");
		assertEquals(viaPoint.getDistance(), viaTree.getDistance(), 0.0,
				"untruncated tree must equal point-to-point (distance)");
		// Sanity: the optimal S→T is the 80 s via-A path (+5 s exit traversal).
		assertEquals(85.0, viaPoint.getTravelTime(), 1e-9, "optimal entry→exit travel time");
	}

	@Test
	@SuppressWarnings("unchecked")
	void truncatedTreeLeavesBeyondBoundTargetAbsentSoNoSuboptimalValueIsCached() {
		Net net = fastDirectSlowOptimal();
		TravelTime tt = new FreeSpeedTravelTime();
		TravelDisutility td = new OnlyTimeDependentTravelDisutility(tt);

		MatsimNetworkCache treeCache = MatsimNetworkCacheTestFixture.createWithRouting(net.network(), tt, td, 900);
		MatsimNetworkCache pointCache = MatsimNetworkCacheTestFixture.createWithRouting(net.network(), tt, td, 900);

		// Finite bound 65 s == the production regime: the loop breaks when nA (70 s) is polled, BEFORE
		// nA relaxes nT, so nT only ever holds a tentative 100 s direct-edge label (>= the 65 s bound).
		// batchPrecompute must NOT cache that tentative value — it caches only settled (within-bound)
		// nodes — so the beyond-bound target is left ABSENT.
		treeCache.batchPrecompute(net.entryLink(), 0.0, new Id[] { net.exitLink() }, 65.0);

		// getSegment then routes the absent target point-to-point (unbounded), finding the true
		// optimum, exactly as a cache with no batch fill would. So the truncated batch no longer
		// diverges from point-to-point: this is what makes batch fills eviction-invariant (an
		// evicted-then-rerouted key reproduces the same value regardless of eviction timing).
		TravelSegment viaTree = treeCache.getSegment(net.entryLink(), net.exitLink(), 0.0);
		TravelSegment viaPoint = pointCache.getSegment(net.entryLink(), net.exitLink(), 0.0);

		assertTrue(viaTree.isReachable() && viaPoint.isReachable(), "both engines reach the target");
		assertEquals(viaPoint.getTravelTime(), viaTree.getTravelTime(), 1e-9,
				"truncated batch must NOT diverge from point-to-point (no suboptimal value cached)");
		assertEquals(viaPoint.getDistance(), viaTree.getDistance(), 1e-9,
				"truncated batch must NOT diverge from point-to-point (distance)");
		assertEquals(85.0, viaPoint.getTravelTime(), 1e-9, "point-to-point finds the true optimum");
		assertEquals(85.0, viaTree.getTravelTime(), 1e-9,
				"with the absent beyond-bound target, getSegment also yields the true optimum");
	}
}
