# ExMas Demand Extraction - Implementation Summary

## Overview
ExMas (Exclusive Mode Assignment Service) extracts DRT demand from MATSim population plans by calculating utility budgets representing agents' willingness to switch from baseline modes to DRT.

## Architecture

### Core Components

1. **ExMasConfigGroup** (`config/ExMasConfigGroup.java`)
   - Configuration parameters for demand extraction
   - Key settings:
     - `drtMode`: DRT service name (default: "drt")
     - `baseModes`: Baseline modes to compare against DRT (default: car, pt, bike, walk)
     - `drtRoutingMode`: Fallback routing mode for DRT when no dedicated routing module exists (default: "car")
     - `privateVehicleModes`: Modes requiring vehicles for subtour constraints (default: car, bike)
     - DRT service quality params: `minDrtCostPerKm`, `minMaxDetourFactor`, `minMaxWalkingDistance`, `minMaxWaitingTime`

2. **ModeRoutingCache** (`demand/ModeRoutingCache.java`)
   - Routes and scores all available modes for each trip
   - Filters modes by person attributes (license, car availability) following MATSim conventions
   - Detects DRT routing module or falls back to configured routing mode
   - Stores best baseline mode per trip (excluding DRT)

3. **ChainIdentifier** (`demand/ChainIdentifier.java`)
   - Identifies hierarchical subtour structures in daily plans
   - Implements combinatorial logic to detect private vehicle usage:
     - Evaluates ALL feasible mode combinations for subtours
     - Private vehicles must be available at subtour start and used consistently
     - Compares private vehicle combinations vs non-private alternatives
   - Groups trips that must be served together (subtours using private vehicles)

4. **BudgetCalculator** (`demand/BudgetCalculator.java`)
   - Calculates trip-wise utility budgets: `score(DRT) - score(best_baseline)`
   - Positive budget: DRT is better (agent willing to pay for DRT)
   - Negative budget: DRT is worse (agent needs compensation)
   - Links trips via groupId for subtours using private vehicles

5. **DemandExtractionListener** (`demand/DemandExtractionListener.java`)
   - Orchestrates demand extraction workflow at last iteration
   - Configures DRT to ideal service quality for budget calculation
   - Writes `drt_requests.csv` with budget and grouping information

## HyperPool: Two-Stage Ride Optimization

ExMas includes an optional **HyperPool** algorithm that converts traditional door-to-door shared rides into more efficient transit-like services through two stages:

### Stage 1: Stop-Based Pooling
- Converts door-to-door rides into stop-to-stop rides where passengers walk to shared pickup/dropoff points
- Uses `GeometricStopFinder` to identify optimal stop locations reachable by all passengers
- Passengers walk short distances (configurable, e.g., 500m) to minimize vehicle routing distance
- Reduces vehicle kilometers traveled while maintaining service quality

