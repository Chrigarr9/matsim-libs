package org.matsim.contrib.demand_extraction.scenarios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class FocusRegistryTest {

    // Resolves from the Maven module root: matsim-libs/contribs/drt-demand-extraction/
    // -> ../../../ = Dissertation repo root.
    private static final Path REGISTRY =
            Path.of("../../../matsim_scenarios/eqasim-france/scenario-selection/data/foci.json");

    @Test
    void resolvesKnownFocus() {
        FocusRegistry.Coords c = FocusRegistry.load(REGISTRY).resolve("loyettes-3communes");
        assertEquals(870540.4, c.x(), 1e-6);
        assertEquals(6526302.7, c.y(), 1e-6);
    }

    @Test
    void throwsForUnknownFocus() {
        FocusRegistry reg = FocusRegistry.load(REGISTRY);
        assertThrows(IllegalArgumentException.class, () -> reg.resolve("does-not-exist"));
    }
}
