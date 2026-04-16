# Post-Extension Pruning Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a configurable post-extension pruning stage that compresses the ride database (MaxPerSet + per-degree percentile filter) before expensive post-processing (Shapley, predecessors), making 10%/25%/100% scenarios tractable.

**Architecture:** New `PostExtensionPruner` class inserted between `ExMasEngine.run()` and `RidePostProcessor.process()` in `DemandExtractionListener`. Two sequential pruning passes: (1) MaxPerSet — collapse each request set to its best N variants, (2) per-degree percentile filter — keep only the top X% of rides by distance savings within each degree. Both are independently configurable and can be disabled. Config params added to `ExMasConfigGroup`. Singles (degree 1) are never pruned.

**Tech Stack:** Java 17, MATSim, JUnit 5

---

### Task 1: Add Config Parameters to ExMasConfigGroup

**Files:**
- Modify: `matsim-libs/contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/config/ExMasConfigGroup.java`

**Step 1: Add field declarations**

After the existing pruning fields (around line 203), add:

```java
// Post-extension pruning (applied after all degrees are generated, before post-processing)
// MaxPerSet: keep only the N best rides per request set (by rideDistance). 0 = disabled.
private int postExtensionMaxPerSet = 0;
// Per-degree percentile: keep only the top X% of rides (by distanceSavings) within each degree.
// 1.0 = disabled (keep all). 0.05 = keep top 5%.
private double postExtensionKeepTopFraction = 1.0;
```

**Step 2: Add getters/setters with MATSim annotations**

Add near the other pruning getters (around line 830):

```java
@StringGetter("postExtensionMaxPerSet")
public int getPostExtensionMaxPerSet() {
    return postExtensionMaxPerSet;
}

@StringSetter("postExtensionMaxPerSet")
public void setPostExtensionMaxPerSet(int postExtensionMaxPerSet) {
    this.postExtensionMaxPerSet = postExtensionMaxPerSet;
}

@StringGetter("postExtensionKeepTopFraction")
public double getPostExtensionKeepTopFraction() {
    return postExtensionKeepTopFraction;
}

@StringSetter("postExtensionKeepTopFraction")
public void setPostExtensionKeepTopFraction(double postExtensionKeepTopFraction) {
    this.postExtensionKeepTopFraction = postExtensionKeepTopFraction;
}
```

**Step 3: Add parameter descriptions**

In the `getComments()` method, add:

```java
map.put("postExtensionMaxPerSet",
    "Post-extension pruning: keep only the N best rides (by rideDistance) per request set. "
    + "Applied after all extension degrees complete, before Shapley/predecessors. 0 = disabled. Default: 0");
map.put("postExtensionKeepTopFraction",
    "Post-extension pruning: keep only the top fraction of rides (by distanceSavings) within each degree. "
    + "1.0 = disabled. 0.10 = keep top 10% per degree. 0.05 = keep top 5%. Applied after MaxPerSet. Default: 1.0");
```

**Step 4: Compile**

Run: `cd matsim-libs/contribs/drt-demand-extraction && mvn compile -Denforcer.skip=true -o -q`
Expected: BUILD SUCCESS

**Step 5: Commit**

```bash
git add src/main/java/org/matsim/contrib/demand_extraction/config/ExMasConfigGroup.java
git commit -m "feat: add postExtensionMaxPerSet and postExtensionKeepTopFraction config params"
```

---

### Task 2: Implement PostExtensionPruner

**Files:**
- Create: `matsim-libs/contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/engine/PostExtensionPruner.java`

**Step 1: Create the pruner class**

