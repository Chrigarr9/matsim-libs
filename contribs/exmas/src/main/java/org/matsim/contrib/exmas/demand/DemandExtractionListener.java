package org.matsim.contrib.exmas.demand;

import org.matsim.api.core.v01.population.Population;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.utils.io.IOUtils;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.List;

@Singleton
public class DemandExtractionListener implements IterationEndsListener {

    private final ModeRoutingCache modeRoutingCache;
    private final ChainIdentifier chainIdentifier;
    private final BudgetCalculator budgetCalculator;
    private final Population population;

    @Inject
    public DemandExtractionListener(ModeRoutingCache modeRoutingCache, ChainIdentifier chainIdentifier,
            BudgetCalculator budgetCalculator, Population population) {
        this.modeRoutingCache = modeRoutingCache;
        this.chainIdentifier = chainIdentifier;
        this.budgetCalculator = budgetCalculator;
        this.population = population;
    }

    @Override
    public void notifyIterationEnds(IterationEndsEvent event) {
        if (event.getIteration() == event.getServices().getConfig().controller().getLastIteration()) {

            // 1. Cache Modes
            modeRoutingCache.cacheModes(population);

            // 2. Identify Chains
            chainIdentifier.identifyChains(population);

            // 3. Calculate Budgets
            List<DrtRequest> requests = budgetCalculator.calculateBudgets(population);

            // 4. Write Output
            String filename = event.getServices().getControlerIO().getOutputFilename("drt_requests.csv");
            try (BufferedWriter writer = IOUtils.getBufferedWriter(filename)) {
                writer.write(
                        "personId,groupId,tripIndex,budget,departureTime,originX,originY,destinationX,destinationY");
                writer.newLine();
                for (DrtRequest req : requests) {
                    writer.write(String.format("%s,%s,%d,%.4f,%.2f,%.2f,%.2f,%.2f,%.2f",
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
