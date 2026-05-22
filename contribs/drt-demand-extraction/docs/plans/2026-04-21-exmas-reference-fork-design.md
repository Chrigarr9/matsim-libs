# ExMAS Reference Fork — Design

**Date:** 2026-04-21
**Status:** Approved, ready for writing-plans.
**Related:**
- `papers/paper1/planning/simulation-flow.md` (§6 — Block 2 demand extraction, R1/R2/R3 matrix)
- `papers/paper1/planning/drt-demand-extraction-skeleton.md` (§1 — claims C1/C2/C3)
- `archive/docs-plans-old/2026-04-14-exmas-master-reference.md` (current algorithm reference; archived May 2026)

---

## 0. Problem

Paper 1 claims C1 (admissibility), C2 (tractability), and C3 (quality preservation under pruning) rest on comparing three configurations:

- **R1** — reference ExMAS (`main` branch of `matsim-libs/contribs/drt-demand-extraction/`), defaults.
- **R2** — current algorithm, quality pruning OFF.
- **R3** — current algorithm, quality pruning ON (default).

`main`'s algorithm and the current branch's algorithm have diverged across 81 commits. `main` has neither a Lyon eqasim runner nor the eqasim input adapters (population income conversion, IDF scoring stack, vehicles source). To run R1 on Lyon, we need the reference algorithm available inside a codebase that *does* speak eqasim.

The code base also needs to read as "two algorithms with a shared input pipeline" to future readers — the paper will cite this repository as the reproducibility artefact.

## 1. Decision summary

One binary, one input pipeline, two algorithms co-exist inside it, selected by a config flag (`--algorithm=exmas|bamas`). The reference algorithm is named **ExMAS** (frozen); the new algorithm is named **BAMAS** — *Budget-Aware Matching of Autonomous Shared-rides*. "ExMAS's `EX`act becomes BAMAS's `B`udget-`A`ware" captures the scientific swap (exactness → tractability + budget semantics).

The fork lives between DRT request construction and ride output — everything upstream (scoring, budgets, `DrtRequest` construction) and everything downstream (CSV writers, HyperPool Stage 2, post-extension pruner) is shared.

## 2. Architecture

### 2.1 Strategy pattern

```
DrtRequest[]  →  ExMasAlgorithm.run()  →  List<Ride>  →  [optional] PostExtensionPruner  →  ExMasCsvWriter
                 {exmas | bamas}                           {shared, config-gated}
```

New interface `org.matsim.contrib.demand_extraction.algorithm.ExMasAlgorithm`:

```java
interface ExMasAlgorithm {
    AlgorithmResult run(List<DrtRequest> requests);
}
record AlgorithmResult(List<Ride> rides, ExtensionStats stats) { }
```

Two implementations, each wraps its own engine stack. `ExMasAlgorithmModule` binds one or the other by reading `ExMasConfigGroup.getAlgorithm()`. Default is `BAMAS` so existing runners and tests keep current behaviour; R1 opts in explicitly.

### 2.2 What is shared vs forked

**Shared at `algorithm/*` root** — both strategies call identical code:
- `domain/*` (all byte-identical across branches): `Ride`, `RideKind`, `RideVariant`, `StopLocation`, `StopSequence`, `TravelSegment`, `HyperPooledRide`.
- `stops/*` (stop-finding, byte-identical).
- `hyperpool/*` (Stage 2, orthogonal to Stage 1 fork, byte-identical).
- `util/*` (byte-identical).
- `generation/StopBasedRideGenerator`, `generation/TimeFilter`, `engine/RidePostProcessor` (byte-identical).
- `graph/ShareabilityGraph` (current version; additive evolution — methods BAMAS uses, reference ignores).
- `network/MatsimNetworkCache` (current version; additive).
- `validation/BudgetValidator` (current version; scoring-context cache is perf-only).
- `engine/PostExtensionPruner` (current version, with `Mode.NONE | RATIO_THRESHOLD | COVERAGE_TOPK`). Applied as an *optional post-filter*, not inside either strategy.

