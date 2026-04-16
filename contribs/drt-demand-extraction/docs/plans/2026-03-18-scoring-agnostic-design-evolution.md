# Scoring-Agnostic Demand Extraction — Design Evolution & Decision Log

**Date:** 2026-03-17 to 2026-03-18
**Context:** Design session for making DRT demand extraction work with arbitrary MATSim scoring systems (standard MATSim, DMC, eqasim, custom).
**Outcome:** v4 adapter architecture with iterative constraint search.

This document captures the full thought process, alternatives considered, and reasons for each design decision. Useful for dissertation methodology chapter.

---

## 1. The Problem

MATSim has two parallel evaluation systems:
- **REPLANNING** (mode choice): DMC → TripEstimator → utility for choosing modes
- **SCORING** (plan selection): CharyparNagel ScoringFunction → planCalcScore params

The DRT demand extraction module used the SCORING channel exclusively (`ScoringFunctionFactory`). With eqasim, planCalcScore has dummy values (marginalUtilityOfMoney=0, all marginals=-1/3600) → garbage budgets, garbage constraints.

The problem has two parts:
1. **Budget calculation** (trip utility comparison): needs the same scoring as the agent's mode choice
2. **Budget-to-constraint conversion** (maxDetour, maxCost, etc.): needs decomposed marginal parameters

---

## 2. Research: MATSim Scoring Landscape

### Paradigm A: Standard MATSim (VSP scenarios — Berlin, Hamburg, Leipzig, Kelheim)
- `SubtourModeChoice` (random mutation)
- planCalcScore with real calibrated params
- Score-based evolutionary plan selection (ChangeExpBeta)
- planCalcScore IS the behavioral model

### Paradigm B: eqasim (Ile-de-France, Switzerland, Bavaria)
- `DiscreteModeChoice` with custom `EqasimUtilityEstimator`
- planCalcScore has dummy values (scoring disabled)
- `KeepLastSelected` (single plan, scoring irrelevant)
- eqasim's TripEstimator IS the behavioral model
- Parameters loaded from YAML into Guice-managed Java objects — NOT in MATSim Config

### Key finding: two different Kelheim scenarios
- `matsim-kelheim` (VSP): Paradigm A, real planCalcScore
- `eqasim-org/bavaria`: Paradigm B, dummy planCalcScore

---

## 3. Design Evolution

### v1: Direct TripEstimator Replacement (initial plan)

**Idea:** Replace `ScoringFunctionFactory` with DMC's `TripEstimator` for all scoring. Use `@Inject(optional=true)` to pick up whatever TripEstimator is bound.

**Mechanism:** Routing override — wrap TripRouter so TripEstimator "routes" pre-constructed elements.

**Problems identified in review:**
- FallbackTripEstimator (catch-all exception for unknown modes) → silent degradation
- Guice binding conflicts (global TripRouter override)
- `DiscreteModeChoiceTrip.setDepartureTime()` doesn't exist
- No solution for budget-to-constraint conversion

### v2: Universal Perturbation (scoring-agnostic via perturbation)

**Idea:** Extract ALL marginal parameters from the TripEstimator via perturbation. Score synthetic trips with small deltas (e.g., +60s travel time) and observe utility changes.

**What worked:**
- Time rate: `dU/dTime` extractable ✓
- Distance rate: `dU/dDistance` extractable ✓ (combined dist + monetary)
- Walk rate: `dU/dDistance_walk` extractable ✓

**What didn't work — the margUtilMoney problem:**
- `dU/dDistance = margUtilDist + monetaryDistRate × margUtilMoney`
- Two unknowns, one equation. Money is ENTANGLED with distance.
- Tried: multiple modes, multiple distances, eqasim interaction fitting — all lead to one equation, two unknowns
- Fundamental constraint: TripEstimator operates in utility space, never exposes EUR

**Alternatives explored for margUtilMoney:**
1. **ScoringFunction.addMoney(1.0)**: Perturb ScoringFunction with 1 EUR money event. Works for standard MATSim (CharyparNagel). Fails for eqasim (ScoringFunction has dummy params, not overridden). Rejected as hacky.

