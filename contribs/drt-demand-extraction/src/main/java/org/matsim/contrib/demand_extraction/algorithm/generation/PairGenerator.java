package org.matsim.contrib.demand_extraction.algorithm.generation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideKind;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

/**
 * Generates FIFO and LIFO ride pairs with delay optimization.
 *
 * Uses parallel processing with deterministic output ordering.
 * Stores direct DrtRequest references in generated Rides.
 *
 * Python reference: rides.py lines 55-370
 */
public final class PairGenerator {
	private static final Logger log = LogManager.getLogger(PairGenerator.class);

	private final MatsimNetworkCache network;
	private final BudgetValidator budgetValidator;
	private final double horizon;
	private static final double EPSILON = 1e-9;

	public PairGenerator(MatsimNetworkCache network, BudgetValidator budgetValidator, double horizon) {
		this.network = network;
		this.budgetValidator = budgetValidator;
		this.horizon = horizon;
	}

	/**
	 * Generate pairs with parallel processing and deterministic results.
	 */
	public List<Ride> generatePairs(List<DrtRequest> requests) {
		return generatePairs(requests.toArray(new DrtRequest[0]));
	}

	/**
	 * Intermediate candidate holding ride data before index assignment.
	 * Used for parallel collection followed by deterministic sorting.
	 */
	private record PairCandidate(
			DrtRequest reqI, DrtRequest reqJ, RideKind kind,
			DrtRequest[] originsOrderedRequests, DrtRequest[] destinationsOrderedRequests,
			double[] passengerTravelTimes, double[] passengerDistances,
			double[] passengerNetworkUtilities, double[] delays, double[] detours,
			double[] connectionTravelTimes, double[] connectionDistances,
			double[] connectionNetworkUtilities, double startTime) {

		/**
		 * Comparator for deterministic ordering: by (reqI.index, reqJ.index, kind).
		 */
		static final Comparator<PairCandidate> COMPARATOR = Comparator
				.comparingInt((PairCandidate c) -> c.reqI.index)
				.thenComparingInt(c -> c.reqJ.index)
				.thenComparing(c -> c.kind);
	}

	/**
	 * Generate FIFO and LIFO pairs from requests.
	 * Parallel processing with deterministic output order.
	 */
	public List<Ride> generatePairs(DrtRequest[] requests) {
		log.info("Generating pair rides from {} requests (horizon={}s) [parallel]...", requests.length, horizon);
		long startTime = System.currentTimeMillis();

		TimeFilter filter = new TimeFilter(requests);
		AtomicInteger processedRequests = new AtomicInteger(0);
		int total = filter.size();
		int logInterval = Math.max(1, total / 10);

		// Phase 1: Parallel collection of candidates (without indices)
		List<PairCandidate> candidates = IntStream.range(0, total)
				.parallel()
				.mapToObj(i -> {
					int processed = processedRequests.incrementAndGet();
					if (processed % logInterval == 0) {
						double percent = (processed * 100.0) / total;
						log.info("  Pair generation progress: {}/{} ({}%)",
								processed, total, String.format("%.1f", percent));
					}
					return generateCandidatesForRequest(filter, i);
				})
				.flatMap(List::stream)
				.collect(Collectors.toList());

		// Phase 2: Sort deterministically by (reqI.index, reqJ.index, kind)
		candidates.sort(PairCandidate.COMPARATOR);

		// Phase 3: Validate and assign indices sequentially
		List<Ride> pairs = new ArrayList<>();
		int nextRideIndex = requests.length; // Start after single rides
		int fifoCreated = 0;
		int lifoCreated = 0;

		for (PairCandidate c : candidates) {
			Ride ride = buildRide(c, nextRideIndex);
			Ride validated = budgetValidator.validateAndPopulateBudgets(ride);
			if (validated != null) {
				pairs.add(validated);
				nextRideIndex++;
				if (c.kind == RideKind.FIFO) fifoCreated++;
				else lifoCreated++;
			}
		}

		long elapsed = System.currentTimeMillis() - startTime;
		double seconds = elapsed / 1000.0;
		double pairsPerSecond = pairs.size() / Math.max(seconds, 0.001);
		log.info("Pair generation complete: {} pairs from {} requests in {}s ({} pairs/s)",
				pairs.size(), requests.length, String.format("%.1f", seconds), String.format("%.1f", pairsPerSecond));
		log.info("  Created: {} FIFO, {} LIFO (from {} candidates)",
				fifoCreated, lifoCreated, candidates.size());

		return pairs;
	}

