package org.matsim.contrib.demand_extraction.scoring;

import java.lang.reflect.Field;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Runtime probe for eqasim classes via reflection.
 *
 * <p>Detects whether eqasim is on the classpath and reads its mode parameters
 * (betaCost, lambda, referenceDistance) from Guice-managed singleton objects.
 * No compile-time dependency on eqasim.
 *
 * <p>If the probe detects eqasim but cannot access the required fields,
 * startup FAILS — there is no config fallback for eqasim parameters.
 */
public final class EqasimRuntimeProbe {

	private static final Logger log = LogManager.getLogger(EqasimRuntimeProbe.class);

	private static final String MODE_PARAMETERS_CLASS = "org.eqasim.core.simulation.mode_choice.parameters.ModeParameters";

	private EqasimRuntimeProbe() {
	}

	/**
	 * Check if eqasim is on the classpath.
	 */
	public static boolean isEqasimPresent() {
		try {
			Class.forName(MODE_PARAMETERS_CLASS);
			return true;
		} catch (ClassNotFoundException e) {
			return false;
		}
	}

	/**
	 * Read eqasim's cost parameters from a Guice injector.
	 *
	 * @param injector the Guice injector (from MATSim's Controler)
	 * @return the cost parameters, or throws if fields are missing
	 * @throws IllegalStateException if eqasim is present but fields cannot be read
	 */
	public static EqasimCostParameters readCostParameters(com.google.inject.Injector injector) {
		try {
			Class<?> modeParamsClass = Class.forName(MODE_PARAMETERS_CLASS);
			Object modeParams = injector.getInstance(modeParamsClass);

			double betaCost = readDoubleField(modeParams, "betaCost_u_MU");
			double lambda = readDoubleField(modeParams, "lambdaCostEuclideanDistance");
			double referenceDistance_km = readDoubleField(modeParams, "referenceEuclideanDistance_km");

			log.info("Eqasim ModeParameters found: betaCost={}, lambda={}, refDist={}km",
					betaCost, lambda, referenceDistance_km);

			return new EqasimCostParameters(betaCost, lambda, referenceDistance_km);
		} catch (ClassNotFoundException e) {
			throw new IllegalStateException(
					"Eqasim was detected but ModeParameters class not found: " + MODE_PARAMETERS_CLASS, e);
		} catch (Exception e) {
			throw new IllegalStateException(
					"Eqasim was detected but could not read ModeParameters fields. " +
							"Check that eqasim version is compatible.", e);
		}
	}

	private static double readDoubleField(Object obj, String fieldName) throws Exception {
		Field field = obj.getClass().getField(fieldName);
		return field.getDouble(obj);
	}

	/**
	 * Eqasim cost parameters read via reflection.
	 *
	 * @param betaCost_u_MU                  marginal utility of cost (utils/MU), typically negative
	 * @param lambdaCostEuclideanDistance     distance interaction exponent
	 * @param referenceEuclideanDistance_km   reference distance for interaction (km)
	 */
	public record EqasimCostParameters(
			double betaCost_u_MU,
			double lambdaCostEuclideanDistance,
			double referenceEuclideanDistance_km
	) {

		/**
		 * Calculate distance-specific marginal utility of money.
		 * Formula: |betaCost| * (euclidDist_km / refDist_km) ^ lambda
		 *
		 * @param euclideanDistance_km trip euclidean distance in km
		 * @return marginal utility of money (positive, utils/EUR)
		 */
		public double marginalUtilityOfMoney(double euclideanDistance_km) {
			if (euclideanDistance_km <= 0 || referenceEuclideanDistance_km <= 0) {
				return Math.abs(betaCost_u_MU);
			}
			double interaction = Math.pow(
					euclideanDistance_km / referenceEuclideanDistance_km,
					lambdaCostEuclideanDistance);
			return Math.abs(betaCost_u_MU) * interaction;
		}
	}
}
