package org.matsim.contrib.demand_extraction.algorithm.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideKind;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

/**
 * Unit tests for {@link PostExtensionPruner}. Covers:
 * <ul>
 *   <li>RATIO_THRESHOLD mode == legacy behaviour (top fraction by ratio per degree)</li>
 *   <li>COVERAGE_TOPK basics: per-request cap, high-quality rides picked first</li>
 *   <li>Singles always pass through unchanged</li>
 *   <li>Empty input returns empty output (no NPE)</li>
 *   <li>K larger than any degree's ride count keeps all rides at that degree</li>
 *   <li>Invalid factory args rejected</li>
 * </ul>
 */
class PostExtensionPrunerTest {

	// ─── Test fixtures ───────────────────────────────────────────────────────

	/** Build a request with only the fields the pruner touches. */
	private static DrtRequest req(int index, double directDistance) {
		return new DrtRequest.Builder()
				.index(index)
				.personId(Id.createPersonId("p" + index))
				.directDistance(directDistance)
				.directTravelTime(0)
				.earliestDeparture(0)
				.latestArrival(0)
				.build();
	}

	/**
	 * Build a ride with minimum required fields. Pruner only reads degree,
	 * rideDistance, and per-request index + directDistance.
	 */
	private static Ride ride(int index, DrtRequest[] requests, double rideDistance) {
		int degree = requests.length;
		double[] zeros = new double[degree];
		RideKind kind = degree == 1 ? RideKind.SINGLE : RideKind.FIFO;
		return Ride.builder()
				.index(index)
				.degree(degree)
				.kind(kind)
				.requests(requests)
				.originsOrderedRequests(requests)
				.destinationsOrderedRequests(requests)
				.passengerTravelTimes(zeros)
				.passengerDistances(zeros)
				.passengerNetworkUtilities(zeros)
				.delays(zeros)
				.detours(zeros)
				.connectionTravelTimes(new double[] { 0.0 })
				.connectionDistances(new double[] { rideDistance })
				.connectionNetworkUtilities(new double[] { 0.0 })
				.startTime(0)
				.build();
	}

	// ─── Factory / validation ────────────────────────────────────────────────

	@Test
	void coverageTopK_rejectsKBelowOne() {
		assertThrows(IllegalArgumentException.class,
				() -> PostExtensionPruner.coverageTopK(0, PostExtensionPruner.ABS_SAVINGS));
	}

	@Test
	void coverageTopK_rejectsNullMetric() {
		assertThrows(IllegalArgumentException.class,
				() -> PostExtensionPruner.coverageTopK(5, null));
	}

	// ─── Empty / single-ride ─────────────────────────────────────────────────

	@Test
	void emptyInput_returnsEmpty() {
		List<Ride> empty = Collections.emptyList();
		assertTrue(PostExtensionPruner.ratioThreshold(0.1).prune(empty).isEmpty());
		assertTrue(PostExtensionPruner.coverageTopK(20, PostExtensionPruner.ABS_SAVINGS).prune(empty).isEmpty());
	}

	@Test
	void singles_alwaysPassThroughUnchanged() {
		// Degree-1 rides must never be pruned by either mode.
		DrtRequest r0 = req(0, 1000);
		DrtRequest r1 = req(1, 1000);
		Ride s0 = ride(0, new DrtRequest[] { r0 }, 1000);
		Ride s1 = ride(1, new DrtRequest[] { r1 }, 1000);
		List<Ride> rides = List.of(s0, s1);

		List<Ride> keptRatio = PostExtensionPruner.ratioThreshold(0.01).prune(rides);
		List<Ride> keptCov = PostExtensionPruner.coverageTopK(1, PostExtensionPruner.ABS_SAVINGS).prune(rides);

		assertEquals(2, keptRatio.size(), "ratio mode must not prune singles");
		assertEquals(2, keptCov.size(), "coverage mode must not prune singles");
	}

	// ─── RATIO_THRESHOLD (legacy) ────────────────────────────────────────────

	@Test
	void ratioThreshold_keepsFraction1_returnsAll() {
		List<Ride> rides = makeDegreeTwoRides();
		List<Ride> kept = PostExtensionPruner.ratioThreshold(1.0).prune(rides);
		assertEquals(rides.size(), kept.size(), "keepTopFraction=1.0 must be a no-op");
	}

	@Test
	void ratioThreshold_keepsTopHalfByRatio() {
		// 4 rides, distinct savingsRatios. top-50% = 2 highest-ratio rides.
		DrtRequest[] rs = makeRequests(4, /*dist*/ 1000);
		List<Ride> rides = new ArrayList<>();
		rides.add(ride(0, new DrtRequest[] { rs[0], rs[1] }, 1900));  // ratio 0.05
		rides.add(ride(1, new DrtRequest[] { rs[0], rs[2] }, 1400));  // ratio 0.30
		rides.add(ride(2, new DrtRequest[] { rs[1], rs[2] }, 1800));  // ratio 0.10
		rides.add(ride(3, new DrtRequest[] { rs[2], rs[3] }, 1000));  // ratio 0.50

		List<Ride> kept = PostExtensionPruner.ratioThreshold(0.5).prune(rides);
		Set<Integer> keptIdx = rideIndices(kept);
		assertEquals(Set.of(1, 3), keptIdx, "top-50% must be the 2 highest-ratio rides");
	}

	// ─── COVERAGE_TOPK basics ────────────────────────────────────────────────

	@Test
	void coverageTopK_keepsAllRidesWhenKExceedsGroupSize() {
		// K=100, only 4 rides exist. Every ride passes the cap trivially.
		List<Ride> rides = makeDegreeTwoRides();
		List<Ride> kept = PostExtensionPruner.coverageTopK(100, PostExtensionPruner.ABS_SAVINGS).prune(rides);
		assertEquals(rides.size(), kept.size(), "K > group size must keep all rides at that degree");
	}

