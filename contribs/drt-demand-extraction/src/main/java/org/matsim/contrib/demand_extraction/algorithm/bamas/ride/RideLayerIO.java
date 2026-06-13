package org.matsim.contrib.demand_extraction.algorithm.bamas.ride;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Binary serialization of one degree's {@link RideLayer} (Plan A3 — checkpoint/resume).
 *
 * <p>Streams the primitive column arrays directly, no per-row object graph. The on-disk form
 * is a small header followed by each column in row order:
 *
 * <pre>
 *   int   MAGIC          (0x53544243 = "STBC")
 *   int   VERSION        (== {@link #VERSION})
 *   int   degree
 *   int   size           (row count)
 *   byte  hasPositions   (1 = positionsFlat present, 0 = absent)
 *   int[] setsFlat       (size * degree)
 *   long[] originOrder   (size)
 *   long[] destOrder     (size)
 *   int[] rideDistanceDm (size)
 *   int[] travelTimeDs   (size)
 *   byte[] flags         (size)
 *   int[] positionsFlat  (size * degree)   -- only if hasPositions == 1
 * </pre>
 *
 * <p>All values are written raw (no rounding), so the round-trip is bit-identical — required
 * for the checkpoint's byte-identical-resume contract. The optional {@code positionsFlat}
 * column is mandatory to round-trip: the degree-2 pair layer carries it for Paper-2 Ext-2
 * copy identity (dropping it would materialize the wrong colliding request copy on resume).
 *
 * <p>The caller owns the stream: {@link #write} flushes but does not close it.
 */
public final class RideLayerIO {

	/** Magic number "STBC" — guards against reading an unrelated/corrupt file. */
	static final int MAGIC = 0x53544243;
	/** Format version — bumped on any layout change; read refuses a mismatch. */
	static final int VERSION = 1;

	private RideLayerIO() {}

	/**
	 * Write {@code sc} to {@code out} (header + all columns). Flushes; does not close.
	 */
	public static void write(RideLayer sc, OutputStream out) throws IOException {
		DataOutputStream d = new DataOutputStream(new BufferedOutputStream(out));
		int degree = sc.degree();
		int size = sc.size();
		boolean hasPositions = sc.hasPositions();

		d.writeInt(MAGIC);
		d.writeInt(VERSION);
		d.writeInt(degree);
		d.writeInt(size);
		d.writeBoolean(hasPositions);

		int[] setsFlat = sc.setsFlatRaw();
		long[] originOrder = sc.originOrderRaw();
		long[] destOrder = sc.destOrderRaw();
		int[] rideDistanceDm = sc.rideDistanceDmRaw();
		int[] travelTimeDs = sc.travelTimeDsRaw();
		byte[] flags = sc.flagsRaw();

		int flat = size * degree;
		for (int i = 0; i < flat; i++) d.writeInt(setsFlat[i]);
		for (int r = 0; r < size; r++) d.writeLong(originOrder[r]);
		for (int r = 0; r < size; r++) d.writeLong(destOrder[r]);
		for (int r = 0; r < size; r++) d.writeInt(rideDistanceDm[r]);
		for (int r = 0; r < size; r++) d.writeInt(travelTimeDs[r]);
		d.write(flags, 0, size);
		if (hasPositions) {
			int[] positionsFlat = sc.positionsFlatRaw();
			for (int i = 0; i < flat; i++) d.writeInt(positionsFlat[i]);
		}

		d.flush();
	}

	/**
	 * Read a {@link RideLayer} from {@code in}. Refuses a bad magic or version mismatch
	 * with an {@link IOException} (same posture as the checkpoint fingerprint refusal).
	 */
	public static RideLayer read(InputStream in) throws IOException {
		DataInputStream d = new DataInputStream(new BufferedInputStream(in));

		int magic = d.readInt();
		if (magic != MAGIC) {
			throw new IOException(String.format(
					"StubColumns checkpoint: bad magic 0x%08X (expected 0x%08X) — not a stub file or corrupt",
					magic, MAGIC));
		}
		int version = d.readInt();
		if (version != VERSION) {
			throw new IOException("StubColumns checkpoint: version " + version
					+ " != supported " + VERSION + " — incompatible checkpoint, rerun from scratch");
		}
		int degree = d.readInt();
		int size = d.readInt();
		boolean hasPositions = d.readBoolean();
		if (degree < 1 || size < 0) {
			throw new IOException("StubColumns checkpoint: invalid degree=" + degree + " size=" + size);
		}

		int flat = size * degree;
		int[] setsFlat = new int[flat];
		long[] originOrder = new long[size];
		long[] destOrder = new long[size];
		int[] rideDistanceDm = new int[size];
		int[] travelTimeDs = new int[size];
		byte[] flags = new byte[size];

		for (int i = 0; i < flat; i++) setsFlat[i] = d.readInt();
		for (int r = 0; r < size; r++) originOrder[r] = d.readLong();
		for (int r = 0; r < size; r++) destOrder[r] = d.readLong();
		for (int r = 0; r < size; r++) rideDistanceDm[r] = d.readInt();
		for (int r = 0; r < size; r++) travelTimeDs[r] = d.readInt();
		d.readFully(flags, 0, size);

		int[] positionsFlat = null;
		if (hasPositions) {
			positionsFlat = new int[flat];
			for (int i = 0; i < flat; i++) positionsFlat[i] = d.readInt();
		}

		return RideLayer.adopt(degree, size, setsFlat, originOrder, destOrder,
				rideDistanceDm, travelTimeDs, flags, positionsFlat);
	}
}
