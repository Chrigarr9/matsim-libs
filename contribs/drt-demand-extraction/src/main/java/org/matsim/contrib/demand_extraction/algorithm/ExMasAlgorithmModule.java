package org.matsim.contrib.demand_extraction.algorithm;

import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.BudgetToConstraintsCalculator;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.router.costcalculators.TravelDisutilityFactory;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.LeastCostPathCalculatorFactory;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.name.Named;

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
		// modules,
		// so we only need to bind the LeastCostPathCalculator that uses them.
        bind(LeastCostPathCalculator.class).toProvider(RouterProvider.class).asEagerSingleton();

        // Bind algorithm components as singletons
        bind(BudgetValidator.class).asEagerSingleton();
        bind(BudgetToConstraintsCalculator.class).asEagerSingleton();
        bind(MatsimNetworkCache.class).asEagerSingleton();
    }
    
    /**
	 * Provides LeastCostPathCalculator (router) using MATSim-bound TravelTime and
	 * TravelDisutility for car mode.
	 * This ensures routing uses the same components as the simulation itself.
	 */
    private static class RouterProvider implements Provider<LeastCostPathCalculator> {
        @Inject
        private Network network;
        @Inject
		@Named(TransportMode.car)
		private TravelDisutilityFactory travelDisutilityFactory;
        @Inject
		@Named(TransportMode.car)
        private TravelTime travelTime;
        @Inject
        private LeastCostPathCalculatorFactory factory;
        
        @Override
        public LeastCostPathCalculator get() {
			TravelDisutility travelDisutility = travelDisutilityFactory.createTravelDisutility(travelTime);
            return factory.createPathCalculator(network, travelDisutility, travelTime);
        }
    }
}
