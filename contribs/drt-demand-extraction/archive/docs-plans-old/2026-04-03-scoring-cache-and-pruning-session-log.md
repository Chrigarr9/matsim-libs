# ExMAS Scoring Cache + Travel Time Pruning — Session Log (2026-04-03)

## Context

Continuing optimization of the ExMAS ride extension algorithm (branch `feature/exmas-traceable` in matsim-libs).
Previous session (2026-04-02/03) implemented pruned greedy enumeration, inter-degree pruning, inline eval with tighten-on-valid, and branch-and-bound. The remaining bottleneck was budget validation cost (~1ms/call) and ordering enumeration overhead.

## Optimizations Implemented

### 1. Scoring Context Cache (Tasks 1-5)

**Problem:** `DrtTripScorer.scoreWithActivityResolution()` parses the person's entire MATSim plan + creates 8 objects PER CALL. At degree 6: 552 plan parsings per set (6 pax x 92 orderings).

**Solution:** Pre-compute and cache per-request scoring context once before Phase 4:
- `ScoringContext` record on `DrtRequest` (volatile for thread-safe publication)
- `BudgetValidator.precomputeScoringContexts()` resolves activities, durations, scoring params, builds template legs/routes
- `DrtTripScorer.scoreWithContext()` fast path — only travelTime, distance, delay vary per call
- `BudgetValidator.calculateDrtScore()` uses cached context when available, falls back for singles/pairs

**Files modified:**
- `demand/DrtRequest.java` — ScoringContext record + volatile field + getter/setter
- `algorithm/validation/BudgetValidator.java` — precomputeScoringContexts() + calculateDrtScore() fast path
- `scoring/DrtTripScorer.java` — scoreWithContext() method
- `algorithm/engine/ExMasEngine.java` — call precompute before Phase 4

**Impact (10% Bavaria):**
| Degree | Before | After | Speedup |
|--------|--------|-------|---------|
| 3 | 47.8s | 27.6s | 1.7x |
| 4 | 141.6s | 43.7s | 3.2x |
| 5 | 1,867s | 692s | 2.7x |
| 6 (ETA) | 14h | 5.4h | 2.6x |

### 2. Per-Passenger Travel Time Pruning (Strategy G)

**Problem:** After scoring cache, pure enumeration (57.5%) and ride construction (39.5%) dominate. At degree 5, 4.8B orderings evaluated, 98.2% fail maxTravelTime constraints — detected only at the leaf.

**Solution:** Track pickup times during origin enumeration, prune destination branches where any passenger's accumulated in-vehicle time already exceeds maxTravelTime:
- Record `pickupTimes[c]` during origin recursion (free — uses existing currentTime)
- **Check A** (start of destination depth): `if (currentTime - pickupTimes[p] > maxTravelTime) return;` — prunes entire subtree
- **Check B** (per-candidate in destination loop): same check after routing to candidate — `continue` to try next candidate

**Files modified:**
- `algorithm/extension/OrderingEnumerator.java` — pickupTimes threading + two pruning checks
- `algorithm/extension/EnumerationStats.java` — new file, profiling counters
- `algorithm/extension/RideExtender.java` — profiling instrumentation in processSet()

**Impact (10% Bavaria):**
| Degree | Orderings/set before | After | Reduction | Wall time before | After | Speedup |
|--------|---------------------|-------|-----------|-----------------|-------|---------|
| 3 | 0.6 | 0.6 | 0% | 28.4s | 27.0s | 1.05x |
| 4 | 12.3 | 7.7 | 37% | 46.2s | 41.2s | 1.12x |
| 5 | 180.7 | 58.5 | 68% | 754.7s | 392.0s | **1.93x** |

**Combined impact (both optimizations, 10% Bavaria):**
| Degree | Original | + Both | **Total speedup** |
|--------|----------|--------|-------------------|
| 5 | 1,867s (31 min) | 392s (6.5 min) | **4.8x** |
| 6 | ETA 14h | ETA ~1-1.5h | **~10x** |

## Profiling Data (10% Bavaria, degree 5, with both optimizations)

```
Sets processed: 26,587,744
Orderings evaluated: 1,554,536,144 (58.5 per set)
Rides built: 1,554,536,144 (58.5 per set)
Rides passed constraints: 85,242,651 (5.5%)
Budget validations: 85,242,651, passed: 100.0%
Segment lookups: 13,990,825,296 (526 per set)
Pruned by travel time: 1,010,464,715 (38.0 per set)
Time breakdown:
  Pure enumeration:   3,754s (65.8%)
  Ride construction:  1,645s (28.8%)
  Budget validation:    270s (4.7%)
```

## Investigation: Beeline Insertion Filter

Tested whether a beeline-based insertion cost bound could pre-filter candidates before enumeration.

**Result:** Would prune 27-54% of candidates, but has 3-8% false positive rate (because the new ride's ordering might differ from the parent's, achieving shorter distance). NOT a provable filter — it's a heuristic.

| Degree | Would prune | False positives | Safe prunes |
|--------|-------------|-----------------|-------------|
| 3 (10%) | 53.7% | 3.1% of pruned | 52.0% |
| 4 (10%) | 31.1% | 7.7% of pruned | 28.7% |
| 5 (10%) | 27.1% | 5.4% of pruned | 25.7% |

## Investigation: Ordering Inheritance

Checked how often the best ride at degree k+1 preserves the parent ride's ordering (with the new passenger inserted).

