# Code Review Fixes Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Fix all issues identified in the drt-demand-extraction code review: 3 bugs, 1 DRY violation, 2 cleanup items, 1 warning addition. Defer 3 large refactors (Guice DI, record conversion, config split) as out-of-scope.

**Architecture:** All fixes are localized to existing files. The DRY fix extracts shared opportunity-cost activity resolution into `DrtTripScorer`. The logging cleanup extracts a helper method in `ExMasEngine`. No new classes or files needed.

**Tech Stack:** Java 17, MATSim 2026.0-SNAPSHOT, Maven, JUnit 5

---

### Task 1: Fix Python format string in DrtRequestFactory

**Files:**
- Modify: `src/main/java/org/matsim/contrib/demand_extraction/demand/DrtRequestFactory.java:298`

**Step 1: Fix the format string**

Line 298 uses Python `{:.1f}` syntax inside a SLF4J log message. The ratio value is already formatted via `String.format` two lines below, but the literal `{:.1f}` in the log template is wrong.

Change:
```java
+ "Beeline={}m but routed distance={}m (ratio={:.1f}x). "
```
to:
```java
+ "Beeline={}m but routed distance={}m (ratio={}x). "
```

The `drtAttrs.distance() / beelineDistance` arg on line 302 will already be passed as-is via `{}`. To get 1-decimal formatting, wrap it with `String.format("%.1f", ...)`:

Change line 302 from:
```java
drtAttrs.distance() / beelineDistance, originLinkId, destinationLinkId);
```
to:
```java
String.format("%.1f", drtAttrs.distance() / beelineDistance), originLinkId, destinationLinkId);
```

**Step 2: Build to verify compilation**

Run: `cd matsim-libs/contribs/drt-demand-extraction && mvn compile -q`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add src/main/java/org/matsim/contrib/demand_extraction/demand/DrtRequestFactory.java
git commit -m "fix: replace Python format string {:.1f} with Java String.format in DrtRequestFactory"
```

---

### Task 2: Fix config comment mismatch in ExMasConfigGroup

**Files:**
- Modify: `src/main/java/org/matsim/contrib/demand_extraction/config/ExMasConfigGroup.java:132`

**Step 1: Fix the comment**

Change:
```java
private int networkTimeBinSize = 60 * 60; // Network cache time bin size in seconds (15 minutes)
```
to:
```java
private int networkTimeBinSize = 60 * 60; // Network cache time bin size in seconds (1 hour)
```

**Step 2: Build to verify**

Run: `cd matsim-libs/contribs/drt-demand-extraction && mvn compile -q`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add src/main/java/org/matsim/contrib/demand_extraction/config/ExMasConfigGroup.java
git commit -m "fix: correct comment — networkTimeBinSize is 1 hour, not 15 minutes"
```

---

### Task 3: Remove dead StopRelocator object in ExMasEngine

**Files:**
- Modify: `src/main/java/org/matsim/contrib/demand_extraction/algorithm/engine/ExMasEngine.java:361`

**Step 1: Remove the unused variable**

Line 361 creates `StopRelocator externalRelocator` that is never used. The anonymous `HyperPoolGenerator.StopRelocator` implementation below it uses Euclidean distance directly. Remove the dead line.

Delete line 361:
```java
StopRelocator externalRelocator = new StopRelocator(matsimNetwork, linkCandidateFinder, exMasConfig);
```

**Step 2: Check if StopRelocator import is still needed**

Check if `StopRelocator` is used elsewhere in the file. It should NOT be (only this one usage). If it's the only usage, also remove the import:
```java
import org.matsim.contrib.demand_extraction.algorithm.hyperpool.StopRelocator;
```

**Step 3: Build to verify**

Run: `cd matsim-libs/contribs/drt-demand-extraction && mvn compile -q`
Expected: BUILD SUCCESS

**Step 4: Commit**

```bash
git add src/main/java/org/matsim/contrib/demand_extraction/algorithm/engine/ExMasEngine.java
git commit -m "fix: remove dead StopRelocator object that was never used in ExMasEngine"
```

---

### Task 4: Extract shared opportunity-cost activity resolution (DRY)

