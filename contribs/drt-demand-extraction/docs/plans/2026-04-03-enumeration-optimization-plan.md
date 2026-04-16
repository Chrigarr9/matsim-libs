# Enumeration Optimization Plan — Reducing the 40ms/set Bottleneck

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Reduce the per-set enumeration cost at degree 6+ from ~40ms to ~5-10ms through three complementary strategies: profiling instrumentation, insertion warm-start, and per-passenger constraint propagation.

**Context:** After the scoring context cache (2026-04-03), budget validation dropped from ~1ms to ~0.05ms per call. The remaining bottleneck is ordering enumeration + ride construction + delay optimization. At degree 6 with 10% inter-degree pruning:

- 487,707 base sets → ~49M candidate sets
- 25 base sets/s → ETA 5.4h
- Per candidate set (single-thread): ~40ms
- Of which budget validation is now only ~2.8ms (92 orderings × 6 pax × 0.05ms)
- Remaining ~37ms: enumeration, routing, ride construction, delay optimization, GC

---

## Task 0: Profiling Instrumentation

**Goal:** Know exactly where time is spent before optimizing.

**Approach:** Lightweight ThreadLocal counters in `processSet()` and key methods. NOT a full JVM profiler — just atomic counters that accumulate per-thread and get summed at the end.

### Step 0.1: Add `EnumerationStats` class

Create `algorithm/extension/EnumerationStats.java`:

```java
/**
 * Lightweight per-thread profiling counters for ordering enumeration.
 * Accumulated via ThreadLocal, summed at extendRides() completion.
 */
public final class EnumerationStats {
    private static final ThreadLocal<EnumerationStats> THREAD_LOCAL =
            ThreadLocal.withInitial(EnumerationStats::new);
    
    // Counters
    public long setsProcessed;
    public long orderingsEvaluated;      // Complete orderings reaching evaluator
    public long orderingsPruned;         // Branches pruned by distance bound
    public long ridesBuilt;              // buildRideFromOrdering calls
    public long ridesPassedConstraints;  // Rides surviving maxTravelTime/delay checks  
    public long budgetValidations;       // validateAndPopulateBudgets calls
    public long budgetPassed;            // Budget validations that passed
    public long segmentLookups;          // network.getSegment calls
    
    // Timing (nanos)
    public long timeEnumeration;         // Total time in enumerateAndEvaluate
    public long timeRideConstruction;    // Total time in buildRideFromOrdering
    public long timeBudgetValidation;    // Total time in validateAndPopulateBudgets
    public long timeDelayOptimization;   // Total time in optimizeDelays
    
    public static EnumerationStats get() { return THREAD_LOCAL.get(); }
    
    public static void reset() { THREAD_LOCAL.set(new EnumerationStats()); }
    
    /** Sum all thread-local stats (call after ForkJoinPool completes) */
    public static EnumerationStats sum(Collection<EnumerationStats> perThread) {
        EnumerationStats total = new EnumerationStats();
        for (EnumerationStats s : perThread) {
            total.setsProcessed += s.setsProcessed;
            total.orderingsEvaluated += s.orderingsEvaluated;
            total.orderingsPruned += s.orderingsPruned;
            total.ridesBuilt += s.ridesBuilt;
            total.ridesPassedConstraints += s.ridesPassedConstraints;
            total.budgetValidations += s.budgetValidations;
            total.budgetPassed += s.budgetPassed;
            total.segmentLookups += s.segmentLookups;
            total.timeEnumeration += s.timeEnumeration;
            total.timeRideConstruction += s.timeRideConstruction;
            total.timeBudgetValidation += s.timeBudgetValidation;
            total.timeDelayOptimization += s.timeDelayOptimization;
        }
        total.setsProcessed = perThread.stream().mapToLong(s -> s.setsProcessed).sum();
        return total;
    }
    
    public void log(Logger log, int degree, int threads) {
        double totalMs = (timeEnumeration + timeRideConstruction + timeBudgetValidation) / 1_000_000.0;
        log.info("=== Enumeration Profile (degree {}) ===", degree);
        log.info("  Sets processed: {}", setsProcessed);
        log.info("  Orderings evaluated: {} ({} per set)", orderingsEvaluated,
                setsProcessed > 0 ? String.format("%.1f", (double) orderingsEvaluated / setsProcessed) : "N/A");
        log.info("  Orderings pruned: {}", orderingsPruned);
        log.info("  Rides built: {} ({} per ordering)", ridesBuilt,
                orderingsEvaluated > 0 ? String.format("%.2f", (double) ridesBuilt / orderingsEvaluated) : "N/A");
        log.info("  Rides passed constraints: {} ({}%)", ridesPassedConstraints,
                ridesBuilt > 0 ? String.format("%.1f", 100.0 * ridesPassedConstraints / ridesBuilt) : "N/A");
        log.info("  Budget validations: {}, passed: {} ({}%)", budgetValidations, budgetPassed,
                budgetValidations > 0 ? String.format("%.1f", 100.0 * budgetPassed / budgetValidations) : "N/A");
        log.info("  Segment lookups: {} ({} per set)", segmentLookups,
                setsProcessed > 0 ? String.format("%.1f", (double) segmentLookups / setsProcessed) : "N/A");
        log.info("  Time breakdown (CPU-ms across {} threads):", threads);
        log.info("    Enumeration:      {}ms ({}ms/set)", 
                String.format("%.0f", timeEnumeration / 1_000_000.0),
                setsProcessed > 0 ? String.format("%.3f", timeEnumeration / 1_000_000.0 / setsProcessed) : "N/A");
        log.info("    Ride construction: {}ms ({}ms/set)",
                String.format("%.0f", timeRideConstruction / 1_000_000.0),
                setsProcessed > 0 ? String.format("%.3f", timeRideConstruction / 1_000_000.0 / setsProcessed) : "N/A");
        log.info("    Budget validation: {}ms ({}ms/set)",
                String.format("%.0f", timeBudgetValidation / 1_000_000.0),
                setsProcessed > 0 ? String.format("%.3f", timeBudgetValidation / 1_000_000.0 / setsProcessed) : "N/A");
        log.info("    Delay optimization: {}ms ({}ms/set)",
                String.format("%.0f", timeDelayOptimization / 1_000_000.0),
                setsProcessed > 0 ? String.format("%.3f", timeDelayOptimization / 1_000_000.0 / setsProcessed) : "N/A");
    }
}
```

