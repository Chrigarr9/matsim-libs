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

		// Trip-level spatial filter: only extract trips where both O and D are inside radius
		boolean hasSpatialFilter = exmasConfig.hasTripSpatialFilter();
		double spatialCenterX = 0, spatialCenterY = 0, spatialRadiusSq = 0;
		if (hasSpatialFilter) {
			spatialCenterX = exmasConfig.getTripFilterCenterX();
			spatialCenterY = exmasConfig.getTripFilterCenterY();
			double r = exmasConfig.getTripFilterRadiusKm() * 1000.0;
			spatialRadiusSq = r * r;
			log.info("Trip spatial filter: {}km around ({}, {})",
					exmasConfig.getTripFilterRadiusKm(), spatialCenterX, spatialCenterY);
		}

		List<DrtRequest> requests = new ArrayList<>();
		int filteredByCommute = 0;
		int filteredBySpatial = 0;

		int processedPersons = 0;
		int totalPersons = population.getPersons().size();
		int logInterval = Math.max(1, totalPersons / 10);

		// Sort persons by ID to ensure deterministic processing order
		// (parallel caching in ModeRoutingCache can complete in any order, but we want consistent output)
		List<Person> sortedPersons = new ArrayList<>(population.getPersons().values());
		sortedPersons.sort(java.util.Comparator.comparing(p -> p.getId().toString()));

		for (Person person : sortedPersons) {
			processedPersons++;

			// 1. Age Filter
			int age = getPersonAge(person);
			if (age >= 0 && age < exmasConfig.getMinAge()) {
				// Person too young
				continue;
			}

			// 2. DRT Availability Filter
			if (exmasConfig.getDrtAvailabilityAttribute() != null) {
				Object attr = person.getAttributes().getAttribute(exmasConfig.getDrtAvailabilityAttribute());
				boolean isAvailable = false;
				if (attr instanceof Boolean) {
					isAvailable = (Boolean) attr;
				} else if (attr instanceof String) {
					isAvailable = Boolean.parseBoolean((String) attr);
				}
				
				if (!isAvailable) {
					// DRT not available for this person
					continue;
				}
			}

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

				// Check commute/education status and apply filter
				boolean isCommute = commuteIdentifier.isCommute(person.getId(), tripIdx);
				boolean isEducation = commuteIdentifier.isEducation(person.getId(), tripIdx);

				if (commuteFilter == ExMasConfigGroup.CommuteFilter.COMMUTES_ONLY && !isCommute) {
					filteredByCommute++;
					continue;
				}
				if (commuteFilter == ExMasConfigGroup.CommuteFilter.COMMUTES_AND_EDUCATION && !isCommute && !isEducation) {
					filteredByCommute++;
					continue;
				}
				if (commuteFilter == ExMasConfigGroup.CommuteFilter.NON_COMMUTES && isCommute) {
					filteredByCommute++;
					continue;
				}

				// Trip-level spatial filter: skip if O or D is outside radius
				if (hasSpatialFilter) {
					Coord oCoord = trip.getOriginActivity().getCoord();
					Coord dCoord = trip.getDestinationActivity().getCoord();
					if (oCoord == null || dCoord == null) {
						filteredBySpatial++;
						continue;
					}
					double dxO = oCoord.getX() - spatialCenterX, dyO = oCoord.getY() - spatialCenterY;
					double dxD = dCoord.getX() - spatialCenterX, dyD = dCoord.getY() - spatialCenterY;
					if ((dxO * dxO + dyO * dyO) > spatialRadiusSq || (dxD * dxD + dyD * dyD) > spatialRadiusSq) {
						filteredBySpatial++;
						continue;
					}
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
		log.info("Request building complete: {} requests from {} persons in {}s (filtered {} by commute filter, {} by spatial filter)",
				requests.size(), totalPersons, String.format("%.1f", seconds), filteredByCommute, filteredBySpatial);

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

		// Get link IDs (activities on links)
		Id<Link> originLinkId = getLinkId(originActivity);
		Id<Link> destinationLinkId = getLinkId(destActivity);

		// IMPORTANT: Derive coordinates from link centroids, not from activities
		// This ensures coordinates are always consistent with the links used for routing
		// Activity coordinates may differ from their assigned link (e.g., facility location vs link centroid)
		Coord originCoord = network.getLinks().get(originLinkId).getCoord();
		Coord destCoord = network.getLinks().get(destinationLinkId).getCoord();

		// Calculate beeline distance between link centroids for validation
		double beelineDistance = org.matsim.core.utils.geometry.CoordUtils.calcEuclideanDistance(originCoord, destCoord);

		// Skip trips with zero travel time/distance (same origin-destination)
		// These cause division by zero and NaN propagation in delay calculations
		if (drtAttrs.travelTime() <= 0.0 || drtAttrs.distance() <= 0.0) {
			log.warn(
					"Skipping request index {} (person: {}): zero travel time/distance (origin=destination or routing failure)",
					requestIndex, person.getId());
			return null;
		}

		// Skip trips where origin and destination are on the same link but have different coordinates
		// These produce unrealistic routing results (router routes from link to itself through network)
		if (originLinkId.equals(destinationLinkId) && beelineDistance > 100.0) {
			log.warn(
					"Skipping request index {} (person: {}): same origin/destination link {} but different coordinates "
							+ "(beeline={}m). This produces unrealistic routing. Consider fixing activity link assignments.",
					requestIndex, person.getId(), originLinkId, String.format("%.0f", beelineDistance));
			return null;
		}

		// Validate routing result against beeline distance
		// Network distance should not be more than 5x beeline distance for realistic routes
		// (factor accounts for road network detours, but 50x+ indicates routing failure)
		double maxRealisticDistanceRatio = 5.0;
		if (beelineDistance > 100.0 && drtAttrs.distance() / beelineDistance > maxRealisticDistanceRatio) {
			log.warn(
					"Skipping request index {} (person: {}): unrealistic routing result. "
							+ "Beeline={}m but routed distance={}m (ratio={:.1f}x). "
							+ "Origin link: {}, Dest link: {}. This may indicate activities mapped to wrong links.",
					requestIndex, person.getId(),
					String.format("%.0f", beelineDistance), String.format("%.0f", drtAttrs.distance()),
					drtAttrs.distance() / beelineDistance, originLinkId, destinationLinkId);
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
				.directTravelTime(drtAttrs.travelTime())
				.directDistance(drtAttrs.distance())
				.maxDetourFactor(exmasConfig.getMaxDetourFactor())
				// Temporary placeholders for time windows (will be recalculated with actual
				// budget)
				.earliestDeparture(requestTime)
				.latestArrival(requestTime + drtAttrs.travelTime())
				.build();

		// Calculate budget using BudgetValidator for consistency
		double budget = budgetValidator.calculateBudget(tempRequest);

		// Skip requests with non-positive budget: DRT is not better than the best
		// alternative mode, so serving this passenger would never be beneficial.
		if (budget <= 0.0) {
			log.debug(
					"Skipping request index {} (person: {}): non-positive budget ({}) — "
							+ "DRT does not outperform best baseline mode ({})",
					requestIndex, person.getId(), String.format("%.2f", budget),
					bestBaselineMode.getKey());
			return null;
		}

		// Calculate max detour factor as minimum of budget-derived and config limit
		// This determines the maximum acceptable trip duration (e.g., 1.5 means 50%
		// longer than direct)
		double budgetDerivedDetour = budgetToConstraintsCalculator.budgetToMaxDetourTime(
				budget, person, drtAttrs.travelTime(), drtAttrs.distance(), tempRequest);
		double configMaxDetour = drtAttrs.travelTime() * (exmasConfig.getMaxDetourFactor() - 1.0);
		double maxAbsoluteDetour = Math.min(budgetDerivedDetour, configMaxDetour);
		
		// Apply absolute detour cap if configured
		if (exmasConfig.getMaxAbsoluteDetour() != null) {
			maxAbsoluteDetour = Math.min(maxAbsoluteDetour, (double) exmasConfig.getMaxAbsoluteDetour());
		}
		
		double effectiveMaxDetourFactor = 1.0 + (maxAbsoluteDetour / drtAttrs.travelTime());

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
		double latestArr = latestDep + drtAttrs.travelTime();

		// Calculate PT accessibility metrics
		// ptMetrics[0] = carTravelTime, ptMetrics[1] = ptTravelTime
		double carTravelTime = (ptMetrics != null && ptMetrics.length > 0) ? ptMetrics[0] : Double.NaN;
		double ptTravelTime = (ptMetrics != null && ptMetrics.length > 1) ? ptMetrics[1] : Double.NaN;

		// PT accessibility = ptTravelTime / carTravelTime
		// Higher value = PT is slower (worse accessibility)
		// Lower value = PT is faster (better accessibility)
		// Value of 1.0 = PT and car are equally fast
		double ptAccessibility = Double.NaN;
	if (Double.isFinite(carTravelTime) && Double.isFinite(ptTravelTime) && carTravelTime > 0) {
		ptAccessibility = ptTravelTime / carTravelTime;
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
				.originActivityType(originActivity.getType())
				.destinationActivityType(destActivity.getType())
				.requestTime(requestTime)
				.earliestDeparture(earliestDep)
				.latestArrival(latestArr)
				.directTravelTime(drtAttrs.travelTime())
				.directDistance(drtAttrs.distance())
				.maxDetourFactor(effectiveMaxDetourFactor)
				.carTravelTime(carTravelTime)
				.ptTravelTime(ptTravelTime)
				.ptAccessibility(ptAccessibility)
				.build();
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

	private int getPersonAge(Person person) {
		Object ageAttr = person.getAttributes().getAttribute("age");
		if (ageAttr instanceof Integer) {
			return (Integer) ageAttr;
		} else if (ageAttr instanceof String) {
			try {
				return Integer.parseInt((String) ageAttr);
			} catch (NumberFormatException e) {
				// ignore
			}
		}
		return -1; // Age unknown
	}
}
