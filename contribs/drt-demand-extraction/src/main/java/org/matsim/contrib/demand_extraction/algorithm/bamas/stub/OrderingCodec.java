package org.matsim.contrib.demand_extraction.algorithm.bamas.stub;

/**
 * Packs a ride's pickup/dropoff ordering into a single {@code long} using 4 bits per position.
 *
 * <p><strong>Contract — local positions only:</strong> the values stored here are
 * <em>local positions 0..d-1 within the sorted request set</em>, NEVER global request indices.
 * Global request indices run to ~109k at 100% sample fraction; {@code & 0xF} would silently
 * truncate them. The global&lt;-&gt;local conversion belongs at the stub boundary (a separate task)
 * and must be applied before calling {@link #pack} and after calling {@link #unpack}.</p>
 *
 * <p>Supports permutations of degree &lt;= 16 (64 bits / 4 bits per position).</p>
 */
public final class OrderingCodec {
	private OrderingCodec() {}

	/** Pack a permutation of [0..d-1], d<=16, as 4 bits per position. */
	public static long pack(int[] order) {
		long p = 0L;
		for (int i = 0; i < order.length; i++) {
			p |= ((long) (order[i] & 0xF)) << (4 * i);
		}
		return p;
	}

	public static int[] unpack(long packed, int degree) {
		int[] order = new int[degree];
		for (int i = 0; i < degree; i++) {
			order[i] = (int) ((packed >>> (4 * i)) & 0xF);
		}
		return order;
	}
}
