# Ordering Conflicts — Design

## Problem

The ExMAS ordering enumerator tries many orderings that are provably infeasible. At degree 7 (10% Bavaria), 93.5% of 9,215 orderings per set fail constraint checks. These failures carry unused information: the specific stop sequence that caused a passenger to time out. Currently, this information is discarded. If we recorded and reused it, we could skip those orderings in future sets at the same and higher degrees.

## Core Insight

Every feasible ride is a sequence of 2n stops: n origins followed by n destinations. A passenger's in-vehicle time is determined by the stops between their pickup (origin) and dropoff (destination). By the **triangle inequality**, inserting additional stops between any two points only increases the path length. Therefore:

> If a stop subsequence causes a passenger to time out, any longer sequence containing that subsequence also causes a timeout.

This is a **monotonic, transferable constraint**. A failure observed at degree k is guaranteed to recur at all higher degrees where the same subsequence appears.

## Key Observation: Pairwise Is Already Captured

The shareability graph already encodes pairwise ordering constraints:
- No A→B pair ride exists → origin ordering forced to B before A
- Only FIFO for A→B → destination ordering forced to D_A before D_B

Any pairwise conflict graph would be completely redundant with the existing system. **The value is exclusively in higher-order constraints** — interactions among 3+ passengers that can't be decomposed into pairs.

Example: pair(A,B) is feasible with A first. pair(A,C) is feasible with A first. But origin ordering [A, B, C] causes A to time out because the combined detour via O_B and O_C exceeds A's maxTravelTime, even though each individual detour was within budget.

## Solution: Unified Stop-Sequence Conflicts

### The stop-sequence model

Instead of tracking constraints over requests (which have separate origin and destination orderings), track constraints over **stops**. Each request i contributes two stops:

```
Origin stop:      stopId = i << 1       (even number)
Destination stop: stopId = (i << 1) | 1  (odd number)
```

The full route is a single path of 2n stops:

```
[O_perm[0], O_perm[1], ..., O_perm[n-1], D_destPerm[0], D_destPerm[1], ..., D_destPerm[n-1]]
```

A **conflict** is an ordered subsequence of stop IDs that, whenever it appears in a route, causes a specific passenger to time out. Conflicts are stored as polynomial hashes in a `LongOpenHashSet`, grouped by length.

### What conflicts look like

At degree 3 with origin ordering [A, B, C]:

| Conflict | Length | Type | Meaning |
|----------|--------|------|---------|
| (O_A, O_B, O_C) | 3 | Pure origin | A busted by origin traversal alone |
| (O_A, O_B, O_C, D_C) | 4 | Mixed | A busted after C dropped off (LIFO for B-C) |
| (O_A, O_B, D_B) | 3 | Mixed | A busted in LIFO(A,B) — D_B before D_A |
| (O_A, O_B, O_C, D_C, D_B) | 5 | Mixed | A busted after C and B dropped off |

All are valid, all transfer by monotonicity. **One encoding captures origin-only, destination-only, and combined failures uniformly.**

### Why this is simpler than separate origin/destination stores

| Aspect | Two stores (origin + dest) | Unified stop sequence |
|--------|---------------------------|-----------------------|
| Data stores | 2 | **1** |
| Recording triggers | 3 (origin Check A, all-dest-fail, dest Check A) | **1** (any Check A fire) |
| Lookup functions | 2 (origin phase, dest phase) | **1** |
| Encoding | Request IDs, victim-tagged | **Stop IDs** |
| At degree 3 | Max conflict length 3 (origin only) | **Max length 6** (full route) |

## Monotonicity Proof

**Claim**: If stop subsequence S causes passenger V to time out, then any route containing S as a subsequence also causes V to time out.

**Proof**: V's in-vehicle time is the total travel time from O_V to D_V along the route, visiting all intermediate stops. By the triangle inequality on the road network:

```
T(A → X → B) ≥ T(A → B)    for any intermediate stop X
```

Inserting a stop between any two consecutive stops in V's in-vehicle path can only increase the total time. A longer route containing S has all the stops of S plus additional stops between them. Each additional stop increases (or preserves) V's in-vehicle time. Since V already exceeded maxTravelTime with subsequence S, V also exceeds it with any superset of S.

**Corollary**: Conflicts learned at degree k are valid at all degrees ≥ k. Adding passengers to a set only adds stops to the route, making things worse.

**Practical note**: Time-dependent routing may cause rare violations (~0.3%, same as the degree graph). The structural argument holds for distance; travel time deviations are negligible.

## Data Structure

