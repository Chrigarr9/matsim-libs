package org.matsim.contrib.demand_extraction.algorithm.validation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.Route;
import org.matsim.contrib.demand_extraction.algorithm.domain.HyperPooledRide;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;
import org.matsim.contrib.demand_extraction.scoring.DemandExtractionScoringAdapter;
import org.matsim.contrib.demand_extraction.scoring.DrtTripScorer;
import org.matsim.core.config.Config;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Validates ride feasibility against budget constraints using the scoring adapter.
 *
 * <p>Builds complete DRT trips with access/egress walking legs and proper DRT routes,
 * then scores via the adapter. Compares actual DRT utility against best baseline mode
 * utility to ensure remaining budget >= 0.
 *
 * <p><b>Bug fix:</b> Opportunity cost is now applied consistently in both
 * ModeRoutingCache and BudgetValidator when configured AND the adapter doesn't
 * already include it. Previous code applied it only in ModeRoutingCache.
 */
@Singleton
public class BudgetValidator {
	private static final Logger log = LogManager.getLogger(BudgetValidator.class);

	private final DemandExtractionScoringAdapter adapter;
	private final ScoringParametersForPerson scoringParametersForPerson;
	private final ExMasConfigGroup exMasConfig;
	private final double walkSpeed;
	private final Config config;
	private final Population population;

	@Inject
	public BudgetValidator(
			DemandExtractionScoringAdapter adapter,
			ScoringParametersForPerson scoringParametersForPerson,
			ExMasConfigGroup exMasConfig,
			Config config,
			Population population) {
		this.adapter = adapter;
		this.scoringParametersForPerson = scoringParametersForPerson;
		this.exMasConfig = exMasConfig;
		this.population = population;
		this.config = config;

		this.walkSpeed = ExMasConfigGroup.getWalkSpeed(config);
	}

	/**
	 * Validate ride against budget constraints for all passengers.
	 * Returns new Ride with populated remainingBudgets field.
	 */
	public Ride validateAndPopulateBudgets(Ride ride) {
		double[] remainingBudgets = calculateRemainingBudgets(ride);

		for (double budget : remainingBudgets) {
			if (budget < 0) {
				return null;
			}
		}

		return ride.toBuilder()
				.remainingBudgets(remainingBudgets)
				.build();
	}

	/**
	 * Calculate remaining budgets for all passengers in a ride.
	 */
	public double[] calculateRemainingBudgets(Ride ride) {
		DrtRequest[] requests = ride.getRequests();
		double[] delays = ride.getDelays();
		double[] travelTimes = ride.getPassengerTravelTimes();
		double[] distances = ride.getPassengerDistances();
		double[] remainingBudgets = new double[ride.getDegree()];

		for (int i = 0; i < ride.getDegree(); i++) {
			DrtRequest request = requests[i];

			double actualDrtScore = calculateDrtScore(
					request,
					delays[i],
					travelTimes[i],
					distances[i],
					exMasConfig.getMinDrtAccessEgressDistance(),
					exMasConfig.getMinDrtAccessEgressDistance());
			remainingBudgets[i] = actualDrtScore - request.bestModeScore;
		}

		return remainingBudgets;
	}

	/**
	 * Calculate budget for a single request (direct travel, no delays).
	 */
	public double calculateBudget(DrtRequest request) {
		double walkDistance = exMasConfig.getMinDrtAccessEgressDistance();
		double actualDrtScore = calculateDrtScore(request, 0.0, request.getTravelTime(), request.getDistance(),
				walkDistance, walkDistance);
		return actualDrtScore - request.bestModeScore;
	}

