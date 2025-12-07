package org.matsim.contrib.demand_extraction.algorithm.validation;

import java.util.ArrayList;
import java.util.List;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.Route;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;
import org.matsim.contrib.drt.routing.DrtRoute;
import org.matsim.core.config.Config;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.RouteFactories;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scoring.ScoringFunction;
import org.matsim.core.scoring.ScoringFunctionFactory;
import org.matsim.core.scoring.functions.ScoringParameters;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;
import org.matsim.facilities.Facility;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Validates ride feasibility against budget constraints using MATSim scoring.
 * 
 * Builds complete DRT trips with access/egress walking legs and proper DRT routes.
 * Uses MATSim's ScoringFunction to calculate utility, ensuring accurate scoring with
 * all person-specific parameters and activity timing effects.
 * 
 * Compares actual DRT utility against best baseline mode utility to ensure
 * remaining budget >= 0. Populates ride with remaining budgets for all passengers.
 */
@Singleton
public class BudgetValidator {
	
	private final ScoringFunctionFactory scoringFunctionFactory;
	private final ScoringParametersForPerson scoringParametersForPerson;
	private final ExMasConfigGroup exMasConfig;
	private final double walkSpeed;
	private final Config config;
	private final Population population;
	
	@Inject
	public BudgetValidator(
			ScoringFunctionFactory scoringFunctionFactory,
			ScoringParametersForPerson scoringParametersForPerson,
			ExMasConfigGroup exMasConfig,
			Config config,
			Population population) {
		this.scoringFunctionFactory = scoringFunctionFactory;
		this.scoringParametersForPerson = scoringParametersForPerson;
		this.exMasConfig = exMasConfig;
		this.population = population;
		this.config = config;
		
		// Get configured walking speed (same logic as ModeRoutingCache)
		double configuredSpeed = config.routing()
				.getOrCreateModeRoutingParams(TransportMode.walk)
				.getTeleportedModeSpeed();
		this.walkSpeed = (configuredSpeed > 0) ? configuredSpeed : 0.833333333; // 3 km/h default
	}
	
	/**
	 * Validate ride against budget constraints for all passengers.
	 * Returns new Ride with populated remainingBudgets field.
	 *
	 * Uses direct object references from the Ride - no need to pass request array.
	 *
	 * @param ride the ride to validate (contains direct DrtRequest references)
	 * @return new Ride with remainingBudgets populated, or null if any budget is negative
	 */
	public Ride validateAndPopulateBudgets(Ride ride) {
		double[] remainingBudgets = calculateRemainingBudgets(ride);

		// Check if all budgets are non-negative
		for (double budget : remainingBudgets) {
			if (budget < 0) {
				return null; // Ride is infeasible
			}
		}

		// Create new Ride with remainingBudgets populated
		return Ride.builder()
				.index(ride.getIndex())
				.degree(ride.getDegree())
				.kind(ride.getKind())
				.requests(ride.getRequests())
				.originsOrdered(ride.getOriginsOrdered())
				.destinationsOrdered(ride.getDestinationsOrdered())
				.originsOrderedRequests(ride.getOriginsOrderedRequests())
				.destinationsOrderedRequests(ride.getDestinationsOrderedRequests())
				.passengerTravelTimes(ride.getPassengerTravelTimes())
				.passengerDistances(ride.getPassengerDistances())
				.passengerNetworkUtilities(ride.getPassengerNetworkUtilities())
				.delays(ride.getDelays())
				.remainingBudgets(remainingBudgets)
				.connectionTravelTimes(ride.getConnectionTravelTimes())
				.connectionDistances(ride.getConnectionDistances())
				.connectionNetworkUtilities(ride.getConnectionNetworkUtilities())
				.startTime(ride.getStartTime())
				.shapleyValues(ride.getShapleyValues())
				.predecessors(ride.getPredecessors())
				.successors(ride.getSuccessors())
				.build();
	}

	/**
	 * Calculate remaining budgets for all passengers in a ride.
	 *
	 * Uses direct object references from the Ride.
	 *
	 * @param ride the ride to evaluate (contains direct DrtRequest references)
	 * @return array of remaining budgets (utils), one per passenger
	 */
	public double[] calculateRemainingBudgets(Ride ride) {
		DrtRequest[] requests = ride.getRequests();
		double[] delays = ride.getDelays();
		double[] travelTimes = ride.getPassengerTravelTimes();
		double[] distances = ride.getPassengerDistances();
		double[] remainingBudgets = new double[ride.getDegree()];

		for (int i = 0; i < ride.getDegree(); i++) {
			DrtRequest request = requests[i];

			// Calculate actual DRT score using real MATSim scoring with access/egress
			double actualDrtScore = calculateDrtScore(
					request,
					delays[i],
					travelTimes[i],
					distances[i],
					// for now we will use the walk distance from the settings. Later with hyperpool
					// we will use actual walk distances
					exMasConfig.getMinDrtAccessEgressDistance(),
					exMasConfig.getMinDrtAccessEgressDistance());

			// Calculate remaining budget (positive = DRT is better than baseline)
			remainingBudgets[i] = actualDrtScore - request.bestModeScore;
		}

		return remainingBudgets;
	}

