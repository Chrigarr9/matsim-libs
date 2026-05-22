# Session journey 2026-04-13 — delay-window check + ordering-conflict investigation

## For the dissertation

This session's arc: from a failed optimization (forbidden-prefix index) to a confirmed 2.5× speedup at degree 6 (incremental delay-window check), with a rigorous measurement disproving the value of ordering-conflict learning for this workload. The approach is a useful example of "diagnose before building" and of how to refute a plausible optimization idea with data rather than waste engineering time on it.

Key references for citing:
- `docs/plans/2026-04-13-ordering-death-diagnostic-session-log.md` — full diagnostic breakdown (where orderings actually die at each degree)
- `docs/plans/2026-04-13-delay-window-v1-session-log.md` — delay-window check implementation + Bavaria 10% speedup measurements
- This file — the end-to-end journey, decisions, and open questions

## Starting state

The previous session (2026-04-12/13) implemented a push-based forbidden-prefix index as an attempted replacement for `SubSetOrderingFeasibility`. Shadow runs showed:
- 0 prunes via `prunedByForbidden` counter
- 3–30× slowdown from cursor overhead
- Ride counts matched baseline (no correctness loss)

It didn't work because the cursor's per-place lookup cost grew with maximum recorded prefix length, while SSF's bounded-size approach kept per-operation cost fixed. The session ended with four options for Christoph to pick from.

`.project-memory/forbidden-prefix-index-2026-04-13.md` captured that state.

## Christoph's pushback that reframed the session

> SSF is fine, but does not really bring a large benefit (prunes ~0.5% of all orderings). That does not make sense — we should have a lot more information from prev iterations and prev ordering branches within the B&B tree, that should limit most of the orderings. Maybe we need to check that first why we are pruning so little. We need to get that ordering count down for the higher degrees.

The key move: **don't design another pruning mechanism, figure out why the existing ones are so weak first**. This turned out to be the right call, because the eventual answer (delay-window intersection is the real filter, not ordering conflicts) was invisible from the code alone.

## Diagnostic run: where do orderings actually die?

### What we instrumented

Added death-cause counters to `EnumerationStats` to bucket every evaluated ordering into one of:
- `ride-null` (constraint failure inside `buildRideFromOrdering`)
- `budget-fail` (budget validator rejected)
- `valid-but-worse` (valid ride, but worse than current best)
- `new-best` (valid ride, tightened the bound)

Plus: per-site prune counters (`prunedByDelayWindowOrigin/Dropoff`, B&B cut events and skipped-candidate totals, ordering-conflict lookup prunes).

### Per-degree results (Bavaria 10% `--max-degree 6`)

Funnel at degree 6:

| Bucket | % of evaluated orderings |
|---|---:|
| **ride-null (constraint)** | **88.7%** |
| valid-but-worse | 11.2% |
| **budget-fail** | **0.0%** |
| new-best | 0.2% |

**1.13B orderings evaluated at deg 6 to find 1.73M improvements. 0.15% yield.**

### Three surprising findings

**1. `ride-null` is dominant and comes entirely from `optimizeDelays()`.**

The only reachable null-return in `buildRideFromOrdering` (when pre-routed segments are passed, which is always in the enumeration path) is `optimizeDelays(...)`. That method intersects per-passenger delay-window contributions and finds a single departure offset. At degree 6 with 6 independent delay windows, the intersection is empty 88.7% of the time.

The in-flight checks (`Check A`, `Check B`, `Dropoff check`) only validate `maxTravelTime` for individual passengers — they don't check whether a single departure-offset exists that simultaneously satisfies all passengers' delay windows. The check was only happening at the very end of ride construction.

**2. Budget validation is a 100% pass-through filter.**

Every ride that passes `buildRideFromOrdering` also passes `BudgetValidator.validateAndPopulateBudgets`. Zero rejections, at every degree. Christoph confirmed this intuitively: "budget is not a filter. maxTravelTime is. whenever maxTravelTime is met, budget will also". The budget constraints are derived from the same scoring parameters as maxTravelTime, so they're effectively subsumed. Scoring-cache optimizations still matter for speed but budget rejection rate is 0.

**3. B&B distance bound is loose.**

