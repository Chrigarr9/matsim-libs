package org.matsim.contrib.demand_extraction.algorithm.network;

import java.util.function.LongSupplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Heap-pressure eviction policy (2026-06-12-connection-cache-memory-design §3): when
 * used/max heap strictly exceeds the watermark fraction, fire the eviction action
 * (one speculative-tier generation rotation per check call).
 *
 * <p>Watermark 1.0 disables eviction entirely ("used &gt; max" is impossible).
 * Checked at coarse barriers (origin-batch end, degree barrier), never per lookup.
 * Production construction uses {@link #forRuntime}; tests inject suppliers.
 *
 * <p>Eviction is output-invariant by construction: evicted segments re-route through
 * the single value source and reproduce bit-identical values — the watermark affects
 * runtime only, never results.
 */
public final class HeapWatermark {

	private static final Logger log = LogManager.getLogger(HeapWatermark.class);

	private final double watermark;
	private final LongSupplier usedBytes;
	private final LongSupplier maxBytes;
	private final Runnable evictAction;
	private long evictions = 0;

	public HeapWatermark(double watermark, LongSupplier usedBytes, LongSupplier maxBytes,
			Runnable evictAction) {
		if (watermark < 0.0 || watermark > 1.0) {
			throw new IllegalArgumentException("watermark must be in [0,1]: " + watermark);
		}
		this.watermark = watermark;
		this.usedBytes = usedBytes;
		this.maxBytes = maxBytes;
		this.evictAction = evictAction;
	}

	public static HeapWatermark forRuntime(double watermark, Runnable evictAction) {
		Runtime rt = Runtime.getRuntime();
		return new HeapWatermark(watermark,
				() -> rt.totalMemory() - rt.freeMemory(), rt::maxMemory, evictAction);
	}

	/** Call at coarse barriers. Synchronized: barriers may race in parallel generators. */
	public synchronized void checkAndMaybeEvict() {
		if (watermark >= 1.0) {
			return;
		}
		long used = usedBytes.getAsLong();
		long max = maxBytes.getAsLong();
		if (used > watermark * max) {
			evictions++;
			log.info("[cache-watermark] heap {}/{} MB > {} - rotating speculative tier (eviction #{})",
					used >> 20, max >> 20, watermark, evictions);
			evictAction.run();
		}
	}

	public synchronized long getEvictionCount() {
		return evictions;
	}
}
