package org.matsim.contrib.demand_extraction.scoring;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Route;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup.OpportunityCostModel;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;
import org.matsim.contrib.drt.routing.DrtRoute;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scoring.functions.ScoringParameters;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;

/**
 * Shared utility for scoring synthetic DRT trips (access walk + DRT leg + egress walk).
 *
 * <p>Both {@link org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator}
 * and {@link org.matsim.contrib.demand_extraction.demand.BudgetToConstraintsCalculator}
 * need to construct and score the same walk+DRT+walk trip structure. This class
 * centralizes that logic to avoid duplication and ensure consistent scoring.
 *
 * <p>After adapter scoring, this class applies:
 * <ul>
 *   <li>Wait time penalty (if adapter did not include it)</li>
 *   <li>Opportunity cost (if configured AND adapter does not include it)</li>
 * </ul>
 */
public final class DrtTripScorer {

	private static final Logger log = LogManager.getLogger(DrtTripScorer.class);

	private DrtTripScorer() {
		// Utility class
	}

	/**
	 * Score a synthetic DRT trip via the adapter.
	 *
	 * <p>Constructs access walk + DRT leg + egress walk, scores via adapter,
	 * and applies wait time penalty + opportunity cost as appropriate.
	 *
	 * @param person                    the person
	 * @param request                   the DRT request (for link IDs, coordinates, timing)
	 * @param adapter                   the scoring adapter
	 * @param scoringParametersForPerson scoring parameters provider
	 * @param drtMode                   the DRT mode string (e.g. "drt")
	 * @param opportunityCostModel      which opportunity cost model to apply
	 * @param travelTime                in-vehicle travel time (seconds)
	 * @param distance                  travel distance (meters)
	 * @param accessWalkDist            access walk distance (meters)
	 * @param egressWalkDist            egress walk distance (meters)
	 * @param delay                     delay/wait time before pickup (seconds)
	 * @param walkSpeed                 walk speed (m/s)
	 * @param originActivity            the real origin activity (for LOG opp cost)
	 * @param destActivity              the real destination activity (for LOG opp cost)
	 * @param originDuration            actual origin activity duration in seconds
	 * @param destDuration              actual destination activity duration in seconds
	 * @return the trip utility score
	 */
	public static double score(
			Person person,
			DrtRequest request,
			DemandExtractionScoringAdapter adapter,
			ScoringParametersForPerson scoringParametersForPerson,
			String drtMode,
			OpportunityCostModel opportunityCostModel,
			double travelTime,
			double distance,
			double accessWalkDist,
			double egressWalkDist,
			double delay,
			double walkSpeed,
			Activity originActivity,
			Activity destActivity,
			double originDuration,
			double destDuration) {

		// Validate inputs
		if (!Double.isFinite(delay)) {
			log.error("Delay is NaN/infinite for request index {} (person: {}). Cannot calculate DRT score.",
					request.index, request.personId);
			return Double.NEGATIVE_INFINITY;
		}

		if (!Double.isFinite(travelTime) || !Double.isFinite(distance)) {
			log.warn("DRT routing failed for request index {} (person: {}): travelTime={}, distance={}",
					request.index, request.personId, travelTime, distance);
			return Double.NEGATIVE_INFINITY;
		}

		if (!Double.isFinite(accessWalkDist) || !Double.isFinite(egressWalkDist)) {
			log.error("Access/egress walk distance is NaN/infinite for request index {} (person: {})",
					request.index, request.personId);
			return Double.NEGATIVE_INFINITY;
		}

		double accessTime = accessWalkDist / walkSpeed;
		double egressTime = egressWalkDist / walkSpeed;
		double pickupTime = request.requestTime + delay;

		// Build trip elements: access walk + DRT leg + egress walk
		List<Leg> elements = new ArrayList<>(3);

		// Access walk
		Leg accessLeg = PopulationUtils.createLeg(TransportMode.walk);
		accessLeg.setDepartureTime(pickupTime - accessTime);
		accessLeg.setTravelTime(accessTime);
		Route accessRoute = RouteUtils.createGenericRouteImpl(
				request.originLinkId, request.originLinkId);
		accessRoute.setDistance(accessWalkDist);
		accessRoute.setTravelTime(accessTime);
		accessLeg.setRoute(accessRoute);
		elements.add(accessLeg);

		// DRT leg
		Leg drtLeg = PopulationUtils.createLeg(drtMode);
		drtLeg.setDepartureTime(pickupTime);
		drtLeg.setTravelTime(travelTime);
		DrtRoute drtRoute = new DrtRoute(request.originLinkId, request.destinationLinkId);
		drtRoute.setDirectRideTime(request.directTravelTime);
		drtRoute.setDistance(request.directDistance);
		drtRoute.setTravelTime(travelTime);
		drtLeg.setRoute(drtRoute);
		elements.add(drtLeg);

		// Egress walk
		Leg egressLeg = PopulationUtils.createLeg(TransportMode.walk);
		egressLeg.setDepartureTime(pickupTime + travelTime);
		egressLeg.setTravelTime(egressTime);
		Route egressRoute = RouteUtils.createGenericRouteImpl(
				request.destinationLinkId, request.destinationLinkId);
		egressRoute.setDistance(egressWalkDist);
		egressRoute.setTravelTime(egressTime);
		egressLeg.setRoute(egressRoute);
		elements.add(egressLeg);

		// Create synthetic activities for adapters that need them (DMC, eqasim)
		Activity syntheticOriginActivity = PopulationUtils.createActivityFromLinkId(
				"drt_interaction", request.originLinkId);
		syntheticOriginActivity.setCoord(new Coord(request.originX, request.originY));
		syntheticOriginActivity.setEndTime(request.requestTime);
		Activity syntheticDestActivity = PopulationUtils.createActivityFromLinkId(
				"drt_interaction", request.destinationLinkId);
		syntheticDestActivity.setCoord(new Coord(request.destinationX, request.destinationY));

		// Score via adapter
		TripScoreRequest scoreRequest = new TripScoreRequest(
				person, drtMode, elements,
				syntheticOriginActivity, syntheticDestActivity,
				request.requestTime, null, request.tripIndex);

		TripScoreResult result = adapter.scoreTrip(scoreRequest);
		double score = result.utility();

		// Wait time penalty (if adapter didn't include it)
		if (!result.waitingDisutilityIncluded()) {
			ScoringParameters scoringParams = scoringParametersForPerson.getScoringParameters(person);
			double marginalUtilityOfWaitingPt_s = scoringParams.marginalUtilityOfWaitingPt_s;

			double detour = travelTime - request.directTravelTime;
			double waitTime = 0.0;

			if (delay > 0) {
				waitTime = delay;
			} else if (delay < 0) {
				waitTime = Math.max(0.0, Math.abs(delay) - detour);
			}

			score += marginalUtilityOfWaitingPt_s * waitTime;
		}

		// Opportunity cost (if configured AND adapter doesn't include it)
		if (opportunityCostModel != OpportunityCostModel.NONE && !adapter.includesOpportunityCost()) {
			double totalTravelTime = accessTime + travelTime + egressTime;
			ScoringParameters scoringParams = scoringParametersForPerson.getScoringParameters(person);
			score -= OpportunityCostCalculator.compute(opportunityCostModel, scoringParams,
					totalTravelTime, originActivity, destActivity,
					originDuration, destDuration);
		}

		return score;
	}

