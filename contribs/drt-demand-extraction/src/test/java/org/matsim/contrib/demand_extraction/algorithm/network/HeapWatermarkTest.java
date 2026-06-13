package org.matsim.contrib.demand_extraction.algorithm.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class HeapWatermarkTest {

	@Test
	void breachTriggersActionOncePerCheck() {
		AtomicInteger rotations = new AtomicInteger();
		// injected usage supplier: used=80, max=100, watermark=0.7 -> breach
		HeapWatermark wm = new HeapWatermark(0.7, () -> 80L, () -> 100L, rotations::incrementAndGet);
		wm.checkAndMaybeEvict();
		assertEquals(1, rotations.get());
		wm.checkAndMaybeEvict(); // still above watermark -> fires again (one rotation per check)
		assertEquals(2, rotations.get());
		assertEquals(2, wm.getEvictionCount());
	}

	@Test
	void noBreachNoAction() {
		AtomicInteger rotations = new AtomicInteger();
		HeapWatermark wm = new HeapWatermark(0.7, () -> 60L, () -> 100L, rotations::incrementAndGet);
		wm.checkAndMaybeEvict();
		assertEquals(0, rotations.get());
		assertEquals(0, wm.getEvictionCount());
	}

	@Test
	void watermarkOneNeverEvicts() {
		AtomicInteger rotations = new AtomicInteger();
		HeapWatermark wm = new HeapWatermark(1.0, () -> 100L, () -> 100L, rotations::incrementAndGet);
		wm.checkAndMaybeEvict();
		assertEquals(0, rotations.get());
	}

	@Test
	void boundaryIsExclusive() {
		AtomicInteger rotations = new AtomicInteger();
		// used == watermark * max exactly -> no eviction (strictly greater fires)
		HeapWatermark wm = new HeapWatermark(0.7, () -> 70L, () -> 100L, rotations::incrementAndGet);
		wm.checkAndMaybeEvict();
		assertEquals(0, rotations.get());
	}

	@Test
	void invalidWatermarkThrows() {
		assertThrows(IllegalArgumentException.class,
				() -> new HeapWatermark(-0.1, () -> 0L, () -> 1L, () -> { }));
		assertThrows(IllegalArgumentException.class,
				() -> new HeapWatermark(1.1, () -> 0L, () -> 1L, () -> { }));
	}
}
