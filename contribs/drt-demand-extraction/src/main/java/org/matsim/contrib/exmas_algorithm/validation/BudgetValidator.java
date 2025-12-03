package org.matsim.contrib.exmas_algorithm.validation;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.drt.routing.DrtRoute;
import org.matsim.contrib.exmas.config.ExMasConfigGroup;
import org.matsim.contrib.exmas.demand.DrtRequest;
import org.matsim.contrib.exmas_algorithm.domain.Ride;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scoring.ScoringFunction;
import org.matsim.core.scoring.ScoringFunctionFactory;

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
	private final ExMasConfigGroup exMasConfig;
	private final Network network;
	private final double walkSpeed;
	
	@Inject
	public BudgetValidator(
			ScoringFunctionFactory scoringFunctionFactory,
			ExMasConfigGroup exMasConfig,
			Network network,
			org.matsim.core.config.Config config) {
		this.scoringFunctionFactory = scoringFunctionFactory;
		this.exMasConfig = exMasConfig;
		this.network = network;
		
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
	 * @param ride the ride to validate
	 * @param requests array of all requests (indexed by request index)
	 * @return new Ride with remainingBudgets populated, or null if any budget is negative
	 */
	public Ride validateAndPopulateBudgets(Ride ride, DrtRequest[] requests) {
		double[] remainingBudgets = calculateRemainingBudgets(ride, requests);
		
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
				.requestIndices(ride.getRequestIndices())
				.originsOrdered(ride.getOriginsOrdered())
				.destinationsOrdered(ride.getDestinationsOrdered())
				.originsIndex(ride.getOriginsIndex())
				.destinationsIndex(ride.getDestinationsIndex())
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
	 * @param ride the ride to evaluate
	 * @param requests array of all requests (indexed by request index)
	 * @return array of remaining budgets (utils), one per passenger
	 */
	public double[] calculateRemainingBudgets(Ride ride, DrtRequest[] requests) {
		int[] requestIndices = ride.getRequestIndices();
		double[] delays = ride.getDelays();
		double[] travelTimes = ride.getPassengerTravelTimes();
		double[] distances = ride.getPassengerDistances();
		double[] remainingBudgets = new double[ride.getDegree()];
		
		for (int i = 0; i < ride.getDegree(); i++) {
			int reqIdx = requestIndices[i];
			DrtRequest request = requests[reqIdx];
			
			// Calculate actual DRT score using real MATSim scoring with access/egress
			double actualDrtScore = calculateDrtScore(
					request,
					delays[i],
					travelTimes[i],
					distances[i]);
			
			// Calculate remaining budget
			remainingBudgets[i] = request.bestModeScore - actualDrtScore;
		}
		
		return remainingBudgets;
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
			double actualTravelTime, double actualDistance) {
		
		// Create a pseudo-person for scoring
		Person person = PopulationUtils.getFactory().createPerson(request.personId);
		ScoringFunction scoringFunction = scoringFunctionFactory.createNewScoringFunction(person);
		
		double accessEgressDistance = exMasConfig.getMinDrtAccessEgressDistance();
		double currentTime = request.requestTime + delay;
		
		// Access leg (walk from origin activity to pickup point)
		// Always add even if distance is 0 - scoring can handle it
		Leg accessLeg = PopulationUtils.createLeg("walk");
		accessLeg.setDepartureTime(currentTime);
		double accessTime = accessEgressDistance / walkSpeed;
		accessLeg.setTravelTime(accessTime);
		
		// Use teleported route (same as ModeRoutingCache)
		Route accessRoute = 
			RouteUtils.createGenericRouteImpl(
				request.originLinkId, request.originLinkId);
		accessRoute.setDistance(accessEgressDistance);
		accessRoute.setTravelTime(accessTime);
		accessLeg.setRoute(accessRoute);
		
		scoringFunction.handleLeg(accessLeg);
		currentTime += accessTime;
		
		// DRT leg (main ride)
		Leg drtLeg = PopulationUtils.createLeg(exMasConfig.getDrtMode());
		drtLeg.setDepartureTime(currentTime);
		drtLeg.setTravelTime(actualTravelTime);
		
		// Use proper DrtRoute
		// directRideTime = unshared direct travel time (for fare calculation)
		// travelTime = actual travel time including waiting + detour (for scoring)
		// Waiting time is automatically scored as travel time by MATSim scoring function
		DrtRoute drtRoute = new DrtRoute(request.originLinkId, request.destinationLinkId);
		drtRoute.setDirectRideTime(request.directTravelTime);
		drtRoute.setDistance(actualDistance);
		drtRoute.setTravelTime(actualTravelTime);
		drtLeg.setRoute(drtRoute);
		
		scoringFunction.handleLeg(drtLeg);
		currentTime += actualTravelTime;
		
		// Egress leg (walk from dropoff point to destination activity)
		// Always add even if distance is 0 - scoring can handle it
		Leg egressLeg = PopulationUtils.createLeg("walk");
		egressLeg.setDepartureTime(currentTime);
		double egressTime = accessEgressDistance / walkSpeed;
		egressLeg.setTravelTime(egressTime);
		
		// Use teleported route (same as ModeRoutingCache)
		org.matsim.core.population.routes.Route egressRoute = 
			org.matsim.core.population.routes.RouteUtils.createGenericRouteImpl(
				request.destinationLinkId, request.destinationLinkId);
		egressRoute.setDistance(accessEgressDistance);
		egressRoute.setTravelTime(egressTime);
		egressLeg.setRoute(egressRoute);
		
		scoringFunction.handleLeg(egressLeg);
		
		scoringFunction.finish();
		return scoringFunction.getScore();
	}
}
