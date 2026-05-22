# ExMAS Scalability Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make ExMAS demand extraction feasible for 100% Bavaria (~245k requests) by adding a beeline pre-filter to pair generation and enabling post-graph degree-2 pruning.

**Architecture:** Two independent changes: (1) a Euclidean distance check in `PairGenerator` that rejects pairs before any network routing if the beeline shared path already exceeds the detour limit, (2) a config change to enable existing post-graph pair pruning at degree 2 with a gentler threshold.

**Tech Stack:** Java 17, MATSim, JUnit 5

**Design doc:** `docs/plans/2026-03-26-exmas-scalability-design.md`

---

### Task 1: Unit Test for Beeline Detour Calculation

**Files:**
- Create: `matsim-libs/contribs/drt-demand-extraction/src/test/java/org/matsim/contrib/demand_extraction/algorithm/generation/BeelineDetourFilterTest.java`

**Step 1: Write the test**

```java
package org.matsim.contrib.demand_extraction.algorithm.generation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for beeline detour pre-filter logic.
 *
 * The filter rejects candidate pairs where the Euclidean (beeline) shared path
 * distance already exceeds the maximum allowed network distance (directDistance × maxDetourFactor).
 * Since beeline ≤ network distance, this has zero false negatives.
 */
class BeelineDetourFilterTest {

    private static double beeline(double x1, double y1, double x2, double y2) {
        double dx = x2 - x1, dy = y2 - y1;
        return Math.sqrt(dx * dx + dy * dy);
    }

    @Test
    void fifo_sameDirection_shouldPass() {
        // Two passengers going east, close together — low beeline detour
        // O_i=(0,0) D_i=(100,0), O_j=(10,5) D_j=(110,5)
        // FIFO passenger i path: O_i→O_j→D_i
        double beeSharedI = beeline(0, 0, 10, 5) + beeline(10, 5, 100, 0);
        double directDistI = 100.0; // network direct distance (stored in request)
        // beeSharedI ≈ 11.2 + 90.1 = 101.3, limit = 100 * 1.5 = 150
        assertTrue(beeSharedI <= directDistI * 1.5, "Same-direction pair should pass beeline filter");
    }

    @Test
    void fifo_oppositeDirection_shouldFail() {
        // Passenger i goes east, passenger j goes west — huge detour
        // O_i=(0,0) D_i=(100,0), O_j=(100,0) D_j=(0,0)
        // FIFO passenger i path: O_i→O_j→D_i = 0→100→100 = 100+0 = 100...
        // Actually same endpoints, bad example. Use offset:
        // O_i=(0,0) D_i=(100,0), O_j=(90,10) D_j=(-10,10)
        // FIFO passenger i: O_i→O_j→D_i
        double beeSharedI = beeline(0, 0, 90, 10) + beeline(90, 10, 100, 0);
        double directDistI = 100.0;
        // beeSharedI ≈ 90.6 + 14.1 = 104.7, limit = 150 — might still pass for i
        // FIFO passenger j: O_j→D_i→D_j
        double beeSharedJ = beeline(90, 10, 100, 0) + beeline(100, 0, -10, 10);
        double directDistJ = beeline(90, 10, -10, 10); // ≈ 100
        // beeSharedJ ≈ 14.1 + 110.5 = 124.6, limit = 100 * 1.5 = 150 — borderline
        // Use more extreme example:
        // O_i=(0,0) D_i=(1000,0), O_j=(1000,0) D_j=(0,100)
        double beeSharedJ2 = beeline(1000, 0, 1000, 0) + beeline(1000, 0, 0, 100);
        double directDistJ2 = beeline(1000, 0, 0, 100); // ≈ 1005
        // beeSharedJ2 = 0 + 1005 = 1005, limit = 1005 * 1.5 = 1508 — still passes
        // The test should use a clearly infeasible geometry:
        // O_i=(0,0) D_i=(100,0), O_j=(0,200) D_j=(100,200) — perpendicular offset 200
        double beeI = beeline(0, 0, 0, 200) + beeline(0, 200, 100, 0);
        double dirI = 100.0;
        // beeI = 200 + 223.6 = 423.6, limit = 150 → FAIL
        assertTrue(beeI > dirI * 1.5, "Large perpendicular offset should fail beeline filter");
    }

    @Test
    void lifo_passengerI_longDetour_shouldFail() {
        // LIFO: passenger i travels O_i→O_j→D_j→D_i (picks up j, drops j, then goes to own dest)
        // O_i=(0,0) D_i=(100,0), O_j=(0,300) D_j=(100,300) — j is 300m away perpendicular
        double beeSharedI = beeline(0, 0, 0, 300) + beeline(0, 300, 100, 300) + beeline(100, 300, 100, 0);
        double directDistI = 100.0;
        // beeSharedI = 300 + 100 + 300 = 700, limit = 150 → FAIL
        assertTrue(beeSharedI > directDistI * 1.5, "LIFO with far-away passenger should fail for i");
    }

    @Test
    void lifo_passengerJ_alwaysDirect() {
        // LIFO: passenger j travels O_j→D_j (rides directly, no detour in LIFO)
        // So beeline check for j in LIFO is just beeline(O_j, D_j) vs directDistance_j
        // Since beeline ≤ network, this always passes — no need to check j in LIFO
        double beeSharedJ = beeline(0, 300, 100, 300); // = 100
        double directDistJ = 100.0; // network distance ≥ beeline
        assertTrue(beeSharedJ <= directDistJ * 1.5, "LIFO passenger j always passes (direct ride)");
    }

    @Test
    void exactlyAtLimit_shouldPass() {
        // Beeline shared = directDistance * maxDetourFactor exactly → should pass (<=)
        // O_i=(0,0) D_i=(100,0), O_j=(50,0) — j is on the direct path
        // FIFO i: O_i→O_j→D_i = 50 + 50 = 100, limit = 100 * 1.5 = 150 → pass
        double beeSharedI = beeline(0, 0, 50, 0) + beeline(50, 0, 100, 0);
        double directDistI = 100.0;
        assertTrue(beeSharedI <= directDistI * 1.5);
    }
}
```

