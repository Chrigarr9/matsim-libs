package org.matsim.contrib.demand_extraction.io;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.List;

import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;
import org.matsim.core.utils.io.IOUtils;

/**
 * Utility class for writing ExMAS data to CSV files.
 * 
 * Uses ' | ' as separator for array values within CSV fields and keeps brackets
 * for clarity during parsing: [1, 2, 3] -> [1 | 2 | 3]
 * 
 * This format makes it clear that values belong to an array while maintaining
 * CSV compatibility (comma remains the field delimiter).
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
			writer.write("personId,groupId,tripIndex,budget,requestTime,originX,originY,destinationX,destinationY");
			writer.newLine();

			for (DrtRequest req : requests) {
				writer.write(String.format(java.util.Locale.US, "%s,%s,%d,%.4f,%.2f,%.2f,%.2f,%.2f,%.2f",
						req.personId, req.groupId, req.tripIndex, req.budget, req.requestTime,
						req.originX, req.originY, req.destinationX, req.destinationY));
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
	 * @param filename output file path
	 * @param rides    list of ExMAS rides
	 * @throws RuntimeException if writing fails
	 */
	public static void writeRides(String filename, List<Ride> rides) {
		try (BufferedWriter writer = IOUtils.getBufferedWriter(filename)) {
			writer.write("rideIndex,degree,kind,requestIndices,startTime,duration,distance,delays,remainingBudgets");
			writer.newLine();

			for (Ride ride : rides) {
				// Format arrays with ' | ' separator and keep brackets
				String reqIndices = formatIntArray(ride.getRequestIndices());
				String delays = formatDoubleArray(ride.getDelays());
				String budgets = ride.getRemainingBudgets() != null
						? formatDoubleArray(ride.getRemainingBudgets())
						: "";

				writer.write(String.format(java.util.Locale.US, "%d,%d,%s,%s,%.2f,%.2f,%.2f,%s,%s",
						ride.getIndex(), ride.getDegree(), ride.getKind(), reqIndices,
						ride.getStartTime(), ride.getRideTravelTime(), ride.getRideDistance(),
						delays, budgets));
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
}
