package org.matsim.contrib.demand_extraction.run;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.contrib.demand_extraction.algorithm.AlgorithmResult;
import org.matsim.contrib.demand_extraction.algorithm.ExMasAlgorithm;
import org.matsim.contrib.demand_extraction.algorithm.bamas.BamasAlgorithm;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.engine.RidePostProcessor;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;
import org.matsim.contrib.demand_extraction.io.ExtractionDataManager;
import org.matsim.contrib.demand_extraction.io.lowmem.PhaseOneDumpLayout;
import org.matsim.contrib.demand_extraction.io.lowmem.PhaseOneDumpReader;

import com.google.inject.Guice;
import com.google.inject.Injector;

/**
 * Phase-2 runner for the {@code --low-memory} two-phase mode. Starts a fresh JVM,
 * reads the Phase-1 dump, builds the minimal {@link Phase2Module} Guice graph, runs
 * the ExMAS algorithm + post-processor, and writes
 * {@code <outputDir>/drt_demand/<runId>.exmas_rides.csv} (and
 * {@code .connection_cache.csv} when predecessors are enabled).
 *
 * <p>The Controler heap from Phase 1 is gone — only the per-request
 * {@link DrtRequest.ScoringContext}s reconstructed by {@link PhaseOneDumpReader}
 * and the network/travel-times wired by {@link Phase2Module} are resident.
 *
 * <pre>
 * cd matsim-libs/contribs/drt-demand-extraction
 * mvn exec:java -Dexec.mainClass="org.matsim.contrib.demand_extraction.run.RunDemandExtractionPhase2" \
 *   -Dexec.args="--phase1-dir ../../../outputs/lyon-phase1-smoke-1pct/phase1_dump \
 *                --network ../../../matsim_scenarios/eqasim-france/output_lyon_drt_1pct/lyon_drt_area/lyon_drt_1pct_network.xml.gz \
 *                --travel-times ../../../matsim_scenarios/eqasim-france/output_fullregion_1pct/travel_times.tsv \
 *                --output-dir ../../../outputs/lyon-phase2-smoke-1pct" \
 *   -Denforcer.skip=true
 * </pre>
 */
public final class RunDemandExtractionPhase2 {

	private static final Logger log = LogManager.getLogger(RunDemandExtractionPhase2.class);

	/** Captures the four required Phase-2 paths. */
	public record Phase2Args(Path phase1Dir, Path networkXml, Path travelTimesTsv, Path outputDir) {}

	private RunDemandExtractionPhase2() {}

