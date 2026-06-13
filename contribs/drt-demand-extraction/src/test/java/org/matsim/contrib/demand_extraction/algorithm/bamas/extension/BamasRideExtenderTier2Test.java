package org.matsim.contrib.demand_extraction.algorithm.bamas.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.demand_extraction.algorithm.bamas.extension.BamasRideExtender.ParentView;
import org.matsim.contrib.demand_extraction.algorithm.bamas.extension.BamasRideExtender.RideParentView;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideKind;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;
import org.matsim.contrib.demand_extraction.algorithm.graph.ShareabilityGraph;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCacheTestFixture;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;
import org.matsim.contrib.demand_extraction.demand.RequestResolver;
import org.matsim.contrib.demand_extraction.scoring.DemandExtractionScoringAdapter;
import org.matsim.contrib.demand_extraction.scoring.TripScoreRequest;
import org.matsim.contrib.demand_extraction.scoring.TripScoreResult;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;

/**
 * Task A3 — marked parents win the canonical claim under tier-2, and the extender
 * passes the claiming parent's first-valid node cap into enumeration.
 *
 * <p>Built on the same three-request degree-2→3 topology as
 * {@link KelheimMissingTripleRegressionTest}: requests {0,1,2} form a triple
 * reachable from all three FIFO pairs, so multiple parents reach the same child
 * set {0,1,2} and the claim/cap wiring can be exercised end-to-end through
 * {@link BamasRideExtender#extendParents}.
 *
 * <p>Distance ordering of the three pairs (rideDistance = round(sum(connDist)*10)/10):
 * <ul>
 *   <li>pair(2,0): 13868.00 + 8279.51 + 12535.99 = 34683.50  (smallest)</li>
 *   <li>pair(2,1):  5151.56 + 25370.64 +  5508.10 = 36030.30</li>
 *   <li>pair(1,0): 18714.37 + 10788.26 +  9198.37 = 38701.00  (largest)</li>
 * </ul>
 * The distance ordering is asserted in-test (precondition lines) rather than
 * assumed, so a fixture change surfaces loudly instead of silently making a test
 * vacuous.
 */
class BamasRideExtenderTier2Test {

	@AfterEach
	void resetAfterFirstValidCap() {
		// Sibling Design-A static cap must stay off; extendParents sets it from config
		// (0 here) but reset defensively so no other test inherits a non-zero value.
		EnumerationStats.setMaxOrderingNodesAfterFirstValid(0);
	}

	// ── Test 1: claiming (marked, cap 0) parent's cap governs the search ──────────
	// The MARKED parent is also the distance-tighter seeder. A second, looser parent
	// carries cap=1 (which would bite if it were the claimant). The ride IS produced
	// because the marked parent claims at cap 0 (unbounded).
	@Test
	void markedTighterSeederClaimsAtCapZero_rideProduced() {
		TestSetup s = buildKelheimMissingTripleSetup();

		ParentView markedTight = parent(s.pairSmallest, 0L);   // pair(2,0), distance 34683.5
		ParentView unmarkedLoose = parent(s.pairLargest, 1L);  // pair(1,0), distance 38701.0

		assertTrue(markedTight.rideDistance() < unmarkedLoose.rideDistance(),
				"precondition: marked parent must be the distance-tighter seeder");

		List<Ride> extended = newExtender(s).extendParents(
				new ArrayList<>(List.of(markedTight, unmarkedLoose)), 3, 100);

		assertTrue(containsRequestSet(extended, 0, 1, 2),
				"marked cap-0 parent claims and runs unbounded → triple produced");
	}

	// ── Test 2 (critical regression): unmarked parent is the TIGHTER seeder ───────
	// The unmarked parent (cap=1) is distance-SMALLER than the marked one. Without
	// marked-first, the unmarked parent claims, cap=1 abandons before first-valid,
	// and the triple is DROPPED. With marked-first (anyCapped → marked primary), the
	// marked cap-0 parent claims despite being the looser seeder → ride survives.
	//
	// Self-validating negative control proves cap=1 actually bites for this fixture
	// (no silent pass): the unmarked-only pool yields NO ride.
	@Test
	void unmarkedTighterSeeder_markedStillWinsClaim_rideProduced() {
		// Negative control: unmarked tighter parent alone, cap=1 → set abandoned.
		{
			TestSetup nc = buildKelheimMissingTripleSetup();
			ParentView unmarkedTightAlone = parent(nc.pairSmallest, 1L);
			List<Ride> none = newExtender(nc).extendParents(
					new ArrayList<>(List.of(unmarkedTightAlone)), 3, 100);
			assertFalse(containsRequestSet(none, 0, 1, 2),
					"negative control: cap=1 must abandon the set (proves the cap bites)");
		}

		// Rescue: add a MARKED looser parent reaching the same set. Marked-first means
		// the marked cap-0 parent claims even though it is the distance-looser seeder.
		TestSetup s = buildKelheimMissingTripleSetup();
		ParentView unmarkedTight = parent(s.pairSmallest, 1L);  // distance 34683.5, cap=1
		ParentView markedLoose = parent(s.pairLargest, 0L);     // distance 38701.0, cap=0

		assertTrue(unmarkedTight.rideDistance() < markedLoose.rideDistance(),
				"precondition: the UNMARKED parent must be the distance-tighter seeder "
						+ "(else marked-first is never exercised — marked would win on distance alone)");

		List<Ride> extended = newExtender(s).extendParents(
				new ArrayList<>(List.of(unmarkedTight, markedLoose)), 3, 100);

		assertTrue(containsRequestSet(extended, 0, 1, 2),
				"marked-first: looser marked cap-0 parent must win the claim and produce the triple");
	}

