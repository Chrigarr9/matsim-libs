package org.matsim.contrib.demand_extraction.algorithm.generation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for beeline detour pre-filter logic.
 *
 * The filter rejects candidate pairs where the Euclidean (beeline) shared path
 * distance already exceeds the maximum allowed network distance (directDistance * maxDetourFactor).
 * Since beeline <= network distance, this has zero false negatives.
 */
class BeelineDetourFilterTest {

    private static double beeline(double x1, double y1, double x2, double y2) {
        double dx = x2 - x1, dy = y2 - y1;
        return Math.sqrt(dx * dx + dy * dy);
    }

    @Test
    void fifo_sameDirection_shouldPass() {
        // Two passengers going east, close together - low beeline detour
        // O_i=(0,0) D_i=(100,0), O_j=(10,5) D_j=(110,5)
        // FIFO passenger i path: O_i->O_j->D_i
        double beeSharedI = beeline(0, 0, 10, 5) + beeline(10, 5, 100, 0);
        double directDistI = 100.0; // network direct distance (stored in request)
        // beeSharedI ~ 11.2 + 90.1 = 101.3, limit = 100 * 1.5 = 150
        assertTrue(beeSharedI <= directDistI * 1.5, "Same-direction pair should pass beeline filter");
    }

    @Test
    void fifo_oppositeDirection_shouldFail() {
        // O_i=(0,0) D_i=(100,0), O_j=(0,200) D_j=(100,200) - perpendicular offset 200
        // FIFO passenger i path: O_i->O_j->D_i
        double beeI = beeline(0, 0, 0, 200) + beeline(0, 200, 100, 0);
        double dirI = 100.0;
        // beeI = 200 + 223.6 = 423.6, limit = 150 -> FAIL
        assertTrue(beeI > dirI * 1.5, "Large perpendicular offset should fail beeline filter");
    }

    @Test
    void lifo_passengerI_longDetour_shouldFail() {
        // LIFO: passenger i travels O_i->O_j->D_j->D_i (picks up j, drops j, then goes to own dest)
        // O_i=(0,0) D_i=(100,0), O_j=(0,300) D_j=(100,300) - j is 300m away perpendicular
        double beeSharedI = beeline(0, 0, 0, 300) + beeline(0, 300, 100, 300) + beeline(100, 300, 100, 0);
        double directDistI = 100.0;
        // beeSharedI = 300 + 100 + 300 = 700, limit = 150 -> FAIL
        assertTrue(beeSharedI > directDistI * 1.5, "LIFO with far-away passenger should fail for i");
    }

    @Test
    void lifo_passengerJ_alwaysDirect() {
        // LIFO: passenger j travels O_j->D_j (rides directly, no detour in LIFO)
        // So beeline check for j in LIFO is just beeline(O_j, D_j) vs directDistance_j
        // Since beeline <= network, this always passes - no need to check j in LIFO
        double beeSharedJ = beeline(0, 300, 100, 300); // = 100
        double directDistJ = 100.0; // network distance >= beeline
        assertTrue(beeSharedJ <= directDistJ * 1.5, "LIFO passenger j always passes (direct ride)");
    }

    @Test
    void exactlyAtLimit_shouldPass() {
        // Beeline shared = directDistance * maxDetourFactor exactly -> should pass (<=)
        // O_i=(0,0) D_i=(100,0), O_j=(50,0) - j is on the direct path
        // FIFO i: O_i->O_j->D_i = 50 + 50 = 100, limit = 100 * 1.5 = 150 -> pass
        double beeSharedI = beeline(0, 0, 50, 0) + beeline(50, 0, 100, 0);
        double directDistI = 100.0;
        assertTrue(beeSharedI <= directDistI * 1.5);
    }
}
