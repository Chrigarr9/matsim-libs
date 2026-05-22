# Ordering-Based Extension Algorithm — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace the decomposition-based extension algorithm (base ride + insertion position) with direct ordering enumeration from pairwise FIFO/LIFO constraints, eliminating dependency on base ride orderings and discovering all valid rides.

**Architecture:** For each candidate request set, extract pairwise constraints from the shareability graph (which pair ride directions and kinds exist). Enumerate valid origin orderings (topological sorts of origin constraint DAG), then for each, enumerate valid destination orderings (topological sorts of destination constraint DAG). All network segments in valid orderings are cache hits (proven below), so routing is a HashMap lookup. Keep best ride per set. The shareability graph neighbor lookup is bidirectional so no valid cliques are missed.

**Tech Stack:** Java 17, MATSim, fastutil, JUnit 5

**Branch:** `feature/ordering-based-extension` (from current master)

---

## Context: Why This Redesign

The current extension algorithm (`RideExtender.extendRides`) works by:
1. Taking degree-D rides as input
2. For each ride, finding common neighbors in the shareability graph
3. For each candidate degree-(D+1) set: trying all decompositions (which element is "added") × all base ride variants × all FIFO/LIFO pair-ride combinations
4. Each combination calls `tryExtend`, which determines ONE destination insertion position from the base ride's existing ordering + FIFO/LIFO constraints

Problems:
- **Ordering-dependent on base rides:** With top-1-per-set, only 1 base ordering exists per set at degree 3+. Different base orderings produce different extensions. Missing orderings cascade to higher degrees.
- **Original ExMAS `product(*E)[0]` bug:** Only tries first FIFO/LIFO combination. We fixed this by trying all combos, but this creates a cartesian product explosion (2^D combinations per base ride).
- **Wasted work:** Many tryExtend calls return null (contradictory FIFO/LIFO constraints). The current approach generates invalid orderings and rejects them, rather than enumerating valid orderings directly.
- **rideMap dependency:** Extension needs all pair ride objects to look up FIFO/LIFO kinds. This wastes memory — the shareability graph already stores kinds.

The new approach:
- **Enumerates valid orderings directly** from pairwise constraints (topological sorts)
- **No base rides needed** for ordering — constraints come from the shareability graph
- **No wasted tryExtend calls** — only valid orderings are routed
- **Cumulative routing time** — each segment routed at its actual departure time, not ride start
- **Bidirectional graph** finds all candidate cliques regardless of pair ride direction

---

## Key Files

| File | Role |
|------|------|
| `algorithm/graph/ShareabilityGraph.java` | Pair ride graph — neighbor lookup, edge queries |
| `algorithm/extension/RideExtender.java` | Extension algorithm — **main refactor target** |
| `algorithm/extension/OrderingEnumerator.java` | **NEW** — constraint extraction + topological sort enumeration |
| `algorithm/engine/ExMasEngine.java` | Orchestrator — calls RideExtender, inter-degree pruning |
| `algorithm/domain/Ride.java` | Ride data object |
| `algorithm/validation/BudgetValidator.java` | Budget constraint validation |
| `algorithm/network/MatsimNetworkCache.java` | Network routing cache (timeBin = 3600s) |
| `config/ExMasConfigGroup.java` | Configuration |

All paths relative to: `matsim-libs/contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/`

---

### Task 0: Create Branch and Commit Bidirectional Graph Fix

The bidirectional graph change is already on the working tree. Create the branch and commit it.

**Step 1: Create branch**

```bash
cd matsim-libs/contribs/drt-demand-extraction
git checkout -b feature/ordering-based-extension
```

**Step 2: Commit bidirectional graph + current extendRides changes**

```bash
git add src/main/java/org/matsim/contrib/demand_extraction/algorithm/graph/ShareabilityGraph.java
git add src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/RideExtender.java
git commit -m "feat: bidirectional shareability graph + set-driven extension with all FIFO/LIFO combos

ShareabilityGraph: sortedNeighbors now includes both outgoing targets AND incoming
sources. This ensures findCommonNeighborsSorted finds candidate cliques regardless
of pair ride direction (fixes directed 3-cycle blind spot).

RideExtender: set-driven enumeration with LongOpenHashSet dedup, all FIFO/LIFO
pair-ride combinations explored (improvement over original ExMAS product(*E)[0]),
top-1-per-set output. Foundation for ordering-based extension."
```