### Step 0.2: Instrument `processSet()` in RideExtender

Add timing around `enumerateAndEvaluate`, `buildRideFromOrdering`, `validateAndPopulateBudgets`, and count orderings/rides.

### Step 0.3: Instrument segment lookups in OrderingEnumerator

Increment `segmentLookups` counter at each `network.getSegment()` call. Also increment `orderingsPruned` at each `break` in the sorted-candidate loop.

### Step 0.4: Collect and log per-degree

In `extendRides()`, after the parallel processing completes, collect all ThreadLocal stats and call `log()`.

### Step 0.5: Run 10% Bavaria to degree 5 only

Run with `--max-degree 5` to get the profile quickly (degree 5 completes in ~12 min). This gives us the real breakdown to validate our optimization targets.

**Compile + test after each step.**

---

## Task 1: Insertion Warm-Start (Strategy B)

**Goal:** Before full topological sort enumeration, try inserting the new passenger into the parent ride's ordering. If any insertion produces a valid ride, use its distance as the initial B&B bound.

**Why this helps:** Currently `bestValidDist[0]` starts at `maxAllowedRideDistance` (loose). The toposort explores many branches before finding the first valid ordering to tighten the bound. With a tight starting bound, the B&B prunes aggressively from the first recursion step.

### Data already available

The parent `Ride` stores:
- `originsOrderedRequests[]` — pickup sequence (line 27 of Ride.java)
- `destinationsOrderedRequests[]` — dropoff sequence (line 28)
- `connectionTravelTimes[]` — segment travel times (line 42)
- `connectionDistances[]` — segment distances (line 43)
- `startTime` — departure time (line 50)

We have everything needed for incremental insertion cost computation.

### Step 1.1: Pass parent ride info to processSet

Currently `processSet(int[] newSet)` has no reference to the parent ride. We need to pass in either:
- (a) The parent ride itself, or
- (b) The parent ordering + connection arrays

