# Next Session Prompt: Implement Scoring Context Cache + Validate

Copy-paste this into a new Claude Code session:

---

## Task

Implement the scoring context cache for ExMAS budget validation, then run 1% and 10% Bavaria to validate correctness and measure the speedup.

## Context

We've been optimizing the ExMAS ride extension algorithm over the last session. The current bottleneck is **budget validation cost**: `DrtTripScorer.scoreWithActivityResolution()` parses the person's entire MATSim plan + creates 8 objects PER CALL. At degree 6 this means 552 plan parsings per candidate set (6 passengers × 92 ordering tries). All per-request data (person, activities, durations, scoring params) is constant across orderings — only travelTime, distance, and delay change.

## What to do

1. **Read the implementation plan:** `docs/plans/2026-04-03-scoring-context-cache.md` — it has exact code for all 5 implementation tasks (Tasks 1-5). Follow it step by step.

2. **Implement Tasks 1-5** from the plan:
   - Task 1: Add `ScoringContext` record + field to `DrtRequest.java`
   - Task 2: Add `precomputeScoringContexts()` to `BudgetValidator.java`
   - Task 3: Add `scoreWithContext()` to `DrtTripScorer.java`
   - Task 4: Wire `BudgetValidator.calculateDrtScore()` to use cached context
   - Task 5: Call precompute from `ExMasEngine.java` before Phase 4

3. **Compile + run E2E tests** after each task:
   ```bash
   cd matsim-libs/contribs/drt-demand-extraction
   mvn compile -Denforcer.skip=true -o -q
   mvn test -Dtest=ExMasDemandExtractionE2ETest -Denforcer.skip=true -o
   mvn test -Dtest=ExMasKelheimHyperPoolE2ETest -Denforcer.skip=true -o
   ```

4. **Run 1% Bavaria validation** (Task 6 in plan):
   ```bash
   mvn exec:java -o \
     -Dexec.mainClass=org.matsim.contrib.demand_extraction.run.RunBavaria30kmDemandExtraction \
     -Dexec.args="--scenario-path ../../../matsim_scenarios/bavaria/output/kelheim_30km_100pct \
       --population ../../../matsim_scenarios/bavaria/output/populations/population_1pct_kelheim30km.xml.gz \
       --sample 100 --iterations 0 --trip-filter-radius 30 \
       --filter-municipality Kelheim \
       --shapes ../../../matsim_scenarios/bavaria/data/germany/tmp_vg250/vg250-ew_12-31.utm32s.gpkg.ebenen/vg250-ew_ebenen_1231/DE_VG250.gpkg \
       --travel-times ../../../matsim_scenarios/bavaria/output/base-simulation-10pct/travel_times.tsv \
       --output-dir ../../../matsim_scenarios/bavaria/output/demand-extraction-1pct-scoring-cache \
       --no-predecessors --inter-degree-keep 1.0" \
     -Denforcer.skip=true
   ```
   **Must produce exactly 12,552 rides** (same as previous correct runs).

5. **Run 10% Bavaria** (Task 7 in plan) with inter-degree pruning (default 10%):
   ```bash
   mvn exec:java -o \
     -Dexec.mainClass=org.matsim.contrib.demand_extraction.run.RunBavaria30kmDemandExtraction \
     -Dexec.args="--scenario-path ../../../matsim_scenarios/bavaria/output/kelheim_30km_100pct \
       --population ../../../matsim_scenarios/bavaria/output/populations/population_10pct_kelheim30km.xml.gz \
       --sample 100 --iterations 0 --trip-filter-radius 30 \
       --filter-municipality Kelheim \
       --shapes ../../../matsim_scenarios/bavaria/data/germany/tmp_vg250/vg250-ew_12-31.utm32s.gpkg.ebenen/vg250-ew_ebenen_1231/DE_VG250.gpkg \
       --travel-times ../../../matsim_scenarios/bavaria/output/base-simulation-10pct/travel_times.tsv \
       --output-dir ../../../matsim_scenarios/bavaria/output/demand-extraction-10pct-scoring-cache \
       --no-predecessors" \
     -Denforcer.skip=true
   ```

6. **Compare results** against these baselines (from the optimization session):

   **10% degree timing (inline eval WITHOUT scoring cache):**
   | Degree | Rides | Time | Sets/s |
   |--------|-------|------|--------|
   | 3 | 1,065,484 | 47.8s | 2,006 |
   | 4 | 2,769,198 | 141.6s | 753 |
   | 5 | 4,877,065 | 1,867s (31min) | 148 |
   | 6 | running | ETA 14h | 9 |

   **Expected with scoring cache:** Major speedup at degree 5+ (budget validation was ~1ms/call, now ~0.05ms). Degree 6 should complete within 1-2h instead of 14h.

   **1% ride counts for correctness check:**
   - Must be exactly 12,552 total (2,097 single + 9,245 pair + 809 deg3 + 313 deg4 + 75 deg5 + 12 deg6 + 1 deg7)

## Key files

All in `matsim-libs/contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/`:
- `demand/DrtRequest.java` — add ScoringContext record + field
- `algorithm/validation/BudgetValidator.java` — add precompute + use cached context
- `scoring/DrtTripScorer.java` — add scoreWithContext method
- `algorithm/engine/ExMasEngine.java` — call precompute before Phase 4

## Important notes

- The current code uses `enumerateAndEvaluate` (inline eval with tighten-on-valid callback). The scoring cache makes this approach fast because each validation in the callback becomes cheap.
- `DrtRequest` currently has all-final fields + builder pattern. The `scoringContext` field is the first mutable field — use `volatile` for thread-safe publication to ForkJoinPool workers.
- The fallback path in `calculateDrtScore` (when context is null) is needed for singles and pairs generated BEFORE `precomputeScoringContexts` runs.
- Branch: `feature/exmas-traceable` in matsim-libs
- Comprehensive session log: `docs/plans/2026-04-02-exmas-optimization-session-log.md`