---

### Task 1: Fix Dedup for Failed Sets

**Problem:** The current dedup uses `resultBySetHash.containsKey()` which only catches successful sets. Failed sets (no valid ride) are reprocessed on every subsequent encounter — causing 2x wasted work with bidirectional neighbors.

**Files:**
- Modify: `algorithm/extension/RideExtender.java` — `extendRides()` method

**Step 1: Add processedSets LongOpenHashSet alongside resultBySetHash**

In `extendRides()`, after `Long2ObjectOpenHashMap<Ride> resultBySetHash`:

```java
// Track ALL processed sets (successful AND failed) to avoid reprocessing.
// resultBySetHash only contains successful sets, so failed sets would be
// reprocessed on every subsequent encounter without this.
LongOpenHashSet processedSetHashes = new LongOpenHashSet();
```

Add import: `import it.unimi.dsi.fastutil.longs.LongOpenHashSet;`

**Step 2: Replace dedup check**

Replace:
```java
if (resultBySetHash.containsKey(newSetHash)) {
    setsSkippedDedup++;
    continue;
}
```

With:
```java
if (!processedSetHashes.add(newSetHash)) {
    setsSkippedDedup++;
    continue;
}
```

**Step 3: Compile + E2E test**

```bash
mvn compile -Denforcer.skip=true -o -q
mvn test -Dtest=ExMasDemandExtractionE2ETest -Denforcer.skip=true -o
```

**Step 4: Run 1% smoke test, verify candidate counts are now correct**

Expected: `setsProcessed` should equal unique candidate sets (no reprocessing).

**Step 5: Commit**

```bash
git add src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/RideExtender.java
git commit -m "fix: dedup all processed sets, not just successful ones"
```

---

### Task 2: Implement OrderingEnumerator (merged constraint extraction + enumeration)

Single class that extracts pairwise FIFO/LIFO constraints from the shareability graph and enumerates all valid (origin, destination) orderings via topological sort. Merges the previously separate PairConstraints helper into this class — they are tightly coupled and only consumed together.

**Files:**
- Create: `algorithm/extension/OrderingEnumerator.java`

**Step 1: Implement the enumerator**

Algorithm:
1. Extract pairwise constraints from shareability graph (both directions, all C(n,2) pairs)
2. Build origin constraint DAG from pair directions → enumerate topological sorts
3. For each origin ordering: pair direction is fixed → FIFO/LIFO kinds give dest constraints → build dest DAG → enumerate topological sorts
4. Return list of (originPerm[], destPerm[]) as index permutations (caller resolves to DrtRequest objects)

