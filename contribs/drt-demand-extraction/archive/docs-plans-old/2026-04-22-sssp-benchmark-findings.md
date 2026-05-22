# SSSP Pair Generation Benchmark — Findings

**Date:** 2026-04-22
**Scenario:** Lyon 1% / 40km radius / 3600s horizon
**Branches:** `feature/sssp-pair-generation` (SSSP) vs `feature/bnb-tightening-v1` (baseline)

## Benchmark Results

| Metric           | SSSP    | Baseline  | Ratio   |
|------------------|---------|-----------|---------|
| Pair gen time    | 726.8s  | 2,662.3s  | 3.66x   |
| Total ExMAS      | 889.5s  | 2,825.4s  | 3.18x   |
| Pairs found      | 380,977 | 369,463   | **+3.1%** |
| Total rides      | 221,458 | 201,649   | +9.8%   |
| Beeline rejected | 692,567 | 6,066,285 | —       |
| Pair throughput  | 524.2/s | 138.8/s   | 3.77x   |
| Requests         | 10,913  | 10,913    | match   |

**Output locations:**
- `outputs/lyon-sssp-40km-WITH-sssp/` (SSSP run)
- `outputs/lyon-sssp-40km-WITHOUT-sssp/` (baseline run)

## Root Cause of +3.1% Pair Discrepancy

### Summary

The pair count difference is **not** a pre-filter issue. Beeline is a lower bound on
network distance, so the beeline pre-filter has zero false positives in both versions.

The root cause is that `batchPrecompute` (LeastCostPathTree / Dijkstra) and
`computeSegment` (SpeedyALT / A* with landmarks) can find **different shortest-time
paths with different network distances** when the cost function is time-only.

### Mechanism

1. Both the Lyon and Bavaria eqasim runners bind
   `OnlyTimeDependentTravelDisutilityFactory` for car routing (the 70x slowdown fix,
   commit `c93447f6a5f`). The DRT mode falls back to car's TravelDisutilityFactory.
   So **cost = travel time only** — no distance component.

2. With time-only cost, multiple shortest-time paths can exist between the same OD pair
   (e.g., a highway at 60 km/h for 20 km vs. an arterial at 45 km/h for 15 km — both
   20 min, but 5 km apart in distance).

3. `batchPrecompute` uses **LeastCostPathTree** (Dijkstra), which explores nodes
   uniformly outward from the source. `computeSegment` uses **SpeedyALT** (A* with
   landmarks), which explores directionally toward the destination. Both find a path
   with the same minimum travel time, but they can settle on **different** equal-time
   paths with different network distances.

4. In the SSSP version, O→O and D→D segments are Dijkstra-computed (via batch);
   remaining segments (O→D, etc.) are SpeedyALT-computed (cache miss → computeSegment).
   In baseline, ALL segments are SpeedyALT.

5. The distance differences propagate into budget validation: DRT scoring uses
   `margUtilDist` (negative), so shorter distance → higher DRT score → more pairs
   survive. Dijkstra's uniform exploration appears to find systematically shorter-distance
   equal-time paths on the Lyon network, yielding +3.1% more pairs.

### Why the unit test doesn't catch it

`MatsimNetworkCacheBatchTest` uses the test constructor which binds `DijkstraFactory`
for point-to-point routing (line 721–722 of MatsimNetworkCache.java). Both batch and
point-to-point use Dijkstra → same tie-breaking → test passes. In production, the
injected router is SpeedyALT — a different algorithm with different tie-breaking.

### Impact

- **Travel times are identical** between both algorithms (the minimum cost = minimum
  time is unique). Only distances differ.
- The +3.1% extra pairs are not "wrong" — they represent valid shortest-time routes
  via a different (equally optimal) path. But the results are inconsistent with a pure
  SpeedyALT baseline.
- The +9.8% more rides is a cascade effect: more pairs → more extension candidates →
  more rides.

### Fix Options

1. **Use SpeedyALT for batch too** — after the SSSP tree populates the cache, re-route
   each segment via SpeedyALT to get consistent distances. Defeats the performance
   purpose of batching.

2. **Use LeastCostPathTree for point-to-point too** — replace SpeedyALT in
   `computeSegment` with a per-query tree. Consistent, but slower for individual
   lookups and changes all existing results.

3. **Add distance to the cost function** — makes paths unique, both algorithms agree.
   But this reintroduces the 70x slowdown (zero-gradient problem with eqasim scoring
   params set to 0).

4. **Accept the discrepancy** — document that SSSP batch introduces ~3% pair-count
   variance due to algorithm tie-breaking. Both sets of results are valid shortest-time
   routes. The speedup (3.7x) is worth the trade-off.

5. **Hybrid: use SSSP for travel-time pre-filter only, then route the surviving pairs
   via SpeedyALT for exact distances** — consistent results, still fast for the
   pre-filter step (where most candidates are rejected).

### Recommended Fix

Option 5 (hybrid) or option 4 (accept). For the dissertation, the ~3% variance is
small compared to other modeling uncertainties (mode choice calibration, synthetic
population, etc.). Document the mechanism and move on. If exact reproducibility with
the SpeedyALT baseline is needed, option 5 gives consistency without sacrificing
the speedup.

## Beeline Rejection Count Difference (692k vs 6M)

This is a **counting artifact**, not a filter difference. The SSSP version has an
additional early-out before the beeline check:

```java
// SSSP only — rejects before beeline check, not counted in beelineRejected
if (oo.getDistance() > reqI.directDistance * reqI.maxDetourFactor) continue;
```

Candidates rejected by this check are not counted in `beelineRejected`. In baseline,
the same candidates would fail the beeline check and be counted. The actual filtering
is equivalent or tighter in SSSP.
