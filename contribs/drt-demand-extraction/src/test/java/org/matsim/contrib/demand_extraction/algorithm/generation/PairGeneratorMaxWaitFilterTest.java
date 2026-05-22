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
 * C5: Test for the PairGenerator maxWaitTime pre-filter.
 *
 * <h2>Network topology</h2>
 * 6 nodes in a line: n0(0,0) — n1(1000,0) — n2(2000,0) — n3(3000,0) — n4(4000,0) — n5(5000,0)
 * Links (1000 m each, 10 m/s = 100 s per link):
 *   link01 (n0→n1), link12 (n1→n2), link23 (n2→n3), link34 (n3→n4), link45 (n4→n5)
 *
 * <h2>Requests</h2>
 * Request i: origin=link01, dest=link45, requestTime=0.  DirectTT≈400s (n1→n2→n3→n4 + link45).
 * Request j: origin=link23, dest=link45, requestTime=10. DirectTT≈200s (n3→n4 + link45).
 *
 * <h2>Non-adjacent routing property</h2>
 * Routing segments for the FIFO ordering [i, j]:
 *   oo = link01 → link23: n1 → n2 (via link12) + link23 traversal ≈ 200 s  (non-adjacent ✓)
 *   od = link23 → link45: n3 → n4 (via link34) + link45 traversal ≈ 200 s  (non-adjacent ✓)
 *   dd = link45 → link45: same-link traversal                          ≈ 100 s  (same-link special case ✓)
 *
 * pttI = oo + od = 400 s,  pttJ = od + dd = 300 s — both within maxDetourFactor=5 bounds.
 * initialDelayJ = 0 + 200 − 10 = 190 s → optimizeDelays → adjusted_j ≈ 95 s.
 *
 * <h2>Tests</h2>
 *   - Flag off (default): pair generated regardless of maxWaitTime.
 *   - Flag on, maxWaitTime_j=200 s (loose, > ~95 s delay): pair still generated.
 *   - Flag on, maxWaitTime_j=80 s  (tight, < ~95 s delay): pair rejected.
 */
class PairGeneratorMaxWaitFilterTest {

    // Link IDs for the 6-node chain
    private static final Id<Link> LINK_01 = Id.createLinkId("link01");
    private static final Id<Link> LINK_12 = Id.createLinkId("link12");
    private static final Id<Link> LINK_23 = Id.createLinkId("link23");
    private static final Id<Link> LINK_34 = Id.createLinkId("link34");
    private static final Id<Link> LINK_45 = Id.createLinkId("link45");

    // Link length 1000 m, freespeed 10 m/s → travel time = 100 s per link.
    private static final double LINK_LEN = 1000.0;
    private static final double FREESPEED = 10.0;

    // Direct travel times (inter-link routing + dest-link traversal)
    // reqI: link01 → link45: n1→n2→n3→n4 (3 × 100 s) + link45 traversal (100 s) = 400 s
    private static final double DIRECT_I = 4 * LINK_LEN / FREESPEED; // 400 s
    // reqJ: link23 → link45: n3→n4 (100 s) + link45 traversal (100 s) = 200 s
    private static final double DIRECT_J = 2 * LINK_LEN / FREESPEED; // 200 s

    private MatsimNetworkCache cache;
    private BudgetValidator validator;

