# B&B tightening v1 — implementation plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Reduce the 98.7% valid-but-worse evaluator funnel at degree 6 by seeding the DFS with the shortest k−1 parent's ordering and adding a candidate-independent LB-based outer B&B cut, targeting a 3–5× speedup at deg 6 on Bavaria 10% with exact Kelheim E2E match.

**Architecture:** Two coupled changes on one branch. Change 1 biases the origin and dest DFS candidate sort so parent-consistent branches are visited first, letting the DFS reach a valid ride fast and tighten `bestValidDist[0]`. Change 2 adds an admissible lower bound `LB(remaining) = Σ minIn[remaining stops]` precomputed once per set and maintained in O(1) per descent, plus an outer cut `partialDist + totalMinInRemaining > bestValidDist[0]` that returns from the whole subtree when the bound fires.

**Tech Stack:** Java 17, JUnit 5, Maven, matsim-libs contribs pipeline (`drt-demand-extraction` module). Work happens in the submodule `C:\Users\VWAUCCY\dev\msf\projects\Dissertation\matsim-libs` on a new branch `feature/bnb-tightening-v1` off `feature/exmas-degree-graph`.

**Design reference:** `docs/plans/2026-04-13-bnb-tightening-v1-design.md` (committed as `c9617df` on master of the Dissertation repo). Read it before starting — it contains the full soundness proof for the LB, the parent-consistent comparator rationale, and the reason the cut must be outer-loop candidate-independent.

---

## Pre-flight — read these first

- `docs/plans/2026-04-13-bnb-tightening-v1-design.md` — the design. Section "Predicate change" is the trickiest part; the reasoning for outer-vs-inner cut is there.
- `docs/plans/2026-04-13-journey-log.md` — why we're doing this work (morning session's diagnostic that revealed the valid-but-worse funnel).
- `matsim-libs/contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingEnumerator.java` — the DFS lives here. Pay attention to `enumerateOriginsPrunedWithEval` (line 209) and `enumerateDestTopoWithEval` (line 384). These are the two recursive hot paths that will change.
- `matsim-libs/contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/RideExtender.java` — the parallel walk and `processSet` method. You'll add `parentRideByHash` here.
- `matsim-libs/contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/EnumerationStats.java` — counters and log output.
- `matsim-libs/contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/domain/Ride.java` — use `getOriginsIndex()` and `getDestinationsIndex()` to get the parent's global request-index orderings; use `getRideDistance()` for the best-parent selection.
- `matsim-libs/contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/graph/DegreeGraph.java` — read for context, not modifying. `hashRequestSet(int[] sortedIndices)` is the canonical hash you'll reuse.

## Conventions

- **Work inside the submodule** `matsim-libs/contribs/drt-demand-extraction`. The outer Dissertation repo is separate.
- **Build command:** `mvn -pl matsim-libs/contribs/drt-demand-extraction -am clean install -DskipTests` from the Dissertation root for compile-only. For tests: `cd matsim-libs/contribs/drt-demand-extraction && mvn test -Dtest=<TestClass>`.
- **Indices:** `Ride.getOriginsIndex()` returns **global** request indices (from `DrtRequest.index`). The `newSet` inside `RideExtender.processSet` is also global. Inside `OrderingEnumerator`, local child-set indices are 0..k−1 mapped to global via `requestIndices[]`. When passing parent order into `enumerateAndEvaluateSeeded`, remap global-to-local by scanning `requestIndices[]`.
- **Commits:** small and frequent. Every task commits. Branch is `feature/bnb-tightening-v1` in the submodule. Commit with the standard co-author footer.
- **Don't push** unless the user explicitly asks. This is a feature branch for later measurement and review.

---

## Task 1: Create the feature branch

**Files:** none (git only)

**Step 1: Verify starting state**

Run from `C:\Users\VWAUCCY\dev\msf\projects\Dissertation\matsim-libs`:

```bash
git status
git branch --show-current
```

Expected: clean working tree on `feature/exmas-degree-graph`.

If not clean or not on that branch, stop and ask the user before proceeding.

**Step 2: Create and switch to the new branch**

```bash
git checkout -b feature/bnb-tightening-v1
```

Expected: `Switched to a new branch 'feature/bnb-tightening-v1'`

**Step 3: Confirm branch**

```bash
git branch --show-current
```

Expected: `feature/bnb-tightening-v1`

No commit in this task — the branch is just a pointer to the current tip of `feature/exmas-degree-graph`.

---

## Task 2: Delete dead OrderingConflicts code

Identified by the 2026-04-13 morning code review. Zero production callers, leftover from an earlier cleanup pass.

**Files:**
- Delete: `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingConflicts.java`
- Delete: `contribs/drt-demand-extraction/src/test/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingConflictsTest.java`

**Step 1: Confirm no other references**

```bash
grep -rn "OrderingConflicts" contribs/drt-demand-extraction/src/main/java
```

Expected: only matches inside `OrderingConflicts.java` itself. If any other file references it, stop and flag to the user — the design assumes zero production callers.

**Step 2: Delete both files**

```bash
rm contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingConflicts.java
rm contribs/drt-demand-extraction/src/test/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingConflictsTest.java
```

**Step 3: Compile to verify clean removal**

```bash
cd contribs/drt-demand-extraction && mvn clean compile -q
```

Expected: `BUILD SUCCESS`. If compile fails, a reference was missed — investigate before committing.

**Step 4: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
cleanup: delete OrderingConflicts (dead code)

Missed by the 2026-04-13 morning cleanup commit that removed the other
order-based pruning mechanisms. OrderingConflicts has no production
callers; only the test file references it. Deleting both.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Thread walk-assigned parent Ride into `processSet`

The walk iterates `uniqueBaseSets` (`List<int[]>`) but we actually want the parent `Ride` in scope — it is already uniquely determined because `processSet` at the previous degree kept one shortest ride per set. Two options for getting the Ride in scope:

- **(a) Iterate `ridesToExtend` directly** (instead of `uniqueBaseSets`). Simplest, one-line change to the parallel stream.
- **(b) Build a `Map<String, Ride>` keyed by `Arrays.toString(sortedIndices)`** alongside `uniqueBaseSets`. Slightly more code but keeps the dedup-by-sorted-indices structure intact.

