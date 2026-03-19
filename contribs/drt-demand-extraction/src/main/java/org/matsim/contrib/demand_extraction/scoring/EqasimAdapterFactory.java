package org.matsim.contrib.demand_extraction.scoring;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eqasim.core.components.config.EqasimConfigGroup;
import org.eqasim.core.simulation.mode_choice.epsilon.EpsilonProvider;
import org.eqasim.core.simulation.mode_choice.parameters.ModeParameters;
import org.eqasim.core.simulation.mode_choice.utilities.EqasimUtilityEstimator;
import org.eqasim.core.simulation.mode_choice.utilities.UtilityEstimator;
import org.eqasim.core.simulation.policies.utility.UtilityPenalty;
import org.matsim.core.config.Config;
import org.matsim.core.router.TripRouter;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;
import org.matsim.core.utils.timing.TimeInterpretation;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.TripEstimator;
import org.matsim.facilities.ActivityFacilities;

import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Provider;
import com.google.inject.util.Types;

/**
 * Factory for creating {@link EqasimScoringAdapter} from a Guice injector.
 *
 * <p>Encapsulates the ~60 lines of Guice wiring needed to construct an eqasim
 * adapter with a wrapped TripRouter for routing override support.
 */
final class EqasimAdapterFactory {

	private static final Logger log = LogManager.getLogger(EqasimAdapterFactory.class);

	private EqasimAdapterFactory() {
		// Factory class
	}

	/**
	 * Create an {@link EqasimScoringAdapter} by resolving eqasim components from Guice.
	 *
	 * @param injector                 the Guice injector
	 * @param scoringParametersForPerson scoring parameters (unused directly, but passed for consistency)
	 * @param buildOverridableTripRouter function to wrap a TripRouter for routing override
	 * @return the configured adapter
	 * @throws IllegalStateException if eqasim classes/bindings are missing
	 */
	static EqasimScoringAdapter create(Injector injector,
			ScoringParametersForPerson scoringParametersForPerson,
			TripRouterWrapper tripRouterWrapper) {

		if (!EqasimRuntimeProbe.isEqasimPresent()) {
			throw new IllegalStateException(
					"Eqasim adapter requested but eqasim classes not found on classpath.");
		}

		EqasimRuntimeProbe.EqasimCostParameters costParams =
				EqasimRuntimeProbe.readCostParameters(injector);

		// Build eqasim estimator with wrapped TripRouter for routing override support.
		// We must construct a NEW EqasimUtilityEstimator with the wrapped router —
		// the Guice-bound one has the original unwrapped router.
		TripEstimator estimator;
		try {
			TripRouter originalRouter = injector.getInstance(TripRouter.class);
			Config config = injector.getInstance(Config.class);
			TripRouter wrappedRouter = tripRouterWrapper.wrap(originalRouter, config);
			ActivityFacilities facilities = injector.getInstance(ActivityFacilities.class);
			TimeInterpretation timeInterpretation = injector.getInstance(TimeInterpretation.class);

			// Get the per-mode utility estimators and epsilon/penalty from Guice
			@SuppressWarnings("unchecked")
			Map<String, Provider<UtilityEstimator>> estimatorProviders =
					(Map<String, Provider<UtilityEstimator>>)
					injector.getInstance(Key.get(
							Types.mapOf(String.class,
									Types.providerOf(UtilityEstimator.class))));

			EqasimConfigGroup eqasimConfig =
					injector.getInstance(EqasimConfigGroup.class);
			EpsilonProvider epsilonProvider =
					injector.getInstance(EpsilonProvider.class);
			UtilityPenalty utilityPenalty =
					injector.getInstance(UtilityPenalty.class);

			// Build per-mode estimator map (same logic as EqasimModeChoiceModule)
			Map<String, UtilityEstimator> estimators = new HashMap<>();
			for (Map.Entry<String, String> entry : eqasimConfig.getEstimators().entrySet()) {
				var factory = estimatorProviders.get(entry.getValue());
				if (factory != null) {
					estimators.put(entry.getKey(), factory.get());
				}
			}

			// Construct EqasimUtilityEstimator with wrapped TripRouter
			estimator = new EqasimUtilityEstimator(
					wrappedRouter, facilities, estimators, timeInterpretation,
					Collections.emptySet(), epsilonProvider, utilityPenalty);

			log.info("Eqasim adapter created with overridable TripRouter ({} wrapped routing modules, {} mode estimators)",
					wrappedRouter.getRegisteredModes().size(), estimators.size());
		} catch (Exception e) {
			throw new IllegalStateException(
					"Eqasim adapter: could not construct estimator. " +
							"Ensure eqasim module is installed.", e);
		}

		ModeParameters modeParams = injector.getInstance(ModeParameters.class);

		return new EqasimScoringAdapter(estimator, costParams, modeParams);
	}

	/**
	 * Functional interface for wrapping a TripRouter with override support.
	 */
	@FunctionalInterface
	interface TripRouterWrapper {
		TripRouter wrap(TripRouter original, Config config);
	}
}
