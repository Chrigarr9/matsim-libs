# R1 vs R2 Canonical Ride Set Equivalence — Debug Log

**Date:** 2026-04-24  
**Branch:** `exmas-reference-fork` worktree  
**Paper claim:** C1 — R1 (ExMAS reference) and R2 (BAMAS no-pruning) produce identical canonical ride sets

---

## Goal

Verify Paper 1 claim C1: the R1 and R2 algorithms produce the same *canonical ride sets* (same
groups of passengers per degree), differing only in distance when routing differs.

The test for this is `ExMasLyonR1R2FastComparisonTest`, which:
- Loads 719 pre-computed DRT requests from `drt_requests.csv` (skipping the ~20-min mode-routing phase)
- Runs R1 (`ExMasReferenceEngine`) and R2 (`BamasEngine`) on the same requests
- Uses `FreeSpeedTravelTime` + Dijkstra via a shared `MatsimNetworkCache` (eliminates routing variation)
- Compares canonical ride sets using `GoldenAsserter.assertEquivalent` (Jaccard per degree must be 1.0)

---

## What We Found: Degree-3 Jaccard < 1.0

**R1 output:** 719 singles, 1174 pairs, 560 degree-3, 268 degree-4, 98 degree-5 = **2819 total**  
**R2 output:** 719 singles,  974 pairs, 282 degree-3,  27 degree-4,  3 degree-5 = **2005 total**

- Degree-1: Jaccard = 1.0 ✓
- Degree-2: Jaccard = 1.0 ✓ (after prior `batchPrecompute` fix in commit `9b22e90115b`)
- **Degree-3: FAIL — R2 has 16 extra canonical sets not found by R1**
- Degrees 4+: unknown (gated on degree-3 passing)

The pair counts also differ (R1: 1174 vs R2: 974), which at first looked alarming, but this is
expected: R1 (ExMAS) generates all FIFO + LIFO *orderings* of each pair, whereas R2 (BAMAS)
generates one canonical ride per pair. After canonicalisation (keep min-distance per request-set),
pair counts are identical at 974.

---

## Root Cause: Edge Direction Bug in `buildGraph`

### How `ShareabilityGraph` works

`ShareabilityGraph` stores directed edges: `addEdge(source, target, rideIndex, kind)` stores the
edge under `outgoingEdges.get(source)`. `getEdges(source, target)` only finds edges where
`source` is the key — it is **unidirectional**.

`findCommonNeighborsSorted` (used to enumerate candidate sets) is **bidirectional** — it uses a
`sortedNeighbors` adjacency list that records both directions.

### The ordering bug in `PairGenerator`

`PairGenerator` stores pair rides with `requests[]` in **departure-time order**: the passenger
who departs first is at position 0. For a LIFO ride where req_551 departs before req_0, this
gives `getRequestIndices() = [551, 0]`.

### How the bug manifested in `buildGraph`

`ExMasReferenceEngine.buildGraph()` used `getRequestIndices()` verbatim:

```java
int reqI = ride.getRequestIndices()[0];  // e.g. 551 (departs first)
int reqJ = ride.getRequestIndices()[1];  // e.g. 0
builder.addEdge(reqI, reqJ, ...);        // stores edge 551 → 0
```

For pair `{0, 551}` where req_551 departs first: edge stored as `551 → 0`.

### How this broke degree-3 extension

In `ReferenceRideExtender.getAllPairRideCombinations([0, 146], candidate=551)`:

```java
for (int req : requests) {          // requests = [0, 146]
    IntList edges = graph.getEdges(req, candidate);  // getEdges(0, 551) → EMPTY
    if (edges.isEmpty()) return null;                 // returns null → "missing pair support"
}
```

`getEdges(0, 551)` returns empty because the edge was stored as `551 → 0`, not `0 → 551`.

### Why `processedSets` made it worse

`extendRides` marks each candidate request set as processed the first time it is encountered.
Set `[0, 146, 551]` was first discovered via outer-loop base ride `[0, 146]` + candidate 551.
At that point, `getAllPairRideCombinations([0, 146], 551)` returned null (missing pair support),
so no variants were produced.

