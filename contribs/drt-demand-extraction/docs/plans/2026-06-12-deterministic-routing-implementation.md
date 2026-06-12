# Deterministic Routing by Construction — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make all routing in DRT demand extraction deterministic across algorithms (LeastCostPathTree vs SpeedyALT), router instances, thread counts, and JVMs — delete the `useDeterministicNetworkRouting` flag and its dead code.

**Architecture:** A `DeterministicTravelDisutility` decorator adds an auto-scaled `ε·length` tie-breaker to any base disutility (unique optimum), and a unified offline travel-time loader clamps to freespeed (admissible A* heuristic). `MatsimNetworkCache` collapses to a single code path that wraps the injected mode disutility and feeds both engines from the same wrapped instance. Spec: `docs/plans/2026-06-12-deterministic-routing-design.md` (same directory).

**Tech Stack:** Java 25, Maven, JUnit 5, MATSim core (SpeedyALT, LeastCostPathTree), Guice.

**Build conventions (every task):**

```bash
# All commands run from the contrib root:
cd /c/Users/VWAUCCY/dev/msf/projects/Dissertation/matsim-libs/contribs/drt-demand-extraction
export JAVA_HOME="C:/Users/VWAUCCY/dev/msf/.jdk/jdk-25.0.2+10"
MVN="/c/Users/VWAUCCY/dev/msf/.maven/maven/bin/mvn"
# Single test class:   $MVN test -Dtest=ClassName
# Default suite:       $MVN test          (excludes groups scenario-lyon,regression)
```

All `src/...` paths below are relative to the contrib root above. Commit in the `matsim-libs` submodule (branch `feature/paper2-bamas-integrated`).

**Known consequence (accepted in the design):** routing results change once — Kelheim goldens regenerate in Task 10; Lyon ride/pair counts shift within tie noise. Do not "fix" tests by reverting routing; regenerate expectations.

---

### Task 1: `DeterministicTravelDisutility` decorator

