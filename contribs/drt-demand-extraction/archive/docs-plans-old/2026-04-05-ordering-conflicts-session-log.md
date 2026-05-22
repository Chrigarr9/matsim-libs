# Ordering Conflicts — Session Log (2026-04-05)

## Context

Continuing ExMAS ride extension optimization. Previous session implemented degree-specific graphs (branch `feature/exmas-degree-graph`). This session: design and implement ordering conflict learning to address the factorial wall on orderings per candidate at degree 7+ (9,215 orderings/set, 93.5% fail).

## Brainstorming Phase

### Key insights from brainstorming

1. **Pairwise conflicts are redundant** with the existing shareability graph. The shareability graph already encodes all pairwise directional constraints (no edge = forced direction, FIFO-only = constrained destination ordering). A separate pairwise conflict matrix adds zero information.

2. **The value is in higher-order constraints** — interactions among 3+ passengers that can't be decomposed into pairs. Example: pair(A,B) feasible, pair(A,C) feasible, but [A, B, C] infeasible because combined detour exceeds A's maxTravelTime.

3. **Unified stop-sequence model** — instead of separate origin/destination conflict stores, each request contributes 2 stops (origin = `i<<1`, destination = `(i<<1)|1`). The full route is one path of 2n stops. Conflicts are ordered subsequences of stop IDs. One data structure, one mechanism for all conflict types.

4. **Transfer by triangle inequality** — if a stop subsequence causes a passenger to time out, inserting additional stops only increases travel time (for distance; violated by time-dependent routing — see findings below).

5. **Future direction** — replace shareability graph with degree-2 DegreeGraph + pairwise OrderingConflicts for methodological consistency (deferred).

## Implementation

### Files created/modified (branch `feature/exmas-degree-graph`)

| File | Change |
|------|--------|
| `OrderingConflicts.java` | **New.** Hash storage, recording, lookup, commit, stop encoding. |
| `OrderingConflictsTest.java` | **New.** 15 unit tests for the data structure. |
| `OrderingEnumerator.java` | Origin-phase Check A (mechanism 1), conflict recording (3 triggers), conflict lookup in origin candidate selection. |
| `RideExtender.java` | Accept and pass OrderingConflicts. |
| `ExMasEngine.java` | Create, pass, commit OrderingConflicts. Currently disabled (see findings). |
| `EnumerationStats.java` | Added `prunedByConflict` counter. |

### Commits

| Commit | Description |
|--------|-------------|
| `63c87f7` | OrderingConflicts data structure + 15 unit tests |
| `5d13f78` | prunedByConflict counter in EnumerationStats |
| `03ca2a9` | Origin-phase Check A (mechanism 1) |
| `ba52ea5` | Thread OrderingConflicts through enumeration + recording + lookup |
| `a2c60a1` | Wire up in RideExtender and ExMasEngine |
| `b4ffab9` | Disable dest-phase conflict lookup (origin-only 7.6x faster) |
| `6122b21` | Disable ordering conflicts entirely (time-dependent routing false positives) |

## Experiment Results (10% Bavaria, 21k requests, degree 3-7)

### Run 1: Conflicts enabled, dest lookup ON

| Degree | Baseline time | Conflict time | Conflict prunes | Rides (baseline) | Rides (conflict) |
|--------|--------------|---------------|-----------------|-----------------|-----------------|
| 3 | 25s | 31s | 0 (learning) | 1,065,484 | 1,065,484 |
| 4 | 12s | 12s | 2,962,597 | 1,581,376 | 1,345,247 |
| 5 | 52s | 61s | 1,611,111 | 1,538,152 | 961,758 |
| 6 | 172s | 559s | 598,593 | 891,593 | 401,399 |
| 7 | 1,062s | 4,076s | 242,099 | 340,761 | 109,931 |

**Problem:** Dest-phase conflict lookup has O(2^(n+d)) subsequence cost that exceeds the cost of trying the ordering. 3.8x SLOWER at degree 7.

### Run 2: Conflicts enabled, dest lookup OFF (origin-only)

| Degree | Baseline time | Conflict time | Speedup | Rides (baseline) | Rides (conflict) | Loss |
|--------|--------------|---------------|---------|-----------------|-----------------|------|
| 3 | 25s | 31s | 0.8x | 1,065,484 | 1,065,484 | 0% |
| 4 | 12s | 9s | **1.3x** | 1,581,376 | 1,345,247 | **−14.9%** |
| 5 | 52s | 19s | **2.7x** | 1,538,152 | 961,758 | **−37.5%** |
| 6 | 172s | 40s | **4.3x** | 891,593 | 401,399 | **−55.0%** |
| 7 | 1,062s | 74s | **14.3x** | 340,761 | 109,931 | **−67.7%** |

