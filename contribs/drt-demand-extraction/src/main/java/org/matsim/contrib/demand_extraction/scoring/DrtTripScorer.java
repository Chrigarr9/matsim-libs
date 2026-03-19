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
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;
import org.matsim.contrib.drt.routing.DrtRoute;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.RouteUtils;
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
	 * @param person                  the person
	 * @param request                 the DRT request (for link IDs, coordinates, timing)
	 * @param adapter                 the scoring adapter
	 * @param scoringParametersForPerson scoring parameters provider
	 * @param exMasConfig             ExMAS config (for DRT mode, opportunity cost flag)
	 * @param travelTime              in-vehicle travel time (seconds)
	 * @param distance                travel distance (meters)
	 * @param accessWalkDist          access walk distance (meters)
	 * @param egressWalkDist          egress walk distance (meters)
	 * @param delay                   delay/wait time before pickup (seconds)
	 * @param walkSpeed               walk speed (m/s)
	 * @return the trip utility score
	 */
	public static double score(
			Person person,
			DrtRequest request,
			DemandExtractionScoringAdapter adapter,
			ScoringParametersForPerson scoringParametersForPerson,
			ExMasConfigGroup exMasConfig,
			double travelTime,
			double distance,
			double accessWalkDist,
			double egressWalkDist,
			double delay,
			double walkSpeed) {

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
		Leg drtLeg = PopulationUtils.createLeg(exMasConfig.getDrtMode());
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
		Activity originActivity = PopulationUtils.createActivityFromLinkId(
				"drt_interaction", request.originLinkId);
		originActivity.setCoord(new Coord(request.originX, request.originY));
		originActivity.setEndTime(request.requestTime);
		Activity destActivity = PopulationUtils.createActivityFromLinkId(
				"drt_interaction", request.destinationLinkId);
		destActivity.setCoord(new Coord(request.destinationX, request.destinationY));

		// Score via adapter
		TripScoreRequest scoreRequest = new TripScoreRequest(
				person, exMasConfig.getDrtMode(), elements,
				originActivity, destActivity,
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
		if (exMasConfig.isIncludeOpportunityCost() && !adapter.includesOpportunityCost()) {
			double totalTravelTime = accessTime + travelTime + egressTime;
			ScoringParameters scoringParams = scoringParametersForPerson.getScoringParameters(person);
			score -= totalTravelTime * scoringParams.marginalUtilityOfPerforming_s;
		}

		return score;
	}
}
