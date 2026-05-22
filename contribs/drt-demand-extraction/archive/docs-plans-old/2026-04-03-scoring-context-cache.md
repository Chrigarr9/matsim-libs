# Scoring Context Cache — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Pre-compute and cache per-request scoring context (person, resolved activities, activity durations, scoring parameters, template objects) so that budget validation during ride extension is cheap arithmetic instead of full plan parsing + object allocation per call.

**Architecture:** Add a `ScoringContext` record to `DrtRequest` that holds all data needed by `DrtTripScorer.score()` that is constant across orderings/sets/degrees. Populate it once per request in `BudgetValidator.precomputeScoringContexts()` called from `ExMasEngine` before ride generation. Add a new `DrtTripScorer.scoreWithContext()` method that uses the cached context. `BudgetValidator.calculateDrtScore()` delegates to the new method when context is available.

**Tech Stack:** Java 17, MATSim scoring framework

---

## What's constant per request (cached)

| Component | Currently computed | Times recomputed |
|-----------|-------------------|------------------|
| `Person` lookup | `population.getPersons().get(personId)` | Every validation call |
| `TripStructureUtils.getTrips(plan)` | Iterates all plan elements | Every call (LOG opp cost) |
| `computeActivityDurations(plan)` | Iterates all plan elements | Every call (LOG opp cost) |
| `originActivity`, `destActivity` | From trips list by tripIndex | Every call |
| `originDuration`, `destDuration` | From durations array | Every call |
| `ScoringParameters` | `scoringParametersForPerson.get(person)` | Every call (wait penalty + opp cost) |
| Access walk `Leg` + `Route` | Object creation | Every call |
| Egress walk `Leg` + `Route` | Object creation | Every call |
| Synthetic origin/dest `Activity` | Object creation | Every call |
| `DrtRoute` template | Object creation (directRideTime, directDistance fixed) | Every call |

## What varies per ordering (not cached)

| Component | Changes with ordering |
|-----------|---------------------|
| `travelTime` | Per-passenger in-vehicle time |
| `distance` | Per-passenger distance |
| `delay` | Wait/early arrival per passenger |

---

## Task 1: Add ScoringContext record to DrtRequest

**Files:**
- Modify: `demand/DrtRequest.java`

**Step 1: Add the ScoringContext record and field**

After the existing field declarations (after `ptAccessibility`, around line 74), add:

```java
/**
 * Pre-computed scoring context for fast budget validation.
 * Populated once per request by BudgetValidator.precomputeScoringContexts().
 * Contains all data that is constant across orderings/sets/degrees.
 * Volatile for thread-safe publication to ForkJoinPool worker threads.
 */
private volatile ScoringContext scoringContext;

/**
 * Cached scoring context holding pre-resolved activities, durations,
 * scoring parameters, and template trip objects for a DRT request.
 * All fields are constant for a given request regardless of ride ordering.
 */
public record ScoringContext(
    Person person,
    Activity originActivity,
    Activity destActivity,
    double originDuration,
    double destDuration,
    ScoringParameters scoringParams,
    // Pre-built template objects (walk legs have fixed distances, DRT route has fixed direct metrics)
    Leg accessWalkLeg,
    Route accessWalkRoute,
    Leg egressWalkLeg,
    Route egressWalkRoute,
    DrtRoute drtRouteTemplate,
    Activity syntheticOriginActivity,
    Activity syntheticDestActivity
) {}

public ScoringContext getScoringContext() { return scoringContext; }
public void setScoringContext(ScoringContext ctx) { this.scoringContext = ctx; }
```

**Step 2: Add required imports** to DrtRequest

```java
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Route;
import org.matsim.contrib.drt.routing.DrtRoute;
import org.matsim.core.scoring.functions.ScoringParameters;
```

**Step 3: Compile**

```bash
cd matsim-libs/contribs/drt-demand-extraction && mvn compile -Denforcer.skip=true -o -q
```

---

## Task 2: Add `precomputeScoringContexts` to BudgetValidator

**Files:**
- Modify: `algorithm/validation/BudgetValidator.java`

**Step 1: Add the precompute method** (after `calculateBudget`, around line 109)

