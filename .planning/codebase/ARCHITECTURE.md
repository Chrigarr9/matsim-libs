# Architecture

**Analysis Date:** 2026-01-20

## Pattern Overview

**Overall:** Module-based Pipeline Architecture with MATSim Controller Integration

**Key Characteristics:**
- MATSim contribution module using Guice dependency injection
- Pipeline-style processing: Population -> Requests -> Rides -> Output
- Event-driven execution via MATSim ShutdownListener
- Separation of demand extraction (MATSim integration) and algorithm (ExMAS core)
- Parallel processing with deterministic output ordering

## Context: MATSim Architecture

The DRT demand extraction module is a MATSim "contrib" - an optional extension that integrates with MATSim's core simulation framework.

**MATSim Core Concepts:**
- `Config`: XML-based configuration with module groups
- `Scenario`: Contains network, population, transit schedule
- `Controler`: Orchestrates simulation iterations and event handling
- `AbstractModule`: Guice module for dependency binding
- `ControllerListener`: Event handlers for iteration/shutdown events

## Layers

**Configuration Layer:**
- Purpose: Define algorithm parameters and runtime settings
- Location: `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/config/`
- Contains: `ExMasConfigGroup.java` - MATSim ReflectiveConfigGroup with 60+ parameters
- Depends on: MATSim core config infrastructure
- Used by: All other layers

**Demand Processing Layer:**
- Purpose: Extract DRT requests from MATSim population plans
- Location: `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/demand/`
- Contains:
  - `DemandExtractionModule.java` - Main Guice module, entry point
  - `DemandExtractionListener.java` - ShutdownListener orchestrating the pipeline
  - `DrtRequestFactory.java` - Builds DrtRequest objects from trips
  - `ModeRoutingCache.java` - Caches mode alternatives and scores
  - `ChainIdentifier.java` - Identifies subtours and trip groupings
  - `CommuteIdentifier.java` - Identifies commute/education trips
  - `FlexibilityCalculator.java` - Computes departure/arrival flexibility
  - `BudgetToConstraintsCalculator.java` - Converts utility budgets to physical constraints
  - `RequestSampler.java` - Samples requests for testing/debugging
- Depends on: MATSim routing, scoring, population APIs
- Used by: Algorithm layer

**Algorithm Layer:**
- Purpose: ExMAS ride generation algorithm implementation
- Location: `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/`
- Contains:
  - `ExMasAlgorithmModule.java` - Guice module for algorithm components
  - `engine/ExMasEngine.java` - Main algorithm orchestrator
  - `engine/RidePostProcessor.java` - Post-processing (Shapley, predecessors)
  - `generation/SingleRideGenerator.java` - Generates degree-1 rides
  - `generation/PairGenerator.java` - Generates FIFO/LIFO pairs
  - `extension/RideExtender.java` - Extends rides to higher degrees
  - `graph/ShareabilityGraph.java` - Adjacency structure for ride extension
  - `network/MatsimNetworkCache.java` - Time-binned network routing cache
  - `validation/BudgetValidator.java` - Validates rides against budgets
  - `domain/` - Domain objects (Ride, RideKind, TravelSegment)
  - `util/` - Utilities (ProgressBar, StringUtils, TripScoringUtils)
- Depends on: MATSim network, routing APIs
- Used by: Demand Processing layer (via ExMasEngine)

**I/O Layer:**
- Purpose: Write output files for downstream optimization
- Location: `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/io/`
- Contains:
  - `ExMasCsvWriter.java` - Writes requests.csv and rides.csv
  - `ConnectionCacheWriter.java` - Writes connection_cache.csv
  - `PersonAttributesWriter.java` - Writes person_attributes.csv
- Depends on: Domain objects
- Used by: DemandExtractionListener

**Run Layer:**
- Purpose: Example runner applications
- Location: `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/run/`
- Contains: `RunKelheimDemandExtraction.java` - Kelheim scenario runner
- Depends on: All other layers

## Data Flow

**Main Pipeline (executed in DemandExtractionListener.notifyShutdown):**