**Files:**
- Create: `src/main/java/org/matsim/contrib/demand_extraction/algorithm/network/DeterministicTravelDisutility.java`
- Create: `src/test/java/org/matsim/contrib/demand_extraction/algorithm/network/DeterministicTravelDisutilityTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package org.matsim.contrib.demand_extraction.algorithm.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;

class DeterministicTravelDisutilityTest {

	/** Two links: "slow" 1000 m @ 10 m/s (gradient 0.1 s/m), "fast" 1000 m @ 25 m/s (0.04 s/m). */
	private static Network twoLinkNetwork() {
		Network network = NetworkUtils.createNetwork();
		Node n0 = NetworkUtils.createAndAddNode(network, Id.createNodeId("n0"), new Coord(0, 0));
		Node n1 = NetworkUtils.createAndAddNode(network, Id.createNodeId("n1"), new Coord(1000, 0));
		Node n2 = NetworkUtils.createAndAddNode(network, Id.createNodeId("n2"), new Coord(2000, 0));
		NetworkUtils.createAndAddLink(network, Id.createLinkId("slow"), n0, n1, 1000.0, 10.0, 1000.0, 1.0);
		NetworkUtils.createAndAddLink(network, Id.createLinkId("fast"), n1, n2, 1000.0, 25.0, 1000.0, 1.0);
		return network;
	}

	/** The eqasim trap: a base disutility with zero gradient on every link. */
	private static final TravelDisutility ZERO_BASE = new TravelDisutility() {
		@Override
		public double getLinkTravelDisutility(Link link, double time,
				org.matsim.api.core.v01.population.Person person, org.matsim.vehicles.Vehicle vehicle) {
			return 0.0;
		}
		@Override
		public double getLinkMinimumTravelDisutility(Link link) {
			return 0.0;
		}
	};

	@Test
	void epsilonAutoScalesToMinCostPerMeterOfBase() {
		Network network = twoLinkNetwork();
		TravelTime tt = new FreeSpeedTravelTime();
		DeterministicTravelDisutility wrapped = DeterministicTravelDisutility.wrap(
				new OnlyTimeDependentTravelDisutility(tt), tt, network);

		// min cost-per-meter = fast link: (1000/25)/1000 = 0.04 s/m -> eps = 1e-6 * 0.04
		assertEquals(1e-6 * 0.04, wrapped.getEpsilon(), 1e-20);
		assertFalse(wrapped.isDegenerateBaseFallback());
	}

	@Test
	void costIsBasePlusEpsilonTimesLength() {
		Network network = twoLinkNetwork();
		TravelTime tt = new FreeSpeedTravelTime();
		DeterministicTravelDisutility wrapped = DeterministicTravelDisutility.wrap(
				new OnlyTimeDependentTravelDisutility(tt), tt, network);
		Link slow = network.getLinks().get(Id.createLinkId("slow"));

		double expected = 100.0 /* 1000m / 10m/s */ + wrapped.getEpsilon() * 1000.0;
		assertEquals(expected, wrapped.getLinkTravelDisutility(slow, 0.0, null, null), 1e-15);
		assertEquals(expected, wrapped.getLinkMinimumTravelDisutility(slow), 1e-15);
	}

	@Test
	void minimumStaysAdmissibleUnderCongestedTravelTime() {
		Network network = twoLinkNetwork();
		// Congested: 2x freespeed time -> actual cost is always >= the freespeed-based minimum.
		TravelTime congested = (link, time, person, vehicle) -> 2.0 * link.getLength() / link.getFreespeed();
		DeterministicTravelDisutility wrapped = DeterministicTravelDisutility.wrap(
				new OnlyTimeDependentTravelDisutility(congested), congested, network);

		for (Link link : network.getLinks().values()) {
			assertTrue(wrapped.getLinkMinimumTravelDisutility(link)
							<= wrapped.getLinkTravelDisutility(link, 8 * 3600.0, null, null) + 1e-15,
					"minimum must never exceed actual cost on " + link.getId());
		}
	}

	@Test
	void zeroGradientBaseFallsBackToTravelTime() {
		Network network = twoLinkNetwork();
		TravelTime tt = new FreeSpeedTravelTime();
		DeterministicTravelDisutility wrapped = DeterministicTravelDisutility.wrap(ZERO_BASE, tt, network);

		assertTrue(wrapped.isDegenerateBaseFallback());
		// Time-gradient eps: min over links of 1/freespeed = 1/25 = 0.04 s/m -> eps = 1e-6 * 0.04
		assertEquals(1e-6 * 0.04, wrapped.getEpsilon(), 1e-20);
		Link slow = network.getLinks().get(Id.createLinkId("slow"));
		// cost = travelTime + eps*length, the base's 0.0 is ignored
		assertEquals(100.0 + wrapped.getEpsilon() * 1000.0,
				wrapped.getLinkTravelDisutility(slow, 0.0, null, null), 1e-15);
		assertEquals(100.0 + wrapped.getEpsilon() * 1000.0,
				wrapped.getLinkMinimumTravelDisutility(slow), 1e-15);
	}

	@Test
	void wrapIsIdempotent() {
		Network network = twoLinkNetwork();
		TravelTime tt = new FreeSpeedTravelTime();
		DeterministicTravelDisutility once = DeterministicTravelDisutility.wrap(
				new OnlyTimeDependentTravelDisutility(tt), tt, network);
		assertSame(once, DeterministicTravelDisutility.wrap(once, tt, network));
	}
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `$MVN test -Dtest=DeterministicTravelDisutilityTest`
Expected: COMPILE ERROR — `DeterministicTravelDisutility` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package org.matsim.contrib.demand_extraction.algorithm.network;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.vehicles.Vehicle;

/**
 * Tie-breaking decorator that makes least-cost paths unique for ANY base disutility:
 *
 * <pre>
 *   cost(link, t) = base(link, t)  + eps * length(link)
 *   min(link)     = baseMin(link)  + eps * length(link)
 * </pre>
 *
 * <p><b>Why:</b> two routing engines fill the same {@code MatsimNetworkCache}
 * (LeastCostPathTree SSSP in {@code batchPrecompute}, SpeedyALT point-to-point on cache
 * miss). With a time-only base, many OD pairs have multiple equal-cost paths; the engines
 * tie-break differently and run-to-run thread scheduling decides which engine populates a
 * key first. A strictly positive distance term makes the optimum unique, so every optimal
 * algorithm, instance, thread count, and JVM returns the identical path.
 *
 * <p><b>eps auto-scaling:</b> {@code eps = 1e-6 x} the smallest positive cost-per-meter of
 * the effective gradient over all network links. Small enough to never override a real cost
 * difference, large enough to sit above double-precision noise on realistic path costs.
 * Unit-independent (works for seconds or utils); no config knob.
 *
 * <p><b>Degenerate-base fallback:</b> if the base has (near-)zero gradient on every link —
 * the eqasim trap where all MATSim mode params are 0 and the default randomizing factory
 * returns 0.0 cost everywhere, degenerating A* to exhaustive search (the historical "70x
 * slowdown") and making every path a tie — the base is ignored and travel time becomes the
 * gradient: {@code cost = travelTime + eps*length}. A loud WARN names the base class.
 * "When there are no real costs, time decides."
 *
 * <p><b>Admissibility:</b> {@code baseMin <= base(t)} for all t implies
 * {@code baseMin + eps*len <= base(t) + eps*len}, so wrapping preserves the base's
 * heuristic admissibility contract. (Offline travel times must additionally be clamped to
 * freespeed — see {@link OfflineTravelTimes}.)
 */
public final class DeterministicTravelDisutility implements TravelDisutility {

	private static final Logger log = LogManager.getLogger(DeterministicTravelDisutility.class);

	/** Relative scale of the distance tie-breaker vs the smallest real cost gradient. */
	static final double EPSILON_FACTOR = 1e-6;
	/** At or below this cost-per-meter a link contributes no usable gradient. */
	static final double DEGENERATE_GRADIENT_THRESHOLD = 1e-12;

	private final TravelDisutility base; // null when degenerateBaseFallback
	private final TravelTime travelTime;
	private final double epsilon;
	private final boolean degenerateBaseFallback;

	private DeterministicTravelDisutility(TravelDisutility base, TravelTime travelTime,
			double epsilon, boolean degenerateBaseFallback) {
		this.base = base;
		this.travelTime = travelTime;
		this.epsilon = epsilon;
		this.degenerateBaseFallback = degenerateBaseFallback;
	}

	/**
	 * Wraps {@code base} with the distance tie-breaker. Idempotent: wrapping an
	 * already-wrapped instance returns it unchanged (no double-eps), so scenario wiring
	 * and {@code MatsimNetworkCache} can both wrap defensively.
	 */
	public static DeterministicTravelDisutility wrap(TravelDisutility base, TravelTime travelTime,
			Network network) {
		if (base instanceof DeterministicTravelDisutility already) {
			return already;
		}

		double maxGradient = 0.0;
		double minPositiveGradient = Double.POSITIVE_INFINITY;
		for (Link link : network.getLinks().values()) {
			double length = link.getLength();
			if (length <= 0.0) {
				continue;
			}
			double gradient = base.getLinkMinimumTravelDisutility(link) / length;
			if (!Double.isFinite(gradient) || gradient < 0.0) {
				continue;
			}
			if (gradient > maxGradient) {
				maxGradient = gradient;
			}
			if (gradient > DEGENERATE_GRADIENT_THRESHOLD && gradient < minPositiveGradient) {
				minPositiveGradient = gradient;
			}
		}

		boolean degenerate = maxGradient <= DEGENERATE_GRADIENT_THRESHOLD;
		if (degenerate) {
			log.warn("Base TravelDisutility {} has zero cost gradient on every network link "
					+ "(the 'routing cost is 0.0 everywhere' trap: degenerate A* + maximal "
					+ "tie-breaking nondeterminism). Falling back to travel time as the routing "
					+ "gradient: cost = travelTime + eps*length.", base.getClass().getName());
			minPositiveGradient = Double.POSITIVE_INFINITY;
			for (Link link : network.getLinks().values()) {
				if (link.getLength() <= 0.0 || link.getFreespeed() <= 0.0) {
					continue;
				}
				double gradient = 1.0 / link.getFreespeed(); // min seconds per meter
				if (gradient < minPositiveGradient) {
					minPositiveGradient = gradient;
				}
			}
		}
		if (!Double.isFinite(minPositiveGradient)) {
			throw new IllegalArgumentException(
					"Cannot derive epsilon: no network link has a positive cost gradient");
		}

		double epsilon = EPSILON_FACTOR * minPositiveGradient;
		log.info("DeterministicTravelDisutility: base={}, degenerateFallback={}, "
						+ "minCostPerMeter={}, epsilon={} per meter",
				base.getClass().getSimpleName(), degenerate, minPositiveGradient, epsilon);
		return new DeterministicTravelDisutility(degenerate ? null : base, travelTime, epsilon, degenerate);
	}

	@Override
	public double getLinkTravelDisutility(Link link, double time, Person person, Vehicle vehicle) {
		double baseCost = degenerateBaseFallback
				? travelTime.getLinkTravelTime(link, time, person, vehicle)
				: base.getLinkTravelDisutility(link, time, person, vehicle);
		return baseCost + epsilon * link.getLength();
	}

	@Override
	public double getLinkMinimumTravelDisutility(Link link) {
		double baseMin = degenerateBaseFallback
				? link.getLength() / link.getFreespeed()
				: base.getLinkMinimumTravelDisutility(link);
		return baseMin + epsilon * link.getLength();
	}

	double getEpsilon() {
		return epsilon;
	}

	boolean isDegenerateBaseFallback() {
		return degenerateBaseFallback;
	}
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `$MVN test -Dtest=DeterministicTravelDisutilityTest`
Expected: 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/matsim/contrib/demand_extraction/algorithm/network/DeterministicTravelDisutility.java \
        src/test/java/org/matsim/contrib/demand_extraction/algorithm/network/DeterministicTravelDisutilityTest.java
git commit -m "feat(routing): DeterministicTravelDisutility — auto-eps distance tie-breaker decorator"
```

---

### Task 2: `OfflineTravelTimes` — unified loader with freespeed clamp

**Files:**
- Create: `src/main/java/org/matsim/contrib/demand_extraction/algorithm/network/OfflineTravelTimes.java`
- Create: `src/test/java/org/matsim/contrib/demand_extraction/algorithm/network/OfflineTravelTimesTest.java`
- Modify: `src/main/java/org/matsim/contrib/demand_extraction/scenarios/LyonEqasimScenarioFixture.java` (loader at 378-391, call at 356)
- Modify: `src/main/java/org/matsim/contrib/demand_extraction/run/Phase2Module.java` (loader at 210-224, call at 117, constants 64-67)
- Modify: `src/main/java/org/matsim/contrib/demand_extraction/run/Phase2RoutingSetup.java` (loader at 69-79, call at 59)
- Modify: `src/main/java/org/matsim/contrib/demand_extraction/run/RunBavariaEqasimDemandExtraction.java` (loader at 305-318, call at 229, constants 81-82)

- [ ] **Step 1: Write the failing test**

The TSV format is what `DvrpOfflineTravelTimes.saveLinkTravelTimes` writes: header `linkId<TAB>0.0<TAB>900.0...`, then one row per link with one value per 900 s bin up to 36 h (144 bins).

