package org.matsim.contrib.demand_extraction.algorithm.bamas.extension;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
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
 * Admissibility regression test for the minIn-based lower bound used by
 * the LB B&B cut (T11-T13). Verifies that for every completed ordering of
 * a synthetic 3-set, the total ride distance is greater than or equal to
 * sum(minIn[all stops]). This is the soundness property that justifies
 * the cut predicate partialDist + totalMinInRemaining > bestValidDist[0].
 *
 * <p>minIn[stop] is defined as the minimum incoming segment distance over all
 * possible predecessor stops (any other stop in the set). It is therefore a
 * lower bound on the actual segment that must enter the given stop in any
 * complete ordering. Summing over all stops (excluding the very first, which
 * has no predecessor) gives a lower bound on the total ride distance.
 *
 * <p>This test does NOT depend on any production minIn helper — it computes
 * minIn directly so it acts as an independent regression guard.
 */
class MinInLowerBoundTest {

    @Test
    void lowerBoundIsAdmissibleForAllOrderings() {
        TestSetup setup = buildThreeRequestSet();
        int[] requestIndices = {0, 1, 2};
        // 2-element parent {0, 1} with new request 2 — valid k=3 case.
        int[] parentOrigin = {0, 1};
        int[] parentDest   = {0, 1};
        int   newRequest   = 2;

        // Collect connDist arrays for every completed ordering.
        // bestValidDist[0] = +Infinity so NO B&B cuts fire — we see every ordering.
        List<double[]> perOrderingConnDist = new ArrayList<>();
        double[] bestValidDist = { Double.POSITIVE_INFINITY };

        OrderingEnumerator.enumerateAndEvaluateSeeded(
                requestIndices, setup.graph, setup.network, setup.requests,
                bestValidDist,
                parentOrigin, parentDest, newRequest,
                /* budgetAwareConstraints= */ false,
                ordering -> perOrderingConnDist.add(ordering.connDist().clone()));

        assertTrue(perOrderingConnDist.size() > 0,
                "At least one ordering should be visited");

        // Compute minIn[] in the test using the same definition T11 will use:
        //   minIn[stop] = min over all other stops of routedDist(other → stop).
        // Indexing: entries 0..n-1 are pickup stops (origin of request i);
        //           entries n..2n-1 are dropoff stops (destination of request i-n).
        double[] minIn = computeMinIn(setup);
        double totalMinIn = 0.0;
        for (double v : minIn) totalMinIn += v;

        // Admissibility: for every completed ordering, total ride distance
        // (sum of connDist entries) must be >= totalMinIn.
        // connDist has length 2*n - 1 (one segment per inter-stop hop).
        // Note: totalMinIn sums minIn over ALL 2*n stops. The first stop in a
        // completed ordering has no incoming segment, so its minIn value is 0
        // (every stop has at least one predecessor other than itself in a set of
        // size >= 2, but the FIRST stop genuinely contributes 0 to total dist).
        // For a 3-request (6-stop) ordering, there are exactly 5 segments.
        // The bound is: sum(minIn[2..6]) <= total_dist (minIn[first stop] = 0
        // if we define it correctly, OR totalMinIn <= total_dist by the
        // admissibility argument regardless of definition, since minIn values
        // computed over ALL stops still sum to <= total_dist for any ordering).
        int visitedCount = perOrderingConnDist.size();
        for (int i = 0; i < visitedCount; i++) {
            double[] cd = perOrderingConnDist.get(i);
            double total = 0.0;
            for (double v : cd) total += v;
            assertTrue(total >= totalMinIn - 1e-6,
                    "Admissibility violated at ordering " + i + ": total="
                            + total + ", sum(minIn)=" + totalMinIn
                            + ", connDist.length=" + cd.length);
        }

        // Report for self-review
        System.out.println("MinInLowerBoundTest: visited " + visitedCount
                + " orderings, totalMinIn=" + totalMinIn);
    }

