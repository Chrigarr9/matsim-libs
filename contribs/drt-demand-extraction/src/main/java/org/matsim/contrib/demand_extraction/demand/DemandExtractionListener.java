package org.matsim.contrib.demand_extraction.demand;

import java.io.IOException;
import java.nio.file.Files;
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
		String demandOutputDir = ensureDemandOutputDir();
		String personAttributesFilename = demandOutputDir + "/" + config.controller().getRunId()
				+ ".person_attributes.csv";
		PersonAttributesWriter.writePersonAttributes(personAttributesFilename, population, requests);
		log.info("Wrote person attributes to: {}", personAttributesFilename);

		String modeCacheFilename = demandOutputDir + "/" + config.controller().getRunId()
				+ ".mode_cache.csv";
		ExMasCsvWriter.writeModeCache(modeCacheFilename, modeRoutingCache.getAllModeAttributes());
		log.info("Wrote mode cache to: {}", modeCacheFilename);

		return requests;
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
		List<Ride> rides = algorithmResult.rides();

		// Post-process rides with advanced metrics (maxCost, Shapley, predecessors)
		RidePostProcessor.MaxCostResolver maxCostResolver = (budget, request, tt, dist) -> {
			Person person = population.getPersons().get(request.personId);
			if (person == null) return 0.0;
			return budgetToConstraintsCalculator.budgetToMaxCost(budget, person, tt, dist, request);
		};
		RidePostProcessor postProcessor = new RidePostProcessor(exMasConfig, networkCache, maxCostResolver);
		rides = postProcessor.process(rides);

		// 5. Write DRT Requests Output + rides + connection cache
		log.info("");
		log.info("STEP 5: Writing output files");
		log.info("----------------------------------------------------------------------");

		String demandOutputDir = ensureDemandOutputDir();

		String requestsFilename = demandOutputDir + "/" + config.controller().getRunId()
				+ ".drt_requests.csv";
		ExMasCsvWriter.writeRequests(requestsFilename, requests);
		log.info("Wrote {} requests to: {}", requests.size(), requestsFilename);

		String ridesFilename = demandOutputDir + "/" + config.controller().getRunId() + ".exmas_rides.csv";
		ExMasCsvWriter.writeRides(ridesFilename, rides);
		log.info("Wrote {} rides to: {}", rides.size(), ridesFilename);

		// HyperPool Stage-2 outputs use a distinct schema (multi-stop sequences,
		// per-pax boarding/alighting indices) and land in their own CSV.
		if (!algorithmResult.hyperPooledRides().isEmpty()) {
			String hyperPoolFilename = demandOutputDir + "/" + config.controller().getRunId()
					+ ".hyperpool_rides.csv";
			ExMasCsvWriter.writeHyperPooledRides(hyperPoolFilename, algorithmResult.hyperPooledRides());
			log.info("Wrote {} hyper-pooled rides to: {}",
					algorithmResult.hyperPooledRides().size(), hyperPoolFilename);
		}

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

		// Network cache statistics (cache hit rate, SSSP vs SpeedyALT breakdown)
		networkCache.logRoutingStatistics();

		return rides;
	}

	/** Wall-clock millis spent so far in Phase 1 (since the start of {@link #runPhase1()}). */
	protected long phase1ElapsedMillis() {
		return System.currentTimeMillis() - phase1StartTimeMs;
	}

	/** Resolves {@code <outputDir>/drt_demand} and ensures it exists. */
	protected String ensureDemandOutputDir() {
		String demandOutputDir = outputDirectory.getOutputPath() + "/drt_demand";
		try {
			Files.createDirectories(Paths.get(demandOutputDir));
		} catch (IOException e) {
			throw new RuntimeException("Failed to create drt_demand output directory", e);
		}
		return demandOutputDir;
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
