package org.matsim.contrib.demand_extraction.scoring;

import org.junit.jupiter.api.Test;
import org.matsim.contrib.demand_extraction.scoring.EqasimRuntimeProbe.EqasimCostParameters;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EqasimRuntimeProbe.EqasimCostParameters} distance interaction formula.
 *
 * <p>Uses Bavaria eqasim parameters:
 * <ul>
 *   <li>betaCost = -0.311 utils/EUR</li>
 *   <li>lambda = -0.258 (distance interaction exponent)</li>
 *   <li>referenceDistance = 4.4 km</li>
 * </ul>
 *
 * <p>Formula: margUtilMoney(d) = |betaCost| * (d / refDist) ^ lambda
 * <p>At reference distance: interaction = 1.0 so margUtilMoney = |betaCost| = 0.311
 * <p>Shorter trips have higher cost sensitivity (lambda < 0 -> (d/ref)^lambda > 1 for d < ref)
 * <p>Longer trips have lower cost sensitivity (lambda < 0 -> (d/ref)^lambda < 1 for d > ref)
 */
class EqasimCostParametersTest {

	// Bavaria eqasim parameters
	private static final double BETA_COST = -0.311;
	private static final double LAMBDA = -0.258;
	private static final double REF_DIST_KM = 4.4;

	private final EqasimCostParameters params = new EqasimCostParameters(BETA_COST, LAMBDA, REF_DIST_KM);

	@Test
	void testAtReferenceDistance() {
		// At reference distance, interaction = (refDist/refDist)^lambda = 1.0^lambda = 1.0
		// margUtilMoney = |betaCost| * 1.0 = 0.311
		double result = params.marginalUtilityOfMoney(REF_DIST_KM);

		assertEquals(Math.abs(BETA_COST), result, 1e-9,
				"At reference distance, margUtilMoney should equal |betaCost|");
		assertEquals(0.311, result, 1e-9);
	}

	@Test
	void testShorterTripHigherCostSensitivity() {
		// At 2km (shorter than 4.4km reference), cost sensitivity should be HIGHER
		// (d/ref)^lambda = (2.0/4.4)^(-0.258)
		// = (0.4545)^(-0.258)
		// Since lambda < 0 and (d/ref) < 1: interaction > 1
		double at2km = params.marginalUtilityOfMoney(2.0);
		double atRef = params.marginalUtilityOfMoney(REF_DIST_KM);

		assertTrue(at2km > atRef,
				"Shorter trips should have HIGHER cost sensitivity. " +
						"at2km=" + at2km + " vs atRef=" + atRef);

		// Hand calculation: (2.0/4.4)^(-0.258) = (0.4545...)^(-0.258)
		// = 1 / (0.4545)^0.258
		// ln(0.4545) = -0.78846
		// 0.258 * (-0.78846) = -0.20342
		// (0.4545)^0.258 = exp(-0.20342) = 0.8160
		// interaction = 1 / 0.8160 = 1.2255
		// margUtilMoney = 0.311 * 1.2255 = 0.3811
		double expectedInteraction = Math.pow(2.0 / REF_DIST_KM, LAMBDA);
		double expected = Math.abs(BETA_COST) * expectedInteraction;

		assertEquals(expected, at2km, 1e-6,
				"At 2km, margUtilMoney should match hand calculation");
	}

	@Test
	void testLongerTripLowerCostSensitivity() {
		// At 10km (longer than 4.4km reference), cost sensitivity should be LOWER
		// (d/ref)^lambda = (10.0/4.4)^(-0.258) = (2.2727)^(-0.258)
		// Since lambda < 0 and (d/ref) > 1: interaction < 1
		double at10km = params.marginalUtilityOfMoney(10.0);
		double atRef = params.marginalUtilityOfMoney(REF_DIST_KM);

		assertTrue(at10km < atRef,
				"Longer trips should have LOWER cost sensitivity. " +
						"at10km=" + at10km + " vs atRef=" + atRef);

		// Hand calculation: (10.0/4.4)^(-0.258) = (2.2727)^(-0.258)
		// ln(2.2727) = 0.82098
		// 0.258 * 0.82098 = 0.21181
		// (2.2727)^0.258 = exp(0.21181) = 1.2360
		// interaction = 1 / 1.2360 = 0.8091
		// margUtilMoney = 0.311 * 0.8091 = 0.2516
		double expectedInteraction = Math.pow(10.0 / REF_DIST_KM, LAMBDA);
		double expected = Math.abs(BETA_COST) * expectedInteraction;

		assertEquals(expected, at10km, 1e-6,
				"At 10km, margUtilMoney should match hand calculation");
	}

