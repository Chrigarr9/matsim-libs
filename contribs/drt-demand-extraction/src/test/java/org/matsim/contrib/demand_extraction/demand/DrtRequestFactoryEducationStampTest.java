package org.matsim.contrib.demand_extraction.demand;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.scoring.DemandExtractionScoringAdapter;
import org.matsim.contrib.demand_extraction.scoring.TripScoreRequest;
import org.matsim.contrib.demand_extraction.scoring.TripScoreResult;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EXT-6 regression: {@link DrtRequestFactory#buildRequest} must stamp the
 * per-trip {@code isEducation} flag onto the built {@link DrtRequest}. Before the
 * fix the flag was computed in {@code buildRequests} but never passed to
 * {@code buildRequest}, so every request (and every CSV row) carried
 * {@code isEducation=false}, silently emptying Paper-2 education segmentation.
 *
 * <p>Strategy mirrors {@code DrtRequestFactoryFinalizeTest}: a lightweight
 * {@link DrtRequestFactory} subclass with a stub {@link BudgetValidator} and an
 * overridden {@code budgetDerivedCaps}, so we exercise the real
 * {@code buildRequest} stamping path without constructing a Controler.
 */
class DrtRequestFactoryEducationStampTest {

    @Test
    void buildRequest_stampsEducationTrue() {
        assertEducationRoundTrips(true);
    }

    @Test
    void buildRequest_stampsEducationFalse() {
        assertEducationRoundTrips(false);
    }

    private void assertEducationRoundTrips(boolean isEducation) {
        Network network = twoLinkNetwork();
        ExMasConfigGroup exMas = buildConfig();

        Person person = PopulationUtils.getFactory().createPerson(Id.createPersonId("p_edu"));
        TripStructureUtils.Trip trip = buildTrip(person);

        DrtRequestFactory harness = new BuildRequestHarness(exMas, stubValidator(exMas), network);

        // ModeAttributes for the DRT mode: positive travel time + distance so the
        // request survives the sanity gates; distance << 5x the ~5000 m beeline.
        Map<String, ModeAttributes> modeAttrs = Map.of(
                "drt", new ModeAttributes(/* travelTime */ 600.0, /* distance */ 6000.0, /* score */ 0.0));

        DrtRequest request = harness.buildRequest(
                /* requestIndex */ 0, person, trip, /* tripIdx */ 0, /* groupId */ "g0",
                /* isCommute */ false, isEducation,
                Map.entry("car", -1.0), modeAttrs, /* ptMetrics */ null);

        assertNotNull(request, "request must survive the sanity gates (positive stub budget)");
        if (isEducation) {
            assertTrue(request.isEducation,
                    "buildRequest must stamp isEducation=true onto the built request");
        } else {
            assertFalse(request.isEducation,
                    "buildRequest must stamp isEducation=false onto the built request");
        }
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    /** Two well-separated links (o at ~0 m, d at ~5000 m) so beeline > 100 m and
     *  routed/beeline ratio stays under the 5x realism gate. */
    private static Network twoLinkNetwork() {
        Network network = NetworkUtils.createNetwork();
        Node n1 = NetworkUtils.createAndAddNode(network, Id.createNodeId("n1"), new Coord(0.0, 0.0));
        Node n2 = NetworkUtils.createAndAddNode(network, Id.createNodeId("n2"), new Coord(100.0, 0.0));
        Node n3 = NetworkUtils.createAndAddNode(network, Id.createNodeId("n3"), new Coord(5000.0, 0.0));
        Node n4 = NetworkUtils.createAndAddNode(network, Id.createNodeId("n4"), new Coord(5100.0, 0.0));
        NetworkUtils.createAndAddLink(network, Id.createLinkId("l_o"), n1, n2, 100.0, 10.0, 1000.0, 1.0);
        NetworkUtils.createAndAddLink(network, Id.createLinkId("l_d"), n3, n4, 100.0, 10.0, 1000.0, 1.0);
        return network;
    }

    private static ExMasConfigGroup buildConfig() {
        ExMasConfigGroup exMas = new ExMasConfigGroup();
        exMas.setDrtMode("drt");
        exMas.setMaxDetourFactor(2.0);
        exMas.setEnableBudgetAwareConstraints(false);
        // Simple scalar flexibility so FlexibilityCalculator needs no attributes.
        exMas.setNegativeFlexibilityAbsoluteMap("default:0.0");
        exMas.setPositiveFlexibilityAbsoluteMap("default:0.0");
        return exMas;
    }

    /** home(l_o, endTime) --car--> work(l_d), extracted as a real Trip. */
    private static TripStructureUtils.Trip buildTrip(Person person) {
        PopulationFactory pf = PopulationUtils.getFactory();
        Plan plan = pf.createPlan();

        Activity home = pf.createActivityFromLinkId("home", Id.createLinkId("l_o"));
        home.setEndTime(8 * 3600.0);
        Leg leg = pf.createLeg(TransportMode.car);
        Activity work = pf.createActivityFromLinkId("work", Id.createLinkId("l_d"));

        plan.addActivity(home);
        plan.addLeg(leg);
        plan.addActivity(work);
        person.addPlan(plan);

        List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(plan);
        return trips.get(0);
    }

    /** Stub validator: always positive budget + a minimal marker scoring context. */
    private static BudgetValidator stubValidator(ExMasConfigGroup exMas) {
        DemandExtractionScoringAdapter adapter = new DemandExtractionScoringAdapter() {
            @Override public TripScoreResult scoreTrip(TripScoreRequest req) {
                return new TripScoreResult(0.0, "fake");
            }
            @Override public String getName() { return "fake"; }
            @Override public double getMarginalUtilityOfMoney(Person p, double d) { return 0.01; }
            @Override public boolean includesOpportunityCost() { return true; }
            @Override public boolean supportsDistanceSpecificMoneyUtility() { return false; }
        };
        return new BudgetValidator(adapter, exMas, 1.34) {
            @Override public double calculateBudget(DrtRequest r) { return 1.0; }
            @Override public double marginalUtilityOfMoney(DrtRequest r, Person p) { return 0.01; }
            @Override public DrtRequest.ScoringContext computeScoringContext(DrtRequest r, Person p) {
                return new DrtRequest.ScoringContext(
                        p,
                        PopulationUtils.createActivityFromLinkId("marker", Id.createLinkId("mk")),
                        PopulationUtils.createActivityFromLinkId("marker", Id.createLinkId("mk")),
                        0.0, 0.0, null, null, null, null, null, null, null, null);
            }
        };
    }

    /**
     * Minimal factory subclass: real network + real FlexibilityCalculator, stub
     * validator, and trivial budget-derived caps so no BudgetToConstraintsCalculator
     * needs wiring.
     */
    private static class BuildRequestHarness extends DrtRequestFactory {
        BuildRequestHarness(ExMasConfigGroup config, BudgetValidator validator, Network network) {
            super(config,
                    /* modeRoutingCache */ null,
                    /* chainIdentifier */ null,
                    /* commuteIdentifier */ null,
                    network,
                    /* budgetToConstraintsCalculator */ null,
                    validator,
                    new FlexibilityCalculator(config));
        }

        @Override
        double[] budgetDerivedCaps(double budget, Person person, DrtRequest draft) {
            return new double[] {100.0, 0.0, 0.0};
        }
    }
}
