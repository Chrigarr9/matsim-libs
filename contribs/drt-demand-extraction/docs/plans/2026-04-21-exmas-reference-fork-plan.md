# ExMAS Reference Fork Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make `main`'s ExMAS algorithm available inside the current branch as a flag-selectable alternative to BAMAS, with scenario-agnostic tests (Kelheim + Lyon eqasim) and a comparison notebook, so Paper 1 can run R1/R2/R3 on the same binary.

**Architecture:** Strategy pattern — `ExMasAlgorithm` interface with `ExMasReferenceAlgorithm` (ported from `main`, frozen) and `BamasAlgorithm` (current, renamed). Guice binds one based on `ExMasConfigGroup.algorithm` enum. Input pipeline, CSV writers, HyperPool, and the `PostExtensionPruner` post-filter are shared.

**Tech Stack:** Java 17, Maven, MATSim 2026.0-SNAPSHOT, eqasim IDF, JUnit 5, Python 3 (pandas/scipy/matplotlib/jinja2), Jupyter.

**Design doc:** `docs/plans/2026-04-21-exmas-reference-fork-design.md` (same directory) — authoritative for architecture; this plan only enumerates tasks.

**Working dir:** All commands relative to `matsim-libs/contribs/drt-demand-extraction/` unless noted otherwise. `matsim-libs` is a git submodule; commit there.

**Git:** Start by branching `feature/exmas-reference-fork` off current `feature/bnb-tightening-v1` HEAD inside the submodule.

---

## Phase 0 — Setup

### Task 0.1: Branch + baseline compile

**Commands:**

```bash
cd matsim-libs
git checkout -b feature/exmas-reference-fork
cd contribs/drt-demand-extraction
mvn clean install -DskipTests -Denforcer.skip=true
```

**Expected:** build succeeds. If not, stop — environment issue, not a design issue.

**Commit:** no commit; this is a sanity check.

### Task 0.2: Spot-check R1 pruner default assumption

**Purpose:** design §2.3 flagged a 5-minute verification. Confirm `main`'s default `PostExtensionPruner` mode so R1 matches `main`'s defaults.

**Commands:**

```bash
git show main:contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/engine/PostExtensionPruner.java | head -80
git show main:contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/engine/ExMasEngine.java | grep -n -i 'pruner\|postExtension'
```

**Decide:** if `main`'s engine calls the pruner with `RATIO_THRESHOLD` on by default, R1 config is `RATIO_THRESHOLD` with `main`'s parameters. Otherwise R1 = `NONE`. Record the decision in a short memory file.

**Commit:**

```bash
# In .project-memory/ at Dissertation root (NOT in the submodule):
# write exmas-reference-pruner-default-2026-04-21.md with the finding
cd ../../../
# edit .project-memory/exmas-reference-pruner-default-2026-04-21.md
git add .project-memory/exmas-reference-pruner-default-2026-04-21.md
git commit -m "memory: R1 pruner default decision"
```

### Task 0.3: Verify PairGenerator identity across branches

**Purpose:** design §2.2 flagged that `PairGenerator` is byte-identical in size but may differ in content. Determine whether it really can be shared or must be copied into `exmas/`.

**Commands:**

```bash
cd matsim-libs
git diff main..feature/bnb-tightening-v1 -- contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/generation/PairGenerator.java
```

**Decide:** if diff is empty → stays shared, remove `ReferencePairGenerator` from Phase 2 task list. If non-empty → copy as planned.

Record decision as a comment at the top of the design doc (edit in place) and commit:

```bash
cd contribs/drt-demand-extraction
# edit docs/plans/2026-04-21-exmas-reference-fork-design.md — add a line under §2.2 recording the PairGenerator decision
git add docs/plans/2026-04-21-exmas-reference-fork-design.md
git commit -m "design: record PairGenerator identity check result"
```

### Task 0.4: Verify ShareabilityGraph / MatsimNetworkCache / BudgetValidator additive-only claim

**Commands:**

```bash
cd matsim-libs
for f in \
  contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/graph/ShareabilityGraph.java \
  contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/network/MatsimNetworkCache.java \
  contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/validation/BudgetValidator.java; do
  echo "=== $f ==="
  git diff main..feature/bnb-tightening-v1 -- "$f" | head -80
done
```

**Decide:** if any method signature on `main` no longer exists on current branch, that's a breaking change — port must adapt to the new API. Note which files fall into that category.

No commit; findings inform Phase 2 port work.

---

## Phase 1 — Strategy scaffold

Outcome: interface exists, BAMAS wraps the current engine, `--algorithm` flag compiles. All existing tests still pass.

### Task 1.1: Create `ExMasAlgorithm` interface + `AlgorithmResult` record

**Files (create):**
- `src/main/java/org/matsim/contrib/demand_extraction/algorithm/ExMasAlgorithm.java`
- `src/main/java/org/matsim/contrib/demand_extraction/algorithm/AlgorithmResult.java`

**Contents:**

```java
// ExMasAlgorithm.java
package org.matsim.contrib.demand_extraction.algorithm;

import java.util.List;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

public interface ExMasAlgorithm {
    AlgorithmResult run(List<DrtRequest> requests);
}
```

```java
// AlgorithmResult.java
package org.matsim.contrib.demand_extraction.algorithm;

import java.util.List;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;

public record AlgorithmResult(List<Ride> rides, java.util.Map<String, Object> diagnostics) { }
```

Rationale for `Map<String, Object> diagnostics` (vs a typed `ExtensionStats`): keep the interface stable across algorithms. Reference ExMAS doesn't produce `EnumerationStats`; BAMAS does. Both can push diagnostic numbers via the map without leaking the BAMAS-specific type into the interface.

**Verify:**

```bash
mvn compile -Denforcer.skip=true
```

**Expected:** success.

**Commit:**