	/**
	 * Pre-compute and cache scoring context for all requests.
	 * Called once before ride generation to avoid repeated plan parsing
	 * and object allocation during budget validation.
	 *
	 * <p>Thread-safe: contexts are published via volatile field on DrtRequest.
	 * ForkJoinPool worker threads read the context after this method completes.
	 */
	public void precomputeScoringContexts(java.util.List<DrtRequest> requests) {
		log.info("Pre-computing scoring contexts for {} requests...", requests.size());
		long start = System.currentTimeMillis();

		String drtMode = exMasConfig.getDrtMode();
		double walkDist = exMasConfig.getMinDrtAccessEgressDistance();
		double accessTime = walkDist / walkSpeed;
		double egressTime = walkDist / walkSpeed;
		ExMasConfigGroup.OpportunityCostModel ocModel = exMasConfig.getOpportunityCostModel();

		for (DrtRequest request : requests) {
			Person person = population.getPersons().get(request.personId);
			if (person == null) continue;

			// Resolve activities (expensive plan parsing — done once here)
			Activity originActivity = null;
			Activity destActivity = null;
			double originDuration = 0.0;
			double destDuration = 0.0;

			if (ocModel == ExMasConfigGroup.OpportunityCostModel.LOG) {
				java.util.List<org.matsim.core.router.TripStructureUtils.Trip> trips =
						org.matsim.core.router.TripStructureUtils.getTrips(person.getSelectedPlan());
				if (request.tripIndex >= 0 && request.tripIndex < trips.size()) {
					org.matsim.core.router.TripStructureUtils.Trip trip = trips.get(request.tripIndex);
					originActivity = trip.getOriginActivity();
					destActivity = trip.getDestinationActivity();
					double[] actDurations = org.matsim.contrib.demand_extraction.scoring.OpportunityCostCalculator
							.computeActivityDurations(person.getSelectedPlan());
					if (request.tripIndex < actDurations.length)
						originDuration = actDurations[request.tripIndex];
					if (request.tripIndex + 1 < actDurations.length)
						destDuration = actDurations[request.tripIndex + 1];
				}
			}
			if (originActivity == null) {
				originActivity = org.matsim.core.population.PopulationUtils
						.createActivityFromLinkId("unknown", request.originLinkId);
			}
			if (destActivity == null) {
				destActivity = org.matsim.core.population.PopulationUtils
						.createActivityFromLinkId("unknown", request.destinationLinkId);
			}

			// Scoring parameters
			org.matsim.core.scoring.functions.ScoringParameters scoringParams =
					scoringParametersForPerson.getScoringParameters(person);

			// Pre-build template legs (access walk, egress walk, DRT route)
			Leg accessLeg = org.matsim.core.population.PopulationUtils.createLeg(TransportMode.walk);
			accessLeg.setTravelTime(accessTime);
			Route accessRoute = org.matsim.core.population.routes.RouteUtils
					.createGenericRouteImpl(request.originLinkId, request.originLinkId);
			accessRoute.setDistance(walkDist);
			accessRoute.setTravelTime(accessTime);
			accessLeg.setRoute(accessRoute);

			Leg egressLeg = org.matsim.core.population.PopulationUtils.createLeg(TransportMode.walk);
			egressLeg.setTravelTime(egressTime);
			Route egressRoute = org.matsim.core.population.routes.RouteUtils
					.createGenericRouteImpl(request.destinationLinkId, request.destinationLinkId);
			egressRoute.setDistance(walkDist);
			egressRoute.setTravelTime(egressTime);
			egressLeg.setRoute(egressRoute);

			org.matsim.contrib.drt.routing.DrtRoute drtRouteTemplate =
					new org.matsim.contrib.drt.routing.DrtRoute(
							request.originLinkId, request.destinationLinkId);
			drtRouteTemplate.setDirectRideTime(request.directTravelTime);
			drtRouteTemplate.setDistance(request.directDistance);

			Activity synOrigAct = org.matsim.core.population.PopulationUtils
					.createActivityFromLinkId("drt_interaction", request.originLinkId);
			synOrigAct.setCoord(new org.matsim.api.core.v01.Coord(request.originX, request.originY));
			synOrigAct.setEndTime(request.requestTime);
			Activity synDestAct = org.matsim.core.population.PopulationUtils
					.createActivityFromLinkId("drt_interaction", request.destinationLinkId);
			synDestAct.setCoord(new org.matsim.api.core.v01.Coord(request.destinationX, request.destinationY));

			request.setScoringContext(new DrtRequest.ScoringContext(
					person, originActivity, destActivity, originDuration, destDuration,
					scoringParams, accessLeg, accessRoute, egressLeg, egressRoute,
					drtRouteTemplate, synOrigAct, synDestAct));
		}

		long elapsed = System.currentTimeMillis() - start;
		log.info("Pre-computed scoring contexts for {} requests in {}ms", requests.size(), elapsed);
	}

