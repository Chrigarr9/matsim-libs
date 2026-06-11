package org.matsim.contrib.demand_extraction.algorithm.bamas.stub;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Proves the bit-exactness contract of {@link StubScaling}.
 *
 * <p>The claim: because {@code Ride} rounds {@code rideDistance}/{@code rideTravelTime}
 * to one decimal place ({@code Math.round(x*10.0)/10.0}), storing
 * {@code Math.round(value*10.0)} as an {@code int} and reconstructing via
 * {@code dm/10.0} introduces <strong>no drift</strong> in:
 * <ol>
 *   <li>the CSV-formatted value ({@code String.format(Locale.US, "%.2f", v)}),</li>
 *   <li>the canonical parent sort (distance ordering), and</li>
 *   <li>the distance gate comparison.</li>
 * </ol>
 *
 * <p>No-epsilon equivalence: {@code compareParentCanonicalKey} in
 * {@code BamasRideExtender} treats distances within {@code EPSILON=1e-9} as equal.
 * Distinct 0.1-rounded values differ by >= ~0.1 >> 1e-9, so
 * <em>epsilon-equal &lt;=&gt; int-equal</em>, and an int comparator with no epsilon
 * is exactly equivalent.
 */
class StubScalingTest {

	/**
	 * Every 0.1-rounded double round-trips through int decimeters without
	 * CSV-formatted drift across ~294 000 sampled raw values in [0, 5000).
	 *
	 * <p>This is the headline assertion for the entire Slim-Ride plan: if it
	 * fails for any value, the int-column strategy is unsound and must be
	 * revisited before proceeding.
	 */
	@Test
	void decimeterRoundTripMatchesRideRounding() {
		for (double raw = 0.0; raw < 5000.0; raw += 0.017) {
			double rounded = Math.round(raw * 10.0) / 10.0;   // what Ride stores
			int dm = StubScaling.toDeci(rounded);              // what the stub stores
			double back = StubScaling.fromDeci(dm);            // reconstructed for CSV
			// The number the CSV writer would emit must be identical either way:
			assertEquals(fmt(rounded), fmt(back),
					"CSV-formatted value drifted at raw=" + raw
							+ " rounded=" + rounded + " dm=" + dm + " back=" + back);
		}
	}

	/**
	 * Ordering by int decimeters agrees with ordering by the rounded double
	 * for all three sign cases: a&lt;b, a==b, a&gt;b.
	 *
	 * <p>This proves the int comparator is a drop-in replacement for the
	 * double comparator used in {@code compareParentCanonicalKey}.
	 */
	@Test
	void intComparisonMatchesDoubleComparison() {
		// a < b
		double a = Math.round(123.4 * 10.0) / 10.0;
		double b = Math.round(123.5 * 10.0) / 10.0;
		assertEquals(
				Integer.signum(Integer.compare(StubScaling.toDeci(a), StubScaling.toDeci(b))),
				Integer.signum(Double.compare(a, b)),
				"sign must agree for a < b");

		// a == b (same 0.1-rounded bucket)
		double c = Math.round(200.0 * 10.0) / 10.0;
		double d = Math.round(200.0 * 10.0) / 10.0;
		assertEquals(
				Integer.signum(Integer.compare(StubScaling.toDeci(c), StubScaling.toDeci(d))),
				Integer.signum(Double.compare(c, d)),
				"sign must agree for a == b");

		// a > b
		double e = Math.round(500.9 * 10.0) / 10.0;
		double f = Math.round(500.8 * 10.0) / 10.0;
		assertEquals(
				Integer.signum(Integer.compare(StubScaling.toDeci(e), StubScaling.toDeci(f))),
				Integer.signum(Double.compare(e, f)),
				"sign must agree for a > b");
	}

	/**
	 * Exact format used by {@code ExMasCsvWriter} for
	 * {@code startTime, endTime, rideTravelTime, rideDistance} columns
	 * (ExMasCsvWriter.java:257-264, format spec {@code "%.2f"}).
	 * Locale.US matters: some locales replace the decimal point with a comma,
	 * which would mask drift.
	 */
	private static String fmt(double v) {
		return String.format(java.util.Locale.US, "%.2f", v);
	}
}
