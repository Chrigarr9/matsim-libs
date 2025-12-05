package org.matsim.contrib.demand_extraction.demand;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.core.router.TripStructureUtils;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Identifies subtour chains in person plans and determines which subtours use private vehicles.
 * 
 * Key concepts:
 * - Subtours are hierarchical: larger subtours (e.g., Home-Work-Home) can contain smaller ones (e.g., Work-Lunch-Work)
 * - Private vehicles (car, bike) create dependencies: if used in outer subtour, they're potentially available for inner subtours
 * - Trips are grouped by the smallest subtour they belong to that uses a private vehicle
 * - If no private vehicle is used in any containing subtour, trips are evaluated independently
 */
@Singleton
public class ChainIdentifier {

    private final ExMasConfigGroup exMasConfig;
    private final ModeRoutingCache modeRoutingCache;

    // Maps person ID -> trip index -> group ID
    // Group ID format: "personId_subtour_X" for subtours using private vehicles, "personId_trip_X" for independent trips
    private final Map<Id<Person>, Map<Integer, String>> tripToGroupId = new ConcurrentHashMap<>();
    
    // Maps person ID -> trip index -> mode used in best baseline (needed to determine vehicle usage)
    private final Map<Id<Person>, Map<Integer, String>> tripToBestBaselineMode = new ConcurrentHashMap<>();

    @Inject
    public ChainIdentifier(ExMasConfigGroup exMasConfig, ModeRoutingCache modeRoutingCache) {
        this.exMasConfig = exMasConfig;
        this.modeRoutingCache = modeRoutingCache;
    }

    /**
     * Identifies subtour chains and determines grouping based on private vehicle usage.
     * Must be called AFTER ModeRoutingCache.cacheModes() so we know the best baseline mode for each trip.
     */
    public void identifyChains(Population population, Map<Id<Person>,Map<Integer,Entry<String,Double>>> bestBaselineModes) {
        population.getPersons().values().parallelStream().forEach(person -> {
            Map<Integer, String> personGroupIds = new HashMap<>();
            Map<Integer,Entry<String,Double>> personBestModes = bestBaselineModes.get(person.getId());
            
            if (personBestModes == null) {
                // No routing cache available - treat all trips independently
                List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(person.getSelectedPlan());
                for (int i = 0; i < trips.size(); i++) {
                    personGroupIds.put(i, person.getId().toString() + "_trip_" + i);
                }
                tripToGroupId.put(person.getId(), personGroupIds);
                return;
            }
            
            List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(person.getSelectedPlan());

            // Get all closed subtours and build hierarchy
            Collection<TripStructureUtils.Subtour> subtours = TripStructureUtils.getSubtours(person.getSelectedPlan());
            List<TripStructureUtils.Subtour> closedSubtours = new ArrayList<>();
            for (TripStructureUtils.Subtour st : subtours) {
                if (st.isClosed()) {
                    closedSubtours.add(st);
                }
            }

            // Sort subtours from smallest to largest
            // This ensures we evaluate inner subtours before outer ones when assigning groups
            closedSubtours.sort((s1, s2) -> Integer.compare(s1.getTrips().size(), s2.getTrips().size()));

            // Determine which subtours use private vehicles based on best baseline modes
            Map<TripStructureUtils.Subtour, Boolean> subtourUsesPrivateVehicle = new HashMap<>();
            for (TripStructureUtils.Subtour subtour : closedSubtours) {
                boolean usesPrivateVehicle = subtourUsesPrivateVehicle(subtour, personBestModes, person);
                subtourUsesPrivateVehicle.put(subtour, usesPrivateVehicle);
            }

            // Assign group IDs to trips based on the smallest subtour they belong to that uses a private vehicle
            for (int tripIdx = 0; tripIdx < trips.size(); tripIdx++) {
                TripStructureUtils.Trip trip = trips.get(tripIdx);
                String groupId = null;

                // Find the smallest subtour containing this trip that uses a private vehicle
                for (TripStructureUtils.Subtour subtour : closedSubtours) {
                    if (subtour.getTrips().contains(trip) && subtourUsesPrivateVehicle.get(subtour)) {
                        // Use subtour index for consistent ID
                        int subtourIdx = closedSubtours.indexOf(subtour);
                        groupId = person.getId().toString() + "_subtour_" + subtourIdx;
                        break;
                    }
                }

                // If no private-vehicle subtour contains this trip, it's independent
                if (groupId == null) {
                    groupId = person.getId().toString() + "_trip_" + tripIdx;
                }

                personGroupIds.put(tripIdx, groupId);
            }

            tripToGroupId.put(person.getId(), personGroupIds);
            tripToBestBaselineMode.put(person.getId(), personBestModes);
        });
    }

