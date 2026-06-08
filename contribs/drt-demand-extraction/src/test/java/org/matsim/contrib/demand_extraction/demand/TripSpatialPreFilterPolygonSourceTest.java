package org.matsim.contrib.demand_extraction.demand;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.matsim.api.core.v01.Coord;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Paper-2 Extension 2 (Task 1c): the polygon-only {@link TripSpatialPreFilter}
 * source used to detect the urban endpoint of a connecting request during
 * virtual-trip expansion, decoupled from the eligibility exclusion zone so the
 * URBAN fleet can supply the metropole geometry without dropping urban_intra.
 */
class TripSpatialPreFilterPolygonSourceTest {

    private static Geometry square(double minX, double minY, double maxX, double maxY) {
        GeometryFactory gf = new GeometryFactory();
        return gf.createPolygon(new Coordinate[] {
                new Coordinate(minX, minY),
                new Coordinate(maxX, minY),
                new Coordinate(maxX, maxY),
                new Coordinate(minX, maxY),
                new Coordinate(minX, minY),
        });
    }

    @Test
    void forPolygon_containsPoint_insideAndOutside() {
        TripSpatialPreFilter src = TripSpatialPreFilter.forPolygon(
                square(0.0, 0.0, 1_000.0, 1_000.0));
        assertTrue(src.containsPoint(new Coord(500.0, 500.0)),
                "interior point is inside the metropole polygon");
        assertFalse(src.containsPoint(new Coord(2_000.0, 2_000.0)),
                "exterior point is outside the metropole polygon");
    }

    @Test
    void forPolygon_nullGeometry_containsNothing() {
        TripSpatialPreFilter src = TripSpatialPreFilter.forPolygon(null);
        assertFalse(src.containsPoint(new Coord(500.0, 500.0)),
                "null polygon -> no containment (expansion falls back to default)");
    }

    @Test
    void metropolePolygonPath_configAccessors() {
        ExMasConfigGroup cfg = new ExMasConfigGroup();
        assertFalse(cfg.hasMetropolePolygon(), "unset by default");
        cfg.setMetropolePolygonPath("/tmp/metropole.shp");
        assertTrue(cfg.hasMetropolePolygon());
    }

    // ---- radius UNION inclusion-polygon eligibility (the rural<->urban fix) ----

    @Test
    void radiusUnionPolygon_endpointInEitherRegionIsIncluded() {
        // Rural corridor: 1 km radius around (0,0). City polygon: far square,
        // entirely outside the circle (mirrors the Lyon core outside the corridor).
        Geometry city = square(10_000.0, 10_000.0, 12_000.0, 12_000.0);
        TripSpatialPreFilter f = TripSpatialPreFilter.forRadiusUnionPolygon(0.0, 0.0, 1.0, city);

        Coord ruralEnd = new Coord(500.0, 500.0);     // inside radius, outside polygon
        Coord cityEnd = new Coord(11_000.0, 11_000.0); // inside polygon, outside radius
        Coord nowhere = new Coord(5_000.0, 5_000.0);   // outside both

        // The fix: a connecting commute (rural origin + metropole-core dest) is now
        // eligible because each endpoint is inside one of the unioned regions.
        assertTrue(f.isTripEligible(ruralEnd, cityEnd),
                "rural end in radius + city end in polygon -> eligible (union)");
        assertTrue(f.isTripEligible(ruralEnd, ruralEnd), "both in radius -> eligible");
        assertTrue(f.isTripEligible(cityEnd, cityEnd), "both in polygon -> eligible");
        assertFalse(f.isTripEligible(ruralEnd, nowhere),
                "one endpoint outside both regions -> not eligible");
        assertFalse(f.isTripEligible(nowhere, cityEnd),
                "one endpoint outside both regions -> not eligible");
    }

    @Test
    void radiusOnly_pureRadiusBehaviourPreserved() {
        // No inclusion polygon -> both endpoints must be within the radius
        // (Paper-1 / Kelheim behaviour is unchanged).
        TripSpatialPreFilter f = TripSpatialPreFilter.forRadiusUnionPolygon(0.0, 0.0, 1.0, null);
        assertTrue(f.isTripEligible(new Coord(500.0, 500.0), new Coord(-500.0, -500.0)),
                "both in radius -> eligible");
        assertFalse(f.isTripEligible(new Coord(500.0, 500.0), new Coord(5_000.0, 5_000.0)),
                "one outside radius, no polygon -> dropped");
    }
}
