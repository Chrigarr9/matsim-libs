package org.matsim.contrib.exmas_algorithm.generation;

import com.exmas.ridesharing.domain.Request;
import com.exmas.ridesharing.domain.Ride;
import com.exmas.ridesharing.domain.RideKind;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates degree-1 (single passenger) rides from requests.
 * Python reference: src/exmas_commuters/core/exmas/rides.py lines 12-41
 */
public final class SingleRideGenerator {

    public List<Ride> generate(List<Request> requests) {
        List<Ride> rides = new ArrayList<>(requests.size());

        for (Request req : requests) {
            rides.add(Ride.builder()
                .index(req.getIndex())
                .degree(1)
                .kind(RideKind.SINGLE)
                .requestIndices(new int[]{req.getIndex()})
                .originsOrdered(new int[]{req.getOrigin()})
                .destinationsOrdered(new int[]{req.getDestination()})
                .originsIndex(new int[]{req.getIndex()})
                .destinationsIndex(new int[]{req.getIndex()})
                .passengerTravelTimes(new double[]{req.getTravelTime()})
                .passengerDistances(new double[]{req.getDistance()})
                .passengerNetworkUtilities(new double[]{req.getNetworkUtility()})
                .delays(new double[]{0.0})
                .connectionTravelTimes(new double[]{req.getTravelTime()})
                .connectionDistances(new double[]{req.getDistance()})
                .connectionNetworkUtilities(new double[]{req.getNetworkUtility()})
                .startTime(req.getRequestTime())
                .build());
        }

        return rides;
    }
}
