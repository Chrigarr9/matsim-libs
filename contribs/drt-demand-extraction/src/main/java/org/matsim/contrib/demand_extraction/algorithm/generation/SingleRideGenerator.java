package org.matsim.contrib.demand_extraction.algorithm.generation;

import java.util.ArrayList;
import java.util.List;

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
 * Generates degree-1 (single passenger) rides from requests.
 * Validates that DRT utility meets or exceeds baseline mode utility (positive
 * budget).
 * Python reference: src/exmas_commuters/core/exmas/rides.py lines 12-41
 */
public final class SingleRideGenerator {
	private static final Logger log = LogManager.getLogger(SingleRideGenerator.class);

    private final MatsimNetworkCache networkCache;
	private final BudgetValidator budgetValidator;
    
	public SingleRideGenerator(MatsimNetworkCache networkCache, BudgetValidator budgetValidator) {
        this.networkCache = networkCache;
		this.budgetValidator = budgetValidator;
    }

    public List<Ride> generate(List<DrtRequest> requests) {
		log.info("Generating single rides from {} requests...", requests.size());
		long startTime = System.currentTimeMillis();

        List<Ride> rides = new ArrayList<>(requests.size());
		DrtRequest[] reqArray = requests.toArray(new DrtRequest[0]);

		int processed = 0;
		int total = requests.size();
		int logInterval = Math.max(1, total / 10);

        for (DrtRequest req : requests) {
			processed++;
			TravelSegment segment = networkCache.getSegment(req.originLinkId, req.destinationLinkId, req.requestTime);

			@SuppressWarnings("unchecked")
			Id<Link>[] origins = (Id<Link>[]) new Id[] { req.originLinkId };
			@SuppressWarnings("unchecked")
			Id<Link>[] destinations = (Id<Link>[]) new Id[] { req.destinationLinkId };

			// Build candidate ride (without budget populated yet)
			Ride candidateRide = Ride.builder()
                .index(req.index)
                .degree(1)
                .kind(RideKind.SINGLE)
                .requestIndices(new int[]{req.index})
					.originsOrdered(origins)
					.destinationsOrdered(destinations)
                .originsIndex(new int[]{req.index})
                .destinationsIndex(new int[]{req.index})
					.passengerTravelTimes(new double[] { segment.getTravelTime() })
					.passengerDistances(new double[] { segment.getDistance() })
					.passengerNetworkUtilities(new double[] { segment.getNetworkUtility() })
                .delays(new double[]{0.0})
					.connectionTravelTimes(new double[] { segment.getTravelTime() })
					.connectionDistances(new double[] { segment.getDistance() })
					.connectionNetworkUtilities(new double[] { segment.getNetworkUtility() })
                .startTime(req.getRequestTime())
					.build();

			// Validate budget: ensure DRT utility meets or exceeds baseline mode
			Ride validatedRide = budgetValidator.validateAndPopulateBudgets(candidateRide, reqArray);
			if (validatedRide != null) {
				rides.add(validatedRide);
			}
			// else: ride rejected because DRT utility < baseline (negative budget)

			// Progress logging
			if (processed % logInterval == 0 || processed == total) {
				double percent = (processed * 100.0) / total;
				log.info("  Single rides progress: {}/{} ({}%) - {} valid rides",
						processed, total, String.format("%.1f", percent), rides.size());
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
