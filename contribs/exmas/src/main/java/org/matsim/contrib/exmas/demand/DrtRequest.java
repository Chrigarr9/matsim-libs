package org.matsim.contrib.exmas.demand;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;

public class DrtRequest {
    public final Id<Person> personId;
    public final String groupId; // Subtour ID or Trip ID
    public final int tripIndex;
    public final double budget;
    public final double departureTime;
    public final double originX;
    public final double originY;
    public final double destinationX;
    public final double destinationY;

    public DrtRequest(Id<Person> personId, String groupId, int tripIndex, double budget,
            double departureTime, double originX, double originY, double destinationX, double destinationY) {
        this.personId = personId;
        this.groupId = groupId;
        this.tripIndex = tripIndex;
        this.budget = budget;
        this.departureTime = departureTime;
        this.originX = originX;
        this.originY = originY;
        this.destinationX = destinationX;
        this.destinationY = destinationY;
    }

    @Override
    public String toString() {
        return "DrtRequest{" +
                "personId=" + personId +
                ", groupId='" + groupId + '\'' +
                ", tripIndex=" + tripIndex +
                ", budget=" + budget +
                '}';
    }
}
