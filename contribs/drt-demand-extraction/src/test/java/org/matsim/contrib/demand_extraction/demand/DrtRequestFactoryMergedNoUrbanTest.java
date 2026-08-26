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
 * Task 6 (2026-08-25 plan, revised 2026-08-26): drop urban_intra from the merged
 * extraction by reusing the existing off-fleet tag-drop ({@code fleetSide=RURAL})
 * together with both-sides connecting expansion ({@code expandConnectingBothSides=true}).
 *
 * <p>Two mechanisms are exercised, both already unit-testable without a Controler:
 * <ol>
 *   <li>{@link DrtRequestFactory#isOffFleetTag} — the pre-construction drop applied in
 *       {@code buildRequests} BEFORE routing/building. With {@code fleetSide=RURAL} it
 *       must drop {@code urban_intra} and pass through {@code rural_intra},
 *       {@code connecting}, {@code connecting-direct} and {@code null} unchanged. This
 *       gate is untouched by Task 6 — the fix is only in letting {@code fleetSide} be
 *       non-null alongside {@code bothSides=true}.</li>
 *   <li>{@link DrtRequestFactory#applyVirtualExpansion} — both-sides connecting
 *       expansion. With {@code fleetSide=RURAL} AND {@code bothSides=true} it must
 *       still emit BOTH {@link HubLegRole#ACCESS_LEG} and
 *       {@link HubLegRole#CONTINUATION_LEG} copies per hub, plus exactly one
 *       {@code connecting-direct} ride — proving expansion keys on the
 *       {@code bothSides} flag ALONE, not on {@code fleetSide == null}. The
 *       {@code fleetSide=null} case (today's merged behavior, already covered by
 *       {@link ConnectingBothSidesExpansionTest}) must be identical in shape.</li>
 * </ol>
 *
 * <p>Chain-group note (plan Task 6): the off-fleet pre-drop removes a person's
 * urban_intra TRIPS before {@code buildRequest} is ever called for them — it never
 * touches {@link ChainIdentifier}'s group-id map, so a kept trip's groupId is computed
 * exactly as it always has been in the production RURAL run (see
 * {@link DrtRequestFactorySpontaneousChainTest} / {@link DrtRequestFactory#resolveGroupId}
 * for that independent contract). Dropping urban_intra while keeping a connecting trip
 * therefore just shortens the request set for that group id; it never changes what the
 * group id itself would have been.
 */
class DrtRequestFactoryMergedNoUrbanTest {

    // -------------------------------------------------------------------------
    // isOffFleetTag: the pre-construction gate reused, unmodified, by Task 6
    // -------------------------------------------------------------------------

    @Test
    void ruralFleetSideDropsUrbanIntraOnly() {
        assertTrue(DrtRequestFactory.isOffFleetTag("urban_intra", FleetSide.RURAL),
                "fleetSide=RURAL must drop urban_intra");
        assertFalse(DrtRequestFactory.isOffFleetTag("rural_intra", FleetSide.RURAL),
                "fleetSide=RURAL must keep rural_intra");
        assertFalse(DrtRequestFactory.isOffFleetTag("connecting", FleetSide.RURAL),
                "fleetSide=RURAL must keep connecting (expanded afterwards, not dropped here)");
        assertFalse(DrtRequestFactory.isOffFleetTag("connecting-direct", FleetSide.RURAL),
                "fleetSide=RURAL must keep connecting-direct");
        assertFalse(DrtRequestFactory.isOffFleetTag(null, FleetSide.RURAL),
                "fleetSide=RURAL must keep untagged (null) requests");
    }

    @Test
    void nullFleetSideDropsNothing_todaysMergedBehavior() {
        for (String tag : new String[]{"urban_intra", "rural_intra", "connecting",
                "connecting-direct", null}) {
            assertFalse(DrtRequestFactory.isOffFleetTag(tag, null),
                    "fleetSide=null must drop nothing (tag=" + tag + ")");
        }
    }

    // -------------------------------------------------------------------------
    // Both-sides expansion with fleetSide=RURAL: unaffected by the fleet-side drop
    // -------------------------------------------------------------------------

    @Test
    void bothSidesWithRuralFleetSide_stillEmitsAccessAndContinuationPerHub_plusOneDirect() {
        MergedHarness harness = harness();
        List<HubSetLoader.Hub> hubs = twoHubs();
        DrtRequest c = TestRequestBuilder.connectingFixture(null);

        List<DrtRequest> result = expand(harness, hubs, FleetSide.RURAL, /* bothSides */ true,
                fakeRouter(hubs, grid()), c);

        long accessCount = result.stream().filter(r -> r.hubLegRole == HubLegRole.ACCESS_LEG).count();
        long continuationCount = result.stream().filter(r -> r.hubLegRole == HubLegRole.CONTINUATION_LEG).count();
        assertEquals(hubs.size(), accessCount, "one ACCESS_LEG per hub, even with fleetSide=RURAL");
        assertEquals(hubs.size(), continuationCount, "one CONTINUATION_LEG per hub, even with fleetSide=RURAL");

        long directCount = result.stream().filter(r -> "connecting-direct".equals(r.requestTag)).count();
        assertEquals(1, directCount, "exactly ONE connecting-direct ride");
        assertEquals(2 * hubs.size() + 1, result.size(), "total = 2|H| + 1");
    }

    @Test
    void bothSidesShapeIsIdenticalRegardlessOfFleetSide() {
        // Proves the "keys on bothSides alone" requirement directly: fleetSide=RURAL
        // and fleetSide=null must produce the SAME role/count shape under bothSides=true.
        MergedHarness harness = harness();
        List<HubSetLoader.Hub> hubs = twoHubs();
        LegRouter router = fakeRouter(hubs, grid());

        List<DrtRequest> withRural = expand(harness, hubs, FleetSide.RURAL, true, router,
                TestRequestBuilder.connectingFixture(null));
        List<DrtRequest> withNull = expand(harness, hubs, null, true, router,
                TestRequestBuilder.connectingFixture(null));

        assertEquals(withNull.size(), withRural.size(), "same total request count");
        assertEquals(
                withNull.stream().filter(r -> r.hubLegRole == HubLegRole.ACCESS_LEG).count(),
                withRural.stream().filter(r -> r.hubLegRole == HubLegRole.ACCESS_LEG).count(),
                "same ACCESS_LEG count");
        assertEquals(
                withNull.stream().filter(r -> r.hubLegRole == HubLegRole.CONTINUATION_LEG).count(),
                withRural.stream().filter(r -> r.hubLegRole == HubLegRole.CONTINUATION_LEG).count(),
                "same CONTINUATION_LEG count");
    }

    // -------------------------------------------------------------------------
    // Helpers: invoke production applyVirtualExpansion (pattern copied from
    // ConnectingBothSidesExpansionTest)
    // -------------------------------------------------------------------------

    private List<DrtRequest> expand(MergedHarness harness, List<HubSetLoader.Hub> hubs,
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
                /* maxHubWait */ 0.0,
                harness.stubValidator,
                routerFactory,
                bothSides).requests();
    }

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

    private static MergedHarness harness() {
        return harness(grid());
    }

    private static MergedHarness harness(Network network) {
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
        return new MergedHarness(cfg, stubValidator, network);
    }

    static class MergedHarness extends DrtRequestFactory {
        final BudgetValidator stubValidator;

        MergedHarness(ExMasConfigGroup config, BudgetValidator validator, Network network) {
            super(config, null, null, null, network, null, validator, null);
            this.stubValidator = validator;
        }

        @Override
        double[] budgetDerivedCaps(double budget, Person person, DrtRequest draft) {
            return new double[]{100.0, 0.0, 0.0};
        }
    }
}
