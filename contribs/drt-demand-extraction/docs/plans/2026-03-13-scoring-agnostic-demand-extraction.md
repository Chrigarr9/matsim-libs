y# Scoring-Aware DRT Demand Extraction — Implementation Plan (v4)

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Status:** Revised 2026-03-18 after iterative constraint discovery insight.

**Repository boundary:** All changes inside `drt-demand-extraction` only.

**Goal:** Make DRT demand extraction use the same trip-utility logic as the configured scoring model. Support standard MATSim, DMC with MATSimTripScoring, and eqasim through adapters. Allow additional scoring schemes via adapter SPI.

**Core insight:** Budget scoring requires exact trip utility (adapter-driven). Constraint conversion (maxDetour, maxWait, maxWalk) uses **iterative binary search** on the adapter's own scoring — no parameter extraction needed. Only `marginalUtilityOfMoney` (for maxCost) must be provided explicitly, because DRT fare is external to the scoring system.

**Tech Stack:** Java 17+, MATSim 2026.0-SNAPSHOT, DMC contrib, Guice, JUnit 5.

---

## 1. Two-Layer Design

### Layer 1: Trip Utility Scoring (adapter-driven)

The adapter scores trips. Budget = drtScore - bestModeScore. Exact, route-based.

### Layer 2: Budget-to-Constraint Conversion (iterative search)

Instead of extracting marginal parameters and computing formulas, **binary search using the adapter's own scoring as a black box:**

```
maxDetour: binary search on DRT travel time until drtScore(degraded) = bestModeScore
maxWait:   binary search on wait time until score penalty exhausts budget
maxWalk:   binary search on access/egress walk distance until score penalty exhausts budget
maxCost:   budget / marginalUtilityOfMoney (the ONE explicit parameter)
```

**Why iterative search is better than parameter extraction:**
- No parameter decomposition needed (the v2/v3 problem of extracting 6+ marginal values disappears)
- Exact for adapters whose utility is fully determined by the trip elements (mode, travel time,
  distance) and activity context (origin, destination, departure time). This covers
  `MATSimTripScoringEstimator` and eqasim's trip estimators.
