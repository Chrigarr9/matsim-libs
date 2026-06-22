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
 * TDD test for Task 6: both-sides connecting expansion.
 *
 * <p>When {@code applyVirtualExpansion} is called with {@code bothSides=true},
 * a single {@code "connecting"} request must fan out to, per hub, BOTH an
 * {@link HubLegRole#ACCESS_LEG} copy (RURAL side, O->hub) AND a
 * {@link HubLegRole#CONTINUATION_LEG} copy (URBAN side, hub->D), PLUS exactly
 * ONE {@code "connecting-direct"} ride ({@link HubLegRole#NONE}) — emitted once
 * per request, NOT once per side.
 *
 * <p>Backward-compat: with {@code bothSides=false} and {@code fleetSide=RURAL},
 * only ACCESS_LEG copies are emitted (today's single-side behavior).
 *
 * <p>Setup mirrors {@link ConnectingDirectRideEmissionTest}: in-memory grid
 * network, two stub hubs, a {@link BothSidesHarness} subclass overriding
 * {@link DrtRequestFactory#budgetDerivedCaps}, and an injected synthetic
 * {@link LegRouter} that routes every leg so neither side drops for routing or
 * temporal reasons (the 3600 s fixture envelope fits both the ACCESS deadline
 * back-out and the CONTINUATION departure shift).
 */
public class ConnectingBothSidesExpansionTest {

    // -------------------------------------------------------------------------
    // Both-sides: per hub one ACCESS_LEG + one CONTINUATION_LEG, + 1 direct
    // -------------------------------------------------------------------------

    @Test
    void bothSides_emitsAccessAndContinuationPerHub_plusOneDirect() {
        BothSidesHarness harness = harness();
        List<HubSetLoader.Hub> hubs = twoHubs();
        DrtRequest c = TestRequestBuilder.connectingFixture(null);

        List<DrtRequest> result = expandBoth(harness, hubs, fakeRouter(hubs, grid()), c);

        long accessCount = result.stream()
                .filter(r -> r.hubLegRole == HubLegRole.ACCESS_LEG)
                .count();
        long continuationCount = result.stream()
                .filter(r -> r.hubLegRole == HubLegRole.CONTINUATION_LEG)
                .count();
        assertEquals(hubs.size(), accessCount, "one ACCESS_LEG per hub");
        assertEquals(hubs.size(), continuationCount, "one CONTINUATION_LEG per hub");

        long directCount = result.stream()
                .filter(r -> "connecting-direct".equals(r.requestTag))
                .count();
        assertEquals(1, directCount,
                "exactly ONE connecting-direct ride per request (not one per side)");

        // total = |H| access + |H| continuation + 1 direct
        assertEquals(2 * hubs.size() + 1, result.size(), "total = 2|H| + 1");
    }

    @Test
    void bothSides_directRideHasNoneRoleAndNullHubId() {
        BothSidesHarness harness = harness();
        DrtRequest c = TestRequestBuilder.connectingFixture(null);
        List<DrtRequest> result = expandBoth(harness, twoHubs(), fakeRouter(twoHubs(), grid()), c);

        DrtRequest direct = result.stream()
                .filter(r -> "connecting-direct".equals(r.requestTag))
                .findFirst().orElseThrow();
        assertEquals(HubLegRole.NONE, direct.hubLegRole, "direct ride role NONE");
        assertNull(direct.hubId, "direct ride hubId null");
    }

    @Test
    void bothSides_eachHubHasBothLegSides() {
        BothSidesHarness harness = harness();
        List<HubSetLoader.Hub> hubs = twoHubs();
        DrtRequest c = TestRequestBuilder.connectingFixture(null);

        List<DrtRequest> result = expandBoth(harness, hubs, fakeRouter(hubs, grid()), c);

        for (HubSetLoader.Hub hub : hubs) {
            long access = result.stream()
                    .filter(r -> r.hubLegRole == HubLegRole.ACCESS_LEG && hub.id().equals(r.hubId))
                    .count();
            long cont = result.stream()
                    .filter(r -> r.hubLegRole == HubLegRole.CONTINUATION_LEG && hub.id().equals(r.hubId))
                    .count();
            assertEquals(1, access, "hub " + hub.id() + " has one ACCESS_LEG");
            assertEquals(1, cont, "hub " + hub.id() + " has one CONTINUATION_LEG");
        }
    }

    // -------------------------------------------------------------------------
    // Backward-compat: single-side RURAL emits only ACCESS_LEG copies
    // -------------------------------------------------------------------------

    @Test
    void singleSideRural_emitsOnlyAccessLegs() {
        BothSidesHarness harness = harness();
        List<HubSetLoader.Hub> hubs = twoHubs();
        DrtRequest c = TestRequestBuilder.connectingFixture(null);

        List<DrtRequest> result = expandSingle(harness, hubs, FleetSide.RURAL,
                fakeRouter(hubs, grid()), c);

        long accessCount = result.stream()
                .filter(r -> r.hubLegRole == HubLegRole.ACCESS_LEG).count();
        long continuationCount = result.stream()
                .filter(r -> r.hubLegRole == HubLegRole.CONTINUATION_LEG).count();
        assertEquals(hubs.size(), accessCount, "single-side RURAL: |H| ACCESS_LEG");
        assertEquals(0, continuationCount, "single-side RURAL: no CONTINUATION_LEG");

        long directCount = result.stream()
                .filter(r -> "connecting-direct".equals(r.requestTag)).count();
        assertEquals(1, directCount, "single-side: exactly one direct ride");
        assertEquals(hubs.size() + 1, result.size(), "single-side total = |H| + 1");
    }

    // -------------------------------------------------------------------------
    // Helpers: invoke production applyVirtualExpansion
    // -------------------------------------------------------------------------

    private List<DrtRequest> expandBoth(BothSidesHarness harness, List<HubSetLoader.Hub> hubs,
            LegRouter router, DrtRequest r) {
        return invoke(harness, hubs, /*fleetSide*/ null, /*bothSides*/ true, router, r);
    }

    private List<DrtRequest> expandSingle(BothSidesHarness harness, List<HubSetLoader.Hub> hubs,
            FleetSide fleetSide, LegRouter router, DrtRequest r) {
        return invoke(harness, hubs, fleetSide, /*bothSides*/ false, router, r);
    }

    private List<DrtRequest> invoke(BothSidesHarness harness, List<HubSetLoader.Hub> hubs,
            FleetSide fleetSide, boolean bothSides, LegRouter router, DrtRequest r) {
        if (r.getScoringContext() == null) {
            Person person = PopulationUtils.getFactory().createPerson(r.personId);
            r.setScoringContext(minimalCtx(person));
        }
        Predicate<Coord> metro = c -> c.getX() >= 500.0;
        List<DrtRequest> input = List.of(r);
        Function<Person, LegRouter> routerFactory = person -> router;

        return harness.applyVirtualExpansion(
                input, hubs, fleetSide, metro,
                /* transferBuffer */ 300.0,
                harness.stubValidator,
                routerFactory,
                bothSides).requests();
    }

    // -------------------------------------------------------------------------
    // Helpers: network, hubs, router (copied from ConnectingDirectRideEmissionTest)
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

    // -------------------------------------------------------------------------
    // Harness
    // -------------------------------------------------------------------------

    private static BothSidesHarness harness() {
        return harness(grid());
    }

    private static BothSidesHarness harness(Network network) {
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
        return new BothSidesHarness(cfg, stubValidator, network);
    }

    static class BothSidesHarness extends DrtRequestFactory {
        final BudgetValidator stubValidator;

        BothSidesHarness(ExMasConfigGroup config, BudgetValidator validator, Network network) {
            super(config, null, null, null, network, null, validator, null);
            this.stubValidator = validator;
        }

        @Override
        double[] budgetDerivedCaps(double budget, Person person, DrtRequest draft) {
            return new double[]{100.0, 0.0, 0.0};
        }
    }
}
