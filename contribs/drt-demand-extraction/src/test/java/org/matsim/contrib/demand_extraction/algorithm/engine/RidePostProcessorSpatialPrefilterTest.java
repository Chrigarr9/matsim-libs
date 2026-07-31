package org.matsim.contrib.demand_extraction.algorithm.engine;

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
 *
 * <h3>Why the observable is a mean</h3>
 *
 * <p>These tests used to compare the successor LISTS the pass attached to each ride. That column is
 * gone — the pass now emits only {@code reposTimeMeanOutgoing}, the mean handoff travel time over
 * the kept successors ({@code maxSuccessors = 0} here, so over ALL feasible ones). The mean is a
 * faithful witness for THIS fixture because rideA's three candidate handoffs have pairwise distinct
 * travel times (B 10 s / C 500 s / D 30 s), so no two successor sets share a mean:
 * A -&gt; {B,D} = 20 s, A -&gt; {B} = 10 s, A -&gt; {D} = 30 s, and a spurious A -&gt; {B,C,D} would
 * read 180 s. Any successor a pre-filter wrongly dropped, or wrongly admitted, moves the number.
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

		Map<Integer, Double> succOff = runAndCollectReposMeans(rides, net, lookup, false);
		Map<Integer, Double> succOn  = runAndCollectReposMeans(rides, net, lookup, true);

		// 20 s == mean(B 10, D 30): rideA reaches both, and rideC (500 s) is excluded — admitting it
		// would read 180 s.
		assertEquals(20.0, succOff.get(0), 1e-9, "OFF: rideA -> {B,D}");
		// rideB ends at 300, so rideD (start 1000) is its only candidate: DB(100,0) -> OD(300,0)
		// is 200 m = 20 s.
		assertEquals(20.0, succOff.get(1), 1e-9, "OFF: rideB -> {D}");
		// rideC likewise reaches only rideD, but from 5000 m out: 4700 m = 470 s.
		assertEquals(470.0, succOff.get(2), 1e-9, "OFF: rideC -> {D}");
		// rideD starts last, so nothing can follow it.
		assertEquals(-1.0, succOff.get(3), 1e-9, "OFF: rideD has no successor");

		assertEquals(succOff, succOn,
				"the time-reach pre-filter must not change any ride's kept successor set");
	}

	// ─── Parity: distance-cap pre-route vs post-route (+ it drops a far handoff) ──

	@Test
	void distanceCap_preRouteEqualsPostRoute_andDropsTimeFeasibleFarHandoff() {
		Network net = fixtureNetwork();
		List<Ride> rides = fixtureRides();
		TravelSegmentLookup lookup = coordConsistentLookup(net, 10.0);

		// factor 0.2 => cap = rideDist_A(1000) * 0.2 = 200 m. rideA->D (300 m) is time-feasible but
		// exceeds the cap; rideA->B (100 m) is kept.
		Map<Integer, Double> withNet = runWithDistanceFactor(rides, net, lookup, 0.2);   // pre-route + post-route
		Map<Integer, Double> noNet   = runWithDistanceFactor(rides, null, lookup, 0.2);  // post-route only
		Map<Integer, Double> unbounded = runAndCollectReposMeans(rides, net, lookup, true); // no distance cap

		// Lossless: pre-route (network) == post-route-only (no network).
		assertEquals(noNet, withNet,
				"applying the distance cap before routing must select exactly what the post-route cap did");
		// Cap is active and meaningful: D dropped by distance (was a successor unbounded).
		assertEquals(20.0, unbounded.get(0), 1e-9, "unbounded: rideA -> {B,D}");
		assertEquals(10.0, withNet.get(0), 1e-9, "factor 0.2: rideA -> {B} (D dropped by distance cap)");
	}

	// ─── Helpers ──────────────────────────────────────────────────────────────

	private static Map<Integer, Double> runAndCollectReposMeans(
			List<Ride> rides, Network net, TravelSegmentLookup lookup, boolean prefilter) {
		ExMasConfigGroup cfg = baseConfig();
		cfg.setPredecessorsSpatialPrefilter(prefilter);
		return run(rides, net, lookup, cfg);
	}

	private static Map<Integer, Double> runWithDistanceFactor(
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

	private static Map<Integer, Double> run(List<Ride> rides, Network net,
			TravelSegmentLookup lookup, ExMasConfigGroup cfg) {
		RidePostProcessor processor = new RidePostProcessor(
				cfg, lookup, (budget, request, tt, dist) -> 0.0, net);
		List<Ride> enriched = processor.process(new MaterializedRideStore(new ArrayList<>(rides)));
		Map<Integer, Double> byIndex = new HashMap<>();
		for (Ride r : enriched) byIndex.put(r.getIndex(), r.getReposTimeMeanOutgoing());
		return byIndex;
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
