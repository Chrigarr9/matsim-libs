package org.matsim.contrib.demand_extraction.demand;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Route;
import org.matsim.contrib.demand_extraction.util.SmallLru;
import org.matsim.contrib.drt.routing.DrtRoute;
import org.matsim.core.scoring.functions.ScoringParameters;

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

    // Commute flag - marks trips that are part of a home-work-home pattern
    // When true, this trip is either home->work or work->home (not intermediate work trips)
    public final boolean isCommute;
    
    // Education flag - marks trips that are part of a home-education-home pattern
    // Used in downstream Python optimization to identify education-related trips for special handling
    // (e.g., school bus optimization, priority scheduling for students)
    public final boolean isEducation;

    // Budget information
    public final double budget; // Utility difference: drtScore - bestModeScore
    public final double bestModeScore; // Score of best baseline mode (for budget validation)
    public final String bestMode; 

    
    // Location (link-based for MATSim routing)
    // Coordinates are derived from link centroids to ensure consistency with routing
    public final Id<Link> originLinkId;
    public final Id<Link> destinationLinkId;
    public final double originX; // Derived from originLinkId centroid - for visualization/export
    public final double originY;
    public final double destinationX; // Derived from destinationLinkId centroid - for visualization/export
    public final double destinationY;

    // Link-endpoint coordinates used for admissible B&B lower bounds in
    // OrderingEnumerator.computeMinIn. The link-based LeastCostPathCalculator
    // routes from fromLink.toNode to toLink.fromNode, so a Euclidean beeline
    // between midpoints can exceed the routed distance (zero for adjacent
    // links). Using the actual routing endpoints keeps the beeline <= any
    // routed path between the same two nodes.
    public final double originLinkCoordFromX; // originLinkId.fromNode coord (routing "arrives" here)
    public final double originLinkCoordFromY;
    public final double originLinkCoordToX;   // originLinkId.toNode coord (routing "leaves" from here)
    public final double originLinkCoordToY;
    public final double destinationLinkCoordFromX; // destinationLinkId.fromNode coord (routing "arrives" here)
    public final double destinationLinkCoordFromY;
    public final double destinationLinkCoordToX;   // destinationLinkId.toNode coord (routing "leaves" from here)
    public final double destinationLinkCoordToY;
    
    // Temporal constraints
    public final double requestTime; // Desired departure time (seconds)
    public final double earliestDeparture; // Earliest acceptable departure (seconds)
    public final double latestArrival; // Latest acceptable arrival (seconds)
    
    // Direct trip metrics (baseline for detour calculation)
    public final double directTravelTime; // Direct travel time without sharing (seconds)
    public final double directDistance; // Direct distance without sharing (meters)
	public final double maxDetourFactor; // Maximum detour factor (e.g., 1.5 means 50% longer than direct)

    /**
     * Maximum acceptable walking distance for stop-based pooling (meters).
     * Calculated from the person's remaining budget using BudgetToConstraintsCalculator.
     * This constrains how far a passenger can walk to/from a shared stop.
     * A value of 0 or negative means the passenger cannot participate in stop-based pooling.
     */
    public final double maxWalkDistance;

    /**
     * Maximum acceptable waiting time for pooled rides (seconds).
     * Calculated from the person's remaining budget using BudgetToConstraintsCalculator.
     * A value of 0 means no wait is tolerable (flag off or zero budget).
     */
    public final double maxWaitTime;

    // Activity types (for downstream analysis grouping)
    public final String originActivityType; // Type of origin activity (e.g., "home", "work")
    public final String destinationActivityType; // Type of destination activity

    // PT Accessibility metrics - calculated for ALL agents regardless of car availability
    // These allow comparing the PT accessibility of each trip
    public final double carTravelTime; // Car travel time for this trip (seconds) - always calculated
    public final double ptTravelTime; // PT travel time for this trip (seconds)
	public final double ptAccessibility; // Ratio: ptTravelTime / carTravelTime (higher = PT slower/worse, lower = PT
											// faster/better)

	/**
	 * Pre-computed scoring context holding pre-resolved activities, scoring parameters,
	 * and mutable template trip objects. Populated once at construction time by
	 * DrtRequestFactory via BudgetValidator.computeScoringContext. Every scoring call
	 * (budget calculation, binary search, ride validation) reuses this context.
	 * Volatile for thread-safe publication to ForkJoinPool worker threads.
	 */
	private volatile ScoringContext scoringContext;

	/**
	 * Cached scoring context holding pre-resolved activities, durations,
	 * scoring parameters, and template trip objects for a DRT request.
	 * All fields are constant for a given request regardless of ride ordering.
	 */
	public record ScoringContext(
		Person person,
		Activity originActivity,
		Activity destActivity,
		double originDuration,
		double destDuration,
		ScoringParameters scoringParams,
		// Pre-built template objects (walk legs have fixed distances, DRT route has fixed direct metrics)
		Leg accessWalkLeg,
		Route accessWalkRoute,
		Leg egressWalkLeg,
		Route egressWalkRoute,
		DrtRoute drtRouteTemplate,
		Activity syntheticOriginActivity,
		Activity syntheticDestActivity
	) {}

	public ScoringContext getScoringContext() { return scoringContext; }
	public void setScoringContext(ScoringContext ctx) { this.scoringContext = ctx; }

	/**
	 * Per-request LRU cache for pooled-ride
	 * {@link BudgetToConstraintsCalculator#budgetToMaxWalkDistance} results, keyed on
	 * the quantized (actualTT, actualDist, delay) triple. Lazily allocated on first
	 * use to avoid pre-emptive allocation for the many requests that never reach
	 * the pooled-ride code path.
	 */
	private SmallLru<WalkCacheKey, Double> walkCapCache;

	/**
	 * Per-request LRU cache for pooled-ride
	 * {@link BudgetToConstraintsCalculator#budgetToMaxWaitingTime} results, keyed on
	 * the quantized (actualTT, actualDist, accessWalk, egressWalk) tuple.
	 */
	private SmallLru<WaitCacheKey, Double> waitCapCache;

	public SmallLru<WalkCacheKey, Double> getOrCreateWalkCapCache(int capacity) {
		SmallLru<WalkCacheKey, Double> c = walkCapCache;
		if (c == null) {
			c = new SmallLru<>(capacity);
			walkCapCache = c;
		}
		return c;
	}

	public SmallLru<WaitCacheKey, Double> getOrCreateWaitCapCache(int capacity) {
		SmallLru<WaitCacheKey, Double> c = waitCapCache;
		if (c == null) {
			c = new SmallLru<>(capacity);
			waitCapCache = c;
		}
		return c;
	}

	/** Cache key for the pooled-ride walk overload. */
	public record WalkCacheKey(int ttBucket, int distBucket, int delayBucket) {}

	/** Cache key for the pooled-ride wait overload. */
	public record WaitCacheKey(int ttBucket, int distBucket, int accessBucket, int egressBucket) {}

    private DrtRequest(Builder builder) {
        this.index = builder.index;
        this.personId = builder.personId;
        this.groupId = builder.groupId;
        this.tripIndex = builder.tripIndex;
        this.isCommute = builder.isCommute;
        this.isEducation = builder.isEducation;
        this.budget = builder.budget;
        this.bestModeScore = builder.bestModeScore;
        this.bestMode = builder.bestMode;
        this.originLinkId = builder.originLinkId;
        this.destinationLinkId = builder.destinationLinkId;
        this.originX = builder.originX;
        this.originY = builder.originY;
        this.destinationX = builder.destinationX;
        this.destinationY = builder.destinationY;
        this.originLinkCoordFromX = builder.originLinkCoordFromX;
        this.originLinkCoordFromY = builder.originLinkCoordFromY;
        this.originLinkCoordToX = builder.originLinkCoordToX;
        this.originLinkCoordToY = builder.originLinkCoordToY;
        this.destinationLinkCoordFromX = builder.destinationLinkCoordFromX;
        this.destinationLinkCoordFromY = builder.destinationLinkCoordFromY;
        this.destinationLinkCoordToX = builder.destinationLinkCoordToX;
        this.destinationLinkCoordToY = builder.destinationLinkCoordToY;
        this.requestTime = builder.requestTime;
        this.earliestDeparture = builder.earliestDeparture;
        this.latestArrival = builder.latestArrival;
        this.directTravelTime = builder.directTravelTime;
        this.directDistance = builder.directDistance;
        this.maxDetourFactor = builder.maxDetourFactor;
        this.maxWalkDistance = builder.maxWalkDistance;
        this.maxWaitTime = builder.maxWaitTime;
        this.originActivityType = builder.originActivityType;
        this.destinationActivityType = builder.destinationActivityType;
        this.carTravelTime = builder.carTravelTime;
        this.ptTravelTime = builder.ptTravelTime;
        this.ptAccessibility = builder.ptAccessibility;
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public Builder toBuilder() {
        return new Builder()
            .index(this.index)
            .personId(this.personId)
            .groupId(this.groupId)
            .tripIndex(this.tripIndex)
            .isCommute(this.isCommute)
            .isEducation(this.isEducation)
            .budget(this.budget)
            .bestModeScore(this.bestModeScore)
            .bestMode(this.bestMode)
            .originLinkId(this.originLinkId)
            .destinationLinkId(this.destinationLinkId)
            .originX(this.originX)
            .originY(this.originY)
            .destinationX(this.destinationX)
            .destinationY(this.destinationY)
            .originLinkCoordFromX(this.originLinkCoordFromX)
            .originLinkCoordFromY(this.originLinkCoordFromY)
            .originLinkCoordToX(this.originLinkCoordToX)
            .originLinkCoordToY(this.originLinkCoordToY)
            .destinationLinkCoordFromX(this.destinationLinkCoordFromX)
            .destinationLinkCoordFromY(this.destinationLinkCoordFromY)
            .destinationLinkCoordToX(this.destinationLinkCoordToX)
            .destinationLinkCoordToY(this.destinationLinkCoordToY)
            .requestTime(this.requestTime)
            .earliestDeparture(this.earliestDeparture)
            .latestArrival(this.latestArrival)
            .directTravelTime(this.directTravelTime)
            .directDistance(this.directDistance)
            .maxDetourFactor(this.maxDetourFactor)
            .maxWalkDistance(this.maxWalkDistance)
            .maxWaitTime(this.maxWaitTime)
            .originActivityType(this.originActivityType)
            .destinationActivityType(this.destinationActivityType)
            .carTravelTime(this.carTravelTime)
            .ptTravelTime(this.ptTravelTime)
            .ptAccessibility(this.ptAccessibility);
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
	 * maxTravelTime = directTravelTime * maxDetourFactor
	 * 
	 * This is separate from temporal flexibility (earliestDeparture/latestArrival),
	 * which controls WHEN someone can depart/arrive, not HOW LONG the trip can
	 * take.
	 * 
	 * Example: maxDetourFactor = 1.5, directTravelTime = 1000s
	 * -> maxTravelTime = 1500s (can be 50% longer than direct)
	 * -> maxAbsoluteDetour = 500s (the extra time allowed)
	 */
    public double getMaxTravelTime() {
		return directTravelTime * maxDetourFactor;
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
        private boolean isCommute;
        private boolean isEducation;
        private double budget;
        private double bestModeScore;
        private String bestMode;
        private Id<Link> originLinkId;
        private Id<Link> destinationLinkId;
        private double originX;
        private double originY;
        private double destinationX;
        private double destinationY;
        private double originLinkCoordFromX;
        private double originLinkCoordFromY;
        private double originLinkCoordToX;
        private double originLinkCoordToY;
        private double destinationLinkCoordFromX;
        private double destinationLinkCoordFromY;
        private double destinationLinkCoordToX;
        private double destinationLinkCoordToY;
        private double requestTime;
        private double earliestDeparture;
        private double latestArrival;
        private double directTravelTime;
        private double directDistance;
		private double maxDetourFactor;
        private double maxWalkDistance = 0.0;
        private double maxWaitTime = 0.0;
        private String originActivityType;
        private String destinationActivityType;
        private double carTravelTime;
        private double ptTravelTime;
        private double ptAccessibility;

        public Builder index(int index) { this.index = index; return this; }
        public Builder personId(Id<Person> personId) { this.personId = personId; return this; }
        public Builder groupId(String groupId) { this.groupId = groupId; return this; }
        public Builder tripIndex(int tripIndex) { this.tripIndex = tripIndex; return this; }
        public Builder isCommute(boolean isCommute) { this.isCommute = isCommute; return this; }
        public Builder isEducation(boolean isEducation) { this.isEducation = isEducation; return this; }
        public Builder budget(double budget) { this.budget = budget; return this; }
        public Builder bestModeScore(double bestModeScore) { this.bestModeScore = bestModeScore; return this; }
		public Builder bestMode(String bestMode) {this.bestMode = bestMode; return this;}
        public Builder originLinkId(Id<Link> originLinkId) { this.originLinkId = originLinkId; return this; }
        public Builder destinationLinkId(Id<Link> destinationLinkId) { this.destinationLinkId = destinationLinkId; return this; }
        public Builder originX(double originX) { this.originX = originX; return this; }
        public Builder originY(double originY) { this.originY = originY; return this; }
        public Builder destinationX(double destinationX) { this.destinationX = destinationX; return this; }
        public Builder destinationY(double destinationY) { this.destinationY = destinationY; return this; }
        public Builder originLinkCoordFromX(double v) { this.originLinkCoordFromX = v; return this; }
        public Builder originLinkCoordFromY(double v) { this.originLinkCoordFromY = v; return this; }
        public Builder originLinkCoordToX(double v) { this.originLinkCoordToX = v; return this; }
        public Builder originLinkCoordToY(double v) { this.originLinkCoordToY = v; return this; }
        public Builder destinationLinkCoordFromX(double v) { this.destinationLinkCoordFromX = v; return this; }
        public Builder destinationLinkCoordFromY(double v) { this.destinationLinkCoordFromY = v; return this; }
        public Builder destinationLinkCoordToX(double v) { this.destinationLinkCoordToX = v; return this; }
        public Builder destinationLinkCoordToY(double v) { this.destinationLinkCoordToY = v; return this; }
        public Builder requestTime(double requestTime) { this.requestTime = requestTime; return this; }
        public Builder earliestDeparture(double earliestDeparture) { this.earliestDeparture = earliestDeparture; return this; }
        public Builder latestArrival(double latestArrival) { this.latestArrival = latestArrival; return this; }
        public Builder directTravelTime(double directTravelTime) { this.directTravelTime = directTravelTime; return this; }
        public Builder directDistance(double directDistance) { this.directDistance = directDistance; return this; }

		public Builder maxDetourFactor(double maxDetourFactor) {
			this.maxDetourFactor = maxDetourFactor;
			return this;
		}
        public Builder maxWalkDistance(double maxWalkDistance) { this.maxWalkDistance = maxWalkDistance; return this; }
        public Builder maxWaitTime(double maxWaitTime) { this.maxWaitTime = maxWaitTime; return this; }
        public Builder originActivityType(String originActivityType) { this.originActivityType = originActivityType; return this; }
        public Builder destinationActivityType(String destinationActivityType) { this.destinationActivityType = destinationActivityType; return this; }
        public Builder carTravelTime(double carTravelTime) { this.carTravelTime = carTravelTime; return this; }
        public Builder ptTravelTime(double ptTravelTime) { this.ptTravelTime = ptTravelTime; return this; }
        public Builder ptAccessibility(double ptAccessibility) { this.ptAccessibility = ptAccessibility; return this; }

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
