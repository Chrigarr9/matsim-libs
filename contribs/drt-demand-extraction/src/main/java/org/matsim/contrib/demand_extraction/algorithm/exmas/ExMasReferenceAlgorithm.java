package org.matsim.contrib.demand_extraction.algorithm.exmas;

import java.util.List;
import java.util.Map;

import org.matsim.contrib.demand_extraction.algorithm.AlgorithmResult;
import org.matsim.contrib.demand_extraction.algorithm.ExMasAlgorithm;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

import com.google.inject.Inject;

/**
 * Reference ExMAS strategy — wraps the frozen {@link ExMasReferenceEngine}
 * ported from {@code main} under the {@link ExMasAlgorithm} strategy interface.
 *
 * <p>Used for paper-1 claim C1 (R1): our algorithm is admissibility-equivalent
 * to vanilla ExMAS on small scenarios. See design §2.3.
 */
public class ExMasReferenceAlgorithm implements ExMasAlgorithm {
	private final MatsimNetworkCache network;
	private final BudgetValidator budgetValidator;
	private final ExMasConfigGroup exMasConfig;

	@Inject
	public ExMasReferenceAlgorithm(MatsimNetworkCache network,
								   BudgetValidator budgetValidator,
								   ExMasConfigGroup exMasConfig) {
		this.network = network;
		this.budgetValidator = budgetValidator;
		this.exMasConfig = exMasConfig;
	}

	@Override
	public AlgorithmResult run(List<DrtRequest> requests) {
		ExMasReferenceEngine engine = new ExMasReferenceEngine(
				network,
				budgetValidator,
				exMasConfig.getSearchHorizon(),
				exMasConfig.getMaxPoolingDegree(),
				exMasConfig);
		List<Ride> rides = engine.run(requests);
		// Reference engine doesn't emit EnumerationStats-style diagnostics.
		return new AlgorithmResult(rides, Map.of());
	}
}
