package org.matsim.contrib.demand_extraction.algorithm;

import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.BudgetToConstraintsCalculator;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.router.util.LeastCostPathCalculator;

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

        // Bind routing components for network cache
		// Use MATSim's bound TravelTime and TravelDisutility (respects simulation
		// state)
		// This automatically uses the TravelTime bound by MATSim's
		// TravelTimeCalculatorModule,
		// which updates based on events/iterations. If user binds custom
		// TravelTime/TravelDisutility,
		// we use those automatically - making our routing consistent with simulation
		// routing.
		// Note: TravelTime and TravelDisutility are already bound by MATSim's core
		// modules.
		// We only bind our DRT-specific router (not a generic one) to ensure
		// ExMAS components use the filtered network while rest of MATSim uses normal
		// routing.

		// Bind DRT-specific router with network filtering based on drtAllowedModes
		// config
		// Named binding uses "direct{drtMode}Router" pattern (e.g., "directDrtRouter")
		ExMasConfigGroup exmasConfig = (ExMasConfigGroup) getConfig().getModules().get(ExMasConfigGroup.GROUP_NAME);
		String drtRouterName = "direct" + capitalize(exmasConfig.getDrtMode()) + "Router";
		bind(LeastCostPathCalculator.class)
				.annotatedWith(com.google.inject.name.Names.named(drtRouterName))
				.toProvider(DrtRouterProvider.class);

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

	private static String capitalize(String str) {
		if (str == null || str.isEmpty()) {
			return str;
		}
		return str.substring(0, 1).toUpperCase() + str.substring(1);
	}
}
