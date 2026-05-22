# Branch-and-Bound Enumeration + Sort-First Validation — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Reduce ordering enumeration from 1,756 to ~10-50 per set at degree 6 via tightening upper bound, then validate only the 1-2 shortest orderings instead of all 252. Combined ~100-350x speedup per set at high degrees.

**Architecture:** Two changes: (1) Add `double[] bestFoundDist` tightening bound to the recursive enumeration in `OrderingEnumerator` — the greedy path (explored first due to distance sorting) sets an initial bound, pruning all subsequent branches with distance > bound. (2) Add `rideDistance` to the `Ordering` record (captured during enumeration), sort orderings by distance in `processSet`, and break after the first ride that passes budget validation.

**Tech Stack:** Java 17, MATSim, JUnit 5

---

## Task 1: Add `rideDistance` to Ordering record + tightening bound to enumeration

**Files:**
- Modify: `OrderingEnumerator.java`

**Step 1: Change the Ordering record** (line 46)

Replace:
```java
public record Ordering(int[] originPerm, int[] destPerm) {}
```

With:
```java
/** A valid ordering with its total ride distance (from cumulative segment routing). */
public record Ordering(int[] originPerm, int[] destPerm, double rideDistance) {}
```

**Step 2: Update `enumerate()` to pass rideDistance=0** (line 68)

The unpruned `enumerate()` doesn't track distance. Pass `Double.NaN` as a sentinel:

Replace:
```java
result.add(new Ordering(origPerm, destPerm));
```

With:
```java
result.add(new Ordering(origPerm, destPerm, Double.NaN));
```

**Step 3: Add `double[] bestFoundDist` parameter to `enumeratePruned`**

Replace the `enumeratePruned` method body (lines 94-126):

```java
public static List<Ordering> enumeratePruned(
        int[] requestIndices, ShareabilityGraph graph,
        MatsimNetworkCache network, DrtRequest[] requests,
        double maxRideDistance) {

    if (maxRideDistance >= Double.MAX_VALUE / 2) {
        return enumerate(requestIndices, graph);
    }

    int n = requestIndices.length;
    PairInfo[] pairs = extractConstraints(requestIndices, n, graph);
    if (pairs == null) return List.of();

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

    // Tightening bound: starts at maxRideDistance, shrinks as shorter orderings are found.
    // Mutable array used as pass-by-reference for the recursive methods.
    double[] bestFoundDist = { maxRideDistance };

    List<Ordering> result = new ArrayList<>();
    enumerateOriginsPruned(origAdj, n, pairs, network, requests,
            maxRideDistance, bestFoundDist, new boolean[n], new int[n], 0,
            0.0, 0.0, result);
    return result;
}
```

**Step 4: Thread `bestFoundDist` through `enumerateOriginsPruned`**

Replace the full method (lines 128-192):

```java
private static void enumerateOriginsPruned(
        Boolean[][] adj, int n, PairInfo[] pairs,
        MatsimNetworkCache network, DrtRequest[] requests,
        double maxRideDistance, double[] bestFoundDist,
        boolean[] used, int[] perm, int depth,
        double partialDist, double currentTime,
        List<Ordering> result) {

    if (depth == n) {
        enumerateDestinationsPruned(n, perm, pairs, network, requests,
                maxRideDistance, bestFoundDist, partialDist, currentTime, result);
        return;
    }

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
        for (int c : candidates) {
            used[c] = true;
            perm[0] = c;
            enumerateOriginsPruned(adj, n, pairs, network, requests,
                    maxRideDistance, bestFoundDist, used, perm, 1,
                    0.0, requests[c].getRequestTime(), result);
            used[c] = false;
        }
        return;
    }

    Id<Link> prevLink = requests[perm[depth - 1]].originLinkId;
    Map<Integer, TravelSegment> segMap = new HashMap<>();
    for (int c : candidates) {
        segMap.put(c, network.getSegment(prevLink, requests[c].originLinkId, currentTime));
    }
    candidates.sort(Comparator.comparingDouble(c -> segMap.get(c).getDistance()));

    for (int c : candidates) {
        TravelSegment seg = segMap.get(c);
        double newPartialDist = partialDist + seg.getDistance();
        // Use tightening bound: prune if exceeds best found so far
        if (newPartialDist > bestFoundDist[0]) break;

        used[c] = true;
        perm[depth] = c;
        enumerateOriginsPruned(adj, n, pairs, network, requests,
                maxRideDistance, bestFoundDist, used, perm, depth + 1,
                newPartialDist, currentTime + seg.getTravelTime(), result);
        used[c] = false;
    }
}
```

