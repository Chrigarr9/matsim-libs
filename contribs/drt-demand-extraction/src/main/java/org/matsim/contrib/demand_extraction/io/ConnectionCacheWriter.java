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
 * Exports only the network connections needed for empty vehicle kilometer
 * calculation in the optimization pipeline. This is a MINIMAL subset of
 * the network - only connections between successor ride destinations
 * and the origins of their following rides.
 * 
 * Format: CSV with columns origin,destination,time_bin,travel_time,distance
 * 
 * The Python optimization uses this to calculate empty VKT when vehicles
 * travel between rides without passengers.
 * 
 * EFFICIENCY NOTE: Connections are already routed during predecessor calculation
 * in RidePostProcessor, so this class filters the existing network cache instead
 * of re-routing. This is much faster than routing ~1500 connections again.
 */
public final class ConnectionCacheWriter {

	private static final Logger log = LogManager.getLogger(ConnectionCacheWriter.class);

	private ConnectionCacheWriter() {
		// Utility class - prevent instantiation
	}

	/**
	 * Write connection cache to CSV file.
	 * 
	 * This exports network segments between ride destinations and
	 * successor ride origins - the minimal data needed for empty VKT calculation.
	 * 
	 * EFFICIENCY: These connections were already routed during predecessor calculation,
	 * so we filter them from the existing cache rather than re-routing.
	 * 
	 * @param filename     output file path
	 * @param rides        list of ExMAS rides with successor relationships
	 * @param networkCache routing cache containing pre-computed connections
	 * @param timeBinSize  size of time bins in seconds (e.g. 900 for 15 min)
	 * @throws IOException if writing fails
	 */
	public static void writeConnectionCache(
			String filename,
			List<Ride> rides,
			MatsimNetworkCache networkCache,
			int timeBinSize) throws IOException {

		// Collect unique connection keys needed for successors
		Set<String> connectionKeys = collectSuccessorConnections(rides, timeBinSize);

		// Export filtered cache (these connections are already routed)
		networkCache.exportFilteredConnectionCache(filename, connectionKeys);

		log.info("Wrote connection cache to: {} ({} unique connections)", filename, connectionKeys.size());
	}

	/**
	 * Collect unique connection keys (origin_destination_timeBin) for all successor relationships.
	 * These are the only connections needed by the Python optimizer for empty vehicle routing.
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
		return connectionKeys;	}
}