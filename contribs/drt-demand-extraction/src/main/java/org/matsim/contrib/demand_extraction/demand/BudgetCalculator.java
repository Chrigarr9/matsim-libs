package org.matsim.contrib.exmas.demand;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.contrib.exmas.config.ExMasConfigGroup;
import org.matsim.core.router.TripStructureUtils;

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
public class BudgetCalculator {

    private final ExMasConfigGroup config;
    private final ModeRoutingCache modeRoutingCache;
    private final ChainIdentifier chainIdentifier;
	private final Network network;
	private final BudgetToConstraintsCalculator budgetToConstraintsCalculator;

    @Inject
	public BudgetCalculator(ExMasConfigGroup config, ModeRoutingCache modeRoutingCache, ChainIdentifier chainIdentifier,
			Network network, BudgetToConstraintsCalculator budgetToConstraintsCalculator) {
        this.config = config;
        this.modeRoutingCache = modeRoutingCache;
        this.chainIdentifier = chainIdentifier;
		this.network = network;
		this.budgetToConstraintsCalculator = budgetToConstraintsCalculator;
    }

    public List<DrtRequest> calculateBudgets(org.matsim.api.core.v01.population.Population population) {
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

			List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(plan);

			if (tripToGroupId == null || tripModeAttributes == null) {
				// No routing data available for this person
				continue;
			}

			// Calculate trip-wise budgets
			// All trips are evaluated individually, but trips in the same group are linked
			for (int tripIdx = 0; tripIdx < trips.size(); tripIdx++) {
				TripStructureUtils.Trip trip = trips.get(tripIdx);

				if (!tripModeAttributes.containsKey(tripIdx)) {
					// No routing data for this trip
					continue;
				}

				Map<String, ModeAttributes> modeAttrs = tripModeAttributes.get(tripIdx);
				String drtMode = config.getDrtMode();

				// Check if DRT is available for this trip
				if (!modeAttrs.containsKey(drtMode)) {
					continue;
				}

				// Find best baseline mode (highest score excluding DRT)
				String bestBaselineMode = null;
				double bestBaselineScore = Double.NEGATIVE_INFINITY;
                
				for (Map.Entry<String, ModeAttributes> entry : modeAttrs.entrySet()) {
					if (!entry.getKey().equals(drtMode)) {
						if (entry.getValue().score > bestBaselineScore) {
							bestBaselineScore = entry.getValue().score;
							bestBaselineMode = entry.getKey();
						}
					}
                }
                
				if (bestBaselineMode == null) {
					// No valid baseline mode found
					continue;
				}

				// Calculate budget: utility gain from switching to DRT
				double drtScore = modeAttrs.get(drtMode).score;
				double budget = drtScore - bestBaselineScore;

				// Get group ID for this trip
				String groupId = tripToGroupId.getOrDefault(tripIdx, person.getId().toString() + "_trip_" + tripIdx);

				// Create DRT request with trip-specific budget
				// Trips with the same groupId must all be served together
				// (e.g., outbound and return trips in a subtour using a private vehicle)
				Activity originActivity = trip.getOriginActivity();
				Activity destActivity = trip.getDestinationActivity();
				
				Coord originCoord = getCoord(originActivity);
				Coord destCoord = getCoord(destActivity);
				
				// Get link IDs (activities on links)
				Id<Link> originLinkId = getLinkId(originActivity);
				Id<Link> destinationLinkId = getLinkId(destActivity);
				
				// Get DRT travel metrics
				ModeAttributes drtAttrs = modeAttrs.get(drtMode);
				double requestTime = trip.getOriginActivity().getEndTime().orElse(0.0);
				
				// Calculate temporal window with flexible origin/destination components
				// max_absolute_detour is the smaller of:
				// 1) Budget-derived detour (how much delay utility budget allows)
				// 2) Config max factor (policy limit on detour)
				double budgetDerivedDetour = budgetToConstraintsCalculator.budgetToMaxDetourTime(
					budget, drtAttrs.travelTime, drtAttrs.distance);
				double configMaxDetour = drtAttrs.travelTime * (config.getMaxDetourFactor() - 1.0);
				double maxAbsoluteDetour = Math.min(budgetDerivedDetour, configMaxDetour);
				
				// Origin flexibility (departure window) - how much earlier/later can we depart?
				// This shares the detour budget: if we use flexibility for late departure,
				// we have less budget for route detours
				double originFlex = config.getOriginFlexibilityAbsolute()
					+ (maxAbsoluteDetour * config.getOriginFlexibilityRelative());
				
				// Destination flexibility (arrival window) - how much earlier/later can we arrive?
				// This also shares the detour budget
				double destFlex = config.getDestinationFlexibilityAbsolute()
					+ (maxAbsoluteDetour * config.getDestinationFlexibilityRelative());
				
				// Python pattern: 
				//   earliest_departure = treq - max_negative_delay
				//   latest_departure = treq + max_positive_delay  
				//   earliest_arrival = earliest_departure + travel_time
				//   latest_arrival = latest_departure + travel_time
				// 
				// Key insight: The flexibility values INCLUDE the detour budget allocation.
				// When calculating latest_arrival, we only add direct travel_time because
				// the detour budget is already consumed by the flexibility calculations.
				double earliestDep = requestTime - originFlex;
				double latestDep = requestTime + originFlex;
				double earliestArr = earliestDep + drtAttrs.travelTime;
				double latestArr = latestDep + drtAttrs.travelTime + destFlex;

				requests.add(DrtRequest.builder()
						.index(requests.size())
						.personId(person.getId())
						.groupId(groupId)
						.tripIndex(tripIdx)
						.budget(budget)
						.bestModeScore(bestBaselineScore)
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
						.build());
			}
		}

		return requests;
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
	 * Gets the link ID of an activity. If activity has no link, finds nearest link to coordinate.
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
