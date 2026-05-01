# R1 Partial Degree-5 Checkpointing Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Export append-only partial R1 degree-5 performance checkpoints so an uncapped Lyon run can preserve rides-evaluated and memory metrics even if the reference engine OOMs mid-degree.

**Architecture:** Add an opt-in reference-side checkpoint sink that the reference extender calls during long-running degree processing, backed by a small CSV writer in `algorithm/profiling`. Keep default behavior unchanged unless the fast-comparison test enables checkpoint export, and treat OOM as a structured terminal event instead of losing the partial trace.

**Tech Stack:** Java 21, JUnit 5, MATSim contrib module, existing `MemoryProfiler`, existing `ExMasLyonR1R2FastComparisonTest` harness.

---

### Task 1: Add failing checkpoint writer test

**Files:**
- Create: `contribs/drt-demand-extraction/src/test/java/org/matsim/contrib/demand_extraction/algorithm/profiling/ReferenceProgressCheckpointWriterTest.java`
- Create: `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/profiling/ReferenceProgressCheckpointWriter.java`

**Step 1: Write the failing test**

Add a test that:
- creates a temporary CSV path,
- writes one running checkpoint row and one terminal OOM row through a new writer API,
- asserts the file exists,
- asserts the header is written once,
- asserts both rows contain stable columns like `run`, `degree`, `status`, `sample_kind`, `sets_processed`, `rides_retained`, `heap_used_gb`.

**Step 2: Run test to verify it fails**

Run: `mvn -Dtest=ReferenceProgressCheckpointWriterTest test`

Expected: FAIL because `ReferenceProgressCheckpointWriter` does not exist yet.

**Step 3: Write minimal implementation**

Implement a tiny append-only writer that:
- opens a buffered writer,
- writes a fixed CSV header once,
- appends rows from primitive/string fields,
- supports `close()`.

Do not wire it into the engine yet.

**Step 4: Run test to verify it passes**

Run: `mvn -Dtest=ReferenceProgressCheckpointWriterTest test`

Expected: PASS.

### Task 2: Expose reusable heap samples from MemoryProfiler

**Files:**
- Modify: `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/profiling/MemoryProfiler.java`
- Test: `contribs/drt-demand-extraction/src/test/java/org/matsim/contrib/demand_extraction/algorithm/profiling/ReferenceProgressCheckpointWriterTest.java`

**Step 1: Write the failing test**

Extend the writer test or add a focused assertion around a new `MemoryProfiler` sample object so checkpoint rows can carry heap fields without parsing logs.

**Step 2: Run test to verify it fails**

Run: `mvn -Dtest=ReferenceProgressCheckpointWriterTest test`

Expected: FAIL because heap sampling is log-only today.

**Step 3: Write minimal implementation**

Add a small immutable sample type and capture methods, for example:
- `captureHeapSample(String stage, boolean runGc)`
- fields for `usedBytes`, `committedBytes`, `maxBytes`, `gcMillis`, and formatted GiB helpers.

Keep existing logging methods by delegating to the new capture method.

**Step 4: Run test to verify it passes**

Run: `mvn -Dtest=ReferenceProgressCheckpointWriterTest test`

Expected: PASS.

### Task 3: Add opt-in progress sink to ReferenceRideExtender

**Files:**
- Modify: `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/exmas/ReferenceRideExtender.java`
- Create: `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/exmas/ReferenceProgressSink.java`
- Create or Modify: `contribs/drt-demand-extraction/src/test/java/org/matsim/contrib/demand_extraction/algorithm/exmas/ReferenceRideExtenderCheckpointPolicyTest.java`

**Step 1: Write the failing test**

Add a focused unit test around a package-private checkpoint policy helper, not the full networked extender. Test that:
- degree `< 5` does not emit partial checkpoints,
- degree `5` does emit at power-of-two milestones or after a configured minimum interval,
- a terminal OOM emission path is available.

**Step 2: Run test to verify it fails**

Run: `mvn -Dtest=ReferenceRideExtenderCheckpointPolicyTest test`

Expected: FAIL because the helper and sink do not exist yet.

**Step 3: Write minimal implementation**