```java
package org.matsim.contrib.demand_extraction.algorithm.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.router.util.TravelTime;

class OfflineTravelTimesTest {

	@TempDir
	Path tempDir;

	@Test
	void clampsTravelTimesToFreespeedAndEndTime() throws Exception {
		// Link "a": 1000 m @ 10 m/s -> freespeed time 100 s.
		Network network = NetworkUtils.createNetwork();
		Node n0 = NetworkUtils.createAndAddNode(network, Id.createNodeId("n0"), new Coord(0, 0));
		Node n1 = NetworkUtils.createAndAddNode(network, Id.createNodeId("n1"), new Coord(1000, 0));
		NetworkUtils.createAndAddLink(network, Id.createLinkId("a"), n0, n1, 1000.0, 10.0, 1000.0, 1.0);
		Link a = network.getLinks().get(Id.createLinkId("a"));

		int bins = OfflineTravelTimes.TRAVEL_TIME_END / OfflineTravelTimes.TRAVEL_TIME_BIN_SIZE; // 144
		StringBuilder sb = new StringBuilder("linkId");
		for (int i = 0; i < bins; i++) {
			sb.append('\t').append((double) (i * OfflineTravelTimes.TRAVEL_TIME_BIN_SIZE));
		}
		sb.append('\n').append("a");
		for (int i = 0; i < bins; i++) {
			// First bin BELOW freespeed time (inadmissible raw value), rest above.
			sb.append('\t').append(i == 0 ? 60.0 : 150.0);
		}
		sb.append('\n');
		Path tsv = tempDir.resolve("travel_times.tsv");
		Files.writeString(tsv, sb.toString());

		TravelTime tt = OfflineTravelTimes.load(tsv.toString());

		// Bin 0: raw 60 s is faster than free flow -> clamped UP to 100 s (heuristic admissibility).
		assertEquals(100.0, tt.getLinkTravelTime(a, 10.0, null, null), 1e-12);
		// Bin 1: raw 150 s is slower than free flow -> kept.
		assertEquals(150.0, tt.getLinkTravelTime(a, 1000.0, null, null), 1e-12);
		// Past 36 h: time clamped to TRAVEL_TIME_END (last bin value 150 s).
		assertEquals(150.0, tt.getLinkTravelTime(a, 40 * 3600.0, null, null), 1e-12);
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `$MVN test -Dtest=OfflineTravelTimesTest`
Expected: COMPILE ERROR — `OfflineTravelTimes` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package org.matsim.contrib.demand_extraction.algorithm.network;

import java.net.URL;
import java.nio.file.Path;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.contrib.common.timeprofile.TimeDiscretizer;
import org.matsim.contrib.dvrp.trafficmonitoring.DvrpOfflineTravelTimes;
import org.matsim.core.router.util.TravelTime;

/**
 * Single loader for offline link travel times ({@code travel_times.tsv} written by
 * {@code TravelTimeExportListener} / {@link DvrpOfflineTravelTimes}). Replaces four
 * verbatim copies (Lyon fixture, Phase2Module, Phase2RoutingSetup, Bavaria runner) so
 * single-process, two-phase, and route-export runs route on byte-identical travel times.
 *
 * <p>Two clamps:
 * <ul>
 *   <li><b>Time:</b> queries past {@link #TRAVEL_TIME_END} read the last bin (legacy
 *       behavior, prevents out-of-range bin lookups).</li>
 *   <li><b>Freespeed:</b> {@code tt >= length/freespeed}. Physically sensible (nothing
 *       drives faster than free flow) and REQUIRED for determinism: SpeedyALT's landmark
 *       heuristic is built from {@code getLinkMinimumTravelDisutility} = freespeed time.
 *       A single TSV value below freespeed time makes the heuristic inadmissible, and an
 *       inadmissible A* can return a genuinely suboptimal path that LeastCostPathTree
 *       (exact Dijkstra) does not — the engines then disagree no matter how unique the
 *       optimum is.</li>
 * </ul>
 */
public final class OfflineTravelTimes {

	private static final Logger log = LogManager.getLogger(OfflineTravelTimes.class);

	/** 15-min bins — matches TravelTimeExportListener's export discretization. */
	public static final int TRAVEL_TIME_BIN_SIZE = 900;
	/** 36 h horizon. */
	public static final int TRAVEL_TIME_END = 36 * 3600;

	private OfflineTravelTimes() {}

	public static TravelTime load(String ttFile) {
		log.info("Loading pre-computed travel times from: {}", ttFile);
		TimeDiscretizer timeDiscretizer = new TimeDiscretizer(TRAVEL_TIME_END, TRAVEL_TIME_BIN_SIZE);
		try {
			URL ttUrl = Path.of(ttFile).toUri().toURL();
			double[][] matrix = DvrpOfflineTravelTimes.loadLinkTravelTimes(timeDiscretizer, ttUrl, "\t");
			TravelTime baseTt = DvrpOfflineTravelTimes.asTravelTime(timeDiscretizer, matrix);
			log.info("Bound pre-computed travel times ({} bins, time-clamped to {}h, freespeed-clamped)",
					timeDiscretizer.getIntervalCount(), TRAVEL_TIME_END / 3600);
			return (link, time, person, vehicle) -> Math.max(
					baseTt.getLinkTravelTime(link, Math.min(time, TRAVEL_TIME_END), person, vehicle),
					link.getLength() / link.getFreespeed());
		} catch (Exception e) {
			throw new RuntimeException("Failed to load offline travel times from " + ttFile, e);
		}
	}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `$MVN test -Dtest=OfflineTravelTimesTest`
Expected: PASS.

- [ ] **Step 5: Replace the four copies**

In each file, replace the private `loadOfflineTravelTimes` call with `OfflineTravelTimes.load(...)`, delete the private method, and delete now-unused imports (`TimeDiscretizer`, `DvrpOfflineTravelTimes`, `URL`) and now-unused private `TRAVEL_TIME_*` constants. Add import `org.matsim.contrib.demand_extraction.algorithm.network.OfflineTravelTimes` where needed.

1. `LyonEqasimScenarioFixture.java:356` → `TravelTime offlineTravelTime = OfflineTravelTimes.load(travelTimesPath);` — delete method at 378-391. Keep the class constants `TRAVEL_TIME_BIN_SIZE`/`TRAVEL_TIME_END` (76-77) ONLY if referenced elsewhere in the file; otherwise delete (check with the compiler).
2. `Phase2Module.java:117` → `TravelTime travelTime = OfflineTravelTimes.load(p2.travelTimesTsv.toString());` — delete method 210-224 and constants 64-67; update the class javadoc line 52 that references them to reference `OfflineTravelTimes`. The "Mirrors LyonEqasimScenarioFixture verbatim" javadoc is obsolete — equality now holds by construction.
3. `Phase2RoutingSetup.java:59` → `TravelTime travelTime = OfflineTravelTimes.load(travelTimesPath);` — delete method 69-79. Re-point the public constants so external users keep compiling:
   ```java
   public static final int TRAVEL_TIME_BIN_SIZE = OfflineTravelTimes.TRAVEL_TIME_BIN_SIZE;
   public static final int TRAVEL_TIME_END = OfflineTravelTimes.TRAVEL_TIME_END;
   ```
   (Do NOT change the disutility here yet — that is Task 8.)
4. `RunBavariaEqasimDemandExtraction.java:229` → `TravelTime offlineTravelTime = OfflineTravelTimes.load(p.travelTimesPath);` — delete method 305-318 and constants 81-82 (keep them if referenced elsewhere in the file; the compiler decides).

- [ ] **Step 6: Compile + run the touched unit tests**

Run: `$MVN test-compile && $MVN test -Dtest=OfflineTravelTimesTest`
Expected: BUILD SUCCESS, test PASS.

- [ ] **Step 7: Commit**

```bash
git add -A src/
git commit -m "feat(routing): OfflineTravelTimes — single loader, freespeed clamp for ALT admissibility"
```

---

### Task 3: `MatsimNetworkCache` single-path rewrite

**Files:**
- Modify: `src/main/java/org/matsim/contrib/demand_extraction/algorithm/network/MatsimNetworkCache.java`
- Modify: `src/test/java/org/matsim/contrib/demand_extraction/algorithm/network/MatsimNetworkCacheTestFixture.java`
- Modify (mechanical rename): every test calling `createWithSpeedyAltRouting`/`createWithSpeedyAltRoutingDeterministic`

- [ ] **Step 1: Rewrite the injected constructor (lines 133-218) to a single code path**

```java
	@Inject
	public MatsimNetworkCache(
			Network network,
			ExMasConfigGroup config,
			Injector injector) {
		// Inject DRT-specific TravelTime / TravelDisutilityFactory; fall back to car.
		// Mode-specific disutility stays the primary cost function so future toll /
		// road-pricing scenarios route correctly. Determinism comes from the wrapper,
		// not from discarding the mode costs.
		String drtMode = config.getDrtMode();

		TravelTime drtTravelTime;
		try {
			drtTravelTime = injector.getInstance(Key.get(TravelTime.class, Names.named(drtMode)));
		} catch (Exception e) {
			drtTravelTime = injector.getInstance(Key.get(TravelTime.class, Names.named(TransportMode.car)));
		}

		TravelDisutilityFactory drtDisutilityFactory;
		try {
			drtDisutilityFactory = injector.getInstance(
					Key.get(TravelDisutilityFactory.class, Names.named(drtMode)));
		} catch (Exception e) {
			drtDisutilityFactory = injector.getInstance(
					Key.get(TravelDisutilityFactory.class, Names.named(TransportMode.car)));
		}
		TravelDisutility baseDisutility = drtDisutilityFactory.createTravelDisutility(drtTravelTime);

		// Deterministic by construction: unique optimum (eps*length tie-breaker) +
		// admissible heuristic => LeastCostPathTree == SpeedyALT == any instance ==
		// any thread count == any JVM. Both engines below are built from this SAME
		// wrapped instance.
		TravelDisutility disutility = DeterministicTravelDisutility.wrap(baseDisutility, drtTravelTime, network);
		log.info("Network cache routing: SpeedyALT + LeastCostPathTree on {} (wrapping {})",
				disutility.getClass().getSimpleName(), baseDisutility.getClass().getSimpleName());

		this.network = network;
		this.travelTime = drtTravelTime;
		this.travelDisutility = disutility;
		this.timeBinSize = config.getNetworkTimeBinSize();

		SpeedyALTFactory altFactory = new SpeedyALTFactory();
		this.threadLocalRouter = ThreadLocal.withInitial(() ->
				altFactory.createPathCalculator(network, disutility, drtTravelTime));

		// Create dummy person and vehicle for generic routing
		this.dummyPerson = PopulationUtils.getFactory().createPerson(Id.createPersonId("exmas_dummy"));
		VehicleType dummyType = VehicleUtils.createVehicleType(Id.create("car", VehicleType.class));
		this.dummyVehicle = VehicleUtils.createVehicle(Id.createVehicleId("exmas_dummy_vehicle"), dummyType);

		// Build SpeedyGraph eagerly — must happen before Id caches are reset at end of simulation
		SpeedyGraph speedyGraph = SpeedyGraphBuilder.build(network);
		this.threadLocalTree = ThreadLocal.withInitial(() ->
			new LeastCostPathTree(speedyGraph, this.travelTime, this.travelDisutility));
	}
