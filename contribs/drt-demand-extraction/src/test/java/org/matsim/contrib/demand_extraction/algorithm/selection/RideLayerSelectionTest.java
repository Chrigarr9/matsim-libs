package org.matsim.contrib.demand_extraction.algorithm.selection;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.demand_extraction.algorithm.bamas.ride.OrderingCodec;
import org.matsim.contrib.demand_extraction.algorithm.bamas.ride.RideLayer;
import org.matsim.contrib.demand_extraction.algorithm.bamas.ride.RideMetricScaling;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

/**
 * Unit tests for {@link RideLayerSelection}, the adapter that wires {@link RideSelector}
 * to stub-mode {@link RideLayer} layers.
 *
 * <p>The Kelheim byte-golden ({@code KelheimHyperPoolStubParityTest}) exercises only the
 * production path: {@link RideLayerSelection#prune} under the default COVERAGE_TOPK mode. It
 * does NOT touch {@link RideLayerSelection#filterParents} (off by default) — and that method
 * is the sole consumer of the {@code OP_COST_PER_PAX} metric — nor the RATIO_THRESHOLD prune
 * branch. Those two paths were previously covered only by {@code ExtensionParentFilterTest}
 * and {@code PostExtensionPrunerTest}, which were deleted when their producers were folded
 * into this adapter. This test re-pins that coverage so the deletion did not silently drop it.
 */
class RideLayerSelectionTest {

	private static DrtRequest req(int index, double directDistance) {
		return DrtRequest.builder()
				.index(index)
				.personId(Id.createPersonId("p" + index))
				.requestTime(0.0)
				.directTravelTime(0.0)
				.directDistance(directDistance)
				.earliestDeparture(0.0)
				.latestArrival(1.0)
				.maxDetourFactor(1.5)
				.build();
	}

	/** Degree-3 layer, 4 rows added in ascending lex order; every member has direct distance
	 *  1000 m so {@code sumDirectDistance == 3000} for all rows. Ride distances increase with
	 *  row index, so every "higher = better" metric ranks rows 0 &lt; 1 &lt; 2 &lt; 3 (best→worst). */
	private static RideLayer fixture() {
		RideLayer cols = new RideLayer(3);
		long ord = OrderingCodec.pack(new int[]{0, 1, 2}); // identity; sum is order-independent
		cols.addRow(new int[]{0, 1, 2}, ord, ord, RideMetricScaling.toDeci(1500.0), 0, (byte) 0);
		cols.addRow(new int[]{0, 1, 3}, ord, ord, RideMetricScaling.toDeci(2000.0), 0, (byte) 0);
		cols.addRow(new int[]{2, 4, 5}, ord, ord, RideMetricScaling.toDeci(2500.0), 0, (byte) 0);
		cols.addRow(new int[]{0, 1, 4}, ord, ord, RideMetricScaling.toDeci(2800.0), 0, (byte) 0);
		return cols;
	}

	private static Map<Integer, DrtRequest> requestsById() {
		Map<Integer, DrtRequest> m = new HashMap<>();
		for (int i = 0; i <= 5; i++) m.put(i, req(i, 1000.0));
		return m;
	}

	@Test
	void filterParentsOpCostPerPaxKeepsPerRequestBestInAscendingRowOrder() {
		// OP_COST_PER_PAX = -(rideDist / degree): higher = better, so smaller ride distance wins.
		// Row order best→worst is 0,1,2,3. PER_REQUEST_TOP_K (k=1): req0/req1/req2 all keep row0,
		// req3 keeps row1 (its only incident), req4/req5 keep row2. Row 3 {0,1,4} is dominated on
		// every member → dropped. Survivors emit in ascending row order: {0,1,2},{0,1,3},{2,4,5}.
		RideLayer filtered = RideLayerSelection.filterParents(
				fixture(), requestsById(), 1,
				ExMasConfigGroup.PruningQualityMetric.OP_COST_PER_PAX,
				ExMasConfigGroup.ExtensionParentsSelectionRule.TOP_K, 0.0);

		assertEquals(3, filtered.size(), "dominated row 3 must be dropped");
		assertArrayEquals(new int[]{0, 1, 2}, filtered.requestIndices(0));
		assertArrayEquals(new int[]{0, 1, 3}, filtered.requestIndices(1));
		assertArrayEquals(new int[]{2, 4, 5}, filtered.requestIndices(2));
		assertEquals(RideMetricScaling.toDeci(1500.0), filtered.rideDistanceDm(0), "columns copied faithfully");
	}

