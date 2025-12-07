package org.matsim.contrib.demand_extraction.algorithm.generation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideKind;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

/**
 * Generates FIFO and LIFO ride pairs with delay optimization.
 *
 * Uses deterministic parallel processing to ensure reproducible results.
 * Stores direct DrtRequest references in generated Rides (no index lookups needed).
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
	 * Generate pairs using deterministic parallel processing.
	 * Results are reproducible across runs with the same input.
	 */
	public List<Ride> generatePairs(List<DrtRequest> requests) {
		return generatePairs(requests.toArray(new DrtRequest[0]));
	}

	/**
	 * Generate pairs using deterministic parallel processing.
	 *
	 * Strategy for determinism:
	 * 1. Pre-count valid pairs per request (sequential, no network calls)
	 * 2. Pre-allocate index ranges for each request
	 * 3. Process requests in parallel, each writing to its reserved indices
	 * 4. Sort results by ride index to ensure deterministic order
	 *
	 * This ensures both deterministic indices AND deterministic list order.
	 */
	public List<Ride> generatePairs(DrtRequest[] requests) {
		log.info("Generating pair rides from {} requests (horizon={}s)...", requests.length, horizon);
		long startTime = System.currentTimeMillis();

		TimeFilter filter = new TimeFilter(requests);

		// Phase 1: Count potential pairs per request (sequential but fast - no network calls)
		int[] pairCountsPerRequest = new int[filter.size()];
		int totalPotentialPairs = 0;

		for (int i = 0; i < filter.size(); i++) {
			DrtRequest reqI = filter.getRequest(i);
			int[] candidates = filter.findCandidatesInHorizon(i, horizon);

			int validCount = 0;
			for (int j : candidates) {
				DrtRequest reqJ = filter.getRequest(j);

				// Same quick filters as in processing phase
				if (reqI.getPaxId().equals(reqJ.getPaxId())) continue;
				if (reqJ.getLatestDeparture() < reqI.getEarliestDeparture() ||
						reqJ.getEarliestDeparture() > reqI.getLatestDeparture() + reqI.getTravelTime()) {
					continue;
				}

				validCount++;
			}

			// Reserve space for up to 2 rides per valid candidate (FIFO + LIFO)
			pairCountsPerRequest[i] = validCount * 2;
			totalPotentialPairs += pairCountsPerRequest[i];
		}

		// Phase 2: Pre-allocate index ranges (sequential - ensures determinism)
		int[] indexStarts = new int[filter.size()];
		int currentIndex = requests.length; // Start after single rides
		for (int i = 0; i < filter.size(); i++) {
			indexStarts[i] = currentIndex;
			currentIndex += pairCountsPerRequest[i];
		}

		log.info("  Pre-allocated {} potential ride indices", totalPotentialPairs);

		// Phase 3: Parallel processing with pre-allocated indices
		AtomicInteger totalComparisons = new AtomicInteger(0);
		AtomicInteger temporalFiltered = new AtomicInteger(0);
		AtomicInteger samePerson = new AtomicInteger(0);
		AtomicInteger fifoAttempts = new AtomicInteger(0);
		AtomicInteger lifoAttempts = new AtomicInteger(0);
		AtomicInteger fifoCreated = new AtomicInteger(0);
		AtomicInteger lifoCreated = new AtomicInteger(0);
		AtomicInteger processedRequests = new AtomicInteger(0);
		int logInterval = Math.max(1, filter.size() / 10);

		// Pre-allocate array for all rides (thread-safe indexed writes)
		Ride[] allRides = new Ride[totalPotentialPairs];

		IntStream.range(0, filter.size()).parallel().forEach(i -> {
			// Progress logging
			int processed = processedRequests.incrementAndGet();
			if (processed % logInterval == 0) {
				double percent = (processed * 100.0) / filter.size();
				int createdSoFar = fifoCreated.get() + lifoCreated.get();
				log.info("  Pair generation progress: {}/{} ({}%) - ~{} pairs created",
						processed, filter.size(), String.format("%.1f", percent), createdSoFar);
			}

			DrtRequest reqI = filter.getRequest(i);
			int[] candidates = filter.findCandidatesInHorizon(i, horizon);
			int localRideIndex = indexStarts[i]; // This request's reserved index range

			// Pre-filter candidates
			List<Integer> validCandidates = new ArrayList<>();
			for (int j : candidates) {
				DrtRequest reqJ = filter.getRequest(j);
				totalComparisons.incrementAndGet();

				if (reqI.getPaxId().equals(reqJ.getPaxId())) {
					samePerson.incrementAndGet();
					continue;
				}

				if (reqJ.getLatestDeparture() < reqI.getEarliestDeparture() ||
						reqJ.getEarliestDeparture() > reqI.getLatestDeparture() + reqI.getTravelTime()) {
					temporalFiltered.incrementAndGet();
					continue;
				}

				validCandidates.add(j);
			}

			// Process valid candidates with reserved indices
			for (int j : validCandidates) {
				DrtRequest reqJ = filter.getRequest(j);

				TravelSegment oo = network.getSegment(reqI.originLinkId, reqJ.originLinkId, reqI.requestTime);
				if (!oo.isReachable()) continue;

				if (reqI.getLatestDeparture() + oo.getTravelTime() < reqJ.getEarliestDeparture()) continue;
				if (reqI.getEarliestDeparture() + oo.getTravelTime() > reqJ.getLatestDeparture()) continue;

				// Try FIFO with pre-allocated index
				fifoAttempts.incrementAndGet();
				Ride fifo = tryFifoWithSegment(reqI, reqJ, oo, localRideIndex);
				if (fifo != null) {
					Ride validated = budgetValidator.validateAndPopulateBudgets(fifo);
					if (validated != null) {
						allRides[localRideIndex - requests.length] = validated;
						localRideIndex++;
						fifoCreated.incrementAndGet();
					}
				}

				// Try LIFO with pre-allocated index
				lifoAttempts.incrementAndGet();
				Ride lifo = tryLifoWithSegment(reqI, reqJ, oo, localRideIndex);
				if (lifo != null) {
					Ride validated = budgetValidator.validateAndPopulateBudgets(lifo);
					if (validated != null) {
						allRides[localRideIndex - requests.length] = validated;
						localRideIndex++;
						lifoCreated.incrementAndGet();
					}
				}
			}
		});

		// Phase 4: Collect non-null rides and sort by index for deterministic order
		List<Ride> pairs = new ArrayList<>();
		for (Ride ride : allRides) {
			if (ride != null) {
				pairs.add(ride);
			}
		}
		// Sort by ride index to ensure deterministic order
		pairs.sort((r1, r2) -> Integer.compare(r1.getIndex(), r2.getIndex()));

		long elapsed = System.currentTimeMillis() - startTime;
		double seconds = elapsed / 1000.0;
		double pairsPerSecond = pairs.size() / Math.max(seconds, 0.001);
		log.info("Pair generation complete: {} pairs from {} requests in {}s ({} pairs/s)",
				pairs.size(), requests.length, String.format("%.1f", seconds), String.format("%.1f", pairsPerSecond));
		log.info("  Filtering: {} comparisons, {} same person, {} temporal filtered",
				totalComparisons.get(), samePerson.get(), temporalFiltered.get());
		log.info("  Attempts: {} FIFO ({} created), {} LIFO ({} created)",
				fifoAttempts.get(), fifoCreated.get(), lifoAttempts.get(), lifoCreated.get());

		return pairs;
	}

	/**
	 * Optimized FIFO generation that reuses pre-fetched Oi->Oj segment.
	 * Returns Ride with direct DrtRequest references.
	 */
	private Ride tryFifoWithSegment(DrtRequest i, DrtRequest j, TravelSegment oo, int index) {
		// oo (Oi -> Oj) already fetched and validated by caller
		TravelSegment od = network.getSegment(j.originLinkId, i.destinationLinkId, i.requestTime);
		TravelSegment dd = network.getSegment(i.destinationLinkId, j.destinationLinkId, i.requestTime);

		if (!od.isReachable() || !dd.isReachable())
			return null;

		// Temporal constraints already checked by caller

		double pttI = oo.getTravelTime() + od.getTravelTime();
		double pttJ = od.getTravelTime() + dd.getTravelTime();

		pttI = Math.max(pttI, i.getTravelTime());
		pttJ = Math.max(pttJ, j.getTravelTime());

		if (pttI > i.getMaxTravelTime() || pttJ > j.getMaxTravelTime()) return null;

		double detourI = pttI - i.getTravelTime();
		double detourJ = pttJ - j.getTravelTime();

		// Calculate effective delays (matching Python rides.py:196-238)
		double posAdjI = i.getPositiveDelayRelComponent() > 0.0
				? Math.max(0.0, i.getPositiveDelayRelComponent() - detourI)
				: 0.0;
		double posAdjJ = j.getPositiveDelayRelComponent() > 0.0
				? Math.max(0.0, j.getPositiveDelayRelComponent() - detourJ)
				: 0.0;
		double negAdjI = i.getNegativeDelayRelComponent() > 0.0
				? Math.max(0.0, i.getNegativeDelayRelComponent() - detourI)
				: 0.0;
		double negAdjJ = j.getNegativeDelayRelComponent() > 0.0
				? Math.max(0.0, j.getNegativeDelayRelComponent() - detourJ)
				: 0.0;

		double[] effMaxPos = {
				(i.getMaxPositiveDelay() - detourI) - posAdjI,
				(j.getMaxPositiveDelay() - detourJ) - posAdjJ
		};
		double[] effMaxNeg = {
				i.getMaxNegativeDelay() - negAdjI,
				j.getMaxNegativeDelay() - negAdjJ
		};

		double initialDelayJ = i.getRequestTime() + oo.getTravelTime() - j.getRequestTime();
		double[] delays = { 0.0, initialDelayJ };

		double[] adjusted = optimizeDelays(delays, effMaxNeg, effMaxPos);
		if (adjusted == null) return null;

		@SuppressWarnings("unchecked")
		Id<Link>[] origins = (Id<Link>[]) new Id[] { i.originLinkId, j.originLinkId };
		@SuppressWarnings("unchecked")
		Id<Link>[] destinations = (Id<Link>[]) new Id[] { i.destinationLinkId, j.destinationLinkId };

		return Ride.builder()
				.index(index)
				.degree(2)
				.kind(RideKind.FIFO)
				.requests(new DrtRequest[] { i, j })
				.originsOrdered(origins)
				.destinationsOrdered(destinations)
				.originsOrderedRequests(new DrtRequest[] { i, j })
				.destinationsOrderedRequests(new DrtRequest[] { i, j })
				.passengerTravelTimes(new double[] { pttI, pttJ })
				.passengerDistances(new double[] { oo.getDistance() + od.getDistance(), od.getDistance() + dd.getDistance() })
				.passengerNetworkUtilities(new double[] { oo.getNetworkUtility() + od.getNetworkUtility(), od.getNetworkUtility() + dd.getNetworkUtility() })
				.delays(adjusted)
				.connectionTravelTimes(new double[] { oo.getTravelTime(), od.getTravelTime(), dd.getTravelTime() })
				.connectionDistances(new double[] { oo.getDistance(), od.getDistance(), dd.getDistance() })
				.connectionNetworkUtilities(new double[] { oo.getNetworkUtility(), od.getNetworkUtility(), dd.getNetworkUtility() })
				.startTime(i.getRequestTime())
				.build();
	}

	/**
	 * Optimized LIFO generation that reuses pre-fetched Oi->Oj segment.
	 * Returns Ride with direct DrtRequest references.
	 */
	private Ride tryLifoWithSegment(DrtRequest i, DrtRequest j, TravelSegment oo, int index) {
		// oo (Oi -> Oj) already fetched and validated by caller
		TravelSegment oj = network.getSegment(j.originLinkId, j.destinationLinkId, i.requestTime);
		TravelSegment jd = network.getSegment(j.destinationLinkId, i.destinationLinkId, i.requestTime);

		if (!oj.isReachable() || !jd.isReachable())
			return null;

		// Temporal constraints already checked by caller

		double pttI = oo.getTravelTime() + oj.getTravelTime() + jd.getTravelTime();
		double pttJ = oj.getTravelTime();

		pttI = Math.max(pttI, i.getTravelTime());
		pttJ = Math.max(pttJ, j.getTravelTime());

		if (pttI > i.getMaxTravelTime() || pttJ > j.getMaxTravelTime()) return null;

		double detourI = pttI - i.getTravelTime();
		double detourJ = pttJ - j.getTravelTime();

		// Calculate effective delays (matching Python rides.py:309-351)
		double posAdjI = i.getPositiveDelayRelComponent() > 0.0
				? Math.max(0.0, i.getPositiveDelayRelComponent() - detourI)
				: 0.0;
		double posAdjJ = j.getPositiveDelayRelComponent() > 0.0
				? Math.max(0.0, j.getPositiveDelayRelComponent() - detourJ)
				: 0.0;
		double negAdjI = i.getNegativeDelayRelComponent() > 0.0
				? Math.max(0.0, i.getNegativeDelayRelComponent() - detourI)
				: 0.0;
		double negAdjJ = j.getNegativeDelayRelComponent() > 0.0
				? Math.max(0.0, j.getNegativeDelayRelComponent() - detourJ)
				: 0.0;

		double[] effMaxPos = {
				(i.getMaxPositiveDelay() - detourI) - posAdjI,
				(j.getMaxPositiveDelay() - detourJ) - posAdjJ
		};
		double[] effMaxNeg = {
				i.getMaxNegativeDelay() - negAdjI,
				j.getMaxNegativeDelay() - negAdjJ
		};

		double initialDelayJ = i.getRequestTime() + oo.getTravelTime() - j.getRequestTime();
		double[] delays = { 0.0, initialDelayJ };

		double[] adjusted = optimizeDelays(delays, effMaxNeg, effMaxPos);
		if (adjusted == null) return null;

		@SuppressWarnings("unchecked")
		Id<Link>[] origins = (Id<Link>[]) new Id[] { i.originLinkId, j.originLinkId };
		@SuppressWarnings("unchecked")
		Id<Link>[] destinations = (Id<Link>[]) new Id[] { j.destinationLinkId, i.destinationLinkId };

		return Ride.builder()
				.index(index)
				.degree(2)
				.kind(RideKind.LIFO)
				.requests(new DrtRequest[] { i, j })
				.originsOrdered(origins)
				.destinationsOrdered(destinations)
				.originsOrderedRequests(new DrtRequest[] { i, j })
				.destinationsOrderedRequests(new DrtRequest[] { j, i })
				.passengerTravelTimes(new double[] { pttI, pttJ })
				.passengerDistances(new double[] { oo.getDistance() + oj.getDistance() + jd.getDistance(), oj.getDistance() })
				.passengerNetworkUtilities(new double[] { oo.getNetworkUtility() + oj.getNetworkUtility() + jd.getNetworkUtility(), oj.getNetworkUtility() })
				.delays(adjusted)
				.connectionTravelTimes(new double[] { oo.getTravelTime(), oj.getTravelTime(), jd.getTravelTime() })
				.connectionDistances(new double[] { oo.getDistance(), oj.getDistance(), jd.getDistance() })
				.connectionNetworkUtilities(new double[] { oo.getNetworkUtility(), oj.getNetworkUtility(), jd.getNetworkUtility() })
				.startTime(i.getRequestTime())
				.build();
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
