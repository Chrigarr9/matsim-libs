# Parent-Ordering Cache Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace `SubSetOrderingFeasibility` + `OrderingConflicts` with a single `ParentOrderingCache` that speeds up the ExMAS ride extension exact path via parent-ordering projection, and adds an optional heuristic insertion path for high degrees.

**Architecture:** After each degree-`k` enumeration pass, cache the feasible origin orderings per feasible set. At degree `k+1`, tighten the origin DAG pairwise from direct-parent data, then filter child orderings by projection membership in each parent's feasible list. Optionally bypass enumeration entirely at high degrees by inserting the new request's pickup/dropoff into the top-*M* parent orderings and routing only the deduplicated insertion candidates.

**Tech Stack:** Java 17, MATSim contribs, Maven, JUnit 5, fastutil (`Long2ObjectOpenHashMap`, `IntOpenHashSet`).

**Design doc:** `docs/plans/2026-04-13-parent-ordering-cache-design.md`

**Working directory:** `matsim-libs/contribs/drt-demand-extraction/` (git submodule). All commits in this plan land in the submodule repo unless stated otherwise. Sync the submodule pointer in the parent repo at the end.

**Build/test commands:**
- Build: `cd matsim-libs/contribs/drt-demand-extraction && mvn test-compile -q`
- Unit test (single class): `mvn test -Dtest=ParentOrderingCacheTest -q`
- Regression E2E: `mvn test -Dtest=ExMasKelheimE2ETest -q`
- 10% Bavaria benchmark: `mvn exec:java -Denforcer.skip=true -Dexec.mainClass=org.matsim.contrib.demand_extraction.run.RunBavaria30kmDemandExtraction -Dexec.args="--scenario-path ../../../matsim_scenarios/bavaria/output/kelheim_30km_100pct --population ../../../matsim_scenarios/bavaria/output/populations/population_10pct_kelheim30km.xml.gz --sample 100 --iterations 0 --trip-filter-radius 30 --filter-municipality Kelheim --shapes ../../../matsim_scenarios/bavaria/data/germany/tmp_vg250/vg250-ew_12-31.utm32s.gpkg.ebenen/vg250-ew_ebenen_1231/DE_VG250.gpkg --travel-times ../../../matsim_scenarios/bavaria/output/base-simulation-10pct/travel_times.tsv --no-predecessors --max-degree 8 --output-dir ../../../outputs/parent-cache-<variant>"`

**Commit prefix convention (match recent submodule history):** `feat:`, `perf:`, `fix:`, `refactor:`, `test:`, `docs:`. Messages one short sentence, no trailing summaries.

---

## Phase 1 — Build `ParentOrderingCache` (exact path foundation)

### Task 1: Create skeleton + record/commit unit test

**Files:**
- Create: `matsim-libs/contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/ParentOrderingCache.java`
- Create: `matsim-libs/contribs/drt-demand-extraction/src/test/java/org/matsim/contrib/demand_extraction/algorithm/extension/ParentOrderingCacheTest.java`

**Step 1: Write the failing test**

```java
// ParentOrderingCacheTest.java
package org.matsim.contrib.demand_extraction.algorithm.extension;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ParentOrderingCacheTest {

    @Test
    void recordAndRetrieveOrderings() {
        ParentOrderingCache cache = new ParentOrderingCache();
        int[] set = {2, 5, 9};
        int[][] orderings = { {0, 1, 2}, {1, 0, 2} };

        cache.recordPending(set, orderings);
        cache.commit();

        int[][] retrieved = cache.get(set);
        assertNotNull(retrieved);
        assertEquals(2, retrieved.length);
        assertArrayEquals(new int[]{0, 1, 2}, retrieved[0]);
        assertArrayEquals(new int[]{1, 0, 2}, retrieved[1]);
    }

    @Test
    void missingSetReturnsNull() {
        ParentOrderingCache cache = new ParentOrderingCache();
        assertNull(cache.get(new int[]{1, 2, 3}));
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd matsim-libs/contribs/drt-demand-extraction && mvn test -Dtest=ParentOrderingCacheTest -q`
Expected: FAIL — `ParentOrderingCache` class does not exist.

**Step 3: Write minimal implementation**

```java
// ParentOrderingCache.java
package org.matsim.contrib.demand_extraction.algorithm.extension;

import java.util.concurrent.ConcurrentLinkedQueue;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * Per-set cache of feasible origin orderings from the previous degree pass.
 *
 * <p>After each degree-k enumeration pass, records the feasible origin orderings
 * for every feasible set. At degree k+1, `OrderingEnumerator` uses this data to
 * tighten the origin DAG and filter child orderings by projection membership.
 *
 * <p>Thread-safe recording via {@link #recordPending} (lock-free queue); commit
 * between degrees via {@link #commit()}. Lookups read the committed map — safe
 * for concurrent reads, no synchronization needed at lookup time.
 */
public final class ParentOrderingCache {

    private static final long HASH_PRIME = 1000003L;

    private final Long2ObjectOpenHashMap<int[][]> committed = new Long2ObjectOpenHashMap<>();
    private final ConcurrentLinkedQueue<Entry> pending = new ConcurrentLinkedQueue<>();

    private record Entry(long key, int[][] orderings) {}

    /** Record feasible orderings for a set. Thread-safe. */
    public void recordPending(int[] sortedSet, int[][] orderings) {
        pending.add(new Entry(hashSorted(sortedSet), orderings));
    }

    /** Merge pending recordings into the committed map. Call between degrees. */
    public void commit() {
        Entry e;
        while ((e = pending.poll()) != null) {
            committed.put(e.key, e.orderings);
        }
    }

    /** Look up feasible orderings for a set. Returns null if not cached. */
    public int[][] get(int[] sortedSet) {
        return committed.get(hashSorted(sortedSet));
    }

    public int size() {
        return committed.size();
    }

    /** Clear committed data. Call after a degree's cache is no longer needed. */
    public void clear() {
        committed.clear();
    }

    static long hashSorted(int[] sortedSet) {
        long h = 0;
        for (int v : sortedSet) h = h * HASH_PRIME + v;
        return h;
    }
}
```

**Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=ParentOrderingCacheTest -q`
Expected: PASS, 2 tests.

**Step 5: Commit**

```bash
cd matsim-libs && git add contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/ParentOrderingCache.java contribs/drt-demand-extraction/src/test/java/org/matsim/contrib/demand_extraction/algorithm/extension/ParentOrderingCacheTest.java && git commit -m "feat: add ParentOrderingCache data structure + record/commit tests"
```

---

### Task 2: Lehmer index helper + projection computation

**Files:**
- Modify: `ParentOrderingCache.java`
- Modify: `ParentOrderingCacheTest.java`

**Step 1: Write the failing tests**

Append to `ParentOrderingCacheTest.java`:

```java
@Test
void lehmerIndexIdentity() {
    // [0,1,2] is the identity permutation, Lehmer index 0
    assertEquals(0, ParentOrderingCache.lehmerIndex(new int[]{0, 1, 2}, 3));
}

@Test
void lehmerIndexReverse() {
    // [2,1,0] is the reverse, last Lehmer index (k!-1 = 5 for k=3)
    assertEquals(5, ParentOrderingCache.lehmerIndex(new int[]{2, 1, 0}, 3));
}

@Test
void lehmerIndexAllPerms3() {
    // All 6 permutations produce unique indices in [0,6)
    int[][] perms = { {0,1,2},{0,2,1},{1,0,2},{1,2,0},{2,0,1},{2,1,0} };
    java.util.Set<Integer> seen = new java.util.HashSet<>();
    for (int[] p : perms) {
        int idx = ParentOrderingCache.lehmerIndex(p, 3);
        assertTrue(idx >= 0 && idx < 6);
        assertTrue(seen.add(idx), "duplicate Lehmer index for " + java.util.Arrays.toString(p));
    }
    assertEquals(6, seen.size());
}

@Test
void projectionExtractsSubsequence() {
    // Set {10, 20, 30, 40} with ordering π = [2, 0, 3, 1] (indices into set)
    // means route order = [30, 10, 40, 20]
    // Projection onto parent {10, 20, 40} (indices [0, 1, 3]) should give
    // the relative order of 10, 20, 40 inside π = [10, 40, 20] → ranks [0, 2, 1]
    int[] set = {10, 20, 30, 40};
    int[] childPerm = {2, 0, 3, 1};
    int[] parentMembers = {0, 1, 3}; // positions of parent elements in sorted set
    int[] projection = ParentOrderingCache.projectOntoParent(childPerm, parentMembers);
    assertArrayEquals(new int[]{0, 2, 1}, projection);
}
```

**Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=ParentOrderingCacheTest -q`
Expected: FAIL — `lehmerIndex` and `projectOntoParent` do not exist.

**Step 3: Implement**

Add to `ParentOrderingCache.java`:

```java
/**
 * Compute the Lehmer code (factorial number system index) of a permutation.
 * For a k-element permutation, the result is in [0, k!).
 * O(k²).
 */
static int lehmerIndex(int[] perm, int k) {
    int index = 0;
    for (int i = 0; i < k; i++) {
        int count = 0;
        for (int j = i + 1; j < k; j++) {
            if (perm[j] < perm[i]) count++;
        }
        index = index * (k - i) + count;
    }
    return index;
}

/**
 * Project a child ordering onto a parent sub-set.
 *
 * <p>Given a child set's permutation (positions into the sorted child set) and the
 * local positions of the parent members inside the child set, returns the
 * sub-permutation of the parent in terms of ranks inside the parent's own sorted order.
 *
 * @param childPerm positional permutation of the child set (local indices 0..childSize-1)
 * @param parentMembers ascending positions of parent elements inside the sorted child set
 * @return a permutation of [0..parentMembers.length) giving the parent's order as
 *         ranks inside the parent's sorted order
 */
static int[] projectOntoParent(int[] childPerm, int[] parentMembers) {
    int parentSize = parentMembers.length;
    // Step 1: walk the child permutation in order, collecting each position from
    // childPerm that belongs to parentMembers. The result is the parent's traversal
    // order expressed as positions into the sorted child set.
    int[] parentPositions = new int[parentSize];
    int idx = 0;
    for (int cp : childPerm) {
        for (int m : parentMembers) {
            if (m == cp) {
                parentPositions[idx++] = cp;
                break;
            }
        }
    }
    // Step 2: convert each parentPositions[i] into a rank inside parentMembers (which
    // is already ascending). The rank is just the index of parentPositions[i] in
    // parentMembers.
    int[] ranks = new int[parentSize];
    for (int i = 0; i < parentSize; i++) {
        for (int j = 0; j < parentSize; j++) {
            if (parentMembers[j] == parentPositions[i]) {
                ranks[i] = j;
                break;
            }
        }
    }
    return ranks;
}
```

**Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=ParentOrderingCacheTest -q`
Expected: PASS, 6 tests.

**Step 5: Commit**

```bash
cd matsim-libs && git add contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/ParentOrderingCache.java contribs/drt-demand-extraction/src/test/java/org/matsim/contrib/demand_extraction/algorithm/extension/ParentOrderingCacheTest.java && git commit -m "feat: add Lehmer index + parent projection to ParentOrderingCache"
```

---

### Task 3: `containsProjection` membership check

**Files:**
- Modify: `ParentOrderingCache.java`
- Modify: `ParentOrderingCacheTest.java`

**Step 1: Write the failing test**

Append:

```java
@Test
void containsProjectionHit() {
    ParentOrderingCache cache = new ParentOrderingCache();
    int[] parentSet = {10, 20, 40};
    int[][] parentOrderings = { {0, 2, 1}, {2, 0, 1} }; // feasible orderings of the parent
    cache.recordPending(parentSet, parentOrderings);
    cache.commit();

    // Child set {10, 20, 30, 40}, child ordering [2,0,3,1] projects to [0,2,1]
    int[] childPerm = {2, 0, 3, 1};
    int[] parentMembers = {0, 1, 3};
    assertTrue(cache.containsProjection(parentSet, childPerm, parentMembers));
}