```
1. MATSim Simulation completes (iteration 0 or last iteration)
   |
2. STEP 0: Configure DRT for budget calculation
   |-- DrtBudgetConfigurator sets optimal service parameters
   |
3. STEP 1: Cache mode alternatives
   |-- ModeRoutingCache.cacheModes(population)
   |-- Routes all baseline modes (car, pt, walk, bike) + DRT
   |-- Calculates scores using MATSim ScoringFunction
   |-- Stores: Person -> Trip -> Mode -> ModeAttributes
   |
4. STEP 2: Identify trip chains
   |-- ChainIdentifier.identifyChains(population)
   |-- Finds closed subtours using private vehicles
   |-- Groups trips into subtour groups or independent trips
   |
5. STEP 3: Build DRT requests with budgets
   |-- DrtRequestFactory.buildRequests(population)
   |-- Filters by age, commute status, DRT availability
   |-- Calculates budget = DRT_score - best_baseline_score
   |-- Creates DrtRequest objects with temporal constraints
   |
6. STEP 4: Run ExMAS ride generation algorithm
   |-- ExMasEngine.run(requests)
   |   |-- Phase 1: SingleRideGenerator -> degree-1 rides
   |   |-- Phase 2: PairGenerator -> FIFO/LIFO pairs (degree-2)
   |   |-- Phase 3: Build ShareabilityGraph from pairs
   |   |-- Phase 4: RideExtender -> degree-3+ rides (iterative)
   |-- RidePostProcessor.process(rides)
   |   |-- Compute maxCosts per passenger
   |   |-- Compute Shapley values (distance contribution)
   |   |-- Compute predecessors/successors (ride sequencing)
   |
7. STEP 5: Write output files
   |-- ExMasCsvWriter.writeRequests(requests)
   |-- ExMasCsvWriter.writeRides(rides)
   |-- PersonAttributesWriter.writePersonAttributes(population, requests)
   |-- ExMasCsvWriter.writeModeCache(modeCache)
   |-- ConnectionCacheWriter.writeConnectionCache(rides) [if calcPredecessors=true]
```

**Request Building Flow:**

```
Person Plan
  |
  v
TripStructureUtils.getTrips(plan) -> List<Trip>
  |
  v
For each Trip:
  |-- ModeRoutingCache provides ModeAttributes (travelTime, distance, score)
  |-- ChainIdentifier provides groupId (subtour or independent)
  |-- CommuteIdentifier provides isCommute, isEducation flags
  |-- BudgetValidator.calculateBudget(request) -> utility budget
  |-- FlexibilityCalculator -> earliestDeparture, latestArrival
  |
  v
DrtRequest object with:
  - index, personId, groupId, tripIndex
  - originLinkId, destinationLinkId, coordinates
  - requestTime, earliestDeparture, latestArrival
  - directTravelTime, directDistance, maxDetourFactor
  - budget, bestModeScore, bestMode
  - isCommute, isEducation
  - ptAccessibility metrics
```

**Ride Generation Flow:**

```
DrtRequest[] requests
  |
  v
SingleRideGenerator.generate(requests)
  |-- Creates Ride for each request with degree=1
  |-- Validates budget (trivially passes for single rides)
  |
  v
PairGenerator.generatePairs(requests)
  |-- TimeFilter groups requests by time window
  |-- For each (i,j) pair within horizon:
  |     |-- Get network segments (origin-to-origin, etc.)
  |     |-- tryFifoCandidate: pickup i, j; dropoff i, j
  |     |-- tryLifoCandidate: pickup i, j; dropoff j, i
  |     |-- optimizeDelays to find feasible departure
  |-- BudgetValidator.validateAndPopulateBudgets(ride)
  |-- Returns validated pairs with remainingBudgets
  |
  v
ShareabilityGraph.build(pairRides)
  |-- Edges: (reqI, reqJ) for each valid pair
  |-- Adjacency index for neighbor lookups
  |
  v
RideExtender.extendRides(currentDegreeRides) [iterative]
  |-- For each base ride:
  |     |-- findCommonNeighborsSorted() -> candidates
  |     |-- tryExtend(base, newRequest, pairRides)
  |     |-- Determine insertion position (FIFO/LIFO/MIXED)
  |     |-- Calculate connection segments
  |     |-- Check max travel time constraints
  |     |-- optimizeDelays for feasibility
  |-- applyHeuristicPruning() to control combinatorial growth
  |-- BudgetValidator validates extended rides
  |
  v
List<Ride> allRides (degree 1 to maxPoolingDegree)
```

**State Management:**

- `ModeRoutingCache`: ConcurrentHashMap - Person -> Trip -> Mode -> Attributes
- `ChainIdentifier.tripToGroupId`: ConcurrentHashMap - Person -> Trip -> GroupId
- `MatsimNetworkCache.cache`: ConcurrentHashMap - (origin, dest, timeBin) -> TravelSegment
- All caches use parallel population during initialization, then read-only access

