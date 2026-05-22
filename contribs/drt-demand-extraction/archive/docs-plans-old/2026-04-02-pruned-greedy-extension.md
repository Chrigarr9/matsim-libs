# Pruned Greedy Extension — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace blind ordering enumeration with a pruned greedy topological sort that uses routed segment distances for candidate ordering and the distance savings threshold for branch pruning. This eliminates most wasted work at high degrees while preserving all valid rides.

**Architecture:** Add `enumeratePruned()` to `OrderingEnumerator` — at each recursion depth, compute routed segments for all valid candidates, sort by distance, accumulate partial ride distance with cumulative time tracking, and break when threshold exceeded. Wire into `RideExtender.processSet()` with extracted `computeMaxAllowedRideDistance()` helper. Replace post-validation `passesDistanceSavingsPruning()` with a debug assertion (cumulative time tracking ensures enumeration distance = ride distance exactly).

**Tech Stack:** Java 17, MATSim (SpeedyALT router, ConcurrentHashMap cache), JUnit 5

**Branch:** `feature/ordering-based-extension` (continues current work)

---

## 1. Understanding the Distance Savings Threshold

### Formula

```
requiredSaving(degree) = min(scale × log₂(degree), maxSaving)
```

With `scale=0.15`, `maxSaving=0.75` (validated in pruning analysis session 2026-03-30 — 92% ride reduction, 100% P90 elite preservation):

| Degree | Required saving | Max ride / sum | Meaning |
|--------|----------------|----------------|---------|
| 2 | 15.0% | 85.0% | Ride ≤ 85% of sum of individual distances |
| 3 | 23.8% | 76.2% | Ride ≤ 76% |
| 4 | 30.0% | 70.0% | Ride ≤ 70% |
| 5 | 34.8% | 65.2% | Ride ≤ 65% |
| 6 | 38.8% | 61.2% | Ride ≤ 61% |
| 7 | 42.1% | 57.9% | Ride ≤ 58% |
| 8 | 45.0% | 55.0% | Ride ≤ 55% |

Previous `scale=0.25` was too aggressive — required 65% savings at degree 6 (only near-perfect collinear clusters survived). With `scale=0.15`, degree 6 requires 39% savings — a ride saving 40% of individual distances passes.

### The physical constraint: longest passenger

The ride distance can NEVER be less than the longest individual passenger's distance. The vehicle must carry that passenger from their pickup to their dropoff — there's no shortcut.

So the **theoretical maximum saving** for a set is:

```
maxPossibleSaving = 1 - maxSingleDistance / sumAllDistances
```

### Real data (1% Bavaria, surviving rides)

```
Degree 3 (168 rides):
  Actual savings:   P5=42.6%  P50=51.1%  P95=59.7%
  Max single/sum:   P5=38.2%  P50=45.5%  P95=54.0%
  → Median ride's longest passenger is 45% of the sum

Degree 4 (27 rides):
  Actual savings:   P5=56.0%  P50=61.8%  P95=68.4%
  Max single/sum:   P5=29.2%  P50=34.7%  P95=42.0%
  → Passengers are more evenly distributed (longest = 35% of sum)

Degree 5 (1 ride):
  Saving: 66.9%, longest passenger = 33% of sum
```

### Examples with scale=0.15

```
DEGREE 3: ride ≤ 76% of sum (need ≥ 24% saving)

  3 passengers: [15.8, 10.4, 6.0] km
  Sum = 32.2 km → ride must be ≤ 24.5 km
  Actual ride = 15.8 km → saving 51% ✓
  
  The vehicle follows the longest passenger's route,
  others are along the way. Easy to pass.

DEGREE 4: ride ≤ 70% of sum (need ≥ 30% saving)

  4 passengers: [12.6, 8.9, 6.2, 9.2] km
  Sum = 36.9 km → ride must be ≤ 25.8 km
  Actual ride = 16.2 km → saving 56% ✓

DEGREE 6: ride ≤ 61% of sum (need ≥ 39% saving)

  6 passengers: [12, 10, 8, 7, 5, 3] km
  Sum = 45 km → ride must be ≤ 27.5 km
  
  Ride = 18 km → saving 60% ✓  (was REJECTED with old scale=0.25!)
  Ride = 25 km → saving 44% ✓  (moderate sharing, still passes)
  Ride = 30 km → saving 33% ✗  (too little sharing)

DEGREE 8: ride ≤ 55% of sum (need ≥ 45% saving)

  8 passengers: [10, 9, 8, 7, 6, 5, 4, 3] km
  Sum = 52 km → ride must be ≤ 28.6 km
  Max single = 10 km (19% of sum) → physically feasible
```

### Why scale=0.15 is the right value

