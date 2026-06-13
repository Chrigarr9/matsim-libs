package org.matsim.contrib.demand_extraction.algorithm.bamas.ride;

import java.util.ArrayList;
import java.util.List;

import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;

/**
 * Small static helpers over {@link RideStore}.
 */
public final class RideStores {

	private RideStores() {
	}

	/**
	 * Eagerly materialize a whole {@link RideStore} into a {@code List<Ride>}.
	 *
	 * <p>This forces the entire output into memory, so it defeats the streaming
	 * memory benefit of a {@link ColumnarRideStore}. It exists for callers that genuinely
	 * need a random-access list (tests, ad-hoc analysis) and for back-compat at call
	 * sites that previously received a fat list directly from the engine. Production
	 * consumers (post-processor, CSV writer) should iterate via
	 * {@link RideStore#forEachMaterialized} instead.
	 *
	 * <p>The {@link RideStore} contract forbids retaining the visited {@link Ride} beyond
	 * the callback; both shipped stores ({@link MaterializedRideStore},
	 * {@link ColumnarRideStore}) hand out a distinct object per element, so collecting them
	 * into a list is safe.
	 *
	 * @param store the store to drain
	 * @return a fresh list holding every ride in the store's order
	 */
	public static List<Ride> toList(RideStore store) {
		List<Ride> out = new ArrayList<>(store.size());
		store.forEachMaterialized(out::add);
		return out;
	}
}
