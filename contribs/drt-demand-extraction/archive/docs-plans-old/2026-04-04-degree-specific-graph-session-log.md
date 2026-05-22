# Degree-Specific Graph — Session Log (2026-04-04)

## Context

Continuing ExMAS ride extension optimization. Previous sessions implemented pruned greedy enumeration, B&B, scoring cache, and travel time pruning. Combined 4.8x speedup at degree 5 (1,867s → 392s). This session: design and implement degree-specific graphs for candidate generation and ordering constraint propagation.

## Key Idea

Replace pair-graph-only candidate generation with degree-specific graphs built after each degree from constraint-feasible rides. Two benefits:
1. **Candidate reduction:** Only generate candidates where ALL sub-sets are feasible (not just pairwise compatible)
2. **Ordering constraint propagation:** Use pairwise direction consensus from sub-set orderings to tighten enumeration DAGs

## Measurement Phase

### Candidate reduction instrumentation (Java)

Added counters to EnumerationStats + sub-set feasibility histogram. Results (10% Bavaria, 21k requests):

| Degree | Current candidates | Graph candidates | Reduction |
|--------|-------------------|-----------------|-----------|
| 4 | 10,730,328 | 1,940,953 | **81.9%** |
| 5 | 26,587,744 | 3,085,700 | **88.4%** |

Sub-set feasibility histogram (degree 5): 12.3M with 1 feasible sub-quad, 5.7M with 2, 3.5M with 3, 2.0M with 4, **3.1M with all 5**.

### Ordering constraint analysis (Python)

Script: `scripts/analyze_ordering_constraints.py`. Used request times + delays to determine ride orderings from CSV data. Results:

| Degree | Origin orderings (pair graph) | + Sub-set constraints | Reduction |
|--------|-------------------------------|----------------------|-----------|
| 4 | 7.1 | 1.6 | **77.6%** |
| 5 | 20.7 | 2.7 | **86.7%** |

64% of degree-4 candidates have origin ordering fully determined after sub-set constraints.

## Implementation

### Phase A: DegreeGraph + candidate generation

**DegreeGraph.java** — New data structure in `algorithm/graph/`:
- Extension index: `Long2ObjectOpenHashMap<int[]>` mapping (k-1)-subset hashes to sorted extension element arrays
- `findExtensions(baseSet)`: k-way sorted intersection of extension lists, minus base elements
- Consensus bitmask: `Long2ObjectOpenHashMap<long[]>` mapping set hashes to pairwise ordering direction bits

**Integration:**
- RideExtender uses `prevDegreeGraph.findExtensions()` at degree 4+ (pair graph at degree 3)
- ExMasEngine builds DegreeGraph after each degree, passes to next iteration

### Phase B: Ordering constraint propagation

**Consensus bitmask encoding:** For each pair (i,j) in a degree-k set, 4 bits:
- bit 0: i before j in origins seen
- bit 1: j before i in origins seen
- bit 2: i before j in destinations seen
- bit 3: j before i in destinations seen

Packed into `long[]` with `bitPos >> 6` for array index, `bitPos & 63` for bit within long. Supports any degree (degree 7 = 2 longs, degree 16 = 8 longs).

**Constraint tightening:** In `processSet` at degree 4+, `tightenConstraints()` checks `DegreeGraph.getOriginConsensus()` for each flexible pair. If all sub-sets agree on one direction → fix it in the PairInfo array before enumeration.

### Phase C: Performance optimization

**Problem:** Initial implementation collected `FeasibleSetResult` objects in a `ConcurrentHashMap` during the parallel processing hot loop. 1.07M puts from 16 threads caused severe contention. Degree 3 went from 27s → 131s (4.9x slower).

**Solution:** Remove FeasibleSetResult collection from processSet entirely. Build graph from valid rides in `resultBySetHash` after parallel processing. Consensus bits accumulated via lightweight `ConcurrentHashMap<Long, long[]>` with one put per feasible set at the END of processSet (not in the evaluator loop).

Two code paths in processSet:
- Degree 3 (`prevDegreeGraph == null`): original 6-param `enumerateAndEvaluate`, no extractConstraints overhead
- Degree 4+ (`prevDegreeGraph != null`): extractConstraints + tightenConstraints + 7-param overload

## Experiments and Results

### Attempt: Remove distance B&B entirely

Hypothesis: collecting ALL constraint-feasible orderings (not just within B&B bound) would give richer consensus for the next degree.

Result: **Failed.** Without distance B&B:
- Graph bloated from 1.07M to 3.33M triples (3x more constraint-feasible-but-distance-failing sets)
- More orderings evaluated per set (5x at degree 3)
- Weaker candidate filtering (55% reduction vs 82%)
- OOM at degree 5 (29 GB)

Decision: Re-enabled distance B&B. Consensus captures orderings within B&B bound only.

### Final results (10% Bavaria, optimized implementation)

**Degree 3-5 (comparable to baseline):**

| Degree | Baseline | Degree graph | Speedup | Candidates |
|--------|----------|-------------|---------|------------|
| 3 | 27s | 25s + 2s build | 1.0x | 7.05M (unchanged) |
| 4 | 41s | 12s + 3s build | **2.7x** | 1.94M (82% fewer) |
| 5 | 392s | 52s + 5s build | **6.9x** | 1.74M (93.5% fewer) |
| **Total 3-5** | **460s** | **99s** | **4.6x** | — |

**Higher degrees (previously unreachable):**

