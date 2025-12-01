package org.matsim.contrib.exmas.demand;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.Route;
import org.matsim.contrib.exmas.config.ExMasConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.RouteFactories;
import org.matsim.core.router.TripRouter;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scoring.ScoringFunction;
import org.matsim.core.scoring.ScoringFunctionFactory;
import org.matsim.core.scoring.functions.ScoringParameters;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;
import org.matsim.core.utils.misc.Time;
import org.matsim.facilities.ActivityFacilities;
import org.matsim.facilities.Facility;
import org.matsim.facilities.FacilitiesUtils;
import org.matsim.utils.objectattributes.attributable.Attributes;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class ModeRoutingCache {

    private final Provider<TripRouter> tripRouterProvider;
    private final ExMasConfigGroup exMasConfig;
    private final ScoringFunctionFactory scoringFunctionFactory;
    private final ScoringParametersForPerson scoringParametersForPerson;
    private final Config config;
    private final Network network;
    private final ActivityFacilities facilities;
    private final Map<Id<Person>, Map<Integer, Map<String, ModeAttributes>>> cache = new ConcurrentHashMap<>();

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
            List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(person.getSelectedPlan());

            // Get scoring params for person (needed for opportunity cost)
            ScoringParameters params = scoringParametersForPerson.getScoringParameters(person);

            int tripIndex = 0;
            for (TripStructureUtils.Trip trip : trips) {
                Map<String, ModeAttributes> modeCache = new ConcurrentHashMap<>();

                Set<Map.Entry<String, String>> modes = exMasConfig.getBaseModes().entrySet();
                modes.add(Map.entry(exMasConfig.getDrtMode(), TransportMode.car)); // DRT routed as car
                for (Map.Entry<String, String> entry : modes) {
                    String mode = entry.getKey();
                    String routingMode = entry.getValue();

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
                        if (pe instanceof Leg) {
                            Leg leg = (Leg) pe;
                            travelTime += leg.getTravelTime().orElse(0.0);
                            if (leg.getRoute() != null) {
                                distance += leg.getRoute().getDistance();
                            }
                        }
                    }

                    // Calculate Score
                    double score = calculateTripScore(person, trip, tripElements, params);

                    // C: Why do we need travel time, distance and cost here? I think for our
                    // calculation the score is the only interesting metric.
                    modeCache.put(mode, new ModeAttributes(travelTime, distance, 0.0, score));
                }
                personCache.put(tripIndex, modeCache);
                tripIndex++;

            }
            cache.put(person.getId(), personCache);
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

    private double calculateTripScore(Person person, TripStructureUtils.Trip originalTrip,
            List<? extends PlanElement> newRoute, ScoringParameters params) {
        ScoringFunction sf = scoringFunctionFactory.createNewScoringFunction(person);

        // // Handle Origin Activity (Departure)
        // // We need to set the end time to the departure time of the trip
        // Activity origin = originalTrip.getOriginActivity();
        // // We shouldn't modify the original activity.
        // // But handleActivity reads it.
        // sf.handleActivity(origin);

        // Handle Legs
        for (PlanElement pe : newRoute) {
            if (pe instanceof Leg) {
                sf.handleLeg((Leg) pe);
            }
        }

        // C:Yes, the opportunity cost should be handled later. For now we will just use
        // the legs for scoring.
        // // Subtract Opportunity Cost (beta_performing * travelTime)
        // // Because longer travel time means less time for the NEXT activity.
        // // C:I think this is not fully correct as we are now subtracting the utility
        // of performing with the whole travel time.
        // // But it would be only the extra travel time, so the time not planned for
        // performing.
        // // Next activity start time subtracted by the actual arrival time. So I think
        // we should change that.
        // // Handle Destination Activity (Arrival)
        // // We don't know the duration, so we just handle the arrival.
        // // ScoringFunction usually scores arrival (late penalty).
        // Activity dest = originalTrip.getDestinationActivity();
        // sf.handleActivity(dest);

        sf.finish();
        // score -= params.marginalUtilityOfPerforming_s * travelTime;

        return sf.getScore();
    }

    public Map<Integer, Map<String, ModeAttributes>> getAttributes(Id<Person> personId) {
        return cache.get(personId);
    }
}
