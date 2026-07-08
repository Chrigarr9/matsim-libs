package org.matsim.contrib.demand_extraction.algorithm.bamas.extension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

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
 * Insertion-first search: the seeded ordering DFS runs a rank-0-only (pure
 * insertion) pass before the full search. A pass driven with
 * {@code insertionOnly == true} must freeze the parent's pickup/dropoff order and
 * only slot in the new request — so every ordering it evaluates is
 * parent-consistent. The complementary full pass ({@code insertionOnly == false})
 * still reaches parent reorderings, so no feasible ordering is lost.
 */
class OrderingEnumeratorInsertionFirstTest {

    private static final double SEG_TT   = 100.0; // seconds per hop
    private static final double SEG_DIST = 1.0;   // metres (uniform → equal-distance orderings)

    /** Parent requests in the seed order below; the new request is index 3. */
    private static final int[] PARENTS = {0, 1, 2};

    @AfterEach
    void resetThreadState() {
        EnumerationStats.setMaxOrderingNodesAfterFirstValid(0);
        EnumerationStats.get().insertionOnly = false;
    }

    /**
     * The insertion-first pass evaluates only parent-consistent orderings: in both
     * the pickup and dropoff permutation, parents 0,1,2 keep their seed order and
     * only the new request (3) moves.
     */
    @Test
    void insertionPassEvaluatesOnlyParentConsistentOrderings() {
        List<Ordering> seen = runPass(/* insertionOnly= */ true);

        assertFalse(seen.isEmpty(), "insertion pass must evaluate at least one ordering");
        for (Ordering o : seen) {
            assertTrue(isParentConsistent(o.originPerm()),
                    "pickup order must keep parents in seed order: " + java.util.Arrays.toString(o.originPerm()));
            assertTrue(isParentConsistent(o.destPerm()),
                    "dropoff order must keep parents in seed order: " + java.util.Arrays.toString(o.destPerm()));
        }
    }

    /**
     * The full pass is unrestricted: it reaches at least one parent reordering, so the
     * fallback that runs after insertion-first can still recover orderings the
     * insertion pass cannot express.
     */
    @Test
    void fullPassStillReachesReorderings() {
        List<Ordering> seen = runPass(/* insertionOnly= */ false);

        boolean anyReordering = seen.stream().anyMatch(
                o -> !isParentConsistent(o.originPerm()) || !isParentConsistent(o.destPerm()));
        assertTrue(anyReordering, "full pass must reach at least one parent reordering");
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private List<Ordering> runPass(boolean insertionOnly) {
        TestSetup setup = buildUniformSetup(4);
        int[] set = {0, 1, 2, 3};
        int[] seedOrigin = {0, 1, 2};
        int[] seedDest = {0, 1, 2};
        int seedNew = 3;

        EnumerationStats.get().probeSetStart();
        List<Ordering> seen = new ArrayList<>();
        double[] best = {Double.MAX_VALUE};
        OrderingEnumerator.enumerateSeededPass(
                set, setup.graph, setup.network, setup.requests, best,
                seedOrigin, seedDest, seedNew, /* budgetAwareConstraints= */ false,
                /* firstValidNodeCap= */ 0L, insertionOnly,
                o -> { seen.add(o); if (o.rideDistance() < best[0]) best[0] = o.rideDistance(); });
        return seen;
    }

    /** A permutation is parent-consistent iff parents 0,1,2 appear in seed order. */
    private static boolean isParentConsistent(int[] perm) {
        int[] pos = new int[PARENTS.length];
        for (int i = 0; i < perm.length; i++) {
            for (int p = 0; p < PARENTS.length; p++) {
                if (perm[i] == PARENTS[p]) pos[p] = i;
            }
        }
        return pos[0] < pos[1] && pos[1] < pos[2];
    }

    // ── Fixture (mirrors OrderingNodeBudgetTest#buildUniformSetup) ──────────

    private record TestSetup(DrtRequest[] requests, ShareabilityGraph graph, MatsimNetworkCache network) {}

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
