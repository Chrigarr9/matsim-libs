package org.matsim.contrib.demand_extraction.algorithm.extension;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;
import org.matsim.contrib.demand_extraction.algorithm.graph.ShareabilityGraph;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCacheTestFixture;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

/**
 * TDD – T5: verifies that {@code enumerateAndEvaluateSeeded} visits the
 * parent-consistent ordering FIRST in the origin DFS.
 *
 * <p>The test deliberately chooses a parent origin order {2, 1, 0} that
 * REVERSES the cheapest-next-segment order {0, 1, 2, 3}.  Under the current
 * stub implementation (which ignores the seed and falls back to
 * {@code enumerateAndEvaluate}), the DFS visits [0, 1, 2, 3] first — so the
 * assertion that r2 precedes r1 precedes r0 in the first visited ordering
 * fails with a meaningful message.
 *
 * <p>T6 will replace the stub with the real parent-biased sort, making this
 * test pass.
 */
class ParentConsistentSortTest {

    // ── Main test ─────────────────────────────────────────────────────────

    @Test
    void firstVisitedOrderingIsParentConsistent() {
        TestSetup setup = buildFourRequestSet();
        int[] requestIndices = {0, 1, 2, 3};

        // Parent origin order: reversed relative to the cheapest-sort order.
        // Cheapest-next-segment visits [0, 1, 2, 3] first; the parent claims
        // {2, 1, 0} — so r2 before r1 before r0 (r3 is the new request).
        int[] parentOrigin = {2, 1, 0};
        int[] parentDest   = {2, 1, 0};
        int   newRequest   = 3;

        List<int[]> visitedOriginPerms = new ArrayList<>();
        double[] bestValidDist = {Double.POSITIVE_INFINITY};

        OrderingEnumerator.enumerateAndEvaluateSeeded(
                requestIndices, setup.graph, setup.network, setup.requests,
                bestValidDist,
                parentOrigin, parentDest, newRequest,
                ordering -> visitedOriginPerms.add(ordering.originPerm().clone()));

        assertTrue(visitedOriginPerms.size() > 0,
                "At least one ordering should be visited");

        // The first visited ordering must preserve the parent's relative origin
        // order: r2 before r1 before r0 (r3 can be inserted anywhere).
        int[] firstOrigin = visitedOriginPerms.get(0);
        int posOf2 = indexOf(firstOrigin, 2);
        int posOf1 = indexOf(firstOrigin, 1);
        int posOf0 = indexOf(firstOrigin, 0);

        assertTrue(posOf2 < posOf1,
                "Parent origin order violated: r2 should come before r1. Got "
                        + Arrays.toString(firstOrigin));
        assertTrue(posOf1 < posOf0,
                "Parent origin order violated: r1 should come before r0. Got "
                        + Arrays.toString(firstOrigin));
    }