	@Test
	void testMonotonicityOverRange() {
		// With lambda < 0, margUtilMoney should DECREASE as distance increases
		double prev = params.marginalUtilityOfMoney(0.5);
		for (double d = 1.0; d <= 50.0; d += 0.5) {
			double current = params.marginalUtilityOfMoney(d);
			assertTrue(current <= prev + 1e-12,
					"margUtilMoney should decrease with distance. " +
							"At d=" + (d - 0.5) + ": " + prev + ", at d=" + d + ": " + current);
			prev = current;
		}
	}

	@Test
	void testZeroDistanceFallsBackToAbsBetaCost() {
		// Edge case: zero or negative distance should return |betaCost|
		double atZero = params.marginalUtilityOfMoney(0.0);
		assertEquals(Math.abs(BETA_COST), atZero, 1e-9,
				"Zero distance should return |betaCost| as fallback");

		double atNegative = params.marginalUtilityOfMoney(-5.0);
		assertEquals(Math.abs(BETA_COST), atNegative, 1e-9,
				"Negative distance should return |betaCost| as fallback");
	}

	@Test
	void testZeroRefDistanceFallsBackToAbsBetaCost() {
		// Edge case: zero reference distance
		EqasimCostParameters zeroRefParams = new EqasimCostParameters(BETA_COST, LAMBDA, 0.0);
		double result = zeroRefParams.marginalUtilityOfMoney(5.0);
		assertEquals(Math.abs(BETA_COST), result, 1e-9,
				"Zero reference distance should return |betaCost| as fallback");
	}

	@Test
	void testAlwaysPositive() {
		// margUtilMoney should always be positive regardless of betaCost sign
		EqasimCostParameters negBeta = new EqasimCostParameters(-0.5, -0.3, 5.0);
		EqasimCostParameters posBeta = new EqasimCostParameters(0.5, -0.3, 5.0);

		for (double d = 1.0; d <= 20.0; d += 1.0) {
			assertTrue(negBeta.marginalUtilityOfMoney(d) > 0,
					"Should be positive with negative betaCost at d=" + d);
			assertTrue(posBeta.marginalUtilityOfMoney(d) > 0,
					"Should be positive with positive betaCost at d=" + d);
		}
	}

	@Test
	void testVeryShortDistance() {
		// Very short distance (0.1 km) should have high cost sensitivity
		double atVeryShort = params.marginalUtilityOfMoney(0.1);
		double atRef = params.marginalUtilityOfMoney(REF_DIST_KM);

		assertTrue(atVeryShort > atRef * 1.5,
				"Very short distance (0.1km) should have significantly higher cost sensitivity. " +
						"at0.1km=" + atVeryShort + " vs atRef=" + atRef);

		double expectedInteraction = Math.pow(0.1 / REF_DIST_KM, LAMBDA);
		double expected = Math.abs(BETA_COST) * expectedInteraction;
		assertEquals(expected, atVeryShort, 1e-6);
	}

	@Test
	void testVeryLongDistance() {
		// Very long distance (100 km) should have low cost sensitivity
		double atVeryLong = params.marginalUtilityOfMoney(100.0);
		double atRef = params.marginalUtilityOfMoney(REF_DIST_KM);

		assertTrue(atVeryLong < atRef * 0.5,
				"Very long distance (100km) should have significantly lower cost sensitivity. " +
						"at100km=" + atVeryLong + " vs atRef=" + atRef);

		double expectedInteraction = Math.pow(100.0 / REF_DIST_KM, LAMBDA);
		double expected = Math.abs(BETA_COST) * expectedInteraction;
		assertEquals(expected, atVeryLong, 1e-6);
	}

	@Test
	void testRecordAccessors() {
		// Verify the record's component accessors
		assertEquals(BETA_COST, params.betaCost_u_MU(), 1e-9);
		assertEquals(LAMBDA, params.lambdaCostEuclideanDistance(), 1e-9);
		assertEquals(REF_DIST_KM, params.referenceEuclideanDistance_km(), 1e-9);
	}
}
