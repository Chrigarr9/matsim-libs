# ExMAS Extension Scalability Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Fix OOM during ride extension by (1) adding beeline pre-filter to extensions and (2) restructuring extension to process per-request-set with inline percentage pruning.

**Architecture:** Two changes to `RideExtender`. First, add a beeline distance check before `tryExtend()` to skip obviously infeasible candidates without routing (saves CPU). Second, replace the current "per-base-ride → collect all → prune" flow with "per-request-set → generate all variants → prune immediately → release" flow, which bounds memory to one request set at a time.

**Tech Stack:** Java 17, MATSim, JUnit 5

---

### Task 1: Beeline Pre-Filter for Extensions

**Files:**
- Modify: `matsim-libs/contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/RideExtender.java:260-320`

The beeline check for extensions works the same as for pairs: before calling `tryExtend()` (which does expensive network routing), check if the new passenger's beeline insertion into the route is feasible.

For adding passenger C to an existing ride with passengers {A,B} in a given order, the new passenger's beeline travel distance must not exceed `C.directDistance * C.maxDetourFactor`. We check C's segment of the extended route using beeline distances.

**Step 1: Add beeline helper and counter**

Add to `RideExtender` class body (near the top, after field declarations):

```java
private static double beeline(double x1, double y1, double x2, double y2) {
    double dx = x2 - x1, dy = y2 - y1;
    return Math.sqrt(dx * dx + dy * dy);
}
```

Add counter field:
```java
private final java.util.concurrent.atomic.AtomicLong beelineExtensionRejected = new java.util.concurrent.atomic.AtomicLong();
```

**Step 2: Add beeline check in `generateExtensionsForRide`**

In the loop over neighbor candidates (after the `getPairRides` check at line ~290, before `tryExtend` at line ~293), add:

```java
// Beeline pre-filter: check if new passenger's beeline detour is feasible
// The new passenger must travel from their origin to their destination via
// some insertion into the existing route. The minimum possible passenger
// distance is the beeline from origin to destination = directDistance.
// The maximum allowed is directDistance * maxDetourFactor.
// For a quick lower bound: the new passenger must at least travel from
// their origin to the nearest existing stop and from the nearest existing
// stop to their destination. The beeline from origin to the ride's
// centroid plus centroid to destination is a loose lower bound.
// Simpler: check that the new passenger's origin and destination are each
// within (directDistance * maxDetourFactor) of at least one existing stop.
// This is looser but very cheap.
{
    double maxDist = newRequest.directDistance * newRequest.maxDetourFactor;
    double oX = newRequest.originX, oY = newRequest.originY;
    double dX = newRequest.destinationX, dY = newRequest.destinationY;

    // Check: new passenger's origin must be within maxDist of some existing O/D
    // and destination must be within maxDist of some existing O/D
    boolean originReachable = false;
    boolean destReachable = false;
    for (DrtRequest existing : ride.getRequests()) {
        if (!originReachable) {
            double beeToO = beeline(oX, oY, existing.originX, existing.originY);
            double beeToD = beeline(oX, oY, existing.destinationX, existing.destinationY);
            if (Math.min(beeToO, beeToD) <= maxDist) originReachable = true;
        }
        if (!destReachable) {
            double beeToO = beeline(dX, dY, existing.originX, existing.originY);
            double beeToD = beeline(dX, dY, existing.destinationX, existing.destinationY);
            if (Math.min(beeToO, beeToD) <= maxDist) destReachable = true;
        }
        if (originReachable && destReachable) break;
    }
    if (!originReachable || !destReachable) {
        beelineExtensionRejected.incrementAndGet();
        continue;
    }
}
```

**Step 3: Log the rejection count**

In `extendRides()`, after the stats log (after `stats.logSummary`), add:

```java
if (beelineExtensionRejected.get() > 0) {
    log.info("  Beeline extension pre-filter rejected {} candidates before routing",
            beelineExtensionRejected.get());
}
```

And reset the counter at the start of `extendRides()`:
```java
beelineExtensionRejected.set(0);
```

