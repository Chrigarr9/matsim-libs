package org.matsim.contrib.demand_extraction.demand;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.router.TripStructureUtils.Trip;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Calculates utility budgets for DRT trips by comparing DRT utility against the
 * best baseline mode.
 * 
 * Budget calculation strategy:
 * - Trip-wise budgets: Each trip gets its own budget = score(DRT) -
 * score(best_baseline)
 * - Linking: Trips within subtours that use private vehicles are linked by
 * groupId
 * - Baseline selection: Always use the mode with the highest score (excluding
 * DRT), not the current plan mode
 * 
 * The budget represents the maximum "price" (in utility terms) that an agent is
 * willing to pay
 * for DRT service compared to their best alternative. During optimization,
 * service quality can be
 * degraded (increasing detour, waiting time, etc.) until this budget is
 * exhausted.
 */
@Singleton
public class DrtRequestFactory {

	private final ExMasConfigGroup exmasConfig;
	private final ModeRoutingCache modeRoutingCache;
	private final ChainIdentifier chainIdentifier;
	private final Network network;
	private final BudgetToConstraintsCalculator budgetToConstraintsCalculator;
	private final BudgetValidator budgetValidator;

	@Inject
	public DrtRequestFactory(ExMasConfigGroup config, ModeRoutingCache modeRoutingCache,
			ChainIdentifier chainIdentifier,
			Network network, BudgetToConstraintsCalculator budgetToConstraintsCalculator,
			BudgetValidator budgetValidator) {
		this.exmasConfig = config;
		this.modeRoutingCache = modeRoutingCache;
		this.chainIdentifier = chainIdentifier;
		this.network = network;
		this.budgetToConstraintsCalculator = budgetToConstraintsCalculator;
		this.budgetValidator = budgetValidator;
	}

	public List<DrtRequest> buildRequests(Population population) {
		List<DrtRequest> requests = new ArrayList<>();

		for (Person person : population.getPersons().values()) {
			Plan plan = person.getSelectedPlan();

			// Get chain/group assignments (trip index -> group ID)
			// Group ID indicates which trips must be served together (subtours with private
			// vehicles)
			Map<Integer, String> tripToGroupId = chainIdentifier.getChainIds(person.getId());

			// Get mode attributes (trip index -> mode -> attributes including score)
			Map<Integer, Map<String, ModeAttributes>> tripModeAttributes = modeRoutingCache
					.getAttributes(person.getId());

			List<Trip> trips = TripStructureUtils.getTrips(plan);
			Map<Id<Person>, Map<Integer, Entry<String, Double>>> bestBaselineModes = modeRoutingCache
					.getBestBaselineModes();
									// Check if DRT is available for this trip

			if (tripToGroupId == null || tripModeAttributes == null) {
				// No routing data available for this person
				continue;
			}

			// Calculate trip-wise budgets
			// All trips are evaluated individually, but trips in the same group are linked
			for (int tripIdx = 0; tripIdx < trips.size(); tripIdx++) {
				Trip trip = trips.get(tripIdx);
				Entry<String, Double> bestBaselineMode = bestBaselineModes.get(person.getId()).get(tripIdx);
				
				Map<String, ModeAttributes> modeAttrs = tripModeAttributes.get(tripIdx);
				String drtMode = exmasConfig.getDrtMode();

				if (!tripModeAttributes.containsKey(tripIdx) || bestBaselineMode == null || !modeAttrs.containsKey(drtMode)) {
					// No routing data for this trip
					continue;
				}

				// Get group ID for this trip
				String groupId = tripToGroupId.getOrDefault(tripIdx, person.getId().toString() + "_trip_" + tripIdx);

				DrtRequest request = buildRequest(
						requests.size(), person, trip, tripIdx, groupId, bestBaselineMode, modeAttrs);

				if (request != null) {
					requests.add(request);
				}
			}
		}

		return requests;
	}

