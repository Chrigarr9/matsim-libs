package org.matsim.contrib.demand_extraction.demand;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Population;
import org.matsim.contrib.demand_extraction.algorithm.util.StringUtils;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup.TourEvaluationMode;
import org.matsim.contrib.demand_extraction.scoring.DemandExtractionScoringAdapter;
import org.matsim.contrib.demand_extraction.scoring.PreviousTripContext;
import org.matsim.contrib.demand_extraction.scoring.TripScoreRequest;
import org.matsim.contrib.demand_extraction.scoring.TripScoreResult;
import org.matsim.core.config.Config;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.router.TripRouter;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scoring.functions.ScoringParameters;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;
import org.matsim.facilities.ActivityFacilities;
import org.matsim.facilities.FacilitiesUtils;
import org.matsim.facilities.Facility;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;

@Singleton
public class ModeRoutingCache {
	private static final Logger log = LogManager.getLogger(ModeRoutingCache.class);

    private final Provider<TripRouter> tripRouterProvider;
    private final ExMasConfigGroup exMasConfig;
    private final DemandExtractionScoringAdapter adapter;
    private final ScoringParametersForPerson scoringParametersForPerson;
    private final Config config;
    private final Network network;
    private final ActivityFacilities facilities;

	// Maps: Person ID -> Trip Index -> Mode Name -> Mode Attributes
    private final Map<Id<Person>, Map<Integer, Map<String, ModeAttributes>>> cache = new ConcurrentHashMap<>();

	// Maps: Person ID -> Trip Index -> Best Baseline Mode (excludes DRT)
	private final Map<Id<Person>, Map<Integer, Entry<String, Double>>> bestBaselineModes = new ConcurrentHashMap<>();

	// PT Accessibility metrics: Person ID -> Trip Index -> [carTravelTime, ptTravelTime]
	// Car travel time is ALWAYS calculated regardless of car availability (for PT accessibility comparison)
	private final Map<Id<Person>, Map<Integer, double[]>> ptAccessibilityMetrics = new ConcurrentHashMap<>();

    @Inject
    public ModeRoutingCache(Provider<TripRouter> tripRouterProvider, ExMasConfigGroup exMasConfig,
            DemandExtractionScoringAdapter adapter,
            ScoringParametersForPerson scoringParametersForPerson,
            Config config, Network network, ActivityFacilities facilities) {
        this.tripRouterProvider = tripRouterProvider;
        this.exMasConfig = exMasConfig;
        this.adapter = adapter;
        this.scoringParametersForPerson = scoringParametersForPerson;
        this.config = config;
        this.network = network;
        this.facilities = facilities;
    }

