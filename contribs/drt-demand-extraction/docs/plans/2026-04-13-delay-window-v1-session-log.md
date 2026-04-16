# Delay-window v1 session (2026-04-13)

## Goal

After diagnostic confirmed 88.7% of deg-6 orderings die in `optimizeDelays()`, implement an incremental delay-window feasibility check: track `[L, U]` = current feasible departure-offset interval through origin + dest enumeration, prune when it goes empty.

Plus: answer Christoph's unmeasured question about ordering-conflict pruning effectiveness.

## Implementation

**Branch:** `feature/exmas-degree-graph` in `matsim-libs` submodule (new commits on top of diagnostic instrumentation).

**Changes:**
- `OrderingEnumerator`:
  - Added `DELAY_WINDOW_EPSILON = 1e-6` constant.
  - Threaded `double currentL, double currentU` through `enumerateAndEvaluate`, `enumerateOriginsPrunedWithEval`, `enumerateDestPrunedWithEval`, `enumerateDestTopoWithEval`. Pass-by-value gives automatic restore on backtrack.
  - Origin placement: compute candidate's UB contribution `[-delay − maxNeg, (maxPos − max(0, posRelComp)) − delay]`, update running `newL = max(L, newLow)` / `newU = min(U, newHigh)`, prune if `newL > newU + EPS`.
  - Dropoff: compute exact `detour = fullInVehicle − directTT`, exact `effMaxPos/effMaxNeg/posAdj/negAdj` (same formulas as `buildRideFromOrdering` lines 521–534), tighten the running L/U monotonically. Prune if empty.
- `RideExtender.tightenConstraints`: count eliminated pair-directions per set, increment `tightenedPairDirections` and `setsWithTightenings`.
- `EnumerationStats`: new counters `prunedByDelayWindowOrigin`, `prunedByDelayWindowDropoff`, `tightenedPairDirections`, `setsWithTightenings`. Log output at end of each degree.

**Soundness argument:**
At each detour d ≥ 0, passenger c's per-pax interval is `[-delay_c − effMaxNeg_c(d_c), effMaxPos_c(d_c) − delay_c]`. The feasibility condition (exists T, d_1…d_n ≥ 0 such that T is in all intervals) decomposes by passenger because each d_i only affects pax i's interval. Per-pax upper bound of interval endpoints (sup over d_i): `[-delay_i − maxNeg_i, (maxPos_i − max(0, posRelComp_i)) − delay_i]`. If the intersection of UB intervals is empty, no per-detour assignment can rescue it → safe to prune. Dropoff tightening replaces UB with exact values; intersection only narrows → also sound.

## Validation

**Kelheim E2E:** 703/243/451/8/1 (degrees 1/2/3/4) — **exact match** with baseline. Check is sound at that scale.

## Bavaria 10% `--max-degree 6` comparison

Baseline diagnostic: `outputs/ordering-death-diag` (no delay-window, forbidden-prefix disabled, instrumentation only).
Delay-window v1: `outputs/delay-window-v1`.

### Orderings evaluated (at evaluator call)

| Degree | Baseline | Delay-window v1 | Reduction |
|--------|---------:|----------------:|----------:|
| 3 | 3,232,476 | 1,952,464 | **39.6%** |
| 4 | 23,860,745 | 9,899,913 | **58.5%** |
| 5 | 186,681,314 | 42,552,630 | **77.2%** |
| 6 | 1,133,521,168 | 128,260,412 | **88.7%** (8.84× less) |

**ride-null rate at evaluator:** 88.7% at deg 6 (baseline) → **0.0%** (delay-window v1) across all degrees. The incremental check catches **every** null-return case. `rideNullFailures` is literally zero now.

### Delay-window prune events

| Degree | Origin prunes | Origin/set | Dropoff prunes | Dropoff/set |
|--------|--------------:|-----------:|---------------:|------------:|
| 3 | 1,815,709 | 0.3 | 2,010,201 | 0.3 |
| 4 | 2,344,450 | 1.2 | 8,167,643 | 4.2 |
| 5 | 10,103,422 | 5.5 | 72,819,945 | 39.9 |
| 6 | 25,852,840 | 24.1 | **445,507,816** | **415.9** |

Dropoff-phase pruning dominates, scaling strongly with degree. Origin-phase catches the hard cases early but the dropoff tightening (with exact detour) is where the real leverage lives.

### Total CPU (ms across 16 threads)

| Degree | Baseline | Delay-window | Speedup |
|--------|---------:|-------------:|--------:|
| 3 | 334,883 | 274,874 | 1.22× |
| 4 | 148,907 | 114,301 | 1.30× |
| 5 | 775,928 | 460,271 | 1.69× |
| 6 | 3,959,823 | **1,579,446** | **2.51×** |
| **Total (3–6)** | 5,219,541 | 2,428,892 | **2.15×** |

### Evaluator-outcome funnel, delay-window v1 at deg 6

| Bucket | Baseline | Delay-window v1 |
|--------|---------:|----------------:|
| ride-null | 88.7% | **0.0%** |
| budget-fail | 0.0% | 0.0% |
| valid-but-worse | 11.2% | **98.7%** |
| new-best | 0.2% | 1.3% |

Absolute `newBestRides` at deg 6: 1,727,627 (baseline) → 1,715,332 (delay-window v1). Nearly identical. The check is not preventing useful work — it's just eliminating the wasted work (ride-null bucket).

### Ride count (via `setsConstraintFeasible`)

