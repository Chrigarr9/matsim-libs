# ExMAS Algorithm — Master Reference

**Purpose.** This is the main entry point for any future session working on the ExMAS ride-pooling algorithm in this repository. It covers the entire development arc from the original Python-ported baseline through the current `feature/bnb-tightening-v1` branch, synthesized from 56 design/plan/session-log files in the Dissertation root, 16 files in the `matsim-libs/contribs/drt-demand-extraction` submodule, and 27 historical 1% Bavaria benchmark runs in `matsim_scenarios/bavaria/output/`.

Use it to: (a) orient yourself before touching the algorithm, (b) find the right design doc / session log for any enhancement, (c) navigate the historical benchmark data, (d) understand the open drift issue and prior hypotheses, (e) pull dissertation material from a single map.

**Audience.** Me in three weeks, trying to remember what the degree graph is for. Co-authors picking up the branch. Dissertation writing sessions needing a named citation for "the enhancement that added X."

**Updates.** Every new algorithmic milestone or benchmark campaign should append to this file. Treat it as the spine.

---

## 1. Arc in one paragraph

The Java port started as a direct translation of the Python ExMAS algorithm and a MATSim demand-extraction pipeline that produces `DrtRequest` objects with utility budgets. The first scaling attempt on Bavaria 10% OOMed at degree 5 because the decomposition-based extension held all candidates in memory before pruning. A sequence of eight algorithmic redesigns followed — each one either **extends the reachable degree** (by reducing memory pressure or enumeration cost) or **tightens a soundness guarantee** (by replacing a heuristic with an exact constraint). The current `feature/bnb-tightening-v1` tip reaches degree 11 (the natural shareability ceiling on this scenario) in ~26 minutes end-to-end, versus baseline which OOMed well before that. Along the way we discovered that the Python reference implementation's ordering enumeration was itself unsound (`product(*E)[0]` missed 33–7800% of destination orderings), which reframed the entire project: the Java port is now the reference, and the Python code is a historical input. The one open issue today is a 0.38–0.64% per-degree ride count drift at degree 3–6 on Bavaria 10% with today's bnb-tightening-v1 code — hypothesis under investigation, with a structured milestone benchmark matrix as the planned diagnostic.

## 2. Quick facts

| Thing | Value |
|---|---|
| Current branch | `feature/bnb-tightening-v1` (submodule `matsim-libs`) |
| Current submodule tip | `466edf4e133` (as of 2026-04-14 morning) |
| Current outer-repo commit | `91b2fcf` (2026-04-14) |
| Scenario | Bavaria 30km filter around Kelheim, eqasim population |
| Benchmark sample sizes | 1% (~2k requests), 10% (~21k requests), 25%, 100% |
| Headline full-run wall time | ~26 min on Bavaria 10% end-to-end (deg 1–11) |
| Max reachable degree today | 11 (natural shareability ceiling; deg 12 has 0 feasible sets) |
| Kelheim E2E signature | 703 rides / 243 / 451 / 8 / 1 (deg 1–4) |
| Known open issue | 0.38–0.64% ride count drift deg 3–6 vs pre-delay-window baseline (Bavaria 10%) |
| Previous known drift | ~0.2% ceiling from parallelism non-determinism (forbidden-prefix session) |
| Primary language | Java 17 (contrib module) + Python (optimization pipeline, unchanged) |

## 3. Chronological timeline — dated milestones

Each row points to the canonical design / session log. "≈ 1% deg-3" is the rides-at-degree-3 number at Bavaria 1% pre-pruning when a historical run exists; it's the easiest handle to compare milestones against each other. Dates are the date the milestone landed (not the date the doc was last edited).

| # | Date | Milestone | Branch / commit | ≈ 1% deg-3 | Key source |
|---|---|---|---|---|---|
| M0 | 2025-12-03 | Initial Java port of ExMAS + pipeline | pre-`main` | — | `drt-demand-extraction/README.md`, `IMPLEMENTATION_COMPLETE.md` |
| M1 | 2026-01-26 | HyperPool Stage 1/2 integration (Phase 8, 77 tasks) | `main` | — | `drt-demand-extraction/.planning/phase-8-hyperpool.md` |
| M2 | 2026-03-26 | Bavaria scalability — beeline pre-filter + post-graph pair pruning | pre-ordering redesign | — | `docs/plans/2026-03-26-exmas-scalability-design.md` |
| M3 | 2026-03-27 | Bavaria demand extraction session — eqasim integration, income fix, vehicle ID fix | — | — | `docs/plans/2026-03-27-bavaria-demand-extraction-session.md` |
| M4 | 2026-03-29 | Per-request-set extension processing (fixes OOM at deg 5) | pre-ordering | — | `docs/plans/2026-03-29-exmas-extension-scalability.md` |
| M5 | 2026-03-31 | **Ordering-based redesign** — topological sort enumeration replaces decomposition | `feature/exmas-traceable` | 168 | `docs/plans/2026-03-31-ordering-based-extension.md`, `.project-memory/ordering-based-extension-2026-04.md` |
| M6 | 2026-04-01 | Parallel ride extension via ForkJoinPool | `feature/exmas-traceable` | 168 | `docs/plans/2026-04-01-extension-parallelization.md` |
| M7 | 2026-04-01 | Informed ordering search / pruned greedy enumeration | `feature/exmas-traceable` | 167 | `docs/plans/2026-04-01-informed-ordering-search.md` |
| M8 | 2026-04-02 | Pruned greedy enumeration + inter-degree 10% pruning | `feature/exmas-traceable` | **809** | `docs/plans/2026-04-02-pruned-greedy-extension.md`, `docs/plans/2026-04-02-inter-degree-pruning-design.md` |
| M9 | 2026-04-02 | Inline eval + tighten-on-valid (distance B&B per set) | `feature/exmas-traceable` | 742 | `docs/plans/2026-04-02-branch-and-bound-enumeration.md` |
| M10 | 2026-04-03 | Scoring context cache (budget validation 2.6–3.2× faster) | `feature/exmas-traceable` | 809 | `docs/plans/2026-04-03-scoring-context-cache.md` |
| M11 | 2026-04-03 | Per-passenger travel time pruning (Check A) | `feature/exmas-traceable` (tip `d8bc90a`) | 809 | `docs/plans/2026-04-03-per-passenger-pruning-plan.md` |
| M12 | 2026-04-04 | **Degree-specific graph + ordering consensus bitmask** | `feature/exmas-degree-graph` | 809 | `docs/plans/2026-04-04-degree-specific-graph-design.md` |
| M13 | 2026-04-05 | Ordering conflicts / Check A origin-phase trigger | `feature/exmas-degree-graph` | — | `docs/plans/2026-04-05-ordering-conflicts-design.md` |
| M14 | 2026-04-06 | Dropoff check + routing passthrough (93.5% failure capture) | `feature/exmas-degree-graph` | — | `docs/plans/2026-04-06-subset-ordering-lookup-plan.md` (dropoff section) |
| M15 | 2026-04-13 AM | Forbidden-prefix index — **built, benchmarked, disabled** | `feature/exmas-degree-graph` (shelved) | — | `docs/plans/2026-04-13-forbidden-prefix-index-session-log.md` |
| M16 | 2026-04-13 AM | Ordering death diagnostic — instrumentation proving where orderings die | `feature/exmas-degree-graph` | — | `docs/plans/2026-04-13-ordering-death-diagnostic-session-log.md` |
| M17 | 2026-04-13 AM | **Delay-window v1** — incremental per-passenger delay feasibility check | `feature/exmas-degree-graph` (tip `a94ada93`) | — | `docs/plans/2026-04-13-delay-window-v1-session-log.md` |
| M18 | 2026-04-13 PM | **B&B tightening v1** — parent-seeded DFS + LB cut | `feature/bnb-tightening-v1` | — | `docs/plans/2026-04-13-bnb-tightening-v1-design.md`, `docs/plans/2026-04-13-bnb-tightening-v1-session-log.md`, `.project-memory/bnb-tightening-v1-2026-04-13.md` |
| M19 | 2026-04-14 AM | Budget refactor — defer validation to post-extension batch | `feature/bnb-tightening-v1` (tip `466edf4e`) | — | `docs/plans/2026-04-14-budget-refactor-and-pivot-session-log.md` (this session) |
| M20 | planned | Milestone benchmark matrix (1% Bavaria, single-threaded) | — | — | this document § 10 |