```bash
git add src/main/java/org/matsim/contrib/demand_extraction/algorithm/ExMasAlgorithm.java \
        src/main/java/org/matsim/contrib/demand_extraction/algorithm/AlgorithmResult.java
git commit -m "feat: ExMasAlgorithm strategy interface + AlgorithmResult record"
```

### Task 1.2: Add `Algorithm` enum to `ExMasConfigGroup`

**Files (modify):** `src/main/java/org/matsim/contrib/demand_extraction/config/ExMasConfigGroup.java`

**Change:** add enum, field, getter/setter, and `@StringGetter`/`@StringSetter` for XML persistence.

```java
public enum Algorithm { EXMAS, BAMAS }

private Algorithm algorithm = Algorithm.BAMAS;

@StringGetter("algorithm")
public Algorithm getAlgorithm() { return algorithm; }

@StringSetter("algorithm")
public void setAlgorithm(Algorithm algorithm) { this.algorithm = algorithm; }
```

**Verify:**

```bash
mvn test -Dtest=ExMasKelheimE2ETest -Denforcer.skip=true
```

**Expected:** existing Kelheim E2E test still passes (default is BAMAS; behaviour unchanged).

**Commit:**

```bash
git add src/main/java/org/matsim/contrib/demand_extraction/config/ExMasConfigGroup.java
git commit -m "feat: Algorithm enum on ExMasConfigGroup (default BAMAS)"
```

### Task 1.3: Create `BamasAlgorithm` wrapping the current engine

**Files (create):** `src/main/java/org/matsim/contrib/demand_extraction/algorithm/bamas/BamasAlgorithm.java`

This is an *adapter* — it calls the current `ExMasEngine` without renaming it yet (renaming happens in Phase 3; now we only scaffold the strategy).

**Contents:**

```java
package org.matsim.contrib.demand_extraction.algorithm.bamas;

import java.util.List;
import javax.inject.Inject;
import org.matsim.contrib.demand_extraction.algorithm.AlgorithmResult;
import org.matsim.contrib.demand_extraction.algorithm.ExMasAlgorithm;
import org.matsim.contrib.demand_extraction.algorithm.engine.ExMasEngine;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

public class BamasAlgorithm implements ExMasAlgorithm {
    private final ExMasEngine engine;  // current engine, not yet renamed

    @Inject
    public BamasAlgorithm(ExMasEngine engine) { this.engine = engine; }

    @Override
    public AlgorithmResult run(List<DrtRequest> requests) {
        List<Ride> rides = engine.run(requests);
        return new AlgorithmResult(rides, java.util.Map.of());  // diagnostics wired later
    }
}
```

**Verify:** `mvn compile -Denforcer.skip=true` succeeds.

**Commit:**

```bash
git add src/main/java/org/matsim/contrib/demand_extraction/algorithm/bamas/BamasAlgorithm.java
git commit -m "feat: BamasAlgorithm adapter over current ExMasEngine"
```

### Task 1.4: Wire strategy into `ExMasAlgorithmModule`

**Files (modify):** `src/main/java/org/matsim/contrib/demand_extraction/algorithm/ExMasAlgorithmModule.java`

Bind `ExMasAlgorithm` to `BamasAlgorithm` when `config.getAlgorithm() == BAMAS`; throw `UnsupportedOperationException("R1 wiring lands in Phase 2")` for `EXMAS` until Phase 2 lands `ExMasReferenceAlgorithm`.

**Change pattern:**

```java
// inside the module's configure() or a @Provides method:
bind(ExMasAlgorithm.class).toProvider(() -> {
    switch (exMasConfig.getAlgorithm()) {
        case BAMAS: return injector.getInstance(BamasAlgorithm.class);
        case EXMAS: throw new UnsupportedOperationException("ExMasReferenceAlgorithm lands in Phase 2");
        default: throw new IllegalStateException();
    }
});
```