	/**
	 * Calculate DRT score with explicit walk distances (for stop-based pooling).
	 */
	public double calculateDrtScoreWithWalks(
			DrtRequest request,
			double delay,
			double actualTravelTime,
			double actualDistance,
			double accessWalkDistance,
			double egressWalkDistance) {
		return calculateDrtScore(request, delay, actualTravelTime, actualDistance,
				accessWalkDistance, egressWalkDistance);
	}

	// ===========================================
	// Hyper-Pooling (Stage 2) Validation Methods
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

			double actualDrtScore = calculateHyperPoolDrtScore(ride, i);

			remainingBudgets[i] = actualDrtScore - request.bestModeScore;

			if (remainingBudgets[i] < 0) {
				log.debug("Hyper-pooled ride {} failed budget validation for passenger {} (budget: {})",
						ride.getIndex(), request.index, remainingBudgets[i]);
				return null;
			}
		}

		return ride.toBuilder()
				.remainingBudgets(remainingBudgets)
				.build();
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
		double distance = request.directDistance;

		return calculateDrtScore(
				request,
				delay,
				inVehicleTime,
				distance,
				accessWalkDistance,
				egressWalkDistance);
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
			double accessWalk = ride.getAccessWalkDistance(i);
			double egressWalk = ride.getEgressWalkDistance(i);

			if (totalWalkDistance > maxWalkDistance) {
				log.debug("Passenger {} total walk distance ({} m) exceeds max ({} m)",
						ride.getRequest(i).index, totalWalkDistance, maxWalkDistance);
				return false;
			}

			if (accessWalk > maxWalkDistance) {
				log.debug("Passenger {} access walk distance ({} m) exceeds max ({} m)",
						ride.getRequest(i).index, accessWalk, maxWalkDistance);
				return false;
			}

			if (egressWalk > maxWalkDistance) {
				log.debug("Passenger {} egress walk distance ({} m) exceeds max ({} m)",
						ride.getRequest(i).index, egressWalk, maxWalkDistance);
				return false;
			}
		}

		return true;
	}

	/**
	 * Calculate DRT trip utility using the scoring adapter.
	 *
	 * <p>Delegates to {@link DrtTripScorer#scoreWithActivityResolution} which resolves
	 * origin/destination activities from the person's plan (for LOG opportunity cost),
	 * builds the complete trip (access walk + DRT leg + egress walk), scores via adapter,
	 * and applies wait time penalty + opportunity cost as appropriate.
	 */
	private double calculateDrtScore(DrtRequest request, double delay,
			double actualTravelTime, double actualDistance,
			double actualWalkDistanceAccess, double actualWalkDistanceEgress) {

		// Use pre-computed scoring context if available (fast path)
		DrtRequest.ScoringContext ctx = request.getScoringContext();
		if (ctx != null) {
			return DrtTripScorer.scoreWithContext(ctx, request, adapter,
					exMasConfig.getDrtMode(), exMasConfig.getOpportunityCostModel(),
					actualTravelTime, actualDistance, delay, walkSpeed);
		}

		// Fallback: full scoring (for single rides generated before precompute)
		Person person = population.getPersons().get(request.personId);
		return DrtTripScorer.scoreWithActivityResolution(person, request, adapter,
				scoringParametersForPerson, exMasConfig.getDrtMode(),
				exMasConfig.getOpportunityCostModel(),
				actualTravelTime, actualDistance,
				actualWalkDistanceAccess, actualWalkDistanceEgress, delay, walkSpeed);
	}
}
