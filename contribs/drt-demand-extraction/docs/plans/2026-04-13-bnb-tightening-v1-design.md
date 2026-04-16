# B&B tightening v1 — parent-seeded DFS + lower-bound cut

**Date:** 2026-04-13
**Branch target:** `feature/bnb-tightening-v1` (matsim-libs submodule, off `feature/exmas-degree-graph`)
**Status:** Design, not yet implemented

## Motivation

After the delay-window check (2026-04-13 morning), the deg-6 evaluator funnel on Bavaria 10% is:

| Bucket | % of evaluated orderings |
|---|---:|
| ride-null (constraint) | 0.0% |
| valid-but-worse | **98.7%** |
| new-best | 0.2% |
| budget-fail | 0.0% |

128M orderings evaluated. 98.7% are built, routed, budget-validated, and rejected only because they are worse than the current best. The remaining speedup lives in this funnel: prune those 98.7% via a tighter distance bound, earlier.

Two current weaknesses feed this:

1. **Loose initial bound.** `RideExtender.processSet` seeds `bestValidDist[0]` from `computeMaxAllowedRideDistance = (1 − requiredSaving) · sumDirectDistances`, which is nearly the pessimistic ceiling for the set. The B&B predicate never fires during the early DFS branches because no candidate comes close to exceeding a ceiling that loose.

2. **No lower-bound on remaining distance.** The cut predicate is `partialDist > bestValidDist[0]`. At deg 6, origin B&B cuts skip only 1.05–1.11 candidates per cut event — the predicate fires on the very last candidate at leaf depth, long after most of the cost has been paid. A predicate of the form `partialDist + LB(remaining) > bestValidDist[0]` would cut at shallower depths.

These two weaknesses compound: a loose bound makes the LB-based predicate fire rarely even if we add it; and a tight LB without a tight bound still doesn't fire at shallow depths. Fixing them together is the point of this branch.

## Non-goals

