package org.matsim.contrib.demand_extraction.io.lowmem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScoringContextsBinRoundTripTest {

	@Test
	void roundTrip(@TempDir Path tmp) throws IOException {
		Path bin = tmp.resolve("scoring_contexts.bin");

		List<ScoringContextsBinWriter.ActivityTypeRow> types = List.of(
				new ScoringContextsBinWriter.ActivityTypeRow("home", 43200.0, true),
				new ScoringContextsBinWriter.ActivityTypeRow("work", 28800.0, true));

		List<ScoringContextsBinWriter.RequestRow> rows = List.of(
				new ScoringContextsBinWriter.RequestRow(0, (byte) 0, (byte) 1, 7200.0, 3600.0, -0.001, -0.002),
				new ScoringContextsBinWriter.RequestRow(1, (byte) 1, (byte) 0, 5400.0, 1800.0, -0.001, -0.002),
				new ScoringContextsBinWriter.RequestRow(42, (byte) -1, (byte) -1, 0.0, 0.0, -0.001, -0.002));

		try (ScoringContextsBinWriter w = new ScoringContextsBinWriter(bin)) {
			w.writeHeader(rows.size(), types);
			for (ScoringContextsBinWriter.RequestRow r : rows) w.writeRow(r);
		}

		try (ScoringContextsBinReader r = new ScoringContextsBinReader(bin)) {
			ScoringContextsBinReader.Header h = r.readHeader();
			assertEquals(3, h.numRequests());
			assertEquals(2, h.activityTypes().size());
			assertEquals("home", h.activityTypes().get(0).type());
			assertEquals(43200.0, h.activityTypes().get(0).typicalDuration());
			assertTrue(h.activityTypes().get(0).scoreAtAll());

			ScoringContextsBinWriter.RequestRow row0 = r.readRow();
			assertEquals(0, row0.requestIndex());
			assertEquals((byte) 0, row0.originActivityTypeIdx());
			assertEquals((byte) 1, row0.destActivityTypeIdx());
			assertEquals(7200.0, row0.originDuration());
			assertEquals(3600.0, row0.destDuration());
			assertEquals(-0.001, row0.marginalUtilityOfPerforming_s());
			assertEquals(-0.002, row0.marginalUtilityOfWaitingPt_s());

			ScoringContextsBinWriter.RequestRow row1 = r.readRow();
			assertEquals(1, row1.requestIndex());
			assertEquals((byte) 1, row1.originActivityTypeIdx());

			ScoringContextsBinWriter.RequestRow row2 = r.readRow();
			assertEquals(42, row2.requestIndex());
			assertEquals((byte) -1, row2.originActivityTypeIdx());
			assertEquals((byte) -1, row2.destActivityTypeIdx());
		}
	}

	@Test
	void rejectsBadMagic(@TempDir Path tmp) throws IOException {
		Path bad = tmp.resolve("bad.bin");
		Files.write(bad, new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 });
		try (ScoringContextsBinReader r = new ScoringContextsBinReader(bad)) {
			assertThrows(IOException.class, r::readHeader);
		}
	}
}