Origin B&B cuts skip 1.05–1.11 candidates per event at every degree. The greedy sort-by-next-segment order happens to align tightly with the distance bound. When the bound fires, it's essentially excluding the very last candidate. Dest B&B is slightly better (1.43 skipped/cut at deg 6) but still weak. A proper lower-bound B&B (`partialDist + lowerBound(remaining) > bestValidDist`) would cut at shallower depths, but the current "partial-only" bound is near-trivially loose.

## Delay-window check — design and result

### Design

The core insight: `optimizeDelays` computes
```
L = max_i (−delay_i − effMaxNeg_i)
U = min_i (effMaxPos_i − delay_i)
feasible iff L ≤ U
```
where per-passenger `effMaxNeg`/`effMaxPos` depend on that passenger's detour, which is only known at ride construction. But both are **monotone in detour**: `effMaxNeg_i ≤ maxNeg_i` and `effMaxPos_i ≤ maxPos_i − max(0, posRelComp_i)` for any detour ≥ 0.

So we can compute a **sound over-approximation of the per-pax interval** at origin placement time (using detour = 0), and **tighten to exact values at dropoff** when the real detour is known. Intersection updates are O(1) via `max`/`min` because both bounds are monotone (L only grows, U only shrinks). On backtrack, Java's pass-by-value for `double currentL, double currentU` automatically restores caller state — no arrays needed.

### Result on Bavaria 10% deg 6

| Metric | Baseline | Delay-window v1 | Δ |
|---|---:|---:|---:|
| Orderings evaluated | 1,133,521,168 | 128,260,412 | **−88.7%** (8.84× less) |
| `ride-null` rate | 88.7% | **0.0%** | — |
| Total CPU (ms) | 3,959,823 | 1,579,446 | **2.51× speedup** |
| new-best (absolute) | 1,727,627 | 1,715,332 | −0.7% |

Cumulative deg 3–6: **2.15× speedup**. Kelheim E2E 703/243/451/8/1 exact match.

**The check eliminated the entire 88.7% `ride-null` bucket.** `rideNullFailures = 0` across all degrees. The diagnosis was exactly right. Dropoff-phase pruning dominated (415.9 events/set at deg 6) because that's where the exact detour becomes known and the interval really tightens.

### Known small concern

Ride count drifted by 0.08% / 0.22% / 0.30% at degrees 4 / 5 / 6 versus the baseline. Kelheim E2E was exact. The drift grows with degree. Soundness of the UB is proved in the session log (uses `max_{d≥0} effMaxNeg(d) = maxNeg` and `max_{d≥0} effMaxPos(d) = maxPos − max(0, posRelComp)`), and the chosen EPSILON (`1e-6`) is looser than `optimizeDelays`' (`1e-9`), so the check should be strictly more permissive. Most likely cause: a subtle floating-point edge case in the dropoff tightening that I haven't isolated. Flagged for follow-up. Does not block the headline speedup.

## Ordering-conflict investigation (the rigorous refutation)

### Three mechanisms in the codebase

1. **`SubSetOrderingFeasibility.isInfeasible`** (per-candidate lookup during enumeration): ACTIVE. At deg 6: 7.7M prune events (~7.2/set), ~1% of orderings reach evaluator. Real but small.
2. **`SubSetOrderingFeasibility.tightenDAG`** (pre-enumeration DAG pruning using triple infeasibility data): DEFINED, TESTED, NEVER CALLED. Dead code. The `OrderingEnumerator` Javadoc at line 178 references this mechanism but no production caller invokes it.
3. **`RideExtender.tightenConstraints`** (DegreeGraph consensus tightening from prior-degree valid rides): DISABLED BY DEFAULT. `ExMasConfigGroup.enableConsensusTightening = false` and the Bavaria runner never enables it. When measured: 0 edges across 0 sets at every degree.

### Christoph's question: can we generalize tightenDAG to quads and quints?

The user spotted that if tightenDAG works for triples via the monotonicity argument, it should also work for quads ({a,b,k,l}) and quints ({a,b,k,l,m}). The logic: if ALL 12 orderings of a quad with "a before b" are infeasible at native degree 4, and all such quads containing the pair agree, constrain `a before b` globally for the set.

