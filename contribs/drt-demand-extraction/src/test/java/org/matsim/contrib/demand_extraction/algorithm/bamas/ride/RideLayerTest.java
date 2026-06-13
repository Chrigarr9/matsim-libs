package org.matsim.contrib.demand_extraction.algorithm.bamas.ride;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;

class RideLayerTest {

	/** Build a minimal DrtRequest sufficient for startTime derivation. */
	private static DrtRequest req(int index, double requestTime) {
		return DrtRequest.builder()
				.index(index)
				.personId(Id.createPersonId("p" + index))
				.requestTime(requestTime)
				.directTravelTime(0.0)
				.directDistance(0.0)
				.earliestDeparture(0.0)
				.latestArrival(1.0)
				.maxDetourFactor(1.5)
				.build();
	}

	// -----------------------------------------------------------------------
	// Core column round-trip (two rows, large non-contiguous request indices)
	// -----------------------------------------------------------------------

	@Test
	void twoRowsColumnRoundTrip() {
		RideLayer cols = new RideLayer(3);

		// Row 0 — large, non-contiguous global request indices
		int[] set0 = {17, 4099, 65537};
		long orig0 = OrderingCodec.pack(new int[]{2, 0, 1}); // local permutation
		long dest0 = OrderingCodec.pack(new int[]{0, 2, 1});
		int dist0 = 123456;  // dm
		int tt0 = 7890;      // ds
		byte flags0 = (byte) 0b00000101;

		int r0 = cols.addRow(set0, orig0, dest0, dist0, tt0, flags0);

		// Row 1 — different values; smaller indices
		int[] set1 = {2, 88, 91};
		long orig1 = OrderingCodec.pack(new int[]{1, 2, 0});
		long dest1 = OrderingCodec.pack(new int[]{0, 1, 2});
		int dist1 = 999;
		int tt1 = 333;
		byte flags1 = (byte) 0xFF;

		int r1 = cols.addRow(set1, orig1, dest1, dist1, tt1, flags1);

		// Row indices
		assertEquals(0, r0);
		assertEquals(1, r1);

		// size / degree
		assertEquals(2, cols.size());
		assertEquals(3, cols.degree());

		// Row 0 scalars
		assertEquals(orig0, cols.originOrder(0));
		assertEquals(dest0, cols.destOrder(0));
		assertEquals(dist0, cols.rideDistanceDm(0));
		assertEquals(tt0, cols.travelTimeDs(0));
		assertEquals(flags0, cols.flags(0));

		// Row 1 scalars
		assertEquals(orig1, cols.originOrder(1));
		assertEquals(dest1, cols.destOrder(1));
		assertEquals(dist1, cols.rideDistanceDm(1));
		assertEquals(tt1, cols.travelTimeDs(1));
		assertEquals(flags1, cols.flags(1));

		// setsFlat slicing — proves [row*d, row*d+d) layout
		assertArrayEquals(set0, cols.requestIndices(0));
		assertArrayEquals(set1, cols.requestIndices(1));
	}

	// -----------------------------------------------------------------------
	// Growth doubling (20 rows → forces at least one resize)
	// -----------------------------------------------------------------------

	@Test
	void growthDoublingPreservesEarlyRow() {
		RideLayer cols = new RideLayer(3);

		int[] set0 = {100, 200, 300};
		long orig0 = OrderingCodec.pack(new int[]{0, 1, 2});
		long dest0 = OrderingCodec.pack(new int[]{2, 1, 0});
		int dist0 = 42;
		int tt0 = 77;
		byte flags0 = (byte) 3;

		cols.addRow(set0, orig0, dest0, dist0, tt0, flags0);

		// Add 19 more rows (junk values)
		for (int i = 1; i < 20; i++) {
			cols.addRow(new int[]{i, i + 1, i + 2}, 0L, 0L, i, i, (byte) 0);
		}

		assertEquals(20, cols.size());

		// Row 0 is still intact after multiple doublings
		assertEquals(orig0, cols.originOrder(0));
		assertEquals(dest0, cols.destOrder(0));
		assertEquals(dist0, cols.rideDistanceDm(0));
		assertEquals(tt0, cols.travelTimeDs(0));
		assertEquals(flags0, cols.flags(0));
		assertArrayEquals(set0, cols.requestIndices(0));
	}

	// -----------------------------------------------------------------------
	// startTime helper (tests the double indirection: unpack → setsFlat → table)
	// -----------------------------------------------------------------------

	/**
	 * Origin ordering for this row is {1, 2, 0}: firstLocal = 1.
	 * Set is {2, 5, 9}: globalIdx = setsFlat[row*3 + 1] = 5.
	 * requestTable[5].requestTime = 99000.0.
	 *
	 * A naive implementation that uses setsFlat[row*d + 0] (=2) or
	 * requestTable[firstLocal] (=requestTable[1]) would produce the wrong value.
	 */
	@Test
	void startTimeDoesDoubleIndirection() {
		RideLayer cols = new RideLayer(3);

		int[] set = {2, 5, 9};
		// firstLocal = 1 (origin ordering picks local position 1 first)
		long originPacked = OrderingCodec.pack(new int[]{1, 2, 0});
		long destPacked   = OrderingCodec.pack(new int[]{0, 1, 2});
		cols.addRow(set, originPacked, destPacked, 100, 200, (byte) 0);

		// Build a tiny requestTable with 10 entries
		DrtRequest[] table = new DrtRequest[10];
		for (int i = 0; i < 10; i++) {
			table[i] = req(i, i * 1000.0); // requestTime = i * 1000
		}
		// table[5].requestTime == 5000.0

		double st = cols.startTime(0, table);
		assertEquals(5000.0, st, 1e-9,
				"startTime must use firstLocal=1 → globalIdx=setsFlat[1]=5 → table[5].requestTime=5000");
	}

	// -----------------------------------------------------------------------
	// Degree / set-length mismatch guard
	// -----------------------------------------------------------------------

	@Test
	void addRowRejectsWrongSetLength() {
		RideLayer cols = new RideLayer(3);
		assertThrows(IllegalArgumentException.class,
				() -> cols.addRow(new int[]{1, 2}, 0L, 0L, 0, 0, (byte) 0),
				"addRow must reject set length != degree");
	}
}
