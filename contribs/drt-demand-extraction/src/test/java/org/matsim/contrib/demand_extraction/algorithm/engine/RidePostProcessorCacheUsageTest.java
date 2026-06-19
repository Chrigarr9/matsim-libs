package org.matsim.contrib.demand_extraction.algorithm.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.demand_extraction.algorithm.bamas.ride.MaterializedRideStore;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideKind;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;
import org.matsim.contrib.demand_extraction.algorithm.network.TravelSegmentLookup;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

/**
 * Asserts the predecessor pass uses the connection cache <em>correctly under eviction</em>:
 *
 * <ol>
 *   <li>it pins every evaluated handoff via {@link TravelSegmentLookup#retainSegment} carrying the
 *       routed value (NOT {@link TravelSegmentLookup#promoteSegment}, a move that no-ops if the entry
 *       was already evicted by a concurrent watermark check); and</li>
 *   <li>it reclaims the dead enumeration speculative tier once, at the start, via
 *       {@link TravelSegmentLookup#dropSpeculativeTier()} (a single-threaded barrier, never during
 *       the parallel routing — the same eviction discipline the degree-barrier extension uses, so it
 *       cannot race a getSegment into a divergent recompute).</li>
 * </ol>
 *
 * <p>Without this, turning eviction on during the parallel pass would silently drop handoffs from the
 * {@code connection_cache.csv} export. Fixture mirrors {@link RidePostProcessorReposTimeTest}: rideA
 * chains to rideB (60s) and rideC (120s); rideB chains to rideC (200s).
 */
class RidePostProcessorCacheUsageTest {

	private static final Id<Link> ORIG_A = Id.createLinkId("origA");
	private static final Id<Link> DEST_A = Id.createLinkId("destA");
	private static final Id<Link> ORIG_B = Id.createLinkId("origB");
	private static final Id<Link> DEST_B = Id.createLinkId("destB");
	private static final Id<Link> ORIG_C = Id.createLinkId("origC");
	private static final Id<Link> DEST_C = Id.createLinkId("destC");

	private static DrtRequest req(int index, Id<Link> originLink, Id<Link> destLink) {
		return new DrtRequest.Builder()
				.index(index)
				.personId(Id.createPersonId("p" + index))
				.originLinkId(originLink)
				.destinationLinkId(destLink)
				.directTravelTime(0)
				.directDistance(0)
				.earliestDeparture(0)
				.latestArrival(Integer.MAX_VALUE)
				.build();
	}

	private static Ride singleRide(int rideIndex, DrtRequest request, double startTime, double tt) {
		return Ride.builder()
				.index(rideIndex)
				.degree(1)
				.kind(RideKind.SINGLE)
				.requests(new DrtRequest[] { request })
				.originsOrderedRequests(new DrtRequest[] { request })
				.destinationsOrderedRequests(new DrtRequest[] { request })
				.passengerTravelTimes(new double[] { tt })
				.passengerDistances(new double[] { 0.0 })
				.passengerNetworkUtilities(new double[] { 0.0 })
				.delays(new double[] { 0.0 })
				.detours(new double[] { 1.0 })
				.connectionTravelTimes(new double[] { tt })
				.connectionDistances(new double[] { 1000.0 })
				.connectionNetworkUtilities(new double[] { 0.0 })
				.startTime(startTime)
				.build();
	}

	private static String pair(Id<Link> from, Id<Link> to) {
		return from.toString() + "|" + to.toString();
	}

	/**
	 * Records retain/promote/checkWatermark calls over a fixed routing table. getSegment returns
	 * {@code (tt, tt*10, 0.0)} for known pairs and {@link TravelSegment#unreachable()} otherwise.
	 */
	private static final class RecordingLookup implements TravelSegmentLookup {
		final Map<String, Double> table;
		final Map<String, double[]> retained = new ConcurrentHashMap<>();
		final AtomicInteger promoteCalls = new AtomicInteger();
		final AtomicInteger dropCalls = new AtomicInteger();
		final AtomicInteger compactCalls = new AtomicInteger();

