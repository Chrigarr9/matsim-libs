package org.matsim.contrib.demand_extraction.io.lowmem;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Route;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;
import org.matsim.contrib.drt.routing.DrtRoute;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ScenarioConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup.ActivityParams;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scoring.functions.ScoringParameters;

/** Builders for unit tests of the low-memory mode that don't want a full MATSim controler. */
final class LowMemTestFixtures {

	private LowMemTestFixtures() {}

	/**
	 * Build a {@link DrtRequest} with index {@code idx} and a populated
	 * {@link DrtRequest.ScoringContext} carrying two activities of the supplied types
	 * and typical durations.
	 */
	static DrtRequest buildRequest(int idx, String originActivityType, String destActivityType,
			double originTypicalDuration, double destTypicalDuration) {
		return buildRequest(idx, originActivityType, destActivityType,
				originTypicalDuration, destTypicalDuration, null, null);
	}

	/**
	 * Variant that also stamps the Extension-2 {@code requestTag} and {@code hubId}
	 * onto the request. Either / both can be {@code null}.
	 */
	static DrtRequest buildRequest(int idx, String originActivityType, String destActivityType,
			double originTypicalDuration, double destTypicalDuration,
			String requestTag, String hubId) {
		return buildRequest(idx, originActivityType, destActivityType,
				originTypicalDuration, destTypicalDuration, requestTag, hubId,
				DrtRequest.HubLegRole.NONE, 0.0, 0.0);
	}

	/**
	 * Variant that additionally stamps the Paper-2 hub-leg fields ({@code hubLegRole},
	 * {@code transferWaitSeconds}, {@code marginalUtilityOfMoney}) onto the request.
	 */
	static DrtRequest buildRequest(int idx, String originActivityType, String destActivityType,
			double originTypicalDuration, double destTypicalDuration,
			String requestTag, String hubId,
			DrtRequest.HubLegRole hubLegRole, double transferWaitSeconds, double marginalUtilityOfMoney) {
		Id<Link> from = Id.createLinkId("L" + (idx * 2));
		Id<Link> to = Id.createLinkId("L" + (idx * 2 + 1));

		DrtRequest req = DrtRequest.builder()
				.index(idx)
				.personId(Id.createPersonId("p" + idx))
				.groupId("g" + idx)
				.tripIndex(0)
				.budget(10.0)
				.bestModeScore(-5.0)
				.bestMode("car")
				.requestTag(requestTag)
				.hubId(hubId)
				.hubLegRole(hubLegRole)
				.transferWaitSeconds(transferWaitSeconds)
				.marginalUtilityOfMoney(marginalUtilityOfMoney)
				.originLinkId(from)
				.destinationLinkId(to)
				.originX(0.0).originY(0.0)
				.destinationX(1000.0).destinationY(0.0)
				.originLinkCoordFromX(0.0).originLinkCoordFromY(0.0)
				.originLinkCoordToX(10.0).originLinkCoordToY(0.0)
				.destinationLinkCoordFromX(990.0).destinationLinkCoordFromY(0.0)
				.destinationLinkCoordToX(1000.0).destinationLinkCoordToY(0.0)
				.requestTime(28800.0)
				.earliestDeparture(28800.0)
				.latestArrival(30000.0)
				.directTravelTime(600.0)
				.directDistance(1000.0)
				.maxDetourFactor(1.5)
				.originActivityType(originActivityType)
				.destinationActivityType(destActivityType)
				.carTravelTime(600.0)
				.ptTravelTime(900.0)
				.ptAccessibility(1.5)
				.build();

		req.setScoringContext(buildContext(req, originActivityType, destActivityType,
				originTypicalDuration, destTypicalDuration));
		return req;
	}

	private static DrtRequest.ScoringContext buildContext(DrtRequest req, String originType, String destType,
			double originTypicalDuration, double destTypicalDuration) {
		Activity originActivity = PopulationUtils.createActivityFromLinkId(originType, req.originLinkId);
		Activity destActivity = PopulationUtils.createActivityFromLinkId(destType, req.destinationLinkId);

		ScoringParameters scoringParams = buildScoringParams(originType, destType,
				originTypicalDuration, destTypicalDuration);

		Leg accessLeg = PopulationUtils.createLeg(TransportMode.walk);
		accessLeg.setTravelTime(71.4);
		Route accessRoute = RouteUtils.createGenericRouteImpl(req.originLinkId, req.originLinkId);
		accessRoute.setDistance(100.0);
		accessRoute.setTravelTime(71.4);
		accessLeg.setRoute(accessRoute);

		Leg egressLeg = PopulationUtils.createLeg(TransportMode.walk);
		egressLeg.setTravelTime(71.4);
		Route egressRoute = RouteUtils.createGenericRouteImpl(req.destinationLinkId, req.destinationLinkId);
		egressRoute.setDistance(100.0);
		egressRoute.setTravelTime(71.4);
		egressLeg.setRoute(egressRoute);

		DrtRoute drtRouteTemplate = new DrtRoute(req.originLinkId, req.destinationLinkId);
		drtRouteTemplate.setDirectRideTime(req.directTravelTime);
		drtRouteTemplate.setDistance(req.directDistance);

		Activity synOrigAct = PopulationUtils.createActivityFromLinkId("drt_interaction", req.originLinkId);
		synOrigAct.setCoord(new Coord(req.originX, req.originY));
		synOrigAct.setEndTime(req.requestTime);
		Activity synDestAct = PopulationUtils.createActivityFromLinkId("drt_interaction", req.destinationLinkId);
		synDestAct.setCoord(new Coord(req.destinationX, req.destinationY));

		// person is intentionally null: Phase 2 invariant. Tests don't exercise scoreViaEstimator.
		Person person = null;

		return new DrtRequest.ScoringContext(
				person, originActivity, destActivity, 7200.0, 3600.0,
				scoringParams, accessLeg, accessRoute, egressLeg, egressRoute,
				drtRouteTemplate, synOrigAct, synDestAct);
	}

	private static ScoringParameters buildScoringParams(String originType, String destType,
			double originTypicalDuration, double destTypicalDuration) {
		ScoringConfigGroup scoring = new ScoringConfigGroup();

		ActivityParams originParams = new ActivityParams(originType);
		originParams.setTypicalDuration(originTypicalDuration);
		originParams.setScoringThisActivityAtAll(true);
		scoring.addActivityParams(originParams);

		ActivityParams destParams = new ActivityParams(destType);
		destParams.setTypicalDuration(destTypicalDuration);
		destParams.setScoringThisActivityAtAll(true);
		scoring.addActivityParams(destParams);

		scoring.setMarginalUtilityOfMoney(1.0);
		scoring.setPerforming_utils_hr(6.0);
		scoring.setMarginalUtlOfWaitingPt_utils_hr(-3.0);

		ScenarioConfigGroup scenarioCfg = ConfigUtils.createConfig().scenario();
		return new ScoringParameters.Builder(scoring, scoring.getScoringParameters(null), scenarioCfg).build();
	}
}
