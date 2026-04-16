# Forbidden-Prefix Index Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

> Supersedes `2026-04-13-parent-ordering-cache-plan.md`.

**Goal:** Replace `SubSetOrderingFeasibility` + `OrderingConflicts` with a single push-based `ForbiddenPrefixIndex` that prunes infeasible orderings via per-set incremental forbidden sets, unifying origin and destination phases and lifting the sub-set size cap.

**Architecture:** A global `Long2ObjectOpenHashMap<IntOpenHashSet>` keyed by ordered stop prefixes (any length ≥ 2), value = stops forbidden as next placement. During B&B descent the enumerator maintains a per-set `forbiddenSet` and a depth-indexed delta stack: on placement, look up subsequences of the prior placements that end at the new stop and union the resulting forbidden completions; on backtrack, pop the delta. Per-candidate check is one `IntSet.contains`. Recording happens at the existing failure triggers (Check A, Trigger 2, plus a new dest-phase Check A) using the **shortest failing prefix** rule.

**Tech Stack:** Java 17, MATSim contribs, Maven, JUnit 5, fastutil (`Long2ObjectOpenHashMap`, `IntOpenHashSet`, `LongOpenHashSet`).

**Design doc:** `docs/plans/2026-04-13-forbidden-prefix-index-design.md`

**Working directory:** `matsim-libs/contribs/drt-demand-extraction/` (git submodule). Commits in this plan land in the submodule unless stated otherwise. Sync the submodule pointer in the parent repo at the end.

**Reset point:** Submodule `a495af2` (bloom + quints `SubSetOrderingFeasibility`) and parent commit `3fdebc0`. We can reset here if the new approach underperforms.

**Build/test commands:**
- Build: `cd matsim-libs/contribs/drt-demand-extraction && mvn test-compile -q`
- Single unit test class: `mvn test -Dtest=ForbiddenPrefixIndexTest -q`
- Regression E2E: `mvn test -Dtest=ExMasKelheimE2ETest -q`
- Full E2E suite: `mvn test -q`
- 10% Bavaria benchmark: see Phase 5 — exact command in Task 14.

**Commit convention (matches submodule history):** `feat:`, `perf:`, `fix:`, `refactor:`, `test:`, `docs:`. One short sentence. Co-author footer.

---

## Phase 1 — Build `ForbiddenPrefixIndex` data structure

### Task 1: Skeleton + record/commit unit test

**Files:**
- Create: `matsim-libs/contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/ForbiddenPrefixIndex.java`
- Create: `matsim-libs/contribs/drt-demand-extraction/src/test/java/org/matsim/contrib/demand_extraction/algorithm/extension/ForbiddenPrefixIndexTest.java`

**Step 1: Write the failing test**

```java
package org.matsim.contrib.demand_extraction.algorithm.extension;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

class ForbiddenPrefixIndexTest {

    @Test
    void recordTripleAndLookup() {
        ForbiddenPrefixIndex index = new ForbiddenPrefixIndex();

        // Record: sequence (10, 20, 30) is infeasible.
        // Insert: index[(10, 20)] += 30
        index.recordPending(new int[]{10, 20, 30});
        index.commit();

        IntOpenHashSet forbidden = index.lookup(new int[]{10, 20});
        assertNotNull(forbidden);
        assertTrue(forbidden.contains(30));
    }

    @Test
    void lookupMissingReturnsNull() {
        ForbiddenPrefixIndex index = new ForbiddenPrefixIndex();
        assertNull(index.lookup(new int[]{1, 2}));
    }

    @Test
    void multipleForbiddenForSamePrefix() {
        ForbiddenPrefixIndex index = new ForbiddenPrefixIndex();
        index.recordPending(new int[]{10, 20, 30});
        index.recordPending(new int[]{10, 20, 40});
        index.commit();

        IntOpenHashSet forbidden = index.lookup(new int[]{10, 20});
        assertEquals(2, forbidden.size());
        assertTrue(forbidden.contains(30));
        assertTrue(forbidden.contains(40));
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd matsim-libs/contribs/drt-demand-extraction && mvn test -Dtest=ForbiddenPrefixIndexTest -q`
Expected: FAIL — class does not exist.

**Step 3: Write minimal implementation**