**Notes on the "≈ 1% deg-3" column.** The numbers are raw rides produced by the extension DFS at degree 3 on Bavaria 1% *before* the inter-degree 10% pruning step. The big jump from 168 rides (M5–M7, ordering-based) to 809 rides (M8, pruned greedy) is not drift — it's a bug fix. The M5–M7 runs were still using an early ordering enumerator that under-counted valid orderings because it only evaluated the first result of `product(*E)[0]` — the same bug we inherited from Python. M8 added pruned greedy enumeration with full coverage. The correct baseline for all subsequent rows is therefore 809, not 168.

**M9 (inline eval + tighten-on-valid) showed 742 vs 809 — an 8.3% drop.** This is noteworthy: it's historical evidence that per-set distance B&B tightening *does* remove rides, and the drift was accepted at the time as a quality-preserving optimization (top rides by distance are still kept, and the dropped rides are provably dominated). This is the same class of optimization as today's M18 LB cut, which shows 0.38–0.64% drift at 10%. The per-degree drift signature is similar in shape, just different in magnitude. **Implication for the current drift investigation:** the LB cut may be admissible-by-design and the drift may be "correct quality-preserving pruning" that just wasn't calibrated against the pre-delay-window baseline. Worth checking whether the dropped rides are all at the bottom of the distance distribution (would prove quality preservation) or spread across the distribution (would indicate a soundness bug).

## 4. Logical architecture — how the enhancements compose

The pieces in the order they depend on each other, not the order they were built. This is the picture to have in mind when reading any single design doc.

```
DEMAND PIPELINE (unchanged across all M2+ milestones)
  MATSim population
    → mode routing cache (per person)
    → trip chain identifier (subtour combinatorics)
    → budget calculator (best baseline utility vs DRT utility)
    → DrtRequest[] with embedded utility budget

SHAREABILITY GRAPH (phases 1–3 of ExMAS)
  singles (degree 1)
    → pair generator (FIFO + LIFO candidates from pairwise routing)
    → pair ride construction with BudgetValidator
    → ShareabilityGraph (edges = feasible pairs)
    → optional pair-ride base pruning (distance savings gate)

EXTENSION LOOP (phase 4 of ExMAS)
  base sets = pair rides (degree 2)
  for degree = 3 .. max_degree:
    DegreeGraph propagation (M12)           ← candidate reduction
    RideExtender.extendRides()              ← parallel over base sets
      per base set:
        OrderingEnumerator.enumerateAndEvaluate[Seeded]  ← DFS
          origin DFS                        ← M5 ordering-based
            sort candidates                 ← M8 pruned greedy | M18 parent-seed
            Check A (per-passenger TT)     ← M11 travel time pruning
            Check (delay window)            ← M17 delay window v1
            LB cut (minIn)                  ← M18 B&B tightening v1
            dropoff check                   ← M14
            dest DFS
              ... (same checks, dest phase)
              evaluate ordering → build Ride → [budget validate] → track best
                                              ↑
                               M19 budget refactor (skip → batch)
    inter-degree 10% pruning                 ← M8
  end

POST-EXTENSION (phase 5 of ExMAS)
  populateBudgetsBatch                       ← M19 (new, runs if deferred)
  RidePostProcessor.computeMaxCosts          ← existing (reads remainingBudgets)
  shapley values (optional)
  predecessor/successor graph (optional)
  CSV writers → Python optimization pipeline
```

**Design invariants** that every milestone preserves (or breaks by design, explicitly):
- **Best-ride-per-set** is the algorithm's output contract. Pruning is allowed to discard *non-best* orderings but must never discard the best-by-distance for any set.
- **Budget admissibility** — any ride whose per-passenger budget goes negative must be rejected. On Bavaria, this never fires because max-travel-time is tighter, so budget validation became optimizable (M19).
- **Determinism modulo parallelism** — two single-threaded runs must produce identical rides. Parallel runs have a known ~0.2% noise floor from `ForkJoinPool` work-stealing tightening `bestValidDist[0]` in different orders across sets (documented in the forbidden-prefix session, M15). This is treated as acceptable noise as long as the *best* ride per set is found consistently.