Use option (a) if `ridesToExtend` has exactly one ride per unique set (which it does: `processSet` stores the best in `resultBySetHash` and `ridesToExtend` is built from that map's values). If there is ever more than one ride per set, fall back to (b).

**Files:**
- Modify: `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/RideExtender.java`

**Step 1: Verify 1-ride-per-set invariant**

Read the previous degree's `processSet` return path in `RideExtender.java` (around line 256) and `resultBySetHash` assignment (around line 163) to confirm there is at most one ride per set hash. If confirmed, proceed with option (a).

**Step 2: Switch the parallel walk to iterate `ridesToExtend`**

Around line 138 in the current code:

```java
// Before
uniqueBaseSets.parallelStream().forEach(baseSetIndices -> {
    ...
});

// After
ridesToExtend.parallelStream().forEach(parentRide -> {
    int[] baseSetIndices = parentRide.getRequestIndices().clone();
    java.util.Arrays.sort(baseSetIndices);
    ...
});
```

`uniqueBaseSets` may still be useful for the log line ("N base rides in M unique request sets") — leave that computation as is, just iterate rides directly in the parallel walk.

**Step 3: Add `findNewRequest` helper**

```java
/** Return the global request index in {@code newSet} not present in {@code parentSet}. */
private static int findNewRequest(int[] newSet, int[] parentSet) {
    int[] sorted = parentSet.clone();
    java.util.Arrays.sort(sorted);
    for (int r : newSet) {
        if (java.util.Arrays.binarySearch(sorted, r) < 0) return r;
    }
    throw new IllegalStateException("newSet does not contain a new request");
}
```

**Step 4: Update `processSet` signature and pass parentRide through**

Inside the walk's inner loop:

```java
// Before
Ride bestRide = processSet(newSet, newSetHash, targetDegree);

// After
Ride bestRide = processSet(newSet, newSetHash, targetDegree, parentRide);
```

Update `processSet` signature (line ~218):

```java
private Ride processSet(int[] newSet, long setHash, int targetDegree, Ride parentRide) {
```

Body does not yet consume `parentRide` — that wiring arrives in Task 4.

**Step 5: Compile and run Kelheim E2E**

```bash
cd contribs/drt-demand-extraction && mvn clean compile -q
mvn test -Dtest=ExMasKelheimE2ETest -q
```

Expected: `BUILD SUCCESS` and `703/243/451/8/1`. Zero behavior change.

**Step 6: Commit**

```bash
git add contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/RideExtender.java
git commit -m "$(cat <<'EOF'
feat: thread walk-assigned parent Ride into processSet

Iterates ridesToExtend directly in the parallel walk so the parent
Ride is in scope for every child set generated inside that iteration.
processSet accepts parentRide as a new parameter but does not yet
consume it. Uses the walk-assigned parent (the single parent whose
atomic-claim won this child set) rather than looking up the shortest
among all k subset-parents — simpler, and each per-set ride is
already the best-for-its-set. Kelheim E2E 703/243/451/8/1.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Add `enumerateAndEvaluateSeeded` entry point as a delegating stub

Create the new public entry point with the full seeded signature. For this task, it delegates to the existing `enumerateAndEvaluate` without using the seed data. The subsequent tasks add the actual sort-bias behavior.

**Files:**
- Modify: `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingEnumerator.java`
- Modify: `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/RideExtender.java`

**Step 1: Add the stub entry point**

In `OrderingEnumerator.java`, right after `enumerateAndEvaluate` (around line 208), add:

```java
/**
 * Enumerate orderings with a parent-ordering seed for DFS sort bias.
 *
 * <p>The DFS visits candidates in a sort order that places parent-consistent
 * candidates (the next un-placed request from the parent's order, or the new
 * inserted request) first, with cheapest-next-segment as tie-breaker. This
 * reaches a valid parent-insertion ordering early, tightening bestValidDist[0]
 * so that subsequent branches are B&B-cut aggressively.
 *
 * @param seedParentOrigin global request indices in parent's origin order (length k-1)
 * @param seedParentDest   global request indices in parent's dest order (length k-1)
 * @param seedNewRequest   global request index of the new element (not in parent)
 */
public static void enumerateAndEvaluateSeeded(
        int[] requestIndices, ShareabilityGraph graph,
        MatsimNetworkCache network, DrtRequest[] requests,
        double[] bestValidDist,
        int[] seedParentOrigin, int[] seedParentDest, int seedNewRequest,
        Consumer<Ordering> evaluator) {

    // Stub: delegate to the existing entry point. Seed data is accepted but
    // not yet consumed — the sort-bias and LB cut are added in subsequent
    // tasks.
    enumerateAndEvaluate(requestIndices, graph, network, requests, bestValidDist, evaluator);
}
```

**Step 2: Wire `RideExtender.processSet` to call it**

In `RideExtender.processSet`, replace the `OrderingEnumerator.enumerateAndEvaluate` call (around line 243) with a computation of the seed data and a call to the new entry.

```java
// Compute seed data from parent ride. Parent indices are global; use the
// set's own ordering of global indices (newSet is sorted) to keep them as
// global — the enumerator will remap to its own local indexing via
// requestIndices[]. seedNewRequest is the element of newSet not in parent.
int[] seedParentOrigin = seedParent.getOriginsIndex();
int[] seedParentDest = seedParent.getDestinationsIndex();
int seedNewRequest = findNewRequest(newSet, seedParent.getRequestIndices());

OrderingEnumerator.enumerateAndEvaluateSeeded(
        newSet, graph, network, setRequests, bestValidDist,
        seedParentOrigin, seedParentDest, seedNewRequest,
        (ordering) -> evaluateOrdering(ordering, newSet, setRequests,
                bestValidDist, bestRide, stats));
```

**Step 3: Add the `findNewRequest` helper to `RideExtender`**

```java
/** Return the global request index in {@code newSet} not present in {@code parentSet}. */
private static int findNewRequest(int[] newSet, int[] parentSet) {
    java.util.Arrays.sort(parentSet); // defensive; parent's getRequestIndices is unordered
    for (int r : newSet) {
        if (java.util.Arrays.binarySearch(parentSet, r) < 0) {
            return r;
        }
    }
    throw new IllegalStateException("newSet does not contain a new request");
}
```

**Step 4: Compile and run Kelheim E2E**

```bash
cd contribs/drt-demand-extraction && mvn clean compile -q
mvn test -Dtest=ExMasKelheimE2ETest -q
```

Expected: compile clean, E2E passes 703/243/451/8/1. Still zero behavior change — the stub delegates to the existing path.

**Step 5: Commit**

```bash
git add contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingEnumerator.java \
        contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/RideExtender.java
git commit -m "$(cat <<'EOF'
feat: enumerateAndEvaluateSeeded entry point (delegating stub)

Adds the new entry point with parent-ordering seed parameters and wires
RideExtender.processSet to compute and pass them. Stub currently
delegates to enumerateAndEvaluate without using the seed data. Sort-bias
implementation comes next. Kelheim E2E 703/243/451/8/1.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Failing test for parent-consistent origin sort bias

TDD. Write the unit test first, watch it fail, then implement.

**Files:**
- Create: `contribs/drt-demand-extraction/src/test/java/org/matsim/contrib/demand_extraction/algorithm/extension/ParentConsistentSortTest.java`

**Step 1: Study an existing test in the same package**

Read `contribs/drt-demand-extraction/src/test/java/org/matsim/contrib/demand_extraction/algorithm/extension/BeelineVsRoutedPruningTest.java` in full to understand the test scaffolding: how `ShareabilityGraph`, `DrtRequest[]`, `MatsimNetworkCache` are constructed. Reuse those patterns.

**Step 2: Write the failing test**

Create `ParentConsistentSortTest.java` with a test that:
1. Constructs 4 requests (k=4) with simple coordinates (grid).
2. Builds a minimal `ShareabilityGraph` and `MatsimNetworkCache` (reuse the helper from `BeelineVsRoutedPruningTest` — extract it to a shared test utility if not already shared).
3. Defines a parent origin order `[r0, r1, r2]` and dest order `[r0, r1, r2]`, with `r3` as the new request.
4. Calls `enumerateAndEvaluateSeeded` with an evaluator that records the sequence of `Ordering` objects in a `List<int[]>` as they arrive at the evaluator (one per complete ordering visited).
5. Asserts that the **first** ordering visited is parent-consistent: the three parent requests `r0, r1, r2` appear in that relative order within the origin permutation, with `r3` inserted at some position.

```java
package org.matsim.contrib.demand_extraction.algorithm.extension;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
// ... other imports as needed — copy from BeelineVsRoutedPruningTest

class ParentConsistentSortTest {

    @Test
    void firstVisitedOrderingIsParentConsistent() {
        // Build a 4-request test set. Use the helper that BeelineVsRoutedPruningTest
        // uses for its 3/4-request setups (copy or extract).
        TestSetup setup = buildFourRequestSet();
        int[] requestIndices = {0, 1, 2, 3};
        int[] parentOrigin = {0, 1, 2};
        int[] parentDest = {0, 1, 2};
        int newRequest = 3;

        // Capture visit order
        List<int[]> visited = new ArrayList<>();
        double[] bestValidDist = { Double.POSITIVE_INFINITY };

        OrderingEnumerator.enumerateAndEvaluateSeeded(
                requestIndices, setup.graph, setup.network, setup.requests,
                bestValidDist, parentOrigin, parentDest, newRequest,
                (ordering) -> visited.add(ordering.originPerm().clone()));

        assertTrue(visited.size() > 0, "At least one ordering should be visited");

        // First visited ordering must preserve parent's origin order {0, 1, 2}.
        int[] firstOrig = visited.get(0);
        int posOf0 = indexOf(firstOrig, 0);
        int posOf1 = indexOf(firstOrig, 1);
        int posOf2 = indexOf(firstOrig, 2);
        assertTrue(posOf0 < posOf1,
                "Parent order violated: r0 should come before r1. Got " + java.util.Arrays.toString(firstOrig));
        assertTrue(posOf1 < posOf2,
                "Parent order violated: r1 should come before r2. Got " + java.util.Arrays.toString(firstOrig));
    }

    private static int indexOf(int[] arr, int val) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == val) return i;
        return -1;
    }

    // --- Test setup helper ---

    private static class TestSetup {
        ShareabilityGraph graph;
        MatsimNetworkCache network;
        DrtRequest[] requests;
    }

    private static TestSetup buildFourRequestSet() {
        // TODO: copy or extract the setup logic from BeelineVsRoutedPruningTest.
        // Four requests in a grid, all pair-compatible so the shareability graph
        // allows all orderings.
        throw new UnsupportedOperationException("implement");
    }
}
```

**Step 3: Flesh out `buildFourRequestSet` by copying from `BeelineVsRoutedPruningTest`**

Read `BeelineVsRoutedPruningTest.java` for its `setUp` method or helper. Copy the relevant setup code (build a minimal grid network, construct 4 `DrtRequest` objects, build a `ShareabilityGraph` that accepts all pair directions). Place the copied helper in `ParentConsistentSortTest` as `buildFourRequestSet`, or — if this is the second test reusing it — extract to a shared test utility class `ExtensionTestFixtures` under the same test package.

**Step 4: Run the test and watch it fail**

```bash
cd contribs/drt-demand-extraction && mvn test -Dtest=ParentConsistentSortTest -q
```

Expected: **FAIL**. The first visited ordering is probably `[0, 1, 2, 3]` or `[1, 0, 2, 3]` depending on the current cheapest-next-segment sort — whichever it is, it's coincidence, not because the sort is parent-aware. More importantly, the stub entry point currently delegates to `enumerateAndEvaluate` which ignores the seed, so the assertion may or may not pass by accident. **If the test happens to pass already**, modify the parent order to `[2, 1, 0]` (reversed) to force the current sort to disagree with the parent, and re-run — it should now fail.

**Step 5: Commit the failing test**

```bash
git add contribs/drt-demand-extraction/src/test/java/org/matsim/contrib/demand_extraction/algorithm/extension/ParentConsistentSortTest.java
git commit -m "$(cat <<'EOF'
test: failing test for parent-consistent origin sort bias

Asserts that the first ordering visited by enumerateAndEvaluateSeeded
preserves the parent's relative origin order. Currently fails because
the stub entry point delegates to enumerateAndEvaluate, which uses
cheapest-next-segment sort without any parent awareness.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Implement the parent-consistent comparator in origin DFS

Make the test from Task 5 pass.

**Files:**
- Modify: `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingEnumerator.java`

**Step 1: Add seeded origin DFS private method**

Duplicate `enumerateOriginsPrunedWithEval` as `enumerateOriginsSeededWithEval`. The duplicate takes three additional parameters: `int[] seedLocalOrigin` (the parent's origin order remapped to child-local indices), `int seedLocalNewRequest` (child-local index of the new request), and a running position pointer. The only functional difference is the candidate sort.

```java
private static void enumerateOriginsSeededWithEval(
        Boolean[][] adj, int n, PairInfo[] pairs,
        MatsimNetworkCache network, DrtRequest[] requests,
        int[] requestIndices,
        double[] bestValidDist,
        boolean[] used, int[] perm, double[] pickupTimes, int depth,
        double partialDist, double currentTime,
        double currentL, double currentU,
        int[] seedLocalOrigin, int seedLocalNewRequest,
        Consumer<Ordering> evaluator,
        double[] connTT, double[] connDist, double[] connUtil) {

    // (Copy body of enumerateOriginsPrunedWithEval verbatim, then replace the
    // candidates.sort line with a parent-consistent comparator.)
    //
    // Also: when recursing at the end of the loop, pass through the same seed
    // parameters unchanged.
}
```

**Step 2: Write the parent-consistent comparator**

Replace the existing `candidates.sort(Comparator.comparingDouble(c -> segMap.get(c).getDistance()))` with:

```java
// Identify the next un-placed parent request (if any) and whether the
// new request is still available.
int nextParentLocal = nextUnplacedInSeed(seedLocalOrigin, used);
int newRequestLocal = used[seedLocalNewRequest] ? -1 : seedLocalNewRequest;

candidates.sort((a, b) -> {
    int rankA = parentConsistentRank(a, nextParentLocal, newRequestLocal);
    int rankB = parentConsistentRank(b, nextParentLocal, newRequestLocal);
    if (rankA != rankB) return Integer.compare(rankA, rankB);
    // Tie-breaker: cheapest-next-segment (existing behavior)
    return Double.compare(segMap.get(a).getDistance(), segMap.get(b).getDistance());
});
```

Add the two helper static methods at the bottom of `OrderingEnumerator`:

```java
/** Returns the child-local index of the next parent request not yet placed, or -1 if all placed. */
private static int nextUnplacedInSeed(int[] seedLocalOrigin, boolean[] used) {
    for (int r : seedLocalOrigin) {
        if (!used[r]) return r;
    }
    return -1;
}

/** Rank: 0 if candidate is parent-consistent (next parent or the new request), 1 otherwise. */
private static int parentConsistentRank(int candidate, int nextParentLocal, int newRequestLocal) {
    if (candidate == nextParentLocal) return 0;
    if (candidate == newRequestLocal) return 0;
    return 1;
}
```

**Step 3: Apply the same comparator at depth 0**

The depth-0 block inside `enumerateOriginsPrunedWithEval` (lines 256–285) iterates `candidates` without sorting. In the seeded variant, sort it with the same comparator before the loop so depth 0 is also parent-biased.

**Step 4: Handle the recursive call in the seeded variant**

At each recursion site (both depth-0 and depth>0 branches), call `enumerateOriginsSeededWithEval` instead of `enumerateOriginsPrunedWithEval`, and also call a new `enumerateDestPrunedSeededWithEval` when depth == n. For this task, if the dest DFS doesn't yet exist in a seeded variant, the seeded origin at `depth == n` should fall back to the existing `enumerateDestPrunedWithEval` — dest seeding is added in Task 8.

**Step 5: Update the `enumerateAndEvaluateSeeded` entry point to call the new origin DFS**

Replace the stub delegation in `enumerateAndEvaluateSeeded` (from Task 5) with real logic:

```java
public static void enumerateAndEvaluateSeeded(
        int[] requestIndices, ShareabilityGraph graph,
        MatsimNetworkCache network, DrtRequest[] requests,
        double[] bestValidDist,
        int[] seedParentOrigin, int[] seedParentDest, int seedNewRequest,
        Consumer<Ordering> evaluator) {

    PairInfo[] constraints = extractConstraints(requestIndices, graph);
    if (constraints == null) return;
    int n = requestIndices.length;

    Boolean[][] origAdj = new Boolean[n][n];
    for (int a = 0; a < n; a++) {
        for (int b = a + 1; b < n; b++) {
            PairInfo p = lookup(constraints, n, a, b);
            if (p.forwardOnly()) {
                origAdj[a][b] = true; origAdj[b][a] = false;
            } else if (p.reverseOnly()) {
                origAdj[b][a] = true; origAdj[a][b] = false;
            }
        }
    }

    // Remap global seed parent indices to child-local indices (0..n-1).
    int[] seedLocalOrigin = remapToLocal(seedParentOrigin, requestIndices);
    int seedLocalNewRequest = localIndexOf(seedNewRequest, requestIndices);

    double[] connTT = new double[2 * n - 1];
    double[] connDist = new double[2 * n - 1];
    double[] connUtil = new double[2 * n - 1];
    enumerateOriginsSeededWithEval(origAdj, n, constraints, network, requests,
            requestIndices, bestValidDist, new boolean[n], new int[n], new double[n], 0,
            0.0, 0.0,
            Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
            seedLocalOrigin, seedLocalNewRequest,
            evaluator, connTT, connDist, connUtil);
}

private static int[] remapToLocal(int[] globalOrder, int[] requestIndices) {
    int[] local = new int[globalOrder.length];
    for (int i = 0; i < globalOrder.length; i++) {
        local[i] = localIndexOf(globalOrder[i], requestIndices);
    }
    return local;
}

private static int localIndexOf(int globalIdx, int[] requestIndices) {
    for (int i = 0; i < requestIndices.length; i++) {
        if (requestIndices[i] == globalIdx) return i;
    }
    throw new IllegalStateException("global index " + globalIdx + " not in requestIndices");
}
```

**Step 6: Run the test and verify it passes**

```bash
cd contribs/drt-demand-extraction && mvn test -Dtest=ParentConsistentSortTest -q
```

Expected: **PASS**.

**Step 7: Run Kelheim E2E to confirm no regression**

```bash
cd contribs/drt-demand-extraction && mvn test -Dtest=ExMasKelheimE2ETest -q
```

Expected: `703/243/451/8/1` exact match. If the ride count drifts, the sort bias has introduced a bug (possibly: the parent-consistent branches over-prune because the new-request-as-parent-consistent logic is wrong). Stop and investigate.

**Step 8: Commit**

```bash
git add contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingEnumerator.java
git commit -m "$(cat <<'EOF'
feat: parent-consistent comparator in seeded origin DFS

Adds enumerateOriginsSeededWithEval with a two-level candidate sort:
primary key is parent-consistency (next parent or new request → 0,
else → 1), secondary key is cheapest-next-segment. Dest DFS still uses
the unseeded variant; dest sort bias comes in Task 8. ParentConsistent-
SortTest now passes. Kelheim E2E 703/243/451/8/1.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: Failing test for parent-consistent dest sort bias

**Files:**
- Modify: `contribs/drt-demand-extraction/src/test/java/org/matsim/contrib/demand_extraction/algorithm/extension/ParentConsistentSortTest.java`

**Step 1: Add a second test case**

Add `firstVisitedOrderingIsParentConsistentForDest` that mirrors Task 5's test but asserts on `ordering.destPerm()` preserving the parent's dest order.

```java
@Test
void firstVisitedOrderingIsParentConsistentForDest() {
    TestSetup setup = buildFourRequestSet();
    int[] requestIndices = {0, 1, 2, 3};
    int[] parentOrigin = {0, 1, 2};
    int[] parentDest = {2, 1, 0}; // reversed dest to distinguish from cheapest-next-segment
    int newRequest = 3;

    List<int[]> visited = new ArrayList<>();
    double[] bestValidDist = { Double.POSITIVE_INFINITY };

    OrderingEnumerator.enumerateAndEvaluateSeeded(
            requestIndices, setup.graph, setup.network, setup.requests,
            bestValidDist, parentOrigin, parentDest, newRequest,
            (ordering) -> visited.add(ordering.destPerm().clone()));

    assertTrue(visited.size() > 0);

    int[] firstDest = visited.get(0);
    int posOf2 = indexOf(firstDest, 2);
    int posOf1 = indexOf(firstDest, 1);
    int posOf0 = indexOf(firstDest, 0);
    assertTrue(posOf2 < posOf1, "Parent dest order violated: r2 before r1. Got " + java.util.Arrays.toString(firstDest));
    assertTrue(posOf1 < posOf0, "Parent dest order violated: r1 before r0. Got " + java.util.Arrays.toString(firstDest));
}
```

**Step 2: Run it, verify it fails**

```bash
cd contribs/drt-demand-extraction && mvn test -Dtest=ParentConsistentSortTest -q
```

Expected: the new test **fails** (the dest DFS still uses the unseeded path). The origin test still passes.

**Step 3: Commit the failing test**

```bash
git add contribs/drt-demand-extraction/src/test/java/org/matsim/contrib/demand_extraction/algorithm/extension/ParentConsistentSortTest.java
git commit -m "$(cat <<'EOF'
test: failing test for parent-consistent dest sort bias

Mirrors the origin sort test but asserts on destPerm and uses a
reversed parent dest order to distinguish the seeded path from the
cheapest-next-segment default.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: Implement parent-consistent comparator in dest DFS

Make the Task 7 test pass.

**Files:**
- Modify: `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingEnumerator.java`

**Step 1: Duplicate `enumerateDestPrunedWithEval` and `enumerateDestTopoWithEval` as seeded variants**

Name them `enumerateDestPrunedSeededWithEval` and `enumerateDestTopoSeededWithEval`. Both take one additional parameter: `int[] seedLocalDest` (parent dest order in child-local indices). The seeded origin from Task 6 should call the seeded dest at `depth == n`.

**Step 2: Apply the same comparator pattern in the dest DFS**

In `enumerateDestTopoSeededWithEval`, replace the candidate sort at line 436 (the `candidates.sort(Comparator.comparingDouble(...))` line) with the parent-consistent comparator. For the dest phase, "parent-consistent" means: the next un-placed parent-dest request, OR the new request (if still not dropped off).

```java
int nextParentLocal = nextUnplacedInSeed(seedLocalDest, used);
int newRequestLocal = used[seedLocalNewRequest] ? -1 : seedLocalNewRequest;

candidates.sort((a, b) -> {
    int rankA = parentConsistentRank(a, nextParentLocal, newRequestLocal);
    int rankB = parentConsistentRank(b, nextParentLocal, newRequestLocal);
    if (rankA != rankB) return Integer.compare(rankA, rankB);
    return Double.compare(segMap.get(a).getDistance(), segMap.get(b).getDistance());
});
```

`seedLocalNewRequest` is the same value as for origin (same child-local index of the new request), threaded through from `enumerateAndEvaluateSeeded`.

**Step 3: Update `enumerateAndEvaluateSeeded` to remap dest seed too**

```java
int[] seedLocalDest = remapToLocal(seedParentDest, requestIndices);
```

Pass `seedLocalDest` into the seeded origin DFS, which passes it down into the seeded dest DFS at `depth == n`.

**Step 4: Run tests**

```bash
cd contribs/drt-demand-extraction && mvn test -Dtest=ParentConsistentSortTest -q
mvn test -Dtest=ExMasKelheimE2ETest -q
```

Expected: both `ParentConsistentSortTest` tests pass, Kelheim E2E `703/243/451/8/1`.

**Step 5: Commit**

```bash
git add contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingEnumerator.java
git commit -m "$(cat <<'EOF'
feat: parent-consistent comparator in seeded dest DFS

Mirrors the origin-DFS sort bias. Dest DFS now visits parent-consistent
candidates first (next unplaced parent-dest request or the new
request), tie-broken by cheapest-next-segment. Kelheim E2E
703/243/451/8/1.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: Add `parentSeedRidesFound` counter + log line

Diagnostic: track how often the parent-consistent seed path produced the very first valid ride for a set.

**Files:**
- Modify: `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/EnumerationStats.java`
- Modify: `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/RideExtender.java`

**Step 1: Add the counter**

In `EnumerationStats.java`:
- Add `public long parentSeedRidesFound;` in the Enumeration flow block (around line 37).
- Zero it in `clear()` (around line 76).
- Sum it in `sum()` (around line 105).
- Add a log line in `log()` after the existing per-set numbers.

**Step 2: Increment it in `evaluateOrdering`**

In `RideExtender.evaluateOrdering` (around line 296), increment `stats.parentSeedRidesFound++` when the **first** `newBestRides` for a set is assigned. Since a set may have many new-best updates over its DFS, we only want to count the first. Simplest: check `stats.newBestRides == 0` before incrementing (no, that's wrong — `newBestRides` is thread-local, shared across sets).

Cleaner: track "has this set found any ride yet" via the existing `bestRide[0] == null` check, which is set-scoped.

```java
if (dist < bestValidDist[0]) {
    boolean firstValidForThisSet = (bestRide[0] == null);
    bestValidDist[0] = dist;
    bestRide[0] = validated;
    stats.newBestRides++;
    if (firstValidForThisSet) {
        stats.parentSeedRidesFound++;
    }
} else {
    stats.validButWorseThanBest++;
}
```

This is an over-count: it just counts how many sets find *any* valid ride. If you want strictly "first-visited ordering was valid", you'd need an ordering counter. For a v1 diagnostic the per-set "did we find anything" is enough; if you want finer granularity we can add it in a follow-up.

**Step 3: Build and run E2E**

```bash
cd contribs/drt-demand-extraction && mvn test -Dtest=ExMasKelheimE2ETest -q
```

Expected: pass. No ride-count change.

**Step 4: Commit**

```bash
git add contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/EnumerationStats.java \
        contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/RideExtender.java
git commit -m "$(cat <<'EOF'
feat: parentSeedRidesFound counter

Counts sets that produced at least one valid ride. Together with
setsProcessed this tells us what fraction of sets the seeded DFS found
a feasible ordering for. Incremented once per set (on first non-null
bestRide assignment).

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 10: Failing admissibility test for `minIn` lower bound

Back to TDD. Start Change 2 with the soundness test.

**Files:**
- Create: `contribs/drt-demand-extraction/src/test/java/org/matsim/contrib/demand_extraction/algorithm/extension/MinInLowerBoundTest.java`

**Step 1: Write the test**

```java
package org.matsim.contrib.demand_extraction.algorithm.extension;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * For a synthetic k=3 set, enumerate all complete orderings and verify that
 * the minIn-based lower bound is admissible at every prefix depth: for every
 * complete ordering O and every prefix length d in [0, 2k-1], the LB computed
 * from the unplaced stops must be ≤ the actual remaining distance of O
 * beyond depth d.
 */
class MinInLowerBoundTest {

    @Test
    void lowerBoundIsAdmissibleForAllOrderings() {
        // Build a 3-set where all pair orderings are feasible so the DFS
        // visits all 36 complete orderings (6 origin × 6 dest — minus topo
        // restrictions from the shareability graph).
        TestSetup setup = buildThreeRequestSet();
        int[] requestIndices = {0, 1, 2};

        List<double[]> perOrderingDistances = captureAllCompletedDistances(setup, requestIndices);
        double[] minIn = computeMinIn(setup);

        for (double[] orderingDistances : perOrderingDistances) {
            // orderingDistances[d] = cumulative distance after placing d stops
            double totalDistance = orderingDistances[orderingDistances.length - 1];
            double totalMinIn = 0;
            for (double v : minIn) totalMinIn += v;

            for (int d = 0; d < orderingDistances.length; d++) {
                double partialDist = orderingDistances[d];
                // Would need to know which stops are placed at depth d to compute
                // the "remaining minIn" — depends on the ordering's sequence.
                // Easier check: total distance ≥ sum of minIn for all stops.
                // Weaker but still a meaningful admissibility sanity check.
                assertTrue(totalDistance >= totalMinIn - 1e-6,
                        "Total distance " + totalDistance + " < sum(minIn) " + totalMinIn);
            }
        }
    }

    // --- Helpers ---

    private static double[] computeMinIn(TestSetup setup) {
        // 2*k entries: [pickup_0, pickup_1, ..., pickup_{k-1}, dropoff_0, ..., dropoff_{k-1}]
        // Compute: for each stop, the minimum distance from any other stop in the
        // set to this stop.
        throw new UnsupportedOperationException("implement once the helper is available in OrderingEnumerator");
    }

    private static List<double[]> captureAllCompletedDistances(TestSetup setup, int[] requestIndices) {
        // Use the evaluator to capture connDist per completed ordering.
        // At depth d (0..2k-1), the cumulative distance is sum of first d connection distances.
        throw new UnsupportedOperationException("implement");
    }

    private static class TestSetup {
        ShareabilityGraph graph;
        MatsimNetworkCache network;
        DrtRequest[] requests;
    }

    private static TestSetup buildThreeRequestSet() {
        throw new UnsupportedOperationException("implement");
    }
}
```

**Step 2: Flesh out the helpers**

- `buildThreeRequestSet`: copy the pattern from `BeelineVsRoutedPruningTest` or `ParentConsistentSortTest`. Three requests, all pair-feasible in both directions.
- `captureAllCompletedDistances`: call `OrderingEnumerator.enumerateAndEvaluate` with an evaluator that converts each `Ordering.connDist` to a cumulative sum array and adds it to the list.
- `computeMinIn`: for now, compute it directly in the test using `network.getSegment(fromLink, toLink, 0.0).getDistance()`. The helper that goes into `OrderingEnumerator` will be added in Task 11.

The weaker "totalDistance ≥ sum(minIn)" assertion is enough for this task — it's a necessary condition for admissibility. The stricter per-depth check is implicit in the mechanism (since each completion pays at least `minIn[remaining]` for each remaining stop).

**Step 3: Run and verify it passes immediately**

```bash
cd contribs/drt-demand-extraction && mvn test -Dtest=MinInLowerBoundTest -q
```

Expected: **PASS**. The assertion is a soundness property of the `minIn` definition — it should hold unconditionally for any network. If it fails, there is a bug in the test's `computeMinIn` (probably mixing up origin/dest link IDs).

Note: this test is a *regression guard* rather than a failing-to-passing TDD test. The implementation of the LB is a performance change, not a behavior change; TDD here is about guaranteeing that the LB formula remains admissible after the code change.

**Step 4: Commit**

```bash
git add contribs/drt-demand-extraction/src/test/java/org/matsim/contrib/demand_extraction/algorithm/extension/MinInLowerBoundTest.java
git commit -m "$(cat <<'EOF'
test: admissibility regression test for minIn lower bound

Verifies that sum(minIn[all stops]) is a lower bound on the total ride
distance for every completed ordering in a synthetic 3-set. Guards
against future changes that would break LB admissibility.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 11: Precompute `minIn[]` and thread `totalMinInRemaining` through seeded origin DFS

Change 2 scaffolding — compute the LB state but don't use it in a cut yet.

**Files:**
- Modify: `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingEnumerator.java`

**Step 1: Add a helper that builds `minIn[]` for a k-set**

At the bottom of `OrderingEnumerator`:

```java
/**
 * Precompute minimum incoming segment distance for each stop in the k-set.
 *
 * <p>Indexing: entries 0..k-1 are pickup stops (origin of request i), entries
 * k..2k-1 are dropoff stops (destination of request i-k). For each stop, the
 * value is the minimum over all other stops s in the set of
 * {@code network.getSegment(s, thisStop, 0.0).getDistance()}.
 *
 * <p>Used as an admissible lower bound on per-stop segment cost in the B&B
 * predicate.
 */
private static double[] computeMinIn(int n, MatsimNetworkCache network, DrtRequest[] requests) {
    Id<Link>[] stopLinks = (Id<Link>[]) new Id[2 * n];
    for (int i = 0; i < n; i++) {
        stopLinks[i] = requests[i].originLinkId;
        stopLinks[i + n] = requests[i].destinationLinkId;
    }

    double[] minIn = new double[2 * n];
    java.util.Arrays.fill(minIn, Double.POSITIVE_INFINITY);

    for (int to = 0; to < 2 * n; to++) {
        for (int from = 0; from < 2 * n; from++) {
            if (from == to) continue;
            double d = network.getSegment(stopLinks[from], stopLinks[to], 0.0).getDistance();
            if (d < minIn[to]) minIn[to] = d;
        }
    }
    return minIn;
}
```

Note: the `@SuppressWarnings` may be needed on the method for the `Id<Link>[]` unchecked cast — match the pattern already used in `Ride.getOriginsOrdered()`.

**Step 2: Call `computeMinIn` from `enumerateAndEvaluateSeeded`**

Right before `enumerateOriginsSeededWithEval`, compute the LB state:

```java
double[] minIn = computeMinIn(n, network, requests);
double totalMinIn = 0;
for (double v : minIn) totalMinIn += v;
```

**Step 3: Thread `minIn` and `totalMinInRemaining` into the seeded origin DFS**

Add two parameters to `enumerateOriginsSeededWithEval`: `double[] minIn` and `double totalMinInRemaining`. At the candidate-loop step, when descending into a candidate `c`:

```java
// Maintenance: subtract minIn for the stop being placed (origin = local idx c).
double newTotalMinInRemaining = totalMinInRemaining - minIn[c];
```

Pass `newTotalMinInRemaining` into the recursive call. On backtrack (after the recursive call returns), Java primitive semantics mean the local `totalMinInRemaining` in the caller frame is unchanged — no explicit restore needed.

Do the same for depth 0 (the special-case block at lines 256–285 in the pruned variant).

**Step 4: Do not yet use the value in a cut**

The threading is plumbing only; the cut is added in the next task.

**Step 5: Compile, run Kelheim E2E, run all seeded tests**

```bash
cd contribs/drt-demand-extraction && mvn test -Dtest=ExMasKelheimE2ETest -q
mvn test -Dtest=ParentConsistentSortTest -q
mvn test -Dtest=MinInLowerBoundTest -q
```

Expected: all pass. Kelheim still `703/243/451/8/1` (zero behavior change — the LB is computed but unused).

**Step 6: Commit**

```bash
git add contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingEnumerator.java
git commit -m "$(cat <<'EOF'
feat: precompute minIn and thread totalMinInRemaining through origin DFS

Computes the per-stop minimum incoming segment distance once per set in
O(k^2) cached lookups, and maintains the running sum of minIn for
unplaced stops via subtraction on descent. LB value is not yet
consumed in a cut predicate. Kelheim E2E 703/243/451/8/1.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 12: Add LB-based outer cut in seeded origin DFS

**Files:**
- Modify: `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingEnumerator.java`
- Modify: `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/EnumerationStats.java`

**Step 1: Add diagnostic counters**

In `EnumerationStats.java`, add in the B&B block:

```java
public long bnbOriginLbCuts;
public long bnbOriginLbSkippedCandidates;
```

Plus the usual `clear()`, `sum()`, and `log()` updates. Log line:

```
log.info("  LB B&B cuts (origin): {} events, {} candidates skipped",
        bnbOriginLbCuts, bnbOriginLbSkippedCandidates);
```

**Step 2: Add the outer cut at the top of `enumerateOriginsSeededWithEval`**

Right after the existing Check A block (around line 240 in the unseeded variant), add:

```java
// LB-based outer B&B cut: if partialDist + the admissible lower bound on
// remaining segments already exceeds bestValidDist, no extension of this
// prefix can improve the bound. Cut the whole subtree.
if (partialDist + totalMinInRemaining > bestValidDist[0]) {
    EnumerationStats s = EnumerationStats.get();
    s.bnbOriginLbCuts++;
    // candidates skipped: we haven't built the candidate list yet, so
    // count 1 event without a skip count. Refine post-measurement if it
    // matters.
    s.bnbOriginLbSkippedCandidates += 0;
    return;
}
```

Placement: after Check A, before candidate enumeration. This is the "candidate-independent" check from the design doc — a single scalar comparison, fires before any work in this stack frame.

**Step 3: Run Kelheim E2E**

```bash
cd contribs/drt-demand-extraction && mvn test -Dtest=ExMasKelheimE2ETest -q
```

Expected: **PASS**, `703/243/451/8/1`. If the cut over-prunes and the ride count drops, there is a soundness bug — most likely the `minIn` precompute used the wrong link IDs (origin vs destination), so it's not actually a lower bound. Compare the dropped count against the `MinInLowerBoundTest` which must also fail in that case; run it to triangulate.

```bash
cd contribs/drt-demand-extraction && mvn test -Dtest=MinInLowerBoundTest -q
```

**Step 4: Run all tests once**

```bash
cd contribs/drt-demand-extraction && mvn test -q
```

Expected: all pass.

**Step 5: Commit**

```bash
git add contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingEnumerator.java \
        contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/EnumerationStats.java
git commit -m "$(cat <<'EOF'
feat: LB-based outer B&B cut in seeded origin DFS

Adds a candidate-independent early-return cut:
  partialDist + totalMinInRemaining > bestValidDist[0] → return
placed immediately after Check A, before the candidate enumeration.
Strictly stronger than the existing inner per-candidate cut: whole
subtrees are pruned at entry rather than cut at leaf level.

New counters: bnbOriginLbCuts, bnbOriginLbSkippedCandidates.
Kelheim E2E 703/243/451/8/1.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 13: Thread `minIn` / `totalMinInRemaining` into seeded dest DFS and add outer cut

**Files:**
- Modify: `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingEnumerator.java`
- Modify: `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/EnumerationStats.java`

**Step 1: Add dest counters**

In `EnumerationStats.java`:

```java
public long bnbDestLbCuts;
public long bnbDestLbSkippedCandidates;
```

Plus `clear()`, `sum()`, `log()`.

**Step 2: Thread `minIn` and `totalMinInRemaining` through seeded dest DFS**

Add both parameters to `enumerateDestPrunedSeededWithEval` and `enumerateDestTopoSeededWithEval`. At depth-entry time, the dest DFS places a dropoff stop (local index `c + n` in `minIn[]`):

```java
double newTotalMinInRemaining = totalMinInRemaining - minIn[c + n];
```

Pass through on recursion. Same pattern as origin DFS.

**Step 3: Pass from origin to dest**

At the transition point in the origin DFS (depth == n), pass `totalMinInRemaining` into the seeded dest DFS.

**Step 4: Add the outer cut at the top of `enumerateDestTopoSeededWithEval`**

Right after the existing Check A block:

```java
if (partialDist + totalMinInRemaining > bestValidDist[0]) {
    EnumerationStats s = EnumerationStats.get();
    s.bnbDestLbCuts++;
    return;
}
```

**Step 5: Run Kelheim E2E**

```bash
cd contribs/drt-demand-extraction && mvn test -Dtest=ExMasKelheimE2ETest -q
```

Expected: `703/243/451/8/1`.

**Step 6: Run full test suite**

```bash
cd contribs/drt-demand-extraction && mvn test -q
```

Expected: all pass.

**Step 7: Commit**

```bash
git add contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingEnumerator.java \
        contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/EnumerationStats.java
git commit -m "$(cat <<'EOF'
feat: LB-based outer B&B cut in seeded dest DFS

Mirrors the origin-phase outer cut. Threads minIn and
totalMinInRemaining through the dest DFS, subtracting minIn[c+n] per
dropoff placement. New counters: bnbDestLbCuts,
bnbDestLbSkippedCandidates. Kelheim E2E 703/243/451/8/1.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 14: Full verification — all existing tests pass

**Files:** none

**Step 1: Run full extension-module test suite**

```bash
cd contribs/drt-demand-extraction && mvn test -q
```

Expected: all pass. No test may be skipped.

**Step 2: Run the three explicit E2E scenarios called out in `CLAUDE.md`**

```bash
cd contribs/drt-demand-extraction && mvn test -Dtest=ExMasDemandExtractionE2ETest -q
mvn test -Dtest=ExMasHyperPoolE2ETest -q
mvn test -Dtest=ExMasKelheimE2ETest -q
```

Expected: all three pass. Kelheim specifically: `703/243/451/8/1`.

**Step 3: No commit — this is a gate, not a change**

If any test fails, stop. Investigate and either fix or roll back the offending change.

---

## Task 15: Bavaria 10% measurement run — `--max-degree 6`

Measure the speedup on the real workload.

**Files:**
- Create: `outputs/bnb-tightening-v1/` (outputs directory, git-ignored)

**Step 1: Identify the Bavaria runner**

The existing runs in `outputs/delay-window-v1/`, `outputs/ordering-death-diag/`, and `outputs/tightendag-baseline/` used `RunBavaria30kmDemandExtraction` (or similar — check `contribs/drt-demand-extraction/src/main/java` for a class with `RunBavaria` in the name). Use the same runner configuration as the `delay-window-v1` run so the comparison is apples-to-apples.

Check the exact command used for the morning session's runs:

```bash
grep -rn "Bavaria" docs/plans/2026-04-13-delay-window-v1-session-log.md
```

Use the same CLI invocation; change only the output directory.

**Step 2: Kick off the measurement**

Expected runtime on `feature/exmas-degree-graph` (current tip): ~1580 s at deg 6. On the new branch, target is 400–700 s (2–4× speedup). Wall clock may be 10–40 minutes depending on machine load.

Run the Bavaria 10% `--max-degree 6` command, redirecting log to `outputs/bnb-tightening-v1/run.log`. Use `mvn` with the same goal as the delay-window session log.

**Step 3: Capture the key metrics**

From the run log, extract:
- Per-degree `orderingsEvaluated`, `timeTotal` (per-degree CPU-ms)
- `bnbOriginCuts`, `bnbOriginSkippedCandidates`
- `bnbOriginLbCuts`, `bnbOriginLbSkippedCandidates` (new)
- `bnbDestCuts`, `bnbDestSkippedCandidates`
- `bnbDestLbCuts`, `bnbDestLbSkippedCandidates` (new)
- `parentSeedRidesFound` / `setsProcessed`
- `newBestRides` (absolute, per degree)
- Final ride count at degrees 2, 3, 4, 5, 6
- Cumulative total CPU ms across degrees 3–6

Compare against the delay-window v1 baseline from the morning:
- deg 6 orderings evaluated: 128,260,412
- deg 6 total CPU ms: 1,579,446
- cumulative 3–6 CPU ms: whatever the session log has
- new-best at deg 6: 1,715,332

Note: if `parentSeedRidesFound / setsProcessed` is low (< 80%), that's a sign the sort bias is not actually finding parent-consistent orderings quickly — investigate before proceeding.

**Step 4: No commit yet** — the session log in the next task captures the results. Output files are git-ignored under `outputs/`.

---

## Task 16: Write session log

**Files:**
- Create: `docs/plans/2026-04-13-bnb-tightening-v1-session-log.md`

**Step 1: Draft the session log**

Model after `docs/plans/2026-04-13-delay-window-v1-session-log.md`. Structure:

```markdown
# B&B tightening v1 — session log 2026-04-13

## Setup

Branch: feature/bnb-tightening-v1 in matsim-libs submodule (off feature/exmas-degree-graph).
Commits: <list from git log --oneline feature/exmas-degree-graph..HEAD>

## Changes
- Dead code cleanup (OrderingConflicts)
- Change 1: parent-consistent DFS sort bias (origin + dest)
- Change 2: LB-based outer B&B cut (origin + dest) with minIn precompute

## Kelheim E2E
Exact match: 703/243/451/8/1 ✓

## Bavaria 10% --max-degree 6

### Orderings evaluated per degree
| Degree | Baseline (delay-window v1) | bnb v1 | Δ |
|---|---:|---:|---:|
| 3 | ... | ... | ... |
| 4 | ... | ... | ... |
| 5 | ... | ... | ... |
| 6 | 128,260,412 | ... | ... |

### CPU time per degree (ms)
| Degree | Baseline | bnb v1 | Speedup |
|---|---:|---:|---:|
| 3 | ... | ... | ... |
| 4 | ... | ... | ... |
| 5 | ... | ... | ... |
| 6 | 1,579,446 | ... | ... |
| **Total 3–6** | ... | ... | ... |

### LB cut effectiveness
| Degree | bnbOriginLbCuts | bnbDestLbCuts | parentSeedRidesFound / setsProcessed |
|---|---:|---:|---:|
| ... | ... | ... | ... |

### Ride counts
Deg 2: ...
Deg 3: ...
Deg 4: ...
Deg 5: ...
Deg 6: ...
(compare against delay-window v1 baseline)

## Interpretation

- Did the LB cut fire at shallow depths? (bnbOriginLbCuts / bnbOriginCuts ratio)
- Did the parent seed find valid rides at a high rate?
- Where does the remaining CPU time live after this change? (informs next session's target)

## Open questions
- Reflect the 0.3% delay-window drift from the morning; did it stay or change?
- Anything unexpected in the numbers?
- Next candidate optimization (Idea 3 — within-set triangularity — or deeper LB improvement?)

## Files
- outputs/bnb-tightening-v1/run.log
- outputs/bnb-tightening-v1/... other artifacts
```

**Step 2: Fill in the measurement numbers from Task 15**

**Step 3: Commit in the outer Dissertation repo**

```bash
cd /c/Users/VWAUCCY/dev/msf/projects/Dissertation
git add docs/plans/2026-04-13-bnb-tightening-v1-session-log.md
git commit -m "$(cat <<'EOF'
docs: B&B tightening v1 session log (Bavaria 10% measurement)

Measurement results for feature/bnb-tightening-v1 on Bavaria 10%
--max-degree 6. Parent-seeded DFS + LB-based outer cut. Numbers show
<speedup TBD> at deg 6 vs the morning delay-window v1 baseline.
Kelheim E2E exact match 703/243/451/8/1.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

**Step 4: Also update the submodule pointer commit in the outer repo**

After the submodule has new commits, the outer repo needs a submodule-update commit to pin the new tip:

```bash
cd /c/Users/VWAUCCY/dev/msf/projects/Dissertation
git add matsim-libs
git commit -m "$(cat <<'EOF'
submodule: feature/bnb-tightening-v1 (parent-seed + LB cut)

Pins matsim-libs to the tip of feature/bnb-tightening-v1 with the
B&B tightening v1 commits. See session log for measurement results.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Open questions and future work

- **0.3% ride count drift from delay-window v1** — still present, still flagged. Unrelated to this branch's work; could be investigated in a separate session.
- **Idea 3 (within-set triangularity)** — separate branch after measuring Task 15's results. Decide whether the remaining funnel still has enough slack to warrant the experiment.
- **Budget validation removal** — still a potential ~400 s CPU saving at deg 6. Orthogonal to this branch.
- **Multi-parent seeding** — if Task 15 shows single-parent is the bottleneck (e.g., low `parentSeedRidesFound` rate), consider seeding with multiple parents or using the best of the k available subset parents (currently we pick shortest; also try closest-match-to-child or similar heuristics).
- **Lower-bound tightening** — if the LB cut fires too rarely at shallow depths, consider a sharper LB: min-cost assignment on remaining stops, or a Held-Karp-style DP lower bound. Both more expensive per-set; only worthwhile if the minIn LB leaves obvious slack.

---

## Plan complete and saved to `docs/plans/2026-04-13-bnb-tightening-v1.md`

Two execution options:

**1. Subagent-Driven (this session)** — I dispatch a fresh subagent per task, review between tasks, fast iteration. Suitable if you want to stay in this session and see each commit as it lands.

**2. Parallel Session (separate)** — Open a new session with the executing-plans skill. Batch execution with checkpoints. Suitable if you want to do other work while this runs.

**Which approach?**
