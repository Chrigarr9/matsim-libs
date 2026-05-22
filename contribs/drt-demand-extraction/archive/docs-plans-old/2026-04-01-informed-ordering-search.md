# Informed Ordering Search — Design Analysis

**Status:** Analysis / brainstorming — not yet an implementation plan.

## The Problem

At degree 6, the ordering-based extension evaluates ~500 orderings per candidate set. With millions of candidate sets, this takes hours. Most orderings produce rides that fail validation. We need a smarter way to find the best ordering without brute-force enumeration.

## Key Insight: Pair Rides Already Contain Segment Costs

Every segment in a degree-n ride was already routed during pair generation. The shareability graph stores which pair rides exist and their kinds (FIFO/LIFO). The network cache has their travel times and distances.

For a ride visiting [O_A, O_B, O_C, D_X, D_Y, D_Z]:
- Segment O_A → O_B: travel time known from pair(A,B)
- Segment O_B → O_C: travel time known from pair(B,C)
- Segment O_C → D_X: travel time known from pair(X,C) FIFO
- Segment D_X → D_Y: travel time known from pair(X,Y) FIFO or pair(Y,X) LIFO
- Segment D_Y → D_Z: travel time known from pair(Y,Z) FIFO or pair(Z,Y) LIFO

**We can compute any ordering's total ride distance from cached pair ride data without calling the router.**

## What We Know Per Pair

For each pair (i,j) in the set, the pair rides tell us:
- pair(i,j) FIFO route: O_i → O_j → D_i → D_j, with known segment times [t_OiOj, t_OjDi, t_DiDj]
- pair(i,j) LIFO route: O_i → O_j → D_j → D_i, with known segment times [t_OiOj, t_OjDj, t_DjDi]
- pair(j,i) FIFO/LIFO: symmetric

From these pair rides, we can extract a **segment cost lookup table**:
```
segmentCost(O_i, O_j) = t_OiOj  (from pair(i,j), first segment)
segmentCost(O_j, D_i) = t_OjDi  (from pair(i,j) FIFO, second segment)
segmentCost(O_j, D_j) = t_OjDj  (from pair(i,j) LIFO, second segment)
segmentCost(D_i, D_j) = t_DiDj  (from pair(i,j) FIFO, third segment)
segmentCost(D_j, D_i) = t_DjDi  (from pair(i,j) LIFO, third segment)
```

## Approach 1: Cost Lookup + Greedy/Branch-and-Bound

Build the segment cost table from pair rides. Then instead of routing each ordering, **compute ride distance by summing segment costs from the table.**

Combined with greedy nearest-neighbor + branch-and-bound:
1. Build segment cost table from pair rides (O(n²) lookups)
2. Use greedy nearest-neighbor to find an initial good ordering (cost = sum of segment costs)
3. Use branch-and-bound with the greedy cost as upper bound: prune branches where partial cost already exceeds the best known
4. Validate only the best ordering via actual routing (one routing call instead of hundreds)

**Advantage:** Evaluating an ordering costs O(n) additions instead of O(n) network cache lookups + validation. At degree 6, n=6, so each ordering evaluation is ~6 additions vs ~11 HashMap lookups + metric computation.

**Complication:** The segment costs from pair rides were computed at the pair ride's departure time, not the degree-n ride's cumulative departure time. With 1-hour time bins, this is usually identical, but not guaranteed. The final routing call on the best ordering handles this correctly.

## Approach 2: Graph Shortest Path on Stop Sequence Graph

Model the problem as a shortest path in a layered graph:

**Layer 0 (start):** Virtual start node
**Layers 1..n (origins):** Each layer has all n requests as nodes. Node "request i at layer k" means "request i is the k-th pickup."
**Layers n+1..2n (destinations):** Same structure for dropoffs.
**Layer 2n+1 (end):** Virtual end node

**Edges:** From node (request i, layer k) to node (request j, layer k+1):
- Edge exists only if the ordering constraint allows j to follow i
- Edge weight = segmentCost(stop_i, stop_j) from the pair ride data
- For origin layers: weight = segmentCost(O_i, O_j)
- For the O→D transition: weight = segmentCost(O_last, D_first)
- For destination layers: weight = segmentCost(D_i, D_j)

**Shortest path from start to end = optimal ordering.**

This is essentially a **constrained shortest Hamiltonian path** — NP-hard in general, but with n ≤ 16 and many pairwise constraints, the effective search space is small.

**Advantage:** Natural framework for dynamic programming or A* search. The pairwise constraints prune the graph heavily.

**Complication:** Origin and destination orderings are coupled (the origin ordering determines which pair ride direction applies, which determines destination constraints). So we can't solve origin and destination independently — they're one joint problem. The layered graph must encode this coupling.

## Approach 3: Greedy Nearest-Neighbor with Constraint Awareness

Simplest approach — O(n²) per set:
1. Build segment cost table from pair rides
2. Greedy origin ordering: at each step, among valid next pickups (predecessors satisfied), pick the one with lowest segmentCost from current position
3. Given origin ordering → destination constraints fixed → greedy destination ordering: same nearest-neighbor strategy
4. Route and validate this single ordering
5. Optionally: try a few variations (swap adjacent compatible elements) for local improvement

**Advantage:** One ordering per set. O(n²) per set. Massive speedup.
**Disadvantage:** May miss the global optimum. But with strict distance savings thresholds, most sets have 0-1 valid orderings anyway — the greedy one is likely to find it.

## Approach 4: Hybrid — Greedy + Limited Enumeration

Combine approaches:
1. Build segment cost table from pair rides
2. Use segment costs to **score** all orderings cheaply (O(n) per ordering, no routing)
3. Sort orderings by cost, take top-K cheapest
4. Route and validate only the top-K orderings

This is the "cap + greedy" idea but with **informed scoring** instead of blind nearest-neighbor at each topo sort step. Every ordering gets a cost estimate, and we only route the promising ones.

**Key difference from current cap approach:** The current greedy topo sort makes locally optimal choices at each step but can miss globally better orderings. This approach scores ALL orderings cheaply (just addition) and picks the global best-K.

But at degree 6 with 500+ orderings, scoring all of them is ~500 × 6 additions = 3000 operations per set. Still much cheaper than 500 routing calls, but we still enumerate all orderings. The enumeration itself (topological sort backtracking) is the bottleneck, not the routing.

## Recommendation

**For immediate impact: Approach 3 (greedy with segment costs)** — simplest, biggest speedup, minimal code change. One O(n²) pass per set instead of enumerating hundreds of orderings.

**For maximum quality: Approach 4 (score all orderings, route top-K)** — keeps the full enumeration but replaces expensive routing with cheap cost summation for all but the top-K. Best orderings are always found.

**For elegance: Approach 2 (layered graph)** — beautiful formulation but the origin-destination coupling makes it complex to implement correctly.

## Open Questions

1. **How to extract segment costs from the shareability graph?** The graph stores ride indices and kinds, but not the segment travel times directly. We'd need to look up each pair ride's segment times from the network cache. This is the same cache lookup the current code does — but we'd do it once per pair upfront (O(n²)) instead of per-ordering.

2. **Does the cumulative departure time matter?** Pair rides were routed at their own start time. The degree-n ride's segments have slightly different departure times. With 1-hour time bins, this rarely matters. But it means the segment cost table is an approximation — the final validation routing should use cumulative time.

3. **Is the distance savings threshold appropriate at degree 6+?** With `requiredSaving = 0.25 * log2(6) = 64.6%`, only ~2% of candidate sets produce valid rides. The threshold may be too aggressive — worth analyzing whether the scale parameter should be reduced for higher degrees.
