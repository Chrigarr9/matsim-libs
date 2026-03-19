package org.matsim.contrib.demand_extraction.scoring;

import java.util.List;

import org.matsim.api.core.v01.population.PlanElement;

/**
 * Thread-local override for routing results.
 *
 * <p>When set, {@link OverridableRoutingModule} returns the override elements
 * instead of actually routing. This lets DMC/eqasim adapters score pre-routed
 * trips through their normal TripEstimator pipeline without re-routing.
 *
 * <p>Usage:
 * <pre>{@code
 * RoutingOverrideManager.set(preRoutedElements);
 * try {
 *     TripCandidate candidate = estimator.estimateTrip(person, mode, trip, previous);
 *     // estimator internally calls TripRouter, which returns our override elements
 * } finally {
 *     RoutingOverrideManager.clear();
 * }
 * }</pre>
 */
public final class RoutingOverrideManager {

	private static final ThreadLocal<List<? extends PlanElement>> OVERRIDE = new ThreadLocal<>();

	private RoutingOverrideManager() {
	}

	/**
	 * Set override elements for the current thread. The next call to
	 * {@link OverridableRoutingModule#calcRoute} will return these elements.
	 */
	public static void set(List<? extends PlanElement> elements) {
		OVERRIDE.set(elements);
	}

	/**
	 * Get the current override, or null if none is set.
	 */
	public static List<? extends PlanElement> get() {
		return OVERRIDE.get();
	}

	/**
	 * Clear the override for the current thread. Always call in a finally block.
	 */
	public static void clear() {
		OVERRIDE.remove();
	}

	/**
	 * Returns true if an override is currently set for this thread.
	 */
	public static boolean hasOverride() {
		return OVERRIDE.get() != null;
	}
}
