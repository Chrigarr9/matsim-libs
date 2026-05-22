# Forbidden-Prefix Index — Design (2026-04-13)

> Supersedes `2026-04-13-parent-ordering-cache-design.md` after the brainstorm
> on push-based pruning and the trie / Aho-Corasick evaluation.

## Problem

The ExMAS ride extension hits a factorial wall on origin orderings per set at high degrees (10% Bavaria, deg 7 = 2,441 orderings/set; deg 8 = 104,893/set). The current pruning stack is a global pull-based hash map (`SubSetOrderingFeasibility` with bloom filter, triple/quad/quint bitmasks indexed by sorted sub-set hash). Two issues:

1. **Per-candidate query cost.** Every candidate at every depth pays C(d,2)+C(d,3)+C(d,4) ≈ 25–50 hash lookups against a global structure. The sub-set sizes are capped at 5 ("quint") because per-candidate cost grows as C(d,k-2).
2. **Dest-phase failures unused.** Recording dest-phase fails into the same structure was abandoned ("dest-phase lookup is negative ROI under pull-based") even though those failures carry information.

## Insight

The B&B that enumerates orderings already maintains an explicit prefix of placed stops at each depth. We can **amortize sub-set lookup cost across siblings at the same depth** by precomputing "what is forbidden as the next placement?" once per descent step rather than per candidate. This shifts work from O(candidates × sub-sets) to O(placements × sub-sets), and reduces per-candidate cost to a single O(1) `IntSet` membership check.

This shift also makes **arbitrary sub-set sizes** structurally cheap: extending the recorded prefix length from 2 (triples) up to 2n−1 (full routes) only changes the per-placement lookup count, not the per-candidate check. So we drop the `maxSubsetSize` cap.

The same structure unifies origin and destination phases: stops use the existing encoding `2i` (origin of request *i*) and `2i+1` (destination of *i*), the index records arbitrary stop sequences, and one B&B descent covers both phases.

## Why not Aho-Corasick / trie

Aho-Corasick is built for **contiguous** substring matching (failure links collapse when a partial match breaks). Our recorded infeasibilities transfer by **subsequence containment** — by the triangle inequality, inserting more stops between the recorded ones only lengthens the victim's in-vehicle time, so a future route is forbidden if it contains the recorded sequence as an ordered subsequence regardless of what's between them. Aho-Corasick's failure links are useless under that semantics.

A subsequence-NFA on a trie *would* work but has unbounded per-placement cost (depends on trie shape, can approach the full trie size in pathological cases) and ~2.5× the implementation complexity of the hashmap approach. We'll keep it as a v2 promotion target if profiling justifies it, but v1 ships the hashmap.

## Data structure: `ForbiddenPrefixIndex`

```
Map<long /* orderedPrefixHash */, IntSet /* forbidden next stops */>
```

