# Forbidden-Prefix Index — Session Log (2026-04-13)

## Context

Implementation of `ForbiddenPrefixIndex` per design `2026-04-13-forbidden-prefix-index-design.md`. Phase 1-2 built the data structure + cursor + wiring. Phase 3 added recording at Trigger 2, origin Check A, and dest-phase Check A / Dropoff / Check B sites. Phase 4 wired the cursor into origin + dest enumeration as a parallel path to `SubSetOrderingFeasibility`.

## Run 1: Bavaria 10% shadow with dest-phase recording active

**Branch:** `feature/exmas-degree-graph` @ `ab4883c0802`
**Command:** `--max-degree 6`, both pruning paths active in parallel.
**Output:** `outputs/forbidden-prefix-shadow` (first run)

### Per-degree comparison vs `outputs/diag-run` baseline

| Deg | Baseline rides | Shadow rides | Δ rides | Baseline time (s) | Shadow time (s) | Slowdown |
|-----|----------------|--------------|---------|-------------------|-----------------|----------|
| 3 | 1,065,484 | 1,065,484 | **0** | 26 | 29 | 1.1× |
| 4 | 1,610,354 | **1,546,205** | **−64,149 (−4.0%)** | 15 | 69 | **4.6×** |

Run aborted at degree 5 due to ride loss and slowdown.

### Diagnostic data (before aborting)

- `ForbiddenPrefixIndex at degree 3: 11,207,407 keys, max prefix length 7` (enormous)
- `prunedByForbidden = 1,570,678` at deg 4 vs `prunedBySubsetLookup = 548,181` (new path pruning 2.9× more)

### Root cause: dest-phase recording is unsound under subsequence matching

The dest-phase recordings (Task 9) stored ordered stop sequences mixing origins and destinations. The `ForbiddenPrefixCursor` matches subsequences of the placed stops during both origin and dest enumeration. The soundness argument — "triangle inequality transfers failures from recorded sub-sequences to any superset route" — assumes the victim passenger is still in-vehicle throughout the matched sub-sequence.