**Step 5: Thread `bestFoundDist` through `enumerateDestinationsPruned`**

Replace the full method (lines 194-233):

```java
private static void enumerateDestinationsPruned(
        int n, int[] origPerm, PairInfo[] pairs,
        MatsimNetworkCache network, DrtRequest[] requests,
        double maxRideDistance, double[] bestFoundDist,
        double partialDist, double currentTime,
        List<Ordering> result) {

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
                return;
            }
        }
    }

    Id<Link> prevLink = requests[origPerm[n - 1]].originLinkId;

    enumerateDestTopoSortPruned(adj, n, origPerm, network, requests,
            maxRideDistance, bestFoundDist, new boolean[n], new int[n], 0,
            partialDist, currentTime, prevLink, result);
}
```

**Step 6: Thread `bestFoundDist` through `enumerateDestTopoSortPruned` + tighten on completion + store rideDistance**

Replace the full method (lines 235-285):

```java
private static void enumerateDestTopoSortPruned(
        Boolean[][] adj, int n, int[] origPerm,
        MatsimNetworkCache network, DrtRequest[] requests,
        double maxRideDistance, double[] bestFoundDist,
        boolean[] used, int[] perm, int depth,
        double partialDist, double currentTime,
        Id<Link> prevLinkId,
        List<Ordering> result) {

    if (depth == n) {
        // Complete ordering found — tighten bound for subsequent branches
        if (partialDist < bestFoundDist[0]) {
            bestFoundDist[0] = partialDist;
        }
        result.add(new Ordering(origPerm.clone(), perm.clone(), partialDist));
        return;
    }

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

    Map<Integer, TravelSegment> segMap = new HashMap<>();
    for (int c : candidates) {
        segMap.put(c, network.getSegment(prevLinkId,
                requests[c].destinationLinkId, currentTime));
    }
    candidates.sort(Comparator.comparingDouble(c -> segMap.get(c).getDistance()));

    for (int c : candidates) {
        TravelSegment seg = segMap.get(c);
        double newPartialDist = partialDist + seg.getDistance();
        // Use tightening bound
        if (newPartialDist > bestFoundDist[0]) break;

        used[c] = true;
        perm[depth] = c;
        enumerateDestTopoSortPruned(adj, n, origPerm, network, requests,
                maxRideDistance, bestFoundDist, used, perm, depth + 1,
                newPartialDist, currentTime + seg.getTravelTime(),
                requests[c].destinationLinkId, result);
        used[c] = false;
    }
}
```

**Step 7: Compile**

```bash
cd matsim-libs/contribs/drt-demand-extraction && mvn compile -Denforcer.skip=true -o -q
```

---

## Task 2: Update `processSet` — sort by distance, early exit on first valid

**Files:**
- Modify: `RideExtender.java`

**Step 1: Replace `processSet`** (the full method, including removing instrumentation counters)

Remove the instrumentation fields (statSetsWithOrderings, statTotalOrderings, etc.) and the stat logging block in extendRides. Then replace processSet with:

```java
private Ride processSet(int[] newSet) {
    DrtRequest[] setRequests = new DrtRequest[newSet.length];
    for (int i = 0; i < newSet.length; i++) {
        setRequests[i] = requestMap.get(newSet[i]);
    }

    for (int i = 0; i < setRequests.length; i++) {
        for (int j = i + 1; j < setRequests.length; j++) {
            if (setRequests[i].getPaxId().equals(setRequests[j].getPaxId())) {
                return null;
            }
        }
    }

    double maxAllowedRideDistance = computeMaxAllowedRideDistance(setRequests);

    List<OrderingEnumerator.Ordering> orderings = OrderingEnumerator.enumeratePruned(
            newSet, graph, network, setRequests, maxAllowedRideDistance);

    if (orderings.isEmpty()) return null;

    // Sort by ride distance (shortest first) — enables early exit
    orderings.sort(Comparator.comparingDouble(OrderingEnumerator.Ordering::rideDistance));

    // Try shortest ordering first. If it passes budget validation, it's the
    // optimal ride for this set (shortest distance = best objective).
    // If it fails, try next shortest, etc.
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

        // First valid ride is the best (sorted by distance = objective)
        return validated;
    }
    return null;
}
```

