package org.matsim.contrib.demand_extraction.algorithm.bamas.extension;

import org.junit.jupiter.api.Test;
import org.matsim.contrib.demand_extraction.algorithm.bamas.ride.OrderingCodec;
import org.matsim.contrib.demand_extraction.algorithm.bamas.ride.RideLayer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * TDD tests for Task A1: {@link BamasRideExtender.ParentView#firstValidNodeCap()}.
 *
 * <p>Verifies that the default cap returned by the 2-arg {@link BamasRideExtender.RowParentView}
 * constructor is {@code 0L} (unbounded), and that an explicit cap passed via the new 3-arg
 * constructor is round-tripped correctly.
 */
class ParentViewNodeCapTest {

	/** Builds a minimal degree-3 {@link RideLayer} with exactly one row at index 0. */
	private static RideLayer buildLayer3() {
		RideLayer layer = new RideLayer(3);
		int[] sortedSet = {0, 1, 2};
		long originPacked = OrderingCodec.pack(new int[]{0, 1, 2});
		long destPacked   = OrderingCodec.pack(new int[]{0, 1, 2});
		layer.addRow(sortedSet, originPacked, destPacked, 1000, 600, (byte) 0);
		return layer;
	}

	@Test
	void defaultCapIsZeroUnbounded() {
		RideLayer layer = buildLayer3();
		BamasRideExtender.RowParentView v = new BamasRideExtender.RowParentView(layer, 0);
		assertEquals(0L, v.firstValidNodeCap(),
				"Default 2-arg RowParentView must report cap == 0 (unbounded)");
	}

	@Test
	void explicitCapIsExposed() {
		RideLayer layer = buildLayer3();
		BamasRideExtender.RowParentView v = new BamasRideExtender.RowParentView(layer, 0, 10_000L);
		assertEquals(10_000L, v.firstValidNodeCap(),
				"3-arg RowParentView must round-trip the explicit cap");
	}
}
