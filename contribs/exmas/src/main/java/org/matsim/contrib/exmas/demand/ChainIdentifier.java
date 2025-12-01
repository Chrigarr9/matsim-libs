package org.matsim.contrib.exmas.demand;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.router.TripStructureUtils;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class ChainIdentifier {

    private final Map<Id<Person>, Map<Integer, String>> chainIds = new ConcurrentHashMap<>();

    @Inject
    public ChainIdentifier() {
    }

    public void identifyChains(Population population) {
        population.getPersons().values().parallelStream().forEach(person -> {
            Map<Integer, String> personChains = new HashMap<>();
            List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(person.getSelectedPlan());

            // Get all closed subtours
            Collection<TripStructureUtils.Subtour> subtours = TripStructureUtils.getSubtours(person.getSelectedPlan());
            List<TripStructureUtils.Subtour> closedSubtours = new ArrayList<>();
            for (TripStructureUtils.Subtour st : subtours) {
                if (st.isClosed()) {
                    closedSubtours.add(st);
                }
            }

            // Sort subtours by size (descending) to prioritize larger loops (e.g. Home-Home
            // over Work-Work)
            // c: i think we need to sort them to the smallest subtours. even if the largest tour is done by car (Home-work-Home),
            // the smaller subtour (work-lunch-work) might be done by walk. if we assign the largest subtour first, we would miss that. 
            // also we need to know all subtours a trip belongs to, not just the largest/smallest one. because we need to check if for the large subtour
            // a vehicle (car) is used. only if it is used on the larger tour, we can use it for the smaller tour. if it is not used on the big tour, everything becomes tripbased.
            // Note: Subtour.getTrips() returns the list of trips.
            closedSubtours.sort((s1, s2) -> Integer.compare(s2.getTrips().size(), s1.getTrips().size()));

            // Assign GroupID to trips
            // We iterate through trips and find the largest subtour they belong to.
            for (int i = 0; i < trips.size(); i++) {
                TripStructureUtils.Trip trip = trips.get(i);
                String groupId = null;

                // Find the first (largest) subtour containing this trip
                for (int sIdx = 0; sIdx < closedSubtours.size(); sIdx++) {
                    TripStructureUtils.Subtour st = closedSubtours.get(sIdx);
                    if (st.getTrips().contains(trip)) {
                        groupId = person.getId().toString() + "_subtour_" + sIdx; // ID based on sorted index
                        break;
                    }
                }

                if (groupId != null) {
                    personChains.put(i, groupId);
                }
            }
            chainIds.put(person.getId(), personChains);
        });
    }

    public Map<Integer, String> getChainIds(Id<Person> personId) {
        return chainIds.get(personId);
    }
}
