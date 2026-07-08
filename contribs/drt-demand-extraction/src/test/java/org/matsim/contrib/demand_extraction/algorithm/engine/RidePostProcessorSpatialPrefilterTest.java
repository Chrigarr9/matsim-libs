package org.matsim.contrib.demand_extraction.algorithm.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.contrib.demand_extraction.algorithm.bamas.ride.MaterializedRideStore;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideKind;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;
import org.matsim.contrib.demand_extraction.algorithm.network.TravelSegmentLookup;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;
import org.matsim.core.network.NetworkUtils;

/**
 * Correctness gate for the predecessor/successor pre-filters:
 * <ul>
 *   <li>{@code predecessorsSpatialPrefilter} — time-reach lower-bound cut (sound: never drops a
 *       feasible successor), asserted identical ON vs OFF.</li>
 *   <li>{@code predecessorsFilterDistanceFactor} applied as a PRE-ROUTING euclidean cut — lossless
 *       w.r.t. the existing post-route distance filter, asserted identical pre-route vs post-route,
 *       and shown to actually drop a time-feasible-but-too-far handoff.</li>
 * </ul>
 *
 * <h3>Fixture (links freespeed 10 m/s; routing at 10 m/s; every ride's own distance = 1000 m)</h3>
 * <ul>
 *   <li>rideA: lastDest (0,0), [0,100]</li>
 *   <li>rideB: firstOrigin (100,0), start 200 — dist 100, tt 10, arrive 110 &le; 200 ✓</li>
 *   <li>rideC: firstOrigin (5000,0), start 250 — dist 5000, tt 500, arrive 600 &gt; 250 ✗ (also far)</li>
 *   <li>rideD: firstOrigin (300,0), start 1000 — dist 300, tt 30, arrive 130 &le; 1000 ✓ (time-feasible)</li>
 * </ul>
 */
class RidePostProcessorSpatialPrefilterTest {

	// ─── withinReach soundness (direct unit test) ─────────────────────────────

	@Test
	void withinReach_isSoundLowerBound() {
		assertTrue(RidePostProcessor.withinReach(100, 15, 1000, 0), "1000 m within 1500 m reach");
		assertTrue(RidePostProcessor.withinReach(100, 15, 0, 1500), "exactly on the boundary is kept");
		assertFalse(RidePostProcessor.withinReach(100, 15, 1501, 0), "1501 m beyond 1500 m reach is cut");
		assertFalse(RidePostProcessor.withinReach(0, 15, 1, 0), "zero gap cannot reach a displaced pickup");
		assertTrue(RidePostProcessor.withinReach(0, 15, 0, 0), "zero gap reaches a co-located pickup");
		assertFalse(RidePostProcessor.withinReach(-5, 15, 0, 0), "negative gap is never reachable");
	}

	@Test
	void preRouteKeep_distanceCap_isLossless() {
		// cap = rideDist(1000) * factor(0.2) = 200 m; spatial off
		assertTrue(RidePostProcessor.preRouteKeep(false, 0, 0, true, 1000, 0.2, 100, 0), "100 m <= 200 m cap");
		assertFalse(RidePostProcessor.preRouteKeep(false, 0, 0, true, 1000, 0.2, 300, 0), "300 m > 200 m cap");
	}

	// ─── Parity: time-reach pre-filter ON vs OFF ──────────────────────────────

	@Test
	void successorsIdentical_prefilterOnVsOff() {
		Network net = fixtureNetwork();
		List<Ride> rides = fixtureRides();
		TravelSegmentLookup lookup = coordConsistentLookup(net, 10.0);

		Map<Integer, int[]> succOff = runAndCollectSuccessors(rides, net, lookup, false);
		Map<Integer, int[]> succOn  = runAndCollectSuccessors(rides, net, lookup, true);

		assertArrayEquals(new int[] {1, 3}, succOff.get(0), "OFF: rideA -> {B,D}");
		assertFalse(containsRide(succOff, 2), "OFF: rideC is never a successor");

		assertEquals(succOff.keySet(), succOn.keySet(), "same rides have successor entries");
		for (Integer rideId : succOff.keySet()) {
			assertArrayEquals(succOff.get(rideId), succOn.get(rideId),
					"successors of ride " + rideId + " must be identical with pre-filter ON vs OFF");
		}
	}

	// ─── Parity: distance-cap pre-route vs post-route (+ it drops a far handoff) ──

