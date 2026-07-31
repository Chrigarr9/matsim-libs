package org.matsim.contrib.demand_extraction.io;

import java.io.IOException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Utility class for writing network connection cache to CSV files.
 *
 * Supports two export modes (configurable via ExMasConfigGroup.connectionCacheExportMode):
 *
 * - "window" (default): Exports only the OD/bin segments the handoff pass actually evaluated
 *   (accepted AND rejected handoffs) — the lookup domain of Python's
 *   compute_dynamic_successors. The window-key set is collected by RidePostProcessor and the
 *   rows are promoted-to-retained (Task 7), so eviction never drops them.
 *
 * - "all": Exports ALL cached connections from the network cache. Debug-only — the full
 *   speculative+retained footprint, much larger than the window domain.
 *
 * A third mode, "successors_only", was removed with the successor column it depended on: it
 * exported only the top-K-capped successor pairs, which is insufficient for dynamic successor
 * computation in Python whenever the active ride set differs from the pre-computed successors —
 * which, post-MIP, it always does.
 *
 * Format: CSV with columns origin,destination,time_bin,travel_time,distance
 *
 * The Python optimization uses this to calculate empty VKT when vehicles
 * travel between rides without passengers.
 */
public final class ConnectionCacheWriter {

	private static final Logger log = LogManager.getLogger(ConnectionCacheWriter.class);

	private ConnectionCacheWriter() {
		// Utility class - prevent instantiation
	}

	/**
	 * Write connection cache to CSV file.
	 *
	 * Export mode determines what is written:
	 * - "window": the evaluated handoff segments collected by the post-processor (default)
	 * - "all": the full network cache (debug-only)
	 *
	 * @param filename     output file path
	 * @param networkCache routing cache containing pre-computed connections
	 * @param exportMode   "window" or "all"
	 * @param windowKeys   packed OD/bin keys evaluated by the handoff pass
	 *                     ({@code RidePostProcessor.getWindowKeys()}); used only for "window" mode
	 * @throws IOException if writing fails
	 */
	public static void writeConnectionCache(
			String filename,
			MatsimNetworkCache networkCache,
			String exportMode,
			LongOpenHashSet windowKeys) throws IOException {

		switch (exportMode) {
			case "all" -> networkCache.exportAllEntries(filename);
			case "window" -> networkCache.exportWindow(filename, windowKeys);
			default -> throw new IllegalArgumentException(
					"Unknown connectionCacheExportMode '" + exportMode
							+ "' (allowed: window|all)");
		}
		log.info("Wrote connection cache (mode={}) to: {}", exportMode, filename);
	}
}