```

- [ ] **Step 2: Delete the dead fields and constants**

- Fields `routerProvider` (line 79), `useSharedDeterministicRouter` (81), `sharedRouter` (82), `routerLock` (83).
- Constants `DETERMINISTIC_TIME_COEF` / `DETERMINISTIC_DISTANCE_COEF` (75-76).
- In the private no-arg test constructor (843-855): delete the assignments to the removed fields.
- Remove now-unused imports (`Provider`, `TimeDistanceTravelDisutility` import stays until Step 3 replaces it — the compiler will tell you which imports are dead at the end).

- [ ] **Step 3: Collapse the three test constructors (862-935) into one production mirror**

Replace all three with:

```java
	/**
	 * Test constructor mirroring the production routing path exactly: the given
	 * disutility is wrapped in {@link DeterministicTravelDisutility}; cache-miss
	 * point-to-point routing uses thread-local SpeedyALT and batch SSSP uses
	 * {@link LeastCostPathTree}, both built from the SAME wrapped instance.
	 *
	 * <p>Keeps raw additive segment metrics: the cache deliberately avoids per-segment
	 * quantization because it is not additive and can make a split route appear shorter
	 * than the equivalent direct route at feasibility boundaries.
	 */
	MatsimNetworkCache(Network network, TravelTime travelTime, TravelDisutility travelDisutility,
			int timeBinSize) {
		this.network = network;
		this.travelTime = travelTime;
		TravelDisutility wrapped = DeterministicTravelDisutility.wrap(travelDisutility, travelTime, network);
		this.travelDisutility = wrapped;
		this.timeBinSize = timeBinSize;

		this.dummyPerson = PopulationUtils.getFactory().createPerson(Id.createPersonId("test_dummy"));
		VehicleType dummyType = VehicleUtils.createVehicleType(Id.create("car", VehicleType.class));
		this.dummyVehicle = VehicleUtils.createVehicle(Id.createVehicleId("test_dummy_vehicle"), dummyType);

		SpeedyGraph speedyGraph = SpeedyGraphBuilder.build(network);
		SpeedyALTFactory altFactory = new SpeedyALTFactory();
		this.threadLocalRouter = ThreadLocal.withInitial(() ->
			altFactory.createPathCalculator(network, wrapped, travelTime));
		this.threadLocalTree = ThreadLocal.withInitial(() ->
			new LeastCostPathTree(speedyGraph, travelTime, wrapped));
	}
```

Delete the 5-arg and 6-arg constructors entirely (`DijkstraFactory` usage goes away — this closes the 2026-04-22 gap where tests routed cache misses with Dijkstra while production used SpeedyALT).

- [ ] **Step 4: Collapse the fixture**

In `MatsimNetworkCacheTestFixture.java`, replace the three `createWith*` factory methods (lines 41-60) with one; keep `create()/put/peek/isSsspCompleted` unchanged:

```java
    /** Build a MatsimNetworkCache with real routing that mirrors production exactly:
     *  the given disutility is wrapped in DeterministicTravelDisutility; SpeedyALT for
     *  cache-miss point-to-point + LeastCostPathTree for batch SSSP, both from the same
     *  wrapped instance. Deterministic across instances, threads, and JVMs. */
    public static MatsimNetworkCache createWithRouting(Network network, TravelTime tt, TravelDisutility td, int timeBinSize) {
        return new MatsimNetworkCache(network, tt, td, timeBinSize);
    }
```

- [ ] **Step 5: Mechanical rename across tests**

```bash
cd /c/Users/VWAUCCY/dev/msf/projects/Dissertation/matsim-libs/contribs/drt-demand-extraction
grep -rl "createWithSpeedyAltRoutingDeterministic\|createWithSpeedyAltRouting" src/test \
  | xargs sed -i 's/createWithSpeedyAltRoutingDeterministic/createWithRouting/g; s/createWithSpeedyAltRouting/createWithRouting/g'
