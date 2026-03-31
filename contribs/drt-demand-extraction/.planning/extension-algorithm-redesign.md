# ExMAS Extension Algorithm: Analysis, Discoveries & Redesign

**Date:** 2026-03-31
**Author:** Christoph Garritsen (with Claude Code)
**Branch:** `feature/exmas-traceable`
**Plan:** `docs/plans/2026-03-31-ordering-based-extension.md` (Dissertation root)

---

## 1. Motivation

The ExMAS extension algorithm extends degree-D rides to degree-(D+1) by adding one passenger at a time. The original algorithm (Kucharski & Cats, 2020) and our Java implementation had several limitations discovered during scaling to 25% Bavaria (53k requests):

1. **Memory:** `HashSet<String>` for processedSets caused OOM at degree 4 (159M candidate sets × ~120 bytes/key ≈ 19 GB)
2. **Incompleteness:** The original algorithm misses valid destination orderings
3. **Complexity:** The decomposition-based approach (base ride + insertion position) is convoluted and hard to reason about

---

## 2. Discovery: Original ExMAS `product(*E)[0]` Bug

### What we found
The original Python ExMAS uses `exts = list(product(*E))[0]` in `extensions.py`, where `E` is the list of pair ride edges per existing member. `product(*E)` computes the cartesian product of all FIFO/LIFO combinations, but `[0]` selects **only the first** — which is always the all-FIFO combination (since FIFO edges are added before LIFO in the graph).

### Empirical impact (1% Bavaria, no pruning)
We modified the Java implementation to try ALL combinations and compared:

| Degree | First-only orderings | All-combos orderings | Missed (%) |
|--------|---------------------|---------------------|------------|
| 3      | 6,913               | 9,186               | 33%        |
| 4      | 4,176               | 7,042               | 69%        |
| 5      | 1,989               | 4,895               | 146%       |
| 6      | 465                 | 2,070               | 345%       |
| 7      | 3                   | 239                 | 7,867%     |

Total rides: 26,703 (first-only) vs 50,307 (all-combos) — **88% more rides discovered**.

### Why it matters
Different FIFO/LIFO combinations for the same (base ride, new request) pair produce different destination insertion positions, leading to genuinely different rides with different routings and travel times. These are not redundant — they represent distinct service options for the MIP optimizer.

### Computational cost
Negligible. Total ExMAS execution time: 9.2s (first-only) vs 9.4s (all-combos). The extra `tryExtend` calls mostly hit the routing cache.

### Reference
- Python source: `RafalKucharskiPK/ExMAS`, `main.py`, `enumerate_ride_extensions()`
- Paper: Kucharski & Cats (2020), Transportation Research Part B, 139:285-310

---

## 3. Discovery: Directional Shareability Graph

### What we found
The `ShareabilityGraph` stores edges unidirectionally: `addEdge(A, B, ...)` only adds A→B. The `sortedNeighbors` map and `findCommonNeighborsSorted` only consider outgoing edges.

### Pair ride direction statistics (1% Bavaria)
| Direction | Count | Percentage |
|-----------|-------|------------|
| Lower-index first only | 3,113 | 41.1% |
| Higher-index first only | 3,814 | 50.4% |
| Both directions | 641 | 8.5% |

### Theoretical concern
A directed 3-cycle (A→B, B→C, C→A with no reverse edges) would be invisible to `findCommonNeighborsSorted`: no base pair can find the third member as a common outgoing neighbor.

### Empirical impact
0 additional candidate sets found at any degree (3, 4, or 5) with bidirectional lookup at 1% Bavaria. Multiple discovery paths (D+1 per set) compensate — at least one path always has all outgoing edges.

### Fix applied
Made `sortedNeighbors` bidirectional: for each edge A→B, both A and B appear as neighbors of each other. `getEdges(A,B)` remains directional (needed for FIFO/LIFO kind lookup).

### Why fix if 0 impact?
1. Theoretical correctness — the directed cycle case exists
2. Required for the ordering-based redesign — we query pair rides in both directions
3. At larger scales or different demand patterns, the fix may have real impact

---

## 4. Understanding the Extension Algorithm