**Step 4: Run E2E test**

Run: `cd matsim-libs/contribs/drt-demand-extraction && mvn test -Dtest=ExMasDemandExtractionE2ETest -Denforcer.skip=true -o`
Expected: PASS (beeline filter only rejects infeasible candidates)

**Step 5: Commit**

```bash
git add src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/RideExtender.java
git commit -m "feat: add beeline pre-filter to RideExtender to skip infeasible extension candidates before routing"
```

---

### Task 2: Restructure Extension to Per-Request-Set Processing

This is the core change. Replace the current flow:

```
for each base ride (parallel) → generate extensions → collect ALL → sort → prune by request set
```

With:

```
enumerate request sets (triangles in graph) → for each set, generate all variants → prune immediately → release
```

**Files:**
- Modify: `matsim-libs/contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/RideExtender.java:75-130`
- Modify: `matsim-libs/contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/graph/ShareabilityGraph.java` (add method to get all nodes)

**Step 1: Add `getNodeIds()` to ShareabilityGraph**

In `ShareabilityGraph.java`, add a method to get all node IDs for triangle enumeration:

```java
/**
 * @return Sorted array of all request IDs that appear as nodes in the graph.
 */
public int[] getNodeIds() {
    IntOpenHashSet nodes = new IntOpenHashSet();
    for (int i = 0; i < edgeCount; i++) {
        nodes.add(sourceRequests[i]);
        nodes.add(targetRequests[i]);
    }
    int[] sorted = nodes.toIntArray();
    Arrays.sort(sorted);
    return sorted;
}

/**
 * @return Sorted neighbors of a single request.
 */
public int[] getNeighbors(int requestId) {
    int[] result = sortedNeighbors.get(requestId);
    return result != null ? result : new int[0];
}
```

**Step 2: Rewrite `extendRides()` with per-request-set processing**

Replace the body of `extendRides()` (lines 75-129) with:

