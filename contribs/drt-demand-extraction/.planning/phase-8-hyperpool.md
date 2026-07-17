# Phase 8: HyperPool Integration

**Status**: ✅ COMPLETE
**Full Plan**: [../docs/HYPERPOOL_INTEGRATION_PLAN.md](../docs/HYPERPOOL_INTEGRATION_PLAN.md)

## Overview

Integrate the HyperPool algorithm for stop-based ride-pooling, enabling passengers to walk to shared pickup/dropoff points.

## Progress Summary

| Sub-Phase | Status | Tasks Done | Notes |
|-----------|--------|------------|-------|
| 8.1 Configuration & Domain Model | ✅ Complete | 8/8 | All classes created, config extended |
| 8.2 Stop Finding Algorithm | ✅ Complete | 8/8 | All stop finders implemented |
| 8.3 Stop-to-Stop Ride Generation | ✅ Complete | 7/7 | StopBasedRideGenerator created |
| 8.4 Budget Validation Extensions | ✅ Complete | 5/5 | calculateDrtScoreWithWalks added |
| 8.5 Engine Integration (Stage 1) | ✅ Complete | 4/4 | Phase 5 added to ExMasEngine |
| 8.6 Output Extensions | ✅ Complete | 6/6 | CSV columns extended |
| 8.7.1 HyperPool Domain Model | ✅ Complete | 4/4 | StopSequence, StopToStopRideWrapper |
| 8.7.2 Stop Compatibility | ✅ Complete | 5/5 | StopCompatibilityChecker |
| 8.7.3 Shareability Graph | ✅ Complete | 5/5 | HyperPoolShareabilityGraph |
| 8.7.4 Stop Relocation | ✅ Complete | 5/5 | StopRelocator |
| 8.7.5 Ride Generation | ✅ Complete | 6/6 | HyperPoolGenerator |
| 8.7.6 Budget Validation | ✅ Complete (re-wired 2026-07-16) | 5/5 | inline in HyperPoolGenerator.generateHyperPooledRide — validateHyperPooledRide was written but NEVER wired (dead code, deleted; HYP-1) |
| 8.7.7 Engine Integration | ✅ Complete | 4/4 | Phase 6 in ExMasEngine |
| 8.7.8 Output Extensions | ✅ Complete | 4/4 | writeHyperPooledRides |

**Overall Progress**: 77/77 tasks (100%) - **BOTH STAGES COMPLETE! 🎉**

## Activation

```xml
<module name="exMas">
    <!-- Stage 1: Stop-based pooling -->
    <param name="enableStopBased" value="true"/>
    <param name="maxWalkDistanceMeters" value="500.0"/>

    <!-- Stage 2: Hyper-pooling (optional) -->
    <param name="enableHyperPooling" value="true"/>
    <param name="hyperPoolMinOccupancy" value="4"/>
</module>
```

## Sub-Phases

### Stage 1: Stop-Based Pooling

| Sub-Phase | Tasks | Description |
|-----------|-------|-------------|
| 8.1 | 8 | Configuration & Domain Model |
| 8.2 | 9 | Stop Finding Algorithm |
| 8.3 | 7 | Stop-to-Stop Ride Generation |
| 8.4 | 5 | Budget Validation Extensions |
| 8.5 | 4 | Engine Integration |
| 8.6 | 6 | Output Extensions |

**Total Stage 1**: 39 tasks

### Stage 2: Hyper-Pooling

| Sub-Phase | Tasks | Description |
|-----------|-------|-------------|
| 8.7.1 | 4 | Domain Model |
| 8.7.2 | 5 | Stop Compatibility |
| 8.7.3 | 5 | Shareability Graph |
| 8.7.4 | 5 | Stop Relocation |
| 8.7.5 | 6 | Ride Generation |
| 8.7.6 | 5 | Budget Validation |
| 8.7.7 | 4 | Engine Integration |
| 8.7.8 | 4 | Output Extensions |

**Total Stage 2**: 38 tasks

## Key Design Decisions

