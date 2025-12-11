package org.matsim.contrib.demand_extraction.io;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;
import org.matsim.core.utils.io.IOUtils;

/**
 * Utility class for writing ExMAS data to CSV files.
 *
 * Uses ' | ' as separator for array values within CSV fields and keeps brackets
 * for clarity during parsing: [1, 2, 3] -> [1 | 2 | 3]
 *
 * For rides, request attributes are flattened into arrays, matching the
 * structure of other ride attributes (delays, travel times, etc.).
 */
public final class ExMasCsvWriter {

	// Separator for array values within CSV fields: ' | '
	private static final String ARRAY_SEPARATOR = " | ";

	private ExMasCsvWriter() {
		// Utility class - prevent instantiation
	}

	/**
	 * Write DRT requests to CSV file.
	 *
	 * @param filename output file path
	 * @param requests list of DRT requests
	 * @throws RuntimeException if writing fails
	 */
	public static void writeRequests(String filename, List<DrtRequest> requests) {
		try (BufferedWriter writer = IOUtils.getBufferedWriter(filename)) {
			// Header includes all request attributes
			writer.write("index,personId,groupId,tripIndex,isCommute,budget,requestTime," +
					"originLinkId,destinationLinkId,originX,originY,destinationX,destinationY," +
					"directTravelTime,directDistance,earliestDeparture,latestArrival," +
					"maxTravelTime,maxPositiveDelay,maxNegativeDelay,bestModeScore,bestMode," +
					"carTravelTime,ptTravelTime,ptAccessibility");
			writer.newLine();

			for (DrtRequest req : requests) {
				writer.write(String.format(java.util.Locale.US,
						"%d,%s,%s,%d,%b,%.4f,%.2f,%s,%s,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.4f,%s,%.2f,%.2f,%.4f",
						req.index, req.personId, req.groupId, req.tripIndex, req.isCommute,
						req.budget, req.requestTime,
						req.originLinkId, req.destinationLinkId,
						req.originX, req.originY, req.destinationX, req.destinationY,
						req.directTravelTime, req.directDistance,
						req.earliestDeparture, req.latestArrival,
						req.getMaxTravelTime(), req.getMaxPositiveDelay(), req.getMaxNegativeDelay(),
						req.bestModeScore, req.bestMode != null ? req.bestMode : "",
						req.carTravelTime, req.ptTravelTime, req.ptAccessibility));
				writer.newLine();
			}
		} catch (IOException e) {
			throw new RuntimeException("Could not write requests CSV: " + filename, e);
		}
	}

