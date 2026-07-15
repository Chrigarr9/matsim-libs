package org.matsim.contrib.demand_extraction.scoring;

import java.util.List;

import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Route;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup.OpportunityCostModel;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;
import org.matsim.contrib.drt.routing.DrtRoute;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.RouteUtils;

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
	 * <p>Thread-confined (EXT-5): this method NEVER writes into {@code ctx}. The
	 * ordering-specific walk legs and routes are built fresh per call from the
	 * supplied access/egress distances and departure times, so the same request's
	 * context can be scored concurrently by the parallel pairgen top-K phase without
	 * a determinism race. The context's template leg/route fields are read-only here
	 * (they remain part of the record purely for the Phase-1 dump schema).
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

		// CONTINUATION_LEG (urban hub->D) has no origin access walk: the physical transfer
		// walk is already charged on the ACCESS_LEG side (hub egress). Zeroing it here
		// ensures the per-leg budget gate correctly models the actual journey split.
		boolean isContinuation = request.hubLegRole == DrtRequest.HubLegRole.CONTINUATION_LEG;
		if (isContinuation) {
			accessWalkDist = 0.0;
		}

		double accessTime = accessWalkDist / walkSpeed;
		double egressTime = egressWalkDist / walkSpeed;
		double pickupTime = request.requestTime + delay;

		// Thread confinement (EXT-5): NEVER write into ctx — the same request's
		// context is scored concurrently by the parallel pairgen top-K phase.
		// Build the walk legs per call, mirroring computeScoringContext's templates.
		Leg accessWalkLeg = PopulationUtils.createLeg(TransportMode.walk);
		Route accessWalkRoute = RouteUtils.createGenericRouteImpl(
				request.originLinkId, request.originLinkId);
		accessWalkRoute.setDistance(accessWalkDist);
		accessWalkRoute.setTravelTime(accessTime);
		accessWalkLeg.setRoute(accessWalkRoute);
		accessWalkLeg.setTravelTime(accessTime);
		accessWalkLeg.setDepartureTime(pickupTime - accessTime);

		Leg egressWalkLeg = PopulationUtils.createLeg(TransportMode.walk);
		Route egressWalkRoute = RouteUtils.createGenericRouteImpl(
				request.destinationLinkId, request.destinationLinkId);
		egressWalkRoute.setDistance(egressWalkDist);
		egressWalkRoute.setTravelTime(egressTime);
		egressWalkLeg.setRoute(egressWalkRoute);
		egressWalkLeg.setTravelTime(egressTime);
		egressWalkLeg.setDepartureTime(pickupTime + travelTime);

		Leg drtLeg = PopulationUtils.createLeg(drtMode);
		drtLeg.setDepartureTime(pickupTime);
		drtLeg.setTravelTime(travelTime);
		DrtRoute drtRoute = new DrtRoute(request.originLinkId, request.destinationLinkId);
		drtRoute.setDirectRideTime(request.directTravelTime);
		drtRoute.setDistance(request.directDistance);
		drtRoute.setTravelTime(travelTime);
		drtLeg.setRoute(drtRoute);

		List<Leg> elements = List.of(accessWalkLeg, drtLeg, egressWalkLeg);

		TripScoreRequest scoreRequest = new TripScoreRequest(
				ctx.person(), drtMode, elements,
				ctx.syntheticOriginActivity(), ctx.syntheticDestActivity(),
				request.requestTime, null, request.tripIndex,
				List.of(), isContinuation);

		TripScoreResult result = adapter.scoreTrip(scoreRequest);
		double score = result.utility();

		if (request.transferWaitSeconds > 0) {
			score += ctx.scoringParams().marginalUtilityOfWaitingPt_s
					* request.transferWaitSeconds;
		}

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