## 5. Milestones — detailed

Each entry is a mini-memo you can cold-read. Includes: **what problem**, **mechanism**, **code entry points**, **measured impact**, **primary sources**, **gotchas**.

### M5 — Ordering-based redesign (2026-03-31)

**Problem.** The original Python ExMAS enumerated orderings via `itertools.product(*E)[0]`, which the Java port faithfully inherited. Investigation revealed this only evaluates the *first element* of the Cartesian product — missing 33–7800% of valid destination orderings. The decomposition-based extension (base ride + insert at position) was also ordering-dependent on the base ride and missed valid orderings due to top-1-per-set pruning at lower degrees.

**Mechanism.** Replace decomposition with topological sort from pairwise FIFO/LIFO constraints extracted directly from the shareability graph. For each candidate set, enumerate all valid (origin, destination) orderings, route each one (all segments are cache hits from pair rides), validate per-passenger constraints. This produces the *correct* set of valid orderings, not just a subset.

**Key files.**
- `OrderingEnumerator.java` — DFS enumeration core
- `RideExtender.java` — parallel driver over base sets
- `.project-memory/ordering-based-extension-2026-04.md` — deep investigation notes

**Measured impact.** Correctness fix with a runtime penalty at low degrees (enumeration is exhaustive). This motivated M6–M8.

**Gotcha.** The 1% deg-3 count jumps from 168 (M5) to 809 (M8) once pruned greedy enumeration fills in the missing orderings. Any historical comparison that crosses this boundary needs the caveat.

---

### M6–M7 — Parallelization + informed ordering search (2026-04-01)

**Problem.** Exhaustive topological enumeration at deg 5 takes ~78 min single-threaded.

**Mechanism.** ForkJoinPool over base sets (each set independent). Informed ordering strategies that sort candidates by cheapest-next-segment to find valid orderings early in the DFS.

**Impact.** 3.1× at deg 4, 7.8× at deg 3, deg 5 now feasible in single-digit minutes.

**Primary source.** `docs/plans/2026-04-01-extension-parallelization.md`, `docs/plans/2026-04-01-informed-ordering-search.md`.

---

### M8 — Pruned greedy + inter-degree pruning (2026-04-02)

**Problem.** Exhaustive DFS is still too slow and memory-heavy for deg 5+ on Bavaria 10%.

**Mechanism.**
1. **Pruned greedy enumeration.** Sort candidates by distance at each DFS depth. Track cumulative distance. Break when cumulative exceeds `bestValidDist[0]`. Provably complete: any ordering exceeding the threshold cannot be best.
2. **Inter-degree 10% pruning.** After each degree's extension, keep top 10% by distance savings as base sets for the next degree. Mandatory. Bounds memory combinatorially.

**Measured impact.** 9.4–10.5× speedup at deg 3–4. Inter-degree pruning solves the deg-5 OOM.

**1% reference counts:** deg 3 = 809, deg 4 = 313, deg 5 = 75, deg 6 = 12, deg 7 = 1, deg 8 = 0. **These are the correct baseline reference counts for all subsequent milestones.**

**Primary source.** `docs/plans/2026-04-02-pruned-greedy-extension.md`, `docs/plans/2026-04-02-inter-degree-pruning-design.md`, `docs/plans/2026-04-02-inter-degree-pruning-plan.md`.

---

### M9 — Inline eval + tighten-on-valid (2026-04-02)

**Problem.** Pruned greedy computes all valid orderings and selects the best afterward. We can tighten the upper bound *during* the DFS as soon as a valid ride is built.

**Mechanism.** Replace "collect all valid orderings → select best" with inline eval: as each ordering is completed, immediately construct the ride, validate, and update `bestValidDist[0]`. Subsequent DFS branches use the tightened bound for pruning.

**Measured impact at Bavaria 1%** (from historical run `demand-extraction-1pct-branch-bound`): deg 3 = 742, deg 4 = 263, deg 5 = 58, deg 6 = 9. **That's −8.3% / −16.0% / −22.7% / −25% vs M8 reference counts.** At the time this was considered quality-preserving (the dropped rides were assumed to be all at the bottom of the distance distribution), but the drop wasn't rigorously verified ride-by-ride. This is the first known instance of "B&B drift" in the project's history.

**Open question worth revisiting.** Are those 8–25% dropped rides actually all dominated-by-best, or is there a soundness bug in the per-set B&B? If the latter, today's M18 LB cut may be inheriting the bug.

**Primary source.** `docs/plans/2026-04-02-branch-and-bound-enumeration.md`.

---

### M10–M11 — Scoring context cache + per-passenger TT pruning (2026-04-03)

**Problem.** `DrtTripScorer.scoreWithActivityResolution` parses the person's entire plan + creates 8 objects per call. At degree 6: 552 plan parsings per set (6 passengers × 92 tries). All per-request data is constant across orderings.

**Mechanism.**
1. **Scoring context cache.** Pre-compute per-request `DrtRequest.ScoringContext` once at the start of extension; store on the request; reuse in every budget validation. Budget validation goes from ~1ms to ~0.05ms.
2. **Travel time pruning (Check A).** Track per-passenger arrival times during DFS. Prune any partial ordering where `currentTime - pickupTimes[p] > maxTravelTime[p]`. Catches 98.2% of failing orderings early.

**Measured impact.** 2.6–3.2× speedup at deg 4–6 combined. 4.8× at deg 5 specifically (from the scoring-cache-and-pruning session).

**Primary source.** `docs/plans/2026-04-03-scoring-context-cache.md`, `docs/plans/2026-04-03-per-passenger-pruning-plan.md`, `docs/plans/2026-04-03-scoring-cache-and-pruning-session-log.md`.

---

### M12 — Degree-specific graph (2026-04-04)

**Problem.** Candidate set generation at each degree is expensive. Many candidate sets will produce zero feasible rides.

**Mechanism.** After each degree's extension completes, build a `DegreeGraph` from the resulting rides. The graph records which (degree-k) subsets can actually produce rides, which prunes candidate enumeration at degree k+1. Ordering consensus via `long[]` bitmask: if a sub-ordering appears in every parent degree's rides, it must appear in any degree-k child as well.