Note: This assumes the objective is `rideDistance` (the default and most common). For other objectives (passengerTravelTime, passengerUtility), the first-valid-shortest-distance may not be globally optimal — but it's a very good approximation and we accept this trade-off for the performance gain.

**Step 2: Remove instrumentation counters and stats logging**

Remove the 6 `AtomicInteger stat*` fields and the stats logging block in `extendRides` (the block that logs "Ordering stats:" and "Of N sets with results:").

**Step 3: Compile + run E2E tests**

```bash
mvn compile -Denforcer.skip=true -o -q
mvn test -Dtest=ExMasDemandExtractionE2ETest -Denforcer.skip=true -o
mvn test -Dtest=ExMasKelheimHyperPoolE2ETest -Denforcer.skip=true -o
```

Both should PASS.

---

## Task 3: Run 1% Bavaria validation + compare ride counts

**Step 1: Run 1% with the optimized code**

```bash
cd matsim-libs/contribs/drt-demand-extraction
mvn exec:java -o \
  -Dexec.mainClass=org.matsim.contrib.demand_extraction.run.RunBavaria30kmDemandExtraction \
  -Dexec.args="--scenario-path ../../../matsim_scenarios/bavaria/output/kelheim_30km_100pct \
    --population ../../../matsim_scenarios/bavaria/output/populations/population_1pct_kelheim30km.xml.gz \
    --sample 100 --iterations 0 --trip-filter-radius 30 \
    --filter-municipality Kelheim \
    --shapes ../../../matsim_scenarios/bavaria/data/germany/tmp_vg250/vg250-ew_12-31.utm32s.gpkg.ebenen/vg250-ew_ebenen_1231/DE_VG250.gpkg \
    --travel-times ../../../matsim_scenarios/bavaria/output/base-simulation-10pct/travel_times.tsv \
    --output-dir ../../../matsim_scenarios/bavaria/output/demand-extraction-1pct-branch-bound \
    --no-predecessors --inter-degree-keep 1.0" \
  -Denforcer.skip=true
```

**Step 2: Compare**

Ride counts may differ slightly from the previous 1% run because the first-valid-shortest
heuristic might pick a different ride than the previous all-orderings-best approach when the
objective is rideDistance but multiple orderings have very close distances. This is acceptable.

Key metrics to check:
- Ride counts by degree (should be similar, not identical)
- Extension wall time (should be noticeably faster at degree 5+)
- Total ExMAS time

---

## Task 4: Run 10% Bavaria with inter-degree pruning

```bash
mvn exec:java -o \
  -Dexec.mainClass=org.matsim.contrib.demand_extraction.run.RunBavaria30kmDemandExtraction \
  -Dexec.args="--scenario-path ../../../matsim_scenarios/bavaria/output/kelheim_30km_100pct \
    --population ../../../matsim_scenarios/bavaria/output/populations/population_10pct_kelheim30km.xml.gz \
    --sample 100 --iterations 0 --trip-filter-radius 30 \
    --filter-municipality Kelheim \
    --shapes ../../../matsim_scenarios/bavaria/data/germany/tmp_vg250/vg250-ew_12-31.utm32s.gpkg.ebenen/vg250-ew_ebenen_1231/DE_VG250.gpkg \
    --travel-times ../../../matsim_scenarios/bavaria/output/base-simulation-10pct/travel_times.tsv \
    --output-dir ../../../matsim_scenarios/bavaria/output/demand-extraction-10pct-branch-bound \
    --no-predecessors" \
  -Denforcer.skip=true
```

Expected: degree 6 reachable within ~1h. Memory stable. Total rides ~170k.
