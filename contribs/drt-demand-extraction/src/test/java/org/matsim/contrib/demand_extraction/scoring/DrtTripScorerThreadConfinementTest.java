package org.matsim.contrib.demand_extraction.scoring;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Route;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;
import org.matsim.contrib.drt.routing.DrtRoute;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scoring.functions.ScoringParameters;

/**
 * EXT-5 regression: {@link DrtTripScorer#scoreWithContext} must be thread-confined —
 * it must NEVER write into the request's shared {@link DrtRequest.ScoringContext}
 * template legs/routes. The parallel pairgen top-K phase scores the same partner
 * request from many threads at once; mutating the shared templates made the kept
 * pair set schedule-dependent (non-deterministic).
 */
class DrtTripScorerThreadConfinementTest {

	@Test
	void scoreWithContext_doesNotMutateSharedContextTemplates() {
		DrtRequest request = buildRequest();
		DrtRequest.ScoringContext ctx = buildContext(request);

		double distBefore = ctx.accessWalkRoute().getDistance();
		double ttBefore = ctx.accessWalkLeg().getTravelTime().seconds();

		DemandExtractionScoringAdapter fakeAdapter = new DemandExtractionScoringAdapter() {
			@Override
			public String getName() {
				return "fake";
			}

			@Override
			public TripScoreResult scoreTrip(TripScoreRequest request) {
				return new TripScoreResult(0.0, true, "fake");
			}

			@Override
			public double getMarginalUtilityOfMoney(Person person, double euclideanDistance_km) {
				return 1.0;
			}

			@Override
			public boolean includesOpportunityCost() {
				return true;
			}

			@Override
			public boolean supportsDistanceSpecificMoneyUtility() {
				return false;
			}
		};

		DrtTripScorer.scoreWithContext(ctx, request, fakeAdapter, "drt",
				ExMasConfigGroup.OpportunityCostModel.NONE,
				600.0, 5000.0, /*accessWalkDist*/ 999.0, /*egressWalkDist*/ 999.0,
				0.0, 1.2);

		assertEquals(distBefore, ctx.accessWalkRoute().getDistance(), 1e-9,
				"scoring must not write into the shared context (EXT-5 race)");
		assertEquals(ttBefore, ctx.accessWalkLeg().getTravelTime().seconds(), 1e-9,
				"scoring must not write into the shared context (EXT-5 race)");
	}

	/**
	 * Inline copy of {@code TestRequestBuilder.baseBuilder} (that class is
	 * package-private to the {@code demand} package). Tag {@code "connecting"},
	 * default {@code HubLegRole.NONE} so the access walk is not zeroed.
	 */
	private static DrtRequest buildRequest() {
		return DrtRequest.builder()
				.index(1)
				.personId(Id.createPersonId("p_connecting"))
				.groupId("p_connecting_g0")
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
				.ptAccessibility(1.5)
				.requestTag("connecting")
				.build();
	}

	/**
	 * Mirrors {@code BudgetValidator.computeScoringContext} (template-object
	 * construction) with a synthetic person and default scoring parameters.
	 * The baseline access-walk distance is 100.0 m so the RED state (overwrite
	 * to 999.0) is detectable.
	 */
	private static DrtRequest.ScoringContext buildContext(DrtRequest request) {
		Config config = ConfigUtils.createConfig();
		ScoringConfigGroup scoring = config.scoring();
		ScoringParameters scoringParams = new ScoringParameters.Builder(
				scoring, scoring.getScoringParameters(null), config.scenario()).build();

		Person person = PopulationUtils.getFactory().createPerson(Id.createPersonId("p"));

		double walkDist = 100.0;
		double accessTime = walkDist / 1.2;
		double egressTime = walkDist / 1.2;

		Activity originActivity = PopulationUtils.createActivityFromLinkId("unknown", request.originLinkId);
		Activity destActivity = PopulationUtils.createActivityFromLinkId("unknown", request.destinationLinkId);

		Leg accessLeg = PopulationUtils.createLeg(TransportMode.walk);
		accessLeg.setTravelTime(accessTime);
		Route accessRoute = RouteUtils.createGenericRouteImpl(request.originLinkId, request.originLinkId);
		accessRoute.setDistance(walkDist);
		accessRoute.setTravelTime(accessTime);
		accessLeg.setRoute(accessRoute);

		Leg egressLeg = PopulationUtils.createLeg(TransportMode.walk);
		egressLeg.setTravelTime(egressTime);
		Route egressRoute = RouteUtils.createGenericRouteImpl(request.destinationLinkId, request.destinationLinkId);
		egressRoute.setDistance(walkDist);
		egressRoute.setTravelTime(egressTime);
		egressLeg.setRoute(egressRoute);

		DrtRoute drtRouteTemplate = new DrtRoute(request.originLinkId, request.destinationLinkId);
		drtRouteTemplate.setDirectRideTime(request.directTravelTime);
		drtRouteTemplate.setDistance(request.directDistance);

		Activity synOrigAct = PopulationUtils.createActivityFromLinkId("drt_interaction", request.originLinkId);
		synOrigAct.setCoord(new Coord(request.originX, request.originY));
		synOrigAct.setEndTime(request.requestTime);
		Activity synDestAct = PopulationUtils.createActivityFromLinkId("drt_interaction", request.destinationLinkId);
		synDestAct.setCoord(new Coord(request.destinationX, request.destinationY));

		return new DrtRequest.ScoringContext(
				person, originActivity, destActivity, 0.0, 0.0,
				scoringParams, accessLeg, accessRoute, egressLeg, egressRoute,
				drtRouteTemplate, synOrigAct, synDestAct);
	}
}