**Files:**
- Modify: `src/main/java/org/matsim/contrib/demand_extraction/scoring/DrtTripScorer.java`
- Modify: `src/main/java/org/matsim/contrib/demand_extraction/algorithm/validation/BudgetValidator.java`
- Modify: `src/main/java/org/matsim/contrib/demand_extraction/demand/BudgetToConstraintsCalculator.java`

**Context:** Both `BudgetValidator.calculateDrtScore()` (lines 238-271) and `BudgetToConstraintsCalculator.scoreDrtTrip()` (lines 270-306) contain identical ~20-line blocks that:
1. Look up the person from population
2. Check if LOG opportunity cost is configured
3. Get trips from person's plan
4. Extract origin/dest activities and compute durations
5. Create fallback activities if null
6. Call `DrtTripScorer.score()`

Extract this into a convenience overload in `DrtTripScorer`.

**Step 1: Add convenience method to DrtTripScorer**

Add a new static method that encapsulates the activity resolution + scoring:

```java
/**
 * Score a DRT trip, resolving origin/destination activities from the person's plan
 * when LOG opportunity cost is configured.
 *
 * <p>Convenience overload that eliminates duplicate activity resolution code
 * in BudgetValidator and BudgetToConstraintsCalculator.
 */
public static double scoreWithActivityResolution(
        Person person,
        DrtRequest request,
        DemandExtractionScoringAdapter adapter,
        ScoringParametersForPerson scoringParametersForPerson,
        String drtMode,
        OpportunityCostModel opportunityCostModel,
        double travelTime,
        double distance,
        double accessWalkDist,
        double egressWalkDist,
        double delay,
        double walkSpeed) {

    Activity originActivity = null;
    Activity destActivity = null;
    double originDuration = 0.0;
    double destDuration = 0.0;

    if (opportunityCostModel == OpportunityCostModel.LOG) {
        List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(person.getSelectedPlan());
        if (request.tripIndex >= 0 && request.tripIndex < trips.size()) {
            TripStructureUtils.Trip trip = trips.get(request.tripIndex);
            originActivity = trip.getOriginActivity();
            destActivity = trip.getDestinationActivity();
            double[] actDurations = OpportunityCostCalculator.computeActivityDurations(person.getSelectedPlan());
            if (request.tripIndex < actDurations.length) originDuration = actDurations[request.tripIndex];
            if (request.tripIndex + 1 < actDurations.length) destDuration = actDurations[request.tripIndex + 1];
        }
    }

    if (originActivity == null) {
        originActivity = PopulationUtils.createActivityFromLinkId("unknown", request.originLinkId);
    }
    if (destActivity == null) {
        destActivity = PopulationUtils.createActivityFromLinkId("unknown", request.destinationLinkId);
    }

    return score(person, request, adapter, scoringParametersForPerson,
            drtMode, opportunityCostModel,
            travelTime, distance,
            accessWalkDist, egressWalkDist, delay, walkSpeed,
            originActivity, destActivity, originDuration, destDuration);
}
```

Add necessary imports to DrtTripScorer:
```java
import java.util.List;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.population.PopulationUtils;
```

Note: `PopulationUtils` and `List` are already imported. Only `TripStructureUtils` needs adding.

**Step 2: Simplify BudgetValidator.calculateDrtScore()**

Replace the entire method body (lines 234-271) with:

```java
private double calculateDrtScore(DrtRequest request, double delay,
        double actualTravelTime, double actualDistance,
        double actualWalkDistanceAccess, double actualWalkDistanceEgress) {

    Person person = population.getPersons().get(request.personId);

    return DrtTripScorer.scoreWithActivityResolution(person, request, adapter,
            scoringParametersForPerson, exMasConfig.getDrtMode(),
            exMasConfig.getOpportunityCostModel(),
            actualTravelTime, actualDistance,
            actualWalkDistanceAccess, actualWalkDistanceEgress, delay, walkSpeed);
}
```

