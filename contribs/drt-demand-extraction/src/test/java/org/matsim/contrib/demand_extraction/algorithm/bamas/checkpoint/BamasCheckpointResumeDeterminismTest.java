package org.matsim.contrib.demand_extraction.algorithm.bamas.checkpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.contrib.demand_extraction.algorithm.bamas.BamasEngine;
import org.matsim.contrib.demand_extraction.algorithm.bamas.stub.RideStores;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.network.ConnectionCacheJournal;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCacheTestFixture;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;
import org.matsim.contrib.demand_extraction.scoring.DemandExtractionScoringAdapter;
import org.matsim.contrib.demand_extraction.scoring.TripScoreRequest;
import org.matsim.contrib.demand_extraction.scoring.TripScoreResult;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;

/**
 * Plan A3 Task 4 + 5 — resume determinism on the streaming pair-stub D2D path.
 *
 * <p>Self-contained (no Lyon env): a short corridor network plus a handful of mutually-shareable
 * requests that pool up to degree 4 under a pass-through budget validator. The engine takes the
 * {@code pairStubPath} (stub mode default-on, stop-based off, maxDegree&gt;2), so checkpoints are
 * written and resume is exercised end-to-end.
 *
 * <h3>What this asserts</h3>
 * Resume reloads the per-degree {@code StubColumns} from disk byte-for-byte (ride STRUCTURE:
 * request sets, pickup ordering, FIFO/LIFO kind, degree) AND, with the Task-5 connection-cache
 * journal, repopulates the routing cache so the per-ride routed VALUES (distance, travel time,
 * network utility, start/end time) re-materialise bit-identically too. Both signatures are asserted
 * for the complete-checkpoint resume and the resume-into-loop case.
 *
 * <p>Note: on this trivial FreeSpeed corridor, point-to-point and SSSP routing happen to agree, so
 * value parity would also hold with a cold cache here; the journal's <i>necessity</i> shows only at
 * Lyon scale (the Task 7 gate). What this test proves is that the journal wiring does not corrupt or
 * drop values — a strong end-to-end check of the cache persist/restore path.
 */
@Tag("fast")
class BamasCheckpointResumeDeterminismTest {

	private static final double LINK_LEN = 1000.0;
	private static final double FREESPEED = 10.0;

	private Network network;
	private BudgetValidator validator;

	@BeforeEach
	void setUp() {
		network = NetworkUtils.createNetwork();
		NetworkFactory f = network.getFactory();
		Node[] n = new Node[7];
		for (int i = 0; i < 7; i++) {
			n[i] = f.createNode(Id.createNodeId("n" + i), new Coord(i * LINK_LEN, 0.0));
			network.addNode(n[i]);
		}
		for (int i = 0; i < 6; i++) {
			addLink(f, "link" + i + (i + 1), n[i], n[i + 1]);
		}
		validator = new PassThroughBudgetValidator();
	}

	private void addLink(NetworkFactory f, String id, Node from, Node to) {
		Link lnk = f.createLink(Id.createLinkId(id), from, to);
		lnk.setLength(LINK_LEN);
		lnk.setFreespeed(FREESPEED);
		lnk.setCapacity(1000.0);
		lnk.setNumberOfLanes(1.0);
		network.addLink(lnk);
	}

	/** A fresh cold cache mirroring the production routing path (every run gets its own). */
	private MatsimNetworkCache freshCache() {
		FreeSpeedTravelTime tt = new FreeSpeedTravelTime();
		OnlyTimeDependentTravelDisutility td = new OnlyTimeDependentTravelDisutility(tt);
		return MatsimNetworkCacheTestFixture.createWithRouting(network, tt, td, 900);
	}