```

Known callers: D3EnumerationGapDiagnosticTest, D3VsD4FeasibilityComparisonTest (×3), RoutingDeterminismTest, LyonDistanceGateSweepTest, LyonKScheduleSweepTest, LyonPruningAblationTest, LyonResidualD4RegressionTest (×3), PttActualTraversalAccountingTest, RoutingAsymmetryProbeTest (×4). Callers of plain `createWithRouting` (MatsimNetworkCacheBatchTest, ConnectionCacheJournalCacheRoundTripTest, BamasCheckpointResumeDeterminismTest, PairGeneratorMaxWaitFilterTest, StopBasedTestFixtures) need no change — same name, same signature, now-deterministic engine.

- [ ] **Step 6: Compile + run the default-suite network tests**

Run: `$MVN test-compile && $MVN test -Dtest='MatsimNetworkCacheBatchTest,ConnectionCacheJournalCacheRoundTripTest,PairGeneratorMaxWaitFilterTest'`
Expected: BUILD SUCCESS; tests PASS. (If a batch test asserts exact segment values computed under the old Dijkstra/unwrapped disutility, the values may shift within tie noise — update the expectation and note it in the commit message; do NOT weaken assertions to ranges.)

- [ ] **Step 7: Commit**

```bash
git add -A src/
git commit -m "refactor(routing): MatsimNetworkCache single deterministic path — wrap mode disutility, delete flag branches + dead router fields, collapse test ctors"
```

---

### Task 4: Cross-engine determinism gate (default test suite)

**Files:**
- Create: `src/test/java/org/matsim/contrib/demand_extraction/algorithm/network/CrossEngineRoutingDeterminismTest.java`

This is the always-on gate (untagged → runs in `mvn test`; the Lyon-scale `RoutingDeterminismTest` stays env-gated). The synthetic network is built to maximize ties: a chain of "diamonds" where both branches have EQUAL travel time but DIFFERENT length — with k diamonds, 2^k equal-time paths. Without the decorator this is exactly the regime that produced the +3.1% pair drift; with it, every engine must pick the identical (shortest-distance) path.

- [ ] **Step 1: Write the test**

```java
package org.matsim.contrib.demand_extraction.algorithm.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility;
import org.matsim.core.router.speedy.LeastCostPathTree;
import org.matsim.core.router.speedy.SpeedyALTFactory;
import org.matsim.core.router.speedy.SpeedyGraphBuilder;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.LeastCostPathCalculator.Path;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;
import org.matsim.core.utils.misc.OptionalTime;

/**
 * Always-on determinism gate on a synthetic tie-heavy network.
 *
 * <p>Chain of K diamonds s_0 .. s_K; in each diamond the TOP branch is 2 links of
 * 100 m @ 10 m/s (20 s, 200 m) and the BOTTOM branch is 2 links of 120 m @ 12 m/s
 * (20 s, 240 m). Every source-to-sink path costs the same TIME (20K s) but a different
 * DISTANCE — 2^K tied paths under a time-only disutility, the worst case for
 * Dijkstra-vs-A* tie-breaking. {@link DeterministicTravelDisutility} must make all
 * engines return the identical all-top path (200 m per diamond).
 */
class CrossEngineRoutingDeterminismTest {

	private static final int K = 6; // diamonds -> 2^6 = 64 tied paths

	private record Net(Network network, Id<Link> entryLink, Id<Link> exitLink) {}

	private static Net diamondChain() {
		Network network = NetworkUtils.createNetwork();
		Node prev = NetworkUtils.createAndAddNode(network, Id.createNodeId("s0"), new Coord(0, 0));
		// Entry stub link so the source of routing is a link, as in production.
		Node entryTail = NetworkUtils.createAndAddNode(network, Id.createNodeId("entry"), new Coord(-50, 0));
		NetworkUtils.createAndAddLink(network, Id.createLinkId("entry"), entryTail, prev, 50.0, 10.0, 1000.0, 1.0);

		for (int i = 0; i < K; i++) {
			double x = (i + 1) * 300.0;
			Node top = NetworkUtils.createAndAddNode(network, Id.createNodeId("t" + i), new Coord(x - 150, 100));
			Node bot = NetworkUtils.createAndAddNode(network, Id.createNodeId("b" + i), new Coord(x - 150, -100));
			Node next = NetworkUtils.createAndAddNode(network, Id.createNodeId("s" + (i + 1)), new Coord(x, 0));
			// top branch: 2 x (100 m @ 10 m/s) = 20 s / 200 m
			NetworkUtils.createAndAddLink(network, Id.createLinkId("t" + i + "a"), prev, top, 100.0, 10.0, 1000.0, 1.0);
			NetworkUtils.createAndAddLink(network, Id.createLinkId("t" + i + "b"), top, next, 100.0, 10.0, 1000.0, 1.0);
			// bottom branch: 2 x (120 m @ 12 m/s) = 20 s / 240 m
			NetworkUtils.createAndAddLink(network, Id.createLinkId("b" + i + "a"), prev, bot, 120.0, 12.0, 1000.0, 1.0);
			NetworkUtils.createAndAddLink(network, Id.createLinkId("b" + i + "b"), bot, next, 120.0, 12.0, 1000.0, 1.0);
			prev = next;
		}
		Node exitHead = NetworkUtils.createAndAddNode(network, Id.createNodeId("exit"), new Coord(K * 300.0 + 50, 0));
		NetworkUtils.createAndAddLink(network, Id.createLinkId("exit"), prev, exitHead, 50.0, 10.0, 1000.0, 1.0);
		return new Net(network, Id.createLinkId("entry"), Id.createLinkId("exit"));
	}

	@Test
	void treeAndTwoAltInstancesAgreeByteForByteAndPickShortestDistanceTie() {
		Net net = diamondChain();
		TravelTime tt = new FreeSpeedTravelTime();
		TravelDisutility td = DeterministicTravelDisutility.wrap(
				new OnlyTimeDependentTravelDisutility(tt), tt, net.network());

		Link from = net.network().getLinks().get(net.entryLink());
		Link to = net.network().getLinks().get(net.exitLink());

		LeastCostPathTree tree = new LeastCostPathTree(SpeedyGraphBuilder.build(net.network()), tt, td);
		tree.calculate(from, 0.0, null, null);
		int toNodeIdx = to.getFromNode().getId().index();
		OptionalTime arrival = tree.getTime(toNodeIdx);
		assertTrue(arrival.isDefined(), "sink must be reachable");
		double treeCost = tree.getCost(toNodeIdx);
		double treeDist = tree.getDistance(toNodeIdx);

		SpeedyALTFactory factory = new SpeedyALTFactory();
		LeastCostPathCalculator alt1 = factory.createPathCalculator(net.network(), td, tt);
		LeastCostPathCalculator alt2 = factory.createPathCalculator(net.network(), td, tt);
		Path p1 = alt1.calcLeastCostPath(from, to, 0.0, null, null);
		Path p2 = alt2.calcLeastCostPath(from, to, 0.0, null, null);
		double d1 = p1.links.stream().mapToDouble(Link::getLength).sum();
		double d2 = p2.links.stream().mapToDouble(Link::getLength).sum();

		// Byte-identical across engines and instances.
		assertEquals(treeCost, p1.travelCost, 0.0, "tree vs ALT cost");
		assertEquals(p1.travelCost, p2.travelCost, 0.0, "ALT instance 1 vs 2 cost");
		assertEquals(arrival.seconds(), p1.travelTime, 0.0, "tree vs ALT travel time");
		assertEquals(treeDist, d1, 0.0, "tree vs ALT distance");
		assertEquals(d1, d2, 0.0, "ALT instance 1 vs 2 distance");

		// The eps tie-breaker resolves all 2^K time-ties toward minimal distance:
		// all-top path = K * 200 m (branch links only; entry/exit stubs excluded from
		// path.links distance? p1.links includes intermediate links between fromLink
		// and toLink, i.e. exactly the K*2 branch links).
		assertEquals(K * 200.0, d1, 1e-9, "must pick the shortest-distance tied path");
	}

