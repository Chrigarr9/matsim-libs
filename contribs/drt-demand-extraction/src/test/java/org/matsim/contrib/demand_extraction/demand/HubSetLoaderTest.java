package org.matsim.contrib.demand_extraction.demand;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link HubSetLoader}: reads {@code FeatureCollection} of {@code Point}
 * features with a {@code hub_id} property and returns a list of {@code Hub}
 * records (id + EPSG-native {@link Coord}).
 *
 * <p>Phase 4 Task 4.1 of the Paper-2 Extension 2 plan.
 */
public class HubSetLoaderTest {

    private static final String STUB_GEOJSON = """
            {
              "type": "FeatureCollection",
              "features": [
                { "type": "Feature", "geometry": {"type": "Point", "coordinates": [841234.5, 6520876.1]}, "properties": {"hub_id": "hub_0"} },
                { "type": "Feature", "geometry": {"type": "Point", "coordinates": [845000.0, 6521000.0]}, "properties": {"hub_id": "hub_1"} },
                { "type": "Feature", "geometry": {"type": "Point", "coordinates": [850000.0, 6522000.0]}, "properties": {"hub_id": "hub_2"} }
              ]
            }
            """;

    @Test
    void loads_three_hubs_with_ids_and_coords(@TempDir Path tmp) throws Exception {
        Path geoJsonPath = tmp.resolve("hubs.geojson");
        Files.writeString(geoJsonPath, STUB_GEOJSON);

        List<HubSetLoader.Hub> hubs = new HubSetLoader().load(geoJsonPath);

        assertEquals(3, hubs.size(), "expected 3 hubs from the stub FeatureCollection");

        assertEquals("hub_0", hubs.get(0).id());
        assertEquals(new Coord(841234.5, 6520876.1), hubs.get(0).coord());

        assertEquals("hub_1", hubs.get(1).id());
        assertEquals(new Coord(845000.0, 6521000.0), hubs.get(1).coord());

        assertEquals("hub_2", hubs.get(2).id());
        assertEquals(new Coord(850000.0, 6522000.0), hubs.get(2).coord());
    }

    @Test
    void missing_file_raises_ioexception(@TempDir Path tmp) {
        Path missing = tmp.resolve("does-not-exist.geojson");
        assertThrows(java.io.IOException.class, () -> new HubSetLoader().load(missing));
    }
}
