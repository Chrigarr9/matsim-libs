package org.matsim.contrib.demand_extraction.algorithm.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.demand_extraction.algorithm.bamas.ride.MaterializedRideStore;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideKind;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;
import org.matsim.contrib.demand_extraction.algorithm.network.TravelSegmentLookup;
import org.matsim.contrib.demand_extraction.algorithm.util.PackedKeyCodec;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Characterisation gate for the predecessor/successor pass, written BEFORE the deduplication
 * rewrite (plan {@code docs/superpowers/plans/2026-07-31-predecessor-pass-dedup.md}) so that the
 * rewrite can be proven output-identical rather than merely "looks right".
 *
 * <p>The rewrite replaces a per-ride-pair loop with a per-group memo: rides sharing
 * {@code (last-destination link, end-time bin)} resolve the SAME connection keys, so the cache
 * read, the retain and the window-key append happen once per distinct key instead of once per
 * pair. Three properties must survive that change, and each has a test here:
 *
 * <ol>
 *   <li><b>The window-key set.</b> This is the {@code connection_cache.csv} export domain. It must
 *       stay <em>exactly</em> the set of evaluated pairs, <b>including pairs that were rejected as
 *       unreachable or too slow</b>. Python's post-MIP path cover treats a cache hit that says
 *       "too slow" as a hard rejection; a missing row instead falls through to a beeline
 *       depot-detour estimate, which biases the fleet estimate downward. Deduplicating must
 *       therefore be memoisation, never filtering.</li>
 *   <li><b>The successor lists.</b></li>
 *   <li><b>The repositioning means</b>, including the {@code -1.0} sentinel that
 *       {@code repos_time.py:68} depends on.</li>
 * </ol>
 *
 * <h3>Fixture A — colliding endpoints ({@link #collidingEndpointFixture()})</h3>
 *
 * Exercises both deduplication dimensions at once. Rides 0-2 all end on link {@code D1} inside one
 * time bin (the outer dimension: one memo serves all three). Rides 3-5 all start on link {@code O1}
 * (the inner dimension: one lookup serves all three candidates). Ride 7 starts on {@code O3}, which
 * is unreachable from every source, so its keys must appear in the window despite contributing no
 * successor.
 *
 * <pre>
 *   idx  origin  dest  start  end     window [end, end+1800]  candidates j
 *     0  OA      D1        0   100    [ 100, 1900]            2,3,4,5,6,7
 *     1  OB      D1       50   150    [ 150, 1950]            3,4,5,6,7
 *     2  OC      D1      100   200    [ 200, 2000]            3,4,5,6,7
 *     3  O1      DP      500   600    [ 600, 2400]            4,5,6,7
 *     4  O1      DQ      600   700    [ 700, 2500]            5,6,7
 *     5  O1      DR      700   800    [ 800, 2600]            6,7
 *     6  O2      DS      800   900    [ 900, 2700]            7
 *     7  O3      DT      900  1000    [1000, 2800]            none
 * </pre>
 *
 * 26 evaluated pairs collapse to 13 distinct {@code (from, to, bin)} keys — the redundancy the
 * rewrite removes, in miniature. All end times are below the 3600 s bin size, so every key is in
 * bin 0.
 *
 * <h3>Fixture B — top-K tie-break ({@link #tiedScoreFixture()})</h3>
 *
 * The selection score is {@code distance * max(1.0, idlingTime)}. Candidates 3 and 4 are
 * constructed to score <em>exactly</em> equal by opposite routes: 300 m with 870 s idle against
 * 8700 m with 30 s idle, both 261,000. With {@code maxSuccessors = 3} the current implementation
 * full-sorts with {@link java.util.Comparator#comparingDouble}, which is stable, and scans
 * candidates in ascending ride index — so the LOWER index wins the last slot. The bounded heap
 * introduced by plan Task 4 keys on {@code (score, rideIndex)} specifically to reproduce this.
 */
class RidePostProcessorWindowKeyTest {

	private static final int BIN_SIZE = 3600;   // ExMasConfigGroup default
	private static final double FILTER_TIME = 1800.0;

	// ─── Fixture A: colliding endpoints ───────────────────────────────────────

	@Test
	void windowKeySetIsEveryEvaluatedPairIncludingRejectedOnes() {
		RidePostProcessor pp = processor(maxSuccessors(50), collidingLookup());
		pp.process(new MaterializedRideStore(collidingEndpointFixture()));

		LongOpenHashSet expected = new LongOpenHashSet();
		// Sources 0,1,2 all end on D1. Their candidate origins are OC (ride 2, reachable only
		// from ride 0's window), O1, O2 and O3. O3 is UNREACHABLE and OC has no routing entry --
		// both must still be present.
		addKey(expected, "D1", "OC");
		addKey(expected, "D1", "O1");
		addKey(expected, "D1", "O2");
		addKey(expected, "D1", "O3");
		// Source 3 (dest DP) sees rides 4,5 (O1), 6 (O2), 7 (O3).
		addKey(expected, "DP", "O1");
		addKey(expected, "DP", "O2");
		addKey(expected, "DP", "O3");
		// Source 4 (dest DQ) sees rides 5 (O1), 6 (O2), 7 (O3).
		addKey(expected, "DQ", "O1");
		addKey(expected, "DQ", "O2");
		addKey(expected, "DQ", "O3");
		// Source 5 (dest DR) sees rides 6 (O2), 7 (O3).
		addKey(expected, "DR", "O2");
		addKey(expected, "DR", "O3");
		// Source 6 (dest DS) sees ride 7 (O3). Source 7 has an empty window.
		addKey(expected, "DS", "O3");

		assertEquals(13, expected.size(), "fixture sanity: 13 distinct keys are expected");
		assertEquals(expected, pp.getWindowKeys(),
				"the window is the evaluated-pair domain; deduplication must not drop or add a key");
	}

	@Test
	void successorsArePinned() {
		Map<Integer, Ride> out = runCollidingFixture(50);

		// Rides 0-2 all end on D1 and all reach O1 (rides 3,4,5) and O2 (ride 6).
		// OC is absent from the routing table and O3 is unreachable, so neither is a successor.
		assertArrayEquals(new int[] {3, 4, 5, 6}, out.get(0).getSuccessors(), "ride 0");
		assertArrayEquals(new int[] {3, 4, 5, 6}, out.get(1).getSuccessors(), "ride 1");
		assertArrayEquals(new int[] {3, 4, 5, 6}, out.get(2).getSuccessors(), "ride 2");
		// Ride 3 ends at 600; ride 4 starts at 600 and needs 50 s, so it arrives late (650 > 600).
		assertArrayEquals(new int[] {5, 6}, out.get(3).getSuccessors(), "ride 3");
		// Rides 4-7 have no routing entries out of their destinations.
		assertArrayEquals(new int[] {}, out.get(4).getSuccessors(), "ride 4");
		assertArrayEquals(new int[] {}, out.get(5).getSuccessors(), "ride 5");
		assertArrayEquals(new int[] {}, out.get(6).getSuccessors(), "ride 6");
		assertArrayEquals(new int[] {}, out.get(7).getSuccessors(), "ride 7");
	}

	@Test
	void reposTimeMeansArePinnedIncludingTheEmptySentinel() {
		Map<Integer, Ride> out = runCollidingFixture(50);

		// Rides 0-2: three O1 handoffs at 200 s and one O2 handoff at 300 s.
		assertEquals(225.0, out.get(0).getReposTimeMeanOutgoing(), 1e-9, "ride 0");
		assertEquals(225.0, out.get(1).getReposTimeMeanOutgoing(), 1e-9, "ride 1");
		assertEquals(225.0, out.get(2).getReposTimeMeanOutgoing(), 1e-9, "ride 2");
		// Ride 3: one O1 handoff at 50 s and one O2 handoff at 60 s.
		assertEquals(55.0, out.get(3).getReposTimeMeanOutgoing(), 1e-9, "ride 3");
		// No successors -> the sentinel repos_time.py:68 keys on.
		assertEquals(-1.0, out.get(4).getReposTimeMeanOutgoing(), 1e-9, "ride 4 sentinel");
		assertEquals(-1.0, out.get(7).getReposTimeMeanOutgoing(), 1e-9, "ride 7 sentinel");
	}

	// ─── Fixture C: shared-passenger pairs ────────────────────────────────────

	@Test
	void aPairSharingAPassengerIsNotInTheWindowAtAll() {
		// A vehicle cannot hand off to a ride that carries one of its own passengers, so the pair
		// is dropped before the connection is ever resolved -- its key must therefore be ABSENT
		// from the export domain. Pinned because the plan reorders this test against the geometric
		// pre-filter: both are pure predicates, so the conjunction (and this key set) must not move.
		//
		//   ride 0: request 100, ends on S1 at t=100
		//   ride 1: request 100 as well -> the pair (S1, T1) is never evaluated
		//   ride 2: request 101         -> the pair (S1, T2) is evaluated normally
		//
		// Ride 2 starts at 800, not 600: ride 1 ends at 600 and every handoff here costs 50 s, so
		// a 600 start would arrive late and drop the ride 1 -> ride 2 edge for a reason that has
		// nothing to do with passenger sharing.
		List<Ride> rides = List.of(
				sharedRequestRide(0, 100, "SA", "S1",   0.0, 100.0),
				sharedRequestRide(1, 100, "T1", "TB", 500.0, 100.0),
				sharedRequestRide(2, 101, "T2", "TC", 800.0, 100.0));

		RidePostProcessor pp = processor(maxSuccessors(50), sharedRequestLookup());
		Map<Integer, Ride> out = byIndex(pp.process(new MaterializedRideStore(rides)));

		LongOpenHashSet expected = new LongOpenHashSet();
		addKey(expected, "S1", "T2");   // ride 0 -> ride 2, disjoint
		addKey(expected, "TB", "T2");   // ride 1 -> ride 2, disjoint
		assertEquals(expected, pp.getWindowKeys(),
				"the shared-passenger pair (S1, T1) must never reach the connection lookup");

		assertArrayEquals(new int[] {2}, out.get(0).getSuccessors(),
				"ride 1 shares a passenger with ride 0 and cannot succeed it");
		assertArrayEquals(new int[] {2}, out.get(1).getSuccessors(), "ride 1 -> ride 2");
	}

	/** Every relevant pair is reachable, so only the disjointness test can remove one. */
	private static TravelSegmentLookup sharedRequestLookup() {
		Map<String, Double> table = new HashMap<>();
		table.put(pair("S1", "T1"), 50.0);
		table.put(pair("S1", "T2"), 50.0);
		table.put(pair("TB", "T2"), 50.0);
		return tableLookup(table, 10.0);
	}

	// ─── Fixture B: top-K boundary ────────────────────────────────────────────

	@Test
	void allFourTiedCandidatesAreFeasibleWithoutTheCap() {
		// Guards the test below from passing vacuously: ride 4 must be a genuine successor that
		// only the K cut removes, not one the feasibility checks already rejected. Ride 4 takes
		// 870 s and arrives at 970, inside the 1000 s start -- deliberately tight.
		RidePostProcessor pp = processor(maxSuccessors(50), tiedScoreLookup());
		Map<Integer, Ride> out = byIndex(pp.process(new MaterializedRideStore(tiedScoreFixture())));

		assertArrayEquals(new int[] {1, 2, 3, 4}, out.get(0).getSuccessors(),
				"uncapped: every candidate including the tied ride 4 is feasible");
		assertEquals(232.5, out.get(0).getReposTimeMeanOutgoing(), 1e-9,
				"uncapped mean spans all four: (10 + 20 + 30 + 870) / 4");
	}

	@Test
	void topKKeepsTheLowerRideIndexOnAScoreTie() {
		RidePostProcessor pp = processor(maxSuccessors(3), tiedScoreLookup());
		Map<Integer, Ride> out = byIndex(pp.process(new MaterializedRideStore(tiedScoreFixture())));

		// Scores: E1 89,000 | E2 176,000 | E3 261,000 | E4 261,000.
		// K = 3, so exactly one of the tied pair survives, and a stable sort over an
		// ascending-index scan keeps ride 3 over ride 4.
		assertArrayEquals(new int[] {1, 2, 3}, out.get(0).getSuccessors(),
				"tie at the K boundary must resolve to the lower ride index");
		// The mean is taken over the POST-pruning set: (10 + 20 + 30) / 3. The contrast with
		// the uncapped 232.5 above is what makes this assertion load-bearing.
		assertEquals(20.0, out.get(0).getReposTimeMeanOutgoing(), 1e-9,
				"repos mean covers the kept top-K only");
	}

	// ─── Fixtures ─────────────────────────────────────────────────────────────

	private static List<Ride> collidingEndpointFixture() {
		return List.of(
				ride(0, "OA", "D1",    0.0, 100.0),
				ride(1, "OB", "D1",   50.0, 100.0),
				ride(2, "OC", "D1",  100.0, 100.0),
				ride(3, "O1", "DP",  500.0, 100.0),
				ride(4, "O1", "DQ",  600.0, 100.0),
				ride(5, "O1", "DR",  700.0, 100.0),
				ride(6, "O2", "DS",  800.0, 100.0),
				ride(7, "O3", "DT",  900.0, 100.0));
	}

	/** D1 reaches O1 in 200 s and O2 in 300 s; DP reaches O1 in 50 s and O2 in 60 s. */
	private static TravelSegmentLookup collidingLookup() {
		Map<String, Double> table = new HashMap<>();
		table.put(pair("D1", "O1"), 200.0);
		table.put(pair("D1", "O2"), 300.0);
		table.put(pair("DP", "O1"),  50.0);
		table.put(pair("DP", "O2"),  60.0);
		return tableLookup(table, 10.0);
	}

	private static List<Ride> tiedScoreFixture() {
		return List.of(
				ride(0, "OA", "D1",    0.0, 100.0),
				ride(1, "E1", "F1", 1000.0, 100.0),
				ride(2, "E2", "F2", 1000.0, 100.0),
				ride(3, "E3", "F3", 1000.0, 100.0),
				ride(4, "E4", "F4", 1000.0, 100.0));
	}

	/**
	 * Source ride 0 ends at 100; every candidate starts at 1000. With distance = 10 * travelTime
	 * the score {@code distance * max(1, idle)} is {@code 10t * (900 - t)}, which takes the value
	 * 261,000 at both t = 30 and t = 870 — the deliberate tie.
	 */
	private static TravelSegmentLookup tiedScoreLookup() {
		Map<String, Double> table = new HashMap<>();
		table.put(pair("D1", "E1"),  10.0);   // score 100 * 890     =  89,000
		table.put(pair("D1", "E2"),  20.0);   // score 200 * 880     = 176,000
		table.put(pair("D1", "E3"),  30.0);   // score 300 * 870     = 261,000
		table.put(pair("D1", "E4"), 870.0);   // score 8700 * 30     = 261,000
		return tableLookup(table, 10.0);
	}

	// ─── Helpers ──────────────────────────────────────────────────────────────

	private static Map<Integer, Ride> runCollidingFixture(int maxSuccessors) {
		RidePostProcessor pp = processor(maxSuccessors(maxSuccessors), collidingLookup());
		return byIndex(pp.process(new MaterializedRideStore(collidingEndpointFixture())));
	}

	private static RidePostProcessor processor(ExMasConfigGroup cfg, TravelSegmentLookup lookup) {
		// Three-arg constructor: no Network, so both the time-reach and distance pre-filters are
		// inactive (RidePostProcessor gates them on a non-null network). The only cuts in play are
		// the time slice and the disjoint-request test, which keeps the expected key set derivable
		// by hand.
		return new RidePostProcessor(cfg, lookup, (budget, request, travelTime, distance) -> 0.0);
	}

	private static ExMasConfigGroup maxSuccessors(int k) {
		ExMasConfigGroup cfg = new ExMasConfigGroup();
		cfg.setCalcPredecessors(true);
		cfg.setCalcShapleyValues(false);
		cfg.setHeuristicsProcessCount(1);
		cfg.setPredecessorsFilterTime(FILTER_TIME);
		cfg.setNetworkTimeBinSize(BIN_SIZE);
		cfg.setMaxSuccessors(k);
		return cfg;
	}

	private static void addKey(LongOpenHashSet keys, String from, String to) {
		keys.add(PackedKeyCodec.segmentKey(link(from).index(), link(to).index(), 0));
	}

	private static Id<Link> link(String name) {
		return Id.createLinkId(name);
	}

	private static String pair(String from, String to) {
		return from + "|" + to;
	}

	/** Stub lookup over an explicit {@code from|to -> travelTime} table; distance = tt * factor. */
	private static TravelSegmentLookup tableLookup(Map<String, Double> table, double distancePerSecond) {
		return (from, to, departureTime) -> {
			Double tt = table.get(from.toString() + "|" + to.toString());
			if (tt == null) {
				return TravelSegment.unreachable();
			}
			return new TravelSegment(tt, tt * distancePerSecond, 0.0);
		};
	}

	private static Map<Integer, Ride> byIndex(List<Ride> rides) {
		Map<Integer, Ride> byIndex = new HashMap<>();
		for (Ride r : rides) {
			byIndex.put(r.getIndex(), r);
		}
		return byIndex;
	}

	/** Like {@link #ride} but with the passenger index decoupled from the ride index, so two rides
	 *  can be made to carry the SAME passenger and exercise the disjointness test. */
	private static Ride sharedRequestRide(int rideIndex, int requestIndex, String originLink,
			String destLink, double startTime, double rideTravelTime) {
		return buildRide(rideIndex, requestIndex, originLink, destLink, startTime, rideTravelTime);
	}

	private static Ride ride(int rideIndex, String originLink, String destLink,
			double startTime, double rideTravelTime) {
		return buildRide(rideIndex, rideIndex, originLink, destLink, startTime, rideTravelTime);
	}

	private static Ride buildRide(int rideIndex, int requestIndex, String originLink, String destLink,
			double startTime, double rideTravelTime) {
		DrtRequest request = new DrtRequest.Builder()
				.index(requestIndex)
				.personId(Id.createPersonId("p" + requestIndex))
				.originLinkId(link(originLink))
				.destinationLinkId(link(destLink))
				.directTravelTime(0)
				.directDistance(0)
				.earliestDeparture(0)
				.latestArrival(Integer.MAX_VALUE)
				.build();
		return Ride.builder()
				.index(rideIndex)
				.degree(1)
				.kind(RideKind.SINGLE)
				.requests(new DrtRequest[] { request })
				.originsOrderedRequests(new DrtRequest[] { request })
				.destinationsOrderedRequests(new DrtRequest[] { request })
				.passengerTravelTimes(new double[] { rideTravelTime })
				.passengerDistances(new double[] { 0.0 })
				.passengerNetworkUtilities(new double[] { 0.0 })
				.delays(new double[] { 0.0 })
				.detours(new double[] { 1.0 })
				.connectionTravelTimes(new double[] { rideTravelTime })
				.connectionDistances(new double[] { 1000.0 })
				.connectionNetworkUtilities(new double[] { 0.0 })
				.startTime(startTime)
				.build();
	}
}
