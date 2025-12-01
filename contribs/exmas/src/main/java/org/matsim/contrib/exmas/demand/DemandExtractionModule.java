package org.matsim.contrib.exmas.demand;

import org.matsim.contrib.exmas.config.ExMasConfigGroup;
import org.matsim.core.controler.AbstractModule;

public class DemandExtractionModule extends AbstractModule {
    @Override
    public void install() {
        // Bind Config Group
        if (getConfig().getModules().containsKey(ExMasConfigGroup.GROUP_NAME)) {
            bind(ExMasConfigGroup.class)
                    .toInstance((ExMasConfigGroup) getConfig().getModule(ExMasConfigGroup.GROUP_NAME));
        } else {
            throw new RuntimeException("ExMasConfigGroup is required but not found in config. "
                    + "Please add it to your config file using: "
                    + "config.addModule(new ExMasConfigGroup())");
        }

        bind(ModeRoutingCache.class).asEagerSingleton();
        bind(ChainIdentifier.class).asEagerSingleton();
        bind(BudgetCalculator.class).asEagerSingleton();
        addControlerListenerBinding().to(DemandExtractionListener.class);
    }
}
