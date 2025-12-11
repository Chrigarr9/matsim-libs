package org.matsim.contrib.demand_extraction.demand;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.population.Population;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.engine.ExMasEngine;
import org.matsim.contrib.demand_extraction.algorithm.engine.RidePostProcessor;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.BudgetToConstraintsCalculator;
import org.matsim.contrib.demand_extraction.io.ConnectionCacheWriter;
import org.matsim.contrib.demand_extraction.io.ExMasCsvWriter;
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
	private final DrtRequestFactory budgetCalculator;
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
			DrtRequestFactory budgetCalculator,
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
        this.budgetCalculator = budgetCalculator;
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
		List<DrtRequest> requests = budgetCalculator.buildRequests(population);
		
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
				exMasConfig.getMaxPoolingDegree());
		List<Ride> rides = exmasEngine.run(requests);

		// Post-process rides with advanced metrics (maxCost, Shapley, predecessors)
		RidePostProcessor postProcessor = new RidePostProcessor(exMasConfig, networkCache, budgetToConstraintsCalculator, population);
		rides = postProcessor.process(rides);

		// 5. Write DRT Requests Output
		log.info("");
		log.info("STEP 5: Writing output files");
		log.info("----------------------------------------------------------------------");
		String requestsFilename = outputDirectory.getOutputFilename("drt_requests.csv");
		ExMasCsvWriter.writeRequests(requestsFilename, requests);
		log.info("Wrote {} requests to: {}", requests.size(), requestsFilename);

		// 6. Write ExMAS Rides Output
		String ridesFilename = outputDirectory.getOutputFilename("exmas_rides.csv");
		ExMasCsvWriter.writeRides(ridesFilename, rides);
		log.info("Wrote {} rides to: {}", rides.size(), ridesFilename);

		// 7. Write Connection Cache (Optional - written when predecessors are calculated)
		if (exMasConfig.isCalcPredecessors()) {
			String connectionCacheFilename = outputDirectory.getOutputFilename("connection_cache.csv");
			log.info("Writing connection cache to: {}", connectionCacheFilename);
			// Use configured time bin size (default 900s = 15 min)
			double timeBinSize = exMasConfig.getNetworkTimeBinSize();
			ConnectionCacheWriter.writeConnectionCache(connectionCacheFilename, rides, networkCache, timeBinSize);
			log.info("Wrote connection cache");
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
}