**Approach:** Change the `processSet` call site (inside the neighbor loop, RideExtender line 128) to also pass the parent ride. The parent ride is the `Ride` from `ridesToExtend` that generated this base set.

Currently the base set loop (line 113) iterates over unique base sets, not individual rides. Multiple rides can share the same request set. We need to pick ONE parent ride per base set to get an ordering.

**Design:** During base set dedup (lines 79-89), also store the best parent ride per unique set (lowest rideDistance). Then pass it to processSet.

### Step 1.2: Implement insertion enumeration

New method in `RideExtender`:

```java
/**
 * Try inserting the new passenger into the parent ride's ordering.
 * Returns the best valid ride found, or null.
 * Also sets bestValidDist[0] to the best distance found (for B&B warm-start).
 */
private Ride tryInsertions(DrtRequest[] setRequests, int[] newSet,
                           Ride parentRide, int newPassengerLocalIdx,
                           double[] bestValidDist) {
    // Get parent ordering
    DrtRequest[] parentOrigins = parentRide.getOriginsOrderedRequests();
    DrtRequest[] parentDests = parentRide.getDestinationsOrderedRequests();
    int parentDeg = parentOrigins.length;
    int newDeg = parentDeg + 1; // = setRequests.length
    
    DrtRequest newPax = setRequests[newPassengerLocalIdx];
    
    Ride bestRide = null;
    
    // Try all insertion positions: pickup at position p, dropoff at position d
    // Pickup: 0..parentDeg (insert before each existing pickup, or after all pickups)
    // But pickup must come in the origin sequence, dropoff in the destination sequence
    // Full sequence: [O_0, ..., O_{n-1}, D_0, ..., D_{n-1}]
    // Insert new origin at position p in [0..parentDeg]
    // Insert new destination at position d in [0..parentDeg]
    
    for (int origInsert = 0; origInsert <= parentDeg; origInsert++) {
        for (int destInsert = 0; destInsert <= parentDeg; destInsert++) {
            // Build new ordering arrays
            DrtRequest[] newOrigins = new DrtRequest[newDeg];
            DrtRequest[] newDests = new DrtRequest[newDeg];
            
            // Insert into origin sequence
            int oi = 0;
            for (int i = 0; i < newDeg; i++) {
                if (i == origInsert) { newOrigins[i] = newPax; }
                else { newOrigins[i] = parentOrigins[oi++]; }
            }
            
            // Insert into destination sequence
            int di = 0;
            for (int i = 0; i < newDeg; i++) {
                if (i == destInsert) { newDests[i] = newPax; }
                else { newDests[i] = parentDests[di++]; }
            }
            
            // Quick check: does this ordering respect FIFO/LIFO constraints?
            // (Optional — we can skip this and let buildRideFromOrdering reject invalid ones)
            
            // Build and validate
            Ride ride = buildRideFromOrdering(newOrigins, newDests, 0);
            if (ride == null) continue;
            
            Ride validated = budgetValidator.validateAndPopulateBudgets(ride);
            if (validated == null) continue;
            
            double dist = validated.getRideDistance();
            if (dist < bestValidDist[0]) {
                bestValidDist[0] = dist;
                bestRide = validated;
            }
        }
    }
    
    return bestRide;
}
```

**Insertion count:** `(parentDeg + 1)²` positions. For degree 6: `6² = 36`. For degree 8: `8² = 64`. Very manageable.

**Note on incremental routing:** Each insertion shares most segments with the parent ride. We COULD compute only the changed segments (4 new, 2 removed). But `buildRideFromOrdering` already does a full routing loop using cached segments — each lookup is a ConcurrentHashMap hit (~50ns). For 11 segments: ~550ns. Not worth optimizing further.

### Step 1.3: Wire into processSet

```java
private Ride processSet(int[] newSet, Ride parentRide, int newPassengerLocalIdx) {
    // ... existing setup (lines 175-188) ...
    
    double[] bestValidDist = { maxAllowedRideDistance };
    Ride[] bestRide = { null };
    
    // Strategy B: Try insertions first for a tight initial bound
    if (parentRide != null) {
        Ride insertionResult = tryInsertions(setRequests, newSet, parentRide,
                                             newPassengerLocalIdx, bestValidDist);
        if (insertionResult != null) {
            bestRide[0] = insertionResult;
            // bestValidDist[0] is already tightened
        }
    }
    
    // Full enumeration with (now tight) initial bound
    OrderingEnumerator.enumerateAndEvaluate(
            newSet, graph, network, setRequests, bestValidDist,
            (ordering) -> {
                // ... existing evaluator callback ...
            });
    
    return bestRide[0];
}
```

