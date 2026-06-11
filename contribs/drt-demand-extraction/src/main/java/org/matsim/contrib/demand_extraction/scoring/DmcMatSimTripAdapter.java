package org.matsim.contrib.demand_extraction.scoring;

import java.util.List;

import org.matsim.api.core.v01.population.Person;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.TripEstimator;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.candidates.TripCandidate;
import org.matsim.core.scoring.functions.ScoringParameters;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;
import org.matsim.utils.objectattributes.attributable.AttributesImpl;

/**
 * Adapter for DMC (Discrete Mode Choice) with MATSimTripScoring estimator.
 *
 * <p>Scores trips via DMC's {@link TripEstimator} using the routing override
 * mechanism: pre-routed elements are injected via {@link RoutingOverrideManager}
 * so the estimator scores them without re-routing.
 *
 * <p>This adapter uses the same scoring parameters as standard MATSim planCalcScore
 * (since MATSimTripScoringEstimator reads planCalcScore params), but goes through
 * DMC's estimation pipeline which may include additional logic like PT waiting
 * time estimation and line switch penalties.
 *
 * <p>Opportunity cost is NOT included — the caller adds it when configured.
 *
 * <p><b>Optional dependency:</b> This class requires {@code discrete_mode_choice}
 * on the classpath. It is only loaded when DMC is detected by the adapter resolver.
 */
public class DmcMatSimTripAdapter implements DemandExtractionScoringAdapter {

	private final TripEstimator tripEstimator;
	private final ScoringParametersForPerson scoringParametersForPerson;

	public DmcMatSimTripAdapter(TripEstimator tripEstimator,
			ScoringParametersForPerson scoringParametersForPerson) {
		this.tripEstimator = tripEstimator;
		this.scoringParametersForPerson = scoringParametersForPerson;
	}

	@Override
	public String getName() {
		return "dmc";
	}

	@Override
	public TripScoreResult scoreTrip(TripScoreRequest request) {
		if (request.excludeModeConstant()) {
			throw new UnsupportedOperationException(
					"excludeModeConstant (Paper-2 continuation legs) is only supported by "
					+ "the eqasim adapter; got " + getName());
		}
		// Build DMC trip from our request
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

		// Set routing override so the estimator's TripRouter returns our pre-routed elements
		RoutingOverrideManager.set(request.routedElements());
		try {
			// Convert previous trip contexts to DMC TripCandidates
			List<TripCandidate> previousCandidates = request.previousTrips().stream()
					.map(ctx -> (TripCandidate) new SimpleTripCandidate(ctx.mode(), ctx.travelTime(), 0.0))
					.toList();

			TripCandidate candidate = tripEstimator.estimateTrip(
					request.person(),
					request.candidateMode(),
					dmcTrip,
					previousCandidates);

			return new TripScoreResult(candidate.getUtility(), "dmc:TripEstimator");
		} finally {
			RoutingOverrideManager.clear();
		}
	}

	@Override
	public double getDailyMonetaryConstantUtils(Person person, String mode) {
		ScoringParameters params = scoringParametersForPerson.getScoringParameters(person);
		var modeParams = params.modeParams.get(mode);
		if (modeParams == null) {
			return 0.0;
		}
		return modeParams.dailyMoneyConstant * params.marginalUtilityOfMoney
				+ modeParams.dailyUtilityConstant;
	}

	@Override
	public double getMarginalUtilityOfMoney(Person person, double euclideanDistance_km) {
		// MATSimTripScoring uses same planCalcScore params
		ScoringParameters params = scoringParametersForPerson.getScoringParameters(person);
		return params.marginalUtilityOfMoney;
	}

	@Override
	public boolean includesOpportunityCost() {
		return false;
	}

	@Override
	public boolean supportsDistanceSpecificMoneyUtility() {
		return false;
	}

}
