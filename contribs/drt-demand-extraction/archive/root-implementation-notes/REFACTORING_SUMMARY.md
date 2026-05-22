# ExMas Refactoring Summary

## Changes Implemented

### 1. Configuration Refactoring (ExMasConfigGroup.java)

**Issue**: `baseModes` was Map<String,String> but all entries except DRT had identical key-value pairs

**Solution**: 
- Changed `baseModes` from `Map<String,String>` to `Set<String>`
- Added `drtRoutingMode` (String) - routing mode for DRT when no dedicated module exists
- Added `privateVehicleModes` (Set<String>) - modes requiring vehicles for subtour constraints

**Rationale**:
- Map was redundant: `{car→car, pt→pt, bike→bike, walk→walk, drt→car}`
- Routing mode detection should check TripRouter, not be hardcoded
- Private vehicle modes should be configurable (not hardcoded to car+bike)

### 2. DRT Routing Mode Detection (ModeRoutingCache.java)

**Issue**: DRT routing hardcoded to `TransportMode.car` without checking if DRT routing module exists

**Solution**:
```java
String routingMode;
if (mode.equals(exMasConfig.getDrtMode())) {
    // Check if dedicated DRT routing module exists
    if (tripRouter.getRoutingModule(mode) != null) {
        routingMode = mode; // Use DRT-specific routing
    } else {
        routingMode = exMasConfig.getDrtRoutingMode(); // Fallback to config
    }
} else {
    routingMode = mode; // Standard modes route as themselves
}
```

**Rationale**:
- DRT might have custom routing module registered in TripRouter
- Should check dynamically, not assume always routes as car
- Config provides fallback when no dedicated module exists

### 3. Subtour Vehicle Detection - Combinatorial Logic (ChainIdentifier.java)

**Issue**: Logic checked best mode per trip, not feasible mode combinations for entire subtour

**Problem Example**:
- Home-Work-Home subtour
- Per-trip best: car(H→W) [best], pt(W→H) [best for return]
- Result: Car left at work (infeasible!)
- Correct: Compare car(H→W)+car(W→H) vs pt(H→W)+pt(W→H)

**Solution**: Implemented combinatorial evaluation
```java
// Evaluate each private vehicle mode (all trips use same vehicle)
for (String privateMode : privateVehicleModes) {
    double combinedUtility = 0.0;
    for (int tripIdx : subtourTripIndices) {
        combinedUtility += tripModes.get(privateMode).score;
    }
    bestPrivateVehicleUtility = Math.max(bestPrivateVehicleUtility, combinedUtility);
}

// Evaluate best non-private combination (per-trip best non-private)
for (int tripIdx : subtourTripIndices) {
    double bestTripUtility = max over non-private modes;
    bestNonPrivateUtility += bestTripUtility;
}

// Uses private vehicle if private combination better
return bestPrivateVehicleUtility > bestNonPrivateUtility;
```

**Rationale**:
- Vehicle constraints require all-or-nothing: if car used, must use for all trips
- Must evaluate utility of complete mode chains, not individual trips
- Nested subtours: inner subtour has vehicle available if outer uses it

### 4. Configuration Injection (ChainIdentifier.java)

**Added Dependencies**:
```java
@Inject
public ChainIdentifier(ExMasConfigGroup exMasConfig, ModeRoutingCache modeRoutingCache) {
    this.exMasConfig = exMasConfig;
    this.modeRoutingCache = modeRoutingCache;
}
```

**Rationale**:
- Needs `privateVehicleModes` config to know which modes are vehicles
- Needs `ModeRoutingCache` to access trip-mode utilities for combinatorial evaluation
- Removes hardcoded assumptions (car, bike)

### 5. Removed Redundant Person Attribute Checks (ChainIdentifier.java)

**Removed**:
```java
// OLD: Redundant checks
if (TransportMode.car.equals(bestMode)) {
    boolean hasLicense = !"no".equals(PersonUtils.getLicense(person));
    boolean carAvailable = !"never".equals(PersonUtils.getCarAvail(person));
    if (hasLicense && carAvailable) return true;
}
```

**Rationale**:
- Mode availability already filtered in `ModeRoutingCache.filterAvailableModes()`
- If mode was routed and scored, person can use it
- Checking again is redundant and violates DRY principle

### 6. End-to-End Test (ExMasDemandExtractionE2ETest.java)

**Created comprehensive integration test**:
- Simple network (hexagonal loop structure)
- 3 test persons:
  1. Car owner with H-W-H subtour
  2. No car with independent H-W-H trips  
  3. Car owner with nested H-W-L-W-H subtour
