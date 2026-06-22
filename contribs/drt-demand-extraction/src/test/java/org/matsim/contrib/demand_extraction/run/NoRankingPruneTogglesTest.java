package org.matsim.contrib.demand_extraction.run;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.demand_extraction.algorithm.bamas.ride.OrderingCodec;
import org.matsim.contrib.demand_extraction.algorithm.bamas.ride.RideLayer;
import org.matsim.contrib.demand_extraction.algorithm.bamas.ride.RideMetricScaling;
import org.matsim.contrib.demand_extraction.algorithm.selection.RideLayerSelection;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;

/**
 * Pins that {@code --coverage-k 0} is a clean no-op pruning toggle: the runner sets
 * {@code RATIO_THRESHOLD} with {@code interDegreeKeepFraction=1.0} (keep everything), and
 * {@link RideLayerSelection#prune} under that config returns ALL rides unmodified.
 *
 * <p>This test calls the REAL {@code applyAlgorithmAndPruning} production method so a future
 * change to its logic will break this test rather than silently bypass it.
 */
class NoRankingPruneTogglesTest {

	// ---- helpers shared by both test methods --------------------------------

	private static DrtRequest req(int index) {
		return DrtRequest.builder()
				.index(index)
				.personId(Id.createPersonId("p" + index))
				.requestTime(0.0)
				.directTravelTime(0.0)
				.directDistance(1000.0)
				.earliestDeparture(0.0)
				.latestArrival(1.0)
				.maxDetourFactor(1.5)
				.build();
	}

	/** Four degree-3 rides over requests 0-5. */
	private static RideLayer fourRideLayer() {
		RideLayer layer = new RideLayer(3);
		long ord = OrderingCodec.pack(new int[]{0, 1, 2});
		layer.addRow(new int[]{0, 1, 2}, ord, ord, RideMetricScaling.toDeci(1500.0), 0, (byte) 0);
		layer.addRow(new int[]{0, 1, 3}, ord, ord, RideMetricScaling.toDeci(2000.0), 0, (byte) 0);
		layer.addRow(new int[]{2, 4, 5}, ord, ord, RideMetricScaling.toDeci(2500.0), 0, (byte) 0);
		layer.addRow(new int[]{0, 1, 4}, ord, ord, RideMetricScaling.toDeci(2800.0), 0, (byte) 0);
		return layer;
	}

	private static Map<Integer, DrtRequest> requestsById() {
		Map<Integer, DrtRequest> m = new HashMap<>();
		for (int i = 0; i <= 5; i++) m.put(i, req(i));
		return m;
	}

	// ---- toggle pin ---------------------------------------------------------

	/**
	 * Verifies that the real {@code applyAlgorithmAndPruning} sets {@code RATIO_THRESHOLD}
	 * with fraction 1.0 when {@code coverageK == 0}, and sets {@code COVERAGE_TOPK} with the
	 * supplied K when {@code coverageK > 0}.
	 */
	@Test
	void coverageKZeroSetsRatioThresholdAtOne_andPositiveKSetsCoverageTopK() {
		// --- OFF branch (coverageK = 0) ---
		Config offConfig = ConfigUtils.createConfig(new ExMasConfigGroup());
		RunLyonEqasimDemandExtraction.CliArgs offArgs = new RunLyonEqasimDemandExtraction.CliArgs();
		offArgs.coverageK = 0;
		// gateScale=-1.0 (default) and NaN intercept/slope → heuristic gate disabled; no NPE.

		RunLyonEqasimDemandExtraction.applyAlgorithmAndPruning(offConfig, offArgs);

		ExMasConfigGroup offCfg = ConfigUtils.addOrGetModule(offConfig, ExMasConfigGroup.class);
		assertEquals(ExMasConfigGroup.PruningMode.RATIO_THRESHOLD, offCfg.getPruningMode(),
				"coverageK=0 must set RATIO_THRESHOLD");
		assertEquals(1.0, offCfg.getInterDegreeKeepFraction(), 1e-9,
				"coverageK=0 must set interDegreeKeepFraction=1.0 (keep everything)");

		// --- ON branch (coverageK > 0) contrast case ---
		Config onConfig = ConfigUtils.createConfig(new ExMasConfigGroup());
		RunLyonEqasimDemandExtraction.CliArgs onArgs = new RunLyonEqasimDemandExtraction.CliArgs();
		onArgs.coverageK = 20;

		RunLyonEqasimDemandExtraction.applyAlgorithmAndPruning(onConfig, onArgs);

		ExMasConfigGroup onCfg = ConfigUtils.addOrGetModule(onConfig, ExMasConfigGroup.class);
		assertEquals(ExMasConfigGroup.PruningMode.COVERAGE_TOPK, onCfg.getPruningMode(),
				"coverageK>0 must set COVERAGE_TOPK");
	}

	// ---- no-drop pin --------------------------------------------------------

	/**
	 * Verifies that {@link RideLayerSelection#prune} configured as the OFF toggle leaves
	 * ({@code RATIO_THRESHOLD}, fraction 1.0}) returns ALL rides unmodified (zero drop).
	 */
	@Test
	void ratioThresholdAtOnePrunesNothingFromRideLayer() {
		ExMasConfigGroup cfg = new ExMasConfigGroup();
		cfg.setPruningMode(ExMasConfigGroup.PruningMode.RATIO_THRESHOLD);
		cfg.setInterDegreeKeepFraction(1.0);

		RideLayer pruned = RideLayerSelection.prune(fourRideLayer(), requestsById(), cfg);

		assertEquals(4, pruned.size(),
				"RATIO_THRESHOLD at fraction=1.0 must keep ALL rides (no top-K drop)");
	}
}
