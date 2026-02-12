package org.matsim.contrib.demand_extraction.demand;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.network.NetworkUtils;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.engine.ExMasEngine;
import org.matsim.contrib.demand_extraction.algorithm.engine.RidePostProcessor;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.io.ConnectionCacheWriter;
import org.matsim.contrib.demand_extraction.io.ExMasCsvWriter;
import org.matsim.contrib.demand_extraction.io.PersonAttributesWriter;
import org.matsim.core.config.Config;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.controler.listener.ShutdownListener;

import com.google.inject.Inject;
import com.google.inject.Singleton;

@Singleton
public class DemandExtractionListener implements ShutdownListener {
	private static final Logger log = LogManager.getLogger(DemandExtractionListener.class);

    private final ModeRoutingCache modeRoutingCache;
    private final ChainIdentifier chainIdentifier;
	private final DrtRequestFactory requestFactory;
    private final Population population;
	private final ExMasConfigGroup exMasConfig;
	private final Config config;
	private final MatsimNetworkCache networkCache;
	private final BudgetValidator budgetValidator;
	private final BudgetToConstraintsCalculator budgetToConstraintsCalculator;
	private final OutputDirectoryHierarchy outputDirectory;
	private final RequestSampler requestSampler;

    @Inject
	public DemandExtractionListener(
			ModeRoutingCache modeRoutingCache,
			ChainIdentifier chainIdentifier,
			DrtRequestFactory requestFactory,
			Population population,
			ExMasConfigGroup exMasConfig,
			Config config,
			MatsimNetworkCache networkCache,
			BudgetValidator budgetValidator,
			BudgetToConstraintsCalculator budgetToConstraintsCalculator,
			OutputDirectoryHierarchy outputDirectory,
			RequestSampler requestSampler) {
        this.modeRoutingCache = modeRoutingCache;
        this.chainIdentifier = chainIdentifier;
        this.requestFactory = requestFactory;
        this.population = population;
		this.exMasConfig = exMasConfig;
		this.config = config;
		this.networkCache = networkCache;
		this.budgetValidator = budgetValidator;
		this.budgetToConstraintsCalculator = budgetToConstraintsCalculator;
		this.outputDirectory = outputDirectory;
		this.requestSampler = requestSampler;
    }

