package org.matsim.contrib.demand_extraction.algorithm;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.matsim.contrib.demand_extraction.algorithm.bamas.stub.RideStore;
import org.matsim.contrib.demand_extraction.algorithm.domain.HyperPooledRide;

/**
 * Result of a Stage-1 algorithm run.
 *
 * <p>{@code rides} is a {@link RideStore} over the door-to-door and stop-to-stop
 * variants. BAMAS may return a streaming {@code StubRideStore} on the memory-critical
 * D2D path; the reference ExMAS path wraps its fat list in a {@code MaterializedRideStore}.
 * Consumers materialize lazily via {@link RideStore#forEachMaterialized}.
 * {@code hyperPooledRides} carries the multi-stop Stage-2 (HyperPool)
 * outputs separately because their domain object ({@link HyperPooledRide})
 * carries a different schema than {@link Ride}; they are serialised to
 * their own CSV by the listener. Empty list when HyperPool is disabled
 * or when Stage 2 emitted nothing.
 *
 * <p>{@code diagnostics} is a loosely-typed bag so each strategy can publish
 * whatever runtime numbers it collects (enumeration stats for BAMAS, nothing
 * or timing-only for the reference ExMAS) without forcing a shared type. Keys
 * are stable per-strategy; callers that care about specific numbers should
 * look them up by string key.
 */
public record AlgorithmResult(
        RideStore rides,
        List<HyperPooledRide> hyperPooledRides,
        Map<String, Object> diagnostics) {

    /** Back-compat shim: ExMAS reference path doesn't emit HyperPool. */
    public AlgorithmResult(RideStore rides, Map<String, Object> diagnostics) {
        this(rides, Collections.emptyList(), diagnostics);
    }
}