```java
package org.matsim.contrib.demand_extraction.algorithm.extension;

import java.util.ArrayList;
import java.util.List;

import org.matsim.contrib.demand_extraction.algorithm.graph.ShareabilityGraph;
import it.unimi.dsi.fastutil.ints.IntList;

/**
 * Enumerates all valid (origin ordering, destination ordering) combinations
 * for a request set, using pairwise FIFO/LIFO constraints from pair rides.
 *
 * <p>Origin ordering is constrained by pair ride DIRECTIONS: if only pair(A,B)
 * exists (A first), then O_A must come before O_B. If both directions exist,
 * either origin order is valid for that pair.
 *
 * <p>Destination ordering is constrained by pair ride KINDS within the chosen
 * direction: FIFO → D_A before D_B; LIFO → D_B before D_A; both → no constraint.
 *
 * <p>Enumeration uses topological sort of constraint DAGs. Typical counts:
 * degree 3: 2-6 orderings, degree 4: 3-18, degree 5: 4-40.
 */
public final class OrderingEnumerator {

    /** Pairwise constraint: which FIFO/LIFO pair ride kinds exist in each direction */
    record PairInfo(
            boolean forwardFifo, boolean forwardLifo,
            boolean reverseFifo, boolean reverseLifo
    ) {
        boolean forwardExists() { return forwardFifo || forwardLifo; }
        boolean reverseExists() { return reverseFifo || reverseLifo; }
        boolean forwardOnly() { return forwardExists() && !reverseExists(); }
        boolean reverseOnly() { return !forwardExists() && reverseExists(); }
    }

    /** A valid (origin, destination) ordering as index permutations into the requests array */
    public record Ordering(int[] originPerm, int[] destPerm) {}

    /**
     * Enumerate all valid orderings for the given request set.
     *
     * @param requestIndices sorted request indices for the set
     * @param graph the shareability graph
     * @return list of valid orderings, or empty if set is infeasible (disconnected pair)
     */
    public static List<Ordering> enumerate(int[] requestIndices, ShareabilityGraph graph) {
        int n = requestIndices.length;

        // Step 1: Extract pairwise constraints from graph
        PairInfo[] pairs = extractConstraints(requestIndices, n, graph);
        if (pairs == null) return List.of();

        List<Ordering> result = new ArrayList<>();

        // Step 2: Enumerate valid origin orderings (topo sorts of origin DAG)
        List<int[]> originPerms = enumerateOriginOrderings(n, pairs);

        // Step 3: For each origin ordering, enumerate valid destination orderings
        for (int[] origPerm : originPerms) {
            List<int[]> destPerms = enumerateDestOrderings(n, origPerm, pairs);
            for (int[] destPerm : destPerms) {
                result.add(new Ordering(origPerm, destPerm));
            }
        }
        return result;
    }

    // --- Constraint extraction ---

    /**
     * Extract constraints for all C(n,2) pairs. Returns null if any pair is disconnected.
     */
    private static PairInfo[] extractConstraints(int[] requestIndices, int n, ShareabilityGraph graph) {
        PairInfo[] pairs = new PairInfo[n * (n - 1) / 2];
        int idx = 0;

        for (int a = 0; a < n; a++) {
            for (int b = a + 1; b < n; b++) {
                int reqA = requestIndices[a];
                int reqB = requestIndices[b];

                // Forward: A picked up first
                IntList[] fwd = graph.getEdgesWithKinds(reqA, reqB);
                boolean fwdFifo = false, fwdLifo = false;
                if (fwd[0].size() > 0) {
                    for (int k = 0; k < fwd[1].size(); k++) {
                        if (fwd[1].getInt(k) == ShareabilityGraph.KIND_FIFO) fwdFifo = true;
                        else fwdLifo = true;
                    }
                }

                // Reverse: B picked up first
                IntList[] rev = graph.getEdgesWithKinds(reqB, reqA);
                boolean revFifo = false, revLifo = false;
                if (rev[0].size() > 0) {
                    for (int k = 0; k < rev[1].size(); k++) {
                        if (rev[1].getInt(k) == ShareabilityGraph.KIND_FIFO) revFifo = true;
                        else revLifo = true;
                    }
                }

                if (!(fwdFifo || fwdLifo || revFifo || revLifo)) {
                    return null; // Disconnected pair → infeasible set
                }

                pairs[idx++] = new PairInfo(fwdFifo, fwdLifo, revFifo, revLifo);
            }
        }
        return pairs;
    }

    /** Look up PairInfo for positions a and b in the set (a < b). */
    private static PairInfo lookup(PairInfo[] pairs, int n, int a, int b) {
        return pairs[a * (2 * n - a - 1) / 2 + (b - a - 1)];
    }

    // --- Origin ordering enumeration ---

    /**
     * Enumerate valid origin orderings. Constraints per pair (a,b):
     * - forwardOnly → a before b; reverseOnly → b before a; both → no constraint
     */
    private static List<int[]> enumerateOriginOrderings(int n, PairInfo[] pairs) {
        Boolean[][] adj = new Boolean[n][n];
        for (int a = 0; a < n; a++) {
            for (int b = a + 1; b < n; b++) {
                PairInfo p = lookup(pairs, n, a, b);
                if (p.forwardOnly()) {
                    adj[a][b] = true;
                    adj[b][a] = false;
                } else if (p.reverseOnly()) {
                    adj[b][a] = true;
                    adj[a][b] = false;
                }
            }
        }

        List<int[]> result = new ArrayList<>();
        enumerateTopoSorts(adj, n, new boolean[n], new int[n], 0, result);
        return result;
    }

    // --- Destination ordering enumeration ---

    /**
     * Enumerate valid destination orderings given a fixed origin ordering.
     * Origin ordering fixes pair direction → FIFO/LIFO kinds → dest constraints.
     */
    private static List<int[]> enumerateDestOrderings(int n, int[] origPerm, PairInfo[] pairs) {
        int[] origPos = new int[n];
        for (int i = 0; i < n; i++) origPos[origPerm[i]] = i;

        Boolean[][] adj = new Boolean[n][n];
        for (int a = 0; a < n; a++) {
            for (int b = a + 1; b < n; b++) {
                PairInfo p = lookup(pairs, n, a, b);

                boolean aBeforeB = origPos[a] < origPos[b];
                boolean hasFifo, hasLifo;

                if (aBeforeB) {
                    hasFifo = p.forwardFifo();
                    hasLifo = p.forwardLifo();
                } else {
                    hasFifo = p.reverseFifo();
                    hasLifo = p.reverseLifo();
                }

                if (hasFifo && hasLifo) {
                    // Both → no constraint on dest ordering for this pair
                } else if (hasFifo) {
                    // FIFO: same order as origin (first-picked-up dropped off first)
                    if (aBeforeB) {
                        adj[a][b] = true;  // FIFO(A,B): D_A before D_B
                        adj[b][a] = false;
                    } else {
                        adj[b][a] = true;  // FIFO(B,A): D_B before D_A
                        adj[a][b] = false;
                    }
                } else if (hasLifo) {
                    // LIFO: reverse order from origin (last-picked-up dropped off first)
                    if (aBeforeB) {
                        adj[b][a] = true;  // LIFO(A,B): D_B before D_A
                        adj[a][b] = false;
                    } else {
                        adj[a][b] = true;  // LIFO(B,A): D_A before D_B
                        adj[b][a] = false;
                    }
                } else {
                    // Neither FIFO nor LIFO for chosen direction → origin ordering invalid
                    return List.of();
                }
            }
        }

        List<int[]> result = new ArrayList<>();
        enumerateTopoSorts(adj, n, new boolean[n], new int[n], 0, result);
        return result;
    }

    // --- Topological sort enumeration ---

    /**
     * Enumerate all topological sorts of a DAG defined by adjacency constraint matrix.
     * adj[i][j] = true means i must come before j. null = no constraint.
     */
    private static void enumerateTopoSorts(Boolean[][] adj, int n, boolean[] used, int[] perm, int depth,
                                           List<int[]> result) {
        if (depth == n) {
            result.add(perm.clone());
            return;
        }

        for (int candidate = 0; candidate < n; candidate++) {
            if (used[candidate]) continue;

            // Check: all predecessors (adj[pred][candidate] = true) must already be placed
            boolean valid = true;
            for (int other = 0; other < n; other++) {
                if (other == candidate || used[other]) continue;
                if (adj[other][candidate] != null && adj[other][candidate]) {
                    valid = false;
                    break;
                }
            }
            if (!valid) continue;

            used[candidate] = true;
            perm[depth] = candidate;
            enumerateTopoSorts(adj, n, used, perm, depth + 1, result);
            used[candidate] = false;
        }
    }
}
```

