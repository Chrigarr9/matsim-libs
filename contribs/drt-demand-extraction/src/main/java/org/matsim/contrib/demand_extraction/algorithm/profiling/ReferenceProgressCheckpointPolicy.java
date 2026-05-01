package org.matsim.contrib.demand_extraction.algorithm.profiling;

public final class ReferenceProgressCheckpointPolicy {
	private ReferenceProgressCheckpointPolicy() {
	}

	public static boolean shouldEmitRunningCheckpoint(
			int targetDegree,
			long setsProcessed,
			long elapsedSinceLastCheckpointMs,
			long minCheckpointIntervalMs) {
		if (targetDegree < 5 || setsProcessed <= 0) {
			return false;
		}

		return isPowerOfTwo(setsProcessed) || elapsedSinceLastCheckpointMs >= minCheckpointIntervalMs;
	}

	public static boolean shouldEmitTerminalOom(int targetDegree) {
		return targetDegree >= 5;
	}

	private static boolean isPowerOfTwo(long value) {
		return value > 0 && (value & (value - 1)) == 0;
	}
}