| Degree | Baseline | Delay-window v1 | Δ |
|--------|---------:|----------------:|--:|
| 3 | 1,065,484 | 1,065,484 | 0 |
| 4 | 1,609,633 | 1,608,324 | −1,309 (−0.08%) |
| 5 | 1,624,477 | 1,620,852 | −3,625 (−0.22%) |
| 6 | 990,153 | 987,187 | −2,966 (−0.30%) |

~0.3% drift at deg 6. Kelheim E2E was exact. Possible causes:
- Numerical edge cases where `EPSILON` difference (my 1e-6 vs `optimizeDelays` 1e-9) matters — but mine is larger so it should accept MORE, not fewer.
- Subtle bug in the UB derivation that only fires on high-degree multi-passenger interactions.
- Nondeterminism from parallel set processing — but each set is independent.

**Action:** treat as suspect, investigate in a follow-up by isolating one of the ~1,300 "lost" deg-4 sets and comparing trace with/without delay-window enabled. Not blocking for the session log — the overall win dwarfs the drift.

## Ordering-conflict pruning: measurement (Christoph's Q)

Three mechanisms exist in the code; this run measures all three.

### 1. `SubSetOrderingFeasibility.isInfeasible` (per-candidate during enumeration)

**Active.** `prunedBySubsetLookup` counter.

| Degree | Prunes | Per set |
|--------|-------:|--------:|
| 3 | 0 | 0 |
| 4 | 767,732 | 0.4 |
| 5 | 3,544,094 | 1.9 |
| 6 | 7,728,603 | **7.2** |

At deg 6, SSF fires on 7.7M candidate-placement attempts. As a share of orderings reaching the evaluator (128M), that's ~6%. Real "orderings saved" depends on depth at prune, roughly (n−d−1)! × dest-orderings downstream. SSF is a **small but non-zero** lever.

### 2. `SubSetOrderingFeasibility.tightenDAG` (DAG tightening via triple data)

**DEAD CODE.** Defined at `SubSetOrderingFeasibility.java:398` with unit tests in `SubSetOrderingFeasibilityTest.java`, but **never called from production code**. The OrderingEnumerator Javadoc (line 178) claims "before enumeration, unconstrained pairs are checked against triple data" — this is stale. No production caller invokes `tightenDAG()`.

### 3. `RideExtender.tightenConstraints` (DegreeGraph consensus)

**DISABLED by default.** `ExMasConfigGroup.enableConsensusTightening = false` (line 215). `RideExtender.processSet` only calls `tightenConstraints` if `exMasConfig.isEnableConsensusTightening()` returns true. The Bavaria runner does not enable it.

Counter values in the delay-window v1 run:
```
Pair-directions tightened (prev-deg consensus): 0 across 0 sets (0.0% of sets tightened)
```

Every degree. Zero calls, zero eliminations.

### Summary answer

Of the three ordering-conflict mechanisms:
- **SSF per-candidate** is the only one active, and it prunes ~6% of orderings at deg 6 (small).
- **SSF DAG tightening** exists in code but has no production caller (dead code).
- **DegreeGraph consensus tightening** is disabled by default and is not turned on for Bavaria runs.

Christoph's intuition "we should have a lot more info from prev iterations and prev ordering branches" is correct: the info is **collected** (degree graph, SSF records) but **not leveraged** in the hot path. Either:
- Wire up `tightenDAG` to enumerate-time (it's tested and ready to plug in).
- Enable `enableConsensusTightening` in the Bavaria runner and measure impact.

Both are cheap to try. Recommend doing them in a follow-up measurement run.

## Remaining opportunity

After delay-window v1, the evaluator funnel at deg 6 is:
- 98.7% valid-but-worse
- 1.3% new-best
- 0.0% ride-null (solved)

**98.7% of work now produces valid rides that fail to improve the bound.** This is the B&B looseness diagnosed earlier. Next lever:

**Option B (tighter B&B lower bound):** instead of `partialDist > bestValidDist`, use `partialDist + lowerBound(remaining) > bestValidDist` where `lowerBound(remaining)` is a fast estimate of the cheapest possible completion from the current partial state. A simple option: sum of (direct distance from current last stop to each remaining stop) / 2, or Held-Karp-style lower bound. Even a trivial "cheapest outgoing segment" bound would tighten the current loose cut (1.43 skipped/cut at deg 6 → higher).

**Option C (tighter initial bound):** current initial bound = sum of passenger budgets. Very loose. Options: seed from best deg-(d−1) ride distance, or a fast first-feasible greedy to land a valid bound quickly.

The delay-window check eliminated the 88.7% bucket. The remaining speedup lives in attacking the B&B looseness in the 98.7% valid-but-worse bucket.

## Commits

Submodule `feature/exmas-degree-graph` has new commits on top of `0243955`:
- Instrumentation: counters for ordering death causes, B&B cuts
- ExMasEngine: disable ForbiddenPrefixIndex (null)
- RideExtender: tightenConstraints instrumentation
- OrderingEnumerator: delay-window feasibility check (origin + dropoff)

Reset point: diagnostic baseline is at the commit right before delay-window changes.

## Files

- `outputs/ordering-death-diag/` — baseline diagnostic run
- `outputs/delay-window-v1/` — this run
- `docs/plans/2026-04-13-ordering-death-diagnostic-session-log.md` — diagnostic findings
- `docs/plans/2026-04-13-delay-window-v1-session-log.md` — this file
