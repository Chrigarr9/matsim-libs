package org.matsim.contrib.demand_extraction.algorithm;

import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.demand.BudgetToConstraintsCalculator;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.LeastCostPathCalculatorFactory;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;

import com.google.inject.Inject;
import com.google.inject.Provider;

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
        // Use car mode free-speed travel times for routing (conservative estimate)
		// C: Nope, we should get the traveltimes from our cached network instead of free-speed traveltimes.
        bind(TravelTime.class).to(FreeSpeedTravelTime.class).asEagerSingleton();
        bind(TravelDisutility.class).toProvider(TravelDisutilityProvider.class).asEagerSingleton();
        bind(LeastCostPathCalculator.class).toProvider(RouterProvider.class).asEagerSingleton();

        // Bind algorithm components as singletons
        bind(BudgetValidator.class).asEagerSingleton();
        bind(BudgetToConstraintsCalculator.class).asEagerSingleton();
        bind(MatsimNetworkCache.class).asEagerSingleton();
    }
    
    /**
     * Provides TravelDisutility based on travel time.
     */
	//C: where do we need this? Travel disutility should be calculated by the MASTim scoring function like in ModeRoutingCache.
    private static class TravelDisutilityProvider implements Provider<TravelDisutility> {
        @Inject
        private TravelTime travelTime;
        
        @Override
        public TravelDisutility get() {
            return new OnlyTimeDependentTravelDisutility(travelTime);
        }
    }
    
    /**
     * Provides LeastCostPathCalculator using configured factory.
     */
    private static class RouterProvider implements Provider<LeastCostPathCalculator> {
        @Inject
        private Network network;
        @Inject
        private TravelDisutility travelDisutility;
        @Inject
        private TravelTime travelTime;
        @Inject
        private LeastCostPathCalculatorFactory factory;
        
        @Override
        public LeastCostPathCalculator get() {
            return factory.createPathCalculator(network, travelDisutility, travelTime);
        }
    }
}
