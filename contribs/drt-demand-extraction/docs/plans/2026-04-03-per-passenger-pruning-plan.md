# Per-Passenger Travel Time Pruning — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Prune ordering enumeration branches where any individual passenger's accumulated in-vehicle time already exceeds their maxTravelTime — catching the 98.2% of failing orderings INSIDE the recursion tree instead of at the leaf.

**Architecture:** Track pickup times during origin enumeration (free — just record what the recursion already computes). During destination enumeration, at each depth check every in-vehicle passenger's accumulated time against their maxTravelTime. If exceeded, prune the entire subtree (return) or skip the candidate (continue). No extra segment lookups — uses only data the recursion already has.

**Tech Stack:** Java 17, modifying `OrderingEnumerator.java` recursion methods.

---

## Files Modified

| File | What changes |
|------|-------------|
| `algorithm/extension/OrderingEnumerator.java` | Add `pickupTimes[]` parameter threading, two pruning checks in destination recursion, temporal check in origin recursion |
| `algorithm/extension/EnumerationStats.java` | Add pruning counters |

All paths relative to `matsim-libs/contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/`.

---

## How the Recursion Works (Context)

The `enumerateAndEvaluate` hot path has this call chain:

```
enumerateAndEvaluate()                          [line 152 — entry point]
  └─ enumerateOriginsPrunedWithEval()           [line 183 — origin recursion]
       ├─ depth 0..n-1: place pickups, route segments, accumulate currentTime
       └─ depth == n: call enumerateDestPrunedWithEval()   [line 243 — bridge]
            └─ enumerateDestTopoWithEval()      [line 282 — destination recursion]
                 ├─ depth 0..n-1: place dropoffs, route segments, accumulate currentTime
                 └─ depth == n: call evaluator   [line 295 — leaf → processSet callback]
```

At each depth in both phases, `currentTime` is accumulated from segment routing that's ALREADY happening for distance pruning. We add pickup time recording in the origin phase and travel time checks in the destination phase using this existing data.

---

## Task 1: Add pruning counters to EnumerationStats

**File:** `algorithm/extension/EnumerationStats.java`

- [ ] **Step 1: Add new counter fields**

After the existing `segmentLookups` field (line 23), add:

```java
public long prunedByTravelTime;      // Destination subtrees/candidates pruned by maxTravelTime check
```

- [ ] **Step 2: Add to sum() method**

In the `sum()` method, add after `total.segmentLookups += ...`:

```java
total.prunedByTravelTime += s.prunedByTravelTime;
```

- [ ] **Step 3: Add to log() method**

After the segment lookups log line, add:

```java
log.info("  Pruned by travel time: {} ({} per set)", prunedByTravelTime,
        setsProcessed > 0 ? String.format("%.1f", (double) prunedByTravelTime / setsProcessed) : "N/A");
```

- [ ] **Step 4: Add to reset block in RideExtender**

In RideExtender's stats reset block (after `pool.shutdown()`), add:

```java
s.prunedByTravelTime = 0;
```

- [ ] **Step 5: Compile**

```bash
cd matsim-libs/contribs/drt-demand-extraction && mvn compile -Denforcer.skip=true -o -q
```

---

## Task 2: Thread pickupTimes through origin recursion

**File:** `algorithm/extension/OrderingEnumerator.java`

The origin recursion (`enumerateOriginsPrunedWithEval`, line 183) needs to record when each passenger is picked up. We add a `double[] pickupTimes` parameter that flows through the recursion and into the destination phase.

- [ ] **Step 1: Modify `enumerateAndEvaluate()` to allocate and pass pickupTimes**

At line 152, the entry point. Replace lines 178-180:

```java
enumerateOriginsPrunedWithEval(origAdj, n, pairs, network, requests,
        bestValidDist, new boolean[n], new int[n], 0,
        0.0, 0.0, evaluator);
```

With:

```java
enumerateOriginsPrunedWithEval(origAdj, n, pairs, network, requests,
        bestValidDist, new boolean[n], new int[n], new double[n], 0,
        0.0, 0.0, evaluator);
```

- [ ] **Step 2: Modify `enumerateOriginsPrunedWithEval()` signature**

At line 183, add `double[] pickupTimes` parameter after `int[] perm`:

