package org.matsim.contrib.demand_extraction.io.lowmem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

/**
 * Unit tests for {@link PhaseOneDumpSubsetter}: write a superset dump, subset it to a
 * chosen index set, and assert the out-dump round-trips through {@link PhaseOneDumpReader}
 * with exactly the kept requests, their scoring-context scalars intact (proving CSV↔BIN
 * alignment survived the filter), the activity-type table preserved, and order preserved.
 */
class PhaseOneDumpSubsetterTest {

	private static final double WALK_SPEED = 1.4;
	private static final double MIN_WALK = 100.0;

	/** Build a 4-request superset dump (indices 10..13) with distinct scoring scalars. */
	private PhaseOneDumpLayout writeSupersetDump(Path dir) throws IOException {
		PhaseOneDumpLayout layout = new PhaseOneDumpLayout(dir);
		List<DrtRequest> originals = List.of(
				LowMemTestFixtures.buildRequest(10, "home", "work", 43200.0, 28800.0),
				LowMemTestFixtures.buildRequest(11, "work", "shop", 28800.0, 3600.0),
				LowMemTestFixtures.buildRequest(12, "shop", "leisure", 3600.0, 7200.0),
				LowMemTestFixtures.buildRequest(13, "leisure", "home", 7200.0, 43200.0));

		PhaseOneDumpWriter.EqasimScoringParams eqasim = new PhaseOneDumpWriter.EqasimScoringParams(
				1.5, -0.05, -0.10, -0.08, 0.7, 4.0);
		PhaseOneDumpWriter.Meta meta = new PhaseOneDumpWriter.Meta(
				"drt", WALK_SPEED, "LOG", MIN_WALK,
				"test-run-id", 1, 12345L, 67890L, eqasim);
		PhaseOneDumpWriter.write(layout, originals, meta);
		// PhaseOneDumpWriter writes only CSV/BIN/JSON; the Phase-1 runner separately drops
		// phase1_config.xml. Emit a stand-in so the subsetter's verbatim config copy is exercised.
		Files.writeString(layout.configXml(), "<config><!-- phase1 config snapshot --></config>\n");
		return layout;
	}

	private Map<Integer, DrtRequest> byIndex(List<DrtRequest> reqs) {
		Map<Integer, DrtRequest> m = new HashMap<>();
		for (DrtRequest r : reqs) m.put(r.index, r);
		return m;
	}

	@Test
	void subsetsToChosenIndicesWithAlignedScoringContexts(@TempDir Path tmp) throws IOException {
		Path in = tmp.resolve("in");
		Path out = tmp.resolve("out");
		PhaseOneDumpLayout inLayout = writeSupersetDump(in);

		PhaseOneDumpSubsetter.subsetDump(in, out, Set.of(11, 13));

		// Round-trips through the reader Phase 2 uses.
		PhaseOneDumpReader.DumpData loaded = PhaseOneDumpReader.read(new PhaseOneDumpLayout(out));
		List<DrtRequest> kept = loaded.requests();
		assertEquals(2, kept.size(), "subset must keep exactly 2 requests");

		Map<Integer, DrtRequest> keptByIdx = byIndex(kept);
		assertTrue(keptByIdx.containsKey(11));
		assertTrue(keptByIdx.containsKey(13));

		// Originals (full dump) to compare scoring scalars against.
		Map<Integer, DrtRequest> origByIdx = byIndex(PhaseOneDumpReader.read(inLayout).requests());

		// Load-bearing: kept rows' scoring scalars equal the ORIGINALS for those indices
		// (NOT shifted/swapped) — proves the CSV row and BIN row stayed aligned per index.
		for (int idx : new int[] {11, 13}) {
			DrtRequest k = keptByIdx.get(idx);
			DrtRequest o = origByIdx.get(idx);
			assertEquals(o.getScoringContext().originDuration(), k.getScoringContext().originDuration(), 1e-12);
			assertEquals(o.getScoringContext().destDuration(), k.getScoringContext().destDuration(), 1e-12);
			assertEquals(o.getScoringContext().originActivity().getType(),
					k.getScoringContext().originActivity().getType());
			assertEquals(o.getScoringContext().destActivity().getType(),
					k.getScoringContext().destActivity().getType());
			assertEquals(o.getScoringContext().scoringParams().marginalUtilityOfPerforming_s,
					k.getScoringContext().scoringParams().marginalUtilityOfPerforming_s, 1e-12);
		}

		// Activity-type table preserved in FULL (all 4 origin/dest types from the superset,
		// even those of dropped rows 10 and 12). The reader exposes the table via utilParams.
		var kCtx = keptByIdx.get(11).getScoringContext().scoringParams();
		for (String type : new String[] {"home", "work", "shop", "leisure"}) {
			assertNotNull(kCtx.utilParams.get(type),
					"full activity-type table must be preserved (missing: " + type + ")");
		}

		// Meta + config present and consistent.
		assertEquals(2, loaded.meta().numRequests(), "meta numRequests must reflect the subset");
		assertEquals("drt", loaded.meta().drtMode());
		assertTrue(Files.exists(new PhaseOneDumpLayout(out).configXml()), "config must be copied");
		assertEquals(2, loaded.scoringContextsVersion(), "bin version preserved (v2)");
	}

	@Test
	void preservesOriginalDumpOrderRegardlessOfSetIteration(@TempDir Path tmp) throws IOException {
		Path in = tmp.resolve("in");
		Path out = tmp.resolve("out");
		writeSupersetDump(in);

		// Keep {13, 11}; the surviving CSV/BIN rows must still appear in original dump order 11 then 13.
		PhaseOneDumpSubsetter.subsetDump(in, out, Set.of(13, 11));

		PhaseOneDumpLayout outLayout = new PhaseOneDumpLayout(out);

		// CSV order: first data row index == 11, second == 13.
		List<String> lines = Files.readAllLines(outLayout.requestsCsv());
		assertEquals(11, Integer.parseInt(lines.get(1).split(",", -1)[0].trim()));
		assertEquals(13, Integer.parseInt(lines.get(2).split(",", -1)[0].trim()));

		// BIN row order matches: first row index == 11, second == 13.
		try (ScoringContextsBinReader r = new ScoringContextsBinReader(outLayout.scoringContextsBin())) {
			ScoringContextsBinReader.Header h = r.readHeader();
			assertEquals(2, h.numRequests());
			assertEquals(11, r.readRow().requestIndex());
			assertEquals(13, r.readRow().requestIndex());
		}
	}

	@Test
	void unknownKeepIndexRaisesClearError(@TempDir Path tmp) throws IOException {
		Path in = tmp.resolve("in");
		Path out = tmp.resolve("out");
		writeSupersetDump(in);

		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> PhaseOneDumpSubsetter.subsetDump(in, out, Set.of(11, 99)));
		assertTrue(ex.getMessage().contains("99"),
				"error must name the missing index, got: " + ex.getMessage());
		// Nothing written on the error path.
		assertTrue(!Files.exists(new PhaseOneDumpLayout(out).requestsCsv()),
				"no out-dump CSV should be written when an index is missing");
	}
}