    public void cacheModes(Population population) {
		log.info("Starting mode caching for {} persons (adapter: {})...",
				population.getPersons().size(), adapter.getName());
		long startTime = System.currentTimeMillis();

		// Thread-safe progress tracking
		AtomicInteger processedPersons = new AtomicInteger(0);
		int totalPersons = population.getPersons().size();
		int logInterval = Math.max(1, totalPersons / 10); // Log every 10%

		var personStream = population.getPersons().values().stream();
		if (!exMasConfig.isUseDeterministicNetworkRouting()) {
			personStream = personStream.parallel();
		}

		personStream.forEach(person -> {
            TripRouter tripRouter = tripRouterProvider.get();
            Map<Integer, Map<String, ModeAttributes>> personCache = new ConcurrentHashMap<>();
			Map<Integer, Entry<String, Double>> personBestModes = new ConcurrentHashMap<>();
			Map<Integer, double[]> personPtAccessibility = new ConcurrentHashMap<>();
            List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(person.getSelectedPlan());

            // Get scoring params for person (needed for opportunity cost)
            ScoringParameters params = scoringParametersForPerson.getScoringParameters(person);

            // GREEDY_PREFIX: accumulate best non-DRT mode per trip for tour context
            boolean useGreedyPrefix = exMasConfig.getTourEvaluationMode() == TourEvaluationMode.GREEDY_PREFIX;
            List<PreviousTripContext> previousTrips = useGreedyPrefix ? new ArrayList<>() : List.of();

            int tripIndex = 0;
            for (TripStructureUtils.Trip trip : trips) {
                Map<String, ModeAttributes> modeCache = new ConcurrentHashMap<>();

				// Filter modes based on person attributes (consistent with MATSim conventions)
				Set<String> allModes = new HashSet<>(exMasConfig.getBaseModes());
				allModes.add(exMasConfig.getDrtMode()); // Add DRT to modes to evaluate
				Set<String> availableModes = filterAvailableModes(person, allModes);

				for (String mode : availableModes) {
					// Determine routing mode
					String routingMode;
					if (mode.equals(exMasConfig.getDrtMode())) {
						String drtRouterName = "direct" + StringUtils.capitalize(mode) + "Router";
						if (tripRouter.getRoutingModule(drtRouterName) != null) {
							routingMode = drtRouterName;
						} else {
							routingMode = exMasConfig.getDrtRoutingMode();
						}
					} else {
						routingMode = mode;
					}

                    List<? extends PlanElement> tripElements;

                    Facility fromFacility = FacilitiesUtils.toFacility(trip.getOriginActivity(), facilities);
                    Facility toFacility = FacilitiesUtils.toFacility(trip.getDestinationActivity(), facilities);

                    tripElements = tripRouter.calcRoute(
                            routingMode,
                            fromFacility,
                            toFacility,
                            trip.getOriginActivity().getEndTime().orElse(0.0),
                            person,
                            trip.getTripAttributes());

                    if (tripElements == null || tripElements.isEmpty())
                        continue;

                    // Fix leg modes when DRT was routed via a different routing mode (e.g., car).
                    // The routing fallback produces legs with mode "car", but they represent
                    // DRT trips and must be scored with DRT mode params.
                    if (!routingMode.equals(mode)) {
                        for (PlanElement pe : tripElements) {
                            if (pe instanceof Leg leg && leg.getMode().equals(routingMode)) {
                                leg.setMode(mode);
                            }
                        }
                    }

                    double travelTime = 0.0;
                    double distance = 0.0;

                    for (PlanElement pe : tripElements) {
						if (pe instanceof Leg leg) {
                            travelTime += leg.getTravelTime().orElse(0.0);
                            if (leg.getRoute() != null) {
                                distance += leg.getRoute().getDistance();
                            }
                        }
                    }

					// Score via adapter (with tour context for GREEDY_PREFIX)
					double score = scoreViaAdapter(person, mode, tripElements, trip,
							tripIndex, params, previousTrips);

					modeCache.put(mode, new ModeAttributes(travelTime, distance, score));
				}

				// Determine best baseline mode (best score excluding DRT)
				String bestMode = null;
				double bestScore = Double.NEGATIVE_INFINITY;
				double bestTravelTime = 0.0;
				String drtMode = exMasConfig.getDrtMode();

				for (Map.Entry<String, ModeAttributes> entry : modeCache.entrySet()) {
					if (!entry.getKey().equals(drtMode) && entry.getValue().score() > bestScore) {
						bestScore = entry.getValue().score();
						bestMode = entry.getKey();
						bestTravelTime = entry.getValue().travelTime();
					}
                }

				if (bestMode != null) {
					personCache.put(tripIndex, modeCache);
					personBestModes.put(tripIndex, Map.entry(bestMode, bestScore));

					// Record best non-DRT mode for next trip's context (GREEDY_PREFIX)
					if (useGreedyPrefix) {
						previousTrips.add(new PreviousTripContext(
								tripIndex, bestMode,
								trip.getOriginActivity().getEndTime().orElse(0.0),
								bestTravelTime));
					}
				} else {
					personCache.put(tripIndex, modeCache);
				}

				// Calculate PT accessibility metrics
				double carTravelTime = Double.NaN;
				double ptTravelTime = Double.NaN;

				if (modeCache.containsKey(TransportMode.car)) {
					carTravelTime = modeCache.get(TransportMode.car).travelTime();
				} else {
					carTravelTime = routeModeForAccessibility(tripRouter, trip, person, TransportMode.car);
				}

				if (modeCache.containsKey(TransportMode.pt)) {
					ptTravelTime = modeCache.get(TransportMode.pt).travelTime();
				} else if (exMasConfig.getBaseModes().contains(TransportMode.pt)) {
					ptTravelTime = routeModeForAccessibility(tripRouter, trip, person, TransportMode.pt);
				}

				personPtAccessibility.put(tripIndex, new double[] { carTravelTime, ptTravelTime });

                tripIndex++;
			}

            cache.put(person.getId(), personCache);
			if (!personBestModes.isEmpty()) {
				bestBaselineModes.put(person.getId(), personBestModes);
			}
			if (!personPtAccessibility.isEmpty()) {
				ptAccessibilityMetrics.put(person.getId(), personPtAccessibility);
			}

			// Progress logging
			int processed = processedPersons.incrementAndGet();
			if (processed % logInterval == 0 || processed == totalPersons) {
				double percent = (processed * 100.0) / totalPersons;
				log.info("  Mode caching progress: {}/{} ({}%)", processed, totalPersons,
						String.format("%.1f", percent));
			}
        });

		long elapsed = System.currentTimeMillis() - startTime;
		double seconds = elapsed / 1000.0;
		log.info("Mode caching complete: {} persons processed in {}s", totalPersons, String.format("%.1f", seconds));
    }

