# SSSP-Based Pair Generation Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace point-to-point routing in PairGenerator with batch SSSP (LeastCostPathTree) to reduce pair generation time from ~10h to ~minutes for 100k+ request scenarios.

**Architecture:** Add a `batchPrecompute()` method to `MatsimNetworkCache` that computes a single-source shortest path tree (SSSP) and populates the existing cache for all target links in one Dijkstra pass. PairGenerator calls this once per unique (originLink, timeBin) before iterating candidates, replacing per-pair beeline+routing with O(1) cache lookups. The existing cache stays as-is for extensions and downstream consumers.

**Tech Stack:** Java 17+, MATSim `LeastCostPathTree` + `SpeedyGraphBuilder`, existing `MatsimNetworkCache` + `ConcurrentHashMap`

**Key insight:** One `LeastCostPathTree.calculate()` (~3ms full network, ~0.3ms with early termination) gives travel times to ALL reachable nodes within a travel time budget. Current code calls `SpeedyALT.calcLeastCostPath()` (~1ms each) separately for each candidate — same source, different destinations. Batching eliminates ~99% of routing work.

**Early termination:** `LeastCostPathTree` has a built-in `TravelTimeStopCriterion` that stops Dijkstra once nodes exceed a travel time limit. For the O→O tree, `reqI.getMaxTravelTime()` (= `directTravelTime × maxDetourFactor`) is a tight bound — if O→O alone exceeds reqI's detour budget, no shared ride is feasible. For the D→D tree, `max(reqJ.getMaxTravelTime())` across candidates is the correct bound — the D_i→D_j segment is part of reqJ's budget. This reduces explored nodes from ~109k (full Lyon network) to a small local neighborhood.

**Compatibility:** Both `SpeedyALT.calcLeastCostPath(Link,Link)` and `LeastCostPathTree.calculate(Link)` route from `fromLink.getToNode()`, and look up destinations at `toLink.getFromNode()`. The travel times are identical (both compute optimal shortest paths on the same graph). Output should be bit-identical to the current implementation.

---

## Task 1: Add `batchPrecompute()` to MatsimNetworkCache

**Files:**
- Modify: `src/main/java/org/matsim/contrib/demand_extraction/algorithm/network/MatsimNetworkCache.java`
- Test: `src/test/java/org/matsim/contrib/demand_extraction/algorithm/network/MatsimNetworkCacheBatchTest.java`

This adds the SSSP capability to the existing cache. The method computes one LeastCostPathTree from a source link, extracts travel segments for a set of target links, and stores them in the existing ConcurrentHashMap cache.

**Step 1: Write the test**

Create `MatsimNetworkCacheBatchTest.java`. This is an integration test that:
1. Builds a small MATSim network (reuse the dvrp-grid test scenario)
2. Calls `batchPrecompute()` from one link to several target links
3. Verifies results match individual `getSegment()` calls

```java
package org.matsim.contrib.demand_extraction.algorithm.network;

// Test that batchPrecompute() produces identical results to individual getSegment() calls.
// Uses the dvrp-grid test scenario (11x11 grid, 200m spacing).

@ExtendWith(MatsimTestExtension.class)
public class MatsimNetworkCacheBatchTest {

    @RegisterExtension
    private MatsimTestUtils utils = new MatsimTestUtils();

    @Test
    void batchPrecomputeMatchesPointToPoint() {
        // 1. Load dvrp-grid scenario, build MatsimNetworkCache with Guice
        // 2. Pick one origin link and 5-10 target links at varying distances
        // 3. Call batchPrecompute(originLink, time, targetLinks, maxTravelTime)
        //    with a generous maxTravelTime that covers all targets
        // 4. For each target: compare cache result with fresh getSegment() call
        //    (clear cache between batch and point-to-point to ensure independent computation)
        // 5. Assert TravelSegment fields match exactly (travelTime, distance, networkUtility)
    }

    @Test
    void batchPrecomputeStopCriterionMarksDistantNodesUnreachable() {
        // 1. Load dvrp-grid scenario, build MatsimNetworkCache with Guice
        // 2. Pick one origin link, one nearby target (e.g. 1 link away) and one far target
        // 3. Call batchPrecompute with a tight maxTravelTime that covers only the nearby target
        // 4. Assert nearby target has valid TravelSegment
        // 5. Assert far target is TravelSegment.unreachable()
        // 6. Verify nearby target result matches point-to-point getSegment()
    }
}
```

