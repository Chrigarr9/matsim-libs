package org.matsim.contrib.demand_extraction.algorithm.exmas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;

/**
 * Unit tests for {@link ReferenceRideExtender#cartesianProduct}.
 *
 * <p>Verifies the fix to the Python {@code list(product(*E))[0]} bug (2026-04-22):
 * when a passenger has both a FIFO and a LIFO edge to the candidate, all 2^n
 * combinations must be returned, not just the first.
 */
class ReferenceRideExtenderCartesianProductTest {

    @Test
    void singlePassengerSingleEdge_returnsOneCombo() {
        List<IntList> edges = List.of(listOf(10));
        List<int[]> result = ReferenceRideExtender.cartesianProduct(edges);
        assertEquals(1, result.size());
        assertComboEquals(new int[]{10}, result.get(0));
    }

    @Test
    void singlePassengerTwoEdges_returnsTwoCombos() {
        // One passenger with FIFO edge (index 10) and LIFO edge (index 11).
        // Before the fix: list(product(*E))[0] → only [10].
        // After the fix: all combinations → [10], [11].
        List<IntList> edges = List.of(listOf(10, 11));
        List<int[]> result = ReferenceRideExtender.cartesianProduct(edges);
        assertEquals(2, result.size());
        assertContainsCombo(result, 10);
        assertContainsCombo(result, 11);
    }

    @Test
    void twoPassengersBothTwoEdges_returnsFourCombos() {
        // Passenger 0: edges [10 (FIFO), 11 (LIFO)]
        // Passenger 1: edges [12 (FIFO), 13 (LIFO)]
        // Expected: [10,12], [10,13], [11,12], [11,13]
        List<IntList> edges = List.of(listOf(10, 11), listOf(12, 13));
        List<int[]> result = ReferenceRideExtender.cartesianProduct(edges);
        assertEquals(4, result.size());
        assertContainsCombo(result, 10, 12);
        assertContainsCombo(result, 10, 13);
        assertContainsCombo(result, 11, 12);
        assertContainsCombo(result, 11, 13);
    }

    @Test
    void twoPassengersOneHasTwoEdgesOneHasOne_returnsTwoCombos() {
        // Passenger 0: edges [10 (FIFO), 11 (LIFO)]; passenger 1: edge [12 (FIFO only)]
        List<IntList> edges = List.of(listOf(10, 11), listOf(12));
        List<int[]> result = ReferenceRideExtender.cartesianProduct(edges);
        assertEquals(2, result.size());
        assertContainsCombo(result, 10, 12);
        assertContainsCombo(result, 11, 12);
    }

    @Test
    void threePassengersAllTwoEdges_returnsEightCombos() {
        List<IntList> edges = List.of(listOf(10, 11), listOf(12, 13), listOf(14, 15));
        List<int[]> result = ReferenceRideExtender.cartesianProduct(edges);
        assertEquals(8, result.size());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static IntList listOf(int... values) {
        return new IntArrayList(values);
    }

    private static void assertComboEquals(int[] expected, int[] actual) {
        assertTrue(Arrays.equals(expected, actual),
                "Expected " + Arrays.toString(expected) + " but got " + Arrays.toString(actual));
    }

    private static void assertContainsCombo(List<int[]> combos, int... expected) {
        for (int[] combo : combos) {
            if (Arrays.equals(combo, expected)) return;
        }
        List<String> found = combos.stream().map(Arrays::toString).toList();
        throw new AssertionError(
                "Expected combo " + Arrays.toString(expected) + " not found in: " + found);
    }
}
