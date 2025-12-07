package org.matsim.contrib.demand_extraction.algorithm.generation;

import java.util.ArrayList;
import java.util.List;

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
 * Simple sequential processing for deterministic results.
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
	 * Generate pairs sequentially for deterministic results.
	 */
	public List<Ride> generatePairs(List<DrtRequest> requests) {
		return generatePairs(requests.toArray(new DrtRequest[0]));
	}

	/**
	 * Generate FIFO and LIFO pairs from requests.
	 * Sequential processing ensures deterministic output order.
	 */
	public List<Ride> generatePairs(DrtRequest[] requests) {
		log.info("Generating pair rides from {} requests (horizon={}s)...", requests.length, horizon);
		long startTime = System.currentTimeMillis();

		TimeFilter filter = new TimeFilter(requests);
		List<Ride> pairs = new ArrayList<>();

		int nextRideIndex = requests.length; // Start after single rides
		int totalComparisons = 0;
		int temporalFiltered = 0;
		int samePerson = 0;
		int fifoAttempts = 0;
		int lifoAttempts = 0;
		int fifoCreated = 0;
		int lifoCreated = 0;

		int logInterval = Math.max(1, filter.size() / 10);

		for (int i = 0; i < filter.size(); i++) {
			if (i > 0 && i % logInterval == 0) {
				double percent = (i * 100.0) / filter.size();
				log.info("  Pair generation progress: {}/{} ({}%) - {} pairs created",
						i, filter.size(), String.format("%.1f", percent), pairs.size());
			}

			DrtRequest reqI = filter.getRequest(i);
			int[] candidates = filter.findCandidatesInHorizon(i, horizon);

			for (int j : candidates) {
				DrtRequest reqJ = filter.getRequest(j);
				totalComparisons++;

				// Skip same person
				if (reqI.getPaxId().equals(reqJ.getPaxId())) {
					samePerson++;
					continue;
				}

				// Quick temporal filter
				if (reqJ.getLatestDeparture() < reqI.getEarliestDeparture() ||
						reqJ.getEarliestDeparture() > reqI.getLatestDeparture() + reqI.getTravelTime()) {
					temporalFiltered++;
					continue;
				}

				// Get origin-to-origin segment
				TravelSegment oo = network.getSegment(reqI.originLinkId, reqJ.originLinkId, reqI.requestTime);
				if (!oo.isReachable()) continue;

				// Additional temporal check with travel time
				if (reqI.getLatestDeparture() + oo.getTravelTime() < reqJ.getEarliestDeparture()) continue;
				if (reqI.getEarliestDeparture() + oo.getTravelTime() > reqJ.getLatestDeparture()) continue;

				// Try FIFO
				fifoAttempts++;
				Ride fifo = tryFifo(reqI, reqJ, oo, nextRideIndex);
				if (fifo != null) {
					Ride validated = budgetValidator.validateAndPopulateBudgets(fifo);
					if (validated != null) {
						pairs.add(validated);
						nextRideIndex++;
						fifoCreated++;
					}
				}

				// Try LIFO
				lifoAttempts++;
				Ride lifo = tryLifo(reqI, reqJ, oo, nextRideIndex);
				if (lifo != null) {
					Ride validated = budgetValidator.validateAndPopulateBudgets(lifo);
					if (validated != null) {
						pairs.add(validated);
						nextRideIndex++;
						lifoCreated++;
					}
				}
			}
		}

		long elapsed = System.currentTimeMillis() - startTime;
		double seconds = elapsed / 1000.0;
		double pairsPerSecond = pairs.size() / Math.max(seconds, 0.001);
		log.info("Pair generation complete: {} pairs from {} requests in {}s ({} pairs/s)",
				pairs.size(), requests.length, String.format("%.1f", seconds), String.format("%.1f", pairsPerSecond));
		log.info("  Filtering: {} comparisons, {} same person, {} temporal filtered",
				totalComparisons, samePerson, temporalFiltered);
		log.info("  Attempts: {} FIFO ({} created), {} LIFO ({} created)",
				fifoAttempts, fifoCreated, lifoAttempts, lifoCreated);

		return pairs;
	}

	/**
	 * Try to create a FIFO ride (first pickup, first dropoff).
	 * Reuses pre-fetched Oi->Oj segment for efficiency.
	 */
	private Ride tryFifo(DrtRequest i, DrtRequest j, TravelSegment oo, int index) {
		TravelSegment od = network.getSegment(j.originLinkId, i.destinationLinkId, i.requestTime);
		TravelSegment dd = network.getSegment(i.destinationLinkId, j.destinationLinkId, i.requestTime);

		if (!od.isReachable() || !dd.isReachable())
			return null;

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
		return Ride.builder()
				.index(index)
				.degree(2)
				.kind(RideKind.FIFO)
				.requests(new DrtRequest[] { i, j })
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
	 * Try to create a LIFO ride (first pickup, last dropoff).
	 * Reuses pre-fetched Oi->Oj segment for efficiency.
	 */
	private Ride tryLifo(DrtRequest i, DrtRequest j, TravelSegment oo, int index) {
		TravelSegment oj = network.getSegment(j.originLinkId, j.destinationLinkId, i.requestTime);
		TravelSegment jd = network.getSegment(j.destinationLinkId, i.destinationLinkId, i.requestTime);

		if (!oj.isReachable() || !jd.isReachable())
			return null;

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
		return Ride.builder()
				.index(index)
				.degree(2)
				.kind(RideKind.LIFO)
				.requests(new DrtRequest[] { i, j })
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
