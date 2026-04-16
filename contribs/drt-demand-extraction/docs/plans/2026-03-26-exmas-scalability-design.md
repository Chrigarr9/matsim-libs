# ExMAS Scalability Design — 100% Bavaria Demand Extraction

**Date:** 2026-03-26
**Context:** Bavaria 25% (61k requests) OOM'd at 30GB during ride extension. 100% would have ~245k requests. Need strategies to keep memory bounded without compromising shareability graph quality.

## Problem Statement

The ExMAS pipeline has three memory-intensive phases:

| Phase | 25% actual | 100% estimate | Bottleneck |
|-------|-----------|---------------|------------|
| Pair generation | 8.5M pairs | ~34M pairs | Routing calls (O(N²)) |
| Ride extension | 833M candidates, OOM | ~13B candidates | Memory (`.collect()` into list) |
| Output | not reached | ~50M rides | Disk I/O |

## Strategy Overview

Two complementary strategies that reduce input volume before the extension phase:

1. **Beeline pre-filter** — reduce pair generation routing calls by 50-70%
2. **Post-graph degree-2 pruning** — reduce extension base rides by 30-50%

These are additive — each reduces the input to the next phase. Combined with increased heap (`-Xmx100g`), this should make 100% feasible.

The existing post-hoc per-request-set pruning (percentage-based, after `.collect()`) remains the quality filter for extensions. An inline top-N cap during extension was considered but rejected — it would hide candidates from the post-hoc pruning, which needs the full picture across all base rides to properly apply percentage-based selection.

---

## Strategy 1: Beeline Pre-Filter in Pair Generation

### Mechanism

Before any network routing for a candidate pair (i, j), compute the Euclidean (beeline) shared path distance for each passenger and check against the existing detour constraint.

For FIFO (pick up i first, then j):
```
passenger_i_shared_beeline = dist(O_i, O_j) + dist(O_j, D_i)
passenger_j_shared_beeline = dist(O_j, D_i) + dist(D_i, D_j)
```

For LIFO (pick up i first, drop off j first):
```
passenger_i_shared_beeline = dist(O_i, O_j) + dist(O_j, D_j) + dist(D_j, D_i)
passenger_j_shared_beeline = dist(O_j, D_j)  // j rides directly, no detour in LIFO
```

**Rejection rule (no threshold needed):**
```
if (passenger_shared_beeline > request.directDistance * maxDetourFactor) → REJECT
```

### Why no threshold is needed

- `beeline(path) ≤ network(path)` — always, by triangle inequality
- `request.directDistance` is the **network-routed** direct distance (already computed)
- Comparing a lower bound (beeline shared) against a limit derived from actual network distance
- If the lower bound already exceeds the limit, the real value certainly does
- **Zero false negatives, guaranteed**

### Where in the code

In `PairGenerator.generateCandidatesForRequest()`, after the temporal filter (line ~167) and before the first routing call (O→O route, line ~177).

Requires access to request origin/destination coordinates — already available via `DrtRequest.getOriginX/Y()` and `getDestinationX/Y()`.

### Expected impact

- Passengers going in opposite directions: huge beeline detour → eliminated
- Passengers far apart laterally: caught by distance inflation
- Passengers with very different trip lengths: short-trip passenger gets massive relative detour
- **Estimated 50-70% of candidate pairs eliminated before any routing**
- Cost: ~4 Euclidean distance calculations per pair (microseconds)

### Subsumes direction filter

A separate direction/angle filter is unnecessary — opposite or perpendicular directions naturally produce large beeline detours. The beeline check is strictly more general.

---

## Strategy 2: Post-Graph Degree-2 Pruning

### Mechanism

Already implemented in `ExMasEngine.maybePrunePairRidesAfterGraph()`. Currently disabled by `pruningDistanceSavingsMinDegree = 3`. Enable by setting `minDegree = 2`.

**Flow:**
1. Generate ALL pair rides (full shareability information)
2. Build shareability graph from all pairs (complete graph)
3. Prune pairs as **extension bases** — remove pairs with insufficient distance savings
4. Pruned pairs remain in `allRides` as **pair support** for `tryExtend()` validation

### Distance savings formula

```
requiredSaving(degree) = scale × log₂(degree)
```

At degree 2: `requiredSaving = scale × 1.0 = scale`

### Threshold discussion

