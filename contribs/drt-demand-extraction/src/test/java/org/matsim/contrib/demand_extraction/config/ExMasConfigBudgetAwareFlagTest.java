package org.matsim.contrib.demand_extraction.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExMasConfigBudgetAwareFlagTest {

    @Test
    void defaultIsFalse_preserveCurrentBehaviour() {
        ExMasConfigGroup cfg = new ExMasConfigGroup();
        assertFalse(cfg.isEnableBudgetAwareConstraints());
    }

    @Test
    void canBeEnabledViaSetter() {
        ExMasConfigGroup cfg = new ExMasConfigGroup();
        cfg.setEnableBudgetAwareConstraints(true);
        assertTrue(cfg.isEnableBudgetAwareConstraints());
    }
}
