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

    // Paper-2 Extension 2: optional inclusion polygon UNIONED with the radius
    // circle. When present, an endpoint is "included" if it is within the radius
    // OR inside this polygon (the Lyon metropole). This is how the urban + rural
    // fleet runs keep connecting commutes whose city-end lies in the metropole
    // core (outside the rural corridor circle): radius scopes the rural end, the
    // polygon scopes the city end. Absent (Paper-1, Kelheim) => pure radius.
    private final boolean hasInclusionPolygon;
    private final Geometry inclusionPolygon;

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

        hasInclusionPolygon = config.hasMetropolePolygon();
        Geometry incl = null;
        if (hasInclusionPolygon) {
            String inclPath = config.getMetropolePolygonPath();
            Collection<SimpleFeature> features = GeoFileReader.getAllFeatures(inclPath);
            for (SimpleFeature feature : features) {
                Geometry geom = (Geometry) feature.getDefaultGeometry();
                if (geom != null) {
                    incl = (incl == null) ? geom : incl.union(geom);
                }
            }
            if (factory == null) factory = new GeometryFactory();
            log.info("Trip inclusion polygon (unioned with radius) loaded from {}: {} feature(s), union geom type={}",
                    inclPath, features.size(), incl != null ? incl.getGeometryType() : "null");
        }
        inclusionPolygon = incl;

        gf = factory;
    }

    /**
     * Polygon-only constructor for {@link #containsPoint} queries: no radius
     * filter, no eligibility role. Used by Paper-2 Extension 2's virtual-trip
     * expansion to detect the urban endpoint of a connecting request from the
     * metropole polygon, independently of any eligibility exclusion zone.
     */
    private TripSpatialPreFilter(Geometry polygonOnly) {
        this.hasSpatialFilter = false;
        this.spatialCenterX = 0;
        this.spatialCenterY = 0;
        this.spatialRadiusSq = 0;
        this.hasExclusionZone = polygonOnly != null;
        this.exclusionZone = polygonOnly;
        this.hasInclusionPolygon = false;
        this.inclusionPolygon = null;
        this.gf = polygonOnly != null ? new GeometryFactory() : null;
    }

    /** Builds a polygon-only filter from an in-memory geometry (for tests + reuse). */
    public static TripSpatialPreFilter forPolygon(Geometry polygon) {
        return new TripSpatialPreFilter(polygon);
    }

    /**
     * Test seam: builds the radius-circle UNION inclusion-polygon eligibility
     * filter (no exclusion zone) from in-memory parameters, mirroring the
     * config-driven main constructor's union semantics without a shapefile.
     * {@code radiusKm <= 0} disables the radius term; {@code inclusionPolygon ==
     * null} disables the polygon term.
     */
    static TripSpatialPreFilter forRadiusUnionPolygon(double centerX, double centerY,
            double radiusKm, Geometry inclusionPolygon) {
        return new TripSpatialPreFilter(centerX, centerY, radiusKm, inclusionPolygon);
    }

    private TripSpatialPreFilter(double centerX, double centerY, double radiusKm, Geometry inclusionPolygon) {
        this.hasSpatialFilter = radiusKm > 0;
        this.spatialCenterX = centerX;
        this.spatialCenterY = centerY;
        double r = radiusKm * 1000.0;
        this.spatialRadiusSq = r * r;
        this.hasExclusionZone = false;
        this.exclusionZone = null;
        this.hasInclusionPolygon = inclusionPolygon != null;
        this.inclusionPolygon = inclusionPolygon;
        this.gf = new GeometryFactory();
    }

    /**
     * Builds a polygon-only filter from a shapefile, unioning all features. Used
     * as the metropole source for connecting-request expansion when a dedicated
     * metropole polygon is configured (decoupled from the exclusion zone).
     */
    public static TripSpatialPreFilter forPolygonFile(String shapefilePath) {
        Collection<SimpleFeature> features = GeoFileReader.getAllFeatures(shapefilePath);
        Geometry zone = null;
        for (SimpleFeature feature : features) {
            Geometry geom = (Geometry) feature.getDefaultGeometry();
            if (geom != null) {
                zone = (zone == null) ? geom : zone.union(geom);
            }
        }
        log.info("Metropole polygon source loaded from {}: {} feature(s), union geom type={}",
                shapefilePath, features.size(), zone != null ? zone.getGeometryType() : "null");
        return new TripSpatialPreFilter(zone);
    }

    /** True if at least one spatial filter is configured. */
    public boolean isActive() {
        return hasSpatialFilter || hasExclusionZone || hasInclusionPolygon;
    }

    /**
     * Returns true if the endpoint passes the inclusion test: within the radius
     * circle OR inside the inclusion polygon. When neither inclusion mechanism is
     * configured this is not called (see {@link #isTripEligible}).
     */
    private boolean isEndpointIncluded(Coord c) {
        if (hasSpatialFilter) {
            double dx = c.getX() - spatialCenterX, dy = c.getY() - spatialCenterY;
            if ((dx * dx + dy * dy) <= spatialRadiusSq) return true;
        }
        if (hasInclusionPolygon && inclusionPolygon != null) {
            Point p = gf.createPoint(new org.locationtech.jts.geom.Coordinate(c.getX(), c.getY()));
            if (inclusionPolygon.contains(p)) return true;
        }
        return false;
    }

    /**
     * Returns true if the trip passes all active spatial filters.
     * A trip is eligible when:
     *  - Both O and D are inside the inclusion region (radius circle UNION the
     *    inclusion polygon), if any inclusion mechanism is active, AND
     *  - NOT both O and D are inside the exclusion zone (if exclusion zone active).
     */
    public boolean isTripEligible(Coord oCoord, Coord dCoord) {
        if (oCoord == null || dCoord == null) return false;

        if (hasSpatialFilter || hasInclusionPolygon) {
            if (!isEndpointIncluded(oCoord) || !isEndpointIncluded(dCoord)) {
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
     * Returns true when an exclusion-zone polygon is configured and the supplied
     * coordinate falls inside its union geometry. Used by Paper-2 Extension 2's
     * virtual-trip expansion to determine which endpoint of a {@code connecting}
     * request is inside the metropole (the urban end) and which is outside (the
     * rural end). The exclusion polygon doubles as the metropole polygon under
     * the Lyon scenario fixture; if no exclusion zone is configured this always
     * returns {@code false}.
     */
    public boolean containsPoint(Coord c) {
        if (!hasExclusionZone || exclusionZone == null || c == null) {
            return false;
        }
        Point p = gf.createPoint(new org.locationtech.jts.geom.Coordinate(c.getX(), c.getY()));
        return exclusionZone.contains(p);
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
