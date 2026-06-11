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
import org.matsim.contrib.demand_extraction.algorithm.bamas.stub.MaterializedRideStore;
import org.matsim.contrib.demand_extraction.algorithm.domain.Ride;
import org.matsim.contrib.demand_extraction.algorithm.engine.RidePostProcessor;
import org.matsim.contrib.demand_extraction.algorithm.network.MatsimNetworkCache;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;
import org.matsim.contrib.demand_extraction.io.ConnectionCacheWriter;
import org.matsim.contrib.demand_extraction.io.ExMasCsvWriter;
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

	/** Publish the Phase-1 request dump under the canonical
	 *  {@code <demandDir>/<runId>.drt_requests.csv} name expected by downstream
	 *  Python tooling. Uses a hard link when the FS supports it (NTFS does
	 *  within a single volume), falling back to a byte copy. Idempotent —
	 *  overwrites an existing canonical file. */
	public static java.nio.file.Path publishCanonicalRequestsCsv(
			java.nio.file.Path phase1Dir, java.nio.file.Path demandDir, String runId) throws IOException {
		java.nio.file.Path src = phase1Dir.resolve(
				org.matsim.contrib.demand_extraction.io.lowmem.PhaseOneDumpLayout.REQUESTS_CSV);
		java.nio.file.Path dst = demandDir.resolve(runId + ".drt_requests.csv");
		if (!java.nio.file.Files.exists(src)) {
			throw new IOException("Phase-1 requests CSV missing at " + src
					+ "; Phase 1 did not complete normally");
		}
		java.nio.file.Files.deleteIfExists(dst);
		try {
			java.nio.file.Files.createLink(dst, src);
		} catch (UnsupportedOperationException | java.nio.file.FileSystemException ex) {
			java.nio.file.Files.copy(src, dst);
		}
		return dst;
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

		log.info("PHASE 2 STEP 3: running {} algorithm", exMasCfg.getAlgorithm());
		ExMasAlgorithm algorithm = injector.getInstance(ExMasAlgorithm.class);
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
		Path demandDir = a.outputDir.resolve("drt_demand");
		Files.createDirectories(demandDir);
		String runId = dump.meta().runId();
		Path ridesCsv = demandDir.resolve(runId + ".exmas_rides.csv");
		ExMasCsvWriter.writeRides(ridesCsv.toString(), new MaterializedRideStore(rides));
		log.info("PHASE 2 STEP 5: wrote {} rides to {}", rides.size(), ridesCsv);

		java.nio.file.Path publishedRequests = publishCanonicalRequestsCsv(
				a.phase1Dir, demandDir, runId);
		log.info("PHASE 2 STEP 5: published canonical requests CSV to {}", publishedRequests);

		if (exMasCfg.isCalcPredecessors()) {
			Path connectionCacheCsv = demandDir.resolve(runId + ".connection_cache.csv");
			MatsimNetworkCache networkCache = injector.getInstance(MatsimNetworkCache.class);
			try {
				ConnectionCacheWriter.writeConnectionCache(connectionCacheCsv.toString(), rides,
						networkCache, exMasCfg.getNetworkTimeBinSize(), exMasCfg.getConnectionCacheExportMode());
				log.info("PHASE 2 STEP 5: wrote connection cache to {}", connectionCacheCsv);
			} catch (IOException e) {
				log.error("Failed to write connection cache", e);
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