@Test
void containsProjectionMiss() {
    ParentOrderingCache cache = new ParentOrderingCache();
    int[] parentSet = {10, 20, 40};
    int[][] parentOrderings = { {0, 1, 2} }; // only identity is feasible
    cache.recordPending(parentSet, parentOrderings);
    cache.commit();

    int[] childPerm = {2, 0, 3, 1}; // projects to [0,2,1], not feasible
    int[] parentMembers = {0, 1, 3};
    assertFalse(cache.containsProjection(parentSet, childPerm, parentMembers));
}
```

**Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=ParentOrderingCacheTest -q`
Expected: FAIL — `containsProjection` not defined.

**Step 3: Implement**

Add to `ParentOrderingCache.java`:

```java
/**
 * Check whether a child ordering's projection onto a cached parent set matches
 * any of the parent's feasible orderings.
 *
 * @return true if the parent is in the cache AND the projection matches one of
 *         its orderings; true also if the parent is NOT in the cache (no info = no prune);
 *         false only when we have parent data and the projection is rejected.
 */
public boolean containsProjection(int[] parentSortedSet, int[] childPerm, int[] parentMembers) {
    int[][] orderings = get(parentSortedSet);
    if (orderings == null) return true; // no data = can't reject
    int[] projection = projectOntoParent(childPerm, parentMembers);
    for (int[] o : orderings) {
        if (java.util.Arrays.equals(o, projection)) return true;
    }
    return false;
}
```

**Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=ParentOrderingCacheTest -q`
Expected: PASS, 8 tests.

**Step 5: Commit**

```bash
cd matsim-libs && git add contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/ParentOrderingCache.java contribs/drt-demand-extraction/src/test/java/org/matsim/contrib/demand_extraction/algorithm/extension/ParentOrderingCacheTest.java && git commit -m "feat: containsProjection membership check in ParentOrderingCache"
```

---

### Task 4: `tightenDAG` using parent pair data

**Files:**
- Modify: `ParentOrderingCache.java`
- Modify: `ParentOrderingCacheTest.java`

**Step 1: Write the failing test**

Append:

```java
@Test
void tightenDAGForcesPairFromParents() {
    ParentOrderingCache cache = new ParentOrderingCache();
    // Parent set {10, 20, 30}: only feasible ordering is [0, 1, 2] (10 before 20 before 30)
    // Parent set {10, 20, 40}: only feasible ordering is [0, 1, 2] (10 before 20 before 40)
    // Parent set {10, 30, 40}: only feasible ordering is [0, 1, 2]
    // Parent set {20, 30, 40}: only feasible ordering is [0, 1, 2]
    cache.recordPending(new int[]{10, 20, 30}, new int[][]{{0, 1, 2}});
    cache.recordPending(new int[]{10, 20, 40}, new int[][]{{0, 1, 2}});
    cache.recordPending(new int[]{10, 30, 40}, new int[][]{{0, 1, 2}});
    cache.recordPending(new int[]{20, 30, 40}, new int[][]{{0, 1, 2}});
    cache.commit();

    int[] childSet = {10, 20, 30, 40};
    Boolean[][] adj = new Boolean[4][4];
    int added = cache.tightenDAG(adj, childSet);

    // Every pair should be forced forward (a before b). 6 pairs × 1 direction each = 6 edges.
    assertTrue(added >= 6);
    for (int i = 0; i < 4; i++) {
        for (int j = i + 1; j < 4; j++) {
            assertEquals(Boolean.TRUE, adj[i][j], "pair (" + i + "," + j + ") not forced");
            assertEquals(Boolean.FALSE, adj[j][i]);
        }
    }
}

@Test
void tightenDAGLeavesUnconstrainedPairs() {
    ParentOrderingCache cache = new ParentOrderingCache();
    // Parent {10,20,30} allows both (0,1,2) and (1,0,2) → 10 vs 20 unconstrained
    cache.recordPending(new int[]{10, 20, 30}, new int[][]{{0, 1, 2}, {1, 0, 2}});
    cache.commit();

    int[] childSet = {10, 20, 30};
    Boolean[][] adj = new Boolean[3][3];
    int added = cache.tightenDAG(adj, childSet);

    // Pair (0,1) is unconstrained → no edges added for it
    assertNull(adj[0][1]);
    assertNull(adj[1][0]);
    // Pair (0,2) and (1,2): both feasible orderings place 2 last → force 0 before 2 and 1 before 2
    assertEquals(Boolean.TRUE, adj[0][2]);
    assertEquals(Boolean.TRUE, adj[1][2]);
    assertEquals(2, added);
}
```

**Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=ParentOrderingCacheTest -q`
Expected: FAIL — `tightenDAG` not defined.

**Step 3: Implement**

Add to `ParentOrderingCache.java`:

