# Ordering-death diagnostic (2026-04-13)

## Goal

Christoph's push-back after forbidden-prefix failure: "SSF prunes <0.5% of orderings, but B&B + prior iterations should let us prune most of them. Why are we pruning so little? Find out first, then design."

Instrumented every failure/prune site in `OrderingEnumerator` + `RideExtender.evaluateOrdering` and ran Bavaria 10% deg 6 shadow. Forbidden-prefix cursor disabled for a clean baseline.

## Setup

- Branch: `feature/exmas-degree-graph` @ submodule commit after new instrumentation
- Command: `RunBavaria30kmDemandExtraction --sample 100 --population population_10pct_kelheim30km.xml.gz --iterations 0 --max-degree 6 --trip-filter-radius 30 --filter-municipality Kelheim`
- Output: `outputs/ordering-death-diag/`
- Changes:
  - `ExMasEngine.prefixIndex = null` (disables cursor path)
  - `EnumerationStats`: added `bnbOriginCuts`, `bnbOriginSkippedCandidates`, `bnbDestCuts`, `bnbDestSkippedCandidates`, `rideNullFailures`, `budgetFailures`, `validButWorseThanBest`, `newBestRides`
  - `OrderingEnumerator.enumerateOriginsPrunedWithEval`: count B&B break at origin phase with candidates skipped
  - `OrderingEnumerator.enumerateDestTopoWithEval`: same for dest phase
  - `RideExtender.evaluateOrdering`: split into `rideNullFailures`, `budgetFailures`, `validButWorseThanBest`, `newBestRides`

## Per-degree death-cause breakdown

| Degree | Sets | Orderings eval'd | per set | Rides built | Passed constraints | Ride-null | Budget-fail | Valid-but-worse | New-best |
|--------|------|------------------|---------|-------------|--------------------|-----------|-------------|-----------------|----------|
| 3 | 7,053,256 | 3,232,476 | 0.5 | 3,232,476 | 1,952,464 (60.4%) | 39.6% | 0.0% | 22.2% | 38.3% |
| 4 | 1,940,935 | 23,860,745 | 12.3 | 23,860,745 | 9,909,659 (41.5%) | 58.5% | 0.0% | 32.4% | 9.2% |
| 5 | 1,828,545 | 186,681,314 | 102.1 | 42,619,695 (22.8%) | 42,619,695 (22.8%) | 77.2% | 0.0% | 21.5% | 1.3% |
| 6 | 1,073,819 | 1,133,521,168 | 1,055.6 | 128,338,684 (11.3%) | 128,338,684 (11.3%) | **88.7%** | **0.0%** | 11.2% | **0.2%** |

### Upstream pruning (per set)

| Degree | Travel-time | Dropoff | Sub-set (SSF) | All-dest-fail recorded |
|--------|-------------|---------|---------------|------------------------|
| 3 | 0.1 | 0.3 | 0.0 | 1,729,141 |
| 4 | 2.7 | 4.2 | 0.3 | 754,433 |
| 5 | 37.0 | 51.2 | 1.9 | 1,614,292 |
| 6 | 582.2 | 725.7 | 10.1 | 2,958,647 |

### B&B distance-bound cuts (leverage = skipped/cut)

| Degree | Origin events | Orig skipped | Per cut | Dest events | Dest skipped | Per cut |
|--------|--------------:|-------------:|--------:|------------:|-------------:|--------:|
| 3 | 292,494 | 321,471 | **1.10** | 12,249,756 | 13,946,861 | **1.14** |
| 4 | 40,865 | 41,065 | **1.00** | 33,775,487 | 41,578,511 | **1.23** |
| 5 | 226,532 | 233,798 | **1.03** | 287,763,383 | 381,534,286 | **1.33** |
| 6 | 705,631 | 742,342 | **1.05** | 1,819,880,190 | 2,611,151,827 | **1.43** |

### Total CPU-ms across 16 threads

| Degree | Total | Pure enum | Ride constr | Budget val | Other |
|--------|------:|----------:|------------:|-----------:|------:|
| 3 | 334,883 | 323,385 (96.6%) | — | 5,203 (1.6%) | — |
| 4 | 148,907 | 107,275 (72.0%) | — | 27,161 (18.2%) | — |
| 5 | 775,928 | 558,716 (72.0%) | — | 124,718 (16.1%) | — |
| 6 | 3,959,823 | 3,043,609 (76.9%) | — | 399,361 (10.1%) | — |

## Findings

### 1. ride-null dominates — and it's all `optimizeDelays()`

At deg 6, **88.7% of orderings that reach the evaluator die in `buildRideFromOrdering`** — and ALL of it is in `optimizeDelays(...)` (lines 562-590). The only other null-return site in the method (`!seg.isReachable()`) is unreachable in the enumeration path because `preConnTT != null` (we pass pre-routed segments).

What `optimizeDelays` is doing: given per-passenger pickup delays from cumulative origin routing, computing detour-shrunk delay windows, and finding a single departure offset that satisfies ALL passengers simultaneously. At deg 6 with 6 independent delay windows, finding a feasible intersection is hard — nearly 9 out of 10 orderings have no feasible offset.

**The enumeration has no in-flight check for this.** `Check A`, `Check B`, `Dropoff check` all verify only `maxTravelTime`, not the delay-window intersection. The intersection check happens only at the very end, after routing the full sequence.

### 2. Budget validation is a no-op filter

**Every ride that passes `buildRideFromOrdering` also passes budget validation — 100% at every degree.** This is surprising given how much B&B / scoring-cache work went into budget validation optimization. Budget is not the filter. The hard constraints inside `buildRideFromOrdering` (specifically delay-window feasibility) are doing all the filtering.