    @BeforeEach
    void setUp() {
        // Build a real 6-node, 5-link network so batchPrecompute can route
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

    /**
     * Builds request i: origin=link01, dest=link45, requestTime=0.
     * Sorts before reqJ (requestTime=10), so oo is link01→link23 (forward direction).
     */
    private DrtRequest buildReqI() {
        return DrtRequest.builder()
                .index(0)
                .personId(Id.createPersonId("pax_i"))
                .groupId("g0")
                .tripIndex(0)
                .budget(5.0)
                .bestModeScore(-5.0)
                .bestMode("car")
                .originLinkId(LINK_01)
                .destinationLinkId(LINK_45)
                .originX(0.0).originY(0.0)
                .destinationX(5.0 * LINK_LEN).destinationY(0.0)
                .requestTime(0.0)
                .earliestDeparture(-600.0)
                .latestArrival(10800.0)
                .directTravelTime(DIRECT_I)
                .directDistance(4 * LINK_LEN)
                .maxDetourFactor(5.0)
                .maxWaitTime(3600.0)  // not under test for reqI
                .build();
    }

    /**
     * Builds request j: origin=link23, dest=link45, requestTime=10.
     *
     * FIFO optimized delay ≈ 95 s (from initialDelayJ=190 s, symmetric shift of −95 s).
     * The tight cap is 80 s (< 95 s) and the loose cap is 200 s (> 95 s).
     *
     * @param maxWaitTime the budget-derived wait cap to test
     */
    private DrtRequest buildReqJ(double maxWaitTime) {
        return DrtRequest.builder()
                .index(1)
                .personId(Id.createPersonId("pax_j"))
                .groupId("g1")
                .tripIndex(0)
                .budget(5.0)
                .bestModeScore(-5.0)
                .bestMode("car")
                .originLinkId(LINK_23)
                .destinationLinkId(LINK_45)
                .originX(2.0 * LINK_LEN).originY(0.0)
                .destinationX(5.0 * LINK_LEN).destinationY(0.0)
                .requestTime(10.0)
                .earliestDeparture(-600.0)
                .latestArrival(10800.0)
                .directTravelTime(DIRECT_J)
                .directDistance(2 * LINK_LEN)
                .maxDetourFactor(5.0)
                .maxWaitTime(maxWaitTime)
                .build();
    }

    /**
     * Flag off (default): pairs are generated regardless of maxWaitTime.
     * Baseline — verifies at least one pair exists before the filter is applied.
     * adjusted_j ≈ 95 s; maxWaitTime=10 is below that but the flag is off, so it must be ignored.
     */
    @Test
    void flagOff_pairGeneratedDespiteDelay() {
        DrtRequest i = buildReqI();
        DrtRequest j = buildReqJ(/* maxWaitTime = tight */ 10.0);
        PairGenerator gen = new PairGenerator(cache, validator, 0.0, 1, /* budgetAwareConstraints= */ false);
        List<Ride> pairs = gen.generatePairs(List.of(i, j));
        assertFalse(pairs.isEmpty(),
                "Flag off: pair should be generated even with tight maxWaitTime. "
                        + "Got " + pairs.size() + " pairs.");
    }

    /**
     * Flag on, loose cap (200 s > expected delay ~95 s): pair still generated.
     */
    @Test
    void flagOn_looseCap_pairStillGenerated() {
        DrtRequest i = buildReqI();
        DrtRequest j = buildReqJ(/* maxWaitTime = loose */ 200.0);
        PairGenerator gen = new PairGenerator(cache, validator, 0.0, 1, /* budgetAwareConstraints= */ true);
        List<Ride> pairs = gen.generatePairs(List.of(i, j));
        assertFalse(pairs.isEmpty(),
                "Flag on, loose cap: pair should still be generated (delay_j ~95 s <= maxWaitTime=200 s). "
                        + "Got " + pairs.size() + " pairs.");
    }

    /**
     * Flag on, tight cap (80 s < expected delay ~95 s): pair rejected.
     * This test FAILS before C6 (filter not yet applied) and PASSES after.
     */
    @Test
    void flagOn_tightCap_pairRejected() {
        DrtRequest i = buildReqI();
        DrtRequest j = buildReqJ(/* maxWaitTime = tight */ 80.0);
        PairGenerator gen = new PairGenerator(cache, validator, 0.0, 1, /* budgetAwareConstraints= */ true);
        List<Ride> pairs = gen.generatePairs(List.of(i, j));
        assertTrue(pairs.isEmpty(),
                "Flag on, tight cap: pair should be rejected (delay_j ~95 s > maxWaitTime=80 s). "
                        + "Got " + pairs.size() + " pairs.");
    }

    // -------------------------------------------------------------------------
    // Passthrough BudgetValidator — always accepts rides
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
