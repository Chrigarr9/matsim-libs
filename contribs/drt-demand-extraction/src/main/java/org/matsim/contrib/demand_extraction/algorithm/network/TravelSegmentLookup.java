package org.matsim.contrib.demand_extraction.algorithm.network;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;

/**
 * Minimal interface for looking up travel segments between network links.
 *
 * <p>Introduced so {@link org.matsim.contrib.demand_extraction.algorithm.engine.RidePostProcessor}
 * can be unit-tested without a full MATSim network/routing stack.
 * {@link MatsimNetworkCache} implements this interface; tests can provide
 * lightweight stubs.
 */
public interface TravelSegmentLookup {

    /**
     * Returns the travel segment (travel time, distance, utility) between two
     * links at the given departure time.  Must never return {@code null}; use
     * {@link TravelSegment#unreachable()} when no path exists.
     *
     * @param originLinkId  the departure link
     * @param destLinkId    the arrival link
     * @param departureTime departure time in seconds since midnight
     * @return travel segment, never null
     */
    TravelSegment getSegment(Id<Link> originLinkId, Id<Link> destLinkId, double departureTime);
}