**Step 2: Compile**

```bash
mvn compile -Denforcer.skip=true -o -q
```

**Step 3: Commit**

```bash
git add src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingEnumerator.java
git commit -m "feat: add OrderingEnumerator — constraint extraction + topological sort enumeration"
```

---

### Task 3: Implement `buildRideFromOrdering`

New method that builds a Ride from explicit (origin, destination) orderings — replacing `tryExtend`. Same routing and validation logic, but takes orderings directly instead of computing insertion positions.

**Key improvements over `tryExtend`:**
- `requests[]` IS `originsOrdered` (origin/pickup order) — eliminates delay remapping
- **Cumulative routing time** — each segment routed at its actual departure time, not ride start
- No beeline pre-filter needed — all segments are cache hits (see Design Decisions)

**Files:**
- Modify: `algorithm/extension/RideExtender.java`

**Step 1: Add the new method**

```java
/**
 * Build a Ride from explicit origin and destination orderings.
 * Routes the full sequence on the network with cumulative departure times,
 * validates per-passenger constraints.
 *
 * <p>requests[] = originsOrdered (pickup order). All per-passenger metric arrays
 * (delays, travelTimes, distances, detours) are indexed by pickup position.
 * Downstream code (BudgetValidator, ExMasCsvWriter) only requires internal
 * consistency, not a specific canonical order.
 *
 * @param originsOrdered requests in pickup order (also used as requests[])
 * @param destsOrdered requests in dropoff order
 * @param index ride index
 * @return validated Ride, or null if routing fails or constraints violated
 */
private Ride buildRideFromOrdering(DrtRequest[] originsOrdered,
                                    DrtRequest[] destsOrdered, int index) {
    int degree = originsOrdered.length;
    // requests[] IS origin ordering — no separate array needed
    DrtRequest[] requests = originsOrdered;

    // Build connection sequence: [O_1, O_2, ..., O_n, D_1, D_2, ..., D_n]
    @SuppressWarnings("unchecked")
    Id<Link>[] sequence = (Id<Link>[]) new Id[degree * 2];
    for (int i = 0; i < degree; i++) {
        sequence[i] = originsOrdered[i].originLinkId;
    }
    for (int i = 0; i < degree; i++) {
        sequence[degree + i] = destsOrdered[i].destinationLinkId;
    }

    // Route all segments with cumulative departure time
    double startTime = originsOrdered[0].getRequestTime();
    double[] connTT = new double[degree * 2 - 1];
    double[] connDist = new double[degree * 2 - 1];
    double[] connUtil = new double[degree * 2 - 1];

    double currentTime = startTime;
    for (int i = 0; i < degree * 2 - 1; i++) {
        TravelSegment seg = network.getSegment(sequence[i], sequence[i + 1], currentTime);
        if (!seg.isReachable()) return null;
        connTT[i] = seg.getTravelTime();
        connDist[i] = seg.getDistance();
        connUtil[i] = seg.getNetworkUtility();
        currentTime += connTT[i];
    }

    // Calculate per-passenger metrics (indexed by pickup position = requests[] position)
    double[] pttActual = new double[degree];
    double[] pDist = new double[degree];
    double[] pUtil = new double[degree];

    for (int i = 0; i < degree; i++) {
        DrtRequest req = requests[i];  // = originsOrdered[i]
        int origIdx = i;  // trivially — requests IS originsOrdered

        // Find destination position
        int destPosInDestArray = -1;
        for (int k = 0; k < degree; k++) {
            if (destsOrdered[k].index == req.index) { destPosInDestArray = k; break; }
        }
        int destIdx = degree + destPosInDestArray;

        for (int j = origIdx; j < destIdx; j++) {
            pttActual[i] += connTT[j];
            pDist[i] += connDist[j];
            pUtil[i] += connUtil[j];
        }

        if (pttActual[i] < req.getTravelTime() - EPSILON) {
            pttActual[i] = req.getTravelTime();
        }
        if (pttActual[i] > req.getMaxTravelTime()) return null;
    }

    // Calculate delays — indexed by pickup position (= requests[] position)
    // No remapping needed since requests[] IS originsOrdered
    double[] delays = new double[degree];
    double arrivalAtOrigin = startTime;
    for (int i = 0; i < degree; i++) {
        delays[i] = arrivalAtOrigin - requests[i].getRequestTime();
        if (i < degree - 1) {
            arrivalAtOrigin += connTT[i];
        }
    }

    // Calculate effective delays and detours
    double[] effMaxNeg = new double[degree];
    double[] effMaxPos = new double[degree];
    double[] detours = new double[degree];

    for (int i = 0; i < degree; i++) {
        DrtRequest req = requests[i];
        double detourFactor = pttActual[i] / req.getTravelTime();
        detours[i] = detourFactor;
        double detourTime = req.getTravelTime() * (detourFactor - 1.0);

        double posAdj = req.getPositiveDelayRelComponent() > 0.0
                ? Math.max(0.0, req.getPositiveDelayRelComponent() - detourTime) : 0.0;
        double negAdj = req.getNegativeDelayRelComponent() > 0.0
                ? Math.max(0.0, req.getNegativeDelayRelComponent() - detourTime) : 0.0;

        effMaxPos[i] = (req.getMaxPositiveDelay() - detourTime) - posAdj;
        effMaxNeg[i] = req.getMaxNegativeDelay() - negAdj;
    }

    double[] adjDelays = optimizeDelays(delays, effMaxNeg, effMaxPos);
    if (adjDelays == null) return null;

    RideKind kind = RideKind.MIXED;

    return Ride.builder()
            .index(index)
            .degree(degree)
            .kind(kind)
            .requests(requests)
            .originsOrderedRequests(originsOrdered)
            .destinationsOrderedRequests(destsOrdered)
            .passengerTravelTimes(pttActual)
            .passengerDistances(pDist)
            .passengerNetworkUtilities(pUtil)
            .delays(adjDelays)
            .detours(detours)
            .connectionTravelTimes(connTT)
            .connectionDistances(connDist)
            .connectionNetworkUtilities(connUtil)
            .startTime(startTime)
            .build();
}
```