	/**
	 * Generate all valid candidates for a single request index.
	 */
	private List<PairCandidate> generateCandidatesForRequest(TimeFilter filter, int i) {
		List<PairCandidate> results = new ArrayList<>();
		DrtRequest reqI = filter.getRequest(i);
		int[] candidateIndices = filter.findCandidatesInHorizon(i, horizon);

		for (int j : candidateIndices) {
			DrtRequest reqJ = filter.getRequest(j);

			// Skip same person
			if (reqI.getPaxId().equals(reqJ.getPaxId())) continue;

			// Quick temporal filter
			if (reqJ.getLatestDeparture() < reqI.getEarliestDeparture() ||
					reqJ.getEarliestDeparture() > reqI.getLatestDeparture() + reqI.getTravelTime()) {
				continue;
			}

			// Get origin-to-origin segment
			TravelSegment oo = network.getSegment(reqI.originLinkId, reqJ.originLinkId, reqI.requestTime);
			if (!oo.isReachable()) continue;

			// Additional temporal check with travel time
			if (reqI.getLatestDeparture() + oo.getTravelTime() < reqJ.getEarliestDeparture()) continue;
			if (reqI.getEarliestDeparture() + oo.getTravelTime() > reqJ.getLatestDeparture()) continue;

			// Try FIFO
			PairCandidate fifo = tryFifoCandidate(reqI, reqJ, oo);
			if (fifo != null) results.add(fifo);

			// Try LIFO
			PairCandidate lifo = tryLifoCandidate(reqI, reqJ, oo);
			if (lifo != null) results.add(lifo);
		}

		return results;
	}

	/**
	 * Build final Ride from candidate with assigned index.
	 */
	private Ride buildRide(PairCandidate c, int index) {
		return Ride.builder()
				.index(index)
				.degree(2)
				.kind(c.kind)
				.requests(new DrtRequest[] { c.reqI, c.reqJ })
				.originsOrderedRequests(c.originsOrderedRequests)
				.destinationsOrderedRequests(c.destinationsOrderedRequests)
				.passengerTravelTimes(c.passengerTravelTimes)
				.passengerDistances(c.passengerDistances)
				.passengerNetworkUtilities(c.passengerNetworkUtilities)
				.delays(c.delays)
				.detours(c.detours)
				.connectionTravelTimes(c.connectionTravelTimes)
				.connectionDistances(c.connectionDistances)
				.connectionNetworkUtilities(c.connectionNetworkUtilities)
				.startTime(c.startTime)
				.build();
	}

	/**
	 * Try to create a FIFO candidate (first pickup, first dropoff).
	 */
	private PairCandidate tryFifoCandidate(DrtRequest i, DrtRequest j, TravelSegment oo) {
		TravelSegment od = network.getSegment(j.originLinkId, i.destinationLinkId, i.requestTime);
		TravelSegment dd = network.getSegment(i.destinationLinkId, j.destinationLinkId, i.requestTime);

		if (!od.isReachable() || !dd.isReachable()) return null;

		double pttI = oo.getTravelTime() + od.getTravelTime();
		double pttJ = od.getTravelTime() + dd.getTravelTime();

		pttI = Math.max(pttI, i.getTravelTime());
		pttJ = Math.max(pttJ, j.getTravelTime());

		if (pttI > i.getMaxTravelTime() || pttJ > j.getMaxTravelTime()) return null;

		double detourI = pttI - i.getTravelTime();
		double detourJ = pttJ - j.getTravelTime();

		double[] effMaxPos = calculateEffectiveMaxPos(i, j, detourI, detourJ);
		double[] effMaxNeg = calculateEffectiveMaxNeg(i, j, detourI, detourJ);

		double initialDelayJ = i.getRequestTime() + oo.getTravelTime() - j.getRequestTime();
		double[] delays = { 0.0, initialDelayJ };

		double[] adjusted = optimizeDelays(delays, effMaxNeg, effMaxPos);
		if (adjusted == null) return null;

		// FIFO: pickup order [i, j], dropoff order [i, j]
		return new PairCandidate(
				i, j, RideKind.FIFO,
				new DrtRequest[] { i, j },
				new DrtRequest[] { i, j },
				new double[] { pttI, pttJ },
				new double[] { oo.getDistance() + od.getDistance(), od.getDistance() + dd.getDistance() },
				new double[] { oo.getNetworkUtility() + od.getNetworkUtility(), od.getNetworkUtility() + dd.getNetworkUtility() },
				adjusted,
				new double[] { detourI, detourJ },
				new double[] { oo.getTravelTime(), od.getTravelTime(), dd.getTravelTime() },
				new double[] { oo.getDistance(), od.getDistance(), dd.getDistance() },
				new double[] { oo.getNetworkUtility(), od.getNetworkUtility(), dd.getNetworkUtility() },
				i.getRequestTime());
	}

