package org.matsim.contrib.demand_extraction.algorithm.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.matsim.contrib.demand_extraction.algorithm.bamas.ride.MaterializedRideStore;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;
import org.matsim.contrib.demand_extraction.algorithm.network.TravelSegmentLookup;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.testutil.RideFixtures;

/**
 * Parity gate for the memory rewrite of the Shapley pass.
 *
 * <p>The pass was re-keyed from {@code HashSet<Integer>} to sorted {@code int[]} and its subset
 * enumeration replaced by an allocation-free bitmask walk over a cached sub-coalition value table.
 * Those changes exist purely to shrink a ~12 GB structure and an allocation storm; they must not
 * move a single number. {@link #naiveReferenceShapley} is a transcription of the pre-rewrite
 * algorithm and is the oracle here.
 *
 * <p>The re-keying sorts the request indices, so the risk it introduces is an OUTPUT ALIGNMENT bug:
 * {@code shapley[i]} must keep belonging to {@code requests[i]} in the ride's own order, not to the
 * i-th smallest request id. {@link #outputStaysAlignedWithUnsortedRequestOrder} pins that directly.
 */
class RidePostProcessorShapleyParityTest {

    private static final TravelSegmentLookup NO_ROUTING = (o, d, t) -> TravelSegment.unreachable();

    private static RidePostProcessor shapleyProcessor() {
        ExMasConfigGroup cfg = new ExMasConfigGroup();
        cfg.setCalcShapleyValues(true);
        cfg.setCalcPredecessors(false);
        return new RidePostProcessor(cfg, NO_ROUTING, (budget, req, tt, dist) -> budget + 1.0);
    }

    private static List<Ride> shapleyOf(List<Ride> pool) {
        return shapleyProcessor().process(new MaterializedRideStore(pool));
    }

    // ── The oracle: the algorithm exactly as it was before the rewrite ────────────────────────

    private static double factorial(int n) {
        double result = 1.0;
        for (int i = 2; i <= n; i++) {
            result *= i;
        }
        return result;
    }

    private static Map<Integer, double[]> naiveReferenceShapley(List<Ride> rides) {
        Map<Set<Integer>, Double> subsetDistance = new HashMap<>();
        for (Ride ride : rides) {
            Set<Integer> subset = Arrays.stream(ride.getRequestIndices())
                    .boxed()
                    .collect(Collectors.toCollection(HashSet::new));
            subsetDistance.merge(subset, ride.getRideDistance(), Math::min);
        }
        subsetDistance.put(Collections.emptySet(), 0.0);

        Map<Integer, double[]> byRide = new HashMap<>();
        for (Ride ride : rides) {
            int[] requests = ride.getRequestIndices();
            int n = requests.length;
            Set<Integer> rideSet = Arrays.stream(requests).boxed()
                    .collect(Collectors.toCollection(HashSet::new));

            if (n == 1) {
                byRide.put(ride.getIndex(), new double[] {
                        subsetDistance.getOrDefault(new HashSet<>(rideSet), ride.getRideDistance()) });
                continue;
            }

            double nFactorial = factorial(n);
            double[] shapley = new double[n];
            for (int i = 0; i < n; i++) {
                int player = requests[i];
                Set<Integer> rest = new HashSet<>(rideSet);
                rest.remove(player);
                List<Integer> restList = new ArrayList<>(rest);
                int restSize = restList.size();
                int subsetCount = 1 << restSize;

                for (int mask = 0; mask < subsetCount; mask++) {
                    Set<Integer> subset = new HashSet<>();
                    for (int bit = 0; bit < restSize; bit++) {
                        if ((mask & (1 << bit)) != 0) {
                            subset.add(restList.get(bit));
                        }
                    }
                    double vS = subsetDistance.getOrDefault(subset, 0.0);
                    Set<Integer> withPlayer = new HashSet<>(subset);
                    withPlayer.add(player);
                    double vSi = subsetDistance.getOrDefault(withPlayer, 0.0);
                    int sSize = subset.size();
                    double weight = (factorial(sSize) * factorial(n - sSize - 1)) / nFactorial;
                    shapley[i] += weight * (vSi - vS);
                }
            }
            byRide.put(ride.getIndex(), shapley);
        }
        return byRide;
    }