**Step 2: Compile + test**

```bash
mvn compile -Denforcer.skip=true -o -q
mvn test -Dtest=ExMasDemandExtractionE2ETest -Denforcer.skip=true -o
```

**Step 3: Commit**

```bash
git add src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/RideExtender.java
git commit -m "feat: add buildRideFromOrdering with cumulative routing time and origin-ordered requests"
```

---

### Task 4: Rewrite `extendRides` with Ordering-Based Algorithm

Replace the decomposition × base rides × FIFO/LIFO combos loop with:
1. Enumerate valid orderings from pairwise constraints (OrderingEnumerator)
2. Resolve int[] permutations to DrtRequest[] arrays
3. Route → validate → keep best per set

**Files:**
- Modify: `algorithm/extension/RideExtender.java` — rewrite `extendRides()`
- Modify: `algorithm/engine/ExMasEngine.java` — update constructor call

**Step 1: Rewrite the main extension method**

The new `extendRides`:
- Input: degree-D rides (1 per set — only used for set enumeration via `findCommonNeighborsSorted`)
- For each candidate set: OrderingEnumerator.enumerate() → resolve perms → route → validate → keep top-1
- No `rideMap`, no `baseRidesBySet`, no decomposition loop

```java
public List<Ride> extendRides(List<Ride> ridesToExtend, int nextRideIndex) {
    // ... (setup, collect unique base sets for neighbor enumeration) ...

    for (int[] baseSetIndices : uniqueBaseSets) {
        int[] neighbors = graph.findCommonNeighborsSorted(baseSetIndices);

        for (int newReq : neighbors) {
            int[] newSet = buildSortedRequestSet(baseSetIndices, newReq);
            long newSetHash = hashRequestSet(newSet);
            if (!processedSetHashes.add(newSetHash)) continue;

            // Resolve request objects (sorted index order, used for perm resolution)
            DrtRequest[] setRequests = new DrtRequest[newSet.length];
            for (int i = 0; i < newSet.length; i++) {
                setRequests[i] = requestMap.get(newSet[i]);
            }

            // Enumerate all valid (origin, destination) orderings
            List<OrderingEnumerator.Ordering> orderings =
                    OrderingEnumerator.enumerate(newSet, graph);

            // Try each ordering: resolve perms → route → validate → track best
            Ride bestRide = null;
            double bestObjective = Double.MAX_VALUE;

            for (OrderingEnumerator.Ordering ord : orderings) {
                // Resolve int[] permutations to DrtRequest[] arrays
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

            if (bestRide != null) {
                resultBySetHash.put(newSetHash, rebuildWithIndex(bestRide, nextRideIndex++));
            }
        }
    }

    return new ArrayList<>(resultBySetHash.values());
}
```