### Step 1.4: Compile + test

Run E2E tests. 1% Bavaria must still produce exactly 12,552 rides.

---

## Task 2: Per-Passenger Constraint Propagation (Strategy G)

**Goal:** During the ordering enumeration recursion, prune branches where any individual passenger's travel time or temporal constraint is already violated — regardless of how remaining stops are ordered.

**Why this helps:** The current B&B only prunes on aggregate ride distance. A branch might have acceptable total distance but an individual passenger's in-vehicle time is already blown. Strategy G catches these cases at intermediate recursion depths, pruning entire subtrees.

### Design

Modify `OrderingEnumerator`'s destination enumeration (`enumerateDestTopoWithEval`, lines 282-332) to track per-passenger state.

**State tracked at each recursion depth (destination phase):**

For each passenger still in the vehicle (picked up but not yet dropped off):
- `pickupTime[i]` — time when passenger i was picked up (known from origin phase)
- `currentTime` — cumulative time at this depth (already tracked)
- `inVehicleTime[i] = currentTime - pickupTime[i]`

**Pruning checks:**

```java
// For each in-vehicle passenger i:
double inVehicleTime = currentTime - pickupTimes[localIdx];
double maxTravelTime = requests[localIdx].getMaxTravelTime();
double minRemainingTime = beeline(currentCoord, requests[localIdx].destinationCoord) / MAX_SPEED;

if (inVehicleTime + minRemainingTime > maxTravelTime) {
    // Passenger i will exceed max travel time regardless of remaining ordering
    // PRUNE this entire subtree
    return;
}
```

**Additional check during origin phase:**

```java
// When considering picking up passenger j at this depth:
double arrivalTime = currentTime + segmentTravelTime;
double latestDeparture = requests[j].getLatestDeparture();
if (arrivalTime > latestDeparture) {
    // Too late to pick up passenger j — skip this candidate
    continue;
}
```

### Step 2.1: Add max network speed constant

In `OrderingEnumerator` or `ExMasConfigGroup`, add:
```java
// Conservative upper bound on network speed (m/s) for beeline lower bounds
// Use a high value (e.g., 130 km/h = 36.1 m/s for Autobahn) to ensure the bound is valid
private static final double MAX_NETWORK_SPEED = 36.1; // m/s (130 km/h)
```

### Step 2.2: Add pickup time tracking to origin enumeration

In `enumerateOriginsPrunedWithEval()`, accumulate `pickupTimes[]` array:
- At depth 0: `pickupTimes[candidateLocalIdx] = startTime`
- At depth d > 0: `pickupTimes[candidateLocalIdx] = currentTime + segmentTravelTime`
- Pass `pickupTimes` to `enumerateDestPrunedWithEval()`

### Step 2.3: Add per-passenger pruning to destination enumeration

In `enumerateDestTopoWithEval()`, at each depth:
1. After computing segment travel time and updating `currentTime`:
2. For each passenger still in vehicle (picked up, not yet dropped off):
   - Compute `inVehicleTime = currentTime - pickupTimes[passengerLocalIdx]`
   - Compute beeline lower bound to their destination
   - If `inVehicleTime + beelineLowerBound > maxTravelTime`: prune (break from recursion)
3. This check costs O(k) comparisons per depth — negligible

### Step 2.4: Add temporal pruning to origin enumeration

In `enumerateOriginsPrunedWithEval()`, when evaluating candidates at each depth:
- Skip candidate j if `currentTime + segmentTravelTime > requests[j].getLatestDeparture()`
- This is O(1) per candidate — essentially free

### Step 2.5: Compile + test

Run E2E tests. 1% Bavaria must still produce exactly 12,552 rides.

---

## Task 3: Evaluate Routing Passthrough

**Question:** Does passing pre-computed segment data from the enumeration recursion to `buildRideFromOrdering` save meaningful time?

**Analysis:**

