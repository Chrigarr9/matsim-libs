# Degree-Specific Graph with Ordering Constraint Propagation — Design

## Problem

The ExMAS ride extension algorithm generates too many candidates at higher degrees. At degree 5 (10% Bavaria, 21k requests), 26.6M candidate sets produce 1.55B orderings, taking 410 seconds. 88.4% of these candidates have at least one infeasible sub-set — work we already know will fail but repeat anyway because the algorithm only uses pair-level information for candidate generation.

## Solution

Build a **degree-specific graph** after each degree from all constraint-feasible sets and their valid orderings. Use it at the next degree for:

1. **Candidate generation** — only generate candidates where ALL sub-sets are feasible
2. **Ordering constraint propagation** — tighten origin/destination DAGs using sub-set ordering information
3. **Remove distance B&B** — find all constraint-feasible orderings per set (needed to populate the graph), pick the best afterward

## Measured Impact (10% Bavaria, from instrumentation run)

### Candidate reduction (measured via Java instrumentation)

| Degree | Current candidates | Graph candidates | Reduction |
|--------|-------------------|-----------------|-----------|
| 3 | 7,053,256 | 7,053,256 | 0% (pair graph for both) |
| 4 | 10,730,328 | 1,940,953 | **81.9%** |
| 5 | 26,587,744 | 3,085,700 | **88.4%** |

Sub-set feasibility histogram (degree 5): of 26.6M candidates, only 3.1M have all 5 sub-quads feasible.

### Ordering reduction (estimated from Python analysis of ride orderings)

| Degree | Origin orderings (pair graph) | + Sub-set constraints | Reduction |
|--------|-------------------------------|----------------------|-----------|
| 4 | 7.1 | 1.6 | **77.6%** |
| 5 | 20.7 | 2.7 | **86.7%** |

64% of degree-4 candidates and 48% of degree-5 candidates have origin ordering fully determined (only 1 valid origin ordering) after sub-set constraints.

### Combined estimated effect

| Degree | Current total orderings | Proposed | Reduction |
|--------|------------------------|----------|-----------|
| 4 | 83M | ~3M | **96%** |
| 5 | 1,555M | ~24M | **98.5%** |

## Correctness Guarantee

**Monotonicity property:** if no ordering of k passengers satisfies constraints (maxTravelTime, budget), adding a (k+1)th passenger only adds stops → travel times can only increase → supersets are also infeasible.

Therefore, filtering candidates by sub-set feasibility cannot miss any valid ride. The algorithm finds exactly the same rides as the current approach.

Verified empirically (1% Bavaria): building the graph from constraint-feasible rides gives 0% miss rate. The 5.8% "misses" in initial analysis were all caused by distance-savings pruning thresholds, not constraint infeasibility.

## Data Structure

```java
class DegreeGraph {
    // Candidate generation: (k-1)-subset hash → sorted extension element array
    private final HashMap<Long, int[]> extensionIndex;
    
    // Ordering constraints: set hash → valid orderings
    private final HashMap<Long, List<OrderingPair>> validOrderings;
    
    record OrderingPair(byte[] originPerm, byte[] destPerm) {}
    
    /**
     * Find all requests that extend baseSet into a feasible (k+1)-set.
     * For each (k-1)-subset of baseSet, look up extension elements.
     * Intersect all k lists → result guarantees all sub-sets feasible.
     */
    int[] findExtensions(int[] baseSet);
    
    /**
     * Get valid orderings for a set (for ordering constraint propagation).
     */
    List<OrderingPair> getOrderings(long setHash);
    
    /**
     * Build the graph from feasible sets and their orderings.
     * For each set, index each (k-1)-subset → extra element.
     */
    static DegreeGraph build(Collection<FeasibleSetResult> feasibleSets, int degree);
}
```

### Memory estimates (10% Bavaria)

| Component | Degree 3 | Degree 4 | Degree 5 |
|-----------|----------|----------|----------|
| Extension index | ~51 MB | ~177 MB | ~390 MB |
| Valid orderings | ~19 MB | ~177 MB | ~1 GB |
| **Peak** (one graph at a time) | | | **~1.4 GB** |

At 100% scale: ~10x → ~14 GB peak. Within 30 GB JVM budget.

## Algorithm Flow

### Current Algorithm

```
pair graph → degree 3 → degree 4 → degree 5
                ↑            ↑            ↑
            pair graph   pair graph   pair graph  (always pair graph for candidates)
```

### Proposed Algorithm