	/**
	 * Calculate budget for a single request (direct travel, no delays).
	 * This is equivalent to calculating remaining budget for a single-ride
	 * and can be used during request creation or single ride validation.
	 *
	 * @param request the DRT request with bestModeScore already populated
	 * @return budget = actualDrtScore - bestModeScore (positive = DRT better than baseline)
	 */
	public double calculateBudget(DrtRequest request) {
		double walkDistance = exMasConfig.getMinDrtAccessEgressDistance();
		double actualDrtScore = calculateDrtScore(request, 0.0, request.getTravelTime(), request.getDistance(),
				walkDistance, walkDistance);
		return actualDrtScore - request.bestModeScore;
	}
	
	/**
	 * Calculate DRT trip utility using MATSim scoring function.
	 * 
	 * Builds complete trip with access walk, DRT leg, and egress walk.
	 * Uses proper DrtRoute and MATSim's scoring infrastructure for accurate utility.
	 * 
	 * @param request the DRT request
	 * @param delay departure delay (seconds, positive = late)
	 * @param actualTravelTime actual in-vehicle travel time including detours (seconds)
	 * @param actualDistance actual travel distance including detours (meters)
	 * @return total utility score (includes all legs)
	 */
	private double calculateDrtScore(DrtRequest request, double delay, 
			double actualTravelTime, double actualDistance,
			double actualWalkDistanceAccess, double actualWalkDistanceEgress) {
		
		// Validate that routing succeeded - if travel time is infinite/NaN, DRT routing
		// failed
		if (!Double.isFinite(actualTravelTime) || !Double.isFinite(actualDistance)) {
			// Return very negative score to indicate this ride is not feasible
			// TODO log a warning with whhat links are problematic
			return Double.NEGATIVE_INFINITY;
		}

		// Create the real persons scoring function
		Person person = population.getPersons().get(request.personId);
		ScoringFunction scoringFunction = scoringFunctionFactory.createNewScoringFunction(person);

		double accessTime = actualWalkDistanceAccess / walkSpeed;
		
		// Timeline:
		// 1. request.requestTime: person ready to depart (earliest departure time)
		// 2. request.requestTime to (request.requestTime + accessTime): access walk
		// 3. (request.requestTime + accessTime) to (request.requestTime + delay): WAITING for vehicle
		// 4. (request.requestTime + delay): pickup (PersonEntersVehicle)
		// 5. (request.requestTime + delay) to (request.requestTime + delay + actualTravelTime): in-vehicle
		// 6. dropoff, then egress walk

		double pickupTime = request.requestTime + delay; // When vehicle actually arrives

		// Access leg (walk from origin activity to pickup point)
		Leg accessLeg = PopulationUtils.createLeg(TransportMode.walk);
		accessLeg.setDepartureTime(pickupTime - accessTime);
		accessLeg.setTravelTime(accessTime);
		
		// Use teleported route (same as ModeRoutingCache)
		Route accessRoute = 
			RouteUtils.createGenericRouteImpl(
				request.originLinkId, request.originLinkId);
		accessRoute.setDistance(actualWalkDistanceAccess);
		accessRoute.setTravelTime(accessTime);
		accessLeg.setRoute(accessRoute);
		
		scoringFunction.handleLeg(accessLeg);

		// DRT leg (main ride) - departs at pickup time
		Leg drtLeg = PopulationUtils.createLeg(exMasConfig.getDrtMode());
		drtLeg.setDepartureTime(pickupTime); // currentTime = pickupTime
		drtLeg.setTravelTime(actualTravelTime);
		
		// Build realistic DrtRoute exactly as MATSim router would
		// This includes: directRideTime, distance, travelTime, and constraints
		DrtRoute drtRoute = buildDrtRoute(
				request.originLinkId,
				request.destinationLinkId,
				request.directTravelTime, // unshared direct ride time
				request.directDistance,   // unshared direct distance
				actualTravelTime,         // actual ride time (may include detours + stop durations)
				actualDistance);          // actual distance (may include detours)
		drtLeg.setRoute(drtRoute);
		
		scoringFunction.handleLeg(drtLeg);

		// Egress leg (walk from dropoff point to destination activity)
		// Always add even if distance is 0 - scoring can handle it
		Leg egressLeg = PopulationUtils.createLeg("walk");
		egressLeg.setDepartureTime(pickupTime + actualTravelTime);
		double egressTime = actualWalkDistanceEgress / walkSpeed;
		egressLeg.setTravelTime(egressTime);
		
		// Use teleported route (same as ModeRoutingCache)
		Route egressRoute = 
			RouteUtils.createGenericRouteImpl(
				request.destinationLinkId, request.destinationLinkId);
		egressRoute.setDistance(actualWalkDistanceEgress);
		egressRoute.setTravelTime(egressTime);
		egressLeg.setRoute(egressRoute);
		
		scoringFunction.handleLeg(egressLeg);
		
		// Wait time scoring - get person-specific waiting utility parameter
		ScoringParameters scoringParams = scoringParametersForPerson.getScoringParameters(person);
		double marginalUtilityOfWaitingPt_s = scoringParams.marginalUtilityOfWaitingPt_s;

		// Calculate wait time based on delay and detour
		// detour = extra travel time beyond direct route
		double detour = actualTravelTime - request.directTravelTime;
		double waitTime = 0.0;

		if (delay > 0) {
			// Positive delay: user waits at origin before pickup
			// Wait time equals the delay (how late the vehicle arrives)
			waitTime = delay;
		} else if (delay < 0) {
			// Negative delay: user leaves early and arrives early at destination
			// The "early arrival" creates waiting time at the destination (before next activity)
			// However, any detour during the ride reduces this waiting time
			// (longer ride means less time waiting at destination)
			// waitTime = |delay| - detour, but cannot be negative
			waitTime = Math.max(0.0, Math.abs(delay) - detour);
		}

		// Apply person-specific waiting disutility
		double waitScore = marginalUtilityOfWaitingPt_s * waitTime;
		scoringFunction.addScore(waitScore);

		scoringFunction.finish();
		return scoringFunction.getScore();
	}
	
