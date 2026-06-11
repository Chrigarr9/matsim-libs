package org.matsim.contrib.demand_extraction.algorithm.bamas.stub;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

class OrderingCodecTest {
	@Test void roundTripsAllPositionsAtMaxDegree() {
		int[] order = {15, 0, 7, 3, 11, 1, 14, 2, 9, 4, 13, 5, 8, 6, 12, 10}; // degree 16 permutation
		long packed = OrderingCodec.pack(order);
		int[] unpacked = OrderingCodec.unpack(packed, 16);
		assertArrayEquals(order, unpacked);
	}
	@Test void roundTripsSmallDegrees() {
		for (int d = 1; d <= 16; d++) {
			int[] order = new int[d];
			for (int i = 0; i < d; i++) order[i] = (d - 1 - i); // reversed
			assertArrayEquals(order, OrderingCodec.unpack(OrderingCodec.pack(order), d));
		}
	}
}