	@Test
	void batchPrecomputeAndPointToPointPopulateIdenticalSegments() {
		Net net = diamondChain();
		TravelTime tt = new FreeSpeedTravelTime();
		TravelDisutility td = new OnlyTimeDependentTravelDisutility(tt);

		MatsimNetworkCache batchFilled = MatsimNetworkCacheTestFixture
				.createWithRouting(net.network(), tt, td, 900);
		MatsimNetworkCache missFilled = MatsimNetworkCacheTestFixture
				.createWithRouting(net.network(), tt, td, 900);

		@SuppressWarnings("unchecked")
		Id<Link>[] targets = new Id[] { net.exitLink() };
		batchFilled.batchPrecompute(net.entryLink(), 100.0, targets, 1e9);

		TravelSegment viaBatch = batchFilled.getSegment(net.entryLink(), net.exitLink(), 100.0);
		TravelSegment viaMiss = missFilled.getSegment(net.entryLink(), net.exitLink(), 100.0);

		assertTrue(viaBatch.isReachable() && viaMiss.isReachable());
		assertEquals(viaBatch.getTravelTime(), viaMiss.getTravelTime(), 0.0,
				"SSSP-tree fill and SpeedyALT cache-miss fill must produce identical travel time");
		assertEquals(viaBatch.getDistance(), viaMiss.getDistance(), 0.0,
				"... identical distance");
		assertEquals(viaBatch.getNetworkUtility(), viaMiss.getNetworkUtility(), 0.0,
				"... identical utility");
	}
}
```

- [ ] **Step 2: Run the test**

Run: `$MVN test -Dtest=CrossEngineRoutingDeterminismTest`
Expected: 2 tests PASS. If the first test's `K * 200.0` distance assertion fails because `path.links` includes/excludes boundary links differently than assumed, print `p1.links` ids, derive the correct expected constant from the actual link set (it must consist of ONLY `t*` top-branch links — that part is the real assertion), and fix the expected value.

- [ ] **Step 3: Sanity-check that the gate would catch a regression**

Temporarily change `createWithRouting`'s body in the fixture to skip the wrap (pass `travelDisutility` through). Run the test — `batchPrecomputeAndPointToPointPopulateIdenticalSegments` and/or the distance assertion SHOULD fail (engines tie-break the 64 equal-time paths differently). Revert the temporary change. If it does NOT fail, the diamond construction isn't producing ties — fix the network, don't ship a gate that can't catch the bug.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/org/matsim/contrib/demand_extraction/algorithm/network/CrossEngineRoutingDeterminismTest.java
git commit -m "test(routing): always-on cross-engine determinism gate on tie-heavy synthetic network"
```

---

### Task 5: `ModeRoutingCache` — always parallel

**Files:**
- Modify: `src/main/java/org/matsim/contrib/demand_extraction/demand/ModeRoutingCache.java:134-139`

- [ ] **Step 1: Delete the sequential guard**

Replace:

```java
		var personStream = persons.stream();
		if (!exMasConfig.isUseDeterministicNetworkRouting()) {
			personStream = personStream.parallel();
		}

		personStream.forEach(person -> {
```

with:

```java
		// Always parallel: per-person routing is independent, results land in
		// per-person maps, and the shared connection cache is deterministic by
		// construction (DeterministicTravelDisutility) — order of fill cannot
		// change any cached value. The old sequential-when-deterministic guard
		// was the flag's dominant performance penalty.
		persons.stream().parallel().forEach(person -> {
```

- [ ] **Step 2: Compile**

Run: `$MVN test-compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/matsim/contrib/demand_extraction/demand/ModeRoutingCache.java
git commit -m "perf(routing): ModeRoutingCache always parallel — delete deterministic-mode sequential guard"
```

---

### Task 6: Extend `RoutingDeterminismTest` (Lyon-scale, offline travel times)

**Files:**
- Modify: `src/test/java/org/matsim/contrib/demand_extraction/algorithm/network/RoutingDeterminismTest.java`

This closes the never-tested regime: the existing test proves engine equality only under `FreeSpeedTravelTime` + hardcoded `TimeDistanceTravelDisutility(1.0, 1e-4)`. Production runs offline TSV travel times — where the admissibility clamp matters.

- [ ] **Step 1: Switch the disutility to the production decorator**

In `dijkstraAndSpeedyAltProduceIdenticalPathsUnderTimeDistanceDisutility` (line 74) and `parallelSpeedyAltMatchesSequentialUnderTimeDistanceDisutility` (line 135), replace

```java
		TravelDisutility td = new TimeDistanceTravelDisutility(tt, TIME_COEF, DIST_COEF);
```

with

```java
		TravelDisutility td = DeterministicTravelDisutility.wrap(
				new OnlyTimeDependentTravelDisutility(tt), tt, network);
```

