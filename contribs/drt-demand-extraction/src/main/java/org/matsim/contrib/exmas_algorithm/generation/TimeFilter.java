package org.matsim.contrib.exmas_algorithm.generation;

import com.exmas.ridesharing.domain.Request;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Time-based filtering using binary search on sorted requests.
 * Python reference: rides.py lines 55-81 (np.searchsorted)
 */
public final class TimeFilter {
    private final Request[] sortedRequests;
    private final double[] requestTimes;

    public TimeFilter(Request[] requests) {
        this.sortedRequests = requests.clone();
        Arrays.sort(sortedRequests, Comparator.comparingDouble(Request::getRequestTime));

        this.requestTimes = new double[sortedRequests.length];
        for (int i = 0; i < sortedRequests.length; i++) {
            requestTimes[i] = sortedRequests[i].getRequestTime();
        }
    }

    public int[] findCandidatesInHorizon(int requestIndex, double horizon) {
        if (horizon <= 0) {
            int[] all = new int[sortedRequests.length - 1];
            for (int i = 0, j = 0; i < sortedRequests.length; i++) {
                if (i != requestIndex) all[j++] = i;
            }
            return all;
        }

        double targetTime = requestTimes[requestIndex];
        int leftIdx = searchLeft(targetTime - horizon);
        int rightIdx = searchRight(targetTime + horizon);

        int[] candidates = new int[rightIdx - leftIdx - (requestIndex >= leftIdx && requestIndex < rightIdx ? 1 : 0)];
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

    public Request getRequest(int index) {
        return sortedRequests[index];
    }

    public int size() {
        return sortedRequests.length;
    }
}
