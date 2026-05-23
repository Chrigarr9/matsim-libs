package org.matsim.contrib.demand_extraction.algorithm.bamas.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.List;

import org.matsim.contrib.demand_extraction.algorithm.bamas.extension.OrderingEnumerator.Ordering;

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
 * C7 — TDD: maxWaitTime pre-filter in OrderingEnumerator.
 *
 * <p>Verifies that when {@code budgetAwareConstraints=true} the origin-phase DFS
 * rejects any candidate whose computed pickup delay exceeds the passenger's
 * {@code maxWaitTime}, while {@code budgetAwareConstraints=false} leaves the
 * behaviour bit-identical to the pre-Phase-C code path.
 *
 * <h3>Scenario</h3>
 * Three requests (I, J, K) all at requestTime=0. Every origin→origin segment
 * takes exactly 100 s / 1 000 m, so:
 * <ul>
 *   <li>1st pickup (depth 0): delay = 0 s — always allowed.</li>
 *   <li>2nd pickup (depth 1): delay = 100 s.</li>
 *   <li>3rd pickup (depth 2): delay = 200 s (two 100 s hops).</li>
 * </ul>
 * With {@code maxWaitTime = 150 s} and the flag on, every origin ordering
 * is cut at depth 2 (200 s > 150 s) → 0 complete orderings emitted.
 * With {@code maxWaitTime = 300 s} or the flag off, all orderings survive.
 *
 * <p>The test calls the non-seeded entry {@link OrderingEnumerator#enumerateAndEvaluate}
 * so no parent ordering is required (the seeded path is exercised by production
 * code; the flag-threading test is the same code path in the inner DFS).
 */
class OrderingEnumeratorMaxWaitFilterTest {

    private static final double SEG_TT   = 100.0; // seconds per hop
    private static final double SEG_DIST = 1.0;   // metres (tiny → B&B never fires)

    // ── Tests ─────────────────────────────────────────────────────────────

    /**
     * Flag off: the filter is inactive regardless of maxWaitTime.
     * Even with a very tight cap (50 s) the DFS must produce orderings.
     */
    @Test
    void flagOff_ordersProducedDespiteDelay() {
        TestSetup setup = buildThreeRequestSetup(50.0 /* tight maxWaitTime on all requests */);
        List<Ordering> captured = new ArrayList<>();

        OrderingEnumerator.enumerateAndEvaluate(
                new int[]{0, 1, 2}, setup.graph, setup.network, setup.requests,
                new double[]{Double.MAX_VALUE},
                /* budgetAwareConstraints= */ false,
                captured::add);

        assertFalse(captured.isEmpty(),
                "flag=false: orderings must be emitted even when maxWaitTime=50 s "
                + "and actual delay reaches 200 s");
    }

    /**
     * Flag on, generous cap (300 s > max actual delay 200 s): all orderings survive.
     */
    @Test
    void flagOn_looseCap_ordersStillProduced() {
        TestSetup setup = buildThreeRequestSetup(300.0);
        List<Ordering> captured = new ArrayList<>();

        OrderingEnumerator.enumerateAndEvaluate(
                new int[]{0, 1, 2}, setup.graph, setup.network, setup.requests,
                new double[]{Double.MAX_VALUE},
                /* budgetAwareConstraints= */ true,
                captured::add);

        assertFalse(captured.isEmpty(),
                "flag=true, maxWaitTime=300 s: orderings must be emitted "
                + "(max delay 200 s < 300 s)");
    }

    /**
     * Flag on, tight cap (150 s < max actual delay 200 s): every 3rd-pickup position
     * has delay 200 s and is rejected → 0 complete orderings.
     */
    @Test
    void flagOn_tightCap_ordersFilteredToZero() {
        TestSetup setup = buildThreeRequestSetup(150.0);
        List<Ordering> captured = new ArrayList<>();

        OrderingEnumerator.enumerateAndEvaluate(
                new int[]{0, 1, 2}, setup.graph, setup.network, setup.requests,
                new double[]{Double.MAX_VALUE},
                /* budgetAwareConstraints= */ true,
                captured::add);

        assertEquals(0, captured.size(),
                "flag=true, maxWaitTime=150 s: all orderings must be pruned "
                + "(3rd-pickup delay=200 s > 150 s in every origin permutation)");
    }

    // ── Fixture ───────────────────────────────────────────────────────────

    private record TestSetup(
            DrtRequest[] requests,
            ShareabilityGraph graph,
            MatsimNetworkCache network) {}

    /**
     * Build a 3-request fixture with symmetric, uniform segment times.
     *
     * <p>Requests I/J/K all have requestTime=0 and the supplied maxWaitTime.
     * Every origin-to-origin hop takes {@value SEG_TT} s / {@value SEG_DIST} m,
     * so the 2nd pickup sees delay=100 s and the 3rd sees delay=200 s.
     * All other segments (O→D, D→D, D→O) use the same tiny values so that
     * the B&B distance bound is never triggered.
     */
    private static TestSetup buildThreeRequestSetup(double maxWaitTime) {
        final int n = 3;

        @SuppressWarnings("unchecked")
        Id<Link>[] oLink = new Id[n];
        @SuppressWarnings("unchecked")
        Id<Link>[] dLink = new Id[n];
        for (int i = 0; i < n; i++) {
            oLink[i] = Id.createLinkId("O" + i);
            dLink[i] = Id.createLinkId("D" + i);
        }

        // Graph: all FIFO + LIFO in both directions → no ordering constraints.
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

        // Network: uniform segments everywhere.
        MatsimNetworkCache net = MatsimNetworkCacheTestFixture.create();
        TravelSegment seg = new TravelSegment(SEG_TT, SEG_DIST, 0.0);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i != j) {
                    MatsimNetworkCacheTestFixture.put(net, oLink[i], oLink[j], seg);
                }
                MatsimNetworkCacheTestFixture.put(net, oLink[i], dLink[j], seg);
                if (i != j) {
                    MatsimNetworkCacheTestFixture.put(net, dLink[i], dLink[j], seg);
                }
                MatsimNetworkCacheTestFixture.put(net, dLink[i], oLink[j], seg);
            }
        }

        // Generous time windows — delay-window and in-vehicle pruning must not fire.
        double requestTime  = 0.0;
        double directTT     = 100.0;
        double earliestDep  = requestTime - 3_600.0;
        double latestArr    = requestTime + 36_000.0 + directTT;

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
                    .maxWaitTime(maxWaitTime)
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