**Under `algorithm/exmas/`** — reference ExMAS, ported from `main`@HEAD, adapted to current-branch infra types. 4 files:
- `ExMasReferenceEngine.java` (from main's `engine/ExMasEngine.java`).
- `ReferenceRideExtender.java` (from main's `extension/RideExtender.java`).
- `ReferenceSingleRideGenerator.java` (from main's `generation/SingleRideGenerator.java`).
- `ReferencePairGenerator.java` (from main's `generation/PairGenerator.java`).

> **2026-04-21 — Phase 0.3 result:** `git diff main..feature/bnb-tightening-v1 -- …/generation/PairGenerator.java` is empty. **PairGenerator is byte-identical across branches and stays shared** (path `algorithm/generation/PairGenerator.java`). `ReferencePairGenerator` is dropped from the port. Phase 2's task list reflects this: `algorithm/exmas/` holds 3 files, not 4.

Plus `ExMasReferenceAlgorithm.java` implementing the strategy interface and wiring these 3 files together.

Flat layout (single directory). The reference is frozen — no need to mirror main's deeper sub-structure.

**Under `algorithm/bamas/`** — current algorithm, renamed from current `algorithm/*`:
- `BamasEngine.java` (was `engine/ExMasEngine.java`).
- `extension/BamasRideExtender.java` (was `extension/RideExtender.java`; keeps the inline `pruningDistanceSavingsLogScale` filter).
- `generation/BamasSingleRideGenerator.java`.
- `generation/BamasPairGenerator.java`.
- `extension/OrderingEnumerator.java` (BAMAS-only).
- `extension/EnumerationStats.java` (BAMAS-only).
- `graph/DegreeGraph.java` (BAMAS-only).

Plus `BamasAlgorithm.java` implementing the strategy interface.

### 2.3 Run configuration matrix

| Run | `algorithm` | `pruningDistanceSavingsLogScale` | `postExtensionPrunerMode` |
|---|---|---|---|
| R1 | `EXMAS` | n/a (reference has no inline distance-savings filter) | `NONE` |
| R2 | `BAMAS` | `-1` (disabled) | `NONE` |
| R3 | `BAMAS` | default | `COVERAGE_TOPK`, K = 20 |

**Spot-check flagged for implementation:** before locking R1's "pruner NONE" default, verify `main`'s `RunKelheim…` / `RunBavaria…` actually ran with pruner disabled. If `main`'s default was `RATIO_THRESHOLD`, R1 config uses that instead. 5-minute check, no design impact either way.

### 2.4 Flag plumbing

- `ExMasConfigGroup` gains an enum field `algorithm` (default `BAMAS`).
- Three runners grow `--algorithm=exmas|bamas`: `RunKelheimDemandExtraction`, `RunBavariaEqasimDemandExtraction`, `RunLyonEqasimDemandExtraction`.
- Algorithm choice logged at run start alongside git SHA.

### 2.5 Names left alone (on purpose)

- `ExMasConfigGroup` — persisted in scenario XML (`<module name="exmas">`). Renaming breaks stored configs.
- `ExMasAlgorithmModule` — a Guice module that binds a strategy; neutral-ish already.
- Existing test classes (`ExMasKelheimE2ETest`, etc.) — keep their names; new tests are added alongside.

## 3. Output contract

Two algorithms, two output semantics. Same CSV schema.

- **BAMAS** — one ride per set, min-distance ordering. `List<Ride>.size() = |feasible sets|`.
- **ExMAS reference** — one ride per admissible ordering. Multiple rows per set.

The `Ride` domain class already permits multiple rides per set (no unique-per-set invariant). `exmas_rides.csv` writes whatever the algorithm produces. Downstream comparison collapses R1 by `argmin(ride_distance)` per `request_set_hash`.

**Schema additions:**
- Materialise `request_set_hash` as a CSV column. Already derivable from `requestIndices`, but materialising drops the reconstruct step in every downstream consumer (notebook, golden diff).

Both R1's full-orderings CSV and R2/R3's one-per-set CSV use this schema unchanged.

## 4. Port regression test

**Purpose:** catch cases where adapting main's code to current-branch types silently changed behaviour.

- `main`-binary output on Kelheim → golden CSV frozen at `src/test/resources/golden/exmas-reference-kelheim.csv` (plus `drt_requests.csv` for the input).
- `ExMasReferencePortRegressionTest` runs reconstructed `exmas/` on Kelheim and asserts strict equivalence with the golden: Jaccard = 1.0 per degree; best-distance rel-tol ≤ 1e-9 for 100 % of sets.
- `@Tag("regression")`, not part of default `mvn verify`. Run via `mvn test -Dgroups=regression` before tagging or algorithm-touching commits.
- Regeneration: `scripts/regenerate_exmas_reference_golden.sh` — spawns a worktree at `main`, builds, runs `main`'s Kelheim E2E, copies output CSV to resources dir. Golden header records `main`@SHA at generation time.

**Known weak points (documented, accepted):**
- Golden drifts if `main` bumps a dependency; regenerate via script.
- Determinism assumption: Kelheim routing is deterministic with a pinned RNG seed. If a parallelised routing cache warmup leaks from current's `MatsimNetworkCache`, test fails with a diagnosable signal; fix is force-sequential warmup in the test setup.
- Kelheim doesn't reach the degrees where `main`'s decomposition-based extender differs most from BAMAS. Lyon-scale algorithmic faithfulness is the C1 comparison's job, not this test's.

## 5. Comparison notebook

Paper-artefact analysis tool. One notebook per claim, both in `papers/paper1/analysis/`:

- `compare_exmas_c1.ipynb` — R1 vs R2 (admissibility).
- `compare_exmas_c3.ipynb` — R2 vs R3 (quality preservation under pruning).

**Structure per notebook:**
1. Parameter cell (edit per comparison: `R1_CSV`, `R2_CSV`, `OUT_DIR`).
2. Load + normalise: construct `request_set_hash` fallback, collapse R1 by `argmin`.
3. Set-level comparison: Jaccard / distance-agreement per degree with pass/fail against skeleton §1.5 thresholds.
4. Distribution comparison: overlay histograms, KS / chi² / Wasserstein with thresholds.
5. Optional `!jupyter nbconvert --to html --no-input` for paper supplement.

**Shared helpers** in `papers/paper1/analysis/exmas_compare.py`: `collapse_r1`, `jaccard_per_degree`, `distance_agreement_fraction`, `distribution_stats_per_degree`. Pure functions, importable from both notebooks.

**No runtime/RSS panel** — those numbers come from run logs into a manually-filled markdown table next to each run. JSON sidecar deferred to YAGNI.

**Prototype plan:** build against prior Bavaria CSVs before Lyon 10 % lands. Catches column/hash/empty-dist bugs on real data at zero production risk.

## 6. Scenario-agnostic test infrastructure

Tests parameterise over scenario × algorithm-profile. Same infrastructure covers Kelheim (fast smoke) and Lyon (thesis scale).

### 6.1 Scenario fixtures

```java
interface ExMasScenarioFixture {
    Config createConfig();
    void configureAlgorithm(Config config, AlgorithmProfile profile);
    void validateOutput(Path outputDir);
    String getName();
}
```

Two implementations:
- `KelheimScenarioFixture` — Kelheim scoring, mode set, DRT mode params.
- `LyonEqasimScenarioFixture` — IDF mode-choice bindings, `eqasim-drt-mode-parameters-idf.yml`, vehicles source, travel-times TSV, income conversion. Reads `LYON_SCENARIO_DIR`, `LYON_SCENARIO_PREFIX`, `LYON_TRAVEL_TIMES_TSV`, `LYON_SAMPLE_PCT` from env; `@EnabledIfEnvironmentVariable` skips cleanly when absent.

**Side effect:** the Lyon fixture is extracted *from* `RunLyonEqasimDemandExtraction`, not duplicated. Runner becomes: parse CLI → `fixture.createConfig()` → `fixture.configureAlgorithm(...)` → `controler.run()`. Same treatment for `RunKelheimDemandExtraction`. Tests and runners share setup.

### 6.2 Parameterised E2E test

```java
@ParameterizedTest(name = "{0} + {1}")
@MethodSource("scenarioAlgorithmMatrix")
void algorithmRunsEndToEnd(ExMasScenarioFixture scenario, AlgorithmProfile profile) { ... }
```

Matrix: `{Kelheim, Lyon-1%}` × `{R1, R2, R3}` = 6 tests.

`AlgorithmProfile` is a record: `{ Algorithm algorithm, double pruningDistanceSavingsLogScale, Mode prunerMode }`.

### 6.3 Execution tags

| Tag | Coverage | Invocation | When |
|---|---|---|---|
| `fast` | Kelheim × 3 profiles | `mvn verify` | every build |
| `scenario-lyon` | Lyon-1% × 3 profiles | `mvn test -Dgroups=scenario-lyon` (with Lyon env vars set) | pre-release, before Lyon-10 % runs |
| `regression` | Port regression (Kelheim-only) | `mvn test -Dgroups=regression` | before tagging, before algorithm-touching commits |

Port regression stays Kelheim-only by necessity: `main` can't run Lyon, so no Lyon golden is generatable. Lyon-scale faithfulness is covered by the notebook C1 comparison.

## 7. Implementation phases

| Phase | Output | Risk |
|---|---|---|
| 1 | Strategy scaffold: `ExMasAlgorithm` interface, `BamasAlgorithm` wrapping current engine, compile green with BAMAS default. | Low. |
| 2 | Port reference: copy 4 files from `main` into `exmas/`, rename, adapt to current types, wire `ExMasReferenceAlgorithm`, compile green. | Medium — `main`'s extender assumes evolved APIs; expect 1-2 days of "no such method" adaptation work. |
| 3 | Package rename: current `algorithm/engine/ExMasEngine`, `extension/RideExtender`, `generation/{Single,Pair}RideGenerator` → `bamas/*` with `Bamas` prefix. ~25 import-site updates. | Low — mechanical via IDE refactor. One commit. |
| 4 | Runner flags: `--algorithm` wired into three runners; logged at startup. | Low. |
| 5a | Fixture extraction: `Kelheim…Fixture`, `LyonEqasim…Fixture`; `Run…` runners refactored to call them; existing E2E tests migrated. | Medium — ~200-400 lines of refactor touching both runners and tests, but no behavioural change. |
| 5b | Parameterised `ExMasAlgorithmE2ETest` with 2×3 matrix. Tags applied. | Low. |
| 6 | Port regression: golden-CSV generation, `ExMasReferencePortRegressionTest`, iterate until green. | Medium — drives any port bugs to surface; golden generation requires worktree tooling. |
| 7 | Notebook prototype: `exmas_compare.py` helpers + `compare_exmas_c1.ipynb`, validated against Bavaria CSVs. | Low. |

Phase 6 is the risk-concentrator. If port regression fails past explainable float noise, root-cause in the port before declaring the fork complete.

## 8. Git hygiene

New branch `feature/exmas-reference-fork` off `feature/bnb-tightening-v1` to avoid entangling the algorithm-fork with the in-flight Lyon runner and bnb-tightening uncommitted changes on the current branch. Merges back when all phases green.

## 9. Documentation updates

- `matsim-libs/contribs/drt-demand-extraction/CLAUDE.md` — add "Algorithm fork" section documenting the `--algorithm` flag, the R1/R2/R3 configuration matrix, and env vars for the Lyon scenario fixture.
- `.project-memory/exmas-reference-fork-2026-04-21.md` — high-level architecture record for future-session context recovery.
- Paper skeletons (`drt-demand-extraction-skeleton.md`, `simulation-flow.md`) — no changes; they already describe R1/R2/R3 at the config level.

## 10. Scope fence

**In scope:**
- Two algorithms co-existing under one Guice-bound strategy.
- Kelheim + Lyon scenario fixtures, parameterised E2E tests.
- Port regression test (Kelheim-only).
- `compare_exmas_c1.ipynb` prototype validated on Bavaria CSVs.

**Out of scope (handled elsewhere):**
- Running Lyon 10 % / 25 % / 100 % — Paper 1 Block 2 execution, not infra work.
- `compare_exmas_c3.ipynb` — follow-on after R3 output is trusted.
- Runtime/RSS JSON sidecar — YAGNI until we actually want programmatic runtime tables.
- HyperPool Stage 2 comparison — orthogonal to R1/R2/R3 story.
- Retiring `RunBavariaEqasimDemandExtraction` — stays for historical comparisons and notebook-prototype data.

## 11. Open questions for the plan stage

1. `PairGenerator.java` is byte-identical across branches. Verify content identity before deciding whether to copy into `exmas/` or share. 5-minute diff.
2. `main`'s default `PostExtensionPruner` mode — confirm it was `NONE` / absent on Kelheim/Bavaria runs so R1's "pruner NONE" reproduces the reference. 5-minute log check.
3. `LYON_SAMPLE_PCT=1` — depends on whether Lyon 1 % plans exist by the time phase 5 lands. If not, downgrade to Kelheim-only for `scenario-lyon` tag with a TODO to wire Lyon when the scenario is available.
