package org.matsim.contrib.demand_extraction.algorithm.stops;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.utils.geometry.CoordUtils;

/**
 * Calculates walking distances for stop-based pooling.
 *
 * Uses MATSim's CoordUtils.distancePointLinesegment() to calculate the
 * perpendicular (shortest) distance from a point to a link. This follows
 * MATSim's walk-to-link model where agents walk to the nearest point on
 * a link, then "teleport" along the link to board the vehicle.
 */
public class WalkingDistanceCalculator {

	private static final Logger log = LogManager.getLogger(WalkingDistanceCalculator.class);

	// Statistics tracking
	private final AtomicLong totalCalculations = new AtomicLong();
	private final AtomicReference<Double> totalWalkDistance = new AtomicReference<>(0.0);

	// Optional beeline factor (1.0 = Euclidean, 1.3 = typical urban)
	private final double beelineDistanceFactor;

	public WalkingDistanceCalculator() {
		this(1.0); // Default: Euclidean
	}

	public WalkingDistanceCalculator(double beelineDistanceFactor) {
		this.beelineDistanceFactor = beelineDistanceFactor;
	}

	/**
	 * Calculate walk distance from origin to the closest point on a link.
	 * Uses CoordUtils.distancePointLinesegment() for perpendicular distance.
	 */
	public double calculateWalkDistance(Coord origin, Link link) {
		double euclideanDistance = CoordUtils.distancePointLinesegment(
				link.getFromNode().getCoord(),
				link.getToNode().getCoord(),
				origin);

		double walkDistance = euclideanDistance * beelineDistanceFactor;

		// Track statistics
		totalCalculations.incrementAndGet();
		totalWalkDistance.updateAndGet(v -> v + walkDistance);

		return walkDistance;
	}

	/**
	 * Get the closest point on a link to a given origin.
	 */
	public Coord getClosestPointOnLink(Coord origin, Link link) {
		return CoordUtils.orthogonalProjectionOnLineSegment(
				link.getFromNode().getCoord(),
				link.getToNode().getCoord(),
				origin);
	}

	/**
	 * Find the best link for a stop given multiple passenger origins.
	 * Returns the link that minimizes total walking while respecting constraints.
	 */
	public Optional<Link> findBestStopLink(
			List<Coord> passengerOrigins,
			double[] maxWalkDistances,
			Collection<Link> candidateLinks) {

		Link bestLink = null;
		double bestTotalWalk = Double.MAX_VALUE;

		for (Link link : candidateLinks) {
			double totalWalk = 0;
			boolean allWithinConstraints = true;

			for (int i = 0; i < passengerOrigins.size(); i++) {
				double walk = calculateWalkDistance(passengerOrigins.get(i), link);

				if (walk > maxWalkDistances[i]) {
					allWithinConstraints = false;
					break;
				}
				totalWalk += walk;
			}

			if (allWithinConstraints && totalWalk < bestTotalWalk) {
				bestTotalWalk = totalWalk;
				bestLink = link;
			}
		}

		return Optional.ofNullable(bestLink);
	}

	/**
	 * Check if all passengers can walk to a given link within their constraints.
	 */
	public boolean allPassengersCanReach(
			List<Coord> passengerOrigins,
			double[] maxWalkDistances,
			Link link) {

		for (int i = 0; i < passengerOrigins.size(); i++) {
			double walk = calculateWalkDistance(passengerOrigins.get(i), link);
			if (walk > maxWalkDistances[i]) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Calculate walk distances for all passengers to a given link.
	 */
	public double[] calculateWalkDistances(List<Coord> passengerOrigins, Link link) {
		double[] distances = new double[passengerOrigins.size()];
		for (int i = 0; i < passengerOrigins.size(); i++) {
			distances[i] = calculateWalkDistance(passengerOrigins.get(i), link);
		}
		return distances;
	}

	public void logStatistics() {
		long calcs = totalCalculations.get();
		if (calcs > 0) {
			double avgWalk = totalWalkDistance.get() / calcs;
			log.info("Walking distance statistics:");
			log.info("  Total calculations: {}", calcs);
			log.info("  Average walk distance: {}m", String.format("%.1f", avgWalk));
			log.info("  Beeline factor: {}", beelineDistanceFactor);
		}
	}

	public long getTotalCalculations() {
		return totalCalculations.get();
	}

	public double getAverageWalkDistance() {
		long calcs = totalCalculations.get();
		return calcs > 0 ? totalWalkDistance.get() / calcs : 0.0;
	}

	public double getBeelineDistanceFactor() {
		return beelineDistanceFactor;
	}
}
