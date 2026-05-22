# Parent-Ordering Cache — Design (2026-04-13)

## Problem

At high degrees the ExMAS ride extension hits a factorial wall on origin orderings per set. On 10% Bavaria we see ~683 orderings/set at degree 6, ~2,441 at degree 7, ~104,893 at degree 8. The current mitigation stack (`OrderingConflicts`, `SubSetOrderingFeasibility` with bloom filter, sub-set hashing over triples/quads/quints, DAG tightening from triple infeasibility) has grown to ~860 LoC across two classes and delivers only marginal speedups relative to no-ordering-filter. We need a different concept.

## Core insight

Every feasible set `S'` at degree `k+1` has exactly `k+1` direct parent sub-sets at degree `k` — and we already enumerated them in the previous pass. `DegreeGraph` stores which parents are feasible but throws away their feasible **origin orderings**. That data is the lever:

1. **Exact:** if a child ordering's projection onto a parent doesn't match any feasible parent ordering, the child ordering cannot be feasible (sound, no monotonicity assumption — no triangle-inequality problem).
2. **Heuristic:** good child orderings are almost always insertions of the new request into a good parent ordering. Enumerating insertions from top-*M* parents replaces factorial enumeration.

Both paths share a single new data structure: `ParentOrderingCache`. Path E (exact) is the replacement for `SubSetOrderingFeasibility`. Path H (heuristic) is an optional mode gated by degree.

## Data structure: `ParentOrderingCache`

```
key   = canonical sorted request index array (same key as DegreeGraph)
value = int[][] feasible origin permutations for that set, ranked by ride score descending
```

Storage format:
- `Long2ObjectOpenHashMap<int[][]>` — one entry per feasible set that produced ≥1 ordering.
- For degree `k`, each permutation is an `int[k]` of local indices. ~4k bytes per ordering.
- Ranked by ride score so top-*M* truncation for heuristic mode is just a prefix.

Lifecycle:
- Built at the end of each degree's enumeration pass (from the same list of `Ordering` records the `RideExtender` already collects).
- Committed between degrees, visible when degree `k+1` starts.
- **Dropped after degree `k+1` completes** (we only ever need the cache of the immediate parent degree). Peak memory = one degree's cache.

Memory estimate, peak degree (rough):
| Deg | Feasible sets | Avg orderings/set | Bytes/ord | Total |
|-----|---------------|-------------------|-----------|-------|
| 3 | 1.07M | 2 | 12 | ~26 MB |
| 4 | 1.61M | 4 | 16 | ~103 MB |
| 5 | 1.63M | 6 | 20 | ~196 MB |
| 6 | 991k | 5 | 24 | ~119 MB |
| 7 | 402k | 3 | 28 | ~34 MB |

Peak ~200 MB at degree 5. Well under the 300 MB budget the user approved.

Fallback if peak exceeds budget: cap per-set list at `maxOrderingsPerSet` (e.g., 32). Loses exact completeness but bounds memory; this switch already exists conceptually in the project (top-fraction post-extension pruner).

## Path E: exact parent-ordering filter (E3 hybrid)

Replaces `SubSetOrderingFeasibility`. Two-layer pruning per child set `S'`:

### Layer 1: pairwise DAG tightening (once per set)

Before origin enumeration starts:
1. For each pair `(i, j)` in `S'`, walk all `k+1` parents of `S'`. For each parent `P` containing both `i` and `j`, check whether any feasible ordering of `P` has `i` before `j` (and vice versa).
2. If **no** parent ordering in any parent containing `(i,j)` has `i` before `j` → force edge `j→i` in the origin DAG of `S'`.
3. Same for the reverse direction.
4. This is the same flavor as today's `SubSetOrderingFeasibility.tightenDAG()`, but (a) derived from direct parents instead of triples hashed from failed runs, (b) uses feasibility data (sound) rather than infeasibility inference (unsound under time-dep routing).

Pair check cost: O((k+1) × parents × parentOrderings). At degree 7 with ~3 orderings per parent: 7 × 3 × 21 pairs ≈ 440 ops per set. Negligible.

This alone can collapse orderings/set dramatically when parent data is consistent.

### Layer 2: per-candidate projection check (during enumeration)