	static Phase2Args parseArgs(String[] args) {
		String phase1Dir = null;
		String networkXml = null;
		String travelTimesTsv = null;
		String outputDir = null;
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--phase1-dir" -> phase1Dir = args[++i];
				case "--network" -> networkXml = args[++i];
				case "--travel-times" -> travelTimesTsv = args[++i];
				case "--output-dir" -> outputDir = args[++i];
				case "--extension-parents-top-k", "--extension-parents-top-k-min-degree",
				     "--extension-parents-top-k-metric", "--extension-parents-selection-rule",
				     "--extension-parents-mmr-lambda", "--checkpoint-dir",
				     "--algorithm-process-count", "--heuristics-process-count" -> i++; // applied via applyPhase2KnobOverrides
				case "--checkpoint-fork-below-min-degree" -> { } // valueless boolean flag — applied via applyPhase2KnobOverrides
				default -> log.warn("Unknown argument: {}", args[i]);
			}
		}
		if (phase1Dir == null || networkXml == null || travelTimesTsv == null || outputDir == null) {
			throw new IllegalArgumentException(
					"Usage: --phase1-dir <path> --network <path.xml.gz> --travel-times <path.tsv> --output-dir <path>");
		}
		return new Phase2Args(Path.of(phase1Dir), Path.of(networkXml),
				Path.of(travelTimesTsv), Path.of(outputDir));
	}

	/**
	 * Rejects a v1 dump when stop-based or HyperPool generation is enabled.
	 * A v1 dump has {@code maxWalkDistance=0} for every request; running
	 * stop-based or HyperPool off it silently produces wrong rides because the
	 * per-pax walk-distance caps would default to zero.
	 *
	 * @throws IllegalStateException when {@code scoringContextsVersion < 2} and
	 *                               either stop-based or HyperPool is enabled.
	 */
	static void assertDumpSupportsConfig(int scoringContextsVersion, ExMasConfigGroup cfg) {
		if (scoringContextsVersion < 2 && (cfg.isEnableStopBased() || cfg.isEnableHyperPooling())) {
			throw new IllegalStateException(
					"v1 dump is not compatible with stop-based/hyperpool: the scoring_contexts.bin "
					+ "v1 format does not persist per-request maxWalkDistance caps, so enabling "
					+ "stop-based or HyperPool generation would silently produce wrong rides. "
					+ "Re-run Phase 1 with a v2 build (current build) to obtain a v2 dump, "
					+ "then re-run Phase 2.");
		}
	}

	/** Optional Phase-2 overrides for the extension_parents_top_k knob, so a smoke
	 *  run can exercise the knob against a cached (K=0) dump without re-running Phase 1. */
	static void applyPhase2KnobOverrides(String[] args, ExMasConfigGroup cfg) {
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--extension-parents-top-k" -> cfg.setExtensionParentsTopK(Integer.parseInt(args[++i]));
				case "--extension-parents-top-k-min-degree" -> cfg.setExtensionParentsTopKMinDegree(Integer.parseInt(args[++i]));
				case "--extension-parents-top-k-metric" -> cfg.setExtensionParentsTopKMetric(ExMasConfigGroup.PruningQualityMetric.valueOf(args[++i].toUpperCase()));
				case "--extension-parents-selection-rule" -> cfg.setExtensionParentsSelectionRule(ExMasConfigGroup.ExtensionParentsSelectionRule.valueOf(args[++i].toUpperCase()));
				case "--extension-parents-mmr-lambda" -> cfg.setExtensionParentsMmrLambda(Double.parseDouble(args[++i]));
				// Plan A3: per-degree checkpoint/resume. Set ⇒ the engine writes stubs +
				// pair universe + connection-cache journal at each barrier, and resumes from
				// the dir if a matching manifest is already present. Off ("") reproduces master.
				case "--checkpoint-dir" -> cfg.setCheckpointDir(args[++i]);
				// Thread-count overrides. Both = 1 makes the whole pipeline (algorithm +
				// post-processing) bit-reproducible: the shared connection cache fills in a
				// fixed order, so routes and post-processing aggregates are deterministic.
				// Used by the A3 kill-resume gate to assert true SHA-256 byte-identity.
				case "--algorithm-process-count" -> cfg.setAlgorithmProcessCount(Integer.parseInt(args[++i]));
				case "--heuristics-process-count" -> cfg.setHeuristicsProcessCount(Integer.parseInt(args[++i]));
				// Valueless boolean flag: opts a resume into accepting a pre-minDegree checkpoint
				// under changed parent-pruning knobs. Must be re-asserted here because Phase 2
				// rebuilds ExMasConfigGroup from phase1_config.xml (which never serialises this
				// flag), so CLI is the only way to enable it in a fresh Phase-2 JVM.
				case "--checkpoint-fork-below-min-degree" -> cfg.setCheckpointForkBelowMinDegree(true);
				default -> { }
			}
		}
	}

	public static void main(String[] args) throws IOException {
		LoggingSetup.configure();
		long overallStartMs = System.currentTimeMillis();
		Phase2Args a;
		try {
			a = parseArgs(args);
		} catch (IllegalArgumentException e) {
			System.err.println(e.getMessage());
			System.exit(1);
			return;
		}

		log.info("======================================================================");
		log.info("STARTING PHASE 2 (algorithm + post-process from dump)");
		log.info("  Phase-1 dump:  {}", a.phase1Dir.toAbsolutePath());
		log.info("  Network:       {}", a.networkXml.toAbsolutePath());
		log.info("  Travel times:  {}", a.travelTimesTsv.toAbsolutePath());
		log.info("  Output:        {}", a.outputDir.toAbsolutePath());
		log.info("======================================================================");

		PhaseOneDumpLayout layout = new PhaseOneDumpLayout(a.phase1Dir);
		Path configXml = layout.configXml();
		if (!Files.exists(configXml)) {
			throw new IllegalStateException("Phase-1 config snapshot missing: " + configXml
					+ ". Re-run Phase 1 with a build that includes the phase1_config.xml writer.");
		}
		log.info("PHASE 2 STEP 1: reading Phase-1 dump");
		PhaseOneDumpReader.DumpData dump = PhaseOneDumpReader.read(layout);
		List<DrtRequest> requests = dump.requests();
		log.info("PHASE 2 STEP 1: loaded {} requests (runId={}, samplePct={}, scoringContextsVersion={})",
				requests.size(), dump.meta().runId(), dump.meta().sampleSize(), dump.scoringContextsVersion());

		log.info("PHASE 2 STEP 2: building Phase2Module graph");
		Phase2Module.Phase2Config p2 = new Phase2Module.Phase2Config(
				configXml, a.networkXml, a.travelTimesTsv, a.outputDir, dump.meta());
		Injector injector = Guice.createInjector(new Phase2Module(p2));

		ExMasConfigGroup exMasCfg = injector.getInstance(ExMasConfigGroup.class);
		assertDumpSupportsConfig(dump.scoringContextsVersion(), exMasCfg);
		applyPhase2KnobOverrides(args, exMasCfg);

		log.info("PHASE 2 STEP 3: running {} algorithm", exMasCfg.getAlgorithm());
		ExMasAlgorithm algorithm = injector.getInstance(ExMasAlgorithm.class);
		// C1 — wire the real routing-input file paths into the BAMAS checkpoint fingerprint. Without
		// this the fingerprint is config-only, so a resume against a DIFFERENT population/travel-times/
		// network would falsely match and silently corrupt output by mixing incompatible routing inputs.
		if (algorithm instanceof BamasAlgorithm bamasAlgorithm) {
			bamasAlgorithm.setFingerprintInputs(
					layout.requestsCsv(),   // Phase-1 requests dump (drt_requests_phase1.csv)
					a.travelTimesTsv(),     // --travel-times arg
					a.networkXml());        // --network arg
		}
		// Take a heap snapshot RIGHT before the algorithm starts. Together with
		// Phase 1's "Used heap at dump" line this gives the operator the
		// memory-released-by-JVM-split metric:
		//   gain = phase1_used_at_dump - phase2_used_at_algorithm_start
		System.gc();   // Make the snapshot reflect live state, not garbage from setup.
		long heapAtAlgStartBytes = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
		log.info("PHASE 2 HEAP at algorithm start: {} MB (used)",
				heapAtAlgStartBytes / (1024L * 1024L));
		AlgorithmResult result = algorithm.run(requests);
		log.info("PHASE 2 STEP 3: algorithm produced {} rides", result.rides().size());

		log.info("PHASE 2 STEP 4: post-processing (maxCost + Shapley + predecessors)");
		RidePostProcessor postProcessor = injector.getInstance(RidePostProcessor.class);
		// result.rides() is a RideStore (streaming or materialized); process through it.
		List<Ride> rides = postProcessor.process(result.rides());

		log.info("PHASE 2 STEP 5: writing outputs");
		String runId = dump.meta().runId();
		ExtractionDataManager dataManager = ExtractionDataManager.forOutputDir(a.outputDir, runId, exMasCfg);

		Path ridesCsv = dataManager.writeRides(rides);
		log.info("PHASE 2 STEP 5: wrote {} rides to {}", rides.size(), ridesCsv);

		Path publishedRequests = dataManager.publishCanonicalRequests(a.phase1Dir);
		log.info("PHASE 2 STEP 5: published canonical requests CSV to {}", publishedRequests);

		if (exMasCfg.isCalcPredecessors()) {
			MatsimNetworkCache networkCache = injector.getInstance(MatsimNetworkCache.class);
			Path connectionCacheCsv = dataManager.writeConnectionCache(
					rides, networkCache, postProcessor.getWindowKeys());
			if (connectionCacheCsv != null) {
				log.info("PHASE 2 STEP 5: wrote connection cache to {}", connectionCacheCsv);
			}
			networkCache.logRoutingStatistics();
		}

		long overallElapsedMs = System.currentTimeMillis() - overallStartMs;
		long peakHeapBytes = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
		log.info("");
		log.info("======================================================================");
		log.info("PHASE 2 COMPLETE");
		log.info("  Requests:        {}", requests.size());
		log.info("  Rides:           {}", rides.size());
		log.info("  Phase-2 wall:    {}s", String.format("%.1f", overallElapsedMs / 1000.0));
		log.info("  Phase-2 peak:    {} MB", peakHeapBytes / (1024L * 1024L));
		log.info("======================================================================");
	}
}
