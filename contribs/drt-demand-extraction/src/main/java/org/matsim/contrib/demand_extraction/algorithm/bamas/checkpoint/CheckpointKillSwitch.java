package org.matsim.contrib.demand_extraction.algorithm.bamas.checkpoint;

/**
 * Test-only crash injection for the kill-resume determinism gate (Plan A3 Task 7).
 *
 * <p>System property {@code -Dbamas.checkpoint.killAfterDegree=N} halts the JVM via
 * {@link Runtime#halt(int)} — bypassing shutdown hooks, {@code finally} blocks, and
 * stream flushing: the closest in-JVM analogue to an external {@code SIGKILL} — the
 * instant the degree-{@code N} checkpoint manifest and connection-cache journal are
 * durably written. This models a real mid-run kill landing at a clean barrier, so a
 * subsequent resume from the same checkpoint dir must reproduce byte-identical output.
 *
 * <p>Degree numbering matches the barriers: {@code N=2} is the pre-loop base barrier
 * (degrees 1+2 plus the pair graph), {@code N>=3} is the in-loop barrier that commits
 * the degree-{@code N} extension layer.
 *
 * <p>Inert in production: with the property unset, {@link #shouldHalt(int)} short-circuits
 * to {@code false} and {@link #maybeHaltAfterDegree(int)} is a no-op. The halt itself is
 * exercised only by the scenario driver (a unit test cannot survive its own JVM halt), so
 * the testable decision is factored into {@link #shouldHalt(int)}.
 */
public final class CheckpointKillSwitch {

	/** Halt the JVM right after this degree's checkpoint commits. Unset ⇒ never halt. */
	static final String KILL_AFTER_DEGREE_PROPERTY = "bamas.checkpoint.killAfterDegree";

	/** Exit code reported on injected halt — 137 = 128 + SIGKILL(9), mirroring an OS kill. */
	static final int SIGKILL_EXIT_CODE = 137;

	private CheckpointKillSwitch() {
	}

	/**
	 * Decide whether an injected halt should fire after the given degree committed.
	 * Pure and side-effect-free so it is unit-testable without killing the JVM.
	 */
	static boolean shouldHalt(int committedDegree) {
		String raw = System.getProperty(KILL_AFTER_DEGREE_PROPERTY);
		if (raw == null || raw.isBlank()) {
			return false;
		}
		return committedDegree == Integer.parseInt(raw.trim());
	}

	/**
	 * Halt the JVM (SIGKILL analogue) if {@link #shouldHalt(int)} matches. Called at each
	 * checkpoint barrier immediately after the manifest + journal are durable.
	 */
	public static void maybeHaltAfterDegree(int committedDegree) {
		if (shouldHalt(committedDegree)) {
			Runtime.getRuntime().halt(SIGKILL_EXIT_CODE);
		}
	}
}