```java
/**
 * Tighten the origin adjacency DAG for a child set using all direct-parent feasibility data.
 *
 * <p>For each pair (i, j) in the child set that is not already constrained, walk all
 * k+1 direct parents of the child set. If no feasible ordering across any parent containing
 * both i and j ever places i before j, force edge j → i (adj[j][i]=true, adj[i][j]=false).
 *
 * <p>Does nothing for parents not in the cache (no data = no inference).
 *
 * @param adj child origin adjacency matrix, modified in place. adj[i][j] = true means
 *            rank i must come before rank j in the final ordering.
 * @param childSortedSet ascending-sorted request indices of the child set (size n)
 * @return number of edges added
 */
public int tightenDAG(Boolean[][] adj, int[] childSortedSet) {
    int n = childSortedSet.length;
    if (n < 3) return 0;

    int edgesAdded = 0;

    // Build all k+1 parent sets (drop each element in turn)
    int[][] parents = new int[n][];
    int[][] parentMembers = new int[n][]; // positions in childSortedSet
    for (int drop = 0; drop < n; drop++) {
        parents[drop] = new int[n - 1];
        parentMembers[drop] = new int[n - 1];
        int pi = 0;
        for (int i = 0; i < n; i++) {
            if (i == drop) continue;
            parents[drop][pi] = childSortedSet[i];
            parentMembers[drop][pi] = i;
            pi++;
        }
    }

    // For each unconstrained pair (a, b), check all parents containing both
    for (int a = 0; a < n; a++) {
        for (int b = a + 1; b < n; b++) {
            if (adj[a][b] != null) continue;

            boolean everABeforeB = false;
            boolean everBBeforeA = false;
            boolean haveAnyData = false;

            for (int drop = 0; drop < n; drop++) {
                if (drop == a || drop == b) continue; // this parent excludes a or b

                int[][] parentOrderings = get(parents[drop]);
                if (parentOrderings == null) continue;
                haveAnyData = true;

                // Find positions of a and b inside parentMembers[drop]
                int posA = -1, posB = -1;
                for (int i = 0; i < parentMembers[drop].length; i++) {
                    if (parentMembers[drop][i] == a) posA = i;
                    if (parentMembers[drop][i] == b) posB = i;
                }

                for (int[] po : parentOrderings) {
                    int rankA = -1, rankB = -1;
                    for (int i = 0; i < po.length; i++) {
                        if (po[i] == posA) rankA = i;
                        if (po[i] == posB) rankB = i;
                    }
                    if (rankA < rankB) everABeforeB = true;
                    else if (rankB < rankA) everBBeforeA = true;
                    if (everABeforeB && everBBeforeA) break;
                }
                if (everABeforeB && everBBeforeA) break;
            }

            if (!haveAnyData) continue;
            if (everABeforeB && !everBBeforeA) {
                adj[a][b] = Boolean.TRUE;
                adj[b][a] = Boolean.FALSE;
                edgesAdded++;
            } else if (everBBeforeA && !everABeforeB) {
                adj[b][a] = Boolean.TRUE;
                adj[a][b] = Boolean.FALSE;
                edgesAdded++;
            }
        }
    }
    return edgesAdded;
}
```

**Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=ParentOrderingCacheTest -q`
Expected: PASS, 10 tests.

**Step 5: Commit**

```bash
cd matsim-libs && git add contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/ParentOrderingCache.java contribs/drt-demand-extraction/src/test/java/org/matsim/contrib/demand_extraction/algorithm/extension/ParentOrderingCacheTest.java && git commit -m "feat: parent-ordering DAG tightening for child sets"
```

---

## Phase 2 — Wire `ParentOrderingCache` into enumeration (Path E)

### Task 5: Add cache parameter + stats counters

**Files:**
- Modify: `matsim-libs/contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/EnumerationStats.java`

**Step 1: Read current EnumerationStats structure**

Read `EnumerationStats.java` to understand the existing counter pattern. Add new counters alongside existing ones.

**Step 2: Add counters**

Add these fields + their getters/increment methods (follow the existing style in the file):

```java
public long prunedByDagTightening;
public long prunedByProjection;
public long heuristicSetsHandled;
public long heuristicInsertionsGenerated;
public long heuristicInsertionsDeduped;
public long heuristicInsertionsRouted;
```

Also add them to whatever aggregation / log line prints stats at end of degree (search for `Orderings evaluated:` in `EnumerationStats.java` to find the report method).

**Step 3: Build**

Run: `cd matsim-libs/contribs/drt-demand-extraction && mvn test-compile -q`
Expected: build passes.

**Step 4: Commit**

```bash
cd matsim-libs && git add contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/EnumerationStats.java && git commit -m "feat: add ParentOrderingCache stats counters to EnumerationStats"
```

---

### Task 6: Thread `ParentOrderingCache` through signatures

**Files:**
- Modify: `matsim-libs/contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingEnumerator.java`
- Modify: `matsim-libs/contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/RideExtender.java`
- Modify: `matsim-libs/contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/engine/ExMasEngine.java`

**Step 1: Change signatures**

In `OrderingEnumerator.java`: add `ParentOrderingCache parentCache` parameter to `enumerateOriginsPrunedWithEval(...)` (or whichever method is the public entry point — locate the method that currently takes `SubSetOrderingFeasibility subsetFeasibility`). For now, also keep the old parameter so both work side-by-side.

In `RideExtender.java`: add `ParentOrderingCache parentCache` field, constructor parameter, and pass it into `OrderingEnumerator` calls inside `processSet`.

In `ExMasEngine.java`: create a `ParentOrderingCache parentCache = new ParentOrderingCache();` alongside the existing `SubSetOrderingFeasibility subsetFeasibility`. Pass into `RideExtender`. Do NOT populate or use it yet — wiring only.

**Step 2: Build**

Run: `mvn test-compile -q` in `matsim-libs/contribs/drt-demand-extraction`
Expected: clean compile.

**Step 3: Run existing regression test**

Run: `mvn test -Dtest=ExMasKelheimE2ETest -q`
Expected: PASS (no behavior change yet).

**Step 4: Commit**

```bash
cd matsim-libs && git add contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingEnumerator.java contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/RideExtender.java contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/engine/ExMasEngine.java && git commit -m "feat: thread ParentOrderingCache through extension pipeline (no-op wiring)"
```

---

### Task 7: Populate cache at end of each degree

**Files:**
- Modify: `RideExtender.java`
- Modify: `ExMasEngine.java`

**Step 1: Collect feasible orderings per set in `processSet`**

Inside `RideExtender.processSet(...)`, the enumerator already returns a list of `Ordering` records. After the set is processed and a best ride selected, collect **all** feasible origin orderings (de-duplicated by origin perm hash) as an `int[][]`, and call:

```java
parentCache.recordPending(newSet, feasibleOrigins);
```

(Where `newSet` is the sorted child set — the same key used by `DegreeGraph`.)

**Step 2: Commit + drop between degrees in `ExMasEngine`**

After each degree's extension pass completes, call:

```java
parentCache.commit();
// log: LOG.info("ParentOrderingCache at degree {}: {} sets", degree, parentCache.size());
```

Before degree `k+1` starts, **reuse** the same cache object — it already contains degree `k` data. After degree `k+1` completes, before degree `k+2` starts, call `parentCache.clear()` then `commit()` (or swap to a new instance) so we only keep one degree's worth of data in memory.

Strictly: we need `clear` + commit pattern. Simplest: two alternating caches (ping-pong). Or easier: a single cache, clear at the *start* of each degree's extension pass, and populate during that pass. Wait — that discards data the current pass needs to USE. Use two caches: `previousCache` (read during enumeration) and `currentCache` (written during enumeration). At the end of degree `k` extension, rotate: `previousCache = currentCache; currentCache = new ParentOrderingCache();`.

Update `OrderingEnumerator` / `RideExtender` parameter to take the read-only `previousCache` for lookups and the write-only `currentCache` for recording.

**Step 3: Build + regression test**

Run: `mvn test -Dtest=ExMasKelheimE2ETest -q`
Expected: PASS, ride counts identical to before.

**Step 4: Commit**

```bash
cd matsim-libs && git add contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/RideExtender.java contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/engine/ExMasEngine.java && git commit -m "feat: populate ParentOrderingCache between degrees (two-cache rotation)"
```

---

### Task 8: Apply DAG tightening at set entry

**Files:**
- Modify: `OrderingEnumerator.java`

**Step 1: Call `tightenDAG` in origin DAG construction**

Find where the origin adjacency matrix (`Boolean[][] adj`) is built from pair constraints for a child set (search for the DAG setup inside `enumerateOriginsPrunedWithEval` or the shareability-graph-driven setup). After pair constraints are applied but before topo sort begins, call:

```java
if (previousCache != null && depth == 0) {
    int added = previousCache.tightenDAG(adj, requestIndices);
    stats.prunedByDagTightening += added; // approximate: 1 edge = eliminates some orderings
}
```

**Step 2: Regression test**

Run: `mvn test -Dtest=ExMasKelheimE2ETest -q`
Expected: PASS, ride counts identical. Tightening can only remove orderings that have no feasible projection, so ride loss should be 0.

**Step 3: Commit**

```bash
cd matsim-libs && git add contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingEnumerator.java && git commit -m "feat: apply parent-cache DAG tightening at origin enumeration entry"
```

---

### Task 9: Per-candidate projection filter

**Files:**
- Modify: `OrderingEnumerator.java`

**Step 1: Add projection check before routing**

Inside the origin enumeration loop (at the depth check where a full ordering is assembled, just before destination enumeration / routing), add:

```java
if (previousCache != null && depth == n) {
    // Check projection onto every k+1 parent
    boolean rejected = false;
    for (int drop = 0; drop < n; drop++) {
        int[] parentSet = new int[n - 1];
        int[] parentMembers = new int[n - 1];
        int pi = 0;
        for (int i = 0; i < n; i++) {
            if (i == drop) continue;
            parentSet[pi] = requestIndices[i];
            parentMembers[pi] = i;
            pi++;
        }
        if (!previousCache.containsProjection(parentSet, perm, parentMembers)) {
            rejected = true;
            break;
        }
    }
    if (rejected) {
        stats.prunedByProjection++;
        continue; // skip this origin ordering
    }
}
```

**Step 2: Regression test**

Run: `mvn test -Dtest=ExMasKelheimE2ETest -q`
Expected: PASS, ride counts identical.

**Step 3: Commit**

```bash
cd matsim-libs && git add contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingEnumerator.java && git commit -m "feat: parent-projection filter before destination enumeration"
```

---

### Task 10: Full regression + baseline benchmark (Path E)

**Files:** none

**Step 1: Run full E2E tests**

Run: `cd matsim-libs/contribs/drt-demand-extraction && mvn test -q`
Expected: all tests pass.

**Step 2: Run 10% Bavaria benchmark**

Use the benchmark command at the top of the plan with `--output-dir ../../../outputs/parent-cache-exact`. Capture the log.

**Step 3: Compare vs `outputs/diag-run` baseline**

Ride counts must match at degrees 3–7. Tabulate per-degree time. Expected: ≥2× speedup at degree 7, no ride loss.

Record the table in a new session log `docs/plans/2026-04-13-parent-ordering-cache-session-log.md`.

**Step 4: Commit session log**

```bash
cd /c/Users/VWAUCCY/dev/msf/projects/Dissertation && git add docs/plans/2026-04-13-parent-ordering-cache-session-log.md && git commit -m "docs: Path E benchmark results"
```

---

## Phase 3 — Remove obsolete code

### Task 11: Remove `SubSetOrderingFeasibility` wiring

**Files:**
- Modify: `ExMasEngine.java`, `RideExtender.java`, `OrderingEnumerator.java`

**Step 1: Delete wiring**

Remove `SubSetOrderingFeasibility` creation from `ExMasEngine.java`. Remove the parameter from `RideExtender`/`OrderingEnumerator` signatures. Remove all `subsetFeasibility.*` calls. Keep the `SubSetOrderingFeasibility.java` source file in place for now (dead code, easy to restore).

**Step 2: Regression test**

Run: `mvn test -Dtest=ExMasKelheimE2ETest -q`
Expected: PASS.

**Step 3: Commit**

```bash
cd matsim-libs && git add contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/engine/ExMasEngine.java contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/RideExtender.java contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingEnumerator.java && git commit -m "refactor: remove SubSetOrderingFeasibility wiring (replaced by ParentOrderingCache)"
```

---

### Task 12: Delete `OrderingConflicts` class + test

**Files:**
- Delete: `matsim-libs/contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingConflicts.java`
- Delete: `matsim-libs/contribs/drt-demand-extraction/src/test/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingConflictsTest.java`

**Step 1: Verify no references**

Run: `grep -rn "OrderingConflicts" matsim-libs/contribs/drt-demand-extraction/src/` (Grep tool in Claude Code)
Expected: only the files being deleted.

**Step 2: Delete + test**

```bash
cd matsim-libs && git rm contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingConflicts.java contribs/drt-demand-extraction/src/test/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingConflictsTest.java
cd contribs/drt-demand-extraction && mvn test -q
```

Expected: tests pass.

**Step 3: Commit**

```bash
cd matsim-libs && git commit -m "refactor: delete OrderingConflicts (superseded by ParentOrderingCache)"
```

---

## Phase 4 — Heuristic path (H2)

### Task 13: Add config fields

**Files:**
- Modify: `matsim-libs/contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/config/ExMasConfigGroup.java`

**Step 1: Read existing config fields to understand the pattern**

Read `ExMasConfigGroup.java` — it uses the MATSim `ReflectiveConfigGroup` style with `@StringGetter`/`@StringSetter`.

**Step 2: Add fields**

```java
private int heuristicOrderingFromDegree = -1; // -1 = disabled
private int heuristicOrderingTopM = 3;
private boolean heuristicOrderingUseAllParents = true;

