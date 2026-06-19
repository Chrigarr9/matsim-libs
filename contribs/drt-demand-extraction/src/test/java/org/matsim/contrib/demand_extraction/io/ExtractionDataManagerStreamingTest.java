package org.matsim.contrib.demand_extraction.io;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.contrib.demand_extraction.algorithm.bamas.ride.MaterializedRideStore;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.io.ExMasCsvWriter;
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

    /**
     * The parallel writer must be byte-identical to the single-threaded one: same rows in the same
     * index order, same encoding. Build a store large enough to span multiple worker chunks and
     * diff the two outputs byte-for-byte.
     */
    @Test
    void parallelWriteIsByteIdenticalToSerial(@TempDir Path dir) throws Exception {
        List<Ride> rides = new ArrayList<>();
        for (int i = 0; i < 23; i++) {
            // alternate degree-1 and degree-2 rows so the output exercises both branches
            rides.add(i % 2 == 0 ? RideFixtures.ride(i, 100 + i) : RideFixtures.ride(i, 100 + i, 200 + i));
        }

        Path serial = dir.resolve("serial.csv");
        Path parallel = dir.resolve("parallel.csv");
        ExMasCsvWriter.writeRidesStreaming(serial.toString(),
                new MaterializedRideStore(rides), UnaryOperator.identity());
        ExMasCsvWriter.writeRidesStreamingParallel(parallel.toString(),
                new MaterializedRideStore(rides), UnaryOperator.identity(), 4);

        assertEquals(Files.readString(serial), Files.readString(parallel),
                "parallel output must equal single-threaded output byte-for-byte");
        // shard temp files must be cleaned up (close the stream so Windows @TempDir cleanup succeeds)
        try (var entries = Files.list(dir)) {
            assertEquals(2, entries.count(), "only serial.csv + parallel.csv remain (shards deleted)");
        }
    }
}