### Current approach (decomposition-based)
For each candidate degree-(D+1) set:
1. Try D+1 decompositions: each element as the "added" request
2. For each decomposition: look up base rides for the remaining D elements
3. For each base ride: the base's destination ordering + FIFO/LIFO pair ride kinds determine ONE insertion position for the new request's destination
4. Route the resulting sequence, validate constraints

**Problem:** The insertion position depends on the base ride's destination ordering. With top-1-per-set (1 base ride), we get 1 ordering per decomposition. Different base orderings (discarded by top-1) would have produced different insertions.

### New approach (ordering-based)
For each candidate degree-(D+1) set:
1. Query the shareability graph for ALL pair ride info between ALL C(D+1,2) pairs, in both directions
2. Each pair ride constrains BOTH origin ordering (pickup direction) AND destination ordering (FIFO/LIFO)
3. Enumerate valid origin orderings (topological sorts of direction constraint DAG)
4. For each origin ordering: the pair ride direction per pair is fixed → FIFO/LIFO kinds give destination constraints → enumerate valid destination orderings (topological sorts)
5. Route each valid (origin, dest) ordering, validate, keep best

**Key insight:** We don't need base rides from previous degrees. The pair rides in the shareability graph directly give us ALL pairwise ordering constraints. The orderings are enumerated, not constructed incrementally.

### What pair rides tell us
For each pair (A, B) in a set, the graph may have:
- pair(A,B) FIFO: O_A before O_B, D_A before D_B
- pair(A,B) LIFO: O_A before O_B, D_B before D_A
- pair(B,A) FIFO: O_B before O_A, D_B before D_A
- pair(B,A) LIFO: O_B before O_A, D_A before D_B

Up to 4 pair rides per pair. Each constrains both origin and destination ordering. Origin and destination orderings are COUPLED through the pair ride selection.

### Routing is cached
Every segment in a valid ordering was already routed by the pair ride that justifies it being in the ordering. `network.getSegment()` is a hash map lookup, not Dijkstra. Extension evaluation is essentially free.

---

## 5. Design Decisions

### Top-1 per set per degree
Keep only the best ride (by objective function) per request set at each degree. Simplifies data structures (rides = sets), eliminates dedup complexity. Accepted tradeoff: base ordering diversity is lost between degrees.

### No beeline pre-filter for orderings
All segments are cache hits → beeline check would cost about the same as a cached routing call. No speedup.

### No max orderings cap
Every ordering is backed by existing pair rides. Real-world demand topology naturally bounds the count (99%+ of sets have ≤256 orderings at degree 4).

### requests[] array uses origin ordering
All per-passenger metric arrays (delays, travelTimes, distances) are indexed by requests[] position. Using pickup order is natural and consistent with pair rides. Verified: downstream code (BudgetValidator, RidePostProcessor, ExMasCsvWriter) only requires internal consistency.

### Bidirectional graph for completeness
Even though 0 additional sets are found at 1%, the fix is simple, theoretically correct, and required for the ordering-based approach to query both pair ride directions.

---

## 6. Branch Strategy

- **`master`**: Previous working algorithm (decomposition-based). PostExtensionPruner, config flags, CLI flags, beeline pre-filters — all production-ready changes stay here.
- **`feature/exmas-traceable`**: Ordering-based redesign. All algorithm changes (bidirectional graph, ordering enumeration, buildRideFromOrdering, simplified RideExtender). Will be validated at 1%/10%/25% before merging.

---

## 7. Expected Impact

| Metric | Previous | Ordering-based |
|--------|----------|---------------|
| Completeness | Misses 33-7800% of orderings | ALL valid orderings discovered |
| Memory (extension) | rideMap + baseRidesBySet + processedSets | Only result map + processedSets (LongOpenHashSet) |
| Computation | Many wasted tryExtend nulls | Only valid orderings routed (all cache hits) |
| Code complexity | Triple loop (decomposition × bases × combos) | Topological sort + route + validate |
| 25% degree 4 | OOM (processedSets HashSet) | Tractable (LongOpenHashSet ≈ 2.5 GB) |
