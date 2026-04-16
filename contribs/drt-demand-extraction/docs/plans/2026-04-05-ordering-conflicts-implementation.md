# Ordering Conflicts Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement unified stop-sequence conflict learning for ExMAS ordering enumeration, reducing wasted orderings at degree 5+ by learning and reusing proven-infeasible stop subsequences across degrees.

**Architecture:** A single `OrderingConflicts` class stores hashed stop subsequences (origins + destinations) that provably cause passenger timeouts. Conflicts are recorded during enumeration when Check A fires (both origin and destination phases) and when all destination orderings fail for an origin ordering. During future enumeration, candidates that would create a known-bad subsequence are filtered out before being tried. The conflict store grows across degrees: degree 3 conflicts prune degree 4+ orderings, etc.

**Tech Stack:** Java 17, fastutil (`LongOpenHashSet`), JUnit 5. Branch `feature/exmas-degree-graph` in matsim-libs.

**Design doc:** `docs/plans/2026-04-05-ordering-conflicts-design.md`

---

## Build & Test Commands

All commands from: `matsim-libs/contribs/drt-demand-extraction`

```bash
# Build
mvn compile -pl . -am

# Single test
mvn test -Dtest=OrderingConflictsTest -pl .

# E2E tests (correctness verification)
mvn test -Dtest=ExMasDemandExtractionE2ETest -pl .
mvn test -Dtest=ExMasKelheimE2ETest -pl .

# All tests
mvn test -pl .
```

---

### Task 1: Create OrderingConflicts Data Structure

**Files:**
- Create: `src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingConflicts.java`
- Test: `src/test/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingConflictsTest.java`

**Step 1: Write the failing test**

