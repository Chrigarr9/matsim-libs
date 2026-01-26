package org.matsim.contrib.demand_extraction.algorithm.stops;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.contrib.demand_extraction.algorithm.domain.StopLocation;
import org.matsim.core.utils.geometry.CoordUtils;

/**
 * Finds optimal stop locations using only network nodes.
 *
 * Strategy:
 * 1. Find all network nodes within search radius of the centroid
 * 2. For each node, check if all passengers can reach it
 * 3. Select the node that minimizes total walking
 * 4. Use one of the node's outgoing links as the stop location
 */
public class NetworkNodeStopFinder implements StopFinder {

	private static final Logger log = LogManager.getLogger(NetworkNodeStopFinder.class);

	private final Network network;
	private final double searchRadius;
	private final double beelineDistanceFactor;

	public NetworkNodeStopFinder(Network network, double searchRadius, double beelineDistanceFactor) {
		this.network = network;
		this.searchRadius = searchRadius;
		this.beelineDistanceFactor = beelineDistanceFactor;
	}

	@Override
	public Optional<StopLocation> findStop(
			List<Coord> passengerLocations,
			double[] maxWalkDistances,
			double departureTime) {

		if (passengerLocations.isEmpty()) {
			return Optional.empty();
		}

		// Calculate centroid
		Coord centroid = calculateCentroid(passengerLocations);

		// Find candidate nodes within search radius
		List<Node> candidateNodes = new ArrayList<>();
		for (Node node : network.getNodes().values()) {
			double distanceToCentroid = CoordUtils.calcEuclideanDistance(centroid, node.getCoord());
			if (distanceToCentroid <= searchRadius + getMaxWalkDistance(maxWalkDistances)) {
				candidateNodes.add(node);
			}
		}

		// Find best node (minimizes total walking, respects constraints)
		Node bestNode = null;
		double bestTotalWalk = Double.MAX_VALUE;

		for (Node node : candidateNodes) {
			double totalWalk = 0;
			boolean allCanReach = true;

			for (int i = 0; i < passengerLocations.size(); i++) {
				double walk = CoordUtils.calcEuclideanDistance(
						passengerLocations.get(i), node.getCoord()) * beelineDistanceFactor;

				if (walk > maxWalkDistances[i]) {
					allCanReach = false;
					break;
				}
				totalWalk += walk;
			}

			if (allCanReach && totalWalk < bestTotalWalk) {
				bestTotalWalk = totalWalk;
				bestNode = node;
			}
		}

		if (bestNode == null) {
			log.debug("No valid node found for {} passengers", passengerLocations.size());
			return Optional.empty();
		}

		// Use one of the node's outgoing links as the stop link
		Link stopLink = getStopLinkForNode(bestNode);
		if (stopLink == null) {
			log.warn("Node {} has no outgoing links", bestNode.getId());
			return Optional.empty();
		}

		return Optional.of(new StopLocation(stopLink.getId(), bestNode.getCoord(), 0.0));
	}

	private Link getStopLinkForNode(Node node) {
		// Prefer an outgoing link, fallback to incoming
		if (!node.getOutLinks().isEmpty()) {
			return node.getOutLinks().values().iterator().next();
		}
		if (!node.getInLinks().isEmpty()) {
			return node.getInLinks().values().iterator().next();
		}
		return null;
	}

	private Coord calculateCentroid(List<Coord> coords) {
		double sumX = 0, sumY = 0;
		for (Coord c : coords) {
			sumX += c.getX();
			sumY += c.getY();
		}
		return new Coord(sumX / coords.size(), sumY / coords.size());
	}

	private double getMaxWalkDistance(double[] distances) {
		double max = 0;
		for (double d : distances) {
			max = Math.max(max, d);
		}
		return max;
	}

	@Override
	public String getName() {
		return "NETWORK_NODE";
	}
}