Validated in the pruning analysis session (2026-03-30):
- With P90 percentile post-processing: **92% total ride reduction, 100% P90 elite preservation**
- Removes negative-savings and marginally-positive sets
- Keeps genuinely useful shared rides at all degrees
- The P90 percentile filter does the final quality selection — the distance savings threshold is a coarse pre-filter for performance, not a quality gate

---

## 2. Pruned Greedy Topological Sort

### Algorithm overview

Modify the existing topological sort in `OrderingEnumerator` to:
1. **Sort candidates by routed segment distance** from previous stop (nearest first)
2. **Cumulative time tracking**: advance departure time along the route, matching `buildRideFromOrdering` exactly
3. **Accumulate partial ride distance** during recursion
4. **Prune branches** where partial distance exceeds the distance savings threshold
5. **Break** on sorted candidates: if candidate k exceeds the threshold, all candidates k+1, k+2, ... are farther and also exceed it

### Why routed distance, not beeline

Beeline (Euclidean) pre-filtering was considered and rejected after empirical analysis
(`BeelineVsRoutedPruningTest`, 1000 Monte Carlo trials):

- **Sorting mismatch rate: 69%** — beeline order ≠ routed order most of the time
  (MATSim routing optimizes for travel TIME, not distance)
- **Beeline break is weaker**: with beeline sort, routed-distance pruning can only
  `continue` (one candidate at a time), not `break` (all remaining at once)
- **getSegment savings marginal**: beeline saves ~3.4 getSegment calls per depth,
  but ~70-85% of those are cache hits (HashMap lookup, ~100ns)
- **Zero false rejections confirmed**: beeline ≤ routed always holds, so beeline
  pruning is correct — but its weaker break + code complexity isn't worth 3 saved lookups

**Decision: single-tier routed-distance sort + routed-distance break.** Simpler code, stronger pruning, negligible performance cost.

### Cumulative time tracking

The enumeration tracks departure time cumulatively, matching `buildRideFromOrdering` exactly:

```
currentTime = requests[origPerm[0]].requestTime   // first pickup request time
// After each segment:
currentTime += segment.getTravelTime()
// Use currentTime for next getSegment() call
```

This ensures:
- **Identical cache keys** as `buildRideFromOrdering` → identical segment distances → identical total ride distance
- **Segments pre-warmed** in cache for surviving orderings (ride building gets 100% cache hits)
- **No time-bin mismatch** — the enumeration's accumulated distance equals `ride.getRideDistance()` exactly

### Detailed algorithm

```
Input:  requestIndices[], graph, network, requests[], maxAllowedRideDistance
Output: list of valid (originPerm, destPerm) orderings

// Phase 1: Origin ordering with pruned greedy topo sort
enumerateOrigins(adj, n, used, perm, depth, partialDist, currentTime, results):
    if depth == n:
        // Complete origin ordering — continue to destinations
        enumerateDestinations(perm, partialDist, currentTime, results)
        return
    
    candidates = [c for c in 0..n-1 if !used[c] and predecessors satisfied]
    // Predecessors: reuse existing FIFO/LIFO constraint logic from PairInfo
    
    // Sort by routed distance from previous stop
    if depth > 0:
        prevReq = requests[perm[depth-1]]
        for each candidate c:
            segment[c] = network.getSegment(prevReq.originLinkId,
                                             requests[c].originLinkId, currentTime)
        sort candidates by segment[c].getDistance()
    
    for c in candidates:
        if depth > 0:
            seg = segment[c]  // already computed for sorting
            if !seg.isReachable():
                continue  // unreachable on network — skip this candidate
            segDist = seg.getDistance()
            segTime = seg.getTravelTime()
            
            newPartialDist = partialDist + segDist
            if newPartialDist > maxAllowedRideDistance:
                break  // sorted by routed distance — all remaining are farther
            
            newTime = currentTime + segTime
        else:
            newPartialDist = 0
            newTime = requests[c].requestTime  // first origin sets the start time
        
        used[c] = true; perm[depth] = c
        enumerateOrigins(adj, n, used, perm, depth+1, newPartialDist, newTime, results)
        used[c] = false

// Phase 2: Destination ordering (given fixed origin ordering)
enumerateDestinations(origPerm, originDist, currentTime, results):
    // Build destination constraint DAG from origin ordering
    // Reuse existing PairInfo FIFO/LIFO constraint logic from enumerateDestOrderings
    adj = buildDestDAG(origPerm, pairs)
    
    lastOriginReq = requests[origPerm[n-1]]
    enumerateDestTopoSort(adj, n, used, perm, 0, originDist, currentTime,
                           lastOriginReq.originLinkId, results)

enumerateDestTopoSort(adj, n, used, perm, depth, partialDist, currentTime,
                       prevLinkId, results):
    if depth == n:
        // Complete ordering — total distance = partialDist (already verified)
        results.add(new Ordering(origPerm, perm))
        return
    
    candidates = [c for c in 0..n-1 if !used[c] and predecessors satisfied]
    
    // Sort by routed distance from previous stop
    for each candidate c:
        segment[c] = network.getSegment(prevLinkId,
                                         requests[c].destinationLinkId, currentTime)
    sort candidates by segment[c].getDistance()
    
    for c in candidates:
        seg = segment[c]
        if !seg.isReachable():
            continue  // skip unreachable
        segDist = seg.getDistance()
        segTime = seg.getTravelTime()
        
        newPartialDist = partialDist + segDist
        if newPartialDist > maxAllowedRideDistance:
            break  // sorted by routed distance — all remaining farther
        
        newTime = currentTime + segTime
        used[c] = true; perm[depth] = c
        enumerateDestTopoSort(adj, n, used, perm, depth+1, newPartialDist,
                               newTime, requests[c].destinationLinkId, results)
        used[c] = false
```

