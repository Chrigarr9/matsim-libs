# Testing Patterns

**Analysis Date:** 2026-01-20

**Focus:** DRT Demand Extraction module (`contribs/drt-demand-extraction/`)

## Test Framework

**Runner:**
- JUnit 5 (Jupiter) - see `pom.xml` dependency
- Config: Inherits from parent MATSim project

**Assertion Library:**
- JUnit 5 `org.junit.jupiter.api.Assertions`

**Run Commands:**
```bash
# Run all tests in module
mvn test -pl contribs/drt-demand-extraction

# Run specific test class
mvn test -pl contribs/drt-demand-extraction -Dtest=ExMasDemandExtractionE2ETest

# Run with output (skip tests in other modules)
mvn test -pl contribs/drt-demand-extraction -DskipTests=false -Dmaven.test.failure.ignore=true
```

## Test File Organization

**Location:**
- Co-located test structure: `src/test/java/` mirrors `src/main/java/`
- Test package: `org.matsim.contrib.demand_extraction`

**Naming:**
- E2E tests: `{Feature}E2ETest.java`
- Unit tests: `{ClassName}Test.java` (not yet present - see Coverage Gaps)

**Current Test Structure:**
```
src/test/java/org/matsim/contrib/demand_extraction/
├── ExMasDemandExtractionE2ETest.java   # DVRP grid scenario E2E test
└── ExMasKelheimE2ETest.java            # Kelheim scenario E2E test
```

**Test Output Directory:**
```
test/output/
├── exmas-e2e-test/           # Grid scenario output
│   └── drt_demand/
│       ├── null.drt_requests.csv
│       ├── null.exmas_rides.csv
│       ├── null.connection_cache.csv
│       └── null.person_attributes.csv
└── exmas-kelheim-e2e-test/   # Kelheim scenario output
    └── drt_demand/
        └── ...
```

## Test Structure

**Suite Organization:**
```java
// From ExMasDemandExtractionE2ETest.java
public class ExMasDemandExtractionE2ETest {

    @Test
    void testDemandExtractionWithDvrpGridScenario() throws IOException {
        // 1. Setup - Create output directory
        Path testOutputDir = Path.of("test/output/exmas-e2e-test");
        Files.createDirectories(testOutputDir);

        // 2. Load config with required modules
        Config config = ConfigUtils.loadConfig(
            new URL(scenarioUrl, "one_shared_taxi_config.xml").toString(),
            new MultiModeDrtConfigGroup(),
            new DvrpConfigGroup(),
            new ExMasConfigGroup());

        // 3. Configure scenario
        configureMonetaryConstants(config);
        configureExMas(config);

        // 4. Create and run simulation
        Scenario scenario = DrtControlerCreator.createScenarioWithDrtRouteFactory(config);
        ScenarioUtils.loadScenario(scenario);
        enhancePopulationWithAttributes(scenario.getPopulation());

        Controler controler = DrtControlerCreator.createControler(config, scenario, false);
        controler.addOverridingModule(new DemandExtractionModule());
        controler.run();

        // 5. Verify outputs exist
        Assertions.assertTrue(Files.exists(requestsFile), "DRT requests file should exist");
        Assertions.assertTrue(Files.exists(ridesFile), "ExMAS rides file should exist");

        // 6. Validate content
        validateRequests(requestsFile);
        validateRides(ridesFile, exMasConfig);
        validatePersonAttributes(personAttributesFile);
    }

    // Private helper methods for configuration
    private void configureMonetaryConstants(Config config) { ... }
    private void configureExMas(Config config) { ... }
    private void enhancePopulationWithAttributes(Population population) { ... }

    // Private helper methods for validation
    private void validateRequests(Path requestsFile) throws IOException { ... }
    private void validateRides(Path ridesFile, ExMasConfigGroup exMasConfig) throws IOException { ... }
    private void validatePersonAttributes(Path personAttributesFile) throws IOException { ... }
}
```

