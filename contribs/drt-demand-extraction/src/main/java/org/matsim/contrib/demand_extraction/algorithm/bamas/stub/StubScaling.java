package org.matsim.contrib.demand_extraction.algorithm.bamas.stub;

/**
 * Scaling helpers for the decimeter / decisecond integer representation used in
 * {@link StubColumns}.
 *
 * <h3>Bit-exactness contract</h3>
 * {@code Ride} rounds {@code rideDistance} and {@code rideTravelTime} to one decimal
 * place via {@code Math.round(x * 10.0) / 10.0}.  The resulting double is always of
 * the form {@code k / 10.0} where {@code k} is a non-negative integer.
 *
 * <p>{@link #toDeci(double)} stores that double as the integer {@code k}; {@link #fromDeci(int)}
 * reconstructs it as {@code k / 10.0}.  Because IEEE-754 represents {@code k / 10.0}
 * identically in both paths (same division, same bit pattern), the formatted value
 * {@code String.format(java.util.Locale.US, "%.2f", fromDeci(toDeci(rounded)))} is
 * bit-for-bit identical to {@code String.format(java.util.Locale.US, "%.2f", rounded)}.
 *
 * <h3>No-epsilon equivalence for comparisons</h3>
 * {@code BamasRideExtender.compareParentCanonicalKey} treats distances within
 * {@code EPSILON = 1e-9} as equal.  Distinct 0.1-rounded values differ by at least
 * ~0.1, which is far larger than 1e-9.  Therefore <em>epsilon-equal &hArr; int-equal</em>:
 * an {@code Integer.compare} on the decimeter columns is exactly equivalent to the
 * double comparison with epsilon, with no information loss.
 *
 * <p>This class is pure (no dependencies on MATSim or other project classes) and
 * stateless.
 */
public final class StubScaling {

	private StubScaling() {}

	/**
	 * Convert a 0.1-rounded distance/time value to integer decimetres or deciseconds.
	 *
	 * <p>The argument must already be of the form {@code Math.round(x*10.0)/10.0}
	 * (i.e. already rounded to one decimal place) as {@link org.matsim.contrib.demand_extraction.algorithm.domain.Ride}
	 * stores it.  Passing an un-rounded value will still produce an integer but the
	 * round-trip contract only holds for values that went through {@code Ride}'s own
	 * rounding step.
	 *
	 * @param rounded a 0.1-rounded non-negative double
	 * @return the integer tenths ({@code Math.round(rounded * 10.0)})
	 */
	public static int toDeci(double rounded) {
		return (int) Math.round(rounded * 10.0);
	}

	/**
	 * Reconstruct the 0.1-rounded double from its integer decimes/deciseconds.
	 *
	 * @param deci integer tenths as returned by {@link #toDeci(double)}
	 * @return {@code deci / 10.0} — bit-identical to the original rounded value
	 */
	public static double fromDeci(int deci) {
		return deci / 10.0;
	}
}
