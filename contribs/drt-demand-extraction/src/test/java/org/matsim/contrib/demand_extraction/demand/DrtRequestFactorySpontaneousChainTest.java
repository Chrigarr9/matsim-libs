package org.matsim.contrib.demand_extraction.demand;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D4' (Paper-2, 2026-08-25 plan Task 5): spontaneous trips leave chain groups.
 *
 * <p>Unit-scale, mirroring {@link DrtRequestFactoryPerClassFlexRelTest}'s style: the
 * production wiring ({@code DrtRequestFactory.buildRequests}) resolves the group ID via
 * the static, testable {@link DrtRequestFactory#resolveGroupId} helper, so this test
 * drives that helper directly instead of spinning up a full population/Controler
 * pipeline.
 *
 * <p>Fixture: a person whose plan is home-work-shop-home (one subtour) would get a
 * single shared {@code ChainIdentifier} groupId for its two commute trip indices (0, 2)
 * and its own entry for the shop trip (1) IF the shop trip were also part of that
 * subtour map; here we model the more general contract directly against the map
 * {@code tripToGroupId}, which is exactly what {@code resolveGroupId} consumes.
 */
class DrtRequestFactorySpontaneousChainTest {

    private static final String PERSON = "p1";

    // A single-subtour chain: all three trip indices share one ChainIdentifier groupId.
    private static final Map<Integer, String> ONE_SUBTOUR_CHAIN = Map.of(
            0, "p1_subtour_0",   // home -> work (commute)
            1, "p1_subtour_0",   // work -> shop (spontaneous, currently folded into the chain)
            2, "p1_subtour_0"    // shop -> home (commute continuation, modeled here as isCommute=false
                                 // for trip 2 is irrelevant to this test; see flagOff/flagOn below)
    );

    // -----------------------------------------------------------------------
    // Flag ON: a non-mandatory (non-commute, non-education) trip always gets
    // the singleton fallback groupId, regardless of what ChainIdentifier assigned.
    // -----------------------------------------------------------------------

    @Test
    void spontaneousTripGetsSingletonGroupWhenFlagOn() {
        // Trip 1 (work -> shop) is neither commute nor education -> spontaneous.
        String shopGroupId = DrtRequestFactory.resolveGroupId(
                ONE_SUBTOUR_CHAIN, /* tripIdx */ 1, PERSON,
                /* spontaneousSingletonChains */ true, /* isCommute */ false, /* isEducation */ false);
        // Trip 0 (home -> work) is a commute -> stays on the chain group.
        String workGroupId = DrtRequestFactory.resolveGroupId(
                ONE_SUBTOUR_CHAIN, /* tripIdx */ 0, PERSON,
                /* spontaneousSingletonChains */ true, /* isCommute */ true, /* isEducation */ false);

        assertNotEquals(workGroupId, shopGroupId,
                "the spontaneous trip must NOT share the commute trip's chain group");
        assertEquals(PERSON + "_trip_1", shopGroupId,
                "spontaneous trip must get the singleton fallback form personId_trip_tripIdx");
        assertEquals("p1_subtour_0", workGroupId,
                "commute trip must keep its ChainIdentifier-assigned chain group");
    }

    @Test
    void educationTripAlsoKeepsChainGroupWhenFlagOn() {
        // isEducation=true must be treated like isCommute=true: it stays on the chain.
        String eduGroupId = DrtRequestFactory.resolveGroupId(
                ONE_SUBTOUR_CHAIN, /* tripIdx */ 2, PERSON,
                /* spontaneousSingletonChains */ true, /* isCommute */ false, /* isEducation */ true);
        assertEquals("p1_subtour_0", eduGroupId,
                "education trip must keep its chain group even with the flag on");
    }

    // -----------------------------------------------------------------------
    // Flag OFF (default): byte-identical to the pre-existing lookup, regardless
    // of commute/education status.
    // -----------------------------------------------------------------------

    @Test
    void flagOffKeepsChainGroups() {
        String shopGroupId = DrtRequestFactory.resolveGroupId(
                ONE_SUBTOUR_CHAIN, /* tripIdx */ 1, PERSON,
                /* spontaneousSingletonChains */ false, /* isCommute */ false, /* isEducation */ false);
        String workGroupId = DrtRequestFactory.resolveGroupId(
                ONE_SUBTOUR_CHAIN, /* tripIdx */ 0, PERSON,
                /* spontaneousSingletonChains */ false, /* isCommute */ true, /* isEducation */ false);

        // All trips of the single-subtour plan share the chain group id, flag off.
        assertEquals(workGroupId, shopGroupId,
                "flag off must preserve the shared chain group for all trips of one subtour");
        assertEquals("p1_subtour_0", shopGroupId);
    }

    @Test
    void flagOffMatchesLegacyGetOrDefaultForAbsentTripIndex() {
        // A trip index absent from tripToGroupId (no-chain trip) must still fall back to
        // the singleton form, exactly as the pre-existing getOrDefault(...) did.
        String groupId = DrtRequestFactory.resolveGroupId(
                ONE_SUBTOUR_CHAIN, /* tripIdx */ 99, PERSON,
                /* spontaneousSingletonChains */ false, /* isCommute */ false, /* isEducation */ false);
        assertEquals(PERSON + "_trip_99", groupId);
    }
}