```java
public final class OrderingConflicts {

    private final LongOpenHashSet[] byLength;  // byLength[L] = set of hashed conflicts of length L
    private final int maxLength;               // maximum conflict length to store and check

    // Thread-safe pending buffer for recording during parallel processing
    private final ConcurrentLinkedQueue<long[]> pending;  // each entry: [length, hash]

    /**
     * Record a conflict (ordered stop sequence).
     * Called from any thread during parallel processSet.
     * Buffered; call commit() between degrees.
     */
    void recordPending(int[] stopSequence, int from, int to);

    /**
     * Merge pending buffer into main hash sets.
     * Called once between degrees (single-threaded).
     */
    void commit();

    /**
     * Check if adding candidateStop to the current path creates a known conflict.
     * Called during both origin and destination enumeration.
     *
     * @param pathStops  stops visited so far (origins placed + dests placed), using global stop IDs
     * @param pathLength number of stops in pathStops
     * @param candidateStop the stop about to be added (origin or destination stop ID)
     * @return true if a known conflict subsequence would be formed
     */
    boolean hasConflict(int[] pathStops, int pathLength, int candidateStop);

    /**
     * Hash a stop sequence using polynomial rolling hash.
     * Same scheme as DegreeGraph.hashRequestSet for consistency.
     */
    static long hash(int[] stops, int from, int to) {
        long h = 0;
        for (int i = from; i < to; i++) {
            h = h * 1000003L + stops[i];
        }
        return h;
    }

    /** Stop ID encoding. */
    static int originStop(int requestIndex) { return requestIndex << 1; }
    static int destStop(int requestIndex)   { return (requestIndex << 1) | 1; }

    /** Statistics for profiling. */
    int getConflictCount();
    int getConflictCount(int length);
}
```

### Hash collision analysis

Polynomial rolling hash with prime 1000003 maps stop sequences to 64-bit longs. Collision probability for n entries: ~n²/2^64. At 5M conflicts: ~5M² / 2^64 ≈ 10^-6. Negligible.

### Memory estimate

| Length | Estimated unique conflicts | Memory (fastutil LongOpenHashSet) |
|--------|----------------------------|-----------------------------------|
| 3 | ~1-2M | ~24 MB |
| 4 | ~500k-1M | ~12 MB |
| 5 | ~200k-500k | ~6 MB |
| 6 | ~100k-200k | ~3 MB |
| 7-8 | ~50k-100k | ~2 MB |
| **Total** | **~3-4M** | **~47 MB** |

### Maximum conflict length

Capped at `maxLength` (configurable, default 8-10) to bound lookup cost. Longer conflicts are too specific for broad transfer. Short conflicts (length 3-5) from lower degrees provide the most pruning value.

## Recording: When and What

### Single trigger: Check A fires

Whenever Check A fires — during origin enumeration (mechanism 1, see below) or during destination enumeration (existing Check A/B) — record the stop sequence from the victim's origin to the current point.

**Conflict extraction when victim V times out:**

```java
// V is at origin position p. Current point is:
//   - During origin enum: origin depth d (d origins visited)
//   - During dest enum: all n origins + dest depth d (n + d stops visited)

// Stops between V's pickup and current point (in visit order):
int[] conflict = new int[conflictLength];
int idx = 0;
conflict[idx++] = originStop(requests[perm[p]].index);       // V's origin (victim)
for (int i = p + 1; i < n; i++)                               // origins after V
    conflict[idx++] = originStop(requests[origPerm[i]].index);
for (int i = 0; i < destDepth; i++)                           // destinations visited
    conflict[idx++] = destStop(requests[destPerm[i]].index);

if (idx >= 3 && idx <= maxLength) {
    conflicts.recordPending(conflict, 0, idx);
}
```

Same code for both origin-phase and dest-phase failures. During origin-phase failures, `destDepth = 0`, so only origin stops are included.

### Recording from multiple victims

When Check A fires, multiple passengers may be over their time limit. Record a conflict for **each** busted passenger (each gives a different conflict starting from their origin position). Shorter conflicts (from later victim positions) are more broadly applicable.

### Trigger 2: All-destinations-fail

When the origin ordering is complete and ALL destination orderings fail (no evaluator callback fires), record the full origin ordering as a conflict. This captures combined failures where no individual Check A fired the conflict recorder (e.g., different passengers busted in different destination orderings).

```java
if (depth == n) {
    boolean[] anyValid = {false};
    enumerateDestPrunedWithEval(..., ordering -> {
        anyValid[0] = true;
        originalEvaluator.accept(ordering);
    });
    if (!anyValid[0]) {
        // Record full origin ordering as conflict (origin stops only, length n)
        int[] conflict = new int[n];
        for (int i = 0; i < n; i++)
            conflict[i] = originStop(requests[perm[i]].index);
        if (n >= 3 && n <= maxLength)
            conflicts.recordPending(conflict, 0, n);
    }
    return;
}
```

## Lookup: Building Only Valid Paths

### The path array

The enumeration maintains a single `pathStops[]` array that grows as stops are added:

```
Origin phase:  pathStops = [O_perm[0], O_perm[1], ..., O_perm[d-1]]
                            pathLength = d

Dest phase:    pathStops = [O_perm[0], ..., O_perm[n-1], D_destPerm[0], ..., D_destPerm[d-1]]
                            pathLength = n + d
```

### Candidate filtering

During both origin and destination enumeration, before adding a candidate to the candidate list:

```java
int candidateStop = isOriginPhase
    ? originStop(requests[c].index)
    : destStop(requests[c].index);

if (conflicts.hasConflict(pathStops, pathLength, candidateStop)) {
    stats.prunedByConflict++;
    continue;  // skip this candidate — known-bad subsequence would form
}
```

**Only candidates that don't create a known-bad subsequence are tried.** This is "building only valid paths."

### hasConflict implementation

Check all subsequences of the current path that end with the candidate stop:

```java
boolean hasConflict(int[] pathStops, int pathLength, int candidateStop) {
    for (int L = 3; L <= Math.min(maxLength, pathLength + 1); L++) {
        LongOpenHashSet set = byLength[L];
        if (set == null || set.isEmpty()) continue;

        // Check all C(pathLength, L-1) subsequences of pathStops
        // that, when appended with candidateStop, form a length-L sequence.
        // The subsequence must be ORDERED (elements in their path positions).
        if (checkSubsequencesOfLength(pathStops, pathLength, candidateStop, L, set))
            return true;
    }
    return false;
}
```

`checkSubsequencesOfLength` enumerates all (L-1)-element ordered subsequences from `pathStops[0..pathLength-1]`, appends `candidateStop`, hashes, and checks the set. Implementation uses a recursive combination generator or iterative approach over index arrays.

### Lookup cost

At each depth, for each candidate, the total subsequences checked:

```
Sum over L = 3 to min(maxLength, pathLength+1) of C(pathLength, L-1)
```

With maxLength = 8:

| Phase | Path length | Checks per candidate | Notes |
|-------|-------------|---------------------|-------|
| Origin depth 3 | 3 | C(3,2)+C(3,3) = 4 | Very cheap |
| Origin depth 6 | 6 | C(6,2)+...+C(6,7) = 57 | Same as before |
| Dest depth 3 (deg 7) | 10 | C(10,2)+...+C(10,7) ≈ 700 | Capped at L=8 |
| Dest depth 5 (deg 7) | 12 | C(12,2)+...+C(12,7) ≈ 1,500 | Manageable |

With ~3 candidates per depth and ~7 depths per phase: worst case ~15,000 hash lookups per set. At ~1ns per lookup: **~15µs per set**. With 360k sets at degree 7: ~5.4 seconds of overhead. The enumeration itself takes ~1,000 seconds, so this is **<1% overhead**.

## Origin-Phase Check A (Mechanism 1)

Currently, travel time checking only occurs during destination enumeration. Adding it to origin enumeration catches failures earlier — before the destination phase — and produces short, broadly applicable conflicts.

### Placement

In `enumerateOriginsPrunedWithEval`, at each depth > 1, before candidate selection:

```java
// Check if any already-picked-up passenger is busted from origin traversal alone
if (depth > 1) {
    for (int p = 0; p < depth; p++) {
        double inVehicle = currentTime - pickupTimes[perm[p]];
        if (inVehicle > requests[perm[p]].getMaxTravelTime()) {
            // Record conflict: origin stops from victim p to current depth
            int len = depth - p;
            if (len >= 3 && conflicts != null) {
                int[] conflict = new int[len];
                for (int i = 0; i < len; i++)
                    conflict[i] = originStop(requests[perm[p + i]].index);
                conflicts.recordPending(conflict, 0, len);
            }
            stats.prunedByTravelTime++;
            return;  // prune entire subtree
        }
    }
}
```

### Value

1. **Immediate pruning**: skips all remaining origin candidates AND all destination orderings for the pruned subtree. At degree 7, skipping one origin subtree saves ~92 destination evaluations.
2. **Conflict recording**: produces short, pure-origin conflicts (length 3-6) that transfer broadly.
3. **No data structure cost**: the check is inline arithmetic, no hash lookups.

## Concurrency Model

### Snapshot + pending buffer

During parallel processSet, multiple threads enumerate orderings concurrently for different candidate sets. Each thread may discover and record conflicts.

1. **Before each degree**: take an immutable snapshot of the conflict hash sets (from all prior degrees)
2. **During processing**: threads READ from the snapshot (no synchronization needed). Threads WRITE to a `ConcurrentLinkedQueue<long[]>` pending buffer (lock-free).
3. **After each degree**: single-threaded `commit()` merges pending entries into the main hash sets.

This means within-degree learning is deferred to the next degree. Cross-degree learning (the primary value) works immediately.

