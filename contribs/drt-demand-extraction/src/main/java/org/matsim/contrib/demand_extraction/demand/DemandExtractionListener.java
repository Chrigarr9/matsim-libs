package org.matsim.contrib.demand_extraction.demand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.contrib.demand_extraction.algorithm.AlgorithmResult;
import org.matsim.contrib.demand_extraction.algorithm.ExMasAlgorithm;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.engine.RidePostProcessor;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.io.ExtractionDataManager;
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
	protected final ExMasConfigGroup exMasConfig;
	protected final Config config;
	private final MatsimNetworkCache networkCache;
	private final BudgetValidator budgetValidator;
	private final BudgetToConstraintsCalculator budgetToConstraintsCalculator;
	protected final OutputDirectoryHierarchy outputDirectory;
	private final RequestSampler requestSampler;
	private final ExMasAlgorithm algorithm;

	private long phase1StartTimeMs;

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
			RequestSampler requestSampler,
			ExMasAlgorithm algorithm) {
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
		this.algorithm = algorithm;
    }

    @Override
	public void notifyShutdown(ShutdownEvent event) {
		log.info("======================================================================");
		log.info("STARTING ExMAS DEMAND EXTRACTION");
		log.info("======================================================================");
		long overallStartTime = System.currentTimeMillis();

		List<DrtRequest> requests = runPhase1();
		List<Ride> rides = runPhase2(requests);

		long overallElapsed = System.currentTimeMillis() - overallStartTime;
		logCompletionBanner(requests.size(), rides.size(), overallElapsed / 1000.0);
	}

	/**
	 * STEPS 0–3 plus the population-dependent CSV writers (person_attributes, mode_cache).
	 * Returns the constructed request list. Phase-1 subclasses (low-memory mode) call this
	 * and then dump the requests to disk; the single-process flow continues into
	 * {@link #runPhase2(List)}.
	 *
	 * <p>Also records {@code phase1StartTimeMs} for subclasses that want to report wall-time.
	 */
	protected List<DrtRequest> runPhase1() {
		phase1StartTimeMs = System.currentTimeMillis();

		// 0. Configure DRT to maximum service quality for budget calculation
		log.info("");
		log.info("STEP 0: Configuring DRT for budget calculation");
		log.info("----------------------------------------------------------------------");
		DrtBudgetConfigurator.configureDrtForBudgetCalculation(config, exMasConfig);
		log.info("DRT configured to maximum service quality");

		// 1. Cache Modes (pre-filtered to persons who have at least one trip in the DRT area)
		log.info("");
		log.info("STEP 1: Caching mode alternatives");
		log.info("----------------------------------------------------------------------");
		TripSpatialPreFilter spatialPreFilter = new TripSpatialPreFilter(exMasConfig);
		java.util.Collection<? extends org.matsim.api.core.v01.population.Person> personsToCache;
		if (spatialPreFilter.isActive()) {
			personsToCache = population.getPersons().values().stream()
					.filter(spatialPreFilter::isPersonEligible)
					.collect(Collectors.toList());
			log.info("Spatial pre-filter: {}/{} persons have at least one trip in the DRT area",
					personsToCache.size(), population.getPersons().size());
		} else {
			personsToCache = population.getPersons().values();
		}
		modeRoutingCache.cacheModes(personsToCache);

		// 2. Identify Chains (hierarchical subtours with private vehicle detection)
		log.info("");
		log.info("STEP 2: Identifying trip chains");
		log.info("----------------------------------------------------------------------");
		chainIdentifier.identifyChains(population);

		// 3. Calculate Budgets (trip-wise with linking for subtours using private vehicles)
		log.info("");
		log.info("STEP 3: Building DRT requests with budgets");
		log.info("----------------------------------------------------------------------");
		List<DrtRequest> requests = requestFactory.buildRequests(population);
		requests = requestSampler.sampleRequests(requests);

		// Write population-dependent outputs (Phase-1 owns these because Phase 2 has no Population).
		ExtractionDataManager dataManager = dataManager();
		Path personAttributesFile = dataManager.writePersonAttributes(population, requests);
		log.info("Wrote person attributes to: {}", personAttributesFile);

		Path modeCacheFile = dataManager.writeModeCache(modeRoutingCache.getAllModeAttributes());
		log.info("Wrote mode cache to: {}", modeCacheFile);

		// Paper-2 Ext-2: per-(commuter, hub) detour diagnostic from virtual-trip
		// expansion (drop reasons + hub-introduced detour vs traveller slack).
		writeConnectingDetourDiag(dataManager.path("connecting_detour_diag.csv").toString());

		return requests;
	}

	/**
	 * Paper-2 Ext-2: dump the per-(commuter, hub) detour diagnostic gathered during
	 * virtual-trip expansion. No-op when no expansion ran (e.g. the Kelheim path).
	 */
	private void writeConnectingDetourDiag(String filename) {
		DrtRequestFactory.ExpansionDropStats stats = requestFactory.getLastExpansionDropStats();
		if (stats == null || stats.detours.isEmpty()) {
			return;
		}
		StringBuilder sb = new StringBuilder(
				"personId,tripIndex,fleetSide,hubId,directTime,ruralLegTime,urbanLegTime,"
				+ "buffer,detourTime,slack,maxAbsDetour,kept,reason\n");
		for (DrtRequestFactory.HubDetour d : stats.detours) {
			sb.append(d.personId()).append(',').append(d.tripIndex()).append(',')
			  .append(d.fleetSide()).append(',').append(d.hubId()).append(',')
			  .append(d.directTime()).append(',').append(d.ruralLegTime()).append(',')
			  .append(d.urbanLegTime()).append(',').append(d.buffer()).append(',')
			  .append(d.detourTime()).append(',').append(d.slack()).append(',')
			  .append(d.maxAbsDetour()).append(',').append(d.kept()).append(',')
			  .append(d.reason()).append('\n');
		}
		try {
			Files.writeString(Paths.get(filename), sb.toString());
			log.info("Wrote {} connecting-detour diagnostic rows to: {}",
					stats.detours.size(), filename);
		} catch (IOException e) {
			log.warn("Failed to write connecting-detour diagnostic to {}: {}",
					filename, e.getMessage());
		}
	}

	/**
	 * STEP 4 (algorithm + post-process) plus STEP 5 (drt_requests, exmas_rides,
	 * connection_cache writers). Returns the post-processed ride list.
	 */
	protected List<Ride> runPhase2(List<DrtRequest> requests) {
		// 4. Generate Stage-1 Rides via the selected strategy (BAMAS or ExMAS reference).
		log.info("");
		log.info("STEP 4: Running {} ride generation algorithm", exMasConfig.getAlgorithm());
		log.info("----------------------------------------------------------------------");
		AlgorithmResult algorithmResult = algorithm.run(requests);

		// Post-process rides with advanced metrics (maxCost, Shapley, predecessors).
		// The algorithm hands back a RideStore (streaming ColumnarRideStore on the memory-critical
		// D2D path, MaterializedRideStore otherwise); the post-processor materializes through it.
		RidePostProcessor.MaxCostResolver maxCostResolver = (budget, request, tt, dist) -> {
			Person person = population.getPersons().get(request.personId);
			if (person == null) return 0.0;
			return budgetToConstraintsCalculator.budgetToMaxCost(budget, person, tt, dist, request);
		};
		RidePostProcessor postProcessor = new RidePostProcessor(exMasConfig, networkCache, maxCostResolver);
		List<Ride> rides = postProcessor.process(algorithmResult.rides());

		// 5. Write DRT Requests Output + rides + connection cache
		log.info("");
		log.info("STEP 5: Writing output files");
		log.info("----------------------------------------------------------------------");

		ExtractionDataManager dataManager = dataManager();

		Path requestsFile = dataManager.writeRequests(requests);
		log.info("Wrote {} requests to: {}", requests.size(), requestsFile);

		Path ridesFile = dataManager.writeRides(rides);
		log.info("Wrote {} rides to: {}", rides.size(), ridesFile);

		// HyperPool Stage-2 outputs use a distinct schema (multi-stop sequences,
		// per-pax boarding/alighting indices) and land in their own CSV.
		Path hyperPoolFile = dataManager.writeHyperPooledRides(algorithmResult.hyperPooledRides());
		if (hyperPoolFile != null) {
			log.info("Wrote {} hyper-pooled rides to: {}",
					algorithmResult.hyperPooledRides().size(), hyperPoolFile);
		}

		// Write Connection Cache (includes depot connections routed in step 5); no-op when
		// isCalcPredecessors() is off.
		dataManager.writeConnectionCache(rides, networkCache, postProcessor.getWindowKeys());

		// Network cache statistics (cache hit rate, SSSP vs SpeedyALT breakdown)
		networkCache.logRoutingStatistics();

		return rides;
	}

	/** Wall-clock millis spent so far in Phase 1 (since the start of {@link #runPhase1()}). */
	protected long phase1ElapsedMillis() {
		return System.currentTimeMillis() - phase1StartTimeMs;
	}

	/**
	 * Build the output-file manager for {@code <outputDir>/drt_demand} under this run's id. Owns the
	 * filename convention and writer wiring previously inlined here and in {@code RunDemandExtractionPhase2}.
	 */
	protected ExtractionDataManager dataManager() {
		try {
			return ExtractionDataManager.forOutputDir(
					Paths.get(outputDirectory.getOutputPath()), config.controller().getRunId(), exMasConfig);
		} catch (IOException e) {
			throw new RuntimeException("Failed to create drt_demand output directory", e);
		}
	}

	protected void logCompletionBanner(int numRequests, int numRides, double overallSeconds) {
		log.info("");
		log.info("======================================================================");
		log.info("ExMAS DEMAND EXTRACTION COMPLETE");
		log.info("  Total requests: {}", numRequests);
		log.info("  Total rides: {}", numRides);
		log.info("  Total time: {}s", String.format("%.1f", overallSeconds));
		log.info("  Output directory: {}", outputDirectory.getOutputPath());
		log.info("======================================================================");
	}
}
