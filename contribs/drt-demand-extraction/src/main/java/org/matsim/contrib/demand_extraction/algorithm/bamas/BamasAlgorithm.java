package org.matsim.contrib.demand_extraction.algorithm.bamas;

import java.util.List;
import java.util.Map;

import org.matsim.contrib.demand_extraction.algorithm.AlgorithmResult;
import org.matsim.contrib.demand_extraction.algorithm.ExMasAlgorithm;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.engine.ExMasEngine;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

import com.google.inject.Inject;

/**
 * Adapter wrapping the current ride-generation engine ({@link ExMasEngine},
 * scheduled to become {@code BamasEngine} in Phase 3) behind the
 * {@link ExMasAlgorithm} strategy interface.
 *
 * <p>Phase 1 scaffold — the engine is still called {@code ExMasEngine} in
 * source; renaming to {@code BamasEngine} happens mechanically in Phase 3
 * alongside the package move to {@code algorithm/bamas/}.
 */
public class BamasAlgorithm implements ExMasAlgorithm {
	private final MatsimNetworkCache network;
	private final BudgetValidator budgetValidator;
	private final ExMasConfigGroup exMasConfig;

	@Inject
	public BamasAlgorithm(MatsimNetworkCache network,
						   BudgetValidator budgetValidator,
						   ExMasConfigGroup exMasConfig) {
		this.network = network;
		this.budgetValidator = budgetValidator;
		this.exMasConfig = exMasConfig;
	}

	@Override
	public AlgorithmResult run(List<DrtRequest> requests) {
		ExMasEngine engine = new ExMasEngine(
				network,
				budgetValidator,
				exMasConfig.getSearchHorizon(),
				exMasConfig.getMaxPoolingDegree(),
				exMasConfig);
		List<Ride> rides = engine.run(requests);
		// Diagnostics are wired in Phase 3.4 once BamasEngine exposes EnumerationStats.
		return new AlgorithmResult(rides, Map.of());
	}
}