```java
private static void enumerateOriginsPrunedWithEval(
        Boolean[][] adj, int n, PairInfo[] pairs,
        MatsimNetworkCache network, DrtRequest[] requests,
        double[] bestValidDist,
        boolean[] used, int[] perm, double[] pickupTimes, int depth,
        double partialDist, double currentTime,
        Consumer<Ordering> evaluator) {
```

- [ ] **Step 3: Record pickup time at depth 0 and pass pickupTimes to recursive call**

Replace the depth 0 block (lines 210-220):

```java
if (depth == 0) {
    for (int c : candidates) {
        used[c] = true;
        perm[0] = c;
        pickupTimes[c] = requests[c].getRequestTime();
        enumerateOriginsPrunedWithEval(adj, n, pairs, network, requests,
                bestValidDist, used, perm, pickupTimes, 1,
                0.0, requests[c].getRequestTime(), evaluator);
        used[c] = false;
    }
    return;
}
```

- [ ] **Step 4: Record pickup time at depth > 0 and pass pickupTimes to recursive call**

In the depth > 0 loop (lines 229-240), after the distance pruning check and before the recursive call, record pickup time and pass pickupTimes:

```java
for (int c : candidates) {
    TravelSegment seg = segMap.get(c);
    double newPartialDist = partialDist + seg.getDistance();
    if (newPartialDist > bestValidDist[0]) break;

    used[c] = true;
    perm[depth] = c;
    pickupTimes[c] = currentTime + seg.getTravelTime();
    enumerateOriginsPrunedWithEval(adj, n, pairs, network, requests,
            bestValidDist, used, perm, pickupTimes, depth + 1,
            newPartialDist, currentTime + seg.getTravelTime(), evaluator);
    used[c] = false;
}
```

- [ ] **Step 5: Pass pickupTimes from origin leaf to destination bridge**

At line 191-194, where origin recursion calls destination bridge at depth == n:

```java
if (depth == n) {
    enumerateDestPrunedWithEval(n, perm, pairs, network, requests,
            bestValidDist, partialDist, currentTime, pickupTimes, evaluator);
    return;
}
```

- [ ] **Step 6: Modify `enumerateDestPrunedWithEval()` signature to receive and pass pickupTimes**

At line 243, add `double[] pickupTimes` parameter before `Consumer<Ordering> evaluator`:

```java
private static void enumerateDestPrunedWithEval(
        int n, int[] origPerm, PairInfo[] pairs,
        MatsimNetworkCache network, DrtRequest[] requests,
        double[] bestValidDist,
        double partialDist, double currentTime,
        double[] pickupTimes,
        Consumer<Ordering> evaluator) {
```

And at line 277-279, pass pickupTimes to the destination recursion:

```java
enumerateDestTopoWithEval(adj, n, origPerm, network, requests,
        bestValidDist, new boolean[n], new int[n], 0,
        partialDist, currentTime, prevLink, pickupTimes, evaluator);
```

- [ ] **Step 7: Modify `enumerateDestTopoWithEval()` signature to receive pickupTimes**

At line 282, add `double[] pickupTimes` parameter before `Consumer<Ordering> evaluator`:

```java
private static void enumerateDestTopoWithEval(
        Boolean[][] adj, int n, int[] origPerm,
        MatsimNetworkCache network, DrtRequest[] requests,
        double[] bestValidDist,
        boolean[] used, int[] perm, int depth,
        double partialDist, double currentTime,
        Id<Link> prevLinkId,
        double[] pickupTimes,
        Consumer<Ordering> evaluator) {
```

And update the recursive call at line 326-329 to pass pickupTimes:

```java
enumerateDestTopoWithEval(adj, n, origPerm, network, requests,
        bestValidDist, used, perm, depth + 1,
        newPartialDist, currentTime + seg.getTravelTime(),
        requests[c].destinationLinkId, pickupTimes, evaluator);
```

- [ ] **Step 8: Compile**

```bash
mvn compile -Denforcer.skip=true -o -q
```

---

## Task 3: Add per-passenger travel time pruning in destination recursion

**File:** `algorithm/extension/OrderingEnumerator.java`, method `enumerateDestTopoWithEval` (line 282)

Two pruning checks:

**Check A (start of depth):** Before processing candidates, check if any in-vehicle passenger's accumulated time already exceeds their maxTravelTime. If so, prune the ENTIRE subtree — their time can only increase.

**Check B (after routing to candidate):** After computing segment travel time for a candidate, check if the updated time violates any remaining in-vehicle passenger. If so, skip this candidate (continue).

