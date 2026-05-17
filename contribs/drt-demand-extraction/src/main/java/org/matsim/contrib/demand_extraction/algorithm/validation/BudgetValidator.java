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
import org.matsim.contrib.demand_extraction.algorithm.domain.HyperPooledRide;
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

	/**
	 * In-place variant of {@link #validateAndPopulateBudgets}: computes the budgets and
	 * mutates {@code ride} via {@link Ride#setRemainingBudgets(double[])}, returning the
	 * same reference. Returns {@code null} if any passenger budget is negative (caller drops).
	 *
	 * <p>Used by {@link #populateBudgetsBatch} to avoid the 2× ride retention from the
	 * builder rebuild in {@link #validateAndPopulateBudgets}. Subclasses (e.g.
	 * passthrough validators in tests) may override to skip the {@link #calculateRemainingBudgets}
	 * call and mutate directly from request fields.
	 */
	public Ride populateBudgetsInPlace(Ride ride) {
		double[] remainingBudgets = calculateRemainingBudgets(ride);
		for (double budget : remainingBudgets) {
			if (budget < 0) {
				return null;
			}
		}
		ride.setRemainingBudgets(remainingBudgets);
		return ride;
	}

	/**
	 * Validate a batch of rides. Rides already carrying budgets pass through unchanged;
	 * others are validated via {@link #populateBudgetsInPlace} (mutates in place) and rides
	 * with any negative budget are removed from the list.
	 *
	 * <p>Used by the deferred extension path: the extension DFS leaves budgets unset and
	 * this method runs once after extension completes. On scenarios where validation never
	 * rejects (e.g. Bavaria) {@code dropped} should be zero; a non-zero value means the
	 * deferred path cannot fall back to a longer-but-budget-feasible ordering the way the
	 * per-ordering path could, so we warn.
	 *
	 * <p>The list is mutated in place (compact-and-truncate) so peak Ride retention stays
	 * at 1× input size — critical at 30M+ rides where the previous rebuild allocated a
	 * second 30M-slot list of cloned Ride objects and OOMed at 64GB heap.
	 */
	public List<Ride> populateBudgetsBatch(List<Ride> rides) {
		int populated = 0;
		int skipped = 0;
		int dropped = 0;
		int writeIdx = 0;
		for (int readIdx = 0; readIdx < rides.size(); readIdx++) {
			Ride ride = rides.get(readIdx);
			if (ride.getRemainingBudgets() != null) {
				if (writeIdx != readIdx) rides.set(writeIdx, ride);
				writeIdx++;
				skipped++;
				continue;
			}
			Ride validated = populateBudgetsInPlace(ride);
			if (validated == null) {
				dropped++;
			} else {
				if (writeIdx != readIdx) rides.set(writeIdx, validated);
				writeIdx++;
				populated++;
			}
		}
		if (writeIdx < rides.size()) {
			rides.subList(writeIdx, rides.size()).clear();
		}
		log.info("populateBudgetsBatch: populated={}, skipped={} (already set), dropped={} (negative budget)",
				populated, skipped, dropped);
		if (dropped > 0) {
			log.warn("populateBudgetsBatch dropped {} rides with negative budgets after deferred validation — " +
					"the deferred path cannot fall back to a longer budget-feasible ordering. " +
					"If this is non-zero on your scenario, disable deferExtensionBudgetValidation.", dropped);
		}
		return rides;
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
			double drtScore = calculateDrtScoreWithWalks(request, delays[i], travelTimes[i], distances[i],
					minWalk, minWalk);
			remainingBudgets[i] = drtScore - request.bestModeScore;
		}
		return remainingBudgets;
	}

	/** Calculate budget for a single request (direct travel, no delay, min walk). */
	public double calculateBudget(DrtRequest request) {
		double minWalk = exMasConfig.getMinDrtAccessEgressDistance();
		double drtScore = calculateDrtScoreWithWalks(request, 0.0, request.getTravelTime(), request.getDistance(),
				minWalk, minWalk);
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

	// ===========================================
	// Hyper-Pooling (Stage 2) Validation
	// ===========================================

	public HyperPooledRide validateHyperPooledRide(HyperPooledRide ride) {
		if (!validateWalkDistances(ride)) {
			log.debug("Hyper-pooled ride {} failed walk distance validation", ride.getIndex());
			return null;
		}

		DrtRequest[] requests = ride.getRequests();
		double[] remainingBudgets = new double[ride.getDegree()];

		for (int i = 0; i < ride.getDegree(); i++) {
			DrtRequest request = requests[i];
			double drtScore = calculateHyperPoolDrtScore(ride, i);
			remainingBudgets[i] = drtScore - request.bestModeScore;

			if (remainingBudgets[i] < 0) {
				log.debug("Hyper-pooled ride {} failed budget validation for passenger {} (budget: {})",
						ride.getIndex(), request.index, remainingBudgets[i]);
				return null;
			}
		}

		return ride.toBuilder().remainingBudgets(remainingBudgets).build();
	}

	public double calculateHyperPoolDrtScore(HyperPooledRide ride, int passengerIndex) {
		DrtRequest request = ride.getRequest(passengerIndex);

		double accessWalkDistance = ride.getAccessWalkDistance(passengerIndex);
		double egressWalkDistance = ride.getEgressWalkDistance(passengerIndex);
		double inVehicleTime = ride.getInVehicleTime(passengerIndex);

		double accessWalkTime = accessWalkDistance / walkSpeed;
		double passengerBoardingTime = ride.getStartTime() + calculateTimeToStop(ride, passengerIndex);
		double passengerReadyTime = request.requestTime + accessWalkTime;
		double delay = passengerBoardingTime - passengerReadyTime;

		return calculateDrtScoreWithWalks(request, delay, inVehicleTime, request.directDistance,
				accessWalkDistance, egressWalkDistance);
	}

	private double calculateTimeToStop(HyperPooledRide ride, int passengerIndex) {
		int boardingStopIndex = ride.getBoardingStopIndex(passengerIndex);
		if (boardingStopIndex == 0) {
			return 0.0;
		}
		double fractionOfStops = (double) boardingStopIndex / (ride.getStopCount() - 1);
		return fractionOfStops * ride.getTotalRideTime();
	}

	public boolean validateWalkDistances(HyperPooledRide ride) {
		double maxWalkDistance = exMasConfig.getMaxWalkDistanceMeters();

		for (int i = 0; i < ride.getDegree(); i++) {
			double totalWalkDistance = ride.getPassengerTotalWalkDistance(i);
			if (totalWalkDistance > maxWalkDistance) {
				log.debug("Passenger {} total walk distance ({} m) exceeds max ({} m)",
						ride.getRequest(i).index, totalWalkDistance, maxWalkDistance);
				return false;
			}
			if (ride.getAccessWalkDistance(i) > maxWalkDistance) {
				log.debug("Passenger {} access walk distance ({} m) exceeds max ({} m)",
						ride.getRequest(i).index, ride.getAccessWalkDistance(i), maxWalkDistance);
				return false;
			}
			if (ride.getEgressWalkDistance(i) > maxWalkDistance) {
				log.debug("Passenger {} egress walk distance ({} m) exceeds max ({} m)",
						ride.getRequest(i).index, ride.getEgressWalkDistance(i), maxWalkDistance);
				return false;
			}
		}
		return true;
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
