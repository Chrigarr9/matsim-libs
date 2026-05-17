package org.matsim.contrib.demand_extraction.run;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.common.timeprofile.TimeDiscretizer;
import org.matsim.contrib.demand_extraction.algorithm.DrtRouterProvider;
import org.matsim.contrib.demand_extraction.algorithm.ExMasAlgorithm;
import org.matsim.contrib.demand_extraction.algorithm.bamas.BamasAlgorithm;
import org.matsim.contrib.demand_extraction.algorithm.engine.RidePostProcessor;
import org.matsim.contrib.demand_extraction.algorithm.exmas.ExMasReferenceAlgorithm;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.BudgetToConstraintsCalculator;
import org.matsim.contrib.demand_extraction.io.lowmem.PhaseOneDumpReader;
import org.matsim.contrib.demand_extraction.scoring.DemandExtractionScoringAdapter;
import org.matsim.contrib.demand_extraction.scoring.Phase2EqasimAdapter;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.contrib.dvrp.trafficmonitoring.DvrpOfflineTravelTimes;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ControllerConfigGroup;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutilityFactory;
import org.matsim.core.router.costcalculators.TravelDisutilityFactory;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.LeastCostPathCalculatorFactory;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scenario.ScenarioUtils;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.name.Names;

/**
 * Standalone Guice module for the Phase-2 JVM of the low-memory two-phase mode.
 *
 * <p>Mirrors {@code LyonEqasimScenarioFixture}'s routing setup exactly — same
 * {@link OnlyTimeDependentTravelDisutilityFactory}, same offline travel times via
 * {@link DvrpOfflineTravelTimes} with {@link #TRAVEL_TIME_BIN_SIZE} / {@link #TRAVEL_TIME_END},
 * same {@link DrtRouterProvider} — so Phase-2 per-ride distances/times match the
 * single-process baseline byte-for-byte. The Task-11 hard gate enforces this.
 *
 * <p>The module does <b>not</b> install MATSim's Controler, the eqasim DI graph, or
 * the discrete-mode-choice plumbing. Phase 2 scores DRT directly via
 * {@link Phase2EqasimAdapter} using the 6 frozen scalars Phase 1 dumped into the meta.
 */
public final class Phase2Module extends AbstractModule {

	private static final Logger log = LogManager.getLogger(Phase2Module.class);

	/** 15-min bins, matches {@code LyonEqasimScenarioFixture.TRAVEL_TIME_BIN_SIZE}. */
	private static final int TRAVEL_TIME_BIN_SIZE = 900;
	/** 36 h, matches {@code LyonEqasimScenarioFixture.TRAVEL_TIME_END}. */
	private static final int TRAVEL_TIME_END = 36 * 3600;

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

		// 3. Offline travel times (must match Lyon fixture's loader byte-for-byte).
		TravelTime travelTime = loadOfflineTravelTimes(p2.travelTimesTsv.toString());
		bind(TravelTime.class).annotatedWith(Names.named(TransportMode.car)).toInstance(travelTime);
		bind(TravelTime.class).toInstance(travelTime);

		// 4. Disutility + routing factory: matches Lyon fixture.
		TravelDisutilityFactory tdf = new OnlyTimeDependentTravelDisutilityFactory();
		bind(TravelDisutilityFactory.class).annotatedWith(Names.named(TransportMode.car))
				.toInstance(tdf);
		bind(LeastCostPathCalculatorFactory.class).toInstance(
				new org.matsim.core.router.speedy.SpeedyALTFactory());

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
		bind(BudgetValidator.class).asEagerSingleton();
		bind(BudgetToConstraintsCalculator.class).asEagerSingleton();
		bind(MatsimNetworkCache.class).asEagerSingleton();
		bind(BamasAlgorithm.class);
		bind(ExMasReferenceAlgorithm.class);
		bind(RidePostProcessor.class).asEagerSingleton();

		// 7. DRT-specific router: shared with single-process to avoid drift.
		ExMasConfigGroup exMas = ConfigUtils.addOrGetModule(config, ExMasConfigGroup.class);
		String drtRouterName = "direct" + capitalize(exMas.getDrtMode()) + "Router";
		bind(LeastCostPathCalculator.class)
				.annotatedWith(Names.named(drtRouterName))
				.toProvider(DrtRouterProvider.class);

		// 8. Output directory hierarchy (RidePostProcessor and the rides writer use it
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

	/** Mirrors {@code LyonEqasimScenarioFixture.loadOfflineTravelTimes} verbatim — any drift
	 *  would break the Task-11 byte-equality gate. */
	private static TravelTime loadOfflineTravelTimes(String ttFile) {
		log.info("Phase 2: loading pre-computed travel times from {}", ttFile);
		TimeDiscretizer timeDiscretizer = new TimeDiscretizer(TRAVEL_TIME_END, TRAVEL_TIME_BIN_SIZE);
		try {
			URL ttUrl = Path.of(ttFile).toUri().toURL();
			double[][] matrix = DvrpOfflineTravelTimes.loadLinkTravelTimes(timeDiscretizer, ttUrl, "\t");
			TravelTime baseTt = DvrpOfflineTravelTimes.asTravelTime(timeDiscretizer, matrix);
			log.info("Phase 2: bound travel times ({} bins, clamped to {}h)",
					timeDiscretizer.getIntervalCount(), TRAVEL_TIME_END / 3600);
			return (link, time, person, vehicle) ->
					baseTt.getLinkTravelTime(link, Math.min(time, TRAVEL_TIME_END), person, vehicle);
		} catch (Exception e) {
			throw new RuntimeException("Failed to load offline travel times from " + ttFile, e);
		}
	}

	private static String capitalize(String s) {
		if (s == null || s.isEmpty()) return s;
		return s.substring(0, 1).toUpperCase() + s.substring(1);
	}
}
