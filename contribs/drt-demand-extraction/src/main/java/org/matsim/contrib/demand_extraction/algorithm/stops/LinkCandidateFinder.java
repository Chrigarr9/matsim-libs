package org.matsim.contrib.demand_extraction.algorithm.stops;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.utils.geometry.CoordUtils;

/**
 * Finds candidate links within a search radius for stop placement.
 *
 * Used by stop finders to identify potential stop locations on the network.
 * Supports filtering by allowed modes and maximum link length.
 */
public class LinkCandidateFinder {

	private final Network network;
	private final Set<String> allowedModes;
	private final double maxLinkLength;

	/**
	 * Creates a LinkCandidateFinder.
	 *
	 * @param network The MATSim network
	 * @param allowedModes Set of allowed modes for links (null or empty = all links)
	 * @param maxLinkLength Maximum link length to consider (Double.MAX_VALUE = no filter)
	 */
	public LinkCandidateFinder(Network network, Set<String> allowedModes, double maxLinkLength) {
		this.network = network;
		this.allowedModes = allowedModes;
		this.maxLinkLength = maxLinkLength;
	}

	/**
	 * Find all candidate links within the given radius of the center point.
	 *
	 * @param center The center point to search around
	 * @param searchRadius The search radius in meters
	 * @return Collection of links within the radius that pass filters
	 */
	public Collection<Link> findCandidateLinks(Coord center, double searchRadius) {
		List<Link> candidates = new ArrayList<>();

		for (Link link : network.getLinks().values()) {
			// Check mode filter
			if (!isAllowedLink(link)) {
				continue;
			}

			// Check length filter
			if (link.getLength() > maxLinkLength) {
				continue;
			}

			// Check if link is within search radius
			// Use perpendicular distance to link, not just centroid distance
			double distance = CoordUtils.distancePointLinesegment(
					link.getFromNode().getCoord(),
					link.getToNode().getCoord(),
					center);

			if (distance <= searchRadius) {
				candidates.add(link);
			}
		}

		return candidates;
	}

	/**
	 * Find candidate links that all passengers can reach within their max walk distances.
	 *
	 * @param passengerLocations Coordinates of all passengers
	 * @param maxWalkDistances Maximum walk distance for each passenger
	 * @param searchRadius Initial search radius (will use max of this and max walk distances)
	 * @return Collection of links reachable by all passengers
	 */
	public Collection<Link> findCandidateLinksForAllPassengers(
			List<Coord> passengerLocations,
			double[] maxWalkDistances,
			double searchRadius) {

		// Calculate the centroid of passenger locations
		Coord centroid = calculateCentroid(passengerLocations);

		// Use the largest max walk distance as additional search radius
		double maxWalk = 0;
		for (double d : maxWalkDistances) {
			maxWalk = Math.max(maxWalk, d);
		}

		// Find all candidate links within extended radius
		Collection<Link> allCandidates = findCandidateLinks(centroid, searchRadius + maxWalk);

		// Filter to only links reachable by all passengers
		List<Link> reachableCandidates = new ArrayList<>();
		for (Link link : allCandidates) {
			boolean allCanReach = true;
			for (int i = 0; i < passengerLocations.size(); i++) {
				double distance = CoordUtils.distancePointLinesegment(
						link.getFromNode().getCoord(),
						link.getToNode().getCoord(),
						passengerLocations.get(i));

				if (distance > maxWalkDistances[i]) {
					allCanReach = false;
					break;
				}
			}
			if (allCanReach) {
				reachableCandidates.add(link);
			}
		}

		return reachableCandidates;
	}

	private boolean isAllowedLink(Link link) {
		if (allowedModes == null || allowedModes.isEmpty()) {
			return true;
		}
		for (String mode : allowedModes) {
			if (link.getAllowedModes().contains(mode)) {
				return true;
			}
		}
		return false;
	}

	private Coord calculateCentroid(List<Coord> coords) {
		double sumX = 0, sumY = 0;
		for (Coord c : coords) {
			sumX += c.getX();
			sumY += c.getY();
		}
		return new Coord(sumX / coords.size(), sumY / coords.size());
	}

	public Network getNetwork() {
		return network;
	}
}
