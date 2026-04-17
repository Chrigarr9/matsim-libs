package org.matsim.contrib.demand_extraction.scoring;

import java.util.Set;

import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.scoring.functions.ModeUtilityParameters;
import org.matsim.core.scoring.functions.ScoringParameters;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;
import org.matsim.pt.routes.TransitPassengerRoute;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Adapter for standard MATSim planCalcScore-based scoring.
 *
 * <p>Scores trips using an inline leg scoring formula (CharyparNagel utility
 * WITHOUT daily constants). This is the correct trip-level utility for demand
 * extraction.
 *
 * <p>For PT trips, additionally applies:
 * <ul>
 *   <li>Waiting time disutility ({@code marginalUtilityOfWaitingPt_s * waitTime})</li>
 *   <li>Line switch penalty ({@code utilityOfLineSwitch * (transfers - 1)})</li>
 * </ul>
 * This matches MATSim's {@code MATSimTripScoringEstimator} behavior.
 *
 * <p>Opportunity cost is NOT included — the caller adds it when configured.
 */
@Singleton
public class PlanCalcScoreAdapter implements DemandExtractionScoringAdapter {

	private static final Logger log = LogManager.getLogger(PlanCalcScoreAdapter.class);

	/** Leg modes that indicate PT vehicular legs (for waiting time + line switches). */
	private static final Set<String> PT_LEG_MODES = Set.of(TransportMode.pt);

	private final ScoringParametersForPerson scoringParametersForPerson;

	@Inject
	public PlanCalcScoreAdapter(ScoringParametersForPerson scoringParametersForPerson) {
		this.scoringParametersForPerson = scoringParametersForPerson;
	}

	@Override
	public String getName() {
		return "planCalcScore";
	}

	@Override
	public TripScoreResult scoreTrip(TripScoreRequest request) {
		ScoringParameters params = scoringParametersForPerson.getScoringParameters(request.person());
		double score = 0.0;

		// Track PT-specific metrics
		int ptVehicularLegs = 0;
		double ptWaitingTime = 0.0;
		double departureTime = request.departureTime();

		for (PlanElement pe : request.routedElements()) {
			if (pe instanceof Leg leg) {
				// Score leg (time, distance, monetary cost, ASC)
				score += scoreLeg(leg, params);

				// Track PT waiting time and line switches
				if (PT_LEG_MODES.contains(leg.getMode()) && leg.getRoute() instanceof TransitPassengerRoute) {
					ptVehicularLegs++;
					// PT waiting time = gap between leg departure and when previous element ended
					// For the first PT leg, this is the time from trip departure to PT departure
					// For subsequent PT legs, this is the transfer waiting time
					// The SwissRailRaptor stores boarding time in the route's departure time
					double legDepartureTime = leg.getDepartureTime().orElse(departureTime);
					double waitTime = legDepartureTime - departureTime;
					if (waitTime > 0) {
						ptWaitingTime += waitTime;
					}
				}

				// Track time progression
				departureTime += leg.getTravelTime().orElse(0.0);
			}
		}

		// PT waiting time disutility (matches MATSimTripScoringEstimator)
		if (ptWaitingTime > 0) {
			score += params.marginalUtilityOfWaitingPt_s * ptWaitingTime;
		}

		// Line switch penalty (matches MATSimTripScoringEstimator)
		if (ptVehicularLegs > 1) {
			score += params.utilityOfLineSwitch * (ptVehicularLegs - 1);
		}

		return new TripScoreResult(score, "planCalcScore:inline");
	}

	/**
	 * Score a single leg, handling walk-like sub-leg mode fallback.
	 * Falls back to walk params for modes containing "walk" that don't have
	 * explicit scoring parameters (e.g., "non_network_walk", "walk_teleportation").
	 * Matches MATSimTripScoringEstimator.computeLegUtility behavior.
	 */
	private double scoreLeg(Leg leg, ScoringParameters params) {
		String mode = leg.getMode();
		ModeUtilityParameters modeParams = params.modeParams.get(mode);

		if (modeParams == null && mode.contains(TransportMode.walk)) {
			modeParams = params.modeParams.get(TransportMode.walk);
		}

		if (modeParams == null) {
			log.warn("No scoring parameters found for mode '{}' — scoring leg as 0.0. "
					+ "Check planCalcScore config if this mode should contribute to utility.", mode);
			return 0.0;
		}

		double travelTime = leg.getTravelTime().orElse(0.0);
		double distance = 0.0;
		if (leg.getRoute() != null) {
			distance = leg.getRoute().getDistance();
			if (Double.isNaN(distance)) distance = 0.0;
		}

		// Compute inline (same formula as TripScoringUtils but uses resolved modeParams)
		return modeParams.constant
				+ modeParams.marginalUtilityOfTraveling_s * travelTime
				+ modeParams.marginalUtilityOfDistance_m * distance
				+ params.marginalUtilityOfMoney * modeParams.monetaryDistanceCostRate * distance;
	}

	@Override
	public double getDailyMonetaryConstantUtils(Person person, String mode) {
		ScoringParameters params = scoringParametersForPerson.getScoringParameters(person);
		ModeUtilityParameters modeParams = params.modeParams.get(mode);
		if (modeParams == null) {
			return 0.0;
		}
		// dailyMoneyConstant is in EUR (converted to utils via margUtilOfMoney)
		// dailyUtilityConstant is already in utils
		return modeParams.dailyMoneyConstant * params.marginalUtilityOfMoney
				+ modeParams.dailyUtilityConstant;
	}

	@Override
	public double getMarginalUtilityOfMoney(Person person, double euclideanDistance_km) {
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