```java
package org.matsim.contrib.demand_extraction.algorithm.extension;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrderingConflictsTest {

    @Test
    void testStopEncoding() {
        assertEquals(0, OrderingConflicts.originStop(0));
        assertEquals(1, OrderingConflicts.destStop(0));
        assertEquals(200, OrderingConflicts.originStop(100));
        assertEquals(201, OrderingConflicts.destStop(100));
    }

    @Test
    void testHashDeterministic() {
        int[] seq1 = {0, 2, 4};  // O_0, O_1, O_2
        int[] seq2 = {0, 2, 4};
        assertEquals(
            OrderingConflicts.hash(seq1, 0, 3),
            OrderingConflicts.hash(seq2, 0, 3)
        );
    }

    @Test
    void testHashOrderSensitive() {
        int[] abc = {0, 2, 4};
        int[] bac = {2, 0, 4};
        assertNotEquals(
            OrderingConflicts.hash(abc, 0, 3),
            OrderingConflicts.hash(bac, 0, 3)
        );
    }

    @Test
    void testRecordAndCommitTriple() {
        OrderingConflicts conflicts = new OrderingConflicts(8);
        int[] conflict = {0, 2, 4};  // O_0, O_1, O_2
        conflicts.recordPending(conflict, 0, 3);
        assertEquals(0, conflicts.getConflictCount());  // not committed yet
        conflicts.commit();
        assertEquals(1, conflicts.getConflictCount());
        assertEquals(1, conflicts.getConflictCount(3));
    }

    @Test
    void testHasConflictBasicTriple() {
        OrderingConflicts conflicts = new OrderingConflicts(8);
        // Record conflict: (O_0, O_1, O_2) = stops 0, 2, 4
        conflicts.recordPending(new int[]{0, 2, 4}, 0, 3);
        conflicts.commit();

        // Path so far: [O_0, O_1] = stops [0, 2]. Candidate: O_2 = stop 4.
        // Should match conflict (0, 2, 4).
        int[] path = {0, 2};
        assertTrue(conflicts.hasConflict(path, 2, 4));
    }

    @Test
    void testHasConflictSubsequenceMatch() {
        OrderingConflicts conflicts = new OrderingConflicts(8);
        // Conflict: (O_0, O_1, O_2) = stops 0, 2, 4
        conflicts.recordPending(new int[]{0, 2, 4}, 0, 3);
        conflicts.commit();

        // Path: [O_0, O_3, O_1] = stops [0, 6, 2]. Candidate: O_2 = stop 4.
        // Subsequence (0, 2, 4) exists: 0 at pos 0, 2 at pos 2, 4 = candidate.
        int[] path = {0, 6, 2};
        assertTrue(conflicts.hasConflict(path, 3, 4));
    }

    @Test
    void testHasConflictNoMatch() {
        OrderingConflicts conflicts = new OrderingConflicts(8);
        // Conflict: (O_0, O_1, O_2) = stops 0, 2, 4
        conflicts.recordPending(new int[]{0, 2, 4}, 0, 3);
        conflicts.commit();

        // Path: [O_3, O_4]. Candidate: O_2 = stop 4.
        // No match — O_0 and O_1 not in path.
        int[] path = {6, 8};
        assertFalse(conflicts.hasConflict(path, 2, 4));
    }

    @Test
    void testHasConflictOrderMatters() {
        OrderingConflicts conflicts = new OrderingConflicts(8);
        // Conflict: (O_0, O_1, O_2) = stops 0, 2, 4
        conflicts.recordPending(new int[]{0, 2, 4}, 0, 3);
        conflicts.commit();

        // Path: [O_1, O_0]. Candidate: O_2 = stop 4.
        // Subsequence (0, 2, 4): 0 at pos 1, 2 at pos 0 — WRONG ORDER.
        // Should NOT match.
        int[] path = {2, 0};
        assertFalse(conflicts.hasConflict(path, 2, 4));
    }

    @Test
    void testMixedOriginDestConflict() {
        OrderingConflicts conflicts = new OrderingConflicts(8);
        // Conflict: (O_A, O_B, D_B) = originStop(0), originStop(1), destStop(1)
        // = stops 0, 2, 3
        conflicts.recordPending(new int[]{0, 2, 3}, 0, 3);
        conflicts.commit();

        // Path during dest enum: [O_A, O_B, O_C, D_C] = [0, 2, 4, 5]
        // Candidate: D_B = stop 3
        // Subsequence (0, 2, 3) exists: 0 at pos 0, 2 at pos 1, 3 = candidate.
        int[] path = {0, 2, 4, 5};
        assertTrue(conflicts.hasConflict(path, 4, 3));
    }

    @Test
    void testLengthBeyondMaxIgnored() {
        OrderingConflicts conflicts = new OrderingConflicts(4);  // max length 4
        // Record a length-5 conflict — should be ignored
        conflicts.recordPending(new int[]{0, 2, 4, 6, 8}, 0, 5);
        conflicts.commit();
        assertEquals(0, conflicts.getConflictCount());
    }

    @Test
    void testLength2Ignored() {
        OrderingConflicts conflicts = new OrderingConflicts(8);
        // Record a length-2 conflict — should be ignored (pairwise handled elsewhere)
        conflicts.recordPending(new int[]{0, 2}, 0, 2);
        conflicts.commit();
        assertEquals(0, conflicts.getConflictCount());
    }

    @Test
    void testEmptyConflictsNoMatch() {
        OrderingConflicts conflicts = new OrderingConflicts(8);
        int[] path = {0, 2, 4, 6};
        assertFalse(conflicts.hasConflict(path, 4, 8));
    }

    @Test
    void testNullConflictsNoMatch() {
        // hasConflict should handle null gracefully (called when conflicts disabled)
        assertFalse(OrderingConflicts.hasConflictSafe(null, new int[]{0, 2}, 2, 4));
    }

    @Test
    void testMultipleLengths() {
        OrderingConflicts conflicts = new OrderingConflicts(8);
        // Triple: (0, 2, 4)
        conflicts.recordPending(new int[]{0, 2, 4}, 0, 3);
        // Quad: (10, 12, 14, 16)
        conflicts.recordPending(new int[]{10, 12, 14, 16}, 0, 4);
        conflicts.commit();

        assertEquals(2, conflicts.getConflictCount());
        assertEquals(1, conflicts.getConflictCount(3));
        assertEquals(1, conflicts.getConflictCount(4));

        // Triple match
        assertTrue(conflicts.hasConflict(new int[]{0, 2}, 2, 4));
        // Quad match
        assertTrue(conflicts.hasConflict(new int[]{10, 12, 14}, 3, 16));
        // No match
        assertFalse(conflicts.hasConflict(new int[]{0, 12, 14}, 3, 16));
    }

    @Test
    void testDuplicateRecordingNoDuplicate() {
        OrderingConflicts conflicts = new OrderingConflicts(8);
        conflicts.recordPending(new int[]{0, 2, 4}, 0, 3);
        conflicts.recordPending(new int[]{0, 2, 4}, 0, 3);  // duplicate
        conflicts.commit();
        assertEquals(1, conflicts.getConflictCount());  // deduped by hash set
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=OrderingConflictsTest -pl .`
Expected: FAIL — `OrderingConflicts` class does not exist.

