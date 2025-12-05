package org.matsim.contrib.demand_extraction.algorithm.generation;

import java.util.ArrayList;
import java.util.List;

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
    private final MatsimNetworkCache networkCache;
	private final BudgetValidator budgetValidator;
    
	public SingleRideGenerator(MatsimNetworkCache networkCache, BudgetValidator budgetValidator) {
        this.networkCache = networkCache;
		this.budgetValidator = budgetValidator;
    }

    public List<Ride> generate(List<DrtRequest> requests) {
        List<Ride> rides = new ArrayList<>(requests.size());
		DrtRequest[] reqArray = requests.toArray(new DrtRequest[0]);

        for (DrtRequest req : requests) {
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
        }

        return rides;
    }
}
