package org.matsim.contrib.demand_extraction.algorithm.bamas.extension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * Design A — per-set ordering node budget B ({@code maxOrderingNodesAfterFirstValid}).
 *
 * <p>The cap descends to the first budget-valid ordering unconditionally (the
 * feasibility floor), then allows at most B more DFS nodes before aborting the
 * set and returning the best ride found in {@code [firstValid, firstValid + B]}.
 * It is wired into the seeded production path, so these tests drive
 * {@link OrderingEnumerator#enumerateAndEvaluateSeeded} and read the always-live
 * per-set node counters on {@link EnumerationStats}.
 *
 * <p>Contract verified:
 * <ul>
 *   <li>B disabled (0): unchanged — explores the full tree, finds the optimum.</li>
 *   <li>B small (1): still returns a valid ride (feasibility preserved), explores
 *       strictly fewer nodes, and never beats the uncapped optimum.</li>
 *   <li>The predicate never fires before a valid ordering exists (descend-to-
 *       first-valid is unconditional).</li>
 * </ul>
 */
class OrderingNodeBudgetTest {

    private static final double SEG_TT   = 100.0; // seconds per hop
    private static final double SEG_DIST = 1.0;   // metres (uniform → equal-distance orderings)

    @AfterEach
    void disableCap() {
        EnumerationStats.setMaxOrderingNodesAfterFirstValid(0);
    }

    /**
     * With the cap off the seeded DFS finds the optimum; with B=1 it still returns a
     * valid ride, explores strictly fewer nodes, and is never better than the optimum.
     */
    @Test
    void capPreservesValidRideAndCutsNodes() {
        TestSetup setup = buildUniformSetup(4);
        int[] set = {0, 1, 2, 3};
        int[] seedOrigin = {0, 1, 2};
        int[] seedDest = {0, 1, 2};
        int seedNew = 3;

        // Baseline: cap off (B = 0), full exploration.
        EnumerationStats.setMaxOrderingNodesAfterFirstValid(0);
        double[] best0 = {Double.MAX_VALUE};
        Ordering[] kept0 = {null};
        OrderingEnumerator.enumerateAndEvaluateSeeded(
                set, setup.graph, setup.network, setup.requests, best0,
                seedOrigin, seedDest, seedNew, /* budgetAwareConstraints= */ false,
                o -> { if (o.rideDistance() < best0[0]) { best0[0] = o.rideDistance(); kept0[0] = o; } });
        long fullNodes = EnumerationStats.get().curSetNodes;
        long firstValid = EnumerationStats.get().curSetNodesFirstValid;

        assertNotNull(kept0[0], "uncapped run must return a ride");
        assertTrue(fullNodes > firstValid + 1,
                "fixture must explore beyond first-valid for the cap to be meaningful "
                + "(full=" + fullNodes + ", firstValid=" + firstValid + ")");

        // Capped: B = 1 node after first-valid.
        EnumerationStats.setMaxOrderingNodesAfterFirstValid(1);
        double[] best1 = {Double.MAX_VALUE};
        Ordering[] kept1 = {null};
        OrderingEnumerator.enumerateAndEvaluateSeeded(
                set, setup.graph, setup.network, setup.requests, best1,
                seedOrigin, seedDest, seedNew, /* budgetAwareConstraints= */ false,
                o -> { if (o.rideDistance() < best1[0]) { best1[0] = o.rideDistance(); kept1[0] = o; } });
        long cappedNodes = EnumerationStats.get().curSetNodes;

        assertNotNull(kept1[0], "feasibility preserved: a valid ride is still returned under B=1");
        assertTrue(cappedNodes < fullNodes,
                "cap must explore fewer nodes (capped=" + cappedNodes + ", full=" + fullNodes + ")");
        assertTrue(best1[0] >= best0[0],
                "capped ride is never better than the uncapped optimum "
                + "(capped=" + best1[0] + ", optimum=" + best0[0] + ")");
    }

    /**
     * Descend-to-first-valid is unconditional: the predicate must return false while
     * no valid ordering has been found yet, no matter how many nodes have been entered.
     */
    @Test
    void budgetNeverFiresBeforeFirstValid() {
        EnumerationStats.setMaxOrderingNodesAfterFirstValid(1);
        EnumerationStats s = EnumerationStats.get();
        s.probeSetStart();
        s.curSetNodes = 1_000_000;          // deep in the tree...
        s.curSetNodesFirstValid = -1;        // ...but no valid ordering yet
        assertFalse(s.orderingBudgetExhausted(),
                "budget must not fire before the first valid ordering (descend-to-first-valid)");

        s.curSetNodesFirstValid = 1_000_000; // first valid just found here
        s.curSetNodes = 1_000_001;           // exactly B=1 node later: still within budget
        assertFalse(s.orderingBudgetExhausted(), "exactly B nodes after first-valid is within budget");
        s.curSetNodes = 1_000_002;           // B+1 nodes later: budget spent
        assertTrue(s.orderingBudgetExhausted(), "more than B nodes after first-valid exhausts the budget");
    }

    // ── Fixture ───────────────────────────────────────────────────────────

    private record TestSetup(DrtRequest[] requests, ShareabilityGraph graph, MatsimNetworkCache network) {}

    /**
     * n requests, all FIFO+LIFO both directions (no ordering constraints), uniform
     * segment time/distance everywhere. Equal-distance orderings → the first complete
     * ordering sets bestValidDist and no later ordering improves it (improvement
     * window 0, matching production's p50), so firstValid is well below nodesTotal.
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