	/**
	 * Try to create a LIFO candidate (first pickup, last dropoff).
	 */
	private PairCandidate tryLifoCandidate(DrtRequest i, DrtRequest j, TravelSegment oo) {
		TravelSegment oj = network.getSegment(j.originLinkId, j.destinationLinkId, i.requestTime);
		TravelSegment jd = network.getSegment(j.destinationLinkId, i.destinationLinkId, i.requestTime);

		if (!oj.isReachable() || !jd.isReachable()) return null;

		double pttI = oo.getTravelTime() + oj.getTravelTime() + jd.getTravelTime();
		double pttJ = oj.getTravelTime();

		pttI = Math.max(pttI, i.getTravelTime());
		pttJ = Math.max(pttJ, j.getTravelTime());

		if (pttI > i.getMaxTravelTime() || pttJ > j.getMaxTravelTime()) return null;

		double detourI = pttI - i.getTravelTime();
		double detourJ = pttJ - j.getTravelTime();

		double[] effMaxPos = calculateEffectiveMaxPos(i, j, detourI, detourJ);
		double[] effMaxNeg = calculateEffectiveMaxNeg(i, j, detourI, detourJ);

		double initialDelayJ = i.getRequestTime() + oo.getTravelTime() - j.getRequestTime();
		double[] delays = { 0.0, initialDelayJ };

		double[] adjusted = optimizeDelays(delays, effMaxNeg, effMaxPos);
		if (adjusted == null) return null;

		// LIFO: pickup order [i, j], dropoff order [j, i]
		return new PairCandidate(
				i, j, RideKind.LIFO,
				new DrtRequest[] { i, j },
				new DrtRequest[] { j, i },
				new double[] { pttI, pttJ },
				new double[] { oo.getDistance() + oj.getDistance() + jd.getDistance(), oj.getDistance() },
				new double[] { oo.getNetworkUtility() + oj.getNetworkUtility() + jd.getNetworkUtility(), oj.getNetworkUtility() },
				adjusted,
				new double[] { detourI, detourJ },
				new double[] { oo.getTravelTime(), oj.getTravelTime(), jd.getTravelTime() },
				new double[] { oo.getDistance(), oj.getDistance(), jd.getDistance() },
				new double[] { oo.getNetworkUtility(), oj.getNetworkUtility(), jd.getNetworkUtility() },
				i.getRequestTime());
	}

	private double[] calculateEffectiveMaxPos(DrtRequest i, DrtRequest j, double detourI, double detourJ) {
		double posAdjI = i.getPositiveDelayRelComponent() > 0.0
				? Math.max(0.0, i.getPositiveDelayRelComponent() - detourI) : 0.0;
		double posAdjJ = j.getPositiveDelayRelComponent() > 0.0
				? Math.max(0.0, j.getPositiveDelayRelComponent() - detourJ) : 0.0;
		return new double[] {
				(i.getMaxPositiveDelay() - detourI) - posAdjI,
				(j.getMaxPositiveDelay() - detourJ) - posAdjJ
		};
	}

	private double[] calculateEffectiveMaxNeg(DrtRequest i, DrtRequest j, double detourI, double detourJ) {
		double negAdjI = i.getNegativeDelayRelComponent() > 0.0
				? Math.max(0.0, i.getNegativeDelayRelComponent() - detourI) : 0.0;
		double negAdjJ = j.getNegativeDelayRelComponent() > 0.0
				? Math.max(0.0, j.getNegativeDelayRelComponent() - detourJ) : 0.0;
		return new double[] {
				i.getMaxNegativeDelay() - negAdjI,
				j.getMaxNegativeDelay() - negAdjJ
		};
	}

	private double[] optimizeDelays(double[] delays, double[] maxNeg, double[] maxPos) {
		// Check initial feasibility
		for (int i = 0; i < delays.length; i++) {
			if (maxPos[i] < -maxNeg[i]) return null;
		}

		// Calculate bounds
		double lower = Double.NEGATIVE_INFINITY;
		double upper = Double.POSITIVE_INFINITY;

		for (int i = 0; i < delays.length; i++) {
			lower = Math.max(lower, -delays[i] - maxNeg[i]);
			upper = Math.min(upper, maxPos[i] - delays[i]);
		}

		if (lower > upper + EPSILON) return null;

		// Find optimal departure adjustment
		double maxDelay = Double.NEGATIVE_INFINITY;
		double minDelay = Double.POSITIVE_INFINITY;
		for (double d : delays) {
			maxDelay = Math.max(maxDelay, d);
			minDelay = Math.min(minDelay, d);
		}

		double depOpt = -(maxDelay + minDelay) / 2.0;
		depOpt = Math.max(lower, Math.min(upper, depOpt));

		// Apply adjustment
		double[] adjusted = new double[delays.length];
		for (int i = 0; i < delays.length; i++) {
			adjusted[i] = delays[i] + depOpt;
			if (adjusted[i] < -maxNeg[i] - EPSILON || adjusted[i] > maxPos[i] + EPSILON) {
				return null;
			}
		}

		return adjusted;
	}
}
