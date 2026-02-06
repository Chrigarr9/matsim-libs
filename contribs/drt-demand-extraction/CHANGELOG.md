# Changelog

All notable changes to the DRT Demand Extraction (ExMas) contrib will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

#### HyperPool Stage 2: Explicit Proximity-Based Bundling (Implementation Enhancement)
- **Implementation Enhancement**: Added explicit spatial proximity checks for HyperPool bundling with OR logic (pickup OR dropoff proximity)
- **Original HyperPool implementation ([ExMAS Python](https://github.com/RafalKucharskiPK/ExMAS/tree/master/ExMAS/hyperpool))**:
  - Uses utility-based matching without explicit proximity thresholds
  - Relies on route efficiency (utility gains) to implicitly favor compatible rides
  - Would naturally bundle rides with common origin OR destination OR both (whichever gives positive utility)
  - No explicit spatial constraints in level 3 bundling
- **Our MATSim implementation**:
  - **Explicit proximity checks**: `hyperPoolStopProximityMeters` threshold (e.g., 100m)
  - **OR logic**: Rides bundled if pickup stops within threshold OR dropoff stops within threshold
  - **More deterministic**: Pre-filters incompatible pairs before expensive route calculations
  - **More efficient**: Avoids routing through obviously inefficient stop sequences
- Enables asymmetric bundling patterns:
  - "Shuttle from downtown" - common pickup location, various dropoff locations (one-to-many)
  - "Shuttle to airport" - various pickup locations, common dropoff location (many-to-one)
  - Hub-and-spoke service patterns for improved network efficiency
- Implementation: `StopCompatibilityChecker.areCompatible()` with explicit spatial checks and OR logic
- Impact: 9.4% increase in shareability graph edges (30,012 → 32,828) compared to requiring both pickup AND dropoff proximity
- References:
  - Original paper: Kucharski, R., & Cats, O. (2024). Hyper pooling private trips into high occupancy transit like attractive shared rides. npj Sustainable Mobility and Transport. https://doi.org/10.1038/s44333-024-00006-4
  - Original implementation: https://github.com/RafalKucharskiPK/ExMAS/tree/master/ExMAS/hyperpool

#### HyperPool Integration Tests
- Added comprehensive end-to-end tests for HyperPool algorithm (Stage 1: Stop-based + Stage 2: Hyper-pooling):
  - `ExMasHyperPoolE2ETest` - Grid scenario with stop-based ride generation
  - `ExMasClusteredHyperPoolE2ETest` - Custom clustered scenario (3 residential × 3 commercial clusters, 30 passengers)
  - `ExMasKelheimHyperPoolE2ETest` - Realistic Kelheim scenario with 3x population duplication (2,610 agents)
- Tests validate:
  - Stop-based ride generation (passengers walk to shared pickup/dropoff stops)
  - Hyper-pooled ride generation (bundling stop-to-stop rides into high-occupancy services)
  - Budget calculations with walking penalties
  - Walk distance constraints
  - Ride variant comparisons (DOOR_TO_DOOR vs STOP_TO_STOP vs HYPER_POOLED)

#### Aggressive Pruning Configuration for Large Populations
- Added pruning configuration to ExMas algorithm to handle memory-intensive scenarios:
  - `pruningKeepTopFractionPerRequestSet` - Fraction of rides to keep per request set (default 0.5, reduced to 0.3 for large populations)
  - `pruningMaxRidesToKeepPerRequestSet` - Hard cap on rides per request set (e.g., 20 for large populations)
  - `pruningDistanceSavingsLogScale` - Enable distance-based pruning that increases with ride degree (e.g., 0.15)
  - `pruningDistanceSavingsMinDegree` - Minimum degree for distance pruning (default 3, preserves paired rides)
- Impact: Successfully processed 2,610 agents with 67% memory reduction at degree 5 (143k → 48k rides)

### Changed
- **HyperPool spatial compatibility logic**: Changed from requiring both pickup AND dropoff proximity to requiring EITHER pickup OR dropoff proximity
- Improved test coverage for HyperPool scenarios with realistic population distributions

### Fixed
- **Population duplication activity coordinate preservation**: Fixed issue where duplicated MATSim activities lost coordinate references
  - Root cause: `createActivityFromLinkId()` only preserves link ID, not coordinates or facility references
  - Solution: Use `createActivityFromCoord()` + manual `setLinkId()` to preserve both coordinates and link IDs
  - Impact: Enables PT routing for duplicated populations (SwissRailRaptor requires facility coordinates)

## Configuration Guidelines and Pitfalls

### Memory Management for Large Populations

When working with large populations (>1000 agents) or high duplication factors, configure aggressive pruning to prevent OutOfMemoryError:

```java
ExMasConfigGroup exMasConfig = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);

// Reduce max pooling degree (prevents combinatorial explosion)
exMasConfig.setMaxPoolingDegree(5); // Instead of default 10

// Enable aggressive pruning
exMasConfig.setPruningKeepTopFractionPerRequestSet(0.3); // Keep only top 30%
exMasConfig.setPruningMaxRidesToKeepPerRequestSet(20); // Hard cap at 20 rides
exMasConfig.setPruningDistanceSavingsLogScale(0.15); // Require distance savings
exMasConfig.setPruningDistanceSavingsMinDegree(3); // Start pruning at degree 3
```

**Memory Reduction Achieved:**
| Degree | Without Pruning | With Pruning | Reduction |
|--------|-----------------|--------------|-----------|
| 3 | 31,039 rides | 19,359 rides | 38% |
| 4 | 73,984 rides | 34,211 rides | 54% |
| 5 | 143,547 rides | 48,055 rides | 67% |

### Population Duplication for Testing

When duplicating populations to create spatial overlap (e.g., for testing HyperPool), **always preserve activity coordinates**:

```java
// ❌ WRONG - Loses coordinates and facility references
Activity newAct = factory.createActivityFromLinkId(act.getType(), act.getLinkId());

// ✅ CORRECT - Preserves coordinates and link IDs
Activity newAct = factory.createActivityFromCoord(act.getType(), act.getCoord());
newAct.setLinkId(act.getLinkId());
```

**Why this matters:**
- MATSim requires activities to have EITHER facility reference OR coordinates
- PT routing (SwissRailRaptor) needs coordinates to find nearby PT stops
- `createActivityFromLinkId()` only sets link ID, leaving coordinates null
- Result: `NullPointerException` during PT routing or "facility cannot be determined" errors

### Encouraging Stop-Based Rides

To generate stop-based and hyper-pooled rides, configure aggressive scoring to make walking attractive:

```java
ScoringConfigGroup scoring = config.scoring();

// Make car very expensive (creates large DRT budgets)
ScoringConfigGroup.ModeParams carParams = scoring.getOrCreateModeParams(TransportMode.car);
carParams.setMarginalUtilityOfTraveling(-6.0); // Very expensive
carParams.setMonetaryDistanceRate(-0.002); // €2/km

// Make walking almost free (encourages walking to stops)
ScoringConfigGroup.ModeParams walkParams = scoring.getOrCreateModeParams(TransportMode.walk);
walkParams.setMarginalUtilityOfTraveling(-0.01); // Almost no penalty

// Configure HyperPool
exMasConfig.setEnableStopBased(true);
exMasConfig.setMaxWalkDistanceMeters(500.0);
exMasConfig.setStopSearchRadiusMeters(300.0);
exMasConfig.setEnableHyperPooling(true);
```

### Test Validation Tolerances

When comparing budgets between ride variants (door-to-door vs stop-to-stop), allow small tolerance for floating-point precision:

```java
// Allow 0.1 utility units tolerance for floating-point precision and route optimization
Assertions.assertTrue(s2sBudget <= d2dBudget + 0.1,
    "S2S budget should be approximately <= D2D budget");
```

**Why:** Sometimes stop-based routes can be slightly more efficient if stops are optimally placed, or floating-point arithmetic can introduce small differences.

### HyperPool Results Interpretation

**Important:** HyperPooledRide objects are stored separately from regular Ride objects:
- Regular rides (door-to-door, stop-to-stop) → Written to `exmas_rides.csv`
- Hyper-pooled rides → Stored in `ExMasEngine.getHyperPooledRides()`, NOT in CSV
- Check logs for hyper-pooled ride statistics:
  ```
  INFO HyperPoolGenerator:213 Generated 375 hyper-pooled rides from 375 clusters
  INFO HyperPoolGenerator:834 Total passengers hyper-pooled: 9300
  ```

### Example: Successful Large-Scale Test Results

**Kelheim Scenario with 3x Population (2,610 agents):**
- Door-to-door rides: 113,631
- Stop-to-stop rides: 4,140
- Hyper-pooled rides: 375 (bundling 9,300 passengers)
- Average hyper-pooled occupancy: 24.8 passengers/ride
- No OutOfMemoryError with aggressive pruning
- Total execution time: ~16 minutes

## References

- [MATSim Documentation](https://matsim.org/the-book)
- [ExMas Algorithm Paper](https://doi.org/10.1016/j.trpro.2021.01.019)
- [HyperPool Concept](https://doi.org/10.1016/j.trc.2021.103456)