### Key properties

1. **No wasted work on hopeless sets:** If even the routed-nearest origin sequence exceeds the threshold partway through, all branches are pruned. For the 98% of degree-6 sets that fail, this happens within the first few stops.

2. **All valid orderings found:** The pruning only removes orderings whose TOTAL distance exceeds the threshold. Any ordering that would produce a ride within the savings threshold is still explored. **Provably complete for the given threshold.** Proof:
   - Partial distance is monotonically increasing (each segment adds ≥ 0 distance)
   - If partial distance at step k exceeds threshold, total distance (which adds more steps) also exceeds
   - Candidates sorted by routed distance → `break` is sound: if candidate k at distance d exceeds threshold, all k+1... at distance ≥ d also exceed

3. **Sorted candidates + break = exponential pruning:** When candidate k exceeds the threshold, candidates k+1..n-1 are skipped without evaluation. Strong single-step elimination of all remaining candidates.

4. **Cumulative time = exact ride distance match:** Because the enumeration tracks time identically to `buildRideFromOrdering`, the accumulated distance equals the final ride distance. This eliminates the need for post-validation distance savings pruning (replaced by debug assertion).

5. **Cache pre-warming for surviving orderings:** Segments computed during enumeration are cached with the exact same keys `buildRideFromOrdering` will use → ride building gets 100% cache hits for surviving orderings.

6. **Segment computation doubles as sort key:** At each depth, `getSegment()` is called for all candidates (for sorting). These same segments are reused for distance accumulation — no redundant routing.

---

## 3. Integration with RideExtender

### Modified `processSet()`

```java
private Ride processSet(int[] newSet) {
    // Resolve requests
    DrtRequest[] setRequests = resolveAndCheckDuplicates(newSet);
    if (setRequests == null) return null;
    
    // Compute threshold for this degree
    double maxAllowedRideDistance = computeMaxAllowedRideDistance(setRequests);
    // maxAllowedRideDistance = Double.MAX_VALUE if pruning disabled
    
    // Enumerate orderings with pruned greedy topo sort
    // Uses beeline pre-filter + routed distance with cumulative time tracking
    List<OrderingEnumerator.Ordering> orderings = OrderingEnumerator.enumeratePruned(
            newSet, graph, network, setRequests, maxAllowedRideDistance);
    
    // Evaluate valid orderings (all within distance threshold by construction)
    Ride bestRide = null;
    double bestObjective = Double.MAX_VALUE;
    
    for (OrderingEnumerator.Ordering ord : orderings) {
        Ride ride = buildRideFromOrdering(...);
        if (ride == null) continue;
        Ride validated = budgetValidator.validateAndPopulateBudgets(ride);
        if (validated == null) continue;
        
        // Debug assertion: enumeration guarantees distance threshold
        // The pruned enumeration uses cumulative time tracking identical to
        // buildRideFromOrdering, so ride.getRideDistance() == enumeration's
        // accumulated distance. This assertion verifies that invariant.
        assert !passesDistanceSavingsPruningEnabled()
            || ride.getRideDistance() <= maxAllowedRideDistance + 1e-6
            : "Ride distance " + ride.getRideDistance()
              + " exceeds threshold " + maxAllowedRideDistance
              + " — enumeration/ride-building distance mismatch";
        
        double obj = objectiveValue(validated);
        if (obj < bestObjective) {
            bestObjective = obj;
            bestRide = validated;
        }
    }
    return bestRide;
}
```

### Computing `maxAllowedRideDistance`

