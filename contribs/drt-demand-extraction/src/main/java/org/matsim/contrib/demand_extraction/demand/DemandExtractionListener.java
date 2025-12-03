package org.matsim.contrib.exmas.demand;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.List;

import org.matsim.api.core.v01.population.Population;
import org.matsim.contrib.exmas.config.ExMasConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.utils.io.IOUtils;

import com.google.inject.Inject;
import com.google.inject.Singleton;

@Singleton
public class DemandExtractionListener implements IterationEndsListener {

    private final ModeRoutingCache modeRoutingCache;
    private final ChainIdentifier chainIdentifier;
    private final BudgetCalculator budgetCalculator;
    private final Population population;
	private final ExMasConfigGroup exMasConfig;
	private final Config config;

    @Inject
    public DemandExtractionListener(ModeRoutingCache modeRoutingCache, ChainIdentifier chainIdentifier,
			BudgetCalculator budgetCalculator, Population population, ExMasConfigGroup exMasConfig, Config config) {
        this.modeRoutingCache = modeRoutingCache;
        this.chainIdentifier = chainIdentifier;
        this.budgetCalculator = budgetCalculator;
        this.population = population;
		this.exMasConfig = exMasConfig;
		this.config = config;
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
			chainIdentifier.identifyChains(population, modeRoutingCache.getBestBaselineModes());

			// 3. Calculate Budgets (trip-wise with linking for subtours using private
			// vehicles)
            List<DrtRequest> requests = budgetCalculator.calculateBudgets(population);

            // 4. Write Output
			String filename = event.getServices().getControllerIO().getOutputFilename("drt_requests.csv");
            try (BufferedWriter writer = IOUtils.getBufferedWriter(filename)) {
                writer.write(
                        "personId,groupId,tripIndex,budget,departureTime,originX,originY,destinationX,destinationY");
                writer.newLine();
                for (DrtRequest req : requests) {
					writer.write(String.format(java.util.Locale.US, "%s,%s,%d,%.4f,%.2f,%.2f,%.2f,%.2f,%.2f",
                            req.personId, req.groupId, req.tripIndex, req.budget, req.departureTime,
                            req.originX, req.originY, req.destinationX, req.destinationY));
                    writer.newLine();
                }
            } catch (IOException e) {
                throw new RuntimeException("Could not write drt_requests.csv", e);
            }
        }
    }
}
