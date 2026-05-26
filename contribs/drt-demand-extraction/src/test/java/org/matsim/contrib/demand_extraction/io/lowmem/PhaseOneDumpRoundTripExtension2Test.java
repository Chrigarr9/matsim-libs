package org.matsim.contrib.demand_extraction.io.lowmem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

/**
 * Phase 1 Task 1.5 — round-trip the two Extension-2 columns ({@code requestTag},
 * {@code hubId}) across the low-memory two-phase dump.
 *
 * <p>The writer side is delegation-only: {@link PhaseOneDumpWriter} calls
 * {@link org.matsim.contrib.demand_extraction.io.ExMasCsvWriter#writeRequests(String, java.util.List)}
 * which already emits the two columns (Task 1.2). This test exercises the reader
 * side: it must parse the columns and populate them on the reconstructed
 * {@link DrtRequest}s via the Builder.
 *
 * <p>Also verifies backward-compatibility: an old dump CSV without the two columns
 * must still read cleanly and surface {@code null} on both fields.
 */
class PhaseOneDumpRoundTripExtension2Test {

	private static final double WALK_SPEED = 1.4;
	private static final double MIN_WALK = 100.0;

	@Test
	void roundTripsRequestTagAndHubId(@TempDir Path tmp) throws IOException {
		PhaseOneDumpLayout layout = new PhaseOneDumpLayout(tmp);

		// One request per Extension-2 tag value. Only the "connecting" virtual trip
		// carries a non-null hubId; the others have hubId == null.
		List<DrtRequest> originals = List.of(
				LowMemTestFixtures.buildRequest(0, "home", "work", 43200.0, 28800.0,
						"rural_intra", null),
				LowMemTestFixtures.buildRequest(1, "home", "work", 43200.0, 28800.0,
						"urban_intra", null),
				LowMemTestFixtures.buildRequest(2, "home", "work", 43200.0, 28800.0,
						"connecting", "hub_03"),
				LowMemTestFixtures.buildRequest(3, "home", "work", 43200.0, 28800.0,
						"external", null));

		PhaseOneDumpWriter.Meta meta = new PhaseOneDumpWriter.Meta(
				"drt", WALK_SPEED, "LOG", MIN_WALK,
				"test-run-id", 1, 0L, 0L, null);

		PhaseOneDumpWriter.write(layout, originals, meta);

		PhaseOneDumpReader.DumpData loaded = PhaseOneDumpReader.read(layout);
		assertEquals(4, loaded.requests().size());

		Map<Integer, DrtRequest> reloadedByIdx = loaded.requests().stream()
				.collect(Collectors.toMap(r -> r.index, r -> r));

		for (DrtRequest original : originals) {
			DrtRequest reloaded = reloadedByIdx.get(original.index);
			assertNotNull(reloaded, "reloaded request missing for idx " + original.index);
			assertEquals(original.requestTag, reloaded.requestTag,
					"requestTag mismatch for idx " + original.index);
			assertEquals(original.hubId, reloaded.hubId,
					"hubId mismatch for idx " + original.index);
		}
	}

	@Test
	void backwardCompatibleReadOfOldDumpWithoutNewColumns(@TempDir Path tmp) throws IOException {
		// First, perform a real round-trip dump so the BIN + JSON + the other CSV
		// columns are consistent, then rewrite the CSV stripping the two trailing
		// Extension-2 columns to simulate a legacy dump.
		PhaseOneDumpLayout layout = new PhaseOneDumpLayout(tmp);

		List<DrtRequest> originals = List.of(
				LowMemTestFixtures.buildRequest(0, "home", "work", 43200.0, 28800.0),
				LowMemTestFixtures.buildRequest(1, "work", "home", 28800.0, 43200.0));

		PhaseOneDumpWriter.Meta meta = new PhaseOneDumpWriter.Meta(
				"drt", WALK_SPEED, "LOG", MIN_WALK,
				"test-run-id", 1, 0L, 0L, null);

		PhaseOneDumpWriter.write(layout, originals, meta);

		// Strip the two trailing columns ("requestTag,hubId") from every line.
		Path csv = layout.requestsCsv();
		List<String> lines = Files.readAllLines(csv);
		Files.write(csv, lines.stream().map(line -> {
			int last = line.lastIndexOf(',');
			int secondLast = line.lastIndexOf(',', last - 1);
			return line.substring(0, secondLast);
		}).collect(Collectors.toList()));

		PhaseOneDumpReader.DumpData loaded = PhaseOneDumpReader.read(layout);
		assertEquals(2, loaded.requests().size());
		for (DrtRequest reloaded : loaded.requests()) {
			assertNull(reloaded.requestTag,
					"legacy dump without requestTag column must surface null (idx " + reloaded.index + ")");
			assertNull(reloaded.hubId,
					"legacy dump without hubId column must surface null (idx " + reloaded.index + ")");
		}
	}
}
