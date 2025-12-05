package org.matsim.contrib.demand_extraction.demand;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.population.Population;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.engine.ExMasEngine;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.io.ExMasCsvWriter;
import org.matsim.core.config.Config;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;

import com.google.inject.Inject;
import com.google.inject.Singleton;

@Singleton
public class DemandExtractionListener implements IterationEndsListener {
	private static final Logger log = LogManager.getLogger(DemandExtractionListener.class);

    private final ModeRoutingCache modeRoutingCache;
    private final ChainIdentifier chainIdentifier;
	private final DrtRequestFactory budgetCalculator;
    private final Population population;
	private final ExMasConfigGroup exMasConfig;
	private final Config config;
	private final MatsimNetworkCache networkCache;
	private final BudgetValidator budgetValidator;

    @Inject
    public DemandExtractionListener(ModeRoutingCache modeRoutingCache, ChainIdentifier chainIdentifier,
			DrtRequestFactory budgetCalculator, Population population, ExMasConfigGroup exMasConfig, Config config,
			MatsimNetworkCache networkCache,
			BudgetValidator budgetValidator) {
        this.modeRoutingCache = modeRoutingCache;
        this.chainIdentifier = chainIdentifier;
        this.budgetCalculator = budgetCalculator;
        this.population = population;
		this.exMasConfig = exMasConfig;
		this.config = config;
		this.networkCache = networkCache;
		this.budgetValidator = budgetValidator;
    }

    @Override
    public void notifyIterationEnds(IterationEndsEvent event) {
        if (event.getIteration() == event.getServices().getConfig().controller().getLastIteration()) {

			// 0. Configure DRT to maximum service quality for budget calculation
			// This sets the DRT service to the best acceptable parameters so that the
			// calculated utility budget represents the maximum willingness to pay.
			// The configuration logic has been moved to `DrtBudgetConfigurator` so it
			// can be reused later (e.g., to reset to optimized parameters).
			DrtBudgetConfigurator.configureDrtForBudgetCalculation(config, exMasConfig);

			// 1. Cache Modes (with mode availability filtering based on person attributes)
            modeRoutingCache.cacheModes(population);

			// 2. Identify Chains (hierarchical subtours with private vehicle detection)
			chainIdentifier.identifyChains(population);

			// 3. Calculate Budgets (trip-wise with linking for subtours using private
			// vehicles)
			List<DrtRequest> requests = budgetCalculator.buildRequests(population);

			// 4. Generate ExMAS Rides (with budget validation)
			log.info("Running ExMAS ride generation...");
			ExMasEngine exmasEngine = new ExMasEngine(
					networkCache, 
					budgetValidator, 
					exMasConfig.getSearchHorizon(),
					exMasConfig.getMaxPoolingDegree()
				);
			List<Ride> rides = exmasEngine.run(requests);
			log.info("Generated {} total rides", rides.size());

            // 5. Write DRT Requests Output
			String requestsFilename = event.getServices().getControllerIO().getOutputFilename("drt_requests.csv");
			ExMasCsvWriter.writeRequests(requestsFilename, requests);
			
			// 6. Write ExMAS Rides Output
			String ridesFilename = event.getServices().getControllerIO().getOutputFilename("exmas_rides.csv");
			ExMasCsvWriter.writeRides(ridesFilename, rides);
        }
    }
}