	// ── Test 3: a set reachable ONLY from a capped unmarked parent is capped ──────
	@Test
	void unmarkedOnlySetIsCapped_noRide() {
		TestSetup s = buildKelheimMissingTripleSetup();
		ParentView unmarkedOnly = parent(s.pairSmallest, 1L); // cap=1 only parent for {0,1,2}

		List<Ride> extended = newExtender(s).extendParents(
				new ArrayList<>(List.of(unmarkedOnly)), 3, 100);

		assertFalse(containsRequestSet(extended, 0, 1, 2),
				"unmarked-only set with cap=1 below nodes-to-first-valid → no ride");
	}

	// ── Test 4: OFF-path claim order unchanged (anyCapped=false) ──────────────────
	// With every cap 0, compareParentClaimKey(false, ...) must return the exact same
	// sign as the pre-change pure compareParentCanonicalKey on the same inputs. This
	// pins that the OFF path keeps today's canonical parent choice byte-for-byte.
	@Test
	void offPathClaimOrderMatchesCanonicalKeyExactly() {
		// A fixed mixed pool of (distance, sortedIndices) pairs covering the relevant
		// cases: strict distance order, EPSILON tie → lex, and full equality.
		double[] dists = {34683.5, 36030.3, 38701.0, 34683.5, 34683.5 + 5e-10};
		int[][] idx = {
				{0, 2}, {1, 2}, {0, 1}, {0, 1}, {0, 3}
		};

		for (int i = 0; i < dists.length; i++) {
			for (int j = 0; j < dists.length; j++) {
				int claim = BamasRideExtender.compareParentClaimKey(
						/* anyCapped= */ false,
						0L, dists[i], idx[i],
						0L, dists[j], idx[j]);
				int canonical = BamasRideExtender.compareParentCanonicalKey(
						dists[i], idx[i], dists[j], idx[j]);
				assertEquals(Integer.signum(canonical), Integer.signum(claim),
						"OFF path (anyCapped=false) must match compareParentCanonicalKey sign "
								+ "for pair (" + i + "," + j + ")");
			}
		}

		// Belt-and-suspenders: when anyCapped=true, a marked (cap 0) parent must sort
		// before an unmarked (cap > 0) one even when it is the distance-looser seeder.
		int markedBeatsUnmarked = BamasRideExtender.compareParentClaimKey(
				/* anyCapped= */ true,
				0L, /* big dist */ 38701.0, new int[] {0, 1},
				1L, /* small dist */ 34683.5, new int[] {0, 2});
		assertTrue(markedBeatsUnmarked < 0,
				"anyCapped=true: marked (cap 0) sorts before unmarked (cap>0) regardless of distance");
	}

	// ─────────────────────────────── helpers ────────────────────────────────────

	private static BamasRideExtender newExtender(TestSetup s) {
		return new BamasRideExtender(
				s.network, s.graph, s.budgetValidator,
				new RequestResolver(s.requests), s.config);
	}

	private static ParentView parent(Ride pairRide, long cap) {
		return new RideParentView(pairRide, cap);
	}

	private static boolean containsRequestSet(List<Ride> rides, int... requestIndices) {
		int[] expected = requestIndices.clone();
		Arrays.sort(expected);
		for (Ride ride : rides) {
			int[] actual = ride.getRequestIndices().clone();
			Arrays.sort(actual);
			if (Arrays.equals(expected, actual)) {
				return true;
			}
		}
		return false;
	}

