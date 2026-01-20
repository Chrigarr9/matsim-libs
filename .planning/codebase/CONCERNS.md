# Codebase Concerns

**Analysis Date:** 2026-01-20

**Focus Area:** DRT Demand Extraction Module (`contribs/drt-demand-extraction/`)

## Tech Debt

**Legacy Code in Wrong Package:**
- Issue: `MobilityServiceOptimization.java` in `legacy/` directory uses incorrect package declaration (`com.vwgroup.msf.utilities...`) and references non-existent VW internal libraries
- Files: `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/legacy/MobilityServiceOptimization.java`
- Impact: Cannot compile, dead code, creates confusion about module purpose
- Fix approach: Delete file or refactor to use MATSim-native dependencies only

**TODOs Requiring Implementation:**
- Issue: Several incomplete features marked with TODO comments
- Files:
  - `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/network/MatsimNetworkCache.java:355` - ThreadLocal router optimization
  - `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/demand/ModeRoutingCache.java:303` - Daily monetary constant tracking
  - `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/run/RunKelheimDemandExtraction.java:413` - SwissRailRaptor range query configuration
- Impact: Missing features affect performance and scoring accuracy
- Fix approach:
  - ThreadLocal: Implement per-thread routers for parallel routing
  - Daily constants: Track per-person mode usage per day
  - SwissRailRaptor: Configure range query settings properly

**Configuration Complexity:**
- Issue: `ExMasConfigGroup.java` (663 lines) has grown large with many configuration options
- Files: `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/config/ExMasConfigGroup.java`
- Impact: Difficult to understand all options, prone to configuration errors
- Fix approach: Consider grouping related parameters into nested config groups

## Known Bugs

**SwissRailRaptor Configuration Issue:**
- Symptoms: PT departure time optimization disabled due to configuration problems
- Files:
  - `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/run/RunKelheimDemandExtraction.java:413-414`
  - `contribs/drt-demand-extraction/src/test/java/org/matsim/contrib/demand_extraction/ExMasKelheimE2ETest.java:197-199`
- Trigger: Setting `ptOptimizeDepartureTime = true` with SwissRailRaptor
- Workaround: `exMasConfig.setPtOptimizeDepartureTime(false)` is set as default

**SpeedyALT Router OutOfMemoryError:**
- Symptoms: Infinite loop in path construction for some link pairs causes OOM
- Files: `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/network/MatsimNetworkCache.java:414-420`
- Trigger: Specific link pairs with problematic network topology
- Workaround: Catches OutOfMemoryError, logs warning, returns unreachable segment

## Security Considerations

**No Critical Security Issues:**
- Risk: Module processes population data and network information
- Current mitigation: No sensitive data storage, no network exposure, file-based I/O only
- Recommendations: Consider adding input validation for external config file paths

## Performance Bottlenecks

**Synchronized Router Access:**
- Problem: Single `synchronized` method blocks all routing threads
- Files: `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/network/MatsimNetworkCache.java:361`
- Cause: SpeedyALT router is not thread-safe, requires serialized access
- Improvement path: Implement ThreadLocal routers as noted in TODO at line 355-360

**Parallel Population Processing:**
- Problem: Mode caching iterates through all persons in parallel but creates new TripRouter per person
- Files: `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/demand/ModeRoutingCache.java:86-87`
- Cause: Each person needs fresh TripRouter from provider
- Improvement path: Consider batch processing or router pooling

**Combinatorial Explosion in Ride Extension:**
- Problem: Higher pooling degrees generate exponentially more ride combinations
- Files: `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/RideExtender.java`
- Cause: Each degree level multiplies candidates by potential extensions
- Improvement path: Pruning heuristics implemented (lines 167-228) but may need tuning

## Fragile Areas

**Budget Calculation Methodology:**
- Files:
  - `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/demand/DrtRequestFactory.java:306-315`
  - `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/validation/BudgetValidator.java`
- Why fragile: Budget depends on multiple MATSim scoring parameters (mode constants, daily constants, opportunity costs), changes in scoring config silently affect results
- Safe modification: Always run E2E tests after scoring changes
- Test coverage: Basic tests exist but edge cases with unusual scoring params not covered

**Ride Index Assignment:**
- Files:
  - `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/engine/ExMasEngine.java:164-180`
  - `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/RideExtender.java:103-106`
- Why fragile: Ride indices are reassigned multiple times (after parallel processing, after sorting, after pruning)
- Safe modification: Ensure deterministic ordering preserved when modifying sort logic
- Test coverage: Limited - relies on output file comparison

**DemandExtractionConfigValidator Timing:**
- Files: `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/demand/DemandExtractionModule.java:25-30`
- Why fragile: Validator MUST be called BEFORE Controler creation, but this is only documented in comments
- Safe modification: Consider enforcing validation order programmatically
- Test coverage: Tests follow correct order but runtime users may not

## Scaling Limits

**Network Cache Memory:**
- Current capacity: Unlimited ConcurrentHashMap growth
- Limit: Large networks with many time bins can exhaust heap
- Scaling path: Add cache size limits, LRU eviction, or persist to disk

**Request Count:**
- Current capacity: Tested up to ~10,000 requests (Kelheim 10% sample)
- Limit: Pair generation is O(n^2) in request count
- Scaling path: Time-based filtering (horizon parameter) already helps; consider spatial partitioning

## Dependencies at Risk

**No Critical Dependency Issues:**
- Risk: Module depends on MATSim core, DRT contrib, and DVRP contrib
- Impact: Changes in upstream contribs may break compatibility
- Migration plan: Track MATSim releases, update alongside core

## Missing Critical Features

**No Predecessor Relationships in Output:**
- Problem: Predecessor calculation was removed from CSV output (predecessors column empty)
- Blocks: Python optimization cannot calculate empty vehicle kilometers without predecessor data
- Note: `calcPredecessors` config option exists but connection_cache.csv is written separately

**No Warm-Start Cache:**
- Problem: Network cache cannot be pre-loaded from previous runs
- Blocks: Repeated runs on same scenario must recalculate all routes
- Note: `importCache()` and `exportCache()` methods exist but not integrated into main workflow

## Test Coverage Gaps

**Unit Test Absence:**
- What's not tested: Individual class unit tests for most components
- Files:
  - `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/validation/BudgetValidator.java`
  - `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/demand/FlexibilityCalculator.java`
  - `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/demand/ChainIdentifier.java`
- Risk: Subtle bugs in budget/flexibility calculations may go unnoticed
- Priority: High - these calculations directly affect output correctness

**Edge Case Testing:**
- What's not tested: Same-link trips, zero-length routes, NaN propagation scenarios
- Files: `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/demand/DrtRequestFactory.java:245-275`
- Risk: Edge cases handled with warnings but not systematically tested
- Priority: Medium

**Integration Test Brittleness:**
- What's not tested: E2E tests check file existence and basic structure but not semantic correctness
- Files:
  - `contribs/drt-demand-extraction/src/test/java/org/matsim/contrib/demand_extraction/ExMasDemandExtractionE2ETest.java`
  - `contribs/drt-demand-extraction/src/test/java/org/matsim/contrib/demand_extraction/ExMasKelheimE2ETest.java`
- Risk: Regression in output values not detected
- Priority: Medium - consider adding golden file comparisons

**Missing Tests for Config Validation:**
- What's not tested: Invalid config combinations, boundary values
- Files: `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/demand/DemandExtractionConfigValidator.java`
- Risk: Users may provide invalid configs that pass validation
- Priority: Low

---

*Concerns audit: 2026-01-20*