    /**
     * Admissibility test for pure-Euclidean minIn.
     *
     * <p>{@link OrderingEnumerator#computeMinIn} computes the straight-line
     * (Euclidean) distance between stop coordinates. Beeline is unconditionally
     * {@code <=} any routed path at any departure time under any travel-time
     * scenario, so it's the only lower bound we can compute without routing
     * every time bin upfront. This test asserts that every {@code minIn[to]}
     * equals the minimum Euclidean incoming distance, regardless of cache
     * contents.
     */
    @Test
    void computeMinInReturnsMinEuclideanIncoming() {
        MatsimNetworkCache cache = MatsimNetworkCacheTestFixture.create();

        Id<Link> o0 = Id.createLinkId("O0");
        Id<Link> o1 = Id.createLinkId("O1");
        Id<Link> d0 = Id.createLinkId("D0");
        Id<Link> d1 = Id.createLinkId("D1");

        // Coordinates chosen so every stop-pair has a distinct beeline
        // distance and the min-over-from values are easy to verify by hand.
        //   O0 = (0, 0)       D0 = (0, 5000)
        //   O1 = (1000, 0)    D1 = (1000, 5000)
        // Beeline table:
        //   O0↔O1 = 1000,   D0↔D1 = 1000
        //   O0↔D0 = 5000,   O1↔D1 = 5000
        //   O0↔D1 = sqrt(1000² + 5000²) ≈ 5099.02
        //   O1↔D0 = sqrt(1000² + 5000²) ≈ 5099.02
        // minIn[O0] = min(1000, 5000, 5099) = 1000
        // minIn[O1] = min(1000, 5099, 5000) = 1000
        // minIn[D0] = min(5000, 5099, 1000) = 1000
        // minIn[D1] = min(5099, 5000, 1000) = 1000
        DrtRequest r0 = beelineRequest(0, o0, d0, 0, 0, 0, 5000);
        DrtRequest r1 = beelineRequest(1, o1, d1, 1000, 0, 1000, 5000);

        double[] minIn = OrderingEnumerator.computeMinIn(2, cache, new DrtRequest[] { r0, r1 });

        org.junit.jupiter.api.Assertions.assertEquals(1000.0, minIn[0], 1e-9,
                "minIn[O0] should fall back to min beeline = 1000 (O1->O0)");
        org.junit.jupiter.api.Assertions.assertEquals(1000.0, minIn[1], 1e-9,
                "minIn[O1] should fall back to min beeline = 1000 (O0->O1)");
        org.junit.jupiter.api.Assertions.assertEquals(1000.0, minIn[2], 1e-9,
                "minIn[D0] should fall back to min beeline = 1000 (D1->D0)");
        org.junit.jupiter.api.Assertions.assertEquals(1000.0, minIn[3], 1e-9,
                "minIn[D1] should fall back to min beeline = 1000 (D0->D1)");
    }

    private static DrtRequest beelineRequest(int i, Id<Link> oLink, Id<Link> dLink,
                                             double ox, double oy, double dx, double dy) {
        // Tests model each stop as a point — both link.fromNode and link.toNode
        // coincide with the stop coordinate. This makes computeMinIn's
        // (outX/Y, inX/Y) asymmetry collapse back to simple point-to-point
        // beeline, matching the test's assertions.
        return DrtRequest.builder()
                .index(i)
                .personId(Id.create("p" + i, Person.class))
                .groupId("g" + i).tripIndex(0).isCommute(false).isEducation(false)
                .budget(10.0).bestModeScore(-5.0).bestMode("car")
                .originLinkId(oLink).destinationLinkId(dLink)
                .originX(ox).originY(oy).destinationX(dx).destinationY(dy)
                .originLinkCoordFromX(ox).originLinkCoordFromY(oy)
                .originLinkCoordToX(ox).originLinkCoordToY(oy)
                .destinationLinkCoordFromX(dx).destinationLinkCoordFromY(dy)
                .destinationLinkCoordToX(dx).destinationLinkCoordToY(dy)
                .requestTime(0).earliestDeparture(0).latestArrival(3600)
                .directTravelTime(600).directDistance(0).maxDetourFactor(100)
                .maxWalkDistance(0)
                .originActivityType("home").destinationActivityType("work")
                .carTravelTime(600).ptTravelTime(1200).ptAccessibility(2.0)
                .build();
    }

    /**
     * Compute minIn[] for the set: minimum incoming segment distance per stop.
     *
     * Indexing: entries 0..n-1 are pickup stops (origin of request i),
     * entries n..2n-1 are dropoff stops (destination of request i-n).
     *
     * minIn[to] = min over all 'from' != to of routedDist(from -> to).
     */
    @SuppressWarnings("unchecked")
    private static double[] computeMinIn(TestSetup setup) {
        int n = setup.requests.length;
        Id<Link>[] stopLinks = new Id[2 * n];
        for (int i = 0; i < n; i++) {
            stopLinks[i]     = setup.requests[i].originLinkId;
            stopLinks[i + n] = setup.requests[i].destinationLinkId;
        }
        double[] minIn = new double[2 * n];
        java.util.Arrays.fill(minIn, Double.POSITIVE_INFINITY);
        for (int to = 0; to < 2 * n; to++) {
            for (int from = 0; from < 2 * n; from++) {
                if (from == to) continue;
                double d = setup.network.getSegment(stopLinks[from], stopLinks[to], 0.0)
                        .getDistance();
                if (d < minIn[to]) minIn[to] = d;
            }
        }
        return minIn;
    }

    // ── Test setup helper ─────────────────────────────────────────────────

    private static class TestSetup {
        ShareabilityGraph graph;
        MatsimNetworkCache network;
        DrtRequest[] requests;
    }