@StringGetter("heuristicOrderingFromDegree")
public int getHeuristicOrderingFromDegree() { return heuristicOrderingFromDegree; }

@StringSetter("heuristicOrderingFromDegree")
public void setHeuristicOrderingFromDegree(int v) { this.heuristicOrderingFromDegree = v; }

@StringGetter("heuristicOrderingTopM")
public int getHeuristicOrderingTopM() { return heuristicOrderingTopM; }

@StringSetter("heuristicOrderingTopM")
public void setHeuristicOrderingTopM(int v) { this.heuristicOrderingTopM = v; }

@StringGetter("heuristicOrderingUseAllParents")
public boolean isHeuristicOrderingUseAllParents() { return heuristicOrderingUseAllParents; }

@StringSetter("heuristicOrderingUseAllParents")
public void setHeuristicOrderingUseAllParents(boolean v) { this.heuristicOrderingUseAllParents = v; }
```

**Step 3: Build**

Run: `mvn test-compile -q`
Expected: clean.

**Step 4: Commit**

```bash
cd matsim-libs && git add contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/config/ExMasConfigGroup.java && git commit -m "feat: config fields for heuristic parent-insertion extension"
```

---

### Task 14: Top-M retrieval + score ranking in `ParentOrderingCache`

**Files:**
- Modify: `ParentOrderingCache.java`
- Modify: `ParentOrderingCacheTest.java`
- Modify: `RideExtender.java` (at the recordPending site — sort orderings by score before recording)

**Step 1: Test: `recordPending` keeps insertion order; `getTopM` returns first M**

Append to `ParentOrderingCacheTest.java`:

```java
@Test
void getTopMReturnsPrefix() {
    ParentOrderingCache cache = new ParentOrderingCache();
    int[][] orderings = { {0,1,2}, {1,0,2}, {2,1,0}, {2,0,1} };
    cache.recordPending(new int[]{10,20,30}, orderings);
    cache.commit();

    int[][] top2 = cache.getTopM(new int[]{10,20,30}, 2);
    assertEquals(2, top2.length);
    assertArrayEquals(new int[]{0,1,2}, top2[0]);
    assertArrayEquals(new int[]{1,0,2}, top2[1]);
}