2. **Config scanning**: Read from MATSim Config modules. Failed — eqasim stores params in Guice objects, not Config. Config only has file paths.

3. **Reflection-based access**: `Class.forName("org.eqasim...ModeParameters")` → `injector.getInstance()` → read `betaCost_u_MU`. Works but couples to eqasim class names.

4. **Reference-mode trick**: Perturb car mode, divide `dU/dDist_car` by known `costPerKm` to isolate margUtilMoney. Requires knowing car cost per km (from planCalcScore for standard MATSim, from user config for eqasim).

5. **Config override in ExMasConfigGroup**: User provides `marginalUtilityOfMoney` directly. Simple, explicit, works for any scenario. Downside: manual, global (not person-specific).

6. **Config pointer**: User specifies which config module and parameter name to read. Failed — eqasim params aren't in Config.

7. **Modifying TripEstimator interface**: Add `getMarginalUtilityOfMoney()` default method. Can't — we only modify `drt-demand-extraction`, not DMC.

8. **ScoringAgnosticParametersForPerson**: Wrap `ScoringParametersForPerson`, always perturb to extract rates, write corrected values into ScoringParameters format. Simplifies downstream code. But still can't solve margUtilMoney for eqasim.

### v2 review findings:
- Provider wiring rebuilds scoring stack in hot path (perf regression)
- Synthetic DMC trip construction diverges from replanning for tour-based estimators
- Global config overrides don't support person-specific heterogeneous models
- Narrow regression test coverage (missed HyperPool/stop-based paths)
- Zero validation too blunt (rejects legitimate zero params)

### v3: Adapter Architecture (explicit per-scoring-system adapters)

**Key insight:** Stop trying to solve all scoring systems with one generic mechanism. Model-specific logic belongs behind adapters.

**Idea:** `DemandExtractionScoringAdapter` SPI with concrete implementations:
- `PlanCalcScoreAdapter` (standard MATSim)
- `DmcMatSimTripAdapter` (DMC with MATSimTripScoring)
- `EqasimScoringAdapter` (reads eqasim objects directly)

Each adapter provides both trip scoring AND constraint parameters. The `ConstraintParameters` record had 7+ fields that each adapter must populate.

**Problems identified in review:**
- Over-complex: 14+ new classes
- `ConstraintParameters` forced every adapter to decompose its utility
- Tour context design was ambiguous
- Eqasim integration path underspecified

### v4: Adapter Architecture + Iterative Constraint Search (final)

**Key breakthrough:** Instead of extracting marginal parameters for constraint formulas, USE THE ADAPTER'S OWN SCORING as a black box and binary search for constraint boundaries.

```
maxDetour: binary search on DRT travel time until score = bestModeScore
maxWait: binary search on wait time
maxWalk: binary search on walk distance
maxCost: budget / margUtilMoney (the ONE parameter that needs explicit provision)
```

**Why this is better:**
- No parameter decomposition (the 6+ field ConstraintParameters disappears)
- Exact for element-determined utility models
- Handles non-linear utility (eqasim distance interaction)
- Person-specific and trip-specific automatically
- Adapters only need `scoreTrip()` + `getMarginalUtilityOfMoney()`

---

## 4. The margUtilMoney Problem — Full Analysis

### Why it can't be extracted from perturbation

The TripEstimator's utility includes monetary cost as `distance × monetaryRate × margUtilMoney`. When we perturb distance, we get the PRODUCT, not the individual factors. This is a fundamental mathematical constraint — not an implementation limitation.

Analogy: measuring `f(x) = (a + b) × x` by varying x. You can measure the slope `a + b` but never separate `a` from `b` with any number of x-measurements.

### Why it matters

`margUtilMoney` converts utility ↔ EUR. Needed for maxCost (how much fare can the person afford?). DRT fare is external to the TripEstimator (applied by `DrtFareHandler` as `PersonMoneyEvent`), so the adapter's scoreTrip() doesn't reflect fare changes.

### Eqasim complication: distance interaction