    /**
     * Build a synthetic 3-request set.
     *
     * <h3>Shareability graph</h3>
     * Every ordered pair (i, j) has BOTH FIFO and LIFO edges in both directions,
     * so there are no topological constraints — all 6 origin permutations and
     * all 6 dest permutations are valid (up to delay-window pruning).
     *
     * <h3>Network cache</h3>
     * Origin→Origin distances follow an asymmetric table so that different
     * orderings yield different total distances. All O→D and D→D and D→O
     * segments are also populated. Distances are chosen so that:
     * <ul>
     *   <li>Every segment has a positive distance</li>
     *   <li>The minimum incoming distance per stop (minIn[stop]) is well-defined</li>
     *   <li>sum(minIn) is strictly less than the minimum possible total ordering
     *       distance — i.e. the admissibility property holds with margin</li>
     * </ul>
     *
     * <h3>Time windows</h3>
     * Very generous delay windows (±1h) and maxDetourFactor=100 ensure no
     * delay-window or travel-time pruning fires, so all 36 orderings (6 × 6)
     * are visited.
     */
    private static TestSetup buildThreeRequestSet() {
        TestSetup setup = new TestSetup();
        int n = 3;

        // ── Link IDs ───────────────────────────────────────────────────────
        @SuppressWarnings("unchecked")
        Id<Link>[] oLink = new Id[n];
        @SuppressWarnings("unchecked")
        Id<Link>[] dLink = new Id[n];
        for (int i = 0; i < n; i++) {
            oLink[i] = Id.createLinkId("O" + i);
            dLink[i] = Id.createLinkId("D" + i);
        }

        // ── ShareabilityGraph ──────────────────────────────────────────────
        // Both FIFO and LIFO edges for every ordered pair (i→j and j→i).
        // This means the constraint DAG has no edges → all n! permutations valid.
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
        MatsimNetworkCache net = MatsimNetworkCacheTestFixture.create();

        // Origin→Origin: asymmetric to produce varied total distances.
        // All values > 0 so minIn is well-defined.
        //         O0   O1   O2
        // O0  [ -, 120, 250 ]
        // O1  [ 90, -, 130  ]
        // O2  [ 200, 80, -  ]
        double[][] oo = {
            { 0, 120, 250 },
            { 90,  0, 130 },
            { 200, 80,  0 }
        };
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i != j) {
                    MatsimNetworkCacheTestFixture.put(net, oLink[i], oLink[j], seg(oo[i][j]));
                }
            }
        }

        // Origin→Dest (from any origin to any dest).
        // Use a table with distinct values so total distances vary.
        //         D0   D1   D2
        // O0  [ 300, 320, 310 ]
        // O1  [ 280, 290, 340 ]
        // O2  [ 270, 310, 280 ]
        double[][] od = {
            { 300, 320, 310 },
            { 280, 290, 340 },
            { 270, 310, 280 }
        };
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                MatsimNetworkCacheTestFixture.put(net, oLink[i], dLink[j], seg(od[i][j]));
            }
        }

        // Dest→Dest (between dropoffs).
        //         D0   D1   D2
        // D0  [ -,  110, 220 ]
        // D1  [ 100,  -, 115 ]
        // D2  [ 190, 105,  - ]
        double[][] dd = {
            {   0, 110, 220 },
            { 100,   0, 115 },
            { 190, 105,   0 }
        };
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i != j) {
                    MatsimNetworkCacheTestFixture.put(net, dLink[i], dLink[j], seg(dd[i][j]));
                }
            }
        }

        // Dest→Origin (for completeness — needed if DFS ever routes D→O).
        // Use uniform 150m so minIn computation sees these as potential sources.
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                MatsimNetworkCacheTestFixture.put(net, dLink[i], oLink[j], seg(150.0));
            }
        }

        setup.network = net;

        // ── DrtRequest array ───────────────────────────────────────────────
        // Generous windows: ±1h departure flexibility, maxDetourFactor=100,
        // directTravelTime=600 s (10 min). All requests depart at 8 am.
        double requestTime = 8 * 3600;
        double directTT    = 600.0;
        double earliestDep = requestTime - 3600;
        double latestArr   = requestTime + 4 * 3600 + directTT;
        double maxDetour   = 100.0;

        setup.requests = new DrtRequest[n];
        for (int i = 0; i < n; i++) {
            double ox = i * 1000.0;
            double oy = 0.0;
            double dxCoord = i * 1000.0;
            double dyCoord = 5000.0;
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
                    .originX(ox)
                    .originY(oy)
                    .destinationX(dxCoord)
                    .destinationY(dyCoord)
                    // Collapse link endpoints to the stop point for this
                    // synthetic test — makes computeMinIn's LB equivalent to
                    // straight-line distance between stop coordinates.
                    .originLinkCoordFromX(ox).originLinkCoordFromY(oy)
                    .originLinkCoordToX(ox).originLinkCoordToY(oy)
                    .destinationLinkCoordFromX(dxCoord).destinationLinkCoordFromY(dyCoord)
                    .destinationLinkCoordToX(dxCoord).destinationLinkCoordToY(dyCoord)
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
