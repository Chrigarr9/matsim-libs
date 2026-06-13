package org.matsim.contrib.demand_extraction.algorithm.bamas.checkpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;

/**
 * Verifies the checkpoint-fork fingerprint behaviour (Plan B1 Task B1).
 *
 * <p>One pair-gen/degree-3 checkpoint must be able to feed a sweep of different parent-pruning
 * configs without the fingerprint refusing the resume — but only when the checkpoint sits strictly
 * below the degree at which those knobs first act ({@code extensionParentsTopKMinDegree}, default
 * 4). At or above that degree the full param set is hashed and refusal is restored.
 */
class RunFingerprintForkTest {

	/** Returns a fresh config with default settings (minDegree == 4, mmrLambda == 0.0). */
	private static ExMasConfigGroup cfg() {
		return new ExMasConfigGroup();
	}

	/**
	 * Below minDegree: the six forkable parent-pruning knobs are excluded from the hash, so two
	 * configs differing only in {@code extensionParentsMmrLambda} produce the same fingerprint.
	 */
	@Test
	void parentPruningKnobsExcludedWhenResumingBelowMinDegree() {
		ExMasConfigGroup a = cfg();
		a.setExtensionParentsMmrLambda(0.0);

		ExMasConfigGroup b = cfg();
		b.setExtensionParentsMmrLambda(0.5);

		// resumeHighestDegree=2 < extensionParentsTopKMinDegree=4 → knob excluded
		assertEquals(
				RunFingerprint.compute(a, null, null, null, "bamas", 2),
				RunFingerprint.compute(b, null, null, null, "bamas", 2));
	}

	/**
	 * At or above minDegree: the forkable knobs are back in the hash, so the same two configs
	 * produce different fingerprints.
	 */
	@Test
	void parentPruningKnobsIncludedWhenResumingAtOrAboveMinDegree() {
		ExMasConfigGroup a = cfg();
		a.setExtensionParentsMmrLambda(0.0);

		ExMasConfigGroup b = cfg();
		b.setExtensionParentsMmrLambda(0.5);

		// resumeHighestDegree=4 == extensionParentsTopKMinDegree=4 → knob included
		assertNotEquals(
				RunFingerprint.compute(a, null, null, null, "bamas", 4),
				RunFingerprint.compute(b, null, null, null, "bamas", 4));
	}

	/**
	 * The gate scale knob ({@code pruningDistanceSavingsLogScale}, exposed as {@code --gate-scale}
	 * on the CLI) is NOT one of the six forkable parent-pruning params and must remain in the hash
	 * even when resuming below minDegree, because it shapes pair-gen/degree-3 stub identity.
	 */
	@Test
	void gateScaleAlwaysIncludedEvenBelowMinDegree() {
		ExMasConfigGroup a = cfg();
		a.setPruningDistanceSavingsLogScale(0.8);  // --gate-scale 0.8

		ExMasConfigGroup b = cfg();
		b.setPruningDistanceSavingsLogScale(0.6);  // --gate-scale 0.6

		// resumeHighestDegree=2 < minDegree=4, but pruningDistanceSavingsLogScale is not forkable
		assertNotEquals(
				RunFingerprint.compute(a, null, null, null, "bamas", 2),
				RunFingerprint.compute(b, null, null, null, "bamas", 2));
	}

	/**
	 * The 5-arg legacy overload must produce the same result as the 6-arg overload with
	 * {@code resumeHighestDegree = Integer.MAX_VALUE}. This guarantees byte-identity with the
	 * pre-fork implementation.
	 */
	@Test
	void legacyOverloadUnchanged() {
		ExMasConfigGroup a = cfg();
		assertEquals(
				RunFingerprint.compute(a, null, null, null, "bamas"),
				RunFingerprint.compute(a, null, null, null, "bamas", Integer.MAX_VALUE));
	}
}
