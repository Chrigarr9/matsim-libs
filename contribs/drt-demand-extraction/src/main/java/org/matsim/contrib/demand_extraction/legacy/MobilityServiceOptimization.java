

package com.vwgroup.msf.utilities.misc.optimization.optimizationUtils.availableServicesOptimizationUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.locationtech.jts.geom.Geometry;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.IdMap;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.Route;
import org.matsim.contrib.drt.optimizer.constraints.DefaultDrtOptimizationConstraintsSet;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.shared_mobility.run.SharingConfigGroup;
import org.matsim.contrib.shared_mobility.run.SharingServiceConfigGroup;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.events.StartupEvent;
import org.matsim.core.controler.listener.StartupListener;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.AnalysisMainModeIdentifier;
import org.matsim.core.router.TripRouter;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.router.TripStructureUtils.Trip;
import org.matsim.core.scoring.ScoringFunctionFactory;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.core.utils.timing.TimeInterpretation;
import org.matsim.facilities.ActivityFacilities;
import org.matsim.facilities.FacilitiesUtils;
import org.matsim.facilities.Facility;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.vwgroup.msf.utilities.extensions.cache.Router;
import com.vwgroup.msf.utilities.misc.ModeDefinitions.TransportModes;
import com.vwgroup.msf.utilities.misc.optimization.Decoder;
import com.vwgroup.msf.utilities.misc.optimization.FitnessFunctions.FitnessFunction;
import com.vwgroup.msf.utilities.misc.optimization.ParallelOptimizationEngine;
import com.vwgroup.msf.utilities.misc.optimization.TerminationCriteria;
import com.vwgroup.msf.utilities.misc.optimization.Trial;
import com.vwgroup.msf.utilities.misc.optimization.Trials;
import com.vwgroup.msf.utilities.misc.optimization.optimizationUtils.OptimizationData;
import com.vwgroup.msf.utilities.misc.optimization.optimizationUtils.availableServicesOptimizationUtils.CustomTripsWriter.CustomLegsWriterExtension;
import com.vwgroup.msf.utilities.misc.optimization.optimizationUtils.availableServicesOptimizationUtils.CustomTripsWriter.CustomTripsWriterExtension;
import com.vwgroup.msf.utilities.misc.optimization.parameter.CategoricalParameter;
import com.vwgroup.msf.utilities.misc.optimization.parameter.DoubleParameter;
import com.vwgroup.msf.utilities.misc.optimization.parameter.Parameter;
import com.vwgroup.msf.utilities.misc.optimization.searchAlgorithms.GridSampler;
import com.vwgroup.msf.utilities.scoring.ModalParams;
import com.vwgroup.msf.utilities.scoring.ScoringUtils;
import com.vwgroup.msf.utilities.scoring.functions.TripScoringDelegation;
import com.vwgroup.msf.utilities.scoring.functions.TripScoringFactory;
import com.vwgroup.msf.utilities.scoring.parameter.ModeChoiceCostParams;

public class MobilityServiceOptimization implements StartupListener {

    private final double walkSpeed;

    private final Population population;
    private final TripScoringFactory tripScoringFactory;
    private final ScoringFunctionFactory scoringFunctionFactory;
    private final Router tripRouteCache;
    private final TripRouter tripRouter;
    private final ActivityFacilities facilities;
    private final List<String> modes = List.of("ad_drt");
    private final Scenario scenario;
    private final int numberOfThreads = 1;
    // Map to accumulate trips for default as well as trial runs.
    private final Map<Id<Person>, List<Trip>> tripsDatabase = new HashMap<>();
    private final Set<Link> relevantLinks;
    private final IdMap<Person, Plan> personTripsMap = new IdMap<>(Person.class);
    private final AnalysisMainModeIdentifier mainModeIdentifier;
    private final TimeInterpretation timeInterpretation;
    private final List<Geometry> targetAreas;

    @Inject
    public MobilityServiceOptimization(Population population, TripScoringFactory tripScoringFactory,
            ActivityFacilities facilities,
            ScoringFunctionFactory scoringFunctionFactory, Router tripRouteCache,
            TripRouter tripRouter, Scenario scenario,
            AnalysisMainModeIdentifier mainModeIdentifier,
            TimeInterpretation timeInterpretation, @Named("targetAreas") List<Geometry> targetAreas,
            @Named("bufferSize") int bufferSize, @Named("relevantLinks") Set<Link> relevantLinks) {

        this.population = population;
        this.tripScoringFactory = tripScoringFactory;
        this.scoringFunctionFactory = scoringFunctionFactory;
        this.tripRouteCache = tripRouteCache;
        this.facilities = facilities;
        this.tripRouter = tripRouter;
        this.scenario = scenario;
        this.mainModeIdentifier = mainModeIdentifier;
        this.timeInterpretation = timeInterpretation;
        this.walkSpeed = scenario.getConfig().routing().getOrCreateModeRoutingParams("walk").getTeleportedModeSpeed();
        this.targetAreas = targetAreas.stream()
                .map(geo -> geo.buffer(bufferSize))
                .collect(Collectors.toList());
        this.relevantLinks = relevantLinks;

    }

