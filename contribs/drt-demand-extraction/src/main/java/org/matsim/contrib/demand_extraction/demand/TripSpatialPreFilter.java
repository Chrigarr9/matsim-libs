package org.matsim.contrib.demand_extraction.demand;

import java.util.Collection;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.geotools.api.feature.simple.SimpleFeature;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.utils.gis.GeoFileReader;

/**
 * Checks whether a trip (origin → destination) passes the configured spatial filters:
 * radius-from-center and/or exclusion-zone shapefile.
 *
 * Used in two places:
 *  1. {@link DemandExtractionListener} — pre-filters the population before mode routing so
 *     we only route persons who have at least one potentially-eligible trip.
 *  2. {@link DrtRequestFactory} — per-trip check when building requests.
 */
public class TripSpatialPreFilter {
    private static final Logger log = LogManager.getLogger(TripSpatialPreFilter.class);

    private final boolean hasSpatialFilter;
    private final double spatialCenterX;
    private final double spatialCenterY;
    private final double spatialRadiusSq;

    private final boolean hasExclusionZone;
    private final Geometry exclusionZone;
    private final GeometryFactory gf;

    public TripSpatialPreFilter(ExMasConfigGroup config) {
        hasSpatialFilter = config.hasTripSpatialFilter();
        if (hasSpatialFilter) {
            spatialCenterX = config.getTripFilterCenterX();
            spatialCenterY = config.getTripFilterCenterY();
            double r = config.getTripFilterRadiusKm() * 1000.0;
            spatialRadiusSq = r * r;
            log.info("Trip spatial filter: {}km around ({}, {})",
                    config.getTripFilterRadiusKm(), spatialCenterX, spatialCenterY);
        } else {
            spatialCenterX = 0;
            spatialCenterY = 0;
            spatialRadiusSq = 0;
        }

        hasExclusionZone = config.hasTripExclusionZone();
        Geometry zone = null;
        GeometryFactory factory = null;
        if (hasExclusionZone) {
            String shapePath = config.getTripFilterExclusionShapefilePath();
            Collection<SimpleFeature> features = GeoFileReader.getAllFeatures(shapePath);
            for (SimpleFeature feature : features) {
                Geometry geom = (Geometry) feature.getDefaultGeometry();
                if (geom != null) {
                    zone = (zone == null) ? geom : zone.union(geom);
                }
            }
            factory = new GeometryFactory();
            log.info("Trip exclusion zone loaded from {}: {} feature(s), union geom type={}",
                    shapePath, features.size(), zone != null ? zone.getGeometryType() : "null");
        }
        exclusionZone = zone;
        gf = factory;
    }

    /** True if at least one spatial filter is configured. */
    public boolean isActive() {
        return hasSpatialFilter || hasExclusionZone;
    }

    /**
     * Returns true if the trip passes all active spatial filters.
     * A trip is eligible when:
     *  - Both O and D are within the radius (if radius filter active), AND
     *  - NOT both O and D are inside the exclusion zone (if exclusion zone active).
     */
    public boolean isTripEligible(Coord oCoord, Coord dCoord) {
        if (oCoord == null || dCoord == null) return false;

        if (hasSpatialFilter) {
            double dxO = oCoord.getX() - spatialCenterX, dyO = oCoord.getY() - spatialCenterY;
            double dxD = dCoord.getX() - spatialCenterX, dyD = dCoord.getY() - spatialCenterY;
            if ((dxO * dxO + dyO * dyO) > spatialRadiusSq || (dxD * dxD + dyD * dyD) > spatialRadiusSq) {
                return false;
            }
        }

        if (hasExclusionZone && exclusionZone != null) {
            Point oPt = gf.createPoint(new org.locationtech.jts.geom.Coordinate(oCoord.getX(), oCoord.getY()));
            Point dPt = gf.createPoint(new org.locationtech.jts.geom.Coordinate(dCoord.getX(), dCoord.getY()));
            if (exclusionZone.contains(oPt) && exclusionZone.contains(dPt)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Returns true if the person has at least one trip that passes all active spatial filters.
     * When no filter is active, always returns true.
     */
    public boolean isPersonEligible(Person person) {
        if (!isActive()) return true;
        for (TripStructureUtils.Trip trip : TripStructureUtils.getTrips(person.getSelectedPlan())) {
            if (isTripEligible(trip.getOriginActivity().getCoord(), trip.getDestinationActivity().getCoord())) {
                return true;
            }
        }
        return false;
    }
}
