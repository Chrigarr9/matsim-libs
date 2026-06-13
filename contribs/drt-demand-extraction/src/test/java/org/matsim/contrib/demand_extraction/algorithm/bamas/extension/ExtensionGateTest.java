package org.matsim.contrib.demand_extraction.algorithm.bamas.extension;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Characterization / pin test for {@link DistanceSlopeGate}.
 *
 * <p>Each row is annotated with the hand-computed arithmetic so the expected value can be
 * verified independently of the implementation. The goal is to lock both the formula and
 * the boundary direction ({@code <=} inclusive) so any divergence from the verbatim
 * {@link BamasRideExtender#computeMaxAllowedRideDistance} expression is caught immediately.
 *
 * <p>Two gate configurations are covered:
 * <ol>
 *   <li><b>Linear gate</b> — intercept=1.5, slope=-0.1, maxSaving=0.5, logScale ignored.</li>
 *   <li><b>Log gate</b>   — intercept=NaN, slope=NaN, logScale=0.3, maxSaving=0.75, minDegree=3.</li>
 * </ol>
 */
class ExtensionGateTest {

    // ---------------------------------------------------------------------------
    // LINEAR GATE  (intercept=1.5, slope=-0.1, maxSaving=0.5; log gate disabled)
    //
    //   gate(d) = max(1 - 0.5, 1.5 + (-0.1)*d) = max(0.5, 1.5 - 0.1*d)
    //   maxAllowed = gate(d) * sumDirect
    //   admit      = candidateDist <= maxAllowed
    // ---------------------------------------------------------------------------

    private static final DistanceSlopeGate LINEAR_GATE = new DistanceSlopeGate(
            1.5,   // linearIntercept
            -0.1,  // linearSlope
            -1.0,  // logScale  (< 0 → log gate disabled)
            0.5,   // maxSaving
            3      // minDegree (irrelevant for linear gate)
    );

    record Row(double candidateDist, double sumDirect, int degree, boolean expectedAdmit) {}

    static Stream<Row> linearGateRows() {
        return Stream.of(
            // --- clear-admit cases ---

            // d=3: gate = max(0.5, 1.5 - 0.3) = max(0.5, 1.2) = 1.2
            //      maxAllowed = 1.2 * 10_000 = 12_000
            //      admit: 8_000 <= 12_000 → true
            new Row(8_000, 10_000, 3, true),

            // d=5: gate = max(0.5, 1.5 - 0.5) = max(0.5, 1.0) = 1.0
            //      maxAllowed = 1.0 * 20_000 = 20_000
            //      admit: 15_000 <= 20_000 → true
            new Row(15_000, 20_000, 5, true),

            // d=2: gate = max(0.5, 1.5 - 0.2) = max(0.5, 1.3) = 1.3
            //      maxAllowed = 1.3 * 5_000 = 6_500
            //      admit: 6_499.99 <= 6_500 → true
            new Row(6_499.99, 5_000, 2, true),

            // --- clear-reject cases ---

            // d=3: gate = 1.2, maxAllowed = 1.2 * 10_000 = 12_000
            //      admit: 13_000 <= 12_000 → false
            new Row(13_000, 10_000, 3, false),

            // d=10: gate = max(0.5, 1.5 - 1.0) = max(0.5, 0.5) = 0.5  (floor active)
            //       maxAllowed = 0.5 * 30_000 = 15_000
            //       admit: 20_000 <= 15_000 → false
            new Row(20_000, 30_000, 10, false),

            // d=5: gate = 1.0, maxAllowed = 1.0 * 20_000 = 20_000
            //      admit: 20_001 <= 20_000 → false
            new Row(20_001, 20_000, 5, false),

            // --- exact-boundary case (locks <= direction) ---

            // d=3: gate = 1.2, maxAllowed = 1.2 * 10_000 = 12_000
            //      admit: 12_000 <= 12_000 → true  (boundary is inclusive)
            new Row(12_000, 10_000, 3, true),

            // --- floor-active case ---

            // d=20: gate = max(0.5, 1.5 - 2.0) = max(0.5, -0.5) = 0.5  (floor dominates)
            //       maxAllowed = 0.5 * 40_000 = 20_000
            //       admit: 18_000 <= 20_000 → true
            new Row(18_000, 40_000, 20, true),

            // d=20: maxAllowed = 0.5 * 40_000 = 20_000
            //       admit: 20_001 <= 20_000 → false
            new Row(20_001, 40_000, 20, false),

            // --- zero/negative sumDirect guard (early-return → always admit) ---

            // sumDirect=0 → guard fires, admit unconditionally
            new Row(999_999, 0, 3, true)
        );
    }

    @ParameterizedTest(name = "[linear] cand={0}, sum={1}, deg={2} → {3}")
    @MethodSource("linearGateRows")
    void testLinearGate(Row row) {
        assertEquals(row.expectedAdmit(),
                LINEAR_GATE.admit(row.candidateDist(), row.sumDirect(), row.degree()),
                String.format("LINEAR cand=%.0f sum=%.0f deg=%d", row.candidateDist(), row.sumDirect(), row.degree()));
    }

    // ---------------------------------------------------------------------------
    // LOG GATE  (logScale=0.3, maxSaving=0.75, minDegree=3; linear gate disabled)
    //
    //   requiredSaving(d) = max(0, min(min(0.99, 0.75), 0.3 * log2(d)))
    //                     = max(0, min(0.75, 0.3 * log2(d)))
    //   maxAllowed        = (1 - requiredSaving) * sumDirect
    //   admit             = candidateDist <= maxAllowed
    // ---------------------------------------------------------------------------

    private static final DistanceSlopeGate LOG_GATE = new DistanceSlopeGate(
            Double.NaN,  // linearIntercept (NaN → log branch)
            Double.NaN,  // linearSlope
            0.3,         // logScale
            0.75,        // maxSaving
            3            // minDegree
    );

    static Stream<Row> logGateRows() {
        return Stream.of(
            // --- below minDegree → gate bypassed, always admit ---

            // d=2 < minDegree=3 → early return true
            new Row(999_999, 10_000, 2, true),

            // --- clear-admit cases ---

            // d=3: log2(3)=1.585, 0.3*1.585=0.4754, min(0.75,0.4754)=0.4754
            //      maxAllowed = (1 - 0.4754) * 20_000 = 0.5246 * 20_000 = 10_492
            //      admit: 8_000 <= 10_492 → true
            new Row(8_000, 20_000, 3, true),

            // d=4: log2(4)=2.0, 0.3*2.0=0.6, min(0.75,0.6)=0.6
            //      maxAllowed = (1 - 0.6) * 10_000 = 0.4 * 10_000 = 4_000
            //      admit: 3_500 <= 4_000 → true
            new Row(3_500, 10_000, 4, true),

            // --- maxSaving cap active ---

            // d=8: log2(8)=3.0, 0.3*3.0=0.9, min(0.75,0.9)=0.75  (cap active)
            //      maxAllowed = (1 - 0.75) * 10_000 = 0.25 * 10_000 = 2_500
            //      admit: 2_000 <= 2_500 → true
            new Row(2_000, 10_000, 8, true),

            // d=8: maxAllowed = 2_500
            //      admit: 3_000 <= 2_500 → false
            new Row(3_000, 10_000, 8, false),

            // --- exact boundary (locks <= direction) ---

            // d=4: maxAllowed = 0.4 * 10_000 = 4_000
            //      admit: 4_000 <= 4_000 → true  (inclusive boundary)
            new Row(4_000, 10_000, 4, true),

            // --- clear-reject ---

            // d=3: maxAllowed ≈ 10_492 (see above)
            //      admit: 15_000 <= 10_492 → false
            new Row(15_000, 20_000, 3, false),

            // --- zero sumDirect guard ---
            new Row(999_999, 0, 4, true)
        );
    }

    @ParameterizedTest(name = "[log] cand={0}, sum={1}, deg={2} → {3}")
    @MethodSource("logGateRows")
    void testLogGate(Row row) {
        assertEquals(row.expectedAdmit(),
                LOG_GATE.admit(row.candidateDist(), row.sumDirect(), row.degree()),
                String.format("LOG cand=%.0f sum=%.0f deg=%d", row.candidateDist(), row.sumDirect(), row.degree()));
    }

    // ---------------------------------------------------------------------------
    // DISABLED GATE (logScale < 0, linear NaN) → everything admitted
    // ---------------------------------------------------------------------------

    @Test
    void testDisabledGateAdmitsAll() {
        DistanceSlopeGate disabled = new DistanceSlopeGate(Double.NaN, Double.NaN, -1.0, 0.75, 3);
        // Even a wildly overshooting candidate should be admitted when gate is off
        assertEquals(true, disabled.admit(1_000_000, 1_000, 5),
                "disabled gate must admit all candidates");
    }
}