| Degree | Time | Candidates | Rides | Success rate | Orderings/set |
|--------|------|------------|-------|-------------|--------------|
| 6 | 172s + 3s | 966,181 | 891,593 | 92% | 934 |
| 7 | 1,062s + 2s | 360,751 | 340,761 | 95% | 9,215 |
| 8 | ~1.3h (killed) | ~130k | — | — | ~100k est. |

Degree 6 in 3 minutes (baseline estimated 1-1.5 hours = **~25x speedup**). Degree 7 in 18 minutes (previously unreachable).

### Ordering constraint effect at degree 7

With `long[]` consensus (generalized to all degrees):
- Orderings/set: 20,298 → 9,215 (**55% reduction**)
- Total time: 1,349s → 1,062s (21% faster)
- The consensus only captures best-ride orderings (within B&B) — richer data would help more

### Ride count comparison (1% Bavaria, no inter-degree pruning)

| Degree | Baseline | Degree graph | Missing |
|--------|----------|-------------|---------|
| 3 | 809 | 809 | 0 |
| 4 | 313 | 287 | 26 |
| 5 | 75 | 64 | 11 |
| 6 | 12 | 8 | 4 |
| 7 | 1 | 0 | 1 |
| **Total** | **12,552** | **12,510** | **42 (0.3%)** |

42 missing rides (0.3%) caused by time-dependent routing violating monotonicity: a sub-set fails constraints at one departure time, but the super-set passes because the extra passenger shifts pickup times to avoid congestion.

## Key Findings

1. **Candidate reduction is the main win.** 82-93.5% fewer candidates at degree 4-5. Grows with degree.
2. **Ordering consensus helps but is limited.** 55% ordering reduction at degree 7 from best-ride-only consensus. Limited by pairwise encoding and B&B-restricted ordering data.
3. **The factorial wall on orderings per candidate** is the remaining bottleneck. 103 → 934 → 9,215 → ~100k orderings per set at degree 5 → 6 → 7 → 8. Consensus mitigates but doesn't overcome this.
4. **Monotonicity holds ~99.7%** in practice. The 0.3% violation from time-dependent routing is negligible.

## Next Steps: Conflict Clause Learning (Brainstorm)

### The Idea

Replace or augment pairwise consensus with **prefix-based failure learning** from constraint-pruned branches:

**Current (consensus):** Track which pairwise directions appear in VALID orderings. Heuristic — absence of a direction doesn't prove it fails.

**Proposed (conflict learning):** Track which ordering PREFIXES are killed by constraint pruning. Provable — if prefix [A, C, F] causes passenger A to exceed maxTravelTime, this is a hard constraint that holds at all higher degrees.

### Why This Could Be Transformative

1. **Provable vs heuristic:** Failed prefixes are monotonically infeasible (adding passengers only makes it worse). Consensus is just "what we happened to see."

2. **Richer than pairwise:** Prefix [A, C, F] captures a 3-way interaction that can't be decomposed into pairs. "A before C" works, "C before F" works, but "A, then C, then F in sequence" fails.

3. **Directly targets the bottleneck:** At degree 7, 93.5% of evaluated orderings fail constraints. If we could predict and skip 90% of these via learned conflict prefixes, orderings/set drops from 9,215 to ~1,000.

4. **Analogous to SAT solver conflict-driven clause learning (CDCL):** When a constraint violation is found, learn the minimal prefix that caused it and avoid repeating it.

### Data Structure Ideas (to brainstorm next session)

- **Prefix trie** per request pair/triple: tracks which sequential pickup orderings are known-infeasible
- **Global transition graph:** nodes = requests, edges = "pickup A immediately before B is feasible" (learned from successful AND failed branches)
- **Conflict clauses:** minimal sets of ordering decisions that provably lead to failure

### Key Questions for Next Session

1. What is the minimal failure prefix? Is it always "the first k passengers in pickup order where travel time was violated"?
2. How to aggregate across sets? Prefix [A,C,F] fails in set {A,B,C,D,E,F} — does it fail in {A,C,F,G,H,I}?
3. Storage: how many unique conflict prefixes exist? Can we compress them?
4. Lookup: during enumeration at degree k+1, how to efficiently check if current partial ordering matches a conflict prefix?
5. How does this interact with the existing B&B and travel time pruning?

## Files Changed (matsim-libs, branch feature/exmas-degree-graph)

| Commit | Description |
|--------|------------|
| `fdb57b3` | Instrumentation: constraint-feasible set tracking + sub-set histogram |
| `3a4497` | DegreeGraph data structure (extension index + ordering storage) |
| `99ad14c` | Integrate DegreeGraph for candidate generation at degree 4+ |
| `a054899` | Ordering constraint propagation + remove distance B&B (later reverted) |
| `f3b49c9` | Re-enable distance B&B with ordering collection |
| `64d56f3` | Compact bitmask consensus (replace OrderingPair lists) |
| `727a318` | Eliminate processSet overhead — build graph from valid rides |
| `b08d443` | Generalize consensus bitmask to long[] for all degrees |

## Profiling Data Archive

All profiling data in `matsim_scenarios/bavaria/output/`:
- `demand-extraction-10pct-graph-analysis/` — instrumentation run
- `demand-extraction-10pct-degree-graph-v2/` — B&B + degree graph run
- `demand-extraction-10pct-optimized/` — optimized processSet (best 99s result)
- `demand-extraction-10pct-degree-graph-full/` — unlimited degree run (through degree 7)
- `demand-extraction-10pct-degree-graph-longarray/` — long[] consensus run
