package org.matsim.contrib.demand_extraction.scenarios;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Resolves short focus names (e.g. {@code "loyettes-3communes"}, {@code "saint-vulbas"})
 * to EPSG:2154 (Lambert-93) coordinates loaded from a JSON registry.
 *
 * <p>Consumed by the paper-1 pruning pipeline via the {@code --trip-filter-focus} flag
 * on {@code RunLyonEqasimDemandExtraction}. The canonical registry lives at
 * {@code matsim_scenarios/eqasim-france/scenario-selection/data/foci.json}.
 *
 * <p>JSON format:
 * <pre>{@code
 * {
 *   "focus-name": { "x": 870540.4, "y": 6526302.7, "description": "..." }
 * }
 * }</pre>
 */
public final class FocusRegistry {

	public record Coords(double x, double y, String description) {}

	private final Map<String, Coords> foci;

	private FocusRegistry(Map<String, Coords> foci) {
		this.foci = foci;
	}

	public static FocusRegistry load(Path jsonPath) {
		try {
			JsonNode root = new ObjectMapper().readTree(Files.readString(jsonPath));
			Map<String, Coords> out = new HashMap<>();
			root.fields().forEachRemaining(e -> {
				JsonNode v = e.getValue();
				out.put(e.getKey(), new Coords(
						v.get("x").asDouble(),
						v.get("y").asDouble(),
						v.has("description") ? v.get("description").asText() : ""));
			});
			return new FocusRegistry(out);
		} catch (IOException ex) {
			throw new RuntimeException("Failed to load focus registry: " + jsonPath, ex);
		}
	}

	public Coords resolve(String name) {
		Coords c = foci.get(name);
		if (c == null) {
			throw new IllegalArgumentException(
					"Unknown focus '" + name + "'. Known: " + foci.keySet());
		}
		return c;
	}
}
