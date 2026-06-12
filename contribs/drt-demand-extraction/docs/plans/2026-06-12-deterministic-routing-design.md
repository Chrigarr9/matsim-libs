# Deterministic Routing by Construction — Design

**Date:** 2026-06-12
**Status:** Approved design, pending implementation plan
**Goal:** All routing in DRT demand extraction is deterministic across algorithms
(LeastCostPathTree/Dijkstra vs SpeedyALT/A*), router instances, thread counts, and
JVM invocations — without a flag, and without the performance penalties the
`useDeterministicNetworkRouting` flag carries today. Net-negative diff.

## Problem

Two routing engines fill the same `MatsimNetworkCache`:

- `batchPrecompute` → `LeastCostPathTree` (Dijkstra SSSP)
- `getSegment` cache miss → `SpeedyALT` (A* with landmarks)

With the default time-only disutility (`OnlyTimeDependentTravelDisutility`, bound in
the Lyon/Bavaria fixtures), many OD pairs have multiple equal-cost paths. Dijkstra
and A* tie-break differently; even two SpeedyALT instances disagree across JVMs
because ALT landmark selection depends on `Id.index()` assignment order. Different
distances on equal-time paths flip borderline pair feasibility (+3.1 % pairs,
documented in `archive/docs-plans-old/2026-04-22-sssp-benchmark-findings.md`).

The existing `useDeterministicNetworkRouting` flag fixes this by switching to
`TimeDistanceTravelDisutility` (time + 1e-9·distance), but:

1. It also forces `ModeRoutingCache.cacheModes` to route the whole population
   **sequentially** (`ModeRoutingCache.java:135`) — the dominant performance hit.
2. It drops toll/monetary terms entirely (time-only base), which we want to keep
   available for future toll scenarios.
3. It left dead code behind: `sharedRouter`, `useSharedDeterministicRouter`,
   `routerLock` are written but never read (the locked-shared-router bottleneck was
   removed 2026-05-27; the log message in `RunLyonEqasimDemandExtraction.java:535`
   is stale).
4. Its ε=1e-9 is unverified — `RoutingDeterminismTest` proves Dijkstra ≡ SpeedyALT
   at ε=1e-4 only, and only under `FreeSpeedTravelTime`.

### Historical note: the "70x slowdown" was not mode-specific disutility

`eqasim-pair-gen-regression` (2026-04-17): eqasim's adapter sets all MATSim mode
params to 0, so the default `RandomizingTimeDistanceTravelDisutilityFactory`
returned **0.0 on every link**. Consequences: A* heuristic = 0 (degenerate
exhaustive search, the 70x) and *every* path an equal-cost tie (maximal
nondeterminism). Smoking gun: `WARN ... travel cost should be > 0. Currently, it
is 0.0`. Binding `OnlyTimeDependentTravelDisutility` was a workaround for the
zero-gradient *input*, not evidence that toll-aware routing is slow. A mode
disutility with a real positive gradient routes fast and deterministically.

## Core principle

> **Determinism = uniqueness of the optimum + admissibility of the heuristic.**

1. **Uniqueness.** If no two distinct paths share the same cost, every optimal
   algorithm must return the identical path. A strictly positive distance
   tie-breaker (`+ ε·length`) makes the optimum unique.
2. **Admissibility.** A* is provably optimal only if its heuristic never
   overestimates remaining cost. SpeedyALT's landmark heuristic is built from
   `getLinkMinimumTravelDisutility` = freespeed time. Offline travel times
   (`travel_times.tsv` via `DvrpOfflineTravelTimes`) are loaded **without a clamp**
   — a single link/bin faster than free flow makes the heuristic inadmissible and
   SpeedyALT can return a genuinely suboptimal path that Dijkstra would not.

Both holds → Dijkstra-tree ≡ SpeedyALT ≡ any instance ≡ any thread count ≡ any JVM,
for any base disutility, including future toll-aware ones (provided the toll
disutility reports a valid `getLinkMinimumTravelDisutility`, the standard MATSim
contract).

## Components

### 1. `DeterministicTravelDisutility` (new; replaces `TimeDistanceTravelDisutility` and the flag)

Decorator around any base `TravelDisutility`:

```
cost(link, t) = base(link, t) + ε·length(link)
min(link)     = baseMin(link) + ε·length(link)
```

Construction scans the network once for the base's minimum cost-per-meter
(`baseMin(link) / length(link)` over all links):

- **Degenerate base (min gradient ≈ 0 network-wide — the eqasim trap):** substitute
  travel time as the gradient (`cost = travelTime + ε·length`) and log a loud WARN
  naming the base class. The 70x trap and the all-ties trap become structurally
  impossible. "When there are no real costs, time decides."
- **ε auto-scaled:** `ε = 1e-6 × minCostPerMeter` of the effective gradient.
  Unit-independent (works for seconds or utils), no config knob.
  - *Pure tie-breaker:* with a time base (~0.028 s/m at 130 km/h), ε ≈ 2.8e-8/m.
    Overriding a real cost difference of 0.001 s would need a ~36 km shorter path.
  - *Above FP noise:* path costs ~1e3 with double precision carry ~1e-9 absolute
    noise; a 1 m distance difference contributes ~2.8e-8.
- **Idempotent wrap:** wrapping an already-wrapped instance returns it unchanged,
  so scenario wiring and `MatsimNetworkCache` can both wrap defensively without
  double-ε.

