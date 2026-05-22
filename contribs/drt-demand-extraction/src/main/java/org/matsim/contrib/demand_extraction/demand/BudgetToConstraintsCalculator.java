package org.matsim.contrib.demand_extraction.demand;

import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.scoring.DemandExtractionScoringAdapter;
import org.matsim.contrib.demand_extraction.scoring.DrtTripScorer;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.utils.geometry.CoordUtils;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Converts a utility budget into physical constraints via binary search over the
 * adapter's own {@link DemandExtractionScoringAdapter#scoreTrip}.
 *
 * <p>For each constraint (maxDetour, maxWait, maxWalk), the search scores synthetic
 * DRT trips with varying parameters and finds the boundary where the DRT score drops
 * below {@code request.bestModeScore}. This is exact for adapters whose utility is
 * fully determined by trip elements.
 *
 * <p>{@link #budgetToMaxCost} is the exception: it still needs
 * {@link DemandExtractionScoringAdapter#getMarginalUtilityOfMoney} because DRT fare
 * is external to the adapter's scoring.
 */
@Singleton
public class BudgetToConstraintsCalculator {

	private static final double BINARY_SEARCH_TOLERANCE_SECONDS = 5.0;
	private static final double BINARY_SEARCH_TOLERANCE_METERS = 5.0;

	/** Upper bound for binary search on maximum waiting time (seconds). */
	private static final double MAX_WAIT_UPPER_BOUND_SECONDS = 3600.0;

	/** Upper bound for binary search on maximum walk distance (meters). */
	private static final double MAX_WALK_UPPER_BOUND_METERS = 5000.0;

	private final ExMasConfigGroup exMasConfig;
	private final DrtConfigGroup drtConfig;
	private final DemandExtractionScoringAdapter adapter;
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
			DemandExtractionScoringAdapter adapter) {
		this.exMasConfig = exMasConfig;
		this.drtConfig = DrtConfigGroup.getSingleModeDrtConfig(config);
		this.adapter = adapter;

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
	 * Find maximum acceptable detour time by binary searching the adapter's scoring.
	 */
	public double budgetToMaxDetourTime(double budget, Person person,
			double directTravelTime, double directDistance, DrtRequest request) {
		if (budget <= 0) {
			return 0.0;
		}

		double speed = directDistance / directTravelTime;
		double lo = 0;
		double hi = directTravelTime * (exMasConfig.getMaxDetourFactor() - 1.0);

		while (hi - lo > BINARY_SEARCH_TOLERANCE_SECONDS) {
			double mid = (lo + hi) / 2.0;
			double detourTime = directTravelTime + mid;
			double detourDist = directDistance + mid * speed;

			double score = scoreDrtTrip(request, detourTime, detourDist, 0.0);

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
	 */
	public double budgetToMaxWaitingTime(double budget, Person person, DrtRequest request) {
		if (budget <= 0) {
			return 0.0;
		}

		double lo = 0;
		double hi = MAX_WAIT_UPPER_BOUND_SECONDS;

		while (hi - lo > BINARY_SEARCH_TOLERANCE_SECONDS) {
			double mid = (lo + hi) / 2.0;
			double score = scoreDrtTrip(request,
					request.directTravelTime, request.directDistance, mid);

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
	 * <p>Splits the resulting distance equally across access and egress.
	 */
	public double budgetToMaxWalkDistance(double budget, Person person, DrtRequest request) {
		if (budget <= 0) {
			return exMasConfig.getMinDrtAccessEgressDistance();
		}

		double lo = 0;
		double hi = MAX_WALK_UPPER_BOUND_METERS;

		while (hi - lo > BINARY_SEARCH_TOLERANCE_METERS) {
			double mid = (lo + hi) / 2.0;
			double score = scoreDrtTripWithWalks(request,
					request.directTravelTime, request.directDistance, mid, mid, 0.0);

			if (score >= request.bestModeScore) {
				lo = mid;
			} else {
				hi = mid;
			}
		}
		return Math.max(lo, exMasConfig.getMinDrtAccessEgressDistance());
	}

	/**
	 * Find max walk distance for a pooled-ride context, given the ride's actual
	 * (travelTime, distance, delay) and any minimum walks already consumed.
	 *
	 * <p>Binary-searches symmetric walk distances (access = egress = mid) while
	 * holding the pooled-ride scoring params fixed. The boundary is where
	 * {@code score(actualTT, actualDist, mid, mid, delay) == request.bestModeScore},
	 * i.e. where the passenger's remaining budget hits zero. The budget consumed
	 * by pooling (detour + wait) is implicit in the gap between
	 * {@code score(minimal walks, pooled params)} and {@code bestModeScore} —
	 * a more aggressive pool leaves a tighter walk envelope.
	 *
	 * <p>2·mid is the total walk budget; callers may split between access and
	 * egress (e.g., asymmetric pickup-then-dropoff stop search).
	 *
	 * <p>The {@code remainingBudget} arg is used only for the early-return guard
	 * at {@code remainingBudget <= 0}; the binary search itself derives the
	 * boundary from {@code request.bestModeScore} alone.
	 */
	public double budgetToMaxWalkDistance(double remainingBudget, Person person, DrtRequest request,
			double actualTT, double actualDist, double delay) {
		if (remainingBudget <= 0) {
			return exMasConfig.getMinDrtAccessEgressDistance();
		}
		double lo = 0;
		double hi = MAX_WALK_UPPER_BOUND_METERS;
		while (hi - lo > BINARY_SEARCH_TOLERANCE_METERS) {
			double mid = (lo + hi) / 2.0;
			double score = scoreDrtTripWithWalks(request, actualTT, actualDist, mid, mid, delay);
			if (score >= request.bestModeScore) {
				lo = mid;
			} else {
				hi = mid;
			}
		}
		return Math.max(lo, exMasConfig.getMinDrtAccessEgressDistance());
	}

	/**
	 * Find maximum acceptable waiting time for a pooled ride, given the ride's actual
	 * (travelTime, distance) and access/egress walk distances already committed.
	 *
	 * <p>Binary-searches the delay while holding actual TT, distance, and walks fixed.
	 * The boundary is where
	 * {@code score(actualTT, actualDist, accessWalk, egressWalk, delay) == request.bestModeScore},
	 * i.e. where the passenger's remaining budget is fully consumed by the wait.
	 *
	 * <p>The {@code remainingBudget} arg is used only for the early-return guard
	 * at {@code remainingBudget <= 0}; the binary search itself derives the
	 * boundary from {@code request.bestModeScore} alone.
	 */
	public double budgetToMaxWaitingTime(double remainingBudget, Person person, DrtRequest request,
			double actualTT, double actualDist, double accessWalk, double egressWalk) {
		if (remainingBudget <= 0) {
			return 0.0;
		}
		double lo = 0;
		double hi = MAX_WAIT_UPPER_BOUND_SECONDS;
		while (hi - lo > BINARY_SEARCH_TOLERANCE_SECONDS) {
			double mid = (lo + hi) / 2.0;
			double score = scoreDrtTripWithWalks(request, actualTT, actualDist, accessWalk, egressWalk, mid);
			if (score >= request.bestModeScore) {
				lo = mid;
			} else {
				hi = mid;
			}
		}
		return lo;
	}

	/** Returns the minimum DRT access/egress walk distance floor (metres) from {@link ExMasConfigGroup}. */
	public double getMinDrtAccessEgressDistance() {
		return exMasConfig.getMinDrtAccessEgressDistance();
	}

	/**
	 * Compute maximum acceptable fare from remaining budget.
	 * This is the only constraint that still needs {@code margUtilMoney} explicitly.
	 */
	public double budgetToMaxCost(double budget, Person person,
			double travelTime, double distanceMeters, DrtRequest request) {
		if (budget < 0 || !Double.isFinite(budget)) {
			return 0.0;
		}

		double euclidDist_km = CoordUtils.calcEuclideanDistance(
				new org.matsim.api.core.v01.Coord(request.originX, request.originY),
				new org.matsim.api.core.v01.Coord(request.destinationX, request.destinationY)) / 1000.0;

		double marginalUtilityOfMoney;
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

	/** Score a DRT trip with fixed min walk distance via the request's scoring context. */
	private double scoreDrtTrip(DrtRequest request, double travelTime, double distance, double delay) {
		double minWalk = exMasConfig.getMinDrtAccessEgressDistance();
		return scoreDrtTripWithWalks(request, travelTime, distance, minWalk, minWalk, delay);
	}

	private double scoreDrtTripWithWalks(DrtRequest request,
			double travelTime, double distance,
			double accessWalkDist, double egressWalkDist, double delay) {
		return DrtTripScorer.scoreWithContext(
				request.getScoringContext(), request, adapter,
				exMasConfig.getDrtMode(), exMasConfig.getOpportunityCostModel(),
				travelTime, distance, accessWalkDist, egressWalkDist, delay, walkSpeed);
	}
}
