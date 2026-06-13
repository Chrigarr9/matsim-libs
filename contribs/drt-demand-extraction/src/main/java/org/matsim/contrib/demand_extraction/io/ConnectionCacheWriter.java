package org.matsim.contrib.demand_extraction.io;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.util.PackedKeyCodec;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Utility class for writing network connection cache to CSV files.
 *
 * Supports three export modes (configurable via ExMasConfigGroup.connectionCacheExportMode):
 *
 * - "window" (default): Exports only the OD/bin segments the predecessor/successor pass
 *   actually evaluated (accepted AND rejected handoffs) — the lookup domain of Python's
 *   compute_dynamic_successors. The window-key set is collected by RidePostProcessor and the
 *   rows are promoted-to-retained (Task 7), so eviction never drops them.
 *
 * - "all": Exports ALL cached connections from the network cache. Debug-only — the full
 *   speculative+retained footprint, much larger than the window domain.
 *
 * - "successors_only": Exports only connections between top-K-capped successor ride pairs.
 *   Legacy behavior producing the smallest file; insufficient for dynamic successor
 *   computation in Python when the active ride set differs from pre-computed successors.
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
	 * - "successors_only": only connections between top-K-capped successor ride pairs
	 *
	 * @param filename     output file path
	 * @param rides        list of ExMAS rides with successor relationships
	 * @param networkCache routing cache containing pre-computed connections
	 * @param timeBinSize  size of time bins in seconds (e.g. 900 for 15 min)
	 * @param exportMode   "window", "all" or "successors_only"
	 * @param windowKeys   packed OD/bin keys evaluated by the predecessor/successor pass
	 *                     ({@code RidePostProcessor.getWindowKeys()}); used only for "window" mode
	 * @throws IOException if writing fails
	 */
	public static void writeConnectionCache(
			String filename,
			List<Ride> rides,
			MatsimNetworkCache networkCache,
			int timeBinSize,
			String exportMode,
			LongOpenHashSet windowKeys) throws IOException {

		switch (exportMode) {
			case "all" -> networkCache.exportAllEntries(filename);
			case "successors_only" -> networkCache.exportWindow(
					filename, collectSuccessorConnections(rides, timeBinSize));
			case "window" -> networkCache.exportWindow(filename, windowKeys);
			default -> throw new IllegalArgumentException(
					"Unknown connectionCacheExportMode '" + exportMode
							+ "' (allowed: window|all|successors_only)");
		}
		log.info("Wrote connection cache (mode={}) to: {}", exportMode, filename);
	}

	/**
	 * Collect packed OD/bin keys for all top-K-capped successor relationships — the only
	 * connections needed by the Python optimizer for empty-vehicle routing under the
	 * "successors_only" export mode. Keys are packed via {@link PackedKeyCodec} using the same
	 * time-bin convention as the cache, so {@code exportWindow} resolves the cached values.
	 *
	 * @param rides       list of rides with successor relationships
	 * @param timeBinSize size of time bins in seconds
	 * @return packed segment keys for every successor handoff
	 */
	private static LongOpenHashSet collectSuccessorConnections(List<Ride> rides, int timeBinSize) {
		LongOpenHashSet connectionKeys = new LongOpenHashSet();
		Map<Integer, Ride> rideMap = new HashMap<>();
		for (Ride ride : rides) {
			rideMap.put(ride.getIndex(), ride);
		}

		for (Ride ride : rides) {
			int[] successors = ride.getSuccessors();
			if (successors == null || successors.length == 0) continue;

			Id<Link>[] dests = ride.getDestinationsOrdered();
			if (dests.length == 0) continue;
			Id<Link> fromLink = dests[dests.length - 1];
			double endTime = ride.getEndTime();
			int timeBin = (int) (endTime / timeBinSize);

			for (int succIdx : successors) {
				Ride succRide = rideMap.get(succIdx);
				if (succRide != null) {
					Id<Link>[] origins = succRide.getOriginsOrdered();
					if (origins.length > 0) {
						connectionKeys.add(PackedKeyCodec.segmentKey(
								fromLink.index(), origins[0].index(), timeBin));
					}
				}
			}
		}
		return connectionKeys;
	}
}
