package org.matsim.contrib.demand_extraction.demand;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.scoring.DemandExtractionScoringAdapter;
import org.matsim.contrib.demand_extraction.scoring.TripScoreRequest;
import org.matsim.contrib.demand_extraction.scoring.TripScoreResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Tests for Task 10: factory finalization — {@link DrtRequestFactory#finalizeVirtualLeg}
 * (fresh scoring context, per-leg budget, drop on non-positive) and
 * {@link DrtRequestFactory#renumber} (index == position invariant).
 *
 * <p>Both methods are package-private so this test (same package) can access them
 * directly without reflection.
 */
public class DrtRequestFactoryFinalizeTest {

    // -------------------------------------------------------------------------
    // Fake adapter — reuses the same minimal pattern as ContinuationLegScoringTest
    // -------------------------------------------------------------------------

    private static DemandExtractionScoringAdapter fakeAdapter() {
        return new DemandExtractionScoringAdapter() {
            @Override
            public TripScoreResult scoreTrip(TripScoreRequest req) {
                double ivt = 0.0;
                double walk = 0.0;
                for (PlanElement pe : req.routedElements()) {
                    if (pe instanceof Leg leg) {
                        double tt = leg.getTravelTime().orElse(0.0);
                        if (leg.getMode().contains(TransportMode.walk)) {
                            walk += tt;
                        } else {
                            ivt += tt;
                        }
                    }
                }
                return new TripScoreResult(-0.001 * ivt - 0.002 * walk, "fake");
            }

            @Override public String getName() { return "fake"; }
            @Override public double getMarginalUtilityOfMoney(Person p, double d) { return 0.01; }
            @Override public boolean includesOpportunityCost() { return true; }
            @Override public boolean supportsDistanceSpecificMoneyUtility() { return false; }
        };
    }

    /**
     * A distinct marker ScoringContext that can be identity-checked via assertSame.
     * The context is wired with real objects only for person; the rest are null
     * because finalizeVirtualLeg only reads person from it and replaces everything else.
     */
    private static DrtRequest.ScoringContext markerContext(Person p) {
        return new DrtRequest.ScoringContext(
                p,
                org.matsim.core.population.PopulationUtils.createActivityFromLinkId(
                        "marker", Id.createLinkId("mk")),
                org.matsim.core.population.PopulationUtils.createActivityFromLinkId(
                        "marker", Id.createLinkId("mk")),
                0.0, 0.0,
                /* scoringParams */ null,
                /* accessWalkLeg */ null,
                /* accessWalkRoute */ null,
                /* egressWalkLeg */ null,
                /* egressWalkRoute */ null,
                /* drtRouteTemplate */ null,
                /* synOrig */ null,
                /* synDest */ null);
    }

    // -------------------------------------------------------------------------
    // Test 1: finalizeVirtualLeg — recomputes budget, drops non-positive,
    //         attaches fresh scoring context from the stub validator.
    // -------------------------------------------------------------------------

    /**
     * Stub BudgetValidator: returns +1.0 for ACCESS_LEG, -0.5 for CONTINUATION_LEG.
     * Also returns a recognisable markerContext from computeScoringContext.
     */
    private static BudgetValidator buildStubValidator() {
        ExMasConfigGroup exMas = new ExMasConfigGroup();
        exMas.setDrtMode("drt");
        exMas.setMinDrtAccessEgressDistance(100.0);
        return new BudgetValidator(fakeAdapter(), exMas, 1.34) {
            @Override
            public double calculateBudget(DrtRequest r) {
                return r.hubLegRole == DrtRequest.HubLegRole.ACCESS_LEG ? 1.0 : -0.5;
            }

            @Override
            public DrtRequest.ScoringContext computeScoringContext(DrtRequest r, Person p) {
                return markerContext(p);
            }
        };
    }

    @Test
    void finalize_recomputesBudgetAndDropsNonPositive() {
        ExMasConfigGroup exMasConfig = new ExMasConfigGroup();
        exMasConfig.setDrtMode("drt");
        exMasConfig.setMinDrtAccessEgressDistance(100.0);
        exMasConfig.setEnableBudgetAwareConstraints(false);

        // Build two virtual copies: one ACCESS_LEG (budget > 0), one CONTINUATION_LEG (budget < 0).
        DrtRequest accessCopy = buildVirtualCopy(DrtRequest.HubLegRole.ACCESS_LEG, 0.0);
        DrtRequest continuationCopy = buildVirtualCopy(DrtRequest.HubLegRole.CONTINUATION_LEG, 300.0);

        Person markerPerson = org.matsim.core.population.PopulationUtils.getFactory()
                .createPerson(accessCopy.personId);
        // Pre-populate the copies' scoring context with a non-null value so
        // finalizeVirtualLeg can read person from it (the stub validator replaces it).
        DrtRequest.ScoringContext dummyCtx = markerContext(markerPerson);
        accessCopy.setScoringContext(dummyCtx);
        continuationCopy.setScoringContext(dummyCtx);

        BudgetValidator stub = buildStubValidator();

        // FinalizeHarness: overrides budgetDerivedCaps to trivial fixed values so we
        // don't need a real BudgetToConstraintsCalculator wired to a full Config.
        DrtRequestFactory harness = new FinalizeHarness(exMasConfig, stub);

        DrtRequest keptResult = harness.finalizeVirtualLeg(accessCopy, markerPerson, stub);
        DrtRequest droppedResult = harness.finalizeVirtualLeg(continuationCopy, markerPerson, stub);

        // Continuation leg must be dropped (budget = -0.5 <= 0)
        assertNull(droppedResult, "continuation copy with negative budget must be dropped");

        // Access leg must be kept
        assertNotNull(keptResult, "access copy with positive budget must be kept");
        assertEquals(1.0, keptResult.budget, 1e-12, "budget must be the stub-returned value");

        // Scoring context on the kept copy must be the markerContext produced by the stub.
        assertNotNull(keptResult.getScoringContext(), "scoring context must be set");
        assertSame(markerPerson, keptResult.getScoringContext().person(),
                "scoring context person must be the marker person returned by computeScoringContext");

        // maxDetourFactor must be finite and >= 1.0.
        // With our trivial caps stub: maxAbsoluteDetour = 100.0, directTravelTime = 600.0
        // => effectiveMaxDetourFactor = 1 + 100/600 ≈ 1.167.
        double maxDF = keptResult.maxDetourFactor;
        assertEquals(true, Double.isFinite(maxDF) && maxDF >= 1.0,
                "maxDetourFactor must be finite and >= 1.0 after finalization; got " + maxDF);
        // Verify it was recomputed off the LEG's directTravelTime (not some stale value).
        double expectedDF = 1.0 + (100.0 / accessCopy.directTravelTime); // caps[0]=100, tt=600
        assertEquals(expectedDF, maxDF, 1e-9,
                "effectiveMaxDetourFactor must be 1 + caps[0]/directTravelTime");
    }

    // -------------------------------------------------------------------------
    // Test 2: renumber — restores index == position invariant.
    // -------------------------------------------------------------------------

    @Test
    void renumber_restoresIndexEqualsPosition() {
        // Build 5 requests with indices {0, 4, 4, 4, 9} (post-expansion shape).
        List<DrtRequest> input = new ArrayList<>();
        int[] rawIndices = {0, 4, 4, 4, 9};
        DrtRequest.ScoringContext[] contexts = new DrtRequest.ScoringContext[rawIndices.length];
        Person sharedPerson = org.matsim.core.population.PopulationUtils.getFactory()
                .createPerson(Id.createPersonId("p_renumber"));
        for (int i = 0; i < rawIndices.length; i++) {
            DrtRequest r = buildVirtualCopy(DrtRequest.HubLegRole.NONE, 0.0, rawIndices[i],
                    "p_renumber_" + i);
            contexts[i] = markerContext(sharedPerson);
            r.setScoringContext(contexts[i]);
            input.add(r);
        }

        List<DrtRequest> out = DrtRequestFactory.renumber(input);

        assertEquals(5, out.size(), "renumber must not change list size");
        for (int i = 0; i < out.size(); i++) {
            assertEquals(i, out.get(i).index,
                    "after renumber, request at position " + i + " must have index " + i);
            // Scoring context must be preserved by reference (not rebuilt).
            assertSame(contexts[i], out.get(i).getScoringContext(),
                    "scoring context must be preserved by reference at position " + i);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Builds a virtual copy with the given role, transferWait, default index=1. */
    private static DrtRequest buildVirtualCopy(DrtRequest.HubLegRole role, double transferWait) {
        return buildVirtualCopy(role, transferWait, 1, "p_finalize");
    }

    /** Builds a virtual copy with the given role, transferWait, and index. */
    private static DrtRequest buildVirtualCopy(DrtRequest.HubLegRole role, double transferWait,
            int index) {
        return buildVirtualCopy(role, transferWait, index, "p_finalize");
    }

    private static DrtRequest buildVirtualCopy(DrtRequest.HubLegRole role, double transferWait,
            int index, String personId) {
        return DrtRequest.builder()
                .index(index)
                .personId(Id.createPersonId(personId))
                .groupId(personId + "_g0")
                .tripIndex(0)
                .isCommute(false)
                .isEducation(false)
                .budget(0.0)
                .bestModeScore(0.0)
                .bestMode("walk")
                .requestTag("connecting")
                .hubId("hub_a")
                .hubLegRole(role)
                .transferWaitSeconds(transferWait)
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
                .directDistance(6000.0)
                .maxDetourFactor(1.5)
                .originActivityType("home")
                .destinationActivityType("work")
                .carTravelTime(600.0)
                .ptTravelTime(900.0)
                .ptAccessibility(1.5)
                .build();
    }

    /**
     * Minimal DrtRequestFactory subclass that:
     * - Passes null for every injected member not used by finalizeVirtualLeg.
     * - Overrides budgetDerivedCaps to return trivial fixed values so no real
     *   BudgetToConstraintsCalculator needs to be configured.
     */
    private static class FinalizeHarness extends DrtRequestFactory {

        FinalizeHarness(ExMasConfigGroup config, BudgetValidator validator) {
            super(config,
                    /* modeRoutingCache */ null,
                    /* chainIdentifier */ null,
                    /* commuteIdentifier */ null,
                    /* network */ null,
                    /* budgetToConstraintsCalculator */ null,
                    validator,
                    /* flexibilityCalculator */ null);
        }

        /**
         * Override caps to trivial fixed values: maxAbsoluteDetour=100, walk=0, wait=0.
         * This avoids needing a real BudgetToConstraintsCalculator wired to a full Config.
         */
        @Override
        double[] budgetDerivedCaps(double budget, Person person, DrtRequest draft) {
            return new double[] {100.0, 0.0, 0.0};
        }
    }
}