    @Override
    public void notifyStartup(StartupEvent event) {
        // Score default trips for each person
        scoreDefaultTrips(population.getPersons().values());
        // Flush the default trips to file and clear them from memory
        flushAndClearTrips();

        Trials trials = new Trials();
        Trial trial = createTrial();
        trials.fillTrails(trial, numberOfThreads);

        Decoder decoder = createDecoder();

        OptimizationData optimizationData = new OptimizationDataOpt(trial);
        ParallelOptimizationEngine optimizationEngine = new ParallelOptimizationEngine(decoder, numberOfThreads,
                optimizationData);
        optimizationEngine.setTrials(trials);

        GridSampler gridSampler = new GridSampler();
        gridSampler.setWriteResults(scenario.getConfig().controller().getOutputDirectory());
        optimizationEngine.setGlobalSearchStrategy(gridSampler);
        optimizationEngine.addTerminationCriteria(
                new TerminationCriteria.MaxTrials((int) gridSampler.calculateTotalCombinations(trial)));
        optimizationEngine.runOptimization();

    }

    /**
     * Creates a Trial instance with required parameters.
     */
    private Trial createTrial() {
        Trial trial = new Trial();
        trial.addParameter(new CategoricalParameter("mode", modes));
        trial.addParameter(new DoubleParameter("detour_factor", 1.0, 4.0, 0.2));
        trial.addParameter(new DoubleParameter("fare", 0.0, 5.0, 0.2));
        trial.addParameter(new DoubleParameter("walking_distance", 100.0, 100.0, 100.0));
        return trial;
    }

    /**
     * Creates and returns a Decoder which updates service constraints based on the trial parameters.
     */
    private Decoder createDecoder() {
        return new Decoder() {
            @Override
            public void decode(OptimizationData optimizationData) {
                String mode = optimizationData.getTrial().getParameters().get("mode").getValue().toString();

                if (mode.contains("drt")) {
                    updateDrtConstraints(mode, optimizationData);
                } else if (mode.contains("sharing")) {
                    updateSharingConstraints(mode);
                }
                updateCostParameters(mode, optimizationData);
            }

            private void updateDrtConstraints(String mode, OptimizationData optimizationData) {
                for (DrtConfigGroup drtCfg : MultiModeDrtConfigGroup.get(scenario.getConfig()).getModalElements()) {
                    if (mode.equals(drtCfg.getMode())) {
                        DefaultDrtOptimizationConstraintsSet constraints = (DefaultDrtOptimizationConstraintsSet) drtCfg
                                .addOrGetDrtOptimizationConstraintsParams()
                                .addOrGetDefaultDrtOptimizationConstraintsSet();
                        constraints.maxTravelTimeBeta = 0.0;
                        constraints.maxWaitTime = 0.0;
                        constraints.rejectRequestIfMaxWaitOrTravelTimeViolated = true;
                        constraints.maxAbsoluteDetour = Double.POSITIVE_INFINITY;
                        constraints.maxAllowedPickupDelay = Double.POSITIVE_INFINITY;
                        constraints.maxWalkDistance = Double.POSITIVE_INFINITY;
                        constraints.maxTravelTimeAlpha = ((Number) optimizationData.getTrial()
                                .getParameters().get("detour_factor").getValue()).doubleValue();
                    }
                }
            }

            private void updateSharingConstraints(String mode) {
                SharingConfigGroup sharingCfg = ConfigUtils.addOrGetModule(scenario.getConfig(),
                        SharingConfigGroup.class);
                for (SharingServiceConfigGroup service : sharingCfg.getServices()) {
                    if (mode.equals(service.getMode())) {
                        service.setMaximumAccessEgressDistance(5.0);
                    }
                }
            }

            private void updateCostParameters(String mode, OptimizationData optimizationData) {
                ModalParams modeParams = tripScoringFactory.getModalParams();
                ModeChoiceCostParams costParams = modeParams.getCostParameters(mode);
                costParams.accessEgressMin = 0;
                costParams.billingIntervalCostEuro = 0;
                costParams.initialCostEuro = 0;
                costParams.maxPerTripEuro = Double.POSITIVE_INFINITY;
                costParams.minPerTripEuro = 0;
                costParams.costPerKmEuro = ((Number) optimizationData.getTrial()
                        .getParameters().get("fare").getValue()).doubleValue();
            }
        };
    }

