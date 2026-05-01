package org.matsim.contrib.demand_extraction.algorithm.profiling;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Heap-usage snapshots for end-of-phase profiling. Used to compare the memory
 * envelope of R1 (vanilla ExMAS) against R2 (BAMAS) and R3 (BAMAS + pruning) on
 * the same scenario, where R1 is expected to balloon at higher degrees because
 * it retains every feasible ordering as a separate Ride.
 *
 * <p>Identical formatting in both engines so the dissertation comparison table
 * is apples-to-apples. All three numbers come from the heap MX bean:
 * <ul>
 *   <li>{@code used} — bytes currently held by live objects</li>
 *   <li>{@code committed} — bytes the JVM has reserved from the OS (the heap
 *       can grow up to {@code max} as needed)</li>
 *   <li>{@code max} — {@code -Xmx} ceiling</li>
 * </ul>
 *
 * <p>{@link #snapshotAtEndOfDegree} requests a full GC before sampling so the
 * reported {@code used} reflects retained-after-GC working set, not transient
 * allocations. Costs ~10-200ms per call which is negligible at degree boundaries.
 */
public final class MemoryProfiler {

	private static final Logger log = LogManager.getLogger(MemoryProfiler.class);

	public record HeapSample(
			String stage,
			long usedBytes,
			long committedBytes,
			long maxBytes,
			long gcMillis) {

		public double usedGiB() {
			return toGiB(usedBytes);
		}

		public double committedGiB() {
			return toGiB(committedBytes);
		}

		public double maxGiB() {
			return maxBytes < 0 ? -1.0 : toGiB(maxBytes);
		}
	}

	private MemoryProfiler() {}

	public static HeapSample captureHeapSample(String stage, boolean runGc) {
		long gcMs = 0L;
		if (runGc) {
			long t0 = System.nanoTime();
			System.gc();
			gcMs = (System.nanoTime() - t0) / 1_000_000L;
		}

		MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
		return new HeapSample(stage, heap.getUsed(), heap.getCommitted(), heap.getMax(), gcMs);
	}

	/**
	 * Log heap usage at a stage boundary. Prefer {@link #snapshotAtEndOfDegree} at
	 * end-of-degree so the GC happens *before* sampling — that gives a stable
	 * "rides retained" number rather than a noisy peak.
	 */
	public static HeapSample snapshot(String stage) {
		HeapSample sample = captureHeapSample(stage, false);
		log.info("[MEM] {}: used={} committed={} max={}",
				sample.stage(),
				formatGiB(sample.usedBytes()),
				formatGiB(sample.committedBytes()),
				formatGiB(sample.maxBytes()));
		return sample;
	}

	/**
	 * Forces a full GC then logs heap usage. Use at end of each extension degree
	 * so the {@code used} value reflects rides retained for the next iteration —
	 * critical for the R1↔R2↔R3 memory comparison.
	 */
	public static HeapSample snapshotAtEndOfDegree(int degree, int rideCount) {
		HeapSample sample = captureHeapSample("degree=" + degree, true);
		log.info("[MEM] degree={} rides={} used={} committed={} max={} (post-gc, gc={}ms)",
				degree, rideCount,
				formatGiB(sample.usedBytes()),
				formatGiB(sample.committedBytes()),
				formatGiB(sample.maxBytes()),
				sample.gcMillis());
		return sample;
	}

	private static double toGiB(long bytes) {
		return bytes / (1024.0 * 1024.0 * 1024.0);
	}

	private static String formatGiB(long bytes) {
		if (bytes < 0) return "n/a";
		return String.format(java.util.Locale.ROOT, "%.2fGB", toGiB(bytes));
	}
}
