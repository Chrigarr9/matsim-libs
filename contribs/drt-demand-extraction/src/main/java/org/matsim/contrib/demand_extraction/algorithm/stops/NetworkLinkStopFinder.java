package org.matsim.contrib.demand_extraction.algorithm.stops;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.demand_extraction.algorithm.domain.StopLocation;

/**
 * Finds optimal stop locations by considering all links within search radius.
 *
 * This is the most flexible strategy but can be computationally expensive
 * for dense networks or large search radii. Uses LinkCandidateFinder for
 * efficient candidate selection.
 */
public class NetworkLinkStopFinder implements StopFinder {

	private static final Logger log = LogManager.getLogger(NetworkLinkStopFinder.class);

	private final LinkCandidateFinder linkCandidateFinder;
	private final WalkingDistanceCalculator walkCalculator;
	private final double searchRadius;

	public NetworkLinkStopFinder(
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

		// Find candidate links that all passengers can reach
		Collection<Link> candidates = linkCandidateFinder.findCandidateLinksForAllPassengers(
				passengerLocations, maxWalkDistances, searchRadius);

		if (candidates.isEmpty()) {
			log.debug("No candidate links found for {} passengers", passengerLocations.size());
			return Optional.empty();
		}

		// Find the best link (minimizes total walking)
		Optional<Link> bestLink = walkCalculator.findBestStopLink(
				passengerLocations, maxWalkDistances, candidates);

		if (bestLink.isEmpty()) {
			return Optional.empty();
		}

		Link link = bestLink.get();

		// Calculate centroid for representative coordinate
		Coord centroid = calculateCentroid(passengerLocations);
		Coord stopCoord = walkCalculator.getClosestPointOnLink(centroid, link);

		return Optional.of(new StopLocation(link.getId(), stopCoord, 0.0));
	}

	private Coord calculateCentroid(List<Coord> coords) {
		double sumX = 0, sumY = 0;
		for (Coord c : coords) {
			sumX += c.getX();
			sumY += c.getY();
		}
		return new Coord(sumX / coords.size(), sumY / coords.size());
	}

	@Override
	public String getName() {
		return "NETWORK_LINK";
	}
}
