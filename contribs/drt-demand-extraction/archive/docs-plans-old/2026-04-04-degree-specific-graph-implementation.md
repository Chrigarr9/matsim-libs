# Degree-Specific Graph Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace pair-graph-only candidate generation with degree-specific graphs that provide 82-88% candidate reduction and 78-87% ordering reduction at degree 4-5.

**Architecture:** New `DegreeGraph` class built after each degree from constraint-feasible sets and their valid orderings. Used for candidate generation (extension index) and ordering constraint propagation (tighter DAGs). Distance B&B removed in favor of collecting all valid orderings per set.

**Tech Stack:** Java 17, MATSim 2026.0-SNAPSHOT, Maven, fastutil (Int2ObjectOpenHashMap, Long2ObjectOpenHashMap)

**Branch:** `feature/exmas-degree-graph` in matsim-libs (already created, branched from `feature/exmas-traceable`)

**Design doc:** `docs/plans/2026-04-04-degree-specific-graph-design.md` in Dissertation repo

---

## Phase A: DegreeGraph + Candidate Reduction

### Task 1: Create DegreeGraph class with extension index

**Files:**
- Create: `algorithm/graph/DegreeGraph.java`
- Test: Run `mvn test -Dtest=ExMasDemandExtractionE2ETest -Denforcer.skip=true` for compilation check

All paths relative to `matsim-libs/contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/`

**Step 1: Create DegreeGraph.java with data structure and build method**

