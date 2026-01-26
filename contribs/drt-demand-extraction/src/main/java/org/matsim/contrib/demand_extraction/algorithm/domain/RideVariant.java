package org.matsim.contrib.demand_extraction.algorithm.domain;

/**
 * Represents the operational variant of a ride, determining how passengers
 * access and egress the shared vehicle.
 *
 * <p>This enum distinguishes between different service models for demand-responsive
 * transport (DRT), ranging from traditional door-to-door service to more
 * transit-like operations with shared stops.
 *
 * <p>The variant affects:
 * <ul>
 *   <li>Passenger walking requirements (none, to/from stops, or along stop sequences)</li>
 *   <li>Vehicle routing flexibility (individual addresses vs. shared stop points)</li>
 *   <li>Service efficiency (higher pooling potential with stop-based variants)</li>
 *   <li>Passenger convenience trade-offs (walking vs. detour time)</li>
 * </ul>
 */
public enum RideVariant {

    /**
     * Standard door-to-door ride service.
     *
     * <p>Passengers are picked up at their exact origin location and dropped off
     * at their exact destination. No walking is required beyond accessing the vehicle.
     *
     * <p>Use case: Traditional DRT/taxi-like service where convenience is prioritized
     * over operational efficiency. Suitable for passengers with mobility constraints
     * or when origins/destinations are far from potential stop locations.
     */
    DOOR_TO_DOOR,

    /**
     * Stop-to-stop ride service with shared pickup and dropoff points.
     *
     * <p>Passengers walk from their origin to a shared pickup stop and from a shared
     * dropoff stop to their final destination. The vehicle serves these stops rather
     * than individual addresses.
     *
     * <p>Use case: Semi-flexible DRT service that balances passenger convenience
     * with operational efficiency. Enables better ride matching by clustering
     * nearby requests to common stops. Walking distances are typically constrained
     * by maximum acceptable walking time/distance parameters.
     */
    STOP_TO_STOP,

    /**
     * HyperPooled ride with a transit-like sequence of pickup and dropoff stops.
     *
     * <p>Multiple stop-to-stop rides are bundled together, with the vehicle following
     * a fixed sequence of stops. Passengers board at their designated pickup stop
     * and alight at their designated dropoff stop, similar to a demand-responsive
     * transit service.
     *
     * <p>Use case: High-efficiency pooling for corridors or areas with sufficient
     * demand density. Maximizes vehicle utilization by serving multiple passengers
     * along a coordinated route. Suitable for commuter patterns or when integrating
     * DRT with fixed-route transit as first/last mile service.
     */
    HYPER_POOLED
}
