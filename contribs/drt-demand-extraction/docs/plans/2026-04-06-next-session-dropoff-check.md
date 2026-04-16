# Next Session Prompt: Dropoff Check + Conflict Recording

Copy-paste this into a new Claude Code session:

---

## Task

Implement a "Check at Dropoff" in the ExMAS ordering enumeration that checks each passenger's full in-vehicle time at the moment they're dropped off. This replaces the redundant travel time check in `buildRideFromOrdering` and is the natural place to record ordering conflicts.

## Context

We've implemented an ordering conflict learning mechanism (branch `feature/exmas-degree-graph` in matsim-libs) that stores proven-infeasible stop subsequences to prune orderings at higher degrees. The mechanism works but currently only records from 2 out of 5 travel time check points, capturing only 72k conflicts while 3.1 BILLION orderings fail in `buildRideFromOrdering`.

### The root cause: duplicate work + missing check

The destination enumeration routes every segment with cumulative departure times (for distance B&B and Check A/B). Then `buildRideFromOrdering` RE-ROUTES the entire sequence from scratch to compute per-passenger travel times. The only thing buildRideFromOrdering adds is checking the DROPPED-OFF passenger's complete travel time — which the enumeration never does.

**Current checks during destination enumeration:**
- **Check A** (depth start): checks passengers still IN VEHICLE — "is anyone already over maxTT?"
- **Check B** (after routing to candidate): checks remaining passengers — "would anyone go over maxTT?"
- **MISSING**: check the passenger BEING DROPPED OFF — "is their completed ride over maxTT?"

This missing check is why 93.5% of orderings survive Check A/B but fail in `buildRideFromOrdering`. Check A/B check passengers BEFORE the segment to their own destination. The segment to their destination can add minutes, pushing them over maxTT.

### What to implement

**1. Add "Check at Dropoff" in `enumerateDestTopoWithEval`:**

After routing to candidate c's destination (`newTime = currentTime + seg.getTravelTime()`), before Check B:

```java
// Check at Dropoff: the dropped-off passenger's ride is now complete
double fullInVehicle = newTime - pickupTimes[c];
if (fullInVehicle > requests[c].getMaxTravelTime()) {
    // c's full in-vehicle time exceeds maxTT — provable violation
    // Record conflict (stop sequence from c's origin to c's destination)
    // Skip this candidate
    stats.prunedByTravelTime++;
    continue;
}
```

**2. Record conflict from the dropoff check:**

The conflict is the stop sequence from c's origin through all intermediate stops to c's destination. Use the `pathStops` array already threaded through the enumeration.

**3. Refactor `buildRideFromOrdering` to remove redundant maxTravelTime check:**

Since the enumeration now validates every passenger at dropoff, `buildRideFromOrdering`'s `if (pttActual[i] > req.getMaxTravelTime()) return null;` check is redundant. It should still compute pttActual (needed for ride metrics) but not reject based on maxTravelTime.

Consider also: can `buildRideFromOrdering` reuse the segment data already routed during enumeration (connTT, connDist, connUtil) instead of re-routing? This would eliminate the duplicate routing entirely. The enumeration already routes every segment — the data could be accumulated in arrays passed through the recursion.

**4. Re-enable ordering conflicts in ExMasEngine:**

The ordering conflicts store (`OrderingConflicts`) is currently `null` in ExMasEngine. Re-enable it. The dropoff check will now generate many more conflicts (millions instead of 72k), all from provable absolute travel time violations.

**5. Conflict recording strategy — origin-only lookup means origin-only conflicts:**

We only look up conflicts during ORIGIN enumeration (O(2^d), cheap). Dest-phase lookup is disabled (O(2^(n+d)), too expensive). Therefore:
- **Origin-only conflicts** (from mechanism 1, Check A at dest depth 0): matchable, valuable ✓
- **Mixed origin+dest conflicts** (from Check A at dest depth 1+, from dropoff check): stored but NEVER matched ✗

The dropoff check is valuable for **immediate within-set pruning** (prune deeper dest subtrees before building the full ride). But the conflicts it generates are mixed and can't be used for cross-set learning via origin lookup.

**Open question for this session:** Can we extract origin-only information from dropoff failures? If passenger p fails at dropoff, the origin ordering contributed most of the time. Could we record just the origin portion? Risk: the origin portion alone might not cause failure (some dest orderings might work). This is the same issue as Trigger 2 — needs careful analysis.

## Key constraint: lookup must be cheaper than the computation it replaces

The conflict lookup is only worthwhile if checking the hash set costs less than actually trying the ordering and having it fail. At degree 7:
- Origin-phase lookup: 35-57 checks per candidate, ~0.5µs. Saves entire dest subtree. **Good ROI.**
- Dest-phase lookup at depth 3 (path length 10): ~600+ checks, ~10µs. Saves one subtree. **Marginal ROI.**
- Dest-phase lookup at depth 6 (path length 13): ~7,800 checks, ~100µs. Saves 1 ordering. **Negative ROI.**

Rule of thumb: only look up when pathLength ≤ 8 or so. Beyond that, just try the ordering.

## Previous session results (10% Bavaria, 21k requests)

**Mechanism 1 (origin-phase Check A):** 1.7x speedup at degree 7 (1,062s → 634s), zero quality loss. KEEP.

**Ordering conflicts with Trigger 2 (all-dest-fail):** 7.6x speedup but 15-68% ride losses. ROOT CAUSE: Trigger 2 recorded distance-B&B failures (relative threshold) as conflicts. REMOVED.

**Ordering conflicts without Trigger 2:** Correct (0.001% loss) but only 72k conflicts → 15% overhead, no net speedup. Need more conflicts from absolute violations.

**Baseline (mechanism 1 only, no conflicts):**

| Degree | Time | Rides |
|--------|------|-------|
| 3 | 25s | 1,065,484 |
| 4 | 11s | 1,581,376 |
| 5 | 44s | 1,538,152 |
| 6 | 187s | 891,593 |
| 7 | 634s | 340,761 |

## Key files

All in `matsim-libs/contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/`:

- `algorithm/extension/OrderingEnumerator.java` — enumeration with Check A/B, mechanism 1, conflict recording/lookup. **Main file to modify.**
- `algorithm/extension/RideExtender.java` — processSet, evaluateOrdering, buildRideFromOrdering. **Refactor ride building.**
- `algorithm/extension/OrderingConflicts.java` — conflict store (hash-based, already implemented)
- `algorithm/extension/EnumerationStats.java` — profiling counters
- `algorithm/engine/ExMasEngine.java` — orchestrator. Re-enable `OrderingConflicts`.

## Reference documents

- `docs/plans/2026-04-05-ordering-conflicts-design.md` — unified stop-sequence design
- `docs/plans/2026-04-05-ordering-conflicts-session-log.md` — full session log with all experiments
- `docs/plans/2026-04-05-ordering-conflicts-implementation.md` — implementation plan (Tasks 1-8)
- `docs/plans/2026-04-04-degree-specific-graph-session-log.md` — degree graph context

## Important notes

- Branch: `feature/exmas-degree-graph` in matsim-libs
- Build: `mvn compile -pl . -am -Denforcer.skip=true`
- Test: `mvn test -Dtest=ExMasDemandExtractionE2ETest -pl . -Denforcer.skip=true`
- The `pttActual` floor (`if (pttActual[i] < req.getTravelTime() - EPSILON) pttActual[i] = req.getTravelTime();`) in buildRideFromOrdering needs to be replicated in the dropoff check for consistency
- Budget validation (100% pass rate at all degrees) happens AFTER ride construction — it's separate from travel time and should stay in the evaluator