The `scale` parameter controls how aggressive the pruning is:

| scale | Required savings at degree 2 | Effect |
|-------|------------------------------|--------|
| 0.05 | 5% | Very gentle — removes only clearly wasteful pairs |
| 0.10 | 10% | Moderate — pair must save at least 10% vs solo |
| 0.15 | 15% | Firm — noticeable reduction in extension bases |
| 0.25 | 25% | Aggressive — significant risk of losing building blocks |

**Recommendation: `scale = 0.20`.**

This produces natural scaling expectations per degree:
- Degree 2: 20% savings — two passengers must share a fifth of their combined route
- Degree 3: 32% savings — three passengers share about a third
- Degree 4: 40% savings — four passengers share two-fifths
- Degree 5: 46% savings — five passengers share nearly half

From 1% empirical data: `scale = 0.20` keeps 7.3% of degree-2 pairs as extension bases (93% reduction). At 25% this means ~620k extension bases instead of 8.5M — massive memory relief. Pruned pairs remain in `allRides` as support for `tryExtend` validation.

### Impact on higher-degree discovery

Pruned pair (A,B) is removed as extension base but stays in `allRides`. Triple (A,B,C) can still be discovered by:
- Extending surviving pair (A,C) with B → `tryExtend` checks pair(A,B) in `allRides` ✓
- Extending surviving pair (B,C) with A → `tryExtend` checks pair(A,B) in `allRides` ✓

The triple is only lost if ALL pairs containing {A,B} subsets are pruned — unlikely with a gentle 10% threshold.

### Implementation

Change in `RunBavaria30kmDemandExtraction.configureExMas()`:
```java
exMasConfig.setPruningDistanceSavingsMinDegree(2);  // was 3
exMasConfig.setPruningDistanceSavingsLogScale(0.10); // was 0.25
```

The log scale of 0.10 means:
- Degree 2: require 10% savings
- Degree 3: require 15.8% savings
- Degree 4: require 20% savings

This is gentler than the current degree 3+ settings (which use scale=0.25 → 39.6% at degree 3).

---

## Combined Scalability Estimate for 100%

| Phase | Without strategies | With strategies | Reduction |
|-------|-------------------|-----------------|-----------|
| Pair routing calls | ~6B | ~2B | 67% (beeline filter) |
| Pair rides | ~34M | ~34M | 0% (all kept for graph) |
| Extension bases | ~34M | ~3.4M | 90% (post-graph pruning) |
| Extension candidates | ~13B | ~340M | 97% (fewer bases, existing inline distance-savings + post-hoc per-request-set pruning) |
| Peak memory | >128 GB (OOM) | ~60-80 GB | Feasible with -Xmx100g |

### Memory budget (100%, 128 GB machine, -Xmx100g)

| Component | Estimated size |
|-----------|---------------|
| Network + transit + facilities | ~5 GB |
| Population (293k agents at 25%) | ~8 GB |
| DRT requests (~245k) | ~3 GB |
| Pair rides (~34M × 500B) | ~17 GB |
| Shareability graph | ~0.5 GB |
| Extension candidates (from 3.4M bases) | ~20-30 GB |
| Connection cache | ~5 GB |
| JVM overhead + GC headroom | ~15 GB |
| **Total** | **~73-83 GB** |

Fits in 100 GB heap. The key reduction comes from having 90% fewer extension bases (strategy 2), which means 90% fewer extension candidates to `.collect()` into memory. The existing post-hoc per-request-set pruning then filters for quality.

---

## Implementation Priority

1. **Beeline pre-filter** — highest impact, simplest to implement, zero risk
2. **Post-graph degree-2 pruning** — enable existing code with config change, tune threshold

Both should be implemented before the next 25% or 100% run. Combined with `-Xmx100g`, this should make 100% feasible.

---

## Not Included (deferred)

- **Inline bounded top-N during extension:** Rejected — would hide candidates from the post-hoc percentage-based per-request-set pruning, which needs the full picture across all base rides. The post-hoc pruning is the quality filter; it cannot work correctly with pre-truncated input.
- **Streaming to disk:** High implementation complexity, only needed if strategies 1+2 with increased heap are insufficient.
- **Time-windowed batching:** Loses cross-window sharing opportunities. Only consider for >100% scenarios.
- **Direction filter:** Subsumed by beeline detour check.
