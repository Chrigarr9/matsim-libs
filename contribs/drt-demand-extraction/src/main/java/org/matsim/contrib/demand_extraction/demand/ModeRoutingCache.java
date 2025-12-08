package org.matsim.contrib.demand_extraction.demand;

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
import org.matsim.core.config.Config;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.router.TripRouter;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scoring.ScoringFunction;
import org.matsim.core.scoring.ScoringFunctionFactory;
import org.matsim.core.scoring.functions.ModeUtilityParameters;
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
    private final ScoringFunctionFactory scoringFunctionFactory;
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
            ScoringFunctionFactory scoringFunctionFactory,
            ScoringParametersForPerson scoringParametersForPerson,
            Config config, Network network, ActivityFacilities facilities) {
        this.tripRouterProvider = tripRouterProvider;
        this.exMasConfig = exMasConfig;
        this.scoringFunctionFactory = scoringFunctionFactory;
        this.scoringParametersForPerson = scoringParametersForPerson;
        this.config = config;
        this.network = network;
        this.facilities = facilities;
    }

    public void cacheModes(Population population) {
		log.info("Starting mode caching for {} persons...", population.getPersons().size());
		long startTime = System.currentTimeMillis();

		// Thread-safe progress tracking
		AtomicInteger processedPersons = new AtomicInteger(0);
		int totalPersons = population.getPersons().size();
		int logInterval = Math.max(1, totalPersons / 10); // Log every 10%

        population.getPersons().values().parallelStream().forEach(person -> {
            TripRouter tripRouter = tripRouterProvider.get();
            Map<Integer, Map<String, ModeAttributes>> personCache = new ConcurrentHashMap<>();
			Map<Integer, Entry<String, Double>> personBestModes = new ConcurrentHashMap<>();
			Map<Integer, double[]> personPtAccessibility = new ConcurrentHashMap<>();
            List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(person.getSelectedPlan());

            // Get scoring params for person (needed for opportunity cost)
            ScoringParameters params = scoringParametersForPerson.getScoringParameters(person);

            int tripIndex = 0;
            for (TripStructureUtils.Trip trip : trips) {
                Map<String, ModeAttributes> modeCache = new ConcurrentHashMap<>();

				// Filter modes based on person attributes (consistent with MATSim conventions)
				Set<String> allModes = new HashSet<>(exMasConfig.getBaseModes());
				allModes.add(exMasConfig.getDrtMode()); // Add DRT to modes to evaluate
				Set<String> availableModes = filterAvailableModes(person, allModes);

				for (String mode : availableModes) {
					// Determine routing mode for this travel mode
					// DRT: Check if DRT routing module exists, otherwise use configured fallback
					// (e.g., "car")
					// Other modes: Use mode name itself as routing mode
					String routingMode;
					if (mode.equals(exMasConfig.getDrtMode())) {	
						// For DRT: check if dedicated DRT routing module exists in TripRouter
						// Routing module name: "direct{DrtMode}Router" (e.g., "directDrtRouter")
						String drtRouterName = "direct" + StringUtils.capitalize(mode) + "Router";
						if (tripRouter.getRoutingModule(drtRouterName) != null) {
							routingMode = drtRouterName; // Use DRT-specific network-filtered routing
						} else {
							routingMode = exMasConfig.getDrtRoutingMode(); // Fallback to configured routing (typically
																			// "car")
						}
					} else {
						routingMode = mode; // Standard modes route as themselves
					}
					
                    List<? extends PlanElement> tripElements;

                    // Convert activities to facilities for routing
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

					// Calculate monetary cost based on mode parameters
					// Cost = distance * monetaryDistanceRate (in monetary units, e.g., EUR)
					double cost = calculateTripCost(mode, distance, params);

                    // Calculate Score
                    double score = calculateTripScore(person, trip, tripElements, params);

					// Store mode attributes: travel time, distance, cost, and score
					// Note: For budget calculation, only 'score' is used to compare modes.
					// Travel time, distance, and cost are retained for debugging and analytics
					// to help understand why certain modes have higher/lower utilities.
					modeCache.put(mode, new ModeAttributes(travelTime, distance, cost, score));
				}

				// Determine best baseline mode (best score excluding DRT)
				String bestMode = null;
				double bestScore = Double.NEGATIVE_INFINITY;
				String drtMode = exMasConfig.getDrtMode();

				for (Map.Entry<String, ModeAttributes> entry : modeCache.entrySet()) {
					if (!entry.getKey().equals(drtMode) && entry.getValue().score > bestScore) {
						bestScore = entry.getValue().score;
						bestMode = entry.getKey();
					}
                }

				if (bestMode != null) {
					personCache.put(tripIndex, modeCache);
					personBestModes.put(tripIndex, Map.entry(bestMode, bestScore));
				} else {
					// No valid baseline mode found - skip this trip
					personCache.put(tripIndex, modeCache);
				}

				// Calculate PT accessibility metrics (car vs PT travel time)
				// IMPORTANT: Car travel time is ALWAYS calculated regardless of car availability
				// This allows comparing PT accessibility even for agents without car access
				double carTravelTime = Double.NaN;
				double ptTravelTime = Double.NaN;

				// Get car travel time (may already be in modeCache, otherwise route it now)
				if (modeCache.containsKey(TransportMode.car)) {
					carTravelTime = modeCache.get(TransportMode.car).travelTime;
				} else {
					// Person doesn't have car available - route it anyway for PT accessibility
					carTravelTime = routeModeForAccessibility(tripRouter, trip, person, TransportMode.car);
				}

				// Get PT travel time (may already be in modeCache, otherwise route it now)
				if (modeCache.containsKey(TransportMode.pt)) {
					ptTravelTime = modeCache.get(TransportMode.pt).travelTime;
				} else if (exMasConfig.getBaseModes().contains(TransportMode.pt)) {
					// PT is configured but not available - route it anyway
					ptTravelTime = routeModeForAccessibility(tripRouter, trip, person, TransportMode.pt);
				}

				// Store PT accessibility metrics: [carTravelTime, ptTravelTime]
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
	 * Filters modes based on person attributes following MATSim conventions.
	 * - Car: requires license != "no" AND carAvail != "never" (see PersonUtils)
	 * - Bike: always available (MATSim has no standard bike availability attribute)
	 * - PT, Walk, DRT: always available
	 * 
	 * This ensures we only route and score modes that the person can actually use,
	 * avoiding infeasible baseline comparisons in budget calculation.
	 */
	private Set<String> filterAvailableModes(Person person, Set<String> modes) {
		Set<String> availableModes = new java.util.HashSet<>();

		for (String mode : modes) {
			// Check car availability using MATSim conventions (see CarModeAvailability in
			// discrete_mode_choice)
			if (TransportMode.car.equals(mode)) {
				boolean hasLicense = !"no".equals(PersonUtils.getLicense(person));
				boolean carAvailable = !"never".equals(PersonUtils.getCarAvail(person));

				if (hasLicense && carAvailable) {
					availableModes.add(mode);
				}
				// If not available, skip this mode entirely (don't route or score it)
			} else if ("bike".equals(mode)) {
				// Bike: MATSim has no standard availability attribute, assume always available
				availableModes.add(mode);
			} else {
				// PT, Walk, DRT, and other modes: always available
				availableModes.add(mode);
			}
		}

		return availableModes;
	}

	/**
	 * Calculates the monetary cost of a trip based on mode parameters.
	 * Cost is calculated as: distance * monetaryDistanceRate
	 * 
	 * @param mode     The transport mode
	 * @param distance The trip distance in meters
	 * @param params   The scoring parameters containing mode-specific cost rates
	 * @return The monetary cost in currency units (e.g., EUR)
	 */
	private double calculateTripCost(String mode, double distance, ScoringParameters params) {
		ModeUtilityParameters modeParams = params.modeParams.get(mode);
		if (modeParams == null) {
			// Fallback for modes without explicit parameters (should rarely happen after
			// filtering)
			return 0.0;
		}

		// MATSim's monetaryDistanceCostRate is the cost per meter (e.g., EUR/m)
		// Total cost = distance (m) * rate (EUR/m)
		double cost = distance * modeParams.monetaryDistanceCostRate;

		// Add per-trip constant (not daily constant)
		// The per-trip constant is applied to every trip regardless of whether it's the
		// first trip
		cost += modeParams.constant;

		// TODO: Add daily monetary constant (dailyMonetaryConstant)
		// This requires tracking which modes have been used today per person
		// Should only be added once per mode per day
		// Implementation would need:
		// 1. Track per person which modes used today (Map<PersonId, Set<Mode>>)
		// 2. Add dailyMonetaryConstant only for first trip of day per mode
		// 3. Update tracking after adding the constant

		return cost;
	}

    private double calculateTripScore(Person person, TripStructureUtils.Trip originalTrip,
            List<? extends PlanElement> newRoute, ScoringParameters params) {
        ScoringFunction sf = scoringFunctionFactory.createNewScoringFunction(person);

		// Score only the legs (travel time, distance, monetary cost)
		// We don't score activities because we're comparing alternatives for the same
		// trip,
		// which have the same origin and destination activities.
        for (PlanElement pe : newRoute) {
			if (pe instanceof Leg leg) {
				sf.handleLeg(leg);
            }
        }

		// Note: Opportunity cost of time is NOT included in this simplified
		// calculation.
		// For budget calculation, we're comparing travel utilities between modes.
		// Since all modes connect the same O-D pair at the same departure time,
		// the opportunity cost difference would be:
		// opportunityCost = (newTravelTime - baselineTravelTime) *
		// marginalUtilityOfPerforming
		//
		// This is implicitly captured in the travel time disutility of the legs.
		// A full implementation would need to account for:
		// - Fixed vs flexible activity durations
		// - Impact on subsequent activities in the daily schedule
		//
		// TODO: Consider adding opportunity cost if activity schedules are tightly
		// constrained

		sf.finish();
        return sf.getScore();
    }

    public Map<Integer, Map<String, ModeAttributes>> getAttributes(Id<Person> personId) {
        return cache.get(personId);
    }

	public Map<Id<Person>, Map<Integer, Entry<String, Double>>> getBestBaselineModes() {
		return bestBaselineModes;
	}

	/**
	 * Get PT accessibility metrics for all persons.
	 * Returns Map: Person ID -> Trip Index -> [carTravelTime, ptTravelTime]
	 * Car travel time is ALWAYS calculated regardless of car availability.
	 */
	public Map<Id<Person>, Map<Integer, double[]>> getPtAccessibilityMetrics() {
		return ptAccessibilityMetrics;
	}

	/**
	 * Route a mode purely to get travel time for accessibility comparison.
	 * This is called for modes the person doesn't have available (e.g., car for non-car-owners).
	 *
	 * @param tripRouter the trip router
	 * @param trip       the trip to route
	 * @param person     the person (for routing context)
	 * @param mode       the mode to route
	 * @return travel time in seconds, or Double.NaN if routing fails
	 */
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
			// Routing failed - return NaN
			return Double.NaN;
		}
	}
}
