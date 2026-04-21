package org.matsim.contrib.demand_extraction.algorithm.bamas.extension;

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
 * Regression test for the "unsound break in seeded DFS" bug.
 *
 * <p>The seeded origin DFS used to sort candidates by a two-level key
 * (primary = parentConsistentRank, secondary = segment distance) and break
 * on the first candidate whose cumulative distance exceeded
 * {@code bestValidDist[0]}. That break was unsound: rank-1 candidates with
 * shorter distances sit behind rank-0 candidates and were silently skipped
 * when an expensive rank-0 triggered the break.
 *
 * <p>This test constructs an adversarial 4-request fixture where:
 * <ul>
 *   <li>At depth 1 from origin O0, the rank-0 group is {O1 (expensive), O3 (cheap)}
 *       and the rank-1 group is {O2 (medium)}.</li>
 *   <li>{@code bestValidDist[0]} is pre-set so that O1's distance triggers
 *       the distance cut, but O2's distance does not.</li>
 * </ul>
 *
 * <p>Under the buggy single-loop form, the sort order is
 * {@code [O3, O1, O2]}: O3 is visited, O1 triggers break, O2 is skipped.
 * Under the fix (separate rank-0 and rank-1 loops with independent breaks),
 * O2 IS visited because rank-1 has its own loop.
 *
 * <p>Assertion: the DFS must visit at least one ordering in which request 2
 * sits at origin-position 1 (right after request 0). Under the bug this
 * never happens via the depth-0 = 0 entry path.
 */
class UnsoundBreakRegressionTest {

    @Test
    void rank1CandidateVisitedAfterExpensiveRank0() {
        TestSetup setup = buildFourRequestSet();
        int[] requestIndices = {0, 1, 2, 3};

        // Parent origin = {0, 1, 2}, newRequest = 3.
        // Depth 0: rank-0 = {0, 3} (next parent = 0, new request = 3).
        // Depth 1 (after placing 0): next parent = 1, new request = 3.
        //   rank-0 = {1, 3}, rank-1 = {2}.
        int[] parentOrigin = {0, 1, 2};
        int[] parentDest   = {0, 1, 2};
        int   newRequest   = 3;

        // Pre-tighten bestValidDist so the depth-1 rank-0 candidate with
        // distance 100 triggers the cut, while the rank-1 candidate with
        // distance 20 does not.
        double[] bestValidDist = {50.0};

        List<int[]> visitedOriginPerms = new ArrayList<>();
        OrderingEnumerator.enumerateAndEvaluateSeeded(
                requestIndices, setup.graph, setup.network, setup.requests,
                bestValidDist,
                parentOrigin, parentDest, newRequest,
                ordering -> visitedOriginPerms.add(ordering.originPerm().clone()));

        // The fix must visit an ordering where request 0 is at position 0
        // AND request 2 is at position 1 (the rank-1 candidate that was
        // silently skipped under the bug).
        boolean visitedZeroThenTwo = false;
        for (int[] perm : visitedOriginPerms) {
            if (perm.length >= 2 && perm[0] == 0 && perm[1] == 2) {
                visitedZeroThenTwo = true;
                break;
            }
        }
        assertTrue(visitedZeroThenTwo,
                "Expected DFS to visit an ordering starting with [0, 2, ...] "
                + "(rank-1 candidate reached after expensive rank-0 triggered cut). "
                + "Visited " + visitedOriginPerms.size() + " orderings; none matched. "
                + "First few: " + formatFirstFew(visitedOriginPerms, 5));
    }

    private static String formatFirstFew(List<int[]> perms, int k) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < Math.min(k, perms.size()); i++) {
            if (i > 0) sb.append(", ");
            sb.append(Arrays.toString(perms.get(i)));
        }
        if (perms.size() > k) sb.append(", ...");
        sb.append("]");
        return sb.toString();
    }

    // ── Test setup ────────────────────────────────────────────────────────

    private static class TestSetup {
        ShareabilityGraph graph;
        MatsimNetworkCache network;
        DrtRequest[] requests;
    }

    /**
     * Build a 4-request synthetic fixture. The graph has all FIFO+LIFO edges
     * (no constraints), so any origin permutation is structurally valid.
     *
     * <p>Origin-to-origin distances are chosen so that from O0:
     * <ul>
     *   <li>O0 → O1 = 100 (rank-0 expensive, triggers the distance cut)</li>
     *   <li>O0 → O2 = 20  (rank-1, shorter than the cut threshold)</li>
     *   <li>O0 → O3 = 5   (rank-0 cheap, visited first)</li>
     * </ul>
     * With {@code bestValidDist[0] = 50}, the buggy single-loop form visits
     * O3, breaks on O1, and never reaches O2. All other distances (O→D, D→D,
     * D→O, and segments from non-O0 starts) are kept at 1 so the recursion
     * can complete and the evaluator callback fires.
     */
    private static TestSetup buildFourRequestSet() {
        TestSetup setup = new TestSetup();
        int n = 4;

        @SuppressWarnings("unchecked")
        Id<Link>[] oLink = new Id[n];
        @SuppressWarnings("unchecked")
        Id<Link>[] dLink = new Id[n];
        for (int i = 0; i < n; i++) {
            oLink[i] = Id.createLinkId("O" + i);
            dLink[i] = Id.createLinkId("D" + i);
        }

        // Shareability graph: all FIFO+LIFO → no origin-order constraints.
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

        MatsimNetworkCache net = MatsimNetworkCacheTestFixture.create();

        // Origin→Origin: adversarial distances from O0; rest uniform small.
        double[][] ooDist = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                ooDist[i][j] = (i == j) ? 0.0 : 1.0;
            }
        }
        ooDist[0][1] = 100.0;  // rank-0 expensive (triggers cut)
        ooDist[0][2] = 20.0;   // rank-1 (must be reached after fix)
        ooDist[0][3] = 5.0;    // rank-0 cheap (visited first)

        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i != j) {
                    MatsimNetworkCacheTestFixture.put(net, oLink[i], oLink[j], seg(ooDist[i][j]));
                }
            }
        }

        // Origin→Dest, Dest→Dest, Dest→Origin: uniform 1 m so recursion can
        // complete under the 50 m bestValidDist ceiling.
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                MatsimNetworkCacheTestFixture.put(net, oLink[i], dLink[j], seg(1.0));
                if (i != j) {
                    MatsimNetworkCacheTestFixture.put(net, dLink[i], dLink[j], seg(1.0));
                }
                MatsimNetworkCacheTestFixture.put(net, dLink[i], oLink[j], seg(1.0));
            }
        }

        setup.network = net;

        // Generous time windows — no pruning via delay windows or maxTravelTime.
        double requestTime = 8 * 3600;
        double directTT    = 600.0;
        double earliestDep = requestTime - 3600;
        double latestArr   = requestTime + 4 * 3600 + directTT;

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
                    .maxDetourFactor(100.0)
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

    private static TravelSegment seg(double dist) {
        return new TravelSegment(dist / 10.0, dist, 0.0);
    }
}
