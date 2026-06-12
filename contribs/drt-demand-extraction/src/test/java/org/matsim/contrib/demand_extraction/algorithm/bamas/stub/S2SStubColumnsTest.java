package org.matsim.contrib.demand_extraction.algorithm.bamas.stub;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class S2SStubColumnsTest {

	/** Identity ordering for degree d. */
	private static long identity(int d) {
		int[] pos = new int[d];
		for (int i = 0; i < d; i++) pos[i] = i;
		return OrderingCodec.pack(pos);
	}

	// -----------------------------------------------------------------------
	// Append one degree-3 row: degree/size, stop ids, D2D getters
	// -----------------------------------------------------------------------

	@Test
	void appendOneRow_degreeAndSizeCorrect() {
		S2SStubColumns cols = new S2SStubColumns(3);

		int[] set = {0, 1, 2};
		long orig = identity(3);
		long dest = OrderingCodec.pack(new int[]{2, 1, 0});
		int dist = 12345;
		int tt   = 6789;
		byte fl  = (byte) 0b00000011;
		int pickup  = 7;
		int dropoff = 13;
		double[] access  = {1.5, 2.5, 3.5};
		double[] egress  = {4.5, 5.5, 6.5};

		double startTime = 28800.0;
		int rideIdx = 42;
		int row = cols.addRow(set, orig, dest, dist, tt, fl, pickup, dropoff, startTime, rideIdx, access, egress);

		assertEquals(0, row, "first row index must be 0");
		assertEquals(3, cols.degree());
		assertEquals(1, cols.size());
	}

	// -----------------------------------------------------------------------
	// Read back pickup/dropoff stop ids and D2D getters
	// -----------------------------------------------------------------------

	@Test
	void readBackStopIdsAndD2D() {
		S2SStubColumns cols = new S2SStubColumns(3);

		int[] set = {10, 20, 30};
		long orig = identity(3);
		long dest = OrderingCodec.pack(new int[]{1, 0, 2});
		int dist = 99999;
		int tt   = 11111;
		byte fl  = (byte) 0xAB;
		int pickup  = 42;
		int dropoff = 55;
		double[] access = {0.1, 0.2, 0.3};
		double[] egress = {0.4, 0.5, 0.6};

		double startTime = 36000.0;
		int rideIdx = 100;
		cols.addRow(set, orig, dest, dist, tt, fl, pickup, dropoff, startTime, rideIdx, access, egress);

		// Stop ids
		assertEquals(pickup,  cols.pickupStopId(0));
		assertEquals(dropoff, cols.dropoffStopId(0));

		// Start time
		assertEquals(Double.doubleToRawLongBits(startTime),
				Double.doubleToRawLongBits(cols.startTime(0)),
				"startTime must be bit-identical");

		// Ride index
		assertEquals(rideIdx, cols.rideIndex(0), "rideIndex must round-trip");

		// D2D delegates
		assertArrayEquals(set, cols.requestIndices(0));
		assertEquals(dist, cols.rideDistanceDm(0));
		assertEquals(tt,   cols.travelTimeDs(0));
		assertEquals(fl,   cols.flags(0));
		assertEquals(orig, cols.originOrder(0));
		assertEquals(dest, cols.destOrder(0));
	}

	// -----------------------------------------------------------------------
	// Bit-identical double round-trip for accessWalk and egressWalk
	// -----------------------------------------------------------------------

	@Test
	void walkArraysBitIdentical() {
		S2SStubColumns cols = new S2SStubColumns(3);

		// Include a value that loses precision as float: 123.456789012345
		double[] access = {123.456789012345, 0.0, -7.000000001};
		double[] egress = {1.0 / 3.0, Math.PI, Double.MAX_VALUE};

		double startTime = 3600.0;
		cols.addRow(new int[]{0, 1, 2}, identity(3), identity(3),
				100, 200, (byte) 0, 1, 2, startTime, 0, access, egress);

		double[] gotAccess = cols.accessWalk(0);
		double[] gotEgress = cols.egressWalk(0);

		assertEquals(3, gotAccess.length);
		assertEquals(3, gotEgress.length);

		for (int i = 0; i < 3; i++) {
			assertEquals(Double.doubleToRawLongBits(access[i]),
					Double.doubleToRawLongBits(gotAccess[i]),
					"accessWalk[" + i + "] must be bit-identical");
			assertEquals(Double.doubleToRawLongBits(egress[i]),
					Double.doubleToRawLongBits(gotEgress[i]),
					"egressWalk[" + i + "] must be bit-identical");
		}
	}

	// -----------------------------------------------------------------------
	// Two rows: first row slice unchanged, second row reads back its own values;
	// proves correct [r*d, r*d+d) slicing and growth
	// -----------------------------------------------------------------------

	@Test
	void twoRows_slicingAndGrowth() {
		S2SStubColumns cols = new S2SStubColumns(3);

		double[] access0 = {1.1, 2.2, 3.3};
		double[] egress0 = {4.4, 5.5, 6.6};
		double st0 = 7200.0;
		cols.addRow(new int[]{0, 1, 2}, identity(3), identity(3),
				111, 222, (byte) 1, 10, 20, st0, 10, access0, egress0);

		double[] access1 = {7.7, 8.8, 9.9};
		double[] egress1 = {10.0, 11.0, 12.0};
		double st1 = 14400.0;
		cols.addRow(new int[]{3, 4, 5}, identity(3), identity(3),
				333, 444, (byte) 2, 30, 40, st1, 11, access1, egress1);

		assertEquals(2, cols.size());

		// Row 0 still intact
		double[] gotAccess0 = cols.accessWalk(0);
		double[] gotEgress0 = cols.egressWalk(0);
		for (int i = 0; i < 3; i++) {
			assertEquals(Double.doubleToRawLongBits(access0[i]),
					Double.doubleToRawLongBits(gotAccess0[i]),
					"row0 accessWalk[" + i + "] must be unchanged after row1 appended");
			assertEquals(Double.doubleToRawLongBits(egress0[i]),
					Double.doubleToRawLongBits(gotEgress0[i]),
					"row0 egressWalk[" + i + "] must be unchanged after row1 appended");
		}
		assertEquals(10, cols.pickupStopId(0));
		assertEquals(20, cols.dropoffStopId(0));
		assertEquals(10, cols.rideIndex(0));

		// Row 1 reads its own values
		double[] gotAccess1 = cols.accessWalk(1);
		double[] gotEgress1 = cols.egressWalk(1);
		for (int i = 0; i < 3; i++) {
			assertEquals(Double.doubleToRawLongBits(access1[i]),
					Double.doubleToRawLongBits(gotAccess1[i]),
					"row1 accessWalk[" + i + "] mismatch");
			assertEquals(Double.doubleToRawLongBits(egress1[i]),
					Double.doubleToRawLongBits(gotEgress1[i]),
					"row1 egressWalk[" + i + "] mismatch");
		}
		assertEquals(30, cols.pickupStopId(1));
		assertEquals(40, cols.dropoffStopId(1));
		assertEquals(11, cols.rideIndex(1));
	}

	// -----------------------------------------------------------------------
	// Wrong-length accessWalk throws IllegalArgumentException
	// -----------------------------------------------------------------------

	@Test
	void wrongLengthAccessWalkThrows() {
		S2SStubColumns cols = new S2SStubColumns(3);
		assertThrows(IllegalArgumentException.class, () ->
				cols.addRow(new int[]{0, 1, 2}, identity(3), identity(3),
						100, 200, (byte) 0, 1, 2, 3600.0, 0,
						new double[]{1.0, 2.0},     // length 2, not 3
						new double[]{1.0, 2.0, 3.0}),
				"addRow with wrong-length accessWalk must throw IllegalArgumentException");
	}

	// -----------------------------------------------------------------------
	// Wrong-length egressWalk throws IllegalArgumentException
	// -----------------------------------------------------------------------

	@Test
	void wrongLengthEgressWalkThrows() {
		S2SStubColumns cols = new S2SStubColumns(3);
		assertThrows(IllegalArgumentException.class, () ->
				cols.addRow(new int[]{0, 1, 2}, identity(3), identity(3),
						100, 200, (byte) 0, 1, 2, 3600.0, 0,
						new double[]{1.0, 2.0, 3.0},
						new double[]{1.0, 2.0}),    // length 2, not 3
				"addRow with wrong-length egressWalk must throw IllegalArgumentException");
	}

	// -----------------------------------------------------------------------
	// startTime column: bit-identical round-trip
	// -----------------------------------------------------------------------

	@Test
	void startTimeBitIdentical() {
		S2SStubColumns cols = new S2SStubColumns(2);
		double startTime0 = 28800.123456789;  // value with sub-second precision
		double startTime1 = 0.0;              // boundary

		cols.addRow(new int[]{0, 1}, identity(2), identity(2),
				100, 200, (byte) 0, 5, 6, startTime0, 0,
				new double[]{1.0, 2.0}, new double[]{3.0, 4.0});
		cols.addRow(new int[]{2, 3}, identity(2), identity(2),
				300, 400, (byte) 0, 7, 8, startTime1, 1,
				new double[]{5.0, 6.0}, new double[]{7.0, 8.0});

		assertEquals(Double.doubleToRawLongBits(startTime0),
				Double.doubleToRawLongBits(cols.startTime(0)),
				"startTime row 0 must be bit-identical");
		assertEquals(Double.doubleToRawLongBits(startTime1),
				Double.doubleToRawLongBits(cols.startTime(1)),
				"startTime row 1 must be bit-identical");
	}
}
