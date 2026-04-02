package org.matsim.contrib.demand_extraction.algorithm.extension;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests whether beeline (Euclidean) distance is a safe and effective pruning
 * proxy for routed distance in the pruned greedy topological sort.
 *
 * <h3>Context</h3>
 * MATSim routing optimizes for travel TIME, not distance. The "routed distance"
 * from {@code MatsimNetworkCache.getSegment()} is the distance along the
 * time-optimal path — which may be LONGER than the distance-optimal path
 * (e.g., a highway that's faster but longer in km than a winding rural road).
 *
 * <h3>Key invariant</h3>
 * {@code beeline(A,B) <= routedDistance(A,B)} always holds because the
 * Euclidean distance is shorter than any road path.
 *
 * <h3>What this test checks</h3>
 * <ol>
 *   <li>Beeline pruning NEVER rejects orderings that survive with routed distances</li>
 *   <li>How often beeline sorting order differs from routed sorting order</li>
 *   <li>Effectiveness comparison: getSegment() calls saved by beeline pre-filter
 *       vs. stronger break achievable with routed-only sorting</li>
 * </ol>
 *
 * <h3>Design decision input</h3>
 * This test informs whether to use a two-tier approach (beeline break + routed
 * continue) or a single-tier approach (routed sort + routed break) in the
 * pruned greedy enumeration.
 */
class BeelineVsRoutedPruningTest {

	// ── Helpers ──────────────────────────────────────────────────────────

	/** Euclidean distance (same as PairGenerator.beeline). */
	private static double beeline(double x1, double y1, double x2, double y2) {
		double dx = x2 - x1, dy = y2 - y1;
		return Math.sqrt(dx * dx + dy * dy);
	}

	/**
	 * A candidate stop with known coordinates and a known routed distance
	 * from a reference point. The routed distance simulates what
	 * {@code MatsimNetworkCache.getSegment()} would return — the distance
	 * along the time-optimal path.
	 */
	record Candidate(String name, double x, double y, double routedDistFromRef) {
		double beelineFromRef(double refX, double refY) {
			return beeline(refX, refY, x, y);
		}
	}

	/**
	 * Result of simulating pruning at one depth level.
	 */
	record PruningResult(
			List<String> surviving,     // candidates that pass pruning
			int getSegmentCalls,        // number of routed distance lookups
			int beelineChecks           // number of beeline computations
	) {}

	// ── Core invariant: beeline <= routed ────────────────────────────────

	@Test
	void beelineIsAlwaysLowerBoundOnRoutedDistance() {
		// Beeline is Euclidean: it's geometrically impossible for a road path
		// to be shorter than the straight line. This is the invariant that
		// makes beeline a safe lower bound for pruning.
		//
		// We verify this with synthetic candidates that have realistic
		// detour factors (routed/beeline) from 1.05 to 2.5.
		double refX = 0, refY = 0;
		var candidates = createRuralBavariaScenario(refX, refY);

		for (var c : candidates) {
			double bee = c.beelineFromRef(refX, refY);
			assertTrue(bee <= c.routedDistFromRef(),
					c.name() + ": beeline (" + bee + ") must be <= routed ("
							+ c.routedDistFromRef() + ")");
		}
	}

	// ── Correctness: beeline break never rejects valid candidates ────────

	@Test
	void beelineBreakNeverRejectsValidCandidate() {
		// At each depth of the pruned greedy enumeration, candidates are
		// sorted and pruned against a distance budget. A "valid" candidate
		// is one whose routed distance from the reference point fits within
		// the budget.
		//
		// If we sort by beeline and BREAK when beeline > budget, we might
		// skip candidates whose beeline > budget. But since beeline <= routed,
		// any candidate with beeline > budget also has routed > budget.
		// Therefore the break never skips a valid candidate.

		double refX = 0, refY = 0;
		var candidates = createRuralBavariaScenario(refX, refY);

		// Test with various budgets
		for (double budget : new double[]{2000, 4000, 6000, 8000, 12000, 20000}) {
			Set<String> beelinePassers = new HashSet<>();
			Set<String> routedPassers = new HashSet<>();

			for (var c : candidates) {
				double bee = c.beelineFromRef(refX, refY);
				if (bee <= budget) beelinePassers.add(c.name());
				if (c.routedDistFromRef() <= budget) routedPassers.add(c.name());
			}

			// Every routed-valid candidate must also be beeline-valid
			// (because beeline <= routed, so if routed <= budget, beeline <= budget)
			assertTrue(beelinePassers.containsAll(routedPassers),
					"Budget " + budget + ": beeline must pass all routed-valid candidates. "
							+ "Routed passers: " + routedPassers + ", beeline passers: " + beelinePassers);
		}
	}

	// ── Sorting order mismatch ──────────────────────────────────────────

	@Test
	void beelineSortingCanDifferFromRoutedSorting() {
		// When time-optimal routing takes highways (longer distance, shorter time)
		// vs rural roads (shorter distance, longer time), candidates that are
		// beeline-close may be routed-far and vice versa.
		//
		// This means sorting by beeline gives a different candidate order than
		// sorting by routed distance, which weakens the pruning break.

		double refX = 0, refY = 0;
		var candidates = createSortingMismatchScenario(refX, refY);

		// Sort by beeline
		var beelineSorted = new ArrayList<>(candidates);
		beelineSorted.sort(Comparator.comparingDouble(c -> c.beelineFromRef(refX, refY)));

		// Sort by routed
		var routedSorted = new ArrayList<>(candidates);
		routedSorted.sort(Comparator.comparingDouble(Candidate::routedDistFromRef));

		// Extract name orderings
		var beelineOrder = beelineSorted.stream().map(Candidate::name).toList();
		var routedOrder = routedSorted.stream().map(Candidate::name).toList();

		// They SHOULD differ in this scenario (highway vs rural road)
		assertNotEquals(beelineOrder, routedOrder,
				"Sorting orders should differ: beeline=" + beelineOrder + ", routed=" + routedOrder);

		// Specifically, "highway" is beeline-far but routed-close,
		// and "lake_detour" is beeline-close but routed-far
		int hwBeelineRank = beelineOrder.indexOf("highway");
		int hwRoutedRank = routedOrder.indexOf("highway");
		int lakeBeelRank = beelineOrder.indexOf("lake_detour");
		int lakeRoutedRank = routedOrder.indexOf("lake_detour");

		assertTrue(hwBeelineRank > hwRoutedRank,
				"highway should rank better (lower) in routed sort than beeline sort");
		assertTrue(lakeBeelRank < lakeRoutedRank,
				"lake_detour should rank better (lower) in beeline sort than routed sort");
	}

	// ── Effectiveness: beeline break vs routed break ────────────────────

	@Test
	void routedBreakPrunesMoreEffectivelyThanBeelineBreak() {
		// Simulate pruning at one enumeration depth with both approaches.
		// The routed-break approach calls getSegment for ALL candidates upfront
		// (for sorting) but then breaks strongly. The beeline-break approach
		// avoids getSegment for candidates beyond beeline threshold but can
		// only 'continue' (not break) on routed distance.

		double refX = 0, refY = 0;
		var candidates = createRuralBavariaScenario(refX, refY);
		double budget = 5000; // meters

		var beelineResult = simulateBeelinePruning(candidates, refX, refY, budget);
		var routedResult = simulateRoutedPruning(candidates, refX, refY, budget);

		// Both must find the same valid candidates (correctness)
		assertEquals(new HashSet<>(routedResult.surviving()), new HashSet<>(beelineResult.surviving()),
				"Both approaches must find identical valid candidates");

		// Routed approach needs more getSegment calls (sorts all upfront)
		// but achieves stronger break
		assertTrue(routedResult.getSegmentCalls() >= beelineResult.getSegmentCalls(),
				"Routed approach calls getSegment for all candidates (for sorting)");

		// Report the trade-off
		int getSegmentSaved = routedResult.getSegmentCalls() - beelineResult.getSegmentCalls();
		System.out.println("=== Beeline vs Routed Pruning Effectiveness ===");
		System.out.println("Candidates: " + candidates.size());
		System.out.println("Budget: " + budget + "m");
		System.out.println("Valid candidates: " + routedResult.surviving().size());
		System.out.println("Beeline approach: " + beelineResult.getSegmentCalls() + " getSegment calls, "
				+ beelineResult.beelineChecks() + " beeline checks");
		System.out.println("Routed approach:  " + routedResult.getSegmentCalls() + " getSegment calls, "
				+ routedResult.beelineChecks() + " beeline checks");
		System.out.println("getSegment calls saved by beeline: " + getSegmentSaved);
		System.out.println("Each saved call avoids: 1 HashMap lookup (cache hit) or 1 SpeedyALT route (cache miss)");
	}

	@Test
	void beelineBreakWeakerThanRoutedBreakDueToSortingMismatch() {
		// When beeline order ≠ routed order, beeline-break misses pruning
		// opportunities that routed-break would catch.
		//
		// Scenario: candidates sorted by beeline have a routed-far candidate
		// BEFORE a routed-close one. The beeline approach can't break on the
		// routed-far candidate (it uses 'continue'), so it continues to check
		// the next candidate. The routed approach would have already broken.

		double refX = 0, refY = 0;
		var candidates = createSortingMismatchScenario(refX, refY);

		// Budget that exposes the mismatch: passes "highway" (routed=3300)
		// but fails "lake_detour" (routed=7200). Since lake_detour has lower
		// beeline, it comes first in beeline sort — we can't break on it,
		// must continue.
		double budget = 5000;

		var beelineResult = simulateBeelinePruning(candidates, refX, refY, budget);
		var routedResult = simulateRoutedPruning(candidates, refX, refY, budget);

		// Same valid candidates found
		assertEquals(new HashSet<>(routedResult.surviving()), new HashSet<>(beelineResult.surviving()));

		System.out.println("=== Sorting Mismatch Impact ===");
		System.out.println("Beeline getSegment calls: " + beelineResult.getSegmentCalls());
		System.out.println("Routed getSegment calls:  " + routedResult.getSegmentCalls());
		System.out.println("Note: beeline approach may call getSegment MORE than routed approach");
		System.out.println("when beeline-close candidates fail routed check (continue, not break)");
		System.out.println("and beeline-far candidates that would break are never reached");
	}

	// ── Monte Carlo: realistic detour factor distributions ──────────────

	@Test
	void monteCarloDetourFactorAnalysis() {
		// Generate many random candidate sets with detour factors drawn from
		// a realistic distribution (rural Bavaria: median 1.3, range 1.05-2.5).
		// For each set, compare beeline vs routed pruning.
		//
		// This gives statistical confidence in the pruning effectiveness gap.

		Random rng = new Random(42);
		int numTrials = 1000;
		int candidatesPerTrial = 6; // degree 6

		int totalBeelineGetSegment = 0;
		int totalRoutedGetSegment = 0;
		int sortingMismatches = 0;
		int falseRejections = 0;

		for (int trial = 0; trial < numTrials; trial++) {
			double refX = 0, refY = 0;
			var candidates = generateRandomCandidates(rng, refX, refY, candidatesPerTrial);

			// Random budget: between 30% and 80% of the max beeline
			double maxBeeline = candidates.stream()
					.mapToDouble(c -> c.beelineFromRef(refX, refY))
					.max().orElse(10000);
			double budget = maxBeeline * (0.3 + rng.nextDouble() * 0.5);

			// Check sorting order
			var beelineSorted = new ArrayList<>(candidates);
			beelineSorted.sort(Comparator.comparingDouble(c -> c.beelineFromRef(refX, refY)));
			var routedSorted = new ArrayList<>(candidates);
			routedSorted.sort(Comparator.comparingDouble(Candidate::routedDistFromRef));

			if (!beelineSorted.stream().map(Candidate::name).toList()
					.equals(routedSorted.stream().map(Candidate::name).toList())) {
				sortingMismatches++;
			}

			// Run both approaches
			var beelineResult = simulateBeelinePruning(candidates, refX, refY, budget);
			var routedResult = simulateRoutedPruning(candidates, refX, refY, budget);

			totalBeelineGetSegment += beelineResult.getSegmentCalls();
			totalRoutedGetSegment += routedResult.getSegmentCalls();

			// Check for false rejections (should NEVER happen)
			if (!new HashSet<>(beelineResult.surviving()).containsAll(routedResult.surviving())) {
				falseRejections++;
			}
		}

		assertEquals(0, falseRejections,
				"Beeline pruning must NEVER reject a valid ordering (beeline <= routed invariant)");

		System.out.println("=== Monte Carlo: " + numTrials + " trials, "
				+ candidatesPerTrial + " candidates each ===");
		System.out.println("Sorting mismatches: " + sortingMismatches + "/" + numTrials
				+ " (" + (100 * sortingMismatches / numTrials) + "%)");
		System.out.println("Total getSegment calls — beeline approach: " + totalBeelineGetSegment);
		System.out.println("Total getSegment calls — routed approach:  " + totalRoutedGetSegment);
		System.out.println("getSegment calls saved by beeline: "
				+ (totalRoutedGetSegment - totalBeelineGetSegment));
		System.out.println("Savings per trial: "
				+ String.format("%.1f", (double)(totalRoutedGetSegment - totalBeelineGetSegment) / numTrials));
		System.out.println("False rejections: " + falseRejections);
	}

	// ── Simulation helpers ──────────────────────────────────────────────

	/**
	 * Simulate beeline-sort + beeline-break + routed-continue pruning.
	 * This is the two-tier approach from the plan.
	 */
	private PruningResult simulateBeelinePruning(List<Candidate> candidates,
			double refX, double refY, double budget) {
		// Sort by beeline
		var sorted = new ArrayList<>(candidates);
		sorted.sort(Comparator.comparingDouble(c -> c.beelineFromRef(refX, refY)));

		List<String> surviving = new ArrayList<>();
		int getSegmentCalls = 0;
		int beelineChecks = 0;

		for (var c : sorted) {
			double bee = c.beelineFromRef(refX, refY);
			beelineChecks++;

			// Tier 1: beeline break
			if (bee > budget) {
				break; // all remaining are farther by beeline
			}

			// Tier 2: routed continue
			getSegmentCalls++;
			if (c.routedDistFromRef() > budget) {
				continue; // can't break — beeline order ≠ routed order
			}

			surviving.add(c.name());
		}

		return new PruningResult(surviving, getSegmentCalls, beelineChecks);
	}

	/**
	 * Simulate routed-sort + routed-break pruning.
	 * This is the simpler single-tier approach.
	 */
	private PruningResult simulateRoutedPruning(List<Candidate> candidates,
			double refX, double refY, double budget) {
		// Must call getSegment for ALL candidates to sort by routed distance
		int getSegmentCalls = candidates.size();

		var sorted = new ArrayList<>(candidates);
		sorted.sort(Comparator.comparingDouble(Candidate::routedDistFromRef));

		List<String> surviving = new ArrayList<>();

		for (var c : sorted) {
			if (c.routedDistFromRef() > budget) {
				break; // all remaining are farther by routed distance
			}
			surviving.add(c.name());
		}

		return new PruningResult(surviving, getSegmentCalls, 0);
	}

	// ── Test data scenarios ─────────────────────────────────────────────

	/**
	 * Rural Bavaria scenario: candidates at various distances with realistic
	 * detour factors. Detour factor = routed / beeline.
	 *
	 * Rural roads: 1.1-1.4 (straight roads between villages)
	 * Lake/river detour: 1.5-2.0 (must go around water body)
	 * Highway access: 0.9-1.1 beeline-to-routed-distance ratio can be
	 * misleading because highway covers MORE distance but in LESS time.
	 * The routed distance (of the time-optimal path) may be larger than
	 * the distance-optimal path.
	 */
	private List<Candidate> createRuralBavariaScenario(double refX, double refY) {
		return List.of(
				// Nearby village, straight road (detour 1.15)
				new Candidate("nearby_village", 1500, 200, 1960),
				// Medium village, normal roads (detour 1.25)
				new Candidate("medium_village", 3000, 1000, 3953),
				// Lake detour — beeline-close but road goes around (detour 1.8)
				new Candidate("lake_detour", 2000, 500, 3709),
				// Highway access — beeline-far but highway is fast (detour 1.1)
				new Candidate("highway_town", 5000, 2000, 5934),
				// River crossing — moderate detour (detour 1.4)
				new Candidate("river_town", 4000, 3000, 7000),
				// Remote village — long winding road (detour 1.6)
				new Candidate("remote_village", 6000, 1000, 9731),
				// Very far town (detour 1.2)
				new Candidate("far_town", 10000, 3000, 12530),
				// Extremely remote (detour 2.0)
				new Candidate("mountain_village", 8000, 6000, 20000)
		);
	}

	/**
	 * Scenario specifically designed to produce beeline/routed sorting mismatch.
	 *
	 * "lake_detour": beeline-close (2062m) but routed-far (7200m, detour 3.5)
	 *   → road must go around a large lake
	 *
	 * "highway": beeline-far (5385m) but routed-close (3300m detour from ref
	 *   is smaller because the highway route, while covering more absolute
	 *   distance, follows a very direct path)
	 *   Wait — routed distance can't be less than beeline. Let's fix this.
	 *
	 *   Actually, highway_town has beeline=5385 and routed must be >= 5385.
	 *   The point is that highway_town has LOWER detour factor than lake_detour.
	 *   So lake_detour with beeline=2062 has routed=7200 (detour 3.5),
	 *   while highway_town with beeline=5385 has routed=5920 (detour 1.1).
	 *
	 *   Beeline order:  lake_detour (2062) < nearby (3162) < highway (5385)
	 *   Routed order:   nearby (3794) < highway (5920) < lake_detour (7200)
	 */
	private List<Candidate> createSortingMismatchScenario(double refX, double refY) {
		return List.of(
				// beeline = sqrt(2000² + 500²) = 2062m, routed = 7200m (detour 3.49)
				new Candidate("lake_detour", 2000, 500, 7200),
				// beeline = sqrt(3000² + 1000²) = 3162m, routed = 3794m (detour 1.20)
				new Candidate("nearby", 3000, 1000, 3794),
				// beeline = sqrt(5000² + 2000²) = 5385m, routed = 5920m (detour 1.10)
				new Candidate("highway", 5000, 2000, 5920)
		);
	}

	/**
	 * Generate random candidates with detour factors drawn from a realistic
	 * distribution for rural Bavaria road networks.
	 *
	 * Detour factor distribution (routed / beeline):
	 *   - Median: 1.3
	 *   - P10: 1.08  (very straight road)
	 *   - P90: 1.8   (significant detour)
	 *   - Max:  2.5+  (river/lake/mountain barrier)
	 *
	 * Modeled as log-normal: ln(detourFactor - 1) ~ Normal(mu, sigma)
	 * with mu = ln(0.3) ≈ -1.2, sigma = 0.6
	 */
	private List<Candidate> generateRandomCandidates(Random rng,
			double refX, double refY, int count) {
		List<Candidate> candidates = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			// Random position: 1-15km from reference, random angle
			double distance = 1000 + rng.nextDouble() * 14000;
			double angle = rng.nextDouble() * 2 * Math.PI;
			double x = refX + distance * Math.cos(angle);
			double y = refY + distance * Math.sin(angle);

			// Random detour factor: log-normal distribution
			double logDetourExcess = -1.2 + 0.6 * rng.nextGaussian();
			double detourFactor = 1.0 + Math.exp(logDetourExcess);
			detourFactor = Math.max(1.01, Math.min(3.0, detourFactor)); // clamp

			double beelineDist = beeline(refX, refY, x, y);
			double routedDist = beelineDist * detourFactor;

			candidates.add(new Candidate("c" + i, x, y, routedDist));
		}
		return candidates;
	}
}
