package org.matsim.contrib.demand_extraction.algorithm.stops;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.demand_extraction.algorithm.domain.StopLocation;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.facilities.ActivityFacilities;
import org.matsim.facilities.ActivityFacility;

/**
 * Finds optimal stop locations from predefined facilities.
 *
 * Uses MATSim ActivityFacilities (loaded from facilities XML file) as
 * candidate stop locations. This is useful for integration with existing
 * transit infrastructure or when stop locations are determined by
 * external constraints.
 */
public class PredefinedStopFinder implements StopFinder {

	private static final Logger log = LogManager.getLogger(PredefinedStopFinder.class);

	private final ActivityFacilities facilities;
	private final double searchRadius;
	private final double beelineDistanceFactor;

	public PredefinedStopFinder(
			ActivityFacilities facilities,
			double searchRadius,
			double beelineDistanceFactor) {
		this.facilities = facilities;
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

		if (facilities == null || facilities.getFacilities().isEmpty()) {
			log.warn("No predefined facilities available");
			return Optional.empty();
		}

		// Calculate centroid
		Coord centroid = calculateCentroid(passengerLocations);

		// Find candidate facilities within search radius
		List<ActivityFacility> candidates = new ArrayList<>();
		for (ActivityFacility facility : facilities.getFacilities().values()) {
			double distanceToCentroid = CoordUtils.calcEuclideanDistance(centroid, facility.getCoord());
			if (distanceToCentroid <= searchRadius + getMaxWalkDistance(maxWalkDistances)) {
				candidates.add(facility);
			}
		}

		// Find best facility (minimizes total walking, respects constraints)
		ActivityFacility bestFacility = null;
		double bestTotalWalk = Double.MAX_VALUE;

		for (ActivityFacility facility : candidates) {
			double totalWalk = 0;
			boolean allCanReach = true;

			for (int i = 0; i < passengerLocations.size(); i++) {
				double walk = CoordUtils.calcEuclideanDistance(
						passengerLocations.get(i), facility.getCoord()) * beelineDistanceFactor;

				if (walk > maxWalkDistances[i]) {
					allCanReach = false;
					break;
				}
				totalWalk += walk;
			}

			if (allCanReach && totalWalk < bestTotalWalk) {
				bestTotalWalk = totalWalk;
				bestFacility = facility;
			}
		}

		if (bestFacility == null) {
			log.debug("No valid predefined stop found for {} passengers", passengerLocations.size());
			return Optional.empty();
		}

		// Get link ID from facility
		Id<Link> linkId = bestFacility.getLinkId();
		if (linkId == null) {
			log.warn("Facility {} has no link ID assigned", bestFacility.getId());
			return Optional.empty();
		}

		return Optional.of(new StopLocation(linkId, bestFacility.getCoord(), 0.0));
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
		return "PREDEFINED";
	}
}
