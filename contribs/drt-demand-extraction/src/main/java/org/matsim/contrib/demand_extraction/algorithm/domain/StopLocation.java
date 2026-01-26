package org.matsim.contrib.demand_extraction.algorithm.domain;

import java.util.Objects;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;

/**
 * Immutable value object representing a pickup/dropoff point for stop-based pooling.
 *
 * In stop-based ride-pooling (HyperPool), passengers walk to designated stop locations
 * rather than being picked up at their exact origin. This class represents such a stop,
 * linking it to the underlying MATSim network.
 *
 * DESIGN:
 * - linkId: The network link where the stop is located (used for routing)
 * - coord: The coordinate of the stop (for visualization/analysis)
 * - snappingPenalty: Additional distance incurred due to snapping a request to this stop
 *
 * Two StopLocations are considered equal if they have the same linkId, regardless of
 * coordinate or snapping penalty differences. This reflects that the routing behavior
 * is determined solely by the link.
 */
public final class StopLocation {
    private final Id<Link> linkId;
    private final Coord coord;
    private final double snappingPenalty;

    /**
     * Creates a new StopLocation with a snapping penalty.
     *
     * @param linkId the network link where the stop is located (must not be null)
     * @param coord the coordinate of the stop (must not be null)
     * @param snappingPenalty distance added due to snapping (in meters, must be >= 0)
     */
    public StopLocation(Id<Link> linkId, Coord coord, double snappingPenalty) {
        if (linkId == null) {
            throw new IllegalArgumentException("linkId must not be null");
        }
        if (coord == null) {
            throw new IllegalArgumentException("coord must not be null");
        }
        if (snappingPenalty < 0) {
            throw new IllegalArgumentException("snappingPenalty cannot be negative: " + snappingPenalty);
        }
        this.linkId = linkId;
        this.coord = coord;
        this.snappingPenalty = snappingPenalty;
    }

    /**
     * Creates a new StopLocation without a snapping penalty.
     *
     * @param linkId the network link where the stop is located (must not be null)
     * @param coord the coordinate of the stop (must not be null)
     */
    public StopLocation(Id<Link> linkId, Coord coord) {
        this(linkId, coord, 0.0);
    }

    // Getters
    public Id<Link> getLinkId() { return linkId; }
    public Coord getCoord() { return coord; }
    public double getSnappingPenalty() { return snappingPenalty; }

    /**
     * Returns whether this stop has a snapping penalty applied.
     */
    public boolean hasSnappingPenalty() {
        return snappingPenalty > 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        StopLocation that = (StopLocation) o;
        return linkId.equals(that.linkId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(linkId);
    }

    @Override
    public String toString() {
        if (hasSnappingPenalty()) {
            return String.format("StopLocation[link=%s, coord=(%.1f,%.1f), snap=%.1fm]",
                    linkId, coord.getX(), coord.getY(), snappingPenalty);
        }
        return String.format("StopLocation[link=%s, coord=(%.1f,%.1f)]",
                linkId, coord.getX(), coord.getY());
    }
}