    /**
     * Writes (appends) the current contents of tripsDatabase to CSV files using the CustomTripsWriter,
     * then clears the in-memory trips.
     */
    private synchronized void flushAndClearTrips() {
        String outputDir = scenario.getConfig().controller().getOutputDirectory();
        // File names as requested
        String tripsFile = Path.of(outputDir, "alternativeTrips.csv").toString();
        String legsFile = Path.of(outputDir, "alternativeLegs.csv").toString();

        // Create a CustomTripsWriter instance using no-extension implementations.
        CustomTripsWriterExtension tripExtension = new CustomTripsWriterExtension() {
            @Override
            public String[] getAdditionalTripHeader() {
                return new String[] { "score", "parameter", "subpopulation", "withinArea" };
            }

            @Override
            public List<String> getAdditionalTripColumns(Trip trip) {
                return List.of(
                        trip.getTripAttributes().getAttribute("score").toString(),
                        trip.getTripAttributes().getAttribute("params").toString(),
                        trip.getTripAttributes().getAttribute("subpopulation") == null ? "default"
                                : trip.getTripAttributes().getAttribute("subpopulation").toString(),
                        trip.getTripAttributes().getAttribute("withinArea") == null ? "false"
                                : trip.getTripAttributes().getAttribute("withinArea").toString());
            }
        };

        CustomLegsWriterExtension legsExtension = new CustomLegsWriterExtension() {
            @Override
            public String[] getAdditionalLegHeader() {
                return new String[0];
            }

            @Override
            public List<String> getAdditionalLegColumns(Trip experiencedTrip, Leg experiencedLeg) {
                return Collections.emptyList();
            }
        };

        CustomTripsWriter.CustomTimeWriter timeWriter = new CustomTripsWriter.DefaultTimeWriter();

        CustomTripsWriter customTripsWriter = new CustomTripsWriter(scenario, tripExtension, legsExtension,
                mainModeIdentifier, timeWriter);

        // Write trips in append mode
        customTripsWriter.write(tripsDatabase, tripsFile, legsFile, true);

        // Clear the in-memory trips data
        tripsDatabase.clear();
    }

    /**
     * Scores the default trips for each person and stores them in the trips database.
     */
    private void scoreDefaultTrips(Collection<? extends Person> persons) {
        for (Person p : persons) {
            Plan selectedPlan = p.getSelectedPlan();
            TripScoringDelegation tripScoring = tripScoringFactory.get(p);
            TripStructureUtils.getTrips(selectedPlan).forEach(t -> {
                double tripScore = tripScoring.scoreTrip(p,
                        TripStructureUtils.identifyMainMode(t.getTripElements()), t, true);

                Activity origin = PopulationUtils.createActivity(t.getOriginActivity());
                Activity destination = PopulationUtils.createActivity(t.getDestinationActivity());

                Facility fromFacility = FacilitiesUtils.toFacility(origin, facilities);
                Facility toFacility = FacilitiesUtils.toFacility(destination, facilities);
                boolean fromFacilityInArea = true;
                boolean toFacilityInArea = true;
                if (!targetAreas.isEmpty()) {
                    fromFacilityInArea = isFacilityInAreas(fromFacility);
                    toFacilityInArea = isFacilityInAreas(toFacility);

                    if (!fromFacilityInArea && !toFacilityInArea) {
                        return; // Skip trips with no valid facilities in the target area
                    }
                }
                t.getTripAttributes().putAttribute("score", tripScore);
                t.getTripAttributes().putAttribute("params", Map.of());
                t.getTripAttributes().putAttribute("withinArea", fromFacilityInArea && toFacilityInArea);
                t.getTripAttributes().putAttribute("subpopulation", PopulationUtils.getSubpopulation(p));
                tripsDatabase.computeIfAbsent(p.getId(), k -> new ArrayList<>()).add(t);
            });
        }
    }

    /**
     * Checks whether an activity’s facility is located within any of the combined activity areas.
     */
    private boolean isFacilityInAreas(Facility facility) {
        if (facility == null)
            return false;
        return targetAreas.stream()
                .anyMatch(shape -> shape.contains(MGC.coord2Point(facility.getCoord())));
    }

    /**
     * Private inner class that extends OptimizationData and provides a runnable which
     * iterates over the population to process and score alternative trips.
     */
    private class OptimizationDataOpt extends OptimizationData {

        public OptimizationDataOpt(Trial trial) {
            super(trial);
        }

        @Override
        public Runnable getRunnable() {
            return this::processPopulationTrips;
        }

        @Override
        public OptimizationData copy(Trial trial) {
            OptimizationDataOpt copy = new OptimizationDataOpt(trial);
            copy.setFitnessFunctions(getFitnessFunctions().stream().map(FitnessFunction::copy).toList());
            if (getPruningStrategy() != null) {
                copy.setPruningStrategy(getPruningStrategy().copy());
            }
            return copy;
        }