	/**
	 * Score a trip via the adapter, applying opportunity cost if needed.
	 */
	private double scoreViaAdapter(Person person, String mode,
			List<? extends PlanElement> tripElements, TripStructureUtils.Trip trip,
			int tripIndex, ScoringParameters params,
			List<PreviousTripContext> previousTrips) {

		TripScoreRequest request = new TripScoreRequest(
				person, mode, tripElements,
				trip.getOriginActivity(), trip.getDestinationActivity(),
				trip.getOriginActivity().getEndTime().orElse(0.0),
				trip.getTripAttributes(), tripIndex, previousTrips);

		TripScoreResult result = adapter.scoreTrip(request);
		double score = result.utility();

		// Apply opportunity cost only if adapter doesn't already include it
		if (exMasConfig.isIncludeOpportunityCost() && !adapter.includesOpportunityCost()) {
			double totalTravelTime = 0.0;
			for (PlanElement pe : tripElements) {
				if (pe instanceof Leg leg) {
					totalTravelTime += leg.getTravelTime().orElse(0.0);
				}
			}
			score -= totalTravelTime * params.marginalUtilityOfPerforming_s;
		}

		return score;
	}

	/**
	 * Filters modes based on person attributes following MATSim conventions.
	 */
	private Set<String> filterAvailableModes(Person person, Set<String> modes) {
		Set<String> availableModes = new java.util.HashSet<>();

		for (String mode : modes) {
			if (TransportMode.car.equals(mode)) {
				boolean hasLicense = !"no".equals(PersonUtils.getLicense(person));
				boolean carAvailable = !"never".equals(PersonUtils.getCarAvail(person));

				if (hasLicense && carAvailable) {
					availableModes.add(mode);
				}
			} else if ("bike".equals(mode)) {
				availableModes.add(mode);
			} else {
				availableModes.add(mode);
			}
		}

		return availableModes;
	}

    public Map<Integer, Map<String, ModeAttributes>> getAttributes(Id<Person> personId) {
        return cache.get(personId);
    }

	public Map<Id<Person>, Map<Integer, Entry<String, Double>>> getBestBaselineModes() {
		return bestBaselineModes;
	}

	public Map<Id<Person>, Map<Integer, double[]>> getPtAccessibilityMetrics() {
		return ptAccessibilityMetrics;
	}

	public Map<Id<Person>, Map<Integer, Map<String, ModeAttributes>>> getAllModeAttributes() {
		return cache;
	}

	private double routeModeForAccessibility(TripRouter tripRouter, TripStructureUtils.Trip trip,
			Person person, String mode) {
		try {
			Facility fromFacility = FacilitiesUtils.toFacility(trip.getOriginActivity(), facilities);
			Facility toFacility = FacilitiesUtils.toFacility(trip.getDestinationActivity(), facilities);

			List<? extends PlanElement> tripElements = tripRouter.calcRoute(
					mode,
					fromFacility,
					toFacility,
					trip.getOriginActivity().getEndTime().orElse(0.0),
					person,
					trip.getTripAttributes());

			if (tripElements == null || tripElements.isEmpty()) {
				return Double.NaN;
			}

			double travelTime = 0.0;
			for (PlanElement pe : tripElements) {
				if (pe instanceof Leg leg) {
					travelTime += leg.getTravelTime().orElse(0.0);
				}
			}
			return travelTime;
		} catch (Exception e) {
			return Double.NaN;
		}
	}
}