**Step 2: Simplify RideExtender constructor**

Remove `rideMap` — no longer needed:

```java
public RideExtender(MatsimNetworkCache network, ShareabilityGraph graph,
                    BudgetValidator budgetValidator, List<DrtRequest> requests,
                    ExMasConfigGroup exMasConfig) {
    this.network = network;
    this.graph = graph;
    this.budgetValidator = budgetValidator;
    this.requestMap = new HashMap<>();
    for (DrtRequest r : requests) requestMap.put(r.index, r);
    this.exMasConfig = exMasConfig;
}
```

**Step 3: Update ExMasEngine to use new constructor**

In `ExMasEngine.java`, the RideExtender creation (line ~158):

Replace:
```java
RideExtender extender = new RideExtender(network, graph, budgetValidator,
                                         requests, pairAndSingleRides, exMasConfig);
```

With:
```java
RideExtender extender = new RideExtender(network, graph, budgetValidator,
                                         requests, exMasConfig);
```

Also remove `pairAndSingleRides` variable (line 144) and the inter-degree MaxPerSet pruning (lines 167-188) since extendRides now returns top-1 per set.

**Step 4: Compile + E2E test**

```bash
mvn compile -Denforcer.skip=true -o -q
mvn test -Dtest=ExMasDemandExtractionE2ETest -Denforcer.skip=true -o
```

