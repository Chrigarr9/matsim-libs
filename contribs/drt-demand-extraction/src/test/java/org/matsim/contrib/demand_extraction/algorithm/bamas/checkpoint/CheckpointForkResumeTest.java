package org.matsim.contrib.demand_extraction.algorithm.bamas.checkpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;

/**
 * Verifies the opt-in fork resume (Plan B Task B2): a checkpoint written strictly below
 * {@code extensionParentsTopKMinDegree} may be resumed under CHANGED parent-pruning knobs when
 * {@code allowCheckpointForkBelowMinDegree} is on — because those knobs have not yet shaped any
 * stub at that degree. Default-off keeps today's strict full-hash guard; non-forkable changes (e.g.
 * the distance gate scale) are refused regardless of the flag.
 * <p>Provenance 2026-09-01 -- the flag was called {@code checkpointForkBelowMinDegree} and was
 * settable only from the Phase-2 CLI; it is now a config param too (see
 * {@link #configFileParamArmsTheFork()}).
 *
 * <p>Drives the testable {@link RunFingerprint#matchesForResume} directly (no algorithm run) plus a
 * {@link CheckpointManager.Manifest} round-trip through {@code writeManifest}/{@code readManifest}.
 */
class CheckpointForkResumeTest {

	private static final int MIN_DEGREE = new ExMasConfigGroup().getExtensionParentsTopKMinDegree(); // 4

	/** Fresh default config (minDegree == 4, mmrLambda == 0.0, gate scale default). */
	private static ExMasConfigGroup cfg() {
		return new ExMasConfigGroup();
	}

	/**
	 * Build the two fingerprints a base config X would have written ({@code fingerprint} = full hash,
	 * {@code baseFingerprint} = forkable-knob-free at {@code minDegree-1}) and pack them into a
	 * Manifest at the requested {@code highestDegree}. Same-package access to the package-private
	 * Manifest constructor.
	 */
	private static CheckpointManager.Manifest manifestFor(ExMasConfigGroup base, int highestDegree) {
		String fingerprint = RunFingerprint.compute(base, null, null, null, "bamas");
		String baseFingerprint = RunFingerprint.compute(base, null, null, null, "bamas", MIN_DEGREE - 1);
		return new CheckpointManager.Manifest(fingerprint, baseFingerprint, true, highestDegree,
				new TreeMap<>());
	}

	/** 1. Fork accepts a below-minDegree checkpoint under a changed (forkable) parent-pruning knob. */
	@Test
	void forkAcceptsBelowMinDegree() {
		ExMasConfigGroup x = cfg();
		x.setExtensionParentsMmrLambda(0.0);
		CheckpointManager.Manifest m = manifestFor(x, 3); // 3 < minDegree 4

		ExMasConfigGroup y = cfg();
		y.setExtensionParentsMmrLambda(0.5);

		assertTrue(RunFingerprint.matchesForResume(m, y, null, null, null, "bamas", true),
				"fork ON below minDegree must accept a changed forkable knob");
	}

	/** 2. Strict (flag off) rejects the same changed-knob resume. */
	@Test
	void strictRejectsWhenFlagOff() {
		ExMasConfigGroup x = cfg();
		x.setExtensionParentsMmrLambda(0.0);
		CheckpointManager.Manifest m = manifestFor(x, 3);

		ExMasConfigGroup y = cfg();
		y.setExtensionParentsMmrLambda(0.5);

		assertFalse(RunFingerprint.matchesForResume(m, y, null, null, null, "bamas", false),
				"fork OFF must keep today's strict full-hash refusal");
	}

	/** 3. A non-forkable change (distance gate scale) is rejected even under the fork flag. */
	@Test
	void nonForkableChangeStillRejectedUnderFlag() {
		ExMasConfigGroup x = cfg();
		x.setPruningDistanceSavingsLogScale(0.8);
		CheckpointManager.Manifest m = manifestFor(x, 3);

		ExMasConfigGroup y = cfg();
		y.setPruningDistanceSavingsLogScale(0.6); // gate scale shapes pair-gen/degree-3 stubs

		assertFalse(RunFingerprint.matchesForResume(m, y, null, null, null, "bamas", true),
				"the distance gate scale is not forkable — must be refused even with fork ON");
	}

	/** 4. At/above minDegree the full guard is back, so a forkable-knob change is refused. */
	@Test
	void atOrAboveMinDegreeFallsBackToFullGuard() {
		ExMasConfigGroup x = cfg();
		x.setExtensionParentsMmrLambda(0.0);
		CheckpointManager.Manifest m = manifestFor(x, MIN_DEGREE); // highestDegree == minDegree

		ExMasConfigGroup y = cfg();
		y.setExtensionParentsMmrLambda(0.5);

		assertFalse(RunFingerprint.matchesForResume(m, y, null, null, null, "bamas", true),
				"at/above minDegree the forkable knob is back in the hash — must refuse");
	}

