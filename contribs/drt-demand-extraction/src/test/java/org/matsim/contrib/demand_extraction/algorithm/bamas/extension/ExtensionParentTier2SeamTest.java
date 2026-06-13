package org.matsim.contrib.demand_extraction.algorithm.bamas.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.demand_extraction.algorithm.bamas.ride.OrderingCodec;
import org.matsim.contrib.demand_extraction.algorithm.bamas.ride.RideLayer;
import org.matsim.contrib.demand_extraction.algorithm.bamas.ride.RideMetricScaling;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;
import org.matsim.contrib.demand_extraction.algorithm.graph.ShareabilityGraph;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCacheTestFixture;
import org.matsim.contrib.demand_extraction.algorithm.selection.RideLayerSelection;
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
 * Task A4 — engine tier-2 seam wiring. Two behaviours are pinned:
 *
 * <ol>
 *   <li>{@link RideLayerSelection#markParents} returns exactly the rows
 *       {@link RideLayerSelection#filterParents} keeps (the refactor that exposes the marked
 *       set without materializing the filtered layer is behaviour-preserving).</li>
 *   <li>The new {@link BamasRideExtender#extendRides(RideLayer, DrtRequest[], int, IntSet, long)}
 *       mixed-cap overload: with every row marked it equals the 3-arg (all-cap-0) output; with
 *       NO row marked and a tiny cap, previously-produced sets drop (the per-row
 *       {@code contains(row) ? 0 : cap} wiring actually feeds the cap into enumeration).</li>
 * </ol>
 */
class ExtensionParentTier2SeamTest {

	@AfterEach
	void resetAfterFirstValidCap() {
		// extendParents sets the sibling Design-A static cap from config (0 here); reset
		// defensively so no other test inherits a non-zero value.
		EnumerationStats.setMaxOrderingNodesAfterFirstValid(0);
	}

	// ── Test 1: markParents == rows filterParents keeps (compared by request-set content) ──
	// filterParents re-indexes survivors via copyRow, so the filtered layer's row POSITIONS are
	// not the original marked indices. Compare by requestIndices() content instead — a clean
	// bijection because each row's request set is unique.
	@Test
	void markParentsEqualsFilterParentsKeptRows() {
		RideLayer parents = selectionFixture();
		Map<Integer, DrtRequest> requestById = selectionRequestsById();

		int k = 1;
		ExMasConfigGroup.PruningQualityMetric metric = ExMasConfigGroup.PruningQualityMetric.OP_COST_PER_PAX;
		ExMasConfigGroup.ExtensionParentsSelectionRule rule = ExMasConfigGroup.ExtensionParentsSelectionRule.TOP_K;

		IntOpenHashSet marked = RideLayerSelection.markParents(parents, requestById, k, metric, rule, 0.0);
		RideLayer filtered = RideLayerSelection.filterParents(parents, requestById, k, metric, rule, 0.0);

		assertEquals(filtered.size(), marked.size(),
				"marked count must equal kept-layer size");

		Set<String> markedSets = new HashSet<>();
		for (int row : marked) markedSets.add(Arrays.toString(parents.requestIndices(row)));

		Set<String> keptSets = new HashSet<>();
		for (int row = 0; row < filtered.size(); row++) keptSets.add(Arrays.toString(filtered.requestIndices(row)));

		assertEquals(keptSets, markedSets,
				"markParents must mark exactly the request sets filterParents keeps");
	}

	// ── Test 2: mixed-cap overload semantics (equivalence + drop) ─────────────────────────
	// Kelheim triple topology as a RideLayer(2): three FIFO pairs all reach child set {0,1,2}.
	//   (a) ALL rows marked (cap 0)  ⇒ identical to the 3-arg overload (all caps 0).
	//   (b) NO rows marked + tiny cap ⇒ the triple, produced under (a)/3-arg, drops (cap bites).
	@Test
	void mixedCapOverloadEquivalenceAndDrop() {
		// (a) Equivalence: full mark set ⇒ all cap 0 ⇒ identical to the 3-arg path.
		{
			TestSetup s = buildKelheimMissingTripleSetup();
			RideLayer layer = pairLayer(s);

			List<Ride> threeArg = newExtender(s).extendRides(layer, reqArray(s), 100);

			IntOpenHashSet allMarked = new IntOpenHashSet();
			for (int row = 0; row < layer.size(); row++) allMarked.add(row);
			List<Ride> allMarkedOut = newExtender(s).extendRides(
					layer, reqArray(s), 100, allMarked, /* cap */ 1L);

			assertEquals(setContents(threeArg), setContents(allMarkedOut),
					"all-rows-marked (cap 0) must equal the 3-arg all-cap-0 output");
			assertTrue(containsRequestSet(threeArg, 0, 1, 2),
					"precondition: the 3-arg path produces the triple (else the drop test is vacuous)");
		}

		// (b) Drop: empty mark set + tiny cap ⇒ every row capped ⇒ the triple drops.
		{
			TestSetup s = buildKelheimMissingTripleSetup();
			RideLayer layer = pairLayer(s);

			List<Ride> capped = newExtender(s).extendRides(
					layer, reqArray(s), 100, IntSets.emptySet(), /* tiny cap */ 1L);

			assertFalse(containsRequestSet(capped, 0, 1, 2),
					"no row marked + cap=1 below nodes-to-first-valid ⇒ triple drops");
			// Belt-and-suspenders: capped output strictly differs from the uncapped 3-arg run.
			List<Ride> threeArg = newExtender(s).extendRides(layer, reqArray(s), 100);
			assertNotEquals(setContents(threeArg), setContents(capped),
					"empty-marked tiny-cap output must differ from the uncapped run");
		}
	}

	// ─────────────────────────── test-1 fixture (selection layer) ────────────────────────
	// Mirrors RideLayerSelectionTest.fixture(): degree-3, 4 rows, every member directDistance
	// 1000 m, ride distances increasing with row index so OP_COST_PER_PAX ranks 0<1<2<3.
	private static RideLayer selectionFixture() {
		RideLayer cols = new RideLayer(3);
		long ord = OrderingCodec.pack(new int[]{0, 1, 2});
		cols.addRow(new int[]{0, 1, 2}, ord, ord, RideMetricScaling.toDeci(1500.0), 0, (byte) 0);
		cols.addRow(new int[]{0, 1, 3}, ord, ord, RideMetricScaling.toDeci(2000.0), 0, (byte) 0);
		cols.addRow(new int[]{2, 4, 5}, ord, ord, RideMetricScaling.toDeci(2500.0), 0, (byte) 0);
		cols.addRow(new int[]{0, 1, 4}, ord, ord, RideMetricScaling.toDeci(2800.0), 0, (byte) 0);
		return cols;
	}

	private static Map<Integer, DrtRequest> selectionRequestsById() {
		Map<Integer, DrtRequest> m = new HashMap<>();
		for (int i = 0; i <= 5; i++) m.put(i, simpleRequest(i, 1000.0));
		return m;
	}

	private static DrtRequest simpleRequest(int index, double directDistance) {
		return DrtRequest.builder()
				.index(index)
				.personId(Id.createPersonId("p" + index))
				.requestTime(0.0)
				.directTravelTime(0.0)
				.directDistance(directDistance)
				.earliestDeparture(0.0)
				.latestArrival(1.0)
				.maxDetourFactor(1.5)
				.build();
	}

	// ─────────────────────────── test-2 fixture (routable layer) ─────────────────────────

	// Build the degree-2 RideLayer for the three Kelheim pairs. Each pair is FIFO [first, second]
	// over its 2-request sorted set; pickup/dropoff local order is [1,0] (the higher-index member
	// is picked up first in all three pairs of this fixture). Ride distances in dm match the A3
	// fixture so the smallest (pair 2,0) is the canonical seeder.
	private static RideLayer pairLayer(TestSetup s) {
		RideLayer cols = new RideLayer(2);
		long order10 = OrderingCodec.pack(new int[]{1, 0});  // first pickup = local pos 1, then 0
		// pair(2,1): sorted {1,2}, pickup [req2,req1] = locals [1,0], distance 36030.3
		cols.addRow(new int[]{1, 2}, order10, order10, RideMetricScaling.toDeci(36030.3), 0, (byte) 0);
		// pair(2,0): sorted {0,2}, pickup [req2,req0] = locals [1,0], distance 34683.5 (smallest)
		cols.addRow(new int[]{0, 2}, order10, order10, RideMetricScaling.toDeci(34683.5), 0, (byte) 0);
		// pair(1,0): sorted {0,1}, pickup [req1,req0] = locals [1,0], distance 38701.0 (largest)
		cols.addRow(new int[]{0, 1}, order10, order10, RideMetricScaling.toDeci(38701.0), 0, (byte) 0);
		return cols;
	}

	private static DrtRequest[] reqArray(TestSetup s) {
		DrtRequest[] arr = new DrtRequest[s.requests.size()];
		for (DrtRequest r : s.requests) arr[r.index] = r;
		return arr;
	}

	private static BamasRideExtender newExtender(TestSetup s) {
		return new BamasRideExtender(
				s.network, s.graph, s.budgetValidator,
				new RequestResolver(s.requests), s.config);
	}

	private static Set<String> setContents(List<Ride> rides) {
		Set<String> out = new HashSet<>();
		for (Ride ride : rides) {
			int[] idx = ride.getRequestIndices().clone();
			Arrays.sort(idx);
			out.add(Arrays.toString(idx));
		}
		return out;
	}

	private static boolean containsRequestSet(List<Ride> rides, int... requestIndices) {
		int[] expected = requestIndices.clone();
		Arrays.sort(expected);
		for (Ride ride : rides) {
			int[] actual = ride.getRequestIndices().clone();
			Arrays.sort(actual);
			if (Arrays.equals(expected, actual)) return true;
		}
		return false;
	}

	// Fixture mirrors KelheimMissingTripleRegressionTest / BamasRideExtenderTier2Test: three
	// FIFO pairs over requests {0,1,2}, all reaching child set {0,1,2}.
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

		ShareabilityGraph.Builder builder = ShareabilityGraph.builder(3);
		builder.addEdge(request81.index, request33.index, 0, ShareabilityGraph.KIND_FIFO);
		builder.addEdge(request81.index, request6.index, 1, ShareabilityGraph.KIND_FIFO);
		builder.addEdge(request33.index, request6.index, 2, ShareabilityGraph.KIND_FIFO);

		ExMasConfigGroup config = new ExMasConfigGroup();
		config.setAlgorithmProcessCount(1);
		config.setPruningDistanceSavingsLogScale(-1.0);

		return new TestSetup(
				requests,
				builder.build(),
				network,
				config,
				new PassThroughBudgetValidator());
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

	private static void put(MatsimNetworkCache cache, Id<Link> from, Id<Link> to, double time, double distance) {
		MatsimNetworkCacheTestFixture.put(cache, from, to, new TravelSegment(time, distance, 0.0));
	}

	private record TestSetup(
			List<DrtRequest> requests,
			ShareabilityGraph graph,
			MatsimNetworkCache network,
			ExMasConfigGroup config,
			BudgetValidator budgetValidator) {}

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
