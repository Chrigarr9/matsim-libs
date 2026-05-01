package org.matsim.contrib.demand_extraction.algorithm.profiling;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ReferenceProgressCheckpointPolicyTest {

	@Test
	void degreeBelowFiveDoesNotEmitRunningCheckpoints() {
		assertFalse(ReferenceProgressCheckpointPolicy.shouldEmitRunningCheckpoint(4, 32, 0, 30_000));
	}

	@Test
	void degreeFiveEmitsAtPowerOfTwoMilestones() {
		assertTrue(ReferenceProgressCheckpointPolicy.shouldEmitRunningCheckpoint(5, 32, 0, 30_000));
	}

	@Test
	void degreeFiveEmitsAfterMinimumInterval() {
		assertTrue(ReferenceProgressCheckpointPolicy.shouldEmitRunningCheckpoint(5, 33, 30_000, 30_000));
	}

	@Test
	void terminalOomEmissionIsOnlyEnabledForTrackedDegrees() {
		assertFalse(ReferenceProgressCheckpointPolicy.shouldEmitTerminalOom(4));
		assertTrue(ReferenceProgressCheckpointPolicy.shouldEmitTerminalOom(5));
	}
}