    /**
     * Determines if a subtour uses a private vehicle in its best feasible mode combination.
     * 
     * Vehicle constraints in subtours require evaluating ALL feasible mode combinations:
     * - Private vehicles (car, bike) must be available at the subtour start location
     * - If used, the same vehicle must be used for all trips in the closed subtour (returns to start)
     * - The utility of the private vehicle combination is compared against all non-private combinations
     * 
     * Example: Home-Work-Home subtour
     *   - Car combination: car(H→W) + car(W→H) → total utility
     *   - PT combination: pt(H→W) + pt(W→H) → total utility
     *   - Mixed not allowed: car(H→W) + pt(W→H) leaves car stranded at work
     * 
     * Nested subtours inherit vehicle availability:
     *   - If outer subtour (H-W-H) uses car, inner subtour (W-Lunch-W) has car available
     *   - Inner subtour evaluates: car(W→L→W) vs pt(W→L→W) vs walk(W→L→W)
     * 
     * @param subtour The subtour to evaluate
     * @param personBestModes The best per-trip baseline modes (used for quick heuristic)
     * @param person The person making the trips
     * @return true if the best feasible mode combination for this subtour uses a private vehicle
     */
    private boolean subtourUsesPrivateVehicle(TripStructureUtils.Subtour subtour, 
                                               Map<Integer,Entry<String,Double>> personBestModes,
                                               Person person) {
        List<TripStructureUtils.Trip> allTrips = TripStructureUtils.getTrips(person.getSelectedPlan());
        List<Integer> subtourTripIndices = new ArrayList<>();
        
        // Get trip indices for this subtour
        for (TripStructureUtils.Trip trip : subtour.getTrips()) {
            int tripIdx = allTrips.indexOf(trip);
            if (tripIdx >= 0) {
                subtourTripIndices.add(tripIdx);
            }
        }
        
        if (subtourTripIndices.isEmpty()) {
            return false;
        }
        
        // Get mode attributes for all trips in subtour
        Map<Integer, Map<String, ModeAttributes>> personModeAttributes = modeRoutingCache.getAttributes(person.getId());
        if (personModeAttributes == null) {
            return false;
        }
        
        // Evaluate mode combinations:
        // 1. Private vehicle combinations (all trips use same private vehicle)
        // 2. Non-private combinations (each trip can use any non-private mode)
        
        Set<String> privateVehicleModes = exMasConfig.getPrivateVehicleModes();
        double bestPrivateVehicleUtility = Double.NEGATIVE_INFINITY;
        double bestNonPrivateUtility = Double.NEGATIVE_INFINITY;
        
        // Evaluate each private vehicle mode (if feasible for all trips)
        for (String privateMode : privateVehicleModes) {
            double combinedUtility = 0.0;
            boolean feasible = true;
            
            for (int tripIdx : subtourTripIndices) {
                Map<String, ModeAttributes> tripModes = personModeAttributes.get(tripIdx);
                if (tripModes == null || !tripModes.containsKey(privateMode)) {
                    feasible = false;
                    break;
                }
                combinedUtility += tripModes.get(privateMode).score;
            }
            
            if (feasible) {
                bestPrivateVehicleUtility = Math.max(bestPrivateVehicleUtility, combinedUtility);
            }
        }
        
        // Evaluate best non-private combination (use per-trip best non-private mode)
        for (int tripIdx : subtourTripIndices) {
            Map<String, ModeAttributes> tripModes = personModeAttributes.get(tripIdx);
            if (tripModes == null) {
                return false; // No modes available - shouldn't happen
            }
            
            double bestTripUtility = Double.NEGATIVE_INFINITY;
            for (Map.Entry<String, ModeAttributes> entry : tripModes.entrySet()) {
                String mode = entry.getKey();
                if (!privateVehicleModes.contains(mode) && !mode.equals(exMasConfig.getDrtMode())) {
                    bestTripUtility = Math.max(bestTripUtility, entry.getValue().score);
                }
            }
            
            if (bestTripUtility == Double.NEGATIVE_INFINITY) {
                // No non-private modes available for this trip
                // This can happen if person only has car/bike routed (no PT/walk)
                continue;
            }
            
            bestNonPrivateUtility += bestTripUtility;
        }
        
        // Subtour uses private vehicle if private vehicle combination has higher utility
        return bestPrivateVehicleUtility > bestNonPrivateUtility;
    }

    public Map<Integer, String> getChainIds(Id<Person> personId) {
        return tripToGroupId.get(personId);
    }
    
    public Map<Integer, String> getBestBaselineModes(Id<Person> personId) {
        return tripToBestBaselineMode.get(personId);
    }
}