```java
private double computeMaxAllowedRideDistance(DrtRequest[] setRequests) {
    if (exMasConfig == null || exMasConfig.getPruningDistanceSavingsLogScale() < 0) {
        return Double.MAX_VALUE; // no pruning
    }
    int degree = setRequests.length;
    int minDegree = Math.max(2, exMasConfig.getPruningDistanceSavingsMinDegree());
    if (degree < minDegree) return Double.MAX_VALUE;
    
    double scale = exMasConfig.getPruningDistanceSavingsLogScale();
    double maxSaving = Math.min(0.99, Math.max(0, exMasConfig.getPruningDistanceSavingsMax()));
    double requiredSaving = requiredSavingForDegree(degree, scale, maxSaving, minDegree);
    
    double sumDirectDistances = 0;
    for (DrtRequest r : setRequests) sumDirectDistances += r.directDistance;
    
    return (1.0 - requiredSaving) * sumDirectDistances;
}
```

---

## 4. OrderingEnumerator Changes

### New method signature

```java
public static List<Ordering> enumeratePruned(
        int[] requestIndices, ShareabilityGraph graph,
        MatsimNetworkCache network, DrtRequest[] requests,
        double maxRideDistance)
// Note: no startTime parameter — the enumeration derives start time from
// requests[origPerm[0]].requestTime (first picked-up request), matching
// buildRideFromOrdering's time tracking exactly.
```

### What changes from current `enumerate()`

| Aspect | Current | Pruned greedy |
|--------|---------|---------------|
| Candidate order | Arbitrary (ascending index) | Sorted by routed segment distance |
| Pruning | None | Branch pruned when partial distance > threshold |
| Break on sorted | No | Yes — remaining candidates guaranteed farther |
| Time tracking | None | Cumulative (matches buildRideFromOrdering) |
| Unreachable segments | N/A | `continue` (skip candidate) |
| Dependencies | graph only | graph + network + requests (for segment lookups + link IDs) |
| Return | All valid orderings | Only orderings within distance threshold |

### Keep original `enumerate()` as fallback

The original `enumerate(int[], ShareabilityGraph)` remains for:
- Unit tests
- Cases where no distance threshold applies (pruning disabled, `maxRideDistance == Double.MAX_VALUE`)
- Backward compatibility

### Constraint logic reuse

The destination DAG construction **must reuse** the existing `PairInfo` FIFO/LIFO constraint logic from `enumerateDestOrderings` (current lines 133-180). Do not reimplement — extract it if needed, but keep the same predecessor-satisfaction logic.

---

## 5. Implementation Tasks

### Task 1: Add `enumeratePruned` to OrderingEnumerator

