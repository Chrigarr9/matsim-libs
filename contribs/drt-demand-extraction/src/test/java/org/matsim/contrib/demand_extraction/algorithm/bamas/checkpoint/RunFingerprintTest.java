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
 * cache corruption otherwise) but MUST allow a different thread count — routing is deterministic
 * via {@code DeterministicTravelDisutility} (verified by {@code CrossEngineRoutingDeterminismTest}),
 * so core count does not change stub/cache identity.
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

	/**
	 * Under the fork-below-minDegree path, {@code maxPoolingDegree} is excluded: a degree-2 base
	 * checkpoint (highestDegree=2 &lt; default minDegree=4) may be resumed with a different
	 * maxPoolingDegree (e.g. capped to 2 to dump the degree-2 universe). The 5-arg full-hash path
	 * still distinguishes it (see {@link #maxDegreeChangesFingerprint()}), so non-fork resumes stay
	 * strict.
	 */
	@Test
	void maxPoolingDegreeExcludedUnderForkBelowMinDegree() {
		ExMasConfigGroup c2 = config();
		c2.setMaxPoolingDegree(2);
		int resumeHighestDegree = 2; // strictly below default minDegree (4) ⇒ fork skip active
		assertEquals(
				RunFingerprint.compute(config(), null, null, null, "bamas-v1", resumeHighestDegree),
				RunFingerprint.compute(c2, null, null, null, "bamas-v1", resumeHighestDegree));
	}

	/**
	 * Under the fork-below-minDegree path, the post-processing flags ({@code calcPredecessors},
	 * {@code calcShapleyValues}) are excluded: they run after generation and shape no stub/cache, so a
	 * degree-2 dump may switch them off. The 5-arg full-hash path still distinguishes them.
	 */
	@Test
	void postProcessFlagsExcludedUnderForkBelowMinDegree() {
		ExMasConfigGroup c2 = config();
		c2.setCalcPredecessors(false);
		c2.setCalcShapleyValues(false);
		int resumeHighestDegree = 2; // strictly below default minDegree (4) ⇒ fork skip active
		assertEquals(
				RunFingerprint.compute(config(), null, null, null, "bamas-v1", resumeHighestDegree),
				RunFingerprint.compute(c2, null, null, null, "bamas-v1", resumeHighestDegree));
	}

	@Test
	void calcPredecessorsChangesFullFingerprint() {
		ExMasConfigGroup c2 = config();
		c2.setCalcPredecessors(false);
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
	void pairgenTopKChangesFingerprint() {
		ExMasConfigGroup c2 = config();
		c2.setPairgenTopK(32);
		assertNotEquals(
				RunFingerprint.compute(config(), null, null, null, "bamas-v1"),
				RunFingerprint.compute(c2, null, null, null, "bamas-v1"));
	}

	/**
	 * pairgenTopK changes the degree-2 universe, so it is NOT forkable: it must still differ
	 * even on the fork-below-minDegree path (resumeHighestDegree=2 < default minDegree=4).
	 */
	@Test
	void pairgenTopKStaysInFingerprintUnderForkBelowMinDegree() {
		ExMasConfigGroup c2 = config();
		c2.setPairgenTopK(32);
		int resumeHighestDegree = 2;
		assertNotEquals(
				RunFingerprint.compute(config(), null, null, null, "bamas-v1", resumeHighestDegree),
				RunFingerprint.compute(c2, null, null, null, "bamas-v1", resumeHighestDegree));
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

	/**
	 * Guard: the excluded-param set is EXACTLY
	 * {@code {algorithmProcessCount, checkpointDir, checkpointJournalCompactionBytes,
	 * allowCheckpointForkBelowMinDegree}}. Each exclusion is sound only on stated grounds
	 * (deterministic routing; location-not-identity; journal disk layout, not journal contents;
	 * a decision ABOUT the resume check that nothing downstream reads). Pinning the set means
	 * adding a new exclusion fails here, forcing a deliberate review of whether that param truly
	 * cannot change stub/cache identity — an unreviewed addition is a silent under-refusal that
	 * corrupts a resume.
	 */
	@Test
	void excludedParamsAreExactlyTheFourSoundExclusions() {
		assertEquals(
				java.util.Set.of("algorithmProcessCount", "checkpointDir",
						"checkpointJournalCompactionBytes",
						"allowCheckpointForkBelowMinDegree"),
				RunFingerprint.EXCLUDED_PARAMS);
	}

	/**
	 * The fork flag became a persisted {@code @StringGetter} param on 2026-09-01 (so a phase-2
	 * config XML can set it), which put it into {@code getParams()} — the very map hashed here.
	 * It MUST NOT reach the hash: an armed flag that changed the fingerprint would guarantee the
	 * mismatch it exists to forgive, and it would invalidate every checkpoint written before the
	 * rename. This is the regression guard for that.
	 */
	@Test
	void forkFlagDoesNotChangeFingerprint() {
		ExMasConfigGroup c2 = config();
		c2.setAllowCheckpointForkBelowMinDegree(true);
		assertEquals(
				RunFingerprint.compute(config(), null, null, null, "bamas-v1"),
				RunFingerprint.compute(c2, null, null, null, "bamas-v1"));
	}

	/** Same guard on the fork (6-arg) path, where the flag is most likely to be set. */
	@Test
	void forkFlagDoesNotChangeFingerprintUnderForkBelowMinDegree() {
		ExMasConfigGroup c2 = config();
		c2.setAllowCheckpointForkBelowMinDegree(true);
		int resumeHighestDegree = 2; // strictly below default minDegree (4)
		assertEquals(
				RunFingerprint.compute(config(), null, null, null, "bamas-v1", resumeHighestDegree),
				RunFingerprint.compute(c2, null, null, null, "bamas-v1", resumeHighestDegree));
	}

	/**
	 * The flag is a real config param now: it round-trips through the string registry (so a
	 * phase-2 config XML can arm it) even though it is fingerprint-excluded. Both halves matter —
	 * settable AND unhashed.
	 */
	@Test
	void forkFlagIsAConfigParamButStillExcluded() {
		ExMasConfigGroup cfg = new ExMasConfigGroup();
		cfg.setAllowCheckpointForkBelowMinDegree(true);
		assertEquals("true", cfg.getParams().get("allowCheckpointForkBelowMinDegree"),
				"must be a persisted param, otherwise a config file cannot set it");
		assertTrue(RunFingerprint.EXCLUDED_PARAMS.contains("allowCheckpointForkBelowMinDegree"),
				"...and must be excluded from the hash, otherwise arming it breaks its own check");
	}

	/**
	 * The journal compaction threshold must not fingerprint: a compacted journal replays to the
	 * same cache map and reports the same barrier count, so changing the threshold mid-run (e.g.
	 * to rescue a filling disk) must not refuse the resume.
	 */
	@Test
	void journalCompactionThresholdDoesNotChangeFingerprint() {
		ExMasConfigGroup c2 = config();
		c2.setCheckpointJournalCompactionBytes(1234L);
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