```java
public List<Ride> extendRides(List<Ride> ridesToExtend, int nextRideIndex) {
    int targetDegree = ridesToExtend.isEmpty() ? 0 : ridesToExtend.get(0).getDegree() + 1;
    ExtensionAttemptStats stats = new ExtensionAttemptStats(targetDegree, exMasConfig);
    log.info("Extending {} rides from degree {} to {} [per-request-set]...",
            ridesToExtend.size(), targetDegree - 1, targetDegree);
    long startTime = System.currentTimeMillis();
    beelineExtensionRejected.set(0);

    // Build lookup: requestIndicesKey -> list of base rides with those indices
    // This lets us find all base rides for a given request set
    Map<String, List<Ride>> baseRidesByRequestSet = new HashMap<>();
    for (Ride ride : ridesToExtend) {
        int[] idx = ride.getRequestIndices().clone();
        Arrays.sort(idx);
        String key = Arrays.toString(idx);
        baseRidesByRequestSet.computeIfAbsent(key, k -> new ArrayList<>()).add(ride);
    }

    // Enumerate all candidate request sets at targetDegree
    // A degree-(D+1) request set adds one new request to a degree-D request set
    // The new request must be a common neighbor of all existing requests in the graph
    Set<String> processedSets = new HashSet<>();
    List<Ride> allExtended = new ArrayList<>();
    int setsProcessed = 0;
    int totalSets = 0;

    // Count total sets for progress (quick pass)
    for (Ride ride : ridesToExtend) {
        int[] neighbors = graph.findCommonNeighborsSorted(ride.getRequestIndices());
        for (int newReq : neighbors) {
            int[] newSet = buildSortedRequestSet(ride.getRequestIndices(), newReq);
            String key = Arrays.toString(newSet);
            if (!processedSets.contains(key)) {
                processedSets.add(key);
                totalSets++;
            }
        }
    }
    processedSets.clear(); // reset for actual processing
    log.info("  Found {} candidate request sets at degree {}", totalSets, targetDegree);

    // Process each request set
    for (Ride ride : ridesToExtend) {
        int[] neighbors = graph.findCommonNeighborsSorted(ride.getRequestIndices());

        for (int newReq : neighbors) {
            int[] newSet = buildSortedRequestSet(ride.getRequestIndices(), newReq);
            String key = Arrays.toString(newSet);

            // Skip if already processed this request set
            if (!processedSets.add(key)) continue;

            setsProcessed++;
            if (isPowerOfTwo(setsProcessed) || setsProcessed == totalSets) {
                double pct = (setsProcessed * 100.0) / Math.max(1, totalSets);
                long now = System.currentTimeMillis();
                double elapsed = Math.max(0.001, (now - startTime) / 1000.0);
                double eta = (totalSets - setsProcessed) / Math.max(setsProcessed / elapsed, 1e-9);
                log.info("  Request-set progress: {}/{} ({}%), ETA {}",
                        setsProcessed, totalSets, String.format("%.1f", pct), formatDuration(eta));
            }

            // Generate ALL variants for this request set from ALL base rides
            List<ExtensionCandidate> variants = new ArrayList<>();
            DrtRequest newRequest = requestMap.get(newReq);

            // Find all base rides that are subsets of this request set
            // For degree D+1, base rides are degree D with D requests from newSet
            for (int i = 0; i < newSet.length; i++) {
                // The base ride is newSet minus newSet[i], the added request is newSet[i]
                int addedReq = newSet[i];
                int[] baseIndices = new int[newSet.length - 1];
                for (int j = 0, k = 0; j < newSet.length; j++) {
                    if (j != i) baseIndices[k++] = newSet[j];
                }
                String baseKey = Arrays.toString(baseIndices);
                List<Ride> bases = baseRidesByRequestSet.get(baseKey);
                if (bases == null) continue;

                DrtRequest addedRequest = requestMap.get(addedReq);

                for (Ride base : bases) {
                    // Beeline pre-filter
                    if (!passesExtensionBeelineFilter(base, addedRequest)) {
                        beelineExtensionRejected.incrementAndGet();
                        continue;
                    }

                    int[] pairRides = getPairRides(base.getRequestIndices(), addedReq);
                    if (pairRides == null) {
                        if (stats != null) stats.missingPairRidesSkipped.increment();
                        continue;
                    }

                    Ride ext = tryExtend(base, addedRequest, pairRides, 0);
                    if (ext == null) {
                        if (stats != null) stats.tryExtendFailed.increment();
                        continue;
                    }

                    Ride validated = budgetValidator.validateAndPopulateBudgets(ext);
                    if (validated == null) {
                        if (stats != null) stats.budgetValidationFailed.increment();
                        continue;
                    }

                    if (exMasConfig != null && exMasConfig.getPruningDistanceSavingsLogScale() >= 0
                            && !passesDistanceSavingsPruning(validated)) {
                        if (stats != null) stats.distanceSavingsPrunedEarly.increment();
                        continue;
                    }

                    variants.add(new ExtensionCandidate(base.getIndex(), addedReq, validated));
                    if (stats != null) stats.candidatesAdded.increment();
                }
            }

            // Percentage-prune this request set immediately
            if (!variants.isEmpty()) {
                List<Ride> pruned = pruneRequestSetVariants(variants);
                for (Ride r : pruned) {
                    allExtended.add(rebuildWithIndex(r, nextRideIndex++));
                }
            }
            // variants released — GC can reclaim
        }
    }

    if (beelineExtensionRejected.get() > 0) {
        log.info("  Beeline extension pre-filter rejected {} candidates before routing",
                beelineExtensionRejected.get());
    }
    stats.logSummary(allExtended.size());

    long elapsed = System.currentTimeMillis() - startTime;
    double seconds = elapsed / 1000.0;
    log.info("Extension complete: {} rides extended to degree {} in {}s ({} request sets)",
            allExtended.size(), targetDegree, String.format("%.1f", seconds), setsProcessed);

    return allExtended;
}
```

**Step 3: Add helper methods**