	private ExMasConfigGroup config() {
		ExMasConfigGroup c = new ExMasConfigGroup();
		c.setAlgorithm(ExMasConfigGroup.Algorithm.BAMAS);
		c.setEnableStopBased(false);         // streaming pair-stub D2D path
		c.setHeuristicPruningEnabled(false);
		c.setPruningDistanceSavingsLogScale(-1.0);
		c.setPruningMode(ExMasConfigGroup.PruningMode.RATIO_THRESHOLD);
		c.setInterDegreeKeepFraction(1.0);   // no pruning — keep the full cascade deterministic
		c.clearPruningCoverageKByDegree();
		c.setCalcPredecessors(false);
		c.setCalcShapleyValues(false);
		c.setMaxPoolingDegree(4);
		c.setAlgorithmProcessCount(1);
		return c;
	}

	/** Five mutually-shareable requests along the corridor — pool to degree 4. */
	private List<DrtRequest> requests() {
		List<DrtRequest> reqs = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			reqs.add(DrtRequest.builder()
					.index(i)
					.personId(Id.createPersonId("pax_" + i))
					.groupId("g" + i)
					.tripIndex(0)
					.budget(50.0)
					.bestModeScore(-50.0)
					.bestMode("car")
					.originLinkId(Id.createLinkId("link01"))
					.destinationLinkId(Id.createLinkId("link56"))
					.originX(LINK_LEN).originY(0.0)
					.destinationX(6.0 * LINK_LEN).destinationY(0.0)
					.requestTime(i * 30.0)
					.earliestDeparture(-3600.0)
					.latestArrival(36000.0)
					.directTravelTime(5 * LINK_LEN / FREESPEED)
					.directDistance(5 * LINK_LEN)
					.maxDetourFactor(10.0)
					.maxWaitTime(36000.0)
					.build());
		}
		return reqs;
	}

	private BamasEngine engine(MatsimNetworkCache cache, ExMasConfigGroup cfg) {
		return new BamasEngine(cache, validator, cfg.getSearchHorizon(),
				cfg.getMaxPoolingDegree(), cfg);
	}

	/** Structural signature per ride: degree + pickup ordering + kind (cache-independent). */
	private static List<String> structure(List<Ride> rides) {
		List<String> sig = new ArrayList<>(rides.size());
		for (Ride r : rides) {
			sig.add(r.getDegree() + "|" + Arrays.toString(r.getRequestIndices()) + "|" + r.getKind());
		}
		sig.sort(null);
		return sig;
	}

	/**
	 * Full signature per ride: structure PLUS the routed values (distance, travel time, network
	 * utility, start/end time) as raw long bits, so any divergence in the journal-restored cache
	 * is caught bit-exactly.
	 */
	private static List<String> valueSignature(List<Ride> rides) {
		List<String> sig = new ArrayList<>(rides.size());
		for (Ride r : rides) {
			sig.add(r.getDegree() + "|" + Arrays.toString(r.getRequestIndices()) + "|" + r.getKind()
					+ "|d=" + Double.doubleToRawLongBits(r.getRideDistance())
					+ "|tt=" + Double.doubleToRawLongBits(r.getRideTravelTime())
					+ "|u=" + Double.doubleToRawLongBits(r.getRideNetworkUtility())
					+ "|s=" + Double.doubleToRawLongBits(r.getStartTime())
					+ "|e=" + Double.doubleToRawLongBits(r.getEndTime()));
		}
		sig.sort(null);
		return sig;
	}

	@Test
	void resumeFromCompleteCheckpointReproducesStructure(@TempDir Path dir) throws IOException {
		// Baseline: a fresh run with no checkpointing.
		List<Ride> golden = RideStores.toList(engine(freshCache(), config()).run(requests()));
		assertTrue(golden.stream().anyMatch(r -> r.getDegree() == 4),
				"scenario must pool to degree 4 to exercise the extension loop");

		// Fresh run WITH checkpointing — writes base + degree_3 + degree_4 + manifest + journal.
		ExMasConfigGroup writeCfg = config();
		writeCfg.setCheckpointDir(dir.toString());
		List<Ride> written = RideStores.toList(engine(freshCache(), writeCfg).run(requests()));
		assertEquals(valueSignature(golden), valueSignature(written),
				"checkpoint-writing run must match the no-checkpoint baseline (values included)");
		assertTrue(Files.exists(dir.resolve("degree_4.stubs.bin")));
		assertTrue(journalNonEmpty(dir.resolve("cache.journal")),
				"a checkpoint run must write a non-empty connection-cache journal");

		// Resume from the COMPLETE checkpoint (fresh cold cache): highest==maxDegree ⇒ the loop is
		// skipped, every layer is loaded, the journal repopulates the cache, and export reproduces
		// both the structure AND the routed values bit-for-bit.
		ExMasConfigGroup resumeCfg = config();
		resumeCfg.setCheckpointDir(dir.toString());
		List<Ride> resumed = RideStores.toList(engine(freshCache(), resumeCfg).run(requests()));
		assertEquals(valueSignature(golden), valueSignature(resumed),
				"resume from a complete checkpoint must reproduce rides AND routed values");
	}

	/** True if a cache.journal exists and holds more than the 8-byte header (i.e. has real data). */
	private static boolean journalNonEmpty(Path journal) throws IOException {
		// 8L == the fixed file header ConnectionCacheJournal writes once: MAGIC (int, 4B) + VERSION
		// (int, 4B). A size strictly larger means at least one tagged record was appended after it.
		return Files.exists(journal) && Files.size(journal) > 8L;
	}

	/**
	 * Drop the journal's final byte — which, on a cleanly-closed journal, is the last barrier's
	 * {@code TAG_BARRIER} marker (0x04): {@code appendBarrier} writes it last + fsyncs, and
	 * {@code close()} only flushes (no trailer). Asserting the byte first makes these truncation
	 * tests fail self-descriptively if the on-disk format ever grows a footer. Demoting the last
	 * barrier marker turns its preceding records into a torn tail ⇒ one fewer committed barrier.
	 */
	private static void dropLastBarrierByte(Path journal) throws IOException {
		long size = Files.size(journal);
		byte[] last = new byte[1];
		try (FileChannel ch = FileChannel.open(journal, StandardOpenOption.READ)) {
			ch.position(size - 1);
			ch.read(java.nio.ByteBuffer.wrap(last));
		}
		assertEquals(4, last[0],
				"journal's final byte must be the TAG_BARRIER (0x04) this truncation is meant to drop");
		try (FileChannel ch = FileChannel.open(journal, StandardOpenOption.WRITE)) {
			ch.truncate(size - 1);
		}
	}

	@Test
	void resumeIntoLoopReExtendsFinalDegree(@TempDir Path full, @TempDir Path rolled) throws IOException {
		List<Ride> golden = RideStores.toList(engine(freshCache(), config()).run(requests()));

		// Produce a full checkpoint.
		ExMasConfigGroup writeCfg = config();
		writeCfg.setCheckpointDir(full.toString());
		RideStores.toList(engine(freshCache(), writeCfg).run(requests()));

		// Roll it back to a degree-3 state: a crash AFTER the degree-3 barrier but before degree 4
		// finished. Copy base + degree_3, drop degree_4, rewrite the manifest to highestDegree=3.
		Files.copy(full.resolve("pair_stubs_preprune.bin"), rolled.resolve("pair_stubs_preprune.bin"));
		Files.copy(full.resolve("degree_3.stubs.bin"), rolled.resolve("degree_3.stubs.bin"));
		List<String> manifest = Files.readAllLines(full.resolve("manifest.txt"), StandardCharsets.UTF_8);
		List<String> rolledManifest = new ArrayList<>();
		for (String line : manifest) {
			if (line.startsWith("degree.4.")) {
				continue; // drop the degree-4 entries
			}
			rolledManifest.add(line.startsWith("highestDegree=") ? "highestDegree=3" : line);
		}
		Files.write(rolled.resolve("manifest.txt"), rolledManifest, StandardCharsets.UTF_8);
		assertFalse(Files.exists(rolled.resolve("degree_4.stubs.bin")));

		// The journal is NOT rolled back (a real crash would leave it at the degree-3 high-water
		// mark; here it still holds all entries). Either way bulk-load warms the cache, so the
		// re-extended degree 4 materialises to the same routed values.
		// TODO(Task 7): add a sub-case that TRUNCATES the journal to the degree-3 high-water mark
		// (drop the degree-4 BARRIER and its records) to exercise the true post-crash journal state
		// + the re-route-on-miss backstop, rather than resuming with a journal that still holds the
		// degree-4 entries. The kill-resume determinism gate (Lyon 1%, kill at d=4/d=6) covers this
		// end-to-end; this is the cheap unit-level mirror.
		Files.copy(full.resolve("cache.journal"), rolled.resolve("cache.journal"));

		// Resume from the rolled-back checkpoint: loads degree_3, rebuilds the degree-3 DegreeGraph
		// + pair graph deterministically, bulk-loads the journal, continues the loop at degree 3 →
		// re-extends degree 4 to the same structure AND routed values.
		ExMasConfigGroup resumeCfg = config();
		resumeCfg.setCheckpointDir(rolled.toString());
		List<Ride> resumed = RideStores.toList(engine(freshCache(), resumeCfg).run(requests()));
		assertEquals(valueSignature(golden), valueSignature(resumed),
				"resume into the loop must re-extend the final degree to the same rides AND values");
	}

	@Test
	void fingerprintMismatchRefusesResume(@TempDir Path dir) {
		ExMasConfigGroup writeCfg = config();
		writeCfg.setCheckpointDir(dir.toString());
		RideStores.toList(engine(freshCache(), writeCfg).run(requests()));

		// A different identity-affecting config (maxDetourFactor lives on requests, so change a
		// hashed config knob instead) must refuse to resume into this checkpoint dir.
		ExMasConfigGroup changed = config();
		changed.setCheckpointDir(dir.toString());
		changed.setPruningDistanceSavingsLogScale(0.25); // a stub/cache-identity-affecting knob
		assertThrows(IllegalStateException.class,
				() -> engine(freshCache(), changed).run(requests()));
	}

	// -------------------------------------------------------------------------
	// Plan A3 Task 6 — journal integrity gate tests.
	// -------------------------------------------------------------------------

	@Test
	void deletedJournalRefusesResume(@TempDir Path dir) throws IOException {
		// Write a full checkpoint.
		ExMasConfigGroup writeCfg = config();
		writeCfg.setCheckpointDir(dir.toString());
		RideStores.toList(engine(freshCache(), writeCfg).run(requests()));
		assertTrue(Files.exists(dir.resolve("cache.journal")));

		// Delete the journal — resume must refuse.
		Files.delete(dir.resolve("cache.journal"));
		ExMasConfigGroup resumeCfg = config();
		resumeCfg.setCheckpointDir(dir.toString());
		assertThrows(IllegalStateException.class,
				() -> RideStores.toList(engine(freshCache(), resumeCfg).run(requests())));
	}

	@Test
	void journalTruncatedBelowHighWaterRefusesResume(@TempDir Path dir) throws IOException {
		// Write a full degree-4 checkpoint (3 committed barriers: base + d3 + d4).
		ExMasConfigGroup writeCfg = config();
		writeCfg.setCheckpointDir(dir.toString());
		RideStores.toList(engine(freshCache(), writeCfg).run(requests()));

		// Drop the degree-4 barrier marker → only 2 committed barriers remain, but the manifest
		// still says highestDegree=4 ⇒ expected 3.
		Path journal = dir.resolve("cache.journal");
		assertEquals(3, ConnectionCacheJournal.read(journal).committedBarrierCount(),
				"precondition: a full degree-4 journal has base + d3 + d4 = 3 committed barriers");
		dropLastBarrierByte(journal);
		assertEquals(2, ConnectionCacheJournal.read(journal).committedBarrierCount(),
				"dropping the d4 barrier marker must leave exactly 2 committed barriers");

		// Resume must refuse with a message mentioning "barrier".
		ExMasConfigGroup resumeCfg = config();
		resumeCfg.setCheckpointDir(dir.toString());
		IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> RideStores.toList(engine(freshCache(), resumeCfg).run(requests())));
		assertTrue(ex.getMessage().toLowerCase().contains("barrier"),
				"refusal message should mention 'barrier', got: " + ex.getMessage());
	}

	@Test
	void journalTornTailBeyondHighWaterStillResumes(@TempDir Path dir) throws IOException {
		// Golden (no-checkpoint).
		List<Ride> golden = RideStores.toList(engine(freshCache(), config()).run(requests()));

		// Write a full degree-4 checkpoint.
		ExMasConfigGroup writeCfg = config();
		writeCfg.setCheckpointDir(dir.toString());
		RideStores.toList(engine(freshCache(), writeCfg).run(requests()));

		// Append a stray partial record after the last committed barrier to simulate a crash during
		// the next (unfinished) append: byte 2 = TAG_SEGMENT, then 4 bytes of a fromId. A full
		// SEGMENT needs 2 ints + 3 doubles (32 bytes after the tag), so 4 bytes is guaranteed
		// incomplete ⇒ read() parses the 3 committed barriers, then EOFs in this partial record and
		// discards it: committedBarrierCount stays 3 ≥ expected 3 ⇒ resume proceeds.
		Path journal = dir.resolve("cache.journal");
		Files.write(journal, new byte[]{2, 0, 0, 0, 1}, StandardOpenOption.APPEND);
		assertEquals(3, ConnectionCacheJournal.read(journal).committedBarrierCount(),
				"a torn tail beyond the last barrier must not change the committed barrier count");

		// Resume must succeed and produce bit-identical values.
		ExMasConfigGroup resumeCfg = config();
		resumeCfg.setCheckpointDir(dir.toString());
		List<Ride> resumed = RideStores.toList(engine(freshCache(), resumeCfg).run(requests()));
		assertEquals(valueSignature(golden), valueSignature(resumed),
				"resume with a torn tail beyond the last barrier must still produce identical rides");
	}

	@Test
	void journalTruncatedExactlyToHighWaterStillResumes(@TempDir Path full, @TempDir Path rolled)
			throws IOException {
		// Golden (no-checkpoint).
		List<Ride> golden = RideStores.toList(engine(freshCache(), config()).run(requests()));

		// Produce a full degree-4 checkpoint.
		ExMasConfigGroup writeCfg = config();
		writeCfg.setCheckpointDir(full.toString());
		RideStores.toList(engine(freshCache(), writeCfg).run(requests()));

		// Roll the manifest back to highestDegree=3 (degree 4 must be re-extended) AND drop the
		// degree-4 barrier marker from the journal — exactly the post-crash state: a journal whose
		// last durable barrier is the degree-3 high-water mark. Base + degree-3 = 2 committed
		// barriers == the rolled manifest's expected 2, so the gate ADMITS it and resume proceeds.
		rollBackToDegree3(full, rolled);
		dropLastBarrierByte(rolled.resolve("cache.journal"));

		// The truncation genuinely dropped one durable barrier (3 → 2): the manifest and the journal
		// now agree at the degree-3 high-water mark.
		assertEquals(3, ConnectionCacheJournal.read(full.resolve("cache.journal")).committedBarrierCount(),
				"a full degree-4 journal has base + d3 + d4 = 3 committed barriers");
		assertEquals(2, ConnectionCacheJournal.read(rolled.resolve("cache.journal")).committedBarrierCount(),
				"dropping the d4 barrier leaves exactly the rolled manifest's expected 2");

		// Resume must proceed (gate admits the exactly-truncated journal) and be bit-identical.
		ExMasConfigGroup resumeCfg = config();
		resumeCfg.setCheckpointDir(rolled.toString());
		List<Ride> resumed = RideStores.toList(engine(freshCache(), resumeCfg).run(requests()));
		assertEquals(valueSignature(golden), valueSignature(resumed),
				"resume from a journal truncated to exactly the completed-degree high-water mark must "
				+ "reproduce the golden bit-for-bit");

		// NOTE — what this does NOT prove: the scoped re-route backstop. On this FreeSpeed corridor
		// every request shares the same OD, so pair-gen's SSSP precompute already caches every segment
		// degree-4 extension needs; the d4 barrier carries no NEW segments and re-extension hits the
		// warm base cache (verified: dropping the d4 barrier above dropped 0 segments — barrier count
		// fell 3→2 but the loaded segment set is unchanged). So no point-to-point re-route is exercised
		// here. The backstop's SSSP-vs-point-to-point divergence handling is proven by the Task 7 Lyon
		// gate, where extension genuinely routes ODs outside the precomputed cones.
	}

	/**
	 * Stage a resume dir rolled back to highestDegree=3: copy the pre-prune pair universe, the
	 * degree-3 layer, the (degree-4-stripped) manifest, and the full cache journal from {@code src}.
	 */
	private static void rollBackToDegree3(Path src, Path dest) throws IOException {
		Files.copy(src.resolve("pair_stubs_preprune.bin"), dest.resolve("pair_stubs_preprune.bin"));
		Files.copy(src.resolve("degree_3.stubs.bin"), dest.resolve("degree_3.stubs.bin"));
		List<String> manifest = Files.readAllLines(src.resolve("manifest.txt"), StandardCharsets.UTF_8);
		List<String> rolled = new ArrayList<>();
		for (String line : manifest) {
			if (line.startsWith("degree.4.")) {
				continue; // drop the degree-4 entries
			}
			rolled.add(line.startsWith("highestDegree=") ? "highestDegree=3" : line);
		}
		Files.write(dest.resolve("manifest.txt"), rolled, StandardCharsets.UTF_8);
		Files.copy(src.resolve("cache.journal"), dest.resolve("cache.journal"));
	}

	// -------------------------------------------------------------------------
	// Pass-through BudgetValidator — accepts every ride (geometry-only feasibility).
	// -------------------------------------------------------------------------

	private static final class PassThroughBudgetValidator extends BudgetValidator {
		private PassThroughBudgetValidator() {
			super(new NoOpScoringAdapter(), person -> null, new ExMasConfigGroup(), dummyConfig());
		}

		@Override
		public Ride validateAndPopulateBudgets(Ride ride) {
			return ride.toBuilder().remainingBudgets(new double[ride.getDegree()]).build();
		}

		private static Config dummyConfig() {
			Config config = ConfigUtils.createConfig();
			config.addModule(new ExMasConfigGroup());
			return config;
		}

		@SuppressWarnings("unused")
		private static ScoringParametersForPerson noOp() {
			return person -> null;
		}
	}

	private static final class NoOpScoringAdapter implements DemandExtractionScoringAdapter {
		@Override
		public String getName() {
			return "noop";
		}

		@Override
		public TripScoreResult scoreTrip(TripScoreRequest request) {
			throw new UnsupportedOperationException("Not used in pass-through validator");
		}

		@Override
		public double getMarginalUtilityOfMoney(org.matsim.api.core.v01.population.Person person, double euclidDist_km) {
			return 1.0;
		}

		@Override
		public boolean supportsDistanceSpecificMoneyUtility() {
			return false;
		}

		@Override
		public boolean includesOpportunityCost() {
			return false;
		}
	}
}
