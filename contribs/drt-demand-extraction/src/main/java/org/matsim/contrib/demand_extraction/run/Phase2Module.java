package org.matsim.contrib.demand_extraction.run;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.demand_extraction.algorithm.ExMasAlgorithm;
import org.matsim.contrib.demand_extraction.algorithm.bamas.BamasAlgorithm;
import org.matsim.contrib.demand_extraction.algorithm.engine.RidePostProcessor;
import org.matsim.contrib.demand_extraction.algorithm.exmas.ExMasReferenceAlgorithm;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.network.OfflineTravelTimes;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.BudgetToConstraintsCalculator;
import org.matsim.contrib.demand_extraction.io.lowmem.PhaseOneDumpReader;
import org.matsim.contrib.demand_extraction.scoring.DemandExtractionScoringAdapter;
import org.matsim.contrib.demand_extraction.scoring.Phase2EqasimAdapter;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ControllerConfigGroup;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutilityFactory;
import org.matsim.core.router.costcalculators.TravelDisutilityFactory;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scenario.ScenarioUtils;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.name.Names;

/**
 * Standalone Guice module for the Phase-2 JVM of the low-memory two-phase mode.
 *
 * <p>Uses {@link OnlyTimeDependentTravelDisutilityFactory} and loads offline travel times via
 * {@link OfflineTravelTimes} (same class as {@code LyonEqasimScenarioFixture}) — so Phase-2
 * per-ride distances/times match the single-process baseline byte-for-byte. The Task-11 hard gate
 * enforces this. Equality holds by construction (both call the same loader, and
 * {@link org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache} resolves
 * TravelTime/TravelDisutilityFactory itself) rather than by verbatim copy.
 *
 * <p>The module does <b>not</b> install MATSim's Controler, the eqasim DI graph, or
 * the discrete-mode-choice plumbing. Phase 2 scores DRT directly via
 * {@link Phase2EqasimAdapter} using the 6 frozen scalars Phase 1 dumped into the meta.
 */
public final class Phase2Module extends AbstractModule {

	private static final Logger log = LogManager.getLogger(Phase2Module.class);

	/** Container for everything Phase 2 needs to wire its tiny Guice graph. */
	public record Phase2Config(
			Path phase1ConfigXml,
			Path networkXml,
			Path travelTimesTsv,
			Path outputDir,
			PhaseOneDumpReader.Meta dumpMeta) {}

	private final Phase2Config p2;

	public Phase2Module(Phase2Config p2) {
		this.p2 = p2;
	}

	@Override
	protected void configure() {
		// 1. Load the Phase-1 config XML. It carries ExMasConfigGroup with all
		//    algorithm/pruning knobs, MultiModeDrtConfigGroup with the DRT fare
		//    parameters BudgetToConstraintsCalculator needs, and DvrpConfigGroup.
		Config config = ConfigUtils.loadConfig(p2.phase1ConfigXml.toString(),
				new ExMasConfigGroup(),
				new MultiModeDrtConfigGroup(),
				new DvrpConfigGroup());
		// Phase-2 owns the output directory and run id (matches the dump meta so
		// rides/connection-cache CSVs land at <runId>.exmas_rides.csv).
		try {
			Files.createDirectories(p2.outputDir);
		} catch (IOException e) {
			throw new RuntimeException("Failed to create Phase-2 output directory " + p2.outputDir, e);
		}
		config.controller().setOutputDirectory(p2.outputDir.toString());
		config.controller().setOverwriteFileSetting(OverwriteFileSetting.overwriteExistingFiles);
		config.controller().setRunId(p2.dumpMeta.runId());
		bind(Config.class).toInstance(config);
		bind(ExMasConfigGroup.class).toInstance(
				ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class));

		// 2. Load network into a Phase-2 scenario.
		Scenario scenario = ScenarioUtils.createScenario(config);
		log.info("Phase 2: loading network from {}", p2.networkXml);
		new MatsimNetworkReader(scenario.getNetwork()).readFile(p2.networkXml.toString());
		log.info("Phase 2: network has {} nodes / {} links",
				scenario.getNetwork().getNodes().size(),
				scenario.getNetwork().getLinks().size());
		bind(Scenario.class).toInstance(scenario);
		bind(Network.class).toInstance(scenario.getNetwork());

		// 3. Offline travel times.
		TravelTime travelTime = OfflineTravelTimes.load(p2.travelTimesTsv.toString());
		bind(TravelTime.class).annotatedWith(Names.named(TransportMode.car)).toInstance(travelTime);
		bind(TravelTime.class).toInstance(travelTime);

