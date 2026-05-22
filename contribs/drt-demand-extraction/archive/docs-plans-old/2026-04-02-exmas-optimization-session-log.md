# ExMAS Extension Optimization — Session Log (2026-04-02/03)

## Starting Point

**Context:** Ordering-based extension (implemented 2026-04-01) enumerates all valid
(origin, destination) orderings per candidate set via topological sort. 10% Bavaria
results: degree 3 in 40s, degree 4 in 7.4min, degree 5 in 78min, degree 6 killed after
9h. The extension cost is dominated by enumerating and routing all orderings, most of which
fail the distance savings threshold.

**Goal:** Make degree 6+ reachable at 10% and eventually 100% population.

---

## Phase 1: Pruned Greedy Enumeration

### Design

Sort candidates by routed segment distance at each recursion depth of the topo sort.
Accumulate partial ride distance with cumulative time tracking (matching
`buildRideFromOrdering` exactly). Break when partial distance exceeds the distance
savings threshold. Provably complete: any ordering within threshold is found.

### Beeline Pre-Filter — Considered and Rejected

**Idea:** Before calling `getSegment()` (routed distance), compute beeline (Euclidean)
distance as a cheaper pre-filter. Break on beeline, continue on routed.

**Empirical analysis (`BeelineVsRoutedPruningTest`, 1000 Monte Carlo trials):**
- **Sorting mismatch rate: 69%** — beeline order ≠ routed order most of the time
  (MATSim routing optimizes for travel TIME, not distance)
- **Zero false rejections** (beeline ≤ routed always holds)
- Beeline break saves ~3.4 getSegment calls per depth but forces weaker `continue`
  (not `break`) on routed distance

**Decision:** Rejected. Single-tier routed-distance sort + break is simpler, gives
stronger pruning, and the marginal getSegment savings don't justify the complexity.

### Cumulative Time Tracking

The enumeration tracks `currentTime += segment.getTravelTime()` at each step, matching
`buildRideFromOrdering` exactly. This ensures:
- Identical cache keys → identical segment distances → identical ride distances
- Segments pre-warmed in cache for surviving orderings (ride building gets 100% cache hits)
- The `passesDistanceSavingsPruning` post-check becomes redundant (replaced by debug assertion)

### Scale Change: 0.25 → 0.15

Validated in pruning analysis session (2026-03-30): scale=0.15 gives 92% total ride
reduction with 100% P90 elite preservation. Scale=0.25 was too aggressive at degree 6
(required 65% savings — only near-perfect collinear clusters survived).

### Results (10% Bavaria, pruned greedy, no inter-degree pruning)

| Degree | Baseline (unpruned) | Pruned Greedy | Speedup per set |
|--------|--------------------|--------------|-----------------| 
| 3 | 234k rides, 312s | 1.07M rides, 33s | **9.4x** |
| 4 | 683k rides, 1,393s | 5.74M rides, 425s | **10.5x** |
| 5 | 1.08M rides, 26,729s | **OOM at 28.8/30 GB** | — |

More rides at every degree because scale=0.15 is less aggressive. But 5.7M degree-4
rides caused OOM at degree 5 — too many base sets stored in `ConcurrentHashMap`.

**Key learning:** Enumeration speed is solved. The bottleneck shifted to MEMORY
(storing millions of rides per degree) and VOLUME (too many candidate sets at high degrees).

---

## Phase 2: Mandatory Inter-Degree Percentile Pruning

### Design

After each degree extension, keep only the top X% of rides by distance savings (default
10%). Survivors become both the final output for that degree AND the base sets for the
next degree. No sqrt scaling (the existing `sqrt(finalKeepTop)` was too gentle — 31.6%
of 5.7M = 1.8M, still OOM).

### Config Parameters Added
- `interDegreeKeepFraction` (double, default 0.10)
- `interDegreeMinRidesPerRequest` (int, default 0 — disabled, build it but verify need first)

### Per-Request Variety Floor — Designed but Disabled

**Idea:** Ensure each request appears in at least N rides after pruning. If a request
has fewer rides above the threshold, rescue its best rides.

**Decision:** Built the parameter but default to 0 (disabled). Run first, check variety
data, enable if needed. Analysis of 1% data showed 17.3% of requests have zero shared
rides (isolated passengers) — the floor can't help them anyway.

### Results (10% Bavaria, pruned greedy + inter-degree 10%)

| Degree | Raw rides | After 10% prune | Time |
|--------|-----------|-----------------|------|
| 3 | 1,065,490 | 106,549 | 30s |
| 4 | 2,769,202 | 276,921 | 262s |
| 5 | 4,877,067 | 487,707 | 2,752s (46min) |
| 6 | running | | ETA ~12h at 11 sets/s |

