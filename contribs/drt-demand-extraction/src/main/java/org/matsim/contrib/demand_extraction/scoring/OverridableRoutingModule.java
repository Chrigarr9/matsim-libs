package org.matsim.contrib.demand_extraction.scoring;

import java.util.List;

import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.router.RoutingModule;
import org.matsim.core.router.RoutingRequest;
import org.matsim.facilities.Facility;

/**
 * Routing module wrapper that checks {@link RoutingOverrideManager} before routing.
 *
 * <p>If an override is set (via ThreadLocal), returns the override elements without
 * routing. Otherwise delegates to the wrapped routing module.
 *
 * <p>This is used by the demand extraction pipeline to score pre-routed trip elements
 * through DMC/eqasim's TripEstimator without re-routing.
 */
public class OverridableRoutingModule implements RoutingModule {

	private final RoutingModule delegate;

	public OverridableRoutingModule(RoutingModule delegate) {
		this.delegate = delegate;
	}

	@Override
	public List<? extends PlanElement> calcRoute(RoutingRequest request) {
		List<? extends PlanElement> override = RoutingOverrideManager.get();
		if (override != null) {
			return override;
		}
		return delegate.calcRoute(request);
	}
}