		RecordingLookup(Map<String, Double> table) {
			this.table = table;
		}

		@Override
		public TravelSegment getSegment(Id<Link> from, Id<Link> to, double departureTime) {
			Double tt = table.get(pair(from, to));
			return tt == null ? TravelSegment.unreachable() : new TravelSegment(tt, tt * 10.0, 0.0);
		}

		@Override
		public void retainSegment(Id<Link> from, Id<Link> to, double departureTime,
				double travelTime, double distance, double networkUtility) {
			retained.put(pair(from, to), new double[] { travelTime, distance, networkUtility });
		}

		@Override
		public void promoteSegment(Id<Link> from, Id<Link> to, double departureTime) {
			promoteCalls.incrementAndGet();
		}

		@Override
		public void dropSpeculativeTier() {
			dropCalls.incrementAndGet();
		}

		@Override
		public void compactRetained() {
			compactCalls.incrementAndGet();
		}
	}

	private static ExMasConfigGroup minimalConfig() {
		ExMasConfigGroup cfg = new ExMasConfigGroup();
		cfg.setCalcPredecessors(true);
		cfg.setCalcShapleyValues(false);
		cfg.setHeuristicsProcessCount(1);
		cfg.setPredecessorsFilterTime(-1.0);
		cfg.setMaxSuccessors(0);
		return cfg;
	}

	@Test
	void predecessorPassRetainsHandoffsByValueAndDrivesEviction() {
		DrtRequest reqA = req(0, ORIG_A, DEST_A);
		DrtRequest reqB = req(1, ORIG_B, DEST_B);
		DrtRequest reqC = req(2, ORIG_C, DEST_C);

		Ride rideA = singleRide(0, reqA, 0.0, 100.0);
		Ride rideB = singleRide(1, reqB, 200.0, 100.0);
		Ride rideC = singleRide(2, reqC, 1000.0, 100.0);

		Map<String, Double> routingTable = new HashMap<>();
		routingTable.put(pair(DEST_A, ORIG_B), 60.0);
		routingTable.put(pair(DEST_A, ORIG_C), 120.0);
		routingTable.put(pair(DEST_B, ORIG_C), 200.0);

		RecordingLookup lookup = new RecordingLookup(routingTable);
		RidePostProcessor processor = new RidePostProcessor(
				minimalConfig(), lookup, (budget, request, travelTime, distance) -> 0.0);

		processor.process(new MaterializedRideStore(List.of(rideA, rideB, rideC)));

		// 1. Every evaluated, reachable handoff is retained BY VALUE (not a key-only promote).
		assertEquals(3, lookup.retained.size(),
				"all three reachable handoffs must be retained by value");
		assertHandoff(lookup, DEST_A, ORIG_B, 60.0);
		assertHandoff(lookup, DEST_A, ORIG_C, 120.0);
		assertHandoff(lookup, DEST_B, ORIG_C, 200.0);

		// 2. The pass must NOT rely on the eviction-unsafe move-based promote.
		assertEquals(0, lookup.promoteCalls.get(),
				"predecessor pass must use retainSegment, never promoteSegment");

		// 3. The dead enumeration speculative tier is dropped exactly once (at the start barrier),
		//    NOT per-ride during routing, and the retained overlay is compacted after the pass joins.
		assertEquals(1, lookup.dropCalls.get(),
				"predecessor pass must drop the speculative tier once, at the start barrier");
		assertTrue(lookup.compactCalls.get() > 0,
				"predecessor pass must compact the retained tier after it joins");
	}

	private static void assertHandoff(RecordingLookup lookup, Id<Link> from, Id<Link> to, double tt) {
		double[] v = lookup.retained.get(pair(from, to));
		assertNotNull(v, "handoff " + pair(from, to) + " must be retained");
		assertEquals(tt, v[0], 1e-9, "retained travelTime");
		assertEquals(tt * 10.0, v[1], 1e-9, "retained distance");
		assertFalse(Double.isNaN(v[2]), "retained utility");
	}
}