**Measured impact (Bavaria 10%).** 82% candidate reduction at deg 4, 93.5% at deg 5. Factorial wall on orderings/set reduced: deg 5→6→7→8 goes 103 → 934 → 9,215 → ~100k. Degree 6 in 3 min (was ~1h), degree 7 in 18 min (was unreachable). Total deg 3–5 wall: 460s → 99s = **4.6× faster**.

**Primary source.** `docs/plans/2026-04-04-degree-specific-graph-design.md`, `docs/plans/2026-04-04-degree-specific-graph-implementation.md`, `docs/plans/2026-04-04-degree-specific-graph-session-log.md`.

---

### M13–M14 — Ordering conflicts + dropoff check (2026-04-05 to 2026-04-06)

**Problem.** DegreeGraph catches "can this set produce a ride?" but doesn't catch "can this specific ordering work?" Orderings that fail at the dest phase are expensive because origins have already been routed.

**Mechanism.**
- **Ordering conflicts (M13).** Record sub-sequences that failed in one set; prune them in other sets. Partially worked — Check A origin-phase trigger gave 1.7× speedup at deg 7. Cross-set conflicts were too sparse to matter.
- **Dropoff check (M14).** Validate dropoff feasibility before committing to a dest ordering. Catches 93.5% of dest-phase failures that would otherwise waste routing calls.

**Measured impact.** Combined with routing passthrough: 1.5× at deg 6 (187s → 124s), 1.6× at deg 7 (634s → 405s), zero quality loss.

**Primary source.** `docs/plans/2026-04-05-ordering-conflicts-design.md`, `docs/plans/2026-04-05-ordering-conflicts-session-log.md`, `docs/plans/2026-04-06-subset-ordering-lookup-plan.md`.

**Important learning.** Ordering conflicts were almost abandoned because cross-set conflict transfer was too sparse (0.00002% of 3-tuple space covered). The dropoff check direction proved more productive. Captured in the session log as a "diagnose before building" lesson — the profiling that motivated the pivot is in `docs/plans/2026-04-13-ordering-death-diagnostic-session-log.md`.

---

### M15 — Forbidden-prefix index (2026-04-13 AM) — built, benchmarked, **disabled**

**Problem.** Push-based cross-set subsequence learning: when an ordering fails at position k, record the prefix `[r_1...r_k]` as forbidden for any set containing that prefix.

**Mechanism.** `ForbiddenPrefixIndex` + per-thread `Cursor`. Record at 3 triggers: Check A failure, dest-phase failure, end-of-DFS-without-valid. Intersect during enumeration.

**Outcome.** Prune rate <0.2% of orderings across all degrees. Cursor overhead 3.2–30× slowdown in the hot path. **Net: pure loss.** Retired same day. The decision memo (`.project-memory/forbidden-prefix-index-2026-04-13.md`) lays out why: the delay-window check (M17) and dropoff check (M14) already catch what this index would prune.

**Why this is in the timeline.** It's a negative result worth citing in the dissertation — shows that not every soundness-preserving optimization is worth the runtime cost. And it generated the profiling that led directly to M17.

**Primary source.** `docs/plans/2026-04-13-forbidden-prefix-index-design.md`, `docs/plans/2026-04-13-forbidden-prefix-index-plan.md`, `docs/plans/2026-04-13-forbidden-prefix-index-session-log.md`, `.project-memory/forbidden-prefix-index-2026-04-13.md`.

---

### M17 — Delay-window v1 (2026-04-13 AM)

**Problem.** The delay-window (maximum per-passenger delay tolerable before scoring drops below baseline utility) is an admissible upper bound on the DFS. It can be checked incrementally as soon as a passenger is picked up.

**Mechanism.** Track per-passenger delay-used vs delay-budget during DFS descent. Prune any branch where `delay_used > delay_budget` for any placed passenger. Same shape as Check A (M11) but for delay, not travel time.

**Measured impact.** Bavaria 10% deg 6: 88.7% ordering reduction (1,133M → 128M evaluations). Zero ride-null rate after the check (meaning every ordering that reaches the end of DFS produces a valid ride).

**Known drift.** 0.08% / 0.22% / 0.30% drift at degrees 4 / 5 / 6 versus the pre-delay-window baseline. The drift **grows with degree**, suggesting a compounding effect. Hypothesized cause at the time: edge case in delay bound computation or tiny FP rounding at the check predicate. Not resolved.

**Primary source.** `docs/plans/2026-04-13-delay-window-v1-session-log.md`, `docs/plans/2026-04-13-journey-log.md`.

---

### M18 — B&B tightening v1 (2026-04-13 PM)

**Problem.** Per-set B&B (`bestValidDist[0]`) tightens only from *found* valid rides. We can also tighten using a *lower bound* on remaining rides (LB cut) and seed the DFS with a parent-consistent ordering (parent-seed sort) so the tightening happens fast.

**Mechanism.**
1. **Parent-seeded DFS.** Primary sort key: parent-consistent rank (0 for the next un-placed parent request or the new inserted request, 1 otherwise). Secondary: cheapest-next-segment. Gets to the parent-insertion ordering first, tightens `bestValidDist[0]` early, then prunes the rest of the tree faster. ≥83% of sets at degrees 4–11 find a valid ride immediately via this seed.
2. **LB-based outer B&B cut.** Precompute `minIn[stop]` = min over other stops of `dist(s → stop)` via `computeMinIn`. Thread `totalMinInRemaining` through the seeded DFS. Prune when `partialDist + totalMinInRemaining > bestValidDist[0]`. Guarded: origin DFS uses `depth > 0` (first origin has no incoming segment); dest DFS unguarded.

**Measured impact.** First full Bavaria 10% run reaching degree 11 (natural shareability ceiling) in ~26 min. Degree 6: 1.93× vs pre-delay-window. Degree 7: 3.04×. Dest LB cut fires ~1 billion times at deg 8, ~1.1 billion at deg 9 — the dominant pruning mechanism.

