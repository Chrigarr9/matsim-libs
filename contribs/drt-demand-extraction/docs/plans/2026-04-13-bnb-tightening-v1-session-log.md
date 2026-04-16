# B&B tightening v1 — session log 2026-04-13

## Setup

Branch: `feature/bnb-tightening-v1` in the matsim-libs submodule (off `feature/exmas-degree-graph`). 13 commits, landed sequentially via the subagent-driven-development workflow (fresh implementer + spec review + code quality review per task).

Plan: `docs/plans/2026-04-13-bnb-tightening-v1.md`
Design: `docs/plans/2026-04-13-bnb-tightening-v1-design.md`

### Commit sequence on the branch

```
1ead91dfd80  cleanup: delete OrderingConflicts (dead code)                          T2
fae778ee701  feat: thread walk-assigned parent Ride into processSet                 T3
f2f6e68129e  feat: enumerateAndEvaluateSeeded entry point (delegating stub)         T4
dcd422bda5c  test: failing test for parent-consistent origin sort bias              T5 (red)
3066c31436d  fix: make MatsimNetworkCache test hooks package-private                T5 (fix)
3e01da90524  feat: parent-consistent comparator in seeded origin DFS                T6 (green)
5c4d51298cb  test: failing test for parent-consistent dest sort bias                T7 (red)
a380fe9686c  feat: parent-consistent comparator in seeded dest DFS                  T8 (green)
88aabb46491  feat: parentSeedRidesFound counter in EnumerationStats                 T9
1fb1edab97a  test: admissibility regression test for minIn lower bound              T10
e670957864c  feat: precompute minIn and thread totalMinInRemaining through seeded DFS  T11
daabf1462c8  feat: LB-based outer B&B cut in seeded origin DFS                      T12
0bd6425b2a5  feat: LB-based outer B&B cut in seeded dest DFS                        T13
```

## Changes

**Dead code cleanup (T2):** removed `OrderingConflicts.java` + its test — leftover from the 2026-04-13 morning cleanup commit.

