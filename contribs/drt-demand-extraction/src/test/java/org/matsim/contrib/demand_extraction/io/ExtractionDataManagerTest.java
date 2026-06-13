package org.matsim.contrib.demand_extraction.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.contrib.demand_extraction.algorithm.bamas.ride.MaterializedRideStore;
import org.matsim.contrib.demand_extraction.algorithm.domain.HyperPooledRide;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.io.lowmem.PhaseOneDumpLayout;

/**
 * Unit tests for {@link ExtractionDataManager}, the single owner of the extraction
 * output-file wiring extracted from {@code DemandExtractionListener} and
 * {@code RunDemandExtractionPhase2} (BAMAS cleanup Task 5, Commit 1).
 *
 * <p>The Kelheim byte-golden ({@code KelheimHyperPoolStubParityTest}) and the Phase-2
 * pre/post full-dir diff are the behavioural gates that the relocation is byte-identical;
 * these tests pin the contract surface — the {@code <demandDir>/<runId>.<name>.csv}
 * filename convention, faithful delegation to the static writers, and the two guards
 * (predecessors-off and empty-hyperpool) that previously lived inline at the call sites.
 */
class ExtractionDataManagerTest {

	private static ExMasConfigGroup cfg() {
		return new ExMasConfigGroup();
	}

	@Test
	void forOutputDirCreatesDemandDirAndDerivesCanonicalPaths(@TempDir Path out) throws IOException {
		ExtractionDataManager dm = ExtractionDataManager.forOutputDir(out, "run-x", cfg());
		Path demand = out.resolve("drt_demand");
		assertTrue(Files.isDirectory(demand), "drt_demand directory is created");
		assertEquals(demand, dm.demandDir());
		assertEquals(demand.resolve("run-x.exmas_rides.csv"), dm.path("exmas_rides.csv"));
		assertEquals(demand.resolve("run-x.connection_cache.csv"), dm.path("connection_cache.csv"));
	}

	@Test
	void writeRidesWritesCanonicalSlotByteIdenticalToDirectWriter(@TempDir Path out) throws IOException {
		ExtractionDataManager dm = ExtractionDataManager.forOutputDir(out, "run-x", cfg());
		Path written = dm.writeRides(List.of());
		assertEquals(out.resolve("drt_demand").resolve("run-x.exmas_rides.csv"), written);
		assertTrue(Files.exists(written));

		// The manager must delegate verbatim: same bytes as calling the writer directly.
		Path direct = out.resolve("direct.csv");
		ExMasCsvWriter.writeRides(direct.toString(), new MaterializedRideStore(List.of()));
		assertArrayEquals(Files.readAllBytes(direct), Files.readAllBytes(written),
				"manager.writeRides must be byte-identical to ExMasCsvWriter.writeRides");
	}

	@Test
	void publishCanonicalRequestsCopiesDumpVerbatim(@TempDir Path tmp) throws IOException {
		Path phase1Dir = tmp.resolve("phase1_dump");
		Files.createDirectories(phase1Dir);
		Path src = phase1Dir.resolve(PhaseOneDumpLayout.REQUESTS_CSV);
		Files.writeString(src, "index,directDistance\n0,1234.5\n");

		ExtractionDataManager dm = ExtractionDataManager.forOutputDir(tmp.resolve("out"), "run-x", cfg());
		Path dst = dm.publishCanonicalRequests(phase1Dir);

		assertEquals(dm.path("drt_requests.csv"), dst);
		assertTrue(Files.exists(dst));
		assertEquals(Files.readString(src), Files.readString(dst), "published bytes equal the dump");
	}

	@Test
	void publishCanonicalRequestsThrowsWhenDumpMissing(@TempDir Path tmp) throws IOException {
		ExtractionDataManager dm = ExtractionDataManager.forOutputDir(tmp.resolve("out"), "run-x", cfg());
		assertThrows(IOException.class, () -> dm.publishCanonicalRequests(tmp.resolve("absent_dump")));
	}

	@Test
	void writeConnectionCacheIsNoOpWhenPredecessorsOff(@TempDir Path out) throws IOException {
		ExMasConfigGroup c = cfg();
		c.setCalcPredecessors(false);
		ExtractionDataManager dm = ExtractionDataManager.forOutputDir(out, "run-x", c);
		// Predecessors off ⇒ returns null before touching the (here null) cache/window args.
		Path result = dm.writeConnectionCache(List.of(), null, null);
		assertNull(result, "no connection_cache file when isCalcPredecessors() is false");
		assertFalse(Files.exists(dm.path("connection_cache.csv")));
	}

	@Test
	void writeHyperPooledRidesIsNoOpWhenEmpty(@TempDir Path out) throws IOException {
		ExtractionDataManager dm = ExtractionDataManager.forOutputDir(out, "run-x", cfg());
		Path result = dm.writeHyperPooledRides(List.<HyperPooledRide>of());
		assertNull(result, "no hyperpool_rides file when there are no hyper-pooled rides");
		assertFalse(Files.exists(dm.path("hyperpool_rides.csv")));
	}
}