In eqasim, effective margUtilMoney = `betaCost × (euclidDist / refDist)^lambda`. Not a flat scalar — varies ~40% across typical trip distances (2-10km). Bavaria values: betaCost=-0.311, refDist=4.4km, lambda=-0.258.

### Final resolution per scenario

| Scenario | margUtilMoney source | Person-specific? | Trip-specific? |
|---|---|---|---|
| Standard MATSim | planCalcScore (auto) | Yes (subpopulation) | No |
| DMC + MATSimTripScoring | planCalcScore (auto) | Yes (subpopulation) | No |
| eqasim | betaCost × interaction from ModeParameters via probe | No (global betaCost) | Yes (distance) |
| Custom | User config | No (global) | No |

---

## 5. The Opportunity Cost Problem

### What it is
`opportunityCost = -travelTime × marginalUtilityOfPerforming_s`

In standard MATSim, leg scoring gives PURE travel disutility. The opportunity cost (lost activity time) is separate. Demand extraction adds it manually if configured.

### Bug discovered during design
ModeRoutingCache applies opportunity cost to bestModeScore but BudgetValidator does NOT apply it to DRT actual score. This gives DRT an unfair advantage on detoured trips (detour time not penalized for lost activity).

### Eqasim complication
eqasim's `betaTravelTime` is estimated from survey data — it captures the TOTAL travel time disutility including implicit opportunity cost. Applying planCalcScore's `margUtilPerforming` on top would DOUBLE COUNT.

### Resolution
Make opportunity cost adapter-controlled via `includesOpportunityCost()` flag:
- Standard MATSim / DMC: `false` → caller adds if configured
- eqasim: `true` → caller must NOT add

---

## 6. The Tour Context Problem

### Three levels considered

1. **TRIP_INDEPENDENT** (current): Each trip scored independently. Misses car-chain, parking.

2. **GREEDY_PREFIX** (implemented): Score trips sequentially, each trip sees preceding trips' best non-DRT modes. Builds the "what would person do without DRT?" baseline.

3. **COMBINATORIAL** (future): Enumerate all feasible mode combinations per subtour, find optimal without-DRT and with-DRT combos. The theoretically correct approach.

### Why greedy, not combinatorial?
- ChainIdentifier already groups trips by subtour — infrastructure exists
- Combinatorial is feasible (~125 combos for 3-trip subtour) but adds complexity
- Greedy captures the main tour effects (car availability, mode chains)
- Combinatorial can be added later without SPI changes

### Why greedy, not actual-plan prefix?
The agent's actual selected plan prefix reflects what happened WITHOUT demand extraction's mode evaluation. For demand extraction, we want "what's the best non-DRT baseline?" — that's the greedy approach. The actual plan may have suboptimal modes.

### Reviewer correction
Initial design called GREEDY_PREFIX "SELECTED_PLAN_PREFIX" which was misleading. Renamed to be honest about what it is: a synthetic greedy baseline, not the agent's actual plan.

---

## 7. The Daily Constant Problem

### Current approach (hack)
Create new ScoringFunction per trip → CharyparNagel adds daily constants → manually subtract them. Fragile, assumes CharyparNagel internals.

### Resolution
All v4 adapters naturally exclude daily constants:
- `TripScoringUtils.calculateLegScore()`: formula never includes them
- `MATSimTripScoringEstimator`: daily constants are in separate `MATSimDayScoringEstimator` class
- eqasim: no daily constant concept in trip-level scoring

The hack is deleted entirely.

---

## 8. The Cost Field Problem

### What ModeAttributes.cost was
`distance × monetaryDistanceCostRate + modeConstant(ASC)` — mixed monetary cost with utility constant. Only used in debug CSV, never in budget calculation.

### Problems
- ASC is not a monetary cost
- In eqasim: monetaryDistRate=0 → cost=0 (meaningless)
- Separate code path from score calculation → inconsistent

### Resolution
Remove `cost` from `ModeAttributes`. The adapter's `score` field captures all utility components including monetary. The cost column was debug-only and broken.

---

## 9. Iterative Constraint Search — The Key Insight