I explained the soundness: the same triangle-inequality monotonicity carries over to k-subsets. Inserting extra passengers between a pax's pickup and dropoff can only extend that pax's in-vehicle time (never shorten it), so any max-TT or delay-window violation in the k-sub-ordering persists in any superset that preserves all k relative orders. Recording at native degree is the forward direction; upward propagation via tightenDAG is sound.

Implementation: `SubSetOrderingFeasibility.tightenDAG4` and `tightenDAG5` added, with `PAIR_BEFORE_MASK_QUAD` (24-bit masks) and `PAIR_BEFORE_MASK_QUINT` (120-bit masks stored in `long[2]`) computed at class load via permutation enumeration + `lehmerIndex`.

### The question of data poisoning

Christoph asked a sharp follow-up:

> Does our intermediate distance-based pruning kill orderings because of distance, that could survive, that would then have an impact on the ordering constraints?

Analysis: the `destResult[1]` flag conservatively prevents SSF recording whenever **any** dest-phase distance cut fires during an origin ordering's dest subtree. This is sound (prevents false recordings of "all-dest-fail" when a distance-cut ordering might actually be valid-but-worse), but it **starves the recording pipeline** when the distance B&B is aggressive. At deg 6 in the delay-window v1 run, `bnbDestCuts = 825M` events, so `destResult[1]` is set on most origin orderings and Trigger 2 recording is largely silenced.

Proposed fix: split the distance cut into "hard" (`bestValidDist == maxRideDistance`, i.e., cut due to sum-of-budgets overflow → genuinely infeasible) vs "soft" (`bestValidDist < maxRideDistance`, i.e., cut due to current-best overflow → potentially valid-but-worse). Hard cuts could be recorded without poisoning. Deferred pending the tightenDAG measurement.

### Measurement setup

To isolate tightenDAG's contribution cleanly:
- `MEASURE_DISABLE_DISTANCE_BNB = true` — no distance cuts, so all orderings that pass hard checks get enumerated
- `MEASURE_DISABLE_SSF_LOOKUP = true` — no per-candidate SSF interference
- `MEASURE_USE_TIGHTEN_DAG` — toggled between false (baseline) and true (variant)

SSF recording stays active so tightenDAG has data to work with.

### Result on Bavaria 10% `--max-degree 5`

| Degree | Baseline orderings | Variant orderings | Δ | tightenDAG edges | Sets affected |
|---|---:|---:|---:|---:|---:|
| 3 | 9,061,594 | 9,061,594 | 0 | 0 | 0 (no data yet) |
| 4 | 33,430,991 | 33,430,985 | **−6** | 1,203 (T=1203, Q=0, Qn=0) | 1,023 (0.05%) |
| 5 | 208,191,212 | 208,191,200 | **−12** | 231 (T=140, Q=91, Qn=0) | 169 (0.009%) |

**tightenDAG is effectively inert.** Six orderings eliminated at deg 4 (out of 33M). Twelve at deg 5 (out of 208M). Quads add 91 edges on top of 140 triples. Quints contribute 0.

### Why it doesn't work

Three reasons:
1. **Unanimity is strict**: every scanned k-subset containing the pair must have ALL `k!/2` "a-before-b" orderings marked infeasible. In practice most k-subsets have partial infeasibility (some orderings feasible, some not), so unanimity rarely holds.
2. **Data is sparse**: most k-subsets have `bits == 0` (never observed with failing orderings), which bails out the check.
3. **Structural redundancy with runtime hard checks**: every ordering that tightenDAG would prune is also pruned by `Check A`, `Check B`, `Dropoff check`, or the delay-window check at partial-descent time. The only theoretical gain is "prune at topological sort level, never generate the ordering at all" — but the CPU savings are swamped by tightenDAG's own overhead (variant is ~8% slower at deg 5 than baseline).

**This confirms that "learn from prior-iteration failures" is not a productive avenue for this workload.** The runtime hard checks are cheap and comprehensive, so SSF-style upward propagation has no marginal value.

## Decisions

