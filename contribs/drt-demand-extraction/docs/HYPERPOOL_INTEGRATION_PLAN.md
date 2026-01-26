# HyperPool Integration Plan for MATSim drt-demand-extraction

## Overview

This document outlines the integration plan for the HyperPool algorithm into the existing ExMAS-based drt-demand-extraction contrib package.

**References:**
- [HyperPool Paper (arXiv)](https://arxiv.org/abs/2206.05940)
- [ExMAS Repository](https://github.com/RafalKucharskiPK/ExMAS)
- [Published Paper (Nature)](https://www.nature.com/articles/s44333-024-00006-4)

---

## Design Decisions

The following design decisions have been made for this integration:

| # | Decision | Choice | Rationale |
|---|----------|--------|-----------|
| D1 | Output strategy | **Output BOTH** door-to-door AND stop-to-stop variants | Different remaining budgets make both valuable for analysis |
| D2 | Single rides (degree 1) | **Door-to-door only** | Single rides have no sharing benefit from stops |
| D3 | Ride extension order | **D2D extension first**, then convert to stop-based | Simpler implementation, follows paper approach |
| D4 | Budget validation basis | Validate against **best mode score** (base score) | Consistent with existing ExMAS budget model |
| D5 | Walk distance calculation | Use **MATSim's walk router** (beeline factor from config) | Adapts automatically to user's MATSim configuration |
| D6 | Hyper-pool stop structure | **Multi-stop sequences** (like paper) | Paper uses sequences of pickup/dropoff stops |
| D7 | Predefined stops format | **MATSim TransitStops/Facilities XML** | Native MATSim format, reusable |
| D8 | Max walk constraint | `min(budgetBased, hardCap)` | Budget-derived limit with safety cap |
| D9 | S2S ride references | **No reference to source D2D ride** | S2S rides are independent entities |
| D10 | Degree filter | **All rides with degree > 1** | Convert all shared rides to stop-based |

---

## Algorithm Understanding

### HyperPool Overview

HyperPool extends ExMAS through a two-stage aggregation approach:

1. **Stop-to-Stop Rides (Stage 1)**: Convert door-to-door rides into rides where travelers walk to common pickup points and from common dropoff points
2. **Hyper-Pooled Rides (Stage 2)**: Bundle stop-to-stop rides that share compatible stops, creating high-occupancy transit-like services

### Key Concepts

- Travelers walk to a **shared pickup point** (PUDO) and from a **shared dropoff point**
- Walking distance is constrained by each traveler's **max acceptable walking distance** (derived from budget)
- Utility calculation includes **walking penalties** that consume the traveler's budget
- The algorithm finds **optimal stop locations** that minimize total walking while keeping all travelers within their budget constraints

---

## Stop Definition Approaches

### Why Network-Based Stops Are Required

For **travel time calculations and routing**, stops MUST be associated with network links because:

1. MATSim's routing infrastructure (`LeastCostPathCalculator`) requires link IDs as origin/destination
2. Travel times are computed along network paths between links
3. The `MatsimNetworkCache` routes from link to link
4. DRT vehicles operate on the network and need valid pickup/dropoff locations

**However**, the stop *finding* process can use different approaches before snapping to the network.

### Stop Definition Strategies

| Strategy | Description | Pros | Cons |
|----------|-------------|------|------|
| **Geometric** | Find optimal point in 2D space, then snap to nearest link | Minimizes walking, flexible | Snapping can add distance |
| **Network Node-Based** | Only consider network nodes as candidates | Natural stopping points | Limited candidate set |
| **Network Link-Based** | Consider all links within radius | More candidates | Computationally expensive |
| **Predefined Stops** | Use predefined PUDO locations (like PT stops) | Realistic, operator-defined | Requires input data |

### The Link Centroid Snapping Problem

**Issue**: When snapping a geometric stop to a network link, we typically use the link's centroid or "to node". For long links (e.g., rural roads, highways), this can add significant walking distance.

**Example**:
```
Optimal geometric stop: (100, 200)
Nearest link: 500m long highway link
Link centroid: (100, 450)  <- 250m away from optimal!

Additional walking per passenger: up to 250m
For a 4-person ride: up to 1000m total additional walking
```

**Mitigation Strategies**:

1. **Use MATSim's existing utilities**: MATSim already provides `CoordUtils.distancePointLinesegment()` and `CoordUtils.orthogonalProjectionOnLineSegment()` for calculating the shortest distance to a link
2. **"Teleporting" along link**: In MATSim's conceptual model, once an agent reaches a link, they can board anywhere on it. Walking distance = perpendicular distance to link, not to link centroid
3. **Link Length Filter**: Optionally exclude very long links from candidate set
4. **Statistics Logging**: Track and log actual walk distances vs what centroid-based would have been

### MATSim's Existing Utilities

```java
// MATSim already has these in CoordUtils:

// Get shortest distance from point to line segment (the link)
double walkDistance = CoordUtils.distancePointLinesegment(
    link.getFromNode().getCoord(),
    link.getToNode().getCoord(),
    passengerOrigin
);

// Get the actual closest point on the link (if needed for visualization)
Coord closestPoint = CoordUtils.orthogonalProjectionOnLineSegment(
    link.getFromNode().getCoord(),
    link.getToNode().getCoord(),
    passengerOrigin
);
```

### Simplified Approach: Walk-to-Link Model

In MATSim, the travel model is inherently link-based:
- **Walking**: Agent walks from their activity location to the nearest point on the link
- **Boarding**: Agent can board the vehicle anywhere on the link (conceptually "teleports" to vehicle)
- **Routing**: Vehicle travel times are computed link-to-link via `MatsimNetworkCache`

This means we don't need complex snapping logic. For stop-based pooling:

```java
// Pseudo-code for walk distance calculation
public double calculateWalkDistanceToLink(Coord origin, Link link) {
    // MATSim's existing utility handles all the math
    return CoordUtils.distancePointLinesegment(
        link.getFromNode().getCoord(),
        link.getToNode().getCoord(),
        origin
    );
}

// The StopLocation only needs the link ID for routing
// The walk distance is calculated separately per passenger
public record StopLocation(Id<Link> linkId, Coord representativeCoord) {}
```

### Statistics Logging (for analysis)

```java
// Track how much we save vs naive centroid approach
double centroidDistance = CoordUtils.calcEuclideanDistance(origin, link.getCoord());
double actualWalkDistance = CoordUtils.distancePointLinesegment(...);
double savingsVsCentroid = centroidDistance - actualWalkDistance;

// Log aggregate stats:
//   - Average savings vs centroid approach
//   - Cases where link length > walk distance (long links)
//   - Distribution of walk distances
log.debug("Walk to link {}: {}m (centroid would be {}m, saved {}m)",
          link.getId(), actualWalkDistance, centroidDistance, savingsVsCentroid);
```

---

## Configuration Design

### New Configuration Parameters in `ExMasConfigGroup`

```java
// ===========================================
// Stop-Based Pooling (Stage 1) Settings
// ===========================================

/** Master switch to enable stop-based ride generation */
private boolean enableStopBased = false;

/** Hard cap on walking distance (meters) - regardless of budget */
private double maxWalkDistanceMeters = 500.0;

/** Radius to search for optimal stops around passenger origins/destinations */
private double stopSearchRadiusMeters = 300.0;

/**
 * Stop finding strategy:
 * - GEOMETRIC: Find optimal 2D point, snap to network
 * - NETWORK_NODE: Only consider network nodes
 * - NETWORK_LINK: Consider all links within radius
 * - PREDEFINED: Use predefined stop locations (requires input file)
 */
private StopFindingStrategy stopFindingStrategy = StopFindingStrategy.GEOMETRIC;

/**
 * Max link length to consider for stops (optional filter).
 * Note: With MATSim's walk-to-link model, long links are not problematic
 * since we use CoordUtils.distancePointLinesegment() to calculate the
 * perpendicular (shortest) distance to the link.
 */
private double maxLinkLengthForStopMeters = Double.MAX_VALUE;  // No filter by default

/** Walking speed for time calculations (m/s) - default 1.2 m/s = 4.3 km/h */
private double walkSpeedMps = 1.2;

/**
 * Path to predefined stops file (if strategy = PREDEFINED).
 * Supports MATSim TransitStops or Facilities XML format.
 */
private String predefinedStopsFile = null;

/**
 * Whether to use MATSim's walk router for distance/time calculations.
 * When true: Uses the bound walk router which respects beeline factor from config.
 * When false: Uses Euclidean distance (for testing/debugging only).
 * Default: true - automatically adapts to user's MATSim configuration.
 */
private boolean useMatsimWalkRouter = true;

// ===========================================
// Hyper-Pooling (Stage 2) Settings
// ===========================================

/** Enable second-stage bundling of stop-to-stop rides */
private boolean enableHyperPooling = false;

/** Max walking distance to relocated stop in hyper-pooling (meters) */
private double hyperPoolMaxStopRelocationMeters = 200.0;

/** Max number of stops in a hyper-pooled ride */
private int hyperPoolMaxStops = 6;

/** Time window for compatible stop-to-stop rides (seconds) */
private double hyperPoolTimeWindowSeconds = 900.0;

/** Minimum occupancy for hyper-pooled rides to be attractive */
private int hyperPoolMinOccupancy = 4;

/** Stop proximity threshold for considering stops as "same" (meters) */
private double hyperPoolStopProximityMeters = 100.0;
```

### Activation Logic

**Stage 1 (Stop-Based)**:
When `enableStopBased = true`:
1. After generating door-to-door rides (standard ExMAS)
2. For each ride, attempt stop-to-stop conversion
3. Use `BudgetToConstraintsCalculator.budgetToMaxWalkDistance()` to determine per-person constraints
4. Find optimal pickup/dropoff meeting points
5. Validate budget including walking penalties

**Stage 2 (Hyper-Pooling)**:
When `enableHyperPooling = true` (requires `enableStopBased = true`):
1. After generating stop-to-stop rides
2. Build stop-compatibility graph from stop-to-stop rides
3. Find rides with compatible/nearby stops
4. Bundle into hyper-pooled rides with shared stop sequences
5. Re-validate budgets with potentially relocated stops

---

## Detailed Task List

### Phase 1: Configuration & Domain Model Extensions

| # | Task | Description | Files to Modify/Create |
|---|------|-------------|----------------------|
| 1.1 | Add stop-based config parameters | Add Stage 1 parameters to ExMasConfigGroup with getters/setters/comments | `ExMasConfigGroup.java` |
| 1.2 | Add hyper-pool config parameters | Add Stage 2 parameters to ExMasConfigGroup | `ExMasConfigGroup.java` |
| 1.3 | Create StopLocation domain class | Immutable class with linkId, coord, and snapping penalty | `algorithm/domain/StopLocation.java` (new) |
| 1.4 | Create StopFindingStrategy enum | GEOMETRIC, NETWORK_NODE, NETWORK_LINK, PREDEFINED | `config/StopFindingStrategy.java` (new) |
| 1.5 | Extend Ride class for stops | Add optional pickupStop, dropoffStop, accessWalkDistances[], egressWalkDistances[] | `Ride.java`, `Ride.Builder` |
| 1.6 | Create RideVariant enum | DOOR_TO_DOOR, STOP_TO_STOP, HYPER_POOLED | `algorithm/domain/RideVariant.java` (new) |
| 1.7 | Extend DrtRequest | Add `maxWalkDistance` field calculated from budget | `DrtRequest.java` |
| 1.8 | Create HyperPooledRide domain | Extended ride with stop sequence and sub-ride references | `algorithm/domain/HyperPooledRide.java` (new) |

### Phase 2: Stop Finding Algorithm

| # | Task | Description | Files to Modify/Create |
|---|------|-------------|----------------------|
| 2.1 | Create StopFinder interface | Define contract: `Optional<StopLocation> findStop(List<Coord> points, double[] maxWalkDistances)` | `algorithm/stops/StopFinder.java` (new) |
| 2.2 | Implement GeometricStopFinder | Find optimal point using weighted centroid, constraint adjustment | `algorithm/stops/GeometricStopFinder.java` (new) |
| 2.3 | Implement NetworkNodeStopFinder | Search network nodes within radius, select min-walk valid node | `algorithm/stops/NetworkNodeStopFinder.java` (new) |
| 2.4 | Implement NetworkLinkStopFinder | Search links within radius, use closest point on link geometry | `algorithm/stops/NetworkLinkStopFinder.java` (new) |
| 2.5 | Implement PredefinedStopFinder | Load stops from MATSim TransitStops/Facilities XML, find nearest valid predefined stop | `algorithm/stops/PredefinedStopFinder.java` (new) |
| 2.6 | Create WalkingDistanceCalculator | Use MATSim's `CoordUtils.distancePointLinesegment()` for walk-to-link distances | `algorithm/stops/WalkingDistanceCalculator.java` (new) |
| 2.7 | Implement walk statistics tracking | Track avg walk distance, savings vs centroid approach | Part of WalkingDistanceCalculator |
| 2.8 | Create LinkCandidateFinder | Find candidate links within search radius using spatial index | `algorithm/stops/LinkCandidateFinder.java` (new) |
| 2.9 | Create StopFinderFactory | Create appropriate StopFinder based on config strategy | `algorithm/stops/StopFinderFactory.java` (new) |

### Phase 3: Stop-to-Stop Ride Generation

| # | Task | Description | Files to Modify/Create |
|---|------|-------------|----------------------|
| 3.1 | Create StopBasedRideGenerator | Main class to convert door-to-door rides to stop-based | `algorithm/generation/StopBasedRideGenerator.java` (new) |
| 3.2 | Implement pickup stop finding | For each ride, find shared pickup satisfying all constraints | Part of StopBasedRideGenerator |
| 3.3 | Implement dropoff stop finding | For each ride, find shared dropoff satisfying all constraints | Part of StopBasedRideGenerator |
| 3.4 | Calculate per-passenger walk distances | Compute access/egress walk for each passenger to/from stops | Part of StopBasedRideGenerator |
| 3.5 | Generate stop-to-stop routes | Route from pickup stop link to dropoff stop link | Uses `MatsimNetworkCache` |
| 3.6 | Handle infeasible conversions | Skip rides where no valid stop pair exists, log statistics | Part of StopBasedRideGenerator |
| 3.7 | Parallel processing support | Process rides in parallel similar to PairGenerator | Part of StopBasedRideGenerator |

### Phase 4: Budget Validation Extensions

| # | Task | Description | Files to Modify/Create |
|---|------|-------------|----------------------|
| 4.1 | Extend BudgetValidator signature | Add overload accepting per-passenger walk distances | `BudgetValidator.java` |
| 4.2 | Update calculateDrtScore | Use actual walk distances instead of config value | `BudgetValidator.java` |
| 4.3 | Add walk distance hard cap validation | Reject if any walk > maxWalkDistanceMeters (regardless of budget) | `BudgetValidator.java` |
| 4.4 | Calculate max walk during request creation | Populate DrtRequest.maxWalkDistance using BudgetToConstraintsCalculator | `DrtRequestFactory.java` |
| 4.5 | Add validation statistics | Track/log how many rides rejected due to walk distance vs budget | `BudgetValidator.java` |

### Phase 5: Engine Integration (Stage 1)

| # | Task | Description | Files to Modify/Create |
|---|------|-------------|----------------------|
| 5.1 | Add stop-based phase to ExMasEngine | Insert Phase 5 after ride extension (or after pairs if maxDegree=2) | `ExMasEngine.java` |
| 5.2 | Conditional execution | Only run stop-based phase if `enableStopBased = true` | `ExMasEngine.java` |
| 5.3 | Output both variants | Keep door-to-door rides, add stop-to-stop variants with different indices | `ExMasEngine.java` |
| 5.4 | Add stop-based statistics logging | Log conversion rate, average walk distances, snapping penalties | `ExMasEngine.java` |

### Phase 6: Output & CSV Extensions

| # | Task | Description | Files to Modify/Create |
|---|------|-------------|----------------------|
| 6.1 | Extend ExMasCsvWriter | Add columns for stop coordinates and walk distances | `io/ExMasCsvWriter.java` |
| 6.2 | Add ride variant column | Output DOOR_TO_DOOR, STOP_TO_STOP, HYPER_POOLED | `io/ExMasCsvWriter.java` |
| 6.3 | Write stop locations | Output pickupStopLinkId, pickupStopX/Y, dropoffStopLinkId, dropoffStopX/Y | `io/ExMasCsvWriter.java` |
| 6.4 | Per-passenger walk distances | Add accessWalkDistance_i, egressWalkDistance_i columns | `io/ExMasCsvWriter.java` |
| 6.5 | Write snapping statistics | Output snapping penalty per stop | `io/ExMasCsvWriter.java` |
| 6.6 | Create stop statistics summary | Aggregate statistics file for stop-based conversion | `io/StopStatisticsWriter.java` (new) |

---

## Phase 7: Hyper-Pooling (Stage 2) - Detailed Tasks

### Overview

Hyper-pooling bundles multiple stop-to-stop rides that have compatible pickup and/or dropoff stops into single high-occupancy rides resembling public transit. The key insight is that stop-to-stop rides can be treated as "pseudo-requests" with their stops as origins/destinations.

### Multi-Stop Sequences (Paper Approach)

Per the [HyperPool paper](https://www.nature.com/articles/s44333-024-00006-4): *"Travellers of hyper-pooled rides walk to common pick-up points, travel with a shared vehicle **along a sequence of stops** and are dropped off at stops"*. The paper uses `O_r` and `D_r` as **sequences** of pickup and drop-off locations.

**Implementation**: We implement multi-stop sequences as described in the paper:
```
Hyper-pooled ride example:
  Pickup_A → Pickup_B → Pickup_C → Dropoff_A → Dropoff_B → Dropoff_C
```

Each passenger:
1. Walks to their assigned pickup stop in the sequence
2. Rides the vehicle (potentially passing through other stops)
3. Alights at their assigned dropoff stop
4. Walks to their final destination

**Note**: The stop sequence is optimized for routing efficiency (typically FIFO for pickups, then FIFO for dropoffs, but may be optimized further).

### Algorithm Flow

```
1. Input: All stop-to-stop rides from Stage 1

2. Build Stop Compatibility Graph:
   For each pair of stop-to-stop rides (R1, R2):
   |-- Check temporal compatibility (departure times within window)
   |-- Check pickup stop proximity (distance < hyperPoolStopProximityMeters)
   |-- Check dropoff stop proximity (distance < hyperPoolStopProximityMeters)
   |-- If compatible, add edge to graph

3. Find Hyper-Poolable Clusters:
   |-- Use shareability graph to find rides shareable with each other
   |-- Group rides by compatible stop pairs
   |-- Apply maximum occupancy constraint

4. Generate Hyper-Pooled Rides:
   For each cluster of compatible rides:
   |-- Find optimal shared pickup stop (may relocate from individual stops)
   |-- Find optimal shared dropoff stop (may relocate from individual stops)
   |-- Re-calculate walk distances for all passengers to new stops
   |-- Validate budgets with relocated walks
   |-- Route the hyper-pooled ride
   |-- Calculate passenger delays and detours

5. Output: Hyper-pooled rides with transit-like characteristics
```

### Detailed Task Breakdown

#### Phase 7.1: Domain Model for Hyper-Pooling

| # | Task | Description | Files to Modify/Create |
|---|------|-------------|----------------------|
| 7.1.1 | Create StopToStopRide wrapper | Wrapper treating stop-to-stop ride as a "pseudo-request" with stop as O/D | `algorithm/hyperpool/StopToStopRideWrapper.java` (new) |
| 7.1.2 | Create HyperPooledRide class | Ride containing multiple stop-to-stop rides, shared stop sequence | `algorithm/domain/HyperPooledRide.java` (new) |
| 7.1.3 | Define HyperPooledRide fields | sourceRides[], sharedPickupStop, sharedDropoffStop, stopSequence[], passengerBoardingStops[] | Part of HyperPooledRide |
| 7.1.4 | Create StopSequence class | Ordered sequence of stops with passenger boarding/alighting info | `algorithm/domain/StopSequence.java` (new) |

#### Phase 7.2: Stop Compatibility Analysis

| # | Task | Description | Files to Modify/Create |
|---|------|-------------|----------------------|
| 7.2.1 | Create StopCompatibilityChecker | Check if two stop-to-stop rides have compatible stops | `algorithm/hyperpool/StopCompatibilityChecker.java` (new) |
| 7.2.2 | Implement temporal compatibility | Check if ride departure times within hyperPoolTimeWindowSeconds | Part of StopCompatibilityChecker |
| 7.2.3 | Implement spatial compatibility | Check if pickup stops within hyperPoolStopProximityMeters | Part of StopCompatibilityChecker |
| 7.2.4 | Implement directional compatibility | Check if rides go in similar direction (dot product of vectors) | Part of StopCompatibilityChecker |
| 7.2.5 | Create compatibility scoring | Score pairs by: stop proximity + time overlap + direction alignment | Part of StopCompatibilityChecker |

#### Phase 7.3: Hyper-Pool Shareability Graph

| # | Task | Description | Files to Modify/Create |
|---|------|-------------|----------------------|
| 7.3.1 | Create HyperPoolShareabilityGraph | Graph where nodes=stop-to-stop rides, edges=compatible pairs | `algorithm/hyperpool/HyperPoolShareabilityGraph.java` (new) |
| 7.3.2 | Build graph from stop-to-stop rides | Iterate all pairs, check compatibility, add edges | Part of HyperPoolShareabilityGraph |
| 7.3.3 | Implement efficient spatial indexing | Use spatial index (R-tree or grid) for stop proximity queries | Part of HyperPoolShareabilityGraph |
| 7.3.4 | Add temporal indexing | Index rides by departure time for fast window queries | Part of HyperPoolShareabilityGraph |
| 7.3.5 | Implement common neighbor queries | Find rides compatible with all members of a set (like ShareabilityGraph) | Part of HyperPoolShareabilityGraph |

#### Phase 7.4: Stop Relocation for Hyper-Pooling

| # | Task | Description | Files to Modify/Create |
|---|------|-------------|----------------------|
| 7.4.1 | Create StopRelocator | Find optimal shared stop for multiple nearby stops | `algorithm/hyperpool/StopRelocator.java` (new) |
| 7.4.2 | Implement relocation optimization | Minimize total walk increase while respecting relocation limit | Part of StopRelocator |
| 7.4.3 | Validate relocation constraints | Each passenger's additional walk <= hyperPoolMaxStopRelocationMeters | Part of StopRelocator |
| 7.4.4 | Handle infeasible relocations | Skip hyper-pooling if no valid shared stop exists | Part of StopRelocator |
| 7.4.5 | Track relocation statistics | Log average relocation distance, success rate | Part of StopRelocator |

#### Phase 7.5: Hyper-Pooled Ride Generation

| # | Task | Description | Files to Modify/Create |
|---|------|-------------|----------------------|
| 7.5.1 | Create HyperPoolGenerator | Main orchestrator for generating hyper-pooled rides | `algorithm/hyperpool/HyperPoolGenerator.java` (new) |
| 7.5.2 | Implement cluster finding | Find maximal cliques or greedy clusters in compatibility graph | Part of HyperPoolGenerator |
| 7.5.3 | Apply occupancy constraints | Ensure cluster size respects hyperPoolMinOccupancy and hyperPoolMaxStops | Part of HyperPoolGenerator |
| 7.5.4 | Generate stop sequences | Order stops for efficient routing (pickup1, pickup2, ..., dropoff1, dropoff2, ...) | Part of HyperPoolGenerator |
| 7.5.5 | Route hyper-pooled rides | Multi-stop routing through stop sequence | Part of HyperPoolGenerator |
| 7.5.6 | Calculate passenger metrics | In-vehicle time, wait time, walk time, total delay for each passenger | Part of HyperPoolGenerator |

#### Phase 7.6: Budget Validation for Hyper-Pooling

**Important**: Budget validation for hyper-pooled rides calculates remaining budget against the **base score** (best alternative mode score), NOT the stop-to-stop ride's remaining budget. This ensures consistent evaluation across all ride variants.

| # | Task | Description | Files to Modify/Create |
|---|------|-------------|----------------------|
| 7.6.1 | Extend BudgetValidator for hyper-pooling | Handle relocated stops and multi-stop rides | `BudgetValidator.java` |
| 7.6.2 | Calculate total walk distances | Total walk = walk to relocated stop (from origin, not from S2S stop) | Part of BudgetValidator |
| 7.6.3 | Calculate hyper-pool in-vehicle time | Sum of segments between passenger's boarding and alighting stops | Part of BudgetValidator |
| 7.6.4 | Validate against base score | `remainingBudget = hyperPoolDrtScore - request.getBestModeScore()` | Part of BudgetValidator |
| 7.6.5 | Apply fare discounts | Hyper-pooled rides may have larger fare discounts (configurable) | Part of BudgetValidator |

#### Phase 7.7: Engine Integration (Stage 2)

| # | Task | Description | Files to Modify/Create |
|---|------|-------------|----------------------|
| 7.7.1 | Add hyper-pooling phase to ExMasEngine | Insert after stop-based phase | `ExMasEngine.java` |
| 7.7.2 | Conditional execution | Only run if `enableHyperPooling = true` (and `enableStopBased = true`) | `ExMasEngine.java` |
| 7.7.3 | Output all variants | Keep door-to-door, stop-to-stop, and hyper-pooled rides | `ExMasEngine.java` |
| 7.7.4 | Add hyper-pooling statistics | Log: clusters found, avg occupancy, conversion rate | `ExMasEngine.java` |

#### Phase 7.8: Output Extensions for Hyper-Pooling

| # | Task | Description | Files to Modify/Create |
|---|------|-------------|----------------------|
| 7.8.1 | Extend CSV for hyper-pooled rides | Add stopSequence, boardingStopIndex, alightingStopIndex per passenger | `io/ExMasCsvWriter.java` |
| 7.8.2 | Write passenger origins/destinations | Output original passenger O/D coords (hyper-pooled rides are self-contained) | `io/ExMasCsvWriter.java` |
| 7.8.3 | Write relocation distances | Add relocationDistanceAccess, relocationDistanceEgress per passenger | `io/ExMasCsvWriter.java` |
| 7.8.4 | Create hyper-pool summary | Aggregate statistics: avg occupancy, VKT savings, passenger-km | `io/HyperPoolStatisticsWriter.java` (new) |

---

## Requirements for Successful Integration

### Functional Requirements

| ID | Requirement | Acceptance Criteria |
|----|-------------|---------------------|
| FR1 | Enable Stage 1 via config | `enableStopBased = true` activates stop-based ride generation |
| FR2 | Enable Stage 2 via config | `enableHyperPooling = true` activates hyper-pooling (requires FR1) |
| FR3 | Per-person walk constraints | Each person's max walk distance is calculated from their remaining budget using `BudgetToConstraintsCalculator.budgetToMaxWalkDistance()` |
| FR4 | Shared pickup point (Stage 1) | All passengers in a stop-based ride walk to the same pickup location |
| FR5 | Shared dropoff point (Stage 1) | All passengers in a stop-based ride walk from the same dropoff location |
| FR6 | Stop relocation (Stage 2) | Hyper-pooling can relocate stops within `hyperPoolMaxStopRelocationMeters` |
| FR7 | Budget validation | All ride variants must pass budget validation including walk disutility |
| FR8 | Walk distance hard cap | No passenger walks more than `maxWalkDistanceMeters` regardless of budget |
| FR9 | Utility-preserving | All ride variants must have non-negative remaining budget for all passengers |
| FR10 | Output completeness | CSV output includes stop coordinates, walk distances, and ride variant |
| FR11 | Snapping statistics | System logs/outputs statistics on link snapping penalties |

### Technical Requirements

| ID | Requirement | Acceptance Criteria |
|----|-------------|---------------------|
| TR1 | Backward compatibility | Default config produces identical results to current implementation |
| TR2 | Deterministic output | All phases generate deterministic results (reproducible) |
| TR3 | Parallel processing | Stop finding and hyper-pooling support parallel processing |
| TR4 | Memory efficiency | Stop and hyper-pool structures don't significantly increase memory usage |
| TR5 | MATSim network integration | All stop locations use valid network links for routing |
| TR6 | Scoring consistency | Walk leg scoring uses same MATSim scoring infrastructure as existing code |
| TR7 | Link snapping quality | System uses closest point on link (not centroid) for long links |

### Performance Requirements

| ID | Requirement | Acceptance Criteria |
|----|-------------|---------------------|
| PR1 | Scalability Stage 1 | Process 10,000+ requests with stop-based conversion in reasonable time |
| PR2 | Scalability Stage 2 | Process 5,000+ stop-to-stop rides for hyper-pooling in reasonable time |
| PR3 | Spatial indexing | Use spatial index for stop proximity queries (not O(n²) brute force) |

---

## Algorithm Flow (Complete)

```
=======================================================================
STANDARD ExMAS PHASES (unchanged)
=======================================================================

Phase 1: Single Ride Generation
    For each request:
        Create degree-1 ride with direct travel

Phase 2: Pair Ride Generation
    For each request pair (i, j):
        Generate FIFO and LIFO variants
        Validate budget for both passengers

Phase 3: Build Shareability Graph
    Create graph from degree-2 rides
    Nodes = requests, Edges = valid pairings

Phase 4: Iterative Ride Extension
    For degree = 3 to maxDegree:
        Extend rides using shareability graph
        Validate budget for all passengers

=======================================================================
STAGE 1: STOP-BASED CONVERSION (when enableStopBased = true)
=======================================================================

Phase 5: Stop-Based Ride Generation
    For each door-to-door ride with degree >= 2:
    |
    |-- 5.1 Calculate max walk distance for each passenger
    |       maxWalkDist[i] = min(
    |           BudgetToConstraintsCalculator.budgetToMaxWalkDistance(budget[i]),
    |           config.maxWalkDistanceMeters
    |       )
    |
    |-- 5.2 Find optimal pickup stop
    |       pickupStop = StopFinder.findStop(
    |           passengers.origins,
    |           maxWalkDist[],
    |           config.stopFindingStrategy
    |       )
    |       If no valid pickup -> skip, log reason
    |
    |-- 5.3 Snap to network (if geometric)
    |       snappedPickup = NetworkSnapper.snap(pickupStop, network)
    |       Log snapping penalty: |snappedPickup - pickupStop|
    |
    |-- 5.4 Find optimal dropoff stop (same process)
    |
    |-- 5.5 Calculate actual walk distances
    |       accessWalk[i] = distance(origin[i], snappedPickup)
    |       egressWalk[i] = distance(snappedDropoff, destination[i])
    |
    |-- 5.6 Validate walk distance hard cap
    |       For each passenger:
    |           If accessWalk[i] + egressWalk[i] > maxWalkDistanceMeters:
    |               reject ride, log reason
    |
    |-- 5.7 Route stop-to-stop
    |       segment = network.getSegment(
    |           snappedPickup.linkId,
    |           snappedDropoff.linkId,
    |           departureTime
    |       )
    |
    |-- 5.8 Validate budget with actual walks
    |       For each passenger:
    |           drtScore = BudgetValidator.calculateDrtScore(
    |               request, delay, travelTime, distance,
    |               accessWalk[i], egressWalk[i]
    |           )
    |           remainingBudget = drtScore - bestModeScore
    |           If remainingBudget < 0 -> reject ride, log reason
    |
    +-- 5.9 Create Stop-Based Ride
            Add to allRides with RideVariant.STOP_TO_STOP
            Include: pickupStop, dropoffStop, accessWalk[], egressWalk[]

    Log statistics:
        - Conversion rate (stop-based / door-to-door)
        - Average walk distances (access, egress, total)
        - Average snapping penalty
        - Rejection reasons distribution

=======================================================================
STAGE 2: HYPER-POOLING (when enableHyperPooling = true)
=======================================================================

Phase 6: Build Hyper-Pool Compatibility Graph
    |
    |-- 6.1 Index stop-to-stop rides
    |       Build spatial index on pickup/dropoff stops
    |       Build temporal index on departure times
    |
    |-- 6.2 Find compatible pairs
    |       For each stop-to-stop ride R1:
    |           Query spatial index for rides with nearby pickup stops
    |           Filter by: dropoff proximity, time window, direction
    |           For each compatible R2:
    |               Add edge (R1, R2) to compatibility graph
    |
    +-- 6.3 Log graph statistics
            Nodes, edges, average degree

Phase 7: Generate Hyper-Pooled Rides
    |
    |-- 7.1 Find hyper-poolable clusters
    |       Use greedy or clique-based algorithm
    |       Respect: hyperPoolMinOccupancy, hyperPoolMaxStops
    |
    |-- 7.2 For each cluster:
    |       |
    |       |-- 7.2.1 Collect all pickup stops from cluster rides
    |       |
    |       |-- 7.2.2 Find shared pickup stop
    |       |         sharedPickup = StopRelocator.findSharedStop(
    |       |             clusterPickupStops[],
    |       |             hyperPoolMaxStopRelocationMeters
    |       |         )
    |       |         If no valid shared stop -> skip cluster
    |       |
    |       |-- 7.2.3 Find shared dropoff stop (same process)
    |       |
    |       |-- 7.2.4 Calculate relocated walk distances
    |       |         For each original passenger:
    |       |             relocationAccess = distance(originalPickup, sharedPickup)
    |       |             relocationEgress = distance(originalDropoff, sharedDropoff)
    |       |             totalAccess = originalAccessWalk + relocationAccess
    |       |             totalEgress = originalEgressWalk + relocationEgress
    |       |
    |       |-- 7.2.5 Validate relocation constraints
    |       |         For each passenger:
    |       |             If relocationAccess > hyperPoolMaxStopRelocationMeters
    |       |                OR relocationEgress > hyperPoolMaxStopRelocationMeters:
    |       |                 reject cluster
    |       |
    |       |-- 7.2.6 Generate stop sequence
    |       |         Order: [sharedPickup] -> [sharedDropoff]
    |       |         (For multi-stop variant: interleave pickups/dropoffs)
    |       |
    |       |-- 7.2.7 Route hyper-pooled ride
    |       |         Multi-segment routing through stop sequence
    |       |
    |       |-- 7.2.8 Calculate passenger metrics
    |       |         For each passenger:
    |       |             inVehicleTime = time from boarding to alighting
    |       |             totalTravelTime = accessWalk + wait + inVehicle + egressWalk
    |       |             delay = totalTravelTime - directTravelTime
    |       |
    |       |-- 7.2.9 Validate budget with hyper-pooling
    |       |         For each passenger:
    |       |             Apply fare discount (hyperPoolFareDiscount)
    |       |             Calculate remaining budget
    |       |             If negative -> reject cluster
    |       |
    |       +-- 7.2.10 Create HyperPooledRide
    |                  Add to allRides with RideVariant.HYPER_POOLED
    |
    +-- 7.3 Log hyper-pooling statistics
            - Clusters attempted, succeeded, failed
            - Average occupancy of hyper-pooled rides
            - VKT reduction vs individual stop-to-stop rides
            - Passenger delay distribution
```

---

## Key Implementation Details

### 1. Walk Distance Calculation Using MATSim Walk Router

**Design Decision**: Use MATSim's bound walk router for distance/time calculations. This automatically respects the user's beeline distance factor and walk speed configuration, ensuring consistency with the rest of the MATSim simulation.

```java
/**
 * Calculates walking distances using MATSim's walk router.
 *
 * This approach automatically adapts to the user's MATSim configuration:
 * - Beeline distance factor (typically 1.3 for urban areas)
 * - Walk speed from scoring parameters
 * - Any custom walk routing logic bound by the user
 */
public class WalkingDistanceCalculator {

    private final TripRouter tripRouter;
    private final String walkMode;

    // Statistics tracking
    private final AtomicLong totalCalculations = new AtomicLong();
    private final AtomicDouble totalWalkDistance = new AtomicDouble();
    private final AtomicDouble totalWalkTime = new AtomicDouble();

    public WalkingDistanceCalculator(TripRouter tripRouter) {
        this.tripRouter = tripRouter;
        this.walkMode = TransportMode.walk;
    }

    /**
     * Calculate walk distance from origin to the closest point on a link.
     * Uses MATSim's walk router which respects beeline factor from config.
     *
     * For stop-based pooling:
     * 1. Calculate Euclidean distance to link (perpendicular)
     * 2. Apply beeline factor via walk router
     */
    public WalkLegInfo calculateWalkToLink(Coord origin, Link targetLink, double departureTime) {
        // Get closest point on link for routing
        Coord closestPoint = CoordUtils.orthogonalProjectionOnLineSegment(
            targetLink.getFromNode().getCoord(),
            targetLink.getToNode().getCoord(),
            origin
        );

        // Use MATSim's walk router - this respects beeline factor
        Facility fromFacility = FacilitiesUtils.wrapCoord(origin);
        Facility toFacility = FacilitiesUtils.wrapLink(targetLink);

        List<? extends PlanElement> walkRoute = tripRouter.calcRoute(
            walkMode, fromFacility, toFacility, departureTime, null, null);

        double walkTime = 0;
        double walkDistance = 0;
        for (PlanElement pe : walkRoute) {
            if (pe instanceof Leg leg) {
                walkTime += leg.getTravelTime().seconds();
                walkDistance += leg.getRoute().getDistance();
            }
        }

        // Track statistics
        totalCalculations.incrementAndGet();
        totalWalkDistance.addAndGet(walkDistance);
        totalWalkTime.addAndGet(walkTime);

        return new WalkLegInfo(walkDistance, walkTime, closestPoint);
    }

    /**
     * Find the best link for a stop given multiple passenger origins.
     * Uses walk router for accurate distance calculation.
     */
    public Optional<Link> findBestStopLink(
            List<Coord> passengerOrigins,
            double[] maxWalkDistances,
            Collection<Link> candidateLinks,
            double departureTime) {

        Link bestLink = null;
        double bestTotalWalk = Double.MAX_VALUE;

        for (Link link : candidateLinks) {
            double totalWalk = 0;
            boolean allWithinConstraints = true;

            for (int i = 0; i < passengerOrigins.size(); i++) {
                WalkLegInfo walkInfo = calculateWalkToLink(
                    passengerOrigins.get(i), link, departureTime);

                if (walkInfo.distance() > maxWalkDistances[i]) {
                    allWithinConstraints = false;
                    break;
                }
                totalWalk += walkInfo.distance();
            }

            if (allWithinConstraints && totalWalk < bestTotalWalk) {
                bestTotalWalk = totalWalk;
                bestLink = link;
            }
        }

        return Optional.ofNullable(bestLink);
    }

    public void logStatistics() {
        long calcs = totalCalculations.get();
        if (calcs > 0) {
            double avgWalk = totalWalkDistance.get() / calcs;
            double avgTime = totalWalkTime.get() / calcs;
            log.info("Walking distance statistics (via MATSim walk router):");
            log.info("  Total calculations: {}", calcs);
            log.info("  Average walk distance: {}m", String.format("%.1f", avgWalk));
            log.info("  Average walk time: {}s", String.format("%.1f", avgTime));
        }
    }

    /** Immutable result of walk calculation */
    public record WalkLegInfo(double distance, double time, Coord closestPointOnLink) {}
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

Current code at line 123-126 already has placeholder:

```java
// for now we will use the walk distance from the settings. Later with hyperpool
// we will use actual walk distances
exMasConfig.getMinDrtAccessEgressDistance(),
exMasConfig.getMinDrtAccessEgressDistance()
```

New signature:

```java
/**
 * Validate and populate budgets for a stop-based ride.
 *
 * @param ride The ride to validate
 * @param accessWalkDistances Per-passenger access walk distances (meters)
 * @param egressWalkDistances Per-passenger egress walk distances (meters)
 * @return Ride with populated budgets, or null if validation fails
 */
public Ride validateStopBasedRide(
        Ride ride,
        double[] accessWalkDistances,
        double[] egressWalkDistances) {

    // Validate hard cap first
    for (int i = 0; i < ride.getDegree(); i++) {
        double totalWalk = accessWalkDistances[i] + egressWalkDistances[i];
        if (totalWalk > exMasConfig.getMaxWalkDistanceMeters()) {
            log.debug("Ride {} rejected: passenger {} walk {}m > max {}m",
                      ride.getIndex(), i, totalWalk, exMasConfig.getMaxWalkDistanceMeters());
            return null;
        }
    }

    // Calculate scores with actual walk distances
    double[] remainingBudgets = new double[ride.getDegree()];
    for (int i = 0; i < ride.getDegree(); i++) {
        double drtScore = calculateDrtScore(
            ride.getRequests()[i],
            ride.getDelays()[i],
            ride.getPassengerTravelTimes()[i],
            ride.getPassengerDistances()[i],
            accessWalkDistances[i],
            egressWalkDistances[i]
        );

        remainingBudgets[i] = drtScore - ride.getRequests()[i].getBestModeScore();

        if (remainingBudgets[i] < 0) {
            log.debug("Ride {} rejected: passenger {} negative budget {}",
                      ride.getIndex(), i, remainingBudgets[i]);
            return null;
        }
    }

    return ride.toBuilder()
        .remainingBudgets(remainingBudgets)
        .accessWalkDistances(accessWalkDistances)
        .egressWalkDistances(egressWalkDistances)
        .build();
}
```

---

## File Structure After Full Integration

```
contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/
|-- algorithm/
|   |-- domain/
|   |   |-- Ride.java                  (extended: stops, walks)
|   |   |-- RideKind.java              (unchanged)
|   |   |-- RideVariant.java           (new)
|   |   |-- StopLocation.java          (new)
|   |   |-- StopSequence.java          (new)
|   |   |-- HyperPooledRide.java       (new)
|   |   +-- TravelSegment.java         (unchanged)
|   |-- engine/
|   |   |-- ExMasEngine.java           (extended: phase 5, 6, 7)
|   |   +-- RidePostProcessor.java     (unchanged)
|   |-- extension/
|   |   +-- RideExtender.java          (unchanged)
|   |-- generation/
|   |   |-- PairGenerator.java         (unchanged)
|   |   |-- SingleRideGenerator.java   (unchanged)
|   |   |-- StopBasedRideGenerator.java (new)
|   |   +-- TimeFilter.java            (unchanged)
|   |-- graph/
|   |   +-- ShareabilityGraph.java     (unchanged)
|   |-- hyperpool/                     (new package)
|   |   |-- StopToStopRideWrapper.java (new)
|   |   |-- StopCompatibilityChecker.java (new)
|   |   |-- HyperPoolShareabilityGraph.java (new)
|   |   |-- StopRelocator.java         (new)
|   |   +-- HyperPoolGenerator.java    (new)
|   |-- network/
|   |   +-- MatsimNetworkCache.java    (unchanged)
|   |-- stops/                         (new package)
|   |   |-- StopFinder.java            (new - interface)
|   |   |-- GeometricStopFinder.java   (new)
|   |   |-- NetworkNodeStopFinder.java (new)
|   |   |-- NetworkLinkStopFinder.java (new)
|   |   |-- PredefinedStopFinder.java  (new)
|   |   |-- StopFinderFactory.java     (new)
|   |   |-- LinkCandidateFinder.java   (new)
|   |   +-- WalkingDistanceCalculator.java (new - uses CoordUtils.distancePointLinesegment)
|   +-- validation/
|       +-- BudgetValidator.java       (extended)
|-- config/
|   |-- ExMasConfigGroup.java          (extended)
|   +-- StopFindingStrategy.java       (new)
|-- demand/
|   |-- BudgetToConstraintsCalculator.java (unchanged)
|   |-- DrtRequest.java                (extended: maxWalkDistance)
|   +-- DrtRequestFactory.java         (extended)
+-- io/
    |-- ExMasCsvWriter.java            (extended)
    |-- StopStatisticsWriter.java      (new)
    +-- HyperPoolStatisticsWriter.java (new)
```

---

## Implementation Priority

1. **High Priority (Stage 1 Core)**:
   - Configuration parameters (1.1, 1.2)
   - StopLocation domain class (1.3)
   - Ride extension for stops (1.5)
   - WalkingDistanceCalculator using MATSim's CoordUtils (2.6, 2.7)
   - GeometricStopFinder (2.2)
   - StopBasedRideGenerator (3.1-3.7)
   - BudgetValidator extension (4.1-4.5)
   - ExMasEngine integration (5.1-5.4)

2. **Medium Priority (Stage 1 Complete)**:
   - Other stop finders (2.3, 2.4, 2.5)
   - DrtRequest maxWalkDistance (1.7, 4.4)
   - CSV output (6.1-6.6)

3. **Medium-High Priority (Stage 2 Core)**:
   - HyperPooledRide domain (7.1.1-7.1.4)
   - StopCompatibilityChecker (7.2.1-7.2.5)
   - HyperPoolShareabilityGraph (7.3.1-7.3.5)
   - StopRelocator (7.4.1-7.4.5)
   - HyperPoolGenerator (7.5.1-7.5.6)

4. **Lower Priority (Stage 2 Complete)**:
   - Budget validation for hyper-pooling (7.6.1-7.6.5)
   - Engine integration Stage 2 (7.7.1-7.7.4)
   - Output extensions (7.8.1-7.8.4)

---

## Testing Strategy

### Stage 1 Tests

1. **Unit Tests**:
   - NetworkSnapper: closest point on link calculation
   - NetworkSnapper: long link filtering
   - GeometricStopFinder: weighted centroid calculation
   - GeometricStopFinder: constraint satisfaction
   - BudgetValidator: walk distance hard cap
   - BudgetValidator: actual walk distance scoring

2. **Integration Tests**:
   - End-to-end with `enableStopBased = true`
   - Comparison of door-to-door vs stop-based ride counts
   - Snapping penalty statistics validation

3. **Regression Tests**:
   - `enableStopBased = false` produces identical output

### Stage 2 Tests

1. **Unit Tests**:
   - StopCompatibilityChecker: temporal window
   - StopCompatibilityChecker: spatial proximity
   - StopRelocator: relocation limit enforcement
   - HyperPoolShareabilityGraph: common neighbor queries

2. **Integration Tests**:
   - End-to-end with `enableHyperPooling = true`
   - Occupancy distribution validation
   - VKT savings calculation

---

## Estimated Effort Summary

| Phase | Tasks | Complexity | Dependencies |
|-------|-------|------------|--------------|
| Phase 1 | 8 tasks | Low | None |
| Phase 2 | 9 tasks | Medium | Phase 1 |
| Phase 3 | 7 tasks | Medium | Phase 1, 2 |
| Phase 4 | 5 tasks | Low | Phase 1 |
| Phase 5 | 4 tasks | Medium | Phase 1-4 |
| Phase 6 | 6 tasks | Low | Phase 1-5 |
| Phase 7.1 | 4 tasks | Medium | Phase 1-6 |
| Phase 7.2 | 5 tasks | Medium | Phase 7.1 |
| Phase 7.3 | 5 tasks | High | Phase 7.1, 7.2 |
| Phase 7.4 | 5 tasks | Medium | Phase 7.1 |
| Phase 7.5 | 6 tasks | High | Phase 7.1-7.4 |
| Phase 7.6 | 5 tasks | Medium | Phase 7.5 |
| Phase 7.7 | 4 tasks | Medium | Phase 7.1-7.6 |
| Phase 7.8 | 4 tasks | Low | Phase 7.1-7.7 |

**Total: 77 tasks across 14 phases**
- Stage 1 (Stop-Based): 39 tasks
- Stage 2 (Hyper-Pooling): 38 tasks
