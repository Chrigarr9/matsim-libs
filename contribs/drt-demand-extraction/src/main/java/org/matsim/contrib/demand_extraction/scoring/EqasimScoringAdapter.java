package org.matsim.contrib.demand_extraction.scoring;

import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.contrib.demand_extraction.scoring.EqasimRuntimeProbe.EqasimCostParameters;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.TripEstimator;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.candidates.TripCandidate;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.utils.objectattributes.attributable.AttributesImpl;
import org.eqasim.core.simulation.mode_choice.parameters.ModeParameters;

import java.util.List;

/**
 * Adapter for eqasim scoring.
 *
 * <p>For baseline modes (car, pt, bike, walk): scores through eqasim's
 * {@link TripEstimator} using the routing override mechanism.
 *
 * <p>For DRT: scores directly using eqasim's DRT parameters (alpha, betaTravelTime,
 * betaAccessEgressTime) extracted from {@link ModeParameters}. This avoids the
 * {@code DrtPredictor} which expects a real {@code DrtRoute} that we don't have
 * (we route DRT via car fallback).
 */
public class EqasimScoringAdapter implements DemandExtractionScoringAdapter {

	private final TripEstimator tripEstimator;
	private final EqasimCostParameters costParameters;
	private final ModeParameters modeParameters;

	public EqasimScoringAdapter(TripEstimator tripEstimator, EqasimCostParameters costParameters,
			ModeParameters modeParameters) {
		this.tripEstimator = tripEstimator;
		this.costParameters = costParameters;
		this.modeParameters = modeParameters;
	}

	@Override
	public String getName() {
		return "eqasim";
	}

	@Override
	public TripScoreResult scoreTrip(TripScoreRequest request) {
		String mode = request.candidateMode();

		// DRT: score directly using eqasim DRT parameters
		// (can't go through EqasimUtilityEstimator → DrtPredictor because our
		// car-routed legs don't have DrtRoute objects)
		if (mode.startsWith("drt")) {
			return scoreDrtDirectly(request);
		}

		// Baseline modes: score through eqasim's full estimator pipeline
		return scoreViaEstimator(request);
	}

	/**
	 * Score DRT directly using eqasim's DRT utility function:
	 * U = alpha + betaTravelTime * tt + betaAccessEgress * accessTime + betaCost * interaction * cost
	 *
	 * <p>This replicates {@code DrtUtilityEstimator} from eqasim-java 2.0.0 but
	 * extracts variables from our car-routed elements instead of requiring DrtRoute.
	 */
	private TripScoreResult scoreDrtDirectly(TripScoreRequest request) {
		double travelTime_min = 0;
		double accessEgressTime_min = 0;

		for (PlanElement pe : request.routedElements()) {
			if (pe instanceof Leg leg) {
				double tt = leg.getTravelTime().orElse(0.0) / 60.0;
				if (leg.getMode().contains(TransportMode.walk)) {
					accessEgressTime_min += tt;
				} else {
					travelTime_min += tt;
				}
			}
		}

		// DRT cost is zero (ZeroCostModel / best-case DRT)
		double utility = modeParameters.drt.alpha_u
				+ modeParameters.drt.betaTravelTime_u_min * travelTime_min
				+ modeParameters.drt.betaAccessEgressTime_u_min * accessEgressTime_min;
		// betaCost * interaction * 0 = 0 (no DRT fare in utility)

		return new TripScoreResult(utility, "eqasim:DrtDirect");
	}

	/**
	 * Score baseline modes through eqasim's full TripEstimator pipeline.
	 */
	private TripScoreResult scoreViaEstimator(TripScoreRequest request) {
		DiscreteModeChoiceTrip dmcTrip = new DiscreteModeChoiceTrip(
				request.originActivity(),
				request.destinationActivity(),
				request.candidateMode(),
				request.routedElements(),
				request.person().getId().hashCode(),
				request.tripIndex(),
				request.tripIndex(),
				request.tripAttributes() != null ? request.tripAttributes() : new AttributesImpl());
		dmcTrip.setDepartureTime(request.departureTime());

		RoutingOverrideManager.set(request.routedElements());
		try {
			List<TripCandidate> previousCandidates = request.previousTrips().stream()
					.map(ctx -> (TripCandidate) new SimpleTripCandidate(ctx.mode(), ctx.travelTime(), 0.0))
					.toList();

			TripCandidate candidate = tripEstimator.estimateTrip(
					request.person(),
					request.candidateMode(),
					dmcTrip,
					previousCandidates);

			return new TripScoreResult(candidate.getUtility(), "eqasim:TripEstimator");
		} finally {
			RoutingOverrideManager.clear();
		}
	}

	@Override
	public double getMarginalUtilityOfMoney(Person person, double euclideanDistance_km) {
		return costParameters.marginalUtilityOfMoney(euclideanDistance_km);
	}

	@Override
	public boolean includesOpportunityCost() {
		return true;
	}

	@Override
	public boolean supportsDistanceSpecificMoneyUtility() {
		return true;
	}

}