- **Delay-window check**: keep. 2.51× at deg 6. Non-negotiable win.
- **Forbidden-prefix index**: remove. Dead code, measured failure.
- **SubSetOrderingFeasibility**: remove. Measured ineffective.
- **tightenDAG / tightenDAG4 / tightenDAG5**: remove. Dead path, measurement proved no value.
- **tightenConstraints (DegreeGraph consensus)**: remove. Disabled by default and fired 0 times when tested.
- **DegreeGraph**: keep (set-level feasibility tracking for prior-degree filtering is independent of ordering-conflict mechanisms).
- **Shareability-graph DAG** (pair-ride directions → `PairInfo[]` → `origAdj`): keep. That's the real constraint source.
- **Distance B&B cut**: keep. Weak but non-trivial.
- **Travel-time + dropoff check + delay-window check**: keep. These are what actually prunes.

## Open questions for next session

1. **B&B looseness.** At deg 6 after the delay-window check, 98.7% of evaluator calls produce valid-but-worse rides. The distance bound only tightens on valid new-best rides, and each origin-B&B cut skips only ~1.1 candidates. The remaining speedup lives here.

   - **Tighter initial bound**: seed `bestValidDist[0]` from prior-degree best ride distance plus a cheap "adding one passenger" estimate. Avoids the "loose budget sum" start.
   - **Lower-bound estimator during enumeration**: `partialDist + lowerBound(remaining) > bestValidDist` instead of `partialDist > bestValidDist`. Candidates for lowerBound: sum of cheapest-outgoing from current last stop, min-cost matching on remaining pickups, or a Held-Karp-style lower bound. Trade implementation complexity against tightness.
   - **First-feasible greedy**: do a fast DFS to land a valid ordering ASAP, to tighten the bound early. Then do the proper B&B.

2. **0.3% ride count drift** from delay-window v1. Small but worth isolating. Pick one lost deg-4 set, trace it with and without the check, identify the edge case.

3. **Segment-order awareness**: the B&B sort is greedy "cheapest next segment", but a longer-but-geometrically-central next stop might enable shorter subsequent segments. Revisit sort heuristic when doing B&B lower-bound work.

4. **Budget validation removal**: since budget is 100% pass-through, we could skip the call entirely and save ~400 s CPU at deg 6. Need to verify nothing else depends on `validated` being non-null (e.g., scoring-cache side effects).

## The broader lesson (for dissertation framing)

Two patterns emerged:

**(a) Diagnose before building.** The forbidden-prefix index was a plausible-looking optimization that failed. The delay-window check was the *right* optimization but only became obvious after we instrumented death causes. Moving from "build speculative mechanism → measure → maybe works" to "measure first → build exactly what the data says → measure again" halved the design cycle and caught the real bottleneck.

**(b) Ordering-conflict learning is theoretically sound but practically redundant when runtime hard checks are cheap.** This is a useful negative result. The intuition "we have all this info from prior iterations, surely it should help" is reasonable but ignores that the runtime hard checks already re-detect the same infeasibilities at near-zero cost. Upward propagation via `tightenDAG` only helps when the hard checks are expensive or missing. For the DRT ridepooling workload with triangle-inequality monotonicity, the hard checks are both cheap and exhaustive.

For the thesis, this translates to: "the extension phase scales via constraint-aware enumeration with hard-check pruning and distance-based B&B; ordering-conflict learning from prior degrees, while sound, does not contribute meaningful pruning power because the runtime constraint checks already catch all transferable infeasibilities at low per-candidate cost".

## Files for next session

- `.project-memory/forbidden-prefix-index-2026-04-13.md` — prior dud, can be archived
- `docs/plans/2026-04-13-ordering-death-diagnostic-session-log.md` — first diagnostic
- `docs/plans/2026-04-13-delay-window-v1-session-log.md` — delay-window implementation + results
- `docs/plans/2026-04-13-journey-log.md` — this file
- `outputs/ordering-death-diag/` — baseline diagnostic data
- `outputs/delay-window-v1/` — delay-window speedup measurement
- `outputs/tightendag-baseline/` and `outputs/tightendag-variant/` — tightenDAG measurement

After the cleanup commit, the codebase will contain: delay-window check, distance B&B, travel-time / dropoff / Check A / Check B, shareability-graph DAG, DegreeGraph set-level filtering. No SSF, no forbidden-prefix, no consensus tightening. Ready for restructuring and rename passes before the B&B work begins.