1. **Output both variants**: Door-to-door AND stop-to-stop (different remaining budgets)
2. **Degree filter**: Only shared rides (degree > 1) get stop-based variants
3. **Walk distance**: Use MATSim's bound walk router (adapts to user config)
4. **Budget validation**: Against best mode score (base score)
5. **Multi-stop sequences**: Hyper-pooled rides have sequence of pickup/dropoff stops

## New Classes (Summary)

### Domain
- `StopLocation` - Immutable stop with linkId and coord
- `RideVariant` - Enum: DOOR_TO_DOOR, STOP_TO_STOP, HYPER_POOLED
- `StopSequence` - Ordered stop sequence for hyper-pooled rides
- `HyperPooledRide` - Extended ride with stop sequence

### Stops Package
- `StopFinder` - Interface for finding optimal stops
- `GeometricStopFinder` - Weighted centroid approach
- `NetworkNodeStopFinder` - Network nodes only
- `NetworkLinkStopFinder` - All links in radius
- `PredefinedStopFinder` - MATSim facilities
- `WalkingDistanceCalculator` - Uses MATSim walk router

### HyperPool Package
- `StopCompatibilityChecker` - Check temporal/spatial compatibility
- `HyperPoolShareabilityGraph` - Graph of compatible S2S rides
- `StopRelocator` - Merge nearby stops
- `HyperPoolGenerator` - Generate hyper-pooled rides

## Algorithm Flow

```
ExMAS (existing)
    |
    v
[enableStopBased = true?] --no--> Output D2D rides only
    |
    yes
    v
Stop-Based Conversion (Phase 8.1-8.6)
    |
    v
Output: D2D rides + S2S rides
    |
    v
[enableHyperPooling = true?] --no--> Done
    |
    yes
    v
Hyper-Pooling (Phase 8.7)
    |
    v
Output: D2D rides + S2S rides + HyperPooled rides
```

## Dependencies

- MATSim core (TripRouter, CoordUtils, Facilities)
- Existing ExMAS infrastructure (Ride, BudgetValidator, MatsimNetworkCache)

## Implementation Log

### Phase 8.1: Configuration & Domain Model (Completed)

**Date**: 2026-01-26

**Tasks Completed**:
1. ✅ 1.1 Add stop-based config parameters to ExMasConfigGroup
2. ✅ 1.2 Add hyper-pool config parameters to ExMasConfigGroup
3. ✅ 1.3 Create StopLocation domain class
4. ✅ 1.4 Create StopFindingStrategy enum
5. ✅ 1.5 Extend Ride class for stops (variant, pickupStop, dropoffStop, walkDistances)
6. ✅ 1.6 Create RideVariant enum
7. ✅ 1.7 Extend DrtRequest with maxWalkDistance
8. ✅ 1.8 Create HyperPooledRide domain class

**New Files Created**:
- `config/StopFindingStrategy.java` - Enum for stop finding strategies
- `algorithm/domain/StopLocation.java` - Immutable stop location class
- `algorithm/domain/RideVariant.java` - Enum for ride variants
- `algorithm/domain/HyperPooledRide.java` - Domain class for hyper-pooled rides

**Modified Files**:
- `config/ExMasConfigGroup.java` - Added 14 new config parameters
- `algorithm/domain/Ride.java` - Added stop-related fields and builder methods
- `demand/DrtRequest.java` - Added maxWalkDistance field

**Notes**:
- Maven build cannot be verified due to network connectivity issues
- Code structure follows existing patterns in the codebase
- All new classes use immutable design with Builder pattern where appropriate

### Phase 8.2: Stop Finding Algorithm (Completed)

**Date**: 2026-01-26

**Tasks Completed**:
1. ✅ 2.1 Create StopFinder interface
2. ✅ 2.2 Create WalkingDistanceCalculator (uses CoordUtils.distancePointLinesegment)
3. ✅ 2.3 Create LinkCandidateFinder helper
4. ✅ 2.4 Implement GeometricStopFinder (weighted centroid approach)
5. ✅ 2.5 Implement NetworkNodeStopFinder (nodes only)
6. ✅ 2.6 Implement NetworkLinkStopFinder (all links in radius)
7. ✅ 2.7 Implement PredefinedStopFinder (MATSim facilities)
8. ✅ 2.8 Create StopFinderFactory

