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

	/**
	 * Eviction-safety contract: a non-adjacent OD routed by cache-miss point-to-point fill
	 * must be bit-identical to the same OD routed by batchPrecompute (SSSP tree).
	 *
	 * <p>Uses the diamond-chain fixture: entry→exit is a multi-hop non-adjacent pair.
	 * Two fresh caches — one batch-prefilled (tree), one empty (forces cache-miss fill via
	 * the SpeedyALT {@code computeSegment} path). After Task 5 was reverted, {@code computeSegment}
	 * routes every non-same-link OD via SpeedyALT; this test (and {@link #treeEqualsAltAcrossManyODs()})
	 * is the load-bearing guard that SpeedyALT point-to-point == batchPrecompute tree on this fixture.
	 */
	@Test
	@SuppressWarnings("unchecked")
	void pointToPointFillEqualsBatchPrecomputeFill() {
		Net net = diamondChain();
		TravelTime tt = new FreeSpeedTravelTime();
		TravelDisutility td = new OnlyTimeDependentTravelDisutility(tt);

		// batchCache: OD is pre-filled by SSSP tree via batchPrecompute.
		MatsimNetworkCache batchCache = MatsimNetworkCacheTestFixture
				.createWithRouting(net.network(), tt, td, 900);
		// pointCache: fresh cache — first getSegment call triggers computeSegment (cache-miss).
		MatsimNetworkCache pointCache = MatsimNetworkCacheTestFixture
				.createWithRouting(net.network(), tt, td, 900);

		// entry→exit: multi-hop, definitely non-adjacent (K diamonds between them).
		Id<Link> from = net.entryLink();
		Id<Link> to = net.exitLink();
		double depTime = 8 * 3600;

		batchCache.batchPrecompute(from, depTime, new Id[] { to }, 1e9);

		TravelSegment viaTree = batchCache.getSegment(from, to, depTime);
		TravelSegment viaPoint = pointCache.getSegment(from, to, depTime);

		assertTrue(viaTree.isReachable(), "batch-filled segment must be reachable");
		assertTrue(viaPoint.isReachable(), "point-to-point-filled segment must be reachable");

		assertEquals(viaTree.getTravelTime(), viaPoint.getTravelTime(), 0.0,
				"travel time must be bit-identical: tree fill vs point-to-point fill");
		assertEquals(viaTree.getDistance(), viaPoint.getDistance(), 0.0,
				"distance must be bit-identical: tree fill vs point-to-point fill");
		assertEquals(viaTree.getNetworkUtility(), viaPoint.getNetworkUtility(), 0.0,
				"network utility must be bit-identical: tree fill vs point-to-point fill");
	}

	/**
	 * Strengthened eviction-safety guard: sweep many random (origin, dest, time-bin) pairs and
	 * assert SpeedyALT point-to-point fill == batchPrecompute SSSP-tree fill on every pair that is
	 * REACHABLE and NON-ADJACENT (the only class that actually exercises tree-vs-ALT). This is the
	 * many-OD generalisation of {@link #pointToPointFillEqualsBatchPrecomputeFill()}: if any reachable
	 * OD disagreed between the two engines, this test goes red.
	 *
	 * <p>Determinism: fixed-seed {@link java.util.Random}, so the swept ODs are identical run-to-run.
	 *
	 * <p>The sweep varies the time-bin too (FreeSpeedTravelTime is time-independent, so the bin
	 * cannot change routing, but exercising multiple bins matches the production key shape exactly
	 * and proves the (origin,dest,bin) key plumbing routes identically across engines per bin).
	 *
	 * <p>Non-vacuity safeguards (see in-line comments):
	 * <ul>
	 *   <li>Tree fills are issued one {@code batchPrecompute} per {@code (origin, timeBin)} over ALL
	 *       of that key's sampled dests. {@code batchPrecompute} skips on {@code (origin, timeBin)}
	 *       once an SSSP was run, so a second call for the same key would NOT tree-fill its new dests
	 *       — they would silently fall through to SpeedyALT and the "tree vs ALT" check would degrade
	 *       to "ALT vs ALT". One call per (origin,bin) with all dests avoids that.</li>
	 *   <li>We count only comparisons that are both reachable and non-adjacent (the genuine
	 *       cross-engine checks) and assert that count clears a floor, so the test cannot pass
	 *       green while checking nothing real.</li>
	 *   <li>We skip a pair only when BOTH engines report unreachable. If exactly one engine reaches
	 *       it, that is a real disagreement and the assertion fires.</li>
	 * </ul>
	 */
	@Test
	@SuppressWarnings("unchecked")
	void treeEqualsAltAcrossManyODs() {
		Net net = diamondChain();
		TravelTime tt = new FreeSpeedTravelTime();
		TravelDisutility td = new OnlyTimeDependentTravelDisutility(tt);

		final int binSize = 900;
		// One tree cache (batch-filled) and one point cache (SpeedyALT cache-miss) shared across
		// the whole sweep. The point cache only ever sees getSegment -> always point-to-point, so
		// reuse is safe. The tree cache is filled per (origin,bin) below.
		MatsimNetworkCache treeCache = MatsimNetworkCacheTestFixture
				.createWithRouting(net.network(), tt, td, binSize);
		MatsimNetworkCache pointCache = MatsimNetworkCacheTestFixture
				.createWithRouting(net.network(), tt, td, binSize);

		java.util.List<Id<Link>> linkIds = new java.util.ArrayList<>(net.network().getLinks().keySet());
		linkIds.sort(java.util.Comparator.comparing(Id::toString)); // deterministic iteration order

		final int SAMPLES = 400;
		final int BINS = 4; // sweep a handful of distinct time bins (departure = bin * binSize + offset)
		java.util.Random rng = new java.util.Random(20260612L);

		// Key the grouping on (origin, timeBin) so each gets exactly one batchPrecompute over ALL
		// its sampled dests (see ssspCompleted-skip note above). depTime is bin*binSize so it maps
		// to that bin; getSegment recomputes the same bin from the same depTime.
		record OBKey(Id<Link> origin, int bin) {}
		java.util.LinkedHashMap<OBKey, java.util.LinkedHashSet<Id<Link>>> destsByOriginBin =
				new java.util.LinkedHashMap<>();
		for (int s = 0; s < SAMPLES; s++) {
			Id<Link> o = linkIds.get(rng.nextInt(linkIds.size()));
			Id<Link> d = linkIds.get(rng.nextInt(linkIds.size()));
			int bin = 30 + rng.nextInt(BINS); // bins 30..33
			destsByOriginBin.computeIfAbsent(new OBKey(o, bin), k -> new java.util.LinkedHashSet<>()).add(d);
		}

		// Tree-fill: one SSSP per (origin,bin) covering all its sampled dests. Large bound (1e9) so the
		// tree reaches every forward node on this small net — guarantees genuinely tree-derived values.
		for (java.util.Map.Entry<OBKey, java.util.LinkedHashSet<Id<Link>>> e : destsByOriginBin.entrySet()) {
			double depTime = e.getKey().bin() * binSize;
			Id<Link>[] dests = e.getValue().toArray(new Id[0]);
			treeCache.batchPrecompute(e.getKey().origin(), depTime, dests, 1e9);
		}

		int crossEngineChecks = 0; // reachable AND non-adjacent: the genuine tree-vs-ALT comparisons
		for (java.util.Map.Entry<OBKey, java.util.LinkedHashSet<Id<Link>>> e : destsByOriginBin.entrySet()) {
			Id<Link> o = e.getKey().origin();
			double depTime = e.getKey().bin() * binSize;
			Link oLink = net.network().getLinks().get(o);
			for (Id<Link> d : e.getValue()) {
				Link dLink = net.network().getLinks().get(d);

				TravelSegment viaTree = treeCache.getSegment(o, d, depTime);
				TravelSegment viaPoint = pointCache.getSegment(o, d, depTime);

				// Skip ONLY when both engines agree the OD is unreachable (a shared unreachable is
				// not a value disagreement). If exactly one is reachable, fall through and let the
				// reachability/value assertions fire.
				if (!viaTree.isReachable() && !viaPoint.isReachable()) {
					continue;
				}
				assertEquals(viaTree.isReachable(), viaPoint.isReachable(),
						"reachability must agree across engines for " + o + " -> " + d);

				// Same-link and adjacent (originLink.toNode == destLink.fromNode) ODs are routed by
				// SpeedyALT in BOTH caches (batchPrecompute defers them to computeSegment too), so
				// they are not cross-engine checks — exclude them from the floor count, but still
				// assert value identity (they must agree trivially).
				boolean sameLink = o.equals(d);
				boolean adjacent = !sameLink
						&& oLink.getToNode().getId().equals(dLink.getFromNode().getId());

				assertEquals(viaTree.getTravelTime(), viaPoint.getTravelTime(), 1e-9,
						"travel time must match (tree vs ALT) for " + o + " -> " + d);
				assertEquals(viaTree.getDistance(), viaPoint.getDistance(), 1e-9,
						"distance must match (tree vs ALT) for " + o + " -> " + d);
				assertEquals(viaTree.getNetworkUtility(), viaPoint.getNetworkUtility(), 1e-9,
						"network utility must match (tree vs ALT) for " + o + " -> " + d);

				if (!sameLink && !adjacent) {
					crossEngineChecks++;
				}
			}
		}

		// Non-vacuity floor: the sweep must actually exercise the tree-vs-ALT path on a meaningful
		// number of reachable, non-adjacent ODs — otherwise it could pass while checking nothing.
		System.out.println("[treeEqualsAltAcrossManyODs] reachable non-adjacent cross-engine checks: "
				+ crossEngineChecks);
		assertTrue(crossEngineChecks >= 50,
				"expected >= 50 reachable non-adjacent cross-engine comparisons, got " + crossEngineChecks);
	}
}
