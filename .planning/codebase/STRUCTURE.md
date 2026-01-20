# Codebase Structure

**Analysis Date:** 2026-01-20

## Directory Layout

```
matsim-libs/                                    # MATSim monorepo
├── contribs/                                   # Optional extension modules
│   ├── drt-demand-extraction/                  # THIS MODULE - DRT demand extraction
│   │   ├── pom.xml                             # Maven config (depends on drt, dvrp)
│   │   ├── src/
│   │   │   ├── main/java/org/matsim/contrib/demand_extraction/
│   │   │   │   ├── algorithm/                  # ExMAS algorithm implementation
│   │   │   │   │   ├── domain/                 # Domain objects (Ride, TravelSegment)
│   │   │   │   │   ├── engine/                 # Algorithm orchestration
│   │   │   │   │   ├── extension/              # Ride extension logic
│   │   │   │   │   ├── generation/             # Single/pair ride generation
│   │   │   │   │   ├── graph/                  # Shareability graph
│   │   │   │   │   ├── network/                # Network routing cache
│   │   │   │   │   ├── util/                   # Utilities
│   │   │   │   │   ├── validation/             # Budget validation
│   │   │   │   │   └── ExMasAlgorithmModule.java
│   │   │   │   ├── config/                     # Configuration
│   │   │   │   │   └── ExMasConfigGroup.java   # All 60+ config parameters
│   │   │   │   ├── demand/                     # MATSim integration layer
│   │   │   │   │   ├── DemandExtractionModule.java    # Main entry point
│   │   │   │   │   ├── DemandExtractionListener.java  # Pipeline orchestrator
│   │   │   │   │   ├── DrtRequest.java                # Request domain object
│   │   │   │   │   ├── DrtRequestFactory.java         # Request builder
│   │   │   │   │   ├── ModeRoutingCache.java          # Mode caching
│   │   │   │   │   ├── ChainIdentifier.java           # Subtour detection
│   │   │   │   │   ├── CommuteIdentifier.java         # Commute detection
│   │   │   │   │   ├── FlexibilityCalculator.java     # Time windows
│   │   │   │   │   ├── BudgetToConstraintsCalculator.java
│   │   │   │   │   ├── DrtBudgetConfigurator.java     # DRT config for budgets
│   │   │   │   │   ├── ModeAttributes.java            # Cached mode data
│   │   │   │   │   ├── DemandExtractionConfigValidator.java
│   │   │   │   │   └── RequestSampler.java            # Request sampling
│   │   │   │   ├── io/                         # Output writers
│   │   │   │   │   ├── ExMasCsvWriter.java            # Requests/rides CSV
│   │   │   │   │   ├── ConnectionCacheWriter.java     # Connection cache
│   │   │   │   │   └── PersonAttributesWriter.java    # Person attributes
│   │   │   │   ├── legacy/                     # Legacy code (excluded from build)
│   │   │   │   │   └── MobilityServiceOptimization.java
│   │   │   │   └── run/                        # Example runners
│   │   │   │       └── RunKelheimDemandExtraction.java
│   │   │   └── test/java/org/matsim/contrib/demand_extraction/
│   │   │       ├── ExMasDemandExtractionE2ETest.java  # Main E2E test
│   │   │       └── ExMasKelheimE2ETest.java           # Kelheim scenario test
│   │   └── output/                             # Git-ignored output directory
│   ├── drt/                                    # DRT (Demand Responsive Transport) module
│   ├── dvrp/                                   # DVRP (Dynamic Vehicle Routing) module
│   └── ...                                     # Other MATSim contributions
├── matsim/                                     # MATSim core library
└── pom.xml                                     # Root Maven config
```

## Directory Purposes

**`algorithm/`:**
- Purpose: Pure ExMAS algorithm implementation
- Contains: Ride generation, graph structures, network caching, validation
- Key files:
  - `engine/ExMasEngine.java`: Main algorithm orchestrator
  - `generation/PairGenerator.java`: FIFO/LIFO pair generation
  - `extension/RideExtender.java`: Higher-degree ride extension
  - `graph/ShareabilityGraph.java`: Adjacency structure
  - `network/MatsimNetworkCache.java`: Time-binned routing cache

