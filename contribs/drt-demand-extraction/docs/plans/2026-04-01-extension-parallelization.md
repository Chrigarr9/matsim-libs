# Parallel Extension Algorithm — Implementation Plan

**Goal:** Parallelize the per-set ordering enumeration + routing + validation in `RideExtender.extendRides()` to utilize all CPU cores. The work per candidate set is independent and embarrassingly parallel.

**Architecture:** Parallel iteration over base sets with concurrent dedup. Each thread independently discovers candidate sets from its base sets, checks a shared `ConcurrentHashMap` for dedup, and processes new sets in-place. No intermediate collection of candidate sets — avoids the memory issue that motivated the entire redesign.

**Branch:** Same as ordering-based extension (`feature/ordering-based-extension`)

---

## Why Parallel Over Base Sets (not two-phase collect-then-process)

The original two-phase plan (collect all candidate sets → process in parallel) stores all `int[]` arrays in memory. At 25% degree 4 with 159M candidate sets, that's ~5.7 GB on top of the 2.5 GB dedup hash — defeating the memory savings we worked hard to achieve.

Instead: parallelize the **outer loop over base sets**. Each base set's neighbors are discovered and processed independently. The only shared mutable state is the dedup/results map, which uses `ConcurrentHashMap.putIfAbsent()` — lock-free, no contention.

| Approach | Memory overhead | Dedup | Complexity |
|----------|----------------|-------|------------|
| Two-phase collect | O(candidateSets) int[] arrays | Sequential LongOpenHashSet | Simple but memory-heavy |
| **Parallel over base sets** | **None** (process in-place) | ConcurrentHashMap.putIfAbsent | Slightly more code, zero extra memory |

## Thread Safety Analysis

| Component | Thread-safe? | Notes |
|-----------|-------------|-------|
| `OrderingEnumerator.enumerate()` | **Yes** — pure function, no shared state | Creates local DAGs, returns new lists |
| `network.getSegment()` | **Yes** — `ConcurrentHashMap.computeIfAbsent()` | Already designed for parallel pair generation |
| `budgetValidator.validateAndPopulateBudgets()` | **Yes** — reads ride data, returns new Ride | No mutable instance fields |
| `buildRideFromOrdering()` | **Yes** — creates new arrays and Ride objects | Only reads `network` (thread-safe) |
| `passesDistanceSavingsPruning()` | **Yes** — reads config, computes on ride | Pure function of ride + config |
| `objectiveValue()` | **Yes** — reads config, computes on ride | Pure function |
| `graph.findCommonNeighborsSorted()` | **Yes** — reads pre-built arrays | Immutable after construction |
| `graph.getEdgesWithKinds()` | **Yes** — reads pre-built arrays | Immutable after construction |
| `requestMap.get()` | **Yes** — HashMap, read-only after construction | Never modified during extension |

---

## Key Files

| File | Role |
|------|------|
| `algorithm/extension/RideExtender.java` | Rewrite `extendRides()` with parallel base-set iteration |

---

### Task 1: Rewrite `extendRides` with Parallel Base-Set Processing

**Files:**
- Modify: `algorithm/extension/RideExtender.java`

**Step 1: Extract `processSet` method**

Move the per-set logic (ordering enumeration → routing → validation → best ride selection) into a standalone method. This is what each thread calls:

```java
/**
 * Process a single candidate set: enumerate orderings, route, validate, return best ride.
 * Thread-safe — only reads shared immutable/thread-safe resources.
 */
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

    // Enumerate valid orderings
    List<OrderingEnumerator.Ordering> orderings = OrderingEnumerator.enumerate(newSet, graph);

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

        if (exMasConfig != null && exMasConfig.getPruningDistanceSavingsLogScale() >= 0
                && !passesDistanceSavingsPruning(validated)) continue;

        double obj = objectiveValue(validated);
        if (obj < bestObjective) {
            bestObjective = obj;
            bestRide = validated;
        }
    }
    return bestRide;
}
```

**Step 2: Rewrite `extendRides` with parallel stream over base sets**