**Step 5: Commit**

```bash
git add -A
git commit -m "feat: ordering-based extension algorithm

Replace decomposition × base rides × FIFO/LIFO combos with direct ordering
enumeration from pairwise constraints. For each candidate set:
1. Extract FIFO/LIFO constraints from shareability graph (both directions)
2. Enumerate valid origin orderings (topological sorts of direction DAG)
3. For each origin ordering: enumerate valid dest orderings (FIFO/LIFO DAG)
4. Route each valid ordering with cumulative departure time → validate → keep best

Eliminates: rideMap, baseRidesBySet, decomposition loop, tryExtend.
Discovers ALL valid orderings regardless of base ride diversity.
Fixes routing: cumulative departure time instead of constant startTime."
```

---

### Task 5: Run 1% Validation and Compare Results

**Step 1: Run 1% extraction with new algorithm**

```bash
cd matsim-libs/contribs/drt-demand-extraction
mvn exec:java -o \
  -Dexec.mainClass="org.matsim.contrib.demand_extraction.run.RunBavaria30kmDemandExtraction" \
  -Dexec.args="--scenario-path ../../../matsim_scenarios/bavaria/output/kelheim_30km_100pct \
               --population ../../../matsim_scenarios/bavaria/output/populations/population_1pct_kelheim30km.xml.gz \
               --sample 100 --iterations 0 \
               --trip-filter-radius 30 --filter-municipality Kelheim \
               --shapes ../../../matsim_scenarios/bavaria/data/germany/tmp_vg250/vg250-ew_12-31.utm32s.gpkg.ebenen/vg250-ew_ebenen_1231/DE_VG250.gpkg \
               --travel-times ../../../matsim_scenarios/bavaria/output/base-simulation-10pct/travel_times.tsv \
               --output-dir ../../../matsim_scenarios/bavaria/output/demand-extraction-1pct-ordering-based \
               --no-pruning" \
  -Denforcer.skip=true
```

**Step 2: Compare key metrics**

Compare against previous runs:
- Total rides per degree (should be >= previous since we now discover all orderings)
- Ride quality (distance, distance savings) — should be same or better (more orderings → better best)
- Computation time — should be comparable (fewer wasted tryExtend calls, but topological sort overhead)
- Memory — should be lower (no rideMap, no baseRidesBySet)

**Step 3: Run full test suite**

```bash
mvn test -Denforcer.skip=true -o
```

**Step 4: Commit results log**

```bash
git add docs/plans/
git commit -m "docs: add ordering-based extension plan and validation results"
```

---

### Task 6: Run 10% Scale Test

Verify scalability. The ordering-based approach should handle 10% without memory issues.

```bash
export MAVEN_OPTS="-Xmx100g"
mvn exec:java -o \
  -Dexec.mainClass="org.matsim.contrib.demand_extraction.run.RunBavaria30kmDemandExtraction" \
  -Dexec.args="--scenario-path ../../../matsim_scenarios/bavaria/output/kelheim_30km_100pct \
               --population ../../../matsim_scenarios/bavaria/output/populations/population_10pct_kelheim30km.xml.gz \
               --sample 100 --iterations 0 \
               --trip-filter-radius 30 --filter-municipality Kelheim \
               --shapes ../../../matsim_scenarios/bavaria/data/germany/tmp_vg250/vg250-ew_12-31.utm32s.gpkg.ebenen/vg250-ew_ebenen_1231/DE_VG250.gpkg \
               --travel-times ../../../matsim_scenarios/bavaria/output/base-simulation-10pct/travel_times.tsv \
               --output-dir ../../../matsim_scenarios/bavaria/output/demand-extraction-10pct-ordering-based \
               --no-pruning" \
  -Denforcer.skip=true
```

Monitor: memory usage, candidate counts per degree, computation time.

---

## Design Decisions (from review)

### Routing cache: all segments are cache hits (proof)

