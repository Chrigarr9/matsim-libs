package org.matsim.contrib.demand_extraction.demand;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link RequestClassificationLoader}: reads CSV emitted by the
 * Python Phase-2 classifier (Task 2.1, commit {@code bf010d2}) keyed on
 * {@code (personId, tripIndex)} -> {@code requestTag} string.
 *
 * <p>Phase 2 Task 2.4 of the Paper-2 Extension 2 plan.
 */
public class RequestClassificationLoaderTest {

    /**
     * Header order is deliberately not the canonical one to verify the loader
     * is header-driven (not positional). Python (Task 2.3) may emit extra
     * columns alongside; the loader must ignore them and look up the three
     * required ones by name.
     */
    private static final String STUB_CSV = """
            tripIndex,personId,requestTag,extraColumn
            0,person_a,rural_intra,foo
            3,person_b,connecting,bar
            1,person_c,urban_intra,baz
            """;

    @Test
    void loads_three_rows_and_lookup_returns_tag(@TempDir Path tmp) throws Exception {
        Path csvPath = tmp.resolve("request_classifications.csv");
        Files.writeString(csvPath, STUB_CSV);

        RequestClassificationLoader loader = new RequestClassificationLoader(csvPath);

        assertEquals("rural_intra", loader.lookup("person_a", 0));
        assertEquals("connecting", loader.lookup("person_b", 3));
        assertEquals("urban_intra", loader.lookup("person_c", 1));
    }

    @Test
    void lookup_returns_null_for_unknown_key(@TempDir Path tmp) throws Exception {
        Path csvPath = tmp.resolve("request_classifications.csv");
        Files.writeString(csvPath, STUB_CSV);

        RequestClassificationLoader loader = new RequestClassificationLoader(csvPath);

        // Unknown person.
        assertNull(loader.lookup("person_unknown", 0));
        // Known person but different tripIndex.
        assertNull(loader.lookup("person_a", 99));
    }

    @Test
    void missing_required_column_raises_ioexception(@TempDir Path tmp) throws Exception {
        Path csvPath = tmp.resolve("bad.csv");
        Files.writeString(csvPath, "personId,tripIndex\nperson_a,0\n");

        assertThrows(java.io.IOException.class,
                () -> new RequestClassificationLoader(csvPath));
    }

    @Test
    void missing_file_raises_ioexception(@TempDir Path tmp) {
        Path missing = tmp.resolve("does-not-exist.csv");
        assertThrows(java.io.IOException.class,
                () -> new RequestClassificationLoader(missing));
    }
}
