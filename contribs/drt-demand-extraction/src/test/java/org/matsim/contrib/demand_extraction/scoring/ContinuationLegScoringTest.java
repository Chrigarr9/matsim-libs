package org.matsim.contrib.demand_extraction.scoring;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scoring.functions.ScoringParameters;
import org.matsim.examples.ExamplesUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for Task 9: continuation-leg scoring — single ASC, no second access
 * walk, transfer wait charged.
 *
 * <p>The target algebra for a connected intermodal O->hub->D journey is:
 * <pre>
 *   U_access       = α + β_tt·t_r + β_walk·(acc_O + walk_hub) + β_wait·w_pickup
 *   U_continuation =     β_tt·t_u + β_walk·(egr_D)            + β_wait·buffer
 * </pre>
 * Together they produce exactly one ASC, one origin access walk, one hub egress
 * walk (= transfer walk), one egress at D, one pickup wait, one transfer wait —
 * the utility of the physical intermodal journey.
 */
class ContinuationLegScoringTest {

    // Fake adapter constants — tuned so the expected-delta formula is easy to verify.
    private static final double IVT_COEFF = 0.001;   // utils/s IVT
    private static final double WALK_COEFF = 0.002;  // utils/s walk
    private static final double ASC = -1.0;           // mode constant (utils)

    // Transfer wait (CONTINUATION_LEG only)
    private static final double TRANSFER_WAIT_S = 300.0;

    // Known mWait: MATSim default for waiting-pt is -6 utils/hr = -1/600 utils/s
    private static final double M_WAIT_S = -6.0 / 3600.0; // = -1/600

    // Walk speed from routing config (set to 1.34 m/s in helpers below)
    private static final double WALK_SPEED = 1.34;

    // minWalk for ExMasConfigGroup (set to 100 m below)
    private static final double MIN_WALK = 100.0;

    // Derived minimum walk time (same for access and egress)
    private static final double MIN_WALK_TIME = MIN_WALK / WALK_SPEED; // ~74.6 s

