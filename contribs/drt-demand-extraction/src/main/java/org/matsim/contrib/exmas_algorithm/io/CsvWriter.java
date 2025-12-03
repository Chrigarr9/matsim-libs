package org.matsim.contrib.exmas_algorithm.io;

import com.exmas.ridesharing.domain.Ride;
import java.io.*;
import java.util.List;

/**
 * CSV writer for ride output.
 */
public final class CsvWriter {

    public static void writeRides(List<Ride> rides, String filename) throws IOException {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(filename))) {
            // Header
            bw.write("index,degree,kind,request_index,origins_ordered,destinations_ordered,");
            bw.write("origins_index,destinations_index,passenger_travel_time,passenger_distance,");
            bw.write("passenger_network_utility,delay,connection_travel_time,connection_distance,");
            bw.write("connection_network_utility,ride_travel_time,ride_distance,start_time,end_time\n");

            for (Ride ride : rides) {
                bw.write(ride.getIndex() + ",");
                bw.write(ride.getDegree() + ",");
                bw.write(ride.getKind() + ",");
                bw.write(arrayToString(ride.getRequestIndices()) + ",");
                bw.write(arrayToString(ride.getOriginsOrdered()) + ",");
                bw.write(arrayToString(ride.getDestinationsOrdered()) + ",");
                bw.write(arrayToString(ride.getOriginsIndex()) + ",");
                bw.write(arrayToString(ride.getDestinationsIndex()) + ",");
                bw.write(arrayToString(ride.getPassengerTravelTimes()) + ",");
                bw.write(arrayToString(ride.getPassengerDistances()) + ",");
                bw.write(arrayToString(ride.getPassengerNetworkUtilities()) + ",");
                bw.write(arrayToString(ride.getDelays()) + ",");
                bw.write(arrayToString(ride.getConnectionTravelTimes()) + ",");
                bw.write(arrayToString(ride.getConnectionDistances()) + ",");
                bw.write(arrayToString(ride.getConnectionNetworkUtilities()) + ",");
                bw.write(ride.getRideTravelTime() + ",");
                bw.write(ride.getRideDistance() + ",");
                bw.write(ride.getStartTime() + ",");
                bw.write(ride.getEndTime() + "\n");
            }
        }
    }

    private static String arrayToString(int[] arr) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(";");
            sb.append(arr[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    private static String arrayToString(double[] arr) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(";");
            sb.append(String.format("%.2f", arr[i]));
        }
        sb.append("]");
        return sb.toString();
    }
}
