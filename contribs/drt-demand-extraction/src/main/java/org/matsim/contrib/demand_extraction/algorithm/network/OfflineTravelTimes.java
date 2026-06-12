package org.matsim.contrib.demand_extraction.algorithm.network;

import java.net.URL;
import java.nio.file.Path;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.contrib.common.timeprofile.TimeDiscretizer;
import org.matsim.contrib.dvrp.trafficmonitoring.DvrpOfflineTravelTimes;
import org.matsim.core.router.util.TravelTime;

/**
 * Single loader for offline link travel times ({@code travel_times.tsv} written by
 * {@code TravelTimeExportListener} / {@link DvrpOfflineTravelTimes}). Replaces four
 * verbatim copies (Lyon fixture, Phase2Module, Phase2RoutingSetup, Bavaria runner) so
 * single-process, two-phase, and route-export runs route on byte-identical travel times.
 *
 * <p>Two clamps:
 * <ul>
 *   <li><b>Time:</b> queries past {@link #TRAVEL_TIME_END} read the last bin (legacy
 *       behavior, prevents out-of-range bin lookups).</li>
 *   <li><b>Freespeed:</b> {@code tt >= length/freespeed}. Physically sensible (nothing
 *       drives faster than free flow) and REQUIRED for determinism: SpeedyALT's landmark
 *       heuristic is built from {@code getLinkMinimumTravelDisutility} = freespeed time.
 *       A single TSV value below freespeed time makes the heuristic inadmissible, and an
 *       inadmissible A* can return a genuinely suboptimal path that LeastCostPathTree
 *       (exact Dijkstra) does not — the engines then disagree no matter how unique the
 *       optimum is.</li>
 * </ul>
 */
public final class OfflineTravelTimes {

	private static final Logger log = LogManager.getLogger(OfflineTravelTimes.class);

	/** 15-min bins — matches TravelTimeExportListener's export discretization. */
	public static final int TRAVEL_TIME_BIN_SIZE = 900;
	/** 36 h horizon. */
	public static final int TRAVEL_TIME_END = 36 * 3600;

	private OfflineTravelTimes() {}

	public static TravelTime load(String ttFile) {
		log.info("Loading pre-computed travel times from: {}", ttFile);
		TimeDiscretizer timeDiscretizer = new TimeDiscretizer(TRAVEL_TIME_END, TRAVEL_TIME_BIN_SIZE);
		try {
			URL ttUrl = Path.of(ttFile).toUri().toURL();
			double[][] matrix = DvrpOfflineTravelTimes.loadLinkTravelTimes(timeDiscretizer, ttUrl, "\t");
			TravelTime baseTt = DvrpOfflineTravelTimes.asTravelTime(timeDiscretizer, matrix);
			log.info("Bound pre-computed travel times ({} bins, time-clamped to {}h, freespeed-clamped)",
					timeDiscretizer.getIntervalCount(), TRAVEL_TIME_END / 3600);
			return (link, time, person, vehicle) -> Math.max(
					baseTt.getLinkTravelTime(link, Math.min(time, TRAVEL_TIME_END), person, vehicle),
					link.getLength() / link.getFreespeed());
		} catch (Exception e) {
			throw new RuntimeException("Failed to load offline travel times from " + ttFile, e);
		}
	}
}