The exact test setup should follow the pattern in `ExMasDemandExtractionE2ETest.java` for loading the dvrp-grid scenario and building the Guice injector. The test should pick links from different parts of the grid (adjacent, far, unreachable if any).

**Step 2: Run test to verify it fails**

Run: `mvn test -pl contribs/drt-demand-extraction -Dtest=MatsimNetworkCacheBatchTest -Dsurefire.failIfNoTests=false`
Expected: FAIL — `batchPrecompute` method doesn't exist yet

**Step 3: Implement `batchPrecompute()` in MatsimNetworkCache**

Add to `MatsimNetworkCache.java`:

1. A `ThreadLocal<LeastCostPathTree>` field (similar to existing `threadLocalRouter`):
```java
private final ThreadLocal<LeastCostPathTree> threadLocalTree;
```

2. Initialize in constructor (after the network, travelTime, travelDisutility are set):
```java
this.threadLocalTree = ThreadLocal.withInitial(() ->
    new LeastCostPathTree(SpeedyGraphBuilder.build(network), travelTime, travelDisutility));
```

3. The `batchPrecompute()` method:
```java
public void batchPrecompute(Id<Link> fromLinkId, double departureTime, Id<Link>[] toLinkIds,
                            double maxTravelTimeSeconds) {
    Link fromLink = network.getLinks().get(fromLinkId);
    if (fromLink == null) return;

    int timeBin = (int)(departureTime / timeBinSize);
    double canonicalDepartureTime = (timeBin + 0.5) * timeBinSize;

    // Check if we already computed this batch (any target already cached means tree was run)
    // Quick check: if first target is cached, skip
    if (toLinkIds.length > 0) {
        CacheKey probe = new CacheKey(fromLinkId, toLinkIds[0], timeBin);
        if (cache.containsKey(probe)) return;
    }

    LeastCostPathTree tree = threadLocalTree.get();
    LeastCostPathTree.StopCriterion stopCriterion =
        new LeastCostPathTree.TravelTimeStopCriterion(maxTravelTimeSeconds);
    tree.calculate(fromLink, canonicalDepartureTime, dummyPerson, dummyVehicle, stopCriterion);

    for (Id<Link> toLinkId : toLinkIds) {
        CacheKey key = new CacheKey(fromLinkId, toLinkId, timeBin);
        if (cache.containsKey(key)) continue;

        if (fromLinkId.equals(toLinkId)) {
            cache.computeIfAbsent(key, k -> computeSegment(fromLinkId, toLinkId, canonicalDepartureTime));
            continue;
        }

        Link toLink = network.getLinks().get(toLinkId);
        if (toLink == null) {
            cache.put(key, TravelSegment.unreachable());
            continue;
        }

        int toNodeIdx = toLink.getFromNode().getId().index();
        OptionalTime time = tree.getTime(toNodeIdx);

        if (time.isDefined()) {
            double tt = time.seconds() - canonicalDepartureTime;
            double dist = tree.getDistance(toNodeIdx);
            double utility = -tree.getCost(toNodeIdx);
            cache.put(key, new TravelSegment(tt, dist, utility));
        } else {
            cache.put(key, TravelSegment.unreachable());
        }
    }
}
```

Key details:
- **Early termination:** Uses `LeastCostPathTree.TravelTimeStopCriterion` to stop Dijkstra once nodes exceed `maxTravelTimeSeconds`. Dijkstra explores in cost order, and MATSim cost is monotonically increasing with travel time, so `break` is safe — no cheaper nodes exist beyond the limit. Nodes beyond the limit that weren't explored will return `POSITIVE_INFINITY` from `getTime()`, producing `TravelSegment.unreachable()`.
- Uses same `canonicalDepartureTime` as `getSegment()` (timeBin midpoint) for cache key consistency
- Falls back to `computeSegment()` for same-link case (handles link traversal time)
- `CacheKey` is the same class used by `getSegment()`, so pre-populated entries are found by later `getSegment()` calls
- Thread-safe: `LeastCostPathTree` is thread-local, `cache.put()` on ConcurrentHashMap is safe
- Skip check: if the first target is already cached, assumes the tree was already computed for this (fromLink, timeBin)