    // ── Pool generation ──────────────────────────────────────────────────────────────────────

    /**
     * A pool with overlapping sub-coalitions, which is what makes Shapley non-trivial: a degree-4
     * ride's value depends on which of its 2- and 3-subsets also exist as rides.
     */
    private static List<Ride> randomPool(long seed, int rideCount, int maxDegree) {
        Random rnd = new Random(seed);
        List<Ride> pool = new ArrayList<>(rideCount);
        for (int r = 0; r < rideCount; r++) {
            int degree = 1 + rnd.nextInt(maxDegree);
            // LinkedHashSet: distinct ids (as in production) in a deliberately unsorted order.
            Set<Integer> picked = new LinkedHashSet<>();
            while (picked.size() < degree) {
                picked.add(rnd.nextInt(12));
            }
            int[] requests = picked.stream().mapToInt(Integer::intValue).toArray();
            double distance = Math.round(rnd.nextDouble() * 5000.0 * 10.0) / 10.0;
            pool.add(RideFixtures.rideWithDistance(r, distance, requests));
        }
        return pool;
    }

    // ── Tests ────────────────────────────────────────────────────────────────────────────────

    @Test
    void matchesNaiveReferenceOnRandomOverlappingPools() {
        for (long seed = 1; seed <= 25; seed++) {
            List<Ride> pool = randomPool(seed, 40, 5);
            Map<Integer, double[]> expected = naiveReferenceShapley(pool);

            for (Ride enriched : shapleyOf(pool)) {
                assertArrayEquals(expected.get(enriched.getIndex()), enriched.getShapleyValues(), 1e-12,
                        "Shapley mismatch for ride " + enriched.getIndex() + " (seed " + seed + ")");
            }
        }
    }

    @Test
    void matchesNaiveReferenceAtTheProductionDegreeCap() {
        // Degree 8 is the degree the 100% Lyon export runs at, and the degree whose 2^n blow-up the
        // rewrite targets. Small pool: the oracle is exponential too.
        List<Ride> pool = randomPool(99L, 25, 8);
        Map<Integer, double[]> expected = naiveReferenceShapley(pool);

        for (Ride enriched : shapleyOf(pool)) {
            assertArrayEquals(expected.get(enriched.getIndex()), enriched.getShapleyValues(), 1e-12,
                    "Shapley mismatch at degree cap for ride " + enriched.getIndex());
        }
    }

    @Test
    void outputStaysAlignedWithUnsortedRequestOrder() {
        // Requests deliberately descending. If the rewrite leaked its internal sort into the output,
        // the two arrays below would come back reversed relative to each other.
        List<Ride> pool = List.of(
                RideFixtures.rideWithDistance(0, 900.0, 7, 3),
                RideFixtures.rideWithDistance(1, 900.0, 3, 7),
                RideFixtures.rideWithDistance(2, 500.0, 7),
                RideFixtures.rideWithDistance(3, 100.0, 3));

        List<Ride> out = shapleyOf(pool);
        double[] descending = out.get(0).getShapleyValues();  // requests [7, 3]
        double[] ascending = out.get(1).getShapleyValues();   // requests [3, 7]

        assertEquals(descending[0], ascending[1], 1e-12, "request 7's share must follow request 7");
        assertEquals(descending[1], ascending[0], 1e-12, "request 3's share must follow request 3");
        // And the two are genuinely different, so the assertion above is not vacuous.
        assertFalse(Math.abs(descending[0] - descending[1]) < 1e-9,
                "fixture must give the two passengers different shares");
    }

    @Test
    void sharesSumToTheRideDistance() {
        // Efficiency axiom: sum_i phi_i = v(grand coalition) - v(empty) = the ride's own distance,
        // whenever no other ride serves the identical request set. Independent of the oracle.
        List<Ride> pool = List.of(
                RideFixtures.rideWithDistance(0, 1000.0, 1, 2, 3),
                RideFixtures.rideWithDistance(1, 600.0, 1, 2),
                RideFixtures.rideWithDistance(2, 700.0, 2, 3),
                RideFixtures.rideWithDistance(3, 400.0, 1),
                RideFixtures.rideWithDistance(4, 300.0, 2),
                RideFixtures.rideWithDistance(5, 500.0, 3));

        for (Ride enriched : shapleyOf(pool)) {
            double sum = Arrays.stream(enriched.getShapleyValues()).sum();
            assertEquals(enriched.getRideDistance(), sum, 1e-9,
                    "shares must exhaust the ride distance for ride " + enriched.getIndex());
        }
    }

