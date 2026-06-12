package org.matsim.contrib.demand_extraction.algorithm.bamas.checkpoint;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns;
import org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumnsIO;

/**
 * Checkpoint writer behaviour (Plan A3 Task 3): files land, are round-trippable, and the
 * manifest tracks the highest completed degree + per-degree counts. (Resume reading is Task 4.)
 */
class CheckpointManagerTest {

	@Test
	void writesBaseAndDegreesWithManifest(@TempDir Path dir) throws IOException {
		CheckpointManager mgr = new CheckpointManager(dir, "fp-abc");
		mgr.init();

		// Base = pre-prune pair universe (degree 2, with positions).
		StubColumns pairs = new StubColumns(2);
		pairs.addRow(new int[] {1, 2}, 0x1L, 0x2L, 100, 50, (byte) 0, new int[] {0, 1});
		pairs.addRow(new int[] {3, 4}, 0x3L, 0x4L, 110, 55, (byte) 1, new int[] {2, 3});
		mgr.writeBase(pairs);

		// Two extension degrees.
		StubColumns d3 = new StubColumns(3);
		d3.addRow(new int[] {1, 2, 3}, 0x123L, 0x321L, 200, 90, (byte) 0);
		mgr.writeDegree(3, d3, 7);

		StubColumns d4 = new StubColumns(4);
		d4.addRow(new int[] {1, 2, 3, 4}, 0x1234L, 0x4321L, 300, 120, (byte) 1);
		d4.addRow(new int[] {2, 3, 4, 5}, 0x2345L, 0x5432L, 310, 125, (byte) 0);
		mgr.writeDegree(4, d4, 11);

		// Files exist (no leftover .tmp).
		assertTrue(Files.exists(dir.resolve("pair_stubs_preprune.bin")));
		assertTrue(Files.exists(dir.resolve("degree_3.stubs.bin")));
		assertTrue(Files.exists(dir.resolve("degree_4.stubs.bin")));
		assertTrue(Files.exists(dir.resolve("manifest.txt")));
		try (var s = Files.list(dir)) {
			assertTrue(s.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")),
					"no orphaned .tmp files");
		}

		// Stub files round-trip via StubColumnsIO (incl. positions on the pair layer).
		StubColumns pairsBack = read(dir.resolve("pair_stubs_preprune.bin"));
		assertEquals(2, pairsBack.degree());
		assertEquals(2, pairsBack.size());
		assertArrayEquals(new int[] {2, 3}, pairsBack.positionIndices(1));

		StubColumns d4Back = read(dir.resolve("degree_4.stubs.bin"));
		assertEquals(4, d4Back.degree());
		assertEquals(2, d4Back.size());
		assertEquals(0x5432L, d4Back.destOrder(1));

		// Manifest reflects highest degree + per-degree generated counts.
		List<String> manifest = Files.readAllLines(dir.resolve("manifest.txt"), StandardCharsets.UTF_8);
		assertTrue(manifest.contains("fingerprint=fp-abc"));
		assertTrue(manifest.contains("base=1"));
		assertTrue(manifest.contains("highestDegree=4"));
		assertTrue(manifest.contains("degree.3.generated=7"));
		assertTrue(manifest.contains("degree.4.rows=2"));
		assertTrue(manifest.contains("degree.4.generated=11"));
	}

	@Test
	void resumeReadsManifestAndLayers(@TempDir Path dir) {
		// Write a base + two degrees, then re-open with a fresh manager and read it back.
		CheckpointManager writer = new CheckpointManager(dir, "fp-resume");
		writer.init();
		StubColumns pairs = new StubColumns(2);
		pairs.addRow(new int[] {1, 2}, 0x1L, 0x2L, 100, 50, (byte) 0, new int[] {0, 1});
		writer.writeBase(pairs);
		StubColumns d3 = new StubColumns(3);
		d3.addRow(new int[] {1, 2, 3}, 0x123L, 0x321L, 200, 90, (byte) 0);
		writer.writeDegree(3, d3, 7);
		StubColumns d4 = new StubColumns(4);
		d4.addRow(new int[] {1, 2, 3, 4}, 0x1234L, 0x4321L, 300, 120, (byte) 1);
		writer.writeDegree(4, d4, 11);

		CheckpointManager reader = new CheckpointManager(dir, "fp-resume");
		assertTrue(reader.hasManifest());
		CheckpointManager.Manifest m = reader.readManifest();
		assertEquals("fp-resume", m.fingerprint);
		assertTrue(m.baseWritten);
		assertEquals(4, m.highestDegree);
		assertEquals(7L, m.generatedFor(3));
		assertEquals(11L, m.generatedFor(4));
		assertEquals(0L, m.generatedFor(5)); // absent degree

		// Layers read back bit-identically.
		assertEquals(1, reader.readBase().size());
		assertArrayEquals(new int[] {0, 1}, reader.readBase().positionIndices(0));
		assertEquals(0x321L, reader.readDegree(3).destOrder(0));
		assertEquals(0x4321L, reader.readDegree(4).destOrder(0));

		// Adopting the manifest lets a resumed run append the NEXT degree on top.
		reader.adoptManifest(m);
		StubColumns d5 = new StubColumns(5);
		d5.addRow(new int[] {1, 2, 3, 4, 5}, 0x12345L, 0x54321L, 400, 150, (byte) 0);
		reader.writeDegree(5, d5, 13);
		CheckpointManager.Manifest m2 = reader.readManifest();
		assertEquals(5, m2.highestDegree);
		assertEquals(7L, m2.generatedFor(3));  // earlier degrees preserved
		assertEquals(13L, m2.generatedFor(5));
	}

	@Test
	void noManifestMeansNoResume(@TempDir Path dir) {
		CheckpointManager mgr = new CheckpointManager(dir, "fp");
		mgr.init();
		assertFalse(mgr.hasManifest());
	}

	@Test
	void corruptManifestWithoutBaseIsRefused(@TempDir Path dir) throws IOException {
		Files.createDirectories(dir);
		Files.writeString(dir.resolve("manifest.txt"),
				"# header\nhighestDegree=3\n", StandardCharsets.UTF_8); // no fingerprint, no base
		CheckpointManager mgr = new CheckpointManager(dir, "fp");
		assertTrue(mgr.hasManifest());
		assertThrows(IllegalStateException.class, mgr::readManifest);
	}

	private static StubColumns read(Path p) throws IOException {
		try (InputStream in = Files.newInputStream(p)) {
			return StubColumnsIO.read(in);
		}
	}
}