- Handles non-linear utility functions (eqasim's distance interaction, diminishing returns)
- Person-specific and trip-specific automatically

**Limitation:** The search varies synthetic DRT elements (travel time, distance, walk distance)
while keeping routing structure fixed. For adapters whose utility depends on route structure,
transfer patterns, or hidden state beyond these element attributes, the search is an
approximation, not exact. The plan only claims exactness for the supported adapter set
(PlanCalcScore, DmcMatSim, eqasim).

**Performance:** Route once, vary override elements, re-score. ~12 iterations per constraint × 3 constraints = ~36 scoring calls per request. For 3000 requests = ~108K calls. Scoring pre-routed elements is microseconds (no re-routing). Total: < 1 second for standard MATSim.

---

## 2. Adapter SPI

```java
public interface DemandExtractionScoringAdapter {
    String getName();

    /** Score a candidate trip. Used for budget calculation AND iterative constraint search. */
    TripScoreResult scoreTrip(TripScoreRequest request);

    /**
     * Marginal utility of 1 EUR of monetary cost (utils/EUR).
     * Only needed for maxCost conversion (DRT fare is external to scoring).
     *
     * For standard MATSim/DMC: reads planCalcScore.marginalUtilityOfMoney.
     * For eqasim: returns |betaCost| × interaction(euclidDist).
     * For custom: user provides via ExMasConfigGroup.marginalUtilityOfMoney.
     */
    double getMarginalUtilityOfMoney(Person person, double euclideanDistance_km);

    /**
     * Does this adapter's trip utility already include activity opportunity cost?
     *
     * In standard MATSim, margUtilTraveling is the PURE travel disutility.
     * Opportunity cost (lost activity time = margUtilPerforming) is separate.
     * The caller adds it if configured.
     *
     * In eqasim, betaTravelTime is estimated from survey data and captures the
     * TOTAL disutility of travel (including implicit opportunity cost).
     * The caller MUST NOT add opportunity cost on top — that would double-count.
     *
     * PlanCalcScoreAdapter / DmcMatSimTripAdapter: false (caller may add)
     * EqasimScoringAdapter: true (already included, caller must not add)
     */
    boolean includesOpportunityCost();

    /** Does this adapter support scoring degraded trips for iterative constraint search? */
    boolean supportsIterativeConstraints();

    /** Does margUtilMoney vary by trip distance? (eqasim: yes, standard MATSim: no) */
    boolean supportsDistanceSpecificMoneyUtility();
}
```

### DTOs

```java
public record TripScoreRequest(
    Person person,
    String candidateMode,
    List<? extends PlanElement> routedElements,
    Activity originActivity,
    Activity destinationActivity,
    double departureTime,
    Attributes tripAttributes,
    int tripIndex,
    List<PreviousTripContext> previousTrips  // empty for TRIP_INDEPENDENT, populated for GREEDY_PREFIX
) {}

public record PreviousTripContext(
    int tripIndex,
    String mode,
    double departureTime,
    double travelTime
) {}

public record TripScoreResult(
    double utility,                      // pure trip utility (no daily constants, no opportunity cost)
    boolean waitingDisutilityIncluded,   // adapter already scored waiting?
    String sourceDescription
) {}
```

**Score semantics:** `TripScoreResult.utility` is the adapter's trip utility. The adapter
is responsible for documenting what is included via flags:
- Daily constants: adapters SHOULD exclude them (trip-level extraction, not day-level).
  All built-in adapters exclude them by construction.
- Opportunity cost: **adapter-dependent** — reported via `includesOpportunityCost()`.
- Waiting disutility: **adapter-dependent** — reported via `waitingDisutilityIncluded`.

**The SPI contract does NOT require the adapter to decompose its utility into components.**
It only requires truthful reporting of what is included, so the caller avoids double-counting.

**Opportunity cost** is applied by the caller ONLY when both conditions are met:
1. `exMasConfig.isIncludeOpportunityCost() == true` (user wants it)
2. `adapter.includesOpportunityCost() == false` (adapter doesn't already include it)

```java
double score = adapter.scoreTrip(request).utility();
if (exMasConfig.isIncludeOpportunityCost() && !adapter.includesOpportunityCost()) {
    score -= totalTravelTime * params.marginalUtilityOfPerforming_s;
}
```

| Adapter | `includesOpportunityCost()` | Why |
|---|---|---|
| `PlanCalcScoreAdapter` | `false` | `TripScoringUtils` scores pure leg disutility, no activity term |
| `DmcMatSimTripAdapter` | `false` | `MATSimTripScoringEstimator` same — pure leg scoring |
| `EqasimScoringAdapter` | `true` | eqasim's `betaTravelTime` is estimated from survey data and captures the TOTAL travel time disutility including implicit opportunity cost. Adding `margUtilPerforming` on top would double-count. |

This fixes the current bug (ModeRoutingCache applies opportunity cost but BudgetValidator
doesn't) AND prevents the eqasim model mismatch (planCalcScore's performing utility ≠
eqasim's implicit opportunity cost).

**Tour context:** `previousTrips` is populated when `TourEvaluationMode.GREEDY_PREFIX` is
active — contains the best non-DRT mode for each preceding trip. The field exists so
adapters that need tour context (eqasim with parking, mode chains) can receive it.
For `TRIP_INDEPENDENT`: `emptyList()`.

---

## 3. Concrete Adapters

| Adapter | Scoring | margUtilMoney | Config needed |
|---|---|---|---|
| `PlanCalcScoreAdapter` | `TripScoringUtils.calculateLegScore()` (no ScoringFunction, no daily-constant hack) | `planCalcScore.marginalUtilityOfMoney` | None |
| `DmcMatSimTripAdapter` | DMC `TripEstimator` via routing override | Same planCalcScore (MATSimTripScoring reads same params) | None |
| `EqasimScoringAdapter` | eqasim estimator via routing override | `betaCost × interaction(euclidDist)` from eqasim runtime objects via probe | None — probe MUST succeed or startup fails |
| Any custom adapter | User-provided scoring | `ExMasConfigGroup.marginalUtilityOfMoney` | One param |

### Adapter Resolver

```
1. Explicit config (exmas.scoringAdapter = "planCalcScore" | "dmc" | "eqasim" | custom name)
2. Auto-detect: eqasim bindings present? → eqasim adapter
3. Auto-detect: DMC MATSimTripScoring? → DMC adapter
4. Default: PlanCalcScore adapter
```

Ambiguous auto-detection → fail fast.

---

## 4. Iterative Constraint Search

```java
/**
 * Finds maximum acceptable detour by binary search on the adapter's scoring.
 * Route once, then vary DRT trip elements and re-score until budget exhausted.
 */
public double findMaxDetourTime(DemandExtractionScoringAdapter adapter,
        Person person, DrtRequest request, double bestModeScore) {

    double lo = 0;
    double hi = request.directTravelTime * (exMasConfig.getMaxDetourFactor() - 1.0);
    double speed = request.directDistance / request.directTravelTime;

    while (hi - lo > 1.0) { // 1-second tolerance
        double mid = (lo + hi) / 2.0;
        double detourTime = request.directTravelTime + mid;
        double detourDist = request.directDistance + mid * speed;

        // Construct degraded DRT trip and score via adapter
        List<PlanElement> elements = buildDrtElements(request, detourTime, detourDist,
                exMasConfig.getMinDrtAccessEgressDistance());
        TripScoreResult result = adapter.scoreTrip(buildRequest(person, request, elements));
        double score = result.utility();

        // Add wait time penalty if adapter doesn't include it
        if (!result.waitingDisutilityIncluded()) {
            score += getWaitTimePenalty(person, 0.0);
        }

        // Apply opportunity cost only if adapter doesn't already include it
        if (exMasConfig.isIncludeOpportunityCost() && !adapter.includesOpportunityCost()) {
            double totalTravelTime = extractTravelTime(elements);
            score -= totalTravelTime * params.marginalUtilityOfPerforming_s;
        }

        if (score >= bestModeScore) {
            lo = mid; // can tolerate more detour
        } else {
            hi = mid; // too much detour
        }
    }
    return lo;
}
```

Same pattern for `findMaxWaitTime()` and `findMaxWalkDistance()`. Each binary search:
- Constructs variant DRT trip elements
- Scores via adapter (same code path as budget calculation)
- Finds the boundary where budget = 0

### maxCost

```java
public double computeMaxCost(DemandExtractionScoringAdapter adapter,
        Person person, DrtRequest request, double remainingBudget,
        double travelTime, double distance, double euclideanDistance_km) {

    double margUtilMoney = adapter.getMarginalUtilityOfMoney(person, euclideanDistance_km);
    if (margUtilMoney <= 0) {
        throw new IllegalStateException("marginalUtilityOfMoney <= 0. " +
            "For eqasim: adapter must provide betaCost. " +
            "For custom: set exmas.marginalUtilityOfMoney in config.");
    }

    double baseFare = computeBaseFare(travelTime, distance);
    double additionalAffordable = remainingBudget / margUtilMoney;
    return Math.max(baseFare + additionalAffordable, minFarePerTrip);
}
```

---

## 5. ExMasConfigGroup Changes

```java
private String scoringAdapter = "auto";

public enum TourEvaluationMode { TRIP_INDEPENDENT, GREEDY_PREFIX }
private TourEvaluationMode tourEvaluationMode = TourEvaluationMode.TRIP_INDEPENDENT;

@Parameter
@Comment("marginalUtilityOfMoney (utils/EUR) for maxCost conversion. " +
         "Only needed when planCalcScore has dummy values AND no dedicated adapter. " +
         "Standard MATSim/DMC: auto-detected from planCalcScore. " +
         "eqasim: auto-detected from eqasim adapter. " +
         "Custom: must be set here.")
private Double marginalUtilityOfMoney = null;
```

Three config additions:
- `scoringAdapter`: adapter selection (default: `"auto"`)
- `tourEvaluationMode`: `TRIP_INDEPENDENT` (default) or `GREEDY_PREFIX` (sequential best-mode context)
- `marginalUtilityOfMoney`: override for custom scoring (optional)

---

## 6. What Changes in Existing Code

| Component | Current | New |
|---|---|---|
| ModeRoutingCache | ScoringFunction + daily-constant hack | adapter.scoreTrip() |
| ModeRoutingCache cost field | calculateTripCost() | **Removed** |
| ModeAttributes | (travelTime, distance, cost, score) | (travelTime, distance, score) |
| BudgetValidator | ScoringFunction + daily-constant hack, NO opportunity cost | adapter.scoreTrip() + opportunity cost (bug fix) |
| BudgetToConstraintsCalculator | Formula with 6+ extracted params | **Iterative binary search** (detour/wait/walk) + margUtilMoney (maxCost) |
| RidePostProcessor.maxCost | budgetToMaxCost with extracted params | adapter.getMarginalUtilityOfMoney() |

---

## 7. Task List

### Task 1: Adapter SPI + DTOs
- Create: `DemandExtractionScoringAdapter`, `TripScoreRequest`, `TripScoreResult`
- Minimal, immutable records

### Task 2: Routing Override Infrastructure
- Create: `RoutingOverrideManager` (ThreadLocal), `OverridableRoutingModule`, `OverridableTripRouterProvider`
- `@Named("demandExtraction")` TripRouter — no global override
- Tests for all three

### Task 3: `PlanCalcScoreAdapter`
- Score via `TripScoringUtils.calculateLegScore()` (no ScoringFunction, no daily-constant hack)
- `getMarginalUtilityOfMoney()` reads `planCalcScore.marginalUtilityOfMoney`
- `supportsIterativeConstraints() = true`
- **Regression scope:** Parity with current code AFTER the documented opportunity-cost fix.
  Leg-level scoring (`TripScoringUtils`) matches `ScoringFunction` minus daily constants —
  verified in existing `TripScoringUtils` tests. DRT-specific handling (waiting time,
  `DrtRoute` construction) is preserved in `BudgetValidator`, not in the adapter.
  Outputs that may legitimately shift: budgets for scenarios with `includeOpportunityCost=true`
  (DRT detour time now correctly penalized). Tests document expected deltas.

### Task 4: `DmcMatSimTripAdapter`
- Score via DMC `TripEstimator` with routing override
- Build `DiscreteModeChoiceTrip` from real activities (departure time from origin endTime)
- `getMarginalUtilityOfMoney()` reads same planCalcScore (MATSimTripScoring uses same params)
- Get estimator ONCE per person-thread, not per scoring call
- Tests verify parity with PlanCalcScoreAdapter for MATSimTripScoring estimator

### Task 5: Eqasim Precheck + `EqasimScoringAdapter`

**Precheck (do FIRST — determines if Task 5 is implementable):**
1. Identify eqasim Maven artifact coordinates (likely `org.eqasim:core`)
2. Verify these classes/fields exist in the MATSim 2026.0-SNAPSHOT compatible version:
   - `org.eqasim.core.simulation.mode_choice.parameters.ModeParameters`
   - Field: `public double betaCost_u_MU`
   - Field: `public double lambdaCostEuclideanDistance`
   - Field: `public double referenceEuclideanDistance_km`
3. Write minimal probe test: `Class.forName(...)` → `injector.getInstance(...)` → read field via reflection
4. Confirm this works without compile-time dependency
5. If precheck fails → document blocker, eqasim adapter is blocked

**Adapter implementation (after precheck passes):**
- Score via eqasim estimator (obtained from Guice injector at runtime)
- `getMarginalUtilityOfMoney(person, euclidDist)` returns `|betaCost| × (euclidDist/refDist)^lambda`
  from eqasim's `ModeParameters` (Guice-managed singleton, accessed via reflection probe)
- `EqasimRuntimeProbe`: `Class.forName("org.eqasim.core.simulation.mode_choice.parameters.ModeParameters")`
  → if found, `injector.getInstance(clazz)` → read `betaCost_u_MU` field
- `includesOpportunityCost() = true` (eqasim betaTravelTime captures full travel disutility)
- `supportsDistanceSpecificMoneyUtility() = true`
- **No config fallback for eqasim margUtilMoney.** If the probe detects eqasim but cannot
  access `ModeParameters.betaCost_u_MU`, startup FAILS. The config `marginalUtilityOfMoney`
  override is for custom adapters only, not for eqasim.
- Build as optional-dependency: normal builds work without eqasim on classpath
- Parity tests gated behind Maven profile (`-Peqasim-tests`)

### Task 6: Adapter Resolver + Module Wiring
- `DemandExtractionAdapterResolver`: auto-detect or explicit config
- `ScoringAdapterModule`: bind resolver, adapters, named TripRouter
- MapBinder for adapter registration
- Tests for resolver priority logic

### Task 7: Implement `GREEDY_PREFIX` Tour Context
- In `ModeRoutingCache.cacheModes()`, process trips sequentially within each person
- After scoring all modes for trip N, record the best non-DRT mode as context for trip N+1
- This builds the best non-DRT baseline sequentially: "what would the person do for
  preceding trips without DRT?" — the correct conceptual baseline for budget calculation
- When `TourEvaluationMode.TRIP_INDEPENDENT`: pass `emptyList()` (current behavior, default)
- When `TourEvaluationMode.GREEDY_PREFIX`: pass accumulated best-mode contexts
- Adapters that ignore `previousTrips` (PlanCalcScore, DmcMatSim trip-local) are unaffected
- Adapters that use `previousTrips` (eqasim with parking/mode-chains) get sequential context
- **No change to parallelism model**: trips within a person are already sequential.
- **Future work:** `COMBINATORIAL` mode would evaluate all feasible mode combinations per
  subtour (via ChainIdentifier grouping). Computationally feasible (~125 combos for 3-trip
  subtour) but not implemented in this plan. The DTOs and adapter SPI support it.

```java
// In cacheModes(), per person:
List<PreviousTripContext> previousTrips = new ArrayList<>();

for (int tripIdx = 0; tripIdx < trips.size(); tripIdx++) {
    Trip trip = trips.get(tripIdx);
    String bestMode = null;
    double bestScore = Double.NEGATIVE_INFINITY;
    double bestTravelTime = 0;

    for (String mode : availableModes) {
        TripScoreRequest request = new TripScoreRequest(
            person, mode, tripElements, origin, dest, departureTime,
            tripAttributes, tripIdx, previousTrips);  // ← greedy prefix
        double score = adapter.scoreTrip(request).utility();
        // ... apply opportunity cost, track best non-DRT mode ...
    }

    // Record best non-DRT mode for next trip's context
    if (tourEvaluationMode == GREEDY_PREFIX && bestMode != null) {
        previousTrips.add(new PreviousTripContext(tripIdx, bestMode, departureTime, bestTravelTime));
    }
}
```

### Task 8: Refactor `ModeRoutingCache` Scoring
- Replace `ScoringFunctionFactory` with adapter
- Score all modes via `adapter.scoreTrip()`
- Remove `calculateTripScore()`, `calculateTripCost()`
- Remove `cost` from `ModeAttributes`
- Get adapter/estimator ONCE per person-thread
- Override uses scoring mode name ("Servicimo"), not routing mode name

### Task 9: Refactor `BudgetValidator`
- Replace ScoringFunction with `adapter.scoreTrip()`
- Use routing override for constructed DRT elements
- Check `TripScoreResult.waitingDisutilityIncluded()` before adding wait penalty
- Check `adapter.includesOpportunityCost()` before adding opportunity cost
- Delete daily-constant correction code
- Pass `previousTrips` from the request's trip context (same context as ModeRoutingCache used)
- **BUG FIX:** Add opportunity cost when `isIncludeOpportunityCost() && !adapter.includesOpportunityCost()`.
  Current code applies opportunity cost in ModeRoutingCache but NOT in BudgetValidator.
  Fix: apply consistently in both places. For eqasim adapter (`includesOpportunityCost()=true`),
  opportunity cost is NOT added by the caller — it's already in the adapter's utility.

### Task 10: Refactor `BudgetToConstraintsCalculator`
- Replace formula-based conversion with iterative binary search
- `findMaxDetourTime()`, `findMaxWaitTime()`, `findMaxWalkDistance()` — all use adapter.scoreTrip()
- `computeMaxCost()` — uses adapter.getMarginalUtilityOfMoney()
- Delete all raw `ScoringParameters` reads for marginal rates
- Keep DRT fare params from `DrtConfigGroup` (baseFare, timeFare, distFare — always correct)

### Task 11: Update `DemandExtractionModule` + Config
- Install `ScoringAdapterModule`
- Add `scoringAdapter` and `marginalUtilityOfMoney` to `ExMasConfigGroup`
- Validate: if `margUtilMoney = 0` in planCalcScore and no adapter provides it and no config override → fail fast

### Task 12: Regression Tests
1. **Standard MATSim parity**: budgets identical to current implementation (note: budgets may change slightly due to opportunity cost bug fix — document expected delta)
2. **Opportunity cost consistency**: verify ModeRoutingCache and BudgetValidator both apply opportunity cost when `isIncludeOpportunityCost() && !adapter.includesOpportunityCost()`. Verify a detoured DRT trip gets penalized. Verify eqasim adapter (`includesOpportunityCost()=true`) does NOT get caller-side opportunity cost added.
3. **PlanCalcScore iterative constraints**: maxDetour/maxWait/maxWalk from binary search match formula-based results within 1%
4. **DMC parity**: adapter scores match TripEstimator output
5. **Eqasim parity** (profile-gated): adapter scores match eqasim trip utility
6. **Bavaria margUtilMoney verification**: known values (betaCost=-0.311, carCost=0.2 EUR/km, lambda=-0.258, refDist=4.4km) produce correct distance-specific margUtilMoney
7. **Capability failure**: unsupported adapter → fail fast
8. **Tour context**: verify `GREEDY_PREFIX` accumulates best non-DRT modes correctly. For a person with 3 trips where best non-DRT modes are [car, pt, walk]: trip 0 gets empty prefix, trip 1 gets [car], trip 2 gets [car, pt]. Verify contexts are the demand-extraction best modes, not the agent's plan modes.
9. **Tour context parity**: with PlanCalcScoreAdapter (ignores previousTrips), verify `TRIP_INDEPENDENT` and `GREEDY_PREFIX` produce identical scores (adapter doesn't use the context)
10. **CSV schema**: mode_cache.csv header = `personId,tripIndex,mode,travelTime,distance,score`

### Task 13: Full E2E
```bash
mvn test -Dtest="ExMasDemandExtractionE2ETest,ExMasKelheimE2ETest,ExMasHyperPoolE2ETest,ExMasKelheimHyperPoolE2ETest"
```

---

## 8. What This Plan Claims

1. **Standard MATSim**: regression-equivalent budgets and constraints, after the documented opportunity-cost fix. Leg-level scoring parity is verified; DRT-specific handling preserved in BudgetValidator.
2. **DMC + MATSimTripScoring**: same trip utility logic as replanning.
3. **eqasim**: exact trip-local scoring and exact margUtilMoney through dedicated adapter. Probe must succeed — no config fallback for eqasim parameters. Tour-coupled effects via GREEDY_PREFIX are an approximation, not an exact replication of eqasim's DMC tour estimator.
4. **Custom**: supported via adapter SPI. Iterative constraints work for adapters whose utility is fully determined by trip elements. Only margUtilMoney must be provided.
5. **Tour context**: `GREEDY_PREFIX` builds the best non-DRT baseline sequentially — each trip scored with the preceding trips' best non-DRT modes as context. Enables tour-aware adapters (eqasim with parking, car-chain effects). Future: `COMBINATORIAL` mode for optimal subtour evaluation.
6. **Opportunity cost**: applied consistently in both ModeRoutingCache and BudgetValidator when configured (bug fix from current code).
7. **Iterative constraints**: exact for the supported adapter set (PlanCalcScore, DmcMatSim, eqasim). For adapters with route-structure-dependent utility, constraints are approximate.
8. **Failure**: missing margUtilMoney or unsupported adapter → fail fast, not silent degradation.

## 9. What This Plan Does NOT Claim

- Automatic support for arbitrary TripEstimator implementations without an adapter.
- Generic extraction of margUtilMoney from a black-box scoring response.
- Full counterfactual tour recomputation. `GREEDY_PREFIX` is a forward greedy pass, not an optimal combinatorial mode assignment across the subtour. `COMBINATORIAL` evaluation is designed-for but not implemented in this plan.
- Exact iterative constraints for adapters with route-structure-dependent utility (only exact for element-determined utility models).
- That iterative search is faster than formula-based (it's slower but exact for supported adapters).
- Byte-identical regression with current code when `includeOpportunityCost=true` (opportunity cost fix changes budgets by design).

---

## 10. Summary: Why v4 Is Simpler Than v3

| | v3 | v4 |
|---|---|---|
| Constraint conversion | `ConstraintParameters` record with 7+ fields, adapter must decompose utility | **Binary search on adapter.scoreTrip()** — no decomposition |
| Parameters adapters must expose | margUtilTraveling, margUtilDistance, margUtilWaiting, margUtilWalking, margUtilMoney, distanceSpecificMoney | **Only margUtilMoney** |
| `AdapterBackedScoringParametersForPerson` | Complex bridge class reconstructing ScoringParameters | **Not needed** |
| `ConstraintParameterRequest` | Person + mode + context + previous trips | **Not needed** (iterative search uses same TripScoreRequest) |
| New classes | ~14 | ~10 |
| Config for eqasim | None (adapter reads runtime state) | Same |
| Config for custom | One param (margUtilMoney) | Same |
