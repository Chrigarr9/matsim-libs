# DRT Demand Extraction — CLAUDE.md

<!-- Keep under 200 lines. Build traps, the Java->Python export contract, and two-phase
     restrictions live in <repo-root>/.claude/rules/java-matsim.md and load when you
     touch a .java file here. -->

## Memory & Planning

- `.project-memory/` (Dissertation root) is the git-tracked shared memory store. Protocol:
  root `CLAUDE.md`.
- `.planning/` holds development plans, research notes, and phase history.
- **Build traps, the export contract, and two-phase restrictions load automatically** from
  `.claude/rules/java-matsim.md`.

## Project Overview

MATSim contrib module that extracts DRT demand from MATSim population plans and generates
optimized shared rides with the ExMAS algorithm, plus optional HyperPool (stop-based
pooling).

**Pipeline:** MATSim agent plans -> DRT requests with utility budgets -> feasible shared
rides (ExMAS) -> optional stop-based / hyper-pooled rides

## Algorithm fork

Two Stage-1 algorithms co-exist, selected by `--algorithm=exmas|bamas` (default `bamas`):

- **`algorithm/exmas/`** — reference ExMAS ported from `main`, **frozen**. Paper 1 R1
  (vanilla ExMAS baseline). Verified byte-equal to `main` on Kelheim by
  `ExMasReferencePortRegressionTest`.
- **`algorithm/bamas/`** — Budget-Aware Matching of Autonomous Shared-rides, the active
  algorithm. Paper 1 R2 (no pruning), R3 (distance-only), R4 (full production pruning).

Paper 1 settings come from the orthogonal triple `--algorithm` / `--gate-scale` /
`--coverage-k` on `RunLyonEqasimDemandExtraction`, or direct `ExMasConfigGroup` setters in
tests. The legacy `AlgorithmProfile` R1..R8 bundle was retired. R2 subset R3 subset R4 is
the conceptual progression by enabled gates. Per-scenario setup lives in
`scenarios/{Kelheim,LyonEqasim}ScenarioFixture` so runners and tests share it.

Architecture: `docs/plans/2026-04-21-exmas-reference-fork-design.md`.

## Commands

```bash
# Build — requires JDK 25 (eqasim 2.1.0 is compiled with Java 25)
cd matsim-libs/contribs/drt-demand-extraction
mvn clean install
mvn clean install -DskipTests

# Tests
mvn test                                                      # Kelheim cells + all unit tests
mvn test -Pscenario-lyon                                      # Lyon cells, 100 GB heap
mvn test -Djunit.groups=scenario-lyon -Djunit.excludedGroups=  # Lyon cells, default heap
mvn test -Djunit.groups=regression    -Djunit.excludedGroups=  # ExMAS port regression vs golden
mvn test -Dtest=ExMasAlgorithmE2ETest                          # Paper 1 profile matrix, Kelheim
mvn test -Dtest=ExMasKelheimE2ETest                            # Kelheim, runner default
mvn test -Dtest=ExMasKelheimHyperPoolE2ETest                   # Kelheim HyperPool

# Lyon test env vars
export LYON_SCENARIO_DIR=/path/to/output_lyon_drt_10pct/lyon_drt_area
export LYON_SCENARIO_PREFIX=lyon_drt_area_    # default
export LYON_TRAVEL_TIMES_TSV=/path/to/travel_times.tsv
export LYON_SAMPLE_PCT=10                     # default 1

# Regenerate the ExMAS port regression golden against current main HEAD
scripts/regenerate_exmas_reference_golden.sh [--force]
```

## Package layout (`org.matsim.contrib.demand_extraction`)

| Package | Role |
|---|---|
| `config/` | `ExMasConfigGroup` (central config), `StopFindingStrategy` |
| `demand/` | Extraction from MATSim: `DemandExtractionModule`/`Listener`, `ModeRoutingCache`, `ChainIdentifier`, `BudgetToConstraintsCalculator`, `DrtRequest`/`Factory` |
| `algorithm/engine/` | `RidePostProcessor` — maxCost/Shapley/successor enrichment, both algorithms |
| `algorithm/bamas/` | `BamasEngine` (phase-structured), `extension/`, `ride/` (columnar store + `RideLayerIO`), `checkpoint/` (per-degree resume), `generation/`, `graph/` |
| `algorithm/exmas/` | Frozen reference ExMAS |
| `algorithm/selection/` | `RideSelector` — `COVERAGE_TOPK` / `RATIO_THRESHOLD` gates over `RideLayer`s |
| `algorithm/generation/` | `SingleRideGenerator`, `PairGenerator` (FIFO/LIFO), `TimeFilter` |
| `algorithm/hyperpool/` | `HyperPoolGenerator`, `HyperPoolShareabilityGraph`, `StopCompatibilityChecker`, `StopRelocator` |
| `algorithm/stops/` | `Geometric`/`NetworkNode`/`Predefined` stop finders |
| `algorithm/domain/` | `Ride`, `HyperPooledRide`, `RideVariant`, `RideKind` |
| `io/` | `ExtractionDataManager` (owns `<demandDir>/<runId>.<name>.csv`), `ExMasCsvWriter`, `ConnectionCacheWriter`, `PersonAttributesWriter` |