For each origin ordering `π` the topo-sort enumerator produces from the tightened DAG:
1. For each of the `k+1` parents `P` of `S'`, extract the projection `π|P` (the sub-permutation restricted to `P`'s elements).
2. Compute its Lehmer index.
3. Check membership in `P`'s feasible-ordering list (binary search on sorted Lehmer indices, or a small `IntOpenHashSet` per set).
4. If any parent rejects the projection, skip `π` before any routing work.

Cost per candidate ordering: O((k+1) × k²) ≈ 350 ops at degree 7. Cheap relative to routing.

**Completeness**: sound by construction. A feasible child ordering has feasible projections onto every parent (the parent's orderings *include* that projection, because ExMAS pair constraints at level `k` are a subset of those at `k+1`). No triangle-inequality dependence, no false positives.

### What Path E replaces

- Remove wiring for `SubSetOrderingFeasibility` in `ExMasEngine`, `RideExtender`, `OrderingEnumerator`.
- Delete `OrderingConflicts.java` + test (already disabled, superseded).
- Keep `SubSetOrderingFeasibility.java` temporarily as dead code for git-bisect comparison; delete after benchmark confirms parent-ordering cache is at least as fast and ride-identical.
- Keep `OrderingEnumerator.checkOriginTravelTime` (Check A / mechanism 1). That's orthogonal and still the free 1.7× win.

## Path H: heuristic parent-insertion beam (H2 with degree threshold)

Optional, activated by config at a configurable minimum degree. When active **replaces** Path E enumeration for the set — skips the full origin-ordering search entirely.

### Config (added to `ExMasConfigGroup`)

```
heuristicOrderingFromDegree = -1  (disabled; e.g. 6 to activate at degree ≥ 6)
heuristicOrderingTopM       = 3
heuristicOrderingUseAllParents = true
```

### Algorithm per set `S'` at degree `k+1`

1. Identify the new request `r` that is in `S'` but not in some parent. For each of the `k+1` parents `P_i` (each excludes exactly one member — that's the "new request" relative to that parent).
2. For each `P_i`:
   a. Take top-*M* feasible orderings from `ParentOrderingCache[P_i]` (ranked by score).
   b. For each parent ordering `o`:
      - Insert pickup of `r` at every valid origin position (0..k), subject to origin DAG constraints (shareability graph pair constraints still enforced).
      - For each origin insertion position, enumerate destination insertion positions consistent with pair FIFO/LIFO constraints.
      - This produces at most `(k+1)(k+2)/2` child orderings per parent ordering — but typically far fewer after pair-constraint filtering.
3. Deduplicate across parents: hash each resulting child origin ordering by Lehmer index into a `IntOpenHashSet`; route only unique ones.
4. Route each surviving candidate (segment cache amortizes cost across insertion positions), keep feasible orderings as today.

### Expected cost

Degree 7 with *M*=3, all 7 parents: 3 × 7 × 28 ≈ 590 insertion candidates before dedup. Dedup typically halves this. ~300 routing calls vs ~2,441 orderings today → ~8× faster at degree 7.

Degree 8 with *M*=3: 3 × 8 × 36 ≈ 860 before dedup, ~400 after. vs ~104,893 today → ~250× faster.

### Quality

Lost orderings fall into two buckets:
- "Emergent" orderings where the child's best sequence is *not* an extension of any top-*M* parent ordering. Rare for DRT pooling — adding a passenger mostly inserts without reshuffling existing segments.
- Orderings where the parent used wasn't in the parent cache (filtered by `PostExtensionPruner` before extension). These are dominated anyway.

Benchmark across `heuristicOrderingTopM ∈ {1, 2, 3, 5, 8}` to build a Pareto curve of speedup vs ride loss. Measured per degree.

## Integration points

New file:
- `algorithm/extension/ParentOrderingCache.java` — the data structure + `record`, `commit`, `get`, `tightenDAG`, `projectionCheck`, `getTopM` methods.
- `algorithm/extension/ParentOrderingCacheTest.java` — unit tests for record/commit, DAG tightening, projection membership, Lehmer index correctness.

Modified:
- `OrderingEnumerator.java` — replace `subsetFeasibility` parameter with `parentOrderingCache`; add Path E layers; add Path H branch gated by config.
- `RideExtender.java` — pass `ParentOrderingCache` through; collect per-set feasible orderings at end of each `processSet`; hand back to engine for `record()`.
- `ExMasEngine.java` — build cache at end of each degree's enumeration; commit before next degree; drop previous-degree cache; remove `SubSetOrderingFeasibility` creation.
- `ExMasConfigGroup.java` — add `heuristicOrderingFromDegree`, `heuristicOrderingTopM`, `heuristicOrderingUseAllParents`.
- `EnumerationStats.java` — add counters: `prunedByDagTightening`, `prunedByProjection`, `heuristicSetsSkipped`, `heuristicInsertionsGenerated`, `heuristicInsertionsDeduped`.

Removed or deprecated:
- `SubSetOrderingFeasibility.java` wiring (keep file as dead code initially).
- `OrderingConflicts.java` + tests (delete after parent-cache is live).
- `OrderingEnumerator.subsetFeasibility` parameter and its recording calls.

## Testing strategy

1. **Unit tests for `ParentOrderingCache`** — record, commit, tightenDAG correctness on hand-built mini sets, projection check edge cases (singleton parents, empty parents, Lehmer boundary).
2. **Regression test:** exact Path E must produce identical ride counts to the current baseline for degrees 3–7 on `ExMasKelheimE2ETest`. Any ride drop is a correctness bug.
3. **Benchmark runs** on 10% Bavaria Kelheim scenario:
   - Baseline (no parent cache, current master) — captured from `outputs/diag-run`.
   - Path E only (exact) — must match ride counts, measure speedup.
   - Path H with `M ∈ {1, 2, 3, 5, 8}` at `heuristicOrderingFromDegree=6` — measure per-degree (time, rides, ride-loss %, Pareto curve).
4. Record results in `docs/plans/2026-04-13-parent-ordering-cache-session-log.md`.

## Open questions (tracked, not blocking)

1. Should Layer 2 projection check also apply to **destination** orderings, or only origins? Origins drive most of the factorial blowup; destinations are already heavily constrained by FIFO/LIFO and in-loop checks. Skip destinations in v1, revisit if profiling shows dest-phase still dominates.
2. At degree 3 there is no "parent" of degree 2 with multi-ordering structure (pair orderings are already constrained by shareability). Cache starts contributing at degree 4. Confirmed in the memory table above.
3. Path H: is "all `k+1` parents" worth the cost vs "one canonical parent"? H2 as described. Benchmark will answer.

## Acceptance

- Path E replaces current sub-set feasibility code, ride counts match baseline ±0 at degree 3–7 for 10% Bavaria, total extension time drops on 10% Bavaria (target: ≥2× at degree 7).
- Path H available via config, disabled by default, benchmarked across *M* values with a Pareto table in the session log.
- `SubSetOrderingFeasibility` + `OrderingConflicts` removed from the hot path. LoC in `algorithm/extension/` drops relative to current master.
