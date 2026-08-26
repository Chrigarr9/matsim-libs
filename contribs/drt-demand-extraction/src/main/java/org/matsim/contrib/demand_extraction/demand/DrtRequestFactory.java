package org.matsim.contrib.demand_extraction.demand;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
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

	/**
	 * Paper-2 Extension 2: drop/detour diagnostics from the most recent
	 * virtual-trip expansion (null until {@link #buildRequests} runs an
	 * expansion). The listener writes these to a per-(commuter, hub) diagnostic
	 * CSV. Not part of the request list so it stays out of the hot path.
	 */
	private volatile ExpansionDropStats lastExpansionDropStats;

	/** @return the last expansion's drop/detour stats, or null if none ran. */
	public ExpansionDropStats getLastExpansionDropStats() {
		return lastExpansionDropStats;
	}

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
		// Paper-2 merged run: when both-sides expansion is on, fleetSide MAY be
		// null (the hub fan-out emits BOTH leg sides per hub via two internal
		// expandConnecting calls, off-fleet drop is a no-op, both intra zones
		// are kept) OR non-null (Task 6, 2026-08-25 plan revised 2026-08-26:
		// fleetSide=RURAL together with bothSides=true reuses the existing
		// off-fleet tag-drop below to remove urban_intra from the merged run,
		// while both-sides expansion below keys on the bothSides flag alone --
		// see the "sides" selection in applyVirtualExpansion, which ignores
		// fleetSide entirely once bothSides is true).
		boolean bothSides = exmasConfig.isExpandConnectingBothSides();
		if (hubSetPath != null && fleetSide == null && !bothSides) {
			throw new IllegalStateException(
					"ExMasConfigGroup.hubSetGeoJsonPath is set (" + hubSetPath
					+ ") but fleetSide is null and expandConnectingBothSides is false. "
					+ "Virtual-trip expansion needs either a fleetSide (RURAL or URBAN, "
					+ "single-side) OR expandConnectingBothSides=true (merged both-sides "
					+ "run). Set one of those, or clear hubSetGeoJsonPath to disable "
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
					// Task 6 (2026-08-25 plan): also reused, unmodified, by the merged
					// bothSides=true run with fleetSide=RURAL to drop urban_intra
					// wholesale while keeping rural_intra and both-sides connecting
					// expansion intact.
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

				// Get group ID for this trip. D4' (Paper-2, 2026-08-25 plan Task 5):
				// with spontaneousSingletonChains ON, a non-mandatory (non-commute,
				// non-education) trip never joins a subtour chain group -- it is
				// booked ad hoc, leg by leg, and carries no day-ahead return
				// guarantee. Flag OFF (default) is byte-identical to today.
				String groupId = resolveGroupId(tripToGroupId, tripIdx, person.getId().toString(),
						exmasConfig.isSpontaneousSingletonChains(), isCommute, isEducation);

				// Get PT accessibility metrics for this trip
				Map<Integer, double[]> personPtMetrics = ptAccessibilityMetrics.get(person.getId());
				double[] ptMetrics = (personPtMetrics != null) ? personPtMetrics.get(tripIdx) : null;

				DrtRequest request = buildRequest(
						requests.size(), person, trip, tripIdx, groupId, isCommute, isEducation, bestBaselineMode, modeAttrs, ptMetrics);

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
		// with the cross-boundary endpoint replaced by the hub coord, plus the
		// original O→D request retagged "connecting-direct". Other tags
		// (rural_intra, urban_intra, null) pass through unchanged. Skipped
		// entirely when hubs == null (Kelheim default and any pre-Extension-2
		// path), preserving prior request-list contents exactly.
		if (hubs != null && (fleetSide != null || bothSides)) {
			Predicate<Coord> isInsideMetropole = (expansionMetropoleSource != null)
					? expansionMetropoleSource::containsPoint
					: c -> false;

			double transferBuffer = exmasConfig.getHubTransferBufferSeconds();
			double maxHubWait = exmasConfig.getMaxHubWaitSeconds();
			boolean hubSyncTwoSided = exmasConfig.isHubSyncTwoSided();
			double hubSyncMaxAdvance = exmasConfig.getHubSyncMaxAdvanceSeconds();
			java.util.function.Function<Person, LegRouter> routerFactory =
					person -> (from, to, dep) -> modeRoutingCache.routeDrtOd(person, from, to, dep);

			ExpansionResult result = applyVirtualExpansion(
					requests, hubs, fleetSide, isInsideMetropole, transferBuffer, maxHubWait,
					hubSyncTwoSided, hubSyncMaxAdvance,
					budgetValidator, routerFactory, bothSides);
			requests = result.requests();
			this.lastExpansionDropStats = result.dropStats();
			log.info("Virtual-trip expansion: {} connecting -> {} virtual legs "
					+ "(|H|={}, fleetSide={}, dropped {} at expansion [{} unroutable rural-leg, "
					+ "{} unroutable urban-leg, {} temporal-infeasible, {} dropped-by-topK], "
					+ "{} non-positive leg budget)",
					result.connectingExpanded(), result.virtualEmitted(), hubs.size(),
					bothSides ? "BOTH" : fleetSide,
					result.virtualDroppedExpansion(), result.dropStats().unroutableRuralLeg,
					result.dropStats().unroutableUrbanLeg, result.dropStats().temporalInfeasible,
					result.dropStats().droppedByTopK,
					result.virtualDroppedBudget());
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
	 * Budget-derived service caps, shared by {@link #buildRequest} and virtual-leg
	 * finalization ({@link #finalizeVirtualLeg}). Returns
	 * {@code {maxAbsoluteDetour, maxWalk, maxWait}}.
	 *
	 * <p>{@code draft} supplies the {@code directTravelTime} and
	 * {@code directDistance} used as the denominator for the detour fraction
	 * and as inputs to the constraint calculators. For normal requests these
	 * are the full O→D direct attributes; for virtual hub-leg copies they are
	 * the per-leg routed attributes (already written onto the copy before this
	 * is called).
	 */
	/**
	 * Class-factor lookup, most-specific key first (EXT-4). Static for testability.
	 *
	 * <p>Resolution order: role-specific key {@code "<tag>:<ROLE>"} (e.g.
	 * {@code connecting:ACCESS_LEG}) → bare {@code "<tag>"} → global
	 * {@code globalFactor}. The role-specific key is only consulted when the role
	 * is non-{@link DrtRequest.HubLegRole#NONE}, so bare-tag / global lookups stay
	 * byte-identical to the previous {@code getOrDefault(tag, global)} semantics
	 * when no {@code tag:ROLE} keys are configured.
	 */
	static double resolveClassFactor(java.util.Map<String, Double> byClass,
			double globalFactor, String requestTag, DrtRequest.HubLegRole role) {
		if (requestTag != null && role != DrtRequest.HubLegRole.NONE) {
			Double roleSpecific = byClass.get(requestTag + ":" + role.name());
			if (roleSpecific != null) return roleSpecific;
		}
		if (requestTag != null) {
			Double tagLevel = byClass.get(requestTag);
			if (tagLevel != null) return tagLevel;
		}
		return globalFactor;
	}

	/**
	 * D4' (Paper-2, 2026-08-25 plan Task 5): group-ID resolution for a single trip.
	 * Static for testability (mirrors {@link #resolveClassFactor}).
	 *
	 * <p>With {@code spontaneousSingletonChains} ON, a non-mandatory trip (neither
	 * commute nor education) never joins its {@link ChainIdentifier}-assigned subtour
	 * group: it always gets the singleton fallback form
	 * {@code personId + "_trip_" + tripIdx}, matching the existing no-chain fallback —
	 * spontaneous trips are booked ad hoc, leg by leg, and carry no day-ahead return
	 * guarantee. Flag OFF (default), or a commute/education trip regardless of the
	 * flag, keeps the {@link ChainIdentifier} lookup (byte-identical to before this
	 * change).
	 */
	static String resolveGroupId(Map<Integer, String> tripToGroupId, int tripIdx,
			String personId, boolean spontaneousSingletonChains, boolean isCommute, boolean isEducation) {
		String fallbackGroupId = personId + "_trip_" + tripIdx;
		if (spontaneousSingletonChains && !isCommute && !isEducation) {
			return fallbackGroupId;
		}
		return tripToGroupId.getOrDefault(tripIdx, fallbackGroupId);
	}

	double[] budgetDerivedCaps(double budget, Person person, DrtRequest draft) {
		double budgetDerivedDetour = budgetToConstraintsCalculator.budgetToMaxDetourTime(
				budget, person, draft.directTravelTime, draft.directDistance, draft);
		double classFactor = resolveClassFactor(exmasConfig.getMaxDetourFactorByClass(),
				exmasConfig.getMaxDetourFactor(), draft.requestTag, draft.hubLegRole);
		double configMaxDetour = draft.directTravelTime * (classFactor - 1.0);
		double maxAbsoluteDetour = Math.min(budgetDerivedDetour, configMaxDetour);
		if (exmasConfig.getMaxAbsoluteDetour() != null) {
			maxAbsoluteDetour = Math.min(maxAbsoluteDetour, (double) exmasConfig.getMaxAbsoluteDetour());
		}
		double maxWalk = exmasConfig.isEnableBudgetAwareConstraints()
				? budgetToConstraintsCalculator.budgetToMaxWalkDistance(budget, person, draft) : 0.0;
		double maxWait = exmasConfig.isEnableBudgetAwareConstraints()
				? budgetToConstraintsCalculator.budgetToMaxWaitingTime(budget, person, draft) : 0.0;
		return new double[] {maxAbsoluteDetour, maxWalk, maxWait};
	}

	/**
	 * Build a DRT request from trip data.
	 * Extracted method to encapsulate request building logic.
	 * Uses BudgetValidator for consistent budget calculation methodology.
	 *
	 * @param ptMetrics PT accessibility metrics: [carTravelTime, ptTravelTime], or null if unavailable
	 *
	 * <p>Package-private so the same-package test harness can invoke it directly
	 * (mirrors the other package-private seams {@code budgetDerivedCaps},
	 * {@code finalizeVirtualLeg}, {@code renumber}).
	 */
	DrtRequest buildRequest(
			int requestIndex, Person person, Trip trip, int tripIdx,
			String groupId, boolean isCommute, boolean isEducation, Entry<String, Double> bestBaselineMode,
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
				.isEducation(isEducation)
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
		double[] caps = budgetDerivedCaps(budget, person, draft);
		double maxAbsoluteDetour = caps[0];
		double effectiveMaxDetourFactor = 1.0 + (maxAbsoluteDetour / drtAttrs.travelTime());
		double budgetDerivedMaxWalk = caps[1];
		double budgetDerivedMaxWait = caps[2];

		// Temporal flexibility (departure/arrival windows) — independent from detour.
		// EXT-4 rel half: a class-keyed rel override (flexRelativeByClass) replaces the
		// FlexibilityCalculator's relative factor; absent classes keep the map default.
		double flexRelClass = resolveClassFactor(exmasConfig.getFlexRelativeByClass(),
				Double.NaN, draft.requestTag, draft.hubLegRole);
		Double flexRelOverride = Double.isNaN(flexRelClass) ? null : flexRelClass;
		double originFlex = flexibilityCalculator.calculateOriginFlexibility(
				person, trip.getOriginActivity(), maxAbsoluteDetour, flexRelOverride);
		double destFlex = flexibilityCalculator.calculateDestinationFlexibility(
				person, trip.getDestinationActivity(), maxAbsoluteDetour, flexRelOverride);
		double earliestDep = requestTime - originFlex;
		double latestArr = requestTime + destFlex + drtAttrs.travelTime();

		DrtRequest finalRequest = draft.toBuilder()
				.budget(budget)
				.earliestDeparture(earliestDep)
				.latestArrival(latestArr)
				.maxDetourFactor(effectiveMaxDetourFactor)
				.maxWalkDistance(budgetDerivedMaxWalk)
				.maxWaitTime(budgetDerivedMaxWait)
				.marginalUtilityOfMoney(budgetValidator.marginalUtilityOfMoney(draft, person))
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

	/**
	 * Per-virtual-copy finalization: fresh scoring context (the copy's endpoint
	 * coords/requestTime differ from the original's), recomputed per-LEG budget
	 * (drop if the ideal leg can't beat the full-trip baseline), and budget-derived
	 * caps off the leg's own direct attributes. Returns {@code null} when dropped.
	 *
	 * <p>Package-private so the test harness in the same package can call it
	 * without reflection.
	 */
	DrtRequest finalizeVirtualLeg(DrtRequest copy, Person person,
			BudgetValidator validator) {
		copy.setScoringContext(validator.computeScoringContext(copy, person));
		double legBudget = validator.calculateBudget(copy);
		if (legBudget <= 0.0) {
			return null;
		}
		double[] caps = budgetDerivedCaps(legBudget, person, copy);
		double effectiveMaxDetourFactor = 1.0 + (caps[0] / copy.directTravelTime);
		DrtRequest done = copy.toBuilder()
				.budget(legBudget)
				.maxDetourFactor(effectiveMaxDetourFactor)
				.maxWalkDistance(caps[1])
				.maxWaitTime(caps[2])
				.marginalUtilityOfMoney(validator.marginalUtilityOfMoney(copy, person))
				.build();
		done.setScoringContext(copy.getScoringContext());
		return done;
	}

	/**
	 * Restores the invariant {@code requests.get(i).index == i} (broken by
	 * expansion, which copies the original's index onto every hub copy).
	 *
	 * <p>Scoring contexts are preserved by reference on any request that needs
	 * renaming. Package-private + static so tests can call it directly.
	 */
	static List<DrtRequest> renumber(List<DrtRequest> requests) {
		List<DrtRequest> out = new ArrayList<>(requests.size());
		for (int i = 0; i < requests.size(); i++) {
			DrtRequest r = requests.get(i);
			if (r.index != i) {
				DrtRequest c = r.toBuilder().index(i).build();
				c.setScoringContext(r.getScoringContext());
				r = c;
			}
			out.add(r);
		}
		return out;
	}

	/** Routes one virtual-leg OD pair. @return {travelTime_s, distance_m}, or
	 *  null if unreachable. Production impl: {@link ModeRoutingCache#routeDrtOd};
	 *  unit tests inject a fake. */
	@FunctionalInterface
	interface LegRouter {
		double[] route(Id<Link> fromLink, Id<Link> toLink, double departureTime);
	}

	/** Carries the result of {@link #applyVirtualExpansion}. */
	record ExpansionResult(
			List<DrtRequest> requests,
			ExpansionDropStats dropStats,
			int connectingExpanded,
			int virtualEmitted,
			int virtualDroppedExpansion,
			int virtualDroppedBudget) {}

	/**
	 * Paper-2 Extension 2 — virtual-trip expansion pass over {@code requests}.
	 *
	 * <p>For each request tagged {@code "connecting"}:
	 * <ol>
	 *   <li>Fan out to {@code |hubs|} hub-leg copies via {@link #expandConnecting},
	 *       once per relevant {@link FleetSide}. In single-side mode
	 *       ({@code bothSides == false}) this is just {@code fleetSide}; in
	 *       merged both-sides mode ({@code bothSides == true}) it is BOTH
	 *       {@link FleetSide#RURAL} (access O→hub legs) AND {@link FleetSide#URBAN}
	 *       (continuation hub→D legs), unioned — the Task-5 decision (call
	 *       {@code expandConnecting} twice and concatenate, NOT a new
	 *       {@code FleetSide.BOTH}). The shared {@code dropStats} accumulates
	 *       across both calls (counters + detour rows).</li>
	 *   <li>Finalize each hub copy via {@link #finalizeVirtualLeg}; drop non-positive.</li>
	 *   <li>Emit the original O→D request retagged {@code "connecting-direct"} —
	 *       preserving origin/destination/budget/time-windows intact — so the
	 *       downstream MIP can choose hub-vs-direct. Emitted EXACTLY ONCE per
	 *       connecting request (outside the side loop), never once per side.</li>
	 * </ol>
	 * Non-connecting requests pass through unchanged.
	 * The returned list is renumbered so {@code index == position}.
	 *
	 * <p>Package-private so the unit test harness can inject a synthetic
	 * {@code LegRouter} without needing a live {@link ModeRoutingCache}.
	 *
	 * @param requests            pre-built, finalized requests (from the
	 *                            population-scan phase of {@code buildRequests})
	 * @param hubs                hub set for the current fleet run
	 * @param fleetSide           RURAL or URBAN fleet
	 * @param isInsideMetropole   spatial predicate for metropole boundary
	 * @param transferBuffer      hub transfer slack in seconds (legacy fixed-buffer
	 *                            continuation split when {@code maxHubWait <= 0})
	 * @param maxHubWait          hub-sync v1 continuation window width in seconds;
	 *                            {@code <= 0} = legacy fixed-buffer behavior, {@code > 0}
	 *                            widens the continuation departure window (see
	 *                            {@link #expandConnecting}). NO effect on ACCESS legs.
	 * @param budgetValidator     for per-leg re-finalization
	 * @param routerFactory       produces a per-person {@link LegRouter}
	 * @param bothSides           if true, emit BOTH leg sides (RURAL access +
	 *                            URBAN continuation) per hub by calling
	 *                            {@link #expandConnecting} twice and unioning;
	 *                            if false, emit only {@code fleetSide}'s leg side
	 *                            (single-side, backward-compatible)
	 */
	ExpansionResult applyVirtualExpansion(
			List<DrtRequest> requests,
			List<HubSetLoader.Hub> hubs,
			FleetSide fleetSide,
			java.util.function.Predicate<Coord> isInsideMetropole,
			double transferBuffer,
			double maxHubWait,
			BudgetValidator budgetValidator,
			java.util.function.Function<Person, LegRouter> routerFactory,
			boolean bothSides) {
		return applyVirtualExpansion(requests, hubs, fleetSide, isInsideMetropole,
				transferBuffer, maxHubWait, /* hubSyncTwoSided */ false,
				/* hubSyncMaxAdvanceSeconds */ 0.0, budgetValidator, routerFactory, bothSides);
	}

	/**
	 * Full overload: hub-sync v2 (Task 11c) adds two-sided ACCESS-variant
	 * emission. When {@code hubSyncTwoSided} is on, the RURAL ACCESS branch of
	 * {@link #expandConnecting} emits multiple variants per (commuter, hub) at
	 * earlier-departure offsets bounded by {@code hubSyncMaxAdvanceSeconds}
	 * (step = {@code maxHubWait}); each is finalized/renumbered individually.
	 * {@code hubSyncTwoSided == false} (the convenience overload's default) is
	 * byte-identical to the v1 single-access behavior.
	 */
	ExpansionResult applyVirtualExpansion(
			List<DrtRequest> requests,
			List<HubSetLoader.Hub> hubs,
			FleetSide fleetSide,
			java.util.function.Predicate<Coord> isInsideMetropole,
			double transferBuffer,
			double maxHubWait,
			boolean hubSyncTwoSided,
			double hubSyncMaxAdvanceSeconds,
			BudgetValidator budgetValidator,
			java.util.function.Function<Person, LegRouter> routerFactory,
			boolean bothSides) {

		ExpansionDropStats dropStats = new ExpansionDropStats();
		int connectingExpanded = 0;
		int virtualEmitted = 0;
		int virtualDroppedExpansion = 0;
		int virtualDroppedBudget = 0;

		List<DrtRequest> expanded = new ArrayList<>(requests.size());
		for (DrtRequest r : requests) {
			if ("connecting".equals(r.requestTag)) {
				Person person = r.getScoringContext().person();
				LegRouter router = routerFactory.apply(person);
				// Which leg side(s) to emit. Both-sides (merged run): RURAL access
				// legs ∪ URBAN continuation legs — call expandConnecting once per
				// side and union (Task-5 decision; NOT a FleetSide.BOTH). The
				// shared dropStats accumulates across both calls. Single-side:
				// just the configured fleetSide.
				List<FleetSide> sides = bothSides
						? List.of(FleetSide.RURAL, FleetSide.URBAN)
						: (fleetSide != null ? List.of(fleetSide) : List.<FleetSide>of());
				for (FleetSide side : sides) {
					List<DrtRequest> copies = expandConnecting(
							r, hubs, side, isInsideMetropole, network, router,
							transferBuffer, maxHubWait, hubSyncTwoSided, hubSyncMaxAdvanceSeconds,
							exmasConfig.getHubTopK(), dropStats);
					// With v2 ACCESS variants a hub may yield >1 copy, so guard the
					// "dropped at expansion" diagnostic against going negative.
					int droppedExpansion = Math.max(0, hubs.size() - copies.size());
					int droppedBudget = 0;
					for (DrtRequest copy : copies) {
						DrtRequest done = finalizeVirtualLeg(copy, person, budgetValidator);
						if (done == null) { droppedBudget++; continue; }
						expanded.add(done);
						virtualEmitted++;
					}
					virtualDroppedExpansion += droppedExpansion;
					virtualDroppedBudget += droppedBudget;
				}
				connectingExpanded++;   // ONCE per request, not per side
				// Emit the connecting-direct ride: the original O→D request
				// retagged so the downstream MIP can choose hub-vs-direct.
				// No re-finalization: budget, time-windows, and caps are
				// inherited from the already-finalized connecting request.
				DrtRequest direct = r.toBuilder().requestTag("connecting-direct").build();
				direct.setScoringContext(r.getScoringContext());
				// EXT-4: the copy was finalized under the "connecting" class; if the
				// config differentiates connecting-direct, re-derive its caps under
				// its own tag. Without such an entry this is a no-op (byte-identical).
				if (exmasConfig.getMaxDetourFactorByClass().containsKey("connecting-direct")) {
					double[] caps = budgetDerivedCaps(direct.budget,
							person, direct);
					DrtRequest recapped = direct.toBuilder()
							.maxDetourFactor(1.0 + caps[0] / direct.directTravelTime)
							.maxWalkDistance(caps[1])
							.maxWaitTime(caps[2])
							.build();
					recapped.setScoringContext(r.getScoringContext());
					direct = recapped;
				}
				expanded.add(direct);
			} else {
				expanded.add(r);
			}
		}
		return new ExpansionResult(renumber(expanded), dropStats,
				connectingExpanded, virtualEmitted,
				virtualDroppedExpansion, virtualDroppedBudget);
	}

	/**
	 * Paper-2 Extension 2 — virtual-trip expansion for one {@code connecting}
	 * request. Returns {@code |hubs|} copies of {@code original}, each with
	 * one endpoint replaced by a hub coordinate. Which endpoint is replaced,
	 * and the leg role, follow the journey ORIENTATION and which physical leg
	 * this fleet serves — NOT {@code fleetSide} alone (EXT-1). The rural fleet
	 * serves the leg whose non-hub endpoint is the rural end; that leg is the
	 * journey's FIRST leg (origin->hub) iff the rural end is the origin
	 * (forward, rural->urban), and the SECOND leg (hub->destination) otherwise
	 * (reverse, urban->rural). The urban fleet mirrors this.
	 *
	 * <ul>
	 *   <li>The copy serving the leg that contains the journey ORIGIN is an
	 *       {@link DrtRequest.HubLegRole#ACCESS_LEG} ({@code O->hub}): the hub
	 *       replaces the destination.</li>
	 *   <li>The copy serving the leg that contains the journey DESTINATION is a
	 *       {@link DrtRequest.HubLegRole#CONTINUATION_LEG} ({@code hub->D}): the
	 *       hub replaces the origin.</li>
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
	 * not the inherited full-trip values. Both legs are routed in physical
	 * travel direction ({@code firstLeg = O->hub}, {@code secondLeg = hub->D})
	 * so the off-fleet leg's direct time drives a temporal split, keyed on the
	 * copy's ROLE (see above), not {@code fleetSide}:
	 * <ul>
	 *   <li>ACCESS leg ({@code O->hub}): departs at {@code requestTime};
	 *       {@code latestArrival} is backed out as
	 *       {@code original.latestArrival - secondLeg_tt - buffer} so the
	 *       continuation + transfer still make the full-trip deadline. Role
	 *       {@code ACCESS_LEG}, {@code transferWaitSeconds = 0}.</li>
	 *   <li>CONTINUATION leg ({@code hub->D}): shifted to
	 *       {@code requestTime + firstLeg_tt + buffer} (and
	 *       {@code earliestDeparture} likewise, EXT-3-clamped so it never
	 *       precedes hub arrival + buffer), keeping the full-trip
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
	/**
	 * Mutable accumulator for per-hub virtual-leg drop reasons (Paper-2 Ext-2
	 * diagnostics). Distinguishes routing failures (a leg endpoint is
	 * unreachable on the routing network, or the routed leg is degenerate) from
	 * temporal infeasibility (both legs route, but the via-hub journey cannot fit
	 * the traveller's {@code directTravelTime + arrival-flexibility} window once
	 * the transfer buffer is charged). The split tells us whether low connecting
	 * feasibility is a network/coverage problem or a detour-budget problem.
	 */
	static final class ExpansionDropStats {
		int kept;
		int unroutableRuralLeg;
		int unroutableUrbanLeg;
		int temporalInfeasible;
		/** D-W5 hub top-K (Task W2): hubs that routed successfully (both legs)
		 *  but were cut because they ranked outside {@code hubTopK} best hubs
		 *  for this trip. Disjoint from the unroutable/temporal counters above
		 *  — a hub is only eligible for this cut once it has already routed. */
		int droppedByTopK;
		final List<HubDetour> detours = new ArrayList<>();
	}

	/**
	 * Per-(commuter, hub) detour diagnostic row (Paper-2 Ext-2). {@code detourTime}
	 * is the via-hub journey time minus the original direct time; a hub is
	 * temporally feasible iff {@code detourTime <= slack}, where {@code slack =
	 * latestArrival - requestTime - directTime} is the traveller's arrival
	 * flexibility (destFlex). {@code maxAbsDetour = (maxDetourFactor-1)*directTime}
	 * is the budget/config-capped absolute detour allowance baked into the
	 * original request; slack is ~half of it under the default rel=0.5 flexibility.
	 * Lets us see, for feasible AND dropped legs, how much detour the hubs
	 * introduce versus what each traveller can absorb.
	 */
	record HubDetour(String personId, int tripIndex, String fleetSide, String hubId,
			double directTime, double ruralLegTime, double urbanLegTime, double buffer,
			double detourTime, double slack, double maxAbsDetour, boolean kept, String reason) {}

	private static void recordDetour(ExpansionDropStats stats, DrtRequest o, FleetSide side,
			HubSetLoader.Hub hub, double ruralLegT, double urbanLegT, double buffer,
			boolean kept, String reason) {
		if (stats == null) return;
		double directTime = o.directTravelTime;
		double slack = o.latestArrival - o.requestTime - directTime;
		boolean bothRouted = !Double.isNaN(ruralLegT) && !Double.isNaN(urbanLegT);
		double detour = bothRouted
				? (ruralLegT + urbanLegT + buffer) - directTime : Double.NaN;
		double maxAbsDetour = (o.maxDetourFactor - 1.0) * directTime;
		stats.detours.add(new HubDetour(o.personId.toString(), o.tripIndex,
				side.toString(), hub.id(), directTime, ruralLegT, urbanLegT, buffer,
				detour, slack, maxAbsDetour, kept, reason));
	}

	/** Convenience overload without diagnostics (used by unit tests). Defaults
	 *  {@code maxHubWaitSeconds = 0.0} (legacy fixed-buffer continuation window). */
	static List<DrtRequest> expandConnecting(
			DrtRequest original,
			List<HubSetLoader.Hub> hubs,
			FleetSide fleetSide,
			Predicate<Coord> isInsideMetropole,
			Network network,
			LegRouter legRouter,
			double transferBufferSeconds) {
		return expandConnecting(original, hubs, fleetSide, isInsideMetropole,
				network, legRouter, transferBufferSeconds, 0.0, false, 0.0, null);
	}

	/** Convenience overload without diagnostics, with explicit maxHubWait. */
	static List<DrtRequest> expandConnecting(
			DrtRequest original,
			List<HubSetLoader.Hub> hubs,
			FleetSide fleetSide,
			Predicate<Coord> isInsideMetropole,
			Network network,
			LegRouter legRouter,
			double transferBufferSeconds,
			double maxHubWaitSeconds) {
		return expandConnecting(original, hubs, fleetSide, isInsideMetropole,
				network, legRouter, transferBufferSeconds, maxHubWaitSeconds, false, 0.0, null);
	}

	/** Diagnostics overload with legacy fixed-buffer continuation window
	 *  ({@code maxHubWaitSeconds = 0.0}); kept for existing call sites/tests. */
	static List<DrtRequest> expandConnecting(
			DrtRequest original,
			List<HubSetLoader.Hub> hubs,
			FleetSide fleetSide,
			Predicate<Coord> isInsideMetropole,
			Network network,
			LegRouter legRouter,
			double transferBufferSeconds,
			ExpansionDropStats stats) {
		return expandConnecting(original, hubs, fleetSide, isInsideMetropole,
				network, legRouter, transferBufferSeconds, 0.0, false, 0.0, stats);
	}

	/** Convenience overload with explicit maxHubWait + diagnostics, but legacy
	 *  single-access ({@code hubSyncTwoSided = false}) v2 behavior. Kept so the
	 *  Task-10b call sites/tests that pass {@code (… maxHubWait, stats)} compile
	 *  unchanged. */
	static List<DrtRequest> expandConnecting(
			DrtRequest original,
			List<HubSetLoader.Hub> hubs,
			FleetSide fleetSide,
			Predicate<Coord> isInsideMetropole,
			Network network,
			LegRouter legRouter,
			double transferBufferSeconds,
			double maxHubWaitSeconds,
			ExpansionDropStats stats) {
		return expandConnecting(original, hubs, fleetSide, isInsideMetropole,
				network, legRouter, transferBufferSeconds, maxHubWaitSeconds, false, 0.0, stats);
	}

	/**
	 * Convenience overload with explicit maxHubWait/hub-sync-v2 params but no
	 * hub top-K cut ({@code hubTopK = 0}, unlimited). Kept so pre-D-W5 call
	 * sites/tests that pass {@code (… hubSyncTwoSided, hubSyncMaxAdvanceSeconds,
	 * stats)} compile and behave unchanged.
	 */
	static List<DrtRequest> expandConnecting(
			DrtRequest original,
			List<HubSetLoader.Hub> hubs,
			FleetSide fleetSide,
			Predicate<Coord> isInsideMetropole,
			Network network,
			LegRouter legRouter,
			double transferBufferSeconds,
			double maxHubWaitSeconds,
			boolean hubSyncTwoSided,
			double hubSyncMaxAdvanceSeconds,
			ExpansionDropStats stats) {
		return expandConnecting(original, hubs, fleetSide, isInsideMetropole,
				network, legRouter, transferBufferSeconds, maxHubWaitSeconds,
				hubSyncTwoSided, hubSyncMaxAdvanceSeconds, /* hubTopK */ 0, stats);
	}

	/**
	 * @param maxHubWaitSeconds Paper-2 hub-sync v1: width (s) of the
	 *        hub-departure window for CONTINUATION legs. {@code <= 0.0} reproduces
	 *        the legacy fixed-buffer split exactly (backward-compat); {@code > 0.0}
	 *        widens the continuation window to
	 *        {@code [hubArrival, hubArrival + maxHubWaitSeconds]} with
	 *        {@code transferWaitSeconds = 0}. Has NO effect on ACCESS legs.
	 * @param hubSyncTwoSided Paper-2 hub-sync v2 (Task 11c): when {@code true} the
	 *        ACCESS (RURAL) branch emits MULTIPLE access variants per hub at
	 *        earlier-departure offsets {@code 0, step, 2·step, …} (step =
	 *        {@code maxHubWaitSeconds}), each shifted earlier in {@code requestTime}
	 *        and {@code earliestDeparture} and re-routed for the new departure.
	 *        Requires {@code maxHubWaitSeconds > 0}. Offset 0 reproduces today's
	 *        single access leg, so {@code false} (default) is byte-identical.
	 *        EXT-2: the CONTINUATION (URBAN) wide-window branch co-shifts in
	 *        lockstep — it emits one continuation variant per ACCESS offset
	 *        {@code k}, anchored at that offset's own hub arrival
	 *        {@code (requestTime − k·step) + firstLeg_k}, so every shifted access
	 *        variant has a continuation ride inside its nesting window. Offset 0
	 *        stays byte-identical; {@code false} leaves the single continuation
	 *        leg unchanged.
	 * @param hubSyncMaxAdvanceSeconds Paper-2 hub-sync v2: upper bound on the
	 *        access variant offset (max seconds a commuter may depart earlier);
	 *        only consulted when {@code hubSyncTwoSided == true}.
	 * @param hubTopK D-W5 (Task W2): the hub list is ranked per trip by
	 *        {@code accessDirect + continuationDirect} (the two nominal-departure
	 *        direct leg times routed once in pass 1) and only the {@code hubTopK}
	 *        best-ranked hubs are emitted in pass 2. {@code hubTopK <= 0} means
	 *        unlimited (every rankable hub survives) and is byte-identical to
	 *        the pre-D-W5 behavior: same hubs, same emission order, same stats,
	 *        same detour rows. Hubs that fail to route are handled exactly as
	 *        before (unroutable/temporal counters), never folded into the
	 *        top-K cut; hubs cut by the ranking increment
	 *        {@link ExpansionDropStats#droppedByTopK} and record a
	 *        {@code "topk_dropped"} detour row instead of being emitted.
	 */
	static List<DrtRequest> expandConnecting(
			DrtRequest original,
			List<HubSetLoader.Hub> hubs,
			FleetSide fleetSide,
			Predicate<Coord> isInsideMetropole,
			Network network,
			LegRouter legRouter,
			double transferBufferSeconds,
			double maxHubWaitSeconds,
			boolean hubSyncTwoSided,
			double hubSyncMaxAdvanceSeconds,
			int hubTopK,
			ExpansionDropStats stats) {
		if (hubSyncTwoSided && maxHubWaitSeconds <= 0.0) {
			throw new IllegalArgumentException(
					"hub-sync v2 (--hub-sync-twosided) requires maxHubWaitSeconds > 0 "
					+ "(the variant step); got maxHubWaitSeconds=" + maxHubWaitSeconds
					+ ". Set --max-hub-wait to a positive value.");
		}
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

		// Journey orientation: is the RURAL end the journey origin (forward,
		// rural->urban) or the destination (reverse, urban->rural)? Ambiguous
		// cases (neither or both endpoints test urban) fall back to FORWARD,
		// matching the historical dominant home-to-work assumption.
		boolean ruralEndIsOrigin = !(originIsUrban && !destIsUrban);

		// Which physical leg does THIS fleet serve? The rural fleet serves the
		// leg whose non-hub endpoint is the rural end; that leg is the journey's
		// FIRST leg (O->hub, ACCESS) iff the rural end is the origin. Mirrored
		// for the urban fleet. Role therefore depends on orientation, NOT on
		// fleetSide alone (the old code inverted reverse-direction trips).
		boolean fleetServesOriginEnd = (fleetSide == FleetSide.RURAL) == ruralEndIsOrigin;
		DrtRequest.HubLegRole role = fleetServesOriginEnd
				? DrtRequest.HubLegRole.ACCESS_LEG
				: DrtRequest.HubLegRole.CONTINUATION_LEG;

		// Endpoint replacement follows the role: an ACCESS copy is O->hub (the
		// hub replaces the destination), a CONTINUATION copy is hub->D (the hub
		// replaces the origin). This reproduces the old geometry in all four
		// orientation x fleetSide cases — only roles/timing/metrics change.
		boolean replaceOrigin = (role == DrtRequest.HubLegRole.CONTINUATION_LEG);

		// D-W5 (Task W2) two-pass hub selection. Pass 1 routes BOTH legs of
		// EVERY hub at the nominal departure exactly as before (unroutable
		// handling untouched) and keeps the results in a small local record so
		// pass 2 can reuse them without re-routing — the whole point of the
		// split is to never pay for routing twice. Pass 1 owns the
		// nominal-departure unroutable drops; the variant/offset re-routing
		// failures and the temporal-infeasibility drops stay inside the
		// pass-2 emission body, unchanged. Pass 2 emits only the
		// hubTopK best-ranked hubs (ranked by accessDirect + continuationDirect,
		// i.e. firstLeg + secondLeg direct time, ascending); hubTopK <= 0 means
		// unlimited, in which case the top-K cut is skipped entirely (the
		// survivor set stays null) and pass 2 runs the identical per-hub
		// emission for every routed hub in the SAME order pass 1 routed
		// them — the original hub-list order — so hubTopK <= 0 is
		// byte-identical to the single-pass loop this replaces.
		record HubRoutedLegs(HubSetLoader.Hub hub, Link hubLink, Id<Link> hubLinkId,
				double[] firstLeg, double[] secondLeg, double ruralLegTime, double urbanLegTime) {
			double rankScore() { return firstLeg[0] + secondLeg[0]; }
		}

		List<HubRoutedLegs> routed = new ArrayList<>(hubs.size());
		for (HubSetLoader.Hub hub : hubs) {
			Coord hubCoord = hub.coord();
			Link hubLink = NetworkUtils.getNearestLink(network, hubCoord);
			Id<Link> hubLinkId = hubLink.getId();

			// Route BOTH legs in PHYSICAL travel direction: firstLeg = journey
			// origin -> hub at the desired departure; secondLeg = hub -> journey
			// destination after the first leg + transfer buffer.
			double[] firstLeg = legRouter.route(original.originLinkId, hubLinkId, original.requestTime);
			if (firstLeg == null || firstLeg[0] <= 0.0 || firstLeg[1] <= 0.0) {
				if (stats != null) {
					if (ruralEndIsOrigin) stats.unroutableRuralLeg++; else stats.unroutableUrbanLeg++;
					recordDetour(stats, original, fleetSide, hub, Double.NaN, Double.NaN,
							transferBufferSeconds, false,
							ruralEndIsOrigin ? "unroutable_rural_leg" : "unroutable_urban_leg");
				}
				continue;
			}
			double[] secondLeg = legRouter.route(hubLinkId, original.destinationLinkId,
					original.requestTime + firstLeg[0] + transferBufferSeconds);
			if (secondLeg == null || secondLeg[0] <= 0.0 || secondLeg[1] <= 0.0) {
				if (stats != null) {
					if (ruralEndIsOrigin) stats.unroutableUrbanLeg++; else stats.unroutableRuralLeg++;
					recordDetour(stats, original, fleetSide, hub,
							ruralEndIsOrigin ? firstLeg[0] : Double.NaN,
							ruralEndIsOrigin ? Double.NaN : firstLeg[0],
							transferBufferSeconds, false,
							ruralEndIsOrigin ? "unroutable_urban_leg" : "unroutable_rural_leg");
				}
				continue;
			}
			// Diagnostics keep the rural/urban naming: map first/second by orientation.
			double ruralLegTime = ruralEndIsOrigin ? firstLeg[0] : secondLeg[0];
			double urbanLegTime = ruralEndIsOrigin ? secondLeg[0] : firstLeg[0];
			routed.add(new HubRoutedLegs(hub, hubLink, hubLinkId, firstLeg, secondLeg,
					ruralLegTime, urbanLegTime));
		}

		// Rank + slice to the hubTopK best (ascending accessDirect+continuationDirect
		// == firstLeg+secondLeg direct time). hubTopK <= 0, or >= the rankable count,
		// means no cut: survivingHubIds stays null so pass 2 below never consults it,
		// which is what keeps hubTopK <= 0 byte-identical (including emission order)
		// to the pre-D-W5 single-pass loop.
		Set<String> survivingHubIds = null;
		if (hubTopK > 0 && hubTopK < routed.size()) {
			List<HubRoutedLegs> ranked = new ArrayList<>(routed);
			ranked.sort(Comparator.comparingDouble(HubRoutedLegs::rankScore));
			survivingHubIds = new HashSet<>();
			for (int i = 0; i < hubTopK; i++) {
				survivingHubIds.add(ranked.get(i).hub().id());
			}
		}

		List<DrtRequest> copies = new ArrayList<>(hubs.size());
		for (HubRoutedLegs hr : routed) {
			HubSetLoader.Hub hub = hr.hub();
			Coord hubCoord = hub.coord();
			Link hubLink = hr.hubLink();
			Id<Link> hubLinkId = hr.hubLinkId();
			double[] firstLeg = hr.firstLeg();
			double[] secondLeg = hr.secondLeg();
			double ruralLegTime = hr.ruralLegTime();
			double urbanLegTime = hr.urbanLegTime();

			// D-W5 top-K cut: this hub routed both legs at the nominal
			// departure in pass 1 but ranked outside the K best for this trip.
			// Ranking deliberately considers ONLY that nominal-departure
			// routability (the spec's rankability criterion), so a surviving hub
			// can still be dropped further down by a variant/offset re-routing
			// failure or by the temporal-infeasibility check, and a cut hub is
			// never promoted in its place.
			if (survivingHubIds != null && !survivingHubIds.contains(hub.id())) {
				if (stats != null) {
					stats.droppedByTopK++;
					recordDetour(stats, original, fleetSide, hub, ruralLegTime, urbanLegTime,
							transferBufferSeconds, false, "topk_dropped");
				}
				continue;
			}

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

			if (role == DrtRequest.HubLegRole.ACCESS_LEG) {
				// ACCESS leg O->hub. The deadline backout uses the SECOND (post-hub)
				// leg time (routed at the original departure) exactly as today:
				//   legLatestArrival = original.latestArrival − secondLeg − buffer.
				double legLatestArrival = original.latestArrival - secondLeg[0] - transferBufferSeconds;

				// Variant offsets. v1 (twosided off) → only offset 0, departing at
				// the original requestTime → byte-identical to today. v2 (twosided
				// on) → 0, step, 2·step, … while offset <= hubSyncMaxAdvanceSeconds,
				// so the hub arrival clusters earlier (Python bins the diversity).
				// The variant count is computed up front (index-based) so the loop
				// never depends on `offset += step`; with twosided off it is always
				// exactly one. The builder `b` already carries this hub's geometry;
				// each variant overwrites the temporal fields and build()s a fresh
				// copy (build() does not freeze the builder).
				int numVariants = 1;
				if (hubSyncTwoSided) {
					// maxHubWaitSeconds > 0 is guaranteed by the step-guard above.
					numVariants = (int) Math.floor(hubSyncMaxAdvanceSeconds / maxHubWaitSeconds) + 1;
				}
				for (int k = 0; k < numVariants; k++) {
					double offset = k * maxHubWaitSeconds;
					double newRequestTime = original.requestTime - offset;
					// Re-route the first (O->hub) leg at the new departure (leg travel
					// time can depend on departure). Offset 0 reuses the already-routed
					// leg so v1 stays byte-identical.
					double[] varFirst;
					if (offset == 0.0) {
						varFirst = firstLeg;
					} else {
						varFirst = legRouter.route(original.originLinkId, hubLinkId, newRequestTime);
						if (varFirst == null || varFirst[0] <= 0.0 || varFirst[1] <= 0.0) {
							if (stats != null) {
								if (ruralEndIsOrigin) stats.unroutableRuralLeg++; else stats.unroutableUrbanLeg++;
								recordDetour(stats, original, fleetSide, hub,
										ruralEndIsOrigin ? Double.NaN : secondLeg[0],
										ruralEndIsOrigin ? secondLeg[0] : Double.NaN,
										transferBufferSeconds, false,
										ruralEndIsOrigin ? "unroutable_rural_leg" : "unroutable_urban_leg");
							}
							continue;
						}
					}
					// Diagnostics keep rural/urban naming: the variant's first-leg time
					// maps to the rural/urban slot by orientation.
					double varRuralLegTime = ruralEndIsOrigin ? varFirst[0] : secondLeg[0];
					double varUrbanLegTime = ruralEndIsOrigin ? secondLeg[0] : varFirst[0];
					if (newRequestTime + varFirst[0] > legLatestArrival) { // hub doesn't fit
						if (stats != null) {
							stats.temporalInfeasible++;
							recordDetour(stats, original, fleetSide, hub, varRuralLegTime, varUrbanLegTime,
									transferBufferSeconds, false, "temporal_infeasible");
						}
						continue;
					}
					// Shift requestTime earlier by the offset. At offset 0 this leaves
					// requestTime/earliestDeparture exactly as the original's
					// (byte-identical to today; earliestDeparture may differ from
					// requestTime under the budget-aware flex path).
					b.requestTime(newRequestTime)
					 // k=0: byte-identical to v1. k>=1: the variant consumes the origin
					 // flexibility — the offset grid is the earliness mechanism (EXT-9).
					 .earliestDeparture(offset == 0.0 ? original.earliestDeparture : newRequestTime)
					 .directTravelTime(varFirst[0]).directDistance(varFirst[1])
					 .latestArrival(legLatestArrival)
					 .hubLegRole(DrtRequest.HubLegRole.ACCESS_LEG)
					 .transferWaitSeconds(0.0);
					DrtRequest variant = b.build();
					variant.setScoringContext(original.getScoringContext());
					copies.add(variant);
					if (stats != null) {
						stats.kept++;
						recordDetour(stats, original, fleetSide, hub, varRuralLegTime, varUrbanLegTime,
								transferBufferSeconds, true, "kept");
					}
				}
				// All ACCESS variants for this hub are already added; the shared
				// single-build tail below applies only to the CONTINUATION side.
				continue;
			} else {
				// CONTINUATION leg hub->D. The pax reaches the hub at
				// requestTime + firstLeg (direct, unbuffered); each sub-branch
				// computes that arrival for its own offset/departure.
				if (maxHubWaitSeconds <= 0.0) {
					// Legacy fixed-buffer split (backward-compat, byte-identical):
					// departure pinned at hubArrival + buffer, buffer charged as wait.
					double shift = firstLeg[0] + transferBufferSeconds;
					if (original.requestTime + shift + secondLeg[0] > original.latestArrival) {
						if (stats != null) {
							stats.temporalInfeasible++;
							recordDetour(stats, original, fleetSide, hub, ruralLegTime, urbanLegTime,
									transferBufferSeconds, false, "temporal_infeasible");
						}
						continue;
					}
					// EXT-3: the continuation can never depart the hub before the pax
					// arrives (hubArrival + buffer == original.requestTime + shift).
					// Without the clamp, originFlex opens the window up to originFlex
					// seconds BEFORE the physical transfer is possible.
					b.directTravelTime(secondLeg[0]).directDistance(secondLeg[1])
					 .requestTime(original.requestTime + shift)
					 .earliestDeparture(Math.max(original.earliestDeparture + shift,
							original.requestTime + shift))
					 .hubLegRole(DrtRequest.HubLegRole.CONTINUATION_LEG)
					 .transferWaitSeconds(transferBufferSeconds);
				} else {
					// Hub-sync wide window. With twosided ON, co-shift the continuation:
					// one variant per ACCESS offset k, anchored at that offset's own hub
					// arrival — otherwise the shifted access variants have no continuation
					// ride inside their nesting window and can never be selected (EXT-2).
					int numContVariants = 1;
					if (hubSyncTwoSided) {
						numContVariants = (int) Math.floor(hubSyncMaxAdvanceSeconds / maxHubWaitSeconds) + 1;
					}
					java.util.HashSet<Double> seenAnchors = new java.util.HashSet<>();
					for (int k = 0; k < numContVariants; k++) {
						double offset = k * maxHubWaitSeconds;
						double shiftedDeparture = original.requestTime - offset;
						double[] varFirst;
						if (offset == 0.0) {
							varFirst = firstLeg; // byte-identical k=0 path
						} else {
							varFirst = legRouter.route(original.originLinkId, hubLinkId, shiftedDeparture);
							if (varFirst == null || varFirst[0] <= 0.0 || varFirst[1] <= 0.0) {
								if (stats != null) {
									if (ruralEndIsOrigin) stats.unroutableRuralLeg++; else stats.unroutableUrbanLeg++;
								}
								continue;
							}
						}
						double varHubArrival = shiftedDeparture + varFirst[0];
						// Time-independent routing can collapse offsets onto one anchor; the
						// set also catches NON-ADJACENT collisions (SYNC-10), which the old
						// prevAnchor guard let through as duplicate variants.
						if (!seenAnchors.add(varHubArrival)) continue;
						if (varHubArrival + secondLeg[0] > original.latestArrival) {
							if (stats != null) {
								stats.temporalInfeasible++;
								recordDetour(stats, original, fleetSide, hub, ruralLegTime, urbanLegTime,
										transferBufferSeconds, false, "temporal_infeasible");
							}
							continue;
						}
						double legLatestArrival = Math.min(original.latestArrival,
								varHubArrival + maxHubWaitSeconds + secondLeg[0]);
						b.directTravelTime(secondLeg[0]).directDistance(secondLeg[1])
						 .requestTime(varHubArrival)
						 .earliestDeparture(varHubArrival)
						 .latestArrival(legLatestArrival)
						 .hubLegRole(DrtRequest.HubLegRole.CONTINUATION_LEG)
						 .transferWaitSeconds(0.0);
						DrtRequest variant = b.build();
						variant.setScoringContext(original.getScoringContext());
						copies.add(variant);
						if (stats != null) {
							stats.kept++;
							recordDetour(stats, original, fleetSide, hub, ruralLegTime, urbanLegTime,
									transferBufferSeconds, true, "kept");
						}
					}
					continue; // variants already added; skip the shared single-build tail
				}
			}

			DrtRequest copy = b.build();
			copy.setScoringContext(original.getScoringContext()); // rebuilt in Task 10
			copies.add(copy);
			if (stats != null) {
				stats.kept++;
				recordDetour(stats, original, fleetSide, hub, ruralLegTime, urbanLegTime,
						transferBufferSeconds, true, "kept");
			}
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