	// Fixture mirrors KelheimMissingTripleRegressionTest's three-pair / one-triple
	// topology. pairSmallest/pairLargest are exposed by distance so tests can pick
	// the tighter/looser seeder deliberately.
	private static TestSetup buildKelheimMissingTripleSetup() {
		DrtRequest request6 = request(0, 35400.0, 6047.0, 24242.04, 33889.73, 42957.27, 9067.55,
				9.7235, -20.6417, 0.0, 0.0, 0.0, 10000.0);
		DrtRequest request33 = request(1, 35520.0, 1454.0, 28038.93, 35157.92, 37336.08, 2178.16,
				5.2841, -7.9094, 1000.0, 0.0, 1000.0, 10000.0);
		DrtRequest request81 = request(2, 35280.0, 1295.0, 21098.13, 34957.51, 36897.49, 1939.97,
				4.1266, -6.4648, 2000.0, 0.0, 2000.0, 10000.0);

		List<DrtRequest> requests = List.of(request6, request33, request81);

		MatsimNetworkCache network = MatsimNetworkCacheTestFixture.create();
		Id<Link> o0 = request6.originLinkId;
		Id<Link> o1 = request33.originLinkId;
		Id<Link> o2 = request81.originLinkId;
		Id<Link> d0 = request6.destinationLinkId;
		Id<Link> d1 = request33.destinationLinkId;
		Id<Link> d2 = request81.destinationLinkId;

		// Pair [81 | 33] FIFO
		put(network, o2, o1, 438.73, 5151.56);
		put(network, o1, d2, 1287.15, 25370.64);
		put(network, d2, d1, 397.42, 5508.10);

		// Pair [81 | 6] FIFO
		put(network, o2, o0, 780.10, 13868.00);
		put(network, o0, d2, 465.98, 8279.51);
		put(network, d2, d0, 703.62, 12535.99);

		// Pair [33 | 6] FIFO
		put(network, o1, o0, 958.96, 18714.37);
		put(network, o0, d1, 554.70, 10788.26);
		put(network, d1, d0, 480.94, 9198.37);

		// Reverse / cross legs: large but reachable so any incidental lookup resolves.
		put(network, o0, o1, 5000.0, 50000.0);
		put(network, o0, o2, 5000.0, 50000.0);
		put(network, o1, o2, 5000.0, 50000.0);
		put(network, d0, d1, 5000.0, 50000.0);
		put(network, d0, d2, 5000.0, 50000.0);
		put(network, d1, d2, 5000.0, 50000.0);
		put(network, o1, d0, 5000.0, 50000.0);
		put(network, o2, d0, 5000.0, 50000.0);
		put(network, o2, d1, 5000.0, 50000.0);
		put(network, d0, o0, 5000.0, 50000.0);
		put(network, d0, o1, 5000.0, 50000.0);
		put(network, d0, o2, 5000.0, 50000.0);
		put(network, d1, o0, 5000.0, 50000.0);
		put(network, d1, o1, 5000.0, 50000.0);
		put(network, d1, o2, 5000.0, 50000.0);
		put(network, d2, o0, 5000.0, 50000.0);
		put(network, d2, o1, 5000.0, 50000.0);
		put(network, d2, o2, 5000.0, 50000.0);

		Ride pair21 = pairRide(0, request81, request33,
				1725.88, 1684.57, 30522.20, 30878.74,
				new double[] { 438.73, 1287.15, 397.42 },
				new double[] { 5151.56, 25370.64, 5508.10 });   // distance 36030.3

		Ride pair20 = pairRide(1, request81, request6,
				1295.00, 6047.00, 22147.51, 20815.50,
				new double[] { 780.10, 465.98, 703.62 },
				new double[] { 13868.00, 8279.51, 12535.99 });  // distance 34683.5 (smallest)

		Ride pair10 = pairRide(2, request33, request6,
				1513.66, 6047.00, 29502.63, 19986.63,
				new double[] { 958.96, 554.70, 480.94 },
				new double[] { 18714.37, 10788.26, 9198.37 });  // distance 38701.0 (largest)

		ShareabilityGraph.Builder builder = ShareabilityGraph.builder(3);
		builder.addEdge(request81.index, request33.index, pair21.getIndex(), ShareabilityGraph.KIND_FIFO);
		builder.addEdge(request81.index, request6.index, pair20.getIndex(), ShareabilityGraph.KIND_FIFO);
		builder.addEdge(request33.index, request6.index, pair10.getIndex(), ShareabilityGraph.KIND_FIFO);

		ExMasConfigGroup config = new ExMasConfigGroup();
		config.setAlgorithmProcessCount(1);
		config.setPruningDistanceSavingsLogScale(-1.0);

		return new TestSetup(
				requests,
				builder.build(),
				network,
				config,
				new PassThroughBudgetValidator(),
				pair20,   // pairSmallest (distance 34683.5)
				pair10);  // pairLargest  (distance 38701.0)
	}