### Why within-degree deferral is acceptable

- Set processing order within a degree is arbitrary (parallel, non-deterministic)
- The primary value is cross-degree transfer: degree 3 conflicts prune degree 4+ orderings
- Within-degree conflicts would only help later-processed sets at the same degree — marginal benefit

## Integration Points

### Files changed

| File | Change | Lines |
|------|--------|-------|
| `OrderingConflicts.java` | **New class.** Hash storage, recording, lookup, commit, stop encoding. | ~150 |
| `OrderingEnumerator.java` | Add origin-phase Check A (mechanism 1). Add conflict lookup in candidate selection for both origin and dest phases. Thread `pathStops[]` array through recursion. Add Trigger 2 (all-dest-fail) at origin depth n. | ~50 |
| `RideExtender.java` | Accept `OrderingConflicts` in constructor. Pass through to `OrderingEnumerator.enumerateAndEvaluate`. | ~10 |
| `ExMasEngine.java` | Create `OrderingConflicts` before extension loop. Pass to each `RideExtender`. Call `commit()` between degrees. Log conflict statistics. | ~10 |
| `EnumerationStats.java` | Add `prunedByConflict` counter for profiling. | ~5 |

### Data flow

```
ExMasEngine
  │
  ├── creates OrderingConflicts (empty)
  │
  ├── Degree 3: RideExtender(conflicts) → OrderingEnumerator
  │     ├── Mechanism 1 fires → recordPending (origin conflicts, length 3)
  │     ├── Check A (dest) fires → recordPending (mixed conflicts, length 3-6)
  │     ├── Trigger 2 fires → recordPending (full origin ordering, length 3)
  │     └── conflicts.commit()  ← merge degree-3 conflicts
  │
  ├── Degree 4: RideExtender(conflicts) → OrderingEnumerator
  │     ├── hasConflict() checks degree-3 conflicts during candidate selection
  │     ├── New conflicts recorded (length 3-8)
  │     └── conflicts.commit()  ← merge degree-4 conflicts
  │
  ├── Degree 5: same pattern, now checking degree 3+4 conflicts
  │     ...
  └── Degree 7: rich conflict database from 4 prior degrees → heavy pruning
```

## Expected Impact

### Conservative estimate

| Source | Fraction of failures caught | Savings |
|--------|----------------------------|---------|
| Mechanism 1 (origin-phase Check A) | ~20-30% of all failures | Skip dest enumeration for pruned origin branches |
| Conflict lookup (cross-degree) | ~15-25% of remaining orderings | Skip before trying |
| Trigger 2 (all-dest-fail) | Captures combined failures | Skip at higher degrees |

Combined: orderings/set at degree 7 could drop from 9,215 to ~4,000-6,000. **1.5-2.5x speedup** on the ordering bottleneck.

### Why measurement is critical

The actual impact depends on:
1. What fraction of failures are origin-only vs combined (determines mechanism 1 yield)
2. How many unique short conflicts exist (determines cross-degree transfer breadth)
3. How often degree-k conflicts match degree-(k+2) orderings (determines transfer hit rate)

The profiling infrastructure (`EnumerationStats.prunedByConflict`) will answer these questions from the first run.

## Future Work

### Shareability graph replacement

The shareability graph stores pairwise feasibility (edges) and ride kinds (FIFO/LIFO). This could be replaced with:
- **Degree-2 DegreeGraph**: candidate generation via extension index (same mechanism as degree 3+)
- **Pairwise OrderingConflicts**: FIFO/LIFO constraints encoded as length-2 stop conflicts

This would make every degree extension work identically — no special-cased pair generation. Deferred until the conflict mechanism proves its value at higher orders.

### Minimal conflict extraction

Currently, the full stop sequence from victim to current point is recorded. Shorter subsequences within it might be independently infeasible (more broadly applicable). Finding minimal conflicts requires testing subsets — expensive at recording time but could improve transfer breadth. Consider implementing if profiling shows most conflicts are redundant with shorter ones.

### Within-degree learning

The snapshot model defers within-degree conflicts to the next degree. An alternative: use `ConcurrentHashMap` for the main store (lock-free reads and writes). This allows within-degree learning at the cost of slightly higher per-lookup overhead. Consider if within-degree hit rates are significant.

## Key Files Reference

All in `matsim-libs/contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/`:

- `algorithm/extension/OrderingEnumerator.java` — enumeration + Check A/B + conflict integration
- `algorithm/extension/RideExtender.java` — processSet, evaluateOrdering, passes conflicts through
- `algorithm/extension/EnumerationStats.java` — profiling counters
- `algorithm/graph/DegreeGraph.java` — degree-specific graph (complementary mechanism)
- `algorithm/engine/ExMasEngine.java` — orchestrator, manages conflict lifecycle