```java
package org.matsim.contrib.demand_extraction.algorithm.graph;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * Degree-specific graph for higher-degree candidate generation.
 *
 * <p>Built from constraint-feasible sets at degree k, used to generate
 * degree-(k+1) candidates. Two components:
 * <ul>
 *   <li><b>Extension index:</b> (k-1)-subset hash → sorted extension elements.
 *       Used by {@link #findExtensions} for candidate generation.</li>
 *   <li><b>Valid orderings:</b> set hash → list of valid (origin, dest) permutations.
 *       Used for ordering constraint propagation to tighten enumeration DAGs.</li>
 * </ul>
 *
 * <p>Replaces pair-graph-based candidate generation at degree 4+.
 * The pair graph is still needed for FIFO/LIFO ordering constraints.
 */
public final class DegreeGraph {

    /** A valid (origin, destination) ordering for a feasible set. */
    public record OrderingPair(byte[] originPerm, byte[] destPerm) {}

    /** Result from processing a candidate set: the set, best ride info, and all valid orderings. */
    public record FeasibleSetResult(int[] sortedRequestSet, long setHash, List<OrderingPair> validOrderings) {}

    private final int degree;
    private final Long2ObjectOpenHashMap<int[]> extensionIndex;
    private final Long2ObjectOpenHashMap<List<OrderingPair>> orderingsBySetHash;

    private DegreeGraph(int degree,
                        Long2ObjectOpenHashMap<int[]> extensionIndex,
                        Long2ObjectOpenHashMap<List<OrderingPair>> orderingsBySetHash) {
        this.degree = degree;
        this.extensionIndex = extensionIndex;
        this.orderingsBySetHash = orderingsBySetHash;
    }

    public int getDegree() { return degree; }

    /**
     * Find all requests that extend baseSet into a feasible (degree+1)-set.
     *
     * <p>For each (k-1)-subset of baseSet, looks up extension elements in the index.
     * Returns the intersection of all k lists, minus base set elements.
     * This guarantees ALL k+1 sub-sets of the result are feasible.
     *
     * @param baseSet sorted request indices of size {@code degree}
     * @return sorted extension request indices (may be empty)
     */
    public int[] findExtensions(int[] baseSet) {
        int k = baseSet.length;
        if (k != degree) {
            throw new IllegalArgumentException("Base set size " + k + " != graph degree " + degree);
        }

        // Look up k extension lists (one per (k-1)-subset)
        int[][] lists = new int[k][];
        for (int skip = 0; skip < k; skip++) {
            long subHash = hashSubsetSkipping(baseSet, skip);
            int[] extensions = extensionIndex.get(subHash);
            if (extensions == null) return EMPTY;
            lists[skip] = extensions;
        }

        // k-way sorted intersection
        int[] result = lists[0];
        for (int i = 1; i < k; i++) {
            result = intersectSorted(result, lists[i]);
            if (result.length == 0) return EMPTY;
        }

        // Remove base set elements
        return removeSorted(result, baseSet);
    }

    /**
     * Get valid orderings for a set (for ordering constraint propagation).
     * @param setHash hash of the sorted request set
     * @return list of valid orderings, or null if not in graph
     */
    public List<OrderingPair> getOrderings(long setHash) {
        return orderingsBySetHash.get(setHash);
    }

    /**
     * Check if request a is always before request b in origin orderings
     * across all sub-sets of fullSet that contain both a and b.
     *
     * @param fullSet sorted request indices of the candidate set (size degree+1)
     * @param idxA index position of request a in fullSet
     * @param idxB index position of request b in fullSet
     * @return Boolean.TRUE if always a before b, Boolean.FALSE if always b before a, null if mixed/unknown
     */
    public Boolean getOriginConsensus(int[] fullSet, int idxA, int idxB) {
        Boolean consensus = null;
        int n = fullSet.length;

        for (int skip = 0; skip < n; skip++) {
            if (skip == idxA || skip == idxB) continue;

            // Build (n-1)-subset excluding fullSet[skip]
            long subHash = hashSubsetSkipping(fullSet, skip);
            List<OrderingPair> orderings = orderingsBySetHash.get(subHash);
            if (orderings == null) continue;

            // Map idxA/idxB to positions in the subset
            // After removing element at 'skip', indices shift:
            int subIdxA = idxA < skip ? idxA : idxA - 1;
            int subIdxB = idxB < skip ? idxB : idxB - 1;

            for (OrderingPair op : orderings) {
                // Find positions in origin permutation
                int posA = -1, posB = -1;
                for (int p = 0; p < op.originPerm().length; p++) {
                    if (op.originPerm()[p] == subIdxA) posA = p;
                    if (op.originPerm()[p] == subIdxB) posB = p;
                }
                if (posA < 0 || posB < 0) continue;

                boolean aFirst = posA < posB;
                if (consensus == null) consensus = aFirst;
                else if (consensus != aFirst) return null; // Disagreement
            }
        }
        return consensus;
    }

    /** Same as getOriginConsensus but for destination orderings. */
    public Boolean getDestConsensus(int[] fullSet, int idxA, int idxB) {
        Boolean consensus = null;
        int n = fullSet.length;

        for (int skip = 0; skip < n; skip++) {
            if (skip == idxA || skip == idxB) continue;

            long subHash = hashSubsetSkipping(fullSet, skip);
            List<OrderingPair> orderings = orderingsBySetHash.get(subHash);
            if (orderings == null) continue;

            int subIdxA = idxA < skip ? idxA : idxA - 1;
            int subIdxB = idxB < skip ? idxB : idxB - 1;

            for (OrderingPair op : orderings) {
                int posA = -1, posB = -1;
                for (int p = 0; p < op.destPerm().length; p++) {
                    if (op.destPerm()[p] == subIdxA) posA = p;
                    if (op.destPerm()[p] == subIdxB) posB = p;
                }
                if (posA < 0 || posB < 0) continue;

                boolean aFirst = posA < posB;
                if (consensus == null) consensus = aFirst;
                else if (consensus != aFirst) return null;
            }
        }
        return consensus;
    }

    /**
     * Build a DegreeGraph from feasible set results.
     *
     * @param feasibleSets results from processSet — each contains sorted request set + valid orderings
     * @param degree the degree of sets in this graph
     * @return built graph
     */
    public static DegreeGraph build(Collection<FeasibleSetResult> feasibleSets, int degree) {
        Long2ObjectOpenHashMap<int[]> extIndex = new Long2ObjectOpenHashMap<>();
        Long2ObjectOpenHashMap<List<OrderingPair>> orderings = new Long2ObjectOpenHashMap<>();

        // Temporary: collect extension lists as growable
        Long2ObjectOpenHashMap<it.unimi.dsi.fastutil.ints.IntArrayList> tempIndex = new Long2ObjectOpenHashMap<>();

        for (FeasibleSetResult fsr : feasibleSets) {
            int[] set = fsr.sortedRequestSet();
            int k = set.length;

            // Store orderings
            if (fsr.validOrderings() != null && !fsr.validOrderings().isEmpty()) {
                orderings.put(fsr.setHash(), fsr.validOrderings());
            }

            // Build extension index: for each (k-1)-subset, the excluded element extends it
            for (int skip = 0; skip < k; skip++) {
                long subHash = hashSubsetSkipping(set, skip);
                int extraElement = set[skip];
                tempIndex.computeIfAbsent(subHash,
                    h -> new it.unimi.dsi.fastutil.ints.IntArrayList()).add(extraElement);
            }
        }

        // Convert IntArrayLists to sorted int arrays
        for (var entry : tempIndex.long2ObjectEntrySet()) {
            int[] arr = entry.getValue().toIntArray();
            Arrays.sort(arr);
            extIndex.put(entry.getLongKey(), arr);
        }

        return new DegreeGraph(degree, extIndex, orderings);
    }

    // --- Utility methods ---

    private static final int[] EMPTY = new int[0];

    static long hashSubsetSkipping(int[] sorted, int skipIndex) {
        long h = 0;
        for (int i = 0; i < sorted.length; i++) {
            if (i == skipIndex) continue;
            h = h * 1000003L + sorted[i];
        }
        return h;
    }

    /** Must match RideExtender.hashRequestSet */
    public static long hashRequestSet(int[] sortedIndices) {
        long h = 0;
        for (int idx : sortedIndices) {
            h = h * 1000003L + idx;
        }
        return h;
    }

    private static int[] intersectSorted(int[] a, int[] b) {
        int[] buf = new int[Math.min(a.length, b.length)];
        int ai = 0, bi = 0, ri = 0;
        while (ai < a.length && bi < b.length) {
            if (a[ai] < b[bi]) ai++;
            else if (a[ai] > b[bi]) bi++;
            else { buf[ri++] = a[ai]; ai++; bi++; }
        }
        return ri == buf.length ? buf : Arrays.copyOf(buf, ri);
    }

    private static int[] removeSorted(int[] source, int[] toRemove) {
        int[] buf = new int[source.length];
        int si = 0, ri = 0, wi = 0;
        while (si < source.length) {
            if (ri < toRemove.length && source[si] == toRemove[ri]) {
                si++; ri++;
            } else if (ri < toRemove.length && source[si] > toRemove[ri]) {
                ri++;
            } else {
                buf[wi++] = source[si++];
            }
        }
        return wi == buf.length ? buf : Arrays.copyOf(buf, wi);
    }
}
```

