package org.matsim.contrib.demand_extraction.io;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.contrib.demand_extraction.algorithm.bamas.ride.MaterializedRideStore;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.testutil.RideFixtures;

class ExtractionDataManagerStreamingTest {

    @Test
    void writesExmasRidesCsvByStreaming(@TempDir Path dir) throws Exception {
        ExtractionDataManager mgr = ExtractionDataManager.forOutputDir(dir, "run42", new ExMasConfigGroup());
        Path out = mgr.writeRidesStreaming(
                new MaterializedRideStore(RideFixtures.singleAndPair()), UnaryOperator.identity());
        assertEquals(dir.resolve("drt_demand").resolve("run42.exmas_rides.csv"), out);
        assertEquals(3, Files.readString(out).lines().count(), "header + 2 rows");
    }
}
