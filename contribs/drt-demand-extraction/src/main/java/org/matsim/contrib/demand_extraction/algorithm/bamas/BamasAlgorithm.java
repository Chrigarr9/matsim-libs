package org.matsim.contrib.demand_extraction.algorithm.bamas;

import java.util.List;
import java.util.Map;

import org.matsim.contrib.demand_extraction.algorithm.AlgorithmResult;
import org.matsim.contrib.demand_extraction.algorithm.ExMasAlgorithm;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
// BamasEngine is in this same package (algorithm.bamas) — no explicit import needed.
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.BudgetToConstraintsCalculator;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

import com.google.inject.Inject;

/**
 * Adapter wrapping {@link BamasEngine} behind the {@link ExMasAlgorithm}
 * strategy interface.
 */
public class BamasAlgorithm implements ExMasAlgorithm {
	private final MatsimNetworkCache network;
	private final BudgetValidator budgetValidator;
	private final ExMasConfigGroup exMasConfig;
	private final BudgetToConstraintsCalculator budgetToConstraints;

	@Inject
	public BamasAlgorithm(MatsimNetworkCache network,
						   BudgetValidator budgetValidator,
						   ExMasConfigGroup exMasConfig,
						   BudgetToConstraintsCalculator budgetToConstraints) {
		this.network = network;
		this.budgetValidator = budgetValidator;
		this.exMasConfig = exMasConfig;
		this.budgetToConstraints = budgetToConstraints;
	}

	@Override
	public AlgorithmResult run(List<DrtRequest> requests) {
		BamasEngine engine = new BamasEngine(
				network,
				budgetValidator,
				exMasConfig.getSearchHorizon(),
				exMasConfig.getMaxPoolingDegree(),
				exMasConfig,
				null,
				budgetToConstraints);
		List<Ride> rides = engine.run(requests);
		// Diagnostics are wired in Phase 3.4 once BamasEngine exposes EnumerationStats.
		return new AlgorithmResult(rides, engine.getHyperPooledRides(), Map.of());
	}
}
