package org.matsim.contrib.exmas.demand;

import org.matsim.contrib.drt.optimizer.constraints.DrtOptimizationConstraintsSet;
import org.matsim.contrib.drt.optimizer.constraints.DrtOptimizationConstraintsSetImpl;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.exmas.config.ExMasConfigGroup;
import org.matsim.core.config.Config;

/**
 * Helper class to configure DRT parameters used for budget calculation.
 *
 * This centralises the logic so the same configuration can later be reset
 * to parameters obtained from optimization.
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

			DrtOptimizationConstraintsSet constraints = drtCfg
					.addOrGetDrtOptimizationConstraintsParams()
					.addOrGetDefaultDrtOptimizationConstraintsSet();

			constraints.setMaxWaitTime(exMasConfig.getMinMaxWaitingTime() * 60.0);
			constraints.setMaxWalkDistance(exMasConfig.getMinDrtAccessEgressDistance());
			constraints.setRejectRequestIfMaxWaitOrTravelTimeViolated(false);
			constraints.setMaxAllowedPickupDelay(Double.POSITIVE_INFINITY);

			if (constraints instanceof DrtOptimizationConstraintsSetImpl impl) {
				impl.setMaxTravelTimeAlpha(exMasConfig.getMinMaxDetourFactor());
				impl.setMaxTravelTimeBeta(0.0);
				impl.setMaxAbsoluteDetour(Double.POSITIVE_INFINITY);
			}

			var fareParams = drtCfg.getDrtFareParams().orElseGet(() -> {
				var newFareParams = new org.matsim.contrib.drt.fare.DrtFareParams();
				drtCfg.addParameterSet(newFareParams);
				return newFareParams;
			});

			fareParams.setBaseFare(0.0);
			fareParams.setDailySubscriptionFee(0.0);
			fareParams.setDistanceFare_m(exMasConfig.getMinDrtCostPerKm() / 1000.0);
			fareParams.setTimeFare_h(0.0);
			fareParams.setMinFarePerTrip(0.0);
		}
	}
}