	/**
	 * Score a DRT trip using pre-computed scoring context.
	 * Avoids plan parsing, activity resolution, object allocation.
	 * Only travelTime, distance, and delay vary per call.
	 *
	 * <p>IMPORTANT: The template Leg objects in the context are MUTATED (departure time,
	 * travel time updated). This is safe because processSet runs sequentially per set
	 * within a single thread. The Leg objects are not shared across threads — each
	 * DrtRequest has its own ScoringContext with its own template objects.
	 */
	public static double scoreWithContext(
			DrtRequest.ScoringContext ctx,
			DrtRequest request,
			DemandExtractionScoringAdapter adapter,
			String drtMode,
			OpportunityCostModel opportunityCostModel,
			double travelTime,
			double distance,
			double delay,
			double walkSpeed) {

		if (!Double.isFinite(delay) || !Double.isFinite(travelTime) || !Double.isFinite(distance)) {
			return Double.NEGATIVE_INFINITY;
		}

		double accessTime = ctx.accessWalkRoute().getTravelTime().seconds();
		double egressTime = ctx.egressWalkRoute().getTravelTime().seconds();
		double pickupTime = request.requestTime + delay;

		// Update mutable template objects with ordering-specific values
		ctx.accessWalkLeg().setDepartureTime(pickupTime - accessTime);

		Leg drtLeg = PopulationUtils.createLeg(drtMode);
		drtLeg.setDepartureTime(pickupTime);
		drtLeg.setTravelTime(travelTime);
		// Clone the DRT route template and set ordering-specific travel time
		DrtRoute drtRoute = new DrtRoute(request.originLinkId, request.destinationLinkId);
		drtRoute.setDirectRideTime(request.directTravelTime);
		drtRoute.setDistance(request.directDistance);
		drtRoute.setTravelTime(travelTime);
		drtLeg.setRoute(drtRoute);

		ctx.egressWalkLeg().setDepartureTime(pickupTime + travelTime);

		// Build trip elements using pre-built legs
		List<Leg> elements = List.of(ctx.accessWalkLeg(), drtLeg, ctx.egressWalkLeg());

		// Score via adapter (using pre-built synthetic activities)
		TripScoreRequest scoreRequest = new TripScoreRequest(
				ctx.person(), drtMode, elements,
				ctx.syntheticOriginActivity(), ctx.syntheticDestActivity(),
				request.requestTime, null, request.tripIndex);

		TripScoreResult result = adapter.scoreTrip(scoreRequest);
		double score = result.utility();

		// Wait time penalty
		if (!result.waitingDisutilityIncluded()) {
			double marginalUtilityOfWaitingPt_s = ctx.scoringParams().marginalUtilityOfWaitingPt_s;
			double detour = travelTime - request.directTravelTime;
			double waitTime = 0.0;
			if (delay > 0) {
				waitTime = delay;
			} else if (delay < 0) {
				waitTime = Math.max(0.0, Math.abs(delay) - detour);
			}
			score += marginalUtilityOfWaitingPt_s * waitTime;
		}

		// Opportunity cost
		if (opportunityCostModel != OpportunityCostModel.NONE && !adapter.includesOpportunityCost()) {
			double totalTravelTime = accessTime + travelTime + egressTime;
			score -= OpportunityCostCalculator.compute(opportunityCostModel, ctx.scoringParams(),
					totalTravelTime, ctx.originActivity(), ctx.destActivity(),
					ctx.originDuration(), ctx.destDuration());
		}

		return score;
	}

