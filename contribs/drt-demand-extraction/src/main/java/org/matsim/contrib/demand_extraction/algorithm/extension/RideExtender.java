package org.matsim.contrib.demand_extraction.algorithm.extension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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

/**
 * Extends rides from degree N to N+1 using shareability graph.
 *
 * Uses parallel processing with deterministic output ordering.
 * Works with direct DrtRequest references stored in Rides.
 *
 * Python reference: extensions.py lines 13-194
 */
public final class RideExtender {
	private static final Logger log = LogManager.getLogger(RideExtender.class);

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
		for (DrtRequest r : requests) requestMap.put(r.index, r);
		this.rideMap = new HashMap<>();
		for (Ride r : rides) rideMap.put(r.getIndex(), r);
	}

	/**
	 * Intermediate candidate holding validated extension before index assignment.
	 */
	private record ExtensionCandidate(
			int baseRideIndex, int newRequestIndex, Ride validatedRide) {

		static final Comparator<ExtensionCandidate> COMPARATOR = Comparator
				.comparingInt((ExtensionCandidate c) -> c.baseRideIndex)
				.thenComparingInt(c -> c.newRequestIndex);
	}

	/**
	 * Extend rides with parallel processing and deterministic output.
	 */
	public List<Ride> extendRides(List<Ride> ridesToExtend, int nextRideIndex) {
		int targetDegree = ridesToExtend.isEmpty() ? 0 : ridesToExtend.get(0).getDegree() + 1;
		log.info("Extending {} rides from degree {} to {} [parallel]...",
				ridesToExtend.size(), targetDegree - 1, targetDegree);
		long startTime = System.currentTimeMillis();

		int total = ridesToExtend.size();
		AtomicInteger processedRides = new AtomicInteger(0);
		int logInterval = Math.max(1, total / 10);

		// Phase 1: Parallel processing - collect validated extensions
		List<ExtensionCandidate> candidates = IntStream.range(0, total)
				.parallel()
				.mapToObj(i -> {
					int processed = processedRides.incrementAndGet();
					if (processed % logInterval == 0) {
						double percent = (processed * 100.0) / total;
						log.info("  Extension progress: {}/{} ({}%)",
								processed, total, String.format("%.1f", percent));
					}
					return generateExtensionsForRide(ridesToExtend.get(i));
				})
				.flatMap(List::stream)
				.collect(Collectors.toList());

		// Phase 2: Sort by (baseRideIndex, newRequestIndex) for deterministic order
		candidates.sort(ExtensionCandidate.COMPARATOR);

		// Phase 3: Reassign indices sequentially
		List<Ride> extended = new ArrayList<>();
		for (ExtensionCandidate c : candidates) {
			Ride reindexed = rebuildWithIndex(c.validatedRide, nextRideIndex++);
			extended.add(reindexed);
		}

		long elapsed = System.currentTimeMillis() - startTime;
		double seconds = elapsed / 1000.0;
		log.info("Extension complete: {} rides extended to degree {} in {}s",
				extended.size(), targetDegree, String.format("%.1f", seconds));

		return extended;
	}

	/**
	 * Generate all valid extensions for a single base ride.
	 */
	private List<ExtensionCandidate> generateExtensionsForRide(Ride ride) {
		List<ExtensionCandidate> results = new ArrayList<>();
		int[] neighbors = graph.findCommonNeighborsSorted(ride.getRequestIndices());

		for (int candidateReq : neighbors) {
			DrtRequest newRequest = requestMap.get(candidateReq);

			// Check for duplicate person
			boolean duplicatePerson = false;
			for (DrtRequest existingRequest : ride.getRequests()) {
				if (newRequest.getPaxId().equals(existingRequest.getPaxId())) {
					duplicatePerson = true;
					break;
				}
			}
			if (duplicatePerson) continue;

			int[] pairRides = getPairRides(ride.getRequestIndices(), candidateReq);
			if (pairRides == null) continue;

			// Use temp index (will be reassigned later)
			Ride ext = tryExtend(ride, newRequest, pairRides, 0);
			if (ext != null) {
				Ride validated = budgetValidator.validateAndPopulateBudgets(ext);
				if (validated != null) {
					results.add(new ExtensionCandidate(ride.getIndex(), candidateReq, validated));
				}
			}
		}

		return results;
	}

	/**
	 * Rebuild ride with new index.
	 */
	private Ride rebuildWithIndex(Ride ride, int newIndex) {
		return Ride.builder()
				.index(newIndex)
				.degree(ride.getDegree())
				.kind(ride.getKind())
				.requests(ride.getRequests())
				.originsOrderedRequests(ride.getOriginsOrderedRequests())
				.destinationsOrderedRequests(ride.getDestinationsOrderedRequests())
				.passengerTravelTimes(ride.getPassengerTravelTimes())
				.passengerDistances(ride.getPassengerDistances())
				.passengerNetworkUtilities(ride.getPassengerNetworkUtilities())
				.delays(ride.getDelays())
				.remainingBudgets(ride.getRemainingBudgets())
				.connectionTravelTimes(ride.getConnectionTravelTimes())
				.connectionDistances(ride.getConnectionDistances())
				.connectionNetworkUtilities(ride.getConnectionNetworkUtilities())
				.startTime(ride.getStartTime())
				.build();
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

	private Ride tryExtend(Ride base, DrtRequest newRequest, int[] pairRides, int index) {
		int degree = base.getDegree();
		DrtRequest[] destOrderedRequests = base.getDestinationsOrderedRequests();

		// Determine insertion position based on pair ride kinds
		int fifoCount = 0;
		int lifoCount = 0;
		int minLifoPos = Integer.MAX_VALUE;
		int maxFifoPos = -1;

		DrtRequest[] baseRequests = base.getRequests();
		for (int i = 0; i < pairRides.length; i++) {
			Ride pairRide = rideMap.get(pairRides[i]);
			DrtRequest oldReq = baseRequests[i];
			int posInDest = indexOfRequest(destOrderedRequests, oldReq);

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

		// Build new request arrays
		DrtRequest[] requests = appendRequest(base.getRequests(), newRequest);
		DrtRequest[] originsOrderedRequests = appendRequest(base.getOriginsOrderedRequests(), newRequest);
		DrtRequest[] destinationsOrderedRequests = insertRequest(base.getDestinationsOrderedRequests(), insertPos, newRequest);

		// Build connection sequence from request arrays (derive Link IDs)
		int seqLen = (degree + 1) * 2;
		Id<Link>[] sequence = buildSequence(originsOrderedRequests, destinationsOrderedRequests);

		double[] connTT = new double[seqLen - 1];
		double[] connDist = new double[seqLen - 1];
		double[] connUtil = new double[seqLen - 1];

		double startTime = requests[0].getRequestTime();
		for (int i = 0; i < seqLen - 1; i++) {
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
			DrtRequest req = requests[i];

			// Origin position is always i
			int origIdx = i;

			// Find where this request appears in the destinations ordering
			int destPosInDestArray = indexOfRequest(destinationsOrderedRequests, req);
			if (destPosInDestArray < 0) {
				throw new IllegalStateException("Request " + req.index + " not found in destinationsOrderedRequests");
			}
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
			for (int j = 0; j < i; j++) {
				arrivalAtOrigin += connTT[j];
			}
			delays[i] = arrivalAtOrigin - requests[i].getRequestTime();
		}

		// Calculate effective delays
		double[] effMaxNeg = new double[degree + 1];
		double[] effMaxPos = new double[degree + 1];

		for (int i = 0; i < degree + 1; i++) {
			DrtRequest req = requests[i];
			double detour = pttActual[i] - req.getTravelTime();

			double posAdj = req.getPositiveDelayRelComponent() > 0.0
					? Math.max(0.0, req.getPositiveDelayRelComponent() - detour) : 0.0;
			double negAdj = req.getNegativeDelayRelComponent() > 0.0
					? Math.max(0.0, req.getNegativeDelayRelComponent() - detour) : 0.0;

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
				.requests(requests)
				.originsOrderedRequests(originsOrderedRequests)
				.destinationsOrderedRequests(destinationsOrderedRequests)
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

	@SuppressWarnings("unchecked")
	private Id<Link>[] buildSequence(DrtRequest[] origins, DrtRequest[] destinations) {
		Id<Link>[] seq = (Id<Link>[]) new Id[origins.length + destinations.length];
		for (int i = 0; i < origins.length; i++) {
			seq[i] = origins[i].originLinkId;
		}
		for (int i = 0; i < destinations.length; i++) {
			seq[origins.length + i] = destinations[i].destinationLinkId;
		}
		return seq;
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

	private int indexOfRequest(DrtRequest[] arr, DrtRequest req) {
		for (int i = 0; i < arr.length; i++) {
			if (arr[i].index == req.index) return i;
		}
		return -1;
	}

	private DrtRequest[] appendRequest(DrtRequest[] arr, DrtRequest val) {
		DrtRequest[] res = Arrays.copyOf(arr, arr.length + 1);
		res[arr.length] = val;
		return res;
	}

	private DrtRequest[] insertRequest(DrtRequest[] arr, int pos, DrtRequest val) {
		DrtRequest[] res = new DrtRequest[arr.length + 1];
		System.arraycopy(arr, 0, res, 0, pos);
		res[pos] = val;
		System.arraycopy(arr, pos, res, pos + 1, arr.length - pos);
		return res;
	}
}
