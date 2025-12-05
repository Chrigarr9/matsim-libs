package org.matsim.contrib.demand_extraction.demand;

import org.matsim.contrib.demand_extraction.algorithm.ExMasAlgorithmModule;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.core.controler.AbstractModule;

public class DemandExtractionModule extends AbstractModule {
    @Override
    public void install() {
		// Check that ExMasConfigGroup is present in config
		// (it will be automatically bound by MATSim's ExplodedConfigModule)
		if (!getConfig().getModules().containsKey(ExMasConfigGroup.GROUP_NAME)) {
            throw new RuntimeException("ExMasConfigGroup is required but not found in config. "
                    + "Please add it to your config file using: "
                    + "config.addModule(new ExMasConfigGroup())");
        }

        // Bind demand extraction components
        bind(ModeRoutingCache.class).asEagerSingleton();
        bind(ChainIdentifier.class).asEagerSingleton();
		bind(DrtRequestFactory.class).asEagerSingleton();
        
        // Install ExMAS algorithm module (validators, network cache, etc.)
        install(new ExMasAlgorithmModule());
        
        // Register controller listener
        addControllerListenerBinding().to(DemandExtractionListener.class);
    }
}