```java
package org.matsim.contrib.demand_extraction.algorithm.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

/**
 * Post-extension pruning: compresses the ride database after all extension degrees
 * are complete, before expensive post-processing (Shapley, predecessors).
 *
 * <p>Two sequential passes:
 * <ol>
 *   <li><b>MaxPerSet</b>: For each request set (same passenger group), keep only the
 *       N best variants (by rideDistance). This collapses redundant routing variants.</li>
 *   <li><b>Per-degree percentile</b>: Within each degree, compute the distance savings
 *       threshold at the configured percentile, and drop rides below it. This removes
 *       low-quality rides that the MIP would never select.</li>
 * </ol>
 *
 * <p>Singles (degree 1) are never pruned — they are needed as fallback options.
 */
public final class PostExtensionPruner {
    private static final Logger log = LogManager.getLogger(PostExtensionPruner.class);

    private final ExMasConfigGroup config;

    public PostExtensionPruner(ExMasConfigGroup config) {
        this.config = config;
    }

    /**
     * Apply post-extension pruning. Returns a new list (does not modify the input).
     */
    public List<Ride> prune(List<Ride> rides) {
        if (rides == null || rides.isEmpty()) {
            return rides;
        }

        int maxPerSet = config.getPostExtensionMaxPerSet();
        double keepTopFraction = config.getPostExtensionKeepTopFraction();

        if (maxPerSet <= 0 && keepTopFraction >= 1.0) {
            log.info("Post-extension pruning: disabled (maxPerSet={}, keepTopFraction={})",
                    maxPerSet, keepTopFraction);
            return rides;
        }

        log.info("Post-extension pruning: {} rides input (maxPerSet={}, keepTopFraction={})",
                rides.size(), maxPerSet <= 0 ? "off" : maxPerSet,
                keepTopFraction >= 1.0 ? "off" : keepTopFraction);

        List<Ride> result = rides;

        // Pass 1: MaxPerSet
        if (maxPerSet > 0) {
            result = applyMaxPerSet(result, maxPerSet);
        }

        // Pass 2: Per-degree percentile
        if (keepTopFraction < 1.0) {
            result = applyPerDegreePercentile(result, keepTopFraction);
        }

        log.info("Post-extension pruning complete: {} -> {} rides ({} removed, {:.1f}% reduction)",
                rides.size(), result.size(), rides.size() - result.size(),
                (1.0 - (double) result.size() / rides.size()) * 100);

        return result;
    }

    private List<Ride> applyMaxPerSet(List<Ride> rides, int maxPerSet) {
        // Group by request set key (sorted request indices)
        Map<String, List<Ride>> byRequestSet = new HashMap<>();
        List<Ride> singles = new ArrayList<>();

        for (Ride ride : rides) {
            if (ride.getDegree() <= 1) {
                singles.add(ride);
                continue;
            }
            int[] indices = ride.getRequestIndices().clone();
            Arrays.sort(indices);
            String key = Arrays.toString(indices);
            byRequestSet.computeIfAbsent(key, k -> new ArrayList<>()).add(ride);
        }

        List<Ride> kept = new ArrayList<>(singles);
        int totalGroups = byRequestSet.size();
        int prunedGroups = 0;

        for (List<Ride> group : byRequestSet.values()) {
            if (group.size() <= maxPerSet) {
                kept.addAll(group);
            } else {
                prunedGroups++;
                group.sort(Comparator.comparingDouble(Ride::getRideDistance));
                kept.addAll(group.subList(0, maxPerSet));
            }
        }

        log.info("  MaxPerSet={}: {} -> {} rides ({} request sets pruned of {})",
                maxPerSet, rides.size(), kept.size(), prunedGroups, totalGroups);
        return kept;
    }

    private List<Ride> applyPerDegreePercentile(List<Ride> rides, double keepTopFraction) {
        // Group by degree
        Map<Integer, List<Ride>> byDegree = new HashMap<>();
        for (Ride ride : rides) {
            byDegree.computeIfAbsent(ride.getDegree(), k -> new ArrayList<>()).add(ride);
        }

        List<Ride> kept = new ArrayList<>();

        for (Map.Entry<Integer, List<Ride>> entry : byDegree.entrySet()) {
            int degree = entry.getKey();
            List<Ride> group = entry.getValue();

            // Never prune singles
            if (degree <= 1) {
                kept.addAll(group);
                continue;
            }

            // Compute distance savings for each ride
            double[] savings = new double[group.size()];
            for (int i = 0; i < group.size(); i++) {
                Ride ride = group.get(i);
                double sumReqDist = 0;
                for (DrtRequest req : ride.getRequests()) {
                    sumReqDist += req.getDistance();
                }
                savings[i] = sumReqDist > 0 ? 1.0 - ride.getRideDistance() / sumReqDist : 0;
            }

            // Find threshold at (1 - keepTopFraction) percentile
            double[] sorted = savings.clone();
            Arrays.sort(sorted);
            int thresholdIndex = (int) Math.floor(sorted.length * (1.0 - keepTopFraction));
            thresholdIndex = Math.min(thresholdIndex, sorted.length - 1);
            double threshold = sorted[thresholdIndex];

            int before = group.size();
            for (int i = 0; i < group.size(); i++) {
                if (savings[i] >= threshold) {
                    kept.add(group.get(i));
                }
            }
            int after = kept.size() - (kept.size() - before + group.size() - before);
            // Simpler: count kept at this degree
            int keptAtDegree = 0;
            for (int i = 0; i < group.size(); i++) {
                if (savings[i] >= threshold) keptAtDegree++;
            }

            log.info("  Degree {}: threshold={:+.3f}, kept {}/{} ({:.1f}%)",
                    degree, threshold, keptAtDegree, before,
                    keptAtDegree * 100.0 / before);
        }

        return kept;
    }
}
```

