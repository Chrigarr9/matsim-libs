# HyperPool Integration Plan for MATSim drt-demand-extraction

## Overview

This document outlines the integration plan for the HyperPool algorithm into the existing ExMAS-based drt-demand-extraction contrib package.

**References:**
- [HyperPool Paper (arXiv)](https://arxiv.org/abs/2206.05940)
- [ExMAS Repository](https://github.com/RafalKucharskiPK/ExMAS)
- [Published Paper (Nature)](https://www.nature.com/articles/s44333-024-00006-4)

---

## Algorithm Understanding

### HyperPool Overview

HyperPool extends ExMAS through a two-stage aggregation approach:

1. **Stop-to-Stop Rides**: Convert door-to-door rides into rides where travelers walk to common pickup points and from common dropoff points
2. **Hyper-Pooled Rides**: Bundle stop-to-stop rides further (resembling public transit)

### Key Concepts

- Travelers walk to a **shared pickup point** (PUDO) and from a **shared dropoff point**
- Walking distance is constrained by each traveler's **max acceptable walking distance** (derived from budget)
- Utility calculation includes **walking penalties** that consume the traveler's budget
- The algorithm finds **optimal stop locations** that minimize total walking while keeping all travelers within their budget constraints

---

## Configuration Design

### New Configuration Parameters in `ExMasConfigGroup`

```java
// Stop-based pooling (HyperPool) settings
private boolean enableStopBased = false;  // Master switch
private double maxWalkDistanceMeters = 500.0;  // Hard cap on walking
private double stopSearchRadiusMeters = 300.0;  // Radius to search for optimal stops
private boolean useNetworkStops = false;  // Use network nodes as stops vs any point
private int maxStopCandidates = 10;  // Max stop candidates to evaluate per ride
private double walkSpeedMps = 1.2;  // Walking speed (m/s) for time calculations
private boolean enableHyperPooling = false;  // Second-stage bundling
```

### Activation Logic

When `enableStopBased = true`:
1. After generating door-to-door rides (standard ExMAS)
2. For each ride, attempt stop-to-stop conversion
3. Use `BudgetToConstraintsCalculator.budgetToMaxWalkDistance()` to determine per-person constraints
4. Find optimal pickup/dropoff meeting points

---

## Detailed Task List

### Phase 1: Configuration & Domain Model Extensions

| # | Task | Description | Files to Modify/Create |
|---|------|-------------|----------------------|
| 1.1 | Add stop-based config parameters | Add new parameters to ExMasConfigGroup with getters/setters/comments | `ExMasConfigGroup.java` |
| 1.2 | Create StopLocation domain class | Immutable class representing a pickup/dropoff point with coordinates and link ID | `algorithm/domain/StopLocation.java` (new) |
| 1.3 | Extend Ride class | Add optional fields for pickup/dropoff stops and per-passenger walk distances | `Ride.java`, `Ride.Builder` |
| 1.4 | Create RideVariant enum | DOOR_TO_DOOR, STOP_TO_STOP, HYPER_POOLED | `algorithm/domain/RideVariant.java` (new) |
| 1.5 | Extend DrtRequest | Add `maxWalkDistance` field calculated from budget | `DrtRequest.java` |

### Phase 2: Stop Finding Algorithm

| # | Task | Description | Files to Modify/Create |
|---|------|-------------|----------------------|
| 2.1 | Create StopFinder interface | Define contract for finding optimal meeting points | `algorithm/stops/StopFinder.java` (new) |
| 2.2 | Implement GeometricStopFinder | Find stops using geometric center with constraint checks | `algorithm/stops/GeometricStopFinder.java` (new) |
| 2.3 | Implement NetworkStopFinder | Find stops at network nodes/links | `algorithm/stops/NetworkStopFinder.java` (new) |
| 2.4 | Create WalkingDistanceCalculator | Calculate walking distances (Euclidean or network-based) | `algorithm/stops/WalkingDistanceCalculator.java` (new) |
| 2.5 | Implement stop optimization | Find min-walk stops satisfying all passenger constraints | `algorithm/stops/StopOptimizer.java` (new) |

### Phase 3: Stop-to-Stop Ride Generation

| # | Task | Description | Files to Modify/Create |
|---|------|-------------|----------------------|
| 3.1 | Create StopBasedRideGenerator | Convert door-to-door rides to stop-based variants | `algorithm/generation/StopBasedRideGenerator.java` (new) |
| 3.2 | Implement pickup stop finding | Find optimal shared pickup for all passengers in ride | Part of StopBasedRideGenerator |
| 3.3 | Implement dropoff stop finding | Find optimal shared dropoff for all passengers in ride | Part of StopBasedRideGenerator |
| 3.4 | Validate budget with walking | Check that walking disutility doesn't exceed remaining budget | Uses `BudgetValidator` |
| 3.5 | Generate stop-to-stop routes | Route from pickup stop to dropoff stop | Uses `MatsimNetworkCache` |

### Phase 4: Budget Validation Extensions

| # | Task | Description | Files to Modify/Create |
|---|------|-------------|----------------------|
| 4.1 | Extend BudgetValidator | Support actual walk distances per passenger | `BudgetValidator.java` |
| 4.2 | Update calculateDrtScore | Use per-passenger walk distances instead of config value | `BudgetValidator.java` |
| 4.3 | Add walk distance validation | Ensure walk distance <= person's max walk distance from budget | `BudgetValidator.java` |
| 4.4 | Calculate max walk during request creation | Populate DrtRequest.maxWalkDistance using BudgetToConstraintsCalculator | `DrtRequestFactory.java` |

### Phase 5: Engine Integration

| # | Task | Description | Files to Modify/Create |
|---|------|-------------|----------------------|
| 5.1 | Add stop-based phase to ExMasEngine | Insert new phase after pair generation | `ExMasEngine.java` |
| 5.2 | Create StopBasedExMasEngine | Alternative engine with stop-based logic | `algorithm/engine/StopBasedExMasEngine.java` (new) or extend existing |
| 5.3 | Conditional execution | Only run stop-based phase if `enableStopBased = true` | `ExMasEngine.java` |
| 5.4 | Output both variants | Keep door-to-door rides and add stop-to-stop variants | `ExMasEngine.java` |

### Phase 6: Output & CSV Extensions

| # | Task | Description | Files to Modify/Create |
|---|------|-------------|----------------------|
| 6.1 | Extend ExMasCsvWriter | Add columns for stop coordinates and walk distances | `io/ExMasCsvWriter.java` |
| 6.2 | Add ride variant column | Output DOOR_TO_DOOR vs STOP_TO_STOP in CSV | `io/ExMasCsvWriter.java` |
| 6.3 | Write stop locations | Output pickup/dropoff stop IDs and coordinates | `io/ExMasCsvWriter.java` |
| 6.4 | Per-passenger walk distances | Add accessWalkDistance, egressWalkDistance columns | `io/ExMasCsvWriter.java` |

### Phase 7: Hyper-Pooling (Stage 2 - Optional Future Work)

| # | Task | Description | Files to Modify/Create |
|---|------|-------------|----------------------|
| 7.1 | Create HyperPoolEngine | Bundle stop-to-stop rides with compatible stops | `algorithm/engine/HyperPoolEngine.java` (new) |
| 7.2 | Implement stop compatibility | Check if stop-to-stop rides can share pickup/dropoff stops | Part of HyperPoolEngine |
| 7.3 | Build hyper-pool shareability graph | Graph based on stop compatibility | New graph structure |
| 7.4 | Generate hyper-pooled rides | Combine compatible stop-to-stop rides | Part of HyperPoolEngine |

---

## Requirements for Successful Integration

### Functional Requirements

| ID | Requirement | Acceptance Criteria |
|----|-------------|---------------------|
| FR1 | Enable via config | `enableStopBased = true` activates stop-based ride generation |
| FR2 | Per-person walk constraints | Each person's max walk distance is calculated from their remaining budget using `BudgetToConstraintsCalculator.budgetToMaxWalkDistance()` |
| FR3 | Shared pickup point | All passengers in a stop-based ride walk to the same pickup location |
| FR4 | Shared dropoff point | All passengers in a stop-based ride walk from the same dropoff location |
| FR5 | Budget validation | Stop-based rides must pass budget validation including walk disutility |
| FR6 | Walk distance hard cap | No passenger walks more than `maxWalkDistanceMeters` regardless of budget |
| FR7 | Utility-preserving | Stop-based rides must have non-negative remaining budget for all passengers |
| FR8 | Output completeness | CSV output includes stop coordinates, walk distances, and ride variant |

### Technical Requirements

| ID | Requirement | Acceptance Criteria |
|----|-------------|---------------------|
| TR1 | Backward compatibility | Default config (`enableStopBased = false`) produces identical results to current implementation |
| TR2 | Deterministic output | Stop-based rides are generated deterministically (reproducible results) |
| TR3 | Parallel processing | Stop finding supports parallel processing similar to existing pair generation |
| TR4 | Memory efficiency | Stop structures don't significantly increase memory usage |
| TR5 | MATSim network integration | Stop locations use valid network links for routing |
| TR6 | Scoring consistency | Walk leg scoring uses same MATSim scoring infrastructure as existing code |

### Performance Requirements

| ID | Requirement | Acceptance Criteria |
|----|-------------|---------------------|
| PR1 | Scalability | Process 10,000+ requests with stop-based conversion in reasonable time |
| PR2 | Incremental processing | Stop conversion can be done per-ride without full reprocessing |

---

## Algorithm Flow (When `enableStopBased = true`)

```
1. Standard ExMAS Phases (unchanged):
   |-- Phase 1: Single ride generation
   |-- Phase 2: Pair ride generation
   |-- Phase 3: Build shareability graph
   +-- Phase 4: Iterative ride extension

2. NEW Stop-Based Phase:
   For each door-to-door ride with degree >= 2:
   |
   |-- 2.1 Calculate max walk distance for each passenger
   |       maxWalkDist[i] = BudgetToConstraintsCalculator.budgetToMaxWalkDistance(
   |                           ride.remainingBudget[i], person[i])
   |
   |-- 2.2 Find optimal pickup stop
   |       pickupStop = StopOptimizer.findOptimalPickup(
   |                       passengers.origins,
   |                       maxWalkDist[],
   |                       network)
   |       If no valid pickup -> skip this ride
   |
   |-- 2.3 Find optimal dropoff stop
   |       dropoffStop = StopOptimizer.findOptimalDropoff(
   |                       passengers.destinations,
   |                       maxWalkDist[],
   |                       network)
   |       If no valid dropoff -> skip this ride
   |
   |-- 2.4 Calculate actual walk distances
   |       accessWalk[i] = distance(origin[i], pickupStop)
   |       egressWalk[i] = distance(dropoffStop, destination[i])
   |
   |-- 2.5 Route stop-to-stop
   |       segment = network.getSegment(pickupStop.linkId, dropoffStop.linkId, time)
   |
   |-- 2.6 Validate budget with actual walks
   |       For each passenger:
   |         drtScore = BudgetValidator.calculateDrtScore(
   |                       request, delay, travelTime, distance,
   |                       accessWalk[i], egressWalk[i])
   |         remainingBudget = drtScore - bestModeScore
   |         If remainingBudget < 0 -> reject ride
   |
   +-- 2.7 Create StopBasedRide
         Add to allRides with RideVariant.STOP_TO_STOP
```

---

## Key Implementation Details

### 1. Stop Location Finding

The optimal stop location minimizes total walking while satisfying all constraints:

```java
// Pseudo-code for geometric approach
public StopLocation findOptimalPickup(List<Coord> origins, double[] maxWalkDist) {
    // Start with weighted centroid
    Coord centroid = calculateWeightedCentroid(origins, maxWalkDist);

    // Check if centroid satisfies all constraints
    for (int i = 0; i < origins.size(); i++) {
        if (distance(origins.get(i), centroid) > maxWalkDist[i]) {
            // Constraint violated - need to adjust
            centroid = adjustToSatisfyConstraints(centroid, origins, maxWalkDist);
        }
    }

    // Snap to network link
    Link nearestLink = network.getNearestLink(centroid);
    return new StopLocation(nearestLink.getId(), nearestLink.getCoord());
}
```

### 2. Budget-to-Walk-Distance Integration

Already exists in `BudgetToConstraintsCalculator.java:229-263`:

```java
public double budgetToMaxWalkDistance(double budget, Person person) {
    // Uses walk mode utility parameters to convert budget to meters
    // Returns minimum of calculated distance and configured max
}
```

### 3. Modified BudgetValidator

Current code at line 123-126 already has placeholder for this:

```java
// for now we will use the walk distance from the settings. Later with hyperpool
// we will use actual walk distances
exMasConfig.getMinDrtAccessEgressDistance(),
exMasConfig.getMinDrtAccessEgressDistance()
```

Change to:

```java
// Use actual walk distances for stop-based rides
actualWalkDistanceAccess[i],
actualWalkDistanceEgress[i]
```

---

## File Structure After Integration

```
contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/
|-- algorithm/
|   |-- domain/
|   |   |-- Ride.java              (extended)
|   |   |-- RideKind.java          (unchanged)
|   |   |-- RideVariant.java       (new)
|   |   |-- StopLocation.java      (new)
|   |   +-- TravelSegment.java     (unchanged)
|   |-- engine/
|   |   |-- ExMasEngine.java       (extended)
|   |   +-- RidePostProcessor.java (unchanged)
|   |-- extension/
|   |   +-- RideExtender.java      (unchanged)
|   |-- generation/
|   |   |-- PairGenerator.java     (unchanged)
|   |   |-- SingleRideGenerator.java (unchanged)
|   |   |-- StopBasedRideGenerator.java (new)
|   |   +-- TimeFilter.java        (unchanged)
|   |-- graph/
|   |   +-- ShareabilityGraph.java (unchanged)
|   |-- network/
|   |   +-- MatsimNetworkCache.java (unchanged)
|   |-- stops/                     (new package)
|   |   |-- StopFinder.java        (new - interface)
|   |   |-- GeometricStopFinder.java (new)
|   |   |-- NetworkStopFinder.java (new)
|   |   |-- StopOptimizer.java     (new)
|   |   +-- WalkingDistanceCalculator.java (new)
|   +-- validation/
|       +-- BudgetValidator.java   (extended)
|-- config/
|   +-- ExMasConfigGroup.java      (extended)
|-- demand/
|   |-- BudgetToConstraintsCalculator.java (unchanged - already has budgetToMaxWalkDistance)
|   |-- DrtRequest.java            (extended with maxWalkDistance)
|   +-- DrtRequestFactory.java     (extended to calculate maxWalkDistance)
+-- io/
    +-- ExMasCsvWriter.java        (extended for stop output)
```

---

## Implementation Priority

1. **High Priority (Core functionality)**:
   - Configuration parameters (1.1)
   - StopLocation domain class (1.2)
   - Ride extension for stops (1.3)
   - StopBasedRideGenerator (3.1-3.5)
   - BudgetValidator extension (4.1-4.3)
   - ExMasEngine integration (5.1, 5.3)

2. **Medium Priority (Complete feature)**:
   - Stop optimization algorithms (2.1-2.5)
   - DrtRequest maxWalkDistance (1.5, 4.4)
   - CSV output (6.1-6.4)

3. **Low Priority (Future enhancement)**:
   - Hyper-pooling stage 2 (7.1-7.4)
   - Network-based stop finding (2.3)

---

## Testing Strategy

1. **Unit Tests**:
   - StopOptimizer finding valid stops within constraints
   - BudgetValidator with varying walk distances
   - Walk distance calculation accuracy

2. **Integration Tests**:
   - End-to-end with `enableStopBased = true`
   - Comparison of door-to-door vs stop-based ride counts
   - Budget validation with actual walking

3. **Regression Tests**:
   - Verify `enableStopBased = false` produces identical output to current version

---

## Estimated Effort

| Phase | Tasks | Complexity | Dependencies |
|-------|-------|------------|--------------|
| Phase 1 | 5 tasks | Low | None |
| Phase 2 | 5 tasks | Medium | Phase 1 |
| Phase 3 | 5 tasks | Medium | Phase 1, 2 |
| Phase 4 | 4 tasks | Low | Phase 1 |
| Phase 5 | 4 tasks | Medium | Phase 1-4 |
| Phase 6 | 4 tasks | Low | Phase 1-5 |
| Phase 7 | 4 tasks | High | Phase 1-6 |

**Total: 31 tasks across 7 phases**
