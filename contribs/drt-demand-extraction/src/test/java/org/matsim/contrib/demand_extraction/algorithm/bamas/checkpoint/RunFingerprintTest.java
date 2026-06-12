package org.matsim.contrib.demand_extraction.algorithm.bamas.checkpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;

/**
 * Fingerprint compatibility refusal for checkpoint/resume (Plan A3 Task 2).
 *
 * <p>A resume must refuse a run whose config/requests/routing inputs differ (silent stub or
 * cache corruption otherwise) but MUST allow a different thread count (Plan A made output
 * scheduling-independent).
 */
class RunFingerprintTest {

	private static ExMasConfigGroup config() {
		ExMasConfigGroup c = new ExMasConfigGroup();
		c.setMaxPoolingDegree(6);
		return c;
	}

	@Test
	void sameInputsSameFingerprint() {
		String a = RunFingerprint.compute(config(), null, null, null, "bamas-v1");
		String b = RunFingerprint.compute(config(), null, null, null, "bamas-v1");
		assertEquals(a, b);
	}

	@Test
	void maxDegreeChangesFingerprint() {
		ExMasConfigGroup c2 = config();
		c2.setMaxPoolingDegree(7);
		assertNotEquals(
				RunFingerprint.compute(config(), null, null, null, "bamas-v1"),
				RunFingerprint.compute(c2, null, null, null, "bamas-v1"));
	}

	/** Review addendum F4: Plan-B knobs MUST be in the fingerprint (the filter is recomputed). */
	@Test
	void extensionParentsTopKChangesFingerprint() {
		ExMasConfigGroup c2 = config();
		c2.setExtensionParentsTopK(8);
		assertNotEquals(
				RunFingerprint.compute(config(), null, null, null, "bamas-v1"),
				RunFingerprint.compute(c2, null, null, null, "bamas-v1"));
	}

	@Test
	void algorithmVersionChangesFingerprint() {
		assertNotEquals(
				RunFingerprint.compute(config(), null, null, null, "bamas-v1"),
				RunFingerprint.compute(config(), null, null, null, "bamas-v2"));
	}

	/** algorithmProcessCount is deliberately EXCLUDED: a crash on one box may resume on another. */
	@Test
	void threadCountDoesNotChangeFingerprint() {
		ExMasConfigGroup c2 = config();
		c2.setAlgorithmProcessCount(c2.getAlgorithmProcessCount() + 4);
		assertEquals(
				RunFingerprint.compute(config(), null, null, null, "bamas-v1"),
				RunFingerprint.compute(c2, null, null, null, "bamas-v1"));
	}

	/** The checkpoint dir path itself must not fingerprint (resume into a moved dir is legitimate). */
	@Test
	void checkpointDirDoesNotChangeFingerprint() {
		ExMasConfigGroup c2 = config();
		c2.setCheckpointDir("/some/other/dir");
		assertEquals(
				RunFingerprint.compute(config(), null, null, null, "bamas-v1"),
				RunFingerprint.compute(c2, null, null, null, "bamas-v1"));
	}

	@Test
	void requestsFileContentChangesFingerprint(@TempDir Path dir) throws IOException {
		Path reqA = Files.writeString(dir.resolve("a.csv"), "request,data,one\n");
		Path reqB = Files.writeString(dir.resolve("b.csv"), "request,data,TWO\n");
		assertNotEquals(
				RunFingerprint.compute(config(), reqA, null, null, "bamas-v1"),
				RunFingerprint.compute(config(), reqB, null, null, "bamas-v1"));
	}

	@Test
	void identicalFileContentSameFingerprint(@TempDir Path dir) throws IOException {
		Path reqA = Files.writeString(dir.resolve("a.csv"), "same,bytes\n");
		Path reqB = Files.writeString(dir.resolve("b.csv"), "same,bytes\n");
		assertEquals(
				RunFingerprint.compute(config(), reqA, null, null, "bamas-v1"),
				RunFingerprint.compute(config(), reqB, null, null, "bamas-v1"));
	}

	@Test
	void matchesReflexiveAndRejects() {
		String a = RunFingerprint.compute(config(), null, null, null, "bamas-v1");
		ExMasConfigGroup c2 = config();
		c2.setMaxPoolingDegree(9);
		String b = RunFingerprint.compute(c2, null, null, null, "bamas-v1");
		assertTrue(RunFingerprint.matches(a, a));
		assertFalse(RunFingerprint.matches(a, b));
	}
}