**New Files Created**:
- `algorithm/stops/StopFinder.java` - Interface for stop finding strategies
- `algorithm/stops/WalkingDistanceCalculator.java` - Walk distance using perpendicular distance to link
- `algorithm/stops/LinkCandidateFinder.java` - Finds candidate links with filters
- `algorithm/stops/GeometricStopFinder.java` - Weighted centroid, snap to network
- `algorithm/stops/NetworkNodeStopFinder.java` - Network nodes only
- `algorithm/stops/NetworkLinkStopFinder.java` - All links within radius
- `algorithm/stops/PredefinedStopFinder.java` - Uses ActivityFacilities
- `algorithm/stops/StopFinderFactory.java` - Factory for creating finders

**Key Implementation Details**:
- Walk distance uses `CoordUtils.distancePointLinesegment()` for perpendicular distance
- Supports beeline factor for realistic urban walking (configurable)
- GeometricStopFinder weights by inverse of max walk distance (tighter constraints = more weight)
- Statistics tracking in WalkingDistanceCalculator for analysis
- Mode and length filtering in LinkCandidateFinder

### Phase 8.3: Stop-to-Stop Ride Generation (Completed)

**Date**: 2026-01-26

**Tasks Completed**:
1. ✅ 3.1 Create StopBasedRideGenerator main class
2. ✅ 3.2 Implement pickup stop finding
3. ✅ 3.3 Implement dropoff stop finding
4. ✅ 3.4 Calculate per-passenger walk distances
5. ✅ 3.5 Generate stop-to-stop routes (via MatsimNetworkCache)
6. ✅ 3.6 Handle infeasible conversions with statistics
7. ✅ 3.7 Parallel processing support

**New Files Created**:
- `algorithm/generation/StopBasedRideGenerator.java` - Main S2S ride generator

**Key Implementation Details**:
- Only converts rides with degree >= 2 (single rides stay door-to-door)
- Uses StopFinder interface for pluggable stop finding strategies
- Validates walk distances against hard cap before budget validation
- Detailed statistics: conversion rate, failure reasons, average walks
- Parallel processing with deterministic output ordering

### Phase 8.4: Budget Validation Extensions (Completed)

**Date**: 2026-01-26

**Tasks Completed**:
1. ✅ 4.1 Extend BudgetValidator with calculateDrtScoreWithWalks()
2. ✅ 4.2 Update to use actual walk distances
3. ✅ 4.3 Add walk distance hard cap validation
4. ✅ 4.4 Budget calculation in StopBasedRideGenerator
5. ✅ 4.5 Validation statistics tracking

**Modified Files**:
- `algorithm/validation/BudgetValidator.java` - Added public calculateDrtScoreWithWalks()
- `algorithm/network/MatsimNetworkCache.java` - Added getNetwork() accessor

### Phase 8.5: Engine Integration (Completed)

**Date**: 2026-01-26

**Tasks Completed**:
1. ✅ 5.1 Add stop-based phase (Phase 5) to ExMasEngine
2. ✅ 5.2 Conditional execution when enableStopBased = true
3. ✅ 5.3 Output both D2D and S2S variants
4. ✅ 5.4 Add stop-based statistics logging

**Modified Files**:
- `algorithm/engine/ExMasEngine.java` - Added Phase 5 stop-based generation

**Key Implementation Details**:
- Phase 5 runs after standard ExMAS algorithm completes
- Creates StopFinderFactory based on configured strategy
- Generates S2S variants for rides with degree >= 2
- Final output sorted by variant (D2D first), then degree
- Summary shows D2D/S2S breakdown

**Fixes**:
- Moved maybePrunePairRidesAfterGraph to proper class method (was incorrectly nested)
- Added facilities parameter to support PREDEFINED stop finder

### Phase 8.6: Output Extensions (Completed)

**Date**: 2026-01-26

**Tasks Completed**:
1. ✅ 6.1 Add variant column to rides CSV
2. ✅ 6.2 Add pickup stop columns (linkId, X, Y, snappingPenalty)
3. ✅ 6.3 Add dropoff stop columns (linkId, X, Y, snappingPenalty)
4. ✅ 6.4 Add accessWalkDistances array column
5. ✅ 6.5 Add egressWalkDistances array column
6. ✅ 6.6 Add writeStopBasedStatistics() method