On-disk checkpoint files keep their legacy `.stubs.bin` names despite the `stub/` ->
`ride/` package rename.

## Key design decisions

- **Trip-wise budgets:** each trip gets an independent budget, linked via `groupId` for
  subtour chains.
- **Budget = score(DRT ideal) - score(best baseline).** Positive means DRT is preferred.
  It can be negative (the person prefers the baseline mode but DRT is still feasible).
- **Combinatorial subtour logic:** evaluates all feasible mode combinations per subtour.
- **Mode filtering early:** only routes modes the person can actually use (license, car
  availability).

## Output (`drt_requests.csv`)

`personId, groupId, tripIndex, budget, departureTime, originX, originY, destinationX,
destinationY`

## Configuration (`ExMasConfigGroup`)

- `drtMode`, `baseModes`, `privateVehicleModes`
- Service quality: `minDrtCostPerKm`, `minMaxDetourFactor`, `minMaxWaitingTime`
- HyperPool stage 1: `enableStopBased`, `maxWalkDistanceMeters`, `stopSearchRadiusMeters`
- HyperPool stage 2: `enableHyperPooling`, `hyperPoolMinOccupancy`,
  `hyperPoolTimeWindowSeconds`
- Pruning: `maxPoolingDegree`, `pruningKeepTopFractionPerRequestSet`,
  `pruningMaxRidesToKeepPerRequestSet`
- Handoff pass: `maxSuccessors` (default 50), `predecessorsFilterTime` (default 1800 s),
  `predecessorsFilterDistanceFactor`, `connectionCacheExportMode`

## Low-memory two-phase mode

Phase 1 runs the eqasim Controler through request construction, dumps to disk, and
`System.exit(0)`, releasing the heap. Phase 2 runs the algorithm in a fresh JVM.
Restrictions and the validation gate are in `.claude/rules/java-matsim.md`.
Plan: `../../../docs/plans/2026-05-17-drt-extraction-low-memory-mode-plan.md`.

```bash
# Orchestrator: one command, two JVMs in sequence
mvn exec:java -Dexec.mainClass="org.matsim.contrib.demand_extraction.run.RunDemandExtractionTwoPhase" \
  -Dexec.args="--sample 1 --scenario-dir <dir> --prefix lyon_drt_1pct_ \
               --travel-times <tsv> --output-dir <out> --algorithm bamas \
               --phase1-heap 110g --phase2-heap 110g" \
  -Denforcer.skip=true
```

Individual phases for debugging: `RunDemandExtractionPhase1`
(`--phase1-dump-dir <out>/phase1_dump`) then `RunDemandExtractionPhase2`
(`--phase1-dir`, `--network`, `--travel-times`, `--output-dir`).

### Dump layout (`<outputDir>/phase1_dump/`)

| File | Contents |
|---|---|
| `drt_requests_phase1.csv` | DRT requests (additive-only schema, includes link coords) |
| `scoring_contexts.bin` | Per-request scoring scalars + per-type activity table |
| `phase1_meta.json` | drtMode, walkSpeed, opportunity-cost model, run id, sample %, peak heap, wall time, eqasim scalars |
| `phase1_config.xml` | Live MATSim Config snapshot; Phase 2 rebuilds the config groups from it |

## Conversation history

Conversations export to `.claude-conversations/`. On a new machine, add this project to
`PROJECT_MAP` in `scripts/export-claude-conversations.py` at the Dissertation root:

```python
"Dissertation-matsim-libs-contribs-drt-demand-extraction": {
    "export_dir": "matsim-libs/contribs/drt-demand-extraction/.claude-conversations",
    "label": "DRT Demand Extraction",
},
```

## Dependencies

Parent `org.matsim:contrib` (2026.0-SNAPSHOT). Direct: `matsim`, `drt`, `dvrp` contribs.
Build JDK 25.