	@Test
	void filterParentsKNonPositiveKeepsEverythingUnmutated() {
		RideLayer parents = fixture();
		RideLayer filtered = RideLayerSelection.filterParents(
				parents, requestsById(), 0,
				ExMasConfigGroup.PruningQualityMetric.OP_COST_PER_PAX,
				ExMasConfigGroup.ExtensionParentsSelectionRule.TOP_K, 0.0);
		assertEquals(4, filtered.size(), "k<=0 keeps everything");
		assertEquals(4, parents.size(), "input layer is never mutated");
	}

	@Test
	void pruneRatioThresholdKeepsTopFractionByFloorIndexInAscendingRowOrder() {
		// savings = 1 - rideDist/3000: row0=0.500, row1=0.333, row2=0.167, row3=0.067.
		// keepFraction 0.5, n=4 → thresholdIndex = floor(4 * 0.5) = 2 → sorted[2] = 0.333.
		// Keep savings >= 0.333 → rows 0,1, emitted in ascending row order.
		ExMasConfigGroup cfg = new ExMasConfigGroup();
		cfg.setPruningMode(ExMasConfigGroup.PruningMode.RATIO_THRESHOLD);
		cfg.setInterDegreeKeepFraction(0.5);

		RideLayer pruned = RideLayerSelection.prune(fixture(), requestsById(), cfg);

		assertEquals(2, pruned.size());
		assertArrayEquals(new int[]{0, 1, 2}, pruned.requestIndices(0));
		assertArrayEquals(new int[]{0, 1, 3}, pruned.requestIndices(1));
	}

	@Test
	void pruneRatioThresholdFractionAtLeastOneIsNoOp() {
		ExMasConfigGroup cfg = new ExMasConfigGroup();
		cfg.setPruningMode(ExMasConfigGroup.PruningMode.RATIO_THRESHOLD);
		cfg.setInterDegreeKeepFraction(1.0);
		RideLayer pruned = RideLayerSelection.prune(fixture(), requestsById(), cfg);
		assertEquals(4, pruned.size(), "keepFraction >= 1.0 is a pass-through");
	}

	@Test
	void pruneCoverageTopKEmitsSurvivorsInQualityDescendingOrder() {
		// Rows added in NON-quality order so the emission order is observable. ABS_SAVINGS quality
		// = 3000 - rideDist. With a large coverage-K every row survives, and the adapter must emit
		// them in SelectionTieBreak (quality-descending) order — NOT stored row order. This pins
		// the byte-significant emission contract that the Kelheim golden relies on.
		RideLayer cols = new RideLayer(3);
		long ord = OrderingCodec.pack(new int[]{0, 1, 2});
		cols.addRow(new int[]{2, 4, 5}, ord, ord, RideMetricScaling.toDeci(2500.0), 0, (byte) 0); // savings  500
		cols.addRow(new int[]{0, 1, 2}, ord, ord, RideMetricScaling.toDeci(1500.0), 0, (byte) 0); // savings 1500
		cols.addRow(new int[]{0, 1, 4}, ord, ord, RideMetricScaling.toDeci(2800.0), 0, (byte) 0); // savings  200
		cols.addRow(new int[]{0, 1, 3}, ord, ord, RideMetricScaling.toDeci(2000.0), 0, (byte) 0); // savings 1000

		ExMasConfigGroup cfg = new ExMasConfigGroup();
		cfg.setPruningMode(ExMasConfigGroup.PruningMode.COVERAGE_TOPK);
		cfg.setPruningQualityMetric(ExMasConfigGroup.PruningQualityMetric.ABS_SAVINGS);
		cfg.setPruningCoverageK(20);

		RideLayer pruned = RideLayerSelection.prune(cols, requestsById(), cfg);

		assertEquals(4, pruned.size(), "large K keeps every row");
		// Quality-descending: 1500, 1000, 500, 200 → sets {0,1,2},{0,1,3},{2,4,5},{0,1,4}.
		assertArrayEquals(new int[]{0, 1, 2}, pruned.requestIndices(0));
		assertArrayEquals(new int[]{0, 1, 3}, pruned.requestIndices(1));
		assertArrayEquals(new int[]{2, 4, 5}, pruned.requestIndices(2));
		assertArrayEquals(new int[]{0, 1, 4}, pruned.requestIndices(3));
	}
}