```java
package org.matsim.contrib.demand_extraction.algorithm.extension;

import java.util.concurrent.ConcurrentLinkedQueue;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

/**
 * Push-based forbidden-prefix index for ordering pruning.
 *
 * <p>Records ordered stop sequences known to be infeasible (a passenger times out).
 * Each record (s_0, ..., s_m) is stored as: index[(s_0, ..., s_{m-1})] += s_m.
 * During B&amp;B descent the {@code OrderingEnumerator} maintains a per-set forbidden
 * set: on each placement, look up sub-sequences of placed stops ending at the new
 * stop and union the resulting forbidden completions; on backtrack, pop the delta.
 *
 * <p>Stops use the unified encoding: origin of request i = {@code 2*i}, destination
 * = {@code 2*i + 1}. The same index handles both origin and dest phases.
 *
 * <p>Thread-safe recording via {@link #recordPending} (lock-free queue), flushed via
 * {@link #commit} between degrees. Lookups read the committed map — safe for
 * concurrent reads, no synchronization at lookup time.
 */
public final class ForbiddenPrefixIndex {

    private static final long HASH_PRIME = 1000003L;

    private final Long2ObjectOpenHashMap<IntOpenHashSet> committed = new Long2ObjectOpenHashMap<>();
    private final ConcurrentLinkedQueue<int[]> pending = new ConcurrentLinkedQueue<>();

    private int maxRecordedKeyLength = 0; // updated at commit

    /**
     * Record an infeasible ordered stop sequence. The last element is the
     * "forbidden next" given the prefix of all earlier elements. Thread-safe.
     *
     * @param sequence ordered stop IDs, length >= 3 (length-2 is the smallest
     *                 record, where the prefix has length 1 — but we require ≥ 3
     *                 so the prefix length is ≥ 2 and length-1 keys are not
     *                 created)
     */
    public void recordPending(int[] sequence) {
        if (sequence.length < 3) return;
        pending.add(sequence);
    }

    /** Merge pending recordings into the committed map. Call between degrees. */
    public void commit() {
        int[] seq;
        while ((seq = pending.poll()) != null) {
            int prefixLen = seq.length - 1;
            int last = seq[prefixLen];
            long key = hashPrefix(seq, prefixLen);
            IntOpenHashSet set = committed.get(key);
            if (set == null) {
                set = new IntOpenHashSet(2);
                committed.put(key, set);
            }
            set.add(last);
            if (prefixLen > maxRecordedKeyLength) maxRecordedKeyLength = prefixLen;
        }
    }

    /** Look up the forbidden-next set for an ordered prefix. Returns null if no entry. */
    public IntOpenHashSet lookup(int[] prefix) {
        return committed.get(hashPrefix(prefix, prefix.length));
    }

    /** Maximum prefix length seen across all committed entries. */
    public int getMaxRecordedKeyLength() { return maxRecordedKeyLength; }

    /** Number of distinct prefix keys committed. */
    public int size() { return committed.size(); }

    static long hashPrefix(int[] seq, int len) {
        long h = 0;
        for (int i = 0; i < len; i++) h = h * HASH_PRIME + seq[i];
        return h;
    }
}
```

**Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=ForbiddenPrefixIndexTest -q`
Expected: PASS, 3 tests.

**Step 5: Commit**

```bash
cd matsim-libs && git add contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/ForbiddenPrefixIndex.java contribs/drt-demand-extraction/src/test/java/org/matsim/contrib/demand_extraction/algorithm/extension/ForbiddenPrefixIndexTest.java && git commit -m "$(cat <<'EOF'
feat: add ForbiddenPrefixIndex data structure with record/commit tests

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Multi-length keys + max-key-length tracking

**Files:**
- Modify: `ForbiddenPrefixIndexTest.java`

**Step 1: Add tests for variable-length keys**

```java
@Test
void recordQuadAndLookup() {
    ForbiddenPrefixIndex index = new ForbiddenPrefixIndex();
    // Record sequence (10, 20, 30, 40) — quad. Insert: index[(10,20,30)] += 40
    index.recordPending(new int[]{10, 20, 30, 40});
    index.commit();

    IntOpenHashSet f = index.lookup(new int[]{10, 20, 30});
    assertNotNull(f);
    assertTrue(f.contains(40));

    // The triple-prefix (10, 20) should NOT be in the index — recording quads
    // does not create entries for shorter prefixes.
    assertNull(index.lookup(new int[]{10, 20}));
}

@Test
void recordsTooShortAreIgnored() {
    ForbiddenPrefixIndex index = new ForbiddenPrefixIndex();
    index.recordPending(new int[]{10, 20}); // length 2 — too short, prefix would be length 1
    index.commit();
    assertEquals(0, index.size());
}

@Test
void maxRecordedKeyLengthTracksLongest() {
    ForbiddenPrefixIndex index = new ForbiddenPrefixIndex();
    index.recordPending(new int[]{10, 20, 30});
    index.recordPending(new int[]{10, 20, 30, 40, 50});
    index.recordPending(new int[]{10, 20, 30, 40});
    index.commit();
    assertEquals(4, index.getMaxRecordedKeyLength()); // quintuple has prefix length 4
}
```