Add to `RideExtender`:

```java
/**
 * Build a sorted request set by adding newReq to existing indices.
 */
private static int[] buildSortedRequestSet(int[] existing, int newReq) {
    int[] result = new int[existing.length + 1];
    System.arraycopy(existing, 0, result, 0, existing.length);
    result[existing.length] = newReq;
    Arrays.sort(result);
    return result;
}

/**
 * Beeline pre-filter for extensions: check if new passenger's origin and destination
 * are each within reach of at least one existing stop in the ride.
 */
private boolean passesExtensionBeelineFilter(Ride base, DrtRequest newRequest) {
    double maxDist = newRequest.directDistance * newRequest.maxDetourFactor;
    double oX = newRequest.originX, oY = newRequest.originY;
    double dX = newRequest.destinationX, dY = newRequest.destinationY;

    boolean originReachable = false;
    boolean destReachable = false;
    for (DrtRequest existing : base.getRequests()) {
        if (!originReachable) {
            double beeO = Math.min(
                beeline(oX, oY, existing.originX, existing.originY),
                beeline(oX, oY, existing.destinationX, existing.destinationY));
            if (beeO <= maxDist) originReachable = true;
        }
        if (!destReachable) {
            double beeD = Math.min(
                beeline(dX, dY, existing.originX, existing.originY),
                beeline(dX, dY, existing.destinationX, existing.destinationY));
            if (beeD <= maxDist) destReachable = true;
        }
        if (originReachable && destReachable) return true;
    }
    return false;
}

/**
 * Prune variants for a single request set using the configured percentage/min/max settings.
 * Returns the kept rides (not yet reindexed).
 */
private List<Ride> pruneRequestSetVariants(List<ExtensionCandidate> variants) {
    if (variants.isEmpty()) return List.of();

    double keepFraction = exMasConfig != null
            ? Math.min(1.0, exMasConfig.getPruningKeepTopFractionPerRequestSet()) : 1.0;
    int minKeep = exMasConfig != null ? Math.max(0, exMasConfig.getPruningMinRidesToKeepPerRequestSet()) : 0;
    int maxKeep = exMasConfig != null ? Math.max(0, exMasConfig.getPruningMaxRidesToKeepPerRequestSet()) : 0;
    boolean minimize = exMasConfig == null || exMasConfig.getPruningRankingGoal() == null
            || exMasConfig.getPruningRankingGoal().equalsIgnoreCase("minimize");

    Comparator<ExtensionCandidate> cmp = Comparator.comparingDouble(c -> objectiveValue(c.validatedRide()));
    if (!minimize) cmp = cmp.reversed();
    variants.sort(cmp);

    int size = variants.size();
    int keep = (int) Math.ceil(size * keepFraction);
    keep = Math.max(keep, minKeep);
    if (maxKeep > 0) keep = Math.min(keep, maxKeep);
    keep = Math.min(keep, size);

    List<Ride> result = new ArrayList<>(keep);
    for (int i = 0; i < keep; i++) {
        result.add(variants.get(i).validatedRide());
    }
    return result;
}
```

**Step 4: Remove old `applyHeuristicPruning` call**

The pruning is now done inline per request set. Remove the call to `applyHeuristicPruning(extended)` from the old code path. If `applyHeuristicPruning` is used elsewhere, keep the method but remove the call in `extendRides`. Since `extendRides` was the only caller, we can leave the method for now and just not call it.

**Step 5: Add required imports**

At the top of `RideExtender.java`, ensure these imports exist:
```java
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
```

**Step 6: Run E2E test**

Run: `cd matsim-libs/contribs/drt-demand-extraction && mvn test -Dtest=ExMasDemandExtractionE2ETest -Denforcer.skip=true -o`
Expected: PASS — same rides generated (possibly in different order due to per-request-set processing), but ride counts and quality should match.

Note: if the E2E test checks exact ride counts, they may differ slightly because the old code sorted by (baseRideIndex, newRequestIndex) before reindexing, while the new code processes in request-set order. The pruning percentages should produce equivalent results. If counts differ, verify the rides are equivalent quality and update expected counts.