```
pair graph → degree 3 → build graph → degree 4 → build graph → degree 5
                ↑                          ↑                        ↑
            pair graph              degree-3 graph           degree-4 graph
            (candidates)            (candidates +            (candidates +
                                     ordering                ordering
                                     constraints)            constraints)
```

### Per-Degree Processing (degree k → k+1)

```
1. CANDIDATE GENERATION
   IF first extension (pair → triples):
     Use pair graph (same as current)
   ELSE:
     Use degreeGraph.findExtensions(baseSet)
       - Look up k extension lists (one per (k-1)-subset of base)
       - Intersect all lists
       - Result: only candidates with all sub-sets feasible

2. PROCESS CANDIDATE (for each candidate set S)
   a. Extract pairwise FIFO/LIFO from pair graph (unchanged)
   b. NEW: Extract additional pairwise constraints from sub-set orderings
      - For each flexible pair (i,j), check sub-set orderings
      - If all agree on direction → add as hard constraint
      - Origin constraints AND destination constraints
   c. Build tighter origin DAG + destination DAG
   d. Enumerate orderings via topological sort (same algorithm, tighter DAGs)
   e. Travel time pruning (Check A, Check B) — unchanged
   f. NO distance B&B — evaluate all orderings that pass travel time checks
   g. For each valid ordering: validate budget, track best
   h. Record ALL valid (originPerm, destPerm) pairs for the graph

3. BUILD DEGREE GRAPH (after all candidates processed)
   a. Build extension index from feasible set request arrays
   b. Store valid orderings per set
   c. Sort extension lists for deterministic intersection

4. INTER-DEGREE PRUNING (unchanged)
   Keep top 10% of valid rides by distance savings → base sets for next degree
   Note: graph uses ALL feasible sets, not just pruned survivors
```

### What Changes vs What Stays

| Component | Change? | Details |
|-----------|---------|---------|
| Pair graph | **Unchanged** | Still needed for FIFO/LIFO ordering constraints |
| Candidate generation | **Changed** at degree 4+ | DegreeGraph.findExtensions() replaces pairGraph.findCommonNeighborsSorted() |
| Ordering enumeration | **Changed** | Tighter DAGs from sub-set ordering constraints |
| Distance B&B | **Removed** | Find all valid orderings instead of best-first |
| Travel time pruning | **Unchanged** | Check A + Check B still active |
| Budget validation | **Unchanged** | Still validates each complete ordering |
| Inter-degree pruning | **Unchanged** | Top 10% for result selection |
| Ride construction | **Unchanged** | Same routing + constraint checking |

## Files to Modify

All in `matsim-libs/contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/`:

| File | Changes |
|------|---------|
| `algorithm/graph/DegreeGraph.java` | **NEW** — degree-specific graph data structure |
| `algorithm/extension/RideExtender.java` | Use DegreeGraph for candidates + collect all valid orderings |
| `algorithm/extension/OrderingEnumerator.java` | Accept tighter constraints from sub-set orderings, remove distance B&B path |
| `algorithm/engine/ExMasEngine.java` | Build DegreeGraph after each degree, pass between iterations |
| `algorithm/extension/EnumerationStats.java` | Update profiling counters |

## Verification Plan

1. **1% Bavaria correctness:** must produce exactly 12,552 rides (unchanged from all previous correct runs)
2. **10% Bavaria performance:** compare against baseline (deg3=32s, deg4=45s, deg5=410s)
3. **Profile:** candidates processed, orderings per set, graph build time, memory usage

## Key Design Decisions

1. **Graph uses all constraint-feasible sets, not just pruned survivors.** The inter-degree pruning (top 10%) controls memory for the ride output. The graph benefits from the full structural information.

2. **Remove distance B&B to collect all valid orderings.** The cost at degree k (more orderings evaluated) is more than offset by the savings at degree k+1 (far fewer candidates + far fewer orderings per candidate). The compound effect grows with degree.

3. **Pair graph still needed alongside degree graph.** The pair graph provides FIFO/LIFO kind information that the degree graph's ordering data supplements but doesn't replace. Both coexist.

4. **Store orderings as byte arrays.** For degree ≤ 16, a permutation of k elements fits in k bytes. Compact and cache-friendly.

## References

- `docs/plans/2026-04-03-scoring-cache-and-pruning-session-log.md` — profiling data, sub-set feasibility investigation
- `docs/plans/2026-04-03-next-session-degree-specific-graph.md` — original session prompt
- `scripts/analyze_ordering_constraints.py` — Python ordering constraint analysis
- `scripts/analyze_subtriple_feasibility.py` — sub-set feasibility investigation (1% data)
