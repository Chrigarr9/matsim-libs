package org.matsim.contrib.demand_extraction.run;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.common.timeprofile.TimeDiscretizer;
import org.matsim.contrib.dvrp.trafficmonitoring.DvrpOfflineTravelTimes;
import org.matsim.contrib.dvrp.trafficmonitoring.TravelTimeUtils;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scenario.MutableScenario;
import org.matsim.core.scenario.ScenarioUtils;

/**
 * Export link travel times from a MATSim events file.
 *
 * <pre>
 * mvn exec:java -o -Dexec.mainClass="...RunExportTravelTimes" \
 *   -Dexec.args="--network path/to/network.xml.gz \
 *                --events path/to/events.xml.gz \
 *                --output path/to/travel_times.tsv \
 *                [--bin-size 900]" \
 *   -Denforcer.skip=true
 * </pre>
 */
public class RunExportTravelTimes {

	private static final Logger log = LogManager.getLogger(RunExportTravelTimes.class);

	public static void main(String[] args) {
		String networkPath = null;
		String eventsPath = null;
		String outputPath = null;
		int binSize = 900; // 15 min

		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--network" -> networkPath = args[++i];
				case "--events" -> eventsPath = args[++i];
				case "--output" -> outputPath = args[++i];
				case "--bin-size" -> binSize = Integer.parseInt(args[++i]);
				default -> log.warn("Unknown argument: {}", args[i]);
			}
		}

		if (networkPath == null || eventsPath == null || outputPath == null) {
			System.err.println("Usage: RunExportTravelTimes "
					+ "--network <path> --events <path> --output <path> [--bin-size <seconds>]");
			System.exit(1);
		}

		log.info("Exporting travel times from events");
		log.info("  Network: {}", networkPath);
		log.info("  Events:  {}", eventsPath);
		log.info("  Output:  {}", outputPath);
		log.info("  Bin size: {}s", binSize);

		// Load network
		Config config = ConfigUtils.createConfig();
		config.global().setCoordinateSystem("Atlantis");
		MutableScenario scenario = (MutableScenario) ScenarioUtils.createScenario(config);
		new MatsimNetworkReader(scenario.getNetwork()).readFile(networkPath);
		Network network = scenario.getNetwork();
		log.info("Loaded network: {} links", network.getLinks().size());

		// Extract travel times from events
		TravelTime travelTime = TravelTimeUtils.createTravelTimesFromEvents(network, config, eventsPath);
		log.info("Travel times extracted from events");

		// Convert to matrix and export
		int endTime = 36 * 3600; // 36h
		TimeDiscretizer timeDiscretizer = new TimeDiscretizer(endTime, binSize);

		double[][] matrix = DvrpOfflineTravelTimes.convertToLinkTravelTimeMatrix(
				travelTime, network.getLinks().values(), timeDiscretizer);

		DvrpOfflineTravelTimes.saveLinkTravelTimes(
				timeDiscretizer, matrix, outputPath, "\t");

		log.info("Exported: {} links, {} time bins -> {}",
				network.getLinks().size(), timeDiscretizer.getIntervalCount(), outputPath);
	}
}
