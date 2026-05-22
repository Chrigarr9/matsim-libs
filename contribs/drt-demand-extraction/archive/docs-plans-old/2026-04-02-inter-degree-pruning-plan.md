# Mandatory Inter-Degree Percentile Pruning — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** After each degree extension, keep only the top X% of rides by distance savings (default 10%), directly — no sqrt scaling. This bounds memory, enables higher degrees, and caps MIP input at ~170k rides.

**Architecture:** Add two config params (`interDegreeKeepFraction`, `interDegreeMinRidesPerRequest`) to `ExMasConfigGroup`. Replace the sqrt-scaled inter-degree logic in `ExMasEngine` with direct fraction pruning using the existing `PostExtensionPruner`. Add per-request floor rescue logic to `PostExtensionPruner`. Configure in `RunBavaria30kmDemandExtraction`.

**Tech Stack:** Java 17, MATSim, JUnit 5

---

## Task 1: Add config parameters to ExMasConfigGroup

**Files:**
- Modify: `algorithm/../config/ExMasConfigGroup.java`

**Step 1: Add fields** (after `postExtensionKeepTopFraction` field, line ~210)

```java
// Inter-degree pruning: keep only the top fraction of rides after EACH degree extension.
// Applied directly (no sqrt scaling). 1.0 = disabled. 0.10 = keep top 10%.
private double interDegreeKeepFraction = 0.10;
// Per-request floor: ensure each request appears in at least N rides after pruning. 0 = disabled.
private int interDegreeMinRidesPerRequest = 0;
```

**Step 2: Add getters/setters** (after postExtensionKeepTopFraction getter/setter)

```java
@StringGetter("interDegreeKeepFraction")
public double getInterDegreeKeepFraction() {
    return interDegreeKeepFraction;
}

@StringSetter("interDegreeKeepFraction")
public void setInterDegreeKeepFraction(double interDegreeKeepFraction) {
    this.interDegreeKeepFraction = interDegreeKeepFraction;
}

@StringGetter("interDegreeMinRidesPerRequest")
public int getInterDegreeMinRidesPerRequest() {
    return interDegreeMinRidesPerRequest;
}

@StringSetter("interDegreeMinRidesPerRequest")
public void setInterDegreeMinRidesPerRequest(int interDegreeMinRidesPerRequest) {
    this.interDegreeMinRidesPerRequest = interDegreeMinRidesPerRequest;
}
```

**Step 3: Add parameter documentation** (in the `getComments()` map, after postExtensionKeepTopFraction entry)

```java
map.put("interDegreeKeepFraction",
        "Inter-degree pruning: keep only the top fraction of rides (by distanceSavings) after EACH degree extension. "
        + "Applied directly (no sqrt scaling). Survivors become base sets for next degree AND final output. "
        + "1.0 = disabled. 0.10 = keep top 10%. Default: 0.10");
map.put("interDegreeMinRidesPerRequest",
        "Per-request floor for inter-degree pruning: ensure each request appears in at least N rides after pruning. "
        + "If a request has fewer rides above the threshold, its best rides are rescued. "
        + "0 = disabled. Default: 0");
```

**Step 4: Compile**

```bash
cd matsim-libs/contribs/drt-demand-extraction && mvn compile -Denforcer.skip=true -o -q
```

---

## Task 2: Add per-request floor to PostExtensionPruner

**Files:**
- Modify: `algorithm/engine/PostExtensionPruner.java`

**Step 1: Add `minRidesPerRequest` field and constructor** (replace constructor at lines 58-62)

```java
private final int minRidesPerRequest;

/**
 * Explicit pruner with maxPerSet, keepTopFraction, and per-request floor.
 * Set maxPerSet=0 to disable MaxPerSet, keepTopFraction=1.0 to disable percentile,
 * minRidesPerRequest=0 to disable per-request floor.
 */
public PostExtensionPruner(int maxPerSet, double keepTopFraction, int minRidesPerRequest) {
    this.config = null;
    this.maxPerSetOverride = maxPerSet > 0 ? maxPerSet : -1;
    this.keepTopOverride = keepTopFraction;
    this.minRidesPerRequest = minRidesPerRequest;
}
```

**Step 2: Update existing constructors** to set `minRidesPerRequest = 0`