	private static DrtRequest request(
			int index, double requestTime, double directTravelTime, double directDistance,
			double earliestDeparture, double latestArrival, double maxTravelTime,
			double budget, double baseModeScore,
			double originX, double originY, double destinationX, double destinationY) {
		return DrtRequest.builder()
				.index(index)
				.personId(Id.create("p" + index, Person.class))
				.groupId("g" + index)
				.tripIndex(0)
				.isCommute(true)
				.isEducation(false)
				.budget(budget)
				.bestModeScore(baseModeScore)
				.bestMode("car")
				.originLinkId(Id.createLinkId("O" + index))
				.destinationLinkId(Id.createLinkId("D" + index))
				.originX(originX)
				.originY(originY)
				.destinationX(destinationX)
				.destinationY(destinationY)
				.originLinkCoordFromX(originX)
				.originLinkCoordFromY(originY)
				.originLinkCoordToX(originX)
				.originLinkCoordToY(originY)
				.destinationLinkCoordFromX(destinationX)
				.destinationLinkCoordFromY(destinationY)
				.destinationLinkCoordToX(destinationX)
				.destinationLinkCoordToY(destinationY)
				.requestTime(requestTime)
				.earliestDeparture(earliestDeparture)
				.latestArrival(latestArrival)
				.directTravelTime(directTravelTime)
				.directDistance(directDistance)
				.maxDetourFactor(maxTravelTime / directTravelTime)
				.maxWalkDistance(0.0)
				.originActivityType("home")
				.destinationActivityType("work")
				.carTravelTime(directTravelTime)
				.ptTravelTime(directTravelTime * 2.0)
				.ptAccessibility(1.0)
				.build();
	}

	private static Ride pairRide(
			int index, DrtRequest first, DrtRequest second,
			double firstPassengerTime, double secondPassengerTime,
			double firstPassengerDistance, double secondPassengerDistance,
			double[] connectionTravelTimes, double[] connectionDistances) {
		return Ride.builder()
				.index(index)
				.degree(2)
				.kind(RideKind.FIFO)
				.requests(new DrtRequest[] { first, second })
				.originsOrderedRequests(new DrtRequest[] { first, second })
				.destinationsOrderedRequests(new DrtRequest[] { first, second })
				.passengerTravelTimes(new double[] { firstPassengerTime, secondPassengerTime })
				.passengerDistances(new double[] { firstPassengerDistance, secondPassengerDistance })
				.passengerNetworkUtilities(new double[] { 0.0, 0.0 })
				.delays(new double[] { 0.0, 0.0 })
				.detours(new double[] {
						Math.max(1.0, firstPassengerTime / first.getTravelTime()),
						Math.max(1.0, secondPassengerTime / second.getTravelTime()) })
				.remainingBudgets(new double[] { 0.0, 0.0 })
				.maxCosts(new double[] { 0.0, 0.0 })
				.maxCostsPerKm(new double[] { 0.0, 0.0 })
				.connectionTravelTimes(connectionTravelTimes)
				.connectionDistances(connectionDistances)
				.connectionNetworkUtilities(new double[] { 0.0, 0.0, 0.0 })
				.startTime(first.getRequestTime())
				.build();
	}

	private static void put(MatsimNetworkCache cache, Id<Link> from, Id<Link> to, double time, double distance) {
		MatsimNetworkCacheTestFixture.put(cache, from, to, new TravelSegment(time, distance, 0.0));
	}

	private record TestSetup(
			List<DrtRequest> requests,
			ShareabilityGraph graph,
			MatsimNetworkCache network,
			ExMasConfigGroup config,
			BudgetValidator budgetValidator,
			Ride pairSmallest,
			Ride pairLargest) {}

	private static final class PassThroughBudgetValidator extends BudgetValidator {
		private PassThroughBudgetValidator() {
			super(new NoOpScoringAdapter(), noOpScoringParameters(), new ExMasConfigGroup(), dummyConfig());
		}

		@Override
		public Ride validateAndPopulateBudgets(Ride ride) {
			return ride.toBuilder().remainingBudgets(new double[ride.getDegree()]).build();
		}
	}

	private static Config dummyConfig() {
		Config config = ConfigUtils.createConfig();
		config.addModule(new ExMasConfigGroup());
		return config;
	}

	private static ScoringParametersForPerson noOpScoringParameters() {
		return person -> null;
	}

	private static final class NoOpScoringAdapter implements DemandExtractionScoringAdapter {
		@Override
		public String getName() {
			return "noop";
		}

		@Override
		public TripScoreResult scoreTrip(TripScoreRequest request) {
			throw new UnsupportedOperationException("Not used in pass-through validator");
		}

		@Override
		public double getMarginalUtilityOfMoney(Person person, double euclideanDistance_km) {
			return 1.0;
		}

		@Override
		public boolean includesOpportunityCost() {
			return false;
		}

		@Override
		public boolean supportsDistanceSpecificMoneyUtility() {
			return false;
		}
	}
}