**Step 2: Run tests — should pass**

Run: `mvn test -Dtest=ForbiddenPrefixIndexTest -q`
Expected: PASS, 6 tests. (No code changes needed — Task 1's implementation already supports variable-length keys.)

**Step 3: Commit**

```bash
cd matsim-libs && git add contribs/drt-demand-extraction/src/test/java/org/matsim/contrib/demand_extraction/algorithm/extension/ForbiddenPrefixIndexTest.java && git commit -m "$(cat <<'EOF'
test: variable-length keys + max-key-length tracking in ForbiddenPrefixIndex

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Per-set forbidden-set helper class

**Files:**
- Create: `matsim-libs/contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/ForbiddenPrefixCursor.java`
- Create: `matsim-libs/contribs/drt-demand-extraction/src/test/java/org/matsim/contrib/demand_extraction/algorithm/extension/ForbiddenPrefixCursorTest.java`

A `ForbiddenPrefixCursor` encapsulates the per-set state: the current `forbiddenSet`, the depth-indexed delta stack, and the placement/backtrack logic. The enumerator owns one cursor per descent.

**Step 1: Write the failing test**

```java
package org.matsim.contrib.demand_extraction.algorithm.extension;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ForbiddenPrefixCursorTest {

    @Test
    void emptyIndexNeverForbids() {
        ForbiddenPrefixIndex index = new ForbiddenPrefixIndex();
        index.commit();
        ForbiddenPrefixCursor cursor = new ForbiddenPrefixCursor(index, 4);

        cursor.place(10);
        cursor.place(20);
        assertFalse(cursor.isForbidden(30));
        assertFalse(cursor.isForbidden(40));
    }

    @Test
    void recordedTripleForbidsThirdStop() {
        ForbiddenPrefixIndex index = new ForbiddenPrefixIndex();
        index.recordPending(new int[]{10, 20, 30});
        index.commit();

        ForbiddenPrefixCursor cursor = new ForbiddenPrefixCursor(index, 4);
        cursor.place(10);
        cursor.place(20);
        assertTrue(cursor.isForbidden(30));
        assertFalse(cursor.isForbidden(40));
    }

    @Test
    void differentOrderingDoesNotTrigger() {
        // Recorded: (10, 20, 30) infeasible. Sequence (20, 10, 30) is NOT.
        ForbiddenPrefixIndex index = new ForbiddenPrefixIndex();
        index.recordPending(new int[]{10, 20, 30});
        index.commit();

        ForbiddenPrefixCursor cursor = new ForbiddenPrefixCursor(index, 4);
        cursor.place(20);
        cursor.place(10);
        assertFalse(cursor.isForbidden(30));
    }

    @Test
    void backtrackRemovesForbiddenAdditions() {
        ForbiddenPrefixIndex index = new ForbiddenPrefixIndex();
        index.recordPending(new int[]{10, 20, 30});
        index.commit();

        ForbiddenPrefixCursor cursor = new ForbiddenPrefixCursor(index, 4);
        cursor.place(10);
        cursor.place(20);
        assertTrue(cursor.isForbidden(30));
        cursor.unplace(); // pop "20"
        assertFalse(cursor.isForbidden(30)); // now only (10) placed → no triple to fire
    }

    @Test
    void quadKeyForbidsFourthStop() {
        ForbiddenPrefixIndex index = new ForbiddenPrefixIndex();
        index.recordPending(new int[]{10, 20, 30, 40});
        index.commit();

        ForbiddenPrefixCursor cursor = new ForbiddenPrefixCursor(index, 5);
        cursor.place(10);
        cursor.place(20);
        cursor.place(30);
        assertTrue(cursor.isForbidden(40));
        assertFalse(cursor.isForbidden(50));
    }

    @Test
    void subsequenceMatchesNotJustContiguous() {
        // Recorded: (10, 30, 50) infeasible. Place 10, 20, 30, 40 → 50 should be forbidden
        // because the placed sequence contains (10, 30) as ordered subsequence.
        ForbiddenPrefixIndex index = new ForbiddenPrefixIndex();
        index.recordPending(new int[]{10, 30, 50});
        index.commit();

        ForbiddenPrefixCursor cursor = new ForbiddenPrefixCursor(index, 6);
        cursor.place(10);
        cursor.place(20);
        cursor.place(30);
        cursor.place(40);
        assertTrue(cursor.isForbidden(50));
    }
}
```

**Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=ForbiddenPrefixCursorTest -q`
Expected: FAIL — `ForbiddenPrefixCursor` not defined.

**Step 3: Implement**

```java
package org.matsim.contrib.demand_extraction.algorithm.extension;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

/**
 * Per-set push-based forbidden-set cursor.
 *
 * <p>Tracks the placed-stop prefix during a single B&amp;B descent. On each placement,
 * looks up subsequences of prior placements ending at the newly placed stop in the
 * {@link ForbiddenPrefixIndex} and unions the forbidden-next sets into a per-depth
 * delta. On backtrack, removes the delta from the active forbidden set.
 *
 * <p>NOT thread-safe — one cursor per worker thread per set.
 */
public final class ForbiddenPrefixCursor {

    private final ForbiddenPrefixIndex index;
    private final int maxKeyLength;

    private final int[] placed;       // placed stops in order, length = capacity
    private int depth = 0;

    private final IntOpenHashSet forbiddenSet = new IntOpenHashSet();
    private final IntOpenHashSet[] deltaStack; // deltaStack[d] = stops added by depth d's place()

    // Reusable scratch buffer for building the prefix-key during lookups
    private final int[] scratchKey;

    public ForbiddenPrefixCursor(ForbiddenPrefixIndex index, int capacity) {
        this.index = index;
        this.maxKeyLength = Math.max(2, index.getMaxRecordedKeyLength());
        this.placed = new int[capacity];
        this.deltaStack = new IntOpenHashSet[capacity];
        this.scratchKey = new int[maxKeyLength];
    }

    /** Returns true if the given stop is currently forbidden as the next placement. */
    public boolean isForbidden(int stop) {
        return forbiddenSet.contains(stop);
    }

    /**
     * Place a stop at the current depth. Updates forbiddenSet by looking up all
     * ordered subsequences of prior placements ending at the new stop, of length
     * 2 .. maxKeyLength.
     */
    public void place(int stop) {
        placed[depth] = stop;
        IntOpenHashSet delta = new IntOpenHashSet();

        // For each subsequence-key length L, choose (L-1) ordered prior positions
        // and append `stop` as the last element.
        // Then look up the index for that key.
        int maxL = Math.min(maxKeyLength, depth + 1);
        for (int L = 2; L <= maxL; L++) {
            enumerateAndLookup(stop, L, delta);
        }

        // Union delta into forbiddenSet, but only the elements not already present
        // (so backtrack only removes what THIS placement added).
        IntOpenHashSet additions = new IntOpenHashSet();
        for (int s : delta) {
            if (forbiddenSet.add(s)) additions.add(s);
        }
        deltaStack[depth] = additions;
        depth++;
    }

    /** Roll back the most recent placement. */
    public void unplace() {
        depth--;
        IntOpenHashSet additions = deltaStack[depth];
        if (additions != null) {
            for (int s : additions) forbiddenSet.remove(s);
            deltaStack[depth] = null;
        }
    }

    public int depth() { return depth; }

    /**
     * Enumerate all (L-1)-element ordered subsets of placed[0..depth-1], append
     * placed[depth] (= stop), look up the resulting prefix in the index, and add
     * any forbidden stops to {@code delta}.
     */
    private void enumerateAndLookup(int stop, int L, IntOpenHashSet delta) {
        int prefixLen = L; // total prefix length including the just-placed stop
        if (prefixLen - 1 > depth) return; // not enough prior placements

        // Recursive enumeration of (L-1) positions from [0..depth-1] in increasing order.
        scratchKey[L - 1] = stop;
        enumerate(0, 0, L - 1, delta, L);
    }

    private void enumerate(int start, int chosen, int needed, IntOpenHashSet delta, int totalLen) {
        if (chosen == needed) {
            IntOpenHashSet forbidden = index.lookup(java.util.Arrays.copyOf(scratchKey, totalLen));
            if (forbidden != null) delta.addAll(forbidden);
            return;
        }
        int remaining = needed - chosen;
        for (int p = start; p <= depth - remaining; p++) {
            scratchKey[chosen] = placed[p];
            enumerate(p + 1, chosen + 1, needed, delta, totalLen);
        }
    }
}
```

**Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=ForbiddenPrefixCursorTest -q`
Expected: PASS, 6 tests.

**Step 5: Commit**

```bash
cd matsim-libs && git add contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/ForbiddenPrefixCursor.java contribs/drt-demand-extraction/src/test/java/org/matsim/contrib/demand_extraction/algorithm/extension/ForbiddenPrefixCursorTest.java && git commit -m "$(cat <<'EOF'
feat: ForbiddenPrefixCursor — per-set push-based forbidden set with backtrack

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Performance — preallocate scratch buffers, avoid `Arrays.copyOf` per lookup

**Files:**
- Modify: `ForbiddenPrefixIndex.java`
- Modify: `ForbiddenPrefixCursor.java`

**Step 1: Add a length-aware lookup overload**

In `ForbiddenPrefixIndex.java`, add:

```java
/** Lookup using a sub-range of an existing array (no allocation). */
public IntOpenHashSet lookup(int[] prefix, int from, int to) {
    return committed.get(hashRange(prefix, from, to));
}

static long hashRange(int[] seq, int from, int to) {
    long h = 0;
    for (int i = from; i < to; i++) h = h * HASH_PRIME + seq[i];
    return h;
}
```

**Step 2: Use it in the cursor**

In `ForbiddenPrefixCursor.enumerate`, replace:

```java
IntOpenHashSet forbidden = index.lookup(java.util.Arrays.copyOf(scratchKey, totalLen));
```

with:

```java
IntOpenHashSet forbidden = index.lookup(scratchKey, 0, totalLen);
```

**Step 3: Run all extension tests**

Run: `mvn test -Dtest='ForbiddenPrefix*' -q`
Expected: PASS, 9 tests total.

**Step 4: Commit**

```bash
cd matsim-libs && git add contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/ForbiddenPrefixIndex.java contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/ForbiddenPrefixCursor.java && git commit -m "$(cat <<'EOF'
perf: allocation-free lookup in ForbiddenPrefixCursor

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 2 — Wire the index into enumeration (no behavior change yet)

### Task 5: Add `ForbiddenPrefixIndex` parameter through the call chain

**Files:**
- Modify: `OrderingEnumerator.java`
- Modify: `RideExtender.java`
- Modify: `ExMasEngine.java`

**Step 1: Read current signatures**

Read the signatures of `OrderingEnumerator.enumerateOriginsPrunedWithEval(...)` (or whichever public entry point currently takes `SubSetOrderingFeasibility subsetFeasibility`), `RideExtender.processSet(...)`, and the engine creation site for these.

**Step 2: Add parameter**

Add a `ForbiddenPrefixIndex prefixIndex` parameter alongside the existing `SubSetOrderingFeasibility subsetFeasibility` (do NOT remove the old one yet — we'll run them in parallel until ride counts match). In `ExMasEngine`, allocate `ForbiddenPrefixIndex prefixIndex = new ForbiddenPrefixIndex();` next to the `subsetFeasibility` allocation. Pass through.

In `OrderingEnumerator`, add a private field but **do not use it yet** — only store the reference.

**Step 3: Build + regression**

Run: `mvn test -Dtest=ExMasKelheimE2ETest -q`
Expected: PASS (no behavior change).

**Step 4: Commit**

```bash
cd matsim-libs && git add contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/engine/ExMasEngine.java contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/RideExtender.java contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingEnumerator.java && git commit -m "$(cat <<'EOF'
feat: thread ForbiddenPrefixIndex through extension pipeline (no-op wiring)

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: Add `prunedByForbidden` stats counter

**Files:**
- Modify: `EnumerationStats.java`

**Step 1: Add counter field + report line**

Add `public long prunedByForbidden;` next to existing counters. Add it to the per-degree report log line so we can profile during the benchmark.

**Step 2: Build**

Run: `mvn test-compile -q`
Expected: clean.

**Step 3: Commit**

```bash
cd matsim-libs && git add contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/EnumerationStats.java && git commit -m "$(cat <<'EOF'
feat: add prunedByForbidden counter to EnumerationStats

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 3 — Recording at failure triggers

### Task 7: Record at Trigger 2 (all-dest-fail, origin-only)

**Files:**
- Modify: `OrderingEnumerator.java`

**Step 1: Find Trigger 2 site**

Search for the existing Trigger 2 code path (the `subsetFeasibility.recordExactOrdering(...)` call at the all-dest-fail branch in `enumerateOriginsPrunedWithEval` — around line 241 in current master). Read it to understand the local variables: `requestIndices`, `perm`, `n`, victim info.

**Step 2: Add ForbiddenPrefixIndex recording alongside the existing call**

When all dest orderings have failed for a given origin perm, identify the victim's shortest-failing-prefix from the routing data already computed during dest enumeration (the failing dest ordering's stop sequence up to the victim's first time-out stop). Encode using the unified stop encoding:

```java
// localPerm = origin permutation (local indices into the sorted set)
// victimLocalIdx = local index of the victim
// failingStopSequence = ordered stop IDs (origin = 2*globalIdx, dest = 2*globalIdx+1)
//                       from victim's pickup up to and including the bust stop
int[] seq = buildShortestFailingPrefix(requestIndices, localPerm, victimLocalIdx, ...);
prefixIndex.recordPending(seq);
```

The exact derivation depends on what's already in scope at the trigger site. Add a helper `buildShortestFailingPrefix` if needed — keep it in `OrderingEnumerator` for now.

**Step 3: Compile + regression**

Run: `mvn test -Dtest=ExMasKelheimE2ETest -q`
Expected: PASS — recording does not affect lookups yet (Phase 4 wires lookups in).

**Step 4: Commit**

```bash
cd matsim-libs && git add contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingEnumerator.java && git commit -m "$(cat <<'EOF'
feat: record shortest failing prefix at Trigger 2 (all-dest-fail)

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: Record at origin-phase Check A (mechanism 1)

**Files:**
- Modify: `OrderingEnumerator.java`

**Step 1: Locate the existing Check A**

Search for `subsetFeasibility.recordExactOrdering(requestIndices, perm, p, depth)` (around line 261 in current master). Read the surrounding context — the failing partial origin sequence is `perm[p..depth]`.

**Step 2: Add `prefixIndex.recordPending(...)` next to it**

Build the unified-stop-encoded failing prefix from `perm[p..depth]` (all stops are origins → `2 * requestIndices[perm[i]]`). Record.

**Step 3: Regression**

Run: `mvn test -Dtest=ExMasKelheimE2ETest -q`
Expected: PASS.

**Step 4: Commit**

```bash
cd matsim-libs && git add contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingEnumerator.java && git commit -m "$(cat <<'EOF'
feat: record shortest failing prefix at origin-phase Check A

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: Record at dest-phase Check A (NEW)

**Files:**
- Modify: `OrderingEnumerator.java`

**Step 1: Locate the dest-phase routing/check**

Find the dest enumeration loop. Identify where a victim's in-vehicle time is checked during dest stop placement. (The exact location depends on the current dest-enum implementation — look for the per-stop `inVehicleTime > maxTravelTime` check.)

**Step 2: Record on first violation**

When a victim times out during dest placement, build the unified failing prefix: origin stops in order, then dest stops in order up to and including the bust stop. Call `prefixIndex.recordPending(seq)`.

**Step 3: Commit + regression**

Run: `mvn test -Dtest=ExMasKelheimE2ETest -q`
Expected: PASS.

```bash
cd matsim-libs && git add contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingEnumerator.java && git commit -m "$(cat <<'EOF'
feat: record shortest failing prefix at dest-phase Check A (new lever)

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 10: Commit pending → committed between degrees

**Files:**
- Modify: `ExMasEngine.java`

**Step 1: Call `prefixIndex.commit()` after each degree pass**

Find where `subsetFeasibility.commit()` is called (around line 160). Add `prefixIndex.commit()` right next to it. Log the size:

```java
LOG.info("ForbiddenPrefixIndex at degree {}: {} keys, max length {}",
         degree, prefixIndex.size(), prefixIndex.getMaxRecordedKeyLength());
```

**Step 2: Regression + check log**

Run: `mvn test -Dtest=ExMasKelheimE2ETest -q`
Expected: PASS. Log should show the index growing across degrees.

**Step 3: Commit**

```bash
cd matsim-libs && git add contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/engine/ExMasEngine.java && git commit -m "$(cat <<'EOF'
feat: commit ForbiddenPrefixIndex between degrees with size logging

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 4 — Use the index for pruning

### Task 11: Wire `ForbiddenPrefixCursor` into origin enumeration

**Files:**
- Modify: `OrderingEnumerator.java`

**Step 1: Allocate cursor at set entry**

In `enumerateOriginsPrunedWithEval` (or the public entry), at the start of processing a set:

```java
ForbiddenPrefixCursor cursor = new ForbiddenPrefixCursor(prefixIndex, 2 * n);
```

(Capacity is `2 * n` because the same cursor will carry over into dest enumeration.)

**Step 2: Add `place` / `isForbidden` / `unplace` calls in the origin DFS**

In the recursive origin DFS:
- Before considering candidate `c`: if `cursor.isForbidden(2 * requestIndices[c])` → skip, increment `stats.prunedByForbidden`.
- After committing `c` to `perm[depth]`: `cursor.place(2 * requestIndices[c])`.
- Before returning from depth `d`: `cursor.unplace()`.

**Important**: keep the existing `subsetFeasibility.isInfeasible(...)` call **alongside** the new check in this commit. We want to verify they prune the same orderings before removing the old path. Add temporary assertions or counters to compare.

**Step 3: Regression — ride counts must match**

Run: `mvn test -Dtest=ExMasKelheimE2ETest -q`
Expected: PASS, ride counts identical.

**Step 4: Commit**

```bash
cd matsim-libs && git add contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingEnumerator.java && git commit -m "$(cat <<'EOF'
feat: wire ForbiddenPrefixCursor into origin enumeration (parallel to old path)

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 12: Carry cursor through to dest enumeration

**Files:**
- Modify: `OrderingEnumerator.java`

**Step 1: Pass cursor into dest enumeration**

When the origin perm is complete (all *n* origins placed), the cursor's `forbiddenSet` already reflects all (origin-prefix, next-something) entries. Pass the SAME cursor into the dest enumeration recursion.

**Step 2: Apply `place` / `isForbidden` / `unplace` in the dest DFS**

Same pattern as origins, but with stop encoding `2 * requestIndices[c] + 1` (destinations have the odd-bit set).

**Step 3: Regression**

Run: `mvn test -Dtest=ExMasKelheimE2ETest -q`
Expected: PASS, ride counts identical.

**Step 4: Commit**

```bash
cd matsim-libs && git add contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingEnumerator.java && git commit -m "$(cat <<'EOF'
feat: carry ForbiddenPrefixCursor from origin into dest enumeration

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 13: Initial benchmark — both paths active

**Files:**
- Create: `docs/plans/2026-04-13-forbidden-prefix-index-session-log.md`

**Step 1: Run 10% Bavaria benchmark**

Use the standard Bavaria 30km command, output dir `outputs/forbidden-prefix-shadow`:

```bash
cd matsim-libs/contribs/drt-demand-extraction && mvn exec:java -Denforcer.skip=true -Dexec.mainClass=org.matsim.contrib.demand_extraction.run.RunBavaria30kmDemandExtraction -Dexec.args="--scenario-path ../../../matsim_scenarios/bavaria/output/kelheim_30km_100pct --population ../../../matsim_scenarios/bavaria/output/populations/population_10pct_kelheim30km.xml.gz --sample 100 --iterations 0 --trip-filter-radius 30 --filter-municipality Kelheim --shapes ../../../matsim_scenarios/bavaria/data/germany/tmp_vg250/vg250-ew_12-31.utm32s.gpkg.ebenen/vg250-ew_ebenen_1231/DE_VG250.gpkg --travel-times ../../../matsim_scenarios/bavaria/output/base-simulation-10pct/travel_times.tsv --no-predecessors --max-degree 8 --output-dir ../../../outputs/forbidden-prefix-shadow"
```

**Step 2: Verify ride counts match `outputs/diag-run` baseline**

Compare deg 3–8 ride counts. Any mismatch → bug, debug before proceeding to Phase 5.

**Step 3: Record per-degree time + index growth**

In `docs/plans/2026-04-13-forbidden-prefix-index-session-log.md`, write:

```markdown
# Forbidden-Prefix Index — Session Log

## Run 1: Both paths active (recording from prefix index, lookups from both)

| Deg | Old time (s) | New time (s) | Rides match? | Index keys | Max key length | prunedByForbidden |
|-----|--------------|--------------|--------------|-----------|---------------|--------------------|
```

**Step 4: Commit log**

```bash
cd /c/Users/VWAUCCY/dev/msf/projects/Dissertation && git add docs/plans/2026-04-13-forbidden-prefix-index-session-log.md && git commit -m "$(cat <<'EOF'
docs: forbidden-prefix shadow run baseline check

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 5 — Remove the old path

### Task 14: Remove `SubSetOrderingFeasibility` calls

**Files:**
- Modify: `OrderingEnumerator.java`, `RideExtender.java`, `ExMasEngine.java`

**Step 1: Verify the new path subsumes the old**

Re-run benchmark (Task 13). Confirm `prunedByForbidden` accounts for the same orderings the old `subsetFeasibility.isInfeasible` was pruning. If counts differ materially, debug — do NOT proceed.

**Step 2: Delete the calls (not the file)**

Remove all `subsetFeasibility.*(...)` calls and the parameter from method signatures in the three files. Leave `SubSetOrderingFeasibility.java` itself in place (dead code, restorable).

**Step 3: Build + full E2E test suite**

Run: `mvn test -q`
Expected: all tests pass.

**Step 4: Commit**

```bash
cd matsim-libs && git add contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/engine/ExMasEngine.java contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/RideExtender.java contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingEnumerator.java && git commit -m "$(cat <<'EOF'
refactor: remove SubSetOrderingFeasibility wiring (replaced by ForbiddenPrefixIndex)

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 15: Bavaria benchmark — exact path final

**Files:**
- Modify: `docs/plans/2026-04-13-forbidden-prefix-index-session-log.md`

**Step 1: Run 10% Bavaria with only the new path**

Output dir: `outputs/forbidden-prefix-final`. Same command as Task 13.

**Step 2: Compare per-degree time vs `outputs/diag-run` baseline**

Append to the session log:

```markdown
## Run 2: ForbiddenPrefixIndex only (old path deleted)

| Deg | diag-run baseline (s) | new (s) | Speedup | Rides baseline | Rides new | Δ rides |
|-----|----------------------|---------|---------|----------------|-----------|---------|
```

Acceptance: ride counts identical at deg 3–8, ≥2× speedup at deg 7, ≥3× speedup at deg 8.

**Step 3: If acceptance fails**

Reset to commit `8edf89a` (parent) / `a495af2` (submodule) and rethink. Do not proceed to Task 16 with bad numbers.

**Step 4: Commit log**

```bash
cd /c/Users/VWAUCCY/dev/msf/projects/Dissertation && git add docs/plans/2026-04-13-forbidden-prefix-index-session-log.md && git commit -m "$(cat <<'EOF'
docs: forbidden-prefix exact-path benchmark results

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 16: Delete `SubSetOrderingFeasibility` and `OrderingConflicts`

**Files:**
- Delete: `matsim-libs/contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/SubSetOrderingFeasibility.java`
- Delete: `matsim-libs/contribs/drt-demand-extraction/src/test/java/org/matsim/contrib/demand_extraction/algorithm/extension/SubSetOrderingFeasibilityTest.java`
- Delete: `matsim-libs/contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingConflicts.java`
- Delete: `matsim-libs/contribs/drt-demand-extraction/src/test/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingConflictsTest.java`

**Step 1: Verify no remaining references**

Grep tool: pattern `SubSetOrderingFeasibility|OrderingConflicts`, path `matsim-libs/contribs/drt-demand-extraction/src/`. Expected: only the files being deleted.

**Step 2: Delete + run all tests**

```bash
cd matsim-libs && git rm contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/SubSetOrderingFeasibility.java contribs/drt-demand-extraction/src/test/java/org/matsim/contrib/demand_extraction/algorithm/extension/SubSetOrderingFeasibilityTest.java contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingConflicts.java contribs/drt-demand-extraction/src/test/java/org/matsim/contrib/demand_extraction/algorithm/extension/OrderingConflictsTest.java
cd contribs/drt-demand-extraction && mvn test -q
```

Expected: all tests pass.

**Step 3: Commit**

```bash
cd matsim-libs && git commit -m "$(cat <<'EOF'
refactor: delete SubSetOrderingFeasibility and OrderingConflicts (superseded)

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 17: Sync submodule pointer in parent repo

**Step 1: Update pointer + commit in parent**

```bash
cd /c/Users/VWAUCCY/dev/msf/projects/Dissertation && git add matsim-libs && git commit -m "$(cat <<'EOF'
submodule: ForbiddenPrefixIndex replaces sub-set feasibility hot path

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

**Step 2: Final regression**

Run: `cd matsim-libs/contribs/drt-demand-extraction && mvn test -q`
Expected: all tests pass.

---

## Notes for the executing engineer

- **TDD discipline.** Tasks 1–4 build trusted primitives via failing test → implementation → passing test. Tasks 5–12 hook those primitives into a 789-line existing enumerator. Don't skip the per-task regression on `ExMasKelheimE2ETest` — it's the smallest reproducible scenario for this code path.
- **Two parallel paths between Tasks 11 and 14.** During this window, both `SubSetOrderingFeasibility` and `ForbiddenPrefixIndex` are active. They MUST prune the same orderings (use the diagnostic counters added in Task 6 to verify). If they diverge, the bug is in the new path's recording or lookup logic.
- **Don't decompose recordings.** The shortest-failing-prefix is exactly that — the smallest sub-sequence that proves the failure. Do NOT also insert shorter prefixes "for safety". That was the original bug.
- **Subsequence ≠ contiguous.** When `ForbiddenPrefixCursor.place(stop)` enumerates ordered subsequences ending at `stop`, those subsequences are positions in `placed[]`, not contiguous suffixes. The test `subsequenceMatchesNotJustContiguous` in Task 3 is the canary for this.
- **Backtrack discipline.** Every `place()` MUST be paired with an `unplace()` on the way out of the recursive call. Missed `unplace` calls leak forbidden state across siblings → silent ride loss. Add an assertion that `cursor.depth() == 0` at the top-level return point.
- **Memory.** Watch the index size at degrees 5–7 in the run log. If it exceeds ~500 MB peak, switch the `IntOpenHashSet` value to a packed-int representation for small sets.
- Use @superpowers:systematic-debugging if any ride-count regression appears.
- Use @superpowers:verification-before-completion before claiming any task complete — every task ends with running a test and checking output, not assuming.
- **Path H (heuristic insertion from top-*M* parent orderings)** is intentionally out of scope here. Once exact-path numbers land, write a follow-up plan for it.