Add an optional sink interface and a tiny checkpoint policy helper. Then wire `ReferenceRideExtender.extendRides()` to emit running checkpoints for degree 5+ using already-available counters:
- `setsProcessed`
- `totalSets`
- `allExtended.size()`
- `stats.candidatesAdded.sum()`
- current elapsed time
- current heap sample from `MemoryProfiler`

Catch `OutOfMemoryError` inside `extendRides()`, emit one terminal OOM checkpoint with the last local counters, then rethrow.

**Step 4: Run test to verify it passes**

Run: `mvn -Dtest=ReferenceRideExtenderCheckpointPolicyTest test`

Expected: PASS.

### Task 4: Thread the sink through ExMasReferenceEngine

**Files:**
- Modify: `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/exmas/ExMasReferenceEngine.java`
- Test: `contribs/drt-demand-extraction/src/test/java/org/matsim/contrib/demand_extraction/algorithm/exmas/ReferenceRideExtenderCheckpointPolicyTest.java`

**Step 1: Write the failing test**

Extend the policy test or add a constructor-level test ensuring the engine can be built with and without a sink.

**Step 2: Run test to verify it fails**

Run: `mvn -Dtest=ReferenceRideExtenderCheckpointPolicyTest test`

Expected: FAIL because the engine constructor does not yet carry the sink.

**Step 3: Write minimal implementation**

Add an overload or optional field so `ExMasReferenceEngine` can pass the sink into each `ReferenceRideExtender` without changing default behavior.

**Step 4: Run test to verify it passes**

Run: `mvn -Dtest=ReferenceRideExtenderCheckpointPolicyTest test`

Expected: PASS.

### Task 5: Enable CSV checkpoint export in the Lyon fast comparison test

**Files:**
- Modify: `contribs/drt-demand-extraction/src/test/java/org/matsim/contrib/demand_extraction/ExMasLyonR1R2FastComparisonTest.java`
- Test: `contribs/drt-demand-extraction/src/test/java/org/matsim/contrib/demand_extraction/algorithm/profiling/ReferenceProgressCheckpointWriterTest.java`

**Step 1: Write the failing test**

Add a small focused test helper assertion, not a full Lyon scenario run, for the property parsing / output-path logic if needed.

**Step 2: Run test to verify it fails**

Run: `mvn -Dtest=ReferenceProgressCheckpointWriterTest,ReferenceRideExtenderCheckpointPolicyTest test`

Expected: FAIL because the harness does not yet create or close the checkpoint writer.

**Step 3: Write minimal implementation**

Add opt-in system properties to the fast comparison harness, for example:
- `-Dr1CheckpointCsv=...`
- `-DallowR1OomForProfiling=true`

Behavior:
- create the writer only for R1,
- pass the sink into `ExMasReferenceEngine`,
- if R1 OOMs and `allowR1OomForProfiling=true`, log it, keep the checkpoint CSV, skip the R1/R2 equality assert, and continue,
- otherwise rethrow as today.

**Step 4: Run focused tests to verify they pass**

Run: `mvn -Dtest=ReferenceProgressCheckpointWriterTest,ReferenceRideExtenderCheckpointPolicyTest,ReferenceRideExtenderCartesianProductTest test`

Expected: PASS.

### Task 6: Run module validation

**Files:**
- Modify: `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/exmas/ExMasReferenceEngine.java`
- Modify: `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/exmas/ReferenceRideExtender.java`
- Modify: `contribs/drt-demand-extraction/src/main/java/org/matsim/contrib/demand_extraction/algorithm/profiling/MemoryProfiler.java`
- Modify: `contribs/drt-demand-extraction/src/test/java/org/matsim/contrib/demand_extraction/ExMasLyonR1R2FastComparisonTest.java`

**Step 1: Run focused tests**

Run: `mvn -Dtest=ReferenceProgressCheckpointWriterTest,ReferenceRideExtenderCheckpointPolicyTest,ReferenceRideExtenderCartesianProductTest test`

Expected: PASS.

**Step 2: Run a narrow compile/test sweep for the touched module**

Run: `mvn -DskipTests compile`

Expected: BUILD SUCCESS.

**Step 3: Smoke-run the fast comparison harness in profiling mode when ready**

Run with the Lyon env vars and an opt-in checkpoint path, initially with a low artificial cap or synthetic failure if available, then on the real uncapped R1 profiling run.

Expected: append-only checkpoint CSV with running rows and a terminal `oom` row when R1 dies mid-degree.