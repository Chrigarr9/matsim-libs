# Technology Stack

**Analysis Date:** 2026-01-20

## Languages

**Primary:**
- Java 21 - Core implementation language for all MATSim code and the DRT demand extraction module

**Secondary:**
- XML - Configuration files (MATSim config, Maven POMs)

## Runtime

**Environment:**
- Java 21 (LTS) via Temurin distribution
- Maven build system
- SDKMAN for Java version management (`.sdkmanrc` specifies `java=22.0.2-tem` for development)

**Package Manager:**
- Apache Maven (managed by parent POM)
- Lockfile: Not applicable (Maven uses explicit versions)

## Frameworks

**Core:**
- MATSim 2026.0-SNAPSHOT - Agent-based transport simulation framework
- Google Guice 7.0.0 - Dependency injection framework used throughout MATSim

**Testing:**
- JUnit Jupiter 6.0.1 - Unit and integration testing
- Mockito 5.20.0 - Mocking framework (available via parent POM)
- AssertJ 3.27.6 - Fluent assertions (available via parent POM)

**Build/Dev:**
- Maven Compiler Plugin - Configured for Java 21 (`maven.compiler.release=21`)
- Maven Surefire - Test execution

## Key Dependencies

**DRT Demand Extraction Module Direct Dependencies:**

From `contribs/drt-demand-extraction/pom.xml`:
- `org.matsim:matsim:2026.0-SNAPSHOT` - Core MATSim framework
- `org.matsim.contrib:drt:2026.0-SNAPSHOT` - Demand Responsive Transport contrib
- `org.matsim.contrib:dvrp:2026.0-SNAPSHOT` - Dynamic Vehicle Routing Problem contrib
- `org.junit.jupiter:junit-jupiter-api` - Testing (scope: test)

**Transitive Dependencies via MATSim Core:**
- Log4j 2.25.2 - Logging framework
- GeoTools 34.1 - Geospatial operations
- JTS 1.20.0 - Java Topology Suite for geometry
- Jackson 2.20.1 - JSON serialization
- Guava 33.5.0-jre - Utility library
- Apache Commons (lang3 3.20.0, math3 3.6.1, csv 1.14.1, io 2.21.0)
- FastUtil 8.5.18 - High-performance collections

**MATSim DRT Contrib Transitive:**
- OpenCSV 5.12.0 - CSV parsing
- JFreeChart 1.5.6 - Chart generation
- StreamEx 0.8.4 - Enhanced stream operations

## Configuration

**Environment:**
- No environment variables required for core functionality
- External scenario configs (e.g., Kelheim) reference online SVN resources

**Build Configuration Files:**
- `pom.xml` - Maven project configuration
- `.sdkmanrc` - SDKMAN Java version specification

**MATSim Configuration:**
- `ExMasConfigGroup` - Custom config group for demand extraction parameters
  - Budget calculation mode (TRIP_LEVEL, SUBTOUR_SUM)
  - DRT mode name and routing fallback
  - Base modes for comparison (car, pt, bike, walk)
  - Service quality parameters (minDrtCostPerKm, minMaxDetourFactor, etc.)
  - ExMAS algorithm parameters (searchHorizon, maxPoolingDegree)
  - Pruning heuristics for combinatorial control
  - Predecessor/successor calculation settings

## Platform Requirements

**Development:**
- Java 21+ (tested with Temurin 22.0.2)
- Maven 3.6+
- Recommended: SDKMAN for Java version management
- IDE: IntelliJ IDEA or Eclipse with Maven support

**Production:**
- JVM with minimum 2GB heap recommended for small scenarios
- Large scenarios (Kelheim 25%) may require 8GB+ heap
- No external services required (standalone JVM application)

## Module Dependencies

The DRT demand extraction module integrates with the MATSim contrib ecosystem:

```
drt-demand-extraction
    |
    +-- matsim (core)
    |       |-- Network, Population, Scenario
    |       |-- TripRouter, ScoringFunctionFactory
    |       |-- LeastCostPathCalculator (routing)
    |       |-- ReflectiveConfigGroup (configuration)
    |
    +-- drt (Demand Responsive Transport)
    |       |-- DrtControlerCreator
    |       |-- MultiModeDrtConfigGroup
    |       |-- DRT route handling
    |
    +-- dvrp (Dynamic Vehicle Routing)
            |-- DvrpConfigGroup
            |-- Network mode configuration
```

## Versioning

- Project version: 2026.0-SNAPSHOT (aligned with MATSim release cycle)
- Parent POM: `org.matsim:contrib:2026.0-SNAPSHOT`
- All MATSim dependencies use `${project.parent.version}` for consistency

---

*Stack analysis: 2026-01-20*
