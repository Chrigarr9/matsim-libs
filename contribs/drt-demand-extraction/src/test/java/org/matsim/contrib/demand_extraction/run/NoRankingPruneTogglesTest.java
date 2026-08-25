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

/**
 * Pins that {@code RATIO_THRESHOLD} with {@code interDegreeKeepFraction=1.0} (the
 * exmas-overlay equivalent of the old {@code --coverage-k 0} CLI toggle) is a clean
 * no-op: {@link RideLayerSelection#prune} under that config returns ALL rides unmodified.
 *
 * <p>The translation from an orthogonal {@code --algorithm}/{@code --coverage-k} CLI
 * triple into these {@code ExMasConfigGroup} fields ({@code applyAlgorithmAndPruning})
 * was removed 2026-08-19 (spec D4): {@code pruningMode} / {@code interDegreeKeepFraction}
 * are now ordinary exmas-overlay params set directly, so this test exercises
 * {@link RideLayerSelection#prune} against the config states directly instead.
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