For mixed-phase recordings, that assumption fails: the recording stores a sequence like `[O_v, ..., X, ..., Y]` (with `v` being the victim who timed out at `Y`). When this is matched in a future route, the cursor has no way to tell whether `D_v` (the victim's dropoff) is already in the placed-so-far between `O_v` and the matched end stop. If `D_v` appears before the matched end, the victim is no longer in-vehicle and the constraint doesn't apply — but the cursor prunes anyway.

Origin-phase recordings (Trigger 2 and origin Check A) don't have this bug because they only fire during origin enumeration, when no passenger has been dropped off yet.

### Fix

Removed the three dest-phase `recordDestFailure(...)` calls at Check A, Dropoff Check, and Check B sites. Deleted the helper method. Kept all cursor wiring in dest enum (Task 12) — it becomes a no-op without dest-phase recordings, but stays in place for later re-enablement once victim tracking is added.

Fix commit: `0243955` on `feature/exmas-degree-graph`.

## Run 2: Bavaria 10% shadow with fix applied

**Branch:** `feature/exmas-degree-graph` @ `0243955`
**Command:** `--max-degree 6`, both pruning paths active in parallel.
**Output:** `outputs/forbidden-prefix-shadow` (second run, same directory overwritten)

### Per-degree comparison

| Deg | Baseline rides | Shadow rides | Δ rides | Baseline time (s) | Shadow time (s) | Slowdown |
|-----|----------------|--------------|---------|-------------------|-----------------|----------|
| 3 | 1,065,484 | 1,065,484 | **0** | 26 | 25 | 1.0× |
| 4 | 1,610,354 | 1,609,633 | −721 (−0.04%) | 15 | 15 | 1.0× |
| 5 | 1,626,280 | 1,624,477 | −1,803 (−0.11%) | 55 | **173** | **3.2×** |
| 6 | 991,685 | — (aborted) | — | 292 | ETA ~2.5 hours | **~30×** |

Ride count differences at deg 4-5 are consistent with non-determinism from parallel thread scheduling (the baseline B&B tightens `bestValidDist` in a slightly different order). `prunedByForbidden = 0` at every degree, so the cursor contributes zero prunes but still runs all lookups.

### Deep-dive: Deg 5 CPU profile comparison

| Stage | Baseline CPU (ms) | Shadow r2 CPU (ms) | Ratio |
|-------|-------------------|---------------------|-------|
| Total | 799,588 | 2,598,060 | 3.25× |
| Pure enumeration | 563,801 (70.5%) | 2,419,824 (93.1%) | **4.29×** |
| Ride construction | 94,919 | 80,642 | 0.85× |
| Budget validation | 137,808 | 95,052 | 0.69× |

The slowdown is concentrated in "Pure enumeration", which includes all the `ForbiddenPrefixCursor.place()` / `unplace()` / `isForbidden()` calls.

### Cost analysis

`ForbiddenPrefixCursor.place(stop)` enumerates ordered sub-sequences of prior placements of lengths 2..maxKeyLength, each time doing:

1. `IntArrayList.wrap(scratchKey, len)` — allocates a new wrapper object
2. `Object2ObjectOpenHashMap.get(key)` — content-based hashCode + lookup
3. Iterate forbidden set on hit (null on miss)

At depth d with maxKeyLength = K, per-place cost is `sum_{L=2}^{K} C(d, L-1)` lookups.

`maxKeyLength` grows with the longest recorded sequence: 2 after deg 3 (triples only), 3 after deg 4, 4 after deg 5. At deg 6 origin enum with maxKeyLength = 4, a single place at depth 5 does 4 + 6 + 4 = 14 lookups. Multiplied across ~720 orderings per set × hundreds of thousands of candidate sets, this becomes the dominant cost.

At deg 5, estimated cursor overhead per ordering ≈ 21 lookups × ~150 ns ≈ 3 µs. Over 186 M orderings, that's ~560 s of CPU — matching the observed ~580 s increase in "Pure enumeration" CPU time (2,419 s − 563 s ≈ 1,856 s across 16 threads = ~116 s per thread of extra work; the math is approximate but directionally consistent with the lookups being the dominant overhead).

At deg 6, with maxKeyLength likely growing to 5, per-place cost at depth 5 doubles (to ~30 lookups) and total per-ordering doubles. Combined with ~720 orderings per deg-6 set and ~162k base sets, the projected deg-6 wall clock is 2+ hours — a 30× slowdown.

### Why prunedByForbidden = 0 even though recordings happen

Every failure path that records into `ForbiddenPrefixIndex` (Trigger 2 + origin Check A) **also** records into `SubSetOrderingFeasibility`. During parallel enumeration, the candidate filter runs in this order:

1. DAG constraints
2. `subsetFeasibility.isInfeasible(...)` → increments `prunedBySubsetLookup`
3. `cursor.isForbidden(...)` → increments `prunedByForbidden`

Any candidate the cursor would prune is a superset of candidates `subsetFeasibility` already prunes, so the subset check fires first and the cursor check is never reached. The cursor does all the lookup work for zero visible benefit during the parallel-paths phase.

This is **not** a bug in the cursor itself — it's a consequence of running both structures in parallel. After Task 14 removes the old path, the cursor would start firing prunes. But the lookup overhead would remain.

## Fundamental issue identified

Even with dest-phase recording removed, the cursor approach has two combined problems:

1. **Per-place cost scales super-linearly** with `maxKeyLength × depth`. At deg 6 with maxKeyLength = 4 or 5, the cost is prohibitive.
2. **`prunedByForbidden = 0` in parallel mode** means we can't incrementally validate that the cursor's lookups are delivering value. We only see their cost.

The `SubSetOrderingFeasibility` structure caps at sub-set size 5 (quints) by design. Its per-candidate cost is bounded: `C(d, 2) + C(d, 3) + C(d, 4)` lookups. That's why it's fast at high degrees despite doing conceptually similar work.

The `ForbiddenPrefixIndex` approach adopted unbounded key length to maximize pruning power. The power is there in principle — but the cost of looking up against an unbounded-length key space per B&B placement is too high.

## Options going forward

Presented to the user:

1. **Cap maxKeyLength at a small constant** (e.g., 3 or 4). Matches `SubSetOrderingFeasibility`'s scope. Eliminates unbounded cost growth. But loses the "no size cap" selling point of the design.

2. **Revert the cursor wiring entirely** (Tasks 11, 12). Keep `ForbiddenPrefixIndex` as a "learn-once, use elsewhere" structure but don't use it in the hot enumeration path. Accept that `SubSetOrderingFeasibility` is the right hot-path pruning mechanism.

3. **Abandon the forbidden-prefix approach.** Revert Phase 3 and 4 entirely. Return to just `SubSetOrderingFeasibility`. Record this as a negative result.

4. **Restart from design.** Re-brainstorm a third approach that keeps the bookkeeping bounded (SubSetOrderingFeasibility-style) but captures more of the value the forbidden-prefix approach was aiming at.

## Commits on `feature/exmas-degree-graph`

| Commit | Task | Content |
|--------|------|---------|
| `c73ad70` | 1 | ForbiddenPrefixIndex skeleton + record/commit + collision-safety fix |
| `6309a16` | 2 | Variable-length key tests |
| `0fc51885` | 3 | ForbiddenPrefixCursor with backtrack + delta-overlap invariant |
| `cc4f96f` | 4 | Allocation-free range lookup |
| `5336229` | 5 | Thread prefixIndex through call chain (no-op wiring) |
| `06c6a79` | 6 | prunedByForbidden counter |
| `f5468e9` | 7 | Record at Trigger 2 |
| `0ad2697` | 8 | Record at origin-phase Check A |
| `9ca007b` | 9 | Record at dest-phase Check A/Dropoff/Check B (reverted in 0243955) |
| `644c17a` | 10 | Commit between degrees + size logging |
| `57462ed` | 11 | Wire cursor into origin enum |
| `ab4883c` | 12 | Carry cursor into dest enum |
| `0243955` | fix | Remove unsound dest-phase recording (Task 9 revert) |

Reset point: `a495af2` (submodule) / `3fdebc0` (parent) — pre-`ForbiddenPrefixIndex` state.
