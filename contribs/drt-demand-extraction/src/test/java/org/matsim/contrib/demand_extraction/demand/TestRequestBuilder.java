package org.matsim.contrib.demand_extraction.demand;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;

/**
 * Minimal in-memory {@link DrtRequest} factories for unit tests that only care
 * about the Extension-2 schema fields ({@code requestTag}, {@code hubId}).
 *
 * <p>The fixtures bypass {@link DrtRequestFactory} (which needs a routed
 * Scenario + scoring config) by going through the public {@link DrtRequest.Builder}
 * directly and supplying the bare minimum to satisfy {@code build()}'s validation.
 */
final class TestRequestBuilder {

    private TestRequestBuilder() {}

    /**
     * Returns a minimal {@link DrtRequest} tagged {@code "connecting"} with the
     * supplied {@code hubId}.
     */
    static DrtRequest connectingFixture(String hubId) {
        return baseBuilder(1, "p_connecting")
            .requestTag("connecting")
            .hubId(hubId)
            .build();
    }

    /**
     * Reverse-direction connecting fixture: origin (1000,0) is URBAN under the
     * tests' `x >= 500` metropole predicate, destination (0,0) is RURAL.
     * Journey: urban origin -> hub -> rural destination (e.g. work -> home).
     */
    static DrtRequest connectingReverseFixture(String hubId) {
        return baseBuilder(3, "p_connecting_rev")
            .requestTag("connecting")
            .hubId(hubId)
            .originLinkId(Id.createLinkId("l_d"))
            .destinationLinkId(Id.createLinkId("l_o"))
            .originX(1000.0).originY(0.0)
            .destinationX(0.0).destinationY(0.0)
            .originLinkCoordFromX(1000.0).originLinkCoordFromY(0.0)
            .originLinkCoordToX(1000.0).originLinkCoordToY(0.0)
            .destinationLinkCoordFromX(0.0).destinationLinkCoordFromY(0.0)
            .destinationLinkCoordToX(0.0).destinationLinkCoordToY(0.0)
            .build();
    }

    /**
     * Returns a minimal {@link DrtRequest} tagged {@code "rural_intra"} with a
     * {@code null} {@code hubId} (non-virtual trip).
     */
    static DrtRequest ruralIntraFixture() {
        return baseBuilder(2, "p_rural_intra")
            .requestTag("rural_intra")
            .hubId(null)
            .build();
    }

    private static DrtRequest.Builder baseBuilder(int index, String personIdStr) {
        // Window: earliestDeparture(0) + directTravelTime(600) <= latestArrival(3600),
        // which satisfies the build()'s `earliestDeparture > latestArrival - directTravelTime`
        // check (it requires <=, not <). The 3600 s envelope is wide enough that the
        // Paper-2 temporal-split copies (rural leg + buffer + urban leg) still fit, so
        // the routed-leg / shift / deadline-backout tests have a feasible hub.
        return DrtRequest.builder()
            .index(index)
            .personId(Id.createPersonId(personIdStr))
            .groupId(personIdStr + "_g0")
            .tripIndex(0)
            .isCommute(false)
            .isEducation(false)
            .budget(0.0)
            .bestModeScore(0.0)
            .bestMode("walk")
            .originLinkId(Id.createLinkId("l_o"))
            .destinationLinkId(Id.createLinkId("l_d"))
            .originX(0.0).originY(0.0)
            .destinationX(1000.0).destinationY(0.0)
            .originLinkCoordFromX(0.0).originLinkCoordFromY(0.0)
            .originLinkCoordToX(0.0).originLinkCoordToY(0.0)
            .destinationLinkCoordFromX(1000.0).destinationLinkCoordFromY(0.0)
            .destinationLinkCoordToX(1000.0).destinationLinkCoordToY(0.0)
            .requestTime(0.0)
            .earliestDeparture(0.0)
            .latestArrival(3600.0)
            .directTravelTime(600.0)
            .directDistance(1000.0)
            .maxDetourFactor(1.5)
            .originActivityType("home")
            .destinationActivityType("work")
            .carTravelTime(600.0)
            .ptTravelTime(900.0)
            .ptAccessibility(1.5);
    }

    // Type witnesses kept purely so this file imports something concrete and
    // breaks loudly if package layout changes; never used at runtime.
    @SuppressWarnings("unused") private static final Class<Link> LINK = Link.class;
    @SuppressWarnings("unused") private static final Class<Person> PERSON = Person.class;
    @SuppressWarnings("unused") private static final Class<Id> ID = Id.class;
}