```java
/**
 * Pre-compute and cache scoring context for all requests.
 * Called once before ride generation to avoid repeated plan parsing
 * and object allocation during budget validation.
 *
 * <p>Thread-safe: contexts are published via volatile field on DrtRequest.
 * ForkJoinPool worker threads read the context after this method completes.
 */
public void precomputeScoringContexts(List<DrtRequest> requests) {
    log.info("Pre-computing scoring contexts for {} requests...", requests.size());
    long start = System.currentTimeMillis();

    String drtMode = exMasConfig.getDrtMode();
    double walkDist = exMasConfig.getMinDrtAccessEgressDistance();
    double accessTime = walkDist / walkSpeed;
    double egressTime = walkDist / walkSpeed;
    ExMasConfigGroup.OpportunityCostModel ocModel = exMasConfig.getOpportunityCostModel();

    for (DrtRequest request : requests) {
        Person person = population.getPersons().get(request.personId);
        if (person == null) continue;

        // Resolve activities (expensive plan parsing — done once here)
        Activity originActivity = null;
        Activity destActivity = null;
        double originDuration = 0.0;
        double destDuration = 0.0;

        if (ocModel == ExMasConfigGroup.OpportunityCostModel.LOG) {
            List<org.matsim.core.router.TripStructureUtils.Trip> trips =
                    org.matsim.core.router.TripStructureUtils.getTrips(person.getSelectedPlan());
            if (request.tripIndex >= 0 && request.tripIndex < trips.size()) {
                org.matsim.core.router.TripStructureUtils.Trip trip = trips.get(request.tripIndex);
                originActivity = trip.getOriginActivity();
                destActivity = trip.getDestinationActivity();
                double[] actDurations = org.matsim.contrib.demand_extraction.scoring.OpportunityCostCalculator
                        .computeActivityDurations(person.getSelectedPlan());
                if (request.tripIndex < actDurations.length)
                    originDuration = actDurations[request.tripIndex];
                if (request.tripIndex + 1 < actDurations.length)
                    destDuration = actDurations[request.tripIndex + 1];
            }
        }
        if (originActivity == null) {
            originActivity = org.matsim.core.population.PopulationUtils
                    .createActivityFromLinkId("unknown", request.originLinkId);
        }
        if (destActivity == null) {
            destActivity = org.matsim.core.population.PopulationUtils
                    .createActivityFromLinkId("unknown", request.destinationLinkId);
        }

        // Scoring parameters
        ScoringParameters scoringParams = scoringParametersForPerson.getScoringParameters(person);

        // Pre-build template legs (access walk, egress walk, DRT route)
        Leg accessLeg = org.matsim.core.population.PopulationUtils.createLeg(TransportMode.walk);
        accessLeg.setTravelTime(accessTime);
        Route accessRoute = org.matsim.core.population.routes.RouteUtils
                .createGenericRouteImpl(request.originLinkId, request.originLinkId);
        accessRoute.setDistance(walkDist);
        accessRoute.setTravelTime(accessTime);
        accessLeg.setRoute(accessRoute);

        Leg egressLeg = org.matsim.core.population.PopulationUtils.createLeg(TransportMode.walk);
        egressLeg.setTravelTime(egressTime);
        Route egressRoute = org.matsim.core.population.routes.RouteUtils
                .createGenericRouteImpl(request.destinationLinkId, request.destinationLinkId);
        egressRoute.setDistance(walkDist);
        egressRoute.setTravelTime(egressTime);
        egressLeg.setRoute(egressRoute);

        org.matsim.contrib.drt.routing.DrtRoute drtRouteTemplate =
                new org.matsim.contrib.drt.routing.DrtRoute(
                        request.originLinkId, request.destinationLinkId);
        drtRouteTemplate.setDirectRideTime(request.directTravelTime);
        drtRouteTemplate.setDistance(request.directDistance);

        Activity synOrigAct = org.matsim.core.population.PopulationUtils
                .createActivityFromLinkId("drt_interaction", request.originLinkId);
        synOrigAct.setCoord(new org.matsim.api.core.v01.Coord(request.originX, request.originY));
        synOrigAct.setEndTime(request.requestTime);
        Activity synDestAct = org.matsim.core.population.PopulationUtils
                .createActivityFromLinkId("drt_interaction", request.destinationLinkId);
        synDestAct.setCoord(new org.matsim.api.core.v01.Coord(request.destinationX, request.destinationY));

        request.setScoringContext(new DrtRequest.ScoringContext(
                person, originActivity, destActivity, originDuration, destDuration,
                scoringParams, accessLeg, accessRoute, egressLeg, egressRoute,
                drtRouteTemplate, synOrigAct, synDestAct));
    }

    long elapsed = System.currentTimeMillis() - start;
    log.info("Pre-computed scoring contexts for {} requests in {}ms", requests.size(), elapsed);
}
```

**Step 2: Add required imports** to BudgetValidator

```java
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Route;
```

**Step 3: Compile**

```bash
mvn compile -Denforcer.skip=true -o -q
```

---

## Task 3: Add `scoreWithContext` to DrtTripScorer

**Files:**
- Modify: `scoring/DrtTripScorer.java`

**Step 1: Add new method** (after the existing `score` method, before `scoreWithActivityResolution`)

