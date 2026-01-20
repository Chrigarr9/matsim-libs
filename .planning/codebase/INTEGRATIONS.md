# External Integrations

**Analysis Date:** 2026-01-20

## MATSim Module Integrations

The DRT demand extraction module deeply integrates with several MATSim components.

### Core MATSim Integration

**Dependency Injection (Guice):**
- Uses `AbstractModule` for binding components
- Key bindings in `DemandExtractionModule`:
  - `ModeRoutingCache.class` - Mode routing and scoring cache
  - `ChainIdentifier.class` - Subtour chain identification
  - `CommuteIdentifier.class` - Commute trip detection
  - `DrtRequestFactory.class` - DRT request generation
  - `FlexibilityCalculator.class` - Departure/arrival flexibility
  - `RequestSampler.class` - Request sampling
- Installs `ExMasAlgorithmModule` for algorithm components
- Controller listener: `DemandExtractionListener`

**Routing Infrastructure:**
- `Provider<TripRouter>` - Multi-modal trip routing
- `LeastCostPathCalculator` - Network path calculation (SpeedyALT router)
- `TravelTime` / `TravelDisutility` - Time-dependent routing costs
- Named bindings for mode-specific routers (e.g., `@Named("directDrtRouter")`)

**Scoring System:**
- `ScoringFunctionFactory` - Creates scoring functions per person
- `ScoringParametersForPerson` - Person-specific scoring parameters
- `ModeUtilityParameters` - Mode-specific utility parameters (constants, costs)

**Population & Network:**
- `Population` - Access to all persons and plans
- `Network` - Transport network for routing
- `ActivityFacilities` - Activity location facilities
- `TripStructureUtils` - Trip extraction from plans

### DRT Contrib Integration

**Controller Setup:**
- `DrtControlerCreator.createScenarioWithDrtRouteFactory()` - Creates scenario with DRT route handling
- `DrtControlerCreator.createControler()` - Creates controller with DRT modules
- `MultiModeDrtConfigGroup` - Multi-mode DRT configuration
- `DvrpConfigGroup` - DVRP network mode configuration

**DRT Configuration:**
- Reads existing DRT mode parameters from scenario config
- Uses DRT scoring parameters for budget calculation
- Supports multiple DRT modes (though single-mode recommended for ExMAS)

### Algorithm Module Integration

**MatsimNetworkCache:**
- Location: `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/network/MatsimNetworkCache.java`
- Wraps MATSim's `LeastCostPathCalculator` with time-binned caching
- Supports cache export/import for persistence
- Thread-safe routing with statistics tracking

**ExMAS Algorithm:**
- `ExMasEngine` - Main algorithm orchestrator
- `SingleRideGenerator` - Generates single-passenger rides
- `PairGenerator` - Generates 2-passenger shared rides
- `RideExtender` - Extends rides to higher pooling degrees
- `ShareabilityGraph` - Graph structure for ride compatibility
- `BudgetValidator` - Validates rides against passenger budgets

## Data Input/Output

### Input Formats

**MATSim Standard Inputs:**
- Population XML (`plans.xml.gz`)
- Network XML (`network.xml.gz`)
- Transit schedule XML (optional, for PT routing)
- Vehicles XML (optional)
- Config XML with module parameters

**External Scenario Resources:**
- SVN-hosted files (e.g., Kelheim scenario from `svn.vsp.tu-berlin.de`)
- Local file paths relative to scenario directory

### Output Files

**CSV Exports (written to `output/drt_demand/`):**

1. `{runId}.drt_requests.csv`:
   - Columns: index, personId, groupId, tripIndex, isCommute, budget, requestTime, originLinkId, destinationLinkId, coordinates, activity types, travel times, constraints, PT accessibility
   - 27 fields total

2. `{runId}.exmas_rides.csv`:
   - Columns: rideIndex, degree, kind, requestIndices, personIds, groupIds, passenger times, distances, delays, detours, budgets, costs, Shapley values, successors, ride metrics
   - 22 fields total
   - Array fields use `[ | ]` separator format

3. `{runId}.person_attributes.csv`:
   - Columns: personId, carAvail, hasLicense, plus all person attributes
   - Used for cluster analysis in downstream Python

4. `{runId}.mode_cache.csv`:
   - Columns: personId, tripIndex, mode, travelTime, distance, cost, score
   - Debug output for mode choice analysis

5. `{runId}.connection_cache.csv` (if `calcPredecessors=true`):
   - Columns: origin, destination, time_bin, travel_time, distance
   - Filtered connections for ride sequencing optimization

## APIs & External Services

**No External APIs Required:**
- The module operates entirely within the MATSim ecosystem
- All data is local or fetched from MATSim SVN repositories
- No authentication or external service calls

**Optional External Data Sources:**
- MATSim SVN repository for scenario data (read-only HTTPS)
  - URL pattern: `https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/...`
  - Used by `RunKelheimDemandExtraction` for plans files

## Data Storage

**Databases:**
- None - File-based I/O only

**File Storage:**
- Local filesystem for input scenarios and output files
- MATSim's `IOUtils` for compressed file handling (`.gz`)

**Caching:**
- In-memory `ConcurrentHashMap` for network routing cache
- Time-binned caching (default 15-minute bins) for travel time queries
- Cache can be exported/imported via CSV for persistence

## Authentication & Identity

**Auth Provider:**
- None - Standalone application

**MATSim Person Attributes:**
- `PersonUtils.getLicense()` - Driver's license status
- `PersonUtils.getCarAvail()` - Car availability
- Custom attributes via `person.getAttributes()`

## Monitoring & Observability

**Logging:**
- Log4j 2 via `LogManager.getLogger()`
- Progress logging during mode caching, chain identification, ride generation
- Routing statistics summary after demand extraction
- Structured logging with timing information

**Metrics:**
- Routing success/failure counts in `MatsimNetworkCache`
- Progress percentages during processing
- Summary statistics (requests, rides, execution time)

## CI/CD & Deployment

**Build:**
- Maven-based build (`mvn clean install`)
- Inherits from MATSim parent POM
- Excludes legacy code via compiler plugin configuration

**Testing:**
- JUnit Jupiter tests in `src/test/java`
- Integration tests use MATSim example scenarios
- E2E test: `ExMasDemandExtractionE2ETest` (dvrp-grid scenario)
- E2E test: `ExMasKelheimE2ETest` (Kelheim scenario)

**Deployment:**
- JAR artifact: `drt-demand-extraction-2026.0-SNAPSHOT.jar`
- Run via Maven exec or direct Java execution
- No containerization configured

## Environment Configuration

**No Required Environment Variables:**
- All configuration via MATSim config files

**Key Config Parameters (ExMasConfigGroup):**
```xml
<module name="exmas">
  <param name="drtMode" value="drt" />
  <param name="baseModes" value="car,pt,bike,walk" />
  <param name="drtRoutingMode" value="car" />
  <param name="maxPoolingDegree" value="10" />
  <param name="maxDetourFactor" value="1.5" />
  <param name="searchHorizon" value="600.0" />
  <param name="calcPredecessors" value="true" />
  <param name="calcShapleyValues" value="true" />
</module>
```

## Downstream Integration

**Python Optimization Pipeline:**
- Output CSVs designed for consumption by Python optimization code
- `drt_requests.csv` and `exmas_rides.csv` format aligned with `exmas_pipeline` expectations
- `connection_cache.csv` provides network data for empty vehicle routing
- `person_attributes.csv` supports demand clustering analysis

---

*Integration audit: 2026-01-20*
