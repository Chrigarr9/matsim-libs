package org.matsim.contrib.demand_extraction.algorithm.bamas;

import java.util.List;
import java.util.Map;

import org.matsim.contrib.demand_extraction.algorithm.AlgorithmResult;
import org.matsim.contrib.demand_extraction.algorithm.ExMasAlgorithm;
import org.matsim.contrib.demand_extraction.algorithm.bamas.ride.RideStore;
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

	// C1 — routing-input file paths forwarded to the BamasEngine for the checkpoint RunFingerprint.
	// Null by default (config-only fingerprint, write/resume symmetric); the runner that owns these
	// paths (RunDemandExtractionPhase2) calls setFingerprintInputs() before run() so a resume against
	// changed requests/travel-times/network is refused. Forwarded unconditionally — the engine only
	// hashes them when checkpointing is enabled, so forwarding nulls is harmless.
	private java.nio.file.Path fpRequestsPath;
	private java.nio.file.Path fpTravelTimesPath;
	private java.nio.file.Path fpNetworkPath;

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

	/**
	 * C1 — supply the routing-input file paths the {@link BamasEngine} hashes into the checkpoint
	 * fingerprint. Forwarded into the engine built in {@link #run}; any argument may be {@code null}
	 * (config-only fingerprint). Must be called before {@link #run} to take effect.
	 */
	public void setFingerprintInputs(java.nio.file.Path requestsPath,
			java.nio.file.Path travelTimesPath, java.nio.file.Path networkPath) {
		this.fpRequestsPath = requestsPath;
		this.fpTravelTimesPath = travelTimesPath;
		this.fpNetworkPath = networkPath;
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
		// C1 — forward the routing-input paths so checkpoint writes and resume gate hash them.
		engine.setFingerprintInputs(fpRequestsPath, fpTravelTimesPath, fpNetworkPath);
		RideStore rides = engine.run(requests);
		// Diagnostics are wired in Phase 3.4 once BamasEngine exposes EnumerationStats.
		return new AlgorithmResult(rides, engine.getHyperPooledRides(), Map.of());
	}
}