```java
/**
 * Score a DRT trip using pre-computed scoring context.
 * Avoids plan parsing, activity resolution, object allocation.
 * Only travelTime, distance, and delay vary per call.
 *
 * <p>IMPORTANT: The template Leg objects in the context are MUTATED (departure time,
 * travel time updated). This is safe because processSet runs sequentially per set
 * within a single thread. The Leg objects are not shared across threads — each
 * DrtRequest has its own ScoringContext with its own template objects.
 */
public static double scoreWithContext(
        DrtRequest.ScoringContext ctx,
        DrtRequest request,
        DemandExtractionScoringAdapter adapter,
        String drtMode,
        OpportunityCostModel opportunityCostModel,
        double travelTime,
        double distance,
        double delay,
        double walkSpeed) {

    if (!Double.isFinite(delay) || !Double.isFinite(travelTime) || !Double.isFinite(distance)) {
        return Double.NEGATIVE_INFINITY;
    }

    double accessTime = ctx.accessWalkRoute().getTravelTime().seconds();
    double egressTime = ctx.egressWalkRoute().getTravelTime().seconds();
    double pickupTime = request.requestTime + delay;

    // Update mutable template objects with ordering-specific values
    ctx.accessWalkLeg().setDepartureTime(pickupTime - accessTime);

    Leg drtLeg = PopulationUtils.createLeg(drtMode);
    drtLeg.setDepartureTime(pickupTime);
    drtLeg.setTravelTime(travelTime);
    // Clone the DRT route template and set ordering-specific travel time
    DrtRoute drtRoute = new DrtRoute(request.originLinkId, request.destinationLinkId);
    drtRoute.setDirectRideTime(request.directTravelTime);
    drtRoute.setDistance(request.directDistance);
    drtRoute.setTravelTime(travelTime);
    drtLeg.setRoute(drtRoute);

    ctx.egressWalkLeg().setDepartureTime(pickupTime + travelTime);

    // Build trip elements using pre-built legs
    List<Leg> elements = List.of(ctx.accessWalkLeg(), drtLeg, ctx.egressWalkLeg());

    // Score via adapter (using pre-built synthetic activities)
    TripScoreRequest scoreRequest = new TripScoreRequest(
            ctx.person(), drtMode, elements,
            ctx.syntheticOriginActivity(), ctx.syntheticDestActivity(),
            request.requestTime, null, request.tripIndex);

    TripScoreResult result = adapter.scoreTrip(scoreRequest);
    double score = result.utility();

    // Wait time penalty
    if (!result.waitingDisutilityIncluded()) {
        double marginalUtilityOfWaitingPt_s = ctx.scoringParams().marginalUtilityOfWaitingPt_s;
        double detour = travelTime - request.directTravelTime;
        double waitTime = 0.0;
        if (delay > 0) {
            waitTime = delay;
        } else if (delay < 0) {
            waitTime = Math.max(0.0, Math.abs(delay) - detour);
        }
        score += marginalUtilityOfWaitingPt_s * waitTime;
    }

    // Opportunity cost
    if (opportunityCostModel != OpportunityCostModel.NONE && !adapter.includesOpportunityCost()) {
        double totalTravelTime = accessTime + travelTime + egressTime;
        score -= OpportunityCostCalculator.compute(opportunityCostModel, ctx.scoringParams(),
                totalTravelTime, ctx.originActivity(), ctx.destActivity(),
                ctx.originDuration(), ctx.destDuration());
    }

    return score;
}
```

**Step 2: Compile**

```bash
mvn compile -Denforcer.skip=true -o -q
```

---

## Task 4: Wire BudgetValidator to use cached context

**Files:**
- Modify: `algorithm/validation/BudgetValidator.java`

**Step 1: Update `calculateDrtScore` to use context when available** (replace lines 228-237)

Replace the existing `calculateDrtScore` method:

```java
private double calculateDrtScore(DrtRequest request, double delay,
        double actualTravelTime, double actualDistance,
        double actualWalkDistanceAccess, double actualWalkDistanceEgress) {

    // Use pre-computed scoring context if available (fast path)
    DrtRequest.ScoringContext ctx = request.getScoringContext();
    if (ctx != null) {
        return DrtTripScorer.scoreWithContext(ctx, request, adapter,
                exMasConfig.getDrtMode(), exMasConfig.getOpportunityCostModel(),
                actualTravelTime, actualDistance, delay, walkSpeed);
    }

    // Fallback: full scoring (for single rides generated before precompute)
    Person person = population.getPersons().get(request.personId);
    return DrtTripScorer.scoreWithActivityResolution(person, request, adapter,
            scoringParametersForPerson, exMasConfig.getDrtMode(),
            exMasConfig.getOpportunityCostModel(),
            actualTravelTime, actualDistance,
            actualWalkDistanceAccess, actualWalkDistanceEgress, delay, walkSpeed);
}
```