| Degree | Insertion ordering | Different ordering |
|--------|-------------------|-------------------|
| 3 (10%) | 62.7% | 37.3% |
| 4 (10%) | 65.0% | 35.0% |
| 5 (10%) | 52.3% | 47.7% |

~52-65% of best rides at degree 3-5 preserve parent ordering. Drops toward 50/50 at higher degrees.

## Correctness Verification

- E2E tests pass (ExMasDemandExtractionE2ETest, ExMasKelheimHyperPoolE2ETest)
- 1% Bavaria: **12,552 rides** (exact match with all previous correct runs)
- Ride breakdown: 2,097 single + 9,245 pair + 809 deg3 + 313 deg4 + 75 deg5 + 12 deg6 + 1 deg7

## Investigation: Beeline Insertion Filter (not provable)

Tested whether `parentRide.rideDistance + minBeelineInsertionCost(E) > maxAllowedRideDistance` could pre-filter candidates before enumeration.

**Result:** 27-54% of candidates would be pruned, but **3-8% false positive rate** at 10%.

**Root cause of false positives:** The bound assumes the new ride's route is an insertion into the parent's route. But the full enumeration can find completely different orderings that are SHORTER than the parent route. The bound is NOT a valid lower bound on the best ride distance for the new set — only on insertion-based rides.

**Conclusion:** Heuristic, not provable. Not safe without accepting some ride loss.

## Investigation: Ordering Inheritance

How often does the best degree-(k+1) ride preserve the parent's ordering?

| Degree | Insertion ordering | Different ordering |
|--------|-------------------|-------------------|
| 3 (10%) | 62.7% | 37.3% |
| 4 (10%) | 65.0% | 35.0% |
| 5 (10%) | 52.3% | 47.7% |

~52-65% preserve parent ordering at degree 3-5. Implication: insertion warm-start (Strategy B) would find the best ride about half the time, providing a decent B&B bound. But full enumeration is still needed for correctness.

**Note:** Analysis 3 (ordering sub-structure reuse) had a bug — compared link IDs against request indices. The 0% result was an artifact. Sub-ordering reuse potential is unknown and should be re-investigated.

## Investigation: Sub-Triple Feasibility (Key Finding)

**Question:** If the degree-3 graph generates degree-4 candidates by connecting rides sharing 2 passengers, how many degree-4 rides would be missed?

**Answer:** Initial analysis showed 18 out of 313 degree-4 rides (5.8%) with only 1 valid sub-triple. Deep investigation revealed:

**ALL 18 "missed" rides were caused by DISTANCE SAVINGS PRUNING, not constraint infeasibility.**

The sub-triples failed because they didn't save enough distance at degree 3 (threshold: 23.8%). But at degree 4, the distance budget is larger (4 passengers' direct distances), so the ride passes the 30.0% threshold. The sub-triples were constraint-feasible (maxTravelTime, budget, delays all OK) but pruned for insufficient distance savings.

**Critical insight:** If a sub-triple fails CONSTRAINT checks (maxTravelTime, budget, delays), adding a 4th passenger provably can't help — more stops = more detour = worse for existing passengers. The degree-3 graph is **provably correct for constraint-based feasibility**.

**To achieve 0% miss rate:** Build the degree-3 graph from all CONSTRAINT-FEASIBLE triples, not just pruning-survivors. Separate constraint feasibility from distance savings pruning.

## Candidate Count Reduction with Degree-Specific Graph

| Degree | Current candidates | Graph candidates | Reduction |
|--------|-------------------|-----------------|-----------|
| 4 | 4,072 | 2,475 | **39% fewer** |
| 5 | 1,498 | 540 | **64% fewer** |
| 6 | 301 | 75 | **75% fewer** |
| 7 | 40 | 9 | **78% fewer** |

## Next Steps — Degree-Specific Shareability Graph Design

### Core Idea

Replace the current approach (always use pair graph for ALL degrees) with degree-specific graphs:

```
Current:  pair graph → deg 3 → deg 4 → deg 5 (always uses pair graph for candidates)
Proposed: pair graph → deg 3 → build deg-3 graph → deg 4 → build deg-4 graph → deg 5
```

Each degree builds on the VALIDATED rides from the previous degree. This unifies three ideas:

1. **Automatic negative cache:** Failed subsets aren't nodes → supersets never generated
2. **Ordering constraints:** Each node stores valid orderings → constrains higher-degree enumeration
3. **Stronger candidate filtering:** Requires ≥2 valid sub-rides (vs current: 1 base + pairwise compatibility)

### Building the Graph Efficiently

Index by shared passenger pairs: for each pair (A,B), store all degree-k rides containing both. Rides in the same bucket are connected. Cost: O(T × k²) where T = number of valid rides.

### Key Requirement for Correctness

Build the graph from CONSTRAINT-FEASIBLE rides (pass maxTravelTime + budget + delays), NOT just distance-savings survivors. This ensures 0% miss rate.

### Additional: Ordering Constraint Propagation

Valid sub-ride orderings provide constraints for the next degree:
- From {A,B,C} ordering [A,B,C]: A before B, B before C
- From {A,B,D} ordering [A,D,B]: A before D, D before B
- Combined: constrains {A,B,C,D} enumeration to orderings compatible with at least one sub-ride

This is the pair graph's FIFO/LIFO system generalized to higher degrees.

### Rides Catalog

Currently top-1 per set. Could keep top-K or all valid orderings for:
- Richer ordering constraints for next degree
- Multiple ride options for downstream MIP optimizer