	/** 5. Same config, fork ON below minDegree → accepts (sanity: no spurious mismatch). */
	@Test
	void forkAcceptsIdenticalConfig() {
		ExMasConfigGroup x = cfg();
		CheckpointManager.Manifest m = manifestFor(x, 3);
		assertTrue(RunFingerprint.matchesForResume(m, cfg(), null, null, null, "bamas", true));
	}

	/**
	 * 6. Manifest round-trip carries {@code baseFingerprint} through write+read, and a v1-style
	 * manifest (no baseFingerprint line) is refused.
	 */
	@Test
	void manifestRoundTripsBaseFingerprint(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
			throws java.io.IOException {
		CheckpointManager mgr = new CheckpointManager(dir, "fp-full", "fp-base");
		mgr.init();
		org.matsim.contrib.demand_extraction.algorithm.bamas.ride.RideLayer pairs =
				new org.matsim.contrib.demand_extraction.algorithm.bamas.ride.RideLayer(2);
		pairs.addRow(new int[] {1, 2}, 0x1L, 0x2L, 100, 50, (byte) 0, new int[] {0, 1});
		mgr.writeBase(pairs);

		CheckpointManager.Manifest m = new CheckpointManager(dir, "ignored", "ignored").readManifest();
		assertEquals("fp-full", m.fingerprint);
		assertEquals("fp-base", m.baseFingerprint);

		// A v1 manifest (no baseFingerprint=) must be refused, not silently mis-read.
		java.nio.file.Files.writeString(dir.resolve("manifest.txt"),
				"# BAMAS checkpoint manifest v1\nfingerprint=fp-full\nbase=1\nhighestDegree=3\n",
				java.nio.charset.StandardCharsets.UTF_8);
		org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
				() -> new CheckpointManager(dir, "x", "y").readManifest());
	}

	/**
	 * 7. The fork is armable from a CONFIG FILE, not only from the Phase-2 CLI (2026-09-01).
	 *
	 * <p>{@code addParam} is precisely the entry point MATSim's XML reader calls for every
	 * {@code <param name=... value=.../>} in a config, so driving it here exercises the same path a
	 * phase-2 config XML takes. The assertion is end-to-end rather than getter-deep: the manifest
	 * was written under a DIFFERENT forkable knob, so it is accepted only if the config-file value
	 * genuinely reached the resume decision.
	 */
	@Test
	void configFileParamArmsTheFork() {
		ExMasConfigGroup x = cfg();
		x.setExtensionParentsMmrLambda(0.0);
		CheckpointManager.Manifest m = manifestFor(x, 3); // 3 < minDegree 4

		ExMasConfigGroup y = cfg();
		y.setExtensionParentsMmrLambda(0.5);          // a forkable knob, changed
		y.addParam("allowCheckpointForkBelowMinDegree", "true"); // as an XML <param> would

		assertTrue(y.isAllowCheckpointForkBelowMinDegree(),
				"a config-file param must arm the flag");
		assertTrue(RunFingerprint.matchesForResume(m, y, null, null, null, "bamas",
						y.isAllowCheckpointForkBelowMinDegree()),
				"the config-file value must reach the resume decision, not just the getter");
	}

	/**
	 * 8. Arming the flag must NOT itself change the fingerprint. It is a persisted param now, so it
	 * sits in {@code getParams()} — the map the hash is built from — and is kept out of the hash by
	 * {@code RunFingerprint.EXCLUDED_PARAMS}. Were that exclusion ever dropped, the manifest below
	 * (written with the flag OFF) would stop matching a resume that turns the flag ON, i.e. the knob
	 * would break the very check it exists to relax. Both fingerprints are asserted, because the
	 * fork path compares the base hash and the strict path the full one.
	 */
	@Test
	void armingTheForkDoesNotPerturbEitherFingerprint() {
		ExMasConfigGroup off = cfg();
		CheckpointManager.Manifest m = manifestFor(off, 3);

		ExMasConfigGroup on = cfg();
		on.setAllowCheckpointForkBelowMinDegree(true);

		assertEquals(m.fingerprint, RunFingerprint.compute(on, null, null, null, "bamas"),
				"full hash must be blind to the fork flag");
		assertEquals(m.baseFingerprint,
				RunFingerprint.compute(on, null, null, null, "bamas", MIN_DEGREE - 1),
				"base hash must be blind to the fork flag");
	}
}