**Known drift.** 0.38–0.64% at degrees 3–6 vs pre-delay-window. **Degree 3 is newly drifted** (M17 didn't affect it). Hypothesis: `computeMinIn` calls `network.getSegment(from, to, 0.0)` at departure time 0, but Bavaria's routing is time-dependent via `travel_times.tsv`. If a real ride uses a segment whose distance is *less* than the time-0 segment, `minIn` overestimates and the LB cut can fire on orderings that would have produced a valid ride. See § 9 for the investigation plan.

**Primary source.** `docs/plans/2026-04-13-bnb-tightening-v1-design.md`, `docs/plans/2026-04-13-bnb-tightening-v1.md`, `docs/plans/2026-04-13-bnb-tightening-v1-session-log.md`, `.project-memory/bnb-tightening-v1-2026-04-13.md`.

**Notable process win.** M18 was implemented via 16 tasks × {implementer, spec reviewer, code quality reviewer} subagent dispatches. The spec review caught one real scope creep (public test hooks on `MatsimNetworkCache`); the code quality review required tightening to package-private; the two-stage review anticipated the depth-0 LB cut admissibility problem a task early. Described at length in the session log.

---

### M19 — Budget refactor (2026-04-14 AM)

**Problem.** `BudgetValidator.validateAndPopulateBudgets` is called per-ordering in the extension DFS. On Bavaria it never rejects (budget is subsumed by max-travel-time), so the 7–11% CPU at degrees 9–11 is pure overhead. The `Ride.remainingBudgets` field is needed downstream for the Python optimization pipeline, so the *population* can't be removed — only the per-ordering calls.

**Mechanism.** New `ExMasConfigGroup.deferExtensionBudgetValidation` flag (default false). When enabled:
- `RideExtender.evaluateOrdering` skips the per-ordering call, propagates the ride directly.
- `BudgetValidator.populateBudgetsBatch` runs once from `ExMasEngine` after the extension loop, populates `remainingBudgets` on each surviving ride, drops any with negative budgets (expected zero on Bavaria, warning if not).

Bavaria runner opts in. Kelheim (default off) preserves exact regression at 703/243/451/8/1.

**Hazard.** On scenarios where budget *does* reject, the deferred path cannot fall back to a longer-but-budget-feasible ordering the way the per-ordering path could. Documented in-code and in the commit message. Flag-gated for safety.

**Measured impact.** CPU saving: `Budget validation: 0ms (0,0%)` observed in `EnumerationStats` during the single-bin diagnostic. No clean Bavaria 10% benchmark yet — the attempted single-bin run OOMed (see § 8). A clean benchmark is part of the planned milestone matrix (§ 10).

**Primary source.** `docs/plans/2026-04-14-budget-refactor-and-pivot-session-log.md`.

---

## 6. Benchmark data — Bavaria 1% (historical)

The complete catalog of historical 1% runs with per-degree ride counts. Source: `matsim_scenarios/bavaria/output/demand-extraction-1pct-*/bavaria-30km-100pct-exmas.logfile.log`. These are raw extension outputs *before* any post-pruning.

**Key numbers in bold** are the ones that matter for the drift story.

| # | Dir suffix | Date | deg 3 | deg 4 | deg 5 | deg 6 | deg 7 | deg 8 | Total | Wall | Peak RAM | Milestone hint |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| 1 | `v3` | 03-30 09:04 | — | — | — | — | — | — | 12,271 | 6.2s | 4.5 GB | early baseline |
| 2 | `unpruned` | 03-30 10:39 | — | — | — | — | — | — | 26,703 | 9.2s | 4.2 GB | `--no-pruning` baseline |
| 3 | `p90` | 03-31 00:18 | — | — | — | — | — | — | 2,872 | 8.7s | 5.8 GB | p90 percentile filter |
| 4 | `p90-v2` | 03-31 02:46 | — | — | — | — | — | — | 2,857 | 6.9s | 4.3 GB | p90 iterate |
| 5 | `combo-experiment` | 03-31 10:38 | — | — | — | — | — | — | 50,307 | 9.4s | 9.0 GB | `--no-pruning` exploratory |
| 6 | `setdriven-test` | 03-31 12:01 | — | — | — | — | — | — | 50,307 | 10.2s | 7.4 GB | set-driven test |
| 7 | `top1-test` | 03-31 12:59 | — | — | — | — | — | — | 19,267 | 9.2s | 4.3 GB | top-1 selection |
| 8 | `bidirectional-test` | 03-31 13:57 | — | — | — | — | — | — | **fail** | — | 2.6 GB | crashed |
| 9 | `bidir-test` | 03-31 13:59 | — | — | — | — | — | — | 19,267 | 8.6s | 3.9 GB | rerun |
| 10 | `ordering-based` | 04-01 09:30 | **168** | 27 | 1 | — | — | — | 11,538 | 7.7s | 4.2 GB | **M5 — under-enumerated** |
| 11 | `parallel` | 04-01 10:48 | **168** | 27 | 1 | — | — | — | 11,538 | 8.2s | 4.3 GB | M6 — same bug, parallel |
| 12 | `optimized` | 04-01 19:48 | 167 | 18 | 1 | — | — | — | 11,528 | 6.2s | 4.5 GB | M7 — informed search |
| 13 | `pruned-greedy` | 04-02 11:33 | **809** | **313** | **75** | **12** | 1 | 0 | 12,552 | 8.0s | 4.6 GB | **M8 — correct reference counts** |
| 14 | `ordering-stats` | 04-02 19:09 | 809 | 313 | 75 | 12 | 1 | 0 | 12,552 | 7.6s | 4.4 GB | M8 + instrumentation |
| 15 | `branch-bound` | 04-02 21:04 | **742** | **263** | **58** | **9** | — | — | 12,414 | 8.8s | 4.3 GB | **M9 — 8–25% drop from M8** |
| 16 | `no-tighten` | 04-02 22:21 | 809 | 313 | 75 | 12 | 1 | 0 | 12,552 | 14.8s | 4.2 GB | M8 control (no B&B) |
| 17 | `dist-analysis` | 04-03 08:00 | 809 | 313 | 75 | 12 | 1 | 0 | 12,552 | 12.5s | 5.3 GB | M8 + dist instrumentation |
| 18 | `inline-eval` | 04-03 08:36 | 809 | 313 | 75 | 12 | 1 | 0 | 12,552 | 26.5s | 4.0 GB | inline eval variant |
| 19 | `inline-eval2` | 04-03 08:43 | 809 | 313 | 75 | 12 | 1 | 0 | 12,552 | 13.6s | 4.2 GB | inline eval v2 |
| 20 | `scoring-cache` | 04-03 10:46 | 809 | 313 | 75 | 12 | 1 | 0 | 12,552 | 8.2s | 4.7 GB | **M10** |
| 21 | `profiled` | 04-03 12:12 | 809 | 313 | 75 | 12 | 1 | 0 | 12,552 | 8.1s | 5.4 GB | M10 + profiling |
| 22 | `traveltime-pruning` | 04-03 14:35 | 809 | 313 | 75 | 12 | 1 | 0 | 12,552 | 7.5s | 4.7 GB | **M11** |
| 23 | `investigation` | 04-03 17:06 | 809 | 313 | 75 | 12 | 1 | 0 | 12,552 | 7.6s | 5.3 GB | M11 + analysis |
| 24 | `degree-graph` | 04-04 00:53 | 809 | 72 | — | — | — | — | 11,431 | 7.8s | 5.4 GB | **M12 — first version** |
| 25 | `degree-graph-v2` | 04-04 01:03 | 809 | 117 | 4 | — | — | — | 11,436 | 7.5s | 4.6 GB | M12 — richer |
| 26 | `degree-graph-noprune` | 04-04 01:07 | 809 | 287 | 64 | 8 | — | — | 12,510 | 6.9s | 3.5 GB | M12 control (no inter-deg prune) |
| 27 | `ordering-conflicts` | 04-05 15:27 | 0 | — | — | — | — | — | 7 | ~0 | 3.4 GB | **M13** — single-sample deterministic test |

**Critical observations.**

1. **M5–M7 are misleading.** Their 168/27/1 deg 3–5 counts reflect the `product(*E)[0]` under-enumeration bug. The jump to 809 at M8 is a *correctness* fix, not optimization drift. Do not compare M5–M7 to later rows.

2. **M8 establishes the 1% reference: 809 / 313 / 75 / 12 / 1 / 0 at deg 3–8.** Every subsequent optimization that claims lossless should reproduce these exactly.

3. **M9 drops to 742 / 263 / 58 / 9.** That's −8.3% / −16.0% / −22.7% / −25% vs M8. Much larger than today's 10% bnb-tightening drift (0.38–0.64%). This was accepted as "quality-preserving per-set B&B tightening" but never rigorously verified ride-by-ride. **This is the earliest precedent for B&B tightening removing rides. Worth investigating whether the same pattern underlies M17/M18 drift.**

4. **M10–M11 (scoring cache, TT pruning) restore M8 reference counts exactly.** 809 / 313 / 75 / 12 / 1 / 0 across 6 consecutive runs (rows 16–22). These are lossless optimizations confirmed.

5. **M12 (degree graph) does NOT match reference counts.** `degree-graph`: 809 / 72 (only deg 3–4 recorded). `degree-graph-v2`: 809 / 117 / 4. `degree-graph-noprune`: 809 / 287 / 64 / 8 — still lower than M8's 313/75/12. The degree-graph-noprune run is supposed to be the "apples to apples" control (without the 10% inter-degree pruning) and it's ALSO drifted at deg 4–6. This is a second historical instance of algorithmic drift that was noticed but not fully debugged at the time.

6. **No 1% runs exist for M17 (delay-window) or M18 (bnb-tightening).** Those milestones went straight to 10%. A 1% run under `--algorithm-process-count 1` on `feature/bnb-tightening-v1` is a missing data point — fills a gap in the matrix.

7. **No 1% run exists for M19 (budget refactor).** Same gap.

## 7. Benchmark data — Bavaria 10% (recent)

The numbers we actually use for dissertation framing. Source: `outputs/bnb-tightening-v1/bavaria-30km-100pct-exmas.logfile.log` and the bnb-tightening v1 session log's reconstructed tables.

| Milestone | deg 3 | deg 4 | deg 5 | deg 6 | deg 7 | deg 8 | deg 9 | deg 10 | deg 11 | Wall |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Pre-delay-window (morning diagnostic) | **1,065,484** | **1,609,633** | **1,624,477** | **991,685** | — | — | — | — | — | — |
| Delay-window v1 (M17) | ~1,065,490 | ~1,608,330 | ~1,620,860 | ~988,710 | — | — | — | — | — | — |
| bnb-tightening v1 (M18) | 1,061,416 | 1,602,843 | 1,617,119 | 985,319 | 399,231 | 108,175 | 16,336 | 1,146 | 25 | 1,571 s |
| bnb-tightening v1 + budget refactor (M19) | — | — | — | — | — | — | — | — | — | — |
| (single-bin diagnostic run — NOT comparable, graph differs) | 2,690,695 | 7,196,914 | 14,277,761 | OOM | — | — | — | — | — | — |

**Drift deltas from pre-delay-window baseline:**

| Milestone | Δ deg 3 | Δ deg 4 | Δ deg 5 | Δ deg 6 |
|---|---:|---:|---:|---:|
| Delay-window v1 | 0.00% | −0.08% | −0.22% | −0.30% |
| bnb-tightening v1 | **−0.38%** | **−0.42%** | **−0.45%** | **−0.64%** |

The bnb-tightening delta at deg 3 is the notable new piece — delay-window didn't affect deg 3 (the delay check only engages once per-passenger detours are known, which first matters at deg 4), but bnb-tightening drops 4,068 rides (0.38%) at deg 3 unambiguously. That must come from the parent-seeded DFS or the LB cut (both were added in M18). Parent-seeded DFS is order-only and shouldn't change ride counts, leaving the LB cut as the prime suspect.

## 8. Today's failed diagnostics — what we ruled out

Both single-bin diagnostic attempts on 2026-04-14:

| Run | Target | Result | Why it failed |
|---|---|---|---|
| `outputs/bnb-time-bin-diag` (deleted) | Bavaria 10% uncapped, single time bin | OOM at deg 6 | Single-bin inflated pair graph 1.48×, compounded to 8.8× at deg 5, exceeded 30 GB heap |
| `outputs/bnb-single-bin-deg5` | Bavaria 10% `--max-degree 5`, single time bin | OOM after deg 5 | Deg-5 extension completed but post-extension staging OOMed 32s later |

The single-bin lever is **structurally incompatible with the drift diagnosis**: it collapses the routing dimension the entire shareability graph depends on, producing a 1.48× larger pair set that's not comparable to the time-dep baseline. The reruns did confirm one useful fact: **run-to-run non-determinism at 10% under parallel execution is ~0.001–0.002%**, well inside the known ~0.2% forbidden-prefix envelope. So the 0.38–0.64% drift is not explained by parallelism noise.

The failed runs also confirmed M19 (budget refactor) is working: `EnumerationStats` reported `Budget validation: 0ms (0,0%)` across the DFS. Budget CPU overhead is gone.

## 9. Open issues and hypotheses

### 9.1 The 0.38–0.64% drift (M18 primary investigation target)

**Prime hypothesis (from 2026-04-13 memo).** `computeMinIn` queries `network.getSegment(from, to, 0.0)` at time 0, but Bavaria's `MatsimNetworkCache` is configured with `travel_times.tsv` so routing is time-dependent. If a real ride at pickup time T uses a segment whose distance is *less* than the time-0 segment, `minIn[stop]` overestimates the true minimum and the LB cut `partialDist + totalMinInRemaining > bestValidDist[0]` can fire on orderings that would have produced a valid ride better than `bestValidDist[0]`. Unsound prune.

**Alternative hypothesis (from today's 1% archaeology).** M9 (inline eval + tighten-on-valid) already showed 8–25% drift at 1% that was never ride-by-ride verified. If the M9 drift is actually a soundness bug in per-set B&B (not a quality-preserving optimization as assumed), today's M18 LB cut may be *inheriting* that bug — the LB cut sits inside the same per-set DFS and interacts with `bestValidDist[0]`. A clean M8 vs M9 vs M18 ride-by-ride comparison on 1% would distinguish these hypotheses.

**Alternative hypothesis (from M12 1% data).** The `degree-graph-noprune` run (row 26) shows deg 3 = 809 (matches M8) but deg 4 = 287 (vs M8's 313, −8%), deg 5 = 64 (vs 75, −15%), deg 6 = 8 (vs 12, −33%). That's a drift from M12 alone, without any B&B or delay-window involvement. Could the drift be structurally from the degree graph's consensus bitmask being over-aggressive? Also worth checking.

**Investigation plan.** Run the milestone benchmark matrix (§ 10). The row where counts first deviate from the M8 reference is the milestone that introduced the drift. We might find:

- Drift starts at M9 (per-set B&B) → fix needs to address admissibility of `bestValidDist[0]` tightening
- Drift starts at M12 (degree graph) → degree-graph consensus bitmask has an over-pruning bug
- Drift starts at M17 (delay-window) → delay bound predicate has FP or edge-case issue
- Drift starts at M18 (bnb-tightening) → time-dep `minIn` confirmed, fix is option (c) from the bnb-v1 memo

The milestone matrix is the single best diagnostic because it's a controlled experiment: one row per code change, all with the same input and the same parallelism floor.

### 9.2 Parallelism noise floor

Known ~0.2% from forbidden-prefix session (April, pre-delay-window). Today's 10% reruns confirm 0.001–0.002% at the specific config tested. For any new benchmark that needs to distinguish < 0.2% drift, run single-threaded (`--algorithm-process-count 1`). Adds ~2–3× wall time. Worth it for comparison rows.

### 9.3 Time-dependent routing in the shareability graph itself

Separate from the `minIn` concern: the shareability graph is built using time-dependent routing at the pair-generation phase. This is fine (it's the ground truth for feasibility), but it means any comparison lever that changes routing policy (single bin, deterministic routing, etc.) *also* changes the graph. Practical implication: the diagnostic levers available are narrower than I initially thought. The clean levers are:
- `--algorithm-process-count 1` — removes parallel noise without changing routing
- Milestone branch checkout — changes algorithm, keeps routing policy constant

### 9.4 Budget refactor soundness on non-Bavaria scenarios

M19 is flag-gated off by default because the deferred path can't fall back to a longer-but-budget-feasible ordering on scenarios where budget actively rejects. Kelheim regression test covers the flag-off case. **No test covers the flag-on case.** For dissertation rigor, a Kelheim-scale test with flag on and an intentionally tight budget (to force rejections) would demonstrate either (a) we drop the expected rides cleanly, or (b) the scenario where the hazard matters. Low priority unless we plan to enable this flag outside Bavaria.

## 10. Gap analysis — what's missing for a clean base-vs-new comparison

For the dissertation, the target story is: *"The baseline ExMAS algorithm fails at degree N on Bavaria 10% due to X. Each of our M8/M12/M17/M18 enhancements extends the reachable degree by Y while preserving best-ride quality. Total speedup at degree Z is W×, total memory reduction is V×, and the rides produced are pairwise identical to the baseline (modulo documented inter-degree pruning)."*

To tell that story we need:

### 10.1 Missing benchmark runs

| Run | Branch / commit | Scenario | Purpose | Priority |
|---|---|---|---|---|
| **M8 reference at 10% single-threaded** | `feature/exmas-traceable` earlier tip (pre-M9) | Bavaria 10% | Establish a non-drifted 10% baseline for M10+ comparison | High |
| **M9 at 10% single-threaded** | `feature/exmas-traceable` with inline eval | Bavaria 10% | Verify the historical 8–25% drift at 1% scales to 10% | High |
| **M12 at 10% single-threaded** | `feature/exmas-degree-graph` early | Bavaria 10% | Isolate degree-graph contribution to drift | High |
| **M17 at 10% single-threaded** | `feature/exmas-degree-graph` delay-window tip | Bavaria 10% | Reproduce the 0.08/0.22/0.30% drift at clean conditions | Medium |
| **M18 at 10% single-threaded** | `feature/bnb-tightening-v1` | Bavaria 10% | Reproduce the 0.38–0.64% drift at clean conditions | Medium |
| **M19 at 10%** | `feature/bnb-tightening-v1` (tip) | Bavaria 10% | Verify budget refactor is a no-op on ride counts | Low (Kelheim already validates default-off) |
| **Full 1% milestone matrix** | All milestones M5→M19 | Bavaria 1% | Drift source isolation on fast-iteration scale | **Critical — do this first** |

### 10.2 Missing ride-by-ride verification

Count-based comparison is not sufficient to prove quality preservation. Two runs can produce the same count but different rides. The dissertation claim "our algorithm finds the same best rides" needs a ride-by-ride diff. Proposal:

- Export `exmas_rides.csv` from each benchmark row
- Index each ride by `(request_set_hash, passenger_order, dropoff_order)`
- For each pair of adjacent milestones in the matrix, diff: rides only in A, rides only in B, rides in both.
- Report: a count of each bucket; for rides present in one but not the other, the distance distribution (should be at the bottom if the drift is quality-preserving).

This is a short Python script — ~30 min to write, ~5 min to run across all comparisons. It's the single most important missing piece for dissertation rigor.

### 10.3 Missing wall-time / memory metrics with consistent config

The historical 1% runs have inconsistent CLI args (different `--inter-degree-keep`, different `--no-pruning` settings, different instrumentation). A clean re-run of all milestones with *identical* config (except for algorithm-side flags that define the milestone) would give a comparable row set. This is part of the full 1% milestone matrix work.

### 10.4 Missing CI/test coverage for milestones

The Kelheim E2E test validates *current* behavior. It doesn't exercise `--max-degree` variants, doesn't test the defer-budget flag with the flag on, doesn't test the single-time-bin override. Adding a few parameterized tests would catch regressions introduced by future optimizations without requiring Bavaria-scale runs.

### 10.5 Missing dissertation integration

The master reference doc is a pointer, not a chapter. Dissertation chapters will need:
- A single-figure Pareto chart: wall time vs memory per milestone
- A single-figure max-reachable-degree per milestone
- A table of ride counts per milestone with the drift column called out
- A narrative paragraph per milestone
- Ride-by-ride preservation proof (see § 10.2)

None of these exist yet. They're downstream of the milestone matrix work — generate the data first, then write.

## 11. Dissertation integration points

Where each result plugs into the dissertation. (Subject to chapter structure — adjust as the outline firms up.)

| Chapter / section | Uses | Source in this repo |
|---|---|---|
| Literature review — limitations of the Python ExMAS | M5 (ordering bug discovery) | `docs/plans/2026-03-31-ordering-based-extension.md`, `.project-memory/ordering-based-extension-2026-04.md` |
| Algorithm chapter — the new enumeration strategy | M5, M8, M11, M12, M14, M17, M18 | Per-milestone sources in § 5 |
| Scalability analysis | M4, M8, M12, M18 | Wall-time tables, peak-memory tables |
| Quality preservation proof | M8 vs M19 ride-by-ride diff (TODO) | § 10.2 |
| Case study — Bavaria / Kelheim ridepooling | Pipeline (M2, M3) + final extraction output | Bavaria scenario docs, demand extraction session logs |
| Appendix — engineering notes | Forbidden-prefix negative result (M15), drift investigation | `docs/plans/2026-04-13-forbidden-prefix-index-session-log.md`, this document § 9 |

## 12. How to use this document

**Starting a new session on ExMAS work?** Read § 2 (quick facts) and § 9 (open issues) first, then skim § 3 (timeline) to orient yourself against the most recent milestone. If you're about to touch the algorithm, read § 5 for the milestone(s) immediately before and after your target.

**Debugging something?** § 9 has prior hypotheses. § 6 has the historical 1% data you can diff against. If the bug's in extension, § 5 M12/M14/M17/M18 are where the cut/check logic lives.

**Writing a dissertation section?** § 11 maps chapters to milestones. § 5 gives you the one-paragraph-per-milestone seeds. § 6 / § 7 are the benchmark tables.

**Planning a new benchmark?** § 10 lists gaps — pick the highest priority missing run. Use `--algorithm-process-count 1` for any comparison row that needs to beat the 0.2% noise floor.

**Adding a new milestone to this document?** Append to § 3 (timeline row), add a § 5.x entry, update § 6 / § 7 with new benchmark data. Update § 2 "current branch" and § 9 if the new milestone affects open issues.

---

## Appendix A — File inventory

The 56 Dissertation-root files and 16 submodule files inventoried in prep for this document live at the paths cited throughout § 5. If you need the flat lists, they're in the session notes at:

- Dissertation root inventory: part of the `2026-04-14-budget-refactor-and-pivot-session-log.md` session (Explore agent output, embedded in the conversation history)
- Submodule inventory: same session
- Historical 1pct run inventory: § 6 table above (complete)

These inventories were generated 2026-04-14 and may drift as new docs are added. Re-run the scan if the timeline in § 3 gets out of sync with the file dates.

## Appendix B — Primary memory files

The `.project-memory/` directory holds condensed summaries of the most active development periods. In chronological order:

- `.project-memory/MEMORY.md` — index of all project-memory files (top-level navigation, not content)
- `.project-memory/paper1-development-session-2026-03-25.md` — paper 1 kickoff, context mapping
- `.project-memory/ordering-based-extension-2026-04.md` — M5 deep investigation (product(*E)[0] bug)
- `.project-memory/pruning-research-2026-03-30.md` — ride database pruning analysis
- `.project-memory/forbidden-prefix-index-2026-04-13.md` — M15 decision memo (why disabled)
- `.project-memory/bnb-tightening-v1-2026-04-13.md` — M18 full summary

These are meant to survive conversation context resets. Read them as "what the last session left behind."

## Appendix C — Branch topology snapshot (2026-04-14)

```
main (54d611e — bike/walk speed fix)
 │
 └─ feature/exmas-traceable (d8bc90a — scoring cache + per-passenger TT pruning)
      │
      └─ feature/exmas-degree-graph (1b864e2 — degree graph + ordering conflicts + dropoff check
            │                                  + forbidden-prefix (disabled) + ordering death diag
            │                                  + delay-window v1)
            │
            └─ feature/bnb-tightening-v1 (466edf4 — parent-seed + LB cut + budget refactor) ← HEAD
```

Not pushed to origin. Two local branches that are *not* on this chain: `feature/exmas-traceable` (already in the chain) and some old origin-only `claude/*` exploration branches from earlier in the project that are unrelated to the current ExMAS arc.

---

*End of master reference. Last updated 2026-04-14 by today's session. Next update trigger: milestone matrix benchmark completes, or next algorithmic milestone lands.*