@Test
void getTopMHandlesFewerThanM() {
    ParentOrderingCache cache = new ParentOrderingCache();
    cache.recordPending(new int[]{10,20,30}, new int[][]{{0,1,2}});
    cache.commit();
    int[][] top5 = cache.getTopM(new int[]{10,20,30}, 5);
    assertEquals(1, top5.length);
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=ParentOrderingCacheTest -q`
Expected: FAIL — `getTopM` not defined.

**Step 3: Implement**

Add to `ParentOrderingCache.java`:

```java
public int[][] getTopM(int[] sortedSet, int m) {
    int[][] all = get(sortedSet);
    if (all == null) return null;
    if (all.length <= m) return all;
    int[][] top = new int[m][];
    System.arraycopy(all, 0, top, 0, m);
    return top;
}
```

In `RideExtender.processSet`, at the recordPending site, **sort** the feasible orderings by ride score descending (use the `Ride` objects that came out of the enumeration). Collect them as `int[][]` in sorted order, then call `recordPending`.

**Step 4: Run tests**

Run: `mvn test -Dtest=ParentOrderingCacheTest -q && mvn test -Dtest=ExMasKelheimE2ETest -q`
Expected: both PASS.

**Step 5: Commit**

```bash
cd matsim-libs && git add contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/ParentOrderingCache.java contribs/drt-demand-extraction/src/test/java/org/matsim/contrib/demand_extraction/algorithm/extension/ParentOrderingCacheTest.java contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/RideExtender.java && git commit -m "feat: rank parent orderings by ride score and expose getTopM"
```

---

### Task 15: Heuristic insertion method (pure logic, unit-testable)

**Files:**
- Create or extend: `OrderingEnumerator.java` — add static helper `generateInsertions`
- Create: `matsim-libs/contribs/drt-demand-extraction/src/test/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingEnumeratorInsertionTest.java`

**Step 1: Write the failing test**

```java
package org.matsim.contrib.demand_extraction.algorithm.extension;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class OrderingEnumeratorInsertionTest {

    @Test
    void insertNewRequestIntoParentOrdering() {
        // Parent ordering [0, 1] for requests {A, B}
        // Child set adds a new request C at position 2 in sorted set {A, B, C}
        // Expected insertions (childSize=3, newIdx=2):
        //   - place C at pos 0: [2, 0, 1]
        //   - place C at pos 1: [0, 2, 1]
        //   - place C at pos 2: [0, 1, 2]
        int[] parentPerm = {0, 1};
        int[] parentMembers = {0, 1}; // positions of parent elements in child sorted set
        int newElementPosition = 2;
        int childSize = 3;

        int[][] insertions = OrderingEnumerator.generateInsertions(
                parentPerm, parentMembers, newElementPosition, childSize);

        assertEquals(3, insertions.length);
        // Order of returned insertions is not specified; assert as a set
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int[] ins : insertions) seen.add(java.util.Arrays.toString(ins));
        assertTrue(seen.contains("[2, 0, 1]"));
        assertTrue(seen.contains("[0, 2, 1]"));
        assertTrue(seen.contains("[0, 1, 2]"));
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=OrderingEnumeratorInsertionTest -q`
Expected: FAIL.

**Step 3: Implement**

Add to `OrderingEnumerator.java`:

```java
/**
 * Generate all possible insertions of a new element into a parent ordering.
 *
 * <p>Given a feasible parent permutation (over child positions of size parentSize),
 * produce all childSize child permutations obtained by inserting the new element at
 * each of the childSize possible positions.
 *
 * @param parentPerm feasible parent permutation (values are positions in the child set)
 * @param parentMembers positions of parent elements in the child sorted set (ascending)
 * @param newElementPosition position of the new element in the child sorted set
 * @param childSize size of the child set (= parentPerm.length + 1)
 * @return all childSize insertion permutations
 */
public static int[][] generateInsertions(int[] parentPerm, int[] parentMembers,
                                          int newElementPosition, int childSize) {
    int parentSize = parentPerm.length;
    assert childSize == parentSize + 1;
    int[][] result = new int[childSize][];
    for (int insertAt = 0; insertAt <= parentSize; insertAt++) {
        int[] child = new int[childSize];
        for (int i = 0; i < insertAt; i++) child[i] = parentPerm[i];
        child[insertAt] = newElementPosition;
        for (int i = insertAt; i < parentSize; i++) child[i + 1] = parentPerm[i];
        result[insertAt] = child;
    }
    return result;
}
```

**Step 4: Run test**

Run: `mvn test -Dtest=OrderingEnumeratorInsertionTest -q`
Expected: PASS.

**Step 5: Commit**

```bash
cd matsim-libs && git add contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingEnumerator.java contribs/drt-demand-extraction/src/test/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingEnumeratorInsertionTest.java && git commit -m "feat: add generateInsertions helper for heuristic extension"
```

---

### Task 16: Integrate heuristic path into `processSet`

**Files:**
- Modify: `RideExtender.java` (or wherever the per-set enumeration entry point is)
- Modify: `OrderingEnumerator.java` (may need a new entry point `enumerateByInsertion`)

**Step 1: Add config access**

Pass `ExMasConfigGroup config` (or the three heuristic fields) into the `RideExtender` constructor if not already there.

**Step 2: Branch in `processSet`**

```java
int degree = newSet.length;
if (config.getHeuristicOrderingFromDegree() > 0
        && degree >= config.getHeuristicOrderingFromDegree()
        && previousCache != null) {
    return processSetByInsertion(newSet, setHash, previousCache,
            config.getHeuristicOrderingTopM(),
            config.isHeuristicOrderingUseAllParents());
}
// else: existing exact path
```

**Step 3: Implement `processSetByInsertion`**

```java
private Ride processSetByInsertion(int[] newSet, long setHash, ParentOrderingCache prev,
                                    int topM, boolean useAllParents) {
    int n = newSet.length;

    // Collect candidate child origin permutations by inserting the new element into
    // top-M parent orderings of each (or one canonical) parent.
    java.util.Set<String> seenPerms = new java.util.HashSet<>(); // dedup by Arrays.toString
    java.util.List<int[]> candidates = new java.util.ArrayList<>();

    int dropStart = useAllParents ? 0 : 0; // always iterate from 0
    int dropEnd = useAllParents ? n : 1;    // but stop at 1 if single-parent

    for (int drop = dropStart; drop < dropEnd; drop++) {
        int[] parentSet = new int[n - 1];
        int[] parentMembers = new int[n - 1];
        int pi = 0;
        for (int i = 0; i < n; i++) {
            if (i == drop) continue;
            parentSet[pi] = newSet[i];
            parentMembers[pi] = i;
            pi++;
        }
        int[][] parentTop = prev.getTopM(parentSet, topM);
        if (parentTop == null) continue;

        for (int[] parentPerm : parentTop) {
            int[][] insertions = OrderingEnumerator.generateInsertions(
                    parentPerm, parentMembers, drop, n);
            for (int[] child : insertions) {
                String key = java.util.Arrays.toString(child);
                if (seenPerms.add(key)) {
                    candidates.add(child);
                }
            }
        }
    }

    stats.heuristicSetsHandled++;
    stats.heuristicInsertionsGenerated += candidates.size() + /*generated before dedup*/0;
    // (Track dedup delta in the loop above if you need the exact number.)

    // Route each candidate: reuse the same per-ordering routing logic the exact path
    // uses — extract it into a helper if needed. For each feasible result, track best Ride.
    Ride best = null;
    for (int[] origPerm : candidates) {
        // ... existing destination enumeration + routing + best-ride selection ...
        // (Factor out from existing enumerateOriginsPrunedWithEval.)
    }
    stats.heuristicInsertionsRouted += candidates.size();
    return best;
}
```

**Step 3.5: Verify it compiles**

Run: `mvn test-compile -q`

**Step 4: Regression — heuristic OFF must be identical**

Run: `mvn test -Dtest=ExMasKelheimE2ETest -q`
Expected: PASS (heuristic config default is disabled).

**Step 5: Commit**

```bash
cd matsim-libs && git add contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/RideExtender.java contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingEnumerator.java && git commit -m "feat: heuristic parent-insertion extension path (gated by config)"
```

---

### Task 17: Integration test with heuristic on

**Files:**
- Create: `matsim-libs/contribs/drt-demand-extraction/src/test/java/org/matsim/contrib/demand_extraction/algorithm/extension/HeuristicExtensionE2ETest.java` (or add @Test to existing E2E suite)

**Step 1: Test that enabling heuristic at degree 4 on a small scenario still produces rides**

Use whatever the existing minimal scenario is (hex grid or clustered grid from the project README). Set `heuristicOrderingFromDegree=4` and `heuristicOrderingTopM=3`, run extension, assert ride count ≥ 50% of the exact-path ride count (sanity only — we'll Pareto-tune later).

**Step 2: Run**

Run: `mvn test -Dtest=HeuristicExtensionE2ETest -q`
Expected: PASS.

**Step 3: Commit**

```bash
cd matsim-libs && git add contribs/drt-demand-extraction/src/test/java/org/matsim/contrib/demand_extraction/algorithm/extension/HeuristicExtensionE2ETest.java && git commit -m "test: heuristic extension E2E sanity check"
```

---

## Phase 5 — Benchmark sweep (Pareto curve)

### Task 18: Exact-path Bavaria benchmark

**Files:**
- Create: `docs/plans/2026-04-13-parent-ordering-cache-session-log.md`

**Step 1: Run benchmark**

Use the 10% Bavaria command with `--output-dir ../../../outputs/parent-cache-exact`, heuristic OFF.

**Step 2: Record in session log**

Add a table to the session log with per-degree time, rides, speedup vs `outputs/diag-run` baseline:

```
| Deg | Baseline (s) | Path E (s) | Speedup | Rides baseline | Rides E | Δ rides |
|-----|--------------|-----------|---------|----------------|---------|---------|
```

**Step 3: Commit**

```bash
cd /c/Users/VWAUCCY/dev/msf/projects/Dissertation && git add docs/plans/2026-04-13-parent-ordering-cache-session-log.md && git commit -m "docs: Path E 10% Bavaria benchmark results"
```

---

### Task 19: Heuristic sweep over top-*M*

**Files:**
- Modify: `docs/plans/2026-04-13-parent-ordering-cache-session-log.md`

**Step 1: Run benchmark for each `M ∈ {1, 2, 3, 5, 8}`**

Use the Bavaria command with `heuristicOrderingFromDegree=6` and `heuristicOrderingTopM=M` (pass via config XML overlay or constructor — the specific mechanism depends on how `RunBavaria30kmDemandExtraction` reads config). Output directories: `outputs/parent-cache-heuristic-m1`, `m2`, `m3`, `m5`, `m8`.

**Step 2: Record Pareto table in session log**

```
| M | Deg 6 time | Deg 6 rides | Δ rides vs exact | Deg 7 time | Deg 7 rides | Δ rides | Deg 8 time | Deg 8 rides | Δ rides | Total ext time | Overall ride loss |
|---|-----------|-------------|------------------|-----------|-------------|---------|-----------|-------------|---------|----------------|-------------------|
```

Plot a Pareto curve (speedup on x-axis, ride loss on y-axis) — use the existing matplotlib pipeline or a quick Python snippet. Save plot to `docs/plans/2026-04-13-heuristic-pareto.png` and reference from session log.

**Step 3: Pick sweet spot**

Based on the curve, recommend a default `heuristicOrderingTopM` (and possibly `heuristicOrderingFromDegree`) for the 100% Bavaria run. Document the recommendation in the session log.

**Step 4: Commit**

```bash
cd /c/Users/VWAUCCY/dev/msf/projects/Dissertation && git add docs/plans/2026-04-13-parent-ordering-cache-session-log.md docs/plans/2026-04-13-heuristic-pareto.png && git commit -m "docs: Path H top-M sweep + Pareto curve"
```

---

## Phase 6 — Cleanup

### Task 20: Delete `SubSetOrderingFeasibility`

**Files:**
- Delete: `matsim-libs/contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/SubSetOrderingFeasibility.java`
- Delete: `matsim-libs/contribs/drt-demand-extraction/src/test/java/org/matsim/contrib/demand_extraction/algorithm/extension/SubSetOrderingFeasibilityTest.java`

**Prerequisite:** Path E benchmark (Task 18) shows ride-count match and speedup. Only then is it safe to drop the reference implementation.

**Step 1: Verify no references**

Use Grep tool: pattern `SubSetOrderingFeasibility`, path `matsim-libs/contribs/drt-demand-extraction/src/`.
Expected: only the files being deleted.

**Step 2: Delete + test**

```bash
cd matsim-libs && git rm contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/SubSetOrderingFeasibility.java contribs/drt-demand-extraction/src/test/java/org/matsim/contrib/demand_extraction/algorithm/extension/SubSetOrderingFeasibilityTest.java
cd contribs/drt-demand-extraction && mvn test -q
```

Expected: all tests pass.

**Step 3: Commit**

```bash
cd matsim-libs && git commit -m "refactor: delete SubSetOrderingFeasibility (replaced by ParentOrderingCache)"
```

---

### Task 21: Sync submodule pointer in parent repo

**Files:**
- Modify: `matsim-libs` submodule pointer in `/c/Users/VWAUCCY/dev/msf/projects/Dissertation`

**Step 1: Update pointer**

```bash
cd /c/Users/VWAUCCY/dev/msf/projects/Dissertation && git add matsim-libs && git commit -m "submodule: ParentOrderingCache + heuristic extension path"
```

**Step 2: Final end-to-end verification**

Run the Kelheim E2E test from the parent repo build if there's a top-level Maven aggregate, or re-run in the submodule:

```bash
cd matsim-libs/contribs/drt-demand-extraction && mvn test -q
```

Expected: all tests pass.

---

## Notes for the executing engineer

- **Never skip TDD discipline.** Write the failing test first, watch it fail, implement, watch it pass, commit. The unit tests in Tasks 1–4 and 14–15 exist specifically so the integration tasks (6–9, 16) have trusted primitives.
- **The existing extension code in `OrderingEnumerator.java` and `RideExtender.java` is non-trivial (789 + many LoC). Don't rewrite — only insert hooks.** If you find yourself wanting to refactor the enumeration loop to integrate the projection filter, stop and extract a helper method instead.
- **Commits are per-step, not per-feature.** Target ~20 commits for this plan. If a commit is more than ~100 lines excluding test fixtures, split it.
- **The two-cache rotation in Task 7 is the subtle correctness point.** If you accidentally clear the cache we're currently reading, the projection filter silently stops pruning. Log `previousCache.size()` at degree entry to catch this.
- **On any ride-count regression during Phase 2 (Tasks 7–10), STOP.** Path E is supposed to be sound. Ride loss means a bug, not a tuning knob. Debug with `ExMasKelheimE2ETest` (smallest reproducible scenario) before touching Bavaria.
- **Heuristic path is optional.** If the exact path alone exceeds the speedup target, the heuristic phase becomes a nice-to-have, not a critical path.
- Use @superpowers:systematic-debugging if a regression appears.
- Use @superpowers:verification-before-completion before claiming any task complete.