	/**
	 * Build a realistic DrtRoute exactly as MATSim's DrtRouteCreator would.
	 * 
	 * This matches the logic in DrtRouteCreator.createRoute():
	 * 1. Set directRideTime (unshared direct travel time for fare calculation)
	 * 2. Set distance (actual distance including any detours)
	 * 3. Set travelTime (actual IN-VEHICLE time from pickup to dropoff)
	 * 4. Calculate and set constraints (maxTravelTime, maxRideTime, maxWaitTime)
	 * 
	 * CRITICAL: actualRideTime is ONLY the in-vehicle time (PersonEntersVehicle to PersonLeavesVehicle).
	 * It does NOT include:
	 * - Access walk time (before pickup)
	 * - Wait time (delay before vehicle arrives)
	 * - Egress walk time (after dropoff)
	 * 
	 * However, it DOES include stop durations at pickup/dropoff:
	 * - DrtStopActivity waits for stopDuration before allowing pickups
	 * - This waiting AT THE STOP is part of in-vehicle time, not pre-pickup waiting
	 * - Scored as travel time using marginalUtilityOfTraveling
	 * 
	 * @param originLinkId origin link
	 * @param destinationLinkId destination link
	 * @param directRideTime unshared direct in-vehicle ride time (seconds) - NOT including access/egress
	 * @param directDistance unshared direct distance (meters)
	 * @param actualRideTime actual in-vehicle ride time including detours and stop durations (seconds)
	 * @param actualDistance actual distance including detours (meters)
	 * @return DrtRoute with all components set
	 */
	private DrtRoute buildDrtRoute(
			Id<Link> originLinkId,
			Id<Link> destinationLinkId,
			double directRideTime,
			double directDistance,
			double actualRideTime,
			double actualDistance) {
		
		DrtRoute route = new DrtRoute(originLinkId, destinationLinkId);
		
		// 1. Set direct ride time (used for fare calculation)
		route.setDirectRideTime(directRideTime);
		
		// 2. Set actual distance
		route.setDistance(directDistance);

		// 5. Override travelTime with actual ride time for scoring
		route.setTravelTime(actualRideTime);
		
		return route;
	}

	private List<? extends PlanElement> adjustDrtTripElements(List<? extends PlanElement> tripElements,
			RouteFactories routingFactories,
			Facility fromFacility, Facility toFacility) {
		boolean containsDrtLeg = false;
		for (PlanElement element : tripElements) {
			if (element instanceof Leg leg && exMasConfig.getDrtMode().equals(leg.getMode())) {
				containsDrtLeg = true;
			}
			if (element instanceof Leg leg && TransportMode.walk.equals(leg.getMode())) {
				double walkDist = exMasConfig.getMinDrtAccessEgressDistance();
				double walkSpeed = config.routing().getOrCreateModeRoutingParams(TransportMode.walk)
						.getTeleportedModeSpeed();
				if (walkSpeed == 0.0) {
					// Fallback to default walk speed if not configured (0.833 m/s = 3 km/h)
					walkSpeed = 0.833333333;
				}
				double walkTime = walkDist / walkSpeed;
				Route route = routingFactories.createRoute(Route.class, fromFacility.getLinkId(),
						toFacility.getLinkId());
				route.setTravelTime(walkTime);
				route.setDistance(walkDist);
				leg.setDepartureTime(leg.getDepartureTime().orElse(0.0) + leg.getTravelTime().orElse(0.0)
						- route.getTravelTime().orElse(0));
				leg.setRoute(route);
				leg.setTravelTime(walkTime);
			}
		}
		if (!containsDrtLeg) {
			return new ArrayList<>();
		}
		return tripElements;
	}
}
