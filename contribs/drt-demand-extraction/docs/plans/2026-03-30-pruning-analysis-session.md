# Pruning Analysis & PostExtensionPruner Session (2026-03-30/31)

## Overview

Two-day session covering per-request-set extension processing (OOM fix from 2026-03-29 plan), comprehensive pruning strategy comparison analysis, PostExtensionPruner implementation, and scaling tests at 1%, 10%, 25%.

## Pruning Analysis Findings

### Value Distribution in Pooling

Analysis of the 1% Bavaria demand extraction (11,684 rides from 2,157 requests):

- **50% of request sets have negative distance savings** — pooling hurts more than it helps for half of all candidate groups
- **Value is concentrated**: top 11% of request sets hold 50% of total distance savings value; top 31% hold 90%
- This heavy-tailed distribution means aggressive pruning can remove the vast majority of rides while preserving nearly all value

### Distance-Savings Gate

The distance-savings (DS) gate filters request sets by requiring:

```
distanceSavings(set) > scale * log2(degree)
```

- This is the **main quality filter** — it removes sets where pooling is counterproductive or marginal
- The `log2(degree)` scaling accounts for higher degrees needing proportionally more savings to justify the routing complexity
- At `scale=0.15`: removes all negative-savings sets plus marginally-positive ones

### Fraction/MaxPerSet Pruning Behavior

- **Never drops request sets entirely** — only reduces the number of route variants within each set
- `MaxPerSet=1`: maximum compression per set (keep only best variant), but limits insertion ordering diversity available as extension bases for higher degrees
- This distinction matters: losing variants at degree 2 means fewer bases for degree 3, but the request set itself remains in the graph for extension enumeration

### Cascade Model: LENIENT

The Java implementation uses a **lenient cascade** model:

- When pruning removes a ride at degree d, this does NOT remove its constituent pair rides from `rideMap` or the graph
- Only affects which rides are available as **extension bases** for degree d+1
- All pair rides remain permanently in `rideMap` and the graph regardless of pruning at higher degrees
- This is critical: it means pair ride connectivity (the graph structure used for extension enumeration) is never affected by pruning

### P90 Per-Degree Percentile Filter

- Post-extension filter: within each degree, keep only rides above the 90th percentile of distance savings
- Effective because value distribution is heavy-tailed
- At `scale=0.15` with lenient cascade: **92% reduction in total rides, 100% P90 elite preservation at all degrees**

## Scaling Results

### 1% Scale (2,157 requests)

- **Input**: 11,684 total rides across all degrees
- **Output**: 2,872 rides after MaxPerSet=1 + P90 percentile pruning
- **Reduction**: 75%
- **Runtime**: ~3 minutes
- **Memory**: no issues

### 10% Scale (~21k requests)

- **Input**: 5.37M total rides
- **Output**: 304k rides after MaxPerSet=1 + P90 percentile pruning
- **Reduction**: 94%
- **Runtime**: ~80 minutes
- **Peak memory**: 19 GB
- Successfully completed all degrees

### 25% Scale (~53k requests)

- **OOM at degree 4**
- **159M candidate request sets** enumerated for degree-4 extension
- `processedSets` HashSet consumes ~18 GB before any rides are stored
- Root cause: `HashSet<String>` where keys are sorted request index strings (e.g., "1234-5678-9012-3456")
- Double enumeration: counting pass enumerates all candidate sets first, then processing pass enumerates again
- Total peak memory exceeds available heap before degree-4 processing completes

## Architecture Decisions

### PostExtensionPruner Design

Two-pass architecture:

1. **MaxPerSet pass** (first): collapse route variants within each request set to at most N
2. **Per-degree percentile pass** (second): within each degree, drop request sets below the keep-top threshold by distance savings

Rationale: MaxPerSet reduces the number of rides that the percentile pass must sort, and the percentile pass operates on the reduced set for efficiency.

### Inter-Degree vs Post-Extension Pruning

- **Inter-degree pruning** (inside extension loop): **MaxPerSet ONLY**
  - Applied after each degree's extension completes, before the next degree begins
  - Percentile pruning between degrees would cascade-kill higher degrees: removing a degree-2 ride removes it as an extension base, which removes all degree-3 rides built from it, etc.
  - MaxPerSet is safe because it preserves at least one variant per request set, maintaining graph connectivity

- **Post-extension pruning** (after all degrees complete): MaxPerSet + percentile
  - No cascade effects since extension is finished
  - This is where aggressive quality filtering happens

### Integration Points

- **`DemandExtractionListener`**: PostExtensionPruner runs between `ExMasEngine.run()` and `RidePostProcessor.process()`
- **`ExMasEngine`**: inter-degree MaxPerSet applied inside the extension loop; `pairAndSingleRides` (not `allRides`) passed to `RideExtender` to avoid re-processing single rides
- **`--max-degree` CLI flag**: caps pooling degree for large-scale runs where higher degrees are intractable

### Config Flags Added

| Flag | Default | Description |
|------|---------|-------------|
| `--post-ext-max-per-set N` | 0 (off) | MaxPerSet after extension |
| `--post-ext-keep-top X` | 1.0 (off) | Per-degree percentile keep fraction |
| `--max-degree N` | 16 | Cap pooling degree |
| `--no-pruning` | false | Disable all pruning for baseline |

## Memory Bottleneck Analysis (25% OOM)

### The Problem

At 25% scale, degree-4 extension enumerates 159M candidate request sets. The `processedSets` HashSet tracks which sets have been processed to avoid duplicates (since the same 4-request set can be reached via multiple 3-request bases).

Memory breakdown:
- Each String key: ~80 bytes (e.g., "12345-23456-34567-45678" + String object overhead)
- 159M entries: ~12.7 GB just for String objects
- HashMap Entry objects: ~5 GB additional
- Total HashSet: ~18 GB
- Plus existing rides, graph, etc.: exceeds available heap

### The Fix (Ready to Implement)

Replace `HashSet<String>` with `LongOpenHashSet` from fastutil:
- Hash sorted request indices into a single `long` value
- Each entry: 8 bytes instead of ~110 bytes (14x reduction)
- 159M entries: ~1.3 GB instead of ~18 GB
- Remove the counting pass entirely (it enumerates all sets just to log a count)
- Expected peak memory: ~3 GB for processedSets, well within heap limits

## Next Steps

1. **Implement processedSets optimization** — replace HashSet<String> with LongOpenHashSet, remove counting pass
2. **Retry 25% with full degree extension** (not capped at degree 3)
3. **Extrapolate memory/runtime to 100% scale** based on 25% results
4. **Validate pruning quality** at 10% and 25% — compare distance savings distributions before/after pruning
