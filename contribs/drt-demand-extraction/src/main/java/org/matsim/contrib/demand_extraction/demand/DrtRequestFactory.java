package org.matsim.contrib.demand_extraction.demand;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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
	private static final Logger log = LogManager.getLogger(DrtRequestFactory.class);

	private final ExMasConfigGroup exmasConfig;
	private final ModeRoutingCache modeRoutingCache;
	private final ChainIdentifier chainIdentifier;
	private final CommuteIdentifier commuteIdentifier;
	private final Network network;
	private final BudgetToConstraintsCalculator budgetToConstraintsCalculator;
	private final BudgetValidator budgetValidator;
	private final FlexibilityCalculator flexibilityCalculator;

	@Inject
	public DrtRequestFactory(ExMasConfigGroup config, ModeRoutingCache modeRoutingCache,
			ChainIdentifier chainIdentifier, CommuteIdentifier commuteIdentifier,
			Network network, BudgetToConstraintsCalculator budgetToConstraintsCalculator,
			BudgetValidator budgetValidator, FlexibilityCalculator flexibilityCalculator) {
		this.exmasConfig = config;
		this.modeRoutingCache = modeRoutingCache;
		this.chainIdentifier = chainIdentifier;
		this.commuteIdentifier = commuteIdentifier;
		this.network = network;
		this.budgetToConstraintsCalculator = budgetToConstraintsCalculator;
		this.budgetValidator = budgetValidator;
		this.flexibilityCalculator = flexibilityCalculator;
	}

	public List<DrtRequest> buildRequests(Population population) {
		log.info("Building DRT requests from {} persons...", population.getPersons().size());
		long startTime = System.currentTimeMillis();

		// Identify commute trips before building requests
		commuteIdentifier.identifyCommutes(population);

		ExMasConfigGroup.CommuteFilter commuteFilter = exmasConfig.getCommuteFilter();
		log.info("Commute filter: {}", commuteFilter);

		List<DrtRequest> requests = new ArrayList<>();
		int filteredByCommute = 0;

		int processedPersons = 0;
		int totalPersons = population.getPersons().size();
		int logInterval = Math.max(1, totalPersons / 10);

		// Sort persons by ID to ensure deterministic processing order
		// (parallel caching in ModeRoutingCache can complete in any order, but we want consistent output)
		List<Person> sortedPersons = new ArrayList<>(population.getPersons().values());
		sortedPersons.sort(java.util.Comparator.comparing(p -> p.getId().toString()));

		for (Person person : sortedPersons) {
			processedPersons++;
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
			Map<Id<Person>, Map<Integer, double[]>> ptAccessibilityMetrics = modeRoutingCache
					.getPtAccessibilityMetrics();

			if (tripToGroupId == null || tripModeAttributes == null) {
				// No routing data available for this person
				continue;
			}
			
			// Get person's baseline modes (may be null if no modes cached for this person)
			Map<Integer, Entry<String, Double>> personBaselineModes = bestBaselineModes.get(person.getId());
			if (personBaselineModes == null) {
				// No baseline modes cached for this person
				continue;
			}

			// Calculate trip-wise budgets
			// All trips are evaluated individually, but trips in the same group are linked
			for (int tripIdx = 0; tripIdx < trips.size(); tripIdx++) {
				Trip trip = trips.get(tripIdx);
				Entry<String, Double> bestBaselineMode = personBaselineModes.get(tripIdx);

				Map<String, ModeAttributes> modeAttrs = tripModeAttributes.get(tripIdx);
				String drtMode = exmasConfig.getDrtMode();

				if (!tripModeAttributes.containsKey(tripIdx) || bestBaselineMode == null || !modeAttrs.containsKey(drtMode)) {
					// No routing data for this trip
					continue;
				}

				// Check commute status and apply filter
				boolean isCommute = commuteIdentifier.isCommute(person.getId(), tripIdx);

				if (commuteFilter == ExMasConfigGroup.CommuteFilter.COMMUTES_ONLY && !isCommute) {
					filteredByCommute++;
					continue;
				}
				if (commuteFilter == ExMasConfigGroup.CommuteFilter.NON_COMMUTES && isCommute) {
					filteredByCommute++;
					continue;
				}

				// Get group ID for this trip
				String groupId = tripToGroupId.getOrDefault(tripIdx, person.getId().toString() + "_trip_" + tripIdx);

				// Get PT accessibility metrics for this trip
				Map<Integer, double[]> personPtMetrics = ptAccessibilityMetrics.get(person.getId());
				double[] ptMetrics = (personPtMetrics != null) ? personPtMetrics.get(tripIdx) : null;

				DrtRequest request = buildRequest(
						requests.size(), person, trip, tripIdx, groupId, isCommute, bestBaselineMode, modeAttrs, ptMetrics);

				if (request != null) {
					requests.add(request);
				}
			}

			// Progress logging
			if (processedPersons % logInterval == 0 || processedPersons == totalPersons) {
				double percent = (processedPersons * 100.0) / totalPersons;
				log.info("  Request building progress: {}/{} ({}%) - {} requests so far",
						processedPersons, totalPersons, String.format("%.1f", percent), requests.size());
			}
		}

		long elapsed = System.currentTimeMillis() - startTime;
		double seconds = elapsed / 1000.0;
		log.info("Request building complete: {} requests from {} persons in {}s (filtered {} by commute filter)",
				requests.size(), totalPersons, String.format("%.1f", seconds), filteredByCommute);

		return requests;
	}

	/**
	 * Build a DRT request from trip data.
	 * Extracted method to encapsulate request building logic.
	 * Uses BudgetValidator for consistent budget calculation methodology.
	 *
	 * @param ptMetrics PT accessibility metrics: [carTravelTime, ptTravelTime], or null if unavailable
	 */
	private DrtRequest buildRequest(
			int requestIndex, Person person, Trip trip, int tripIdx,
			String groupId, boolean isCommute, Entry<String, Double> bestBaselineMode,
			Map<String, ModeAttributes> modeAttrs, double[] ptMetrics) {

		String drtMode = exmasConfig.getDrtMode();
		ModeAttributes drtAttrs = modeAttrs.get(drtMode);

		Activity originActivity = trip.getOriginActivity();
		Activity destActivity = trip.getDestinationActivity();

		Coord originCoord = getCoord(originActivity);
		Coord destCoord = getCoord(destActivity);

		// Get link IDs (activities on links)
		Id<Link> originLinkId = getLinkId(originActivity);
		Id<Link> destinationLinkId = getLinkId(destActivity);

		// Skip trips with zero travel time/distance (same origin-destination)
		// These cause division by zero and NaN propagation in delay calculations
		if (drtAttrs.travelTime <= 0.0 || drtAttrs.distance <= 0.0) {
			log.warn(
					"Skipping request index {} (person: {}): zero travel time/distance (origin=destination or routing failure)",
					requestIndex, person.getId());
			return null;
		}

		double requestTime = trip.getOriginActivity().getEndTime().orElse(0.0);

		// Build temporary request to use BudgetValidator for budget calculation
		// This ensures consistent methodology between initial and ride validation
		DrtRequest tempRequest = DrtRequest.builder()
				.index(requestIndex)
				.personId(person.getId())
				.groupId(groupId)
				.tripIndex(tripIdx)
				.isCommute(isCommute)
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
				.maxDetourFactor(exmasConfig.getMaxDetourFactor())
				// Temporary placeholders for time windows (will be recalculated with actual
				// budget)
				.earliestDeparture(requestTime)
				.latestArrival(requestTime + drtAttrs.travelTime)
				.build();

		// Calculate budget using BudgetValidator for consistency
		double budget = budgetValidator.calculateBudget(tempRequest);

		// Calculate max detour factor as minimum of budget-derived and config limit
		// This determines the maximum acceptable trip duration (e.g., 1.5 means 50%
		// longer than direct)
		double budgetDerivedDetour = budgetToConstraintsCalculator.budgetToMaxDetourTime(
				budget, person, drtAttrs.travelTime, drtAttrs.distance);
		double configMaxDetour = drtAttrs.travelTime * (exmasConfig.getMaxDetourFactor() - 1.0);
		double maxAbsoluteDetour = Math.min(budgetDerivedDetour, configMaxDetour);
		
		// Apply absolute detour cap if configured
		if (exmasConfig.getMaxAbsoluteDetour() != null) {
			maxAbsoluteDetour = Math.min(maxAbsoluteDetour, exmasConfig.getMaxAbsoluteDetour());
		}
		
		double effectiveMaxDetourFactor = 1.0 + (maxAbsoluteDetour / drtAttrs.travelTime);

		// Flexibility controls WHEN someone can depart/arrive (temporal window)
		// This is INDEPENDENT from detour (which controls HOW LONG the trip can take)
		
		// Origin flexibility (Negative Flexibility): how much earlier/later can passenger depart?
		// Corresponds to max_negative_delay in Python
		double originFlex = flexibilityCalculator.calculateOriginFlexibility(person, trip.getOriginActivity(), maxAbsoluteDetour);

		// Destination flexibility (Positive Flexibility): how much earlier/later can passenger arrive?
		// Corresponds to max_positive_delay in Python
		double destFlex = flexibilityCalculator.calculateDestinationFlexibility(person, trip.getDestinationActivity(), maxAbsoluteDetour);

		// Time window calculation (matching Python reference implementation):
		// earliest_departure = treq - max_negative_delay (flexibility)
		// latest_departure = treq + max_positive_delay (flexibility)
		// earliest_arrival = earliest_departure + travel_time
		// latest_arrival = latest_departure + travel_time
		//
		// Note: max_travel_time is SEPARATE and equals directTravelTime *
		// maxDetourFactor

		double earliestDep = requestTime - originFlex;
		double latestDep = requestTime + destFlex;
		double latestArr = latestDep + drtAttrs.travelTime;

		// Calculate PT accessibility metrics
		// ptMetrics[0] = carTravelTime, ptMetrics[1] = ptTravelTime
		double carTravelTime = (ptMetrics != null && ptMetrics.length > 0) ? ptMetrics[0] : Double.NaN;
		double ptTravelTime = (ptMetrics != null && ptMetrics.length > 1) ? ptMetrics[1] : Double.NaN;

		// PT accessibility = carTravelTime / ptTravelTime
		// Higher value = PT more competitive (if car takes 30min and PT takes 30min, ratio = 1.0)
		// If PT is faster than car, ratio > 1.0 (PT is better)
		// If car is faster, ratio < 1.0 (car is better)
		double ptAccessibility = Double.NaN;
		if (Double.isFinite(carTravelTime) && Double.isFinite(ptTravelTime) && ptTravelTime > 0) {
			ptAccessibility = carTravelTime / ptTravelTime;
		}

		// Build final request with calculated budget and time windows
		return DrtRequest.builder()
				.index(requestIndex)
				.personId(person.getId())
				.groupId(groupId)
				.tripIndex(tripIdx)
				.isCommute(isCommute)
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
				.maxDetourFactor(effectiveMaxDetourFactor)
				.carTravelTime(carTravelTime)
				.ptTravelTime(ptTravelTime)
				.ptAccessibility(ptAccessibility)
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