The enumeration routes segments during recursion (for distance pruning). `buildRideFromOrdering` re-routes all 2n-1 segments from scratch. Could we pass the already-computed values?

**Cost of current approach:** At degree 6, `buildRideFromOrdering` does 11 `network.getSegment()` calls. Each is a ConcurrentHashMap hit:
- CacheKey creation + hash: ~10ns
- HashMap bucket lookup + equals: ~20-40ns
- Total per lookup: ~30-50ns
- 11 lookups: ~350-550ns per buildRideFromOrdering call
- 92 orderings per set: ~32-50μs per set

**This is 0.05-0.1ms per set out of 40ms total — 0.1-0.25%.**

**Verdict: NOT worth implementing.** The ConcurrentHashMap cache is fast enough that re-looking up segments is negligible. The complexity of threading segment arrays through the recursion and into the evaluator callback outweighs the ~0.05ms savings.

**What IS worth checking:** Whether the routing call during the ENUMERATION recursion is also just cache hits, or whether some segments are being computed for the first time. If there are cache misses at degree 6, those involve actual Dijkstra routing which IS expensive.

The profiling counters from Task 0 will reveal this (count cache hits vs. misses).

---

## Task 4: Allocation Reduction (Bonus)

**Observation from code review:** `buildRideFromOrdering` allocates per call:
- 3 connection arrays (size 2n-1): `connTT`, `connDist`, `connUtil`
- 3 per-passenger arrays (size n): `pttActual`, `pDist`, `pUtil`
- 5 delay/detour arrays (size n): `delays`, `detours`, `effMaxNeg`, `effMaxPos`, `adjDelays`
- 1 sequence array (size 2n): `Id<Link>[]`
- 2 DrtRequest arrays (from evaluator callback): `originsOrdered`, `destsOrdered`
- Ride.Builder + Ride (with defensive .clone() on every array)

At degree 6: **14 arrays + 2 objects per call × 92 orderings = ~1300 allocations per set**. With 16 threads and 25 base sets/s → ~50,000 allocations/second across threads. GC logs confirm memory oscillating 8-27 GB.

**Optimization:** Pre-allocate workspace arrays per thread via ThreadLocal. Reuse for orderings that fail validation (the majority). Only create fresh arrays for the final winning ride.

This is a lower priority than B and G — save for after profiling confirms GC is significant.

---

## Task 5: Validate with profiling + 10% Bavaria

After implementing Tasks 0-2:

1. Run 1% Bavaria with `--inter-degree-keep 1.0` — verify 12,552 rides
2. Run 10% Bavaria with profiling — compare:
   - Orderings evaluated per set (should decrease with B)
   - Branches pruned (should increase with G)
   - Wall time per degree (should decrease significantly at deg 5+)

---

## Expected Impact

**Strategy B (insertion warm-start):**
- At degree 6: ~36 insertions to try + full enumeration with tight bound
- If insertion finds a valid ride with distance D, the toposort prunes any branch with partial distance > D
- Expected: 60-80% of toposort branches pruned at early recursion depths
- Net: orderings reaching evaluator drops from ~92 to ~15-25

**Strategy G (per-passenger propagation):**
- Catches branches where individual passengers are doomed even though aggregate distance is OK
- Complements B — prunes branches that B's distance bound misses
- Expected: additional 10-20% pruning on top of B

**Combined:**
- Orderings evaluated per set: ~92 → ~10-20 (plus ~36 insertion tries)
- Time in ride construction: 92 × X → ~50 × X (insertions + surviving toposort)
- Time in budget validation: 92 × 6 × 0.05ms = 27.6ms → ~50 × 6 × 0.05ms = 15ms
- Net per-set time: ~40ms → ~15-20ms → ~50-100 sets/s → ETA ~1.5-2.5h

---

## Implementation Order

1. **Task 0 (Profiling)** — FIRST. Get real numbers before optimizing.
2. **Task 1 (Insertion warm-start)** — Highest expected impact.
3. **Task 2 (Per-passenger propagation)** — Complements Task 1.
4. **Task 5 (Validate)** — Confirm the combined speedup.
5. **Task 3 (Routing passthrough)** — Skip unless profiling reveals surprise.
6. **Task 4 (Allocation reduction)** — Only if GC is significant.