**Step 4: Run test to verify it passes**

Run: `mvn test -pl contribs/drt-demand-extraction -Dtest=MatsimNetworkCacheBatchTest`
Expected: PASS

**Step 5: Commit**

```bash
git add contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/network/MatsimNetworkCache.java
git add contribs/drt-demand-extraction/src/test/java/org/matsim/contrib/demand_extraction/algorithm/network/MatsimNetworkCacheBatchTest.java
git commit -m "feat: add batchPrecompute() to MatsimNetworkCache using SSSP trees"
```

---

## Task 2: Modify PairGenerator to use batch precompute for O→O segments

**Files:**
- Modify: `src/main/java/org/matsim/contrib/demand_extraction/algorithm/generation/PairGenerator.java`

This is the core change. Before iterating candidates for a given reqI, batch-precompute all O→O segments from reqI.origin to all candidate origins. Then the existing `network.getSegment()` calls hit the warm cache.

**Step 1: Modify `generateCandidatesForRequest()`**

At the top of the method, after getting candidates from `TimeFilter`, collect candidate origin link IDs and call `batchPrecompute()`:

```java
private List<PairCandidate> generateCandidatesForRequest(TimeFilter filter, int i) {
    List<PairCandidate> results = new ArrayList<>();
    DrtRequest reqI = filter.getRequest(i);
    int[] candidateIndices = filter.findCandidatesInHorizon(i, horizon);

    // Batch precompute O→O segments: one SSSP from reqI.origin covers all candidates
    // Stop criterion: reqI.getMaxTravelTime() — if O→O alone exceeds reqI's detour budget,
    // no FIFO or LIFO arrangement is feasible
    Id<Link>[] candidateOrigins = new Id[candidateIndices.length];
    for (int k = 0; k < candidateIndices.length; k++) {
        candidateOrigins[k] = filter.getRequest(candidateIndices[k]).originLinkId;
    }
    network.batchPrecompute(reqI.originLinkId, reqI.requestTime, candidateOrigins,
        reqI.getMaxTravelTime());

    for (int j : candidateIndices) {
        DrtRequest reqJ = filter.getRequest(j);

        if (reqI.getPaxId().equals(reqJ.getPaxId())) continue;

        if (reqJ.getLatestDeparture() < reqI.getEarliestDeparture() ||
                reqJ.getEarliestDeparture() > reqI.getLatestDeparture() + reqI.getTravelTime()) {
            continue;
        }

        // O→O lookup — now a cache hit from batchPrecompute, ~O(1)
        TravelSegment oo = network.getSegment(reqI.originLinkId, reqJ.originLinkId, reqI.requestTime);
        if (!oo.isReachable()) continue;

        // Temporal check with actual O→O travel time
        if (reqI.getLatestDeparture() + oo.getTravelTime() < reqJ.getEarliestDeparture()) continue;
        if (reqI.getEarliestDeparture() + oo.getTravelTime() > reqJ.getLatestDeparture()) continue;

        // FIFO/LIFO feasibility — use actual O→O distance instead of beeline
        boolean fifoFeasible =
            (oo.getDistance() + beeline(reqJ.originX, reqJ.originY, reqI.destinationX, reqI.destinationY))
                <= reqI.directDistance * reqI.maxDetourFactor &&
            (beeline(reqJ.originX, reqJ.originY, reqI.destinationX, reqI.destinationY)
                + beeline(reqI.destinationX, reqI.destinationY, reqJ.destinationX, reqJ.destinationY))
                <= reqJ.directDistance * reqJ.maxDetourFactor;

        boolean lifoFeasible =
            (oo.getDistance()
                + beeline(reqJ.originX, reqJ.originY, reqJ.destinationX, reqJ.destinationY)
                + beeline(reqJ.destinationX, reqJ.destinationY, reqI.destinationX, reqI.destinationY))
                <= reqI.directDistance * reqI.maxDetourFactor;

        if (!fifoFeasible && !lifoFeasible) {
            beelineRejected.incrementAndGet();
            continue;
        }

        if (fifoFeasible) {
            PairCandidate fifo = tryFifoCandidate(reqI, reqJ, oo);
            if (fifo != null) results.add(fifo);
        }

        if (lifoFeasible) {
            PairCandidate lifo = tryLifoCandidate(reqI, reqJ, oo);
            if (lifo != null) results.add(lifo);
        }
    }

    return results;
}
```