**Step 3: Write the implementation**

Create `src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingConflicts.java`:

```java
package org.matsim.contrib.demand_extraction.algorithm.extension;

import java.util.concurrent.ConcurrentLinkedQueue;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Stores proven-infeasible stop subsequences for ordering enumeration pruning.
 *
 * <p>Each request contributes two stops: origin ({@code requestIndex << 1}) and
 * destination ({@code (requestIndex << 1) | 1}). A conflict is an ordered subsequence
 * of stop IDs that, whenever it appears in a route, causes a passenger to time out.
 *
 * <p>Conflicts transfer across sets and degrees by the triangle inequality: inserting
 * stops between any two points can only increase travel time. If a subsequence causes
 * a timeout at degree k, it also causes a timeout at all degrees ≥ k.
 *
 * <p>Thread safety: record via {@link #recordPending} (lock-free queue), then merge
 * via {@link #commit} between degrees. Lookups via {@link #hasConflict} read from
 * immutable committed sets — safe for concurrent reads.
 */
public final class OrderingConflicts {

    private static final long HASH_PRIME = 1000003L;
    private static final int MIN_LENGTH = 3;  // length 2 = pairwise, handled by shareability graph

    private final LongOpenHashSet[] byLength;  // byLength[L] = conflicts of length L
    private final int maxLength;

    // Thread-safe buffer for recording during parallel processing
    private final ConcurrentLinkedQueue<long[]> pending = new ConcurrentLinkedQueue<>();
    // pending entries: long[]{length, hash}

    public OrderingConflicts(int maxLength) {
        this.maxLength = maxLength;
        this.byLength = new LongOpenHashSet[maxLength + 1];
        for (int i = MIN_LENGTH; i <= maxLength; i++) {
            byLength[i] = new LongOpenHashSet();
        }
    }

    // --- Stop encoding ---

    public static int originStop(int requestIndex) { return requestIndex << 1; }
    public static int destStop(int requestIndex) { return (requestIndex << 1) | 1; }

    // --- Hashing ---

    /**
     * Polynomial rolling hash over a stop sequence.
     * Same scheme as {@code DegreeGraph.hashRequestSet} for consistency.
     */
    public static long hash(int[] stops, int from, int to) {
        long h = 0;
        for (int i = from; i < to; i++) {
            h = h * HASH_PRIME + stops[i];
        }
        return h;
    }

    // --- Recording ---

    /**
     * Buffer a conflict for later commit. Thread-safe (lock-free queue).
     *
     * @param stopSequence ordered array of stop IDs
     * @param from start index (inclusive)
     * @param to end index (exclusive)
     */
    public void recordPending(int[] stopSequence, int from, int to) {
        int len = to - from;
        if (len < MIN_LENGTH || len > maxLength) return;
        long h = hash(stopSequence, from, to);
        pending.add(new long[]{len, h});
    }

    /**
     * Merge all pending conflicts into the main hash sets.
     * Call once between degrees (single-threaded).
     */
    public void commit() {
        long[] entry;
        while ((entry = pending.poll()) != null) {
            int len = (int) entry[0];
            if (len >= MIN_LENGTH && len <= maxLength) {
                byLength[len].add(entry[1]);
            }
        }
    }

    // --- Querying ---

    /**
     * Check if adding candidateStop to the current path creates a known conflict.
     * Called during both origin and destination enumeration.
     *
     * <p>Enumerates all ordered subsequences of {@code pathStops[0..pathLength-1]}
     * of length L-1, appends candidateStop, hashes, and checks for each conflict
     * length L from 3 to min(maxLength, pathLength+1).
     *
     * @param pathStops stops visited so far (origins placed + destinations placed)
     * @param pathLength number of valid entries in pathStops
     * @param candidateStop the stop about to be added
     * @return true if a known conflict subsequence would be formed
     */
    public boolean hasConflict(int[] pathStops, int pathLength, int candidateStop) {
        int maxL = Math.min(maxLength, pathLength + 1);
        for (int L = MIN_LENGTH; L <= maxL; L++) {
            LongOpenHashSet set = byLength[L];
            if (set.isEmpty()) continue;
            // Check all C(pathLength, L-1) ordered subsequences of length L-1
            // from pathStops, each extended with candidateStop to form length L
            if (enumerateAndCheck(pathStops, pathLength, candidateStop, L - 1, set, 0, 0L)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Null-safe static wrapper for hasConflict.
     * Returns false if conflicts is null (feature disabled).
     */
    public static boolean hasConflictSafe(OrderingConflicts conflicts,
                                           int[] pathStops, int pathLength, int candidateStop) {
        return conflicts != null && conflicts.hasConflict(pathStops, pathLength, candidateStop);
    }

    /**
     * Recursive combination generator with incremental hashing.
     * Enumerates all (remaining)-element ordered subsequences from pathStops[startIdx..],
     * building the hash incrementally. When remaining == 0, appends candidateStop and checks.
     */
    private boolean enumerateAndCheck(int[] path, int pathLen, int candidate,
                                       int remaining, LongOpenHashSet set,
                                       int startIdx, long partialHash) {
        if (remaining == 0) {
            long fullHash = partialHash * HASH_PRIME + candidate;
            return set.contains(fullHash);
        }
        int maxStart = pathLen - remaining;
        for (int i = startIdx; i <= maxStart; i++) {
            long newHash = partialHash * HASH_PRIME + path[i];
            if (enumerateAndCheck(path, pathLen, candidate, remaining - 1, set, i + 1, newHash)) {
                return true;
            }
        }
        return false;
    }

    // --- Statistics ---

    /** Total number of stored conflicts across all lengths. */
    public int getConflictCount() {
        int total = 0;
        for (int i = MIN_LENGTH; i <= maxLength; i++) {
            if (byLength[i] != null) total += byLength[i].size();
        }
        return total;
    }

    /** Number of stored conflicts of a specific length. */
    public int getConflictCount(int length) {
        if (length < MIN_LENGTH || length > maxLength) return 0;
        return byLength[length] != null ? byLength[length].size() : 0;
    }

    /** Maximum conflict length this instance tracks. */
    public int getMaxLength() { return maxLength; }
}
```

**Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=OrderingConflictsTest -pl .`
Expected: ALL PASS

**Step 5: Commit**

```bash
git add src/main/java/.../extension/OrderingConflicts.java \
        src/test/java/.../extension/OrderingConflictsTest.java
git commit -m "feat: add OrderingConflicts data structure with unit tests"
```

---

### Task 2: Add prunedByConflict Counter to EnumerationStats

**Files:**
- Modify: `src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/EnumerationStats.java`

**Step 1: Add counter field, sum logic, and log output**

In `EnumerationStats.java`, add:

```java
// New field (alongside existing prunedByTravelTime):
public long prunedByConflict;

// In sum():
total.prunedByConflict += s.prunedByConflict;

// In log():
log.info("  Pruned by conflict: {} ({} per set)", prunedByConflict,
        setsProcessed > 0 ? String.format("%.1f", (double) prunedByConflict / setsProcessed) : "N/A");

// In the reset block in RideExtender (where other fields are reset):
s.prunedByConflict = 0;
```

**Step 2: Verify build compiles**

Run: `mvn compile -pl .`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add src/main/java/.../extension/EnumerationStats.java
git commit -m "feat: add prunedByConflict counter to EnumerationStats"
```

---

### Task 3: Add Origin-Phase Check A (Mechanism 1)

This is the immediate-pruning mechanism: check during origin enumeration if any picked-up passenger already exceeds maxTravelTime from origin traversal alone. No conflict recording yet — just pruning.

**Files:**
- Modify: `src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingEnumerator.java` — method `enumerateOriginsPrunedWithEval` (line 203)

**Step 1: Add origin-phase Check A**

In `enumerateOriginsPrunedWithEval`, after the `if (depth == n)` block and before candidate selection, add:

```java
// Origin-phase Check A: prune if any picked-up passenger already exceeds
// maxTravelTime from origin traversal alone. No destination ordering can help.
if (depth > 1) {
    EnumerationStats stats = EnumerationStats.get();
    for (int p = 0; p < depth; p++) {
        double inVehicle = currentTime - pickupTimes[perm[p]];
        if (inVehicle > requests[perm[p]].getMaxTravelTime()) {
            stats.prunedByTravelTime++;
            return;
        }
    }
}
```

This goes at line ~212 (after the `depth == n` return, before the candidate loop).

**Step 2: Run E2E tests to verify correctness**

Run: `mvn test -Dtest=ExMasDemandExtractionE2ETest -pl .`
Expected: PASS (mechanism 1 is a pure pruning optimization — it cannot change which rides are found, only skip provably-infeasible branches faster)

Run: `mvn test -Dtest=ExMasKelheimE2ETest -pl .`
Expected: PASS

**Step 3: Commit**

```bash
git add src/main/java/.../extension/OrderingEnumerator.java
git commit -m "feat: add origin-phase Check A (mechanism 1) to ordering enumeration"
```

---

### Task 4: Thread pathStops and OrderingConflicts Through Enumeration

Add `OrderingConflicts` and `int[] pathStops` parameters to the enumeration methods. No recording or lookup logic yet — just plumbing.

**Files:**
- Modify: `src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingEnumerator.java`

**Step 1: Update public API — both `enumerateAndEvaluate` overloads**

Add `OrderingConflicts conflicts` parameter to both public methods. Create `pathStops` internally and pass through:

```java
// 6-param overload (degree 3 path): add conflicts parameter
public static void enumerateAndEvaluate(
        int[] requestIndices, ShareabilityGraph graph,
        MatsimNetworkCache network, DrtRequest[] requests,
        double[] bestValidDist,
        Consumer<Ordering> evaluator,
        OrderingConflicts conflicts) {           // NEW

    PairInfo[] constraints = extractConstraints(requestIndices, graph);
    enumerateAndEvaluate(requestIndices, graph, constraints, network, requests,
            bestValidDist, evaluator, conflicts);
}

// 7-param overload (degree 4+ path): add conflicts parameter
public static void enumerateAndEvaluate(
        int[] requestIndices, ShareabilityGraph graph,
        PairInfo[] pairConstraints,
        MatsimNetworkCache network, DrtRequest[] requests,
        double[] bestValidDist,
        Consumer<Ordering> evaluator,
        OrderingConflicts conflicts) {           // NEW

    if (pairConstraints == null) return;
    int n = requestIndices.length;

    Boolean[][] origAdj = new Boolean[n][n];
    // ... existing adj matrix setup ...

    // Create pathStops array: max size 2*n (n origins + n destinations)
    int[] pathStops = new int[2 * n];

    enumerateOriginsPrunedWithEval(origAdj, n, pairConstraints, network, requests,
            bestValidDist, new boolean[n], new int[n], new double[n], 0,
            0.0, 0.0, evaluator,
            conflicts, pathStops);              // NEW params
}
```

**Step 2: Update private method signatures**

Add `OrderingConflicts conflicts, int[] pathStops` to:
- `enumerateOriginsPrunedWithEval` (add at end of parameter list)
- `enumerateDestPrunedWithEval` (add at end of parameter list)
- `enumerateDestTopoWithEval` (add at end of parameter list)

Thread them through all recursive calls. In origin enumeration, populate pathStops:

```java
// In enumerateOriginsPrunedWithEval, when placing candidate c:
pathStops[depth] = OrderingConflicts.originStop(requests[c].index);
// ... recurse ...
```

In dest setup (`enumerateDestPrunedWithEval`), the first n entries of pathStops are already filled. Pass `pathStops` through to `enumerateDestTopoWithEval`.

In dest recursion (`enumerateDestTopoWithEval`), populate pathStops:

```java
// When placing destination candidate c:
pathStops[n + depth] = OrderingConflicts.destStop(requests[c].index);
// ... recurse ...
```

**Step 3: Update callers in RideExtender**

In `RideExtender.processSet`, both code paths call `OrderingEnumerator.enumerateAndEvaluate`. Add `null` as the conflicts parameter for now (no conflicts wired up yet):

