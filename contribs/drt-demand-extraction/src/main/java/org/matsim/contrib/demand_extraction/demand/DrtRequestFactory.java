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
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Population;
import java.util.Set;
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

		// Plan-mode exclusion filter (e.g., drop trips whose original plan mode is
		// "car_passenger" — their eqasim IDF score is a ZeroUtilityEstimator stub
		// and the comparison to DRT is meaningless). Default empty = no filter.
		Set<String> excludedTripModes = exmasConfig.getExcludedTripModes();
		boolean hasModeFilter = !excludedTripModes.isEmpty();
		if (hasModeFilter) {
			log.info("Excluded trip modes: {}", excludedTripModes);
		}

		TripSpatialPreFilter spatialFilter = new TripSpatialPreFilter(exmasConfig);

		List<DrtRequest> requests = new ArrayList<>();
		int filteredByCommute = 0;
		int filteredBySpatial = 0;
		int filteredByExcludedMode = 0;

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

				// Plan-mode exclusion: drop the trip if its original plan leg is
				// in the excluded set (e.g., car_passenger with a stub estimator).
				if (hasModeFilter && isTripInExcludedMode(trip, excludedTripModes)) {
					filteredByExcludedMode++;
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

				// Trip-level spatial filter: skip if O or D is outside radius, or both inside exclusion zone
				if (spatialFilter.isActive()) {
					Coord oCoord = trip.getOriginActivity().getCoord();
					Coord dCoord = trip.getDestinationActivity().getCoord();
					if (!spatialFilter.isTripEligible(oCoord, dCoord)) {
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
		log.info("Request building complete: {} requests from {} persons in {}s "
				+ "(filtered {} by commute filter, {} by spatial/exclusion-zone filter, {} by excluded mode)",
				requests.size(), totalPersons, String.format("%.1f", seconds),
				filteredByCommute, filteredBySpatial, filteredByExcludedMode);

		return requests;
	}

	/**
	 * True when any non-interaction leg in the trip has a mode in {@code excluded}.
	 * Access/egress walk legs bracketing pt/drt/bike rides are ignored — the
	 * trip's "main" mode is the one that's not walk. For pure car_passenger
	 * trips, all legs have that mode so the check catches them.
	 */
	private static boolean isTripInExcludedMode(Trip trip, Set<String> excluded) {
		for (PlanElement pe : trip.getTripElements()) {
			if (pe instanceof Leg leg) {
				String mode = leg.getMode();
				if (mode != null && excluded.contains(mode)) {
					return true;
				}
			}
		}
		return false;
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
		org.matsim.api.core.v01.network.Link originLink = network.getLinks().get(originLinkId);
		org.matsim.api.core.v01.network.Link destLink = network.getLinks().get(destinationLinkId);
		Coord originCoord = originLink.getCoord();
		Coord destCoord = destLink.getCoord();
		// Node coordinates used by the link-based router: routing goes from
		// fromLink.toNode to toLink.fromNode (see LeastCostPathCalculator
		// default method). Storing these alongside the centroids lets
		// OrderingEnumerator.computeMinIn compute an admissible beeline LB
		// between the actual routing endpoints rather than between midpoints.
		Coord originLinkFrom = originLink.getFromNode().getCoord();
		Coord originLinkTo = originLink.getToNode().getCoord();
		Coord destLinkFrom = destLink.getFromNode().getCoord();
		Coord destLinkTo = destLink.getToNode().getCoord();

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
							+ "Beeline={}m but routed distance={}m (ratio={}x). "
							+ "Origin link: {}, Dest link: {}. This may indicate activities mapped to wrong links.",
					requestIndex, person.getId(),
					String.format("%.0f", beelineDistance), String.format("%.0f", drtAttrs.distance()),
					String.format("%.1f", drtAttrs.distance() / beelineDistance), originLinkId, destinationLinkId);
			return null;
		}

		double requestTime = trip.getOriginActivity().getEndTime().orElse(0.0);

		// PT accessibility = ptTravelTime / carTravelTime (higher = PT slower, 1.0 = parity)
		double carTravelTime = (ptMetrics != null && ptMetrics.length > 0) ? ptMetrics[0] : Double.NaN;
		double ptTravelTime = (ptMetrics != null && ptMetrics.length > 1) ? ptMetrics[1] : Double.NaN;
		double ptAccessibility = (Double.isFinite(carTravelTime) && Double.isFinite(ptTravelTime) && carTravelTime > 0)
				? ptTravelTime / carTravelTime
				: Double.NaN;

		// Build a draft request with every field that doesn't depend on the budget.
		// Budget, time windows, and maxDetourFactor are placeholders, refined below
		// once the binary search runs over this draft.
		DrtRequest draft = DrtRequest.builder()
				.index(requestIndex)
				.personId(person.getId())
				.groupId(groupId)
				.tripIndex(tripIdx)
				.isCommute(isCommute)
				.bestModeScore(bestBaselineMode.getValue())
				.bestMode(bestBaselineMode.getKey())
				.originLinkId(originLinkId)
				.destinationLinkId(destinationLinkId)
				.originX(originCoord.getX())
				.originY(originCoord.getY())
				.destinationX(destCoord.getX())
				.destinationY(destCoord.getY())
				.originLinkCoordFromX(originLinkFrom.getX())
				.originLinkCoordFromY(originLinkFrom.getY())
				.originLinkCoordToX(originLinkTo.getX())
				.originLinkCoordToY(originLinkTo.getY())
				.destinationLinkCoordFromX(destLinkFrom.getX())
				.destinationLinkCoordFromY(destLinkFrom.getY())
				.destinationLinkCoordToX(destLinkTo.getX())
				.destinationLinkCoordToY(destLinkTo.getY())
				.originActivityType(originActivity.getType())
				.destinationActivityType(destActivity.getType())
				.requestTime(requestTime)
				.directTravelTime(drtAttrs.travelTime())
				.directDistance(drtAttrs.distance())
				.carTravelTime(carTravelTime)
				.ptTravelTime(ptTravelTime)
				.ptAccessibility(ptAccessibility)
				.budget(0.0)
				.earliestDeparture(requestTime)
				.latestArrival(requestTime + drtAttrs.travelTime())
				.maxDetourFactor(exmasConfig.getMaxDetourFactor())
				.build();

		draft.setScoringContext(budgetValidator.computeScoringContext(draft, person));

		double budget = budgetValidator.calculateBudget(draft);
		if (budget <= 0.0) {
			log.debug(
					"Skipping request index {} (person: {}): non-positive budget ({}) — "
							+ "DRT does not outperform best baseline mode ({})",
					requestIndex, person.getId(), String.format("%.2f", budget),
					bestBaselineMode.getKey());
			return null;
		}

		// maxDetourFactor = min(budget-derived detour, config cap, absolute cap if configured)
		double budgetDerivedDetour = budgetToConstraintsCalculator.budgetToMaxDetourTime(
				budget, person, drtAttrs.travelTime(), drtAttrs.distance(), draft);
		double configMaxDetour = drtAttrs.travelTime() * (exmasConfig.getMaxDetourFactor() - 1.0);
		double maxAbsoluteDetour = Math.min(budgetDerivedDetour, configMaxDetour);
		if (exmasConfig.getMaxAbsoluteDetour() != null) {
			maxAbsoluteDetour = Math.min(maxAbsoluteDetour, (double) exmasConfig.getMaxAbsoluteDetour());
		}
		double effectiveMaxDetourFactor = 1.0 + (maxAbsoluteDetour / drtAttrs.travelTime());

		// maxWalkDistance — ideal-DRT walk cap derived from the person's remaining budget.
		// Gated on enableBudgetAwareConstraints; flag off leaves the field at 0.0 (current behaviour).
		double budgetDerivedMaxWalk = exmasConfig.isEnableBudgetAwareConstraints()
				? budgetToConstraintsCalculator.budgetToMaxWalkDistance(budget, person, draft)
				: 0.0;

		// maxWaitTime — ideal-DRT wait cap derived from the person's remaining budget.
		// Gated on enableBudgetAwareConstraints; flag off leaves the field at 0.0 (current behaviour).
		double budgetDerivedMaxWait = exmasConfig.isEnableBudgetAwareConstraints()
				? budgetToConstraintsCalculator.budgetToMaxWaitingTime(budget, person, draft)
				: 0.0;

		// Temporal flexibility (departure/arrival windows) — independent from detour.
		double originFlex = flexibilityCalculator.calculateOriginFlexibility(person, trip.getOriginActivity(), maxAbsoluteDetour);
		double destFlex = flexibilityCalculator.calculateDestinationFlexibility(person, trip.getDestinationActivity(), maxAbsoluteDetour);
		double earliestDep = requestTime - originFlex;
		double latestArr = requestTime + destFlex + drtAttrs.travelTime();

		DrtRequest finalRequest = draft.toBuilder()
				.budget(budget)
				.earliestDeparture(earliestDep)
				.latestArrival(latestArr)
				.maxDetourFactor(effectiveMaxDetourFactor)
				.maxWalkDistance(budgetDerivedMaxWalk)
				.maxWaitTime(budgetDerivedMaxWait)
				.build();
		finalRequest.setScoringContext(draft.getScoringContext());
		return finalRequest;
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