        /**
         * Iterates over all persons and their trips to compute alternative routing and scores.
         * At the end of processing each trial, it flushes the computed trips to file (via the CustomTripsWriter)
         * and clears the in-memory data.
         */
        private void processPopulationTrips() {
            String mode = getTrial().getParameters().get("mode").getValue().toString();
            population.getPersons().values().forEach(person -> TripStructureUtils.getTrips(person.getSelectedPlan())
                    .forEach(trip -> processTrip(person, trip, mode)));
            // Flush the trial's trips to file and remove them from memory
            flushAndClearTrips();
        }

        /**
         * Processes a single trip by recalculating the route, adjusting legs if necessary,
         * inserting the trip into a new plan and scoring it.
         */
        private void processTrip(Person person, Trip trip, String mode) {
            TripScoringDelegation tripScoring = tripScoringFactory.get(person);
            Activity origin = PopulationUtils.createActivity(trip.getOriginActivity());
            Activity destination = PopulationUtils.createActivity(trip.getDestinationActivity());
            List<PlanElement> planElements = new ArrayList<>();
            planElements.add(origin);
            planElements.add(destination);

            Facility fromFacility = FacilitiesUtils.toFacility(origin, facilities);
            Facility toFacility = FacilitiesUtils.toFacility(destination, facilities);
            boolean fromFacilityInArea = true;
            boolean toFacilityInArea = true;

            if (!targetAreas.isEmpty()) {
                fromFacilityInArea = isFacilityInAreas(fromFacility);
                toFacilityInArea = isFacilityInAreas(toFacility);

                if (!fromFacilityInArea && !toFacilityInArea) {
                    return; // Skip trips with no valid facilities in the target area
                }
            }

            List<? extends PlanElement> tripElements = tripRouteCache.calcRoute(tripRouter, mode,
                    fromFacility, toFacility,
                    trip.getLegsOnly().get(0).getDepartureTime().seconds(),
                    person.getSelectedPlan().getPerson(), null);

            if (!ScoringUtils.containsMainMode(tripElements, mode)) {
                return; // Skip trips that could not be routed with the intended mode
            }

            if ("ad_drt".equals(mode)) {
                tripElements = adjustAdDrtTripElements(tripElements, fromFacility, toFacility);
            }

            TripRouter.insertTrip(planElements, origin, tripElements, destination);
            Trip tripCandidate = TripStructureUtils.getTrips(planElements).get(0);
            double utility = tripScoring.scoreTrip(person.getSelectedPlan().getPerson(), mode, tripCandidate, true);

            tripCandidate.getTripAttributes().putAttribute("score", utility);
            tripCandidate.getTripAttributes().putAttribute("withinArea", fromFacilityInArea && toFacilityInArea);
            tripCandidate.getTripAttributes().putAttribute("subpopulation", PopulationUtils.getSubpopulation(person));

            Map<String, Object> trialParams = getTrial().getParameters().values().stream()
                    .collect(Collectors.toMap(Parameter::getName, Parameter::getValue));
            // print trial and trialParams
            tripCandidate.getTripAttributes().putAttribute("params", trialParams);

            tripsDatabase.computeIfAbsent(person.getId(), k -> new ArrayList<>()).add(tripCandidate);
            if (fromFacility != null && fromFacility.getLinkId() != null) {
                relevantLinks.add(scenario.getNetwork().getLinks().get(fromFacility.getLinkId()));
            }
            if (toFacility != null && toFacility.getLinkId() != null) {
                relevantLinks.add(scenario.getNetwork().getLinks().get(toFacility.getLinkId()));
            }
        }

        /**
         * Adjusts walk legs in the trip elements for "ad_drt" mode.
         */
        private List<? extends PlanElement> adjustAdDrtTripElements(List<? extends PlanElement> tripElements,
                Facility fromFacility, Facility toFacility) {
            for (PlanElement element : tripElements) {
                if (element instanceof Leg leg && TransportModes.WALK.equals(leg.getMode())) {
                    double walkingDistance = ((Number) getTrial().getParameters()
                            .get("walking_distance").getValue()).doubleValue();
                    double travelTime = walkingDistance / walkSpeed;
                    Route route = scenario.getPopulation().getFactory().getRouteFactories()
                            .createRoute(Route.class, fromFacility.getLinkId(), toFacility.getLinkId());
                    route.setTravelTime(travelTime);
                    route.setDistance(walkingDistance);
                    leg.setDepartureTime(leg.getDepartureTime().orElse(0.0) + leg.getTravelTime().orElse(0.0)
                            - route.getTravelTime().orElse(0));
                    leg.setRoute(route);
                    leg.setTravelTime(travelTime);
                }
            }
            return tripElements;
        }
    }
}

