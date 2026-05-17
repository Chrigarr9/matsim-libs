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
import org.matsim.core.config.Config;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.ShutdownEvent;

import com.google.inject.Inject;
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
			Phase1Config phase1Config) {
		super(modeRoutingCache, chainIdentifier, requestFactory, population, exMasConfig,
				config, networkCache, budgetValidator, budgetToConstraintsCalculator,
				outputDirectory, requestSampler, algorithm);
		this.phase1Config = phase1Config;
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

		PhaseOneDumpWriter.Meta meta = new PhaseOneDumpWriter.Meta(
				exMasConfig.getDrtMode(),
				ExMasConfigGroup.getWalkSpeed(config),
				exMasConfig.getOpportunityCostModel().name(),
				exMasConfig.getMinDrtAccessEgressDistance(),
				config.controller().getRunId(),
				phase1Config.samplePct(),
				phase1WallMs,
				peakHeapBytes);

		try {
			PhaseOneDumpWriter.write(layout, requests, meta);
		} catch (IOException e) {
			throw new RuntimeException("Failed to write Phase-1 dump to " + dumpRoot, e);
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
}