The shared state is a single `ConcurrentHashMap<Long, Ride>` that serves as both dedup and result storage. Each base set's work is submitted to a `ForkJoinPool`:

```java
public List<Ride> extendRides(List<Ride> ridesToExtend, int nextRideIndex) {
    int targetDegree = ridesToExtend.isEmpty() ? 0 : ridesToExtend.get(0).getDegree() + 1;
    log.info("Extending {} sets from degree {} to {} ...",
            ridesToExtend.size(), targetDegree - 1, targetDegree);
    long startTime = System.currentTimeMillis();

    // Collect unique base sets
    List<int[]> uniqueBaseSets = new ArrayList<>();
    {
        var seen = new java.util.HashSet<String>();
        for (Ride ride : ridesToExtend) {
            int[] idx = ride.getRequestIndices().clone();
            Arrays.sort(idx);
            if (seen.add(Arrays.toString(idx))) {
                uniqueBaseSets.add(idx);
            }
        }
    }
    log.info("  {} base rides in {} unique request sets", ridesToExtend.size(), uniqueBaseSets.size());

    // Shared concurrent result map: setHash → bestRide
    // putIfAbsent serves as atomic dedup — first thread to claim a set processes it
    ConcurrentHashMap<Long, Ride> resultMap = new ConcurrentHashMap<>();
    // Sentinel for "claimed but processing" — prevents other threads from re-processing
    Ride PROCESSING_SENTINEL = Ride.builder()
            .index(-1).degree(1).kind(RideKind.SINGLE)
            .requests(new DrtRequest[0])  // dummy, never returned
            ... // minimal valid build
            .build();
    // Actually, simpler: use a ConcurrentHashMap<Long, Boolean> for dedup,
    // separate ConcurrentHashMap<Long, Ride> for results.

    // Dedup: thread-safe set of claimed hashes
    ConcurrentHashMap.KeySetView<Long, Boolean> claimedSets = ConcurrentHashMap.newKeySet();
    // Results: thread-safe map of successful rides
    ConcurrentHashMap<Long, Ride> resultBySetHash = new ConcurrentHashMap<>();

    // Progress counters
    AtomicInteger setsProcessed = new AtomicInteger();
    AtomicInteger setsSkippedDedup = new AtomicInteger();

    // Determine parallelism
    int parallelism = exMasConfig.getAlgorithmProcessCount();
    if (parallelism <= 0) parallelism = Runtime.getRuntime().availableProcessors();
    log.info("  Processing with {} threads", parallelism);

    // Parallel processing over base sets
    ForkJoinPool pool = new ForkJoinPool(parallelism);
    try {
        pool.submit(() ->
            uniqueBaseSets.parallelStream().forEach(baseSetIndices -> {
                int[] neighbors = graph.findCommonNeighborsSorted(baseSetIndices);

                for (int newReq : neighbors) {
                    int[] newSet = buildSortedRequestSet(baseSetIndices, newReq);
                    long newSetHash = hashRequestSet(newSet);

                    // Atomic dedup: only first thread to add this hash processes it
                    if (!claimedSets.add(newSetHash)) {
                        setsSkippedDedup.incrementAndGet();
                        continue;
                    }

                    int count = setsProcessed.incrementAndGet();
                    if (Integer.bitCount(count) == 1 && count >= 1024) {
                        double elapsed = (System.currentTimeMillis() - startTime) / 1000.0;
                        log.info("  Progress: {} sets processed, {} results, {} skipped (dedup), {}/s",
                                count, resultBySetHash.size(), setsSkippedDedup.get(),
                                String.format("%.0f", count / Math.max(0.001, elapsed)));
                    }

                    Ride bestRide = processSet(newSet);
                    if (bestRide != null) {
                        resultBySetHash.put(newSetHash, bestRide);
                    }
                }
            })
        ).get();
    } catch (InterruptedException | ExecutionException e) {
        throw new RuntimeException("Parallel extension failed", e);
    } finally {
        pool.shutdown();
    }

    // Assign sequential indices (sequential, fast)
    List<Ride> results = new ArrayList<>(resultBySetHash.values());
    for (int i = 0; i < results.size(); i++) {
        results.set(i, rebuildWithIndex(results.get(i), nextRideIndex + i));
    }

    long elapsed = System.currentTimeMillis() - startTime;
    log.info("Extension complete: {} rides at degree {} in {}s ({} candidate sets, {} threads, {} skipped dedup, {} base sets)",
            results.size(), targetDegree, String.format("%.1f", elapsed / 1000.0),
            setsProcessed.get(), parallelism, setsSkippedDedup.get(), uniqueBaseSets.size());

    return results;
}
```