    // Fake adapter: utility = ASC (unless excludeModeConstant) - IVT_COEFF*ivt - WALK_COEFF*walk
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
                double u = -IVT_COEFF * ivt - WALK_COEFF * walk;
                if (!req.excludeModeConstant()) {
                    u += ASC;
                }
                return new TripScoreResult(u, "fake");
            }

            @Override
            public String getName() {
                return "fake";
            }

            @Override
            public double getMarginalUtilityOfMoney(Person p, double d) {
                return 0.01;
            }

            @Override
            public boolean includesOpportunityCost() {
                return true; // suppress extra opportunity-cost term
            }

            @Override
            public boolean supportsDistanceSpecificMoneyUtility() {
                return false;
            }
        };
    }

    /** Build the shared BudgetValidator backed by the fake adapter. */
    private static BudgetValidator buildValidator() {
        ExMasConfigGroup exMas = new ExMasConfigGroup();
        exMas.setDrtMode("drt");
        exMas.setMinDrtAccessEgressDistance(MIN_WALK);
        exMas.setIncludeOpportunityCost(false);
        // Walk speed must match what DrtTripScorer uses; stored in routing config.
        // BudgetValidator Phase-2 ctor accepts walkSpeed directly.
        return new BudgetValidator(fakeAdapter(), exMas, WALK_SPEED);
    }

    /**
     * Build a DrtRequest and attach a minimal ScoringContext so
     * {@code calculateDrtScoreWithWalks} has something to work with.
     * The context's scoringParams must supply {@code marginalUtilityOfWaitingPt_s}.
     */
    private static DrtRequest buildRequestWithContext(DrtRequest.HubLegRole role,
            double transferWaitSeconds) {
        DrtRequest req = baseBuilder(role, transferWaitSeconds).build();
        req.setScoringContext(buildScoringContext(req));
        return req;
    }

    private static DrtRequest.Builder baseBuilder(DrtRequest.HubLegRole role,
            double transferWaitSeconds) {
        return DrtRequest.builder()
                .index(1)
                .personId(Id.createPersonId("p1"))
                .groupId("p1_g0")
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
                .directDistance(6000.0)
                .maxDetourFactor(1.5)
                .originActivityType("home")
                .destinationActivityType("work")
                .carTravelTime(600.0)
                .ptTravelTime(900.0)
                .ptAccessibility(1.5)
                .hubLegRole(role)
                .transferWaitSeconds(transferWaitSeconds);
    }

    /** Build a minimal ScoringContext wired with the marginalUtilityOfWaitingPt_s we expect. */
    private static DrtRequest.ScoringContext buildScoringContext(DrtRequest req) {
        // Load a base config that provides a valid ScoringConfigGroup/ScenarioConfig
        URL scenarioUrl = ExamplesUtils.getTestScenarioURL("dvrp-grid");
        Config config;
        try {
            config = ConfigUtils.loadConfig(
                    new URL(scenarioUrl, "one_shared_taxi_config.xml").toString(),
                    new MultiModeDrtConfigGroup(),
                    new DvrpConfigGroup(),
                    new ExMasConfigGroup());
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
        config.removeModule("otfvis");

        ScoringConfigGroup scoring = config.scoring();
        scoring.setMarginalUtlOfWaitingPt_utils_hr(M_WAIT_S * 3600.0); // -6 utils/hr

        ScoringParameters scoringParams = new ScoringParameters.Builder(
                scoring,
                scoring.getScoringParameters(null),
                config.scenario()).build();

        Person person = PopulationUtils.getFactory().createPerson(req.personId);

        // Reuse the standard context builder from BudgetValidator (private helper replicated here)
        var accessLeg = org.matsim.core.population.PopulationUtils.createLeg(TransportMode.walk);
        var accessRoute = org.matsim.core.population.routes.RouteUtils
                .createGenericRouteImpl(req.originLinkId, req.originLinkId);
        double walkDist = MIN_WALK;
        double accessTime = walkDist / WALK_SPEED;
        accessRoute.setDistance(walkDist);
        accessRoute.setTravelTime(accessTime);
        accessLeg.setRoute(accessRoute);
        accessLeg.setTravelTime(accessTime);

        var egressLeg = org.matsim.core.population.PopulationUtils.createLeg(TransportMode.walk);
        var egressRoute = org.matsim.core.population.routes.RouteUtils
                .createGenericRouteImpl(req.destinationLinkId, req.destinationLinkId);
        egressRoute.setDistance(walkDist);
        egressRoute.setTravelTime(accessTime);
        egressLeg.setRoute(egressRoute);
        egressLeg.setTravelTime(accessTime);

        var drtRoute = new org.matsim.contrib.drt.routing.DrtRoute(
                req.originLinkId, req.destinationLinkId);
        drtRoute.setDirectRideTime(req.directTravelTime);
        drtRoute.setDistance(req.directDistance);

        var synOrig = org.matsim.core.population.PopulationUtils
                .createActivityFromLinkId("drt_interaction", req.originLinkId);
        synOrig.setEndTime(req.requestTime);
        var synDest = org.matsim.core.population.PopulationUtils
                .createActivityFromLinkId("drt_interaction", req.destinationLinkId);

        return new DrtRequest.ScoringContext(
                person, synOrig, synDest,
                /* originDuration */ 0.0, /* destDuration */ 0.0,
                scoringParams,
                accessLeg, accessRoute, egressLeg, egressRoute,
                drtRoute, synOrig, synDest);
    }

    // -------------------------------------------------------------------------
    // Core correctness test
    // -------------------------------------------------------------------------

    /**
     * Score both a NONE (normal) and a CONTINUATION_LEG request with identical
     * travel inputs via {@link BudgetValidator#calculateDrtScoreWithWalks} and
     * assert the delta matches the expected formula:
     *
     * <pre>
     *   delta = score(continuation) - score(normal)
     *         = +1.0  (dropped ASC)
     *         + WALK_COEFF * minWalkTime  (dropped access walk)
     *         + M_WAIT_S * 300            (added transfer wait)
     * </pre>
     */
    @Test
    void continuationLeg_dropsAscAndAccessWalk_andChargesTransferWait() {
        BudgetValidator validator = buildValidator();

        DrtRequest normalReq = buildRequestWithContext(DrtRequest.HubLegRole.NONE, 0.0);
        DrtRequest continuationReq = buildRequestWithContext(
                DrtRequest.HubLegRole.CONTINUATION_LEG, TRANSFER_WAIT_S);

        // Call parameters: delay=0, tt=600, dist=6000, accessWalk=minWalk, egressWalk=minWalk
        double normalScore = validator.calculateDrtScoreWithWalks(
                normalReq, 0.0, 600.0, 6000.0, MIN_WALK, MIN_WALK);
        double continuationScore = validator.calculateDrtScoreWithWalks(
                continuationReq, 0.0, 600.0, 6000.0, MIN_WALK, MIN_WALK);

        // Expected delta (continuation - normal):
        //   +1.0             from dropping ASC (-1.0 → 0)
        //   +WALK_COEFF * minWalkTime  from dropping the ACCESS walk leg
        //   +M_WAIT_S * 300  from charging transfer wait (negative value = negative contribution)
        double expectedDelta = -ASC                                    // = +1.0
                + WALK_COEFF * MIN_WALK_TIME                           // access walk dropped
                + M_WAIT_S * TRANSFER_WAIT_S;                          // transfer wait charged

        assertEquals(expectedDelta, continuationScore - normalScore, 1e-9,
                "continuation delta should match: +ASC_drop + access_walk_drop + transfer_wait");
    }

    // -------------------------------------------------------------------------
    // Regression: NONE role is completely unchanged (no flag, no wait charge)
    // -------------------------------------------------------------------------

    /**
     * NONE role scores equal a closed-form expected value:
     * {@code ASC - IVT_COEFF*600 - 2*WALK_COEFF*MIN_WALK_TIME}.
     *
     * <p>This is a real regression guard: if any of the new conditionals were deleted
     * (e.g. ASC omitted, or walk terms dropped), this test would fail. The previous
     * version compared two identical NONE requests and could never fail even if all
     * conditionals were removed.
     */
    @Test
    void noneRole_isIdenticalToBaseline() {
        BudgetValidator validator = buildValidator();

        DrtRequest r1 = buildRequestWithContext(DrtRequest.HubLegRole.NONE, 0.0);

        double score = validator.calculateDrtScoreWithWalks(r1, 0.0, 600.0, 6000.0, MIN_WALK, MIN_WALK);

        // Closed-form: one ASC + IVT over 600s + two walk legs each of MIN_WALK_TIME
        double expected = ASC
                - IVT_COEFF * 600.0
                - 2.0 * WALK_COEFF * MIN_WALK_TIME;

        assertEquals(expected, score, 1e-9,
                "NONE baseline score must match: ASC - IVT_COEFF*600 - 2*WALK_COEFF*MIN_WALK_TIME");
    }

    // -------------------------------------------------------------------------
    // ACCESS_LEG: scored like NONE (ASC present, access walk present, no wait)
    // -------------------------------------------------------------------------

    @Test
    void accessLeg_isScoredLikeNone() {
        BudgetValidator validator = buildValidator();

        DrtRequest noneReq = buildRequestWithContext(DrtRequest.HubLegRole.NONE, 0.0);
        DrtRequest accessReq = buildRequestWithContext(DrtRequest.HubLegRole.ACCESS_LEG, 0.0);

        double noneScore = validator.calculateDrtScoreWithWalks(
                noneReq, 0.0, 600.0, 6000.0, MIN_WALK, MIN_WALK);
        double accessScore = validator.calculateDrtScoreWithWalks(
                accessReq, 0.0, 600.0, 6000.0, MIN_WALK, MIN_WALK);

        assertEquals(noneScore, accessScore, 1e-9,
                "ACCESS_LEG must score identically to NONE (ASC present, access walk present)");
    }

    // -------------------------------------------------------------------------
    // BudgetValidator.calculateBudget: continuation vs none
    // -------------------------------------------------------------------------

    /**
     * {@link BudgetValidator#calculateBudget} is the public entry point called by
     * {@code DrtRequestFactory} for each constructed request. Verify that the
     * continuation path (no ASC, no access walk) produces a STRICTLY GREATER budget
     * than the equivalent NONE path (ASC present, access walk present).
     *
     * <p>With the small {@code TRANSFER_WAIT_S=300} buffer, the gain from dropping ASC
     * (+1.0) and the access walk (+WALK_COEFF*MIN_WALK_TIME ≈ +0.149) easily dominates
     * the transfer-wait charge (M_WAIT_S*300 ≈ -0.5), so the net delta is positive and
     * strictly discriminates the two branches.
     */
    @Test
    void calculateBudget_continuationIsStrictlyGreaterThanNone() {
        BudgetValidator validator = buildValidator();

        // Both requests have zero bestModeScore so calculateBudget == drtScore.
        // The DrtRequest.builder sets budget=0 and bestModeScore=0 in baseBuilder.
        DrtRequest noneReq = buildRequestWithContext(DrtRequest.HubLegRole.NONE, 0.0);
        DrtRequest continuationReq = buildRequestWithContext(
                DrtRequest.HubLegRole.CONTINUATION_LEG, TRANSFER_WAIT_S);

        double noneBudget = validator.calculateBudget(noneReq);
        double continuationBudget = validator.calculateBudget(continuationReq);

        // continuation must be strictly more attractive (higher budget)
        // because no ASC charge and no access walk more than compensate for transfer wait
        double expectedDelta = -ASC                       // = +1.0  (ASC dropped)
                + WALK_COEFF * MIN_WALK_TIME              // access walk dropped
                + M_WAIT_S * TRANSFER_WAIT_S;             // transfer wait charged (negative)

        assertEquals(expectedDelta, continuationBudget - noneBudget, 1e-9,
                "calculateBudget delta (continuation - none) must match: +ASC_drop + access_walk_drop + transfer_wait");
        assertTrue(continuationBudget > noneBudget,
                "continuation budget must be strictly greater than NONE budget for these inputs");
    }
}
