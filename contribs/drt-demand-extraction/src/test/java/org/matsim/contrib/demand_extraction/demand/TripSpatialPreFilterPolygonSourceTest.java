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
}