Memory stable at 7-18 GB / 30 GB. Degree 6 reachable but slow (488k base sets × 
~100 candidates each × ~14ms per candidate set).

### Variety Analysis (1% data, no inter-degree pruning)

| Shared rides per request | Count | % |
|---|---|---|
| 0 (single only) | 363 | 17.3% |
| 1-2 | 450 | 21.5% |
| 3-5 | 394 | 18.8% |
| 6-10 | 311 | 14.8% |
| 11+ | 579 | 27.6% |

Highly skewed: top requests have 177-266 rides, bottom have 0.

---

## Phase 3: Branch-and-Bound + Sort-First Validation

### Why 14ms Per Candidate Set at Degree 6?

Instrumentation revealed the root cause:

| Degree | Avg orderings/set | Avg validated/set | Avg tries until valid |
|--------|-------------------|-------------------|-----------------------|
| 3 | 3.7 | 3.3 | 1.3 |
| 4 | 15.1 | 11.2 | 3.6 |
| 5 | 119.3 | 58.6 | 23.1 |
| 6 | **1,756** | **252.2** | **92.3** |
| 7 | **36,384** | **574.0** | **723.0** |

At degree 6: 1,756 orderings enumerated, 252 validated via budget scoring, only 1 kept.
Budget validation (full MATSim scoring) costs ~1ms per ride. 252 validations = ~252ms 
per set — this is the bottleneck, not enumeration.

### Tightening Bound — Implemented Then Fixed

**First attempt:** Tighten `bestFoundDist[0]` to the shortest COMPLETED ordering during
DFS. This reduces orderings from 1,756 to ~50 at degree 6.

**Bug discovered:** The shortest ordering might fail budget validation. Tightening to its
distance prunes longer orderings that WOULD have passed validation. Result: 12,414 rides
at 1% instead of 12,552 (1.1% loss). At 10%: ~20% fewer rides at high degrees.

**Why the loss grows with degree:** At degree 6, 75% of the time the shortest ordering
passes validation (no loss). But 25% of the time it fails, and the valid fallback is
pruned. At degree 7: 0% first-ordering success → all fallbacks pruned.

### Sort-First Validation (No Tightening)

**Design:** Enumerate all orderings within threshold (no tightening), add `rideDistance`
to `Ordering` record, sort by distance, validate shortest first, return first valid.

**Results at 1%:** 12,552 rides (exact match). But at 10% degree 6: 10 sets/s (same as
before) because enumerating all 1,756 orderings is the baseline cost.

**Key insight:** Sort-first validation reduces validation work (252 → 1.3 validations)
but doesn't reduce enumeration work (still 1,756 orderings per set).

### Inline Evaluation with Tighten-on-Valid

**Design:** Call `buildRideFromOrdering` + `budgetValidator` INSIDE the DFS callback for
each complete ordering. If valid, tighten bound to its distance. The bound only tightens
on VALID orderings — never prunes valid fallbacks.

**Correctness:** Proven — if a valid ordering exists at distance V, its partial distance
never exceeds V, and V ≤ bestValidDist[0] (which only decreases), so it's never pruned.
1% validation: 12,552 rides (exact match).

**Performance problem:** Inline eval validates EVERY ordering during DFS (not just the
sorted shortest). At degree 4: sort-first validates 3.6, inline eval validates ~10.
Inline eval is actually SLOWER than sort-first at low-medium degrees because validation
is expensive (~1ms per call) and sort-first validates in optimal order.

### Distance Distribution Analysis

| Degree | Total orderings | Within 0% (exact) | Within 5% | Within 10% |
|--------|----------------|-------------------|-----------|------------|
| 3 | 5,074 | 2,711 (53%) | 3,587 (71%) | 4,219 (83%) |
| 4 | 17,668 | 6,411 (36%) | 9,751 (55%) | 12,968 (73%) |
| 5 | 61,578 | 14,128 (23%) | 26,730 (43%) | 38,218 (62%) |
| 6 | 168,602 | 24,157 (14%) | 51,394 (31%) | 81,986 (49%) |

