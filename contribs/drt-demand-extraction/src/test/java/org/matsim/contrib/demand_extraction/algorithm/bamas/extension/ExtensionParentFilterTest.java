package org.matsim.contrib.demand_extraction.algorithm.bamas.extension;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.demand_extraction.algorithm.bamas.stub.OrderingCodec;
import org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubColumns;
import org.matsim.contrib.demand_extraction.algorithm.bamas.stub.StubScaling;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

/**
 * Tests the engine-side glue {@link ExtensionParentFilter}: per-stub metric computation,
 * ranker invocation, and filtered-layer construction. The 1% Lyon d2d fast-gate dataset
 * produces only degree-1 rides, so the K&gt;0 path cannot be exercised by that gate — this
 * synthetic degree-3 fixture provides the positive proof that the K&gt;0 branch selects and
 * copies the right rows.
 */
class ExtensionParentFilterTest {

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

	/** Degree-3 layer: 4 rows, all member requests have direct distance 1000 m, so
	 *  ABS_SAVINGS = 3000 - rideDistance. Rows are added in ascending lex order. */
	private static StubColumns fixture() {
		StubColumns cols = new StubColumns(3);
		long ord = OrderingCodec.pack(new int[]{0, 1, 2}); // identity; sum is order-independent
		cols.addRow(new int[]{0, 1, 2}, ord, ord, StubScaling.toDeci(1500.0), 0, (byte) 0); // savings 1500
		cols.addRow(new int[]{0, 1, 3}, ord, ord, StubScaling.toDeci(2000.0), 0, (byte) 0); // savings 1000
		cols.addRow(new int[]{2, 4, 5}, ord, ord, StubScaling.toDeci(2500.0), 0, (byte) 0); // savings  500
		cols.addRow(new int[]{0, 1, 4}, ord, ord, StubScaling.toDeci(2800.0), 0, (byte) 0); // savings  200
		return cols;
	}

	private static Map<Integer, DrtRequest> requestsById() {
		Map<Integer, DrtRequest> m = new HashMap<>();
		for (int i = 0; i <= 5; i++) m.put(i, req(i, 1000.0));
		return m;
	}

	@Test
	void topKOneKeepsBestPerRequestAndDropsDominatedRow() {
		StubColumns parents = fixture();
		// K=1, ABS_SAVINGS, TOP_K. Row 3 {0,1,4} loses req0/req1 to row0 and req4 to row2,
		// so it is the only row marked by no request → dropped. Rows 0,1,2 survive.
		StubColumns filtered = ExtensionParentFilter.filter(
				parents, requestsById(), 1,
				ExMasConfigGroup.PruningQualityMetric.ABS_SAVINGS,
				ExMasConfigGroup.ExtensionParentsSelectionRule.TOP_K, 0.0);

		assertEquals(3, filtered.size(), "row 3 (dominated everywhere) must be dropped");
		// Filtered rows preserve ascending row order → sets {0,1,2},{0,1,3},{2,4,5}.
		assertArrayEquals(new int[]{0, 1, 2}, filtered.requestIndices(0));
		assertArrayEquals(new int[]{0, 1, 3}, filtered.requestIndices(1));
		assertArrayEquals(new int[]{2, 4, 5}, filtered.requestIndices(2));
		// Columns copied faithfully (distance of the first kept row).
		assertEquals(StubScaling.toDeci(1500.0), filtered.rideDistanceDm(0));

		// Input layer is never mutated.
		assertEquals(4, parents.size(), "input parents layer must be unchanged");
	}

	@Test
	void kZeroKeepsEveryRow() {
		StubColumns parents = fixture();
		StubColumns filtered = ExtensionParentFilter.filter(
				parents, requestsById(), 0,
				ExMasConfigGroup.PruningQualityMetric.ABS_SAVINGS,
				ExMasConfigGroup.ExtensionParentsSelectionRule.TOP_K, 0.0);
		assertEquals(4, filtered.size(), "K=0 is exact passthrough");
	}

	@Test
	void mmrLambdaZeroMatchesTopK() {
		StubColumns parents = fixture();
		StubColumns topK = ExtensionParentFilter.filter(
				parents, requestsById(), 1,
				ExMasConfigGroup.PruningQualityMetric.ABS_SAVINGS,
				ExMasConfigGroup.ExtensionParentsSelectionRule.TOP_K, 0.0);
		StubColumns mmr = ExtensionParentFilter.filter(
				parents, requestsById(), 1,
				ExMasConfigGroup.PruningQualityMetric.ABS_SAVINGS,
				ExMasConfigGroup.ExtensionParentsSelectionRule.MMR, 0.0);
		assertEquals(topK.size(), mmr.size(), "MMR λ=0 must keep the same rows as TOP_K");
		for (int r = 0; r < topK.size(); r++) {
			assertArrayEquals(topK.requestIndices(r), mmr.requestIndices(r));
		}
	}
}