```java
// Degree 3 path:
OrderingEnumerator.enumerateAndEvaluate(
    newSet, graph, network, setRequests, bestValidDist,
    (ordering) -> evaluateOrdering(...), null);   // null = no conflicts yet

// Degree 4+ path:
OrderingEnumerator.enumerateAndEvaluate(
    newSet, graph, pairConstraints, network, setRequests, bestValidDist,
    (ordering) -> evaluateOrdering(...), null);   // null = no conflicts yet
```

**Step 4: Verify everything compiles and tests pass**

Run: `mvn test -pl .`
Expected: ALL PASS (no behavioral change — conflicts is null everywhere)

**Step 5: Commit**

```bash
git add src/main/java/.../extension/OrderingEnumerator.java \
        src/main/java/.../extension/RideExtender.java
git commit -m "refactor: thread OrderingConflicts and pathStops through enumeration"
```

---

### Task 5: Add Conflict Recording

Add conflict recording from three triggers:
1. Mechanism 1 (origin-phase Check A)
2. Dest-phase Check A
3. Trigger 2 (all-dest-fail)

**Files:**
- Modify: `src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingEnumerator.java`

**Step 1: Record from mechanism 1 (origin-phase Check A)**

In `enumerateOriginsPrunedWithEval`, where mechanism 1 fires (from Task 3), add recording before the return:

```java
if (depth > 1) {
    EnumerationStats stats = EnumerationStats.get();
    for (int p = 0; p < depth; p++) {
        double inVehicle = currentTime - pickupTimes[perm[p]];
        if (inVehicle > requests[perm[p]].getMaxTravelTime()) {
            // Record conflict: stop sequence from victim p to current depth
            if (conflicts != null) {
                int len = depth - p;
                if (len >= 3) {
                    int[] conflict = new int[len];
                    for (int i = 0; i < len; i++)
                        conflict[i] = OrderingConflicts.originStop(requests[perm[p + i]].index);
                    conflicts.recordPending(conflict, 0, len);
                }
            }
            stats.prunedByTravelTime++;
            return;
        }
    }
}
```

**Step 2: Record from dest-phase Check A**

In `enumerateDestTopoWithEval`, where existing Check A fires (line ~326), add recording:

```java
for (int p = 0; p < n; p++) {
    if (used[p]) continue;
    double inVehicleTime = currentTime - pickupTimes[p];
    if (inVehicleTime > requests[p].getMaxTravelTime()) {
        // Record conflict: stops from victim's origin through all subsequent
        // origins and destinations visited so far
        if (conflicts != null) {
            // Find victim's origin position in origPerm
            int victimOrigPos = -1;
            for (int i = 0; i < n; i++) {
                if (origPerm[i] == p) { victimOrigPos = i; break; }
            }
            if (victimOrigPos >= 0) {
                int origCount = n - victimOrigPos;  // origins from victim onwards
                int len = origCount + depth;        // + destinations visited
                if (len >= 3) {
                    int[] conflict = new int[len];
                    int idx = 0;
                    for (int i = victimOrigPos; i < n; i++)
                        conflict[idx++] = OrderingConflicts.originStop(requests[origPerm[i]].index);
                    for (int i = 0; i < depth; i++)
                        conflict[idx++] = OrderingConflicts.destStop(requests[perm[i]].index);
                    conflicts.recordPending(conflict, 0, len);
                }
            }
        }
        stats.prunedByTravelTime++;
        return;
    }
}
```

**Step 3: Add Trigger 2 (all-dest-fail)**

In `enumerateOriginsPrunedWithEval`, at `depth == n`, wrap the dest call to detect all-fail:

```java
if (depth == n) {
    if (conflicts != null) {
        boolean[] anyValid = {false};
        Consumer<Ordering> wrappedEvaluator = (ordering) -> {
            anyValid[0] = true;
            evaluator.accept(ordering);
        };
        enumerateDestPrunedWithEval(n, perm, pairs, network, requests,
                bestValidDist, partialDist, currentTime, pickupTimes, wrappedEvaluator,
                conflicts, pathStops);
        if (!anyValid[0] && n >= 3) {
            // Full origin ordering failed — record as conflict (origin stops only)
            int[] conflict = new int[n];
            for (int i = 0; i < n; i++)
                conflict[i] = OrderingConflicts.originStop(requests[perm[i]].index);
            conflicts.recordPending(conflict, 0, n);
        }
    } else {
        enumerateDestPrunedWithEval(n, perm, pairs, network, requests,
                bestValidDist, partialDist, currentTime, pickupTimes, evaluator,
                conflicts, pathStops);
    }
    return;
}
```