**Test Method Pattern:**
- Single `@Test` method per scenario
- Helper methods for setup and validation
- Clear step comments (// 1. Setup, // 2. Configure, etc.)

## Mocking

**Framework:** None used currently

**Patterns:**
- Tests use real MATSim infrastructure (full integration tests)
- No mocking of MATSim components
- Uses MATSim example scenarios as test fixtures

**What to Mock (if unit tests added):**
- Network routing (LeastCostPathCalculator)
- Scoring functions (ScoringFunctionFactory)
- Population/scenario data

**What NOT to Mock:**
- Configuration groups (use real config)
- Domain objects (Ride, DrtRequest)
- CSV writers (verify actual file output)

## Fixtures and Factories

**Test Data:**
```java
// Using MATSim example scenarios as fixtures
URL scenarioUrl = ExamplesUtils.getTestScenarioURL("dvrp-grid");
// or
URL scenarioUrl = ExamplesUtils.getTestScenarioURL("kelheim");
```

**Population Enhancement:**
```java
// From ExMasDemandExtractionE2ETest.java
private void enhancePopulationWithAttributes(Population population) {
    int personCount = 0;
    for (Person person : population.getPersons().values()) {
        int personType = personCount % 3;
        if (personType == 0) {
            // Car owner - should create subtour groups
            PersonUtils.setLicence(person, "yes");
            PersonUtils.setCarAvail(person, "always");
        } else if (personType == 1) {
            // No car - trips should be independent
            PersonUtils.setLicence(person, "no");
            PersonUtils.setCarAvail(person, "never");
        } else {
            // Sometimes car available
            PersonUtils.setLicence(person, "yes");
            PersonUtils.setCarAvail(person, "sometimes");
        }
        personCount++;
    }
}
```

**ExMAS Configuration Setup:**
```java
// From ExMasKelheimE2ETest.java
private void configureExMas(Config config) {
    ExMasConfigGroup exMasConfig = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);

    exMasConfig.setDrtMode("drt");

    Set<String> baseModes = new HashSet<>();
    baseModes.add(TransportMode.car);
    baseModes.add(TransportMode.pt);
    baseModes.add(TransportMode.walk);
    baseModes.add(TransportMode.bike);
    exMasConfig.setBaseModes(baseModes);

    exMasConfig.setDrtRoutingMode(TransportMode.car);
    exMasConfig.setCommuteFilter(CommuteFilter.COMMUTES_ONLY);

    exMasConfig.setMinDrtCostPerKm(0.0);
    exMasConfig.setMinMaxDetourFactor(1.0);
    exMasConfig.setMinMaxWaitingTime(0.0);

    exMasConfig.setSearchHorizon(600.0);
    exMasConfig.setMaxDetourFactor(1.5);
    exMasConfig.setMaxPoolingDegree(10);
}
```

**Location:**
- Fixtures: MATSim examples via `ExamplesUtils.getTestScenarioURL()`
- Test output: `test/output/{test-name}/`

## Coverage

**Requirements:** None formally enforced

**View Coverage:**
```bash
# Run with JaCoCo (if configured in parent POM)
mvn test jacoco:report -pl contribs/drt-demand-extraction

# View report at target/site/jacoco/index.html
```

## Test Types

**Unit Tests:**
- Not currently present in module
- Would test individual classes in isolation
- Priority candidates: `PairGenerator`, `BudgetValidator`, `TravelSegment`

**Integration Tests:**
- Current test type (named E2E but really integration)
- Tests full demand extraction pipeline with MATSim
- Verifies module interaction with MATSim infrastructure

**E2E Tests:**
- `ExMasDemandExtractionE2ETest.java` - DVRP grid scenario
  - Uses 11x11 grid network (200m spacing)
  - Tests with DRT in config
  - Verifies basic demand extraction flow
- `ExMasKelheimE2ETest.java` - Kelheim scenario
  - Uses realistic small-town scenario
  - Tests WITHOUT DRT simulation (routing only)
  - Tests commute filtering
  - More comprehensive validation

## Common Patterns

**File Existence Assertions:**
```java
Path demandDir = testOutputDir.resolve("drt_demand");
Path requestsFile = demandDir.resolve("null.drt_requests.csv");
Path ridesFile = demandDir.resolve("null.exmas_rides.csv");

Assertions.assertTrue(Files.exists(requestsFile), "DRT requests file should exist: " + requestsFile);
Assertions.assertTrue(Files.exists(ridesFile), "ExMAS rides file should exist: " + ridesFile);
```

**CSV Content Validation:**
```java
// From ExMasDemandExtractionE2ETest.java
private void validateRequests(Path requestsFile) throws IOException {
    Set<String> personIds = new HashSet<>();
    int requestCount = 0;

    try (BufferedReader reader = IOUtils.getBufferedReader(requestsFile.toString())) {
        // Validate header
        String header = reader.readLine();
        Assertions.assertNotNull(header, "File should have header");
        Assertions.assertTrue(header.contains("personId"), "Header should contain personId");
        Assertions.assertTrue(header.contains("budget"), "Header should contain budget");

        // Validate each row
        String line;
        while ((line = reader.readLine()) != null) {
            String[] parts = line.split(",");
            Assertions.assertEquals(27, parts.length, "Each request should have 27 fields");

            String personId = parts[1];
            double budget = Double.parseDouble(parts[5]);
            personIds.add(personId);

            // Validate field value
            Assertions.assertFalse(Double.isNaN(budget), "Budget should be a valid number");

            requestCount++;
        }
    }

    // Validate aggregate counts
    Assertions.assertTrue(personIds.size() >= 3, "Should have requests from multiple persons");
    Assertions.assertTrue(requestCount >= 3, "Should have multiple trip requests");
}
```

**Ride Validation Pattern:**
```java
private void validateRides(Path ridesFile, ExMasConfigGroup exMasConfig) throws IOException {
    int rideCount = 0;
    Map<Integer, Integer> ridesByDegree = new HashMap<>();

    try (BufferedReader reader = IOUtils.getBufferedReader(ridesFile.toString())) {
        String header = reader.readLine();
        // Header validation...

        String line;
        while ((line = reader.readLine()) != null) {
            String[] parts = line.split(",");
            Assertions.assertEquals(22, parts.length, "Each ride should have 22 fields");

            int degree = Integer.parseInt(parts[1]);
            int maxDegree = exMasConfig.getMaxPoolingDegree();
            Assertions.assertTrue(degree >= 1 && degree <= maxDegree,
                "Degree should be between 1 and " + maxDegree);

            ridesByDegree.put(degree, ridesByDegree.getOrDefault(degree, 0) + 1);

            double duration = Double.parseDouble(parts[20]);
            Assertions.assertTrue(duration >= 0, "Duration should be non-negative");

            rideCount++;
        }
    }

    // Verify results
    Assertions.assertTrue(rideCount > 0, "Should have generated at least one ride");
    Assertions.assertTrue(ridesByDegree.getOrDefault(1, 0) > 0,
        "Should have generated at least one single-passenger ride");

    // Log summary for debugging
    System.out.println("\n=== Ride Generation Results ===");
    System.out.println("Total rides: " + rideCount);
    System.out.println("Single-passenger rides: " + ridesByDegree.getOrDefault(1, 0));
}
```

**Test Output Logging:**
```java
System.out.println("\n=== Test Output Location ===");
System.out.println("Requests: " + requestsFile.toAbsolutePath());
System.out.println("Rides: " + ridesFile.toAbsolutePath());
System.out.println("============================\n");
```

## Test Scenarios

**DVRP Grid Scenario (`ExMasDemandExtractionE2ETest`):**
- Network: 11x11 grid, 200m link spacing
- Population: 10 passengers (modified with car availability attributes)
- DRT: Configured and simulated
- Iterations: 1 (minimum for test)
- Purpose: Basic integration test

**Kelheim Scenario (`ExMasKelheimE2ETest`):**
- Network: Realistic small-town with PT
- Population: 1% sample, real trip patterns
- DRT: Routing only (no simulation)
- Iterations: 0 (warmup only)
- Purpose: Realistic scenario test, commute filtering

## Test Configuration

**Output Directory Setup:**
```java
Path testOutputDir = Path.of("test/output/exmas-e2e-test");
Files.createDirectories(testOutputDir);

config.controller().setOutputDirectory(testOutputDir.toString());
config.controller().setOverwriteFileSetting(
    OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
```

**Config Module Loading:**
```java
Config config = ConfigUtils.loadConfig(
    new URL(scenarioUrl, "config.xml").toString(),
    new MultiModeDrtConfigGroup(),
    new DvrpConfigGroup(),
    new ExMasConfigGroup());
```

**Freight Agent Filtering (Kelheim):**
```java
// Filter out freight agents (no proper link IDs)
scenario.getPopulation().getPersons().values()
    .removeIf(person -> person.getSelectedPlan().getPlanElements().stream()
        .filter(org.matsim.api.core.v01.population.Activity.class::isInstance)
        .map(org.matsim.api.core.v01.population.Activity.class::cast)
        .anyMatch(act -> act.getType().startsWith("freight")));
```

## Test Coverage Gaps

**Untested Areas:**

1. **Unit Tests for Core Algorithm Classes:**
   - `PairGenerator` - pair generation logic
   - `RideExtender` - ride extension algorithm
   - `SingleRideGenerator` - single ride creation
   - `TimeFilter` - temporal filtering
   - `ShareabilityGraph` - graph operations
   - Files: `algorithm/generation/*.java`, `algorithm/graph/*.java`
   - Risk: Algorithm bugs undetected until E2E tests
   - Priority: High

2. **Domain Object Builders:**
   - `Ride.Builder` validation
   - `DrtRequest.Builder` validation
   - `TravelSegment` edge cases
   - Files: `algorithm/domain/*.java`, `demand/DrtRequest.java`
   - Risk: Invalid objects created silently
   - Priority: Medium

3. **Budget Calculation:**
   - `BudgetValidator` scoring logic
   - `BudgetToConstraintsCalculator` conversion
   - Edge cases: negative budgets, NaN values
   - Files: `algorithm/validation/BudgetValidator.java`, `demand/BudgetToConstraintsCalculator.java`
   - Risk: Incorrect utility calculations
   - Priority: High

4. **CSV Writers:**
   - `ExMasCsvWriter` format correctness
   - `ConnectionCacheWriter` output format
   - `PersonAttributesWriter` edge cases
   - Files: `io/*.java`
   - Risk: Invalid output format breaks downstream Python
   - Priority: Medium

5. **Configuration Validation:**
   - `DemandExtractionConfigValidator` all paths
   - Invalid configuration combinations
   - Files: `demand/DemandExtractionConfigValidator.java`
   - Risk: Silent misconfigurations
   - Priority: Medium

6. **Chain/Commute Identification:**
   - `ChainIdentifier` subtour detection
   - `CommuteIdentifier` commute classification
   - Edge cases: complex activity patterns
   - Files: `demand/ChainIdentifier.java`, `demand/CommuteIdentifier.java`
   - Risk: Incorrect trip grouping
   - Priority: Medium

7. **Network Cache:**
   - `MatsimNetworkCache` concurrency
   - Cache import/export roundtrip
   - Routing failure handling
   - Files: `algorithm/network/MatsimNetworkCache.java`
   - Risk: Race conditions, incorrect caching
   - Priority: Low (well-tested via E2E)

---

*Testing analysis: 2026-01-20*
