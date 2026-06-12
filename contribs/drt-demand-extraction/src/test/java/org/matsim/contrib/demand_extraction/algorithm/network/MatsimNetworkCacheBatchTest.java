package org.matsim.contrib.demand_extraction.algorithm.network;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;

/**
 * Tests that batchPrecompute() produces identical results to individual getSegment() calls.
 * Uses a programmatically-built 5x5 grid network.
 */
public class MatsimNetworkCacheBatchTest {

	private MatsimNetworkCache cache;
	private List<Id<Link>> linkIds;

	/**
	 * Rebuild the shared cache before each test. SpeedyALT stores internal node/link index
	 * arrays that are invalidated when MATSim's AutoResetIdCaches fires between tests (e.g.
	 * after a test that creates a new network with different link IDs). Per-test setup
	 * ensures each test gets a pristine SpeedyALT instance on a freshly registered network.
	 */
	@BeforeEach
	void setUp() {
		Network network = buildGridNetwork(5, 5, 200.0, 15.0);
		var tt = new FreeSpeedTravelTime();
		var td = new OnlyTimeDependentTravelDisutility(tt);
		cache = MatsimNetworkCacheTestFixture.createWithRouting(network, tt, td, 900);

		linkIds = new ArrayList<>(network.getLinks().keySet());
		linkIds.sort(Comparator.comparing(Id::toString));
	}