**Step 2: Verify compilation**

Run: `cd matsim-libs/contribs/drt-demand-extraction && mvn compile -q -Denforcer.skip=true`
Expected: Clean compile

**Step 3: Commit**

```bash
git add src/main/java/org/matsim/contrib/demand_extraction/algorithm/graph/DegreeGraph.java
git commit -m "feat: add DegreeGraph data structure for degree-specific candidate generation"
```

---

### Task 2: Integrate DegreeGraph into RideExtender for candidate generation

**Files:**
- Modify: `algorithm/extension/RideExtender.java`

**Step 1: Replace prevConstraintFeasibleHashes with DegreeGraph**

Change the field and constructor to accept a `DegreeGraph` instead of `Set<Long>`:

```java
// Replace these fields:
//   private final Set<Long> prevConstraintFeasibleHashes;
//   private ConcurrentHashMap.KeySetView<Long, Boolean> constraintFeasibleHashes;
// With:
private final DegreeGraph prevDegreeGraph;
private final ConcurrentHashMap<Long, DegreeGraph.FeasibleSetResult> feasibleSetResults;
```

Update both constructors:
```java
public RideExtender(MatsimNetworkCache network, ShareabilityGraph graph, BudgetValidator budgetValidator,
                    List<DrtRequest> requests, ExMasConfigGroup exMasConfig) {
    this(network, graph, budgetValidator, requests, exMasConfig, null);
}

public RideExtender(MatsimNetworkCache network, ShareabilityGraph graph, BudgetValidator budgetValidator,
                    List<DrtRequest> requests, ExMasConfigGroup exMasConfig,
                    DegreeGraph prevDegreeGraph) {
    this.network = network;
    this.graph = graph;
    this.budgetValidator = budgetValidator;
    this.requestMap = new HashMap<>();
    for (DrtRequest r : requests) requestMap.put(r.index, r);
    this.exMasConfig = exMasConfig;
    this.prevDegreeGraph = prevDegreeGraph;
    this.feasibleSetResults = new ConcurrentHashMap<>();
}

/** Build DegreeGraph from collected feasible sets after extendRides completes. */
public DegreeGraph buildDegreeGraph(int degree) {
    return DegreeGraph.build(feasibleSetResults.values(), degree);
}
```

