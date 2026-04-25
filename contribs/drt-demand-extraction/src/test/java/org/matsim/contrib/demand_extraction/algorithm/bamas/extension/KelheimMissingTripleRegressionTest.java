package org.matsim.contrib.demand_extraction.algorithm.bamas.extension;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideKind;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;
import org.matsim.contrib.demand_extraction.algorithm.exmas.ReferenceRideExtender;
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

class KelheimMissingTripleRegressionTest {

	@Test
	void referenceFindsKelheimTripleButBamasMissesItOnReconstructedPairTopology() {
		TestSetup setup = buildKelheimMissingTripleSetup();

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

		List<Ride> referenceExtended = reference.extendRides(setup.pairRides, 100);
		List<Ride> bamasExtended = bamas.extendRides(setup.pairRides, 100);

		assertTrue(
				containsRequestSet(referenceExtended, 0, 1, 2),
				"ReferenceRideExtender should reconstruct the Kelheim-missing triple from the same pair support");

		assertTrue(
				containsRequestSet(bamasExtended, 0, 1, 2),
				"BamasRideExtender should also reconstruct the same triple from the same pair support");
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

		// Fill reverse / cross legs with large but reachable segments so the
		// network cache is complete for any incidental lookup outside the intended path.
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

		Ride pair21 = pairRide(
				0,
				request81,
				request33,
				1725.88,
				1684.57,
				30522.20,
				30878.74,
				new double[] { 438.73, 1287.15, 397.42 },
				new double[] { 5151.56, 25370.64, 5508.10 });

		Ride pair20 = pairRide(
				1,
				request81,
				request6,
				1295.00,
				6047.00,
				22147.51,
				20815.50,
				new double[] { 780.10, 465.98, 703.62 },
				new double[] { 13868.00, 8279.51, 12535.99 });

		Ride pair10 = pairRide(
				2,
				request33,
				request6,
				1513.66,
				6047.00,
				29502.63,
				19986.63,
				new double[] { 958.96, 554.70, 480.94 },
				new double[] { 18714.37, 10788.26, 9198.37 });

		ShareabilityGraph.Builder builder = ShareabilityGraph.builder(3);
		builder.addEdge(request81.index, request33.index, pair21.getIndex(), ShareabilityGraph.KIND_FIFO);
		builder.addEdge(request81.index, request6.index, pair20.getIndex(), ShareabilityGraph.KIND_FIFO);
		builder.addEdge(request33.index, request6.index, pair10.getIndex(), ShareabilityGraph.KIND_FIFO);

		ExMasConfigGroup config = new ExMasConfigGroup();
		config.setAlgorithmProcessCount(1);
		config.setPruningDistanceSavingsLogScale(-1.0);

		return new TestSetup(
				requests,
				List.of(pair20, pair21, pair10),
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
			double firstPassengerTime,
			double secondPassengerTime,
			double firstPassengerDistance,
			double secondPassengerDistance,
			double[] connectionTravelTimes,
			double[] connectionDistances) {
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