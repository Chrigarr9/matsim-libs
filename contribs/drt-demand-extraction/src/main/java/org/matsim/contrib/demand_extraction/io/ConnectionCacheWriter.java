package org.matsim.contrib.demand_extraction.io;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;

/**
 * Utility class for writing network connection cache to CSV files.
 *
 * Supports two export modes (configurable via ExMasConfigGroup.connectionCacheExportMode):
 *
 * - "all" (default): Exports ALL cached connections from the network cache.
 *   This provides complete coverage for Python's dynamic successor computation,
 *   which needs travel times for arbitrary ride pairs (not just pre-computed successors).
 *
 * - "successors_only": Exports only connections between successor ride pairs.
 *   Legacy behavior producing a smaller file, but insufficient for dynamic successor
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
	 * - "all": exports the full network cache (all routed OD pairs)
	 * - "successors_only": exports only connections between successor ride pairs
	 *
	 * @param filename     output file path
	 * @param rides        list of ExMAS rides with successor relationships
	 * @param networkCache routing cache containing pre-computed connections
	 * @param timeBinSize  size of time bins in seconds (e.g. 900 for 15 min)
	 * @param exportMode   "all" or "successors_only"
	 * @throws IOException if writing fails
	 */
	public static void writeConnectionCache(
			String filename,
			List<Ride> rides,
			MatsimNetworkCache networkCache,
			int timeBinSize,
			String exportMode) throws IOException {

		// null filter = export all; non-null = export only those keys
		Set<String> filter = "successors_only".equals(exportMode)
				? collectSuccessorConnections(rides, timeBinSize)
				: null;

		networkCache.exportConnectionCache(filename, filter);
		log.info("Wrote connection cache (mode={}) to: {}", exportMode, filename);
	}

	/**
	 * Collect unique connection keys (origin_destination_timeBin) for all successor relationships.
	 * These are the only connections needed by the Python optimizer for empty vehicle routing
	 * when using the "successors_only" export mode.
	 *
	 * @param rides       list of rides with successor relationships
	 * @param timeBinSize size of time bins in seconds
	 * @return set of connection keys in format "originLinkId_destLinkId_timeBin"
	 */
	private static Set<String> collectSuccessorConnections(List<Ride> rides, int timeBinSize) {
		Set<String> connectionKeys = new HashSet<>();
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
						connectionKeys.add(fromLink + "_" + origins[0] + "_" + timeBin);
					}
				}
			}
		}
		return connectionKeys;
	}
}