**Step 2: Change candidate generation in extendRides to use DegreeGraph when available**

In the parallel processing loop (inside `uniqueBaseSets.parallelStream().forEach`), replace:
```java
int[] neighbors = graph.findCommonNeighborsSorted(baseSetIndices);
```
with:
```java
int[] neighbors;
if (prevDegreeGraph != null) {
    neighbors = prevDegreeGraph.findExtensions(baseSetIndices);
} else {
    neighbors = graph.findCommonNeighborsSorted(baseSetIndices);
}
```

**Step 3: In processSet, collect feasible set results**

After the enumeration, replace the constraint-feasible hash tracking with FeasibleSetResult collection:
```java
// Replace:
//   if (feasibilityFlags[0]) {
//       stats.setsConstraintFeasible++;
//       constraintFeasibleHashes.add(hashRequestSet(newSet));
//   }
// With:
if (feasibilityFlags[0]) {
    stats.setsConstraintFeasible++;
    // Collect all valid orderings for graph building
    // For now, just record the set as feasible (orderings added in Task 4)
    long setHash = hashRequestSet(newSet);
    feasibleSetResults.put(setHash, new DegreeGraph.FeasibleSetResult(
        newSet.clone(), setHash, java.util.Collections.emptyList()));
}
```

**Step 4: Remove old getConstraintFeasibleHashes getter, add import for DegreeGraph**

```java
import org.matsim.contrib.demand_extraction.algorithm.graph.DegreeGraph;
```

Remove the `getConstraintFeasibleHashes()` method and `constraintFeasibleHashes` field.

**Step 5: Update ExMasEngine to use DegreeGraph**

In `ExMasEngine.java`, replace the hash-passing logic:
```java
// Replace:
//   java.util.Set<Long> prevConstraintFeasibleHashes = null;
//   ...
//   RideExtender extender = new RideExtender(network, graph, budgetValidator,
//                                            requests, exMasConfig, prevConstraintFeasibleHashes);
//   ...
//   prevConstraintFeasibleHashes = extender.getConstraintFeasibleHashes();
//   log.info("  Constraint-feasible sets at degree {}: {} (graph node count)",
//           degree + 1, prevConstraintFeasibleHashes.size());
// With:
DegreeGraph prevDegreeGraph = null;
...
RideExtender extender = new RideExtender(network, graph, budgetValidator,
                                         requests, exMasConfig, prevDegreeGraph);
List<Ride> extended = extender.extendRides(currentDegreeRides, nextRideIndex);

// Build degree graph for next iteration
long graphBuildStart = System.currentTimeMillis();
prevDegreeGraph = extender.buildDegreeGraph(degree + 1);
long graphBuildMs = System.currentTimeMillis() - graphBuildStart;
log.info("  Degree-{} graph: {} feasible sets, built in {}ms",
        degree + 1, prevDegreeGraph != null ? extender.feasibleSetResults.size() : 0, graphBuildMs);
```

Note: make `feasibleSetResults` package-private or add a size getter.

**Step 6: Verify compilation and run E2E test**

Run: `mvn compile -q -Denforcer.skip=true`
Run: `mvn test -Dtest=ExMasDemandExtractionE2ETest -Denforcer.skip=true`
Expected: compile + tests pass

**Step 7: Commit**

```bash
git add -A
git commit -m "feat: integrate DegreeGraph for candidate generation at degree 4+

Uses DegreeGraph.findExtensions() instead of pairGraph.findCommonNeighborsSorted()
for degrees 4+. Candidates are only generated when all sub-sets are feasible.
Ordering constraint propagation and distance B&B removal in next commits."
```