Many orderings cluster near the shortest distance. The valid fallback (try #92) is usually
within 5% of the shortest. But 92 tries means we can't just keep the top 10 — we'd need
~100 fallback orderings.

---

## Phase 4: The Real Bottleneck — Budget Validation Cost

### Discovery

Profiling `BudgetValidator.calculateDrtScore()` → `DrtTripScorer.scoreWithActivityResolution()`:

For EACH passenger in EACH ordering:
1. `TripStructureUtils.getTrips(person.getSelectedPlan())` — **parses entire plan**
2. `computeActivityDurations(person.getSelectedPlan())` — **iterates all plan elements**
3. Creates 3 `Leg` objects + 3 `Route` objects + 2 `Activity` objects — **heavy allocation**
4. `adapter.scoreTrip()` — full MATSim scoring adapter call
5. `scoringParametersForPerson.getScoringParameters(person)` — HashMap lookup

At degree 6 with 92 tries: 6 passengers × 92 orderings = **552 plan parsings per set**.

### Key Insight

For orderings of the SAME request set, ALL of this is constant:
- Person, plan, activities, activity durations, scoring parameters
- Walk legs (same access/egress distances always)
- Synthetic origin/destination activities
- DRT route template (directRideTime, directDistance)

Only **three values** change: `travelTime`, `distance`, `delay`.

### Solution: Pre-Compute Per-Request Scoring Context

Pre-compute once per request (before ride generation starts), store on DrtRequest:
- Resolved activities + durations (from plan)
- ScoringParameters
- Template Leg/Route/Activity objects

Then `calculateDrtScore` reuses the cached context instead of re-parsing the plan.

**This is sharable across ALL sets, ALL degrees, ALL orderings.** 21k requests × 1 
precompute = 21k plan parsings total (vs millions during extension).

**Expected impact:** Budget validation drops from ~1ms to ~0.05ms per call. The 92 tries
at degree 6 go from 92ms to ~5ms. Combined with the inline eval + tighten-on-valid, 
degree 6 at 10% should complete in well under 1h.

**Plan:** `docs/plans/2026-04-03-scoring-context-cache.md`

---

## Summary of Optimizations (Cumulative)

| Optimization | What it solves | Speedup | Status |
|---|---|---|---|
| Pruned greedy enumeration | Hopeless orderings routed unnecessarily | 9-28x per set | ✅ Implemented |
| Scale 0.25 → 0.15 | Too aggressive threshold at high degrees | More rides found | ✅ Implemented |
| Inter-degree 10% pruning | OOM from millions of rides per degree | Memory bounded | ✅ Implemented |
| Sort-first validation | All orderings built + validated | 252 → 1.3 validations | ✅ Implemented |
| Inline eval + tighten-on-valid | Enumerate-all cost at high degrees | Correct + fast tightening | ✅ Implemented |
| Scoring context cache | Plan parsing per validation call | ~20x per validation | 📋 Planned |

### Performance Evolution (10% Bavaria, degree 5)

| Version | Time | Notes |
|---------|------|-------|
| Original (unpruned, scale=0.25) | 26,729s (7.4h) | All orderings, all validations |
| + Pruned greedy (scale=0.15) | OOM | More rides, no memory control |
| + Inter-degree 10% | 2,752s (46min) | Memory bounded, but slow per-set |
| + Sort-first (no tighten) | 1,476s (24.6min) | Fewer validations per set |
| + Inline eval (tighten-on-valid) | 1,867s (31min) | Correct but validates during DFS |
| + Scoring context cache (planned) | ~200s (est.) | Cheap validation enables fast inline eval |

### Degree 6 Projection

| Version | Degree 6 time (10%) |
|---------|-------------------|
| Original | killed after 9h |
| Inter-degree only | ETA 12h (11 sets/s) |
| + Buggy tighten-on-all | **59min** (99 sets/s) — but ~20% ride loss |
| + Sort-first (no tighten) | ETA 15h (10 sets/s) |
| + Inline eval (no cache) | ETA 14h (9 sets/s) |
| + Scoring context cache (planned) | **~30-60min** (est.) |

---

## Decisions Made and Rationale

### 1. Routed distance sorting, not beeline
**Data:** 69% sorting mismatch in Monte Carlo. Beeline's weaker break (continue instead
of break on routed) doesn't justify the marginal getSegment savings (~3.4 calls/depth).

### 2. Scale 0.15, not 0.25
**Data:** Scale=0.25 required 65% savings at degree 6 — only 2% of sets pass. Scale=0.15
requires 39% — physically reasonable for collinear clusters. Validated with P90
percentile analysis: 92% reduction, 100% elite preservation.

### 3. Direct inter-degree fraction, no sqrt scaling
**Data:** sqrt(0.10) = 0.316 → 31.6% of 5.7M = 1.8M rides → still OOM. Direct 10% →
570k rides, manageable. The "gentler" sqrt was designed for preserving base diversity,
but pragmatic approach is sufficient.

