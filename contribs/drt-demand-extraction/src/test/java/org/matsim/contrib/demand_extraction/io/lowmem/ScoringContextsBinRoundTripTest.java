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
				new ScoringContextsBinWriter.RequestRow(0, (byte) 0, (byte) 1, 7200.0, 3600.0, -0.001, -0.002, 0.0, 0.0),
				new ScoringContextsBinWriter.RequestRow(1, (byte) 1, (byte) 0, 5400.0, 1800.0, -0.001, -0.002, 0.0, 0.0),
				new ScoringContextsBinWriter.RequestRow(42, (byte) -1, (byte) -1, 0.0, 0.0, -0.001, -0.002, 0.0, 0.0));

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

	@Test
	void v2_roundTripPreservesMaxWalkAndMaxWait(@TempDir Path tmp) throws IOException {
		Path bin = tmp.resolve("scoring_contexts_v2.bin");

		List<ScoringContextsBinWriter.ActivityTypeRow> types = List.of(
				new ScoringContextsBinWriter.ActivityTypeRow("home", 43200.0, true));

		List<ScoringContextsBinWriter.RequestRow> rows = List.of(
				new ScoringContextsBinWriter.RequestRow(
						0, (byte) 0, (byte) 0,
						7200.0, 3600.0, -0.001, -0.002,
						/* maxWalkDistance */ 250.5, /* maxWaitTime */ 480.0),
				new ScoringContextsBinWriter.RequestRow(
						1, (byte) 0, (byte) 0,
						5400.0, 1800.0, -0.001, -0.002,
						/* maxWalkDistance */ 0.0, /* maxWaitTime */ 0.0));

		try (ScoringContextsBinWriter w = new ScoringContextsBinWriter(bin)) {
			w.writeHeader(rows.size(), types);
			for (ScoringContextsBinWriter.RequestRow r : rows) w.writeRow(r);
		}

		try (ScoringContextsBinReader r = new ScoringContextsBinReader(bin)) {
			ScoringContextsBinReader.Header h = r.readHeader();
			assertEquals(2, h.version());
			assertEquals(2, h.numRequests());

			ScoringContextsBinWriter.RequestRow row0 = r.readRow();
			assertEquals(250.5, row0.maxWalkDistance());
			assertEquals(480.0, row0.maxWaitTime());

			ScoringContextsBinWriter.RequestRow row1 = r.readRow();
			assertEquals(0.0, row1.maxWalkDistance());
			assertEquals(0.0, row1.maxWaitTime());
		}
	}

	@Test
	void v1_readsBackAsZeroForNewFields(@TempDir Path tmp) throws IOException {
		// Hand-write a v1 dump: header(magic, version=1, numRequests=1, numTypes=0) + 35-byte row.
		Path bin = tmp.resolve("scoring_contexts_v1.bin");
		try (var out = new java.io.DataOutputStream(java.nio.file.Files.newOutputStream(bin))) {
			out.writeInt(PhaseOneDumpLayout.SCORING_CONTEXTS_MAGIC);
			out.writeInt(1);                       // version 1 (legacy)
			out.writeInt(1);                       // numRequests
			out.writeInt(0);                       // numActivityTypes
			out.writeInt(42);                      // requestIndex
			out.writeByte((byte) -1);              // origin actType idx (synthetic)
			out.writeByte((byte) -1);              // dest actType idx
			out.writeDouble(0.0);                  // originDuration
			out.writeDouble(0.0);                  // destDuration
			out.writeDouble(-0.001);               // marg util performing
			out.writeDouble(-0.002);               // marg util waiting pt
		}

		try (ScoringContextsBinReader r = new ScoringContextsBinReader(bin)) {
			ScoringContextsBinReader.Header h = r.readHeader();
			assertEquals(1, h.version());

			ScoringContextsBinWriter.RequestRow row = r.readRow();
			assertEquals(42, row.requestIndex());
			assertEquals(0.0, row.maxWalkDistance(), "v1 dumps must surface maxWalkDistance=0");
			assertEquals(0.0, row.maxWaitTime(), "v1 dumps must surface maxWaitTime=0");
		}
	}
}
