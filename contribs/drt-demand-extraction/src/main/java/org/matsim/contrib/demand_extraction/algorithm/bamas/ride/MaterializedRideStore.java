package org.matsim.contrib.demand_extraction.algorithm.bamas.ride;

import java.util.List;
import java.util.function.Consumer;

import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;

/**
 * Behavior-preserving {@link RideStore} shim backed by a {@code List<Ride>}.
 *
 * <p>Wraps the engine's existing ride list without a defensive copy — this is
 * a zero-overhead adapter that preserves current behavior identically.
 * Callers on {@link #forEachMaterialized} must not retain the passed {@link Ride}
 * reference (contract from {@link RideStore}); with a list backing the objects
 * are long-lived anyway, but future stub-backed stores will hand out transient
 * objects, so enforcing the contract now keeps migration safe.
 *
 * <p>This class is the seam that later phases replace with a stub-backed store;
 * {@code materialize} / {@code requestIndices} / {@code forEachMaterialized}
 * will then operate on packed stubs rather than full {@link Ride} objects.
 */
public final class MaterializedRideStore implements RideStore {

    private final List<Ride> rides;

    /**
     * @param rides the engine's ride list; held by reference (no defensive copy)
     */
    public MaterializedRideStore(List<Ride> rides) {
        this.rides = rides;
    }

    @Override
    public int size() {
        return rides.size();
    }

    @Override
    public Ride materialize(int row) {
        return rides.get(row);
    }

    @Override
    public void forEachMaterialized(Consumer<Ride> visitor) {
        for (Ride r : rides) {
            visitor.accept(r);
        }
    }

    @Override
    public int[] requestIndices(int row) {
        return rides.get(row).getRequestIndices();
    }
}