### The old way (formula-based)
Extract 6+ marginal parameters → compute: `maxDetour = budget / disutilityPerSecond`

Problems: needs parameter decomposition, assumes linear utility, breaks for eqasim (non-linear interaction term), different code path from budget scoring.

### The new way (iterative binary search)
Use the adapter's own `scoreTrip()` to find constraint boundaries by binary search.

For maxDetour: construct DRT trips with increasing travel time, score each, find where budget = 0.

**Why it's exact:** Same scoring function as budget calculation. Same adapter, same parameters, same code path. For element-determined utility models, the search result IS the exact constraint.

**Why it's fast:** Route once, vary override elements, re-score. ~12 iterations per constraint × 3 constraints = 36 calls per request. Scoring pre-routed elements is microseconds.

**What it replaces:** The entire `BudgetToConstraintsCalculator` formula infrastructure. Instead of reading 6+ raw `ScoringParameters` fields, we make ~36 adapter calls per request.

**The one exception:** maxCost still needs margUtilMoney because DRT fare is external to the adapter's scoring. This is the only explicit parameter adapters must provide.

---

## 10. Alternatives NOT Chosen

### "Always perturb, one path" (v2 final iteration)
Extract all rates via perturbation for all scenarios. Rejected because margUtilMoney can't be extracted generically (mathematical impossibility, not implementation limitation).

### "Write eqasim params into planCalcScore" (v2 brainstorm)
Transform eqasim parameters into planCalcScore format at startup. Rejected because eqasim's distance interaction term can't be represented in planCalcScore's flat parameter model.

### "Modify TripEstimator interface" (v2 brainstorm)
Add `getMarginalUtilityOfMoney()` to DMC's TripEstimator. Rejected because we can only modify `drt-demand-extraction`, not DMC.

### "ScoringFunction.addMoney(1.0)" (v2 brainstorm)
Perturb ScoringFunction with a money event to extract margUtilMoney. Rejected: works for standard MATSim but returns 0 in eqasim (ScoringFunction has dummy params, not overridden by eqasim in "mode choice in the loop" setup).

### "Full ConstraintParameters record" (v3)
7+ field record that each adapter must populate with decomposed utility components. Rejected in favor of iterative search — adapters only need `scoreTrip()` + `getMarginalUtilityOfMoney()`.

### "AdapterBackedScoringParametersForPerson" (v3)
Bridge class that reconstructs MATSim ScoringParameters from adapter output. Rejected — unnecessary with iterative search.

### "Combinatorial tour evaluation" (v4 brainstorm)
Evaluate all feasible mode combinations per subtour. Computationally feasible but deferred — GREEDY_PREFIX captures the main effects. COMBINATORIAL can be added later without SPI changes.

---

## 11. Key Design Decisions Summary

| Decision | Choice | Alternatives considered | Reason |
|---|---|---|---|
| Scoring abstraction | Adapter SPI | Generic TripEstimator wrapper, ScoringParametersForPerson override | Adapters are honest about what each system can do |
| Constraint conversion | Iterative binary search | Formula with 6+ extracted params, perturbation | Exact for supported models, no decomposition needed |
| margUtilMoney | Adapter provides (probe for eqasim, planCalcScore for MATSim, config for custom) | Generic perturbation extraction | Can't be extracted generically (math impossibility) |
| Opportunity cost | Adapter-controlled flag | Always apply from planCalcScore | eqasim betaTravelTime already includes it — would double-count |
| Daily constants | Eliminated (adapters exclude by design) | Preserve manual subtraction hack | All adapters naturally exclude them |
| Tour context | GREEDY_PREFIX (sequential best non-DRT) | Actual plan prefix, combinatorial | Best non-DRT baseline for demand extraction |
| Cost field | Removed from ModeAttributes | Fix ASC bug and keep | Debug-only, broken in eqasim, redundant with score |
| Eqasim access | Runtime probe via reflection | Config override, eqasim dependency, ScoringFunction hack | No compile-time dependency, reads real params |
| Fail behavior | Fail fast on missing capabilities | Silent fallback, warn-and-continue | Garbage results are worse than a clear error |