**Problem:** 7.6x total speedup but 15-68% ride losses from time-dependent routing false positives.

### Run 3: Conflicts disabled, mechanism 1 only

| Degree | Old baseline (no mech1) | New baseline (mech1 only) | Rides | Match? |
|--------|------------------------|--------------------------|-------|--------|
| 3 | 25s | 25.5s | 1,065,484 | identical |
| 4 | 12s | 11.0s | 1,581,376 | identical |
| 5 | 52s | 43.8s | 1,538,152 | identical |
| 6 | 172s | 186.5s | 891,593 | identical |
| 7 | 1,062s | 634.4s | 340,761 | identical |

**Mechanism 1 is safe:** zero quality loss, 1.7x speedup at degree 7 (634s vs 1,062s).

## Key Findings

### 1. Mechanism 1 (origin-phase Check A) is a free win

Adding travel time checking during origin enumeration catches failures before the destination phase. If a passenger's origin-only in-vehicle time exceeds maxTravelTime, no destination ordering can help.

- **1.7x speedup at degree 7** (1,062s → 634s)
- **Zero quality loss** (ride counts match baseline exactly)
- **No data structure cost** (inline arithmetic check)

### 2. Cross-set conflict transfer is broken by time-dependent routing

The monotonicity argument ("inserting stops only increases travel time") relies on the travel time triangle inequality. With time-dependent routing, this is violated: going through an intermediate stop at a different time of day can result in SHORTER travel time on subsequent segments.

At the SET level (degree graph), this causes only 0.3% ride loss (42 out of 12,552). At the ORDERING level (conflicts), this causes 15-68% ride loss because:
- 8.2M conflicts are learned at degree 3
- 2.96M orderings are pruned at degree 4
- ~8% of prunes are false positives
- Each false prune potentially removes a valid ride from a set

### 3. Dest-phase conflict lookup is economically wrong

The conflict lookup cost during destination enumeration (O(2^(n+d)) subsequence checks) exceeds the cost of simply trying the ordering and letting Check A/B prune it. Origin-phase lookup (O(2^d) with d ≤ n) is cheap and prunes entire dest subtrees — good ROI. Dest-phase lookup prunes individual orderings at high cost — negative ROI.

### 4. The conflict lookup during origins is fast when it works

At degree 4 with origin-phase-only lookup: 2.96M orderings pruned, 9s total (vs 12s baseline). The lookup cost (57 subsequence checks per candidate) is negligible compared to the routing calls saved. The MECHANISM is sound — the MONOTONICITY ASSUMPTION is the problem.

## Current State

- **Mechanism 1 (origin-phase Check A):** ENABLED, committed, 1.7x speedup, zero quality loss
- **OrderingConflicts infrastructure:** IMPLEMENTED, tested, but DISABLED (`conflicts = null` in ExMasEngine)
- **Cross-set conflict transfer:** DISABLED pending fix for time-dependent routing false positives

## Next Steps: Fixing False Positives

Three promising directions:

### Option A: Distance-based conflicts
Use `maxRideDistance` instead of `maxTravelTime` for conflict recording. The distance triangle inequality ALWAYS holds (dist(A→B→C) ≥ dist(A→C)), unlike travel time. Conflicts would be: "this origin ordering's accumulated distance exceeds the distance savings threshold." No false positives possible.

**Trade-off:** Distance-based conflicts are less sensitive than travel-time-based (distance and time aren't perfectly correlated). Might learn fewer conflicts. But zero false positives.

### Option B: Margin-based conflicts
Only record when violation exceeds a safety margin: `inVehicle > maxTravelTime * (1 + margin)`. With margin=0.1 (10%), only record conflicts where the passenger exceeds maxTT by 10% or more. Time-dependent routing variations are typically <5%, so a 10% margin would eliminate virtually all false positives.

**Trade-off:** Learns fewer conflicts (only severe violations). But the most impactful conflicts (large violations) are the most transferable.

### Option C: Per-set validation
When a conflict match is found during lookup, quickly verify it still holds for the current set: compute the victim's origin-only time and check if it exceeds maxTT. If not, don't prune (the conflict doesn't apply here).

**Trade-off:** Requires routing calls during lookup (partially negates the speedup). But only for matches (2.96M matches vs 23.7M total orderings at degree 4 = 12.5% extra routing).
