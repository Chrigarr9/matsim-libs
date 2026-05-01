package org.matsim.contrib.demand_extraction.algorithm.profiling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MemoryProfilerTest {

	@Test
	void captureHeapSampleReturnsStructuredValuesWithoutGc() {
		MemoryProfiler.HeapSample sample = MemoryProfiler.captureHeapSample("checkpoint", false);

		assertEquals("checkpoint", sample.stage());
		assertTrue(sample.usedBytes() >= 0);
		assertTrue(sample.committedBytes() >= 0);
		assertTrue(sample.maxBytes() >= -1);
		assertEquals(0, sample.gcMillis());
		assertTrue(sample.usedGiB() >= 0.0);
	}
}