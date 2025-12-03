package org.matsim.contrib.exmas_algorithm.network;

import com.exmas.ridesharing.domain.TravelSegment;

/**
 * Interface for network/graph queries providing travel metrics between locations.
 *
 * Python reference: self.network accessed throughout the codebase
 * - network.dict[(origin, dest)] returns {'travel_time': x, 'distance': y, 'network_utility': z}
 * - Returns infinity values if no connection exists
 */
public interface Network {
    /**
     * Get travel segment between origin and destination.
     * Returns segment with infinity values if no connection exists.
     *
     * @param origin the origin location ID
     * @param destination the destination location ID
     * @return travel segment with metrics, or infinity segment if no connection
     */
    TravelSegment getSegment(int origin, int destination);

    /**
     * Check if a direct connection exists between origin and destination.
     *
     * @param origin the origin location ID
     * @param destination the destination location ID
     * @return true if connection exists, false otherwise
     */
    boolean hasConnection(int origin, int destination);

    /**
     * Get the total number of segments in the network.
     *
     * @return number of OD pairs with defined travel metrics
     */
    int size();
}