New method with:
- Routed-distance sorting + routed-distance break (single-tier, no beeline)
- Cumulative time tracking: advance `currentTime += segment.getTravelTime()` at each step
- Origin topo sort: candidates sorted by routed O→O distance, break when accumulated distance > threshold
- Destination topo sort: candidates sorted by routed D→D distance, break when accumulated distance > threshold
- Handle `TravelSegment.unreachable()`: skip candidate with `continue` (don't break — other candidates may be reachable)
- Reuse existing `PairInfo` FIFO/LIFO constraint logic for DAG construction
- Returns only orderings whose total ride distance ≤ maxRideDistance

**Files:** `algorithm/extension/OrderingEnumerator.java`

### Task 2: Update `processSet` in RideExtender

- Compute `maxAllowedRideDistance` from config + request distances
- Call `enumeratePruned` instead of `enumerate` (falls back to `enumerate` when `maxRideDistance == Double.MAX_VALUE`)
- Replace `passesDistanceSavingsPruning` check with a debug assertion verifying enumeration/ride-building distance consistency (tolerance 1e-6 m)
- Note: the assertion catches any cumulative-time or floating-point mismatch between enumeration and ride building

**Files:** `algorithm/extension/RideExtender.java`

### Task 3: Add `computeMaxAllowedRideDistance` helper

Extract threshold computation into a reusable method.

**Files:** `algorithm/extension/RideExtender.java`

### Task 4: Compile + E2E test

```bash
mvn compile -Denforcer.skip=true -o -q
mvn test -Dtest=ExMasDemandExtractionE2ETest -Denforcer.skip=true -o
```

Run with assertions enabled (`-ea`) to verify the distance consistency invariant.

### Task 5: Run 1% validation

Compare ride counts against the unpruned version. They should be IDENTICAL — the pruning only removes orderings that would have failed the distance savings check anyway. Also verify the debug assertion never fires.

### Task 6: Run 10% with timing

Compare extension wall time per degree against the unpruned parallel run.
Expected: massive speedup at degree 5+ (most sets pruned within first few stops).
Log cache hit/miss statistics via `MatsimNetworkCache.logRoutingStatistics()` to measure routing calls.

---

## 6. Expected Impact

### For a hopeless set (98% of degree-6 sets)

```
Current:  enumerate 500+ orderings × route each × all fail savings check
Pruned:   sort candidates at depth 1 by routed distance → nearest first
          → accumulated distance exceeds threshold at depth 2-3 → break
          → ~6-12 getSegment() calls total (vs 500+ full routings + ride builds)
```

Each getSegment() call = HashMap lookup (cache hit, ~100ns) or SpeedyALT route (cache miss). No Ride object creation, no budget validation, no metric computation. The break on sorted candidates eliminates all remaining candidates in one step.

### For a valid set (2% of degree-6 sets)

```
Current:  enumerate 500+ orderings × route each × keep best
Pruned:   routed-distance-sorted candidates → nearest-first exploration
          → valid orderings found quickly → distant branches pruned by break
          → maybe 10-50 orderings survive → route and validate those 10-50
          → segments already cached from enumeration (100% hit rate in ride building)
```

### Cache behavior

**Network cache (timeBinSize = 3600s):**
- ~70-85% hit rate for `getSegment()` calls during degree-6 enumeration (pre-warmed by degree 2-5 processing)
- ~15-30% cache misses trigger SpeedyALT routing (expensive but unavoidable — same calls buildRideFromOrdering would make)
- Cumulative time tracking ensures segments cached during enumeration are reused verbatim during ride building (100% hit rate for surviving orderings)
- Segments computed for sorting are reused for accumulation — no redundant routing

### Correctness guarantee

**The pruned greedy produces exactly the same set of valid rides as full enumeration** (for a given distance threshold). Proof:
- An ordering with total distance > threshold would have failed `passesDistanceSavingsPruning` anyway → pruning it doesn't change the result
- An ordering with total distance ≤ threshold is never pruned (partial distance ≤ total distance ≤ threshold at every step)
- Candidates sorted by routed distance → `break` eliminates all remaining in one step
- Cumulative time tracking matches `buildRideFromOrdering` exactly → enumeration distance = ride distance (verified by debug assertion)

---

## 7. Threshold Parameter Change

Change `RunBavaria30kmDemandExtraction` from `scale=0.25` to `scale=0.15`:

```java
exMasConfig.setPruningDistanceSavingsLogScale(0.15);  // was 0.25
```

This is a one-line config change in `configureExMas()`. The pruned greedy algorithm works with any threshold — a lower threshold means more rides survive (more work but more results for the MIP optimizer), while the branch pruning still eliminates the truly hopeless sets efficiently.

---

## 8. Execution Tasks (TDD)

**Base path:** `matsim-libs/contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/`

### Task 1: Extract `computeMaxAllowedRideDistance` helper in RideExtender

**Files:**
- Modify: `algorithm/extension/RideExtender.java`

**Step 1: Add the helper method** (after `passesDistanceSavingsPruning`, line ~412)

```java
/**
 * Compute the maximum allowed ride distance for a request set based on
 * the distance savings pruning threshold.
 *
 * @return max ride distance in meters, or Double.MAX_VALUE if pruning disabled
 */
double computeMaxAllowedRideDistance(DrtRequest[] setRequests) {
    if (exMasConfig == null || exMasConfig.getPruningDistanceSavingsLogScale() < 0) {
        return Double.MAX_VALUE;
    }
    int degree = setRequests.length;
    int minDegree = Math.max(2, exMasConfig.getPruningDistanceSavingsMinDegree());
    if (degree < minDegree) return Double.MAX_VALUE;

    double scale = exMasConfig.getPruningDistanceSavingsLogScale();
    double maxSaving = exMasConfig.getPruningDistanceSavingsMax();
    if (!(maxSaving >= 0)) maxSaving = 0.0;
    maxSaving = Math.min(0.99, maxSaving);

    double requiredSaving = requiredSavingForDegree(degree, scale, maxSaving, minDegree);
    double sumDirectDistances = 0;
    for (DrtRequest r : setRequests) sumDirectDistances += r.directDistance;

    return (1.0 - requiredSaving) * sumDirectDistances;
}
```

**Step 2: Compile**

```bash
cd matsim-libs/contribs/drt-demand-extraction
mvn compile -Denforcer.skip=true -o -q
```

**Step 3: Commit**

```bash
git add -p
git commit -m "refactor: extract computeMaxAllowedRideDistance helper in RideExtender"
```

---

### Task 2: Add `enumeratePruned` to OrderingEnumerator

**Files:**
- Modify: `algorithm/extension/OrderingEnumerator.java`

**Step 1: Add imports** (after existing imports, line 6-7)

```java
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;
import org.matsim.contrib.demand_extraction.algorithm.graph.ShareabilityGraph;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

import it.unimi.dsi.fastutil.ints.IntList;
```

**Step 2: Add `enumeratePruned` public entry point** (after `enumerate`, line ~64)

```java
/**
 * Enumerate valid orderings with distance-based branch pruning.
 *
 * <p>At each recursion depth, candidates are sorted by routed segment distance
 * from the previous stop. The accumulated partial ride distance is tracked with
 * cumulative departure times (matching {@code buildRideFromOrdering} exactly).
 * When partial distance exceeds the threshold, all remaining sorted candidates
 * are pruned via {@code break}.
 *
 * <p>Provably complete: any ordering whose total ride distance ≤ threshold is
 * found. Proof: partial distance is monotonically increasing, so if it exceeds
 * threshold at step k, total distance (adding more steps) also exceeds.
 *
 * @param requestIndices sorted request indices for the set
 * @param graph the shareability graph
 * @param network network cache for routed segment lookups
 * @param requests DrtRequest objects (requests[i] corresponds to requestIndices[i])
 * @param maxRideDistance maximum allowed total ride distance (meters)
 * @return list of valid orderings within distance threshold
 */
public static List<Ordering> enumeratePruned(
        int[] requestIndices, ShareabilityGraph graph,
        MatsimNetworkCache network, DrtRequest[] requests,
        double maxRideDistance) {

    // Delegate to unpruned enumeration if pruning is disabled
    if (maxRideDistance >= Double.MAX_VALUE / 2) {
        return enumerate(requestIndices, graph);
    }

    int n = requestIndices.length;
    PairInfo[] pairs = extractConstraints(requestIndices, n, graph);
    if (pairs == null) return List.of();

    // Build origin adjacency matrix (same logic as enumerateOriginOrderings)
    Boolean[][] origAdj = new Boolean[n][n];
    for (int a = 0; a < n; a++) {
        for (int b = a + 1; b < n; b++) {
            PairInfo p = lookup(pairs, n, a, b);
            if (p.forwardOnly()) {
                origAdj[a][b] = true; origAdj[b][a] = false;
            } else if (p.reverseOnly()) {
                origAdj[b][a] = true; origAdj[a][b] = false;
            }
        }
    }

    List<Ordering> result = new ArrayList<>();
    enumerateOriginsPruned(origAdj, n, pairs, network, requests,
            maxRideDistance, new boolean[n], new int[n], 0,
            0.0, 0.0, result);
    return result;
}
```

**Step 3: Add `enumerateOriginsPruned`** (private, after `enumeratePruned`)

```java
private static void enumerateOriginsPruned(
        Boolean[][] adj, int n, PairInfo[] pairs,
        MatsimNetworkCache network, DrtRequest[] requests,
        double maxRideDistance,
        boolean[] used, int[] perm, int depth,
        double partialDist, double currentTime,
        List<Ordering> result) {

    if (depth == n) {
        // Complete origin ordering — enumerate destinations with pruning
        enumerateDestinationsPruned(n, perm, pairs, network, requests,
                maxRideDistance, partialDist, currentTime, result);
        return;
    }

    // Collect valid candidates (predecessors satisfied in topo sort)
    List<Integer> candidates = new ArrayList<>();
    for (int c = 0; c < n; c++) {
        if (used[c]) continue;
        boolean valid = true;
        for (int other = 0; other < n; other++) {
            if (other == c || used[other]) continue;
            if (adj[other][c] != null && adj[other][c]) {
                valid = false; break;
            }
        }
        if (valid) candidates.add(c);
    }

    if (depth == 0) {
        // First origin: no segment, no distance. Try each candidate.
        for (int c : candidates) {
            used[c] = true;
            perm[0] = c;
            enumerateOriginsPruned(adj, n, pairs, network, requests,
                    maxRideDistance, used, perm, 1,
                    0.0, requests[c].getRequestTime(), result);
            used[c] = false;
        }
        return;
    }

    // Compute routed segments from previous origin to each candidate
    Id<Link> prevLink = requests[perm[depth - 1]].originLinkId;
    Map<Integer, TravelSegment> segMap = new HashMap<>();
    for (int c : candidates) {
        segMap.put(c, network.getSegment(prevLink, requests[c].originLinkId, currentTime));
    }

    // Sort candidates by routed distance (unreachable = infinity, sorts to end)
    candidates.sort(Comparator.comparingDouble(c -> segMap.get(c).getDistance()));

    // Iterate with break on threshold
    for (int c : candidates) {
        TravelSegment seg = segMap.get(c);
        double newPartialDist = partialDist + seg.getDistance();
        if (newPartialDist > maxRideDistance) break; // sorted — all remaining farther

        used[c] = true;
        perm[depth] = c;
        enumerateOriginsPruned(adj, n, pairs, network, requests,
                maxRideDistance, used, perm, depth + 1,
                newPartialDist, currentTime + seg.getTravelTime(), result);
        used[c] = false;
    }
}
```

**Step 4: Add `enumerateDestinationsPruned`** (private, after origins)

```java
private static void enumerateDestinationsPruned(
        int n, int[] origPerm, PairInfo[] pairs,
        MatsimNetworkCache network, DrtRequest[] requests,
        double maxRideDistance,
        double partialDist, double currentTime,
        List<Ordering> result) {

    // Build destination adjacency (same logic as enumerateDestOrderings)
    int[] origPos = new int[n];
    for (int i = 0; i < n; i++) origPos[origPerm[i]] = i;

    Boolean[][] adj = new Boolean[n][n];
    for (int a = 0; a < n; a++) {
        for (int b = a + 1; b < n; b++) {
            PairInfo p = lookup(pairs, n, a, b);
            boolean aBeforeB = origPos[a] < origPos[b];
            boolean hasFifo = aBeforeB ? p.forwardFifo() : p.reverseFifo();
            boolean hasLifo = aBeforeB ? p.forwardLifo() : p.reverseLifo();

            if (hasFifo && hasLifo) {
                // no constraint
            } else if (hasFifo) {
                if (aBeforeB) { adj[a][b] = true; adj[b][a] = false; }
                else          { adj[b][a] = true; adj[a][b] = false; }
            } else if (hasLifo) {
                if (aBeforeB) { adj[b][a] = true; adj[a][b] = false; }
                else          { adj[a][b] = true; adj[b][a] = false; }
            } else {
                return; // infeasible
            }
        }
    }

    // Previous link = last origin's origin link (transition point)
    Id<Link> prevLink = requests[origPerm[n - 1]].originLinkId;

    enumerateDestTopoSortPruned(adj, n, origPerm, network, requests,
            maxRideDistance, new boolean[n], new int[n], 0,
            partialDist, currentTime, prevLink, result);
}

private static void enumerateDestTopoSortPruned(
        Boolean[][] adj, int n, int[] origPerm,
        MatsimNetworkCache network, DrtRequest[] requests,
        double maxRideDistance,
        boolean[] used, int[] perm, int depth,
        double partialDist, double currentTime,
        Id<Link> prevLinkId,
        List<Ordering> result) {

    if (depth == n) {
        result.add(new Ordering(origPerm.clone(), perm.clone()));
        return;
    }

    // Collect valid candidates (predecessors satisfied)
    List<Integer> candidates = new ArrayList<>();
    for (int c = 0; c < n; c++) {
        if (used[c]) continue;
        boolean valid = true;
        for (int other = 0; other < n; other++) {
            if (other == c || used[other]) continue;
            if (adj[other][c] != null && adj[other][c]) {
                valid = false; break;
            }
        }
        if (valid) candidates.add(c);
    }

    // Compute routed segments and sort by distance
    Map<Integer, TravelSegment> segMap = new HashMap<>();
    for (int c : candidates) {
        segMap.put(c, network.getSegment(prevLinkId,
                requests[c].destinationLinkId, currentTime));
    }
    candidates.sort(Comparator.comparingDouble(c -> segMap.get(c).getDistance()));

    // Iterate with break on threshold
    for (int c : candidates) {
        TravelSegment seg = segMap.get(c);
        double newPartialDist = partialDist + seg.getDistance();
        if (newPartialDist > maxRideDistance) break;

        used[c] = true;
        perm[depth] = c;
        enumerateDestTopoSortPruned(adj, n, origPerm, network, requests,
                maxRideDistance, used, perm, depth + 1,
                newPartialDist, currentTime + seg.getTravelTime(),
                requests[c].destinationLinkId, result);
        used[c] = false;
    }
}
```

**Step 5: Compile**

```bash
mvn compile -Denforcer.skip=true -o -q
```

**Step 6: Commit**

```bash
git add -p
git commit -m "feat: add enumeratePruned with routed-distance sorting and branch pruning"
```

---

### Task 3: Wire `processSet` to use `enumeratePruned`

**Files:**
- Modify: `algorithm/extension/RideExtender.java` (lines 172-219)

**Step 1: Replace the `processSet` method body**

Replace lines 188-218 (the enumeration + evaluation loop):

```java
private Ride processSet(int[] newSet) {
    // Resolve requests
    DrtRequest[] setRequests = new DrtRequest[newSet.length];
    for (int i = 0; i < newSet.length; i++) {
        setRequests[i] = requestMap.get(newSet[i]);
    }

    // Duplicate person check
    for (int i = 0; i < setRequests.length; i++) {
        for (int j = i + 1; j < setRequests.length; j++) {
            if (setRequests[i].getPaxId().equals(setRequests[j].getPaxId())) {
                return null;
            }
        }
    }

    // Compute distance threshold for pruned enumeration
    double maxAllowedRideDistance = computeMaxAllowedRideDistance(setRequests);

    // Enumerate valid orderings (pruned by distance threshold)
    List<OrderingEnumerator.Ordering> orderings = OrderingEnumerator.enumeratePruned(
            newSet, graph, network, setRequests, maxAllowedRideDistance);

    Ride bestRide = null;
    double bestObjective = Double.MAX_VALUE;

    for (OrderingEnumerator.Ordering ord : orderings) {
        int n = newSet.length;
        DrtRequest[] originsOrdered = new DrtRequest[n];
        DrtRequest[] destsOrdered = new DrtRequest[n];
        for (int i = 0; i < n; i++) {
            originsOrdered[i] = setRequests[ord.originPerm()[i]];
            destsOrdered[i] = setRequests[ord.destPerm()[i]];
        }

        Ride ride = buildRideFromOrdering(originsOrdered, destsOrdered, 0);
        if (ride == null) continue;

        Ride validated = budgetValidator.validateAndPopulateBudgets(ride);
        if (validated == null) continue;

        // Debug assertion: enumeration guarantees distance threshold.
        // Cumulative time tracking in enumeratePruned matches buildRideFromOrdering
        // exactly, so accumulated distance == ride.getRideDistance().
        assert maxAllowedRideDistance >= Double.MAX_VALUE / 2
                || validated.getRideDistance() <= maxAllowedRideDistance + 1e-3
                : "Ride distance " + validated.getRideDistance()
                  + " exceeds threshold " + maxAllowedRideDistance
                  + " — enumeration/ride-building distance mismatch";

        double obj = objectiveValue(validated);
        if (obj < bestObjective) {
            bestObjective = obj;
            bestRide = validated;
        }
    }
    return bestRide;
}
```

**Step 2: Compile**

```bash
mvn compile -Denforcer.skip=true -o -q
```

**Step 3: Run E2E test**

```bash
mvn test -Dtest=ExMasDemandExtractionE2ETest -Denforcer.skip=true -o -ea
```

Expected: PASS (the dvrp-grid scenario has small degree, pruning delegates to unpruned `enumerate()` via the `maxRideDistance >= MAX_VALUE/2` check since the E2E test doesn't configure distance savings pruning).

**Step 4: Run Kelheim HyperPool E2E test** (this one HAS distance savings pruning enabled with scale=0.15)

```bash
mvn test -Dtest=ExMasKelheimHyperPoolE2ETest -Denforcer.skip=true -o -ea
```

Expected: PASS with assertion enabled. This validates the pruned enumeration on a real scenario with actual pruning.

**Step 5: Commit**

```bash
git add -p
git commit -m "feat: wire processSet to use enumeratePruned with distance-based branch pruning"
```

---

### Task 4: Change scale 0.25 → 0.15 in RunBavaria30kmDemandExtraction

**Files:**
- Modify: `run/RunBavaria30kmDemandExtraction.java` (line 713)

**Step 1: Change the scale value**

```java
// Line 713: change 0.25 to 0.15
exMasConfig.setPruningDistanceSavingsLogScale(0.15);
```

**Step 2: Compile**

```bash
mvn compile -Denforcer.skip=true -o -q
```

**Step 3: Commit**

```bash
git add -p
git commit -m "config: change Bavaria distance savings scale from 0.25 to 0.15"
```

---

### Task 5: Run 1% Bavaria validation

**Step 1: Run 1% with pruned greedy**

```bash
cd matsim_scenarios
java -ea -Xmx8g -cp ../matsim-libs/contribs/drt-demand-extraction/target/classes:../matsim-libs/contribs/drt-demand-extraction/target/dependency/* \
  org.matsim.contrib.demand_extraction.run.RunBavaria30kmDemandExtraction \
  --scale 0.01 --output outputs/bavaria-30km-1pct-pruned-greedy
```

**Step 2: Compare ride counts against unpruned baseline**

Ride counts should be IDENTICAL to the previous 1% run (11,538 rides).
The pruned enumeration only removes orderings that would have failed the distance savings check anyway.
If assertion fires, investigate the distance mismatch.

---

### Task 6: Run 10% Bavaria with timing

**Step 1: Run 10%**

```bash
java -ea -Xmx32g -cp ... \
  org.matsim.contrib.demand_extraction.run.RunBavaria30kmDemandExtraction \
  --scale 0.10 --output outputs/bavaria-30km-10pct-pruned-greedy
```

**Step 2: Compare timing**

Previous 10% run: deg3=234k rides, deg4=683k, deg5=1.08M (degree 6 killed after 9h).
Expected: massive speedup at degree 5+ (most sets pruned within first few stops).

Log the extension wall time per degree and compare.
