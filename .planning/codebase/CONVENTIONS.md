# Coding Conventions

**Analysis Date:** 2026-01-20

**Focus:** DRT Demand Extraction module (`contribs/drt-demand-extraction/`)

## Naming Patterns

**Files:**
- Classes: PascalCase matching class name (e.g., `ExMasEngine.java`, `DrtRequest.java`)
- Test files: `{ClassName}Test.java` or `{ClassName}E2ETest.java` for end-to-end tests
- Package structure: `org.matsim.contrib.demand_extraction.{subpackage}`

**Classes:**
- Domain objects: Noun-based (e.g., `Ride`, `DrtRequest`, `TravelSegment`)
- Services/Processors: Action + noun (e.g., `PairGenerator`, `BudgetValidator`, `RidePostProcessor`)
- Configuration: `{Name}ConfigGroup` extending MATSim's `ReflectiveConfigGroup`
- Modules: `{Name}Module` extending MATSim's `AbstractModule`
- Listeners: `{Name}Listener` implementing MATSim controller listener interfaces

**Functions/Methods:**
- Getters: `get{PropertyName}()` or `is{BooleanProperty}()` for booleans
- Setters: `set{PropertyName}(value)`
- Actions: verb + object (e.g., `generatePairs()`, `validateAndPopulateBudgets()`, `cacheModes()`)
- Factory methods: `create{Thing}()` or `build{Thing}()`
- Calculation methods: `calculate{Thing}()` or `compute{Thing}()`

**Variables:**
- Local variables: camelCase (e.g., `requests`, `pairRides`, `timeBin`)
- Constants: UPPER_SNAKE_CASE (e.g., `GROUP_NAME`, `DEFAULT_WALK_SPEED`, `ARRAY_SEPARATOR`)
- Private fields: camelCase with `this.` prefix in constructors (e.g., `this.network = network`)

**Types/Enums:**
- Enums: PascalCase with UPPER_SNAKE_CASE values
```java
// From `algorithm/domain/RideKind.java`
public enum RideKind {
    SINGLE,  // Degree 1
    FIFO,    // First-In-First-Out
    LIFO,    // Last-In-First-Out
    MIXED    // Mixed order (degree 3+)
}
```

## Code Style

**Formatting:**
- Tab-based indentation (MATSim project convention)
- Opening braces on same line as declaration
- No explicit formatter configuration in module (follows parent project)

**Linting:**
- No module-specific linting configuration
- Follows MATSim project-wide conventions
- Compiler excludes `**/legacy/**` from build (see `pom.xml`)

## Import Organization

**Order:**
1. `java.*` standard library
2. External libraries (`org.apache.*`, `com.google.*`, `ch.sbb.*`)
3. MATSim core (`org.matsim.api.*`, `org.matsim.core.*`)
4. MATSim contrib (`org.matsim.contrib.*`)
5. Local package imports

**Example from `algorithm/engine/ExMasEngine.java`:**
```java
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideKind;
```

**Path Aliases:**
- None used; all imports are fully qualified

## Error Handling

**Patterns:**
- Throw `RuntimeException` with descriptive message for configuration errors
- Use `IllegalArgumentException` for invalid method parameters
- Return `null` from validation methods when validation fails (e.g., `BudgetValidator.validateAndPopulateBudgets()`)
- Log warnings/errors before throwing or returning failure

**Configuration Validation Pattern (from `demand/DemandExtractionConfigValidator.java`):**
```java
private static void validateExMasParameters(ExMasConfigGroup exMasConfig) {
    if (exMasConfig.getMaxDetourFactor() < 1.0) {
        throw new RuntimeException(
            "Invalid maxDetourFactor: " + exMasConfig.getMaxDetourFactor() +
            " (must be >= 1.0)");
    }
    // ...
}
```

**Builder Validation Pattern (from `demand/DrtRequest.java`):**
```java
public DrtRequest build() {
    if (directTravelTime < 0) {
        throw new IllegalArgumentException("Direct travel time cannot be negative: " + directTravelTime);
    }
    if (earliestDeparture > latestArrival - directTravelTime) {
        throw new IllegalArgumentException(
            String.format("Infeasible temporal window: earliest departure (%.2f) + travel time (%.2f) > latest arrival (%.2f)",
                earliestDeparture, directTravelTime, latestArrival)
        );
    }
    return new DrtRequest(this);
}
```

**Null Safety Pattern:**
```java
// From ExMasCsvWriter - handle potentially null fields
String budgets = ride.getRemainingBudgets() != null
    ? formatDoubleArray(ride.getRemainingBudgets())
    : "[]";
```

## Logging