### Stage 2: Hyper-Pooling (Configurable Spatial Filtering)
- Bundles stop-to-stop rides into high-occupancy transit-like services
- **Two matching modes** (configurable via `hyperPoolEnableSpatialFilter`):
  1. **Fast mode (default)**: Pre-filters by proximity (pickup OR dropoff within threshold)
     - 3-15x faster, finds 85-95% of valid patterns
     - Explicit spatial checks with OR logic
  2. **Comprehensive mode**: Evaluates all pairs like [original HyperPool](https://github.com/RafalKucharskiPK/ExMAS/tree/master/ExMAS/hyperpool)
     - Utility-based matching without proximity filtering
     - Finds 100% of valid patterns, but slower
- Enables asymmetric bundling patterns:
  - "Shuttle from downtown" - common pickup, various dropoffs (one-to-many)
  - "Shuttle to airport" - various pickups, common dropoff (many-to-one)
  - Hub-and-spoke service patterns
- Generates `HyperPooledRide` objects with optimized stop sequences
- Trade-off: Fast mode may miss long-distance directional bundles (e.g., opposite city ends, same direction)

### Configuration Modes

HyperPool can be configured to match the original research implementation or use production optimizations:

**Research Mode** (default): Matches original ExMAS/HyperPool research implementation
- No stop relocation - works with actual stop locations
- Unlimited stops per ride - no artificial constraints
- No directional filtering - utility-based matching only
- No spatial filtering - evaluates all ride pairs
- Optimized sequencing - distance-minimizing routes

**Production Mode**: Optional optimizations for faster, more practical results
- Stop relocation - merge nearby stops to reduce complexity
- Max stops constraint - cap ride sizes (e.g., 6 stops)
- Directional filtering - reject opposite-direction bundles
- Spatial filtering - pre-filter incompatible pairs
- Sequencing - optimized (distance-minimizing) in both modes

Choose based on your use case:
- Research/validation: Use defaults (matches original paper)
- Production/large-scale: Enable optimizations (3-15x faster)

### Configuration Example

```java
ExMasConfigGroup exMasConfig = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);

// Stage 1: Enable stop-based pooling
exMasConfig.setEnableStopBased(true);
exMasConfig.setMaxWalkDistanceMeters(500.0);  // Max walk to stops
exMasConfig.setStopSearchRadiusMeters(300.0); // Search radius for finding stops

// Stage 2: Enable hyper-pooling
exMasConfig.setEnableHyperPooling(true);
exMasConfig.setHyperPoolMaxStopRelocationMeters(200.0); // Max stop relocation
exMasConfig.setHyperPoolMinOccupancy(4); // Min passengers per hyper-pooled ride
exMasConfig.setHyperPoolTimeWindowSeconds(900.0); // 15 min time window
exMasConfig.setHyperPoolStopProximityMeters(100.0); // Stop proximity threshold

// Configuration mode (choose based on your needs)

// Research Mode (matches original ExMAS/HyperPool) - DEFAULT
exMasConfig.setHyperPoolEnableStopRelocation(false);  // No stop merging
exMasConfig.setHyperPoolMaxStops(-1);                 // Unlimited stops
exMasConfig.setHyperPoolEnableDirectionalFilter(false); // No directional check
exMasConfig.setHyperPoolEnableSpatialFilter(false);   // Utility-based matching

// Production Mode (faster, more constrained) - OPTIONAL
// exMasConfig.setHyperPoolEnableStopRelocation(true);   // Merge nearby stops
// exMasConfig.setHyperPoolMaxStops(6);                  // Cap at 6 stops
// exMasConfig.setHyperPoolEnableDirectionalFilter(true); // Filter opposite directions
// exMasConfig.setHyperPoolEnableSpatialFilter(true);    // Proximity pre-filtering
```

### Performance Tips

For large populations or testing scenarios, enable aggressive pruning to prevent memory issues:

```java
exMasConfig.setMaxPoolingDegree(5); // Reduce from default 10
exMasConfig.setPruningKeepTopFractionPerRequestSet(0.3); // Keep top 30%
exMasConfig.setPruningMaxRidesToKeepPerRequestSet(20); // Hard cap
exMasConfig.setPruningDistanceSavingsLogScale(0.15); // Enable distance pruning
```

See `CHANGELOG.md` for detailed configuration guidelines and common pitfalls.

## Key Concepts

### Mode Availability Filtering
- Uses MATSim conventions: `PersonUtils.getLicense()` and `PersonUtils.getCarAvail()`
- Car requires: license != "no" AND carAvail != "never"
- Bike, PT, Walk, DRT: always available (if configured)
- Only routes and scores modes person can actually use

### Subtour Vehicle Constraints
- Closed subtours (return to origin) create dependencies
- If best mode combination uses private vehicle → all trips linked
- Combinatorial evaluation:
  - Private: all trips same vehicle (e.g., car-car-car)
  - Non-private: best per-trip mix (e.g., pt-walk-pt)
  - Compare total utilities to determine best
- Nested subtours inherit vehicle availability from outer subtours

### DRT Routing Mode Detection
- First checks if dedicated DRT routing module registered in TripRouter
- Falls back to `drtRoutingMode` config (typically "car") if not found
- Enables flexible DRT routing without requiring full DRT simulation setup

### Budget Calculation Strategy
- **Always use best alternative** (highest utility baseline mode), not current plan mode
- DRT configured to ideal service quality (min cost, min detour, min waiting)
- Budget represents maximum degradation tolerable before baseline becomes better
- Optimization can "spend" budget by increasing fare, detour, waiting time, etc.

## Implementation Decisions

### Why Trip-Wise Budgets?
Previous subtour-sum approach was abandoned because:
1. Combinatorial explosion for nested subtours
2. Difficult to allocate shared budget to individual trips
3. Trip-wise budgets with linking provides same constraints with simpler logic

### Why Combinatorial Subtour Detection?
Simple per-trip best mode check fails because:
- Example: H-W-H subtour
  - Per-trip best: car(H→W), pt(W→H) → car stranded at work (infeasible)
  - Must evaluate: car(H→W)+car(W→H) vs pt(H→W)+pt(W→H)
- Evaluates ALL feasible combinations, not just per-trip greedy choice

### Why Filter Mode Availability Early?
- Avoids routing infeasible modes (e.g., car for person without license)
- Reduces computation: only score accessible alternatives
- Consistent with discrete_mode_choice contrib patterns

## Testing

### E2E Test (`ExMasDemandExtractionE2ETest.java`)
- Simple network (hexagonal loop structure)
- Test population:
  1. Person with car: H-W-H subtour (2 trips, 1 group)
  2. Person without car: H-W-H independent trips (2 trips, 2 groups)
  3. Person with car + nested subtour: H-W-L-W-H (4 trips, hierarchical grouping)
- Validates:
  - DRT requests generated for all persons
  - Budgets are valid numbers
  - Grouping reflects subtour structure
  - Output format correct

### Running Tests
```bash
cd matsim-libs/contribs/exmas
mvn test
```

## Usage Example

```java
Config config = ConfigUtils.createConfig();

// Configure ExMas
ExMasConfigGroup exMasConfig = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
exMasConfig.setDrtMode("drt");
exMasConfig.setBaseModes(Set.of("car", "pt", "bike", "walk"));
exMasConfig.setPrivateVehicleModes(Set.of("car", "bike"));
exMasConfig.setMinDrtCostPerKm(0.0); // Best pricing
exMasConfig.setMinMaxDetourFactor(1.0); // Direct routes

// Configure DRT (if using full DRT simulation)
MultiModeDrtConfigGroup drtConfig = ConfigUtils.addOrGetModule(config, MultiModeDrtConfigGroup.class);
// ... add DRT config ...

// Run with demand extraction
Controler controler = new Controler(config);
controler.addOverridingModule(new DemandExtractionModule());
controler.run();

// Output: output/drt_requests.csv
```

## Output Format

`drt_requests.csv`:
```
personId,groupId,tripIndex,budget,departureTime,originX,originY,destinationX,destinationY
person1,person1_subtour_0,0,2.5,28800.00,0.00,0.00,1500.00,866.00
person1,person1_subtour_0,1,1.8,61200.00,1500.00,866.00,0.00,0.00
person2,person2_trip_0,0,-0.5,25200.00,0.00,1732.00,1000.00,0.00
person2,person2_trip_1,1,-0.3,57600.00,1000.00,0.00,0.00,1732.00
```

Fields:
- `personId`: Person identifier
- `groupId`: Unique group identifier (trips with same ID must be served together)
- `tripIndex`: Trip position in daily plan
- `budget`: Utility difference (DRT - baseline), can be positive/negative
- `departureTime`: Planned departure time (seconds after midnight)
- `originX, originY`: Origin coordinates
- `destinationX, destinationY`: Destination coordinates

## Known Limitations

1. **Opportunity Cost**: Not fully accounted for in scoring
   - Assumes activity schedules are flexible
   - TODO: Add opportunity cost for time-constrained activities

2. **Bike Availability**: No standard MATSim attribute
   - Currently assumes always available if configured
   - TODO: Add person attribute if needed

3. **Cognitive Complexity**: Some methods exceed 15 (SonarQube warning)
   - `ModeRoutingCache.cacheModes()`: 54 (acceptable for main workflow)
   - `ChainIdentifier.subtourUsesPrivateVehicle()`: 25 (combinatorial logic inherently complex)

4. **DRT Fare**: Not directly controlled via constraints
   - Set via scoring parameters (mode-specific cost per km)
   - Budget calculation uses `minDrtCostPerKm` config

## Future Enhancements

1. **Multi-Agent Optimization**: Use budgets to optimize fleet size, fares, service quality
2. **Time-Dependent Budgets**: Account for schedule flexibility
3. **Joint Trip Constraints**: Household members traveling together
4. **Mode Chain Constraints**: Some mode sequences prohibited (e.g., bike→pt→bike)
5. **Activity Location Choice**: Integrate with destination choice models

## References

- MATSim Book: [matsim.org/the-book](https://matsim.org/the-book)
- DRT contrib: [github.com/matsim-org/matsim-libs/tree/master/contribs/drt](https://github.com/matsim-org/matsim-libs/tree/master/contribs/drt)
- discrete_mode_choice: [github.com/matsim-org/matsim-libs/tree/master/contribs/discrete_mode_choice](https://github.com/matsim-org/matsim-libs/tree/master/contribs/discrete_mode_choice)