**Note:** The `log.info` format strings use `{:.1f}` which is Log4j2 syntax — replace with `String.format` patterns. Let me fix:

The log statements should use:
```java
log.info("  Degree {}: threshold={}, kept {}/{} ({}%)",
    degree, String.format("%+.3f", threshold), keptAtDegree, before,
    String.format("%.1f", keptAtDegree * 100.0 / before));
```

And the final summary:
```java
log.info("Post-extension pruning complete: {} -> {} rides ({} removed, {}% reduction)",
    rides.size(), result.size(), rides.size() - result.size(),
    String.format("%.1f", (1.0 - (double) result.size() / rides.size()) * 100));
```

**Step 2: Compile**

Run: `cd matsim-libs/contribs/drt-demand-extraction && mvn compile -Denforcer.skip=true -o -q`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add src/main/java/org/matsim/contrib/demand_extraction/algorithm/engine/PostExtensionPruner.java
git commit -m "feat: add PostExtensionPruner for MaxPerSet + per-degree percentile compression"
```

---

### Task 3: Wire PostExtensionPruner into DemandExtractionListener

**Files:**
- Modify: `matsim-libs/contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/demand/DemandExtractionListener.java:116-120`

**Step 1: Add pruner call between ExMAS and post-processing**

Replace lines 116-120:

```java
List<Ride> rides = exmasEngine.run(requests);

// Post-process rides with advanced metrics (maxCost, Shapley, predecessors)
RidePostProcessor postProcessor = new RidePostProcessor(exMasConfig, networkCache, budgetToConstraintsCalculator, population);
rides = postProcessor.process(rides);
```

With:

```java
List<Ride> rides = exmasEngine.run(requests);

// Post-extension pruning: compress ride database before expensive post-processing
PostExtensionPruner pruner = new PostExtensionPruner(exMasConfig);
rides = pruner.prune(rides);

