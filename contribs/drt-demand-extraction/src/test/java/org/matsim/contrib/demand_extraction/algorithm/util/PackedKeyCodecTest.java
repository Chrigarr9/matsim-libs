package org.matsim.contrib.demand_extraction.algorithm.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PackedKeyCodecTest {

	@Test
	void segmentKeyRoundTrips() {
		long key = PackedKeyCodec.segmentKey(123456, 654321, 144);
		assertEquals(123456, PackedKeyCodec.origin(key));
		assertEquals(654321, PackedKeyCodec.dest(key));
		assertEquals(144, PackedKeyCodec.bin(key));
	}

	@Test
	void boundaryValuesRoundTrip() {
		long key = PackedKeyCodec.segmentKey(
				PackedKeyCodec.MAX_LINK_INDEX, 0, PackedKeyCodec.MAX_TIME_BIN);
		assertEquals(PackedKeyCodec.MAX_LINK_INDEX, PackedKeyCodec.origin(key));
		assertEquals(0, PackedKeyCodec.dest(key));
		assertEquals(PackedKeyCodec.MAX_TIME_BIN, PackedKeyCodec.bin(key));

		long zero = PackedKeyCodec.segmentKey(0, 0, 0);
		assertEquals(0, PackedKeyCodec.origin(zero));
		assertEquals(0, PackedKeyCodec.dest(zero));
		assertEquals(0, PackedKeyCodec.bin(zero));
	}

	@Test
	void linkIndexOverflowThrows() {
		assertThrows(IllegalArgumentException.class,
				() -> PackedKeyCodec.segmentKey(PackedKeyCodec.MAX_LINK_INDEX + 1, 0, 0));
		assertThrows(IllegalArgumentException.class,
				() -> PackedKeyCodec.segmentKey(0, PackedKeyCodec.MAX_LINK_INDEX + 1, 0));
		assertThrows(IllegalArgumentException.class,
				() -> PackedKeyCodec.segmentKey(0, 0, PackedKeyCodec.MAX_TIME_BIN + 1));
		assertThrows(IllegalArgumentException.class,
				() -> PackedKeyCodec.segmentKey(-1, 0, 0));
		assertThrows(IllegalArgumentException.class,
				() -> PackedKeyCodec.segmentKey(0, -1, 0));
		assertThrows(IllegalArgumentException.class,
				() -> PackedKeyCodec.segmentKey(0, 0, -1));
	}

	@Test
	void ssspKeyRoundTrips() {
		long key = PackedKeyCodec.ssspKey(230_000, 144);
		assertEquals(230_000, PackedKeyCodec.ssspOrigin(key));
		assertEquals(144, PackedKeyCodec.ssspBin(key));

		long max = PackedKeyCodec.ssspKey(PackedKeyCodec.MAX_LINK_INDEX, PackedKeyCodec.MAX_TIME_BIN);
		assertEquals(PackedKeyCodec.MAX_LINK_INDEX, PackedKeyCodec.ssspOrigin(max));
		assertEquals(PackedKeyCodec.MAX_TIME_BIN, PackedKeyCodec.ssspBin(max));
	}

	@Test
	void ssspKeyBoundsThrow() {
		assertThrows(IllegalArgumentException.class,
				() -> PackedKeyCodec.ssspKey(PackedKeyCodec.MAX_LINK_INDEX + 1, 0));
		assertThrows(IllegalArgumentException.class,
				() -> PackedKeyCodec.ssspKey(0, PackedKeyCodec.MAX_TIME_BIN + 1));
		assertThrows(IllegalArgumentException.class,
				() -> PackedKeyCodec.ssspKey(-1, 0));
	}

	@Test
	void distinctInputsGiveDistinctKeys() {
		assertNotEquals(PackedKeyCodec.segmentKey(1, 2, 3), PackedKeyCodec.segmentKey(2, 1, 3));
		assertNotEquals(PackedKeyCodec.segmentKey(1, 2, 3), PackedKeyCodec.segmentKey(1, 2, 4));
		assertNotEquals(PackedKeyCodec.segmentKey(1, 2, 3), PackedKeyCodec.segmentKey(1, 3, 2));
		assertNotEquals(PackedKeyCodec.ssspKey(1, 2), PackedKeyCodec.ssspKey(2, 1));
	}
}
