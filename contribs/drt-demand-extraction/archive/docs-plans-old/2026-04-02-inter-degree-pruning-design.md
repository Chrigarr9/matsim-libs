# Mandatory Inter-Degree Percentile Pruning — Design

## Problem

With the pruned greedy enumeration (scale=0.15), the ExMAS extension generates
far more rides than before — 5.7M at degree 4 for 10% Bavaria. This causes OOM
at degree 5 (28.8 GB / 30 GB, GC thrash). The downstream MIP optimizer struggles
above 1M rides. We need to aggressively filter between degrees while keeping
high-quality, diverse ride options.

## Design Decision

**Mandatory inter-degree percentile pruning.** After each degree-D extension,
keep only the top `interDegreeKeepFraction` of rides by distance savings.
Survivors become both the final output for degree D and the base sets for
degree D+1.

### Key choices

- **No sqrt scaling.** The fraction applies directly. The previous sqrt scaling
  (keep 31.6% when configured for 10%) was too gentle — 31.6% of 5.7M = 1.8M,
  still OOM. Direct 10% → 570k, manageable.

- **No post-process pruning.** The inter-degree pruning IS the final curation.
  The existing `PostExtensionPruner` post-process pass becomes redundant.

- **Per-request floor (optional, default disabled).** If enabled, requests with
  fewer than `interDegreeMinRidesPerRequest` kept rides get their best rides
  rescued. Default 0 = disabled. Run first, check if needed.

- **Pragmatic, not complete.** Some good high-degree rides may be missed because
  their lower-degree ancestors were pruned. Acceptable — the Pareto front won't
  materially change.

## Config Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `interDegreeKeepFraction` | double | 0.10 | Fraction of rides to keep after each degree (1.0 = disabled) |
| `interDegreeMinRidesPerRequest` | int | 0 | Per-request floor (0 = disabled) |

## Algorithm

```
After each degree-D extension completes:

1. Compute distance savings for each ride:
   savings = 1 - ride.rideDistance / sum(request.directDistances)

2. Find threshold at keepFraction:
   threshold = savings[floor(rides.length * keepFraction)]
   Keep all rides with savings >= threshold

3. Per-request floor (if minRidesPerRequest > 0):
   For each request appearing in < minRidesPerRequest kept rides:
     Add back its best pruned rides until floor reached (or exhausted)

4. Return kept rides as:
   - Final output for degree D
   - Base sets for degree D+1 extension
```

## Expected Numbers (10% Bavaria, keepFraction=0.10)

| Degree | Raw rides | After pruning | Base sets for next |
|--------|-----------|---------------|-------------------|
| 3 | ~1.07M | ~107k | 107k |
| 4 | ~500k | ~50k | 50k |
| 5 | ~100k | ~10k | 10k |
| 6 | ~20k | ~2k | 2k |
| 7 | ~2k | ~200 | 200 |
| 8 | ~50 | ~5 | done |
| **Total** | | **~170k** | |

Memory peak: ~1M rides at degree 3 before pruning ~ 5 GB. Well within 30 GB.
MIP input: ~170k rides total — comfortably under 1M target.

## Where It Runs

In `ExMasEngine`, after each `RideExtender.extendRides()` call. Replaces the
existing optional inter-degree pruning logic (the sqrt-scaled code). Uses the
existing `PostExtensionPruner` percentile implementation — just called with the
direct fraction.

## Implementation Scope

1. Add `interDegreeKeepFraction` and `interDegreeMinRidesPerRequest` to `ExMasConfigGroup`
2. Modify `ExMasEngine` extension loop: call pruner after each degree with direct fraction
3. Remove the sqrt-scaling logic
4. Configure in `RunBavaria30kmDemandExtraction` (keepFraction=0.10)
5. Run 10% Bavaria, verify memory + ride counts + higher degrees reachable