    /**
     * T7: verifies that {@code enumerateAndEvaluateSeeded} visits the
     * parent-consistent ordering FIRST in the dest DFS.
     *
     * <p>The fixture's D→D distances are all uniform (100.0), so the unseeded
     * dest DFS sorts by cheapest-next-segment and — with equal distances —
     * falls back to insertion order (ascending index). For origins {@code [0,1,2,3]}
     * FIFO constraints force exactly one valid dest permutation: {@code [0,1,2,3]}.
     * The parent claims {@code {2, 1, 0}} which disagrees (reversed relative to
     * ascending index). The assertion that r2 precedes r1 precedes r0 therefore
     * fails under the current unseeded dest DFS.
     *
     * <p>T8 will add the seeded dest DFS, making this test pass.
     */
    @Test
    void firstVisitedOrderingIsParentConsistentForDest() {
        TestSetup setup = buildFourRequestSet();
        int[] requestIndices = {0, 1, 2, 3};
        // Natural parent origin order — not what we're testing here.
        // The seeded origin DFS will visit [0,1,2,3] first, which is also
        // the cheapest-next-segment order, so no surprises on the origin side.
        int[] parentOrigin = {0, 1, 2};
        // Reversed parent dest order — should force the seeded dest DFS to prefer
        // r2 → r1 → r0. Under the unseeded dest DFS all D→D distances are equal
        // so the DFS iterates by ascending index, producing [0,1,2,3] first.
        int[] parentDest = {2, 1, 0};
        int newRequest = 3;

        List<int[]> visitedDestPerms = new ArrayList<>();
        double[] bestValidDist = {Double.POSITIVE_INFINITY};

        OrderingEnumerator.enumerateAndEvaluateSeeded(
                requestIndices, setup.graph, setup.network, setup.requests,
                bestValidDist,
                parentOrigin, parentDest, newRequest,
                ordering -> visitedDestPerms.add(ordering.destPerm().clone()));

        assertTrue(visitedDestPerms.size() > 0,
                "At least one ordering should be visited");

        // The first visited ordering must preserve the parent's relative dest
        // order: r2 before r1 before r0 (r3 can be inserted anywhere).
        int[] firstDest = visitedDestPerms.get(0);
        int posOf2 = indexOf(firstDest, 2);
        int posOf1 = indexOf(firstDest, 1);
        int posOf0 = indexOf(firstDest, 0);

        assertTrue(posOf2 < posOf1,
                "Parent dest order violated: r2 should come before r1. Got "
                        + Arrays.toString(firstDest));
        assertTrue(posOf1 < posOf0,
                "Parent dest order violated: r1 should come before r0. Got "
                        + Arrays.toString(firstDest));
    }

    // ── Helper ───────────────────────────────────────────────────────────

