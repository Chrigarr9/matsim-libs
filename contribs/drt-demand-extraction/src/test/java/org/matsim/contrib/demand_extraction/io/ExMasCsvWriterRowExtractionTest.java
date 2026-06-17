package org.matsim.contrib.demand_extraction.io;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.testutil.RideFixtures;

/**
 * Characterises {@link ExMasCsvWriter#writeRides(String, List)} output BEFORE the
 * {@code writeRideRow} extraction (Task 1) so the refactor is proven byte-neutral.
 */
class ExMasCsvWriterRowExtractionTest {

    @Test
    void writeRidesBytesUnchangedAfterExtraction(@TempDir Path dir) throws Exception {
        List<Ride> rides = RideFixtures.singleAndPair(); // indices 0 and 1
        Path out = dir.resolve("rides.csv");
        ExMasCsvWriter.writeRides(out.toString(), rides);
        String csv = Files.readString(out);
        assertEquals(3, csv.lines().count(), "header + 2 rows");
        assertEquals(1, csv.lines().filter(l -> l.startsWith("0,")).count());
        assertEquals(1, csv.lines().filter(l -> l.startsWith("1,")).count());
    }
}