    @Override
	public void notifyShutdown(ShutdownEvent event) {
		log.info("======================================================================");
		log.info("STARTING ExMAS DEMAND EXTRACTION");
		log.info("======================================================================");
		long overallStartTime = System.currentTimeMillis();

		// 0. Configure DRT to maximum service quality for budget calculation
		log.info("");
		log.info("STEP 0: Configuring DRT for budget calculation");
		log.info("----------------------------------------------------------------------");
		DrtBudgetConfigurator.configureDrtForBudgetCalculation(config, exMasConfig);
		log.info("DRT configured to maximum service quality");

		// 1. Cache Modes (with mode availability filtering based on person attributes)
		log.info("");
		log.info("STEP 1: Caching mode alternatives");
		log.info("----------------------------------------------------------------------");
		modeRoutingCache.cacheModes(population);

		// 2. Identify Chains (hierarchical subtours with private vehicle detection)
		log.info("");
		log.info("STEP 2: Identifying trip chains");
		log.info("----------------------------------------------------------------------");
		chainIdentifier.identifyChains(population);

		// 3. Calculate Budgets (trip-wise with linking for subtours using private
		// vehicles)
		log.info("");
		log.info("STEP 3: Building DRT requests with budgets");
		log.info("----------------------------------------------------------------------");
		List<DrtRequest> requests = requestFactory.buildRequests(population);
		
		// Apply sampling if configured
		requests = requestSampler.sampleRequests(requests);

		// 4. Generate ExMAS Rides (with budget validation)
		log.info("");
		log.info("STEP 4: Running ExMAS ride generation algorithm");
		log.info("----------------------------------------------------------------------");
		ExMasEngine exmasEngine = new ExMasEngine(
			networkCache,
			budgetValidator,
			exMasConfig.getSearchHorizon(),
			exMasConfig.getMaxPoolingDegree(),
			exMasConfig);
		List<Ride> rides = exmasEngine.run(requests);

		// Post-process rides with advanced metrics (maxCost, Shapley, predecessors)
		RidePostProcessor postProcessor = new RidePostProcessor(exMasConfig, networkCache, budgetToConstraintsCalculator, population);
		rides = postProcessor.process(rides);

		// 5. Compute depot location and route depot connections
		log.info("");
		log.info("STEP 5: Computing depot connections for path cover fleet sizing");
		log.info("----------------------------------------------------------------------");
		Id<Link> depotLinkId = computeDepotAndRouteConnections(requests, rides);

		// 6. Write DRT Requests Output
		log.info("");
		log.info("STEP 6: Writing output files");
		log.info("----------------------------------------------------------------------");

		// Create subdirectory for demand extraction outputs
		String demandOutputDir = outputDirectory.getOutputPath() + "/drt_demand";
		try {
			Files.createDirectories(Paths.get(demandOutputDir));
		} catch (IOException e) {
			throw new RuntimeException("Failed to create drt_demand output directory", e);
		}

		String requestsFilename = demandOutputDir + "/" + config.controller().getRunId()
				+ ".drt_requests.csv";
		ExMasCsvWriter.writeRequests(requestsFilename, requests);
		log.info("Wrote {} requests to: {}", requests.size(), requestsFilename);

		// Write ExMAS Rides Output
		String ridesFilename = demandOutputDir + "/" + config.controller().getRunId() + ".exmas_rides.csv";
		ExMasCsvWriter.writeRides(ridesFilename, rides);
		log.info("Wrote {} rides to: {}", rides.size(), ridesFilename);

		// Write Person Attributes (for cluster analysis in Python)
		String personAttributesFilename = demandOutputDir + "/" + config.controller().getRunId()
				+ ".person_attributes.csv";
		PersonAttributesWriter.writePersonAttributes(personAttributesFilename, population, requests);
		log.info("Wrote person attributes to: {}", personAttributesFilename);

		// Write Mode Cache (for debugging mode choice issues)
		String modeCacheFilename = demandOutputDir + "/" + config.controller().getRunId()
				+ ".mode_cache.csv";
		ExMasCsvWriter.writeModeCache(modeCacheFilename, modeRoutingCache.getAllModeAttributes());
		log.info("Wrote mode cache to: {}", modeCacheFilename);

		// Write Connection Cache (includes depot connections routed in step 5)
		if (exMasConfig.isCalcPredecessors()) {
			String connectionCacheFilename = demandOutputDir + "/" + config.controller().getRunId()
					+ ".connection_cache.csv";
			try {
				ConnectionCacheWriter.writeConnectionCache(connectionCacheFilename, rides, networkCache,
						exMasConfig.getNetworkTimeBinSize(), exMasConfig.getConnectionCacheExportMode());
			} catch (IOException e) {
				log.error("Failed to write connection cache", e);
			}
		}

		// Write Depot metadata (for Python depot-based path cover)
		if (depotLinkId != null) {
			String depotFilename = demandOutputDir + "/" + config.controller().getRunId() + ".depot.csv";
			writeDepotMetadata(depotFilename, depotLinkId);
		}

		// Final summary
		long overallElapsed = System.currentTimeMillis() - overallStartTime;
		double overallSeconds = overallElapsed / 1000.0;
		log.info("");
		log.info("======================================================================");
		log.info("ExMAS DEMAND EXTRACTION COMPLETE");
		log.info("  Total requests: {}", requests.size());
		log.info("  Total rides: {}", rides.size());
		log.info("  Total time: {}s", String.format("%.1f", overallSeconds));
		log.info("  Output directory: {}", outputDirectory.getOutputPath());
		log.info("======================================================================");
    }