	@Test
	void distanceCap_preRouteEqualsPostRoute_andDropsTimeFeasibleFarHandoff() {
		Network net = fixtureNetwork();
		List<Ride> rides = fixtureRides();
		TravelSegmentLookup lookup = coordConsistentLookup(net, 10.0);

		// factor 0.2 => cap = rideDist_A(1000) * 0.2 = 200 m. rideA->D (300 m) is time-feasible but
		// exceeds the cap; rideA->B (100 m) is kept.
		Map<Integer, int[]> withNet = runWithDistanceFactor(rides, net, lookup, 0.2);   // pre-route + post-route
		Map<Integer, int[]> noNet   = runWithDistanceFactor(rides, null, lookup, 0.2);  // post-route only
		Map<Integer, int[]> unbounded = runAndCollectSuccessors(rides, net, lookup, true); // no distance cap

		// Lossless: pre-route (network) == post-route-only (no network).
		assertEquals(noNet.keySet(), withNet.keySet(), "same successor-entry keys");
		for (Integer rideId : noNet.keySet()) {
			assertArrayEquals(noNet.get(rideId), withNet.get(rideId),
					"ride " + rideId + " successors: distance pre-route must equal post-route");
		}
		// Cap is active and meaningful: D dropped by distance (was a successor unbounded).
		assertArrayEquals(new int[] {1, 3}, unbounded.get(0), "unbounded: rideA -> {B,D}");
		assertArrayEquals(new int[] {1}, withNet.get(0), "factor 0.2: rideA -> {B} (D dropped by distance cap)");
	}

	// ─── Helpers ──────────────────────────────────────────────────────────────

	private static Map<Integer, int[]> runAndCollectSuccessors(
			List<Ride> rides, Network net, TravelSegmentLookup lookup, boolean prefilter) {
		ExMasConfigGroup cfg = baseConfig();
		cfg.setPredecessorsSpatialPrefilter(prefilter);
		return run(rides, net, lookup, cfg);
	}

	private static Map<Integer, int[]> runWithDistanceFactor(
			List<Ride> rides, Network net, TravelSegmentLookup lookup, double factor) {
		ExMasConfigGroup cfg = baseConfig();
		cfg.setPredecessorsSpatialPrefilter(false); // isolate the distance cap
		cfg.setPredecessorsFilterDistanceFactor(factor);
		return run(rides, net, lookup, cfg);
	}

	private static ExMasConfigGroup baseConfig() {
		ExMasConfigGroup cfg = new ExMasConfigGroup();
		cfg.setCalcPredecessors(true);
		cfg.setCalcShapleyValues(false);
		cfg.setHeuristicsProcessCount(1);
		cfg.setPredecessorsFilterTime(2000.0);
		cfg.setMaxSuccessors(0);
		return cfg;
	}

	private static Map<Integer, int[]> run(List<Ride> rides, Network net,
			TravelSegmentLookup lookup, ExMasConfigGroup cfg) {
		RidePostProcessor processor = new RidePostProcessor(
				cfg, lookup, (budget, request, tt, dist) -> 0.0, net);
		List<Ride> enriched = processor.process(new MaterializedRideStore(new ArrayList<>(rides)));
		Map<Integer, int[]> byIndex = new HashMap<>();
		for (Ride r : enriched) byIndex.put(r.getIndex(), r.getSuccessors());
		return byIndex;
	}

	private static boolean containsRide(Map<Integer, int[]> succ, int rideId) {
		for (int[] arr : succ.values()) {
			for (int v : arr) if (v == rideId) return true;
		}
		return false;
	}

	private static TravelSegmentLookup coordConsistentLookup(Network net, double speed) {
		return (from, to, departureTime) -> {
			Link f = net.getLinks().get(from);
			Link t = net.getLinks().get(to);
			if (f == null || t == null) return TravelSegment.unreachable();
			double dx = t.getCoord().getX() - f.getCoord().getX();
			double dy = t.getCoord().getY() - f.getCoord().getY();
			double dist = Math.sqrt(dx * dx + dy * dy);
			return new TravelSegment(dist / speed, dist, 0.0);
		};
	}

	private static Network fixtureNetwork() {
		Network net = NetworkUtils.createNetwork();
		addLocationLink(net, "DA", 0, 0);
		addLocationLink(net, "OA", 0, 0);
		addLocationLink(net, "OB", 100, 0);
		addLocationLink(net, "DB", 100, 0);
		addLocationLink(net, "OC", 5000, 0);
		addLocationLink(net, "DC", 5000, 0);
		addLocationLink(net, "OD", 300, 0);
		addLocationLink(net, "DD", 300, 0);
		return net;
	}

	private static List<Ride> fixtureRides() {
		Ride rideA = singleRide(0, req(0, id("OA"), id("DA")), 0.0,    100.0);
		Ride rideB = singleRide(1, req(1, id("OB"), id("DB")), 200.0,  100.0);
		Ride rideC = singleRide(2, req(2, id("OC"), id("DC")), 250.0,  100.0);
		Ride rideD = singleRide(3, req(3, id("OD"), id("DD")), 1000.0, 100.0);
		return List.of(rideA, rideB, rideC, rideD);
	}

	private static Id<Link> id(String name) { return Id.createLinkId(name); }

	private static void addLocationLink(Network net, String name, double x, double y) {
		NetworkFactory f = net.getFactory();
		Node a = f.createNode(Id.createNodeId(name + "_a"), new Coord(x, y));
		Node b = f.createNode(Id.createNodeId(name + "_b"), new Coord(x + 1, y));
		net.addNode(a);
		net.addNode(b);
		Link link = f.createLink(Id.createLinkId(name), a, b);
		link.setLength(1.0);
		link.setFreespeed(10.0);
		link.setCapacity(1000.0);
		link.setNumberOfLanes(1.0);
		net.addLink(link);
	}

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
}