### 4. Per-request variety floor: disabled by default
**Rationale:** 17.3% of requests have zero shared rides regardless (isolated). The floor
can't help them. Build the parameter, verify need empirically, enable if data shows gaps.

### 5. Tighten on VALID orderings, not all
**Bug found:** Tightening on shortest enumerated ordering prunes valid fallbacks when
that ordering fails budget validation. Loss: 1.1% at 1%, ~20% at 10% high degrees. Fix:
only tighten when a valid ride is found. Preserves correctness.

### 6. Inline eval over sort-first for high degrees
**Data:** Sort-first is faster at degree 3-4 (validates optimal-order). But at degree 6
with 1,756 orderings, sort-first enumerates all of them. Inline eval with tighten-on-valid
only explores branches shorter than the best valid ride. The tradeoff: inline eval
validates more per ordering (~150 vs ~92) but explores fewer orderings (~300 vs ~1,756).
**With scoring cache, inline eval wins decisively** (cheap validation → tighten-on-valid
eliminates most enumeration).

### 7. Scoring context cache is the key unlock
**Discovery:** Budget validation does full plan parsing + object allocation per call.
At degree 6: 552 plan parsings per set (6 passengers × 92 orderings). Pre-computing
per-request context (once per 21k requests) eliminates 99.99% of the plan parsing work.
This is the single most impactful optimization remaining.

---

## Architecture After All Optimizations

```
ExMasEngine.run():
  1. Generate singles (degree 1)
  2. Generate pairs (degree 2)
  3. Build shareability graph
  4. Pre-compute scoring contexts (NEW — once per request)
  5. For degree 3 → maxDegree:
     a. RideExtender.extendRides():
        - For each base set, find common neighbors
        - For each candidate set:
          OrderingEnumerator.enumerateAndEvaluate():
            - DFS with distance sorting at each depth
            - Cumulative time tracking
            - Break when partialDist > bestValidDist (tighten-on-valid)
            - At each leaf: build ride + validate budget (CHEAP with cache)
            - If valid: tighten bestValidDist → prune remaining branches
        - Return best valid ride per set
     b. Inter-degree pruning: keep top 10% by savings
     c. Survivors → base sets for next degree
```

---

## Files Changed (This Session)

### matsim-libs (branch: feature/exmas-traceable)

| File | Changes |
|------|---------|
| `OrderingEnumerator.java` | +enumeratePruned (routed-distance sort, cumulative time), +enumerateAndEvaluate (callback-based tighten-on-valid), rideDistance in Ordering record |
| `RideExtender.java` | processSet uses enumerateAndEvaluate, computeMaxAllowedRideDistance helper, removed passesDistanceSavingsPruning call |
| `PostExtensionPruner.java` | +minRidesPerRequest parameter, per-request floor rescue logic |
| `ExMasConfigGroup.java` | +interDegreeKeepFraction, +interDegreeMinRidesPerRequest |
| `ExMasEngine.java` | Inter-degree pruning with direct fraction (no sqrt) |
| `RunBavaria30kmDemandExtraction.java` | scale 0.25→0.15, CLI args for inter-degree params |
| `BeelineVsRoutedPruningTest.java` | New test validating beeline vs routed pruning decision |

### Dissertation repo

| File | Contents |
|------|---------|
| `docs/plans/2026-04-02-pruned-greedy-extension.md` | Design + execution plan for pruned greedy |
| `docs/plans/2026-04-02-inter-degree-pruning-design.md` | Inter-degree pruning design doc |
| `docs/plans/2026-04-02-inter-degree-pruning-plan.md` | Implementation plan |
| `docs/plans/2026-04-02-branch-and-bound-enumeration.md` | Branch-and-bound + sort-first plan |
| `docs/plans/2026-04-03-scoring-context-cache.md` | Scoring context cache plan |
| `docs/plans/2026-04-02-exmas-optimization-session-log.md` | This document |

---

## Next Steps

1. **Implement scoring context cache** (plan: `2026-04-03-scoring-context-cache.md`)
   - Pre-compute per-request context in BudgetValidator
   - Store on DrtRequest objects
   - New `DrtTripScorer.scoreWithContext()` method
   - Expected: 20x faster validation → degree 6 in ~30-60min at 10%

2. **Run 10% with cache** — validate correctness + measure degree 6 timing

3. **Run 25% and 100%** — the goal. With all optimizations, 100% should be feasible
   within a few hours for degree 3-6.

4. **Optional: beeline diameter pre-reject** — skip candidate sets whose spatial diameter
   exceeds maxAllowedRideDistance. Saves enumeration for obviously impossible sets.
   Lower priority since scoring cache makes per-set cost cheap.