**Change 1 — parent-seeded DFS sort bias (T3–T9):**
- `RideExtender.extendRides` now iterates `ridesToExtend` directly in the parallel walk so the parent `Ride` is in scope for every child set (instead of iterating `uniqueBaseSets` as `int[]`). Uses the walk-assigned parent — each (k−1)-set already has exactly one ride stored (the shortest-per-set from the previous degree's `processSet`), so no extra map or subset-enumeration is needed.
- Added `enumerateAndEvaluateSeeded(...)` on `OrderingEnumerator` with seed parameters for parent origin order, parent dest order, and the child's new request.
- Duplicated `enumerateOriginsPrunedWithEval` as `enumerateOriginsSeededWithEval` and `enumerateDestTopoWithEval` as `enumerateDestTopoSeededWithEval` (with `enumerateDestPrunedSeededWithEval` as the setup shell). Each seeded variant replaces the cheapest-next-segment candidate sort with a two-level comparator: **primary** key = parent-consistent rank (0 for the next un-placed parent request or the new inserted request, 1 otherwise), **secondary** key = cheapest-next-segment distance. Depth-0 in the origin DFS uses `Integer.compare(a, b)` as the secondary key because no `prevLink` is available.
- Helper methods `nextUnplacedInSeed`, `parentConsistentRank`, `remapToLocal`, `localIndexOf` added once in `OrderingEnumerator` and reused across both origin and dest seeded variants.
- Added `parentSeedRidesFound` counter to `EnumerationStats` — incremented once per set when the first valid ride assigns to `bestRide[0]`. Used as a "did the DFS find any feasible completion for this set" diagnostic.
- The unseeded paths (`enumerateAndEvaluate`, `enumerateOriginsPrunedWithEval`, `enumerateDestPrunedWithEval`, `enumerateDestTopoWithEval`) are **byte-for-byte unchanged**. They are no longer reachable from any production caller — noted as a post-branch cleanup candidate.

**Change 2 — LB-based outer B&B cut (T10–T13):**
- `computeMinIn(n, network, requests)` helper precomputes a 2k-entry table: for each pickup stop (indices `0..k−1`) and each dropoff stop (indices `k..2k−1`), the minimum incoming segment distance from any other stop in the set. Called once per set at the top of `enumerateAndEvaluateSeeded`. Uses `network.getSegment(from, to, 0.0).getDistance()` in a 2k × (2k−1) nested loop — all cached, trivially cheap (~182 lookups at k=7).
- `totalMinInRemaining` is threaded through both seeded DFS variants (origin + dest) as a primitive `double`, maintained via `newTotalMinInRemaining = totalMinInRemaining - minIn[stop]` on descent. Java's pass-by-value for primitives gives automatic per-frame isolation — no manual save/restore on backtrack. Origin DFS subtracts `minIn[c]` (pickup index); dest DFS subtracts `minIn[c + n]` (dropoff index offset into the stop table).
- LB cut predicate `partialDist + totalMinInRemaining > bestValidDist[0]` placed after Check A at the top of each recursive call, as a candidate-independent early return on the whole subtree.
- **Origin DFS uses a `depth > 0` guard** on the cut. Rationale: at depth 0, `totalMinInRemaining` sums over all `2k` stops, but a real ride has only `2k−1` segments (the first-placed origin has no incoming segment). Applying the cut at depth 0 would over-count by `minIn[first_origin]` and risk over-pruning. Skipping the cut at depth 0 costs at most one "whole set too expensive" event per set — negligible compared to the recursive speedup.
- **Dest DFS does NOT use a depth-0 guard.** By the time the dest DFS is first entered (origin DFS at `depth == n`), all `n` origins have been placed and their `minIn` values subtracted. `totalMinInRemaining` now sums only over the `n` dropoff stops, and every one of them will be entered by an incoming segment in any completion (one O→D followed by `n−1` D→D). The LB is admissible at every dest-DFS depth including 0.
- New diagnostic counters in `EnumerationStats`: `bnbOriginLbCuts`, `bnbOriginLbSkippedCandidates`, `bnbDestLbCuts`, `bnbDestLbSkippedCandidates`. Skipped-candidates counters are declared for future use but currently stay 0 (we don't have the candidate list at the outer-cut site).

**New unit tests:**
- `ParentConsistentSortTest.java` — two test methods asserting that the first visited ordering preserves the parent's origin order and the parent's dest order respectively. Each test seeds a reversed parent ordering so the first-visited ordering under the current code differs from a cheapest-sort first visit. TDD red → green across T5/T6 (origin) and T7/T8 (dest).
- `MinInLowerBoundTest.java` — regression guard asserting `sum(minIn[all stops]) ≤ total ride distance` for every completed ordering of a synthetic 3-set. Passes in the fixture (the depth-0 weak form of the admissibility invariant). Will catch future changes that break `minIn` computation.
- Supporting: `MatsimNetworkCacheTestFixture.java` in the `network` test package — thin public bridge that forwards to the package-private `forTesting()` / `putForTesting()` hooks on `MatsimNetworkCache`, letting tests outside the `network` package construct a cache without Guice or a real router.

## Correctness verification

### Unit and integration tests

```
mvn test -Denforcer.skip=true   # full module suite
```

Result: **87 tests pass, 1 skipped.** One pre-existing failure in `upsampling.AttributeAdapterTest.testCarAvailabilityNever` is unrelated to this branch (reproduced on `feature/exmas-degree-graph` before any of this branch's commits).

### Three named E2E scenarios

```
mvn test -Dtest=ExMasDemandExtractionE2ETest  # pass
mvn test -Dtest=ExMasHyperPoolE2ETest         # pass
mvn test -Dtest=ExMasKelheimE2ETest           # pass — 703/243/451/8/1 exact match
```

Kelheim E2E exact match held at every one of the 13 commits on the branch — every implementation task had it as a mandatory gate. No drift, no ride count regression.

## Measurement — Bavaria 10%, no max-degree constraint

**Run configuration:** `--scenario-path kelheim_30km_100pct`, `--population population_10pct_kelheim30km.xml.gz`, `--sample 100`, `--iterations 0`, `--trip-filter-radius 30`, `--filter-municipality Kelheim`, `--no-predecessors`. **No `--max-degree` flag** — uses the runner's default (16), so the extension walk runs until shareability exhausts.

**Baseline for comparison:** the morning's `delay-window-v1` run (2026-04-13 morning) used `--max-degree 6` on the same population. Numbers from `docs/plans/2026-04-13-delay-window-v1-session-log.md` and `docs/plans/2026-04-13-journey-log.md`:

| Metric | Baseline (delay-window v1, deg-6 cap) |
|---|---:|
| Orderings evaluated (deg 6) | 128,260,412 |
| CPU time (deg 6 ms) | 1,579,446 |
| new-best (absolute, deg 6) | 1,715,332 |
| `ride-null` rate (deg 6) | 0.0% |
| `valid-but-worse` rate (deg 6) | 98.7% |
| Cumulative deg 3–6 CPU | 2,150,000 ms (approx) |

Prior-degree references from project memory (pre-delay-window, for structural context):
- Degree 7 under degree-graph alone: 1,062 s / 18 min
- Degree 6 under degree-graph alone: 172 s

### Results

**The walk reached degree 11 with feasible rides and stopped at degree 12 with zero feasible sets** — unprecedented. All prior runs on this scenario were capped at degree 6 (morning delay-window v1) or degree 7 (earlier degree-graph runs). Total ExMAS pipeline wall time (pair generation through degree 11 extension + output writing): **1571 s (~26 min)**. Extension-only wall time (deg 3 through deg 11): **1066 s (~17.8 min)**.

#### Per-degree enumeration metrics (Bavaria 10%, 16 threads, no max-degree)

| Deg | Sets processed | Orderings evaluated | CPU-ms (Σ 16 threads) | Wall (s) | Rides produced | Parent seed % |
|---|---:|---:|---:|---:|---:|---:|
| 3 | 7,053,256 | 1,993,755 | 2,245,174 | 143.9 | 1,061,416 | 15.0% |
| 4 | 1,931,477 | 8,888,743 | 168,564 | 12.2 | 1,602,843 | 83.0% |
| 5 | 1,819,131 | 34,931,988 | 382,081 | 26.7 | 1,617,119 | 88.9% |
| 6 | 1,068,124 | 94,585,243 | 966,822 | **64.1** | 985,319 | 92.2% |
| 7 | 424,112 | 167,999,726 | 2,050,274 | **133.2** | 399,231 | 94.1% |
| 8 | 115,152 | 171,447,552 | 3,204,547 | **219.5** | 108,175 | 93.9% |
| 9 | 17,849 | 84,104,508 | 3,606,059 | **302.2** | 16,336 | 91.5% |
| 10 | 1,255 | 27,376,480 | 1,315,463 | **147.6** | 1,146 | 91.3% |
| 11 | 27 | 605,656 | 25,676 | **16.3** | 25 | 92.6% |
| 12 | 0 | 0 | 0 | ≈0 | 0 | — |
| **Σ 3–11** | **12,430,383** | **591,933,651** | **14,064,660** | **1065.7** | — | — |

Post-extension pruning keeps 10% per degree (`pruningKeepTopFraction = 0.1`, threshold tightening per degree). Final pruned ride counts written to output: 21,171 requests generating **1,536,395 total rides** across all degrees.

#### B&B cut effectiveness (absolute event counts, summed over the set's DFS)

| Deg | Origin B&B (inner) | Origin LB (outer) | Dest B&B (inner) | Dest LB (outer) | ride-null % | valid-but-worse % | new-best % |
|---|---:|---:|---:|---:|---:|---:|---:|
| 3 | 150,674 | 3,047,312 | 1,621,901 | 4,682,585 | 0.0% | 35.4% | 64.6% |
| 4 | 21,723 | 45,711 | 3,796,009 | 11,414,225 | 0.0% | 77.7% | 22.3% |
| 5 | 51,537 | 194,206 | 17,553,245 | 47,861,529 | 0.0% | 94.0% | 6.0% |
| 6 | 60,139 | 510,468 | 55,807,064 | **154,431,409** | 0.0% | 98.6% | 1.4% |
| 7 | 34,650 | 768,739 | 123,030,716 | **431,134,177** | 0.0% | 99.7% | 0.3% |
| 8 | 8,627 | 591,518 | 198,623,752 | **976,262,035** | 0.0% | 99.9% | 0.1% |
| 9 | 622 | 189,026 | 174,449,493 | **1,137,118,561** | 0.0% | 100.0% | 0.0% |
| 10 | 2 | 18,997 | 39,522,185 | **451,645,013** | 0.0% | 100.0% | 0.0% |
| 11 | 0 | 482 | 571,621 | 5,887,957 | 0.0% | 100.0% | 0.0% |

**Dest-phase LB cut is by far the dominant pruning mechanism at high degrees.** At degree 8, 976 million LB-cut events in the dest phase — roughly 5.7× the count of orderings that actually reached the evaluator (171 M). At degree 9, over 1.1 billion dest LB cuts against 84 M evaluated orderings (13.5×). The LB cut is returning from subtrees that would otherwise explode via the factorial wall.

The origin-side LB cut is comparatively minor because the `depth > 0` guard skips the most common cut opportunity (depth 0 is visited once per set), and most of the work at high degrees happens in the dest phase where multiple passengers in-vehicle amplifies the combinatorics.

The pre-existing inner segment B&B cuts (`bnbOriginCuts`/`bnbDestCuts`) are still firing (especially dest: 2.1–3.1 skipped/cut ratio at degrees 8–11, up from 1.6 at degree 4), but their absolute event counts are dwarfed by the LB cuts.

### Comparison with morning delay-window v1 (the relevant baseline)

Only degree 6 is directly comparable — the morning run was capped at `--max-degree 6`. Numbers from `docs/plans/2026-04-13-journey-log.md` (Bavaria 10% delay-window v1, degree 6):

| Metric | Morning delay-window v1 | This run (bnb-tightening v1) | Δ |
|---|---:|---:|---:|
| Orderings evaluated (deg 6) | 128,260,412 | 94,585,243 | **−26.3%** (1.36× less) |
| CPU-ms (deg 6, Σ 16 threads) | 1,579,446 | 966,822 | **1.63× faster** |
| Wall time (deg 6, implied) | ~98.7 s | **64.1 s** | **1.54× faster** |
| new-best (deg 6, absolute) | 1,715,332 | 1,304,380 | −24.0% |
| valid-but-worse rate (deg 6) | 98.7% | 98.6% | ≈ same |

**Deg 6 speedup: ~1.5× wall-clock, ~1.6× CPU.** The B&B tightening work is a clean win at deg 6 but not a transformational one on its own — the delay-window check from the morning already eliminated the `ride-null` funnel, so most of the remaining "waste" at deg 6 is genuine valid-but-worse rides that the LB cut can only partially short-circuit.

**The much bigger story is degrees 7–11**, which were previously either slow or unreachable:

| Deg | Pre-delay-window (dropoff+passthrough) baseline | This run | Speedup vs that baseline |
|---|---:|---:|---:|
| 6 | 124 s wall | 64.1 s wall | 1.93× |
| 7 | 405 s wall | 133.2 s wall | **3.04×** |
| 8 | unreachable in practice | **219.5 s wall** | new capability |
| 9 | unreachable | **302.2 s wall** | new capability |
| 10 | unreachable | **147.6 s wall** | new capability |
| 11 | unreachable | **16.3 s wall** | new capability |

(Pre-delay-window baseline numbers are from `docs/plans/2026-04-13-journey-log.md` morning section and `.claude/memory` entries. The delay-window v1 morning run capped at deg 6, so degrees 7–11 have no intermediate comparison point — the jump from "pre-delay-window" to "bnb-tightening v1" represents the combined effect of delay-window + parent-seed + LB cut.)

**Practical impact:** the natural shareability ceiling on this Bavaria 10% scenario is degree 11 (no feasible 12-sets). The morning's `--max-degree 6` cap was an artificial stopping criterion to keep runs tractable. This branch makes the full shareability walk tractable end-to-end — degree 7 is now cheap (133 s), degrees 8–11 all complete in under 5 minutes each, and the whole pipeline from pair generation through degree 11 finishes in ~26 minutes on 16 threads. For dissertation framing, this changes the story from "we study ridepooling up to degree 6 for computational reasons" to "we study ridepooling to the natural shareability limit; computational cost is no longer the binding constraint".

### Interpretation of the funnel

The `valid-but-worse` fraction grows with degree (35.4% → 77.7% → 94.0% → 98.6% → 99.7% → 99.9% → 100.0% at degrees 3 → 9). At degrees ≥ 8, essentially every ordering that reaches the evaluator is built, routed, scored, and then discarded because it's worse than the current best. The LB cut is firing to prevent the DFS from reaching that expensive evaluator check for many subtrees, but per-set CPU still grows rapidly with degree because each successful new-best discovery buys less tightening.

`parentSeedRidesFound / setsProcessed` stays high (83–94%) at degrees 4–11, confirming that the parent-consistent seed successfully finds a valid insertion for most sets and tightens `bestValidDist[0]` early. The low 15% rate at degree 3 is expected — pair parents have trivial 2-element orderings so the "parent-consistent" branch is barely more informative than a random start, and many degree-3 sets produced by shareability-graph neighbor enumeration are simply infeasible.

The dominant remaining cost at high degrees is the 10–20% of CPU time spent in budget validation (deg 9: 10.7%; deg 10: 8.7%; deg 11: 6.6%), which is 100% pass-through on Bavaria (budget passes iff travel-time passes, per the morning journey log's finding). This is the next clean optimization target: skipping `budgetValidator.validateAndPopulateBudgets()` entirely when the scoring context cache already says "non-binding". Out of scope for this branch.

## Ride-count drift from morning's delay-window v1 (still open)

The morning session noted a 0.08 / 0.22 / 0.30% ride count drift at degrees 4 / 5 / 6 versus the pre-delay-window baseline. That drift is not in this branch's scope — it's flagged as a follow-up in `docs/plans/2026-04-13-journey-log.md`. This branch's Bavaria run will inherit the same drift (since it builds on delay-window v1). Compare final ride counts against the delay-window v1 baseline, not the pre-delay-window one.

## Post-branch cleanup candidates (noted for follow-up, not in scope)

1. **Delete the runtime-dead unseeded path.** `OrderingEnumerator.enumerateAndEvaluate` and its downstream chain (`enumerateOriginsPrunedWithEval`, `enumerateDestPrunedWithEval`, `enumerateDestTopoWithEval`) have zero production callers after T4 switched `RideExtender.processSet` to the seeded entry point. Total ~270 lines. Kept alive through this branch as a diffable reference for the seeded variants. Delete in a followup commit.

2. **Extract shared structure between origin and dest DFS.** Post-T8 the code has four near-identical DFS methods: `{origin, dest} × {unseeded, seeded}`. The only differences are the candidate comparator and some phase-specific checks. A strategy-based extraction (`CandidateComparator` interface with `CHEAPEST_SEGMENT` and `parentConsistent(seedLocal, seedLocalNewRequest)` implementations, shared DFS core) would halve the maintained surface area. Defer until after the branch is measured — not worth the refactor risk on the critical path.

3. **Migrate `candidates`/`perm`/`used` to `fastutil.IntArrayList` / primitive collections.** The hot path currently uses `ArrayList<Integer>` with autoboxing. Micro-optimization candidate, particularly relevant at degree 7+ where the factorial wall makes allocation churn visible. Tie to the extraction in (2).

4. **`uniqueBaseSets` is now redundant** — after T3 switched the parallel walk to iterate `ridesToExtend` directly, `uniqueBaseSets` is only used for its `.size()` in the log line. If the invariant `ridesToExtend.size() == uniqueBaseSets.size()` always holds (which it does because `processSet` returns exactly one ride per set), the `uniqueBaseSets` construction can be dropped entirely.

5. **0.3% delay-window ride count drift** — flagged in the morning session and still open. Not this branch's concern.

## Correctness follow-up — per-degree ride count comparison against pre-delay-window baseline (added 2026-04-14)

After the benchmark finished, Christoph asked for a direct per-degree ride count comparison against the pre-delay-window baseline from the morning's `ordering-death-diagnostic` session, to check whether the speedup comes with a soundness penalty. The drift is small but real and is the primary open issue on this branch.

### Three-way comparison

Built ride counts (before post-extension pruning), Bavaria 10%:

| Degree | Pre-delay-window (morning diagnostic, `ordering-death-diagnostic-session-log.md:142`) | Delay-window v1 (morning, reverse-engineered from output CSV × 10) | bnb-tightening v1 (this run) | Δ vs pre-delay-window |
|---|---:|---:|---:|---:|
| 3 | 1,065,484 | ~1,065,490 | 1,061,416 | **−0.38%** |
| 4 | 1,609,633 | ~1,608,330 | 1,602,843 | **−0.42%** |
| 5 | 1,624,477 | ~1,620,860 | 1,617,119 | **−0.45%** |
| 6 | 991,685 | ~988,710 | 985,319 | **−0.64%** |

Additional drift from this branch (bnb v1 vs delay-window v1 alone):

| Degree | Delay-window drift (morning) | bnb **additional** drift | Combined drift vs pre-delay-window |
|---|---:|---:|---:|
| 3 | 0.00% | **−0.38%** | −0.38% |
| 4 | −0.08% | **−0.34%** | −0.42% |
| 5 | −0.22% | **−0.23%** | −0.45% |
| 6 | −0.30% | **−0.34%** | −0.64% |

### What the drift pattern tells us

1. **Degree 3 is newly drifted.** The morning's delay-window v1 produced exactly the same degree-3 count as the pre-delay-window baseline (the delay-window check only engages from deg 4+ where actual detours matter). bnb-tightening v1 drops 407 rides (0.38%) at degree 3 — unambiguously new drift from this branch's parent-seed + LB cut work, not from inherited delay-window v1 behavior.

2. **The additional drift is roughly flat (0.23–0.38%) across degrees**, unlike the morning's delay-window drift which grew with degree (0 → 0.08 → 0.22 → 0.30%). A flat profile is consistent with "per-set probability of the LB cut over-pruning is roughly degree-independent" — what we'd expect if the cause is a single-ordering-level miss rather than a compounding combinatorial effect.

3. **Combined drift stays at ≤0.64%.** Within the tolerance that Christoph called "small but worth isolating" in the morning journey log. Not a branch-blocking soundness bug, but a real flag that needs investigation before the branch lands permanently.

### Most likely cause — time-dependent routing vs. time-0 `minIn`

`computeMinIn` (in `OrderingEnumerator.java`, added in T11) calls `network.getSegment(from, to, 0.0).getDistance()` — at departure time **0**. Bavaria is configured with the `travel_times.tsv` file, so routing is time-dependent: the router can produce different shortest-path distances at different departure times. If a real ride's segment at the DFS's actual pickup time is shorter than the same segment at time 0, then `minIn[stop]` overestimates the true minimum and the LB cut `partialDist + totalMinInRemaining > bestValidDist[0]` can fire on orderings that would actually have produced a valid ride strictly better than `bestValidDist[0]` — an unsound prune, exactly the pattern we observe.

Kelheim E2E `703/243/451/8/1` was exact because Kelheim's test network either doesn't configure time-dependent routing or the scenario is too small to produce a case where `minIn` overestimates. Bavaria 10% is the first place the issue surfaces.

### Investigation options (pending user decision)

**(a) Isolate one lost set at degree 3.** Pick a specific 3-set that delay-window v1 found feasible but bnb-tightening v1 does not. Trace the DFS path with the LB cut disabled and compare which ordering produces the first valid ride. Confirm or reject the time-dependent hypothesis by comparing `minIn[stop]` against the DFS's actual segment distance at the current time. ~1 day of focused debugging; cheapest way to know.

**(b) Quick soundness fix: epsilon slack on the LB predicate.** Change `partialDist + totalMinInRemaining > bestValidDist[0]` to `> bestValidDist[0] * (1 + LB_EPSILON)` with `LB_EPSILON` around 1e-3. Re-run Bavaria 10%. If the drift drops to zero, the cause is FP/time-dependent noise. If the drift stays, the cause is structural (bigger time variation than `LB_EPSILON`) and (c) is needed. ~30 min code + 30 min run.

**(c) Structural fix: time-conservative `minIn`.** Sample `network.getSegment(from, to, time)` at a few reference times (e.g., 6h / 12h / 18h / 21h) and take the minimum over all sampled times per stop pair. Or query the router for the minimum across its configured time bins. Makes the LB admissible under time-dependent routing. ~half day.

**Default recommendation:** (a) first, because it tells us whether (b) or (c) is the right fix. If (a) confirms time-dependence, skip (b) and go straight to (c).

### Not blocking for dissertation reporting

The 0.64% combined drift is within measurement noise for dissertation numbers (and the morning's 0.30% delay-window drift was already accepted as non-blocking). But it IS a soundness flag on the LB cut, and "the branch produces slightly fewer rides than the baseline" is the kind of finding that needs an explanation in the methods section, not a hand-wave. The investigation should happen before the branch merges permanently.

## Files referenced

- Design: `docs/plans/2026-04-13-bnb-tightening-v1-design.md`
- Plan: `docs/plans/2026-04-13-bnb-tightening-v1.md`
- Prior session logs: `docs/plans/2026-04-13-journey-log.md`, `docs/plans/2026-04-13-delay-window-v1-session-log.md`, `docs/plans/2026-04-13-ordering-death-diagnostic-session-log.md` (row 142–145 has the pre-delay-window baseline used for the drift comparison above)
- Output artifacts: `outputs/bnb-tightening-v1/run.log` (main log), `outputs/bnb-tightening-v1/drt_demand/*` (extracted demand + rides)
- Project memory: `.project-memory/bnb-tightening-v1-2026-04-13.md` (full findings, learnings, and drift investigation plan)
- Submodule branch: `feature/bnb-tightening-v1` at tip `0bd6425b2a5`
