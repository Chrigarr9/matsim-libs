package org.matsim.contrib.demand_extraction.algorithm.extension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.RideKind;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;
import org.matsim.contrib.demand_extraction.algorithm.graph.ShareabilityGraph;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntSet;

/**
 * Extends rides from degree N to N+1 using sharability graph.
 * Python reference: extensions.py lines 13-194
 */
public final class RideExtender {
    private final MatsimNetworkCache network;
    private final ShareabilityGraph graph;
    private final BudgetValidator budgetValidator;
    private final Map<Integer, DrtRequest> requestMap;
    private final Map<Integer, Ride> rideMap;
    private static final double EPSILON = 1e-9;

    public RideExtender(MatsimNetworkCache network, ShareabilityGraph graph, BudgetValidator budgetValidator,
                        List<DrtRequest> requests, List<Ride> rides) {
        this.network = network;
        this.graph = graph;
        this.budgetValidator = budgetValidator;
        this.requestMap = new HashMap<>();
		// C: should we build rides and requests directly as maps instead of lists? would this have benefits within the whole algorithm?
        for (DrtRequest r : requests) requestMap.put(r.index, r);
        this.rideMap = new HashMap<>();
        for (Ride r : rides) rideMap.put(r.getIndex(), r);
    }

    public List<Ride> extendRides(List<Ride> ridesToExtend, DrtRequest[] drtRequests, int nextRideIndex) {
        List<Ride> extended = new ArrayList<>();
        int rideIndex = nextRideIndex;

        for (Ride ride : ridesToExtend) {
            IntSet commonNeighbors = graph.findCommonNeighbors(ride.getRequestIndices());

            for (int candidateReq : commonNeighbors) {
                // Check that candidate request has different personId from all existing passengers
                DrtRequest newRequest = requestMap.get(candidateReq);
                boolean duplicatePerson = false;
                for (int existingReqIdx : ride.getRequestIndices()) {
                    DrtRequest existingRequest = requestMap.get(existingReqIdx);
                    if (newRequest.getPaxId().equals(existingRequest.getPaxId())) {
                        duplicatePerson = true;
                        break;
                    }
                }
                if (duplicatePerson) continue;

                int[] pairRides = getPairRides(ride.getRequestIndices(), candidateReq);
                if (pairRides == null) continue;

                Ride ext = tryExtend(ride, candidateReq, pairRides, rideIndex);
                if (ext != null) {
                    // Validate budgets before adding
                    Ride validated = budgetValidator.validateAndPopulateBudgets(ext, drtRequests);
                    if (validated != null) {
                        extended.add(validated);
                        rideIndex++;
                    }
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
			// C: we need to explore why we oly use [0] here. test without and look if there are rides with the exact same originsOrderd + destinations ordered (duplicates)
            pairRides[i] = edges.getInt(0);
        }
        return pairRides;
    }

    private Ride tryExtend(Ride base, int newReq, int[] pairRides, int index) {
        DrtRequest newRequest = requestMap.get(newReq);
		Id<Link> newOriginLink = newRequest.originLinkId;
		Id<Link> newDestLink = newRequest.destinationLinkId;
        int degree = base.getDegree();
        int[] destIndex = base.getDestinationsIndex();

        // Determine insertion position
        int fifoCount = 0;
		int lifoCount = 0;
        int minLifoPos = Integer.MAX_VALUE;
		int maxFifoPos = -1;

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
		int[] reqIndices = appendInt(base.getRequestIndices(), newReq);
		Id<Link>[] originsOrdered = appendLink(base.getOriginsOrdered(), newOriginLink);
		int[] originsIndex = appendInt(base.getOriginsIndex(), newReq); // Store REQUEST index, not position
		Id<Link>[] destinationsOrdered = insertLink(base.getDestinationsOrdered(), insertPos, newDestLink);
		int[] destinationsIndex = insertInt(base.getDestinationsIndex(), insertPos, newReq); // Store REQUEST index, not
																								// position

        // Build connection sequence
		Id<Link>[] sequence = concatLink(originsOrdered, destinationsOrdered);
        double[] connTT = new double[sequence.length - 1];
        double[] connDist = new double[sequence.length - 1];
        double[] connUtil = new double[sequence.length - 1];

		double startTime = requestMap.get(reqIndices[0]).getRequestTime();
        for (int i = 0; i < sequence.length - 1; i++) {
			TravelSegment seg = network.getSegment(sequence[i], sequence[i + 1], startTime);
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
            DrtRequest req = requestMap.get(reqIdx);

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
            DrtRequest req = requestMap.get(reqIndices[i]);
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

	private int[] appendInt(int[] arr, int val) {
        int[] res = Arrays.copyOf(arr, arr.length + 1);
        res[arr.length] = val;
        return res;
    }

	private int[] insertInt(int[] arr, int pos, int val) {
        int[] res = new int[arr.length + 1];
        System.arraycopy(arr, 0, res, 0, pos);
        res[pos] = val;
        System.arraycopy(arr, pos, res, pos + 1, arr.length - pos);
        return res;
    }

	@SuppressWarnings("unchecked")
	private Id<Link>[] appendLink(Id<Link>[] arr, Id<Link> val) {
		Id<Link>[] res = (Id<Link>[]) new Id[arr.length + 1];
		System.arraycopy(arr, 0, res, 0, arr.length);
		res[arr.length] = val;
		return res;
	}

	@SuppressWarnings("unchecked")
	private Id<Link>[] insertLink(Id<Link>[] arr, int pos, Id<Link> val) {
		Id<Link>[] res = (Id<Link>[]) new Id[arr.length + 1];
		System.arraycopy(arr, 0, res, 0, pos);
		res[pos] = val;
		System.arraycopy(arr, pos, res, pos + 1, arr.length - pos);
		return res;
	}

	@SuppressWarnings("unchecked")
	private Id<Link>[] concatLink(Id<Link>[] a, Id<Link>[] b) {
		Id<Link>[] res = (Id<Link>[]) new Id[a.length + b.length];
        System.arraycopy(a, 0, res, 0, a.length);
        System.arraycopy(b, 0, res, a.length, b.length);
        return res;
    }
}
