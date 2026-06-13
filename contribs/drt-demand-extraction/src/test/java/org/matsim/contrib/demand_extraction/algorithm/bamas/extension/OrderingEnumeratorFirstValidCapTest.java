package org.matsim.contrib.demand_extraction.algorithm.bamas.extension;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.demand_extraction.algorithm.bamas.extension.OrderingEnumerator.Ordering;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;
import org.matsim.contrib.demand_extraction.algorithm.graph.ShareabilityGraph;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCacheTestFixture;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

/**
 * Task A2 — per-set first-valid node cap on {@link OrderingEnumerator#enumerateAndEvaluateSeeded}.
 *
 * <p>When {@code firstValidNodeCap > 0} and the DFS has expanded that many nodes
 * without recording a first valid ordering (i.e. {@code curSetNodesFirstValid == -1}),
 * the set is abandoned immediately and produces no ordering/ride. When
 * {@code firstValidNodeCap == 0} the cap is disabled and the full tree is explored.
 *
 * <p>Contract verified:
 * <ul>
 *   <li>Cap disabled ({@code 0L}): a valid ordering IS produced.</li>
 *   <li>Cap = N-1 (where N = {@code curSetNodesFirstValid} of the uncapped run):
 *       the set is abandoned before the first valid ordering — no ride produced.</li>
 *   <li>The after-first-valid budget (Design A) is unaffected and left at 0 throughout.</li>
 * </ul>
 */
class OrderingEnumeratorFirstValidCapTest {

	private static final double SEG_TT   = 100.0;
	private static final double SEG_DIST = 1.0;

	@AfterEach
	void disableAfterFirstValidCap() {
		// Ensure the sibling Design-A cap is off — this test only exercises A2's cap.
		EnumerationStats.setMaxOrderingNodesAfterFirstValid(0);
	}

	/**
	 * UNCAPPED (cap=0): a valid ordering is produced and N = curSetNodesFirstValid >= 2,
	 * so cap = N-1 stays > 0 (enabled). CAPPED (cap=N-1): set abandoned, no ride.
	 */
	@Test
	void capBeforeFirstValid_abandonsSet() {
		TestSetup setup = buildUniformSetup(4);
		int[] set        = {0, 1, 2, 3};
		int[] seedOrigin = {0, 1, 2};
		int[] seedDest   = {0, 1, 2};
		int   seedNew    = 3;

		// --- Uncapped run (firstValidNodeCap = 0L) ---
		EnumerationStats.reset();
		EnumerationStats.setMaxOrderingNodesAfterFirstValid(0); // Design-A cap off
		double[] bestUncapped = {Double.MAX_VALUE};
		Ordering[] keptUncapped = {null};
		OrderingEnumerator.enumerateAndEvaluateSeeded(
				set, setup.graph, setup.network, setup.requests, bestUncapped,
				seedOrigin, seedDest, seedNew,
				/* budgetAwareConstraints= */ false,
				/* firstValidNodeCap= */ 0L,
				o -> {
					if (o.rideDistance() < bestUncapped[0]) {
						bestUncapped[0] = o.rideDistance();
						keptUncapped[0] = o;
					}
				});

		long N = EnumerationStats.get().curSetNodesFirstValid;
		assertNotNull(keptUncapped[0], "uncapped run must produce a valid ride");
		assertTrue(N >= 2,
				"fixture must expand >= 2 nodes before first valid, so cap N-1 stays > 0 (N=" + N + ")");

		// --- Capped run (firstValidNodeCap = N-1): should abandon before first valid ---
		EnumerationStats.reset();
		EnumerationStats.setMaxOrderingNodesAfterFirstValid(0); // Design-A cap still off
		double[] bestCapped = {Double.MAX_VALUE};
		Ordering[] keptCapped = {null};
		OrderingEnumerator.enumerateAndEvaluateSeeded(
				set, setup.graph, setup.network, setup.requests, bestCapped,
				seedOrigin, seedDest, seedNew,
				/* budgetAwareConstraints= */ false,
				/* firstValidNodeCap= */ N - 1,
				o -> {
					if (o.rideDistance() < bestCapped[0]) {
						bestCapped[0] = o.rideDistance();
						keptCapped[0] = o;
					}
				});

		assertNull(keptCapped[0],
				"cap=" + (N - 1) + " (= N-1) must abandon the set before first valid — no ride produced");
		assertTrue(EnumerationStats.get().curSetNodesFirstValid < 0,
				"curSetNodesFirstValid must stay -1 when set is abandoned (cap=" + (N - 1) + ")");
	}