The set was then permanently marked processed. When the outer loop later encountered base ride
`[0, 551]` (requestIndices `[551, 0]`, stored under key `"[0, 551]"`) + candidate 146, the set
`[0, 146, 551]` was already in `processedSets` and silently skipped — even though this path
WOULD have succeeded:

- `getAllPairRideCombinations([551, 0], 146)`:
  - `getEdges(551, 146)` → ✓ edge 551 → 146 exists
  - `getEdges(0, 146)` → ✓ edge 0 → 146 exists (req_0 departs before req_146)

### Trace confirmation

Added `TRACE_REQUEST_SETS = Set.of("[0, 146, 551]")` to `ReferenceRideExtender`. Output:

```
TRACE REF start set [0, 146, 551] from base [0, 146] + 551
TRACE REF set [0, 146, 551] base [551, 146] + 0 missing pair support
TRACE REF set [0, 146, 551] base [0, 146] + 551 missing pair support   ← root cause
TRACE REF finish set [0, 146, 551] variants 0 kept 0
```

No trace for `base [551, 0] + 146` confirms the `processedSets` dedup caused this path to be
skipped silently (the set was already marked processed after the first failed attempt).

---

## 2026-04-24 (later) — Diagnosis revised

The original diagnosis was wrong on two counts:

### What was actually wrong with R1 (now fixed)

1. **R1 carried a beeline pre-filter in the extension path.** `ReferenceRideExtender.passesExtensionBeelineFilter` rejected candidates whose origin/destination weren't within their own detour budget of the base ride's existing stops. Vanilla ExMAS (Python `extensions.py`) has **no such filter** — it's a `main`-branch performance optimisation that snuck into the R1 port. On Lyon 1% the filter silently rejected 454/204/4 candidates at degrees 3/4/5, accounting for the bulk of the missing sets.

2. **The original directional `getEdges(req, candidate)` was correct.** Edges stored as `pickup-first → pickup-second` are *only* compatible with extensions where `req` is in the base and `candidate` is pickup-last. The unidirectional lookup is the right filter. The earlier "fix" (lo→hi normalization) collapsed direction semantics, admitted incompatible pair rides, and produced 12 spurious extras at d=4 in Kelheim and 12 at d=3 in Lyon (with inverted FIFO/LIFO interpretation in `tryExtend`).

### Fixes applied

| File | Change |
|---|---|
| `ExMasReferenceEngine.java` `buildGraph` | Reverted lo→hi normalization. Edges stored as `requests[0] → requests[1]` (pickup order). |
| `ReferenceRideExtender.java` `getAllPairRideCombinations` | Reverted lo→hi normalization. `getEdges(req, candidate)` queried directly. |
| `ReferenceRideExtender.java` (both call sites) | Removed `passesExtensionBeelineFilter` invocations. |
| `ReferenceRideExtender.java` | Removed unused `passesExtensionBeelineFilter` method, `beeline` helper, `beelineExtensionRejected` counter, and its log. |

### New regression test

`src/test/java/.../algorithm/exmas/LyonMissingTripleRegressionTest.java` reproduces the `[0, 146, 551]` Lyon scenario in isolation: three requests with hand-crafted pair-ride topology mirroring the failure (sorted indices ≠ pickup-time order, both `[0,2]` orderings present). Asserts both R1 and R2 find the triple. Companion of `KelheimMissingTripleRegressionTest`.

---

## Result on Lyon 1% after fixes

```
Degree 1: Jaccard = 1.0 ✓
Degree 2: Jaccard = 1.0 ✓
Degree 3: Jaccard = 1.0 ✓   (was 12 R1-extras / 103 R1-missing)
Degree 4: 9 R1-extras / 0 R1-missing
Degree 5: 3 R1-extras / 0 R1-missing
0 distance mismatches
```

R1 is now **complete-or-greater** vs R2 at every degree. R2 underreports 12 sets at d=4-5 — all involve request 75 or 134, suggesting a localised BAMAS-side bug rather than a pervasive issue. Examples:

