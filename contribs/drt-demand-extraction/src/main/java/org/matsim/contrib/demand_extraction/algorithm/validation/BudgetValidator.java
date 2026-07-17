package org.matsim.contrib.demand_extraction.algorithm.validation;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Route;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;
import org.matsim.contrib.demand_extraction.scoring.DemandExtractionScoringAdapter;
import org.matsim.contrib.demand_extraction.scoring.DrtTripScorer;
import org.matsim.contrib.demand_extraction.scoring.OpportunityCostCalculator;
import org.matsim.contrib.drt.routing.DrtRoute;
import org.matsim.core.config.Config;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scoring.functions.ScoringParameters;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Validates ride feasibility against budget constraints using the scoring adapter.
 *
 * <p>Every {@link DrtRequest} must carry a {@link DrtRequest.ScoringContext} populated
 * by {@link #computeScoringContext} (called from
 * {@link org.matsim.contrib.demand_extraction.demand.DrtRequestFactory} at request
 * construction). Scoring goes through {@link DrtTripScorer#scoreWithContext} which
 * reuses the context's pre-built trip elements.
 */
@Singleton
public class BudgetValidator {
	private static final Logger log = LogManager.getLogger(BudgetValidator.class);

	private final DemandExtractionScoringAdapter adapter;
	private final ScoringParametersForPerson scoringParametersForPerson;
	private final ExMasConfigGroup exMasConfig;
	private final double walkSpeed;

	@Inject
	public BudgetValidator(
			DemandExtractionScoringAdapter adapter,
			ScoringParametersForPerson scoringParametersForPerson,
			ExMasConfigGroup exMasConfig,
			Config config) {
		this(adapter, scoringParametersForPerson, exMasConfig, ExMasConfigGroup.getWalkSpeed(config));
	}

	/**
	 * Phase-2 ctor: no {@link ScoringParametersForPerson}. {@link #computeScoringContext}
	 * is not callable on instances built this way — Phase 2 reloads pre-computed scoring
	 * contexts from disk and never builds new ones.
	 */
	public BudgetValidator(
			DemandExtractionScoringAdapter adapter,
			ExMasConfigGroup exMasConfig,
			double walkSpeed) {
		this(adapter, null, exMasConfig, walkSpeed);
	}

	private BudgetValidator(
			DemandExtractionScoringAdapter adapter,
			ScoringParametersForPerson scoringParametersForPerson,
			ExMasConfigGroup exMasConfig,
			double walkSpeed) {
		this.adapter = adapter;
		this.scoringParametersForPerson = scoringParametersForPerson;
		this.exMasConfig = exMasConfig;
		this.walkSpeed = walkSpeed;
	}

	/**
	 * Validate a single ride. Returns the ride with populated budgets, or null if any
	 * passenger's budget is negative.
	 */
	public Ride validateAndPopulateBudgets(Ride ride) {
		double[] remainingBudgets = calculateRemainingBudgets(ride);
		for (double budget : remainingBudgets) {
			if (budget < 0) {
				return null;
			}
		}
		return ride.toBuilder().remainingBudgets(remainingBudgets).build();
	}

	/** Calculate remaining budgets for all passengers in a ride. */
	public double[] calculateRemainingBudgets(Ride ride) {
		DrtRequest[] requests = ride.getRequests();
		double[] delays = ride.getDelays();
		double[] travelTimes = ride.getPassengerTravelTimes();
		double[] distances = ride.getPassengerDistances();
		double[] remainingBudgets = new double[ride.getDegree()];

		double minWalk = exMasConfig.getMinDrtAccessEgressDistance();
		for (int i = 0; i < ride.getDegree(); i++) {
			DrtRequest request = requests[i];
			// CONTINUATION_LEG has no origin access walk (hub transfer walk is charged on the
			// ACCESS_LEG side); ACCESS_LEG and NONE use the standard minWalk for access.
			double accessWalk = request.hubLegRole == DrtRequest.HubLegRole.CONTINUATION_LEG
					? 0.0 : minWalk;
			double drtScore = calculateDrtScoreWithWalks(request, delays[i], travelTimes[i], distances[i],
					accessWalk, minWalk);
			remainingBudgets[i] = drtScore - request.bestModeScore;
		}
		return remainingBudgets;
	}

	/** Calculate budget for a single request (direct travel, no delay, min walk). */
	public double calculateBudget(DrtRequest request) {
		double minWalk = exMasConfig.getMinDrtAccessEgressDistance();
		// CONTINUATION_LEG has no origin access walk.
		double accessWalk = request.hubLegRole == DrtRequest.HubLegRole.CONTINUATION_LEG
				? 0.0 : minWalk;
		double drtScore = calculateDrtScoreWithWalks(request, 0.0, request.getTravelTime(), request.getDistance(),
				accessWalk, minWalk);
		return drtScore - request.bestModeScore;
	}

	/**
	 * Build the scoring context for a single request. Called once per request at
	 * construction time by {@code DrtRequestFactory}.
	 */
	public DrtRequest.ScoringContext computeScoringContext(DrtRequest request, Person person) {
		if (scoringParametersForPerson == null) {
			throw new IllegalStateException(
					"computeScoringContext is unavailable in Phase 2 (no ScoringParametersForPerson). "
					+ "Scoring contexts are pre-built in Phase 1 and reloaded via the low-memory dump.");
		}
		double walkDist = exMasConfig.getMinDrtAccessEgressDistance();
		double accessTime = walkDist / walkSpeed;
		double egressTime = walkDist / walkSpeed;

		Activity originActivity = null;
		Activity destActivity = null;
		double originDuration = 0.0;
		double destDuration = 0.0;

		if (exMasConfig.getOpportunityCostModel() == ExMasConfigGroup.OpportunityCostModel.LOG) {
			List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(person.getSelectedPlan());
			if (request.tripIndex >= 0 && request.tripIndex < trips.size()) {
				TripStructureUtils.Trip trip = trips.get(request.tripIndex);
				originActivity = trip.getOriginActivity();
				destActivity = trip.getDestinationActivity();
				double[] actDurations = OpportunityCostCalculator.computeActivityDurations(person.getSelectedPlan());
				if (request.tripIndex < actDurations.length) originDuration = actDurations[request.tripIndex];
				if (request.tripIndex + 1 < actDurations.length) destDuration = actDurations[request.tripIndex + 1];
			}
		}
		if (originActivity == null) {
			originActivity = PopulationUtils.createActivityFromLinkId("unknown", request.originLinkId);
		}
		if (destActivity == null) {
			destActivity = PopulationUtils.createActivityFromLinkId("unknown", request.destinationLinkId);
		}

		ScoringParameters scoringParams = scoringParametersForPerson.getScoringParameters(person);

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
				person, originActivity, destActivity, originDuration, destDuration,
				scoringParams, accessLeg, accessRoute, egressLeg, egressRoute,
				drtRouteTemplate, synOrigAct, synDestAct);
	}

	/**
	 * Person- and distance-specific marginal utility of money for a request's
	 * direct leg (utils/EUR); feeds the downstream fare-to-utility conversion.
	 * Uses the Euclidean distance between origin and destination as the distance
	 * input to the scoring adapter (consistent with how income-dependent adapters
	 * look up marginal utility by distance band).
	 */
	public double marginalUtilityOfMoney(DrtRequest request, Person person) {
		double euclid_km = Math.hypot(
				request.destinationX - request.originX,
				request.destinationY - request.originY) / 1000.0;
		return adapter.getMarginalUtilityOfMoney(person, euclid_km);
	}

	/**
	 * Score a DRT trip via the request's pre-built scoring context. Public entry point
	 * used by stop-based and hyperpool ride generators that have explicit walk distances.
	 */
	public double calculateDrtScoreWithWalks(DrtRequest request, double delay,
			double actualTravelTime, double actualDistance,
			double accessWalkDist, double egressWalkDist) {
		return DrtTripScorer.scoreWithContext(
				request.getScoringContext(), request, adapter,
				exMasConfig.getDrtMode(), exMasConfig.getOpportunityCostModel(),
				actualTravelTime, actualDistance, accessWalkDist, egressWalkDist, delay, walkSpeed);
	}
}