---

### Task 3: Verify candidate reduction with 1% Bavaria

**Step 1: Run 1% Bavaria correctness check**

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
               --output-dir ../../../matsim_scenarios/bavaria/output/demand-extraction-1pct-degree-graph \
               --no-predecessors" \
  -Denforcer.skip=true
```

Expected: **12,552 rides** (exact match with all previous correct runs)

Check log for:
- "Degree-3 graph: N feasible sets, built in Xms" 
- Degree 4+ using graph-based candidate generation
- Candidate count reduction vs baseline

**Step 2: If correctness fails, debug by comparing ride-by-ride against baseline output**

---

## Phase B: Ordering Constraint Propagation + Remove Distance B&B

### Task 4: Collect all valid orderings in processSet

**Files:**
- Modify: `algorithm/extension/RideExtender.java`
- Modify: `algorithm/extension/OrderingEnumerator.java`

**Step 1: Add a new enumeration method that collects all valid orderings without distance B&B**

In `OrderingEnumerator.java`, add a new method that wraps the existing enumeration but:
1. Does NOT use distance-based pruning (bestValidDist = Double.MAX_VALUE)
2. Collects ALL orderings that pass constraint + budget checks
3. Still uses travel time pruning (Check A and Check B)

```java
/**
 * Enumerate all constraint-feasible orderings without distance B&B.
 * Used to populate the degree-specific graph with all valid orderings.
 *
 * @param requestIndices sorted request indices
 * @param graph pair graph for FIFO/LIFO constraints
 * @param pairConstraints pairwise constraints (may be tightened by sub-set orderings)
 * @param network routing cache
 * @param requests DrtRequest array
 * @param evaluator called for each complete ordering
 */
public static void enumerateAllFeasible(
        int[] requestIndices, ShareabilityGraph graph,
        PairInfo[] pairConstraints,
        MatsimNetworkCache network, DrtRequest[] requests,
        double[] bestValidDist,
        Consumer<Ordering> evaluator) {
    if (pairConstraints == null) return;
    int n = requestIndices.length;

    // Use tightened constraints for enumeration
    // (same topological sort logic, but with potentially fewer valid orderings)
    enumerateOriginsPrunedWithEval(
        pairConstraints, n, new int[n], new boolean[n], 0,
        requestIndices, network, requests, bestValidDist,
        new double[n], 0.0, evaluator);
}
```

Also, make `extractConstraints` public so RideExtender can call it:
```java
public static PairInfo[] extractConstraints(int[] requestIndices, ShareabilityGraph graph) {
    // ... existing implementation, change visibility from private to public
}
```

And make the `PairInfo` record and `lookup` method public (if not already).

**Step 2: Modify processSet to collect all valid orderings**

In `RideExtender.processSet`, collect orderings during evaluation:

```java
// Add at start of processSet:
List<DegreeGraph.OrderingPair> allValidOrderings = new ArrayList<>();

// In the evaluator lambda, after budgetPassed:
// Record this valid ordering for the degree graph
int[] origPerm = ordering.originPerm().clone();
int[] destPerm = ordering.destPerm().clone();
byte[] origBytes = new byte[origPerm.length];
byte[] destBytes = new byte[destPerm.length];
for (int i = 0; i < origPerm.length; i++) {
    origBytes[i] = (byte) origPerm[i];
    destBytes[i] = (byte) destPerm[i];
}
allValidOrderings.add(new DegreeGraph.OrderingPair(origBytes, destBytes));

// After enumeration, update the FeasibleSetResult:
if (feasibilityFlags[1]) {  // budget-feasible
    stats.setsConstraintFeasible++;
    long setHash = hashRequestSet(newSet);
    feasibleSetResults.put(setHash, new DegreeGraph.FeasibleSetResult(
        newSet.clone(), setHash, allValidOrderings));
}
```

**Step 3: Compile and test**

Run: `mvn compile -q -Denforcer.skip=true && mvn test -Dtest=ExMasDemandExtractionE2ETest -Denforcer.skip=true`

**Step 4: Commit**

```bash
git commit -am "feat: collect all valid orderings per feasible set for degree graph"
```

---

### Task 5: Add ordering constraint propagation to enumeration

**Files:**
- Modify: `algorithm/extension/RideExtender.java`
- Modify: `algorithm/extension/OrderingEnumerator.java`

**Step 1: Add constraint tightening in processSet**

Before calling `OrderingEnumerator.enumerateAndEvaluate`, extract and tighten pairwise constraints:

```java
// In processSet, after computing maxAllowedRideDistance:

// Extract base pairwise constraints from pair graph
OrderingEnumerator.PairInfo[] pairConstraints =
    OrderingEnumerator.extractConstraints(newSet, graph);
if (pairConstraints == null) {
    stats.timeTotal += System.nanoTime() - t0;
    return null;  // infeasible: missing pair edge
}

// Tighten with sub-set ordering constraints from degree graph
if (prevDegreeGraph != null) {
    pairConstraints = tightenConstraints(pairConstraints, newSet, prevDegreeGraph);
}

// Use tightened constraints for enumeration
OrderingEnumerator.enumerateAllFeasible(
    newSet, graph, pairConstraints, network, setRequests, bestValidDist, evaluator);
```

**Step 2: Implement tightenConstraints in RideExtender**

```java
/**
 * Tighten pairwise ordering constraints using sub-set orderings from the degree graph.
 * For each pair that the pair graph marks as "both directions possible",
 * check if all sub-set orderings agree on one direction.
 */
private static OrderingEnumerator.PairInfo[] tightenConstraints(
        OrderingEnumerator.PairInfo[] original, int[] requestIndices, DegreeGraph degreeGraph) {
    int n = requestIndices.length;
    OrderingEnumerator.PairInfo[] result = original.clone();

    for (int i = 0; i < n; i++) {
        for (int j = i + 1; j < n; j++) {
            OrderingEnumerator.PairInfo pi = OrderingEnumerator.lookup(result, n, i, j);
            if (pi.forwardOnly() || pi.reverseOnly()) continue; // Already fixed

            // Check origin consensus
            Boolean originDir = degreeGraph.getOriginConsensus(requestIndices, i, j);
            // Check dest consensus (independent)
            Boolean destDir = degreeGraph.getDestConsensus(requestIndices, i, j);

            boolean fwdFifo = pi.forwardFifo();
            boolean fwdLifo = pi.forwardLifo();
            boolean revFifo = pi.reverseFifo();
            boolean revLifo = pi.reverseLifo();

            if (originDir != null) {
                if (originDir) {
                    // a before b → remove reverse
                    revFifo = false;
                    revLifo = false;
                } else {
                    // b before a → remove forward
                    fwdFifo = false;
                    fwdLifo = false;
                }
            }

            // Destination tightening: if dest consensus says a before b,
            // that means FIFO in forward direction or LIFO in reverse direction
            // This is more complex — for V1, only tighten origins
            // Dest constraints from sub-sets can be added as a follow-up

            if (fwdFifo != pi.forwardFifo() || fwdLifo != pi.forwardLifo()
                    || revFifo != pi.reverseFifo() || revLifo != pi.reverseLifo()) {
                int idx = i * (2 * n - i - 1) / 2 + (j - i - 1);
                result[idx] = new OrderingEnumerator.PairInfo(fwdFifo, fwdLifo, revFifo, revLifo);
            }
        }
    }
    return result;
}
```

**Step 3: Modify OrderingEnumerator to accept pre-computed PairInfo**

Add an overload of `enumerateAndEvaluate` that accepts `PairInfo[]` instead of extracting from graph:

```java
public static void enumerateAndEvaluate(
        int[] requestIndices, ShareabilityGraph graph,
        PairInfo[] pairConstraints,
        MatsimNetworkCache network, DrtRequest[] requests,
        double[] bestValidDist,
        Consumer<Ordering> evaluator) {
    if (pairConstraints == null) return;
    int n = requestIndices.length;
    EnumerationStats stats = EnumerationStats.get();

    enumerateOriginsPrunedWithEval(
        pairConstraints, n, new int[n], new boolean[n], 0,
        requestIndices, network, requests, bestValidDist,
        new double[n], 0.0, evaluator);
}
```

The existing `enumerateAndEvaluate(int[], ShareabilityGraph, MatsimNetworkCache, DrtRequest[], double[], Consumer)` can delegate:
```java
public static void enumerateAndEvaluate(
        int[] requestIndices, ShareabilityGraph graph,
        MatsimNetworkCache network, DrtRequest[] requests,
        double[] bestValidDist, Consumer<Ordering> evaluator) {
    PairInfo[] constraints = extractConstraints(requestIndices, graph);
    enumerateAndEvaluate(requestIndices, graph, constraints, network, requests, bestValidDist, evaluator);
}
```

**Step 4: Compile and test**

Run: `mvn compile -q -Denforcer.skip=true && mvn test -Dtest=ExMasDemandExtractionE2ETest -Denforcer.skip=true`

**Step 5: Commit**

```bash
git commit -am "feat: propagate sub-set ordering constraints to tighten enumeration DAGs"
```

---

### Task 6: Remove distance B&B for degree 3+ enumeration

**Files:**
- Modify: `algorithm/extension/RideExtender.java`

**Step 1: Change processSet to not use distance B&B when building the degree graph**

The key change: `bestValidDist[0]` starts at `Double.MAX_VALUE` instead of `maxAllowedRideDistance`. Travel time pruning (Check A, Check B) remains active. We still track the best ride (shortest distance) for the result, but we don't use distance to prune branches.

```java
// In processSet, change:
//   double[] bestValidDist = { maxAllowedRideDistance };
// To:
double[] bestValidDist = { Double.MAX_VALUE };  // No distance B&B — find all feasible orderings
```

We still want the BEST ride as the result, so after enumeration pick the shortest:
- The evaluator still tracks `bestRide[0]` (the shortest valid ride)
- But `bestValidDist[0]` is not used for pruning (starts at MAX_VALUE and never tightens during enumeration since we want ALL orderings)

Wait — we DO still want the best ride for the result. We just don't want to PRUNE by distance. So:

```java
double[] bestValidDist = { Double.MAX_VALUE };  // No distance pruning
Ride[] bestRide = { null };
double[] bestRideDist = { Double.MAX_VALUE };   // Track best for result selection

