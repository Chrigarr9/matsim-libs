package org.matsim.contrib.exmas_algorithm.extension;

import com.exmas.ridesharing.domain.*;
import com.exmas.ridesharing.graph.ShareabilityGraph;
import com.exmas.ridesharing.network.Network;
import it.unimi.dsi.fastutil.ints.*;
import java.util.*;

/**
 * Extends rides from degree N to N+1 using sharability graph.
 * Python reference: extensions.py lines 13-194
 */
public final class RideExtender {
    private final Network network;
    private final ShareabilityGraph graph;
    private final Map<Integer, Request> requestMap;
    private final Map<Integer, Ride> rideMap;
    private static final double EPSILON = 1e-9;

    public RideExtender(Network network, ShareabilityGraph graph, List<Request> requests, List<Ride> rides) {
        this.network = network;
        this.graph = graph;
        this.requestMap = new HashMap<>();
        for (Request r : requests) requestMap.put(r.getIndex(), r);
        this.rideMap = new HashMap<>();
        for (Ride r : rides) rideMap.put(r.getIndex(), r);
    }

    public List<Ride> extendRides(List<Ride> ridesToExtend, int nextRideIndex) {
        List<Ride> extended = new ArrayList<>();
        int rideIndex = nextRideIndex;

        for (Ride ride : ridesToExtend) {
            IntSet commonNeighbors = graph.findCommonNeighbors(ride.getRequestIndices());

            for (int candidateReq : commonNeighbors) {
                int[] pairRides = getPairRides(ride.getRequestIndices(), candidateReq);
                if (pairRides == null) continue;

                Ride ext = tryExtend(ride, candidateReq, pairRides, rideIndex);
                if (ext != null) {
                    extended.add(ext);
                    rideIndex++;
                }
            }
        }
        return extended;
    }

    private int[] getPairRides(int[] requests, int candidate) {
        int[] pairRides = new int[requests.length];
        for (int i = 0; i < requests.length; i++) {
            IntList edges = graph.getEdges(requests[i], candidate);
            if (edges.isEmpty()) return null;
            pairRides[i] = edges.getInt(0);
        }
        return pairRides;
    }