Remove now-unused imports from BudgetValidator:
```java
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.contrib.demand_extraction.scoring.OpportunityCostCalculator;
import org.matsim.api.core.v01.population.Activity;
```
(Keep `Activity` if it's used elsewhere in the file — check first. It IS used in `validateHyperPooledRide` indirectly? No — Activity is only used in calculateDrtScore. Remove it.)

Actually, check: `Activity` is NOT used anywhere else in BudgetValidator besides `calculateDrtScore`. The `validateHyperPooledRide` uses `HyperPooledRide` methods. So remove it.

BUT `OpportunityCostModel` is still imported via `ExMasConfigGroup.OpportunityCostModel`. Check if it's used elsewhere — no, it was only used in calculateDrtScore. But `exMasConfig.getOpportunityCostModel()` is still called, so the import of `ExMasConfigGroup` stays (already there). `OpportunityCostModel` itself is no longer needed as a direct import.

**Step 3: Simplify BudgetToConstraintsCalculator.scoreDrtTrip()**

Replace the entire method body (lines 270-306) with:

```java
private double scoreDrtTrip(Person person, DrtRequest request,
        double travelTime, double distance,
        double accessWalkDist, double egressWalkDist,
        double delay) {

    return DrtTripScorer.scoreWithActivityResolution(person, request, adapter,
            scoringParametersForPerson, exMasConfig.getDrtMode(),
            exMasConfig.getOpportunityCostModel(),
            travelTime, distance,
            accessWalkDist, egressWalkDist, delay, walkSpeed);
}
```

Remove now-unused imports from BudgetToConstraintsCalculator:
```java
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.contrib.demand_extraction.scoring.OpportunityCostCalculator;
import org.matsim.api.core.v01.population.Activity;
```
(Keep any that are used elsewhere in the file. Check: `Activity` is only used in scoreDrtTrip — remove. `PopulationUtils` only in scoreDrtTrip — remove. `TripStructureUtils` only in scoreDrtTrip — remove. `OpportunityCostCalculator` only in scoreDrtTrip — remove.)

Check remaining imports: `OpportunityCostModel` is still used in scoreDrtTrip via `exMasConfig.getOpportunityCostModel()`, but `OpportunityCostModel` the class is no longer referenced. The return type is used by `exMasConfig.getOpportunityCostModel()` which returns the enum, but we just pass it to the helper. The import `ExMasConfigGroup.OpportunityCostModel` is no longer needed as an explicit import. Actually wait — it's imported as `import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup.OpportunityCostModel;` — we still reference it? No, we just call `exMasConfig.getOpportunityCostModel()` and pass the result. The type is inferred. So remove the explicit OpportunityCostModel import.

**Step 4: Run tests to verify**

Run: `cd matsim-libs/contribs/drt-demand-extraction && mvn test -q`
Expected: All tests pass (especially E2E tests that exercise both BudgetValidator and BudgetToConstraintsCalculator)

**Step 5: Commit**

```bash
git add src/main/java/org/matsim/contrib/demand_extraction/scoring/DrtTripScorer.java \
        src/main/java/org/matsim/contrib/demand_extraction/algorithm/validation/BudgetValidator.java \
        src/main/java/org/matsim/contrib/demand_extraction/demand/BudgetToConstraintsCalculator.java
git commit -m "refactor: extract shared opportunity-cost activity resolution into DrtTripScorer"
```

---

### Task 5: Remove dead `exMasConfig != null` guards in ExMasEngine

**Files:**
- Modify: `src/main/java/org/matsim/contrib/demand_extraction/algorithm/engine/ExMasEngine.java`

**Step 1: Remove null checks**

`exMasConfig` is always non-null (set in constructor, both constructors require it). Remove the dead guards:

Line 126: Change from:
```java
int algorithmProcessCount = exMasConfig != null ? exMasConfig.getAlgorithmProcessCount() : -1;
```
to:
```java
int algorithmProcessCount = exMasConfig.getAlgorithmProcessCount();
```

Line 200: Change from:
```java
if (exMasConfig != null && exMasConfig.isEnableStopBased()) {
```
to:
```java
if (exMasConfig.isEnableStopBased()) {
```

Line 214: Change from:
```java
if (exMasConfig != null && exMasConfig.isEnableHyperPooling()) {
```
to:
```java
if (exMasConfig.isEnableHyperPooling()) {
```

Line 247: Change from:
```java
if (exMasConfig != null && exMasConfig.isEnableStopBased()) {
```
to:
```java
if (exMasConfig.isEnableStopBased()) {
```

Line 427 (in `maybePrunePairRidesAfterGraph`): Change from:
```java
if (exMasConfig == null || pairRides.isEmpty()) {
```
to:
```java
if (pairRides.isEmpty()) {
```

**Step 2: Build to verify**

Run: `cd matsim-libs/contribs/drt-demand-extraction && mvn compile -q`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add src/main/java/org/matsim/contrib/demand_extraction/algorithm/engine/ExMasEngine.java
git commit -m "cleanup: remove dead exMasConfig null checks — always non-null via constructor"
```

---

### Task 6: Extract completion logging helper in ExMasEngine

**Files:**
- Modify: `src/main/java/org/matsim/contrib/demand_extraction/algorithm/engine/ExMasEngine.java`

**Step 1: Add helper method**

Extract the repeated early-return logging blocks (lines 106-120 and 131-146) into a helper:

```java
private List<Ride> completeEarly(long algorithmStartTime, String reason) {
    long totalElapsed = System.currentTimeMillis() - algorithmStartTime;
    double totalSeconds = totalElapsed / 1000.0;
    log.info("");
    log.info("======================================================================");
    log.info("ExMAS Algorithm Complete ({})", reason);
    log.info("  Total rides: {}", allRides.size());
    log.info("  Total time: {}s", String.format("%.1f", totalSeconds));
    log.info("======================================================================");
    log.info("");
    network.logRoutingStatistics();
    return allRides;
}
```

**Step 2: Replace the two early-exit blocks**

Lines 105-120 become:
```java
if (maxDegree < 2) {
    return completeEarly(algorithmStartTime, "maxDegree < 2, skipping pair generation");
}
```

Lines 131-146 become:
```java
if (maxDegree <= 2) {
    return completeEarly(algorithmStartTime, "maxDegree <= 2");
}
```

**Step 3: Build to verify**

Run: `cd matsim-libs/contribs/drt-demand-extraction && mvn compile -q`
Expected: BUILD SUCCESS

**Step 4: Commit**

```bash
git add src/main/java/org/matsim/contrib/demand_extraction/algorithm/engine/ExMasEngine.java
git commit -m "cleanup: extract completeEarly() helper to deduplicate ExMasEngine early-return logging"
```

---

### Task 7: Add warning for unknown mode in PlanCalcScoreAdapter

**Files:**
- Modify: `src/main/java/org/matsim/contrib/demand_extraction/scoring/PlanCalcScoreAdapter.java:112`

**Step 1: Add logger field (if not present)**

Check if PlanCalcScoreAdapter has a logger. It does NOT currently have one. Add:

```java
private static final Logger log = LogManager.getLogger(PlanCalcScoreAdapter.class);
```

And the import:
```java
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
```

**Step 2: Add warning before returning 0.0**

Change lines 112-114 from:
```java
if (modeParams == null) {
    return 0.0;
}
```
to:
```java
if (modeParams == null) {
    log.warn("No scoring parameters found for mode '{}' — scoring leg as 0.0. "
            + "Check planCalcScore config if this mode should contribute to utility.", mode);
    return 0.0;
}
```

**Step 3: Build to verify**

Run: `cd matsim-libs/contribs/drt-demand-extraction && mvn compile -q`
Expected: BUILD SUCCESS

**Step 4: Commit**

```bash
git add src/main/java/org/matsim/contrib/demand_extraction/scoring/PlanCalcScoreAdapter.java
git commit -m "fix: warn when unknown mode has no scoring parameters instead of silently returning 0"
```

---

### Task 8: Run full test suite

**Step 1: Run all tests**

Run: `cd matsim-libs/contribs/drt-demand-extraction && mvn test`
Expected: All tests pass. Key tests to watch:
- `ExMasDemandExtractionE2ETest` — exercises BudgetValidator + BudgetToConstraintsCalculator
- `ExMasHyperPoolE2ETest` — exercises HyperPool + StopRelocator path
- `ExMasKelheimE2ETest` — exercises PlanCalcScoreAdapter + full pipeline

**Step 2: Fix any failures and recommit**

---

## Deferred Items (Out of Scope)

These were identified in the review but are large refactors better done separately:

1. **Make `ExMasEngine` Guice-injectable** — Would change DemandExtractionListener, ExMasEngine constructor, and all tests. Risk: high coupling change.
2. **Convert `DrtRequest` to record** — Would touch every file that uses DrtRequest fields. Risk: ~50 files.
3. **Split `ExMasConfigGroup` into nested groups** — Would change config loading, all runners, all tests. Risk: config compatibility.
