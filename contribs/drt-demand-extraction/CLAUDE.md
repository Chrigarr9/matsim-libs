# DRT Demand Extraction — CLAUDE.md

## Project-Bound Memory

This project uses **`.project-memory/`** (at the Dissertation repo root) as a git-tracked shared memory store.
When you learn something worth remembering, sync it to `.project-memory/` — see the root `CLAUDE.md` for the full protocol.

## Planning & Context

Check `.planning/` for development plans, research notes, and phase history.
Also check the root `.project-memory/` for cross-project insights.

## Conversation History

Conversations are exported to `.claude-conversations/` directories (gzip-compressed JSONL).
If running Claude Code from this directory for the first time on a new machine, add this project
to `PROJECT_MAP` in `scripts/export-claude-conversations.py` (at the Dissertation repo root):

```python
"Dissertation-matsim-libs-contribs-drt-demand-extraction": {
    "export_dir": "matsim-libs/contribs/drt-demand-extraction/.claude-conversations",
    "label": "DRT Demand Extraction",
},
```

Then run `python scripts/export-claude-conversations.py` to export local conversations.
Conversations started from the Dissertation root that involve this module are already
captured in the root `.claude-conversations/`.

## Project Overview

MATSim contrib module that extracts DRT demand from MATSim population plans and generates optimized shared rides using the ExMAS algorithm with optional HyperPool (stop-based pooling).

**Pipeline:** MATSim agent plans -> DRT requests with utility budgets -> feasible shared rides (ExMAS) -> optional stop-based/hyper-pooled rides

## Commands

```bash
# Build
cd matsim-libs/contribs/drt-demand-extraction
mvn clean install

# Test
mvn test
mvn test -Dtest=ExMasDemandExtractionE2ETest    # Basic demand extraction
mvn test -Dtest=ExMasHyperPoolE2ETest            # HyperPool Stage 1 & 2
mvn test -Dtest=ExMasKelheimE2ETest              # Kelheim scenario
mvn test -Dtest=ExMasKelheimHyperPoolE2ETest     # Kelheim HyperPool (2610 agents)
mvn clean install -DskipTests                     # Build without tests
```

## Architecture

### Package Structure (`org.matsim.contrib.demand_extraction`)

**`config/`** — `ExMasConfigGroup` (central config), `StopFindingStrategy` (enum)

**`demand/`** — Demand extraction from MATSim
- `DemandExtractionModule` — Guice entry point
- `DemandExtractionListener` — Orchestrates workflow at simulation shutdown
- `ModeRoutingCache` — Routes all modes per trip, filters by person attributes
- `ChainIdentifier` — Identifies subtour chains and private vehicle dependencies
- `BudgetToConstraintsCalculator` — Converts utility budget to DRT constraints
- `DrtRequest` / `DrtRequestFactory` — Core request data objects

**`algorithm/engine/`** — Main ExMAS orchestration
- `ExMasEngine` — Entry point: singles -> pairs -> extensions -> HyperPool

**`algorithm/generation/`** — Ride generation
- `SingleRideGenerator` (degree 1), `PairGenerator` (degree 2 FIFO/LIFO), `TimeFilter`

**`algorithm/hyperpool/`** — HyperPool Stage 2
- `HyperPoolGenerator` — Bundles S2S rides into multi-stop sequences
- `HyperPoolShareabilityGraph`, `StopCompatibilityChecker`, `StopRelocator`

**`algorithm/stops/`** — Stop finding strategies
- `GeometricStopFinder`, `NetworkNodeStopFinder`, `PredefinedStopFinder`, etc.

**`algorithm/domain/`** — Core data: `Ride`, `HyperPooledRide`, `RideVariant`, `RideKind`

**`io/`** — Output: `ExMasCsvWriter`, `ConnectionCacheWriter`, `PersonAttributesWriter`

### Key Design Decisions
- **Trip-wise budgets:** Each trip gets independent budget, linked via `groupId` for subtour chains
- **Budget = score(DRT ideal) - score(best baseline):** Positive = DRT preferred
- **Combinatorial subtour logic:** Evaluates ALL feasible mode combos per subtour
- **Mode filtering early:** Only routes modes person can actually use (license, car availability)

## Output Format (`drt_requests.csv`)

Fields: `personId, groupId, tripIndex, budget, departureTime, originX, originY, destinationX, destinationY`

Budget can be negative (person prefers baseline mode but DRT still feasible).

## Configuration (`ExMasConfigGroup`)

Key params:
- `drtMode`, `baseModes`, `privateVehicleModes`
- DRT service quality: `minDrtCostPerKm`, `minMaxDetourFactor`, `minMaxWaitingTime`
- HyperPool Stage 1: `enableStopBased`, `maxWalkDistanceMeters`, `stopSearchRadiusMeters`
- HyperPool Stage 2: `enableHyperPooling`, `hyperPoolMinOccupancy`, `hyperPoolTimeWindowSeconds`
- Pruning: `maxPoolingDegree`, `pruningKeepTopFractionPerRequestSet`, `pruningMaxRidesToKeepPerRequestSet`

## Integration with Python (ExmasCommuters)

```
drt-demand-extraction (Java) → drt_requests.csv + exmas_rides.csv + network
ExmasCommuters (Python)      → MIP optimization + SimWrapper export
simwrapper (Vue.js)          → Interactive visualization
```

### Outstanding Integration TODOs
- `max_cost` per ride in rides CSV
- Shapley values export
- Predecessor/successor relationships
- Network export for empty vehicle distances

## Dependencies

Parent: `org.matsim:contrib` (2026.0-SNAPSHOT). Direct: `matsim`, `drt`, `dvrp` contribs. Java 17+.

## Important Notes

- Build excludes `**/legacy/**` from compilation
- Windows: avoid non-ASCII in output (cp1252 encoding issues)
- Test scenarios: hexagonal grid (unit), clustered grid (integration), Kelheim (E2E)
