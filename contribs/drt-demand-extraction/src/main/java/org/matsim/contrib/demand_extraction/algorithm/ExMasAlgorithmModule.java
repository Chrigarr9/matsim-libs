package org.matsim.contrib.demand_extraction.algorithm;

import java.util.Set;

import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.BudgetToConstraintsCalculator;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.network.NetworkUtils;
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
				.toProvider(new DrtRouterProvider(drtRouterName))
				.asEagerSingleton();

        // Bind algorithm components as singletons
        bind(BudgetValidator.class).asEagerSingleton();
        bind(BudgetToConstraintsCalculator.class).asEagerSingleton();
        bind(MatsimNetworkCache.class).asEagerSingleton();
    }

	/**
	 * Provides DRT-specific LeastCostPathCalculator with network filtered by
	 * drtAllowedModes.
	 * If drtAllowedModes is empty, uses full network (no filtering).
	 */
	private static class DrtRouterProvider implements Provider<LeastCostPathCalculator> {
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
		@Inject
		private ExMasConfigGroup exmasConfig;

		DrtRouterProvider(String routerName) {
			// routerName kept for potential future logging/debugging
		}

		@Override
		public LeastCostPathCalculator get() {
			Network routingNetwork = filterNetworkByAllowedModes(network, exmasConfig.getDrtAllowedModes());
			TravelDisutility travelDisutility = travelDisutilityFactory.createTravelDisutility(travelTime);
			return factory.createPathCalculator(routingNetwork, travelDisutility, travelTime);
		}

		private Network filterNetworkByAllowedModes(Network originalNetwork, Set<String> allowedModes) {
			// If no modes specified, use full network
			if (allowedModes == null || allowedModes.isEmpty()) {
				return originalNetwork;
			}

			// Create filtered network
			Network filteredNetwork = NetworkUtils.createNetwork();

			for (Link link : originalNetwork.getLinks().values()) {
				if (isLinkAllowed(link, allowedModes)) {
					addLinkWithNodes(filteredNetwork, link);
				}
			}

			return filteredNetwork;
		}

		private boolean isLinkAllowed(Link link, Set<String> allowedModes) {
			Set<String> linkModes = link.getAllowedModes();
			for (String mode : allowedModes) {
				if (linkModes.contains(mode)) {
					return true;
				}
			}
			return false;
		}

		private void addLinkWithNodes(Network network, Link link) {
			if (!network.getNodes().containsKey(link.getFromNode().getId())) {
				network.addNode(link.getFromNode());
			}
			if (!network.getNodes().containsKey(link.getToNode().getId())) {
				network.addNode(link.getToNode());
			}
			network.addLink(link);
		}
	}

	private static String capitalize(String str) {
		if (str == null || str.isEmpty()) {
			return str;
		}
		return str.substring(0, 1).toUpperCase() + str.substring(1);
	}
}
