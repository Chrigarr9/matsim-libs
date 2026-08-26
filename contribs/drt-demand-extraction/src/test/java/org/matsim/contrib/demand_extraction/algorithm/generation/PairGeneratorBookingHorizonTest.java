package org.matsim.contrib.demand_extraction.algorithm.generation;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCacheTestFixture;
import org.matsim.contrib.demand_extraction.algorithm.validation.BookingHorizonRule;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;
import org.matsim.contrib.demand_extraction.scoring.DemandExtractionScoringAdapter;
import org.matsim.contrib.demand_extraction.scoring.TripScoreRequest;
import org.matsim.contrib.demand_extraction.scoring.TripScoreResult;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 3: PairGenerator enforcement of the booking-time rule (BookingHorizonRule).
 *
 * <h2>Network topology</h2>
 * Same 6-node line as {@link PairGeneratorMaxWaitFilterTest}:
 * n0(0,0) - n1(1000,0) - n2(2000,0) - n3(3000,0) - n4(4000,0) - n5(5000,0), links 1000 m
 * each at 10 m/s (100 s/link), directed forward only (n_k -> n_k+1).
 *
 * <h2>Requests</h2>
 * reqA: mandatory commuter, origin=link01, dest=link45, requestTime=28800 (08:00).
 * reqB: spontaneous (non-commute, non-education), origin=link23, dest=link45,
 * requestTime=30600 (08:30).
 *
 * <p>Routing is forward-only, so only the A-first candidate ordering exists (reqA processed
 * as the first pickup routes A-origin -> B-origin in the forward direction; the reverse
 * B-origin -> A-origin direction is unreachable, so no B-first ordering is generated).
 * The A-first ordering's start time is reqA.requestTime = 28800, which is 1800 s (30 min)
 * before reqB's request time (30600) - short of the 600 s spontaneous booking horizon,
 * so with the horizon enabled every surviving candidate for this pair is removed.
 */
class PairGeneratorBookingHorizonTest {

    private static final Id<Link> LINK_01 = Id.createLinkId("link01");
    private static final Id<Link> LINK_12 = Id.createLinkId("link12");
    private static final Id<Link> LINK_23 = Id.createLinkId("link23");
    private static final Id<Link> LINK_34 = Id.createLinkId("link34");
    private static final Id<Link> LINK_45 = Id.createLinkId("link45");

    private static final double LINK_LEN = 1000.0;
    private static final double FREESPEED = 10.0;

    private static final double DIRECT_A = 4 * LINK_LEN / FREESPEED; // 400 s
    private static final double DIRECT_B = 2 * LINK_LEN / FREESPEED; // 200 s

    private static final double REQUEST_TIME_A = 28800.0; // 08:00
    private static final double REQUEST_TIME_B = 30600.0; // 08:30

    private MatsimNetworkCache cache;
    private BudgetValidator validator;

    @BeforeEach
    void setUp() {
        Network network = NetworkUtils.createNetwork();
        NetworkFactory f = network.getFactory();

        Node n0 = f.createNode(Id.createNodeId("n0"), new Coord(0.0, 0.0));
        Node n1 = f.createNode(Id.createNodeId("n1"), new Coord(LINK_LEN, 0.0));
        Node n2 = f.createNode(Id.createNodeId("n2"), new Coord(2 * LINK_LEN, 0.0));
        Node n3 = f.createNode(Id.createNodeId("n3"), new Coord(3 * LINK_LEN, 0.0));
        Node n4 = f.createNode(Id.createNodeId("n4"), new Coord(4 * LINK_LEN, 0.0));
        Node n5 = f.createNode(Id.createNodeId("n5"), new Coord(5 * LINK_LEN, 0.0));
        network.addNode(n0); network.addNode(n1); network.addNode(n2);
        network.addNode(n3); network.addNode(n4); network.addNode(n5);

        addLink(network, f, "link01", n0, n1);
        addLink(network, f, "link12", n1, n2);
        addLink(network, f, "link23", n2, n3);
        addLink(network, f, "link34", n3, n4);
        addLink(network, f, "link45", n4, n5);

        FreeSpeedTravelTime tt = new FreeSpeedTravelTime();
        OnlyTimeDependentTravelDisutility td = new OnlyTimeDependentTravelDisutility(tt);
        cache = MatsimNetworkCacheTestFixture.createWithRouting(network, tt, td, 900);

        validator = new PassThroughBudgetValidator();
    }

    private static void addLink(Network net, NetworkFactory f, String id, Node from, Node to) {
        Link lnk = f.createLink(Id.createLinkId(id), from, to);
        lnk.setLength(LINK_LEN);
        lnk.setFreespeed(FREESPEED);
        lnk.setCapacity(1000.0);
        lnk.setNumberOfLanes(1.0);
        net.addLink(lnk);
    }

