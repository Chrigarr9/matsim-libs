package org.matsim.contrib.demand_extraction.demand;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup.FleetSide;
import org.matsim.contrib.demand_extraction.demand.DrtRequest.HubLegRole;
import org.matsim.contrib.demand_extraction.demand.DrtRequestFactory.LegRouter;
import org.matsim.contrib.demand_extraction.scoring.DemandExtractionScoringAdapter;
import org.matsim.contrib.demand_extraction.scoring.TripScoreRequest;
import org.matsim.contrib.demand_extraction.scoring.TripScoreResult;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD test for Task 2: the connecting branch of
 * {@link DrtRequestFactory#applyVirtualExpansion} must emit the original O→D
 * request retagged {@code "connecting-direct"} alongside the hub-leg copies.
 *
 * <p>RED: before the 3-line addition, no {@code connecting-direct} ride appears
 * in the expanded list.
 * <p>GREEN: after the 3-line addition in {@code applyVirtualExpansion}, all
 * assertions below pass.
 *
 * <p>Setup pattern mirrors {@link DrtRequestFactoryVirtualTripTest} and
 * {@link DrtRequestFactoryFinalizeTest}: in-memory grid network, stub hubs,
 * a {@link DirectEmissionHarness} subclass that overrides
 * {@link DrtRequestFactory#budgetDerivedCaps} to avoid needing a full
 * {@link org.matsim.contrib.demand_extraction.demand.BudgetToConstraintsCalculator}.
 */
public class ConnectingDirectRideEmissionTest {

    // -------------------------------------------------------------------------
    // Core: direct ride emitted alongside hub copies
    // -------------------------------------------------------------------------

    @Test
    void connectingRequest_emitsDirectRideAlongsideHubCopies() {
        DirectEmissionHarness harness = harness();
        List<DrtRequest> result = expand(harness, twoHubs(), fakeRouter(twoHubs(), grid()),
                TestRequestBuilder.connectingFixture(null));

        long directCount = result.stream()
                .filter(r -> "connecting-direct".equals(r.requestTag))
                .count();
        assertEquals(1, directCount,
                "connecting branch must emit exactly one connecting-direct ride");
    }

    // -------------------------------------------------------------------------
    // Field preservation: tag, hubLegRole, hubId, origin, destination, budget
    // -------------------------------------------------------------------------

    @Test
    void connectingDirectRide_hasNoneHubLegRoleAndNullHubId() {
        DirectEmissionHarness harness = harness();
        DrtRequest c = TestRequestBuilder.connectingFixture(null);
        List<DrtRequest> result = expand(harness, twoHubs(), fakeRouter(twoHubs(), grid()), c);

        DrtRequest direct = directRide(result);
        assertEquals(HubLegRole.NONE, direct.hubLegRole,
                "direct ride must have hubLegRole NONE");
        assertNull(direct.hubId, "direct ride must have null hubId");
    }

    @Test
    void connectingDirectRide_preservesOriginalOriginDestinationAndBudget() {
        DirectEmissionHarness harness = harness();
        DrtRequest c = TestRequestBuilder.connectingFixture(null);
        List<DrtRequest> result = expand(harness, twoHubs(), fakeRouter(twoHubs(), grid()), c);

        DrtRequest direct = directRide(result);
        assertEquals(c.originLinkId, direct.originLinkId, "originLinkId");
        assertEquals(c.destinationLinkId, direct.destinationLinkId, "destinationLinkId");
        assertEquals(c.originX, direct.originX, 1e-9, "originX");
        assertEquals(c.destinationX, direct.destinationX, 1e-9, "destinationX");
        assertEquals(c.budget, direct.budget, 1e-12, "budget");
        assertEquals(c.directTravelTime, direct.directTravelTime, 1e-9, "directTravelTime");
        assertEquals(c.latestArrival, direct.latestArrival, 1e-9, "latestArrival");
        assertEquals(c.earliestDeparture, direct.earliestDeparture, 1e-9, "earliestDeparture");
    }

    @Test
    void connectingDirectRide_hasScoringContextFromOriginal() {
        DirectEmissionHarness harness = harness();
        DrtRequest c = TestRequestBuilder.connectingFixture(null);
        // Wire a recognisable person onto the fixture's scoring context.
        Person person = PopulationUtils.getFactory().createPerson(c.personId);
        c.setScoringContext(minimalCtx(person));

        List<DrtRequest> result = expand(harness, twoHubs(), fakeRouter(twoHubs(), grid()), c);

        DrtRequest direct = directRide(result);
        assertNotNull(direct.getScoringContext(),
                "direct ride must carry a scoring context");
        assertSame(person, direct.getScoringContext().person(),
                "direct ride scoring context must reference the original person");
    }

    // -------------------------------------------------------------------------
    // Unconditional emission: direct ride present even when all hub copies drop
    // -------------------------------------------------------------------------

    @Test
    void connectingDirectRide_isEmittedEvenWhenAllHubCopiesDropped() {
        DirectEmissionHarness harness = harness();
        DrtRequest c = TestRequestBuilder.connectingFixture(null);
        // Slow router → temporal infeasibility → all hub copies dropped.
        LegRouter slow = (f, t, dep) -> new double[]{1e7, 1e7};

        List<DrtRequest> result = expand(harness, twoHubs(), slow, c);

        long hubCount = result.stream()
                .filter(r -> r.hubLegRole == HubLegRole.ACCESS_LEG
                        || r.hubLegRole == HubLegRole.CONTINUATION_LEG)
                .count();
        assertEquals(0, hubCount, "slow router must drop all hub copies");

        long directCount = result.stream()
                .filter(r -> "connecting-direct".equals(r.requestTag))
                .count();
        assertEquals(1, directCount,
                "direct ride must be emitted even when all hub copies are dropped");
    }

    // -------------------------------------------------------------------------
    // Count: |H|=2 hub copies + 1 direct = 3 total
    // -------------------------------------------------------------------------

    @Test
    void hubCopiesAndDirectRideCount() {
        Network network = grid();
        List<HubSetLoader.Hub> hubs = twoHubs();
        DirectEmissionHarness harness = harness();
        DrtRequest c = TestRequestBuilder.connectingFixture(null);

        List<DrtRequest> result = expand(harness, hubs, fakeRouter(hubs, network), c);

        long hubCopies = result.stream()
                .filter(r -> r.hubLegRole == HubLegRole.ACCESS_LEG)
                .count();
        assertEquals(hubs.size(), hubCopies, "|H|=2 ACCESS_LEG copies");

        long directCount = result.stream()
                .filter(r -> "connecting-direct".equals(r.requestTag))
                .count();
        assertEquals(1, directCount, "exactly 1 direct ride");

        assertEquals(hubs.size() + 1, result.size(), "total = |H| + 1");
    }

    // -------------------------------------------------------------------------
    // EXT-4: connecting-direct re-cap. When the config differentiates the
    // "connecting-direct" class, the direct copy re-derives its caps under its
    // own tag; without such an entry the copy is field-identical to today.
    // -------------------------------------------------------------------------

    @Test
    void connectingDirectRide_reCappedUnderOwnTag_whenConfigured() {
        // Map differentiates connecting-direct with factor 1.05; the fixture's
        // original maxDetourFactor is 1.5. RecapHarness.budgetDerivedCaps applies
        // resolveClassFactor, so the direct copy re-derives to 1.05.
        RecapHarness harness = recapHarness(java.util.Map.of("connecting-direct", 1.05));
        DrtRequest c = TestRequestBuilder.connectingFixture(null);
        List<DrtRequest> result = expandRecap(harness, twoHubs(), fakeRouter(twoHubs(), grid()), c);

        DrtRequest direct = directRide(result);
        assertEquals(1.05, direct.maxDetourFactor, 1e-9,
                "connecting-direct copy must re-derive maxDetourFactor under its own class factor 1.05");
        assertNotEquals(c.maxDetourFactor, direct.maxDetourFactor, 1e-9,
                "re-capped direct copy must differ from the original connecting request");
    }

    @Test
    void connectingDirectRide_fieldIdenticalWhenNoConnectingDirectEntry() {
        // No connecting-direct entry → recap gate is false → direct copy inherits
        // the original caps unchanged (byte-identical to pre-EXT-4 behaviour).
        RecapHarness harness = recapHarness(java.util.Map.of("connecting", 1.2));
        DrtRequest c = TestRequestBuilder.connectingFixture(null);
        List<DrtRequest> result = expandRecap(harness, twoHubs(), fakeRouter(twoHubs(), grid()), c);

        DrtRequest direct = directRide(result);
        assertEquals(c.maxDetourFactor, direct.maxDetourFactor, 1e-12,
                "without a connecting-direct entry the direct copy's maxDetourFactor is unchanged");
        assertEquals(c.maxWalkDistance, direct.maxWalkDistance, 1e-12, "maxWalkDistance unchanged");
        assertEquals(c.maxWaitTime, direct.maxWaitTime, 1e-12, "maxWaitTime unchanged");
    }

    private List<DrtRequest> expandRecap(
            RecapHarness harness,
            List<HubSetLoader.Hub> hubs,
            LegRouter router,
            DrtRequest r) {
        if (r.getScoringContext() == null) {
            Person person = PopulationUtils.getFactory().createPerson(r.personId);
            r.setScoringContext(minimalCtx(person));
        }
        Predicate<Coord> metro = c -> c.getX() >= 500.0;
        Function<Person, LegRouter> routerFactory = person -> router;
        return harness.applyVirtualExpansion(
                List.of(r), hubs, FleetSide.RURAL, metro,
                /* transferBuffer */ 300.0, /* maxHubWait */ 0.0,
                harness.stubValidator, routerFactory, /* bothSides */ false).requests();
    }

    private static RecapHarness recapHarness(java.util.Map<String, Double> byClass) {
        Network network = grid();
        ExMasConfigGroup cfg = new ExMasConfigGroup();
        cfg.setDrtMode("drt");
        cfg.setMinDrtAccessEgressDistance(100.0);
        cfg.setEnableBudgetAwareConstraints(false);
        cfg.setMaxDetourFactor(1.5);
        cfg.setMaxDetourFactorByClass(byClass);
        DemandExtractionScoringAdapter fakeAdapter = new DemandExtractionScoringAdapter() {
            @Override public TripScoreResult scoreTrip(TripScoreRequest req) { return new TripScoreResult(1.0, "stub"); }
            @Override public String getName() { return "stub"; }
            @Override public double getMarginalUtilityOfMoney(Person p, double d) { return 0.01; }
            @Override public boolean includesOpportunityCost() { return true; }
            @Override public boolean supportsDistanceSpecificMoneyUtility() { return false; }
        };
        BudgetValidator stubValidator = new BudgetValidator(fakeAdapter, cfg, 1.34) {
            @Override public double calculateBudget(DrtRequest r) { return 1.0; }
            @Override public DrtRequest.ScoringContext computeScoringContext(DrtRequest r, Person p) {
                return minimalCtx(p);
            }
        };
        return new RecapHarness(cfg, stubValidator, network);
    }

    /**
     * Harness whose {@link #budgetDerivedCaps} applies the production
     * {@link DrtRequestFactory#resolveClassFactor} lookup against the config map,
     * so the connecting-direct re-cap re-derives a class-specific factor. Budget-
     * derived detour is generous so the config factor always binds.
     */
    static class RecapHarness extends DrtRequestFactory {
        final BudgetValidator stubValidator;
        final ExMasConfigGroup cfg;

        RecapHarness(ExMasConfigGroup config, BudgetValidator validator, Network network) {
            super(config, null, null, null, network, null, validator, null);
            this.stubValidator = validator;
            this.cfg = config;
        }

        @Override
        double[] budgetDerivedCaps(double budget, Person person, DrtRequest draft) {
            double factor = DrtRequestFactory.resolveClassFactor(
                    cfg.getMaxDetourFactorByClass(), cfg.getMaxDetourFactor(),
                    draft.requestTag, draft.hubLegRole);
            return new double[]{draft.directTravelTime * (factor - 1.0), 0.0, 0.0};
        }
    }

    // -------------------------------------------------------------------------
    // Helpers: invoke production applyVirtualExpansion
    // -------------------------------------------------------------------------

    /**
     * Calls the production {@link DrtRequestFactory#applyVirtualExpansion} with
     * a single connecting request, RURAL fleet, and the stub metropole
     * ({@code x >= 500}). A scoring context is wired onto {@code r} if absent.
     * The {@code router} is a person-independent fake, so the routerFactory
     * ignores the person and returns it directly.
     */
    private List<DrtRequest> expand(
            DirectEmissionHarness harness,
            List<HubSetLoader.Hub> hubs,
            LegRouter router,
            DrtRequest r) {

        if (r.getScoringContext() == null) {
            Person person = PopulationUtils.getFactory().createPerson(r.personId);
            r.setScoringContext(minimalCtx(person));
        }

        Predicate<Coord> metro = c -> c.getX() >= 500.0;
        List<DrtRequest> input = List.of(r);
        Function<Person, LegRouter> routerFactory = person -> router;

        return harness.applyVirtualExpansion(
                input, hubs, FleetSide.RURAL, metro,
                /* transferBuffer */ 300.0,
                /* maxHubWait */ 0.0,
                harness.stubValidator,
                routerFactory,
                /* bothSides */ false).requests();
    }

    // -------------------------------------------------------------------------
    // Helpers: network, hubs, router
    // -------------------------------------------------------------------------

    private static Network grid() {
        Network net = NetworkUtils.createNetwork();
        NetworkFactory nf = net.getFactory();
        Node nw = nf.createNode(Id.createNodeId("nw"), new Coord(-1_000.0, 11_000.0));
        Node ne = nf.createNode(Id.createNodeId("ne"), new Coord(11_000.0, 11_000.0));
        Node sw = nf.createNode(Id.createNodeId("sw"), new Coord(-1_000.0, -1_000.0));
        Node se = nf.createNode(Id.createNodeId("se"), new Coord(11_000.0, -1_000.0));
        net.addNode(nw); net.addNode(ne); net.addNode(sw); net.addNode(se);
        net.addLink(nf.createLink(Id.createLinkId("n"), nw, ne));
        net.addLink(nf.createLink(Id.createLinkId("e"), ne, se));
        net.addLink(nf.createLink(Id.createLinkId("s"), se, sw));
        net.addLink(nf.createLink(Id.createLinkId("w"), sw, nw));
        // Force QuadTree build so SearchableNetwork is ready for getNearestLink calls.
        assertNotNull(NetworkUtils.getNearestLink(net, new Coord(0.0, 0.0)));
        return net;
    }

    private static List<HubSetLoader.Hub> twoHubs() {
        return List.of(
                new HubSetLoader.Hub("hub_a", new Coord(5_000.0, 8_000.0)),
                new HubSetLoader.Hub("hub_b", new Coord(7_500.0, 5_000.0))
        );
    }

    /** Returns {600 s / 6000 m} for legs routed TO a hub link, {900 s / 9000 m} otherwise. */
    private static LegRouter fakeRouter(List<HubSetLoader.Hub> hubs, Network network) {
        java.util.Set<Id<org.matsim.api.core.v01.network.Link>> hubLinks = new java.util.HashSet<>();
        for (HubSetLoader.Hub h : hubs) {
            hubLinks.add(NetworkUtils.getNearestLink(network, h.coord()).getId());
        }
        return (from, to, dep) -> hubLinks.contains(to)
                ? new double[]{600.0, 6000.0}
                : new double[]{900.0, 9000.0};
    }

    private static DrtRequest.ScoringContext minimalCtx(Person person) {
        return new DrtRequest.ScoringContext(
                person,
                PopulationUtils.createActivityFromLinkId("home", Id.createLinkId("l_o")),
                PopulationUtils.createActivityFromLinkId("work", Id.createLinkId("l_d")),
                0.0, 0.0, null, null, null, null, null, null, null, null);
    }

    private static DrtRequest directRide(List<DrtRequest> result) {
        return result.stream()
                .filter(r -> "connecting-direct".equals(r.requestTag))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No connecting-direct ride found in expanded list (size=" + result.size() + ")"));
    }

    // -------------------------------------------------------------------------
    // Harness: exposes applyVirtualExpansion for testing
    // -------------------------------------------------------------------------

    private static DirectEmissionHarness harness() {
        return harness(grid());
    }

    private static DirectEmissionHarness harness(Network network) {
        ExMasConfigGroup cfg = new ExMasConfigGroup();
        cfg.setDrtMode("drt");
        cfg.setMinDrtAccessEgressDistance(100.0);
        cfg.setEnableBudgetAwareConstraints(false);
        DemandExtractionScoringAdapter fakeAdapter = new DemandExtractionScoringAdapter() {
            @Override public TripScoreResult scoreTrip(TripScoreRequest req) { return new TripScoreResult(1.0, "stub"); }
            @Override public String getName() { return "stub"; }
            @Override public double getMarginalUtilityOfMoney(Person p, double d) { return 0.01; }
            @Override public boolean includesOpportunityCost() { return true; }
            @Override public boolean supportsDistanceSpecificMoneyUtility() { return false; }
        };
        BudgetValidator stubValidator = new BudgetValidator(fakeAdapter, cfg, 1.34) {
            @Override public double calculateBudget(DrtRequest r) { return 1.0; }
            @Override public DrtRequest.ScoringContext computeScoringContext(DrtRequest r, Person p) {
                return minimalCtx(p);
            }
        };
        return new DirectEmissionHarness(cfg, stubValidator, network);
    }

    /**
     * Minimal DrtRequestFactory subclass that:
     * - Overrides {@link #budgetDerivedCaps} to trivial fixed values.
     * - Exposes {@link #applyVirtualExpansion} for direct testing.
     * - Carries its own {@link #stubValidator} so the test can pass it through.
     * - Takes the test {@link Network} so {@code expandConnecting}'s
     *   nearest-link lookup works without a live MATSim SearchableNetwork.
     */
    static class DirectEmissionHarness extends DrtRequestFactory {
        final BudgetValidator stubValidator;

        DirectEmissionHarness(ExMasConfigGroup config, BudgetValidator validator,
                Network network) {
            super(config, null, null, null, network, null, validator, null);
            this.stubValidator = validator;
        }

        @Override
        double[] budgetDerivedCaps(double budget, Person person, DrtRequest draft) {
            return new double[]{100.0, 0.0, 0.0};
        }
    }
}