`MatsimNetworkCache` keys on `(fromLink, toLink, timeBin)` where `timeBin = (int)(departureTime / 3600)` (1-hour bins). Shareable requests are within minutes of each other (bounded by time filter), so all queries for a given ride land in the same time bin.

For a valid ordering [O₁, O₂, ..., Oₙ, D₁, D₂, ..., Dₙ], each consecutive segment is proven to be a cache hit:

- **O→O segments** (Oᵢ → Oᵢ₊₁): Always cached from pair(i, i+1) — the first segment of that pair ride. ✓
- **O→D transition** (Oₙ → D₁, last origin → first destination): The topological sort guarantees D₁ is dropped off before Dₙ. With Oₙ picked up last, pair(D₁'s request, Oₙ's request) FIFO must exist (otherwise the topo sort wouldn't have placed D₁ first). That pair ride routes O_last → D_first as its second segment. ✓
- **D→D segments** (Dᵢ → Dᵢ₊₁): If the first-picked-up of the pair is dropped off first (same order) → FIFO pair ride has this segment. If reversed → LIFO pair ride has it. The topo sort only produces orderings backed by existing pair rides. ✓

**No beeline pre-filter needed.** Since all segments are cache hits (HashMap lookup, not Dijkstra), beeline coordinate math would cost as much as the cached routing call itself. No speedup possible.

### Cumulative routing time (fix)

The current code passes the same `startTime` (first request's departure time) to ALL `getSegment()` calls. This is an approximation — later segments depart later. The new code uses cumulative departure time:

```java
double currentTime = startTime;
for (int i = 0; i < seqLen - 1; i++) {
    TravelSegment seg = network.getSegment(sequence[i], sequence[i + 1], currentTime);
    currentTime += connTT[i];
}
```

With 1-hour time bins, this rarely changes the result (shareable rides are <1 hour), but it's strictly more correct. Applied to `buildRideFromOrdering` only — existing `tryExtend` and `PairGenerator` retain the old behavior for backward compatibility during validation.

### `requests[]` array ordering
**Use origin ordering (pickup order).** `requests[] = originsOrdered` — the Ride's `requests` array is the same object as `originsOrderedRequests`. All per-passenger metric arrays (delays, travelTimes, distances, detours) are indexed by pickup position. This eliminates the delay-remapping step (delays are naturally in pickup order). Downstream code (BudgetValidator, ExMasCsvWriter) only requires internal consistency — it doesn't assume a specific canonical order.

### No max orderings cap
Every ordering is backed by existing pair rides. Real-world demand topology naturally bounds the count: at 1% Bavaria, 99%+ of sets have ≤4 unconstrained pairs (max ~256 orderings). All orderings are cache hits (microseconds each). No configurable parameter needed.

### Bidirectional graph
**Already implemented.** `sortedNeighbors` includes both outgoing targets and incoming sources. Empirically finds 0 additional unique candidate sets at 1% (the directional 3-cycle blind spot doesn't occur in practice), but is theoretically correct and necessary for the ordering-based approach to fully exploit reverse-direction pair rides.

---

## Summary of Changes

| Component | Before | After |
|-----------|--------|-------|
| ShareabilityGraph neighbors | Directional (outgoing only) | **Bidirectional** |
| Extension enumeration | Per-ride, redundant for same set | **Per unique set** |
| Ordering discovery | Base ride ordering + insertion position | **Topological sort of constraint DAG** |
| FIFO/LIFO handling | Cartesian product of combos (many null) | **Constraint edges in DAG** |
| Origin ordering | Inherited from construction chain | **Enumerated from pair directions** |
| `requests[]` ordering | Append order (inconsistent) | **Origin/pickup order** (= `originsOrderedRequests`) |
| Routing time | Constant `startTime` for all segments | **Cumulative departure time** per segment |
| Dedup | Result map only (failed sets reprocessed) | **LongOpenHashSet for ALL processed** |
| rideMap | All pair rides as Ride objects | **Eliminated** (kinds from graph) |
| baseRidesBySet | String → List\<Ride\> map | **Eliminated** (orderings from constraints) |
| Pre-filter | Beeline per-request | **Not needed** (all segments are cache hits) |
| Output | Multiple rides per set (pruned) | **Top-1 per set** |