// In evaluator, after budget passes:
double dist = validated.getRideDistance();
if (dist < bestRideDist[0]) {
    bestRideDist[0] = dist;
    bestRide[0] = validated;
}
// Note: do NOT set bestValidDist[0] = dist (that would re-enable B&B)
```

**Step 2: Apply distance-savings threshold as a post-filter instead**

After finding the best ride, check if it meets the distance-savings threshold:
```java
// After enumeration:
if (bestRide[0] != null) {
    double maxAllowed = computeMaxAllowedRideDistance(setRequests);
    if (bestRide[0].getRideDistance() > maxAllowed) {
        // Ride exists but doesn't meet distance savings threshold
        // Still constraint-feasible (for graph) but not a "valid" ride
        bestRide[0] = null;
    }
}
```

This separation is important: the set is CONSTRAINT-FEASIBLE (for the graph) even if the best ride fails the distance-savings threshold.

**Step 3: Update feasibility tracking to distinguish constraint-feasible from distance-valid**

```java
if (!allValidOrderings.isEmpty()) {
    stats.setsConstraintFeasible++;
    long setHash = hashRequestSet(newSet);
    feasibleSetResults.put(setHash, new DegreeGraph.FeasibleSetResult(
        newSet.clone(), setHash, allValidOrderings));
}
if (feasibilityFlags[1]) stats.setsBudgetFeasible++;
```

**Step 4: Compile and test**

Run: `mvn compile -q -Denforcer.skip=true && mvn test -Dtest=ExMasDemandExtractionE2ETest -Denforcer.skip=true`

**Step 5: Commit**

```bash
git commit -am "feat: remove distance B&B, collect all feasible orderings for degree graph

