package org.matsim.contrib.demand_extraction.algorithm.bamas.checkpoint;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link CheckpointKillSwitch} decision logic. The halt itself
 * cannot be unit-tested (it terminates the JVM); the scenario driver exercises that.
 * Here we pin the {@link CheckpointKillSwitch#shouldHalt(int)} predicate so the gate
 * fires at exactly the intended barrier and stays inert otherwise.
 */
class CheckpointKillSwitchTest {

	@AfterEach
	void clearProperty() {
		System.clearProperty(CheckpointKillSwitch.KILL_AFTER_DEGREE_PROPERTY);
	}

	@Test
	void propertyUnsetNeverHalts() {
		System.clearProperty(CheckpointKillSwitch.KILL_AFTER_DEGREE_PROPERTY);
		assertFalse(CheckpointKillSwitch.shouldHalt(2));
		assertFalse(CheckpointKillSwitch.shouldHalt(4));
		assertFalse(CheckpointKillSwitch.shouldHalt(6));
	}

	@Test
	void blankPropertyNeverHalts() {
		System.setProperty(CheckpointKillSwitch.KILL_AFTER_DEGREE_PROPERTY, "   ");
		assertFalse(CheckpointKillSwitch.shouldHalt(4));
	}

	@Test
	void haltsOnlyAtMatchingDegree() {
		System.setProperty(CheckpointKillSwitch.KILL_AFTER_DEGREE_PROPERTY, "4");
		assertFalse(CheckpointKillSwitch.shouldHalt(2));
		assertFalse(CheckpointKillSwitch.shouldHalt(3));
		assertTrue(CheckpointKillSwitch.shouldHalt(4));
		assertFalse(CheckpointKillSwitch.shouldHalt(5));
	}

	@Test
	void baseBarrierDegreeTwoMatches() {
		System.setProperty(CheckpointKillSwitch.KILL_AFTER_DEGREE_PROPERTY, "2");
		assertTrue(CheckpointKillSwitch.shouldHalt(2));
		assertFalse(CheckpointKillSwitch.shouldHalt(3));
	}

	@Test
	void whitespacePaddedValueParses() {
		System.setProperty(CheckpointKillSwitch.KILL_AFTER_DEGREE_PROPERTY, " 6 ");
		assertTrue(CheckpointKillSwitch.shouldHalt(6));
		assertFalse(CheckpointKillSwitch.shouldHalt(4));
	}
}
