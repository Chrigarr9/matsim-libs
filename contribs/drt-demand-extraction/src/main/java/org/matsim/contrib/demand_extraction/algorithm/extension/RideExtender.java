package org.matsim.contrib.demand_extraction.algorithm.extension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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
import it.unimi.dsi.fastutil.ints.IntSet;

/**
 * Extends rides from degree N to N+1 using shareability graph.
 *
 * Uses deterministic parallel processing to ensure reproducible results.
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
	 * Extend rides using deterministic parallel processing.
	 * Results are reproducible across runs with the same input.
	 */
	public List<Ride> extendRides(List<Ride> ridesToExtend, int nextRideIndex) {
		int targetDegree = ridesToExtend.isEmpty() ? 0 : ridesToExtend.get(0).getDegree() + 1;
		log.info("Extending {} rides from degree {} to {}...",
				ridesToExtend.size(), targetDegree - 1, targetDegree);
		long startTime = System.currentTimeMillis();

		int total = ridesToExtend.size();

		// Phase 1: Count extensions per ride for index allocation (sequential, fast)
		int[] extensionCounts = new int[total];
		int totalPotentialExtensions = 0;

		for (int i = 0; i < total; i++) {
			Ride ride = ridesToExtend.get(i);
			IntSet commonNeighbors = graph.findCommonNeighbors(ride.getRequestIndices());
			extensionCounts[i] = commonNeighbors.size();
			totalPotentialExtensions += commonNeighbors.size();
		}

		// Phase 2: Pre-allocate index ranges
		int[] indexStarts = new int[total];
		int currentIndex = nextRideIndex;
		for (int i = 0; i < total; i++) {
			indexStarts[i] = currentIndex;
			currentIndex += extensionCounts[i];
		}

		log.info("  Pre-allocated {} potential extension indices", totalPotentialExtensions);

		// Phase 3: Parallel processing with pre-allocated indices
		AtomicInteger processedRequests = new AtomicInteger(0);
		AtomicInteger candidatesFound = new AtomicInteger(0);
		AtomicInteger extensionAttempts = new AtomicInteger(0);
		AtomicInteger duplicatePersons = new AtomicInteger(0);
		AtomicInteger missingPairs = new AtomicInteger(0);
		AtomicInteger validExtensions = new AtomicInteger(0);
		int logInterval = Math.max(1, total / 10);

		Ride[] allExtensions = new Ride[totalPotentialExtensions];

		IntStream.range(0, total).parallel().forEach(i -> {
			int processed = processedRequests.incrementAndGet();
			if (processed % logInterval == 0) {
				double percent = (processed * 100.0) / total;
				log.info("  Extension progress: {}/{} ({}%) - ~{} extended rides",
						processed, total, String.format("%.1f", percent), validExtensions.get());
			}

			Ride ride = ridesToExtend.get(i);
			IntSet commonNeighbors = graph.findCommonNeighbors(ride.getRequestIndices());
			int localRideIndex = indexStarts[i];

			for (int candidateReq : commonNeighbors) {
				candidatesFound.incrementAndGet();

				// Check that candidate request has different personId from all existing passengers
				DrtRequest newRequest = requestMap.get(candidateReq);
				boolean duplicatePerson = false;
				for (DrtRequest existingRequest : ride.getRequests()) {
					if (newRequest.getPaxId().equals(existingRequest.getPaxId())) {
						duplicatePerson = true;
						break;
					}
				}
				if (duplicatePerson) {
					duplicatePersons.incrementAndGet();
					continue;
				}

				int[] pairRides = getPairRides(ride.getRequestIndices(), candidateReq);
				if (pairRides == null) {
					missingPairs.incrementAndGet();
					continue;
				}

				extensionAttempts.incrementAndGet();
				Ride ext = tryExtend(ride, newRequest, pairRides, localRideIndex);
				if (ext != null) {
					Ride validated = budgetValidator.validateAndPopulateBudgets(ext);
					if (validated != null) {
						allExtensions[localRideIndex - nextRideIndex] = validated;
						localRideIndex++;
						validExtensions.incrementAndGet();
					}
				}
			}
		});

		// Phase 4: Collect non-null extensions and sort for deterministic order
		List<Ride> extended = new ArrayList<>();
		for (Ride ride : allExtensions) {
			if (ride != null) {
				extended.add(ride);
			}
		}
		extended.sort((r1, r2) -> Integer.compare(r1.getIndex(), r2.getIndex()));

		long elapsed = System.currentTimeMillis() - startTime;
		double seconds = elapsed / 1000.0;
		log.info("Extension complete: {} rides extended to degree {} in {}s",
				extended.size(), targetDegree, String.format("%.1f", seconds));
		log.info("  Statistics: {} candidates, {} attempts, {} duplicate persons, {} missing pairs",
				candidatesFound.get(), extensionAttempts.get(), duplicatePersons.get(), missingPairs.get());

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

	private Ride tryExtend(Ride base, DrtRequest newRequest, int[] pairRides, int index) {
		Id<Link> newOriginLink = newRequest.originLinkId;
		Id<Link> newDestLink = newRequest.destinationLinkId;
		int degree = base.getDegree();
		DrtRequest[] destOrderedRequests = base.getDestinationsOrderedRequests();

		// Determine insertion position
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

		// Build new arrays with direct object references
		DrtRequest[] requests = appendRequest(base.getRequests(), newRequest);
		Id<Link>[] originsOrdered = appendLink(base.getOriginsOrdered(), newOriginLink);
		DrtRequest[] originsOrderedRequests = appendRequest(base.getOriginsOrderedRequests(), newRequest);
		Id<Link>[] destinationsOrdered = insertLink(base.getDestinationsOrdered(), insertPos, newDestLink);
		DrtRequest[] destinationsOrderedRequests = insertRequest(base.getDestinationsOrderedRequests(), insertPos, newRequest);

		// Build connection sequence
		Id<Link>[] sequence = concatLink(originsOrdered, destinationsOrdered);
		double[] connTT = new double[sequence.length - 1];
		double[] connDist = new double[sequence.length - 1];
		double[] connUtil = new double[sequence.length - 1];

		double startTime = requests[0].getRequestTime();
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
			DrtRequest req = requests[i];

			// Origin position is always i (origins are always in sequential order)
			int origIdx = i;

			// Find where this request appears in the destinations ordering
			int destPosInDestArray = indexOfRequest(destinationsOrderedRequests, req);
			if (destPosInDestArray < 0) {
				throw new IllegalStateException("Request " + req.index + " not found in destinationsOrderedRequests");
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
				.requests(requests)
				.originsOrdered(originsOrdered)
				.destinationsOrdered(destinationsOrdered)
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
