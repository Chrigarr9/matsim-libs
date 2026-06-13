package org.matsim.contrib.demand_extraction.algorithm.network;

import org.matsim.api.core.v01.network.Network;
import org.matsim.core.router.costcalculators.TravelDisutilityFactory;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;

/**
 * {@link TravelDisutilityFactory} decorator that wraps a base factory's output in
 * {@link DeterministicTravelDisutility}.
 *
 * <p><b>Why:</b> {@link org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache}
 * already wraps the mode disutility for Phase-2 served routing, so served distances are unique
 * across engine/thread/JVM. But Phase-1 quantities — {@code req.directDistance} /
 * {@code directTravelTime}, routed through {@code TripRouter.calcRoute} in
 * {@code ModeRoutingCache} — use the <em>global</em> {@code @Named(car)} factory directly. With a
 * time-only base ({@code OnlyTimeDependentTravelDisutility}) many OD pairs have multiple
 * equal-time paths of different length; {@code cacheModes} runs parallel, so which equal-cost path
 * each per-thread router returns varies run-to-run and {@code directDistance} drifts. Binding this
 * factory for {@code @Named(car)} gives {@code TripRouter} the same {@code +eps*length}
 * tie-breaker the cache uses, so Phase-1 routing is deterministic by construction too.
 *
 * <p>The {@code eps} tie-break only canonicalizes <em>exact</em> equal-cost ties; it does not
 * neutralize a stochastic base (e.g. {@code RandomizingTimeDistanceTravelDisutility} with
 * {@code routingRandomness &gt; 0}), whose per-instance perturbations dwarf {@code eps}. Wrap a
 * non-random base (time-only here), or set {@code routingRandomness=0} on a randomizing one.
 *
 * <p>Idempotent: {@link DeterministicTravelDisutility#wrap} returns an already-wrapped instance
 * unchanged, so binding this and letting the cache wrap again does not double the {@code eps}.
 */
public final class DeterministicTravelDisutilityFactory implements TravelDisutilityFactory {

	private final TravelDisutilityFactory base;
	private final Network network;

	public DeterministicTravelDisutilityFactory(TravelDisutilityFactory base, Network network) {
		this.base = base;
		this.network = network;
	}

	@Override
	public TravelDisutility createTravelDisutility(TravelTime travelTime) {
		return DeterministicTravelDisutility.wrap(base.createTravelDisutility(travelTime), travelTime, network);
	}
}
