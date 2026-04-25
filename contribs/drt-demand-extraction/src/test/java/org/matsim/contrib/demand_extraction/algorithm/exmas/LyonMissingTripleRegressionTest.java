package org.matsim.contrib.demand_extraction.algorithm.exmas;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.demand_extraction.algorithm.bamas.extension.BamasRideExtender;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideKind;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;
import org.matsim.contrib.demand_extraction.algorithm.graph.ShareabilityGraph;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCacheTestFixture;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;
import org.matsim.contrib.demand_extraction.scoring.DemandExtractionScoringAdapter;
import org.matsim.contrib.demand_extraction.scoring.TripScoreRequest;
import org.matsim.contrib.demand_extraction.scoring.TripScoreResult;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;

/**
 * Reconstructs the Lyon {@code [0, 146, 551]} scenario where R1 (ExMAS reference) silently
 * missed the triple while R2 (BAMAS) found it.
 *
 * <p>Uses three requests 0/1/2 with times arranged so that the sorted-index {@code [0, 1, 2]}
 * does NOT match departure order — req 2 departs first, req 0 middle, req 1 latest. This mirrors
 * Lyon's sorted-index {@code [0, 146, 551]} where 551 departs first and 146 departs last.
 *
 * <p>Only the extension frame {@code base={0, 2} + added=1} is geometrically valid (candidate is
 * pickup-last). The other two frames ({@code base={1, 2}+0} and {@code base={0, 1}+2}) have no
 * compatible pair-ride support. A lo→hi edge normalization or an over-aggressive beeline pre-filter
 * would cause R1 to fail this test.
 */
class LyonMissingTripleRegressionTest {

	@Test
	void referenceFindsLyonTripleViaMiddleSortedFrame() {
		TestSetup setup = buildLyonMissingTripleSetup();

		ReferenceRideExtender reference = new ReferenceRideExtender(
				setup.network,
				setup.graph,
				setup.budgetValidator,
				setup.requests,
				setup.pairRides,
				setup.config);

		BamasRideExtender bamas = new BamasRideExtender(
				setup.network,
				setup.graph,
				setup.budgetValidator,
				setup.requests,
				setup.config);

		List<Ride> referenceExtended = reference.extendRides(setup.pairRides, 1000);
		List<Ride> bamasExtended = bamas.extendRides(setup.pairRides, 1000);

		assertTrue(
				containsRequestSet(referenceExtended, 0, 1, 2),
				"ReferenceRideExtender (R1) should find triple {0,1,2} via Frame base={0,2}+1");

		assertTrue(
				containsRequestSet(bamasExtended, 0, 1, 2),
				"BamasRideExtender (R2) should also find triple {0,1,2}");
	}

	private static boolean containsRequestSet(List<Ride> rides, int... requestIndices) {
		int[] expected = requestIndices.clone();
		Arrays.sort(expected);
		for (Ride ride : rides) {
			int[] actual = ride.getRequestIndices();
			Arrays.sort(actual);
			if (Arrays.equals(expected, actual)) {
				return true;
			}
		}
		return false;
	}