Note: The fallback path is needed because `SingleRideGenerator` and `PairGenerator` call `BudgetValidator` BEFORE `precomputeScoringContexts` runs. Only the extension phase benefits from caching. But single/pair generation is already fast — the caching matters for degree 3+.

**Step 2: Compile**

```bash
mvn compile -Denforcer.skip=true -o -q
```

---

## Task 5: Call precompute from ExMasEngine

**Files:**
- Modify: `algorithm/engine/ExMasEngine.java`

**Step 1: Add precompute call** before Phase 4 (the extension loop)

Find the line `log.info("PHASE 4: Iterative Ride Extension");` (around line 141) and add before it:

```java
		// Pre-compute scoring contexts for all requests (avoids plan parsing during extension)
		budgetValidator.precomputeScoringContexts(drtRequests);
```

**Step 2: Compile + run E2E tests**

```bash
mvn compile -Denforcer.skip=true -o -q
mvn test -Dtest=ExMasDemandExtractionE2ETest -Denforcer.skip=true -o
mvn test -Dtest=ExMasKelheimHyperPoolE2ETest -Denforcer.skip=true -o
```

Both should PASS with identical ride counts.

---

## Task 6: Run 1% Bavaria validation

```bash
mvn exec:java -o \
  -Dexec.mainClass=org.matsim.contrib.demand_extraction.run.RunBavaria30kmDemandExtraction \
  -Dexec.args="--scenario-path ../../../matsim_scenarios/bavaria/output/kelheim_30km_100pct \
    --population ../../../matsim_scenarios/bavaria/output/populations/population_1pct_kelheim30km.xml.gz \
    --sample 100 --iterations 0 --trip-filter-radius 30 \
    --filter-municipality Kelheim \
    --shapes ../../../matsim_scenarios/bavaria/data/germany/tmp_vg250/vg250-ew_12-31.utm32s.gpkg.ebenen/vg250-ew_ebenen_1231/DE_VG250.gpkg \
    --travel-times ../../../matsim_scenarios/bavaria/output/base-simulation-10pct/travel_times.tsv \
    --output-dir ../../../matsim_scenarios/bavaria/output/demand-extraction-1pct-scoring-cache \
    --no-predecessors --inter-degree-keep 1.0" \
  -Denforcer.skip=true
```

Verify: 12,552 rides (exact match). Check precompute log: "Pre-computed scoring contexts for 2101 requests in Xms".

---

## Task 7: Run 10% Bavaria with inter-degree pruning

```bash
mvn exec:java -o \
  -Dexec.mainClass=org.matsim.contrib.demand_extraction.run.RunBavaria30kmDemandExtraction \
  -Dexec.args="--scenario-path ../../../matsim_scenarios/bavaria/output/kelheim_30km_100pct \
    --population ../../../matsim_scenarios/bavaria/output/populations/population_10pct_kelheim30km.xml.gz \
    --sample 100 --iterations 0 --trip-filter-radius 30 \
    --filter-municipality Kelheim \
    --shapes ../../../matsim_scenarios/bavaria/data/germany/tmp_vg250/vg250-ew_12-31.utm32s.gpkg.ebenen/vg250-ew_ebenen_1231/DE_VG250.gpkg \
    --travel-times ../../../matsim_scenarios/bavaria/output/base-simulation-10pct/travel_times.tsv \
    --output-dir ../../../matsim_scenarios/bavaria/output/demand-extraction-10pct-scoring-cache \
    --no-predecessors" \
  -Denforcer.skip=true
```

Compare timing per degree against the no-cache inline-eval run.
Expected: major speedup at degree 5+ where 92+ budget validations per set were the bottleneck.

---

## Expected Impact

| Degree | Validations/set | Before (ms/validation) | After (ms/validation) | Set speedup |
|--------|----------------|----------------------|---------------------|-------------|
| 3 | 1.3 | ~1ms | ~0.05ms | ~1.3x |
| 4 | 3.6 | ~1ms | ~0.05ms | ~2x |
| 5 | 23 | ~1ms | ~0.05ms | ~10x |
| 6 | 92 | ~1ms | ~0.05ms | ~20x |

The per-validation cost drops from ~1ms (plan parsing + object allocation) to ~0.05ms (adapter.scoreTrip with pre-built objects). The adapter call itself is irreducible — it's the MATSim scoring framework doing its work. But we eliminate all the constant overhead around it.

Combined with the inline eval + tighten-on-valid approach, degree 6 at 10% should go from ~15h to well under 1h.