    @Test
    void degreeOneRideTakesItsOwnDistance() {
        List<Ride> out = shapleyOf(List.of(RideFixtures.rideWithDistance(0, 250.0, 4)));

        assertArrayEquals(new double[] { 250.0 }, out.get(0).getShapleyValues(), 1e-12);
    }

    @Test
    void cheaperDuplicateRequestSetWins() {
        // subsetDistance keeps the MINIMUM distance per request set. Both rides carry {1,2}, so both
        // must be valued at 400, not at their own distance.
        List<Ride> out = shapleyOf(List.of(
                RideFixtures.rideWithDistance(0, 900.0, 1, 2),
                RideFixtures.rideWithDistance(1, 400.0, 1, 2)));

        assertEquals(400.0, Arrays.stream(out.get(0).getShapleyValues()).sum(), 1e-9);
        assertEquals(400.0, Arrays.stream(out.get(1).getShapleyValues()).sum(), 1e-9);
    }

    @Test
    void degreeAboveTheSupportedCapFailsLoudly() {
        int[] requests = new int[RidePostProcessor.MAX_SHAPLEY_DEGREE + 1];
        for (int i = 0; i < requests.length; i++) {
            requests[i] = i;
        }
        List<Ride> pool = List.of(RideFixtures.rideWithDistance(0, 1000.0, requests));

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> shapleyOf(pool));

        // The parallel pass wraps worker failures, so search the cause chain for the real message.
        Throwable t = thrown;
        StringBuilder chain = new StringBuilder();
        boolean found = false;
        while (t != null) {
            chain.append(t.getMessage()).append(" | ");
            if (t.getMessage() != null && t.getMessage().contains("sub-coalitions")) {
                found = true;
                break;
            }
            t = t.getCause();
        }
        assertTrue(found, "expected a degree-cap message, got: " + chain);
    }

    // ── The overlap test that replaced Collections.disjoint ──────────────────────────────────

    @Test
    void disjointSortedAgreesWithSetSemantics() {
        assertTrue(RidePostProcessor.disjointSorted(new int[] { 1, 3, 5 }, new int[] { 2, 4, 6 }));
        assertFalse(RidePostProcessor.disjointSorted(new int[] { 1, 3, 5 }, new int[] { 5, 7 }));
        assertFalse(RidePostProcessor.disjointSorted(new int[] { 1, 3, 5 }, new int[] { 3 }));
        assertTrue(RidePostProcessor.disjointSorted(new int[] {}, new int[] { 1 }));
        assertTrue(RidePostProcessor.disjointSorted(new int[] { 1 }, new int[] {}));
        assertFalse(RidePostProcessor.disjointSorted(new int[] { 9 }, new int[] { 9 }));
        // Overlap only at the very end of both arrays — the case a short-circuiting walk can miss.
        assertFalse(RidePostProcessor.disjointSorted(new int[] { 1, 2, 8 }, new int[] { 3, 4, 8 }));
    }

    @Test
    void disjointSortedMatchesBruteForceOnRandomPairs() {
        Random rnd = new Random(4242L);
        for (int trial = 0; trial < 2000; trial++) {
            int[] a = randomSortedSet(rnd);
            int[] b = randomSortedSet(rnd);

            boolean expected = Arrays.stream(a).noneMatch(x -> Arrays.stream(b).anyMatch(y -> y == x));

            assertEquals(expected, RidePostProcessor.disjointSorted(a, b),
                    "disjointSorted(" + Arrays.toString(a) + ", " + Arrays.toString(b) + ")");
        }
    }

    private static int[] randomSortedSet(Random rnd) {
        Set<Integer> s = new HashSet<>();
        int size = rnd.nextInt(6);
        for (int i = 0; i < size; i++) {
            s.add(rnd.nextInt(10));
        }
        int[] arr = s.stream().mapToInt(Integer::intValue).toArray();
        Arrays.sort(arr);
        return arr;
    }
}