	private static TestSetup buildLyonMissingTripleSetup() {
		// Lyon analog: req 551 (idx 2) earliest time, req 0 (idx 0) middle, req 146 (idx 1) latest.
		// Time-sorted pickup order in the feasible triple: [2, 0, 1].
		DrtRequest request0 = request(0, 29310.0, 1700.0, 25000.0, 29000.0, 33000.0, 2500.0,
				100000.0, -20.0, 0.0, 0.0, 5000.0, 0.0);
		DrtRequest request1 = request(1, 29836.0, 650.0, 10000.0, 29500.0, 32000.0, 1500.0,
				100000.0, -20.0, 4000.0, 0.0, 6000.0, 0.0);
		DrtRequest request2 = request(2, 29023.0, 2820.0, 42000.0, 28700.0, 34000.0, 3500.0,
				100000.0, -20.0, -3000.0, 0.0, 3500.0, 0.0);

		List<DrtRequest> requests = List.of(request0, request1, request2);

		MatsimNetworkCache network = MatsimNetworkCacheTestFixture.create();
		Id<Link> o0 = request0.originLinkId, d0 = request0.destinationLinkId;
		Id<Link> o1 = request1.originLinkId, d1 = request1.destinationLinkId;
		Id<Link> o2 = request2.originLinkId, d2 = request2.destinationLinkId;

		// Pair (0, 1) — 0 pickup-first (t0 < t1). FIFO: O0 → O1 → D0 → D1.
		put(network, o0, o1, 200.0, 4000.0);
		put(network, o1, d0, 500.0, 7500.0);
		put(network, d0, d1, 250.0, 3800.0);

		// Pair (0, 2) — BOTH orderings feasible. Mirrors Lyon pair (0, 551) which had
		// rides 731 ([0|551] FIFO) AND 2654/2655 ([551|0] FIFO+LIFO).
		// Variant A: 2 pickup-first (natural time order, t2 < t0). FIFO: O2 → O0 → D2 → D0.
		put(network, o2, o0, 300.0, 5500.0);
		put(network, o0, d2, 700.0, 11000.0);
		put(network, d2, d0, 350.0, 5200.0);
		// Variant B: 0 pickup-first (req 2 waits at its origin). FIFO: O0 → O2 → D0 → D2.
		// Reuses d2→d0 and o0→d2 above; add o0→o2 and adjust for FIFO with 0 first.
		put(network, o0, o2, 350.0, 6000.0);
		put(network, o2, d0, 600.0, 9000.0);

		// Pair (1, 2) — 2 pickup-first (t2 < t1). FIFO: O2 → O1 → D2 → D1.
		put(network, o2, o1, 400.0, 6500.0);
		put(network, o1, d2, 450.0, 7000.0);
		put(network, d2, d1, 500.0, 7800.0);

		// Fill all remaining cross-legs with large-but-reachable placeholders so incidental
		// lookups never see a missing segment.
		put(network, o0, d1, 5000.0, 50000.0);
		put(network, o1, o0, 5000.0, 50000.0);
		put(network, o1, o2, 5000.0, 50000.0);
		put(network, o1, d1, 5000.0, 50000.0);
		put(network, o2, d1, 5000.0, 50000.0);
		put(network, o2, d2, 5000.0, 50000.0);
		put(network, d0, o0, 5000.0, 50000.0);
		put(network, d0, o1, 5000.0, 50000.0);
		put(network, d0, o2, 5000.0, 50000.0);
		put(network, d0, d2, 5000.0, 50000.0);
		put(network, d1, o0, 5000.0, 50000.0);
		put(network, d1, o1, 5000.0, 50000.0);
		put(network, d1, o2, 5000.0, 50000.0);
		put(network, d1, d0, 5000.0, 50000.0);
		put(network, d1, d2, 5000.0, 50000.0);
		put(network, d2, o0, 5000.0, 50000.0);
		put(network, d2, o1, 5000.0, 50000.0);
		put(network, d2, o2, 5000.0, 50000.0);

		// Pair rides: mirror Lyon's output exactly.
		Ride pair01 = pairRide(
				10, request0, request1, RideKind.FIFO,
				1700.0, 650.0,
				(200.0 + 500.0) + 250.0 + /*dummy pad*/ 0.0, // not meaningful in pass-through budget
				0.0,
				new double[] { 200.0, 500.0, 250.0 },
				new double[] { 4000.0, 7500.0, 3800.0 },
				request0.getRequestTime(),
				/*destReqs*/ new DrtRequest[] { request0, request1 });

		Ride pair02_2first = pairRide(
				11, request2, request0, RideKind.FIFO,
				2820.0, 1700.0, 0.0, 0.0,
				new double[] { 300.0, 700.0, 350.0 },
				new double[] { 5500.0, 11000.0, 5200.0 },
				request2.getRequestTime(),
				new DrtRequest[] { request2, request0 });

		Ride pair02_0first = pairRide(
				12, request0, request2, RideKind.FIFO,
				1700.0, 2820.0, 0.0, 0.0,
				new double[] { 350.0, 600.0, /*d0→d2 already = 5200 but we approximate*/ 5200.0 },
				new double[] { 6000.0, 9000.0, 5200.0 },
				request0.getRequestTime(),
				new DrtRequest[] { request0, request2 });

		Ride pair12 = pairRide(
				13, request2, request1, RideKind.FIFO,
				2820.0, 650.0, 0.0, 0.0,
				new double[] { 400.0, 450.0, 500.0 },
				new double[] { 6500.0, 7000.0, 7800.0 },
				request2.getRequestTime(),
				new DrtRequest[] { request2, request1 });

		ShareabilityGraph.Builder builder = ShareabilityGraph.builder(8);
		// Edge direction = pickup order (source = pickup-first).
		builder.addEdge(request0.index, request1.index, pair01.getIndex(), ShareabilityGraph.KIND_FIFO);
		builder.addEdge(request2.index, request0.index, pair02_2first.getIndex(), ShareabilityGraph.KIND_FIFO);
		builder.addEdge(request0.index, request2.index, pair02_0first.getIndex(), ShareabilityGraph.KIND_FIFO);
		builder.addEdge(request2.index, request1.index, pair12.getIndex(), ShareabilityGraph.KIND_FIFO);

		ExMasConfigGroup config = new ExMasConfigGroup();
		config.setAlgorithmProcessCount(1);
		config.setPruningDistanceSavingsLogScale(-1.0);

		return new TestSetup(
				requests,
				List.of(pair01, pair02_2first, pair02_0first, pair12),
				builder.build(),
				network,
				config,
				new PassThroughBudgetValidator());
	}

	private static DrtRequest request(
			int index,
			double requestTime,
			double directTravelTime,
			double directDistance,
			double earliestDeparture,
			double latestArrival,
			double maxTravelTime,
			double budget,
			double baseModeScore,
			double originX,
			double originY,
			double destinationX,
			double destinationY) {
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
			int index,
			DrtRequest first,
			DrtRequest second,
			RideKind kind,
			double firstPassengerTime,
			double secondPassengerTime,
			double firstPassengerDistance,
			double secondPassengerDistance,
			double[] connectionTravelTimes,
			double[] connectionDistances,
			double startTime,
			DrtRequest[] destinationsOrderedRequests) {
		return Ride.builder()
				.index(index)
				.degree(2)
				.kind(kind)
				.requests(new DrtRequest[] { first, second })
				.originsOrderedRequests(new DrtRequest[] { first, second })
				.destinationsOrderedRequests(destinationsOrderedRequests)
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
				.startTime(startTime)
				.build();
	}

	private static void put(MatsimNetworkCache cache, Id<Link> from, Id<Link> to, double time, double distance) {
		MatsimNetworkCacheTestFixture.put(cache, from, to, new TravelSegment(time, distance, 0.0));
	}

	private record TestSetup(
			List<DrtRequest> requests,
			List<Ride> pairRides,
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
