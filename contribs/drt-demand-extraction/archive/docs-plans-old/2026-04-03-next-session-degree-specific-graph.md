# Next Session Prompt: Degree-Specific Shareability Graph Design + Implementation

Copy-paste this into a new Claude Code session:

---

## Task

Brainstorm and design a degree-specific shareability graph for ExMAS ride extension, then implement and validate against the current approach. This is the next major optimization after scoring cache + travel time pruning.

## Context

We've been optimizing the ExMAS ride extension algorithm over the last two sessions. Current state:

**What's implemented (branch `feature/exmas-traceable` in matsim-libs):**
- Pruned greedy enumeration with ordering-based extension
- Inter-degree 10% pruning (keep top rides by distance savings)
- Inline evaluation with tighten-on-valid branch-and-bound
- Scoring context cache (budget validation ~1ms → ~0.05ms)
- Per-passenger travel time pruning in ordering enumeration (catches 68% of failing orderings)

**Current performance (10% Bavaria, 21k requests):**
- Degree 3: 27s, degree 4: 41s, degree 5: 392s (6.5 min), degree 6 ETA ~1-1.5h
- Combined speedup from last two sessions: 4.8x at degree 5

**Remaining bottleneck:** At degree 5, there are 26.6M candidate sets generating 1.55B orderings. 94.5% of orderings still fail. The number of candidate sets is the main driver — each base set generates ~96 candidates by checking common neighbors in the PAIR graph.

## The Idea: Degree-Specific Shareability Graph

Currently, ALL degrees use the pair graph for candidate generation:
```
pair graph → deg 3 → deg 4 → deg 5 (always pair graph)
```

Proposed: build a new graph at each degree from validated rides:
```
pair graph → deg 3 → build deg-3 graph → deg 4 → build deg-4 graph → deg 5
```

Each degree-k graph has:
- **Nodes:** valid degree-k rides (constraint-feasible, not just pruning-survivors)
- **Edges:** connect rides sharing k-1 passengers
- **Candidate generation:** pairs of connected rides → degree-(k+1) candidate

This unifies three optimization ideas:
1. **Automatic negative cache:** Failed subsets aren't nodes → supersets never generated
2. **Ordering constraints:** Each node stores valid orderings → constrains higher-degree enumeration (like FIFO/LIFO in the pair graph, but for triples/quads)
3. **Stronger candidate filtering:** Requires ≥2 valid sub-rides (vs current: 1 base + pairwise compatibility)

## Key Finding from Investigation

We verified empirically (1% Bavaria, `scripts/analyze_subtriple_feasibility.py`):

**If a sub-triple fails CONSTRAINT checks (maxTravelTime, budget, delays), adding a 4th passenger provably can't help** — more stops = more detour = worse for existing passengers.

The 18 "missed" degree-4 rides (5.8%) in the initial analysis were ALL caused by distance savings pruning thresholds, NOT constraint infeasibility. Building the graph from CONSTRAINT-FEASIBLE rides (ignoring distance savings) gives **0% miss rate**.

**Candidate reduction estimates (1% data):**
| Degree | Current candidates | Graph candidates | Reduction |
|--------|-------------------|-----------------|-----------|
| 4 | 4,072 | 2,475 | 39% fewer |
| 5 | 1,498 | 540 | 64% fewer |
| 6 | 301 | 75 | 75% fewer |

## What to brainstorm

1. **Graph data structure:** What should nodes and edges store? How to build efficiently?
2. **Constraint-feasible vs pruning-survivor:** The graph needs constraint-feasible rides as nodes, but the current algorithm discards rides that fail distance savings. Need to separate these two concepts.
3. **Ordering constraint propagation:** How to use sub-ride orderings to constrain higher-degree enumeration? The pair graph stores FIFO/LIFO per pair. What's the equivalent for triples?
4. **Candidate generation:** How to enumerate degree-(k+1) candidates from the degree-k graph? Index by (k-1)-subsets for efficient lookup.
5. **Integration with existing code:** How does this fit into ExMasEngine's extension loop?

## After brainstorming, if design is solid

1. Implement the degree-specific graph
2. Run 1% Bavaria → verify exact 12,552 rides (correctness)
3. Run 10% Bavaria to degree 5 → compare timing and candidate counts against baseline:
   - Baseline (with scoring cache + travel time pruning): deg3=27s, deg4=41s, deg5=392s
   - Expected: significant reduction at deg 4-5 from fewer candidate sets

## Key files

All in `matsim-libs/contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/`:
- `algorithm/engine/ExMasEngine.java` — orchestrator, extension loop
- `algorithm/extension/RideExtender.java` — candidate generation + processSet
- `algorithm/extension/OrderingEnumerator.java` — ordering enumeration with B&B
- `algorithm/graph/ShareabilityGraph.java` — current pair-level graph
- `algorithm/domain/Ride.java` — ride data model

## Reference documents

- `docs/plans/2026-04-03-scoring-cache-and-pruning-session-log.md` — comprehensive session log with all profiling data, investigations, and design discussion
- `docs/plans/2026-04-02-exmas-optimization-session-log.md` — previous optimization session
- `scripts/analyze_subtriple_feasibility.py` — investigation script (sub-set feasibility + candidate generation comparison)
- `scripts/analyze_missed_rides.py` — investigation script (why sub-triples appear invalid)

## Important notes

- Branch: `feature/exmas-traceable` in matsim-libs
- 1% correctness check: must produce exactly 12,552 rides
- The ordering analysis (Analysis 3 in the session log) had a bug — compared link IDs vs request indices. Sub-ordering reuse potential is UNKNOWN and should be properly investigated.
- Currently top-1 ride per set. Consider whether top-K or all valid orderings would enable richer ordering constraints for the next degree.