**Step 4: Verify compilation**

Run: `mvn compile -pl .`
Expected: BUILD SUCCESS

**Step 5: Commit**

```bash
git add src/main/java/.../extension/OrderingEnumerator.java
git commit -m "feat: add conflict recording from all three triggers"
```

---

### Task 6: Add Conflict Lookup to Candidate Selection

Filter candidates that would create known-bad subsequences, in both origin and destination enumeration.

**Files:**
- Modify: `src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingEnumerator.java`

**Step 1: Add lookup in origin candidate selection**

In `enumerateOriginsPrunedWithEval`, in the candidate loop (after pairwise validity check, before adding to candidates list):

```java
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
    if (!valid) continue;

    // NEW: conflict check — does placing this candidate create a known-bad subsequence?
    if (conflicts != null) {
        int candidateStop = OrderingConflicts.originStop(requests[c].index);
        if (conflicts.hasConflict(pathStops, depth, candidateStop)) {
            EnumerationStats.get().prunedByConflict++;
            continue;
        }
    }

    candidates.add(c);
}
```

**Step 2: Add lookup in destination candidate selection**

In `enumerateDestTopoWithEval`, in the candidate loop (after pairwise validity check):

```java
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
    if (!valid) continue;

    // NEW: conflict check
    if (conflicts != null) {
        int candidateStop = OrderingConflicts.destStop(requests[c].index);
        // pathStops[0..n-1] = origins, pathStops[n..n+depth-1] = dests placed so far
        if (conflicts.hasConflict(pathStops, n + depth, candidateStop)) {
            EnumerationStats.get().prunedByConflict++;
            continue;
        }
    }

    candidates.add(c);
}
```

**Step 3: Run unit tests**

Run: `mvn test -Dtest=OrderingConflictsTest -pl .`
Expected: PASS

**Step 4: Commit**

```bash
git add src/main/java/.../extension/OrderingEnumerator.java
git commit -m "feat: add conflict lookup to candidate selection in both phases"
```

---

### Task 7: Wire Up in RideExtender and ExMasEngine

Connect the OrderingConflicts through the pipeline.

**Files:**
- Modify: `src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/RideExtender.java`
- Modify: `src/main/java/org/matsim/contrib/demand_extraction/algorithm/engine/ExMasEngine.java`

**Step 1: RideExtender — accept and pass OrderingConflicts**

Add `OrderingConflicts` field to RideExtender:

```java
// New field:
private final OrderingConflicts conflicts;

// Update constructor (the one with prevDegreeGraph):
public RideExtender(MatsimNetworkCache network, ShareabilityGraph graph,
                    BudgetValidator budgetValidator, List<DrtRequest> requests,
                    ExMasConfigGroup exMasConfig, DegreeGraph prevDegreeGraph,
                    OrderingConflicts conflicts) {       // NEW
    // ... existing initialization ...
    this.conflicts = conflicts;
}
```

Update the 5-param constructor to pass `null` for conflicts (backward compatibility).

In `processSet`, replace `null` with `this.conflicts` in both `enumerateAndEvaluate` calls:

```java
// Degree 3 path:
OrderingEnumerator.enumerateAndEvaluate(
    newSet, graph, network, setRequests, bestValidDist,
    (ordering) -> evaluateOrdering(...), conflicts);

// Degree 4+ path:
OrderingEnumerator.enumerateAndEvaluate(
    newSet, graph, pairConstraints, network, setRequests, bestValidDist,
    (ordering) -> evaluateOrdering(...), conflicts);
```

**Step 2: ExMasEngine — create and manage OrderingConflicts**

In `ExMasEngine.run()`, before the extension loop:

```java
// Create ordering conflicts store
int conflictsMaxLength = Math.min(maxDegree * 2, 10);  // cap at 10
OrderingConflicts conflicts = new OrderingConflicts(conflictsMaxLength);
```

In the extension loop, pass conflicts to RideExtender and commit after each degree:

```java
for (int degree = 2; degree < maxDegree; degree++) {
    RideExtender extender = new RideExtender(network, graph, budgetValidator,
                                             requests, exMasConfig, prevDegreeGraph,
                                             conflicts);     // NEW
    List<Ride> extended = extender.extendRides(currentDegreeRides, nextRideIndex);

    // Commit conflicts learned during this degree
    conflicts.commit();
    log.info("  Ordering conflicts: {} total (by length: {})",
            conflicts.getConflictCount(), conflictsByLengthString(conflicts, conflictsMaxLength));

    // ... existing degree graph build, pruning ...
}
```

Add helper for logging:

```java
private static String conflictsByLengthString(OrderingConflicts c, int maxLen) {
    StringBuilder sb = new StringBuilder();
    for (int L = 3; L <= maxLen; L++) {
        int count = c.getConflictCount(L);
        if (count > 0) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("L").append(L).append("=").append(count);
        }
    }
    return sb.length() > 0 ? sb.toString() : "none";
}
```

**Step 3: Run full test suite**

Run: `mvn test -pl .`
Expected: ALL PASS

**Step 4: Commit**

```bash
git add src/main/java/.../extension/RideExtender.java \
        src/main/java/.../engine/ExMasEngine.java
git commit -m "feat: wire OrderingConflicts through RideExtender and ExMasEngine"
```

---

### Task 8: Integration Test — Correctness Verification

Verify that enabling ordering conflicts does not change the rides produced. The conflicts are a pruning optimization — they should skip provably-infeasible orderings, never skip valid ones.

**Files:**
- Run existing tests as correctness check

**Step 1: Run E2E tests**

Run: `mvn test -Dtest=ExMasDemandExtractionE2ETest -pl .`
Expected: PASS (same rides as before)

Run: `mvn test -Dtest=ExMasKelheimE2ETest -pl .`
Expected: PASS (same rides as before — this test uses degree 3+, so conflicts are active)

**Step 2: Run all tests**

Run: `mvn test -pl .`
Expected: ALL PASS

**Step 3: Manual verification with Bavaria 10% (optional, performance)**

Run the Bavaria 10% scenario and check:
1. `prunedByConflict` counter is > 0 at degrees 4+
2. Conflict counts by length are logged
3. Ride counts match baseline (within 0.3% tolerance from time-dependent routing)
4. Timing improvements at degree 5+

**Step 4: Final commit with all changes**

```bash
git add -A
git commit -m "feat: ordering conflicts — unified stop-sequence conflict learning

Implements proven-infeasible stop subsequence learning for ExMAS ordering
enumeration. Three recording triggers: origin-phase Check A (mechanism 1),
dest-phase Check A, and all-dest-fail detection. Conflicts transfer across
degrees by triangle inequality monotonicity. Unified stop encoding (origin
+ destination) enables one data structure for all conflict types."
```

---

## Summary of Changes

| File | Lines added | Purpose |
|------|------------|---------|
| `OrderingConflicts.java` | ~150 | Core data structure: hash, record, lookup, commit |
| `OrderingConflictsTest.java` | ~150 | Unit tests for the data structure |
| `OrderingEnumerator.java` | ~80 | Mechanism 1 + recording + lookup + pathStops threading |
| `RideExtender.java` | ~10 | Accept and pass conflicts |
| `ExMasEngine.java` | ~15 | Create, pass, commit, log conflicts |
| `EnumerationStats.java` | ~5 | prunedByConflict counter |
| **Total** | **~410** | |

## Execution Order

Tasks 1-2 are independent (data structure + stats counter).
Task 3 is independent (mechanism 1, no conflicts needed).
Task 4 depends on Task 1 (threading the type through signatures).
Tasks 5-6 depend on Task 4 (recording and lookup use the threaded parameters).
Task 7 depends on Tasks 5-6 (wiring connects everything).
Task 8 depends on Task 7 (integration testing of the full pipeline).

Optimal parallelization: Tasks 1, 2, 3 in parallel, then 4, then 5-6 in parallel, then 7, then 8.