Distance-savings threshold applied as post-filter instead of B&B pruning.
Sets are constraint-feasible (for graph) even if best ride fails distance
threshold. Travel time pruning (Check A/B) unchanged."
```

---

## Phase C: Verification

### Task 7: Full correctness + performance verification

**Step 1: Run 1% Bavaria → must produce exactly 12,552 rides**

```bash
mvn exec:java -o \
  -Dexec.mainClass=org.matsim.contrib.demand_extraction.run.RunBavaria30kmDemandExtraction \
  -Dexec.args="--scenario-path ../../../matsim_scenarios/bavaria/output/kelheim_30km_100pct \
               --population ../../../matsim_scenarios/bavaria/output/populations/population_1pct_kelheim30km.xml.gz \
               --sample 100 --iterations 0 --trip-filter-radius 30 \
               --filter-municipality Kelheim \
               --shapes ../../../matsim_scenarios/bavaria/data/germany/tmp_vg250/vg250-ew_12-31.utm32s.gpkg.ebenen/vg250-ew_ebenen_1231/DE_VG250.gpkg \
               --travel-times ../../../matsim_scenarios/bavaria/output/base-simulation-10pct/travel_times.tsv \
               --output-dir ../../../matsim_scenarios/bavaria/output/demand-extraction-1pct-degree-graph-full \
               --no-predecessors" \
  -Denforcer.skip=true
```

**CRITICAL:** Must produce exactly 12,552 rides. If not, the ordering constraint tightening or distance B&B removal changed results — debug by comparing ride-by-ride.

Note: removing distance B&B means we find MORE constraint-feasible orderings, but the BEST ride per set should still be the same (shortest distance among all valid orderings). So the ride count should be identical.

**Step 2: Run 10% Bavaria to degree 5 → compare timing**

```bash
mvn exec:java -o \
  -Dexec.mainClass=org.matsim.contrib.demand_extraction.run.RunBavaria30kmDemandExtraction \
  -Dexec.args="--scenario-path ../../../matsim_scenarios/bavaria/output/kelheim_30km_100pct \
               --population ../../../matsim_scenarios/bavaria/output/populations/population_10pct_kelheim30km.xml.gz \
               --sample 100 --iterations 0 --trip-filter-radius 30 \
               --filter-municipality Kelheim \
               --shapes ../../../matsim_scenarios/bavaria/data/germany/tmp_vg250/vg250-ew_12-31.utm32s.gpkg.ebenen/vg250-ew_ebenen_1231/DE_VG250.gpkg \
               --travel-times ../../../matsim_scenarios/bavaria/output/base-simulation-10pct/travel_times.tsv \
               --output-dir ../../../matsim_scenarios/bavaria/output/demand-extraction-10pct-degree-graph-full \
               --no-predecessors --max-degree 5" \
  -Denforcer.skip=true
```

**Compare against baseline (10%, with scoring cache + travel time pruning):**

| Metric | Baseline | Expected with degree graph |
|--------|----------|--------------------------|
| Degree 3 time | 32s | ~35s (+graph build) |
| Degree 4 time | 45s | ~10-15s |
| Degree 5 time | 410s | ~30-80s |
| Degree 4 candidates | 10.73M | ~1.94M |
| Degree 5 candidates | 26.59M | ~3.09M |
| Degree 4 orderings/set | 7.7 | ~1.5-3 |
| Degree 5 orderings/set | 58.5 | ~3-10 |

**Step 3: Check log output for all profiling data**

```bash
grep -E "Enumeration Profile|Sets processed|Orderings|Extension complete|Degree.*graph|Graph" \
  ../../../matsim_scenarios/bavaria/output/demand-extraction-10pct-degree-graph-full/*.logfile.log
```

**Step 4: Document results and commit session log**

Write results to `docs/plans/2026-04-04-degree-graph-session-log.md` with all timing data, candidate counts, ordering statistics.

```bash
git commit -am "docs: degree-specific graph implementation results"
```

---

## Summary of changes per file

| File | What changes |
|------|-------------|
| `algorithm/graph/DegreeGraph.java` | **NEW** — extension index, ordering storage, findExtensions, consensus queries |
| `algorithm/extension/RideExtender.java` | Use DegreeGraph for candidates, collect all orderings, tighten constraints, remove distance B&B |
| `algorithm/extension/OrderingEnumerator.java` | Make extractConstraints/PairInfo public, add overload accepting pre-computed PairInfo |
| `algorithm/engine/ExMasEngine.java` | Build DegreeGraph after each degree, pass to next iteration |
| `algorithm/extension/EnumerationStats.java` | Minor: update counters (already instrumented) |