		// 4. Disutility: matches Lyon fixture. MatsimNetworkCache resolves its own
		//    SpeedyALTFactory internally — no LeastCostPathCalculatorFactory binding needed here.
		TravelDisutilityFactory tdf = new OnlyTimeDependentTravelDisutilityFactory();
		bind(TravelDisutilityFactory.class).annotatedWith(Names.named(TransportMode.car))
				.toInstance(tdf);

		// 5. Scoring adapter: frozen-scalar Phase-2 twin of EqasimScoringAdapter.
		PhaseOneDumpReader.EqasimScoringParams ep = p2.dumpMeta.eqasimScoringParams();
		if (ep == null) {
			throw new IllegalStateException(
					"Phase-1 dump at " + p2.phase1ConfigXml.getParent() + " has no "
							+ "eqasimScoringParams block — Phase 2 currently supports the "
							+ "eqasim adapter only. Re-run Phase 1 with the eqasim adapter "
							+ "(LyonEqasimScenarioFixture defaults).");
		}
		Phase2EqasimAdapter adapter = new Phase2EqasimAdapter(
				ep.drtAlpha_u(), ep.drtBetaTravelTime_u_min(), ep.drtBetaAccessEgressTime_u_min(),
				ep.betaCost_u_MU(), ep.lambdaCostEuclideanDistance(),
				ep.referenceEuclideanDistance_km());
		bind(DemandExtractionScoringAdapter.class).toInstance(adapter);

		// 6. Demand-extraction services that ExMasAlgorithmModule normally binds.
		//    (We can't install that module standalone — it extends MATSim's
		//    AbstractModule which needs the Controler's bootstrap injector.)
		//    BudgetValidator goes through @Provides so we pick the 3-arg Phase-2
		//    constructor (no ScoringParametersForPerson, which Phase 2 doesn't have).
		bind(BudgetToConstraintsCalculator.class).asEagerSingleton();
		bind(MatsimNetworkCache.class).asEagerSingleton();
		bind(BamasAlgorithm.class);
		bind(ExMasReferenceAlgorithm.class);
		// RidePostProcessor has no @Inject ctor in single-process (it's built by hand
		// inside DemandExtractionListener) — wire it via @Provides instead.

		// 7. Output directory hierarchy (RidePostProcessor and the rides writer use it
		//    only indirectly — Phase-2 runner writes outputs through ExMasCsvWriter
		//    against the run id resolved from the dump meta).
		OutputDirectoryHierarchy odh = new OutputDirectoryHierarchy(p2.outputDir.toString(),
				p2.dumpMeta.runId(),
				OverwriteFileSetting.overwriteExistingFiles,
				ControllerConfigGroup.CompressionType.none);
		bind(OutputDirectoryHierarchy.class).toInstance(odh);
	}

	/** ExMasAlgorithm strategy dispatch (same as {@code ExMasAlgorithmModule.provideExMasAlgorithm}). */
	@Provides
	@Singleton
	ExMasAlgorithm provideExMasAlgorithm(com.google.inject.Injector injector, ExMasConfigGroup cfg) {
		return switch (cfg.getAlgorithm()) {
			case BAMAS -> injector.getInstance(BamasAlgorithm.class);
			case EXMAS -> injector.getInstance(ExMasReferenceAlgorithm.class);
		};
	}

	/** Scalar MaxCostResolver — replaces the Person-based variant the single-process
	 *  flow uses. Calls {@link BudgetToConstraintsCalculator#budgetToMaxCost} with a
	 *  {@code null} Person; the {@link Phase2EqasimAdapter} ignores Person. */
	@Provides
	@Singleton
	RidePostProcessor.MaxCostResolver provideMaxCostResolver(BudgetToConstraintsCalculator btc) {
		return (budget, request, tt, dist) -> btc.budgetToMaxCost(budget, null, tt, dist, request);
	}

	@Provides
	@Singleton
	RidePostProcessor provideRidePostProcessor(ExMasConfigGroup exMas,
			MatsimNetworkCache networkCache, RidePostProcessor.MaxCostResolver resolver) {
		return new RidePostProcessor(exMas, networkCache, resolver);
	}

	/** Use the Phase-2 3-arg BudgetValidator constructor (no ScoringParametersForPerson).
	 *  Phase 2 reloads pre-computed scoring contexts from disk and never builds new ones,
	 *  so the per-person scoring-parameters dependency the @Inject ctor takes is unnecessary. */
	@Provides
	@Singleton
	BudgetValidator provideBudgetValidator(DemandExtractionScoringAdapter adapter,
			ExMasConfigGroup exMas, Config config) {
		return new BudgetValidator(adapter, exMas, ExMasConfigGroup.getWalkSpeed(config));
	}

}