Key changes:
- Added `batchPrecompute()` call at top — one SSSP per reqI
- Replaced 5-beeline O→O check with actual `oo.getDistance()` for O→O leg
- Still use beeline for O→D and D→D legs (those segments aren't batch-precomputed yet)
- The FIFO/LIFO feasibility check is now TIGHTER: real O→O distance + beeline for remaining legs (previously all beeline)
- `network.getSegment()` for O→O is now guaranteed cache hit → ~O(1)
- FIFO/LIFO `tryFifoCandidate()`/`tryLifoCandidate()` unchanged — they call `network.getSegment()` for O→D and D→D segments (point-to-point, may miss cache but these are far fewer calls)

**Step 2: Run E2E tests**

Run: `mvn test -pl contribs/drt-demand-extraction -Dtest=ExMasDemandExtractionE2ETest`
Expected: PASS — output should be identical since routing results are the same

Also run: `mvn test -pl contribs/drt-demand-extraction -Dtest=ExMasKelheimE2ETest`
Expected: PASS

**Step 3: Commit**

```bash
git add contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/generation/PairGenerator.java
git commit -m "perf: use SSSP batch precompute for O→O routing in PairGenerator"
```

---

## Task 3: Also batch-precompute reqI.dest segments (FIFO D→D)

**Files:**
- Modify: `src/main/java/org/matsim/contrib/demand_extraction/algorithm/generation/PairGenerator.java`

The FIFO check needs `reqI.dest → reqJ.dest` segments. Since `reqI.dest` is the same for all candidates of a given reqI, one more SSSP covers all D→D lookups.

**Step 1: Add second batchPrecompute call**

In `generateCandidatesForRequest()`, after the O→O batch precompute, add:

```java
// Batch precompute D→D segments: one SSSP from reqI.dest covers all FIFO D→D lookups
// Stop criterion: max(reqJ.getMaxTravelTime()) across candidates — D_i→D_j is part
// of reqJ's budget, so the tightest correct bound is the largest candidate budget
double maxCandidateMaxTravelTime = 0;
Id<Link>[] candidateDestinations = new Id[candidateIndices.length];
for (int k = 0; k < candidateIndices.length; k++) {
    DrtRequest reqJ = filter.getRequest(candidateIndices[k]);
    candidateDestinations[k] = reqJ.destinationLinkId;
    maxCandidateMaxTravelTime = Math.max(maxCandidateMaxTravelTime, reqJ.getMaxTravelTime());
}
network.batchPrecompute(reqI.destinationLinkId, reqI.requestTime, candidateDestinations,
    maxCandidateMaxTravelTime);
```

Now both `tryFifoCandidate()` line 262 (`network.getSegment(reqI.destinationLinkId, reqJ.destinationLinkId, ...)`) and `tryLifoCandidate()` line 307 (`network.getSegment(reqJ.destinationLinkId, reqI.destinationLinkId, ...)`) benefit. The FIFO D→D call is a guaranteed cache hit. The LIFO D→D call (from reqJ.dest) won't be cached by THIS SSSP, but will be cached when reqJ is processed as reqI.

**Step 2: Update the FIFO/LIFO feasibility check**

Now that we also have D→D segments cached, we COULD look them up instead of using beeline. But this adds a second `getSegment()` call per candidate before knowing if the pair is feasible at all. For simplicity, keep the beeline-based pre-filter for the O→D and D→D legs — it's cheap and effective enough.

Alternatively, for a cleaner approach: remove the beeline check entirely and just let `tryFifoCandidate()`/`tryLifoCandidate()` handle it via the maxTravelTime check on line 272/317. The SSSP-cached segments make the `getSegment()` calls in those methods fast. This simplifies the code.

**Recommended: Remove the beeline pre-filter entirely.** The O→O, D→D segments are now cache hits. Only O→D (from reqJ.origin) may be a cache miss, but it's one routing call that would have happened anyway. The `maxTravelTime` check in `tryFifoCandidate`/`tryLifoCandidate` rejects infeasible pairs after computing the actual segments.

Simplified candidate loop:
```java
for (int j : candidateIndices) {
    DrtRequest reqJ = filter.getRequest(j);

    if (reqI.getPaxId().equals(reqJ.getPaxId())) continue;

    if (reqJ.getLatestDeparture() < reqI.getEarliestDeparture() ||
            reqJ.getEarliestDeparture() > reqI.getLatestDeparture() + reqI.getTravelTime()) {
        continue;
    }

    // O→O: cache hit from batch precompute
    TravelSegment oo = network.getSegment(reqI.originLinkId, reqJ.originLinkId, reqI.requestTime);
    if (!oo.isReachable()) continue;

    if (reqI.getLatestDeparture() + oo.getTravelTime() < reqJ.getEarliestDeparture()) continue;
    if (reqI.getEarliestDeparture() + oo.getTravelTime() > reqJ.getLatestDeparture()) continue;

    // Try both — tryFifo/tryLifo handle feasibility via maxTravelTime check internally
    PairCandidate fifo = tryFifoCandidate(reqI, reqJ, oo);
    if (fifo != null) results.add(fifo);

    PairCandidate lifo = tryLifoCandidate(reqI, reqJ, oo);
    if (lifo != null) results.add(lifo);
}
```

Wait — this removes the beeline guard that prevents calling tryFifo/tryLifo on obviously infeasible pairs. Without it, EVERY O→O-feasible pair triggers FIFO and LIFO routing calls. That could be MORE routing calls than before.

**Keep a lightweight guard:** Use the already-fetched `oo.getDistance()` to reject pairs where O→O alone exceeds the detour budget:
```java
if (oo.getDistance() > reqI.directDistance * reqI.maxDetourFactor) continue;
```

This single check (no sqrt, no beeline) rejects pairs where the O→O leg already uses the full detour budget, making FIFO impossible. LIFO would also be infeasible since O→O is just the first leg.

**Step 3: Run E2E tests**

Run: `mvn test -pl contribs/drt-demand-extraction -Dtest=ExMasDemandExtractionE2ETest,ExMasKelheimE2ETest`
Expected: PASS

**Step 4: Commit**

```bash
git add contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/generation/PairGenerator.java
git commit -m "perf: batch-precompute reqI.dest segments, simplify feasibility checks"
```

---

## Task 4: Add diagnostic logging

**Files:**
- Modify: `src/main/java/org/matsim/contrib/demand_extraction/algorithm/network/MatsimNetworkCache.java`
- Modify: `src/main/java/org/matsim/contrib/demand_extraction/algorithm/generation/PairGenerator.java`

**Step 1: Add batch precompute counters to MatsimNetworkCache**

```java
private final AtomicInteger batchTreesComputed = new AtomicInteger(0);
private final AtomicInteger batchTreesSkipped = new AtomicInteger(0);
private final AtomicLong batchSegmentsPopulated = new AtomicLong(0);
```

Increment in `batchPrecompute()`:
- `batchTreesComputed` when a tree is actually computed
- `batchTreesSkipped` when the skip-check triggers (first target already cached)
- `batchSegmentsPopulated` for each new cache entry created

Add to `logRoutingStatistics()`:
```java
log.info("  Batch precompute: {} trees computed, {} skipped, {} segments populated",
    batchTreesComputed.get(), batchTreesSkipped.get(), batchSegmentsPopulated.get());
```

**Step 2: Update PairGenerator logging**

Replace the beeline rejection log with a more relevant metric. Log:
- Number of O→O reachable pairs (after cache lookup)
- Number of temporal rejections (after O→O time check)

**Step 3: Run E2E test**

Run: `mvn test -pl contribs/drt-demand-extraction -Dtest=ExMasDemandExtractionE2ETest`
Expected: PASS with new log lines visible

**Step 4: Commit**

```bash
git add contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/network/MatsimNetworkCache.java
git add contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/generation/PairGenerator.java
git commit -m "feat: add SSSP batch precompute diagnostic logging"
```

---

## Task 5: Run full test suite and squash into single commit

**Step 1: Run all tests**

```bash
mvn test -pl contribs/drt-demand-extraction
```

Expected: ALL PASS. If any test fails, it's likely a regression — the SSSP results should be bit-identical to point-to-point routing.

**Step 2: Squash commits into one**

If all tests pass, squash the 4 commits into a single commit for a clean history:

```bash
git rebase -i HEAD~4
# Mark commits 2-4 as "squash"
```

Final commit message:
```
perf: SSSP batch routing for pair generation

Replace point-to-point routing in PairGenerator with LeastCostPathTree
(SSSP) batch precomputation. For each reqI, one Dijkstra pass from
reqI.origin and one from reqI.dest pre-populate the MatsimNetworkCache
with all candidate travel segments. Subsequent getSegment() calls are
cache hits. Early termination via TravelTimeStopCriterion limits tree
exploration to reqI's detour budget.

Speedup: ~100x for O→O routing (1 SSSP replaces ~100 SpeedyALT calls
per request). Total pair generation for 100k+ requests: ~10h → ~minutes.

Changes:
- MatsimNetworkCache: add batchPrecompute() with TravelTimeStopCriterion
  using ThreadLocal<LeastCostPathTree>
- PairGenerator: batch-precompute O→O and D→D before candidate loop
- PairGenerator: simplify feasibility check (O→O distance replaces beeline)
- Add diagnostic logging for batch precompute statistics
```

---

## Design Notes

### Why this is simple
- MatsimNetworkCache API unchanged — `getSegment()` still works exactly as before
- PairGenerator structure unchanged — just adds precompute calls at top of candidate loop
- No new data structures — SSSP results go into the existing ConcurrentHashMap cache
- Extensions and downstream code unaffected — they benefit from warmer cache

### What we intentionally left out (future optimizations)
- **Smart request ordering** — sorting requests by origin node to maximize SSSP reuse across reqI's. Current code relies on the skip-check (first target cached → skip tree). This works but may recompute trees for the same origin in different time bins.
- **FIFO O→D batch** — the O→D segment (from reqJ.origin) varies per candidate and can't be batched for a single reqI. It benefits from cache hits when reqJ is later processed as reqI, but isn't explicitly batch-computed. Would require caching SSSP trees across reqI iterations.

### Memory impact
- `ThreadLocal<LeastCostPathTree>`: ~4.4 MB per thread (109k nodes × 3 doubles + arrays). With 16 threads: ~70 MB. Pre-allocated at full network size even with early termination (arrays are reused across `calculate()` calls).
- Cache entries: only segments for supplied target links are stored — NOT the entire tree. Each segment is one `TravelSegment` (3 doubles) + `CacheKey` overhead. With early termination, targets beyond the travel time limit produce `TravelSegment.unreachable()` (still cached to avoid re-computation).
- No additional persistent data structures.

### Early termination
- Uses `LeastCostPathTree.TravelTimeStopCriterion` (built-in MATSim class)
- O→O tree: bounded by `reqI.getMaxTravelTime()` = `directTravelTime × maxDetourFactor`. Tight bound — if O→O alone exceeds the detour budget, no arrangement works.
- D→D tree: bounded by `max(reqJ.getMaxTravelTime())` across all candidates. The D_i→D_j segment is part of reqJ's total ride, so the tightest correct bound is the largest candidate budget. (Note: `horizon` is NOT a valid bound — it constrains departure time difference, not spatial distance between destinations.)
- Dijkstra explores in cost order; MATSim cost monotonically increases with travel time, so `break` is safe.
- Estimated speedup: ~3ms → ~0.3ms per tree for typical DRT trips (few km, exploring only local neighborhood instead of full 109k-node network).

### Correctness guarantee
Both `SpeedyALT.calcLeastCostPath(Link, Link)` (line 87) and `LeastCostPathTree.calculate(Link, ...)` (line 77) start from `startLink.getToNode()`. Destinations are looked up at `toLink.getFromNode()`. Both compute optimal shortest paths on the same SpeedyGraph with the same TravelTime/TravelDisutility. Results are numerically identical.
