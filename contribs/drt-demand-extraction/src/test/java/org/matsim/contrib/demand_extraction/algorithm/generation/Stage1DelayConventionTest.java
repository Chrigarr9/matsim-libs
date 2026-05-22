package org.matsim.contrib.demand_extraction.algorithm.generation;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A7: Failing test for the Stage 1 {@code delay} convention bug.
 *
 * <h2>Background</h2>
 * {@link StopBasedRideGenerator} calls
 * {@link BudgetValidator#calculateDrtScoreWithWalks} with a {@code delay}
 * argument. Under the preplanned-service model, {@code delay} represents the
 * pickup-wait, i.e. the time from the passenger's request time to vehicle
 * arrival at the stop. In Stage 1, the vehicle arrives exactly when the
 * passenger finishes walking to the stop, so:
 *
 * <pre>
 *   delay = accessWalkTime = accessWalkDistance / walkSpeed
 * </pre>
 *
 * <h2>The bug (pre-A8)</h2>
 * {@code convertToStopBasedBudgetAware} currently computes:
 *
 * <pre>
 *   delays[i] = passengerTravelTimes[i] - requests[i].getTravelTime()
 *             = (accessTime + inVehicleTime + egressTime) - directTT
 * </pre>
 *
 * On the minimal test network (directTT = 600 s, 1000 m link at 13.89 m/s
 * ≈ 72 s in-vehicle, 50 m walk at 1.2 m/s ≈ 41.7 s access+egress each), this
 * gives {@code delay ≈ (41.7 + 72 + 41.7) - 600 = -444.7 s} — large negative,
 * which overcharges marginal wait disutility by walks + detour.
 *
 * <h2>The fix (A8)</h2>
 * In {@code convertToStopBasedBudgetAware} only, replace:
 * <pre>
 *   delays[i] = passengerTravelTimes[i] - requests[i].getTravelTime();
 * </pre>
 * with:
 * <pre>
 *   delays[i] = accessTime;   // = accessWalkDistances[i] / walkSpeed
 * </pre>
 * The legacy path ({@code convertToStopBasedLegacy}) keeps the old formula
 * for backward compatibility.
 *
 * <h2>Test design</h2>
 * A {@link RecordingBudgetValidator} records the {@code delay} argument for
 * every {@link BudgetValidator#calculateDrtScoreWithWalks} call. After
 * generating stop-based rides, we assert both passengers' recorded delays
 * equal {@code WALK_M / walkSpeed}.
 *
 * The test FAILS before A8 (recorded delay is ~ -454 s, not ~ 37 s) and
 * PASSES after A8 (recorded delay = 37.3 s).
 */
class Stage1DelayConventionTest {

    /**
     * With {@code enableBudgetAwareConstraints=true}, the {@code delay} passed
     * to {@link BudgetValidator#calculateDrtScoreWithWalks} must equal
     * {@code accessWalkDistance / walkSpeed} for each passenger.
     *
     * <p>This test FAILS before A8 and PASSES after.
     */
    @Test
    void budgetAwarePath_delayEqualsAccessWalkTime_notWalksPlusDetour() {
        RecordingBudgetValidator recordingValidator = buildRecordingValidator();
        StopBasedRideGenerator gen = StopBasedTestFixtures.buildGenerator(
                /* enableBudgetAware= */ true,
                new StopBasedTestFixtures.CapturingStopFinder(),
                recordingValidator);

        gen.generateStopBasedRides(List.of(StopBasedTestFixtures.buildD2DRide()), 0);

        ExMasConfigGroup cfg = StopBasedTestFixtures.buildExMasConfig(true);
        double expectedDelay = StopBasedTestFixtures.WALK_M / cfg.getWalkSpeedMps();

        // With the bug (pre-A8):
        //   accessTime    ≈ 41.7 s  (50 m / 1.2 m/s default walk speed)
        //   inVehicleTime ≈ 72.0 s  (1000 m at 13.89 m/s, "stop"→"dropoff" segment)
        //   egressTime    ≈ 41.7 s
        //   directTT       = 600 s
        //   buggy delay    = (41.7 + 72.0 + 41.7) - 600 = -444.7 s  ← large negative
        // With the fix (post-A8):
        //   correct delay  = 50 / 1.2 ≈ 41.7 s

        assertFalse(recordingValidator.capturedDelays.isEmpty(),
                "Expected at least one calculateDrtScoreWithWalks call "
                + "(no ride converted — check converter rejects before budget step)");

        System.out.printf("%nExpected delay: %.4f s%n", expectedDelay);
        for (int i = 0; i < recordingValidator.capturedDelays.size(); i++) {
            System.out.printf("  Pax %d recorded delay: %.4f s%n",
                    i, recordingValidator.capturedDelays.get(i));
        }

        for (int i = 0; i < recordingValidator.capturedDelays.size(); i++) {
            assertEquals(expectedDelay,
                    recordingValidator.capturedDelays.get(i),
                    1e-3,
                    "Pax " + i + ": delay passed to scorer must equal accessWalkTime="
                    + expectedDelay + " s (accessWalk=" + StopBasedTestFixtures.WALK_M + " m / "
                    + cfg.getWalkSpeedMps() + " m/s). "
                    + "Pre-A8 buggy value ≈ -444.7 s (walks+detour-directTT). "
                    + "Recorded: " + recordingValidator.capturedDelays.get(i));
        }
    }

    // =========================================================================
    // Infrastructure
    // =========================================================================

    /**
     * Build a {@link RecordingBudgetValidator} that always grants budget
     * (returns {@code request.bestModeScore + 1.0}) so rides are not rejected
     * on budget, but also records the {@code delay} arg on each call.
     */
    private RecordingBudgetValidator buildRecordingValidator() {
        ExMasConfigGroup cfg = StopBasedTestFixtures.buildExMasConfig(/* enableBudgetAware= */ true);
        return new RecordingBudgetValidator(cfg);
    }

    /**
     * {@link BudgetValidator} stub that records the {@code delay} argument
     * passed to every {@link #calculateDrtScoreWithWalks} call.
     *
     * <p>Always returns {@code request.bestModeScore + 1.0} so remaining
     * budget is positive and rides are not rejected before the assertion.
     */
    static class RecordingBudgetValidator extends BudgetValidator {
        /** Recorded {@code delay} args, one per {@link #calculateDrtScoreWithWalks} call. */
        final List<Double> capturedDelays = new ArrayList<>();

        RecordingBudgetValidator(ExMasConfigGroup exMasConfig) {
            super(null, exMasConfig, 1.34); // null adapter — Phase-2 ctor
        }

        @Override
        public double calculateDrtScoreWithWalks(
                DrtRequest request,
                double delay,
                double actualTravelTime,
                double actualDistance,
                double accessWalkDist,
                double egressWalkDist) {
            capturedDelays.add(delay);
            // Always grant budget so the ride reaches the assertion
            return request.bestModeScore + 1.0;
        }
    }
}