	/**
	 * Build a DRT request from trip data.
	 * Extracted method to encapsulate request building logic.
	 * Uses BudgetValidator for consistent budget calculation methodology.
	 */
	private DrtRequest buildRequest(
			int requestIndex, Person person, Trip trip, int tripIdx,
			String groupId,	Entry<String, Double> bestBaselineMode, Map<String, ModeAttributes> modeAttrs) {

		String drtMode = exmasConfig.getDrtMode();
		ModeAttributes drtAttrs = modeAttrs.get(drtMode);

		Activity originActivity = trip.getOriginActivity();
		Activity destActivity = trip.getDestinationActivity();

		Coord originCoord = getCoord(originActivity);
		Coord destCoord = getCoord(destActivity);

		// Get link IDs (activities on links)
		Id<Link> originLinkId = getLinkId(originActivity);
		Id<Link> destinationLinkId = getLinkId(destActivity);

		double requestTime = trip.getOriginActivity().getEndTime().orElse(0.0);

		// Build temporary request to use BudgetValidator for budget calculation
		// This ensures consistent methodology between initial and ride validation
		DrtRequest tempRequest = DrtRequest.builder()
				.index(requestIndex)
				.personId(person.getId())
				.groupId(groupId)
				.tripIndex(tripIdx)
				.budget(0.0) // Will be calculated
				.bestModeScore(bestBaselineMode.getValue())
				.bestMode(bestBaselineMode.getKey())
				.originLinkId(originLinkId)
				.destinationLinkId(destinationLinkId)
				.originX(originCoord.getX())
				.originY(originCoord.getY())
				.destinationX(destCoord.getX())
				.destinationY(destCoord.getY())
				.requestTime(requestTime)
				.directTravelTime(drtAttrs.travelTime)
				.directDistance(drtAttrs.distance)

				// Temporary placeholders for time windows (will be recalculated with actual
				// budget)
				.earliestDeparture(requestTime)
				.latestArrival(requestTime + drtAttrs.travelTime)
				.build();

		// Calculate budget using BudgetValidator for consistency
		double budget = budgetValidator.calculateInitialBudget(
				tempRequest);

		// Calculate temporal window with flexible origin/destination components
		// max_absolute_detour is the smaller of:
		// 1) Budget-derived detour (how much delay utility budget allows)
		// 2) Config max factor (policy limit on detour)
		// Uses person-specific scoring parameters for accurate budget-to-constraint
		// conversion
		double budgetDerivedDetour = budgetToConstraintsCalculator.budgetToMaxDetourTime(
				budget, person, drtAttrs.travelTime, drtAttrs.distance);
		double configMaxDetour = drtAttrs.travelTime * (exmasConfig.getMaxDetourFactor() - 1.0);
		double maxAbsoluteDetour = Math.min(budgetDerivedDetour, configMaxDetour);

		// Origin flexibility (departure window) - how much earlier/later can we depart?
		// This shares the detour budget: if we use flexibility for late departure,
		// we have less budget for route detours
		double originFlex = exmasConfig.getOriginFlexibilityAbsolute()
				+ (maxAbsoluteDetour * exmasConfig.getOriginFlexibilityRelative());

		// Destination flexibility (arrival window) - how much earlier/later can we
		// arrive?
		// This also shares the detour budget
		double destFlex = exmasConfig.getDestinationFlexibilityAbsolute()
				+ (maxAbsoluteDetour * exmasConfig.getDestinationFlexibilityRelative());

		// Time window calculation:
		// earliestDeparture: earliest time DRT vehicle can pick up passenger
		// = requestTime (activity end) - originFlex (can leave earlier)
		// latestArrival: latest time passenger can arrive at destination
		// = requestTime + directTravelTime + maxAbsoluteDetour + destFlex

		double earliestDep = requestTime - originFlex;
		double latestArr = requestTime + drtAttrs.travelTime + maxAbsoluteDetour + destFlex;

		// Build final request with calculated budget and time windows
		return DrtRequest.builder()
				.index(requestIndex)
				.personId(person.getId())
				.groupId(groupId)
				.tripIndex(tripIdx)
				.budget(budget)
				.bestModeScore(bestBaselineMode.getValue())
				.bestMode(bestBaselineMode.getKey())
				.originLinkId(originLinkId)
				.destinationLinkId(destinationLinkId)
				.originX(originCoord.getX())
				.originY(originCoord.getY())
				.destinationX(destCoord.getX())
				.destinationY(destCoord.getY())
				.requestTime(requestTime)
				.earliestDeparture(earliestDep)
				.latestArrival(latestArr)
				.directTravelTime(drtAttrs.travelTime)
				.directDistance(drtAttrs.distance)
				.build();
	}

	/**
	 * Gets the coordinate of an activity, either from its coord attribute or from
	 * its link.
	 * This handles both activity-on-link and activity-with-coordinates models.
	 */
	private Coord getCoord(Activity activity) {
		Coord coord = activity.getCoord();
		if (coord != null) {
			return coord;
		}

		// Fall back to link coordinate if activity doesn't have explicit coordinate
		if (activity.getLinkId() != null) {
			return network.getLinks().get(activity.getLinkId()).getCoord();
		}

		throw new IllegalStateException("Activity has neither coordinate nor link ID: " + activity);
	}

	/**
	 * Gets the link ID of an activity. If activity has no link, finds nearest link
	 * to coordinate.
	 */
	private Id<Link> getLinkId(Activity activity) {
		if (activity.getLinkId() != null) {
			return activity.getLinkId();
		}

		// Activity has coordinate but no link - find nearest network link
		Coord coord = activity.getCoord();
		if (coord != null) {
			return org.matsim.core.network.NetworkUtils.getNearestLink(network, coord).getId();
		}

		throw new IllegalStateException("Activity has neither coordinate nor link ID: " + activity);
	}
}
