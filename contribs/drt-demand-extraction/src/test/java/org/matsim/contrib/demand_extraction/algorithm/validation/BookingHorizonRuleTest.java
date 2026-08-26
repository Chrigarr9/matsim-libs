package org.matsim.contrib.demand_extraction.algorithm.validation;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

class BookingHorizonRuleTest {

    // Use the same builder pattern as DrtRequestFactoryPerClassFlexRelTest to
    // construct minimal DrtRequests; only requestTime / isCommute / isEducation matter.
    private DrtRequest req(double requestTime, boolean commute, boolean education) {
        return DrtRequest.builder()
                .index(0).requestTime(requestTime)
                .isCommute(commute).isEducation(education)
                .build();
    }

    @Test
    void disabledHorizonAdmitsEverything() {
        DrtRequest spont = req(10000, false, false);
        assertTrue(BookingHorizonRule.isAdmissible(0.0, new DrtRequest[]{spont}, 0.0));
        assertTrue(BookingHorizonRule.isAdmissible(0.0, new DrtRequest[]{spont}, -1.0));
    }

    @Test
    void mandatoryMembersNeverBind() {
        DrtRequest commuter = req(50000, true, false);
        DrtRequest student = req(50000, false, true);
        // ride starts 10h before their request time - still admissible (prebooked)
        assertTrue(BookingHorizonRule.isAdmissible(14000, new DrtRequest[]{commuter, student}, 600.0));
    }

    @Test
    void spontaneousMemberBindsOnRideStart() {
        DrtRequest spont = req(30000, false, false);
        DrtRequest commuter = req(29000, true, false);
        // ride departs at 29000 = 1000s before the spontaneous member booked (30000-600=29400)
        assertFalse(BookingHorizonRule.isAdmissible(29000, new DrtRequest[]{commuter, spont}, 600.0));
        // ride departs at 29500 >= 29400 - admissible
        assertTrue(BookingHorizonRule.isAdmissible(29500, new DrtRequest[]{commuter, spont}, 600.0));
        // boundary: exactly requestTime - horizon is admissible
        assertTrue(BookingHorizonRule.isAdmissible(29400, new DrtRequest[]{commuter, spont}, 600.0));
    }

    @Test
    void pairOverloadMatchesArrayOverload() {
        DrtRequest spont = req(30000, false, false);
        DrtRequest commuter = req(29000, true, false);
        assertEquals(
            BookingHorizonRule.isAdmissible(29000, new DrtRequest[]{commuter, spont}, 600.0),
            BookingHorizonRule.isAdmissible(29000, commuter, spont, 600.0));
    }

    @Test
    void isMandatoryFollowsFlags() {
        assertTrue(BookingHorizonRule.isMandatory(req(0, true, false)));
        assertTrue(BookingHorizonRule.isMandatory(req(0, false, true)));
        assertFalse(BookingHorizonRule.isMandatory(req(0, false, false)));
    }
}