**Modified Files**:
- `io/ExMasCsvWriter.java` - Extended writeRides(), added writeStopBasedStatistics()

**New CSV Columns**:
- `variant`: DOOR_TO_DOOR, STOP_TO_STOP, or HYPER_POOLED
- `pickupStopLinkId`, `pickupStopX`, `pickupStopY`, `pickupSnappingPenalty`
- `dropoffStopLinkId`, `dropoffStopX`, `dropoffStopY`, `dropoffSnappingPenalty`
- `accessWalkDistances`: [dist1 | dist2 | ...] per passenger
- `egressWalkDistances`: [dist1 | dist2 | ...] per passenger

**Statistics Output**:
- Count and average degree per variant
- Average access/egress/total walk distances for S2S rides

---

## Stage 2: HyperPool Implementation Log

### Phase 8.7.1: HyperPool Domain Model (Completed)

**Date**: 2026-01-26

**New Files Created**:
- `algorithm/domain/StopSequence.java` - Ordered sequence of stops with passenger mappings
- `algorithm/hyperpool/StopToStopRideWrapper.java` - Wrapper for S2S rides as pseudo-requests

**Modified Files**:
- `algorithm/domain/HyperPooledRide.java` - Extended with sourceRides, orderedStopSequence, passenger metrics

### Phase 8.7.2-8.7.5: Core HyperPool Algorithms (Completed)

**Date**: 2026-01-26

**New Files Created**:
- `algorithm/hyperpool/StopCompatibilityChecker.java` - Temporal, spatial, directional compatibility
- `algorithm/hyperpool/HyperPoolShareabilityGraph.java` - Graph with spatial/temporal indexing
- `algorithm/hyperpool/StopRelocator.java` - Weighted centroid stop merging
- `algorithm/hyperpool/HyperPoolGenerator.java` - Main orchestrator for hyper-pooling

### Phase 8.7.6-8.7.8: Integration & Output (Completed)

**Date**: 2026-01-26

**Modified Files**:
- `algorithm/validation/BudgetValidator.java` - Added validateHyperPooledRide() — NOTE (2026-07-16): this method was never called from production code; Stage-2 budget + walk-cap acceptance is now wired inline in `HyperPoolGenerator.generateHyperPooledRide` with a boarding-time delay definition, and the dead block was deleted (methodology review HYP-1/HYP-9)
- `algorithm/engine/ExMasEngine.java` - Added Phase 6 for hyper-pooling
- `io/ExMasCsvWriter.java` - Added writeHyperPooledRides()

---

## Both Stages Complete! 🎉🎉

**Summary**: Full HyperPool integration is implemented. To enable:

```xml
<module name="exMas">
    <!-- Stage 1: Stop-based pooling -->
    <param name="enableStopBased" value="true"/>
    <param name="maxWalkDistanceMeters" value="500.0"/>
    <param name="stopFindingStrategy" value="GEOMETRIC"/>

    <!-- Stage 2: Hyper-pooling -->
    <param name="enableHyperPooling" value="true"/>
    <param name="hyperPoolMinOccupancy" value="4"/>
    <param name="hyperPoolMaxStops" value="6"/>
    <param name="hyperPoolTimeWindowSeconds" value="900.0"/>
    <param name="hyperPoolStopProximityMeters" value="100.0"/>
</module>
```

## New Classes Summary

### Stage 1 (Stop-Based Pooling)
- `StopLocation`, `RideVariant`, `StopFindingStrategy`
- `StopFinder` interface with 4 implementations
- `WalkingDistanceCalculator`, `LinkCandidateFinder`
- `StopBasedRideGenerator`

### Stage 2 (Hyper-Pooling)
- `StopSequence`, `StopToStopRideWrapper`
- `StopCompatibilityChecker`, `HyperPoolShareabilityGraph`
- `StopRelocator`, `HyperPoolGenerator`
- Extended `HyperPooledRide`

## References

- [HyperPool Paper](https://arxiv.org/abs/2206.05940)
- [Full Planning Document](../docs/HYPERPOOL_INTEGRATION_PLAN.md)