Admissibility of the decorated minimum follows from the base's
(`baseMin ≤ base(t) ∀t` ⇒ `baseMin + ε·len ≤ base(t) + ε·len`).

### 2. Offline travel-time loading: dedupe + freespeed clamp

`loadOfflineTravelTimes` is copy-pasted in four places:
`LyonEqasimScenarioFixture`, `Phase2Module`, `Phase2RoutingSetup`,
`RunBavariaEqasimDemandExtraction`. Replace with one utility (e.g.
`algorithm/network/OfflineTravelTimes.load(path, endTime, binSize)`) whose
returned `TravelTime` clamps:

```
tt(link, t) = max(matrix(link, bin(t)), length(link) / freespeed(link))
```

Physically sensible (nothing drives faster than free flow) and guarantees the
freespeed-based ALT heuristic is admissible. The 36 h time clamp stays.

### 3. Wiring

- **`MatsimNetworkCache`:** single code path. Always wrap the injected
  mode-specific `TravelDisutility` in `DeterministicTravelDisutility`; always build
  thread-local SpeedyALT routers **and** the `LeastCostPathTree` from that same
  wrapped instance. The injected `routerProvider` (named `direct<Mode>Router`) is
  no longer used for cache misses — it was created from the unwrapped disutility.
- **Fixtures/runners:** keep their base bindings (`OnlyTimeDependentTravelDisutility`
  today; a toll-aware factory tomorrow). Optionally bind the wrapper at module level
  so TripRouter-based budget/mode-choice routing shares the exact cost function;
  the idempotent wrap keeps this safe either way.

### 4. Deletions (the simplification)

| Item | Where |
|---|---|
| `useDeterministicNetworkRouting` option + getter/setter/comments | `ExMasConfigGroup` |
| `--deterministic-routing` CLI flags + stale log lines | `RunLyonEqasimDemandExtraction`, `RunKelheimDemandExtraction`, `RunBavaria30kmDemandExtraction`, `RunDemandExtractionPhase1` |
| Flag logging | `DemandExtractionConfigValidator` |
| Dead fields: `sharedRouter`, `useSharedDeterministicRouter`, `routerLock`, `routerProvider` (unused once cache misses always use own SpeedyALT) | `MatsimNetworkCache` |
| Disutility-selection branches in injected constructor | `MatsimNetworkCache` |
| 3 test constructors → 1 mirroring production (SpeedyALT + tree from same wrapped disutility) | `MatsimNetworkCache`, `MatsimNetworkCacheTestFixture` |
| Sequential-stream guard (always parallel) | `ModeRoutingCache.cacheModes` |
| `TimeDistanceTravelDisutility` (subsumed by the decorator's fallback mode) | `algorithm/network` |
| 3 of 4 `loadOfflineTravelTimes` copies | see Component 2 |

The collapsed test constructor also closes the 2026-04-22 "why the unit test
doesn't catch it" gap: tests routed cache misses with Dijkstra while production
used SpeedyALT, hiding tie-breaking differences.

## Validation

1. **`RoutingDeterminismTest` extended** (the critical new coverage):
   - production decorator with auto-ε instead of hardcoded 1e-4;
   - offline TSV travel times (`LYON_TRAVEL_TIMES_TSV`) instead of only
     `FreeSpeedTravelTime` — exercises the admissibility clamp in the regime that
     was never tested;
   - existing assertions stay: Dijkstra ≡ SpeedyALT byte-for-byte on cost/time/
     distance over 2000 OD pairs; parallel ≡ sequential.
2. **Unit tests:** ε auto-scaling, zero-gradient fallback (WARN + time gradient),
   idempotent wrap, freespeed clamp in `OfflineTravelTimes`.
3. **E2E determinism gate:** run Kelheim extraction twice in separate JVMs → byte-equal
   `drt_requests.csv` + `exmas_rides.csv` (hash compare). This also certifies the
   re-parallelized `ModeRoutingCache`; if it ever fails, that exposes a shared-state
   bug to fix, not to serialize around.
4. **Lyon 1 % R1/R2 fast comparison** must still pass at every degree.
5. **Checkpoint/resume (Plan A3)** benefits: the never-journaled "backstop" segment
   class is reproduced point-to-point on resume; cross-JVM SpeedyALT equality is now
   guaranteed instead of empirical.

## Known consequences

- **Results change once.** Default routing cost moves from the unwrapped mode
  disutility (with nondeterministic tie-breaking) to the wrapped one. Kelheim
  goldens (`ExMasReferencePortRegressionTest`, via
  `scripts/regenerate_exmas_reference_golden.sh`) need regeneration; ride/pair
  counts in prior baselines shift within tie noise. After this, results are
  canonical and reproducible indefinitely.
- **Tolls:** supported and deterministic by the same mechanism. Two requirements on
  a future toll disutility: valid `getLinkMinimumTravelDisutility` (lower bound
  across all times) and non-degenerate gradient (otherwise the fallback triggers
  and ignores it, loudly).
- **Tie pathologies:** two distinct paths with identical base cost *and* identical
  length remain tied after ε. Measure-zero in real networks; accepted.

## Out of scope

- PT routing determinism (SwissRailRaptor) — separate engine, not part of the
  network cache.
- Quantization of cached segment values — deliberately avoided; non-additive
  rounding can make split routes appear shorter than direct ones at feasibility
  boundaries (see `MatsimNetworkCache` test-constructor javadoc).
