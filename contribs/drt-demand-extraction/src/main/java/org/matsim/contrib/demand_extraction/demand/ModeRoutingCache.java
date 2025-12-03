package org.matsim.contrib.demand_extraction.demand;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.Route;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.population.routes.RouteFactories;
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
	private final Map<Id<Person>, Map<Integer, String>> bestBaselineModes = new ConcurrentHashMap<>();

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
        population.getPersons().values().parallelStream().forEach(person -> {
            TripRouter tripRouter = tripRouterProvider.get();
            Map<Integer, Map<String, ModeAttributes>> personCache = new ConcurrentHashMap<>();
			Map<Integer, String> personBestModes = new ConcurrentHashMap<>();
            List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(person.getSelectedPlan());

            // Get scoring params for person (needed for opportunity cost)
            ScoringParameters params = scoringParametersForPerson.getScoringParameters(person);

            int tripIndex = 0;
            for (TripStructureUtils.Trip trip : trips) {
                Map<String, ModeAttributes> modeCache = new ConcurrentHashMap<>();

				// Filter modes based on person attributes (consistent with MATSim conventions)
				Set<String> allModes = new java.util.HashSet<>(exMasConfig.getBaseModes());
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
						if (tripRouter.getRoutingModule(mode) != null) {
							routingMode = mode; // Use DRT-specific routing
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

                    // If DRT mode, adjust route for access/egress
                    if (mode.equals(exMasConfig.getDrtMode())) {
                        tripElements = adjustDrtTripElements(tripElements, population.getFactory().getRouteFactories(),
                                fromFacility, toFacility);
                    }

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
					personBestModes.put(tripIndex, bestMode);
				} else {
					// No valid baseline mode found - skip this trip
					personCache.put(tripIndex, modeCache);
				}

                tripIndex++;
			}

            cache.put(person.getId(), personCache);
			if (!personBestModes.isEmpty()) {
				bestBaselineModes.put(person.getId(), personBestModes);
			}
        });
    }

    private List<? extends PlanElement> adjustDrtTripElements(List<? extends PlanElement> tripElements,
            RouteFactories routingFactories,
            Facility fromFacility, Facility toFacility) {
        boolean containsDrtLeg = false;
        for (PlanElement element : tripElements) {
            if (element instanceof Leg leg && exMasConfig.getDrtMode().equals(leg.getMode())) {
                containsDrtLeg = true;
            }
            if (element instanceof Leg leg && TransportMode.walk.equals(leg.getMode())) {
                double walkDist = exMasConfig.getMinDrtAccessEgressDistance();
                double walkSpeed = config.routing().getOrCreateModeRoutingParams(TransportMode.walk)
                        .getTeleportedModeSpeed();
                if (walkSpeed == 0.0) {
                    // Fallback to default walk speed if not configured (0.833 m/s = 3 km/h)
                    walkSpeed = 0.833333333;
                }
                double walkTime = walkDist / walkSpeed;
                Route route = routingFactories.createRoute(Route.class, fromFacility.getLinkId(),
                        toFacility.getLinkId());
                route.setTravelTime(walkTime);
                route.setDistance(walkDist);
                leg.setDepartureTime(leg.getDepartureTime().orElse(0.0) + leg.getTravelTime().orElse(0.0)
                        - route.getTravelTime().orElse(0));
                leg.setRoute(route);
                leg.setTravelTime(walkTime);
            }
        }
        if (!containsDrtLeg) {
            return new ArrayList<>();
        }
        return tripElements;
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

		// Add daily monetary constant if this is the first trip of the day for this
		// mode
		// Note: In this cache, we calculate each trip independently, so we don't track
		// whether this is the first trip. The daily constant is properly handled in
		// the scoring function during actual simulation.

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

	public Map<Id<Person>, Map<Integer, String>> getBestBaselineModes() {
		return bestBaselineModes;
	}
}