**Key design points:**
- `ConcurrentHashMap.newKeySet().add(hash)` is atomic — returns false if already present, no race conditions
- No sentinel objects needed — `claimedSets` handles dedup, `resultBySetHash` stores only successful rides
- Each thread processes its base sets' neighbors inline — zero intermediate storage
- Progress logging uses `AtomicInteger` — negligible contention
- `ForkJoinPool` sized to `algorithmProcessCount` (not the default common pool)

**Step 3: Add imports**

```java
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
```

**Step 4: Compile + E2E test**

```bash
mvn compile -Denforcer.skip=true -o -q
mvn test -Dtest=ExMasDemandExtractionE2ETest -Denforcer.skip=true -o
```

**Step 5: Commit**

```bash
git add src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/RideExtender.java
git commit -m "feat: parallelize extension with concurrent base-set processing

Parallel iteration over base sets using ForkJoinPool. Each thread
independently discovers candidate sets, claims them via atomic
ConcurrentHashMap.add() for dedup, then processes (enumerate orderings,
route, validate) inline. Zero intermediate storage — no candidate set
list collected in memory.

Thread count controlled by algorithmProcessCount config.
All per-set work is stateless or reads thread-safe shared resources."
```

---

### Task 2: Validate Correctness and Measure Speedup

**Step 1: Run 1% and compare ride counts**

Results may differ in ride INDEX ordering (non-deterministic processing order) but ride COUNTS per degree and ride QUALITY (best objective per set) must be identical — same candidate sets, same orderings, same routing cache.

**Step 2: Run 10% and measure wall time**

Compare against the sequential baseline from today's 10% run.

Expected speedup: ~Nx for N cores on the extension phase (Phase 4). Phases 1-3 and post-processing are unchanged. The bottleneck at degree 4+ (20M+ candidate sets at 2-12k sets/s) should see the most benefit.

---

## Memory Analysis

| Data structure | Size | Notes |
|---|---|---|
| `claimedSets` (ConcurrentHashMap.KeySetView) | ~32 bytes/entry | Long boxed + ConcurrentHashMap node overhead |
| `resultBySetHash` (ConcurrentHashMap) | ~48 bytes/entry + Ride objects | Only successful sets |
| **No candidate set list** | **0** | Processed inline, never stored |

At 159M candidate sets (25% degree 4): `claimedSets` ≈ 159M × 32 = ~5 GB. This is more than the LongOpenHashSet (~2.5 GB) but avoids the separate candidate list. Acceptable trade-off.

**Optimization if needed:** Replace `ConcurrentHashMap.newKeySet()` with a `ConcurrentLongOpenHashSet` from a library, or use a `long[]` + `AtomicLongArray` for a lock-free long set. But ConcurrentHashMap should be fine for our scale.

Alternative: keep the sequential `LongOpenHashSet` for Phase A dedup (enumerate-only pass, no processing), then use a parallel stream over the collected hashes to process. But this brings back the memory issue of storing all sets.

**Best pragmatic approach:** Use `ConcurrentHashMap<Long, Boolean>` where the key is the set hash. The boxing overhead (Long vs long) adds ~16 bytes/entry vs a primitive hash set. At 159M entries: 5 GB vs 2.5 GB. Both fit in 50 GB. If memory becomes an issue, we can switch to a concurrent primitive long set.