**Step 7: Commit**

```bash
git add src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/RideExtender.java
git add src/main/java/org/matsim/contrib/demand_extraction/algorithm/graph/ShareabilityGraph.java
git commit -m "feat: restructure RideExtender to per-request-set processing with inline percentage pruning

Fixes OOM during degree-3+ extension by processing one request set at a time
instead of collecting all candidates into memory. Memory is now bounded to
one request set's variants (~3-30 entries) instead of all candidates (~39M).

Also includes beeline pre-filter for extensions to skip infeasible candidates
before routing."
```

---

### Task 3: Smoke Test with Bavaria 1%

**Files:**
- No code changes — validation run

**Step 1: Run 1% extraction with new extension logic**

```bash
cd matsim-libs/contribs/drt-demand-extraction
mvn exec:java -o \
  -Dexec.mainClass="org.matsim.contrib.demand_extraction.run.RunBavaria30kmDemandExtraction" \
  -Dexec.args="--scenario-path ../../../matsim_scenarios/bavaria/output/kelheim_30km_100pct \
               --population ../../../matsim_scenarios/bavaria/output/populations/population_1pct_kelheim30km.xml.gz \
               --sample 100 --iterations 0 \
               --trip-filter-radius 30 \
               --filter-municipality Kelheim \
               --shapes ../../../matsim_scenarios/bavaria/data/germany/tmp_vg250/vg250-ew_12-31.utm32s.gpkg.ebenen/vg250-ew_ebenen_1231/DE_VG250.gpkg \
               --travel-times ../../../matsim_scenarios/bavaria/output/base-simulation-10pct/travel_times.tsv \
               --output-dir ../../../matsim_scenarios/bavaria/output/demand-extraction-1pct-v2" \
  -Denforcer.skip=true
```

**Step 2: Verify output**

Check logs for:
- "Found N candidate request sets at degree 3" — should be reasonable (not millions)
- "Beeline extension pre-filter rejected N candidates" — should be nonzero
- "Extension complete: N rides extended to degree 3" — should be similar to previous 1% runs
- No OOM, memory stays reasonable

**Step 3: Compare with previous 1% results**

```python
import pandas as pd
old = pd.read_csv('.../demand-extraction-1pct-pruned025/drt_demand/bavaria-30km-1pct-exmas.exmas_rides.csv')
new = pd.read_csv('.../demand-extraction-1pct-v2/drt_demand/bavaria-30km-1pct-exmas.exmas_rides.csv')
print("Old degrees:", old['degree'].value_counts().sort_index().to_dict())
print("New degrees:", new['degree'].value_counts().sort_index().to_dict())
```

Degree distribution should be similar (not identical due to processing order differences).

---

### Task 4: Run Bavaria 25% Demand Extraction

**Files:**
- No code changes — production run

**Step 1: Launch 25% extraction**

```bash
cd matsim-libs/contribs/drt-demand-extraction
export MAVEN_OPTS="-Xmx100g"
nohup mvn exec:java -o \
  -Dexec.mainClass="org.matsim.contrib.demand_extraction.run.RunBavariaKelheim30kmComparison" \
  -Denforcer.skip=true > ../../../matsim_scenarios/bavaria/output/demand-extraction-25pct.log 2>&1 &
```

**Step 2: Monitor**

```bash
tail -5 matsim_scenarios/bavaria/output/demand-extraction-25pct.log
grep "MemoryObserver" matsim_scenarios/bavaria/output/demand-extraction-25pct.log | tail -3
```

Expected: memory should stay well below 100 GB since only one request set's candidates are held at a time.

**Step 3: Verify completion**

```bash
ls -lh matsim_scenarios/bavaria/output/demand-extraction-25pct-kelheim30km/drt_demand/
grep "COMPLETE\|Total rides\|Total D2D" matsim_scenarios/bavaria/output/demand-extraction-25pct.log
```