- [ ] **Step 1: Add Check A — prune entire subtree at start of depth**

In `enumerateDestTopoWithEval`, after the `if (depth == n)` block (line 297) and before the candidates loop, add:

```java
// Per-passenger travel time pruning: if any in-vehicle passenger's accumulated
// time already exceeds their maxTravelTime, prune the entire subtree.
// Their time can only increase as more stops are visited before their dropoff.
EnumerationStats stats = EnumerationStats.get();
for (int p = 0; p < n; p++) {
    if (used[p]) continue; // already dropped off
    double inVehicleTime = currentTime - pickupTimes[origPerm[p]];
    if (inVehicleTime > requests[origPerm[p]].getMaxTravelTime()) {
        stats.prunedByTravelTime++;
        return;
    }
}
```

**IMPORTANT NOTE on indexing:** In the destination recursion, `used[p]` and `perm[p]` use LOCAL indices (0..n-1). These map to request LOCAL indices via `origPerm`. The `pickupTimes` array was populated using the SAME local indices (in the origin recursion, `pickupTimes[c]` where c is a local index). So `pickupTimes[origPerm[p]]` is WRONG — we need `pickupTimes[p]`... 

Wait, let me re-check. In the origin phase:
- `perm[depth] = c` where `c` is a local index (0..n-1)
- `pickupTimes[c] = currentTime + seg.getTravelTime()` — indexed by local index

In the destination phase:
- `origPerm[i]` maps position i in origin order to local index
- `used[p]` — p is a local index, used means this local index has been dropped off
- `requests[origPerm[p]]` — WRONG. Actually, looking at the destination code more carefully:

In `enumerateDestTopoWithEval`, line 282-332:
- `used[c]` — c is a local index (0..n-1)
- `requests[c]` — wait, is this requests passed to the method? Let me check...

The `requests` parameter is `DrtRequest[] requests` passed from `enumerateAndEvaluate`. In `enumerateAndEvaluate` (line 154), `requests` is the parameter from the caller (RideExtender). In RideExtender's `processSet`, `setRequests` is built by mapping `newSet[i] → requestMap.get(newSet[i])`. So `requests[i]` corresponds to the local index i.

In the destination recursion, candidates are local indices. `requests[c]` gives the DrtRequest for local index c. `pickupTimes[c]` gives the pickup time for local index c (set in the origin phase).

So the check should use local index `p` directly:

```java
double inVehicleTime = currentTime - pickupTimes[p];
if (inVehicleTime > requests[p].getMaxTravelTime()) {
```

No `origPerm` mapping needed here — both `pickupTimes` and `requests` are indexed by local index.

OK, let me correct the plan.

- [ ] **Step 2: Add Check B — skip individual candidates in destination loop**

In the candidates loop (lines 319-331), after the distance pruning check and before recursing, add:

```java
for (int c : candidates) {
    TravelSegment seg = segMap.get(c);
    double newPartialDist = partialDist + seg.getDistance();
    if (newPartialDist > bestValidDist[0]) break;

    // Per-passenger check: after routing to this candidate's destination,
    // would any remaining in-vehicle passenger exceed their maxTravelTime?
    double newTime = currentTime + seg.getTravelTime();
    boolean busted = false;
    for (int p = 0; p < n; p++) {
        if (used[p] || p == c) continue; // already dropped off, or being dropped off now
        if (newTime - pickupTimes[p] > requests[p].getMaxTravelTime()) {
            busted = true;
            break;
        }
    }
    if (busted) {
        stats.prunedByTravelTime++;
        continue; // try next candidate (not break — other candidates may have shorter travel time)
    }

    used[c] = true;
    perm[depth] = c;
    enumerateDestTopoWithEval(adj, n, origPerm, network, requests,
            bestValidDist, used, perm, depth + 1,
            newPartialDist, newTime,
            requests[c].destinationLinkId, pickupTimes, evaluator);
    used[c] = false;
}
```

**Why `continue` not `break`:** Candidates are sorted by DISTANCE, not travel time. A farther candidate might have shorter travel time (different road speeds). So we can't assume subsequent candidates are worse — must check each.

- [ ] **Step 3: Compile**

```bash
mvn compile -Denforcer.skip=true -o -q
```

- [ ] **Step 4: Run E2E tests**