**Step 2: Run test to verify it passes (pure math, no implementation yet)**

Run: `cd matsim-libs/contribs/drt-demand-extraction && mvn test -Dtest=BeelineDetourFilterTest -Denforcer.skip=true -o`
Expected: PASS (tests are self-contained math assertions, no external dependencies)

**Step 3: Commit**

```bash
git add src/test/java/org/matsim/contrib/demand_extraction/algorithm/generation/BeelineDetourFilterTest.java
git commit -m "test: add unit tests for beeline detour pre-filter logic"
```

---

### Task 2: Implement Beeline Pre-Filter in PairGenerator

**Files:**
- Modify: `matsim-libs/contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/generation/PairGenerator.java:159-194`

**Step 1: Add beeline distance helper method**

Add to `PairGenerator.java` after the `formatDuration` method (~line 154):

```java
/**
 * Euclidean (beeline) distance between two points.
 */
private static double beeline(double x1, double y1, double x2, double y2) {
    double dx = x2 - x1, dy = y2 - y1;
    return Math.sqrt(dx * dx + dy * dy);
}
```

**Step 2: Add beeline pre-filter in `generateCandidatesForRequest`**

In `generateCandidatesForRequest()`, after the temporal filter (line 173) and BEFORE the first routing call (line 177), add:

```java
// Beeline pre-filter: reject pairs where the Euclidean shared path
// already exceeds the max allowed distance. Since beeline ≤ network
// distance, this has zero false negatives.
// Check FIFO: passenger i travels O_i→O_j→D_i, passenger j travels O_j→D_i→D_j
double beeOO = beeline(reqI.originX, reqI.originY, reqJ.originX, reqJ.originY);
double beeOD = beeline(reqJ.originX, reqJ.originY, reqI.destinationX, reqI.destinationY);
double beeDD = beeline(reqI.destinationX, reqI.destinationY, reqJ.destinationX, reqJ.destinationY);
double beeOJ = beeline(reqJ.originX, reqJ.originY, reqJ.destinationX, reqJ.destinationY);
double beeJD = beeline(reqJ.destinationX, reqJ.destinationY, reqI.destinationX, reqI.destinationY);

boolean fifoFeasible =
    (beeOO + beeOD) <= reqI.directDistance * reqI.maxDetourFactor &&  // FIFO passenger i
    (beeOD + beeDD) <= reqJ.directDistance * reqJ.maxDetourFactor;   // FIFO passenger j

// LIFO: passenger i travels O_i→O_j→D_j→D_i, passenger j travels O_j→D_j (direct)
// Passenger j in LIFO rides directly — beeline always ≤ network, so always passes.
boolean lifoFeasible =
    (beeOO + beeOJ + beeJD) <= reqI.directDistance * reqI.maxDetourFactor;  // LIFO passenger i

if (!fifoFeasible && !lifoFeasible) continue;
```

**Step 3: Pass feasibility flags to FIFO/LIFO attempts**

Replace the existing FIFO/LIFO attempt block:

```java
// Try FIFO (only if beeline check passed)
if (fifoFeasible) {
    PairCandidate fifo = tryFifoCandidate(reqI, reqJ, oo);
    if (fifo != null) results.add(fifo);
}

// Try LIFO (only if beeline check passed)
if (lifoFeasible) {
    PairCandidate lifo = tryLifoCandidate(reqI, reqJ, oo);
    if (lifo != null) results.add(lifo);
}
```

**Step 4: Add logging counter for beeline rejections**

Add a counter field and log the total rejections. In the constructor area, add:

```java
private final java.util.concurrent.atomic.AtomicLong beelineRejected = new java.util.concurrent.atomic.AtomicLong();
```

In the beeline filter block, when both FIFO and LIFO are infeasible:

```java
if (!fifoFeasible && !lifoFeasible) {
    beelineRejected.incrementAndGet();
    continue;
}
```

In `generatePairs()`, after the completion log (around line 130), add:

```java
log.info("  Beeline pre-filter rejected {} candidate pairs before routing", beelineRejected.get());
```

**Step 5: Handle the OO routing call**

The OO routing currently happens before FIFO/LIFO attempts. With the beeline filter, we can skip routing entirely if both are infeasible. But if only one is feasible, we still need OO. The OO routing should move AFTER the beeline check but BEFORE the FIFO/LIFO attempts.

The full rewritten block (lines 170-191):

```java
// Quick temporal filter
if (reqJ.getLatestDeparture() < reqI.getEarliestDeparture() ||
        reqJ.getEarliestDeparture() > reqI.getLatestDeparture() + reqI.getTravelTime()) {
    continue;
}

// Beeline pre-filter: reject pairs where the Euclidean shared path
// already exceeds the max allowed distance (directDistance * maxDetourFactor).
// Since beeline ≤ network distance, this has zero false negatives.
double beeOO = beeline(reqI.originX, reqI.originY, reqJ.originX, reqJ.originY);
double beeOD = beeline(reqJ.originX, reqJ.originY, reqI.destinationX, reqI.destinationY);
double beeDD = beeline(reqI.destinationX, reqI.destinationY, reqJ.destinationX, reqJ.destinationY);
double beeOJ = beeline(reqJ.originX, reqJ.originY, reqJ.destinationX, reqJ.destinationY);
double beeJD = beeline(reqJ.destinationX, reqJ.destinationY, reqI.destinationX, reqI.destinationY);

boolean fifoFeasible =
    (beeOO + beeOD) <= reqI.directDistance * reqI.maxDetourFactor &&
    (beeOD + beeDD) <= reqJ.directDistance * reqJ.maxDetourFactor;

boolean lifoFeasible =
    (beeOO + beeOJ + beeJD) <= reqI.directDistance * reqI.maxDetourFactor;

if (!fifoFeasible && !lifoFeasible) {
    beelineRejected.incrementAndGet();
    continue;
}

// Get origin-to-origin segment (routing call)
TravelSegment oo = network.getSegment(reqI.originLinkId, reqJ.originLinkId, reqI.requestTime);
if (!oo.isReachable()) continue;

// Additional temporal check with travel time
if (reqI.getLatestDeparture() + oo.getTravelTime() < reqJ.getEarliestDeparture()) continue;
if (reqI.getEarliestDeparture() + oo.getTravelTime() > reqJ.getLatestDeparture()) continue;

// Try FIFO (only if beeline check passed)
if (fifoFeasible) {
    PairCandidate fifo = tryFifoCandidate(reqI, reqJ, oo);
    if (fifo != null) results.add(fifo);
}

// Try LIFO (only if beeline check passed)
if (lifoFeasible) {
    PairCandidate lifo = tryLifoCandidate(reqI, reqJ, oo);
    if (lifo != null) results.add(lifo);
}
```

