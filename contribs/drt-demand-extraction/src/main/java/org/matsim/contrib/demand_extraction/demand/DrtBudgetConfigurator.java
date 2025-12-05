package org.matsim.contrib.demand_extraction.demand;

import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.core.config.Config;

/**
 * Helper class to configure DRT fare parameters used for budget calculation.
 *
 * Note: We only configure fare parameters here because
 * BudgetToConstraintsCalculator
 * reads these values from the DRT config. The DRT optimization constraints
 * (max wait time, max detour, etc.) are NOT configured here because:
 * 1. ExMAS uses its own routing (directDrtRouter), not the real DRT simulation
 * 2. Service quality constraints are derived from budgets, not enforced by DRT
 * optimizer
 * 3. The actual DRT simulation never runs - we only generate requests/rides
 * offline
 */
public final class DrtBudgetConfigurator {

	private DrtBudgetConfigurator() {
		// utility class
	}

	public static void configureDrtForBudgetCalculation(Config config, ExMasConfigGroup exMasConfig) {
		if (!config.getModules().containsKey(MultiModeDrtConfigGroup.GROUP_NAME)) {
			return;
		}

		MultiModeDrtConfigGroup multiModeDrtConfig = MultiModeDrtConfigGroup.get(config);
		String drtMode = exMasConfig.getDrtMode();

		for (DrtConfigGroup drtCfg : multiModeDrtConfig.getModalElements()) {
			if (!drtMode.equals(drtCfg.getMode()))
				continue;

			// Configure DRT fare parameters that will be read by
			// BudgetToConstraintsCalculator
			var fareParams = drtCfg.getDrtFareParams().orElseGet(() -> {
				var newFareParams = new org.matsim.contrib.drt.fare.DrtFareParams();
				drtCfg.addParameterSet(newFareParams);
				return newFareParams;
			});

			// Set fare parameters based on ExMAS config (minimum service quality = maximum
			// quality)
			fareParams.setBaseFare(0.0);
			fareParams.setDailySubscriptionFee(0.0);
			fareParams.setDistanceFare_m(exMasConfig.getMinDrtCostPerKm() / 1000.0);
			fareParams.setTimeFare_h(0.0);
			fareParams.setMinFarePerTrip(0.0);
		}
	}
}
