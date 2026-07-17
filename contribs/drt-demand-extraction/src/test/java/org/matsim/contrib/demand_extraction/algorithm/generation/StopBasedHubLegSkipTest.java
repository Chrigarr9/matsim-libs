package org.matsim.contrib.demand_extraction.algorithm.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

/**
 * HYP-8: rides containing a hub-leg request copy (hubLegRole != NONE) must be
 * excluded from stop-based conversion. Snapping a hub-leg pickup/dropoff away
 * from the hub breaks the hub-transfer nesting contract (the physical transfer
 * point no longer matches the hub coordinates the adapter/MIP pair on), charges
 * a phantom origin access walk on CONTINUATION legs (double-charged across the
 * leg pair), and applies a door-to-door wait semantic to a hub dwell.
 */
class StopBasedHubLegSkipTest {

    @Test
    void hubLegRides_areSkipped_withCountedStat() {
        var finder = new StopBasedTestFixtures.CapturingStopFinder();
        ExMasConfigGroup cfg = StopBasedTestFixtures.buildExMasConfig(true);
        BudgetValidator validator = StopBasedTestFixtures.buildPassingValidator(cfg);
        StopBasedRideGenerator gen =
                StopBasedTestFixtures.buildGenerator(true, finder, validator);

        Ride normal = StopBasedTestFixtures.buildD2DRide();
        Ride hubLeg = StopBasedTestFixtures.buildD2DRideWithHubLegPax();

        List<Ride> out = gen.generateStopBasedRides(
                new ArrayList<>(List.of(normal, hubLeg)), 100);

        assertEquals(1, out.size(), "only the non-hub ride may convert");
        assertEquals(100, out.get(0).getIndex(), "index threading unchanged");
        assertEquals(1, gen.getSkippedHubLegRides(), "skip must be counted");
        for (DrtRequest r : out.get(0).getRequests()) {
            assertEquals(DrtRequest.HubLegRole.NONE,
                    r.hubLegRole == null ? DrtRequest.HubLegRole.NONE : r.hubLegRole,
                    "no hub-leg pax may survive into an S2S ride");
        }
    }

    @Test
    void nonHubRides_stillConvert_statZero() {
        var finder = new StopBasedTestFixtures.CapturingStopFinder();
        ExMasConfigGroup cfg = StopBasedTestFixtures.buildExMasConfig(true);
        StopBasedRideGenerator gen = StopBasedTestFixtures.buildGenerator(
                true, finder, StopBasedTestFixtures.buildPassingValidator(cfg));

        List<Ride> out = gen.generateStopBasedRides(
                new ArrayList<>(List.of(StopBasedTestFixtures.buildD2DRide())), 0);

        assertEquals(1, out.size());
        assertEquals(0, gen.getSkippedHubLegRides());
    }
}