```bash
mvn test -Dtest=ExMasDemandExtractionE2ETest -Denforcer.skip=true -o
mvn test -Dtest=ExMasKelheimHyperPoolE2ETest -Denforcer.skip=true -o
```

Both must PASS with identical ride counts.

---

## Task 4: Validate correctness with 1% Bavaria

- [ ] **Step 1: Run 1% Bavaria**

```bash
mvn exec:java -o \
  -Dexec.mainClass=org.matsim.contrib.demand_extraction.run.RunBavaria30kmDemandExtraction \
  -Dexec.args="--scenario-path ../../../matsim_scenarios/bavaria/output/kelheim_30km_100pct \
    --population ../../../matsim_scenarios/bavaria/output/populations/population_1pct_kelheim30km.xml.gz \
    --sample 100 --iterations 0 --trip-filter-radius 30 \
    --filter-municipality Kelheim \
    --shapes ../../../matsim_scenarios/bavaria/data/germany/tmp_vg250/vg250-ew_12-31.utm32s.gpkg.ebenen/vg250-ew_ebenen_1231/DE_VG250.gpkg \
    --travel-times ../../../matsim_scenarios/bavaria/output/base-simulation-10pct/travel_times.tsv \
    --output-dir ../../../matsim_scenarios/bavaria/output/demand-extraction-1pct-traveltime-pruning \
    --no-predecessors --inter-degree-keep 1.0" \
  -Denforcer.skip=true
```

**Must produce exactly 12,552 rides.**

- [ ] **Step 2: Check profiling output**

Look for "Pruned by travel time" in the log. At degree 6+, expect significant pruning counts.

---

## Task 5: Profile with 10% Bavaria to degree 5

- [ ] **Step 1: Run 10% Bavaria to degree 5**

```bash
mvn exec:java -o \
  -Dexec.mainClass=org.matsim.contrib.demand_extraction.run.RunBavaria30kmDemandExtraction \
  -Dexec.args="--scenario-path ../../../matsim_scenarios/bavaria/output/kelheim_30km_100pct \
    --population ../../../matsim_scenarios/bavaria/output/populations/population_10pct_kelheim30km.xml.gz \
    --sample 100 --iterations 0 --trip-filter-radius 30 \
    --filter-municipality Kelheim \
    --shapes ../../../matsim_scenarios/bavaria/data/germany/tmp_vg250/vg250-ew_12-31.utm32s.gpkg.ebenen/vg250-ew_ebenen_1231/DE_VG250.gpkg \
    --travel-times ../../../matsim_scenarios/bavaria/output/base-simulation-10pct/travel_times.tsv \
    --output-dir ../../../matsim_scenarios/bavaria/output/demand-extraction-10pct-traveltime-pruning \
    --no-predecessors --max-degree 5" \
  -Denforcer.skip=true
```

- [ ] **Step 2: Compare against baseline**

| Metric | Baseline (no pruning) | With pruning |
|--------|----------------------|--------------|
| Degree 5 orderings/set | 180.7 | ? (target: <50) |
| Degree 5 constraint pass rate | 1.8% | ? (target: >5%) |
| Degree 5 wall time | 755s | ? (target: <300s) |
| Degree 5 pruned by travel time | N/A | ? |

---

## Expected Impact

At degree 5 (10% data):
- 4.8B orderings evaluated, 98.2% fail constraints
- Most failures are maxTravelTime violations in `buildRideFromOrdering` line 295
- Check A (start of depth) catches passengers already over the limit before ANY candidate processing → prunes entire subtrees
- Check B (after routing) catches passengers that the new segment pushes over → skips individual candidates

The checks cost O(k) comparisons per destination depth — at degree 5 that's 5 depths × ~3 comparisons = ~15 comparisons per destination path. At ~1ns per comparison, that's 15ns per path. Negligible compared to the ~50ns per segment lookup that gets AVOIDED by pruning.

**Conservative estimate:** If pruning eliminates 50% of the recursion tree nodes at degree 5:
- Orderings reaching evaluator: 180.7 → ~90
- Segment lookups: 1,626/set → ~800/set
- Pure enumeration time: 57.5% → ~30%
- Ride construction time: 39.5% → ~20%
- Wall time: 755s → ~400s

**Optimistic estimate:** If pruning eliminates 80%:
- Orderings: 180.7 → ~36
- Wall time: 755s → ~200s
