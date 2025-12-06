package org.matsim.contrib.demand_extraction.algorithm.generation;

import java.util.ArrayList;
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
     * Generate pairs with optional parallel processing.
     * Set useParallel=true for faster generation (non-deterministic order in list but deterministic indices).
     * Set useParallel=false for fully deterministic behavior.
     */
    public List<Ride> generatePairs(DrtRequest[] requests) {
		return generatePairs(requests, false); // Default to sequential for determinism
	}

	public List<Ride> generatePairs(DrtRequest[] requests, boolean useParallel) {
		if (useParallel) {
			return generatePairsParallel(requests);
		} else {
			return generatePairsSequential(requests);
		}
	}

	/**
	 * Sequential pair generation - fully deterministic results.
	 */
    private List<Ride> generatePairsSequential(DrtRequest[] requests) {
		log.info("Generating pair rides from {} requests (horizon={}s)...", requests.length, horizon);
		long startTime = System.currentTimeMillis();

		TimeFilter filter = new TimeFilter(requests);
		List<Ride> pairs = new ArrayList<>();
		int rideIndex = requests.length; // Start after single rides

		// Statistics tracking
		int totalComparisons = 0;
		int temporalFiltered = 0;
		int samePerson = 0;
		int fifoAttempts = 0;
		int lifoAttempts = 0;
		int fifoCreated = 0;
		int lifoCreated = 0;
		int logInterval = Math.max(1, filter.size() / 10);

		// Sequential processing to maintain deterministic ride indices
		for (int i = 0; i < filter.size(); i++) {
			// Progress logging
			if ((i + 1) % logInterval == 0 && i > 0) {
				double percent = ((i + 1) * 100.0) / filter.size();
				log.info("  Pair generation progress: {}/{} ({}%) - {} pairs found",
						(i + 1), filter.size(), String.format("%.1f", percent), pairs.size());
			}

            DrtRequest reqI = filter.getRequest(i);
            int[] candidates = filter.findCandidatesInHorizon(i, horizon);

			// Pre-filter candidates (matches Python approach - lines 89-95)
			// This avoids expensive network calls for obviously incompatible pairs
			List<Integer> validCandidates = new ArrayList<>();
			for (int j : candidates) {
				DrtRequest reqJ = filter.getRequest(j);
				totalComparisons++;

				// Quick filter: same person
				if (reqI.getPaxId().equals(reqJ.getPaxId())) {
					samePerson++;
					continue;
				}

				// Temporal window constraint (Python rides.py:89-92)
				// Filter BEFORE any network calls (major performance win)
				if (reqJ.getLatestDeparture() < reqI.getEarliestDeparture() ||
						reqJ.getEarliestDeparture() > reqI.getLatestDeparture() + reqI.getTravelTime()) {
					temporalFiltered++;
					continue;
				}

				validCandidates.add(j);
			}

			// Only process candidates that passed temporal filtering
			for (int j : validCandidates) {
				DrtRequest reqJ = filter.getRequest(j);

				// Pre-fetch common segment (Oi -> Oj) used by both FIFO and LIFO
				// Reusing this segment is a major optimization
				TravelSegment oo = network.getSegment(reqI.originLinkId, reqJ.originLinkId, reqI.requestTime);
				if (!oo.isReachable())
					continue; // Both FIFO and LIFO need this

				// Second temporal constraint with actual travel time (Python rides.py:120-122)
				if (reqI.getLatestDeparture() + oo.getTravelTime() < reqJ.getEarliestDeparture())
					continue;
				if (reqI.getEarliestDeparture() + oo.getTravelTime() > reqJ.getLatestDeparture())
					continue;

                // Try FIFO: Oi -> Oj -> Di -> Dj
				fifoAttempts++;
				Ride fifo = tryFifoWithSegment(reqI, reqJ, oo, rideIndex);
                if (fifo != null) {
                    // Validate budgets before adding
                    Ride validated = budgetValidator.validateAndPopulateBudgets(fifo, requests);
                    if (validated != null) {
						pairs.add(validated);
						rideIndex++;
						fifoCreated++;
                    }
                }

                // Try LIFO: Oi -> Oj -> Dj -> Di
				lifoAttempts++;
				Ride lifo = tryLifoWithSegment(reqI, reqJ, oo, rideIndex);
                if (lifo != null) {
                    // Validate budgets before adding
                    Ride validated = budgetValidator.validateAndPopulateBudgets(lifo, requests);
                    if (validated != null) {
						pairs.add(validated);
						rideIndex++;
						lifoCreated++;
					}
				}
			}
		}

		long elapsed = System.currentTimeMillis() - startTime;
		double seconds = elapsed / 1000.0;
		double pairsPerSecond = pairs.size() / seconds;
		log.info("Pair generation complete: {} pairs from {} requests in {}s ({} pairs/s)",
				pairs.size(), requests.length, String.format("%.1f", seconds), String.format("%.1f", pairsPerSecond));
		log.info("  Filtering: {} comparisons, {} same person, {} temporal filtered",
				totalComparisons, samePerson, temporalFiltered);
		log.info("  Attempts: {} FIFO ({} created), {} LIFO ({} created)",
				fifoAttempts, fifoCreated, lifoAttempts, lifoCreated);

        return pairs;
    }

	/**
	 * Parallel pair generation with deterministic indices.
	 * 
	 * Strategy: Pre-allocate index ranges for each request in a sequential phase,
	 * then process requests in parallel using their reserved index ranges.
	 * This ensures:
	 * 1. Deterministic ride indices (same indices across runs)
	 * 2. Thread-safe parallel processing
	 * 3. Fast execution through parallelization
	 * 
	 * Note: List order may vary between runs, but ride indices are stable.
	 */
	private List<Ride> generatePairsParallel(DrtRequest[] requests) {
		log.info("Generating pair rides from {} requests (horizon={}s) [PARALLEL MODE]...", requests.length, horizon);
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
				
				// Same quick filters as before
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
					Ride validated = budgetValidator.validateAndPopulateBudgets(fifo, requests);
					if (validated != null) {
						allRides[localRideIndex - requests.length] = validated; // Array write is thread-safe with pre-allocated indices
						localRideIndex++;
						fifoCreated.incrementAndGet();
					}
				}

				// Try LIFO with pre-allocated index
				lifoAttempts.incrementAndGet();
				Ride lifo = tryLifoWithSegment(reqI, reqJ, oo, localRideIndex);
				if (lifo != null) {
					Ride validated = budgetValidator.validateAndPopulateBudgets(lifo, requests);
					if (validated != null) {
						allRides[localRideIndex - requests.length] = validated;
						localRideIndex++;
						lifoCreated.incrementAndGet();
					}
				}
			}
		});

		// Phase 4: Collect non-null rides (sequential)
		List<Ride> pairs = new ArrayList<>();
		for (Ride ride : allRides) {
			if (ride != null) {
				pairs.add(ride);
			}
		}

		long elapsed = System.currentTimeMillis() - startTime;
		double seconds = elapsed / 1000.0;
		double pairsPerSecond = pairs.size() / seconds;
		log.info("Pair generation complete: {} pairs from {} requests in {}s ({} pairs/s) [PARALLEL]",
				pairs.size(), requests.length, String.format("%.1f", seconds), String.format("%.1f", pairsPerSecond));
		log.info("  Filtering: {} comparisons, {} same person, {} temporal filtered",
				totalComparisons.get(), samePerson.get(), temporalFiltered.get());
		log.info("  Attempts: {} FIFO ({} created), {} LIFO ({} created)",
				fifoAttempts.get(), fifoCreated.get(), lifoAttempts.get(), lifoCreated.get());

		return pairs;
	}

	/**
	 * Optimized FIFO generation that reuses pre-fetched Oi->Oj segment.
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
        double[] delays = {0.0, initialDelayJ};

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
            .requestIndices(new int[]{i.index, j.index})
				.originsOrdered(origins)
				.destinationsOrdered(destinations)
            .originsIndex(new int[]{i.index, j.index})
            .destinationsIndex(new int[]{i.index, j.index})
            .passengerTravelTimes(new double[]{pttI, pttJ})
            .passengerDistances(new double[]{oo.getDistance() + od.getDistance(), od.getDistance() + dd.getDistance()})
            .passengerNetworkUtilities(new double[]{oo.getNetworkUtility() + od.getNetworkUtility(), od.getNetworkUtility() + dd.getNetworkUtility()})
            .delays(adjusted)
            .connectionTravelTimes(new double[]{oo.getTravelTime(), od.getTravelTime(), dd.getTravelTime()})
            .connectionDistances(new double[]{oo.getDistance(), od.getDistance(), dd.getDistance()})
            .connectionNetworkUtilities(new double[]{oo.getNetworkUtility(), od.getNetworkUtility(), dd.getNetworkUtility()})
            .startTime(i.getRequestTime())
            .build();
    }

	/**
	 * Optimized LIFO generation that reuses pre-fetched Oi->Oj segment.
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
        double[] delays = {0.0, initialDelayJ};

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
            .requestIndices(new int[]{i.index, j.index})
				.originsOrdered(origins)
				.destinationsOrdered(destinations)
            .originsIndex(new int[]{i.index, j.index})
            .destinationsIndex(new int[]{j.index, i.index})
            .passengerTravelTimes(new double[]{pttI, pttJ})
            .passengerDistances(new double[]{oo.getDistance() + oj.getDistance() + jd.getDistance(), oj.getDistance()})
            .passengerNetworkUtilities(new double[]{oo.getNetworkUtility() + oj.getNetworkUtility() + jd.getNetworkUtility(), oj.getNetworkUtility()})
            .delays(adjusted)
            .connectionTravelTimes(new double[]{oo.getTravelTime(), oj.getTravelTime(), jd.getTravelTime()})
            .connectionDistances(new double[]{oo.getDistance(), oj.getDistance(), jd.getDistance()})
            .connectionNetworkUtilities(new double[]{oo.getNetworkUtility(), oj.getNetworkUtility(), jd.getNetworkUtility()})
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
