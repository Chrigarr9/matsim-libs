package org.matsim.contrib.exmas.demand;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.contrib.exmas.config.ExMasConfigGroup;
import org.matsim.core.router.TripStructureUtils;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Singleton
public class BudgetCalculator {

    private final ExMasConfigGroup config;
    private final ModeRoutingCache modeRoutingCache;
    private final ChainIdentifier chainIdentifier;

    @Inject
    public BudgetCalculator(ExMasConfigGroup config, ModeRoutingCache modeRoutingCache, ChainIdentifier chainIdentifier) {
        this.config = config;
        this.modeRoutingCache = modeRoutingCache;
        this.chainIdentifier = chainIdentifier;
    }

    public List<DrtRequest> calculateBudgets(org.matsim.api.core.v01.population.Population population) {
        List<DrtRequest> requests = new ArrayList<>();

        for (Person person : population.getPersons().values()) {
            Plan plan = person.getSelectedPlan();

            // c: this should be clearer why is chains a map integer to string? why modeAttributes is a map string to modeAttributes?
            // maybe clearer custom types for these maps?
            Map<Integer, String> chains = chainIdentifier.getChainIds(person.getId());
            Map<Integer, Map<String, ModeAttributes>> modeAttributes = modeRoutingCache.getAttributes(person.getId());
            List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(plan);

            // Group trips by Chain ID (or treat as single trips if no chain)
            // We use a list of indices for each group
            Map<String, List<Integer>> groups = new java.util.HashMap<>();
            for (int i = 0; i < trips.size(); i++) {
                String groupId = chains != null ? chains.get(i) : "trip_" + i;
                groups.computeIfAbsent(groupId, k -> new ArrayList<>()).add(i);
            }

            for (Map.Entry<String, List<Integer>> entry : groups.entrySet()) {
                String groupId = entry.getKey();
                List<Integer> tripIndices = entry.getValue();
                
                // Evaluate combinations
                // If TRIP_LEVEL, we treat each trip individually (group size 1 effectively, or forced)
                if (config.getBudgetCalculationMode() == ExMasConfigGroup.BudgetCalculationMode.TRIP_LEVEL) {
                     for (int idx : tripIndices) {
                         evaluateCombination(person, trips.get(idx), idx, groupId, modeAttributes, requests);
                     }
                } else {
                    // SUBTOUR_SUM: Evaluate all combinations for the group
                    evaluateSubtourCombinations(person, trips, tripIndices, groupId, modeAttributes, requests);
                }
            }
        }
        return requests;
    }

    private void evaluateCombination(Person person, TripStructureUtils.Trip trip, int index, String groupId,
                                     Map<Integer, Map<String, ModeAttributes>> modeAttributes, List<DrtRequest> requests) {
        if (modeAttributes == null || !modeAttributes.containsKey(index)) return;
        
        Map<String, ModeAttributes> attrs = modeAttributes.get(index);
        String currentMode = TripStructureUtils.identifyMainMode(trip.getTripElements());
        
        // If current mode not in cache, we can't compare easily. 
        // Fallback: assume current mode score is 0 relative to others? No, dangerous.
        // Try to find "car" or "pt" if current matches.
        // If not found, skip.
        if (!attrs.containsKey(currentMode)) {
            // Maybe it's "transit_walk" but main mode is "pt"?
            // TripStructureUtils.identifyMainMode handles this.
            // If still not found, maybe we didn't route it.
            return;
        }
        
        double currentScore = attrs.get(currentMode).score;
        String drtMode = config.getDrtMode();
        
        if (attrs.containsKey(drtMode)) {
            double drtScore = attrs.get(drtMode).score;
            double budget = drtScore - currentScore;
            
            requests.add(new DrtRequest(
                    person.getId(),
                    groupId,
                    index,
                    budget,
                    trip.getOriginActivity().getEndTime().orElse(0.0),
                    trip.getOriginActivity().getCoord().getX(),
                    trip.getOriginActivity().getCoord().getY(),
                    trip.getDestinationActivity().getCoord().getX(),
                    trip.getDestinationActivity().getCoord().getY()
            ));
        }
    }

    private void evaluateSubtourCombinations(Person person, List<TripStructureUtils.Trip> allTrips, List<Integer> groupIndices, String groupId,
                                             Map<Integer, Map<String, ModeAttributes>> modeAttributes, List<DrtRequest> requests) {
        
        // Generate combinations: 2^N
        int n = groupIndices.size();
        int combinations = 1 << n;
        
        double maxBudget = Double.NEGATIVE_INFINITY;
        int bestCombination = -1;
        
        // We want to find the combination that maximizes (Total Score with DRT - Total Score Original)
        // Which is equivalent to maximizing Total Score with DRT.
        // But we need to output the "Budget" which is the difference.
        
        for (int i = 1; i < combinations; i++) { // Start from 1 to ensure at least one DRT trip? Or 0?
            // If i=0 (all original), budget is 0.
            // We want to see if we can improve.
            
            double currentBudget = 0.0;
            boolean possible = true;
            
            for (int j = 0; j < n; j++) {
                int tripIdx = groupIndices.get(j);
                TripStructureUtils.Trip trip = allTrips.get(tripIdx);
                String currentMode = TripStructureUtils.identifyMainMode(trip.getTripElements());
                
                if (modeAttributes == null || !modeAttributes.containsKey(tripIdx)) {
                    possible = false; break;
                }
                Map<String, ModeAttributes> attrs = modeAttributes.get(tripIdx);
                if (!attrs.containsKey(currentMode)) {
                    possible = false; break;
                }
                
                double originalScore = attrs.get(currentMode).score;
                double newScore;
                
                if ((i >> j & 1) == 1) {
                    // Use DRT
                    String drtMode = config.getDrtMode();
                    if (!attrs.containsKey(drtMode)) {
                        possible = false; break;
                    }
                    newScore = attrs.get(drtMode).score;
                } else {
                    // Use Original
                    newScore = originalScore;
                }
                
                currentBudget += (newScore - originalScore);
            }
            
            if (possible) {
                if (currentBudget > maxBudget) {
                    maxBudget = currentBudget;
                    bestCombination = i;
                }
            }
        }
        
        // If we found a valid combination
        if (bestCombination != -1) {
            // Output requests for the DRT trips in the best combination
            // All share the same budget?
            // Usually budget is total.
            // If we output multiple requests, how does the optimizer interpret it?
            // "If you serve these N requests, you get X budget".
            // We should probably distribute the budget or mark them.
            // For now, let's assign the TOTAL budget to EACH request, but maybe the optimizer knows they are a group?
            // The `groupId` links them.
            // If the optimizer sees same groupId, it should know.
            
            for (int j = 0; j < n; j++) {
                if ((bestCombination >> j & 1) == 1) {
                    int tripIdx = groupIndices.get(j);
                    TripStructureUtils.Trip trip = allTrips.get(tripIdx);
                    
                    requests.add(new DrtRequest(
                            person.getId(),
                            groupId,
                            tripIdx,
                            maxBudget, // Shared budget
                            trip.getOriginActivity().getEndTime().orElse(0.0),
                            trip.getOriginActivity().getCoord().getX(),
                            trip.getOriginActivity().getCoord().getY(),
                            trip.getDestinationActivity().getCoord().getX(),
                            trip.getDestinationActivity().getCoord().getY()
                    ));
                }
            }
        }
    }
}
