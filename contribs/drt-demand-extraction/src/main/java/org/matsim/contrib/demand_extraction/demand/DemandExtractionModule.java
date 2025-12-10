package org.matsim.contrib.demand_extraction.demand;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.contrib.demand_extraction.algorithm.ExMasAlgorithmModule;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.controler.AbstractModule;

public class DemandExtractionModule extends AbstractModule {
	private static final Logger log = LogManager.getLogger(DemandExtractionModule.class);

	@Override
    public void install() {
		Config config = getConfig();

		// Check that ExMasConfigGroup is present in config
		// (it will be automatically bound by MATSim's ExplodedConfigModule)
		if (!config.getModules().containsKey(ExMasConfigGroup.GROUP_NAME)) {
            throw new RuntimeException("ExMasConfigGroup is required but not found in config. "
                    + "Please add it to your config file using: "
                    + "config.addModule(new ExMasConfigGroup())");
        }

		// NOTE: Configuration validation MUST be done BEFORE controler/scenario creation
		// Calling DemandExtractionConfigValidator.prepareConfigForDemandExtraction(config) here is TOO LATE!
		// The SwissRailRaptor, routing modules, and other components have already been instantiated.
		// 
		// IMPORTANT: Call DemandExtractionConfigValidator.prepareConfigForDemandExtraction(config)
		//            BEFORE DrtControlerCreator.createControler() or Controler creation.

		// Bind demand extraction components
        bind(ModeRoutingCache.class).asEagerSingleton();
        bind(ChainIdentifier.class).asEagerSingleton();
		bind(CommuteIdentifier.class).asEagerSingleton();
		bind(DrtRequestFactory.class).asEagerSingleton();
        
        // Install ExMAS algorithm module (validators, network cache, etc.)
        install(new ExMasAlgorithmModule());
        
		// Register shutdown listener (runs after all iterations complete)
        addControllerListenerBinding().to(DemandExtractionListener.class);
    }
}
