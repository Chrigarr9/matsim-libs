package org.matsim.contrib.demand_extraction.demand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.matsim.api.core.v01.Coord;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Loads a hub set from a GeoJSON {@code FeatureCollection} of {@code Point}
 * features. Each feature is expected to expose a {@code hub_id} string
 * property and two-element {@code geometry.coordinates} array {@code [x, y]}
 * in the scenario CRS (EPSG:2154 / Lambert-93 for the Lyon scenario).
 *
 * <p>Paper-2 Extension 2 Phase 4: emitted by the Python hub-discovery stage
 * and consumed by {@link DrtRequestFactory} when virtual-trip expansion is
 * enabled.
 *
 * <p>Mirrors the {@link org.matsim.contrib.demand_extraction.scenarios.FocusRegistry}
 * Jackson-based loader convention already used elsewhere in this module.
 */
public final class HubSetLoader {

	/** A single hub: stable string id and a MATSim {@link Coord} in scenario CRS. */
	public record Hub(String id, Coord coord) {}

	/**
	 * Parse the given GeoJSON file into a list of {@link Hub} records, preserving
	 * feature order.
	 *
	 * @param geoJsonPath path to a {@code FeatureCollection} of {@code Point} features
	 * @return immutable list of hubs in feature order
	 * @throws IOException if the file cannot be read or has missing/malformed required fields
	 */
	public List<Hub> load(Path geoJsonPath) throws IOException {
		JsonNode root = new ObjectMapper().readTree(Files.readString(geoJsonPath));

		JsonNode features = root.get("features");
		if (features == null || !features.isArray()) {
			throw new IOException("GeoJSON missing 'features' array: " + geoJsonPath);
		}

		List<Hub> hubs = new ArrayList<>(features.size());
		for (int i = 0; i < features.size(); i++) {
			JsonNode feature = features.get(i);

			JsonNode props = feature.get("properties");
			if (props == null || props.get("hub_id") == null) {
				throw new IOException("Feature " + i + " missing 'properties.hub_id' in " + geoJsonPath);
			}
			String id = props.get("hub_id").asText();

			JsonNode geom = feature.get("geometry");
			if (geom == null) {
				throw new IOException("Feature " + i + " (" + id + ") missing 'geometry' in " + geoJsonPath);
			}
			JsonNode coords = geom.get("coordinates");
			if (coords == null || !coords.isArray() || coords.size() < 2) {
				throw new IOException("Feature " + i + " (" + id
						+ ") missing or malformed 'geometry.coordinates' (need [x,y]) in " + geoJsonPath);
			}
			double x = coords.get(0).asDouble();
			double y = coords.get(1).asDouble();

			hubs.add(new Hub(id, new Coord(x, y)));
		}
		return List.copyOf(hubs);
	}
}
