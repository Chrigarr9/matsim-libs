package org.matsim.contrib.demand_extraction.algorithm.bamas.ride;

import java.util.function.Consumer;

import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;

/**
 * Minimal read-only surface over a collection of rides, consumed by the three
 * core D2D consumers: DegreeGraph build, post-processor, and CSV writer.
 *
 * <p>Design constraints (YAGNI):
 * <ul>
 *   <li>Exactly four methods — no {@code isEmpty()}, {@code stream()}, degree
 *       accessors, or HyperPool/cluster hooks.</li>
 *   <li>The {@link #forEachMaterialized} visitor MUST NOT retain the {@link Ride}
 *       reference beyond the callback. A future stub-backed implementation will
 *       hand out transient, reused objects; retaining them would observe stale
 *       or overwritten data.</li>
 * </ul>
 *
 * <p>The {@link MaterializedRideStore} shim wraps a {@code List<Ride>} and
 * preserves current behaviour unchanged. Later phases will swap in a
 * stub-backed store without touching the consumers.
 */
public interface RideStore {

    /** Total number of rides in this store. */
    int size();

    /**
     * Random-access full materialization of one ride.
     *
     * @param row zero-based row index in [{@code 0}, {@link #size()})
     * @return the {@link Ride} at the given row
     */
    Ride materialize(int row);

    /**
     * Streaming batch materialization: invokes {@code visitor} once for each
     * ride in insertion order.
     *
     * <p><strong>Contract:</strong> the {@link Ride} passed to the visitor MUST
     * NOT be retained beyond the call. A future stub-backed implementation will
     * reuse a single transient object across calls.
     *
     * @param visitor called once per ride in order; must not retain the argument
     */
    void forEachMaterialized(Consumer<Ride> visitor);

    /**
     * Returns the request indices for the given row without fully materializing
     * the ride. Used by {@code DegreeGraph} build to avoid touching per-pax
     * metrics.
     *
     * @param row zero-based row index in [{@code 0}, {@link #size()})
     * @return array of request indices ({@link Ride#getRequestIndices()})
     */
    int[] requestIndices(int row);
}
