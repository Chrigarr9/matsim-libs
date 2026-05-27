package org.matsim.contrib.demand_extraction.algorithm.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideKind;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;
import org.matsim.contrib.demand_extraction.algorithm.network.TravelSegmentLookup;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

/**
 * Failing (red) test for Task 2 of the chained_timebin feature.
 *
 * <p>Asserts that {@link RidePostProcessor#process(List)} populates
 * {@link Ride#getReposTimeMeanOutgoing()} with the mean travel time over
 * all feasible outgoing successors, per the design spec:
 *
 * <pre>
 *   repos_time(r) = mean over s in successors(r) of: travel_time(dropoff_r to pickup_s)
 * </pre>
 *
 * The test will remain RED until Task 3 implements the field population in
 * {@link RidePostProcessor}.
 *
 * <h3>Fixture overview</h3>
 * <ul>
 *   <li><b>rideA</b> (startTime=0, rideTT=100s, endTime=100)
 *       has TWO successors: rideB (repos tt=60s) and rideC (repos tt=120s).
 *       Expected mean = (60+120)/2 = 90.0.</li>
 *   <li><b>rideB</b> (startTime=200, rideTT=100s, endTime=300)
 *       has ONE successor: rideC (repos tt=200s).
 *       Expected mean = 200.0.</li>
 *   <li><b>rideC</b> (startTime=1000, rideTT=100s, endTime=1100)
 *       has NO successors (placed last in time; stub returns unreachable for
 *       its destination pair).
 *       Expected: sentinel -1.0.</li>
 * </ul>
 *
 * <h3>Link layout</h3>
 * Each ride has a unique destination link ({@code destA}, {@code destB},
 * {@code destC}) and a unique origin link ({@code origA}, {@code origB},
 * {@code origC}). The stub lookup answers exactly the four relevant pairs:
 * <ul>
 *   <li>(destA, origB) → 60s — rideA chains to rideB</li>
 *   <li>(destA, origC) → 120s — rideA chains to rideC</li>
 *   <li>(destB, origC) → 200s — rideB chains to rideC</li>
 *   <li>all other pairs → unreachable</li>
 * </ul>
 *
 * <h3>Timing feasibility check</h3>
 * {@code computePredecessors} accepts a connection only when
 * {@code endTime_i + travelTime(lastDest_i, firstOrigin_j) <= startTime_j}:
 * <ul>
 *   <li>rideA→rideB: 100+60=160 &le; 200 ✓</li>
 *   <li>rideA→rideC: 100+120=220 &le; 1000 ✓</li>
 *   <li>rideB→rideC: 300+200=500 &le; 1000 ✓</li>
 * </ul>
 */
class RidePostProcessorReposTimeTest {

	// ─── Link IDs ────────────────────────────────────────────────────────────

	private static final Id<Link> ORIG_A = Id.createLinkId("origA");
	private static final Id<Link> DEST_A = Id.createLinkId("destA");
	private static final Id<Link> ORIG_B = Id.createLinkId("origB");
	private static final Id<Link> DEST_B = Id.createLinkId("destB");
	private static final Id<Link> ORIG_C = Id.createLinkId("origC");
	private static final Id<Link> DEST_C = Id.createLinkId("destC");

	// ─── Fixture helpers ─────────────────────────────────────────────────────

	/**
	 * Builds a single-passenger request at the specified index with the given
	 * origin and destination links. Fields not required by the post-processor
	 * are set to benign defaults.
	 */
	private static DrtRequest req(int index, Id<Link> originLink, Id<Link> destLink) {
		return new DrtRequest.Builder()
				.index(index)
				.personId(org.matsim.api.core.v01.Id.createPersonId("p" + index))
				.originLinkId(originLink)
				.destinationLinkId(destLink)
				.directTravelTime(0)
				.directDistance(0)
				.earliestDeparture(0)
				.latestArrival(Integer.MAX_VALUE)
				.build();
	}

	/**
	 * Builds a degree-1 (single) ride whose vehicle segment has the specified
	 * {@code connectionTravelTime}, giving {@code rideTravelTime = connectionTravelTime}
	 * and {@code endTime = startTime + connectionTravelTime}.
	 *
	 * <p>The origin link of the ride's first request becomes
	 * {@link Ride#getOriginsOrdered()}{@code [0]}, and the destination link
	 * becomes {@link Ride#getDestinationsOrdered()}{@code [0]} — these are what
	 * {@code computePredecessors} reads as the "first origin" and "last dest".
	 */
	private static Ride singleRide(int rideIndex, DrtRequest request,
								   double startTime, double connectionTravelTime) {
		return Ride.builder()
				.index(rideIndex)
				.degree(1)
				.kind(RideKind.SINGLE)
				.requests(new DrtRequest[] { request })
				.originsOrderedRequests(new DrtRequest[] { request })
				.destinationsOrderedRequests(new DrtRequest[] { request })
				.passengerTravelTimes(new double[] { connectionTravelTime })
				.passengerDistances(new double[] { 0.0 })
				.passengerNetworkUtilities(new double[] { 0.0 })
				.delays(new double[] { 0.0 })
				.detours(new double[] { 1.0 })
				.connectionTravelTimes(new double[] { connectionTravelTime })
				.connectionDistances(new double[] { 1000.0 })
				.connectionNetworkUtilities(new double[] { 0.0 })
				.startTime(startTime)
				.build();
	}

