package org.matsim.contrib.demand_extraction.scoring;

import java.util.List;

import org.matsim.api.core.v01.population.Leg;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup.OpportunityCostModel;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;
import org.matsim.contrib.drt.routing.DrtRoute;
import org.matsim.core.population.PopulationUtils;

/**
 * Scores a synthetic DRT trip (access walk + DRT leg + egress walk) via the adapter,
 * using each request's pre-computed {@link DrtRequest.ScoringContext}.
 *
 * <p>After adapter scoring, applies:
 * <ul>
 *   <li>Wait time penalty (if the adapter did not include it)</li>
 *   <li>Opportunity cost (if configured AND the adapter does not include it)</li>
 * </ul>
 */
public final class DrtTripScorer {

	private DrtTripScorer() {
		// Utility class
	}

	/**
	 * Score a DRT trip using the request's pre-computed scoring context.
	 *
	 * <p>The context's walk legs and routes are reused as mutable templates; each call
	 * overwrites them with the supplied access/egress distances, the derived walk times,
	 * and ordering-specific departure times. Safe as long as each context is used by at
	 * most one thread at a time — every {@link DrtRequest} owns its own context, and
	 * ExMAS processes any given request's rides sequentially within a worker thread.
	 */
	public static double scoreWithContext(
			DrtRequest.ScoringContext ctx,
			DrtRequest request,
			DemandExtractionScoringAdapter adapter,
			String drtMode,
			OpportunityCostModel opportunityCostModel,
			double travelTime,
			double distance,
			double accessWalkDist,
			double egressWalkDist,
			double delay,
			double walkSpeed) {

		if (!Double.isFinite(delay) || !Double.isFinite(travelTime) || !Double.isFinite(distance)
				|| !Double.isFinite(accessWalkDist) || !Double.isFinite(egressWalkDist)) {
			return Double.NEGATIVE_INFINITY;
		}

		double accessTime = accessWalkDist / walkSpeed;
		double egressTime = egressWalkDist / walkSpeed;
		double pickupTime = request.requestTime + delay;

		ctx.accessWalkRoute().setDistance(accessWalkDist);
		ctx.accessWalkRoute().setTravelTime(accessTime);
		ctx.accessWalkLeg().setTravelTime(accessTime);
		ctx.accessWalkLeg().setDepartureTime(pickupTime - accessTime);

		ctx.egressWalkRoute().setDistance(egressWalkDist);
		ctx.egressWalkRoute().setTravelTime(egressTime);
		ctx.egressWalkLeg().setTravelTime(egressTime);
		ctx.egressWalkLeg().setDepartureTime(pickupTime + travelTime);

		Leg drtLeg = PopulationUtils.createLeg(drtMode);
		drtLeg.setDepartureTime(pickupTime);
		drtLeg.setTravelTime(travelTime);
		DrtRoute drtRoute = new DrtRoute(request.originLinkId, request.destinationLinkId);
		drtRoute.setDirectRideTime(request.directTravelTime);
		drtRoute.setDistance(request.directDistance);
		drtRoute.setTravelTime(travelTime);
		drtLeg.setRoute(drtRoute);

		List<Leg> elements = List.of(ctx.accessWalkLeg(), drtLeg, ctx.egressWalkLeg());

		TripScoreRequest scoreRequest = new TripScoreRequest(
				ctx.person(), drtMode, elements,
				ctx.syntheticOriginActivity(), ctx.syntheticDestActivity(),
				request.requestTime, null, request.tripIndex);

		TripScoreResult result = adapter.scoreTrip(scoreRequest);
		double score = result.utility();

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

		if (opportunityCostModel != OpportunityCostModel.NONE && !adapter.includesOpportunityCost()) {
			double totalTravelTime = accessTime + travelTime + egressTime;
			score -= OpportunityCostCalculator.compute(opportunityCostModel, ctx.scoringParams(),
					totalTravelTime, ctx.originActivity(), ctx.destActivity(),
					ctx.originDuration(), ctx.destDuration());
		}

		return score;
	}
}