**Step 6: Run existing E2E test to verify no regression**

Run: `cd matsim-libs/contribs/drt-demand-extraction && mvn test -Dtest=ExMasDemandExtractionE2ETest -Denforcer.skip=true -o`
Expected: PASS — the beeline filter only rejects infeasible pairs, so all previously valid pairs should still be generated.

**Step 7: Commit**

```bash
git add src/main/java/org/matsim/contrib/demand_extraction/algorithm/generation/PairGenerator.java
git commit -m "feat: add beeline pre-filter to PairGenerator to skip infeasible pairs before routing"
```

---

### Task 3: Enable Post-Graph Degree-2 Pruning

**Files:**
- Modify: `matsim-libs/contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/run/RunBavaria30kmDemandExtraction.java:656-658`

**Step 1: Change pruning config**

In `RunBavaria30kmDemandExtraction.configureExMas()`, change lines 656-658:

```java
// Before:
exMasConfig.setPruningDistanceSavingsLogScale(0.25);
exMasConfig.setPruningDistanceSavingsMax(0.75);
exMasConfig.setPruningDistanceSavingsMinDegree(3); // do not prune paired rides (degree 2)

// After:
exMasConfig.setPruningDistanceSavingsLogScale(0.20);
exMasConfig.setPruningDistanceSavingsMax(0.75);
exMasConfig.setPruningDistanceSavingsMinDegree(2); // prune paired rides after graph construction
```

This means:
- Degree 2: require 20% distance savings (= `0.20 × log₂(2) = 0.20`)
- Degree 3: require 31.7% savings (= `0.20 × log₂(3)`)
- Degree 4: require 40% savings (= `0.20 × log₂(4)`)
- Degree 5: require 46.4% savings (= `0.20 × log₂(5)`)

**Step 2: Update comment**

```java
// Degree-aware distance-savings pruning:
// requiredSaving(d) = scale * log2(d) (clamped).
// scale < 0 disables; scale = 0 matches legacy non-improving.
// Degree 2 pairs are pruned as extension BASES only (after shareability graph
// construction). Pruned pairs remain in allRides as pair support for tryExtend
// validation, so higher-degree rides can still be discovered via alternate pair paths.
exMasConfig.setPruningDistanceSavingsLogScale(0.20);
exMasConfig.setPruningDistanceSavingsMax(0.75);
exMasConfig.setPruningDistanceSavingsMinDegree(2);
```

**Step 3: Run E2E test**

Run: `cd matsim-libs/contribs/drt-demand-extraction && mvn test -Dtest=ExMasDemandExtractionE2ETest -Denforcer.skip=true -o`
Expected: PASS — the E2E test uses a small scenario where most pairs save more than 10%, so pruning shouldn't affect it. If it fails, the test may need updated expected counts.