```java
public PostExtensionPruner(ExMasConfigGroup config) {
    this.config = config;
    this.maxPerSetOverride = -1;
    this.keepTopOverride = -1;
    this.minRidesPerRequest = 0;
}

public PostExtensionPruner(int maxPerSet) {
    this.config = null;
    this.maxPerSetOverride = maxPerSet;
    this.keepTopOverride = 1.0;
    this.minRidesPerRequest = 0;
}

public PostExtensionPruner(int maxPerSet, double keepTopFraction) {
    this(maxPerSet, keepTopFraction, 0);
}
```

**Step 3: Add per-request floor logic to `applyPerDegreePercentile`**

After the threshold-based keep loop (line ~184), add:

```java
// Per-request floor: rescue rides for requests with too few options
if (minRidesPerRequest > 0) {
    // Count rides per request in kept set
    Map<Integer, Integer> requestRideCount = new HashMap<>();
    for (Ride ride : kept) {
        if (ride.getDegree() <= 1) continue;
        for (DrtRequest req : ride.getRequests()) {
            requestRideCount.merge(req.index, 1, Integer::sum);
        }
    }

    // Find requests below floor from the pruned rides at this degree
    List<Ride> pruned = new ArrayList<>();
    for (int i = 0; i < group.size(); i++) {
        if (savings[i] < threshold) {
            pruned.add(group.get(i));
        }
    }

    // Sort pruned rides by savings descending (best first)
    double[] finalSavings = savings;
    List<Ride> finalGroup = group;
    pruned.sort((a, b) -> {
        int idxA = finalGroup.indexOf(a);
        int idxB = finalGroup.indexOf(b);
        return Double.compare(finalSavings[idxB], finalSavings[idxA]);
    });

    int rescued = 0;
    for (Ride ride : pruned) {
        boolean needed = false;
        for (DrtRequest req : ride.getRequests()) {
            if (requestRideCount.getOrDefault(req.index, 0) < minRidesPerRequest) {
                needed = true;
                break;
            }
        }
        if (needed) {
            kept.add(ride);
            rescued++;
            for (DrtRequest req : ride.getRequests()) {
                requestRideCount.merge(req.index, 1, Integer::sum);
            }
        }
    }

    if (rescued > 0) {
        log.info("  Degree {}: rescued {} rides for per-request floor (min={})",
                degree, rescued, minRidesPerRequest);
    }
}
```

Note: The `pruned.sort` using `indexOf` is O(n^2) but only runs on the pruned set when `minRidesPerRequest > 0`. Since this feature is disabled by default and only needed for small rescue sets, performance is acceptable. If it becomes a bottleneck, switch to index-based sorting.

**Step 4: Compile**

```bash
mvn compile -Denforcer.skip=true -o -q
```

---

## Task 3: Replace ExMasEngine inter-degree logic

**Files:**
- Modify: `algorithm/engine/ExMasEngine.java` (lines 143-170)

**Step 1: Replace lines 143-170** (the inter-degree calculation + pruning block inside the loop)

Replace:
```java
double finalKeepTop = exMasConfig.getPostExtensionKeepTopFraction();
// Inter-degree: keep top 50% (sqrt of final fraction, minimum 0.5)
// Gentler than final P90/P95 to preserve base diversity for higher degrees
double interDegreeKeepTop = finalKeepTop < 1.0
        ? Math.max(0.5, Math.sqrt(finalKeepTop)) : 1.0;
```

With:
```java
double interDegreeKeepFraction = exMasConfig.getInterDegreeKeepFraction();
int interDegreeMinPerRequest = exMasConfig.getInterDegreeMinRidesPerRequest();
```

Replace the pruning block inside the loop (lines 159-170):
```java
            // Inter-degree percentile pruning to bound memory:
            // Drop bottom sets by savings (preserves base diversity for higher degrees)
            int generatedCount = extended.size();
            if (interDegreeKeepTop < 1.0 && extended.size() > 1000) {
                PostExtensionPruner pct = new PostExtensionPruner(0, interDegreeKeepTop);
                extended = pct.prune(extended);
                if (extended.size() < generatedCount) {
                    log.info("Inter-degree pruning at degree {}: {} -> {} rides (keepTop={})",
                            degree + 1, generatedCount, extended.size(),
                            String.format("%.2f", interDegreeKeepTop));
                }
            }
```

