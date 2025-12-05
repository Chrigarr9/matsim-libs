package org.matsim.contrib.demand_extraction.demand;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;

/**
 * Unified request class for DRT demand extraction and ExMAS ride generation.
 * 
 * Combines DRT budget information with ExMAS algorithm requirements:
 * - Budget tracking (bestModeScore for validation)
 * - Temporal windows (earliest/latest departure/arrival)
 * - Direct travel metrics (for detour calculation)
 * - Link-based locations (for MATSim network routing)
 */
public class DrtRequest {
    // Core identification
    public final int index; // Sequential index in request list (for ExMAS)
    public final Id<Person> personId;
    public final String groupId; // Subtour ID or Trip ID (for linked trips)
    public final int tripIndex; // Trip index within person's plan
    
    // Budget information
    public final double budget; // Utility difference: drtScore - bestModeScore
    public final double bestModeScore; // Score of best baseline mode (for budget validation)
    public final String bestMode; 

    
    // Location (link-based for MATSim routing)
    public final Id<Link> originLinkId;
    public final Id<Link> destinationLinkId;
    public final double originX; // Kept for backward compatibility/visualization
    public final double originY;
    public final double destinationX;
    public final double destinationY;
    
    // Temporal constraints
    public final double requestTime; // Desired departure time (seconds)
    public final double earliestDeparture; // Earliest acceptable departure (seconds)
    public final double latestArrival; // Latest acceptable arrival (seconds)
    
    // Direct trip metrics (baseline for detour calculation)
    public final double directTravelTime; // Direct travel time without sharing (seconds)
    public final double directDistance; // Direct distance without sharing (meters)

    private DrtRequest(Builder builder) {
        this.index = builder.index;
        this.personId = builder.personId;
        this.groupId = builder.groupId;
        this.tripIndex = builder.tripIndex;
        this.budget = builder.budget;
        this.bestModeScore = builder.bestModeScore;
		this.bestMode = builder.bestMode;
        this.originLinkId = builder.originLinkId;
        this.destinationLinkId = builder.destinationLinkId;
        this.originX = builder.originX;
        this.originY = builder.originY;
        this.destinationX = builder.destinationX;
        this.destinationY = builder.destinationY;
        this.requestTime = builder.requestTime;
        this.earliestDeparture = builder.earliestDeparture;
        this.latestArrival = builder.latestArrival;
        this.directTravelTime = builder.directTravelTime;
        this.directDistance = builder.directDistance;
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    // Computed and compatibility getters for ExMAS algorithm
    public double getLatestDeparture() {
        return latestArrival - directTravelTime;
    }
    
    public double getEarliestDeparture() {
        return earliestDeparture;
    }
    
    public double getRequestTime() {
        return requestTime;
    }
    
    public double getTravelTime() {
        return directTravelTime;
    }
    
    public double getDistance() {
        return directDistance;
    }
    
    public String getPaxId() {
        return personId.toString();
    }
    
    /**
	 * Maximum acceptable pooled travel time (used by ExMAS algorithm).
	 * 
	 * This represents the maximum total travel time INCLUDING detour:
	 * = direct travel time + maximum acceptable detour
	 * 
	 * The maximum detour is already baked into the temporal window
	 * (earliestDeparture to latestArrival) during budget calculation,
	 * where BudgetToConstraintsCalculator.budgetToMaxDetourTime() converts
	 * the utility budget into a time constraint.
	 * 
	 * Therefore: maxTravelTime = latestArrival - earliestDeparture
	 * = directTravelTime + maxDetourFromBudget
	 * 
	 * This is equivalent to: directTravelTime * (1.0 + effectiveDetourFactor)
	 * where effectiveDetourFactor is determined by budget, not config.
	 */
    public double getMaxTravelTime() {
        return latestArrival - earliestDeparture;
    }
    
	// Delay methods for temporal flexibility (used by pair generation algorithm)	
	public double getMaxPositiveDelay() {
        return getLatestDeparture() - requestTime;
    }
    
    public double getMaxNegativeDelay() {
        return requestTime - earliestDeparture;
    }
    
    public double getPositiveDelayRelComponent() {
        return 0.0; // No pre-consumed flexibility
    }
    
    public double getNegativeDelayRelComponent() {
        return 0.0; // No pre-consumed flexibility
    }
    
    public static class Builder {
        private int index;
        private Id<Person> personId;
        private String groupId;
        private int tripIndex;
        private double budget;
        private double bestModeScore;
		private String bestMode;
        private Id<Link> originLinkId;
        private Id<Link> destinationLinkId;
        private double originX;
        private double originY;
        private double destinationX;
        private double destinationY;
        private double requestTime;
        private double earliestDeparture;
        private double latestArrival;
        private double directTravelTime;
        private double directDistance;
        
        public Builder index(int index) { this.index = index; return this; }
        public Builder personId(Id<Person> personId) { this.personId = personId; return this; }
        public Builder groupId(String groupId) { this.groupId = groupId; return this; }
        public Builder tripIndex(int tripIndex) { this.tripIndex = tripIndex; return this; }
        public Builder budget(double budget) { this.budget = budget; return this; }
        public Builder bestModeScore(double bestModeScore) { this.bestModeScore = bestModeScore; return this; }
		public Builder bestMode(String bestMode) {this.bestMode = bestMode; return this;}
        public Builder originLinkId(Id<Link> originLinkId) { this.originLinkId = originLinkId; return this; }
        public Builder destinationLinkId(Id<Link> destinationLinkId) { this.destinationLinkId = destinationLinkId; return this; }
        public Builder originX(double originX) { this.originX = originX; return this; }
        public Builder originY(double originY) { this.originY = originY; return this; }
        public Builder destinationX(double destinationX) { this.destinationX = destinationX; return this; }
        public Builder destinationY(double destinationY) { this.destinationY = destinationY; return this; }
        public Builder requestTime(double requestTime) { this.requestTime = requestTime; return this; }
        public Builder earliestDeparture(double earliestDeparture) { this.earliestDeparture = earliestDeparture; return this; }
        public Builder latestArrival(double latestArrival) { this.latestArrival = latestArrival; return this; }
        public Builder directTravelTime(double directTravelTime) { this.directTravelTime = directTravelTime; return this; }
        public Builder directDistance(double directDistance) { this.directDistance = directDistance; return this; }
        
        public DrtRequest build() {
            if (directTravelTime < 0) {
                throw new IllegalArgumentException("Direct travel time cannot be negative: " + directTravelTime);
            }
            if (directDistance < 0) {
                throw new IllegalArgumentException("Direct distance cannot be negative: " + directDistance);
            }
            if (earliestDeparture > latestArrival - directTravelTime) {
                throw new IllegalArgumentException(
                    String.format("Infeasible temporal window: earliest departure (%.2f) + travel time (%.2f) > latest arrival (%.2f)",
                        earliestDeparture, directTravelTime, latestArrival)
                );
            }
            return new DrtRequest(this);
        }
    }

    @Override
    public String toString() {
        return String.format("DrtRequest[idx=%d, person=%s, %s->%s, treq=%.1f, tt=%.1f, window=[%.1f, %.1f], budget=%.2f]",
                index, personId, originLinkId, destinationLinkId, requestTime, 
                directTravelTime, earliestDeparture, latestArrival, budget);
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        DrtRequest other = (DrtRequest) obj;
        return index == other.index && personId.equals(other.personId);
    }
    
    @Override
    public int hashCode() {
        return 31 * index + personId.hashCode();
    }
}
