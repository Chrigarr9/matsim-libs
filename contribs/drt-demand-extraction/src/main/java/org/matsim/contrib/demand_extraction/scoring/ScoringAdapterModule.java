package org.matsim.contrib.demand_extraction.scoring;

import java.util.Collections;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contribs.discrete_mode_choice.components.estimators.MATSimTripScoringEstimator;
import org.matsim.contribs.discrete_mode_choice.components.utils.PTWaitingTimeEstimator;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.TripEstimator;
import org.matsim.core.config.Config;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.router.TripRouter;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;
import org.matsim.core.utils.timing.TimeInterpretation;
import org.matsim.facilities.ActivityFacilities;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Provider;

/**
 * Guice module that resolves and binds the appropriate
 * {@link DemandExtractionScoringAdapter}.
 *
 * <h2>Resolution order</h2>
 * <ol>
 *   <li>Explicit config: {@code exmas.scoringAdapter = "planCalcScore" | "dmc" | "eqasim"}</li>
 *   <li>Auto-detect: eqasim bindings present? → eqasim adapter</li>
 *   <li>Auto-detect: DMC TripEstimator bound? → DMC adapter</li>
 *   <li>Default: PlanCalcScore adapter</li>
 * </ol>
 *
 * <p>Ambiguous auto-detection → fail fast.
 */
public class ScoringAdapterModule extends AbstractModule {

	private static final Logger log = LogManager.getLogger(ScoringAdapterModule.class);

	@Override
	public void install() {
		bind(DemandExtractionScoringAdapter.class).toProvider(AdapterProvider.class).asEagerSingleton();
	}

	static class AdapterProvider implements Provider<DemandExtractionScoringAdapter> {

		@Inject
		private ExMasConfigGroup exMasConfig;

		@Inject
		private ScoringParametersForPerson scoringParametersForPerson;

		@Inject
		private Injector injector;

		@Override
		public DemandExtractionScoringAdapter get() {
			String configured = exMasConfig.getScoringAdapter();

			if (configured != null && !configured.equals("auto")) {
				return resolveExplicit(configured);
			}

			return autoDetect();
		}

		private DemandExtractionScoringAdapter resolveExplicit(String name) {
			switch (name) {
				case "planCalcScore":
					log.info("Scoring adapter: planCalcScore (explicit config)");
					return new PlanCalcScoreAdapter(scoringParametersForPerson);

				case "dmc":
					log.info("Scoring adapter: DMC MATSimTripScoring (explicit config)");
					return createDmcAdapter();

				case "eqasim":
					log.info("Scoring adapter: eqasim (explicit config)");
					return createEqasimAdapter();

				default:
					throw new IllegalStateException(
							"Unknown scoring adapter: '" + name + "'. " +
									"Valid values: auto, planCalcScore, dmc, eqasim");
			}
		}

		private DemandExtractionScoringAdapter autoDetect() {
			// Eqasim: classes on classpath AND EqasimConfigGroup in config with estimators
			boolean eqasimConfigured = isEqasimConfigured();
			boolean dmcPresent = isDmcPresent();

			if (eqasimConfigured) {
				log.info("Scoring adapter: eqasim (auto-detected via EqasimConfigGroup)");
				return createEqasimAdapter();
			}

			if (dmcPresent) {
				log.info("Scoring adapter: DMC MATSimTripScoring (auto-detected)");
				return createDmcAdapter();
			}

			log.info("Scoring adapter: planCalcScore (default)");
			return new PlanCalcScoreAdapter(scoringParametersForPerson);
		}

		private boolean isEqasimConfigured() {
			if (!EqasimRuntimeProbe.isEqasimPresent()) {
				return false;
			}
			// Check if EqasimConfigGroup is in the config with estimator mappings
			try {
				var eqasimConfig = injector.getInstance(
						org.eqasim.core.components.config.EqasimConfigGroup.class);
				return eqasimConfig != null && !eqasimConfig.getEstimators().isEmpty();
			} catch (Exception e) {
				return false;
			}
		}

		private boolean isDmcPresent() {
			try {
				Class.forName("org.matsim.contribs.discrete_mode_choice.model.trip_based.TripEstimator");
			} catch (ClassNotFoundException e) {
				return false;
			}
			try {
				var binding = injector.getExistingBinding(
						com.google.inject.Key.get(TripEstimator.class));
				return binding != null;
			} catch (Exception e) {
				return false;
			}
		}

		/**
		 * Create DMC adapter with a wrapped TripRouter that supports routing override.
		 *
		 * <p>Builds a new {@link MATSimTripScoringEstimator} with a TripRouter whose
		 * routing modules are wrapped in {@link OverridableRoutingModule}. This ensures
		 * that when the adapter sets the routing override (via {@link RoutingOverrideManager}),
		 * the estimator's internal routing returns our pre-routed elements instead of
		 * re-routing through MATSim's DRT/PT modules.
		 */
		private DmcMatSimTripAdapter createDmcAdapter() {
			try {
				TripRouter originalRouter = injector.getInstance(TripRouter.class);
				Config config = injector.getInstance(Config.class);
				ActivityFacilities facilities = injector.getInstance(ActivityFacilities.class);
				TimeInterpretation timeInterpretation = injector.getInstance(TimeInterpretation.class);

				// Build a new TripRouter with all modules wrapped for override support
				TripRouter wrappedRouter = buildOverridableTripRouter(originalRouter, config);

				// Get or create PT waiting time estimator
				PTWaitingTimeEstimator waitingTimeEstimator;
				try {
					waitingTimeEstimator = injector.getInstance(PTWaitingTimeEstimator.class);
				} catch (Exception e) {
					// No PTWaitingTimeEstimator bound — use null estimator (zero wait time)
					waitingTimeEstimator = new org.matsim.contribs.discrete_mode_choice.components.utils.NullWaitingTimeEstimator();
					log.info("No PTWaitingTimeEstimator bound, using NullWaitingTimeEstimator");
				}

				// Construct our own MATSimTripScoringEstimator with the wrapped TripRouter
				TripEstimator estimator = new MATSimTripScoringEstimator(
						facilities, wrappedRouter, waitingTimeEstimator,
						scoringParametersForPerson, timeInterpretation,
						Collections.singleton("pt"));

				log.info("DMC adapter created with overridable TripRouter ({} wrapped routing modules)",
						wrappedRouter.getRegisteredModes().size());

				return new DmcMatSimTripAdapter(estimator, scoringParametersForPerson);
			} catch (Exception e) {
				throw new IllegalStateException(
						"DMC adapter requested but could not be created. " +
								"Ensure DiscreteModeChoice module is installed.", e);
			}
		}

		/**
		 * Build a TripRouter clone with all routing modules wrapped in
		 * {@link OverridableRoutingModule}.
		 */
		private TripRouter buildOverridableTripRouter(TripRouter original, Config config) {
			TripRouter.Builder builder = new TripRouter.Builder(config);
			for (String mode : original.getRegisteredModes()) {
				builder.setRoutingModule(mode,
						new OverridableRoutingModule(original.getRoutingModule(mode)));
			}
			return builder.build();
		}

		private EqasimScoringAdapter createEqasimAdapter() {
			return EqasimAdapterFactory.create(injector, scoringParametersForPerson,
					this::buildOverridableTripRouter);
		}
	}
}