	/**
	 * Write ExMAS rides to CSV file.
	 *
	 * Array fields are formatted as: [val1 | val2 | val3]
	 * This keeps brackets for clarity while using ' | ' as internal separator.
	 *
	 * Request attributes are flattened into arrays with the same order as the
	 * requests array in the ride:
	 * - requestIndices: indices of all passengers
	 * - personIds: person IDs of all passengers
	 * - groupIds: group IDs of all passengers
	 * - requestTimes: request times of all passengers
	 *
	 * @param filename output file path
	 * @param rides    list of ExMAS rides
	 * @throws RuntimeException if writing fails
	 */
	public static void writeRides(String filename, List<Ride> rides) {
		try (BufferedWriter writer = IOUtils.getBufferedWriter(filename)) {
			// Header with all ride attributes and flattened request attributes
			writer.write("rideIndex,degree,kind," +
					"requestIndices,personIds,groupIds,requestTimes,isCommutes," +
					"originsOrdered,destinationsOrdered," +
					"passengerTravelTimes,passengerDistances,delays,detours,remainingBudgets,maxCosts,shapleyValues,predecessors,successors," +
					"startTime,endTime,rideTravelTime,rideDistance");
			writer.newLine();

			for (Ride ride : rides) {
				// Flatten request attributes using direct object references
				DrtRequest[] requests = ride.getRequests();

				String reqIndices = formatIntArray(ride.getRequestIndices());
				String personIds = formatStringArray(Arrays.stream(requests)
						.map(r -> r.personId.toString())
						.toArray(String[]::new));
				String groupIds = formatStringArray(Arrays.stream(requests)
						.map(r -> r.groupId)
						.toArray(String[]::new));
				String requestTimes = formatDoubleArray(Arrays.stream(requests)
						.mapToDouble(r -> r.requestTime)
						.toArray());
				String isCommutes = formatBooleanArray(Arrays.stream(requests)
						.map(r -> r.isCommute)
						.toArray(Boolean[]::new));

				// Format origin/destination sequences
				String origins = formatLinkIdArray(ride.getOriginsOrdered());
				String destinations = formatLinkIdArray(ride.getDestinationsOrdered());

				// Format other arrays
				String pttimes = formatDoubleArray(ride.getPassengerTravelTimes());
				String pdists = formatDoubleArray(ride.getPassengerDistances());
				String delays = formatDoubleArray(ride.getDelays());
				String detours = formatDoubleArray(ride.getDetours());
				String budgets = ride.getRemainingBudgets() != null
						? formatDoubleArray(ride.getRemainingBudgets())
						: "[]";
				String maxCosts = ride.getMaxCosts() != null ? formatDoubleArray(ride.getMaxCosts()) : "[]";
				String shapleyValues = ride.getShapleyValues() != null ? formatDoubleArray(ride.getShapleyValues()) : "[]";
				String predecessors = ride.getPredecessors() != null ? formatIntArray(ride.getPredecessors()) : "[]";
				String successors = ride.getSuccessors() != null ? formatIntArray(ride.getSuccessors()) : "[]";
				writer.write(String.format(java.util.Locale.US,
						"%d,%d,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%.2f,%.2f,%.2f,%.2f",
						ride.getIndex(), ride.getDegree(), ride.getKind(),
						reqIndices, personIds, groupIds, requestTimes, isCommutes,
						origins, destinations,
						pttimes, pdists, delays, detours, budgets, maxCosts, shapleyValues, predecessors, successors,
						ride.getStartTime(), ride.getEndTime(),
						ride.getRideTravelTime(), ride.getRideDistance()));
				writer.newLine();
			}
		} catch (IOException e) {
			throw new RuntimeException("Could not write rides CSV: " + filename, e);
		}
	}

	/**
	 * Format integer array for CSV output: [1, 2, 3] -> [1 | 2 | 3]
	 */
	private static String formatIntArray(int[] array) {
		if (array == null || array.length == 0) {
			return "[]";
		}

		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < array.length; i++) {
			if (i > 0) {
				sb.append(ARRAY_SEPARATOR);
			}
			sb.append(array[i]);
		}
		sb.append("]");
		return sb.toString();
	}

	/**
	 * Format double array for CSV output: [1.5, 2.7, 3.9] -> [1.5 | 2.7 | 3.9]
	 */
	private static String formatDoubleArray(double[] array) {
		if (array == null || array.length == 0) {
			return "[]";
		}

		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < array.length; i++) {
			if (i > 0) {
				sb.append(ARRAY_SEPARATOR);
			}
			sb.append(String.format(java.util.Locale.US, "%.2f", array[i]));
		}
		sb.append("]");
		return sb.toString();
	}

	/**
	 * Format string array for CSV output: [a, b, c] -> [a | b | c]
	 */
	private static String formatStringArray(String[] array) {
		if (array == null || array.length == 0) {
			return "[]";
		}

		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < array.length; i++) {
			if (i > 0) {
				sb.append(ARRAY_SEPARATOR);
			}
			sb.append(array[i]);
		}
		sb.append("]");
		return sb.toString();
	}

	/**
	 * Format boolean array for CSV output: [true, false] -> [true | false]
	 */
	private static String formatBooleanArray(Boolean[] array) {
		if (array == null || array.length == 0) {
			return "[]";
		}

		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < array.length; i++) {
			if (i > 0) {
				sb.append(ARRAY_SEPARATOR);
			}
			sb.append(array[i]);
		}
		sb.append("]");
		return sb.toString();
	}

	/**
	 * Format Link ID array for CSV output: [link1, link2] -> [link1 | link2]
	 */
	private static String formatLinkIdArray(Id<Link>[] array) {
		if (array == null || array.length == 0) {
			return "[]";
		}

		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < array.length; i++) {
			if (i > 0) {
				sb.append(ARRAY_SEPARATOR);
			}
			sb.append(array[i].toString());
		}
		sb.append("]");
		return sb.toString();
	}
}