(Actual Guice idiom may differ — match the module's existing style.)

Also update any call site currently using `ExMasEngine` directly to use `ExMasAlgorithm`. This is the narrow seam — likely 1-2 call sites in `DemandExtractionListener` or similar.

**Verify:**

```bash
mvn test -Dtest=ExMasKelheimE2ETest -Denforcer.skip=true
```

**Expected:** Kelheim test passes with BAMAS path (default).

**Commit:**

```bash
git add -u
git commit -m "feat: wire ExMasAlgorithm strategy in ExMasAlgorithmModule"
```

### Task 1.5: Full test run as Phase 1 gate

**Commands:**

```bash
mvn test -Denforcer.skip=true
```

**Expected:** all existing tests pass. Strategy scaffold introduces no behavioural change when `algorithm=BAMAS`.

No commit.

---

## Phase 2 — Port reference ExMAS

Outcome: 4 files from `main` live under `exmas/`, adapted to current-branch APIs. `ExMasReferenceAlgorithm` implements the strategy. Code compiles. The regression test lands in Phase 6; Phase 2 stops at "compiles + reference can be bound in Guice without throwing."

### Task 2.1: Copy the 4 files from `main`

**Commands:**

```bash
cd matsim-libs

# Export main's 4 files into the exmas subpackage
mkdir -p contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/exmas

git show main:contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/engine/ExMasEngine.java \
  > contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/exmas/ExMasReferenceEngine.java

git show main:contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/RideExtender.java \
  > contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/exmas/ReferenceRideExtender.java

git show main:contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/generation/SingleRideGenerator.java \
  > contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/exmas/ReferenceSingleRideGenerator.java

# Conditional on Task 0.3 result:
git show main:contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/generation/PairGenerator.java \
  > contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/exmas/ReferencePairGenerator.java
```

Skip the last `git show` if Task 0.3 concluded PairGenerator is shareable.

**Verify:** files exist and are non-empty. `ls -la` on the exmas directory.

No commit yet; files don't compile.

### Task 2.2: Rename packages + classes in the copied files

**Files (modify, all 4):**
- `.../algorithm/exmas/ExMasReferenceEngine.java` — change `package … .engine` → `package … .exmas`; class name `ExMasEngine` → `ExMasReferenceEngine`.
- `.../algorithm/exmas/ReferenceRideExtender.java` — `package … .extension` → `.exmas`; class `RideExtender` → `ReferenceRideExtender`.
- `.../algorithm/exmas/ReferenceSingleRideGenerator.java` — `.generation` → `.exmas`; `SingleRideGenerator` → `ReferenceSingleRideGenerator`.
- `.../algorithm/exmas/ReferencePairGenerator.java` — `.generation` → `.exmas`; `PairGenerator` → `ReferencePairGenerator`.

Also update every intra-file reference between these 4 classes (e.g., `ExMasReferenceEngine` constructs `ReferenceRideExtender`, not `RideExtender`).

Use IDE refactor or sed. A rough sed for the package line only:

```bash
for f in contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/exmas/*.java; do
  sed -i '1,/^package/ s|package org\.matsim\.contrib\.demand_extraction\.algorithm\.\(engine\|extension\|generation\);|package org.matsim.contrib.demand_extraction.algorithm.exmas;|' "$f"
done
```

Then open each file and fix class-name references by hand (sed is unreliable for identifiers that may appear in javadoc, string literals, etc.).

**Verify:** `mvn compile -Denforcer.skip=true` — expect compile errors, but they should be "can't find type X" (from the class-name renames intersecting with other files importing `ExMasEngine` etc.) NOT package-statement errors.

No commit yet.

### Task 2.3: Fix compile errors in `exmas/` files — stage 1: API adaptation

**Expected error clusters** (from design §11 risks):
- "no method `foo` in `MatsimNetworkCache`" — API evolved; find the nearest current-branch equivalent and adapt.
- "no method `bar` in `ShareabilityGraph`" — likely additive; current has a superset of main's methods, so just compiles.
- "no method `baz` in `DrtRequest`" — the biggest risk; current `DrtRequest` may have restructured some fields.
- "cannot find class `X`" — if the port references a helper only on main. Inline the helper into the `exmas/` file (it belongs to the frozen reference).

**Approach:** compile; read the first 5 errors; fix each by adapting to the current API signature, keeping *algorithmic logic* identical. If an adaptation is non-obvious, leave a `// PORT-NOTE: <what I changed and why>` comment so the next pair of eyes can review.

**Verify:** `mvn compile -Denforcer.skip=true` succeeds after iteration.

**Commit:**

```bash
git add src/main/java/org/matsim/contrib/demand_extraction/algorithm/exmas/
git commit -m "feat: port main's ExMAS algorithm into exmas/ subpackage"
```

Budget for this task: 4-8 hours. If the port fights back beyond one working day, stop and re-evaluate the adaptation strategy — likely indicates an API mismatch that wants a different approach (extracting a compatibility shim).

### Task 2.4: Create `ExMasReferenceAlgorithm` strategy implementation

**Files (create):** `src/main/java/org/matsim/contrib/demand_extraction/algorithm/exmas/ExMasReferenceAlgorithm.java`

```java
package org.matsim.contrib.demand_extraction.algorithm.exmas;

import java.util.List;
import javax.inject.Inject;
import org.matsim.contrib.demand_extraction.algorithm.AlgorithmResult;
import org.matsim.contrib.demand_extraction.algorithm.ExMasAlgorithm;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

public class ExMasReferenceAlgorithm implements ExMasAlgorithm {
    private final ExMasReferenceEngine engine;

    @Inject
    public ExMasReferenceAlgorithm(ExMasReferenceEngine engine) { this.engine = engine; }

    @Override
    public AlgorithmResult run(List<DrtRequest> requests) {
        List<Ride> rides = engine.run(requests);
        return new AlgorithmResult(rides, java.util.Map.of());
    }
}
```

**Commit:**

```bash
git add src/main/java/org/matsim/contrib/demand_extraction/algorithm/exmas/ExMasReferenceAlgorithm.java
git commit -m "feat: ExMasReferenceAlgorithm strategy impl"
```

### Task 2.5: Wire `EXMAS` branch in `ExMasAlgorithmModule`

**Files (modify):** `.../algorithm/ExMasAlgorithmModule.java`

Replace the Phase 1 `UnsupportedOperationException` with `injector.getInstance(ExMasReferenceAlgorithm.class)`.

Also bind `ExMasReferenceEngine`, `ReferenceRideExtender`, `ReferenceSingleRideGenerator`, (`ReferencePairGenerator`) — mirror how `ExMasEngine` and friends are currently bound. If they're `@Inject`-constructor instances, no explicit binding needed; Guice resolves automatically.

**Verify:**

```bash
mvn test -Dtest=ExMasKelheimE2ETest -Denforcer.skip=true
```

**Expected:** still passes with BAMAS default; EXMAS path now reachable in principle.

**Commit:**

```bash
git add -u
git commit -m "feat: wire ExMasReferenceAlgorithm in module"
```

### Task 2.6: Smoke-test the EXMAS path (not regression, just "runs without throwing")

**Files (create, temporary):** `src/test/java/org/matsim/contrib/demand_extraction/algorithm/exmas/ExMasReferenceSmokeTest.java`

Copy the structure of `ExMasKelheimE2ETest` but set `Algorithm.EXMAS` and only assert "files exist + non-empty". This is the "it doesn't crash" check, NOT the strict regression.

```java
// in configureExMas(): exMasConfig.setAlgorithm(ExMasConfigGroup.Algorithm.EXMAS);
// validateRides() replaced with: assertThat(Files.exists(ridesFile)).isTrue(); + line count > 0
```

**Verify:**

```bash
mvn test -Dtest=ExMasReferenceSmokeTest -Denforcer.skip=true
```

**Expected:** passes. If fails, the port has a runtime bug — fix before moving on.

**Commit:**

```bash
git add src/test/java/org/matsim/contrib/demand_extraction/algorithm/exmas/ExMasReferenceSmokeTest.java
git commit -m "test: ExMasReferenceSmokeTest — EXMAS path runs end-to-end on Kelheim"
```

This test becomes obsolete once Phase 5b's parameterised test covers the same ground. Delete in Phase 5b.

---

## Phase 3 — Rename current algorithm to BAMAS

Outcome: current `algorithm/engine/ExMasEngine`, `algorithm/extension/RideExtender`, `algorithm/generation/SingleRideGenerator`, `algorithm/generation/PairGenerator` → `algorithm/bamas/*` with `Bamas` prefix. All imports updated. All tests pass.

### Task 3.1: IDE refactor — move + rename files

Execute via IDE (IntelliJ: right-click → Refactor → Move Class / Rename Class, which updates imports automatically). If no IDE available, use the hand-fallback in Task 3.2.

**Target moves:**

| From | To |
|---|---|
| `algorithm/engine/ExMasEngine` | `algorithm/bamas/BamasEngine` |
| `algorithm/extension/RideExtender` | `algorithm/bamas/extension/BamasRideExtender` |
| `algorithm/extension/OrderingEnumerator` | `algorithm/bamas/extension/OrderingEnumerator` (stays same class name) |
| `algorithm/extension/EnumerationStats` | `algorithm/bamas/extension/EnumerationStats` |
| `algorithm/generation/SingleRideGenerator` | `algorithm/bamas/generation/BamasSingleRideGenerator` |
| `algorithm/generation/PairGenerator` | `algorithm/bamas/generation/BamasPairGenerator` |
| `algorithm/graph/DegreeGraph` | `algorithm/bamas/graph/DegreeGraph` |

Leave `algorithm/engine/PostExtensionPruner`, `algorithm/engine/RidePostProcessor`, `algorithm/generation/StopBasedRideGenerator`, `algorithm/generation/TimeFilter`, `algorithm/graph/ShareabilityGraph` in place (shared).

### Task 3.2: (Fallback) hand-refactor via git + sed

If no IDE:

```bash
# 1. move the files
mkdir -p src/main/java/org/matsim/contrib/demand_extraction/algorithm/bamas/{extension,generation,graph}

git mv src/main/java/org/matsim/contrib/demand_extraction/algorithm/engine/ExMasEngine.java \
       src/main/java/org/matsim/contrib/demand_extraction/algorithm/bamas/BamasEngine.java
git mv src/main/java/org/matsim/contrib/demand_extraction/algorithm/extension/RideExtender.java \
       src/main/java/org/matsim/contrib/demand_extraction/algorithm/bamas/extension/BamasRideExtender.java
# ... etc for each row in the table above

# 2. update package statements inside moved files
# run a sed per file correcting the `package` declaration

# 3. update class names inside moved files (where file was renamed)
# sed ExMasEngine → BamasEngine, RideExtender → BamasRideExtender, etc.

# 4. update every import / usage elsewhere in the codebase
grep -rl "demand_extraction.algorithm.engine.ExMasEngine" src/ | xargs sed -i 's|demand_extraction\.algorithm\.engine\.ExMasEngine|demand_extraction.algorithm.bamas.BamasEngine|g'
grep -rl "\bExMasEngine\b" src/ | xargs sed -i 's|\bExMasEngine\b|BamasEngine|g'
# ... repeat for each rename
```

**Critical:** `BamasAlgorithm.java` from Task 1.3 imports `ExMasEngine` — update to `BamasEngine`.

### Task 3.3: Verify rename is complete + green

```bash
mvn clean test -Denforcer.skip=true
```

**Expected:** full test suite passes. Any `ClassNotFoundException` or compile failure = missed import.

**Commit:**

```bash
git add -A
git commit -m "refactor: rename current algorithm to BAMAS (bamas/ subpackage)"
```

Single commit for the rename — it's mechanical and reverting the whole thing is cleaner than reverting half.

### Task 3.4: Unblock `BamasAlgorithm` diagnostics

**Files (modify):** `algorithm/bamas/BamasAlgorithm.java` — populate `diagnostics` from `BamasEngine.getEnumerationStats()` (if BAMAS engine exposes it). Otherwise leave `Map.of()` and file a follow-up.

```java
// inside run():
List<Ride> rides = engine.run(requests);
Map<String, Object> diag = Map.of(
    "enumerationStats", engine.getEnumerationStats().toMap()  // or similar
);
return new AlgorithmResult(rides, diag);
```

**Commit:**

```bash
git add src/main/java/org/matsim/contrib/demand_extraction/algorithm/bamas/BamasAlgorithm.java
git commit -m "feat: BamasAlgorithm exposes EnumerationStats as diagnostics"
```

---

## Phase 4 — Runner `--algorithm` flag

### Task 4.1: `RunKelheimDemandExtraction`

**Files (modify):** `src/main/java/org/matsim/contrib/demand_extraction/run/RunKelheimDemandExtraction.java`

Add CLI flag and plumb through.

```java
// In parseArgs / CommandLine setup:
CommandLine cmd = new CommandLine.Builder(args)
    .allowOptions(..., "algorithm")
    .build();
String algo = cmd.getOption("algorithm").orElse("bamas");
exMasConfig.setAlgorithm(ExMasConfigGroup.Algorithm.valueOf(algo.toUpperCase()));

log.info("Running with algorithm: {}", exMasConfig.getAlgorithm());
```

**Commit:**

```bash
git add src/main/java/org/matsim/contrib/demand_extraction/run/RunKelheimDemandExtraction.java
git commit -m "feat: --algorithm=exmas|bamas flag in RunKelheimDemandExtraction"
```

### Task 4.2: `RunBavariaEqasimDemandExtraction`

Same pattern. Update the existing `ParsedArgs` record + `parseArgs` to capture `algorithm`.

**Commit:**

```bash
git add src/main/java/org/matsim/contrib/demand_extraction/run/RunBavariaEqasimDemandExtraction.java
git commit -m "feat: --algorithm flag in RunBavariaEqasimDemandExtraction"
```

### Task 4.3: `RunLyonEqasimDemandExtraction`

Same pattern. Include the flag in the javadoc example invocation.

**Commit:**

```bash
git add src/main/java/org/matsim/contrib/demand_extraction/run/RunLyonEqasimDemandExtraction.java
git commit -m "feat: --algorithm flag in RunLyonEqasimDemandExtraction"
```

### Task 4.4: Smoke-test all three runners with `--algorithm=exmas`

Runner-level smoke is manual; skip if time-constrained. Recommended quick checks:

```bash
# Kelheim — uses test scenario, may not have a standalone smoke invocation
mvn test -Dtest=ExMasReferenceSmokeTest

# Bavaria + Lyon need input data; skip if not available in current dev environment
```

No commit.

---

## Phase 5 — Scenario-agnostic test infrastructure

### Task 5a.1: Define `ExMasScenarioFixture` interface + `AlgorithmProfile` record

**Files (create):**
- `src/test/java/org/matsim/contrib/demand_extraction/testing/ExMasScenarioFixture.java`
- `src/test/java/org/matsim/contrib/demand_extraction/testing/AlgorithmProfile.java`

```java
package org.matsim.contrib.demand_extraction.testing;

import java.nio.file.Path;
import org.matsim.core.config.Config;

public interface ExMasScenarioFixture {
    Config createConfig(Path outputDir);
    void configureAlgorithm(Config config, AlgorithmProfile profile);
    void validateOutput(Path outputDir);
    String getName();
}
```

```java
package org.matsim.contrib.demand_extraction.testing;

import org.matsim.contrib.demand_extraction.algorithm.engine.PostExtensionPruner;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;

public record AlgorithmProfile(
    String label,   // "R1", "R2", "R3"
    ExMasConfigGroup.Algorithm algorithm,
    Double pruningDistanceSavingsLogScale,   // nullable = leave default
    PostExtensionPruner.Mode prunerMode
) {
    public static final AlgorithmProfile R1 =
        new AlgorithmProfile("R1", ExMasConfigGroup.Algorithm.EXMAS, null, PostExtensionPruner.Mode.NONE);
    public static final AlgorithmProfile R2 =
        new AlgorithmProfile("R2", ExMasConfigGroup.Algorithm.BAMAS, -1.0, PostExtensionPruner.Mode.NONE);
    public static final AlgorithmProfile R3 =
        new AlgorithmProfile("R3", ExMasConfigGroup.Algorithm.BAMAS, null, PostExtensionPruner.Mode.COVERAGE_TOPK);
}
```

(If Task 0.2 decided R1 needs `RATIO_THRESHOLD`, adjust `R1` constant.)

**Commit:**

```bash
git add src/test/java/org/matsim/contrib/demand_extraction/testing/
git commit -m "feat(test): ExMasScenarioFixture interface + AlgorithmProfile presets"
```

### Task 5a.2: Extract `KelheimScenarioFixture`

**Files (create):** `src/test/java/org/matsim/contrib/demand_extraction/testing/KelheimScenarioFixture.java`

Lift scenario setup out of `ExMasKelheimE2ETest` (the `configureExMas`, scoring config, mode set, DRT mode params). Implement all 4 interface methods. `validateOutput` asserts what the current `ExMasKelheimE2ETest.validateRequests` / `validateRides` assert.

**Commit:**

```bash
git add src/test/java/org/matsim/contrib/demand_extraction/testing/KelheimScenarioFixture.java
git commit -m "feat(test): KelheimScenarioFixture extracted from ExMasKelheimE2ETest"
```

### Task 5a.3: Refactor `ExMasKelheimE2ETest` to use the fixture

**Files (modify):** `src/test/java/org/matsim/contrib/demand_extraction/ExMasKelheimE2ETest.java`

Reduces to:

```java
@Test
void testDemandExtractionWithKelheimScenario() {
    var fixture = new KelheimScenarioFixture();
    var outputDir = Path.of("test/output/exmas-kelheim-e2e-test");
    Config config = fixture.createConfig(outputDir);
    fixture.configureAlgorithm(config, AlgorithmProfile.R3);   // current default
    runDemandExtraction(config);   // extracted helper
    fixture.validateOutput(outputDir);
}
```

**Verify:** `mvn test -Dtest=ExMasKelheimE2ETest` — same outcome as before.

**Commit:**

```bash
git add src/test/java/org/matsim/contrib/demand_extraction/ExMasKelheimE2ETest.java
git commit -m "refactor: ExMasKelheimE2ETest uses KelheimScenarioFixture"
```

### Task 5a.4: Extract `LyonEqasimScenarioFixture`

**Files (create):** `src/test/java/org/matsim/contrib/demand_extraction/testing/LyonEqasimScenarioFixture.java`

Lift scenario setup out of `RunLyonEqasimDemandExtraction` (IDF mode-choice bindings, DRT mode-params YAML, vehicles source, travel-times TSV loading, income conversion). The fixture reads config from env vars:

```java
private static final String SCENARIO_DIR = System.getenv("LYON_SCENARIO_DIR");
private static final String PREFIX = System.getenv().getOrDefault("LYON_SCENARIO_PREFIX", "lyon_drt_area_");
private static final String TRAVEL_TIMES = System.getenv("LYON_TRAVEL_TIMES_TSV");
private static final int SAMPLE_PCT = Integer.parseInt(System.getenv().getOrDefault("LYON_SAMPLE_PCT", "1"));
```

`createConfig` fails loudly if `LYON_SCENARIO_DIR` is null (test class is gated by `@EnabledIfEnvironmentVariable`, so this should never fire — belt and suspenders).

**Commit:**

```bash
git add src/test/java/org/matsim/contrib/demand_extraction/testing/LyonEqasimScenarioFixture.java
git commit -m "feat(test): LyonEqasimScenarioFixture from RunLyonEqasimDemandExtraction"
```

### Task 5a.5: Refactor `RunLyonEqasimDemandExtraction` to use the fixture

**Files (modify):** `src/main/java/org/matsim/contrib/demand_extraction/run/RunLyonEqasimDemandExtraction.java`

Runner delegates setup to fixture. Keeps CLI parsing + `controler.run()` only.

Note: the fixture lives under `src/test/java/`, but is needed from `src/main/`. Two options:
- **(a)** Move the fixture to `src/main/java/…/scenarios/LyonEqasimScenarioConfig.java` and re-export from tests. Cleaner separation.
- **(b)** Duplicate setup between `src/main` runner and `src/test` fixture. Rejected (the whole point of this phase).

Take option (a): move fixtures into `src/main/java/org/matsim/contrib/demand_extraction/scenarios/` under a new package. `src/test/` has thin test-only subclasses if additional test-specific knobs are needed.

Re-structure from 5a.2 and 5a.4: fixtures actually live in `src/main/java/...scenarios/`, interface file stays there too.

**Commit:**

```bash
git add -A
git commit -m "refactor: scenario fixtures live in src/main/scenarios, runner delegates"
```

### Task 5b.1: Parameterised `ExMasAlgorithmE2ETest`

**Files (create):** `src/test/java/org/matsim/contrib/demand_extraction/ExMasAlgorithmE2ETest.java`

```java
@Tag("fast")   // overridden per-scenario by nested class
public class ExMasAlgorithmE2ETest {

    static Stream<Arguments> scenarioAlgorithmMatrix() {
        return Stream.of(
            Arguments.of(new KelheimScenarioFixture(), AlgorithmProfile.R1),
            Arguments.of(new KelheimScenarioFixture(), AlgorithmProfile.R2),
            Arguments.of(new KelheimScenarioFixture(), AlgorithmProfile.R3)
        );
    }

    @ParameterizedTest(name = "{0} + {1}")
    @MethodSource("scenarioAlgorithmMatrix")
    @Tag("fast")
    void algorithmRunsEndToEnd(ExMasScenarioFixture scenario, AlgorithmProfile profile) {
        Path outputDir = Path.of("test/output/" + scenario.getName() + "-" + profile.label());
        Config config = scenario.createConfig(outputDir);
        scenario.configureAlgorithm(config, profile);
        runDemandExtraction(config);
        scenario.validateOutput(outputDir);
    }

    @Nested
    @Tag("scenario-lyon")
    @EnabledIfEnvironmentVariable(named = "LYON_SCENARIO_DIR", matches = ".+")
    class Lyon {
        @ParameterizedTest @MethodSource("lyonMatrix")
        void lyonAlgorithmRunsEndToEnd(ExMasScenarioFixture scenario, AlgorithmProfile profile) { /* ... */ }

        static Stream<Arguments> lyonMatrix() {
            return Stream.of(
                Arguments.of(new LyonEqasimScenarioFixture(), AlgorithmProfile.R1),
                Arguments.of(new LyonEqasimScenarioFixture(), AlgorithmProfile.R2),
                Arguments.of(new LyonEqasimScenarioFixture(), AlgorithmProfile.R3)
            );
        }
    }
}
```

**Verify:**

```bash
mvn test -Dtest=ExMasAlgorithmE2ETest -Denforcer.skip=true
```

**Expected:** 3 Kelheim test combinations pass. Lyon nested class skips (env var not set).

**Commit:**

```bash
git add src/test/java/org/matsim/contrib/demand_extraction/ExMasAlgorithmE2ETest.java
git commit -m "test: parameterised ExMasAlgorithmE2ETest (Kelheim + Lyon, 3 profiles)"
```

### Task 5b.2: Remove the Phase 2 smoke test

**Files (delete):** `src/test/java/org/matsim/contrib/demand_extraction/algorithm/exmas/ExMasReferenceSmokeTest.java`

Redundant — now covered by `ExMasAlgorithmE2ETest` Kelheim × R1.

**Commit:**

```bash
git rm src/test/java/org/matsim/contrib/demand_extraction/algorithm/exmas/ExMasReferenceSmokeTest.java
git commit -m "test: drop Phase 2 smoke test (covered by ExMasAlgorithmE2ETest)"
```

### Task 5b.3: Configure Maven Surefire for tag-based execution

**Files (modify):** `contribs/drt-demand-extraction/pom.xml`

Add / adjust surefire configuration to honour `-Dgroups`:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-surefire-plugin</artifactId>
  <configuration>
    <groups>${junit.groups}</groups>
    <excludedGroups>${junit.excludedGroups}</excludedGroups>
  </configuration>
</plugin>
```

With properties:

```xml
<properties>
  <junit.groups>fast</junit.groups>
  <junit.excludedGroups>scenario-lyon,regression</junit.excludedGroups>
</properties>
```

Default run (`mvn test`) is `fast` only. `mvn test -Djunit.groups=scenario-lyon -Djunit.excludedGroups=` runs Lyon. Similar for regression.

**Verify:**

```bash
mvn test -Denforcer.skip=true
mvn test -Djunit.groups=scenario-lyon -Djunit.excludedGroups= -Denforcer.skip=true
```

First is green with 3 Kelheim combos. Second reports Lyon tests skipped (no env var).

**Commit:**

```bash
git add pom.xml
git commit -m "build: surefire tag groups (fast default, scenario-lyon + regression opt-in)"
```

---

## Phase 6 — Port regression test

### Task 6.1: Generate golden CSV from `main`

**Commands:**

```bash
cd matsim-libs

# Spawn a worktree at main
git worktree add /tmp/matsim-libs-main main

# Build and run main's Kelheim E2E
cd /tmp/matsim-libs-main/contribs/drt-demand-extraction
mvn test -Dtest=ExMasKelheimE2ETest -Denforcer.skip=true

# Copy outputs to current-branch test resources
GOLDEN_DIR=/c/Users/VWAUCCY/dev/msf/projects/Dissertation/matsim-libs/contribs/drt-demand-extraction/src/test/resources/golden/exmas-kelheim
mkdir -p "$GOLDEN_DIR"
cp test/output/exmas-kelheim-e2e-test/drt_demand/*.exmas_rides.csv "$GOLDEN_DIR/exmas_rides.csv"
cp test/output/exmas-kelheim-e2e-test/drt_demand/*.drt_requests.csv "$GOLDEN_DIR/drt_requests.csv"

# Record main's SHA into a metadata file
echo "main_sha=$(cd /tmp/matsim-libs-main && git rev-parse HEAD)" > "$GOLDEN_DIR/METADATA"
echo "generated_at=$(date -Iseconds)" >> "$GOLDEN_DIR/METADATA"

# Clean up worktree
cd /c/Users/VWAUCCY/dev/msf/projects/Dissertation/matsim-libs
git worktree remove /tmp/matsim-libs-main
```

**Commit** (in the submodule):

```bash
cd contribs/drt-demand-extraction
git add src/test/resources/golden/exmas-kelheim/
git commit -m "test: golden CSVs for ExMAS port regression (main@<sha>)"
```

### Task 6.2: Write regeneration script

**Files (create):** `contribs/drt-demand-extraction/scripts/regenerate_exmas_reference_golden.sh`

Encapsulate Task 6.1's commands into a repeatable script with sanity checks (build failure aborts, golden dir creation is idempotent, existing golden is overwritten only with `--force`).

**Commit:**

```bash
git add scripts/regenerate_exmas_reference_golden.sh
git commit -m "test: regenerate_exmas_reference_golden.sh (documents golden creation)"
```

### Task 6.3: Write `ExMasReferencePortRegressionTest`

**Files (create):** `src/test/java/org/matsim/contrib/demand_extraction/algorithm/exmas/ExMasReferencePortRegressionTest.java`

Skeleton:

```java
@Tag("regression")
class ExMasReferencePortRegressionTest {

    @Test
    void reconstructedExMasMatchesMainBinaryOnKelheim() throws IOException {
        // 1. Run reconstructed exmas/ on Kelheim
        Path out = Path.of("test/output/exmas-port-regression");
        Config config = new KelheimScenarioFixture().createConfig(out);
        new KelheimScenarioFixture().configureAlgorithm(config, AlgorithmProfile.R1);
        runDemandExtraction(config);

        // 2. Load both CSVs
        Path actual = out.resolve("drt_demand/.../exmas_rides.csv");
        Path golden = Path.of("src/test/resources/golden/exmas-kelheim/exmas_rides.csv");

        // 3. Assert equivalence
        GoldenAsserter.assertEquivalent(golden, actual,
            GoldenAsserter.Tolerance.STRICT);   // Jaccard=1.0, rel-tol 1e-9
    }
}
```

Plus a `GoldenAsserter` helper that:
1. Reads both CSVs.
2. Groups actual (many orderings per set) by `request_set_hash`, takes `argmin(ride_distance)`.
3. Asserts set-membership Jaccard = 1.0 per degree.
4. Asserts per-set best-distance rel-tol ≤ 1e-9 for every common set.

**Commit:**

```bash
git add src/test/java/org/matsim/contrib/demand_extraction/algorithm/exmas/ExMasReferencePortRegressionTest.java \
        src/test/java/org/matsim/contrib/demand_extraction/testing/GoldenAsserter.java
git commit -m "test: ExMasReferencePortRegressionTest with strict golden asserter"
```

### Task 6.4: Run regression + iterate

**Commands:**

```bash
mvn test -Djunit.groups=regression -Djunit.excludedGroups= -Denforcer.skip=true
```

**Expected:** passes.

**If fails:**
1. Diff golden vs actual CSVs by hand. Identify: (a) sets present in one, not other, or (b) distance mismatches.
2. Trace to the port: which adaptation introduced the divergence?
3. Fix in `algorithm/exmas/*`. Re-run until green.
4. Budget: 1-3 days. If longer, the port strategy is wrong — pause and re-design.

**Commit the fix** once green:

```bash
git add -u
git commit -m "fix(exmas-port): <specific divergence> — now matches golden"
```

---

## Phase 7 — Comparison notebook

Notebook lives at Dissertation root, not inside the submodule.

### Task 7.1: `exmas_compare.py` helpers

**Files (create):** `papers/paper1/analysis/exmas_compare.py`

Pure functions:

```python
import pandas as pd
import numpy as np
from scipy.stats import ks_2samp, chi2_contingency, wasserstein_distance

def ensure_request_set_hash(df: pd.DataFrame) -> pd.DataFrame:
    """Fallback: construct request_set_hash from sorted requestIndices if absent."""
    if 'request_set_hash' in df.columns: return df
    df = df.copy()
    df['request_set_hash'] = df['requestIndices'].apply(
        lambda s: hash(tuple(sorted(int(x) for x in s.split(';'))))
    )
    return df

def collapse_r1(df: pd.DataFrame) -> pd.DataFrame:
    """R1 emits many orderings per set → collapse to min ride_distance per set."""
    df = ensure_request_set_hash(df)
    return df.loc[df.groupby('request_set_hash')['ride_distance'].idxmin()]

def jaccard_per_degree(a: pd.DataFrame, b: pd.DataFrame) -> pd.DataFrame:
    """Per-degree Jaccard of request_set_hash sets."""
    rows = []
    for d in sorted(set(a['degree']).union(b['degree'])):
        sa = set(a[a.degree == d].request_set_hash)
        sb = set(b[b.degree == d].request_set_hash)
        j = len(sa & sb) / max(len(sa | sb), 1)
        rows.append({'degree': d, 'jaccard': j, '|a|': len(sa), '|b|': len(sb), '|both|': len(sa & sb)})
    return pd.DataFrame(rows)

def distance_agreement_fraction(a: pd.DataFrame, b: pd.DataFrame, rel_tol: float) -> pd.DataFrame:
    """Per-degree fraction of common sets where |a_dist - b_dist| / a_dist < rel_tol."""
    ...  # left as an exercise; straightforward merge-on-hash + relative diff

def distribution_stats_per_degree(a: pd.DataFrame, b: pd.DataFrame, column: str) -> pd.DataFrame:
    """KS + Wasserstein per degree on the named column."""
    ...
```

**Verify:** none; helpers are imported in the notebook.

**Commit** (in Dissertation root, NOT submodule):

```bash
cd /c/Users/VWAUCCY/dev/msf/projects/Dissertation
git add papers/paper1/analysis/exmas_compare.py
git commit -m "analysis: exmas_compare helpers (collapse, jaccard, distance agreement, KS)"
```

### Task 7.2: `compare_exmas_c1.ipynb`

**Files (create):** `papers/paper1/analysis/compare_exmas_c1.ipynb`

Structure per design §5. Include:
- Parameter cell with path placeholders.
- Data-load cell.
- Jaccard table.
- Distance-agreement table (with pass/fail markers).
- Overlay histograms for degrees 2-5 (distance savings, pooling degree, detour).
- Final `nbconvert` cell (commented out by default).

Prototype against an existing pair of Bavaria CSVs to verify helpers work end-to-end.

**Commit:**

```bash
git add papers/paper1/analysis/compare_exmas_c1.ipynb
git commit -m "analysis: compare_exmas_c1.ipynb (R1 vs R2 claim check)"
```

### Task 7.3: Validate notebook on Bavaria CSVs

**Commands** (manual):

```bash
cd papers/paper1/analysis
jupyter notebook compare_exmas_c1.ipynb
# Set R1_CSV, R2_CSV to existing Bavaria outputs
# Run all cells
```

**Expected:** all cells run, plots render, summary tables populate. If any cell throws, fix in `exmas_compare.py` and re-run.

No commit unless helpers changed.

### Task 7.4: Document the notebook workflow

**Files (modify):** `papers/paper1/planning/drt-demand-extraction-skeleton.md`

Update §1.6 from "One script: `scripts/compare_exmas_runs.py`" to point at the notebook. One paragraph, no restructuring.

**Commit:**

```bash
git add papers/paper1/planning/drt-demand-extraction-skeleton.md
git commit -m "paper1: update skeleton §1.6 to reference compare_exmas_c1.ipynb"
```

---

## Phase 8 — Documentation & merge

### Task 8.1: Update module `CLAUDE.md`

**Files (modify):** `matsim-libs/contribs/drt-demand-extraction/CLAUDE.md`

Add an "Algorithm fork" section:

```markdown
## Algorithm fork

Two algorithms co-exist, selected by `--algorithm=exmas|bamas` (default `bamas`):

- `exmas/` — reference ExMAS, ported from `main` branch, frozen.
- `bamas/` — Budget-Aware Matching of Autonomous Shared-rides (the active algorithm).

Run configurations for Paper 1 C1/C2/C3 claims: see `docs/plans/2026-04-21-exmas-reference-fork-design.md` §2.3.

Lyon test fixtures require env vars: `LYON_SCENARIO_DIR`, `LYON_SCENARIO_PREFIX`, `LYON_TRAVEL_TIMES_TSV`, `LYON_SAMPLE_PCT`.
Port regression: `mvn test -Djunit.groups=regression -Djunit.excludedGroups=`.
```

**Commit:**

```bash
git add matsim-libs/contribs/drt-demand-extraction/CLAUDE.md
git commit -m "docs: CLAUDE.md notes the algorithm fork"
```

### Task 8.2: Memory entry

**Files (create):** `.project-memory/exmas-reference-fork-2026-04-21.md`

Short, high-signal: "Why we have two algorithms under `algorithm/exmas/` and `algorithm/bamas/`; key decisions and pointers to design + plan."

**Commit:**

```bash
# In Dissertation root
git add .project-memory/exmas-reference-fork-2026-04-21.md .project-memory/MEMORY.md
git commit -m "memory: exmas reference fork architecture (index + entry)"
```

### Task 8.3: Merge branch back

**Commands:**

```bash
cd matsim-libs
git checkout feature/bnb-tightening-v1
git merge --no-ff feature/exmas-reference-fork -m "merge: ExMAS reference fork (R1/R2/R3 infrastructure)"
```

Resolve any conflicts with the in-flight Lyon runner + bnb-tightening changes currently uncommitted on `feature/bnb-tightening-v1`. Most should not conflict — the fork work touches different files.

No separate commit; merge is the commit.

---

## Done criteria

- [ ] `mvn test -Denforcer.skip=true` green — 3 Kelheim × R1/R2/R3 pass by default.
- [ ] `mvn test -Djunit.groups=regression -Djunit.excludedGroups= -Denforcer.skip=true` green — port regression passes.
- [ ] `mvn test -Djunit.groups=scenario-lyon -Djunit.excludedGroups= -Denforcer.skip=true` (with Lyon env vars set) green — 3 Lyon-1% × R1/R2/R3 pass.
- [ ] `compare_exmas_c1.ipynb` produces a complete HTML report when run against Bavaria CSVs.
- [ ] Design doc and memory entry committed; `CLAUDE.md` updated.
- [ ] Branch merged into `feature/bnb-tightening-v1` cleanly.

**Estimated total effort:** 3-5 working days, dominated by Phase 2 port adaptation and Phase 6 regression iteration.