// Post-process rides with advanced metrics (maxCost, Shapley, predecessors)
RidePostProcessor postProcessor = new RidePostProcessor(exMasConfig, networkCache, budgetToConstraintsCalculator, population);
rides = postProcessor.process(rides);
```

**Step 2: Add import**

Add at the top of the file:

```java
import org.matsim.contrib.demand_extraction.algorithm.engine.PostExtensionPruner;
```

**Step 3: Compile**

Run: `cd matsim-libs/contribs/drt-demand-extraction && mvn compile -Denforcer.skip=true -o -q`
Expected: BUILD SUCCESS

**Step 4: Run E2E test**

Run: `cd matsim-libs/contribs/drt-demand-extraction && mvn test -Dtest=ExMasDemandExtractionE2ETest -Denforcer.skip=true -o`
Expected: PASS (pruner is disabled by default — maxPerSet=0, keepTopFraction=1.0)

**Step 5: Commit**

```bash
git add src/main/java/org/matsim/contrib/demand_extraction/demand/DemandExtractionListener.java
git commit -m "feat: wire PostExtensionPruner between ExMAS engine and post-processing"
```

---

### Task 4: Add CLI flags to RunBavaria30kmDemandExtraction

**Files:**
- Modify: `matsim-libs/contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/run/RunBavaria30kmDemandExtraction.java`

**Step 1: Add CLI argument parsing**

In the variable declarations (around line 91), add:

```java
int postExtMaxPerSet = 0;
double postExtKeepTop = 1.0;
```

In the switch block, add:

```java
case "--post-ext-max-per-set" -> postExtMaxPerSet = Integer.parseInt(args[++i]);
case "--post-ext-keep-top" -> postExtKeepTop = Double.parseDouble(args[++i]);
```

**Step 2: Set config values in configureExMas**

Pass the values through to `configureExMas` and add at the end of that method:

```java
exMasConfig.setPostExtensionMaxPerSet(postExtMaxPerSet);
exMasConfig.setPostExtensionKeepTopFraction(postExtKeepTop);
if (postExtMaxPerSet > 0 || postExtKeepTop < 1.0) {
    log.info("  Post-extension pruning: maxPerSet={}, keepTopFraction={}",
            postExtMaxPerSet, postExtKeepTop);
}
```

Note: thread the `postExtMaxPerSet` and `postExtKeepTop` values through `configureForDemandExtraction` → `configureExMas` the same way `noPruning` is threaded.

**Step 3: Compile + test**

Run: `cd matsim-libs/contribs/drt-demand-extraction && mvn test -Dtest=ExMasDemandExtractionE2ETest -Denforcer.skip=true -o`
Expected: PASS

**Step 4: Commit**

```bash
git add src/main/java/org/matsim/contrib/demand_extraction/run/RunBavaria30kmDemandExtraction.java
git commit -m "feat: add --post-ext-max-per-set and --post-ext-keep-top CLI flags"
```

---

### Task 5: Run 1% Smoke Test with Post-Extension Pruning

**Files:**
- No code changes — validation run

**Step 1: Run with MaxPerSet=1 + P90**

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
               --output-dir ../../../matsim_scenarios/bavaria/output/demand-extraction-1pct-postprune-test \
               --post-ext-max-per-set 1 --post-ext-keep-top 0.10" \
  -Denforcer.skip=true
```

**Step 2: Verify output**

Check logs for:
- "Post-extension pruning: N rides input (maxPerSet=1, keepTopFraction=0.1)"
- "MaxPerSet=1: N -> M rides"
- "Degree 2: threshold=..., kept .../... (%)"
- "Post-extension pruning complete: N -> M rides (X removed, Y% reduction)"
- Total rides should be ~10% of the non-pruned 1% run (~2,400 from unpruned 24,575)
- Shapley and predecessors should complete quickly on the reduced set

**Step 3: Compare degree distribution**

```bash
head -1 matsim_scenarios/bavaria/output/demand-extraction-1pct-postprune-test/drt_demand/*.exmas_rides.csv
wc -l matsim_scenarios/bavaria/output/demand-extraction-1pct-postprune-test/drt_demand/*.exmas_rides.csv
```

---

### Task 6: Run 10% with Post-Extension Pruning

**Files:**
- No code changes — production run

**Step 1: Launch 10% extraction with P90 pruning**

```bash
cd matsim-libs/contribs/drt-demand-extraction
export MAVEN_OPTS="-Xmx100g"
nohup mvn exec:java -o \
  -Dexec.mainClass="org.matsim.contrib.demand_extraction.run.RunBavaria30kmDemandExtraction" \
  -Dexec.args="--scenario-path ../../../matsim_scenarios/bavaria/output/kelheim_30km_100pct \
               --population ../../../matsim_scenarios/bavaria/output/populations/population_10pct_kelheim30km.xml.gz \
               --sample 100 --iterations 0 \
               --trip-filter-radius 30 --filter-municipality Kelheim \
               --shapes ../../../matsim_scenarios/bavaria/data/germany/tmp_vg250/vg250-ew_12-31.utm32s.gpkg.ebenen/vg250-ew_ebenen_1231/DE_VG250.gpkg \
               --travel-times ../../../matsim_scenarios/bavaria/output/base-simulation-10pct/travel_times.tsv \
               --output-dir ../../../matsim_scenarios/bavaria/output/demand-extraction-10pct-v5 \
               --post-ext-max-per-set 1 --post-ext-keep-top 0.10" \
  -Denforcer.skip=true > ../../../matsim_scenarios/bavaria/output/demand-extraction-10pct-v5.log 2>&1 &
```

**Step 2: Monitor**

Expected ride count: ~600k (5.9M × 10% after MaxPerSet + P90 per degree).
Expected post-processing time: much less than previous 7+ hours.
Memory should stay within bounds.
