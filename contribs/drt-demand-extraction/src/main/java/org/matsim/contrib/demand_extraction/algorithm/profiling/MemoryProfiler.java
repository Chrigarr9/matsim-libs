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

	private MemoryProfiler() {}

	/**
	 * Log heap usage at a stage boundary. Prefer {@link #snapshotAtEndOfDegree} at
	 * end-of-degree so the GC happens *before* sampling — that gives a stable
	 * "rides retained" number rather than a noisy peak.
	 */
	public static void snapshot(String stage) {
		MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
		log.info("[MEM] {}: used={} committed={} max={}",
				stage,
				formatGiB(heap.getUsed()),
				formatGiB(heap.getCommitted()),
				formatGiB(heap.getMax()));
	}

	/**
	 * Forces a full GC then logs heap usage. Use at end of each extension degree
	 * so the {@code used} value reflects rides retained for the next iteration —
	 * critical for the R1↔R2↔R3 memory comparison.
	 */
	public static void snapshotAtEndOfDegree(int degree, int rideCount) {
		long t0 = System.nanoTime();
		System.gc();
		long gcMs = (System.nanoTime() - t0) / 1_000_000L;
		MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
		log.info("[MEM] degree={} rides={} used={} committed={} max={} (post-gc, gc={}ms)",
				degree, rideCount,
				formatGiB(heap.getUsed()),
				formatGiB(heap.getCommitted()),
				formatGiB(heap.getMax()),
				gcMs);
	}

	private static String formatGiB(long bytes) {
		if (bytes < 0) return "n/a";
		return String.format(java.util.Locale.ROOT, "%.2fGB", bytes / (1024.0 * 1024.0 * 1024.0));
	}
}