**`algorithm/domain/`:**
- Purpose: Core domain objects for the algorithm
- Contains: Immutable value objects with Builder pattern
- Key files:
  - `Ride.java`: Shared ride representation (70+ fields via arrays)
  - `RideKind.java`: Enum (FIFO, LIFO, MIXED, SINGLE)
  - `TravelSegment.java`: Network routing result

**`algorithm/engine/`:**
- Purpose: Algorithm orchestration and post-processing
- Contains: Main engine and enrichment logic
- Key files:
  - `ExMasEngine.java`: Coordinates phases (single -> pairs -> extensions)
  - `RidePostProcessor.java`: Computes Shapley, predecessors, maxCosts

**`algorithm/generation/`:**
- Purpose: Initial ride generation (degree 1-2)
- Contains: Parallel generators with deterministic ordering
- Key files:
  - `SingleRideGenerator.java`: Creates degree-1 rides
  - `PairGenerator.java`: Creates FIFO/LIFO pairs
  - `TimeFilter.java`: Time-window indexing for pair search

**`algorithm/extension/`:**
- Purpose: Extends rides beyond degree 2
- Contains: Extension logic using shareability graph
- Key files:
  - `RideExtender.java`: Extends rides iteratively with pruning

**`algorithm/graph/`:**
- Purpose: Graph data structure for extension
- Contains: Memory-efficient adjacency representation
- Key files:
  - `ShareabilityGraph.java`: Edge-list with sorted adjacency

**`algorithm/network/`:**
- Purpose: Network routing abstraction
- Contains: Time-binned caching over MATSim routing
- Key files:
  - `MatsimNetworkCache.java`: LeastCostPathCalculator wrapper with caching

**`algorithm/validation/`:**
- Purpose: Budget constraint validation
- Contains: Score calculation and budget checking
- Key files:
  - `BudgetValidator.java`: Validates rides against passenger budgets

**`algorithm/util/`:**
- Purpose: Shared utilities
- Contains: Progress bars, string manipulation, scoring helpers
- Key files:
  - `ProgressBar.java`, `StringUtils.java`, `TripScoringUtils.java`

**`config/`:**
- Purpose: Configuration management
- Contains: MATSim config group with all parameters
- Key files:
  - `ExMasConfigGroup.java`: 60+ parameters for algorithm configuration

**`demand/`:**
- Purpose: MATSim integration and request extraction
- Contains: Population processing, mode routing, chain detection
- Key files:
  - `DemandExtractionModule.java`: Guice module (entry point)
  - `DemandExtractionListener.java`: Pipeline orchestrator
  - `DrtRequest.java`: Request domain object
  - `DrtRequestFactory.java`: Builds requests from trips
  - `ModeRoutingCache.java`: Caches mode alternatives

**`io/`:**
- Purpose: Output file generation
- Contains: CSV writers for downstream optimization
- Key files:
  - `ExMasCsvWriter.java`: Writes requests.csv and rides.csv
  - `ConnectionCacheWriter.java`: Writes connection_cache.csv

**`run/`:**
- Purpose: Example runner applications
- Contains: Scenario-specific main classes
- Key files:
  - `RunKelheimDemandExtraction.java`: Kelheim scenario example

**`legacy/`:**
- Purpose: Deprecated code (excluded from Maven build)
- Contains: Old implementations for reference
- Note: Excluded via `maven-compiler-plugin` configuration in pom.xml

## Key File Locations

**Entry Points:**
- `demand/DemandExtractionModule.java`: Main Guice module to install
- `demand/DemandExtractionListener.java`: Pipeline execution
- `run/RunKelheimDemandExtraction.java`: Example runner

**Configuration:**
- `config/ExMasConfigGroup.java`: All algorithm parameters
- XML config: Add `<module name="exmas">` to MATSim config

**Core Logic:**
- `algorithm/engine/ExMasEngine.java`: Algorithm coordination
- `algorithm/generation/PairGenerator.java`: Pair creation (most complex generation)
- `algorithm/extension/RideExtender.java`: Higher-degree rides
- `demand/ModeRoutingCache.java`: Mode scoring and caching

**Domain Objects:**
- `demand/DrtRequest.java`: Input to algorithm
- `algorithm/domain/Ride.java`: Output from algorithm