**Framework:** Log4j2 via `org.apache.logging.log4j.LogManager`

**Logger Declaration:**
```java
private static final Logger log = LogManager.getLogger(ExMasEngine.class);
```

**Log Levels Used:**
- `log.info()` - Progress updates, phase transitions, statistics
- `log.warn()` - Recoverable issues, configuration mismatches
- `log.error()` - Failures that may affect results

**Structured Progress Logging Pattern (from `algorithm/engine/ExMasEngine.java`):**
```java
log.info("======================================================================");
log.info("Starting ExMAS algorithm");
log.info("  Requests: {}", drtRequests.size());
log.info("  Horizon: {}s", horizon);
log.info("  Max degree: {}", maxDegree);
log.info("======================================================================");
```

**Phase Logging Pattern:**
```java
log.info("");
log.info("PHASE 1: Single Ride Generation");
log.info("======================================================================");
// ... phase work ...
log.info("Pair generation complete: {} pairs from {} requests in {}s ({} pairs/s)",
    pairs.size(), requests.length, String.format("%.1f", seconds), String.format("%.1f", pairsPerSecond));
```

## Comments

**When to Comment:**
- Javadoc on all public classes and methods
- Inline comments for complex algorithms explaining the "why"
- Reference to Python implementation for ported code
- DESIGN notes for architectural decisions

**Javadoc Style:**
```java
/**
 * Validates ride feasibility against budget constraints using MATSim scoring.
 *
 * Builds complete DRT trips with access/egress walking legs and proper DRT routes.
 * Uses MATSim's ScoringFunction to calculate utility, ensuring accurate scoring with
 * all person-specific parameters and activity timing effects.
 *
 * @param ride the ride to validate (contains direct DrtRequest references)
 * @return new Ride with remainingBudgets populated, or null if any budget is negative
 */
public Ride validateAndPopulateBudgets(Ride ride) { ... }
```

**Python Reference Pattern:**
```java
/**
 * Immutable representation of a shared ride.
 * Corresponds to a row in the Python rides DataFrame.
 *
 * Python reference: src/exmas_commuters/core/exmas/rides.py
 */
public final class Ride { ... }
```

**Design Decision Comments:**
```java
// DESIGN NOTE: This implementation uses a separate cache layer on top of the router.
// An alternative design would be to implement a caching LeastCostPathCalculator decorator
// that wraps the base router (similar to caching patterns used elsewhere in MATSim).
```

## Function Design

**Size:**
- Methods typically 20-50 lines
- Complex algorithms broken into private helper methods
- Single responsibility per method

**Parameters:**
- Use domain objects over primitives when possible
- Builder pattern for objects with many parameters
- Avoid boolean parameters; use enums for clarity

**Return Values:**
- Return `null` for "not found" or "validation failed" cases
- Return new immutable objects instead of modifying inputs
- Use arrays for fixed-size collections, List for variable-size

**Builder Pattern (from `algorithm/domain/Ride.java`):**
```java
public static Builder builder() {
    return new Builder();
}

public Builder toBuilder() {
    return new Builder()
        .index(this.index)
        .degree(this.degree)
        // ... copy all fields
        ;
}

public static final class Builder {
    public Builder index(int index) { this.index = index; return this; }
    public Builder degree(int degree) { this.degree = degree; return this; }
    // ... fluent setters

    public Ride build() {
        // Validation
        if (degree < 1) {
            throw new IllegalArgumentException("Degree must be >= 1, got: " + degree);
        }
        return new Ride(this);
    }
}
```

## Module Design

**Guice Dependency Injection:**
- Use `@Inject` constructor injection (not field injection)
- Mark singletons with `@Singleton`
- Bind services in module's `install()` method

**Module Pattern (from `demand/DemandExtractionModule.java`):**
```java
public class DemandExtractionModule extends AbstractModule {
    @Override
    public void install() {
        // Check config prerequisites
        if (!config.getModules().containsKey(ExMasConfigGroup.GROUP_NAME)) {
            throw new RuntimeException("ExMasConfigGroup is required...");
        }

        // Bind services as eager singletons
        bind(ModeRoutingCache.class).asEagerSingleton();
        bind(ChainIdentifier.class).asEagerSingleton();
        bind(DrtRequestFactory.class).asEagerSingleton();

        // Install sub-modules
        install(new ExMasAlgorithmModule());

        // Register listeners
        addControllerListenerBinding().to(DemandExtractionListener.class);
    }
}
```