## Key Abstractions

**DrtRequest:**
- Purpose: Unified request representing a potential DRT trip
- Location: `demand/DrtRequest.java`
- Pattern: Immutable value object with Builder pattern
- Fields: identity (index, personId), location (linkIds, coordinates), temporal (requestTime, windows), budget (budget, bestModeScore)

**Ride:**
- Purpose: Represents a shared ride with multiple passengers
- Location: `algorithm/domain/Ride.java`
- Pattern: Immutable value object with Builder pattern
- Key arrays (all indexed by passenger position):
  - `requests[]`: DrtRequest references
  - `originsOrderedRequests[]`, `destinationsOrderedRequests[]`: pickup/dropoff sequences
  - `passengerTravelTimes[]`, `passengerDistances[]`: per-passenger metrics
  - `delays[]`, `detours[]`: temporal adjustments
  - `remainingBudgets[]`, `maxCosts[]`: budget metrics
  - `shapleyValues[]`: distance contribution
  - `predecessors[]`, `successors[]`: ride sequencing

**TravelSegment:**
- Purpose: Network routing result between two links
- Location: `algorithm/domain/TravelSegment.java`
- Fields: travelTime, distance, networkUtility
- Special: `TravelSegment.unreachable()` for routing failures

**ModeAttributes:**
- Purpose: Cached mode routing results
- Location: `demand/ModeAttributes.java`
- Fields: travelTime, distance, cost, score

**ShareabilityGraph:**
- Purpose: Lightweight adjacency structure for ride extension
- Location: `algorithm/graph/ShareabilityGraph.java`
- Pattern: Edge-list with pre-sorted adjacency for deterministic iteration
- Key method: `findCommonNeighborsSorted(requests)` - intersection for extension candidates

## Entry Points

**DemandExtractionModule (Primary):**
- Location: `demand/DemandExtractionModule.java`
- Triggers: Called when user adds `new DemandExtractionModule()` to Controler
- Responsibilities:
  - Validates ExMasConfigGroup is present
  - Binds all demand extraction services as eager singletons
  - Installs ExMasAlgorithmModule
  - Registers DemandExtractionListener for shutdown event

**DemandExtractionListener (Pipeline Orchestrator):**
- Location: `demand/DemandExtractionListener.java`
- Triggers: MATSim Controler shutdown event (after all iterations)
- Responsibilities: Executes the full demand extraction pipeline

**RunKelheimDemandExtraction (Example Runner):**
- Location: `run/RunKelheimDemandExtraction.java`
- Triggers: Main method execution
- Responsibilities: Loads Kelheim scenario, configures ExMAS, runs controller

## Error Handling

**Strategy:** Fail-fast with logging, graceful degradation for routing failures

**Patterns:**

1. **Config Validation:**
   ```java
   if (!config.getModules().containsKey(ExMasConfigGroup.GROUP_NAME)) {
       throw new RuntimeException("ExMasConfigGroup is required but not found...");
   }
   ```

2. **Routing Failures:**
   ```java
   TravelSegment seg = networkCache.getSegment(origin, dest, time);
   if (!seg.isReachable()) return null; // Skip this candidate
   ```

3. **Budget Validation:**
   ```java
   Ride validated = budgetValidator.validateAndPopulateBudgets(ride);
   if (validated == null) continue; // Budget violated, skip ride
   ```

4. **NaN/Infinity Guards:**
   ```java
   if (!Double.isFinite(actualTravelTime) || !Double.isFinite(actualDistance)) {
       log.warn("DRT routing failed for request...");
       return Double.NEGATIVE_INFINITY;
   }
   ```

## Cross-Cutting Concerns

**Logging:**
- Framework: Log4j2 (MATSim standard)
- Pattern: Progress logging at 10% intervals for long-running operations
- Statistics: Network routing success/failure rates, ride counts by degree

**Validation:**
- `DemandExtractionConfigValidator.prepareConfigForDemandExtraction(config)` - must be called BEFORE Controler creation
- `BudgetValidator` - ensures rides respect passenger budget constraints

**Parallelism:**
- ModeRoutingCache uses parallel streams for population-level caching
- PairGenerator uses parallel IntStream for pair generation
- RideExtender uses parallel IntStream for extension generation
- Determinism ensured by post-processing sorts and ConcurrentHashMap

**Authentication:** Not applicable (no external services)

---

*Architecture analysis: 2026-01-20*