```
d=4 R1-only: [49, 75, 134, 359], [75, 126, 492, 614], [75, 134, 354, 638],
             [75, 134, 359, 638], [75, 134, 359, 659], (+4 more)
d=5 R1-only: [75, 134, 354, 392, 638], [75, 134, 359, 392, 638],
             [75, 134, 359, 638, 659]
```

---

## 2026-04-25 — Final root cause: routing-cache false-unreachable

The "12 R1-only sets at d=4-5" residual claimed above turned out to be a **routing-cache contamination bug, not an algorithm bug**. The earlier "DegreeGraph unsoundness" diagnosis was wrong: DegreeGraph is sound under physical monotonicity (a feasible d-set's projection to any (d-1)-subset is also feasible). The actual chain:

### Investigation (set `{49, 75, 134, 359}`)

1. R1 reports the set at d=4 with all four `remainingBudgets > 0` → genuinely feasible.
2. By monotonicity, the projection `{75, 134, 359}` to d=3 must be feasible too. But R1 has zero variants of it at d=3 — *enumeration gap, not infeasibility*.
3. Adding `[75, 134, 359]` to `TRACE_REQUEST_SETS` showed all 8 (base × pair-edge) combinations at the only reachable extension path (base `[134, 75]` + 359) failing — 2 via the mixed-ordering check, 6 silently in `tryExtend`.
4. Adding a trace at `tryExtend`'s `seg.isReachable()` branch showed every silent rejection was the segment **`25867 → 253495` (75_o → 359_o) reported unreachable** at currentTime ≈ 25920 (timeBin 28).
5. The same segment was *reachable* at timeBin 29 — used by the d=2 pair `(75, 359)`. So the network reaches it; the cache for bin 28 was wrong.

### The cache-contamination mechanism

Two requests share origin link 25867:

| Request | requestTime | timeBin | maxTravelTime |
|---|---|---|---|
| req73 | 25434 | **28** | **884.87s** |
| req75 | 26490 | 29 | 3493.45s |

`PairGenerator` calls `batchPrecompute(reqI.origin, reqI.requestTime, candidates, reqI.maxTravelTime)`. From req73, the SSSP from link 25867 with stop-criterion 884.87s did *not* reach link 253495. `MatsimNetworkCache.batchPrecompute` then wrote `cache.put((25867, 253495, bin=28), TravelSegment.unreachable())` — even though the network has a real path, just longer than req73's bound. Later d=3 lookups in bin 28 hit this stale "unreachable" verdict and dropped the ride.

### Why monotonicity reasoning failed earlier

Monotonicity says `d+1 feasible ⇒ all d-subsets feasible`. R1's d=4 result for `{49,75,134,359}` *is* feasible, so `{75,134,359}` *must* be d=3-feasible. R1's failure to enumerate it isn't a soundness failure of the algorithm — it's a routing layer producing wrong answers (false unreachable) for legitimate segments. Once the routing layer was fixed, R1 found `{75,134,359}` at d=3 and R2 found `{49,75,134,359}` at d=4 via DegreeGraph extension — exactly as monotonicity predicts.

### Fixes (committed 2026-04-25)

| File | Change | Rationale |
|---|---|---|
| `MatsimNetworkCache.batchPrecompute` (line 309) | When `tree.getTime` is undefined, **leave the key absent** instead of writing `unreachable`. | A stop-criterion miss means "didn't reach within bound", not "no path exists". Leaving the key absent lets `getSegment` recompute via on-demand point-to-point routing when the segment is actually needed. |
| `PairGenerator.generateCandidatesForRequest` (line 189-194) | Pass `globalMaxTravelTime = max(r.maxTravelTime over all requests)` as the SSSP bound, instead of the per-request `reqI.maxTravelTime`. | Per-request bound is unsafe: the cache key `(origin, dest, timeBin)` is anonymous, so a downstream lookup may need a segment whose travel time exceeds *every* origin-X request's bound but is fine for some other passenger that traverses X. Microbenchmark: global-max bound costs 1.07x vs unbounded SSSP and reaches 99.8% of the network — virtually no fallback to SpeedyALT, vs 35.6% with the per-request bound. |

### Result on Lyon 1%

| Run | SSSP bound | Cache writes unreachable on miss? | Runtime | Equivalence |
|---|---|---|---|---|
| Pre-fix (buggy) | per-request maxTT | yes | 3.5 min | FAIL — 12 R1-only sets at d=4-5 |
| Cache fix only | per-request maxTT | no | 9.7 min | PASS |
| Cache fix + global-max bound | global-max maxTT | no | **3.0 min** | **PASS** |

R1: 10,600 rides. R2: 5,247 rides. SpeedyALT calls 21,275 → 12,848 (-40%). Cache hit rate 93.9% → 96.3%.

### Kelheim regression

`ExMasAlgorithmE2ETest` (R1/R2/R3 matrix): 3 tests passed, 442s. No regression.

---

## Audit of prior fixes (correct vs. misguided)

| Prior fix | Verdict | Reasoning |
|---|---|---|
| Removed beeline pre-filter in `ReferenceRideExtender` | ✅ Correct | Vanilla ExMAS Python reference has no such filter — port artifact. False rejections at d=3 (~454/204/4 in Lyon 1%). Independent of the cache bug. |
| Reverted lo→hi edge-direction normalization | ✅ Correct | Edges stored as `pickup-first → pickup-second` are directionally meaningful for FIFO/LIFO semantics. The lo→hi attempt collapsed direction and admitted 12 spurious extras at d=3. Independent of the cache bug. |
| Built `LyonMissingTripleRegressionTest` reproducer | ✅ Correct | Codifies the topology (sorted indices ≠ pickup-time order, both `[a,b]` orderings present) that broke the lo→hi attempt. Still useful as a regression guard. |
| ❌ Misdiagnosis: "DegreeGraph unsoundness" | Withdrawn | DegreeGraph is sound under monotonicity. R2's d=4-5 gap was downstream of the cache bug, not its cause. Do *not* gate DegreeGraph behind a config flag. |
| ❌ Performance debt: cache-fix-only at 9.7 min | Resolved | The 3x slowdown was on-demand SpeedyALT picking up segments the SSSP bound was too tight to cover. Global-max bound recovers 3.2x without correctness loss. |

---

## What Remains To Do

1. **Sync findings to `.project-memory/`** — especially correct/supersede `sssp-batchprecompute-fix.md` (the 2026-04-23 fix was incomplete; this is stage-2).
2. **Remove trace scaffolding** (`TRACE_REQUEST_SETS` etc.) in `ReferenceRideExtender` AND `BamasRideExtender`.
3. **Scale equivalence test to Lyon 10% / 25%** — confirm equivalence holds at scale (`simulation-flow.md` Block 2 C1 primary).
4. **R1 vs R2 vs R3 benchmark** — `simulation-flow.md` Block 2: ride count + wall-time per degree per scale.
5. **Update paper C1 wording** to "R1 = R2 (canonical sets identical at every degree)" with the routing-cache caveat noted in methods.

---

## Key Files

| File | Role |
|------|------|
| `src/test/java/.../ExMasLyonR1R2FastComparisonTest.java` | Fast comparison test (load CSV, run R1+R2, assert Jaccard=1.0) |
| `src/main/java/.../algorithm/exmas/ExMasReferenceEngine.java` | R1 engine — `buildGraph()` method fixed here |
| `src/main/java/.../algorithm/exmas/ReferenceRideExtender.java` | R1 extension — `getAllPairRideCombinations()` fixed here; trace logs here |
| `src/main/java/.../algorithm/graph/ShareabilityGraph.java` | Graph: `getEdges(src, tgt)` is unidirectional; `findCommonNeighborsSorted` is bidirectional |
| `src/main/java/.../algorithm/generation/PairGenerator.java` | Stores `requests[]` in departure-time order → source of edge-direction ambiguity |
| `test/output/lyon-r1r2-fast-comparison/r1_rides.csv` | R1 output from last test run |
| `test/output/lyon-r1r2-fast-comparison/r2_rides.csv` | R2 output from last test run |
| `src/test/java/.../scenarios/GoldenAsserter.java` | Computes Jaccard per degree and asserts 1.0 |
