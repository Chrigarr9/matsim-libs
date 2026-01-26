# Phase 8: HyperPool Integration

**Status**: In Progress
**Full Plan**: [../docs/HYPERPOOL_INTEGRATION_PLAN.md](../docs/HYPERPOOL_INTEGRATION_PLAN.md)

## Overview

Integrate the HyperPool algorithm for stop-based ride-pooling, enabling passengers to walk to shared pickup/dropoff points.

## Progress Summary

| Sub-Phase | Status | Tasks Done | Notes |
|-----------|--------|------------|-------|
| 8.1 Configuration & Domain Model | ✅ Complete | 8/8 | All classes created, config extended |
| 8.2 Stop Finding Algorithm | ✅ Complete | 8/8 | All stop finders implemented |
| 8.3 Stop-to-Stop Ride Generation | ⏳ Pending | 0/7 | |
| 8.4 Budget Validation Extensions | ⏳ Pending | 0/5 | |
| 8.5 Engine Integration (Stage 1) | ⏳ Pending | 0/4 | |
| 8.6 Output Extensions | ⏳ Pending | 0/6 | |
| 8.7 Hyper-Pooling (Stage 2) | ⏳ Pending | 0/38 | |

**Overall Progress**: 16/77 tasks (21%)

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

## References

- [HyperPool Paper](https://arxiv.org/abs/2206.05940)
- [Full Planning Document](../docs/HYPERPOOL_INTEGRATION_PLAN.md)
