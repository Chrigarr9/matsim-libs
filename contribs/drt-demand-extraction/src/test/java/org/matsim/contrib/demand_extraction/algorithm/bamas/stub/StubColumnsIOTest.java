package org.matsim.contrib.demand_extraction.algorithm.bamas.stub;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.junit.jupiter.api.Test;

/**
 * Round-trip serialization of a single degree's {@link StubColumns} (Plan A3 Task 1).
 *
 * <p>The checkpoint contract is bit-identical resume, so the IO must round-trip every
 * column exactly — including the optional {@code positionsFlat} column that only the
 * degree-2 pair layer carries (Paper-2 Ext-2 copy-identity; see review addendum F1).
 */
class StubColumnsIOTest {

	@Test
	void roundTripsDegree3WithoutPositions() throws IOException {
		StubColumns sc = new StubColumns(3);
		// Five rows of known, distinct values. Orderings are opaque longs to the IO layer.
		sc.addRow(new int[] {1, 2, 3}, 0x0102L, 0x0201L, 1234, 567, (byte) 1);
		sc.addRow(new int[] {4, 5, 6}, 0x0405L, 0x0504L, 2345, 678, (byte) 0);
		sc.addRow(new int[] {7, 8, 9}, 0x0708L, 0x0807L, 3456, 789, (byte) 1);
		sc.addRow(new int[] {10, 11, 12}, 0x0A0BL, 0x0B0AL, 4567, 890, (byte) 0);
		sc.addRow(new int[] {13, 14, 15}, 0x0D0EL, 0x0E0DL, 5678, 901, (byte) 1);

		StubColumns back = roundTrip(sc);
		assertStubColumnsEqual(sc, back);
	}

	@Test
	void roundTripsDegree2WithPositions() throws IOException {
		StubColumns sc = new StubColumns(2);
		// Degree-2 pair layer: every row carries reqArray positions (the copy-identity handle).
		// Deliberately make sortedSet share an index (13) across rows but DISTINCT positions,
		// mirroring the Ext-2 hub-copy collision the positions column exists to resolve.
		sc.addRow(new int[] {13, 20}, 0x01L, 0x10L, 100, 50, (byte) 0, new int[] {3, 7});
		sc.addRow(new int[] {13, 21}, 0x01L, 0x10L, 110, 55, (byte) 1, new int[] {9, 7});
		sc.addRow(new int[] {30, 40}, 0x01L, 0x10L, 120, 60, (byte) 0, new int[] {1, 2});

		StubColumns back = roundTrip(sc);
		assertStubColumnsEqual(sc, back);
		// And specifically that the positions survived (not just the sorted indices).
		assertArrayEquals(new int[] {3, 7}, back.positionIndices(0));
		assertArrayEquals(new int[] {9, 7}, back.positionIndices(1));
		assertArrayEquals(new int[] {1, 2}, back.positionIndices(2));
	}

	@Test
	void roundTripsEmptyLayer() throws IOException {
		StubColumns sc = new StubColumns(5);
		StubColumns back = roundTrip(sc);
		assertEquals(5, back.degree());
		assertEquals(0, back.size());
	}

	@Test
	void rejectsVersionMismatch() throws IOException {
		StubColumns sc = new StubColumns(3);
		sc.addRow(new int[] {1, 2, 3}, 1L, 2L, 10, 20, (byte) 0);
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		StubColumnsIO.write(sc, buf);
		byte[] bytes = buf.toByteArray();
		// Corrupt the version int (first 4 bytes are MAGIC, next 4 are VERSION).
		bytes[4] = (byte) 0x7F;
		bytes[5] = (byte) 0x7F;
		assertThrows(IOException.class,
				() -> StubColumnsIO.read(new ByteArrayInputStream(bytes)));
	}

	@Test
	void rejectsBadMagic() throws IOException {
		StubColumns sc = new StubColumns(2);
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		StubColumnsIO.write(sc, buf);
		byte[] bytes = buf.toByteArray();
		bytes[0] = (byte) 0xFF;
		assertThrows(IOException.class,
				() -> StubColumnsIO.read(new ByteArrayInputStream(bytes)));
	}

	// ------------------------------------------------------------------

	private static StubColumns roundTrip(StubColumns sc) throws IOException {
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		StubColumnsIO.write(sc, buf);
		return StubColumnsIO.read(new ByteArrayInputStream(buf.toByteArray()));
	}

	private static void assertStubColumnsEqual(StubColumns a, StubColumns b) {
		assertEquals(a.degree(), b.degree(), "degree");
		assertEquals(a.size(), b.size(), "size");
		for (int r = 0; r < a.size(); r++) {
			assertArrayEquals(a.requestIndices(r), b.requestIndices(r), "setsFlat row " + r);
			assertEquals(a.originOrder(r), b.originOrder(r), "originOrder row " + r);
			assertEquals(a.destOrder(r), b.destOrder(r), "destOrder row " + r);
			assertEquals(a.rideDistanceDm(r), b.rideDistanceDm(r), "rideDistanceDm row " + r);
			assertEquals(a.travelTimeDs(r), b.travelTimeDs(r), "travelTimeDs row " + r);
			assertEquals(a.flags(r), b.flags(r), "flags row " + r);
		}
	}
}
