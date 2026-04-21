package org.matsim.contrib.demand_extraction.algorithm.generation;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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
 * Generates degree-1 (single passenger) rides from requests.
 * Validates that DRT utility meets or exceeds baseline mode utility (positive budget).
 *
 * <p>Uses parallel processing with deterministic output ordering when
 * {@code algorithmProcessCount != 1}, mirroring {@link PairGenerator}. The
 * network cache, budget validator, and scoring adapter are all singleton and
 * thread-safe; each request's output depends only on its own state plus those
 * singletons, so parallelization is safe. Output order follows the input list.
 *
 * Python reference: src/exmas_commuters/core/exmas/rides.py lines 12-41
 */
public final class SingleRideGenerator {
	private static final Logger log = LogManager.getLogger(SingleRideGenerator.class);

	private final MatsimNetworkCache networkCache;
	private final BudgetValidator budgetValidator;
	private final boolean useParallel;

	public SingleRideGenerator(MatsimNetworkCache networkCache, BudgetValidator budgetValidator) {
		this(networkCache, budgetValidator, -1);
	}

	public SingleRideGenerator(MatsimNetworkCache networkCache, BudgetValidator budgetValidator,
			int algorithmProcessCount) {
		this.networkCache = networkCache;
		this.budgetValidator = budgetValidator;
		this.useParallel = algorithmProcessCount != 1;
	}

	public List<Ride> generate(List<DrtRequest> requests) {
		int total = requests.size();
		log.info("Generating single rides from {} requests [{}]...",
				total, useParallel ? "parallel" : "sequential");
		long startTime = System.currentTimeMillis();

		AtomicInteger processedCounter = new AtomicInteger(0);
		AtomicInteger validCounter = new AtomicInteger(0);
		int logInterval = Math.max(1, total / 10);

		IntStream indexStream = IntStream.range(0, total);
		if (useParallel) {
			indexStream = indexStream.parallel();
		}

		Ride[] results = indexStream
				.mapToObj(i -> {
					DrtRequest req = requests.get(i);
					TravelSegment segment = networkCache.getSegment(
							req.originLinkId, req.destinationLinkId, req.requestTime);

					// Build candidate ride with direct request references.
					// detour = 1.0 (passengerTravelTime = directTravelTime, no detour)
					Ride candidateRide = Ride.builder()
							.index(req.index)
							.degree(1)
							.kind(RideKind.SINGLE)
							.requests(new DrtRequest[] { req })
							.originsOrderedRequests(new DrtRequest[] { req })
							.destinationsOrderedRequests(new DrtRequest[] { req })
							.passengerTravelTimes(new double[] { segment.getTravelTime() })
							.passengerDistances(new double[] { segment.getDistance() })
							.passengerNetworkUtilities(new double[] { segment.getNetworkUtility() })
							.delays(new double[] { 0.0 })
							.detours(new double[] { 1.0 })
							.connectionTravelTimes(new double[] { segment.getTravelTime() })
							.connectionDistances(new double[] { segment.getDistance() })
							.connectionNetworkUtilities(new double[] { segment.getNetworkUtility() })
							.startTime(req.getRequestTime())
							.build();

					Ride validatedRide = budgetValidator.validateAndPopulateBudgets(candidateRide);
					if (validatedRide != null) {
						validCounter.incrementAndGet();
					}

					int done = processedCounter.incrementAndGet();
					if (done % logInterval == 0 || done == total) {
						double percent = (done * 100.0) / total;
						log.info("  Single rides progress: {}/{} ({}%) - {} valid rides",
								done, total, String.format("%.1f", percent), validCounter.get());
					}
					return validatedRide;
				})
				.toArray(Ride[]::new);

		List<Ride> rides = new ArrayList<>(total);
		for (Ride r : results) {
			if (r != null) {
				rides.add(r);
			}
		}

		long elapsed = System.currentTimeMillis() - startTime;
		double seconds = elapsed / 1000.0;
		int rejected = total - rides.size();
		log.info("Single ride generation complete: {} valid rides ({} rejected) in {}s",
				rides.size(), rejected, String.format("%.1f", seconds));

		return rides;
	}
}