**Service Class Pattern:**
```java
@Singleton
public class BudgetValidator {
    private final ScoringFunctionFactory scoringFunctionFactory;
    private final ExMasConfigGroup exMasConfig;
    // ... other dependencies

    @Inject
    public BudgetValidator(
            ScoringFunctionFactory scoringFunctionFactory,
            ExMasConfigGroup exMasConfig,
            // ... other dependencies
            ) {
        this.scoringFunctionFactory = scoringFunctionFactory;
        this.exMasConfig = exMasConfig;
    }

    // Public methods...
}
```

**Exports:**
- Public API classes in main package directories
- Internal/helper classes kept package-private where possible

**Barrel Files:**
- Not used; MATSim convention uses explicit imports

## Immutability Patterns

**Immutable Domain Objects:**
- Mark classes as `final` when immutability is intended
- Use private constructor with Builder
- Defensive copy arrays in constructor and getters

```java
// From algorithm/domain/Ride.java
public final class Ride {
    private final int index;
    private final double[] passengerTravelTimes;

    private Ride(Builder builder) {
        this.index = builder.index;
        // Defensive copy
        this.passengerTravelTimes = builder.passengerTravelTimes.clone();
    }

    public double[] getPassengerTravelTimes() {
        return passengerTravelTimes.clone(); // Defensive copy on read
    }
}
```

**Record Classes for Internal Data:**
```java
// From algorithm/generation/PairGenerator.java
private record PairCandidate(
    DrtRequest reqI, DrtRequest reqJ, RideKind kind,
    DrtRequest[] originsOrderedRequests, DrtRequest[] destinationsOrderedRequests,
    double[] passengerTravelTimes, double[] passengerDistances,
    // ... more fields
) {
    static final Comparator<PairCandidate> COMPARATOR = Comparator
        .comparingInt((PairCandidate c) -> c.reqI.index)
        .thenComparingInt(c -> c.reqJ.index)
        .thenComparing(c -> c.kind);
}
```

## Parallel Processing Patterns

**Parallel Stream with Deterministic Output:**
```java
// From algorithm/generation/PairGenerator.java
// Phase 1: Parallel collection
List<PairCandidate> candidates = IntStream.range(0, total)
    .parallel()
    .mapToObj(i -> generateCandidatesForRequest(filter, i))
    .flatMap(List::stream)
    .collect(Collectors.toList());

// Phase 2: Sort deterministically
candidates.sort(PairCandidate.COMPARATOR);

// Phase 3: Sequential processing with index assignment
for (PairCandidate c : candidates) {
    Ride ride = buildRide(c, nextRideIndex);
    // ...
}
```

**Thread-Safe Caching:**
```java
// From algorithm/network/MatsimNetworkCache.java
private final ConcurrentHashMap<CacheKey, TravelSegment> cache = new ConcurrentHashMap<>();

public TravelSegment getSegment(Id<Link> originLinkId, Id<Link> destLinkId, double departureTime) {
    CacheKey key = new CacheKey(originLinkId, destLinkId, timeBin);
    // computeIfAbsent is atomic - prevents duplicate computation
    return cache.computeIfAbsent(key, k -> computeSegment(originLinkId, destLinkId, departureTime));
}

// Synchronized for non-thread-safe router
private synchronized TravelSegment computeSegment(...) {
    // Router calls here
}
```

## MATSim-Specific Conventions

**Configuration Groups:**
```java
public class ExMasConfigGroup extends ReflectiveConfigGroup {
    public static final String GROUP_NAME = "exmas";

    // Parameter name constants
    private static final String BUDGET_CALCULATION_MODE = "budgetCalculationMode";

    // Field with default value
    private BudgetCalculationMode budgetCalculationMode = BudgetCalculationMode.TRIP_LEVEL;

    @StringGetter(BUDGET_CALCULATION_MODE)
    public BudgetCalculationMode getBudgetCalculationMode() {
        return budgetCalculationMode;
    }

    @StringSetter(BUDGET_CALCULATION_MODE)
    public void setBudgetCalculationMode(BudgetCalculationMode mode) {
        this.budgetCalculationMode = mode;
    }

    @Override
    public Map<String, String> getComments() {
        Map<String, String> map = super.getComments();
        map.put(BUDGET_CALCULATION_MODE, "Mode for calculating utility budget...");
        return map;
    }
}
```

**Controller Listeners:**
```java
@Singleton
public class DemandExtractionListener implements ShutdownListener {
    @Override
    public void notifyShutdown(ShutdownEvent event) {
        // Runs after simulation completes
    }
}
```

**MATSim ID Handling:**
```java
Id<Person> personId = Id.createPersonId("person_1");
Id<Link> linkId = Id.createLinkId("link_123");
```

---

*Convention analysis: 2026-01-20*