    private static int indexOf(int[] arr, int val) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == val) return i;
        return -1;
    }

    // ── Test setup ────────────────────────────────────────────────────────

    private static class TestSetup {
        ShareabilityGraph graph;
        MatsimNetworkCache network;
        DrtRequest[] requests;
    }

    /**
     * Build a 4-request synthetic set.
     *
     * <h3>Shareability graph</h3>
     * Every pair (i, j) has edges in BOTH directions with kind FIFO, so no
     * origin-order constraint is imposed by the graph. All 24 origin
     * permutations are valid. The {@code extractConstraints} check passes
     * because every pair has at least one edge.
     *
     * <h3>Network cache</h3>
     * Distances from O_i to O_j are assigned so that sorting by
     * cheapest-next-segment places origins in ascending index order {0,1,2,3}:
     * <ul>
     *   <li>From O0: dist(→O1)=100, dist(→O2)=200, dist(→O3)=300</li>
     *   <li>From O1: dist(→O2)=100, dist(→O3)=200</li>
     *   <li>From O2: dist(→O3)=100</li>
     *   <li>Reverse segments follow the same pattern for completeness.</li>
     * </ul>
     * All origin→dest and dest→dest segments use dist=100 (uniform) so that
     * the destination phase completes without B&amp;B cuts and the evaluator
     * callback fires on the first attempted dest ordering.
     *
     * <h3>DRT requests</h3>
     * Very generous time windows and maxDetourFactor ensure no pruning fires
     * inside the enumeration. The {@code bestValidDist} is kept at
     * {@code +Infinity} (evaluator never updates it) so no B&amp;B cut fires
     * during the origin phase either.
     */
    private static TestSetup buildFourRequestSet() {
        TestSetup setup = new TestSetup();
        int n = 4;

        // ── Link IDs ───────────────────────────────────────────────────────
        // Each request has a distinct origin link O_i and destination link D_i.
        @SuppressWarnings("unchecked")
        Id<Link>[] oLink = new Id[n];
        @SuppressWarnings("unchecked")
        Id<Link>[] dLink = new Id[n];
        for (int i = 0; i < n; i++) {
            oLink[i] = Id.createLinkId("O" + i);
            dLink[i] = Id.createLinkId("D" + i);
        }

        // ── ShareabilityGraph ──────────────────────────────────────────────
        // Add both FIFO and LIFO edges for every ordered pair (i→j and j→i).
        // Having both FIFO and LIFO for each direction means the dest DAG has NO
        // constraints (hasFifo && hasLifo → no constraint), so all n! dest
        // orderings are valid. This lets the seeded dest DFS demonstrate its
        // sort bias by choosing the parent-consistent ordering as the first
        // visited, regardless of distance tiebreak.
        // rideIndex is arbitrary — it's only used by the graph to store
        // edge metadata and is not checked during ordering enumeration.
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
        setup.graph = gb.build();

        // ── MatsimNetworkCache ─────────────────────────────────────────────
        // Bypass Guice / router via the test fixture that exposes the
        // package-private forTesting hook. Pre-populate every segment that the
        // DFS will query. With timeBinSize = Integer.MAX_VALUE (set inside
        // forTesting), all departure times map to bin 0, matching the keys
        // written by MatsimNetworkCacheTestFixture.put.
        MatsimNetworkCache net = MatsimNetworkCacheTestFixture.create();

        // Travel time = distance / 10 (10 m/s ≈ 36 km/h). Very fast to keep
        // all passengers inside their maxTravelTime (6000 s).

        // Origin→Origin segments. Distance grows with index difference so that
        // cheapest-sort produces ascending {0,1,2,3} regardless of start node.
        int[][] ooDistTable = {
            //          O0   O1   O2   O3
            /* O0 */ {   0, 100, 200, 300 },
            /* O1 */ { 100,   0, 100, 200 },
            /* O2 */ { 200, 100,   0, 100 },
            /* O3 */ { 300, 200, 100,   0 }
        };
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i != j) {
                    double dist = ooDistTable[i][j];
                    MatsimNetworkCacheTestFixture.put(net, oLink[i], oLink[j], seg(dist));
                }
            }
        }

        // Origin→Dest segments (from last origin pickup to first dropoff).
        // All uniform — we don't care about dest ordering for this test.
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                MatsimNetworkCacheTestFixture.put(net, oLink[i], dLink[j], seg(100.0));
            }
        }

        // Dest→Dest segments (between dropoffs). All uniform.
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i != j) {
                    MatsimNetworkCacheTestFixture.put(net, dLink[i], dLink[j], seg(100.0));
                }
            }
        }

        // Dest→Origin segments: needed by computeMinIn (T11) which queries all-pairs
        // among the 2n stops. Use uniform 150 m so minIn sees these as potential sources.
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                MatsimNetworkCacheTestFixture.put(net, dLink[i], oLink[j], seg(150.0));
            }
        }

        setup.network = net;

        // ── DrtRequest array ───────────────────────────────────────────────
        // Generous windows: ±1h departure flexibility, maxDetourFactor=100,
        // directTravelTime=600 s (10 min). All requests depart at 8 am.
        double requestTime     = 8 * 3600;            // 28 800 s
        double directTT        = 600.0;
        double earliestDep     = requestTime - 3600;  // 7 am
        double latestArr       = requestTime + 4 * 3600 + directTT; // generous
        double maxDetour       = 100.0;

        setup.requests = new DrtRequest[n];
        for (int i = 0; i < n; i++) {
            setup.requests[i] = DrtRequest.builder()
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
                    .originX(i * 1000.0)
                    .originY(0.0)
                    .destinationX(i * 1000.0)
                    .destinationY(5000.0)
                    .requestTime(requestTime)
                    .earliestDeparture(earliestDep)
                    .latestArrival(latestArr)
                    .directTravelTime(directTT)
                    .directDistance(5000.0)
                    .maxDetourFactor(maxDetour)
                    .maxWalkDistance(0.0)
                    .originActivityType("home")
                    .destinationActivityType("work")
                    .carTravelTime(directTT)
                    .ptTravelTime(1200.0)
                    .ptAccessibility(2.0)
                    .build();
        }

        return setup;
    }

    /** Convenience: make a TravelSegment with travelTime = dist / 10, util = 0. */
    private static TravelSegment seg(double dist) {
        return new TravelSegment(dist / 10.0, dist, 0.0);
    }
}