(`OnlyTimeDependentTravelDisutility` is already imported at line 31. Delete the now-unused `TIME_COEF`/`DIST_COEF` constants and update the class javadoc 44-55 to name `DeterministicTravelDisutility`. Rename the two methods' `...UnderTimeDistanceDisutility` suffix to `...UnderDeterministicDisutility`.)

- [ ] **Step 2: Add the offline-TSV variant**

Add after the first test method:

```java
	@Test
	void dijkstraAndSpeedyAltAgreeUnderOfflineTravelTimes() throws Exception {
		String ttTsv = System.getenv("LYON_TRAVEL_TIMES_TSV");
		assumeTrue(ttTsv != null && !ttTsv.isBlank(), "LYON_TRAVEL_TIMES_TSV required");

		Network network = loadLyonNetwork();
		// Production loader: freespeed clamp keeps SpeedyALT's landmark heuristic
		// (built from freespeed-time minimums) admissible — without it a single TSV
		// value below free-flow lets A* return genuinely suboptimal paths that
		// LeastCostPathTree does not, regardless of tie-breaking.
		TravelTime tt = OfflineTravelTimes.load(ttTsv);
		TravelDisutility td = DeterministicTravelDisutility.wrap(
				new OnlyTimeDependentTravelDisutility(tt), tt, network);
		assertEnginesAgree(network, tt, td);
	}
```

Extract the comparison loop body of the first test (lines 76-127, everything from `SpeedyGraph speedyGraph = ...` to the final `assertTrue(compared > ...)`) into a private helper so both tests share it:

```java
	private void assertEnginesAgree(Network network, TravelTime tt, TravelDisutility td) {
		// (moved body, unchanged — uses departure time 0.0 for tree.calculate and
		// alt.calcLeastCostPath as before)
	}
```

NOTE: the tree comparison reads `tree.getTime/getCost/getDistance` at departure 0.0; with time-binned offline travel times that is bin 0 — fine, both engines query the same bin.

- [ ] **Step 3: Compile (test is env-gated; full run happens in Task 10)**

Run: `$MVN test-compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/org/matsim/contrib/demand_extraction/algorithm/network/RoutingDeterminismTest.java
git commit -m "test(routing): RoutingDeterminismTest uses production decorator + offline-TSV admissibility variant"
```

---### Task 7: Phase-2 export routing on the decorator; delete `TimeDistanceTravelDisutility`

**Files:**
- Modify: `src/main/java/org/matsim/contrib/demand_extraction/run/Phase2RoutingSetup.java`
- Modify: `src/main/java/org/matsim/contrib/demand_extraction/run/RunConnectingRouteExport.java:26` (javadoc only)
- Delete: `src/main/java/org/matsim/contrib/demand_extraction/algorithm/network/TimeDistanceTravelDisutility.java`
- Delete: `src/test/java/org/matsim/contrib/demand_extraction/algorithm/network/TimeDistanceTravelDisutilityTest.java`

- [ ] **Step 1: Switch Phase2RoutingSetup to the decorator**

In `Phase2RoutingSetup.load(...)`, replace

```java
        TravelDisutility disutility = new TimeDistanceTravelDisutility(travelTime, DET_TIME_COEF, DET_DIST_COEF);
```

with

```java
        // Identical wrap to MatsimNetworkCache's injected path (Lyon fixture binds
        // OnlyTimeDependentTravelDisutilityFactory for car), so route/detour exports
        // are bit-for-bit the phase-2 routing.
        TravelDisutility disutility = DeterministicTravelDisutility.wrap(
                new OnlyTimeDependentTravelDisutility(travelTime), travelTime, network);
```

Delete the `DET_TIME_COEF`/`DET_DIST_COEF` constants (39-40), the `TimeDistanceTravelDisutility` import, and add imports for `DeterministicTravelDisutility` and `org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility`. Update the class javadoc (28-33) — it names "deterministic time-distance disutility"; say "DeterministicTravelDisutility wrap" instead. Update `RunConnectingRouteExport.java:26` javadoc the same way.

- [ ] **Step 2: Delete the subsumed class and its test**

```bash
git rm src/main/java/org/matsim/contrib/demand_extraction/algorithm/network/TimeDistanceTravelDisutility.java \
       src/test/java/org/matsim/contrib/demand_extraction/algorithm/network/TimeDistanceTravelDisutilityTest.java
```

- [ ] **Step 3: Compile — the compiler finds any straggler references**

Run: `$MVN test-compile`
Expected: BUILD SUCCESS. (Tasks 3 and 6 already removed the MatsimNetworkCache and RoutingDeterminismTest references; if anything else still imports it, switch it to the decorator the same way.)

- [ ] **Step 4: Commit**

```bash
git add -A src/
git commit -m "refactor(routing): Phase2 export routing on DeterministicTravelDisutility; delete subsumed TimeDistanceTravelDisutility"
```

---

### Task 8: Remove the `useDeterministicNetworkRouting` flag

**Files:**
- Modify: `src/main/java/org/matsim/contrib/demand_extraction/config/ExMasConfigGroup.java` (217-220, 992-1000, 1666-1668)
- Modify: `src/main/java/org/matsim/contrib/demand_extraction/demand/DemandExtractionConfigValidator.java` (114-115, 160-164)
- Modify: `src/main/java/org/matsim/contrib/demand_extraction/run/RunLyonEqasimDemandExtraction.java`
- Modify: `src/main/java/org/matsim/contrib/demand_extraction/run/RunDemandExtractionPhase1.java` (86, 159-161)
- Modify: `src/main/java/org/matsim/contrib/demand_extraction/run/RunKelheimDemandExtraction.java` (137, 569, 584, 603-605)
- Modify: `src/main/java/org/matsim/contrib/demand_extraction/run/RunBavaria30kmDemandExtraction.java` (118, 715, 729, 749-751)
- Modify: `src/test/java/org/matsim/contrib/demand_extraction/run/RunLyonEqasimDemandExtractionWiringTest.java` (80-91)
- Modify: `src/test/java/org/matsim/contrib/demand_extraction/run/RunDemandExtractionPhase1WiringTest.java` (121, 147)

**CLI compatibility rule:** external callers (paper1_workflow.py, paper2_workflow.py, paper2_zones.py, zone YAMLs with `deterministic_routing: true`, run_a3_killresume_gate.sh) still pass `--deterministic-routing` / `--deterministic`. The runners MUST keep accepting these flags as warn-and-ignore no-ops. Only the config option and its plumbing are deleted.

- [ ] **Step 1: ExMasConfigGroup — delete the option**

Delete:
- field + comment block, lines 217-220 (`// Network routing settings ... private boolean useDeterministicNetworkRouting = false;`)
- `@StringGetter`/`@StringSetter` pair, lines 992-1000
- comment-map entry, lines 1666-1668 (`map.put("useDeterministicNetworkRouting", ...)`)

- [ ] **Step 2: DemandExtractionConfigValidator — delete flag logging**

Delete lines 114-115 (the `Deterministic network routing:` log). Replace the branch at 160-164 with one line:

```java
		log.info("  Routing: mode-specific disutility wrapped in DeterministicTravelDisutility "
				+ "(deterministic by construction, tolls included if configured)");
```

Keep `routingRandomness = 0` enforcement (149-155) — randomized disutility would break the unique-optimum property at its root.

- [ ] **Step 3: RunLyonEqasimDemandExtraction — field removal + no-op flag**

- Delete the `deterministicRouting` field and its javadoc (lines 70-73), the constructor parameter (124) and assignment (150), and the argument in the `return new ParsedArgs(...)` call (248).
- Replace the parse case (223) with a warn-and-ignore no-op:
  ```java
  				case "--deterministic-routing" -> log.warn(
  						"--deterministic-routing is deprecated and ignored: routing is always "
  						+ "deterministic (DeterministicTravelDisutility tie-breaker).");
  ```
- Remove `[--deterministic-routing]` from the usage string (381).
- Delete the apply block in `applyCliOverrides` (534-537) — this also removes the stale "shared locked router" log line.

- [ ] **Step 4: RunDemandExtractionPhase1**

- Remove `[--deterministic-routing]` from the usage string (86).
- Delete the apply block (159-161) in `applyParsedArgs`.

- [ ] **Step 5: RunKelheimDemandExtraction + RunBavaria30kmDemandExtraction**

In both: keep the `--deterministic` parse case but make it warn-and-ignore (delete the local `deterministic` variable), remove the `deterministic` parameter from `configureExMas(...)` (declaration + call site), and delete the `if (deterministic) { exMasConfig.setUseDeterministicNetworkRouting(true); }` block (Kelheim 603-605, Bavaria 749-751).

Kelheim parse site (line 137 area):
```java
			} else if ("--deterministic".equals(args[i])) {
				log.warn("--deterministic is deprecated and ignored: routing is always deterministic.");
			}
```

Bavaria parse site (line 118):
```java
				case "--deterministic" -> log.warn(
						"--deterministic is deprecated and ignored: routing is always deterministic.");
```

- [ ] **Step 6: Update the wiring tests**

- `RunLyonEqasimDemandExtractionWiringTest`: the `parsesDeterministicRoutingFlag` test (80-91) asserted `args.deterministicRouting` — replace it with a no-crash acceptance test:
  ```java
      @Test
      void acceptsDeprecatedDeterministicRoutingFlagAsNoOp() {
          // Python workflows (paper1/paper2) still pass the flag; parsing must not fail.
          RunLyonEqasimDemandExtraction.ParsedArgs args = RunLyonEqasimDemandExtraction.parseArgs(new String[] {
                  "--sample", "1",
                  "--scenario-dir", "scenario",
                  "--travel-times", "tt.tsv",
                  "--deterministic-routing"
          });
          assertEquals(1, args.sample);
      }
  ```
- `RunDemandExtractionPhase1WiringTest`: remove `b.deterministicRouting` from the builder/ctor call (121) and the `boolean deterministicRouting = false;` builder field (147).
- `RunDemandExtractionTwoPhaseArgsTest` (45, 66): unchanged — the two-phase driver still forwards the flag and downstream runners warn-and-ignore. Run it to confirm.

- [ ] **Step 7: Compile + run the wiring tests**

Run: `$MVN test -Dtest='RunLyonEqasimDemandExtractionWiringTest,RunDemandExtractionPhase1WiringTest,RunDemandExtractionTwoPhaseArgsTest'`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add -A src/
git commit -m "refactor(routing): delete useDeterministicNetworkRouting option — CLI flags stay as warn-and-ignore no-ops"
```

---

### Task 9: Delete `DrtRouterProvider`, its bindings, and orphaned `drtAllowedModes`

After Task 3, the named `direct<Mode>Router` binding has zero consumers (MatsimNetworkCache was the only one; verify with `grep -r "directDrtRouter\|direct.*Router\|DrtRouterProvider" src/`).

**Files:**
- Delete: `src/main/java/org/matsim/contrib/demand_extraction/algorithm/DrtRouterProvider.java`
- Modify: `src/main/java/org/matsim/contrib/demand_extraction/algorithm/ExMasAlgorithmModule.java` (52-59, 85-90)
- Modify: `src/main/java/org/matsim/contrib/demand_extraction/run/Phase2Module.java` (125-126, 155-160, 226-229)
- Modify: `src/main/java/org/matsim/contrib/demand_extraction/config/ExMasConfigGroup.java` (117-122, 777-783, 1619-1620)

Behavior note (already true today): production deterministic mode routed on the FULL injected network (both SpeedyALT and the tree), not on DrtRouterProvider's mode-filtered network. The single path keeps full-network routing; `drtAllowedModes` was only consumed by the provider and nothing in the repo ever sets it.

- [ ] **Step 1: Verify zero consumers**

```bash
grep -rn "DrtRouterProvider\|getDrtAllowedModes\|setDrtAllowedModes" src/ | grep -v "ExMasAlgorithmModule\|Phase2Module\|ExMasConfigGroup\|DrtRouterProvider.java"
```
Expected: no output. If anything else consumes them, STOP and surface it instead of deleting.

- [ ] **Step 2: Delete**

- `git rm src/main/java/org/matsim/contrib/demand_extraction/algorithm/DrtRouterProvider.java`
- `ExMasAlgorithmModule`: delete the binding block (52-59 incl. the `exmasConfig`/`drtRouterName` locals), the `capitalize` helper (85-90), the `LeastCostPathCalculator` import, and trim the now-stale comment block (37-54) to a short note that MatsimNetworkCache resolves TravelTime/TravelDisutilityFactory itself.
- `Phase2Module`: delete the section-7 binding (155-160 incl. comment), the `capitalize` helper (226-229), and the `LeastCostPathCalculatorFactory` binding (125-126) IF the grep in Step 1 plus `grep -rn "LeastCostPathCalculatorFactory" src/main/java/org/matsim/contrib/demand_extraction/` shows DrtRouterProvider was its only consumer in the Phase-2 injector; remove dead imports.
- `ExMasConfigGroup`: delete the `drtAllowedModes` field + comment (117-122), getter/setter (777-783), comment-map entry (1619-1620).

- [ ] **Step 3: Compile + default suite smoke**

Run: `$MVN test-compile && $MVN test -Dtest='CrossEngineRoutingDeterminismTest,DeterministicTravelDisutilityTest,OfflineTravelTimesTest'`
Expected: BUILD SUCCESS, tests PASS.

- [ ] **Step 4: Commit**

```bash
git add -A src/
git commit -m "refactor(routing): delete DrtRouterProvider + named router bindings + orphaned drtAllowedModes option"
```

---

### Task 10: Full validation + golden regeneration

- [ ] **Step 1: Full default test suite**

Run: `$MVN test`
Expected: BUILD SUCCESS. Two classes of legitimate failures:
- **Golden/regression expectations**: routing values shifted within tie noise (the accepted one-time change). Regenerate (Step 2) or update expected values; record old→new in the commit message.
- Anything else: a real bug — debug, don't paper over.
Known pre-existing failure on master: `ExMasR1R2ParityTest` degree-4 (6 golden vs 5 actual) — not caused by this work, may now even pass; note whichever way it lands.

- [ ] **Step 2: Regenerate Kelheim goldens**

```bash
bash scripts/regenerate_exmas_reference_golden.sh
$MVN test -Djunit.groups=regression -Djunit.excludedGroups= -Dtest=ExMasReferencePortRegressionTest
```
Expected: regeneration completes; regression test PASSES against the new goldens. Commit the regenerated golden files with a message stating routing changed from unwrapped/nondeterministic-tie disutility to `DeterministicTravelDisutility` (design doc reference).

- [ ] **Step 3: E2E thread-count determinism gate (the flag's replacement guarantee)**

Run the Kelheim E2E extraction twice with DIFFERENT thread counts and hash-compare outputs. Differing thread counts is the point: it certifies output is scheduling-independent, which also re-validates `RunFingerprint`'s exclusion of `algorithmProcessCount` from the resume fingerprint (Plan A3).

```bash
# Two runs differing ONLY in process counts (adapt args to the Kelheim runner's CLI):
# run 1: --algorithm-threads 16   -> out dir A
# run 2: --algorithm-threads 4    -> out dir B
# then:
sha256sum A/drt_demand/*.drt_requests.csv B/drt_demand/*.drt_requests.csv
sha256sum A/drt_demand/*.exmas_rides.csv  B/drt_demand/*.exmas_rides.csv
```
Expected: pairwise identical hashes. If they differ, diff the CSVs column-wise — a residual nondeterminism source (e.g. an unordered parallel reduction in post-processing) must be found and fixed, NOT serialized around.

- [ ] **Step 4: Lyon-scale env-gated tests (manual, needs scenario data)**

```bash
LYON_SCENARIO_DIR=<dir> LYON_TRAVEL_TIMES_TSV=<travel_times.tsv> \
  $MVN test -Pscenario-lyon -Dtest=RoutingDeterminismTest
```
Expected: all tests PASS, including the new offline-TSV variant.

- [ ] **Step 5: Lyon 1% R1/R2 fast comparison** — run the existing R1/R2 chain once to confirm parity at every degree (counts will differ from OLD baselines once, then are canonical).

- [ ] **Step 6: Final commit + submodule bump**

```bash
git add -A
git commit -m "test(routing): regenerated goldens + E2E thread-count determinism gate results"
# In the Dissertation superproject: bump the matsim-libs submodule pointer.
```

---

## Self-review checklist (done while writing)

- **Spec coverage:** Component 1 → Task 1; Component 2 → Task 2; Component 3 wiring → Task 3 (+7 for Phase-2); deletions table → Tasks 3, 5, 7, 8, 9 (flag, CLI, validator, dead fields, branches, test ctors, sequential guard, TimeDistanceTravelDisutility, loader copies); Validation 1 → Task 6, 2 → Tasks 1/2, 3 → Task 10.3 (extended to vary thread count), 4 → Task 10.5, 5 (A3 benefit) → certified by Task 10.3.
- **Deviation from design doc:** CLI flags are NOT deleted outright — kept as warn-and-ignore no-ops because paper1/paper2 Python workflows and zone YAMLs still pass them (verified by grep). Config option, plumbing, and behavior ARE deleted.
- **Addition beyond design doc:** `DrtRouterProvider` + `drtAllowedModes` deletion (Task 9) — consequence of the dead `routerProvider` field already listed in the deletions table; verified unconsumed.
- **Type consistency:** `DeterministicTravelDisutility.wrap(TravelDisutility, TravelTime, Network)` used identically in Tasks 1, 3, 6, 7; fixture method `createWithRouting(Network, TravelTime, TravelDisutility, int)` matches the surviving package-private constructor; `OfflineTravelTimes.load(String)` used in Tasks 2, 6, 7.
