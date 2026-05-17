package org.matsim.contrib.demand_extraction.io.lowmem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

class PhaseOneDumpWriterTest {

	@Test
	void writesAllThreeArtifacts(@TempDir Path tmp) throws IOException {
		PhaseOneDumpLayout layout = new PhaseOneDumpLayout(tmp);

		// Two requests with realistic scoring contexts (synthetic minimal Config).
		List<DrtRequest> requests = List.of(
				LowMemTestFixtures.buildRequest(0, "home", "work", 43200.0, 28800.0),
				LowMemTestFixtures.buildRequest(1, "work", "home", 28800.0, 43200.0));

		PhaseOneDumpWriter.Meta meta = new PhaseOneDumpWriter.Meta(
				"drt", 1.4, "LOG", 100.0,
				"test-run-id", 1, 12345L, 67890L, null);

		PhaseOneDumpWriter.write(layout, requests, meta);

		// All three files exist
		assertTrue(Files.exists(layout.requestsCsv()), "requests csv should exist");
		assertTrue(Files.exists(layout.scoringContextsBin()), "scoring contexts bin should exist");
		assertTrue(Files.exists(layout.metaJson()), "meta json should exist");

		// CSV: header + 2 rows = 3 lines
		List<String> csvLines = Files.readAllLines(layout.requestsCsv());
		assertEquals(3, csvLines.size(), "csv should have header + 2 rows");
		assertTrue(csvLines.get(0).contains("originLinkCoordFromX"), "csv header should expose link-coord columns");

		// BIN round-trip via reader
		try (ScoringContextsBinReader r = new ScoringContextsBinReader(layout.scoringContextsBin())) {
			ScoringContextsBinReader.Header h = r.readHeader();
			assertEquals(2, h.numRequests());
			assertEquals(2, h.activityTypes().size()); // "home" and "work"
			ScoringContextsBinWriter.RequestRow row0 = r.readRow();
			assertEquals(0, row0.requestIndex());
			ScoringContextsBinWriter.RequestRow row1 = r.readRow();
			assertEquals(1, row1.requestIndex());
		}

		// JSON: has expected top-level keys
		String json = Files.readString(layout.metaJson());
		assertTrue(json.contains("\"drtMode\""), "json should contain drtMode key");
		assertTrue(json.contains("\"walkSpeed\""), "json should contain walkSpeed key");
		assertTrue(json.contains("\"opportunityCostModel\""), "json should contain opportunityCostModel key");
		assertTrue(json.contains("\"numRequests\""), "json should contain numRequests key");
		assertTrue(json.contains("\"runId\""), "json should contain runId key");
		assertTrue(json.contains("\"drt\""), "json should contain the drtMode value");
		assertTrue(json.contains("test-run-id"), "json should contain the runId value");
	}
}
