package org.matsim.contrib.demand_extraction.demand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads request classifications emitted by the Python Phase-2 classifier
 * (Task 2.1, commit {@code bf010d2}). Each CSV row maps a
 * {@code (personId, tripIndex)} pair to a {@code requestTag} drawn from
 * {@code {rural_intra, urban_intra, connecting, external}}.
 *
 * <p>The CSV is header-driven: columns may appear in any order and additional
 * columns are tolerated. The three required columns are {@code personId},
 * {@code tripIndex}, and {@code requestTag}; a missing column raises
 * {@link IOException} on construction.
 *
 * <p>{@code tripIndex} is the trip index within the person's selected plan,
 * matching {@link org.matsim.core.router.TripStructureUtils#getTrips} ordering
 * — the same iteration index that {@link DrtRequestFactory#buildRequests}
 * uses when constructing {@link DrtRequest} instances.
 *
 * <p>Paper-2 Extension 2 Phase 2 Task 2.4.
 */
public final class RequestClassificationLoader {

    /** A single classification row: stable string id, plan-trip index, and tag. */
    public record Classification(String personId, int tripIndex, String requestTag) {}

    private record RequestKey(String personId, int tripIndex) {}

    private final Map<RequestKey, String> tagByKey;

    /**
     * Parse the given CSV into an in-memory lookup table.
     *
     * @param csvPath path to a CSV file with a header row containing at least
     *                the columns {@code personId}, {@code tripIndex},
     *                {@code requestTag}
     * @throws IOException if the file cannot be read, the header is missing
     *                     any required column, or any row's {@code tripIndex}
     *                     fails to parse as an integer
     */
    public RequestClassificationLoader(Path csvPath) throws IOException {
        List<String> lines = Files.readAllLines(csvPath);
        if (lines.isEmpty()) {
            throw new IOException("Classification CSV is empty: " + csvPath);
        }

        String[] header = splitCsv(lines.get(0));
        int personCol = indexOf(header, "personId");
        int tripCol = indexOf(header, "tripIndex");
        int tagCol = indexOf(header, "requestTag");
        if (personCol < 0 || tripCol < 0 || tagCol < 0) {
            throw new IOException(
                    "Classification CSV missing required column(s) (personId, tripIndex, requestTag) in header: "
                            + lines.get(0) + " (file: " + csvPath + ")");
        }

        Map<RequestKey, String> map = new HashMap<>(Math.max(16, lines.size()));
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            String[] fields = splitCsv(line);
            if (fields.length <= Math.max(personCol, Math.max(tripCol, tagCol))) {
                throw new IOException("Classification CSV row " + i + " has too few columns: "
                        + line + " (file: " + csvPath + ")");
            }
            String personId = fields[personCol];
            int tripIndex;
            try {
                tripIndex = Integer.parseInt(fields[tripCol].trim());
            } catch (NumberFormatException e) {
                throw new IOException("Classification CSV row " + i + " has non-integer tripIndex '"
                        + fields[tripCol] + "' (file: " + csvPath + ")", e);
            }
            String tag = fields[tagCol];
            map.put(new RequestKey(personId, tripIndex), tag);
        }
        this.tagByKey = Map.copyOf(map);
    }

    /**
     * Look up the classification tag for a single {@code (personId, tripIndex)}
     * pair.
     *
     * @return the tag string, or {@code null} if this pair was not present in
     *         the CSV (the caller decides the default — typically leaves
     *         {@link DrtRequest#requestTag} null)
     */
    public String lookup(String personId, int tripIndex) {
        return tagByKey.get(new RequestKey(personId, tripIndex));
    }

    /** Number of classifications loaded. Useful for logging. */
    public int size() {
        return tagByKey.size();
    }

    /**
     * Stdlib split-on-comma. The Python emitter is the only producer and it
     * does not quote fields (request tags + person IDs are simple tokens),
     * so a full CSV parser would be over-kill here.
     */
    private static String[] splitCsv(String line) {
        return line.split(",", -1);
    }

    private static int indexOf(String[] header, String name) {
        for (int i = 0; i < header.length; i++) {
            if (name.equals(header[i].trim())) {
                return i;
            }
        }
        return -1;
    }
}