	/**
	 * Resolve origin/destination activities from the person's plan and delegate to
	 * {@link #score}.
	 *
	 * <p>Both {@link org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator}
	 * and {@link org.matsim.contrib.demand_extraction.demand.BudgetToConstraintsCalculator}
	 * need to perform identical activity-resolution logic before scoring. This method
	 * encapsulates that shared logic:
	 * <ol>
	 *   <li>If {@code opportunityCostModel == LOG}, look up the trip from the person's
	 *       selected plan by {@code request.tripIndex} and extract activities + durations.</li>
	 *   <li>Create {@code "unknown"} fallback activities for any that are still null.</li>
	 *   <li>Delegate to {@link #score}.</li>
	 * </ol>
	 *
	 * @param person                      the person (must not be null)
	 * @param request                     the DRT request
	 * @param adapter                     the scoring adapter
	 * @param scoringParametersForPerson  scoring parameters provider
	 * @param drtMode                     the DRT mode string (e.g. "drt")
	 * @param opportunityCostModel        which opportunity cost model to apply
	 * @param travelTime                  in-vehicle travel time (seconds)
	 * @param distance                    travel distance (meters)
	 * @param accessWalkDist              access walk distance (meters)
	 * @param egressWalkDist              egress walk distance (meters)
	 * @param delay                       delay/wait time before pickup (seconds)
	 * @param walkSpeed                   walk speed (m/s)
	 * @return the trip utility score
	 */
	public static double scoreWithActivityResolution(
			Person person,
			DrtRequest request,
			DemandExtractionScoringAdapter adapter,
			ScoringParametersForPerson scoringParametersForPerson,
			String drtMode,
			OpportunityCostModel opportunityCostModel,
			double travelTime,
			double distance,
			double accessWalkDist,
			double egressWalkDist,
			double delay,
			double walkSpeed) {

		Activity originActivity = null;
		Activity destActivity = null;
		double originDuration = 0.0;
		double destDuration = 0.0;

		if (opportunityCostModel == OpportunityCostModel.LOG) {
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

		// Provide fallback activities if not resolved
		if (originActivity == null) {
			originActivity = PopulationUtils.createActivityFromLinkId("unknown", request.originLinkId);
		}
		if (destActivity == null) {
			destActivity = PopulationUtils.createActivityFromLinkId("unknown", request.destinationLinkId);
		}

		return score(person, request, adapter, scoringParametersForPerson,
				drtMode, opportunityCostModel,
				travelTime, distance,
				accessWalkDist, egressWalkDist, delay, walkSpeed,
				originActivity, destActivity, originDuration, destDuration);
	}
}
