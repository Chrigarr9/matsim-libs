package org.matsim.contrib.exmas_algorithm;

import org.matsim.contrib.exmas.config.ExMasConfigGroup;
import org.matsim.contrib.exmas_algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.exmas_algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.demand.BudgetToConstraintsCalculator;
import org.matsim.core.controler.AbstractModule;

/**
 * Guice module for ExMAS algorithm components.
 * 
 * Binds all services needed for ride generation:
 * - BudgetValidator: validates ride feasibility against budget constraints
 * - BudgetToConstraintsCalculator: converts utility budgets to physical constraints
 * - MatsimNetworkCache: provides network travel time/distance lookups
 * 
 * All components are singletons to ensure consistent state across the algorithm.
 */
public class ExMasAlgorithmModule extends AbstractModule {
    @Override
    public void install() {
        // Check that ExMasConfigGroup is present
        if (!getConfig().getModules().containsKey(ExMasConfigGroup.GROUP_NAME)) {
            throw new RuntimeException("ExMasConfigGroup is required but not found in config. "
                    + "Please add it to your config file using: "
                    + "config.addModule(new ExMasConfigGroup())");
        }

        // Bind algorithm components as singletons
        bind(BudgetValidator.class).asEagerSingleton();
        bind(BudgetToConstraintsCalculator.class).asEagerSingleton();
        bind(MatsimNetworkCache.class).asEagerSingleton();
    }
}
