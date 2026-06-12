package org.matsim.contrib.demand_extraction.algorithm.network;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.vehicles.Vehicle;

/**
 * Tie-breaking decorator that makes least-cost paths unique for ANY base disutility:
 *
 * <pre>
 *   cost(link, t) = base(link, t)  + eps * length(link)
 *   min(link)     = baseMin(link)  + eps * length(link)
 * </pre>
 *
 * <p><b>Why:</b> two routing engines fill the same {@code MatsimNetworkCache}
 * (LeastCostPathTree SSSP in {@code batchPrecompute}, SpeedyALT point-to-point on cache
 * miss). With a time-only base, many OD pairs have multiple equal-cost paths; the engines
 * tie-break differently and run-to-run thread scheduling decides which engine populates a
 * key first. A strictly positive distance term makes the optimum unique, so every optimal
 * algorithm, instance, thread count, and JVM returns the identical path.
 *
 * <p><b>eps auto-scaling:</b> {@code eps = 1e-6 x} the smallest positive cost-per-meter of
 * the effective gradient over all network links. Small enough to never override a real cost
 * difference, large enough to sit above double-precision noise on realistic path costs.
 * Unit-independent (works for seconds or utils); no config knob.
 *
 * <p><b>Degenerate-base fallback:</b> if the base has (near-)zero gradient on every link —
 * the eqasim trap where all MATSim mode params are 0 and the default randomizing factory
 * returns 0.0 cost everywhere, degenerating A* to exhaustive search (the historical "70x
 * slowdown") and making every path a tie — the base is ignored and travel time becomes the
 * gradient: {@code cost = travelTime + eps*length}. A loud WARN names the base class.
 * "When there are no real costs, time decides."
 *
 * <p><b>Admissibility:</b> {@code baseMin <= base(t)} for all t implies
 * {@code baseMin + eps*len <= base(t) + eps*len}, so wrapping preserves the base's
 * heuristic admissibility contract. (Offline travel times must additionally be clamped to
 * freespeed — see {@link OfflineTravelTimes}.)
 */
public final class DeterministicTravelDisutility implements TravelDisutility {

	private static final Logger log = LogManager.getLogger(DeterministicTravelDisutility.class);

	/** Relative scale of the distance tie-breaker vs the smallest real cost gradient. */
	static final double EPSILON_FACTOR = 1e-6;
	/** At or below this cost-per-meter a link contributes no usable gradient. */
	static final double DEGENERATE_GRADIENT_THRESHOLD = 1e-12;

	private final TravelDisutility base; // null when degenerateBaseFallback
	private final TravelTime travelTime;
	private final double epsilon;
	private final boolean degenerateBaseFallback;

	private DeterministicTravelDisutility(TravelDisutility base, TravelTime travelTime,
			double epsilon, boolean degenerateBaseFallback) {
		this.base = base;
		this.travelTime = travelTime;
		this.epsilon = epsilon;
		this.degenerateBaseFallback = degenerateBaseFallback;
	}

	/**
	 * Wraps {@code base} with the distance tie-breaker. Idempotent: wrapping an
	 * already-wrapped instance returns it unchanged (no double-eps), so scenario wiring
	 * and {@code MatsimNetworkCache} can both wrap defensively.
	 */
	public static DeterministicTravelDisutility wrap(TravelDisutility base, TravelTime travelTime,
			Network network) {
		if (base instanceof DeterministicTravelDisutility already) {
			return already;
		}

		double maxGradient = 0.0;
		double minPositiveGradient = Double.POSITIVE_INFINITY;
		for (Link link : network.getLinks().values()) {
			double length = link.getLength();
			if (length <= 0.0) {
				continue;
			}
			double gradient = base.getLinkMinimumTravelDisutility(link) / length;
			if (!Double.isFinite(gradient) || gradient < 0.0) {
				continue;
			}
			if (gradient > maxGradient) {
				maxGradient = gradient;
			}
			if (gradient > DEGENERATE_GRADIENT_THRESHOLD && gradient < minPositiveGradient) {
				minPositiveGradient = gradient;
			}
		}

		boolean degenerate = maxGradient <= DEGENERATE_GRADIENT_THRESHOLD;
		if (degenerate) {
			log.warn("Base TravelDisutility {} has zero cost gradient on every network link "
					+ "(the 'routing cost is 0.0 everywhere' trap: degenerate A* + maximal "
					+ "tie-breaking nondeterminism). Falling back to travel time as the routing "
					+ "gradient: cost = travelTime + eps*length.", base.getClass().getName());
			minPositiveGradient = Double.POSITIVE_INFINITY;
			for (Link link : network.getLinks().values()) {
				if (link.getLength() <= 0.0 || link.getFreespeed() <= 0.0) {
					continue;
				}
				double gradient = 1.0 / link.getFreespeed(); // min seconds per meter
				if (gradient < minPositiveGradient) {
					minPositiveGradient = gradient;
				}
			}
		}
		if (!Double.isFinite(minPositiveGradient)) {
			throw new IllegalArgumentException(
					"Cannot derive epsilon: no network link has a positive cost gradient");
		}

		double epsilon = EPSILON_FACTOR * minPositiveGradient;
		log.info("DeterministicTravelDisutility: base={}, degenerateFallback={}, "
						+ "minCostPerMeter={}, epsilon={} per meter",
				base.getClass().getSimpleName(), degenerate, minPositiveGradient, epsilon);
		return new DeterministicTravelDisutility(degenerate ? null : base, travelTime, epsilon, degenerate);
	}

	@Override
	public double getLinkTravelDisutility(Link link, double time, Person person, Vehicle vehicle) {
		double baseCost = degenerateBaseFallback
				? travelTime.getLinkTravelTime(link, time, person, vehicle)
				: base.getLinkTravelDisutility(link, time, person, vehicle);
		return baseCost + epsilon * link.getLength();
	}

	@Override
	public double getLinkMinimumTravelDisutility(Link link) {
		double baseMin = degenerateBaseFallback
				? link.getLength() / link.getFreespeed()
				: base.getLinkMinimumTravelDisutility(link);
		return baseMin + epsilon * link.getLength();
	}

	double getEpsilon() {
		return epsilon;
	}

	boolean isDegenerateBaseFallback() {
		return degenerateBaseFallback;
	}
}