	/**
	 * Minimal {@link TravelSegmentLookup} stub driven by an explicit
	 * {@code (fromLinkId, toLinkId) -> travelTime} map.
	 * All other pairs return {@link TravelSegment#unreachable()}.
	 */
	private static TravelSegmentLookup stubLookup(
			Map<String, Double> travelTimeByLinkPair) {
		return (from, to, departureTime) -> {
			String key = from.toString() + "|" + to.toString();
			Double tt = travelTimeByLinkPair.get(key);
			if (tt == null) {
				return TravelSegment.unreachable();
			}
			return new TravelSegment(tt, tt * 10.0 /* distance placeholder */, 0.0);
		};
	}

	/**
	 * Convenience to produce a link-pair lookup key.
	 */
	private static String pair(Id<Link> from, Id<Link> to) {
		return from.toString() + "|" + to.toString();
	}

	// ─── Config ──────────────────────────────────────────────────────────────

	/**
	 * Minimal {@link ExMasConfigGroup}: predecessors enabled, Shapley off,
	 * single-threaded, unbounded filter time (covers all pairs), no distance
	 * filter, maxSuccessors=0 (unlimited).
	 */
	private static ExMasConfigGroup minimalConfig() {
		ExMasConfigGroup cfg = new ExMasConfigGroup();
		cfg.setCalcPredecessors(true);
		cfg.setCalcShapleyValues(false);
		cfg.setHeuristicsProcessCount(1);
		cfg.setPredecessorsFilterTime(-1.0);   // unbounded
		cfg.setMaxSuccessors(0);               // no top-K pruning
		return cfg;
	}

	// ─── Test ────────────────────────────────────────────────────────────────

	/**
	 * Primary assertion: after {@code process()}, each ride reports the
	 * expected {@code reposTimeMeanOutgoing} value.
	 *
	 * <p>This test FAILS until Task 3 populates the field in
	 * {@link RidePostProcessor}.
	 */
	@Test
	void reposTimeMeanOutgoing_computedFromSuccessorTravelTimes() {

		// Build requests (distinct link IDs so computePredecessors can route them)
		DrtRequest reqA = req(0, ORIG_A, DEST_A);
		DrtRequest reqB = req(1, ORIG_B, DEST_B);
		DrtRequest reqC = req(2, ORIG_C, DEST_C);

		// Build rides
		// rideA: [0, 100], rideB: [200, 300], rideC: [1000, 1100]
		Ride rideA = singleRide(0, reqA, 0.0,    100.0);
		Ride rideB = singleRide(1, reqB, 200.0,  100.0);
		Ride rideC = singleRide(2, reqC, 1000.0, 100.0);

		// Stub routing table:
		//   destA -> origB : 60s  (rideA chains to rideB; arrival 100+60=160 <= 200 ✓)
		//   destA -> origC : 120s (rideA chains to rideC; arrival 100+120=220 <= 1000 ✓)
		//   destB -> origC : 200s (rideB chains to rideC; arrival 300+200=500 <= 1000 ✓)
		//   everything else: unreachable
		Map<String, Double> routingTable = new HashMap<>();
		routingTable.put(pair(DEST_A, ORIG_B),  60.0);
		routingTable.put(pair(DEST_A, ORIG_C), 120.0);
		routingTable.put(pair(DEST_B, ORIG_C), 200.0);

		TravelSegmentLookup lookup = stubLookup(routingTable);
		ExMasConfigGroup cfg = minimalConfig();

		RidePostProcessor processor = new RidePostProcessor(
				cfg,
				lookup,
				(budget, request, travelTime, distance) -> 0.0  // maxCostResolver stub
		);

		List<Ride> enriched = processor.process(List.of(rideA, rideB, rideC));

		// Map by ride index for readable assertions
		Map<Integer, Ride> byIndex = new HashMap<>();
		for (Ride r : enriched) byIndex.put(r.getIndex(), r);

		// rideA has two successors: tt=60 and tt=120 → mean = 90.0
		assertEquals(90.0, byIndex.get(0).getReposTimeMeanOutgoing(), 1e-6,
				"rideA with successors [60s, 120s] should have mean=90.0");

		// rideB has one successor: tt=200 → mean = 200.0
		assertEquals(200.0, byIndex.get(1).getReposTimeMeanOutgoing(), 1e-6,
				"rideB with one successor [200s] should have mean=200.0");

		// rideC has no successors → sentinel -1.0 (field untouched)
		assertEquals(-1.0, byIndex.get(2).getReposTimeMeanOutgoing(), 1e-6,
				"rideC with no successors should retain sentinel -1.0");
	}
}