	/**
	 * Compute depot location and route all unique ride endpoints to/from depot.
	 *
	 * If depotLinkId is configured, uses that link. Otherwise, auto-computes the
	 * gravity center of all request coordinates and finds the nearest network link.
	 *
	 * Routes are cached in networkCache and automatically exported in the connection
	 * cache CSV (when using "all" export mode).
	 *
	 * @param requests list of DRT requests (for gravity center computation)
	 * @param rides    list of ExMAS rides (for unique endpoint collection)
	 * @return the depot link ID, or null if no rides/requests exist
	 */
	private Id<Link> computeDepotAndRouteConnections(List<DrtRequest> requests, List<Ride> rides) {
		if (requests.isEmpty() || rides.isEmpty()) {
			log.info("No requests or rides — skipping depot computation");
			return null;
		}

		// Determine depot link ID
		Id<Link> depotLink;
		if (exMasConfig.getDepotLinkId() != null && !exMasConfig.getDepotLinkId().isEmpty()) {
			depotLink = Id.createLinkId(exMasConfig.getDepotLinkId());
			log.info("Using configured depot link: {}", depotLink);
		} else {
			// Auto-compute from request gravity center
			double sumX = 0, sumY = 0;
			for (DrtRequest req : requests) {
				sumX += req.originX;
				sumY += req.originY;
			}
			Coord gravityCenter = new Coord(sumX / requests.size(), sumY / requests.size());
			Network net = networkCache.getNetwork();
			Link nearestLink = NetworkUtils.getNearestLink(net, gravityCenter);
			depotLink = nearestLink.getId();
			log.info("Auto-computed depot at link {} (gravity center: {}, {})",
					depotLink, String.format("%.1f", gravityCenter.getX()),
					String.format("%.1f", gravityCenter.getY()));
		}

		// Collect unique endpoint link IDs from all rides
		Set<Id<Link>> uniqueEndpoints = new HashSet<>();
		for (Ride ride : rides) {
			Id<Link>[] origins = ride.getOriginsOrdered();
			Id<Link>[] dests = ride.getDestinationsOrdered();
			if (origins.length > 0) {
				uniqueEndpoints.add(origins[0]);              // first pickup
			}
			if (dests.length > 0) {
				uniqueEndpoints.add(dests[dests.length - 1]); // last dropoff
			}
		}

		// Determine time bin range from ride start/end times
		int timeBinSize = exMasConfig.getNetworkTimeBinSize();
		double minTime = Double.MAX_VALUE;
		double maxTime = Double.MIN_VALUE;
		for (Ride ride : rides) {
			minTime = Math.min(minTime, ride.getStartTime());
			maxTime = Math.max(maxTime, ride.getEndTime());
		}
		int firstBin = (int) (minTime / timeBinSize);
		int lastBin = (int) (maxTime / timeBinSize);

		// Route all endpoints to/from depot at every time bin covering the ride period
		int numBins = lastBin - firstBin + 1;
		int endpointsExclDepot = (int) uniqueEndpoints.stream().filter(e -> !e.equals(depotLink)).count();
		int totalRoutes = endpointsExclDepot * numBins * 2;  // 2 directions per endpoint per bin
		int routeCount = 0;
		long routingStartNanos = System.nanoTime();

		log.info("Routing {} depot connections ({} endpoints x 2 directions x {} time bins)...",
				totalRoutes, endpointsExclDepot, numBins);

		for (int bin = firstBin; bin <= lastBin; bin++) {
			// Use bin midpoint as canonical departure time (matches MatsimNetworkCache)
			double time = (bin + 0.5) * timeBinSize;
			for (Id<Link> endpoint : uniqueEndpoints) {
				if (!endpoint.equals(depotLink)) {
					// Route: endpoint → depot (vehicle returns to depot after last dropoff)
					networkCache.getSegment(endpoint, depotLink, time);
					routeCount++;
					// Route: depot → endpoint (vehicle departs depot to first pickup)
					networkCache.getSegment(depotLink, endpoint, time);
					routeCount++;

					// Progress logging at powers of 2 (exponential spacing)
					if (routeCount == totalRoutes || ((routeCount & (routeCount - 1)) == 0)) {
						double elapsedSeconds = (System.nanoTime() - routingStartNanos) / 1e9;
						double remainingSeconds = routeCount <= 0 ? 0.0
								: (elapsedSeconds / routeCount) * (totalRoutes - routeCount);
						double percent = 100.0 * routeCount / totalRoutes;
						log.info("      Depot routing progress: {}/{} ({}%), ETA {}",
								routeCount, totalRoutes,
								String.format("%.1f", percent), formatDuration(remainingSeconds));
					}
				}
			}
		}

		double totalSeconds = (System.nanoTime() - routingStartNanos) / 1e9;
		log.info("Routed {} depot connections ({} unique endpoints x 2 directions x {} time bins) in {}",
				routeCount, endpointsExclDepot, numBins, formatDuration(totalSeconds));

		return depotLink;
	}

	/**
	 * Write depot metadata to a small CSV file for Python consumption.
	 *
	 * Format: depot_link_id,depot_x,depot_y
	 */
	private static String formatDuration(double seconds) {
		if (!Double.isFinite(seconds) || seconds <= 0) {
			return "0s";
		}
		long totalSeconds = (long) Math.ceil(seconds);
		long hours = TimeUnit.SECONDS.toHours(totalSeconds);
		long minutes = TimeUnit.SECONDS.toMinutes(totalSeconds) % 60;
		long secs = totalSeconds % 60;
		if (hours > 0) {
			return String.format("%dh%02dm%02ds", hours, minutes, secs);
		}
		if (minutes > 0) {
			return String.format("%dm%02ds", minutes, secs);
		}
		return String.format("%ds", secs);
	}

	private void writeDepotMetadata(String filename, Id<Link> depotLink) {
		Network net = networkCache.getNetwork();
		Link link = net.getLinks().get(depotLink);
		if (link == null) {
			log.warn("Depot link {} not found in network — skipping depot metadata write", depotLink);
			return;
		}
		Coord coord = link.getCoord();
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(filename))) {
			writer.write("depot_link_id,depot_x,depot_y");
			writer.newLine();
			writer.write(depotLink.toString() + "," + coord.getX() + "," + coord.getY());
			writer.newLine();
			log.info("Wrote depot metadata to: {}", filename);
		} catch (IOException e) {
			log.error("Failed to write depot metadata", e);
		}
	}

}
