package org.matsim.contrib.demand_extraction.io;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.contrib.demand_extraction.algorithm.bamas.ride.MaterializedRideStore;
import org.matsim.contrib.demand_extraction.algorithm.bamas.ride.RideStore;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.testutil.RideFixtures;

/**
 * The streaming {@code RideStore -> CSV} writer must be byte-identical to the batch writer for a
 * store that emits rows in {@link Ride#getIndex()} order.
 */
class ExMasCsvWriterStreamingTest {

    @Test
    void streamingMatchesMaterializedBytes(@TempDir Path dir) throws Exception {
        List<Ride> rides = RideFixtures.singleAndPair(); // indices 0,1 already in order
        RideStore store = new MaterializedRideStore(rides);

        Path batch = dir.resolve("batch.csv");
        ExMasCsvWriter.writeRides(batch.toString(), rides);

        Path streamed = dir.resolve("streamed.csv");
        ExMasCsvWriter.writeRidesStreaming(streamed.toString(), store, UnaryOperator.identity());

        assertEquals(Files.readString(batch), Files.readString(streamed),
                "streaming write must be byte-identical to the batch write for an in-order store");
    }
}
