package org.matsim.contrib.demand_extraction.algorithm;

import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.BudgetToConstraintsCalculator;
import org.matsim.core.controler.AbstractModule;

import com.google.inject.Injector;
import com.google.inject.Provides;
import com.google.inject.Singleton;

import org.matsim.contrib.demand_extraction.algorithm.bamas.BamasAlgorithm;
import org.matsim.contrib.demand_extraction.algorithm.exmas.ExMasReferenceAlgorithm;

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

        // MatsimNetworkCache resolves TravelTime/TravelDisutilityFactory itself and wraps them deterministically.

        // Bind algorithm components as singletons
        bind(BudgetValidator.class).asEagerSingleton();
        bind(BudgetToConstraintsCalculator.class).asEagerSingleton();
        bind(MatsimNetworkCache.class).asEagerSingleton();

        // Strategy implementations (MATSim disables Guice JIT bindings, so these
        // must be bound explicitly for provideExMasAlgorithm to resolve them).
        bind(BamasAlgorithm.class);
        bind(ExMasReferenceAlgorithm.class);
    }

    /**
     * Strategy dispatch. Selects the Stage-1 algorithm from
     * {@link ExMasConfigGroup#getAlgorithm()}.
     */
    @Provides
    @Singleton
    public ExMasAlgorithm provideExMasAlgorithm(Injector injector, ExMasConfigGroup cfg) {
        return switch (cfg.getAlgorithm()) {
            case BAMAS -> injector.getInstance(BamasAlgorithm.class);
            case EXMAS -> injector.getInstance(ExMasReferenceAlgorithm.class);
        };
    }

}
