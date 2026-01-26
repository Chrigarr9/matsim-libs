/*
 * *********************************************************************** *
 * project: org.matsim.*
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2024 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** *
 */

package org.matsim.contrib.demand_extraction.config;

/**
 * Strategy for finding candidate stop locations in the ride-pooling system.
 * <p>
 * Different strategies offer trade-offs between computational efficiency,
 * solution quality, and compatibility with existing infrastructure.
 *
 * @author MATSim contributors
 */
public enum StopFindingStrategy {

	/**
	 * Find optimal point in 2D space, then snap to nearest link.
	 * <p>
	 * This strategy first computes the geometrically optimal meeting point
	 * considering all passenger locations, then snaps the result to the
	 * nearest network link. Best suited for dense networks where the
	 * geometric optimum is likely to be close to a valid network location.
	 */
	GEOMETRIC,

	/**
	 * Only consider network nodes as candidate stops.
	 * <p>
	 * This strategy restricts candidate stop locations to network nodes
	 * (intersections). This can be computationally efficient and ensures
	 * stops are at natural meeting points, but may result in longer
	 * walking distances for passengers.
	 */
	NETWORK_NODE,

	/**
	 * Consider all links within search radius.
	 * <p>
	 * This strategy evaluates all network links within a specified search
	 * radius as potential stop locations. Provides the most flexibility
	 * but may be computationally expensive for large search radii or
	 * dense networks.
	 */
	NETWORK_LINK,

	/**
	 * Use predefined stop locations from MATSim TransitStops/Facilities file.
	 * <p>
	 * This strategy uses pre-defined stop locations, typically loaded from
	 * a MATSim transit schedule or facilities file. Ideal for integration
	 * with existing public transport infrastructure or when stop locations
	 * are determined by external constraints (e.g., regulatory requirements,
	 * existing infrastructure).
	 */
	PREDEFINED
}
