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
 * Generates FIFO and LIFO ride pairs with delay optimization.
 * Python reference: rides.py lines 55-370
 */
public final class PairGenerator {
    private final MatsimNetworkCache network;
    private final BudgetValidator budgetValidator;
    private final double horizon;
    private static final double EPSILON = 1e-9;

    public PairGenerator(MatsimNetworkCache network, BudgetValidator budgetValidator, double horizon) {
        this.network = network;
        this.budgetValidator = budgetValidator;
        this.horizon = horizon;
    }

    public List<Ride> generatePairs(DrtRequest[] requests) {
        TimeFilter filter = new TimeFilter(requests);
        List<Ride> pairs = new ArrayList<>();
        int rideIndex = requests.length; // Start after single rides

        for (int i = 0; i < filter.size(); i++) {
            DrtRequest reqI = filter.getRequest(i);
            int[] candidates = filter.findCandidatesInHorizon(i, horizon);

            for (int j : candidates) {
                DrtRequest reqJ = filter.getRequest(j);

                if (reqI.getPaxId().equals(reqJ.getPaxId())) continue;

                // Apply temporal window constraint (Python rides.py:82-88)
                // Constraint 1: reqJ.latestDeparture >= reqI.earliestDeparture
                if (reqJ.getLatestDeparture() < reqI.getEarliestDeparture()) continue;
                // Constraint 2: reqJ.earliestDeparture <= reqI.latestDeparture + reqI.travelTime
                if (reqJ.getEarliestDeparture() > reqI.getLatestDeparture() + reqI.getTravelTime()) continue;

                // Try FIFO: Oi -> Oj -> Di -> Dj
                Ride fifo = tryFifo(reqI, reqJ, rideIndex);
                if (fifo != null) {
                    // Validate budgets before adding
                    Ride validated = budgetValidator.validateAndPopulateBudgets(fifo, requests);
                    if (validated != null) {
                        pairs.add(validated);
                        rideIndex++;
                    }
                }

                // Try LIFO: Oi -> Oj -> Dj -> Di
                Ride lifo = tryLifo(reqI, reqJ, rideIndex);
                if (lifo != null) {
                    // Validate budgets before adding
                    Ride validated = budgetValidator.validateAndPopulateBudgets(lifo, requests);
                    if (validated != null) {
                        pairs.add(validated);
                        rideIndex++;
                    }
                }
            }
        }
        return pairs;
    }

    private Ride tryFifo(DrtRequest i, DrtRequest j, int index) {
		TravelSegment oo = network.getSegment(i.originLinkId, j.originLinkId, i.requestTime);
		TravelSegment od = network.getSegment(j.originLinkId, i.destinationLinkId, i.requestTime);
		TravelSegment dd = network.getSegment(i.destinationLinkId, j.destinationLinkId, i.requestTime);

        if (!oo.isReachable() || !od.isReachable() || !dd.isReachable()) return null;

        // Second temporal constraint using actual network travel time (Python rides.py:128-132)
        if (i.getLatestDeparture() + oo.getTravelTime() < j.getEarliestDeparture()) return null;
        if (i.getEarliestDeparture() + oo.getTravelTime() > j.getLatestDeparture()) return null;

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

    private Ride tryLifo(DrtRequest i, DrtRequest j, int index) {
		TravelSegment oo = network.getSegment(i.originLinkId, j.originLinkId, i.requestTime);
		TravelSegment oj = network.getSegment(j.originLinkId, j.destinationLinkId, i.requestTime);
		TravelSegment jd = network.getSegment(j.destinationLinkId, i.destinationLinkId, i.requestTime);

        if (!oo.isReachable() || !oj.isReachable() || !jd.isReachable()) return null;

        // Second temporal constraint for LIFO using actual network travel time
        if (i.getLatestDeparture() + oo.getTravelTime() < j.getEarliestDeparture()) return null;
        if (i.getEarliestDeparture() + oo.getTravelTime() > j.getLatestDeparture()) return null;

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
