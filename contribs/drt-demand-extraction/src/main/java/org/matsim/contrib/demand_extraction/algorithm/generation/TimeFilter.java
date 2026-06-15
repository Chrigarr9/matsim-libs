package org.matsim.contrib.demand_extraction.algorithm.generation;

import org.matsim.contrib.demand_extraction.demand.DrtRequest;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Time-based filtering using binary search on sorted requests.
 * Python reference: rides.py lines 55-81 (np.searchsorted)
 */
public final class TimeFilter {
    private final DrtRequest[] sortedRequests;
    private final double[] requestTimes;
    private final double[] earliestDepartures;
    private final double[] latestArrivals;
    // Global maxima of each request's one-sided flexibility, used as a lossless
    // pad so the per-request window filter still catches partners whose OWN
    // flexibility lets their window reach into this request's window.
    private final double maxNegativeFlex; // max(requestTime - earliestDeparture)
    private final double maxPositiveFlex; // max(latestDeparture - requestTime)

    public TimeFilter(DrtRequest[] requests) {
        this.sortedRequests = requests.clone();
        Arrays.sort(sortedRequests, Comparator.comparingDouble(DrtRequest::getRequestTime));

        this.requestTimes = new double[sortedRequests.length];
        this.earliestDepartures = new double[sortedRequests.length];
        this.latestArrivals = new double[sortedRequests.length];
        double maxNeg = 0.0, maxPos = 0.0;
        for (int i = 0; i < sortedRequests.length; i++) {
            DrtRequest r = sortedRequests[i];
            requestTimes[i] = r.getRequestTime();
            earliestDepartures[i] = r.getEarliestDeparture();
            latestArrivals[i] = r.getLatestDeparture() + r.getTravelTime();
            maxNeg = Math.max(maxNeg, r.getMaxNegativeDelay());
            maxPos = Math.max(maxPos, r.getMaxPositiveDelay());
        }
        this.maxNegativeFlex = maxNeg;
        this.maxPositiveFlex = maxPos;
    }

    public int[] findCandidatesInHorizon(int requestIndex, double horizon) {
        if (horizon <= 0) {
            return allExcept(requestIndex);
        }
        double targetTime = requestTimes[requestIndex];
        return collectRange(searchLeft(targetTime - horizon),
                searchRight(targetTime + horizon), requestIndex);
    }

    /**
     * Per-request candidate pre-filter: returns every other request whose request
     * time falls inside this request's temporal window, padded by the global max
     * flexibility, and never narrower than {@code horizonFloor}.
     *
     * <p>This is the lossless replacement for {@link #findCandidatesInHorizon}: the
     * downstream exact overlap check in {@code PairGenerator} keeps a partner
     * {@code j} iff the windows {@code [earliestDeparture, latestArrival]} overlap.
     * The max request-time gap for which that can still pass is
     * {@code directTravelTime_i + destFlex_i + originFlex_j} on the late side and
     * {@code originFlex_i + destFlex_j} on the early side; bounding {@code j}'s
     * flexibility by the global maxima yields the band below. Flooring at
     * {@code horizonFloor} keeps the result a superset of the old flat-horizon
     * candidate set, so short-trip scenarios (where the flat horizon already
     * covered every feasible pair) are unaffected.
     */
    public int[] findCandidatesInWindow(int requestIndex, double horizonFloor) {
        if (horizonFloor <= 0) {
            return allExcept(requestIndex);
        }
        double targetTime = requestTimes[requestIndex];
        double lower = Math.min(targetTime - horizonFloor,
                earliestDepartures[requestIndex] - maxPositiveFlex);
        double upper = Math.max(targetTime + horizonFloor,
                latestArrivals[requestIndex] + maxNegativeFlex);
        return collectRange(searchLeft(lower), searchRight(upper), requestIndex);
    }

    private int[] allExcept(int requestIndex) {
        int[] all = new int[sortedRequests.length - 1];
        for (int i = 0, j = 0; i < sortedRequests.length; i++) {
            if (i != requestIndex) all[j++] = i;
        }
        return all;
    }

    private int[] collectRange(int leftIdx, int rightIdx, int requestIndex) {
        int self = (requestIndex >= leftIdx && requestIndex < rightIdx) ? 1 : 0;
        int[] candidates = new int[rightIdx - leftIdx - self];
        for (int i = leftIdx, j = 0; i < rightIdx; i++) {
            if (i != requestIndex) candidates[j++] = i;
        }
        return candidates;
    }

    private int searchLeft(double value) {
        int idx = Arrays.binarySearch(requestTimes, value);
        return idx >= 0 ? idx : -(idx + 1);
    }

    private int searchRight(double value) {
        int idx = Arrays.binarySearch(requestTimes, value);
        return idx >= 0 ? idx + 1 : -(idx + 1);
    }

    public DrtRequest getRequest(int index) {
        return sortedRequests[index];
    }

    public int size() {
        return sortedRequests.length;
    }
}