- Validates:
  - DRT requests generated for all persons
  - Budget values are valid numbers
  - Grouping reflects subtour structure
  - Output CSV format correct

**Test Infrastructure**:
- Creates network programmatically
- Creates test population with varied attributes
- Configures MATSim, DRT, and ExMas
- Runs 1 iteration to generate travel time data
- Validates output file content

### 7. Dependencies (pom.xml)

**Added**:
```xml
<dependency>
    <groupId>org.matsim.contrib</groupId>
    <artifactId>drt</artifactId>
    <version>${project.parent.version}</version>
</dependency>
```

**Rationale**: ExMas depends on DRT for configuration and testing

### 8. Documentation (README.md)

**Created comprehensive documentation**:
- Architecture overview
- Component descriptions
- Key concepts (mode availability, subtour constraints, budget calculation)
- Implementation decisions with justifications
- Usage example
- Output format specification
- Known limitations and future enhancements

## Code Quality Improvements

1. **Explanatory Comments**: Added detailed comments explaining:
   - Why certain decisions were made
   - What assumptions are being made
   - What limitations exist
   - How complex logic works

2. **Type Safety**: Changed from `Map<String,String>` to more type-safe structures

3. **Configurability**: Replaced hardcoded values with configuration parameters

4. **DRY Principle**: Removed redundant person attribute checks

5. **Testability**: Added comprehensive E2E test

## Files Modified

1. `src/main/java/org/matsim/contrib/exmas/config/ExMasConfigGroup.java`
   - Refactored baseModes to Set
   - Added drtRoutingMode and privateVehicleModes
   - Updated getComments() documentation

2. `src/main/java/org/matsim/contrib/exmas/demand/ModeRoutingCache.java`
   - Implemented DRT routing mode detection
   - Updated to use Set-based baseModes

3. `src/main/java/org/matsim/contrib/exmas/demand/ChainIdentifier.java`
   - Added config and cache dependencies
   - Implemented combinatorial subtour vehicle detection
   - Removed redundant person attribute checks
   - Changed imports (removed PersonUtils, added Set)

4. `src/test/java/org/matsim/contrib/exmas/ExMasDemandExtractionE2ETest.java`
   - **NEW FILE**: Comprehensive integration test

5. `pom.xml`
   - Added DRT dependency

6. `README.md`
   - **NEW FILE**: Complete documentation

## Known Warnings

**SonarQube Cognitive Complexity**:
- `ModeRoutingCache.cacheModes()`: 54 (acceptable - main workflow method)
- `ChainIdentifier.subtourUsesPrivateVehicle()`: 25 (acceptable - combinatorial logic)

**TODO Comments**:
- Opportunity cost calculation (future enhancement)
- Bike availability attribute (MATSim has no standard)

## Testing

**Manual Test**:
```bash
cd matsim-libs/contribs/exmas
mvn clean test
```

**Expected Output**:
- Test creates network and population
- Runs 1 MATSim iteration
- Generates `drt_requests.csv`
- Validates content and structure
- Prints: "✓ E2E test passed: X DRT requests generated"

## Migration Guide

**For existing ExMas users**:

1. **Config changes required**:
   ```java
   // OLD
   Map<String, String> baseModes = new HashMap<>();
   baseModes.put("car", "car");
   baseModes.put("pt", "pt");
   baseModes.put("drt", "car");
   exMasConfig.setBaseModes(baseModes);
   
   // NEW
   exMasConfig.setBaseModes(Set.of("car", "pt", "bike", "walk"));
   exMasConfig.setDrtRoutingMode("car"); // Fallback for DRT routing
   exMasConfig.setPrivateVehicleModes(Set.of("car", "bike")); // For subtour detection
   ```

2. **Behavior changes**:
   - Subtour detection now evaluates mode combinations (more accurate)
   - DRT routing mode auto-detected from TripRouter
   - Mode availability filtering happens earlier (in routing cache)

3. **Output unchanged**:
   - `drt_requests.csv` format identical
   - Budget calculation strategy unchanged (trip-wise with linking)

## Rationale Summary

All changes address issues identified in code review comments:
1. **C: cant this be a set?** → Changed baseModes to Set
2. **C: is the TransportMode.car correct here?** → Added DRT routing mode detection
3. **C: more complicated...need to check utility of all trips** → Implemented combinatorial logic
4. **C: this is checked already** → Removed redundant person attribute checks
5. **C: should be configured in config** → Added privateVehicleModes configuration

Changes maintain backward compatibility in output format while improving:
- Code clarity
- Configurability
- Correctness (especially subtour vehicle detection)
- Testability
