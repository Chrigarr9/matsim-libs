package org.matsim.contrib.demand_extraction.algorithm.stops;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.demand_extraction.algorithm.domain.StopLocation;
import org.matsim.core.utils.geometry.CoordUtils;

/**
 * Finds optimal stop locations using geometric centroid approach.
 *
 * Strategy:
 * 1. Calculate the weighted centroid of passenger locations
 * 2. Find candidate links within search radius
 * 3. Select the link that minimizes total walking for all passengers
 *    while respecting individual walk distance constraints
 *
 * The centroid is weighted by inverse of max walk distance, giving more
 * weight to passengers with tighter constraints.
 */
public class GeometricStopFinder implements StopFinder {

	private static final Logger log = LogManager.getLogger(GeometricStopFinder.class);

	private final LinkCandidateFinder linkCandidateFinder;
	private final WalkingDistanceCalculator walkCalculator;
	private final double searchRadius;

	public GeometricStopFinder(
			LinkCandidateFinder linkCandidateFinder,
			WalkingDistanceCalculator walkCalculator,
			double searchRadius) {
		this.linkCandidateFinder = linkCandidateFinder;
		this.walkCalculator = walkCalculator;
		this.searchRadius = searchRadius;
	}

	@Override
	public Optional<StopLocation> findStop(
			List<Coord> passengerLocations,
			double[] maxWalkDistances,
			double departureTime) {

		if (passengerLocations.isEmpty()) {
			return Optional.empty();
		}

		// Calculate weighted centroid (weights = inverse of max walk distance)
		Coord centroid = calculateWeightedCentroid(passengerLocations, maxWalkDistances);

		// Find candidate links that all passengers can reach
		Collection<Link> candidates = linkCandidateFinder.findCandidateLinksForAllPassengers(
				passengerLocations, maxWalkDistances, searchRadius);

		if (candidates.isEmpty()) {
			log.debug("No candidate links found within constraints for {} passengers",
					passengerLocations.size());
			return Optional.empty();
		}

		// Find the best link (minimizes total walking)
		Optional<Link> bestLink = walkCalculator.findBestStopLink(
				passengerLocations, maxWalkDistances, candidates);

		if (bestLink.isEmpty()) {
			return Optional.empty();
		}

		Link link = bestLink.get();

		// Calculate representative coordinate (closest point on link to centroid)
		Coord stopCoord = walkCalculator.getClosestPointOnLink(centroid, link);

		// Calculate snapping penalty (distance from centroid to actual stop)
		double snappingPenalty = CoordUtils.calcEuclideanDistance(centroid, stopCoord);

		return Optional.of(new StopLocation(link.getId(), stopCoord, snappingPenalty));
	}

	/**
	 * Calculate weighted centroid where weights are inverse of max walk distance.
	 * Passengers with tighter constraints get more weight.
	 */
	private Coord calculateWeightedCentroid(List<Coord> locations, double[] maxWalkDistances) {
		double sumX = 0, sumY = 0, sumWeights = 0;

		for (int i = 0; i < locations.size(); i++) {
			// Weight = 1 / maxWalkDistance (tighter constraint = higher weight)
			// Use a minimum to avoid division issues
			double weight = 1.0 / Math.max(maxWalkDistances[i], 1.0);

			sumX += locations.get(i).getX() * weight;
			sumY += locations.get(i).getY() * weight;
			sumWeights += weight;
		}

		return new Coord(sumX / sumWeights, sumY / sumWeights);
	}

	@Override
	public String getName() {
		return "GEOMETRIC";
	}
}
