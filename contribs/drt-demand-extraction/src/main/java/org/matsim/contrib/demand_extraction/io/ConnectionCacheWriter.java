package org.matsim.contrib.demand_extraction.io;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.domain.TravelSegment;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.core.utils.misc.Counter;

/**
 * Utility class for writing network connection cache to CSV files.
 * 
 * Exports only the network connections needed for empty vehicle kilometer
 * calculation in the optimization pipeline. This is a MINIMAL subset of
 * the network - only connections between successor ride destinations
 * and the origins of their following rides.
 * 
 * Format: CSV with columns origin, destination, travel_time, distance
 * 
 * The Python optimization uses this to calculate empty VKT when vehicles
 * travel between rides without passengers.
 */
public final class ConnectionCacheWriter {

	private ConnectionCacheWriter() {
		// Utility class - prevent instantiation
	}

	/**
	 * Record to hold a connection key for deduplication.
	 * Uses link IDs as origin and destination, plus time bin.
	 */
	private record ConnectionKey(String origin, String destination, double time) {
	}

	/**
	 * Write connection cache to CSV file.
	 * 
	 * This exports network segments between ride destinations and
	 * successor ride origins - the minimal data needed for empty VKT calculation.
	 * 
	 * For each ride with successors:
	 * - Get the last destination of this ride (where vehicle ends)
	 * - For each successor ride, get its first origin (where vehicle starts next)
	 * - Route between these points and record distance/time
	 * 
	 * @param filename     output file path
	 * @param rides        list of ExMAS rides with successor relationships
	 * @param networkCache routing cache for distance/time lookups
	 * @param timeBinSize  size of time bins in seconds (e.g. 900 for 15 min)
	 * @throws RuntimeException if writing fails
	 */
	public static void writeConnectionCache(
			String filename,
			List<Ride> rides,
			MatsimNetworkCache networkCache,
			double timeBinSize) {

		// Build map from ride index to ride for quick lookup
		java.util.Map<Integer, Ride> rideMap = new java.util.HashMap<>();
		for (Ride ride : rides) {
			rideMap.put(ride.getIndex(), ride);
		}

		// Collect unique connections (deduplicate by origin, destination AND time bin)
		Set<ConnectionKey> seenConnections = new HashSet<>();
		java.util.List<ConnectionData> connections = new java.util.ArrayList<>();

		Counter counter = new Counter("Processing rides for connections: ", " rides");

		for (Ride ride : rides) {
			counter.incCounter();

			int[] successors = ride.getSuccessors();
			if (successors == null || successors.length == 0) {
				continue;
			}

			// Get the last destination of this ride (vehicle ends here)
			Id<Link>[] destinations = ride.getDestinationsOrdered();
			if (destinations == null || destinations.length == 0) {
				continue;
			}
			Id<Link> fromDest = destinations[destinations.length - 1];
			
			// Determine time bin based on ride end time
			// We use the start of the time bin as the key
			double rideEndTime = ride.getEndTime();
			double timeBinStart = Math.floor(rideEndTime / timeBinSize) * timeBinSize;

			// For each successor, get its first origin (vehicle starts here next)
			for (int succIdx : successors) {
				Ride succRide = rideMap.get(succIdx);
				if (succRide == null) {
					continue;
				}

				Id<Link>[] succOrigins = succRide.getOriginsOrdered();
				if (succOrigins == null || succOrigins.length == 0) {
					continue;
				}
				Id<Link> toOrigin = succOrigins[0];

				// Create key for deduplication
				ConnectionKey key = new ConnectionKey(fromDest.toString(), toOrigin.toString(), timeBinStart);
				if (seenConnections.contains(key)) {
					continue;
				}
				seenConnections.add(key);

				// Route the connection
				// Use timeBinStart as the departure time for routing
				TravelSegment segment = networkCache.getSegment(fromDest, toOrigin, timeBinStart);
				if (segment != null && segment.isReachable()) {
					connections.add(new ConnectionData(
							fromDest.toString(),
							toOrigin.toString(),
							timeBinStart,
							segment.getTravelTime(),
							segment.getDistance()));
				}
			}
		}
		counter.printCounter();

		// Write to CSV
		try (BufferedWriter writer = IOUtils.getBufferedWriter(filename)) {
			writer.write("origin,destination,departure_time,travel_time,distance");
			writer.newLine();

			for (ConnectionData conn : connections) {
				writer.write(String.format(java.util.Locale.US,
						"%s,%s,%.1f,%.2f,%.2f",
						conn.origin,
						conn.destination,
						conn.departureTime,
						conn.travelTime,
						conn.distance));
				writer.newLine();
			}
		} catch (IOException e) {
			throw new RuntimeException("Failed to write connection cache", e);
		}
		
		System.out.println("Wrote " + connections.size() + " unique connections to " + filename);
	}

	/**
	 * Data holder for a connection between two points.
	 */
	private record ConnectionData(
			String origin,
			String destination,
			double departureTime,
			double travelTime,
			double distance) {
	}
}