- Within-set triangularity pruning (user's Idea 3) — separate branch after 1+2 is measured.
- Multi-parent seeding (use more than one parent's ordering as DFS seed) — deferred; start with single best parent.
- Budget-validation skip — noted as future work but unrelated to B&B.
- 0.3% delay-window ride-count drift — still on open questions list from the morning session.

## Change 1 — parent-consistent DFS sort bias

### Idea

The current origin DFS (`OrderingEnumerator.enumerateOriginsPrunedWithEval`, lines 288–335) sorts candidates at each depth by cheapest-next-segment distance. We add a primary sort key: **parent-consistent candidates come first**.

A candidate at origin DFS depth `d` is parent-consistent if it is either:
- **the new request** (the element of the k-set not in the chosen parent), *or*
- **the next un-placed parent request in the parent's own origin order** (i.e., the parent request with the smallest parent-origin-index among those not yet in the current prefix).

Tie-breaker: cheapest-next-segment (the current sort), so that when only one parent-consistent candidate exists, we still pick among the rest by the current heuristic. The new request and the next parent request may both be parent-consistent at the same depth; that's fine — whichever has the shorter next segment goes first.

Same idea for the dest DFS, using the parent's dest order.

### Why this works

At any depth of the child DFS, the parent-consistent subtree is the set of orderings that preserve the parent's relative origin order and insert the new request somewhere. Following the parent-consistent branches greedily reaches one of these parent-insertion orderings early in DFS. Assuming the parent ride was distance-feasible, a parent-insertion ordering is very likely also feasible (it differs from the parent only by one extra stop), so `buildRideFromOrdering` returns non-null, `bestValidDist[0]` tightens, and every subsequent non-parent-consistent branch is B&B-cut by Change 2's LB predicate.

The optimality is not compromised: the DFS still enumerates the full candidate tree; the sort-bias only re-orders the visit sequence. Every branch that the new comparator de-prioritizes is still visited later, subject to the tighter bound.

### "Best parent" selection

Per the user, the seed parent should be the **shortest** among all k (k−1)-subset parents of the child k-set. This requires knowing, for each child k-set, which k-ride among the feasible (k−1)-parents has the minimum ride distance.

Implementation:

1. At the start of `RideExtender.extendRides`, build a concurrent `Map<Long, Ride> parentRideByHash` from `ridesToExtend` — one entry per parent set-hash (there is already exactly one ride per set at the prior degree). Cheap, O(parents).
2. Inside the parallel walk (after `claimedSets.add(newSetHash)`), enumerate the k (k−1)-subsets of `newSet`, look each up in `parentRideByHash`, and pick the one with the smallest `getRideDistance()`. That is the seed parent.
3. Pass `seedParent` into `processSet(newSet, newSetHash, targetDegree, seedParent)`.

Note: not every (k−1)-subset may have a feasible parent ride — only those that were generated at the prior degree. `DegreeGraph.findExtensions` guarantees at least one feasible (k−1) parent exists for any child it emits, because the child was built by extending that parent. So at least one lookup succeeds; pick the minimum over whatever subset lookups return non-null.

For degree 3 specifically, parents are pair rides. Pair rides have trivial orderings (two elements, two permutations), but they still provide a "parent-consistent" signal: the DFS will visit the pair's own relative origin order first. Overhead at degree 3 is small because the sort is over ≤3 candidates.

### Plumbing changes

- `RideExtender`:
  - New field: `Map<Long, Ride> parentRideByHash`, built in `extendRides` after computing `uniqueBaseSets`.
  - `processSet` signature: add `Ride seedParent` parameter (nullable — but in practice always non-null after the subset-min-lookup step).
- `OrderingEnumerator`:
  - New entry point `enumerateAndEvaluateSeeded(int[] requestIndices, ShareabilityGraph, MatsimNetworkCache, DrtRequest[], double[] bestValidDist, int[] seedOriginOrder, int[] seedDestOrder, int seedInsertIdx, Consumer<Ordering>)`.
    - `seedOriginOrder`: length k−1 array of request indices (the parent's origin order, with indices in the parent's own local numbering re-mapped to the child k-set's local numbering).
    - `seedDestOrder`: same for destinations.
    - `seedInsertIdx`: the child-local index of the new request (the one not in `seedOriginOrder`).
  - The origin DFS comparator is updated: primary key is "parent-consistent rank" (0 for next-parent or new-request, 1 otherwise), secondary key is next-segment distance. Same for the dest DFS.
  - "Next parent request" at depth `d` is computed as: walk `seedOriginOrder` and return the first element whose child-local index is not in the `used[]` array at this depth. O(k) per depth — trivial at k ≤ 8.

## Change 2 — lower-bound B&B cut (Flavor A)

### Idea

Admissible lower bound on the remaining distance in any completion of the current partial ordering:

```
minIn[stop]   = min over all other stops s in the k-set of dist(s → stop)
totalMinIn    = sum over all 2k stops of minIn[stop]
LB(remaining) = totalMinIn − sum over placed stops of minIn[placed]
```

Soundness: for every feasible completion, every remaining stop (remaining origin or remaining destination) is entered by some segment from another stop in the set. The distance of that segment is ≥ `minIn[stop]`. So the sum of remaining segments is ≥ `LB(remaining)`. Adding `LB(remaining)` to `partialDist` gives an admissible lower bound on the completed ride distance. The B&B cut `partialDist + LB(remaining) > bestValidDist[0]` is therefore sound: any ordering that would pass it has distance strictly greater than the current best, and can be pruned without loss of optimal orderings.

Worth noting why the LB uses only incoming segments: every stop (except the very first) contributes exactly one incoming segment to the ride path. The first pickup has no incoming segment — but it is also always placed at depth 0, so by the time `LB(remaining)` is evaluated (depth ≥ 1 in origin DFS), every remaining stop genuinely has an incoming segment in the completion. Including `minIn[firstPickup]` in the total is then double-counting unless it's subtracted when depth 0 places it — which is exactly how the O(1) maintenance handles it.

### State and maintenance

- Per-set precompute at the start of `enumerateAndEvaluateSeeded`:
  - `minIn` double[2k]: indexed by child-local stop id (0..k−1 for pickups, k..2k−1 for dropoffs).
  - Compute by calling `network.getSegment(otherLink, thisLink, 0.0).getDistance()` over every other stop in the set. Cached in `MatsimNetworkCache`, so at k=6 this is 132 hash lookups, most of which are already in cache from pair/extension precomputation.
  - `totalMinInRemaining`: double, initialized to `sum(minIn)`.
- Per-descent update (origin DFS enter, inside the candidate loop at `OrderingEnumerator.java:322` after `used[c] = true`):
  - `totalMinInRemaining -= minIn[c]` (pickup stop `c` is now placed).
- Per-backtrack update (after the recursive call, at `OrderingEnumerator.java:334` before `used[c] = false`):
  - `totalMinInRemaining += minIn[c]`.
- Same pattern in dest DFS, using the dest-stop child-local index `c + k` to select from `minIn`.

Since `totalMinInRemaining` is a scalar passed by value into the recursive call (Java primitive semantics), no explicit save/restore is needed — each recursive invocation sees its own updated copy, and the caller's copy is unchanged. This is the same trick used by `currentL`/`currentU` in the delay-window check.

### Predicate change

Origin DFS, currently at lines 298–304:

```java
if (newPartialDist > bestValidDist[0]) {
    EnumerationStats s = EnumerationStats.get();
    s.bnbOriginCuts++;
    s.bnbOriginSkippedCandidates += (candCount - idx);
    break;
}
```

Becomes:

```java
double newRemaining = totalMinInRemaining - minIn[c];
if (newPartialDist + newRemaining > bestValidDist[0]) {
    EnumerationStats s = EnumerationStats.get();
    s.bnbOriginCuts++;
    s.bnbOriginSkippedCandidates += (candCount - idx);
    break;
}
```

Same shape for the dest DFS at lines 442–448.

The `break` (not `continue`) is preserved: the sort is ascending by next-segment distance, and the LB predicate is monotone in next-segment distance (`newPartialDist + newRemaining` grows as `seg.getDistance()` grows, and `newRemaining` is identical for all candidates at the same depth because it depends only on `totalMinInRemaining − minIn[c]` and `minIn[c]` is bounded by `segDist` but unrelated to sort order). Actually this needs care: `minIn[c]` depends on which candidate we're considering, so the LB-augmented predicate is *not* strictly monotone in the same order as the original. Two options:

1. **Keep `break`.** Prove monotonicity: for candidate `c` at loop index `idx`, `minIn[c] ≤ seg.getDistance()` by definition of `minIn`. So `newPartialDist + newRemaining = partialDist + seg.getDistance() + (totalMinInRemaining − minIn[c]) ≥ partialDist + totalMinInRemaining`, which is candidate-independent. But we want monotonicity in `idx`, and `seg.getDistance() − minIn[c]` is not necessarily sorted. So `break` is unsound.

2. **Switch to `continue`.** Correct but loses the "skip remaining candidates" benefit of the original break. Counter event becomes "number of skipped candidates" — a continue-based implementation still skips the candidate, just doesn't skip siblings.

3. **Use the candidate-independent lower bound inside the `break`.** Predicate: `partialDist + totalMinInRemaining > bestValidDist[0]`. Since this is candidate-independent, if it fires at any candidate it fires at the next one too, so `break` is sound. Downside: strictly weaker than `newPartialDist + newRemaining` because it doesn't include the `c`-specific segment cost. But at depth `d` with `2k−d` remaining stops, `totalMinInRemaining` already includes `minIn[c]`, which is an admissible lower bound on `seg.getDistance()` for stop `c` — so this predicate is `partialDist + (all remaining stops' LBs including c) > bestValidDist[0]`, compared to the current `partialDist + seg.getDistance() > bestValidDist[0]` which uses the actual segment distance for `c`.

**Decision:** use Option 3 (evaluated once **before** the candidate loop, since `totalMinInRemaining` is identical for all candidates at this depth). If the predicate fires, skip the entire loop. Additionally, keep an inner-loop check `newPartialDist > bestValidDist[0]` exactly as before (per-candidate, unchanged) so we still catch the case where a large incoming segment for one candidate blows past the bound even if the LB would not. Both cuts active, counted separately for diagnostics.

```java
// Outer cut: LB-based, candidate-independent, sound to break early
if (partialDist + totalMinInRemaining > bestValidDist[0]) {
    EnumerationStats s = EnumerationStats.get();
    s.bnbOriginLbCuts++;
    s.bnbOriginLbSkippedCandidates += candCount;
    return; // whole subtree is pruned, nothing to enumerate
}

// Inner cut: current predicate, unchanged
for (int idx = 0; idx < candCount; idx++) {
    ...
    double newPartialDist = partialDist + seg.getDistance();
    if (newPartialDist > bestValidDist[0]) { /* break, bnbOriginCuts++ */ }
    ...
}
```

Note that the outer LB cut returns from the whole function (not `break`) because if the partial ordering is already too expensive, *no* candidate extension can recover — the next depth would only add more distance. This is a strictly stronger pruning than the current inner break.

## Diagnostics

New `EnumerationStats` counters:

- `bnbOriginLbCuts`, `bnbOriginLbSkippedCandidates` — for the outer LB cut in origin DFS.
- `bnbDestLbCuts`, `bnbDestLbSkippedCandidates` — for the outer LB cut in dest DFS.
- `parentSeedRidesFound` — number of sets where the DFS found a first-valid ride within the parent-consistent subtree (for sanity: should be close to 100% of feasible sets).

Existing `bnbOriginCuts` / `bnbOriginSkippedCandidates` continue to measure the inner (segment-specific) cut for comparison.

Log block in `EnumerationStats.log` adds:
- "LB-based B&B cuts (origin): X events, Y candidates skipped"
- "LB-based B&B cuts (dest): X events, Y candidates skipped"
- "Parent seed effectiveness: X / Y sets (Z%)"

## Soundness proof for Change 2

Claim: every ordering that the LB cut prunes has strictly greater total distance than `bestValidDist[0]`.

Proof: at the moment the cut fires, `partialDist` is the exact cumulative segment distance from depth 0 to the current depth. `totalMinInRemaining` is `Σ minIn[stop]` over stops not yet placed. For any completion of this partial ordering, the ride's total distance equals `partialDist + Σ_{stop ∈ remaining} actualSegmentInto[stop]`. Since `actualSegmentInto[stop] ≥ minIn[stop]` by definition (minIn is the minimum over all possible predecessor stops in the set, and any actual completion picks some predecessor), we have `total ≥ partialDist + totalMinInRemaining`. The cut fires only when `partialDist + totalMinInRemaining > bestValidDist[0]`, so `total > bestValidDist[0]`, meaning this completion is strictly worse than the current best and its pruning loses no optimal ordering. ∎

## Verification plan

1. **Unit test — admissibility of LB.** Construct a synthetic 3- or 4-set with known segment distances. For every possible complete ordering, verify that `LB(remaining)` at every depth is ≤ the actual remaining distance of that completion. Failure indicates a bug in `minIn` or maintenance.

2. **Unit test — parent-seed optimality preservation.** Construct a 4-set where the optimum ordering is *not* parent-consistent (i.e., the optimum differs from the parent's relative order). Run `enumerateAndEvaluateSeeded` with a parent that gives a valid-but-suboptimal insertion ordering, and verify the returned best ride is still the optimum (DFS must explore past the first parent-consistent valid ride).

3. **Kelheim E2E.** `ExMasKelheimE2ETest`. Expected result: 703/243/451/8/1 (exact match). Any deviation blocks the branch.

4. **Bavaria 10% measurement.** Run `--max-degree 6` with the branch versus current `feature/exmas-degree-graph` head, report:
   - Per-degree `orderingsEvaluated` (want: much lower)
   - Per-degree CPU time (want: much lower)
   - `bnbOriginCuts` and `bnbOriginSkippedCandidates` (want: higher skip/cut ratio)
   - New `bnbOriginLbCuts` / `bnbOriginLbSkippedCandidates` (want: meaningful, > 0 at every degree)
   - `parentSeedRidesFound` / `setsProcessed` (want: high, > 90%)
   - `newBestRides` (want: comparable to baseline within noise)
   - Final ride count at each degree (want: exact match if possible, else within the 0.3% drift tolerance already flagged)

5. **Bavaria 10% `--max-degree 7`.** Target: bring deg 7 under 5 minutes (currently ~7 min post-morning-changes extrapolated). Not a blocker — headline is deg 6 speedup.

## Risks and rollback

- **Parent-lookup overhead at high parallelism.** Looking up k (k−1)-subsets per new set inside the parallel walk adds k hash lookups to a hot code path. At k=7 that's 7 extra lookups per set. Should be negligible; mitigate via a thread-local hash buffer if profiling shows it matters.

- **LB precompute hitting non-cached segments.** At the start of `enumerateAndEvaluateSeeded`, computing `minIn[2k]` touches 2k·(2k−1) segments. Most are hit by the existing enumeration anyway, but doing them up-front means the cache population is front-loaded. No semantic risk, but first-iteration measurement may be skewed. Use warm-cache runs only for comparisons.

- **`break` vs `continue` reasoning in LB predicate.** If Option 3's "outer LB cut, inner unchanged" reasoning has a flaw, either (a) we over-prune and miss rides (E2E will catch it immediately, 703/243/451/8/1 mismatch), or (b) we under-prune and lose speedup (no correctness risk, just smaller measured improvement). Both are caught by the verification plan.

- **Degree-3 parent-seeding overhead.** Pair rides have trivial orderings, but we still pay the parent-lookup cost and comparator branches. At degree 3 this runs over 2-element candidate lists, so the overhead is tiny compared to the ride-build cost. Not expected to matter.

- **Branch rollback.** Single branch, two coupled changes. If Change 2 destabilizes, `git revert` Change 2's commit and keep Change 1 as a standalone win. Change 1 alone tightens the initial bound even without the LB predicate, via faster valid-ride discovery and the existing inner cut — likely a 1.5–2× speedup by itself.

## Expected outcome (rough estimates, to be replaced by measurement)

Working backwards from the 98.7% valid-but-worse figure:

- Change 1 alone: DFS finds a valid ride within the first O(k²) leaves instead of the first O(k!) leaves. At k=6 this is ~30 vs 720 leaves to first valid — but `bestValidDist[0]` is only useful if the B&B predicate then cuts the remaining 720−30 leaves. With the current inner-only predicate and a tighter bound, expect the cut to fire at depth ≈ k−1 instead of depth ≈ k. Rough: 1.5–2× speedup at deg 6.

- Change 2 alone (without tighter initial bound): LB predicate fires only rarely because `bestValidDist[0]` starts at `maxAllowedRideDistance`. Rough: 1.1–1.3× speedup.

- Changes 1 + 2 combined: multiplicative — tight bound + admissible LB cut firing at shallow depths. Rough: 3–5× speedup at deg 6. If achieved, Bavaria deg 6 drops from ~1580 s post-morning to ~400 s.

These are very rough; the measurement is what matters.

## Files to touch

- `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/RideExtender.java` — build `parentRideByHash`, subset-min lookup, thread `seedParent` into `processSet`.
- `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingEnumerator.java` — new `enumerateAndEvaluateSeeded` entry, sort comparator with parent-consistent primary key, `minIn[]` precompute, `totalMinInRemaining` threading, LB-based outer cut in both DFS phases.
- `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/EnumerationStats.java` — new counters and log lines.
- Tests: new unit tests for LB admissibility and parent-seed optimality preservation under `contribs/drt-demand-extraction/src/test/java/org/matsim/contrib/demand_extraction/algorithm/extension/`.

## Cleanup to piggyback (from morning code review)

Unrelated to the B&B work but in the same area:

- Delete `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingConflicts.java` and its test `OrderingConflictsTest.java`. Dead code, no production caller, leftover from the pre-forbidden-prefix cleanup that the 2026-04-13 morning cleanup missed.

Do this as a separate commit at the head of the branch, before the Change 1/2 commits.

## Commit sequence

1. `cleanup: delete OrderingConflicts (dead code from prior cleanup)`
2. `feat: parent-consistent DFS sort bias (Change 1)` — includes `parentRideByHash` plumbing, `seedParent` parameter, comparator update, `parentSeedRidesFound` counter, unit test for optimality preservation.
3. `feat: LB-based outer B&B cut with minIn precompute (Change 2)` — includes `minIn[]` precompute, `totalMinInRemaining` threading, outer cut predicate, new counters, unit test for admissibility.
4. `measure: Bavaria 10% --max-degree 6 B&B tightening v1` — session log with numbers, no code changes.