- **Key**: an ordered prefix of placed stops, of length ≥ 2. Encoded as a polynomial hash of the stop IDs in placement order. (Length-1 keys are uninteresting — they'd just say "this stop alone is forbidden as second", which the shareability graph already handles.)
- **Value**: `IntSet` of stop IDs that, when appended to the prefix, complete a known-infeasible ordered sub-sequence.

Examples:

| Recorded infeasible sequence | Insert |
|---|---|
| (A, B, C) — a length-3 origin-only fail | `index[(A,B)] += C` |
| (A, B, C, D) — a length-4 fail | `index[(A,B,C)] += D` |
| (O_v, O_a, O_b, D_v) — v times out during dropoff | `index[(O_v, O_a, O_b)] += D_v` |

Stops use the unified encoding: origin of request *i* = `2i`, dest = `2i+1`. The same index handles both phases.

Storage: `Long2ObjectOpenHashMap<IntOpenHashSet>` (fastutil). Memory is dominated by `IntSet` overhead per key; for an estimated worst case of ~5M unique keys, ~250 MB peak — well under the 100 GB envelope.

Recording is thread-safe via a pending `ConcurrentLinkedQueue<long[]>` flushed at `commit()` between degrees, mirroring the current `SubSetOrderingFeasibility` pattern.

## Recording

At each existing failure trigger:

1. **Check A (origin phase, mechanism 1)**: victim *v*'s in-vehicle time exceeds `maxTravelTime` partway through origin enumeration.
2. **Check B / Trigger 2 (all-dest-fail)**: every destination ordering for a fixed origin permutation busts a victim.
3. **(NEW) Dest-phase Check A**: victim times out during destination enumeration.

Each trigger gives us:
- The route prefix `[s₀, s₁, …, s_t]` placed when the victim first exceeded `maxTravelTime`.
- The "shortest failing prefix": from `s₀` (which is `O_v`, the victim's origin stop) up to and including `s_t` (the stop where v's accumulated time crossed the threshold).

Recording rule: `index[(s₀, …, s_{t-1})] += s_t`. **One entry per trigger**, the smallest one that proves the failure. (We don't decompose into shorter sub-prefixes — that was the bug we already fixed; a shorter prefix may still be feasible for v under different downstream stops.)

Records originating from origin phase have all-even stops in the key. Records from dest phase have mixed even/odd. Both live in the same map, queried by the same lookup logic.

## Query: per-set push-based forbidden set

During B&B descent for a set, maintain:

- `forbiddenSet` — current set of stops forbidden as the next placement, given the prefix placed so far.
- `forbiddenDeltaStack[depth]` — at each B&B depth, the IntSet of stops added to `forbiddenSet` by that depth's placement (for undo).

### On candidate check (before placing stop *X* at depth *d*):

```
if (forbiddenSet.contains(X)) { stats.prunedByForbidden++; skip; }
```

O(1).

### On placement (after committing *X* as `perm[d]`):

For each ordered subsequence of the prior placements ending at *X*:

```
for length L in 2 .. min(d+1, maxRecordedKeyLength):
    for each ordered (L-1)-tuple from positions [0..d-1]:
        prefix = (those stops..., X)
        forbidden = index.get(hash(prefix))
        if forbidden != null:
            delta.addAll(forbidden)
forbiddenSet.addAll(delta)
forbiddenDeltaStack[d] = delta
```

Per-placement cost: sum_{L=2}^{maxK} C(d, L-1) lookups. At d=6, K=7: 62 lookups. We track `maxRecordedKeyLength` globally (max over all recorded entries) to bound the inner loop in practice — most entries are length 3–6.

### On backtrack (leaving depth *d*):

```
forbiddenSet.removeAll(forbiddenDeltaStack[d])
forbiddenDeltaStack[d] = null
```

O(|delta|).

### Phase transition: origins → destinations

`OrderingEnumerator` currently runs origin enumeration and then dest enumeration as separate B&Bs. The forbiddenSet from the completed origin permutation **carries over** into the dest enumeration as the starting state — when we begin placing destinations, the prefix is all *n* origins, and `forbiddenSet` already reflects every (origin-only-prefix, next-dest) entry.

Implementation: the dest enumerator inherits the origin enumerator's `forbiddenSet` snapshot at depth *n*, and runs its own delta stack on top.

## Integration points

New file:
- `algorithm/extension/ForbiddenPrefixIndex.java` — data structure (hashmap + IntSets, pending queue, commit, getMaxRecordedKeyLength, lookup).
- `algorithm/extension/ForbiddenPrefixIndexTest.java` — unit tests for record/commit, lookup, multi-length keys, empty index.

Modified:
- `OrderingEnumerator.java`:
  - Add `forbiddenSet` + `forbiddenDeltaStack` lifecycle (alloc per set, push on placement, pop on backtrack).
  - Replace `subsetFeasibility.isInfeasible(...)` calls with `forbiddenSet.contains(stop)`.
  - Add origin→dest carry-over.
  - Add dest-phase Check A recording.
- `RideExtender.java`: pass `ForbiddenPrefixIndex` through; record on triggers.
- `ExMasEngine.java`: create `ForbiddenPrefixIndex`, commit between degrees, log size + maxKeyLength stats.
- `EnumerationStats.java`: rename existing `prunedBySubsetLookup` (or add) → `prunedByForbidden`, plus per-placement counters for diagnostics.

Removed:
- `SubSetOrderingFeasibility.java` + test (after benchmark confirms `ForbiddenPrefixIndex` is at least as fast and ride-identical).
- `OrderingConflicts.java` + test (already disabled).

Path H (heuristic insertion from top-*M* parent orderings) is **deferred** to a follow-up plan after exact-path numbers land.

## Memory analysis

| Quantity | Estimate |
|---|---|
| Unique keys at deg 7 | ~5–10M (extrapolating from current 26k triples + larger entries) |
| Avg IntSet size per key | ~2 stops |
| `Long2ObjectOpenHashMap` overhead | ~24 B/entry |
| `IntOpenHashSet` overhead | ~32 B base + 4 B/elem |
| Total | ~300–600 MB peak |

Within the 100 GB process envelope. If the empirical key count is much higher, fall back to a `Long2LongOpenHashMap` storing a packed-int representation for small forbidden sets (most are 1–2 stops).

## Testing strategy

1. **Unit tests** for `ForbiddenPrefixIndex`: record/commit, multi-length lookup, key collision, max-key-length tracking, edge cases (empty index, length-2 only).
2. **Regression test**: `ExMasKelheimE2ETest` must produce **identical ride counts** to current master at all degrees. Any drop is a correctness bug — push-based pruning is logically equivalent to pull-based and equally sound.
3. **Bavaria 10% benchmark** vs `outputs/diag-run` baseline: per-degree time and ride counts. Targets: ≥2× speedup at deg 7, ≥3× at deg 8, ride counts identical.
4. **Profile per-placement cost**: log a histogram of `(depth, lookups, addedToForbidden)` to confirm the cost model and identify any pathological sets.

## Acceptance

- `SubSetOrderingFeasibility` and `OrderingConflicts` removed from the hot path; LoC under `algorithm/extension/` decreases relative to current master.
- 10% Bavaria deg 3–8 ride counts match `outputs/diag-run` baseline.
- Total extension time on 10% Bavaria drops by ≥2× at deg 7, ≥3× at deg 8.
- `ForbiddenPrefixIndex` covers both origin and destination phases via the unified stop encoding.
- No size cap on recorded prefix length (driven by `maxRecordedKeyLength` observed at runtime).