    private DrtRequest buildReqA() {
        return DrtRequest.builder()
                .index(0)
                .personId(Id.createPersonId("pax_a"))
                .groupId("g0")
                .tripIndex(0)
                .budget(5.0)
                .bestModeScore(-5.0)
                .bestMode("car")
                .isCommute(true)
                .isEducation(false)
                .originLinkId(LINK_01)
                .destinationLinkId(LINK_45)
                .originX(0.0).originY(0.0)
                .destinationX(5.0 * LINK_LEN).destinationY(0.0)
                .requestTime(REQUEST_TIME_A)
                .earliestDeparture(REQUEST_TIME_A - 600.0)
                .latestArrival(REQUEST_TIME_A + 10800.0)
                .directTravelTime(DIRECT_A)
                .directDistance(4 * LINK_LEN)
                .maxDetourFactor(5.0)
                .maxWaitTime(3600.0)
                .build();
    }

    private DrtRequest buildReqB() {
        return DrtRequest.builder()
                .index(1)
                .personId(Id.createPersonId("pax_b"))
                .groupId("g1")
                .tripIndex(0)
                .budget(5.0)
                .bestModeScore(-5.0)
                .bestMode("car")
                .isCommute(false)
                .isEducation(false)
                .originLinkId(LINK_23)
                .destinationLinkId(LINK_45)
                .originX(2.0 * LINK_LEN).originY(0.0)
                .destinationX(5.0 * LINK_LEN).destinationY(0.0)
                .requestTime(REQUEST_TIME_B)
                .earliestDeparture(REQUEST_TIME_B - 600.0)
                .latestArrival(REQUEST_TIME_B + 10800.0)
                .directTravelTime(DIRECT_B)
                .directDistance(2 * LINK_LEN)
                .maxDetourFactor(5.0)
                .maxWaitTime(3600.0)
                .build();
    }

    private static DrtRequest[] membersOf(Ride r) {
        return r.getRequests();
    }

    private List<Ride> generatePairs(double spontaneousBookingHorizon) {
        PairGenerator gen = new PairGenerator(cache, validator, /* horizon (TimeFilter floor)= */ 0.0,
                /* algorithmProcessCount= */ 1, /* budgetAwareConstraints= */ false,
                /* pairgenTopK= */ 0, spontaneousBookingHorizon);
        return gen.generatePairs(List.of(buildReqA(), buildReqB()));
    }

    private List<Ride> generatePairsViaOldConstructor() {
        PairGenerator gen = new PairGenerator(cache, validator, 0.0, 1, /* budgetAwareConstraints= */ false);
        return gen.generatePairs(List.of(buildReqA(), buildReqB()));
    }

    @Test
    void horizonRemovesOrderingsThatDepartBeforeBooking() {
        List<Ride> withoutHorizon = generatePairs(0.0);
        List<Ride> withHorizon = generatePairs(600.0);

        assertFalse(withoutHorizon.isEmpty(), "sanity: unconstrained run must produce candidates");

        // every surviving ride satisfies the rule
        for (Ride r : withHorizon) {
            assertTrue(BookingHorizonRule.isAdmissible(r.getStartTime(), membersOf(r), 600.0),
                    "ride " + r.getIndex() + " departs before the spontaneous member booked");
        }
        // at least one ride was removed relative to the unconstrained run
        assertTrue(withHorizon.size() < withoutHorizon.size());
    }

    @Test
    void zeroHorizonIsByteIdenticalLegacy() {
        assertEquals(generatePairsViaOldConstructor().toString(), generatePairs(0.0).toString());
    }

    // -------------------------------------------------------------------------
    // Passthrough BudgetValidator - always accepts rides
    // -------------------------------------------------------------------------

    private static final class PassThroughBudgetValidator extends BudgetValidator {
        private PassThroughBudgetValidator() {
            super(new NoOpScoringAdapter(), noOpScoringParameters(), new ExMasConfigGroup(), dummyConfig());
        }

        @Override
        public Ride validateAndPopulateBudgets(Ride ride) {
            return ride.toBuilder().remainingBudgets(new double[ride.getDegree()]).build();
        }

        private static Config dummyConfig() {
            Config config = ConfigUtils.createConfig();
            config.addModule(new ExMasConfigGroup());
            return config;
        }

        private static ScoringParametersForPerson noOpScoringParameters() {
            return person -> null;
        }
    }

    private static final class NoOpScoringAdapter implements DemandExtractionScoringAdapter {
        @Override
        public String getName() {
            return "noop";
        }

        @Override
        public TripScoreResult scoreTrip(TripScoreRequest request) {
            throw new UnsupportedOperationException("Not used in pass-through validator");
        }

        @Override
        public double getMarginalUtilityOfMoney(org.matsim.api.core.v01.population.Person person, double euclidDist_km) {
            return 1.0;
        }

        @Override
        public boolean supportsDistanceSpecificMoneyUtility() {
            return false;
        }

        @Override
        public boolean includesOpportunityCost() {
            return false;
        }
    }
}