With:
```java
            // Mandatory inter-degree pruning: keep top X% by distance savings.
            // Direct fraction (no sqrt scaling). Survivors = output + base sets for next degree.
            int generatedCount = extended.size();
            if (interDegreeKeepFraction < 1.0) {
                PostExtensionPruner pruner = new PostExtensionPruner(
                        0, interDegreeKeepFraction, interDegreeMinPerRequest);
                extended = pruner.prune(extended);
            }
```

**Step 2: Compile**

```bash
mvn compile -Denforcer.skip=true -o -q
```

**Step 3: Run E2E tests**

```bash
mvn test -Dtest=ExMasDemandExtractionE2ETest -Denforcer.skip=true -o
```

The dvrp-grid E2E test should still pass — with default `interDegreeKeepFraction=0.10`, the small test population produces few rides so most survive the 10% threshold.

```bash
mvn test -Dtest=ExMasKelheimHyperPoolE2ETest -Denforcer.skip=true -o
```

Kelheim HyperPool test should pass — inter-degree pruning kicks in for higher degrees.

---

## Task 4: Configure in RunBavaria30kmDemandExtraction

**Files:**
- Modify: `run/RunBavaria30kmDemandExtraction.java`

**Step 1: Add CLI args** (after `--max-degree` arg parsing, line ~125)

```java
case "--inter-degree-keep" -> interDegreeKeep = Double.parseDouble(args[++i]);
case "--inter-degree-min-per-request" -> interDegreeMinPerReq = Integer.parseInt(args[++i]);
```

**Step 2: Add variable declarations** (after `int maxDegree = 16;`, line ~96)

```java
double interDegreeKeep = 0.10;
int interDegreeMinPerReq = 0;
```

**Step 3: Add config setting** (after the existing post-extension config, line ~743)

```java
// Inter-degree pruning: mandatory, direct fraction (no sqrt scaling)
exMasConfig.setInterDegreeKeepFraction(interDegreeKeep);
exMasConfig.setInterDegreeMinRidesPerRequest(interDegreeMinPerReq);
log.info("  Inter-degree pruning: keepFraction={}, minRidesPerRequest={}",
        interDegreeKeep, interDegreeMinPerReq);
```

**Step 4: Disable in no-pruning mode** (inside the `if (noPruning)` block, line ~720)

```java
exMasConfig.setInterDegreeKeepFraction(1.0);
exMasConfig.setInterDegreeMinRidesPerRequest(0);
```

**Step 5: Compile**

```bash
mvn compile -Denforcer.skip=true -o -q
```

---

## Task 5: Run 10% Bavaria validation

**Step 1: Run 10% with inter-degree pruning (default keepFraction=0.10)**

```bash
cd matsim-libs/contribs/drt-demand-extraction
mvn exec:java -o \
  -Dexec.mainClass=org.matsim.contrib.demand_extraction.run.RunBavaria30kmDemandExtraction \
  -Dexec.args="--scenario-path ../../../matsim_scenarios/bavaria/output/kelheim_30km_100pct \
    --population ../../../matsim_scenarios/bavaria/output/populations/population_10pct_kelheim30km.xml.gz \
    --sample 100 --iterations 0 --trip-filter-radius 30 \
    --filter-municipality Kelheim \
    --shapes ../../../matsim_scenarios/bavaria/data/germany/tmp_vg250/vg250-ew_12-31.utm32s.gpkg.ebenen/vg250-ew_ebenen_1231/DE_VG250.gpkg \
    --travel-times ../../../matsim_scenarios/bavaria/output/base-simulation-10pct/travel_times.tsv \
    --output-dir ../../../matsim_scenarios/bavaria/output/demand-extraction-10pct-inter-degree \
    --no-predecessors" \
  -Denforcer.skip=true
```

**Step 2: Verify**

- Memory stays well below 30 GB
- Degree 5, 6, 7+ are reached
- Total rides < 1M (target ~170k)
- Log shows inter-degree pruning after each degree
- No assertion errors (pruned greedy distance invariant)

**Step 3: Compare ride quality**

Check the savings distribution at each degree — the kept rides should have consistently high savings (top 10%).
