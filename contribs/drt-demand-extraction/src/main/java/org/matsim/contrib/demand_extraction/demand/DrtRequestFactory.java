package org.matsim.contrib.demand_extraction.demand;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Predicate;

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
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup.FleetSide;
import org.matsim.core.network.NetworkUtils;
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

	/**
	 * Paper-2 Extension 2: lazily-loaded request-classification lookup. Populated
	 * once on the first {@link #buildRequests(Population)} call when
	 * {@link ExMasConfigGroup#getRequestClassificationsPath()} is non-null;
	 * stays null in the Kelheim path where no classification CSV is configured.
	 * Not injected — the CSV may live anywhere on disk and may not exist at
	 * injector-construction time.
	 */
	private volatile RequestClassificationLoader requestClassificationLoader;

	/**
	 * Paper-2 Extension 2: lazily-loaded hub set for virtual-trip expansion.
	 * Populated once on the first {@link #buildRequests(Population)} call when
	 * {@link ExMasConfigGroup#getHubSetGeoJsonPath()} is non-null; stays null
	 * in the Kelheim path where no hub set is configured. Loaded from disk
	 * here rather than via Guice because the GeoJSON may live anywhere on disk
	 * and may not exist at injector-construction time.
	 */
	private volatile List<HubSetLoader.Hub> hubs;

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

		// Paper-2 Extension 2: load request classifications once, lazily.
		// Path null = preserve Kelheim default (DrtRequest.requestTag stays null).
		String classificationsPath = exmasConfig.getRequestClassificationsPath();
		if (classificationsPath != null && requestClassificationLoader == null) {
			try {
				requestClassificationLoader = new RequestClassificationLoader(Path.of(classificationsPath));
				log.info("Loaded {} request classifications from {}",
						requestClassificationLoader.size(), classificationsPath);
			} catch (IOException e) {
				throw new RuntimeException(
						"Failed to load request classifications from " + classificationsPath, e);
			}
		}

		// Paper-2 Extension 2: load hub set once, lazily, when virtual-trip
		// expansion is configured. Both hubSetGeoJsonPath AND fleetSide must
		// be set to enable expansion; either being null disables it. If a hub
		// set is configured but fleetSide is missing, fail fast — that's a
		// misconfiguration that would silently drop the entire connecting
		// cohort downstream.
		String hubSetPath = exmasConfig.getHubSetGeoJsonPath();
		FleetSide fleetSide = exmasConfig.getFleetSide();
		if (hubSetPath != null && fleetSide == null) {
			throw new IllegalStateException(
					"ExMasConfigGroup.hubSetGeoJsonPath is set (" + hubSetPath
					+ ") but fleetSide is null. Both must be configured together to "
					+ "enable virtual-trip expansion. Set ExMasConfigGroup.fleetSide "
					+ "to RURAL or URBAN, or clear hubSetGeoJsonPath to disable "
					+ "expansion.");
		}
		if (hubSetPath != null && hubs == null) {
			try {
				hubs = new HubSetLoader().load(Path.of(hubSetPath));
				log.info("Loaded {} hubs from {} (fleetSide={})",
						hubs.size(), hubSetPath, fleetSide);
			} catch (IOException e) {
				throw new RuntimeException(
						"Failed to load hub set from " + hubSetPath, e);
			}
		}

		// Metropole-polygon source for virtual-trip expansion: a coord is "inside
		// the metropole" iff TripSpatialPreFilter.containsPoint returns true.
		// Prefer the dedicated metropolePolygonPath (URBAN run: keeps urban_intra
		// because no exclusion zone is set, yet still has the metropole geometry
		// for endpoint detection); fall back to the exclusion polygon (RURAL run /
		// pre-Extension-2 config, where the exclusion polygon IS the metropole).
		TripSpatialPreFilter expansionMetropoleSource = null;
		if (hubs != null) {
			String metropolePath = exmasConfig.hasMetropolePolygon()
					? exmasConfig.getMetropolePolygonPath()
					: (exmasConfig.hasTripExclusionZone()
							? exmasConfig.getTripFilterExclusionShapefilePath()
							: null);
			if (metropolePath != null) {
				expansionMetropoleSource = TripSpatialPreFilter.forPolygonFile(metropolePath);
			} else {
				log.warn("Virtual-trip expansion is enabled (hubSetGeoJsonPath={}) but no "
						+ "metropolePolygonPath or tripFilterExclusionShapefilePath is "
						+ "configured. The metropole polygon is required to identify which "
						+ "endpoint of a connecting request is urban. Falling back to: "
						+ "'destination is always urban'.",
						hubSetPath);
			}
		}

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
		int filteredByExternalTag = 0;
		int filteredByOffFleetTag = 0;

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

				// Paper-2 Extension 2: drop "external" trips pre-construction.
				// These were classified by the Phase-2 Python pass as falling
				// entirely outside the rural+metropole study area; emitting them
				// would only pollute the request list. Only applies when a
				// classifications CSV is loaded; without it the tag is null and
				// the trip passes through unchanged (Kelheim default).
				if (requestClassificationLoader != null) {
					String tagPreCheck = requestClassificationLoader.lookup(
							person.getId().toString(), tripIdx);
					if ("external".equals(tagPreCheck)) {
						filteredByExternalTag++;
						continue;
					}
					// Paper-2 Extension 2: drop the OTHER fleet's internal trips.
					// In the RURAL run urban_intra is dropped; in the URBAN run
					// rural_intra is dropped. This is what makes the two-run
					// partition exact (connecting is kept + expanded below;
					// fleetSide == null leaves everything in place, Kelheim path).
					if (isOffFleetTag(tagPreCheck, fleetSide)) {
						filteredByOffFleetTag++;
						continue;
					}
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

		// Paper-2 Extension 2 — virtual-trip expansion (post-construction pass).
		// For each request tagged "connecting", emit |H| copies (one per hub)
		// with the cross-boundary endpoint replaced by the hub coord. Other
		// tags (rural_intra, urban_intra, null) pass through unchanged. Skipped
		// entirely when hubs == null (Kelheim default and any pre-Extension-2
		// path), preserving prior request-list contents exactly.
		int connectingExpanded = 0;
		int virtualEmitted = 0;
		if (hubs != null && fleetSide != null) {
			Predicate<Coord> isInsideMetropole = (expansionMetropoleSource != null)
					? expansionMetropoleSource::containsPoint
					: c -> false;

			double transferBuffer = exmasConfig.getHubTransferBufferSeconds();

			List<DrtRequest> expanded = new ArrayList<>(requests.size());
			for (DrtRequest r : requests) {
				if ("connecting".equals(r.requestTag)) {
					Person person = r.getScoringContext().person();
					LegRouter router = (from, to, dep) ->
							modeRoutingCache.routeDrtOd(person, from, to, dep);
					List<DrtRequest> copies = expandConnecting(
							r, hubs, fleetSide, isInsideMetropole, network, router,
							transferBuffer);
					expanded.addAll(copies);
					connectingExpanded++;
					virtualEmitted += copies.size();
				} else {
					expanded.add(r);
				}
			}
			requests = expanded;
			log.info("Virtual-trip expansion: expanded {} connecting request(s) into {} "
					+ "virtual DrtRequest(s) (|H|={}, fleetSide={})",
					connectingExpanded, virtualEmitted, hubs.size(), fleetSide);
		}

		long elapsed = System.currentTimeMillis() - startTime;
		double seconds = elapsed / 1000.0;
		log.info("Request building complete: {} requests from {} persons in {}s "
				+ "(filtered {} by commute filter, {} by spatial/exclusion-zone filter, "
				+ "{} by excluded mode, {} by external tag, {} by off-fleet tag)",
				requests.size(), totalPersons, String.format("%.1f", seconds),
				filteredByCommute, filteredBySpatial, filteredByExcludedMode,
				filteredByExternalTag, filteredByOffFleetTag);

		return requests;
	}

	/**
	 * Paper-2 Extension 2: returns true if a request carrying {@code tag} belongs
	 * to the OTHER fleet and must be dropped. With {@code fleetSide == RURAL} the
	 * urban-internal trips ({@code urban_intra}) are dropped; with {@code URBAN}
	 * the rural-internal trips ({@code rural_intra}) are dropped. {@code connecting}
	 * is never dropped here (it is expanded afterwards), and {@code external} is
	 * handled by its own pre-drop. When {@code fleetSide == null} (Kelheim /
	 * Paper-1 path) or {@code tag == null}, nothing is dropped.
	 */
	static boolean isOffFleetTag(String tag, ExMasConfigGroup.FleetSide fleetSide) {
		if (fleetSide == null || tag == null) {
			return false;
		}
		return switch (fleetSide) {
			case RURAL -> "urban_intra".equals(tag);
			case URBAN -> "rural_intra".equals(tag);
		};
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

		// Paper-2 Extension 2: per-trip classification tag (null when no
		// classifications CSV is configured OR this (person, tripIdx) pair was
		// absent from the CSV — both leave DrtRequest.requestTag = null).
		String requestTag = (requestClassificationLoader == null)
				? null
				: requestClassificationLoader.lookup(person.getId().toString(), tripIdx);

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
				.requestTag(requestTag)
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

	/** Routes one virtual-leg OD pair. @return {travelTime_s, distance_m}, or
	 *  null if unreachable. Production impl: {@link ModeRoutingCache#routeDrtOd};
	 *  unit tests inject a fake. */
	@FunctionalInterface
	interface LegRouter {
		double[] route(Id<Link> fromLink, Id<Link> toLink, double departureTime);
	}

	/**
	 * Paper-2 Extension 2 — virtual-trip expansion for one {@code connecting}
	 * request. Returns {@code |hubs|} copies of {@code original}, each with
	 * one endpoint replaced by a hub coordinate (which endpoint depends on
	 * {@code fleetSide}):
	 *
	 * <ul>
	 *   <li>{@link FleetSide#RURAL} — the URBAN endpoint (the one inside the
	 *       metropole polygon) is replaced. The rural fleet sees a rural-to-hub
	 *       trip.</li>
	 *   <li>{@link FleetSide#URBAN} — the RURAL endpoint is replaced. The
	 *       urban fleet sees a hub-to-urban trip.</li>
	 * </ul>
	 *
	 * <p>For non-connecting tags ({@code rural_intra}, {@code urban_intra},
	 * {@code null}) this returns the single-element list {@code [original]}.
	 *
	 * <p>The replaced endpoint's {@code linkId} is snapped to the nearest
	 * network link to the hub via {@link NetworkUtils#getNearestLink}; the
	 * link-corner coordinates are recomputed off that new link.
	 *
	 * <p>Paper-2 Ext-2 (Task 8): each virtual copy carries its OWN leg's routed
	 * {@code directTravelTime} / {@code directDistance} (from {@code legRouter}),
	 * not the inherited full-trip values. The journey orientation is
	 * {@code ruralEnd -> hub -> urbanEnd}; both legs are routed so the off-fleet
	 * leg's direct time drives a temporal split:
	 * <ul>
	 *   <li>{@link FleetSide#RURAL} ACCESS leg ({@code O->hub}): departs at
	 *       {@code requestTime}; {@code latestArrival} is backed out as
	 *       {@code original.latestArrival - urbanLeg_tt - buffer} so the
	 *       continuation + transfer still make the full-trip deadline. Role
	 *       {@code ACCESS_LEG}, {@code transferWaitSeconds = 0}.</li>
	 *   <li>{@link FleetSide#URBAN} CONTINUATION leg ({@code hub->D}): shifted to
	 *       {@code requestTime + ruralLeg_tt + buffer} (and
	 *       {@code earliestDeparture} likewise), keeping the full-trip
	 *       {@code latestArrival}. Role {@code CONTINUATION_LEG},
	 *       {@code transferWaitSeconds = buffer}.</li>
	 * </ul>
	 * Hubs whose routing fails (null / non-positive leg) or that do not fit the
	 * traveller's time envelope are dropped; the caller logs the drop via
	 * {@code copies.size()}.
	 *
	 * <p>The {@link ScoringContext} from {@code original} is reused on every copy
	 * as a placeholder; Task 10 rebuilds it per copy off the new per-leg
	 * direct metrics and split window.
	 *
	 * <p>If neither endpoint is identified as inside the metropole by
	 * {@code isInsideMetropole}, the fallback is to treat the DESTINATION as
	 * urban (i.e. rural→urban orientation). This keeps the helper total even
	 * when the configured polygon is missing or doesn't cover the request.
	 *
	 * <p>Package-private + static so unit tests can drive it without a
	 * Controler-built injector.
	 */
	static List<DrtRequest> expandConnecting(
			DrtRequest original,
			List<HubSetLoader.Hub> hubs,
			FleetSide fleetSide,
			Predicate<Coord> isInsideMetropole,
			Network network,
			LegRouter legRouter,
			double transferBufferSeconds) {
		if (!"connecting".equals(original.requestTag)) {
			return List.of(original);
		}
		if (hubs == null || hubs.isEmpty()) {
			return List.of(original);
		}

		// Identify which endpoint is urban (inside metropole) and which is
		// rural. The fallback (when neither tests inside) treats the
		// destination as urban — this matches the dominant home-to-work
		// orientation in the rural-to-urban connecting cohort.
		Coord originCoord = new Coord(original.originX, original.originY);
		Coord destCoord = new Coord(original.destinationX, original.destinationY);
		boolean originIsUrban = isInsideMetropole.test(originCoord);
		boolean destIsUrban = isInsideMetropole.test(destCoord);
		boolean replaceOrigin;
		if (fleetSide == FleetSide.RURAL) {
			// Rural fleet sees rural-to-hub: replace the urban endpoint.
			if (originIsUrban && !destIsUrban) {
				replaceOrigin = true;     // origin was urban, becomes hub
			} else {
				replaceOrigin = false;    // default: destination was urban
			}
		} else { // URBAN
			// Urban fleet sees hub-to-urban: replace the rural endpoint.
			if (destIsUrban && !originIsUrban) {
				replaceOrigin = true;     // origin was rural, becomes hub
			} else if (!destIsUrban && originIsUrban) {
				replaceOrigin = false;    // destination was rural, becomes hub
			} else {
				// Fallback: assume origin is rural (mirrors the
				// rural-fleet fallback orientation).
				replaceOrigin = true;
			}
		}

		// Endpoint links in journey orientation: rural end -> hub -> urban end.
		Id<Link> ruralEndLink = replaceOrigin ? original.destinationLinkId : original.originLinkId;
		Id<Link> urbanEndLink = replaceOrigin ? original.originLinkId : original.destinationLinkId;

		List<DrtRequest> copies = new ArrayList<>(hubs.size());
		for (HubSetLoader.Hub hub : hubs) {
			Coord hubCoord = hub.coord();
			Link hubLink = NetworkUtils.getNearestLink(network, hubCoord);
			Id<Link> hubLinkId = hubLink.getId();

			// Route BOTH legs regardless of fleetSide: the off-fleet leg's direct
			// time drives the temporal split (urban shift / rural deadline).
			double[] ruralLeg = legRouter.route(ruralEndLink, hubLinkId, original.requestTime);
			if (ruralLeg == null || ruralLeg[0] <= 0.0 || ruralLeg[1] <= 0.0) continue;
			double[] urbanLeg = legRouter.route(hubLinkId, urbanEndLink,
					original.requestTime + ruralLeg[0] + transferBufferSeconds);
			if (urbanLeg == null || urbanLeg[0] <= 0.0 || urbanLeg[1] <= 0.0) continue;

			DrtRequest.Builder b = original.toBuilder().hubId(hub.id());
			Coord hubLinkFrom = hubLink.getFromNode().getCoord();
			Coord hubLinkTo = hubLink.getToNode().getCoord();
			if (replaceOrigin) {
				b.originLinkId(hubLinkId)
				 .originX(hubCoord.getX()).originY(hubCoord.getY())
				 .originLinkCoordFromX(hubLinkFrom.getX()).originLinkCoordFromY(hubLinkFrom.getY())
				 .originLinkCoordToX(hubLinkTo.getX()).originLinkCoordToY(hubLinkTo.getY());
			} else {
				b.destinationLinkId(hubLinkId)
				 .destinationX(hubCoord.getX()).destinationY(hubCoord.getY())
				 .destinationLinkCoordFromX(hubLinkFrom.getX()).destinationLinkCoordFromY(hubLinkFrom.getY())
				 .destinationLinkCoordToX(hubLinkTo.getX()).destinationLinkCoordToY(hubLinkTo.getY());
			}

			if (fleetSide == FleetSide.RURAL) {
				// ACCESS leg O->hub: departs as originally requested; must deliver
				// early enough for the urban leg + transfer to still make the
				// traveller's full-trip deadline.
				double legLatestArrival = original.latestArrival - urbanLeg[0] - transferBufferSeconds;
				if (original.requestTime + ruralLeg[0] > legLatestArrival) continue; // hub doesn't fit
				b.directTravelTime(ruralLeg[0]).directDistance(ruralLeg[1])
				 .latestArrival(legLatestArrival)
				 .hubLegRole(DrtRequest.HubLegRole.ACCESS_LEG)
				 .transferWaitSeconds(0.0);
			} else {
				// CONTINUATION leg hub->D: scheduled after the rural leg's direct
				// arrival plus the transfer buffer; keeps the full-trip deadline.
				double shift = ruralLeg[0] + transferBufferSeconds;
				if (original.requestTime + shift + urbanLeg[0] > original.latestArrival) continue;
				b.directTravelTime(urbanLeg[0]).directDistance(urbanLeg[1])
				 .requestTime(original.requestTime + shift)
				 .earliestDeparture(original.earliestDeparture + shift)
				 .hubLegRole(DrtRequest.HubLegRole.CONTINUATION_LEG)
				 .transferWaitSeconds(transferBufferSeconds);
			}

			DrtRequest copy = b.build();
			copy.setScoringContext(original.getScoringContext()); // rebuilt in Task 10
			copies.add(copy);
		}
		return copies;
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
