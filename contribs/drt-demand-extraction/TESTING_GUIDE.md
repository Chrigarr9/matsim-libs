# ExMas E2E Test - Quick Start Guide

## Prerequisites

- Java 17 or higher
- Maven 3.6 or higher
- MATSim libs repository cloned

## Running the Test

### Option 1: Maven Command Line

```bash
# Navigate to exmas directory
cd matsim-libs/contribs/exmas

# Run test
mvn clean test
```

### Option 2: IDE (IntelliJ IDEA / Eclipse)

1. Open `matsim-libs` project
2. Navigate to `contribs/exmas/src/test/java/org/matsim/contrib/exmas`
3. Right-click on `ExMasDemandExtractionE2ETest.java`
4. Select "Run Test" or "Debug Test"

### Expected Output

**Console output**:
```
[INFO] Running org.matsim.contrib.exmas.ExMasDemandExtractionE2ETest
✓ E2E test passed: 8 DRT requests generated
  Persons: [person1, person2, person3]
  Groups: 5
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```

**Generated files** (in temp directory):
```
output/
  drt_requests.csv          # Main output: DRT demand with budgets
  output_config.xml         # MATSim config used
  output_plans.xml.gz       # Final population plans
  output_events.xml.gz      # Events from simulation
  output_network.xml.gz     # Network used
```

## What the Test Does

1. **Loads dvrp-grid Network**
   - Uses MATSim's example grid network (11x11 grid, 200m spacing)
   - Pre-configured with proper link modes and attributes
   - Realistic network structure for DRT testing

2. **Creates Test Population**
   - **Person 1**: Car owner, H-W-H pattern
     - Should create 1 group (2 trips linked by car usage)
   - **Person 2**: No car, H-W-H pattern
     - Should create 2 independent trips
   - **Person 3**: Car owner, H-W-L-W-H nested subtour
     - Should create hierarchical groups (outer H-W-H, inner W-L-W)

3. **Configures ExMas**
   - Base modes: car, pt, walk, bike
   - DRT routing mode: car (fallback)
   - Private vehicle modes: car, bike
   - Ideal DRT service quality for budget calculation

4. **Runs MATSim**
   - 1 iteration to generate network travel times
   - Triggers demand extraction at last iteration

5. **Validates Output**
   - Checks `drt_requests.csv` exists
   - Verifies format and content
   - Validates budgets are valid numbers
   - Confirms all persons have requests

## Understanding the Output

### Sample `drt_requests.csv`:
```csv
personId,groupId,tripIndex,budget,departureTime,originX,originY,destinationX,destinationY
person1,person1_subtour_0,0,2.50,28800.00,-800.00,-400.00,400.00,400.00
person1,person1_subtour_0,1,1.80,61200.00,400.00,400.00,-800.00,-400.00
person2,person2_trip_0,0,-0.50,25200.00,-600.00,-600.00,600.00,600.00
person2,person2_trip_1,1,-0.30,57600.00,600.00,600.00,-600.00,-600.00
person3,person3_subtour_0,0,3.20,28800.00,0.00,-600.00,400.00,0.00
person3,person3_subtour_1,1,0.80,43200.00,400.00,0.00,200.00,200.00
person3,person3_subtour_1,2,0.70,46800.00,200.00,200.00,400.00,0.00
person3,person3_subtour_0,3,2.90,61200.00,400.00,0.00,0.00,-600.00
```

### Key Points:
- **person1**: Both trips have same `groupId` (subtour_0) → must be served together
- **person2**: Each trip has different `groupId` (trip_0, trip_1) → independent
- **person3**: Outer trips (0,3) share `subtour_0`, inner trips (1,2) share `subtour_1`
- **Budget**: Positive = DRT better than baseline, negative = DRT worse

## Troubleshooting

### Test Fails with "DRT requests file should exist"

**Cause**: Demand extraction not triggered or file write failed

**Solutions**:
1. Check console for MATSim errors
2. Verify last iteration ran (should see iteration 1 logs)
3. Check temp directory has write permissions

### Test Fails with "Should have requests from all 3 persons"

**Cause**: Mode routing failed or no valid baseline modes

**Solutions**:
1. Check person attributes (license, car availability)
2. Verify mode params configured (car, pt, walk, bike, drt)
3. Check network allows car and drt modes on links

### Compilation Errors

**Cause**: Missing dependencies or wrong MATSim version

**Solutions**:
1. Check MATSim version: 2026.0-SNAPSHOT or compatible
2. Verify DRT dependency in `pom.xml`
3. Run `mvn clean install` from `matsim-libs` root
4. Refresh Maven dependencies in IDE

### Import Errors in IDE

**Cause**: Maven dependencies not resolved

**Solutions**:
1. Reimport Maven project
2. Right-click on project → Maven → Reload Project
3. Invalidate caches and restart IDE
4. Check Maven settings point to correct repository

## Customizing the Test

### Use Different Network
Replace the network loading code:
```java
// Instead of dvrp-grid, use equil or your own network
URL scenarioUrl = ExamplesUtils.getTestScenarioURL("equil");
config.network().setInputFile(new URL(scenarioUrl, "network.xml").toString());
```

### Change Population
Edit `createTestPopulation()`:
```java
// Add person with bike
Person person4 = factory.createPerson(Id.createPersonId("person4"));
PersonUtils.setLicense(person4, "no");
PersonUtils.setCarAvail(person4, "never");
// ... add bike trips ...
```

### Change Scoring
Edit `configureMatsim()`:
```java
// Make DRT more attractive
ScoringConfigGroup.ModeParams drtParams = new ScoringConfigGroup.ModeParams("drt");
drtParams.setMarginalUtilityOfTraveling(-2.0); // Less negative = more attractive
drtParams.setMonetaryDistanceRate(-0.0001); // Lower cost
```

### Change ExMas Config
Edit `configureExMas()`:
```java
// Add more baseline modes
exMasConfig.setBaseModes(Set.of("car", "pt", "walk", "bike", "rideshare"));

// Adjust DRT service quality
exMasConfig.setMinMaxDetourFactor(1.2); // Allow 20% detour
exMasConfig.setMinMaxWaitingTime(5.0); // Allow 5 min waiting
```

## Next Steps

1. **Run Full Optimization**: Use generated `drt_requests.csv` as input to DRT fleet optimizer
2. **Analyze Budgets**: Study budget distribution across population
3. **Test Scenarios**: Create variations with different network/population structures
4. **Integration**: Integrate with existing MATSim scenarios
5. **Validation**: Compare with real-world DRT demand data

## Documentation References

- [README.md](README.md) - Full architecture and usage documentation
- [REFACTORING_SUMMARY.md](REFACTORING_SUMMARY.md) - Complete change log
- [MATSim Book](https://matsim.org/the-book) - MATSim framework documentation
- [DRT Contrib](https://github.com/matsim-org/matsim-libs/tree/master/contribs/drt) - DRT module documentation