**Testing:**
- `test/java/.../ExMasDemandExtractionE2ETest.java`: Full integration test
- `test/java/.../ExMasKelheimE2ETest.java`: Kelheim scenario test

## Naming Conventions

**Files:**
- Java classes: PascalCase (e.g., `DrtRequestFactory.java`)
- Test classes: `*Test.java` suffix (e.g., `ExMasDemandExtractionE2ETest.java`)
- Package names: lowercase with underscores (e.g., `demand_extraction`)

**Classes:**
- Modules: `*Module.java` (e.g., `DemandExtractionModule`)
- Listeners: `*Listener.java` (e.g., `DemandExtractionListener`)
- Config groups: `*ConfigGroup.java` (e.g., `ExMasConfigGroup`)
- Factories: `*Factory.java` (e.g., `DrtRequestFactory`)
- Writers: `*Writer.java` (e.g., `ExMasCsvWriter`)
- Generators: `*Generator.java` (e.g., `PairGenerator`)

**Methods:**
- Getters: `getX()` or `isX()` for booleans
- Setters: `setX()` (MATSim config uses `@StringGetter`/`@StringSetter` annotations)
- Builders: `builder()`, `toBuilder()`, `build()`
- Actions: verb-first (e.g., `cacheModes()`, `identifyChains()`, `generatePairs()`)

**Variables:**
- Instance fields: camelCase (e.g., `modeRoutingCache`)
- Constants: SCREAMING_SNAKE_CASE (e.g., `GROUP_NAME`, `DEFAULT_WALK_SPEED`)
- Config parameter names: camelCase strings (e.g., `"maxPoolingDegree"`)

## Where to Add New Code

**New Feature (DRT demand extraction):**
- Implementation: `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/`
- Tests: `contribs/drt-demand-extraction/src/test/java/org/matsim/contrib/demand_extraction/`
- If new algorithm component: `algorithm/` subdirectory
- If new MATSim integration: `demand/` subdirectory
- If new output format: `io/` subdirectory

**New Configuration Parameter:**
- Add to `config/ExMasConfigGroup.java`:
  1. Add field with default value
  2. Add `@StringGetter` and `@StringSetter` methods
  3. Add description to `getComments()` map

**New Domain Object:**
- Add to `algorithm/domain/` if algorithm-related
- Add to `demand/` if MATSim-integration-related
- Use immutable pattern with Builder

**New Algorithm Phase:**
- Create class in appropriate `algorithm/` subdirectory
- Wire into `ExMasEngine.run()` method
- Add tests to E2E test class

**New Output File:**
- Create writer in `io/` directory
- Call from `DemandExtractionListener.notifyShutdown()`
- Document format in writer class javadoc

**Utilities:**
- Shared helpers: `algorithm/util/`
- MATSim-specific utils: Consider MATSim core utilities first

## Special Directories

**`legacy/`:**
- Purpose: Deprecated code for reference
- Generated: No
- Committed: Yes (excluded from build via Maven)
- Note: Contains `MobilityServiceOptimization.java` - old implementation

**`output/`:**
- Purpose: Test and run output files
- Generated: Yes (during test/run execution)
- Committed: No (should be in .gitignore)

**`.mvn/`:**
- Purpose: Maven wrapper configuration
- Generated: Partially
- Committed: Yes

**`.vscode/`:**
- Purpose: VS Code editor settings
- Generated: No
- Committed: Depends on team preference

## Output File Structure

When `DemandExtractionListener` runs, it creates:

```
{outputDirectory}/drt_demand/
├── {runId}.drt_requests.csv      # DRT requests with budgets
├── {runId}.exmas_rides.csv       # All feasible rides
├── {runId}.person_attributes.csv # Person attributes for clustering
├── {runId}.mode_cache.csv        # All mode alternatives (debugging)
└── {runId}.connection_cache.csv  # Network connections (if calcPredecessors=true)
```

## Dependencies

**Internal (MATSim contribs):**
- `drt`: DRT module for DrtRoute, DrtConfigGroup
- `dvrp`: DVRP module for vehicle routing primitives

**External:**
- `it.unimi.dsi:fastutil`: Fast primitive collections (ShareabilityGraph)
- `junit-jupiter`: Testing framework

---

*Structure analysis: 2026-01-20*