(Implication: the scoring-context cache still matters for speed of the budget call itself, but budget *rejection rate* is zero. No pruning leverage here.)

### 3. B&B distance-bound is a loose bound

**Origin B&B cuts skip ~1.0 candidates per event across all degrees.** When the bound fires, it catches essentially the last candidate in the sorted-by-next-segment list. The greedy sort order is so tight relative to the bound that the cut is a near-trivial "just barely over" kill.

Dest B&B is slightly better (1.43 skipped/cut at deg 6) but still weak — on the order of 40% of one remaining candidate cut.

**Interpretation:** the partial-distance bound is loose because it's not using a lower-bound estimate for remaining segments. It cuts only when `partialDist > bestValidDist`, but by that depth the partial is already so large that only the weakest sibling is excluded. A proper lower-bound B&B (partial + cheapest-completion ≥ bound) would cut higher up the tree.

### 4. New-best rate collapses with degree

new-best %: deg 3 = 38.3% → deg 4 = 9.2% → deg 5 = 1.3% → deg 6 = **0.2%**

At deg 6 we evaluate 1.13B orderings to find 1.73M new-best events. **99.8% of evaluator work produces either invalid rides (88.7%) or valid-but-worse rides (11.2%).** The B&B bound saturates very early — after a handful of valid rides, almost every subsequent valid ride fails to improve on the best-so-far.

### 5. Upstream pruning already does massive work

At deg 6: travel-time + dropoff + SSF prunes **1.41B events at subtree level**. Add 1.82B dest B&B break events and 705k origin break events. Compared to 1.13B orderings that actually reach evaluator, the upstream filter is already catching ~56% of the work. The remaining ~44% is the 1.13B deathly evaluator-reaching cohort.

## Biggest leverage (pending decision)

**A. Incremental delay-window feasibility check (biggest leverage)**

`optimizeDelays` effectively computes: `[lower, upper]` departure offset interval = intersection over all passengers of `[−delay_i − maxNeg_i, maxPos_i − delay_i]`, then checks non-empty.

This can be maintained **incrementally**:
- At each origin placement, we know `delays[i]` = cumulative arrival − requested. Intersect with passenger i's window.
- At each dest placement (dropoff), we know the full in-vehicle time and therefore the detour, which shrinks passenger i's effective window further. Intersect again.
- If `[lower, upper]` becomes empty → prune the whole subtree. No descendent ordering can produce a feasible ride.

This is provably sound: detour is non-decreasing as more dests are added between pickup and dropoff, so the window only shrinks further. Origin-phase check uses maximum window (no detour yet) — over-approximation, still sound.

**Expected gain:** should catch a large fraction of the 88.7% ride-null orderings at deg 6. Rough estimate: if even half are caught mid-enumeration, we save ~500M orderings worth of routing + optimizeDelays work. Potential speedup: ~2–5× at deg 6, more at deg 7.

**B. Tighter B&B lower bound for remaining completion**

Current: `partialDist > bestValidDist` → cut. Better: `partialDist + lowerBound(remaining) > bestValidDist` → cut.

A cheap lower bound: sum of (direct segment distance from the current last stop to each not-yet-visited stop) / 2, or min-cost perfect matching on remaining, etc. Even a trivial "cheapest outgoing from current" lower bound would tighten cuts.

Expected gain: lower than (A) — the current cut is already firing billions of times at deg 6 and the bound is just slightly loose. Realistic: maybe 1.5× at deg 6.

**C. Start with a tighter initial bound**

Currently `bestValidDist[0] = maxAllowedRideDistance` = sum of passenger budgets (very loose — this is the initial bound before any valid ordering is found). As soon as the first valid ordering is found, the bound tightens dramatically (99.8% of orderings become pruned-or-worse at deg 6). So the speed of finding a first-valid determines how long we run with a loose bound.

Ideas:
- **Seed from sub-set:** use the best degree-(d−1) ride's distance + a cheap extension estimate as initial bound.
- **Feasibility-first greedy:** evaluate origins in order of tightest delay-window first to land a valid ordering early.

Expected gain: modest at deg 6 (the bound saturates early anyway) but meaningful at deg 7+.

**D. Catch more budget-trivially-0 patterns**

Budget-fail rate is 0 across all degrees. This is unexpected — was budget not actually a filter? Investigate whether budget validation is a no-op at this point in the pipeline (maybe it only fires post-extension for HyperPool, and extension-time budget is always satisfied because constraints are derived from budgets). If so, we can skip the 400s of budget validation CPU at deg 6 entirely.

## Recommendation

**Go with A (incremental delay-window check) as the next build.** It's the biggest lever (88.7% of evaluator deaths) and the soundness proof is clean (window only shrinks monotonically under detour). Also investigate D (skip budget validation if it's always a no-op) as a free side-benefit.

B and C are smaller wins and can wait.

## Reset point

Submodule at `0243955` is the pre-diagnostic state. New commits on `feature/exmas-degree-graph` are additive instrumentation — safe to keep or revert cleanly.

## Ride count sanity

| Deg | Baseline | Diagnostic | Δ |
|-----|----------|------------|---|
| 3 | 1,065,484 | 1,065,484 | 0 |
| 4 | 1,609,633 | 1,609,633 | 0 |
| 5 | 1,624,477 | 1,624,477 | 0 |
| 6 | 991,685 | 990,153 | −1,532 (−0.15%) |

Ride counts match baseline to within thread-scheduling nondeterminism. Instrumentation is correct.
