package org.matsim.contrib.demand_extraction.demand;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.population.Population;
import org.matsim.contrib.demand_extraction.algorithm.ExMasAlgorithm;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.algorithm.validation.BudgetValidator;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.io.lowmem.PhaseOneDumpLayout;
import org.matsim.contrib.demand_extraction.io.lowmem.PhaseOneDumpWriter;
import org.matsim.contrib.demand_extraction.scoring.DemandExtractionScoringAdapter;
import org.matsim.contrib.demand_extraction.scoring.EqasimRuntimeProbe;
import org.matsim.core.config.Config;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.ShutdownEvent;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Singleton;

/**
 * Phase-1 variant of {@link DemandExtractionListener}. Runs steps 0–3 via the
 * inherited {@link #runPhase1()}, writes the {@link PhaseOneDumpWriter} payload,
 * then calls {@code System.exit(0)} so the Controler heap is released before the
 * Phase-2 JVM starts. Phase-2 work (steps 4–5) is never invoked.
 *
 * <p>Wired by {@code RunDemandExtractionPhase1} via a Guice override that binds
 * {@link DemandExtractionListener} to this subclass plus the {@link Config} record
 * carrying the dump location and the percent-sample tag for the dump meta.
 */
@Singleton
public class Phase1DumpListener extends DemandExtractionListener {
	private static final Logger log = LogManager.getLogger(Phase1DumpListener.class);

	/** Wiring payload for the Phase-1 listener: where to write the dump and what
	 *  to record as {@code sampleSize} in the dump meta. */
	public record Phase1Config(Path dumpRoot, int samplePct) {}

	private final Phase1Config phase1Config;
	private final DemandExtractionScoringAdapter scoringAdapter;
	private final Injector injector;

	@Inject
	public Phase1DumpListener(
			ModeRoutingCache modeRoutingCache,
			ChainIdentifier chainIdentifier,
			DrtRequestFactory requestFactory,
			Population population,
			ExMasConfigGroup exMasConfig,
			Config config,
			MatsimNetworkCache networkCache,
			BudgetValidator budgetValidator,
			BudgetToConstraintsCalculator budgetToConstraintsCalculator,
			OutputDirectoryHierarchy outputDirectory,
			RequestSampler requestSampler,
			ExMasAlgorithm algorithm,
			Phase1Config phase1Config,
			DemandExtractionScoringAdapter scoringAdapter,
			Injector injector) {
		super(modeRoutingCache, chainIdentifier, requestFactory, population, exMasConfig,
				config, networkCache, budgetValidator, budgetToConstraintsCalculator,
				outputDirectory, requestSampler, algorithm);
		this.phase1Config = phase1Config;
		this.scoringAdapter = scoringAdapter;
		this.injector = injector;
	}

	@Override
	public void notifyShutdown(ShutdownEvent event) {
		log.info("======================================================================");
		log.info("STARTING PHASE 1 (dump-only mode) — dump dir: {}", phase1Config.dumpRoot());
		log.info("======================================================================");

		List<DrtRequest> requests = runPhase1();

		Path dumpRoot = phase1Config.dumpRoot();
		try {
			Files.createDirectories(dumpRoot);
		} catch (IOException e) {
			throw new RuntimeException("Failed to create Phase-1 dump directory " + dumpRoot, e);
		}
		PhaseOneDumpLayout layout = new PhaseOneDumpLayout(dumpRoot);

		long phase1WallMs = phase1ElapsedMillis();
		long peakHeapBytes = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();

		PhaseOneDumpWriter.EqasimScoringParams eqasimParams = harvestEqasimParams();

		PhaseOneDumpWriter.Meta meta = new PhaseOneDumpWriter.Meta(
				exMasConfig.getDrtMode(),
				ExMasConfigGroup.getWalkSpeed(config),
				exMasConfig.getOpportunityCostModel().name(),
				exMasConfig.getMinDrtAccessEgressDistance(),
				config.controller().getRunId(),
				phase1Config.samplePct(),
				phase1WallMs,
				peakHeapBytes,
				eqasimParams);

		try {
			PhaseOneDumpWriter.write(layout, requests, meta);
		} catch (IOException e) {
			throw new RuntimeException("Failed to write Phase-1 dump to " + dumpRoot, e);
		}

		// Snapshot the live config so Phase 2 can rebuild ExMasConfigGroup +
		// MultiModeDrtConfigGroup without having to be handed the original cut XML.
		Path configSnapshot = layout.configXml();
		try {
			new org.matsim.core.config.ConfigWriter(config).write(configSnapshot.toString());
		} catch (Exception e) {
			throw new RuntimeException("Failed to write Phase-1 config snapshot to " + configSnapshot, e);
		}

		log.info("");
		log.info("======================================================================");
		log.info("PHASE 1 DUMP COMPLETE");
		log.info("  Requests:        {}", requests.size());
		log.info("  Dump dir:        {}", dumpRoot.toAbsolutePath());
		log.info("  Phase-1 wall:    {}s", String.format("%.1f", phase1WallMs / 1000.0));
		log.info("  Peak heap (used):{} MB", peakHeapBytes / (1024L * 1024L));
		log.info("======================================================================");
		log.info("Exiting JVM (System.exit(0)) so Phase 2 starts with a clean heap.");
		System.exit(0);
	}

	/**
	 * Harvests the 6 scalars Phase 2 needs to score DRT and convert budgets to fares
	 * without standing up the eqasim DI graph. Returns {@code null} when the resolved
	 * adapter is not eqasim — in that case Phase 2 can still read the dump but cannot
	 * score (the Phase-2 runner refuses to start with a {@code null} eqasim block).
	 */
	private PhaseOneDumpWriter.EqasimScoringParams harvestEqasimParams() {
		String adapterName = scoringAdapter.getName();
		if (!"eqasim".equals(adapterName)) {
			log.warn("Phase-1 adapter is '{}', not 'eqasim' — eqasim scoring params will be "
					+ "omitted from the dump. The two-phase mode currently supports the "
					+ "eqasim adapter only; Phase 2 will refuse this dump.", adapterName);
			return null;
		}
		EqasimRuntimeProbe.EqasimDrtParameters drt = EqasimRuntimeProbe.readDrtParameters(injector);
		EqasimRuntimeProbe.EqasimCostParameters cost = EqasimRuntimeProbe.readCostParameters(injector);
		return new PhaseOneDumpWriter.EqasimScoringParams(
				drt.alpha_u(), drt.betaTravelTime_u_min(), drt.betaAccessEgressTime_u_min(),
				cost.betaCost_u_MU(), cost.lambdaCostEuclideanDistance(),
				cost.referenceEuclideanDistance_km());
	}
}