	// ── Fixture (identical structure to OrderingNodeBudgetTest.buildUniformSetup) ────

	private record TestSetup(DrtRequest[] requests, ShareabilityGraph graph, MatsimNetworkCache network) {}

	/**
	 * n requests, all FIFO+LIFO both directions (no ordering constraints), uniform
	 * segment time/distance everywhere. Mirrors the sibling {@code OrderingNodeBudgetTest}.
	 */
	private static TestSetup buildUniformSetup(int n) {
		@SuppressWarnings("unchecked")
		Id<Link>[] oLink = new Id[n];
		@SuppressWarnings("unchecked")
		Id<Link>[] dLink = new Id[n];
		for (int i = 0; i < n; i++) {
			oLink[i] = Id.createLinkId("O" + i);
			dLink[i] = Id.createLinkId("D" + i);
		}

		ShareabilityGraph.Builder gb = ShareabilityGraph.builder(n * (n - 1) * 4);
		int rideIdx = 0;
		for (int i = 0; i < n; i++) {
			for (int j = 0; j < n; j++) {
				if (i != j) {
					gb.addEdge(i, j, rideIdx++, ShareabilityGraph.KIND_FIFO);
					gb.addEdge(i, j, rideIdx++, ShareabilityGraph.KIND_LIFO);
				}
			}
		}
		ShareabilityGraph graph = gb.build();

		MatsimNetworkCache net = MatsimNetworkCacheTestFixture.create();
		TravelSegment seg = new TravelSegment(SEG_TT, SEG_DIST, 0.0);
		for (int i = 0; i < n; i++) {
			for (int j = 0; j < n; j++) {
				if (i != j) {
					MatsimNetworkCacheTestFixture.put(net, oLink[i], oLink[j], seg);
					MatsimNetworkCacheTestFixture.put(net, dLink[i], dLink[j], seg);
				}
				MatsimNetworkCacheTestFixture.put(net, oLink[i], dLink[j], seg);
				MatsimNetworkCacheTestFixture.put(net, dLink[i], oLink[j], seg);
			}
		}

		double requestTime = 0.0;
		double directTT    = 100.0;
		double earliestDep = requestTime - 3_600.0;
		double latestArr   = requestTime + 36_000.0 + directTT;

		DrtRequest[] requests = new DrtRequest[n];
		for (int i = 0; i < n; i++) {
			requests[i] = DrtRequest.builder()
					.index(i)
					.personId(Id.create("p" + i, Person.class))
					.groupId("g" + i)
					.tripIndex(0)
					.isCommute(false)
					.isEducation(false)
					.budget(10.0)
					.bestModeScore(-5.0)
					.bestMode("car")
					.originLinkId(oLink[i])
					.destinationLinkId(dLink[i])
					.originX(i * 1_000.0)
					.originY(0.0)
					.destinationX(i * 1_000.0)
					.destinationY(5_000.0)
					.requestTime(requestTime)
					.earliestDeparture(earliestDep)
					.latestArrival(latestArr)
					.directTravelTime(directTT)
					.directDistance(5_000.0)
					.maxDetourFactor(100.0)
					.maxWalkDistance(0.0)
					.maxWaitTime(36_000.0)
					.originActivityType("home")
					.destinationActivityType("work")
					.carTravelTime(directTT)
					.ptTravelTime(200.0)
					.ptAccessibility(1.0)
					.build();
		}

		return new TestSetup(requests, graph, net);
	}
}
