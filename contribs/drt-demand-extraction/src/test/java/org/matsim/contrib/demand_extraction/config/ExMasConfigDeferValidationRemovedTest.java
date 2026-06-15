package org.matsim.contrib.demand_extraction.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The {@code deferExtensionBudgetValidation} knob was removed (2026-06-15): budget validation
 * is now ALWAYS inline during extension. Deferring picks the shortest-distance ordering without
 * a budget check, which silently undercounts (or hard-crashes at export on) scenarios where
 * budget binds — e.g. rural Lyon. There is no safe use case, so the flag must be impossible to set.
 *
 * <p>{@link ExMasConfigGroup} extends a strict {@code ReflectiveConfigGroup} ({@code super(GROUP_NAME)},
 * no lenient unknown-param storage), so a removed param is rejected at XML/programmatic parse time.
 */
class ExMasConfigDeferValidationRemovedTest {

    @Test
    void deferExtensionBudgetValidationParamIsRejected() {
        ExMasConfigGroup cfg = new ExMasConfigGroup();
        assertThrows(IllegalArgumentException.class,
                () -> cfg.addParam("deferExtensionBudgetValidation", "true"));
    }
}