**Step 4: Commit**

```bash
git add src/main/java/org/matsim/contrib/demand_extraction/run/RunBavaria30kmDemandExtraction.java
git commit -m "feat: enable post-graph degree-2 pair pruning with 10% distance-savings threshold"
```

---

### Task 4: Smoke Test with Bavaria 1% (Quick Validation)

**Files:**
- Modify: `matsim-libs/contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/run/RunBavariaKelheim30kmComparison.java`

**Step 1: Set to 1% for quick validation**

Change `SAMPLE_PERCENT = 25` to `SAMPLE_PERCENT = 1` temporarily.

**Step 2: Run and compare**

Run: `cd matsim-libs/contribs/drt-demand-extraction && mvn exec:java -o -Dexec.mainClass="org.matsim.contrib.demand_extraction.run.RunBavariaKelheim30kmComparison" -Denforcer.skip=true`

Expected output in log:
- "Beeline pre-filter rejected N candidate pairs before routing" — N should be significant (>50% of candidates)
- "Pair-ride base pruning (after graph): kept X/Y" — should show meaningful reduction
- Total rides should be LOWER than the previous 1% run (31,465 rides) because fewer pairs survive as extension bases
- But the difference should be modest — most good pairs pass both filters

**Step 3: Compare results**

```bash
python -c "
import pandas as pd
old = pd.read_csv('matsim_scenarios/bavaria/output/demand-extraction-1pct-kelheim30km/drt_demand/bavaria-30km-1pct-exmas.drt_requests.csv')
new = pd.read_csv('matsim_scenarios/bavaria/output/demand-extraction-1pct-kelheim30km/drt_demand/bavaria-30km-1pct-exmas.drt_requests.csv')  # same output dir, overwritten
print(f'Requests: {len(old)} -> {len(new)}')
# Requests should be identical (filters don't change request generation)
"
```

Request count should be unchanged (filters only affect ride generation, not request extraction).

**Step 4: Revert to 25% and commit**

Change `SAMPLE_PERCENT` back to `25`.

```bash
git add src/main/java/org/matsim/contrib/demand_extraction/run/RunBavariaKelheim30kmComparison.java
git commit -m "test: validate beeline filter and degree-2 pruning on Bavaria 1%"
```

---

### Task 5: Run Bavaria 25% with Scalability Fixes

**Files:**
- No code changes — run the extraction

**Step 1: Run with increased heap**

```bash
cd matsim-libs/contribs/drt-demand-extraction
export MAVEN_OPTS="-Xmx100g"
nohup mvn exec:java -o \
  -Dexec.mainClass="org.matsim.contrib.demand_extraction.run.RunBavariaKelheim30kmComparison" \
  -Denforcer.skip=true \
  > ../../../matsim_scenarios/bavaria/output/demand-extraction-25pct-kelheim30km-run3.log 2>&1 &
echo "PID: $!"
```

**Step 2: Monitor progress**

```bash
tail -5 matsim_scenarios/bavaria/output/demand-extraction-25pct-kelheim30km-run3.log
grep "Beeline\|Pair-ride base pruning\|Pair generation complete\|Extension\|COMPLETE\|MemoryObserver" \
  matsim_scenarios/bavaria/output/demand-extraction-25pct-kelheim30km-run3.log | tail -20
```

Expected:
- Beeline filter should reject 50-70% of pair candidates
- Post-graph pruning should reduce extension bases by 30-50%
- Peak memory should stay well below 100 GB
- Total runtime: ~4-8 hours (reduced from 9.5h that OOM'd)

**Step 3: Verify output**

```bash
ls -la matsim_scenarios/bavaria/output/demand-extraction-25pct-kelheim30km/drt_demand/
```

All 5 CSV files should be present (drt_requests, exmas_rides, person_attributes, mode_cache, connection_cache).

**Step 4: Commit results note**

No code commit — just verify the run completes successfully.