	@Test
	@SuppressWarnings("unchecked")
	void batchPrecomputeMatchesPointToPoint() {
		cache.clearCache();
		assertTrue(linkIds.size() >= 6, "Need at least 6 links, got " + linkIds.size());

		Id<Link> origin = linkIds.get(0);
		Id<Link>[] targets = new Id[] {
			linkIds.get(1),
			linkIds.get(linkIds.size() / 4),
			linkIds.get(linkIds.size() / 2),
			linkIds.get(3 * linkIds.size() / 4),
			linkIds.get(linkIds.size() - 1),
		};

		double departureTime = 8 * 3600;

		// Step 1: compute each target individually (point-to-point)
		TravelSegment[] pointToPoint = new TravelSegment[targets.length];
		for (int i = 0; i < targets.length; i++) {
			pointToPoint[i] = cache.getSegment(origin, targets[i], departureTime);
			assertTrue(pointToPoint[i].isReachable(), "Point-to-point for " + targets[i] + " should be reachable");
		}

		// Step 2: clear cache and use batch precompute
		cache.clearCache();
		double maxTT = 600;
		cache.batchPrecompute(origin, departureTime, targets, maxTT);

		// Step 3: verify each target matches exactly
		for (int i = 0; i < targets.length; i++) {
			TravelSegment batch = cache.getSegment(origin, targets[i], departureTime);
			assertTrue(batch.isReachable(), "Batch for " + targets[i] + " should be reachable");
			assertEquals(pointToPoint[i].getTravelTime(), batch.getTravelTime(), 1e-9,
					"Travel time mismatch for " + targets[i]);
			assertEquals(pointToPoint[i].getDistance(), batch.getDistance(), 1e-9,
					"Distance mismatch for " + targets[i]);
			assertEquals(pointToPoint[i].getNetworkUtility(), batch.getNetworkUtility(), 1e-9,
					"Network utility mismatch for " + targets[i]);
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void batchPrecomputeStopCriterionMarksDistantNodesUnreachable() {
		Id<Link> origin = linkIds.get(0);
		Id<Link> nearby = linkIds.get(1);
		Id<Link> far = linkIds.get(linkIds.size() - 1);

		double departureTime = 8 * 3600;
		cache.clearCache();

		// Get actual travel time to nearby target
		TravelSegment nearbyPtp = cache.getSegment(origin, nearby, departureTime);
		assertTrue(nearbyPtp.isReachable(), "Nearby should be reachable via point-to-point");
		cache.clearCache();

		// Tight bound: covers nearby but not far target
		double tightMaxTT = nearbyPtp.getTravelTime() + 5;
		Id<Link>[] targets = new Id[] { nearby, far };

		cache.batchPrecompute(origin, departureTime, targets, tightMaxTT);

		TravelSegment nearbyBatch = cache.getSegment(origin, nearby, departureTime);
		assertTrue(nearbyBatch.isReachable(), "Nearby should be reachable with tight bound");
		assertEquals(nearbyPtp.getTravelTime(), nearbyBatch.getTravelTime(), 1e-9);

		TravelSegment farCached = MatsimNetworkCacheTestFixture.peek(cache, origin, far,
				(int) (departureTime / 900));
		assertNull(farCached, "Far target should remain absent after tight-bound batch precompute");

		TravelSegment farLookup = cache.getSegment(origin, far, departureTime);
		assertTrue(farLookup.isReachable(), "Far target should still be routable on demand after batch miss");
	}

	@Test
	@SuppressWarnings("unchecked")
	void batchPrecomputeDoesNotCacheForbiddenAdjacentTurnAsReachable() {
		Network network = buildForbiddenAdjacentTurnNetwork();
		var tt = new FreeSpeedTravelTime();
		var td = new OnlyTimeDependentTravelDisutility(tt);
		MatsimNetworkCache localCache = MatsimNetworkCacheTestFixture
				.createWithRouting(network, tt, td, 900);

		Id<Link> origin = Id.createLinkId("12");
		Id<Link> target = Id.createLinkId("23");
		double departureTime = 8 * 3600;

		TravelSegment pointToPoint = localCache.getSegment(origin, target, departureTime);
		assertFalse(pointToPoint.isReachable(),
				"Direct link-to-link routing should respect the forbidden immediate turn");

		localCache.clearCache();
		Id<Link>[] targets = new Id[] { target };
		localCache.batchPrecompute(origin, departureTime, targets, 600.0);

		TravelSegment cachedAfterBatch = MatsimNetworkCacheTestFixture.peek(localCache, origin, target,
				(int) (departureTime / 900));
		assertTrue(cachedAfterBatch == null || !cachedAfterBatch.isReachable(),
				"batchPrecompute must not materialize a reachable segment for a forbidden adjacent turn");

		TravelSegment afterBatchLookup = localCache.getSegment(origin, target, departureTime);
		assertFalse(afterBatchLookup.isReachable(),
				"Lookup after batch precompute must remain unreachable for a forbidden adjacent turn");
	}

	/**
	 * Build a grid network with bidirectional links.
	 * Nodes at (col*spacing, row*spacing), links connect adjacent nodes horizontally and vertically.
	 */
	private static Network buildGridNetwork(int rows, int cols, double spacing, double freespeed) {
		Network network = NetworkUtils.createNetwork();
		NetworkFactory factory = network.getFactory();

		// Create nodes
		Node[][] nodes = new Node[rows][cols];
		for (int r = 0; r < rows; r++) {
			for (int c = 0; c < cols; c++) {
				String nodeId = r + "_" + c;
				Node node = factory.createNode(Id.createNodeId(nodeId), new Coord(c * spacing, r * spacing));
				network.addNode(node);
				nodes[r][c] = node;
			}
		}

		// Create bidirectional links
		for (int r = 0; r < rows; r++) {
			for (int c = 0; c < cols; c++) {
				if (c + 1 < cols) {
					addBidirectionalLink(network, factory, nodes[r][c], nodes[r][c + 1], spacing, freespeed);
				}
				if (r + 1 < rows) {
					addBidirectionalLink(network, factory, nodes[r][c], nodes[r + 1][c], spacing, freespeed);
				}
			}
		}

		return network;
	}

	private static Network buildForbiddenAdjacentTurnNetwork() {
		Network network = NetworkUtils.createNetwork();
		Node n1 = NetworkUtils.createAndAddNode(network, Id.createNodeId("1"), new Coord(0, 0));
		Node n2 = NetworkUtils.createAndAddNode(network, Id.createNodeId("2"), new Coord(100, 0));
		Node n3 = NetworkUtils.createAndAddNode(network, Id.createNodeId("3"), new Coord(200, 0));

		Link link12 = NetworkUtils.createAndAddLink(network, Id.createLinkId("12"), n1, n2,
				100.0, 10.0, 1000.0, 1.0);
		Link link23 = NetworkUtils.createAndAddLink(network, Id.createLinkId("23"), n2, n3,
				100.0, 10.0, 1000.0, 1.0);

		link12.setAllowedModes(Set.of(TransportMode.car));
		link23.setAllowedModes(Set.of(TransportMode.car));
		NetworkUtils.addDisallowedNextLinks(link12, TransportMode.car, List.of(link23.getId()));

		return network;
	}

	private static void addBidirectionalLink(Network network, NetworkFactory factory,
	                                          Node from, Node to, double length, double freespeed) {
		String fwd = from.getId() + "-" + to.getId();
		String rev = to.getId() + "-" + from.getId();

		Link linkFwd = factory.createLink(Id.createLinkId(fwd), from, to);
		linkFwd.setLength(length);
		linkFwd.setFreespeed(freespeed);
		linkFwd.setCapacity(1000);
		linkFwd.setNumberOfLanes(1);
		linkFwd.setAllowedModes(Set.of(TransportMode.car));
		network.addLink(linkFwd);

		Link linkRev = factory.createLink(Id.createLinkId(rev), to, from);
		linkRev.setLength(length);
		linkRev.setFreespeed(freespeed);
		linkRev.setCapacity(1000);
		linkRev.setNumberOfLanes(1);
		linkRev.setAllowedModes(Set.of(TransportMode.car));
		network.addLink(linkRev);
	}
}
