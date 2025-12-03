package org.matsim.contrib.demand_extraction.algorithm.generation;

import org.matsim.contrib.demand_extraction.demand.DrtRequest;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideKind;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates degree-1 (single passenger) rides from requests.
 * Python reference: src/exmas_commuters/core/exmas/rides.py lines 12-41
 */
public final class SingleRideGenerator {
    private final MatsimNetworkCache networkCache;
    
    public SingleRideGenerator(MatsimNetworkCache networkCache) {
        this.networkCache = networkCache;
    }

    public List<Ride> generate(List<DrtRequest> requests) {
        List<Ride> rides = new ArrayList<>(requests.size());

        for (DrtRequest req : requests) {
            int originNode = networkCache.getNodeIndex(req.originLinkId);
            int destNode = networkCache.getNodeIndex(req.destinationLinkId);
            double networkUtility = networkCache.getNetworkUtility(originNode, destNode);
            
            rides.add(Ride.builder()
                .index(req.index)
                .degree(1)
                .kind(RideKind.SINGLE)
                .requestIndices(new int[]{req.index})
                .originsOrdered(new int[]{originNode})
                .destinationsOrdered(new int[]{destNode})
                .originsIndex(new int[]{req.index})
                .destinationsIndex(new int[]{req.index})
                .passengerTravelTimes(new double[]{req.getTravelTime()})
                .passengerDistances(new double[]{req.getDistance()})
                .passengerNetworkUtilities(new double[]{networkUtility})
                .delays(new double[]{0.0})
                .connectionTravelTimes(new double[]{req.getTravelTime()})
                .connectionDistances(new double[]{req.getDistance()})
                .connectionNetworkUtilities(new double[]{networkUtility})
                .startTime(req.getRequestTime())
                .build());
        }

        return rides;
    }
}