    private Ride tryExtend(Ride base, int newReq, int[] pairRides, int index) {
        Request newRequest = requestMap.get(newReq);
        int degree = base.getDegree();
        int[] destIndex = base.getDestinationsIndex();

        // Determine insertion position
        int fifoCount = 0, lifoCount = 0;
        int minLifoPos = Integer.MAX_VALUE, maxFifoPos = -1;

        for (int i = 0; i < pairRides.length; i++) {
            Ride pairRide = rideMap.get(pairRides[i]);
            int oldReq = base.getRequestIndices()[i];
            int posInDest = indexOf(destIndex, oldReq);

            if (pairRide.getKind() == RideKind.FIFO) {
                fifoCount++;
                maxFifoPos = Math.max(maxFifoPos, posInDest);
            } else if (pairRide.getKind() == RideKind.LIFO) {
                lifoCount++;
                minLifoPos = Math.min(minLifoPos, posInDest);
            }
        }

        RideKind kind;
        int insertPos;

        if (lifoCount == 0) {
            kind = RideKind.FIFO;
            insertPos = degree;
        } else if (fifoCount == 0) {
            kind = RideKind.LIFO;
            insertPos = 0;
        } else if (minLifoPos > maxFifoPos) {
            kind = RideKind.MIXED;
            insertPos = minLifoPos;
        } else {
            return null;
        }

        // Build new arrays
        int[] reqIndices = append(base.getRequestIndices(), newReq);
        int[] originsOrdered = append(base.getOriginsOrdered(), newRequest.getOrigin());
        int[] originsIndex = append(base.getOriginsIndex(), newReq);  // Store REQUEST index, not position
        int[] destinationsOrdered = insert(base.getDestinationsOrdered(), insertPos, newRequest.getDestination());
        int[] destinationsIndex = insert(base.getDestinationsIndex(), insertPos, newReq);  // Store REQUEST index, not position

        // Build connection sequence
        int[] sequence = concat(originsOrdered, destinationsOrdered);
        double[] connTT = new double[sequence.length - 1];
        double[] connDist = new double[sequence.length - 1];
        double[] connUtil = new double[sequence.length - 1];

        for (int i = 0; i < sequence.length - 1; i++) {
            TravelSegment seg = network.getSegment(sequence[i], sequence[i + 1]);
            if (!seg.isReachable()) return null;
            connTT[i] = seg.getTravelTime();
            connDist[i] = seg.getDistance();
            connUtil[i] = seg.getNetworkUtility();
        }

        // Calculate passenger metrics
        double[] pttActual = new double[degree + 1];
        double[] pDist = new double[degree + 1];
        double[] pUtil = new double[degree + 1];

        for (int i = 0; i < degree + 1; i++) {
            int reqIdx = reqIndices[i];
            Request req = requestMap.get(reqIdx);

            // Origin position is always i (origins are always in sequential order)
            int origIdx = i;

            // Find where this request appears in the destinations ordering
            // Now destinationsIndex stores REQUEST indices, so search for reqIdx
            int destPosInDestArray = indexOf(destinationsIndex, reqIdx);
            if (destPosInDestArray < 0) {
                throw new IllegalStateException("Request " + reqIdx + " not found in destinationsIndex");
            }
            // Convert to position in full sequence (origins are 0..degree, destinations are degree+1..2*degree+1)
            int destIdx = degree + 1 + destPosInDestArray;

            for (int j = origIdx; j < destIdx; j++) {
                pttActual[i] += connTT[j];
                pDist[i] += connDist[j];
                pUtil[i] += connUtil[j];
            }

            // Fix numerical issues
            if (pttActual[i] < req.getTravelTime() - EPSILON) {
                pttActual[i] = req.getTravelTime();
            }

            if (pttActual[i] > req.getMaxTravelTime()) return null;
        }

        // Calculate delays
        double startTime = requestMap.get(reqIndices[0]).getRequestTime();
        double[] delays = new double[degree + 1];
        for (int i = 0; i < degree + 1; i++) {
            double arrivalAtOrigin = startTime;
            // Sum all connection times before passenger i's origin
            // Since origins are in sequential order, passenger i is at position i
            for (int j = 0; j < i; j++) {
                arrivalAtOrigin += connTT[j];
            }
            delays[i] = arrivalAtOrigin - requestMap.get(reqIndices[i]).getRequestTime();
        }

        // Calculate effective delays (matching Python extensions.py:167-172)
        double[] effMaxNeg = new double[degree + 1];
        double[] effMaxPos = new double[degree + 1];

        for (int i = 0; i < degree + 1; i++) {
            Request req = requestMap.get(reqIndices[i]);
            double detour = pttActual[i] - req.getTravelTime();

            double posAdj = req.getPositiveDelayRelComponent() > 0.0
                ? Math.max(0.0, req.getPositiveDelayRelComponent() - detour)
                : 0.0;
            double negAdj = req.getNegativeDelayRelComponent() > 0.0
                ? Math.max(0.0, req.getNegativeDelayRelComponent() - detour)
                : 0.0;

            effMaxPos[i] = (req.getMaxPositiveDelay() - detour) - posAdj;
            effMaxNeg[i] = req.getMaxNegativeDelay() - negAdj;
        }

        // Optimize delays
        double[] adjDelays = optimizeDelays(delays, effMaxNeg, effMaxPos);
        if (adjDelays == null) return null;

        return Ride.builder()
            .index(index)
            .degree(degree + 1)
            .kind(kind)
            .requestIndices(reqIndices)
            .originsOrdered(originsOrdered)
            .destinationsOrdered(destinationsOrdered)
            .originsIndex(originsIndex)
            .destinationsIndex(destinationsIndex)
            .passengerTravelTimes(pttActual)
            .passengerDistances(pDist)
            .passengerNetworkUtilities(pUtil)
            .delays(adjDelays)
            .connectionTravelTimes(connTT)
            .connectionDistances(connDist)
            .connectionNetworkUtilities(connUtil)
            .startTime(startTime)
            .build();
    }

    private double[] optimizeDelays(double[] delays, double[] maxNeg, double[] maxPos) {
        for (int i = 0; i < delays.length; i++) {
            if (maxPos[i] < -maxNeg[i]) return null;
        }

        double lower = Double.NEGATIVE_INFINITY, upper = Double.POSITIVE_INFINITY;
        for (int i = 0; i < delays.length; i++) {
            lower = Math.max(lower, -delays[i] - maxNeg[i]);
            upper = Math.min(upper, maxPos[i] - delays[i]);
        }

        if (lower > upper + EPSILON) return null;

        double maxDelay = Double.NEGATIVE_INFINITY, minDelay = Double.POSITIVE_INFINITY;
        for (double d : delays) {
            maxDelay = Math.max(maxDelay, d);
            minDelay = Math.min(minDelay, d);
        }

        double depOpt = -(maxDelay + minDelay) / 2.0;
        depOpt = Math.max(lower, Math.min(upper, depOpt));

        double[] adjusted = new double[delays.length];
        for (int i = 0; i < delays.length; i++) {
            adjusted[i] = delays[i] + depOpt;
            if (adjusted[i] < -maxNeg[i] - EPSILON || adjusted[i] > maxPos[i] + EPSILON) return null;
        }
        return adjusted;
    }

    private int indexOf(int[] arr, int val) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == val) return i;
        return -1;
    }

    private int[] append(int[] arr, int val) {
        int[] res = Arrays.copyOf(arr, arr.length + 1);
        res[arr.length] = val;
        return res;
    }

    private int[] insert(int[] arr, int pos, int val) {
        int[] res = new int[arr.length + 1];
        System.arraycopy(arr, 0, res, 0, pos);
        res[pos] = val;
        System.arraycopy(arr, pos, res, pos + 1, arr.length - pos);
        return res;
    }

    private int[] concat(int[] a, int[] b) {
        int[] res = new int[a.length + b.length];
        System.arraycopy(a, 0, res, 0, a.length);
        System.arraycopy(b, 0, res, a.length, b.length);
        return res;
    }
}