	@Test
	void coverageTopK_capsPerRequestAtK_whenNoFreshPartnersRemain() {
		// 10 rides on the SAME request pair (r0, r1). After K=2 keeps both caps fill;
		// remaining 8 rides must be rejected (no fresh partners to justify a keep).
		DrtRequest r0 = req(0, 1000);
		DrtRequest r1 = req(1, 1000);
		List<Ride> rides = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			// Ride i has absSavings = 2000 - (1000 + i*50); ride 0 = highest quality.
			rides.add(ride(i, new DrtRequest[] { r0, r1 }, 1000 + i * 50));
		}

		List<Ride> kept = PostExtensionPruner.coverageTopK(2, PostExtensionPruner.ABS_SAVINGS).prune(rides);

		assertEquals(2, kept.size(), "with no fresh partners, cap K=2 must hold strictly");
		Set<Integer> keptIdx = rideIndices(kept);
		assertEquals(Set.of(0, 1), keptIdx, "top-2 by absSavings must be selected");
	}

	@Test
	void coverageTopK_keepsFreshPartnerRides_evenAfterCappedRequestIsFull() {
		// 10 rides all include r0, each paired with a unique r1..r10. With K=2 and fresh
		// partners, the cap on r0 does NOT block a ride as long as its OTHER request
		// still has slack — this is the intended "coverage floor, not hard cap" semantic
		// that makes the algorithm deliver median ~K options to the MIP optimizer.
		DrtRequest r0 = req(0, 1000);
		List<Ride> rides = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			DrtRequest partner = req(i + 1, 1000);
			rides.add(ride(i, new DrtRequest[] { r0, partner }, 1000 + i * 50));
		}

		List<Ride> kept = PostExtensionPruner.coverageTopK(2, PostExtensionPruner.ABS_SAVINGS).prune(rides);

		assertEquals(10, kept.size(),
				"every ride keeps coverage thanks to its unique partner request (slack > 0)");
		long r0Count = kept.stream()
				.flatMap(r -> Arrays.stream(r.getRequests()))
				.filter(q -> q.index == 0)
				.count();
		assertTrue(r0Count >= 2,
				"r0 must reach at least K=2 (its dedicated cap) — got " + r0Count);
	}

	@Test
	void coverageTopK_coversEveryReachableRequest() {
		// 5 requests each appearing in 3 rides (with partner rotation).
		// K=1 means each request keeps exactly 1 ride. Every request must be covered.
		DrtRequest[] rs = makeRequests(5, 1000);
		List<Ride> rides = new ArrayList<>();
		int rideIdx = 0;
		for (int i = 0; i < 5; i++) {
			for (int j = i + 1; j < 5; j++) {
				// rideDistance varies — rides farther from i+j pair have lower quality
				double rideDist = 1500 + (i + j) * 20;
				rides.add(ride(rideIdx++, new DrtRequest[] { rs[i], rs[j] }, rideDist));
			}
		}

		List<Ride> kept = PostExtensionPruner.coverageTopK(1, PostExtensionPruner.ABS_SAVINGS).prune(rides);

		Set<Integer> coveredReqs = new HashSet<>();
		for (Ride r : kept) {
			for (DrtRequest req : r.getRequests()) coveredReqs.add(req.index);
		}
		assertEquals(5, coveredReqs.size(), "K=1 must still cover every request with at least one ride");
	}

	// ─── Multi-degree integrity ──────────────────────────────────────────────

	@Test
	void multiDegree_coverageKAppliedIndependentlyPerDegree() {
		// A ride at degree 2 and a ride at degree 3 both using request 0.
		// Both should be kept: per-degree cov[] resets, so they don't compete.
		DrtRequest r0 = req(0, 1000);
		DrtRequest r1 = req(1, 1000);
		DrtRequest r2 = req(2, 1000);
		Ride deg2 = ride(0, new DrtRequest[] { r0, r1 }, 1500);
		Ride deg3 = ride(1, new DrtRequest[] { r0, r1, r2 }, 2500);

		List<Ride> kept = PostExtensionPruner.coverageTopK(1, PostExtensionPruner.ABS_SAVINGS)
				.prune(List.of(deg2, deg3));

		assertEquals(2, kept.size(), "per-degree reset must keep one ride per degree even with K=1");
	}

	// ─── helpers ─────────────────────────────────────────────────────────────

	private static DrtRequest[] makeRequests(int n, double directDistance) {
		DrtRequest[] rs = new DrtRequest[n];
		for (int i = 0; i < n; i++) rs[i] = req(i, directDistance);
		return rs;
	}

	private static List<Ride> makeDegreeTwoRides() {
		DrtRequest[] rs = makeRequests(4, 1000);
		List<Ride> rides = new ArrayList<>();
		rides.add(ride(0, new DrtRequest[] { rs[0], rs[1] }, 1800));
		rides.add(ride(1, new DrtRequest[] { rs[0], rs[2] }, 1500));
		rides.add(ride(2, new DrtRequest[] { rs[1], rs[2] }, 1700));
		rides.add(ride(3, new DrtRequest[] { rs[2], rs[3] }, 1200));
		return rides;
	}

	private static Set<Integer> rideIndices(List<Ride> rides) {
		Set<Integer> s = new HashSet<>();
		for (Ride r : rides) s.add(r.getIndex());
		return s;
	}

	private static int coveredRequests(List<Ride> rides) {
		Set<Integer> s = new HashSet<>();
		for (Ride r : rides) {
			for (DrtRequest req : r.getRequests()) s.add(req.index);
		}
		return s.size();
	}
}
