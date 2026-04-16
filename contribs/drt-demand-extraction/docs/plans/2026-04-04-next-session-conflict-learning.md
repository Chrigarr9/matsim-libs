# Next Session Prompt: Conflict Clause Learning for Ordering Enumeration

Copy-paste this into a new Claude Code session:

---

## Task

Brainstorm and design a conflict clause learning mechanism for the ExMAS ordering enumeration. This is the next major optimization after the degree-specific graph.

## Context

We've implemented a degree-specific graph (branch `feature/exmas-degree-graph` in matsim-libs) that provides:
1. **Candidate reduction:** 82-93.5% fewer candidates at degree 4-5 (builds graph from feasible sets, generates candidates via extension index)
2. **Ordering consensus:** pairwise direction bitmask from valid orderings → 55% ordering reduction at degree 7

**Current results (10% Bavaria):**
- Degrees 3-5: 460s → 99s (4.6x faster)
- Degree 6: ~3 min (was ~1h)
- Degree 7: 18 min (was unreachable)
- Degree 8: ~1.3h (factorial wall on orderings per candidate)

**The remaining bottleneck:** orderings per candidate grow ~10x per degree (103 → 934 → 9,215 → ~100k). At degree 7, 93.5% of evaluated orderings fail constraint checks (travel time). We evaluate ~9,215 orderings per set but only ~600 pass. The 8,600 failed orderings carry unused information.

## The Idea: Conflict Clause Learning

Inspired by CDCL in SAT solvers. When the ordering enumerator prunes a branch (travel time Check A/B fires), learn the **minimal prefix** that caused failure and avoid it in future sets.

### Current system (pairwise consensus — heuristic):
- Records which pairwise directions appear in **valid** orderings
- If only A→B seen, assumes A→B is the right direction
- Limitation: not provable (B→A might be valid but pruned by B&B distance), pairwise only

### Proposed (conflict prefixes — provable):
- When Check A fires: "partial origin ordering [A, C, F] caused passenger A to exceed maxTravelTime"
- By monotonicity: adding more passengers between A's pickup and dropoff only increases A's travel time
- Therefore prefix [A, C, F] is **provably infeasible** for any set containing A, C, F at any higher degree
- This is a **3-way constraint** that pairwise consensus cannot express

### Why this could be transformative:
1. **Provable vs heuristic:** Failed prefixes are monotonically infeasible. Consensus is just observation.
2. **Richer than pairwise:** Prefix [A,C,F] captures sequential interaction. "A before C" and "C before F" both work individually, but the sequence [A,C,F] fails.
3. **Targets the bottleneck directly:** 93.5% of orderings fail → if we predict and skip 90% of those, orderings/set drops from 9,215 to ~1,000.
4. **Analogy: CDCL in SAT solvers** learns minimal conflict clauses and avoids repeating them.

## What to brainstorm

1. **Minimal conflict prefix:** When Check A fires at depth d during origin enumeration, what is the minimal prefix? Is it always [perm[0]...perm[d]]? Or can we identify a shorter subset that causes the failure (e.g., just [A, F] if A's travel time exceeds limit regardless of what's between A and F)?

2. **Data structure:** How to store and look up conflict prefixes efficiently?
   - Prefix trie indexed by request ID sequences?
   - Hash set of prefix hashes?
   - Global transition feasibility graph (nodes = requests, edges = "A immediately before B is feasible")?
   - Per-pair or per-triple conflict sets?

3. **Aggregation across sets:** Prefix [A,C,F] fails in set {A,B,C,D,E,F}. Does it necessarily fail in {A,C,F,G,H,I}? (Yes, by monotonicity — the prefix routing is the same regardless of later passengers.) How to store this efficiently?

4. **Integration with existing enumeration:** The topological sort recursion builds orderings position by position. At each depth, we choose the next request. How to check: "is the current partial ordering a known conflict prefix?" Needs to be fast (called millions of times).

5. **Destination orderings too?** Check B fires during destination enumeration. Same idea applies — destination prefixes can be conflict clauses.

6. **Interaction with B&B:** Distance B&B also prunes branches. Those are NOT provable conflicts (the ordering might be valid, just long). How to distinguish constraint-pruned (provable) from distance-pruned (not provable)?

7. **Memory scaling:** At degree 6 with 966k sets and ~900 orderings each, how many unique conflict prefixes? Can we limit to length-2 or length-3 prefixes for practicality?

## Key files

All in `matsim-libs/contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/`:
- `algorithm/extension/OrderingEnumerator.java` — where Check A/B fire, where conflict learning would be integrated
- `algorithm/extension/RideExtender.java` — processSet, evaluateOrdering, tightenConstraints
- `algorithm/graph/DegreeGraph.java` — degree-specific graph with extension index + consensus bitmask
- `algorithm/engine/ExMasEngine.java` — orchestrator, builds graph between degrees

## Reference documents

- `docs/plans/2026-04-04-degree-specific-graph-session-log.md` — full session log with all data, measurements, design decisions
- `docs/plans/2026-04-04-degree-specific-graph-design.md` — original design document
- `docs/plans/2026-04-03-scoring-cache-and-pruning-session-log.md` — travel time pruning (Check A/B) implementation details

## Important notes

- Branch: `feature/exmas-degree-graph` in matsim-libs (6 commits ahead of `feature/exmas-traceable`)
- The existing consensus bitmask can coexist with conflict learning — they're complementary
- 1% correctness check: 12,510 rides (42 fewer than baseline 12,552 due to time-dependent routing, 0.3% loss)
- Budget pass rate is 100% at all degrees — constraint checks (maxTravelTime) are the only filter
