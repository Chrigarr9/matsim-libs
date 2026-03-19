package org.matsim.contrib.demand_extraction.demand;

import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.scoring.DemandExtractionScoringAdapter;
import org.matsim.contrib.demand_extraction.scoring.DrtTripScorer;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.scoring.functions.ScoringParameters;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;
import org.matsim.core.utils.geometry.CoordUtils;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Converts utility budget into physical constraints using iterative binary search.
 *
 * <p>Instead of extracting marginal parameters and computing formulas, this class
 * uses the adapter's own {@link DemandExtractionScoringAdapter#scoreTrip} as a
 * black box and binary searches for constraint boundaries.
 *
 * <p>For each constraint (maxDetour, maxWait, maxWalk), the search constructs
 * DRT trips with varying parameters and scores them until the budget is exhausted.
 * This is exact for adapters whose utility is fully determined by trip elements.
 *
 * <p>The one exception is {@link #computeMaxCost}, which still needs
 * {@link DemandExtractionScoringAdapter#getMarginalUtilityOfMoney} because DRT
 * fare is external to the adapter's scoring.
 */
@Singleton
public class BudgetToConstraintsCalculator {

	private static final double BINARY_SEARCH_TOLERANCE_SECONDS = 1.0;
	private static final double BINARY_SEARCH_TOLERANCE_METERS = 1.0;

	/** Upper bound for binary search on maximum waiting time (seconds). */
	private static final double MAX_WAIT_UPPER_BOUND_SECONDS = 3600.0;

	/** Upper bound for binary search on maximum walk distance (meters). */
	private static final double MAX_WALK_UPPER_BOUND_METERS = 5000.0;

	private final Config config;
	private final ExMasConfigGroup exMasConfig;
	private final DrtConfigGroup drtConfig;
	private final DemandExtractionScoringAdapter adapter;
	private final ScoringParametersForPerson scoringParametersForPerson;
	private final Population population;
	private final double walkSpeed;

	// DRT fare parameters
	private final double baseFare;
	private final double timeFare_h;
	private final double distanceFare_m;
	private final double minFarePerTrip;

	@Inject
	public BudgetToConstraintsCalculator(
			Config config,
			ExMasConfigGroup exMasConfig,
			DemandExtractionScoringAdapter adapter,
			ScoringParametersForPerson scoringParametersForPerson,
			Population population) {
		this.config = config;
		this.exMasConfig = exMasConfig;
		this.drtConfig = DrtConfigGroup.getSingleModeDrtConfig(config);
		this.adapter = adapter;
		this.scoringParametersForPerson = scoringParametersForPerson;
		this.population = population;

		this.walkSpeed = ExMasConfigGroup.getWalkSpeed(config);

		var drtFareParams = drtConfig.getDrtFareParams().orElse(null);
		if (drtFareParams != null) {
			this.baseFare = drtFareParams.getBaseFare();
			this.timeFare_h = drtFareParams.getTimeFare_h();
			this.distanceFare_m = drtFareParams.getDistanceFare_m();
			this.minFarePerTrip = drtFareParams.getMinFarePerTrip();
		} else {
			this.baseFare = 0.0;
			this.timeFare_h = 0.0;
			this.distanceFare_m = 0.0;
			this.minFarePerTrip = 0.0;
		}
	}

	/**
	 * Find maximum acceptable detour time by binary search on the adapter's scoring.
	 *
	 * <p>Constructs DRT trips with increasing travel time and scores them.
	 * Finds the boundary where DRT score drops below bestModeScore.
	 *
	 * @param budget           remaining utility budget (must be > 0)
	 * @param person           the person
	 * @param directTravelTime direct DRT travel time (seconds)
	 * @param directDistance   direct DRT distance (meters)
	 * @param request          the DRT request (for link IDs, trip index)
	 * @return maximum additional detour time in seconds
	 */
	public double budgetToMaxDetourTime(double budget, Person person,
			double directTravelTime, double directDistance, DrtRequest request) {
		if (budget <= 0) {
			return 0.0;
		}

		if (!adapter.supportsIterativeConstraints()) {
			return fallbackMaxDetourTime(budget, person);
		}

		double speed = directDistance / directTravelTime;
		double lo = 0;
		double hi = directTravelTime * (exMasConfig.getMaxDetourFactor() - 1.0);

		while (hi - lo > BINARY_SEARCH_TOLERANCE_SECONDS) {
			double mid = (lo + hi) / 2.0;
			double detourTime = directTravelTime + mid;
			double detourDist = directDistance + mid * speed;

			double score = scoreDrtTrip(person, request, detourTime, detourDist,
					exMasConfig.getMinDrtAccessEgressDistance(),
					exMasConfig.getMinDrtAccessEgressDistance(), 0.0);

			if (score >= request.bestModeScore) {
				lo = mid;
			} else {
				hi = mid;
			}
		}
		return lo;
	}

	/**
	 * Find maximum acceptable waiting time by binary search.
	 *
	 * @param budget  remaining utility budget (must be > 0)
	 * @param person  the person
	 * @param request the DRT request
	 * @return maximum waiting time in seconds
	 */
	public double budgetToMaxWaitingTime(double budget, Person person, DrtRequest request) {
		if (budget <= 0) {
			return 0.0;
		}

		if (!adapter.supportsIterativeConstraints()) {
			return fallbackMaxWaitingTime(budget, person);
		}

		double lo = 0;
		double hi = MAX_WAIT_UPPER_BOUND_SECONDS;

		while (hi - lo > BINARY_SEARCH_TOLERANCE_SECONDS) {
			double mid = (lo + hi) / 2.0;

			// Score a DRT trip with this wait time (via delay parameter)
			double score = scoreDrtTrip(person, request,
					request.directTravelTime, request.directDistance,
					exMasConfig.getMinDrtAccessEgressDistance(),
					exMasConfig.getMinDrtAccessEgressDistance(), mid);

			if (score >= request.bestModeScore) {
				lo = mid;
			} else {
				hi = mid;
			}
		}
		return lo;
	}

	/**
	 * Find maximum acceptable walk distance by binary search.
	 *
	 * @param budget  remaining utility budget (must be > 0)
	 * @param person  the person
	 * @param request the DRT request
	 * @return maximum walk distance in meters (total access + egress)
	 */
	public double budgetToMaxWalkDistance(double budget, Person person, DrtRequest request) {
		if (budget <= 0) {
			return exMasConfig.getMinDrtAccessEgressDistance();
		}

		if (!adapter.supportsIterativeConstraints()) {
			return fallbackMaxWalkDistance(budget, person);
		}

		double lo = 0;
		double hi = MAX_WALK_UPPER_BOUND_METERS;

		while (hi - lo > BINARY_SEARCH_TOLERANCE_METERS) {
			double mid = (lo + hi) / 2.0;

			// Score with this walk distance (split equally access/egress)
			double score = scoreDrtTrip(person, request,
					request.directTravelTime, request.directDistance,
					mid, mid, 0.0);

			if (score >= request.bestModeScore) {
				lo = mid;
			} else {
				hi = mid;
			}
		}
		return Math.max(lo, exMasConfig.getMinDrtAccessEgressDistance());
	}

	/**
	 * Compute maximum acceptable fare from remaining budget.
	 * This is the ONE constraint that still needs an explicit parameter (margUtilMoney).
	 *
	 * @param budget          remaining utility budget
	 * @param person          the person
	 * @param travelTime      actual travel time (seconds)
	 * @param distanceMeters  actual distance (meters)
	 * @param request         the DRT request (for euclidean distance)
	 * @return maximum acceptable fare in currency units
	 */
	public double budgetToMaxCost(double budget, Person person,
			double travelTime, double distanceMeters, DrtRequest request) {
		if (budget < 0 || !Double.isFinite(budget)) {
			return 0.0;
		}

		// Get euclidean distance in km for distance-specific margUtilMoney
		double euclidDist_km = CoordUtils.calcEuclideanDistance(
				new org.matsim.api.core.v01.Coord(request.originX, request.originY),
				new org.matsim.api.core.v01.Coord(request.destinationX, request.destinationY)) / 1000.0;

		double marginalUtilityOfMoney;

		// Check config override first
		Double configOverride = exMasConfig.getMarginalUtilityOfMoneyOverride();
		if (configOverride != null && configOverride > 0) {
			marginalUtilityOfMoney = configOverride;
		} else {
			marginalUtilityOfMoney = adapter.getMarginalUtilityOfMoney(person, euclidDist_km);
		}

		if (!Double.isFinite(marginalUtilityOfMoney) || marginalUtilityOfMoney <= 0) {
			return 0.0;
		}

		double calculatedFare = this.baseFare
				+ (timeFare_h * (travelTime / 3600.0))
				+ (distanceFare_m * distanceMeters);
		double fareBaseline = Math.max(calculatedFare, minFarePerTrip);

		double additionalAffordableFare = budget / marginalUtilityOfMoney;
		double maxCost = fareBaseline + additionalAffordableFare;

		return Math.max(maxCost, minFarePerTrip);
	}

	/**
	 * Score a synthetic DRT trip via the adapter.
	 *
	 * <p>Delegates to {@link DrtTripScorer} which constructs access walk + DRT leg +
	 * egress walk, scores via adapter, and applies wait time penalty + opportunity
	 * cost as appropriate.
	 */
	private double scoreDrtTrip(Person person, DrtRequest request,
			double travelTime, double distance,
			double accessWalkDist, double egressWalkDist,
			double delay) {

		return DrtTripScorer.score(person, request, adapter, scoringParametersForPerson,
				exMasConfig, travelTime, distance,
				accessWalkDist, egressWalkDist, delay, walkSpeed);
	}

	// Fallback methods for adapters that don't support iterative constraints

	private double fallbackMaxDetourTime(double budget, Person person) {
		ScoringParameters params = scoringParametersForPerson.getScoringParameters(person);
		var drtParams = params.modeParams.get(exMasConfig.getDrtMode());
		if (drtParams == null) return 0.0;
		double disutil = Math.abs(drtParams.marginalUtilityOfTraveling_s);
		return disutil > 0 ? Math.abs(budget) / disutil : Double.POSITIVE_INFINITY;
	}

	private double fallbackMaxWaitingTime(double budget, Person person) {
		ScoringParameters params = scoringParametersForPerson.getScoringParameters(person);
		double waitUtil = Math.abs(params.marginalUtilityOfWaitingPt_s);
		return waitUtil > 0 ? Math.abs(budget) / waitUtil : Double.POSITIVE_INFINITY;
	}

	private double fallbackMaxWalkDistance(double budget, Person person) {
		ScoringParameters params = scoringParametersForPerson.getScoringParameters(person);
		var walkParams = params.modeParams.get(TransportMode.walk);
		if (walkParams == null) return exMasConfig.getMinDrtAccessEgressDistance();
		double disutilPerMeter = Math.abs(walkParams.marginalUtilityOfTraveling_s / walkSpeed
				+ walkParams.marginalUtilityOfDistance_m);
		return disutilPerMeter > 0
				? Math.max(Math.abs(budget) / disutilPerMeter, exMasConfig.getMinDrtAccessEgressDistance())
				: Double.POSITIVE_INFINITY;
	}
}
